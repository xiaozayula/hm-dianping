package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    /**
     * 加载 判断秒杀券库存是否充足 并且 判断用户是否已下单 的Lua脚本
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    /**
     * 存储订单的阻塞队列
     */
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    /**
     * 线程池
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 当前类初始化完毕就立马执行该方法
     */
    @PostConstruct
    private void init() {
        // 执行线程任务
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 线程任务: 不断从阻塞队列中获取订单
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                // 从阻塞队列中获取订单信息，并创建订单
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }
    /**
     * 创建订单
     *
     * @param voucherOrder
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:"+ userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 索取锁失败，重试或者直接抛异常（这个业务是一人一单，所以直接返回失败信息）
            log.error("一人只能下一单");
            return;
        }
        try {
            // 创建订单（使用代理对象调用，是为了确保事务生效）
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }


    /**
     * 抢购秒杀券
     *
     * @param voucherId
     * @return
     */
    @Transactional
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId=UserHolder.getUser().getId();
        // 1、执行Lua脚本，判断用户是否具有秒杀资格
        Long result = null;
        try {
            result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    userId.toString()
            );
        } catch (Exception e) {
            log.error("Lua脚本执行失败");
            throw new RuntimeException(e);
        }
        if (result != null && !result.equals(0L)) {
            // result为1表示库存不足，result为2表示用户已下单
            int r = result.intValue();
            return Result.fail(r == 2 ? "不能重复下单" : "库存不足");
        }
        // 2、result为0，用户具有秒杀资格，将订单保存到阻塞队列中，实现异步下单
        long orderId = redisIdWorker.nextId("order");
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 将订单保存到阻塞队列中
        orderTasks.add(voucherOrder);
        // 索取锁成功，创建代理对象，使用代理对象调用第三方事务方法， 防止事务失效
        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        this.proxy = proxy;
        return Result.ok();
    }
    /**
     * VoucherOrderServiceImpl类的代理对象
     * 将代理对象的作用域进行提升，方面子线程取用
     */
    private IVoucherOrderService proxy;

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1、查询秒杀券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2、判断秒杀券是否合法
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 秒杀券的开始时间在当前时间之后
//            return Result.fail("秒杀尚未开始");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            // 秒杀券的结束时间在当前时间之前
//            return Result.fail("秒杀已结束");
//        }
//        if (voucher.getStock() < 1) {
//            return Result.fail("秒杀券已抢空");
//        }
//
//        // 3、创建订单（使用分布式锁）
//        Long userId=UserHolder.getUser().getId();
//
////        SimpleRedisLock lock = new SimpleRedisLock( stringRedisTemplate,"order:" + userId);
//        // 3、创建订单（使用分布式锁）
//       RLock lock = redissonClient.getLock("lock:order:" + userId);
//
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            // 索取锁失败，重试或者直接抛异常（这个业务是一人一单，所以直接返回失败信息）
//            return Result.fail("一人只能下一单");
//        }
//        try {
//            // 索取锁成功，创建代理对象，使用代理对象调用第三方事务方法， 防止事务失效
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        Long userId=voucherOrder.getUserId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
        if(count>0){
            log.error("用户已经购买过了");
            return ;
        }
        // 5、秒杀券合法，则秒杀券抢购成功，秒杀券库存数量减一
        boolean flag = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder)
                .gt("stock", 0)
                .update();
        if (!flag){
            log.error("库存不足");
            return ;
        }

        // 6、秒杀成功，创建对应的订单，并保存到数据库
       save(voucherOrder);


    }

}
