package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;


@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 缓存重建线程池
     */
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据id查询商铺数据
     *
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1、从Redis中查询店铺数据，并判断缓存是否命中
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)) {
            // 1.1 缓存未命中，直接返回失败信息
            return Result.fail("店铺数据不存在");
        }
        // 1.2 缓存命中，将JSON字符串反序列化未对象，并判断缓存数据是否逻辑过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        // 这里需要先转成JSONObject再转成反序列化，否则可能无法正确映射Shop的字段
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 当前缓存数据未过期，直接返回
            return Result.ok(shop);
        }

        // 2、缓存数据已过期，获取互斥锁，并且重建缓存
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            // 获取锁成功，开启一个子线程去重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShopToCache(id, CACHE_SHOP_LOGICAL_TTL);
                } finally {
                    unlock(lockKey);
                }
            });
        }

        // 3、获取锁失败，再次查询缓存，判断缓存是否重建（这里双检是有必要的）
        shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)) {
            // 3.1 缓存未命中，直接返回失败信息
            return Result.fail("店铺数据不存在");
        }
        // 3.2 缓存命中，将JSON字符串反序列化未对象，并判断缓存数据是否逻辑过期
        redisData = JSONUtil.toBean(shopJson, RedisData.class);
        // 这里需要先转成JSONObject再转成反序列化，否则可能无法正确映射Shop的字段
        data = (JSONObject) redisData.getData();
        shop = JSONUtil.toBean(data, Shop.class);
        expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 当前缓存数据未过期，直接返回
            return Result.ok(shop);
        }

        // 4、返回过期数据
        return Result.ok(shop);


        //互斥锁解决缓存击穿
//        String key = CACHE_SHOP_KEY + id;
//        // 1、从Redis中查询店铺数据，并判断缓存是否命中
//        Result result = getShopFromCache(key);
//        if (Objects.nonNull(result)) {
//            // 缓存命中，直接返回
//            return result;
//        }
//        try {
//            // 2、缓存未命中，需要重建缓存，判断能否能够获取互斥锁
//            String lockKey = LOCK_SHOP_KEY + id;
//            boolean isLock = tryLock(lockKey);
//            if (!isLock) {
//                // 2.1 获取锁失败，已有线程在重建缓存，则休眠重试
//                Thread.sleep(50);
//                return queryById(id);
//            }
//            // 2.2 获取锁成功，判断缓存是否重建，防止堆积的线程全部请求数据库（所以说双检是很有必要的）
//            result = getShopFromCache(key);
//            if (Objects.nonNull(result)) {
//                // 缓存命中，直接返回
//                return result;
//            }
//
//            // 3、从数据库中查询店铺数据，并判断数据库是否存在店铺数据
//            Shop shop = this.getById(id);
//            if (Objects.isNull(shop)) {
//                // 数据库中不存在，缓存空对象（解决缓存穿透），返回失败信息
//                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.SECONDS);
//                return Result.fail("店铺不存在");
//            }
//
//            // 4、数据库中存在，重建缓存，响应数据
//            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),
//                    CACHE_SHOP_TTL, TimeUnit.MINUTES);
//            return Result.ok(shop);
//        }catch (Exception e){
//            throw new RuntimeException("发生异常");
//        } finally {
//            // 5、释放锁（释放锁一定要记得放在finally中，防止死锁）
//            unlock(key);
//        }

        //缓存穿透
//        String key = CACHE_SHOP_KEY + id;
//        // 1、从Redis中查询店铺数据
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        Shop shop = null;
//        // 2、判断缓存是否命中
//        if (StrUtil.isNotBlank(shopJson)) {
//            // 2.1 缓存命中，直接返回店铺数据
//            shop = JSONUtil.toBean(shopJson, Shop.class);
//            return Result.ok(shop);
//        }
//
//        // 2.2 缓存未命中，判断缓存中查询的数据是否是空字符串(isNotBlank把null和空字符串给排除了)
//        if (Objects.nonNull(shopJson)){
//            // 2.2.1 当前数据是空字符串（说明该数据是之前缓存的空对象），直接返回失败信息
//            return Result.fail("店铺不存在");
//        }
//        // 2.2.2 当前数据是null，则从数据库中查询店铺数据
//        shop = this.getById(id);
//
//        // 4、判断数据库是否存在店铺数据
//        if (Objects.isNull(shop)) {
//            // 4.1 数据库中不存在，缓存空对象（解决缓存穿透），返回失败信息
//            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.SECONDS);
//            return Result.fail("店铺不存在");
//        }
//        // 4.2 数据库中存在，重建缓存，并返回店铺数据
//        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return Result.ok(shop);
    }
    /**
     * 将数据保存到缓存中
     *
     * @param id            商铺id
     * @param expireSeconds 逻辑过期时间
     */
    public void saveShopToCache(Long id, Long expireSeconds) {
        // 从数据库中查询店铺数据
        Shop shop = this.getById(id);
        // 封装逻辑过期数据
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 将逻辑过期数据存入Redis中
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 从缓存中获取店铺数据
     * @param key
     * @return
     */
    private Result getShopFromCache(String key) {
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 缓存数据有值，说明缓存命中了，直接返回店铺数据
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        // 判断缓存中查询的数据是否是空字符串(isNotBlank把 null 和 空字符串 给排除了)
        if (Objects.nonNull(shopJson)) {
            // 当前数据是空字符串，说明缓存也命中了（该数据是之前缓存的空对象），直接返回失败信息
            return Result.fail("店铺不存在");
        }
        // 缓存未命中（缓存数据既没有值，又不是空字符串）
        return null;
    }


    /**
     * 获取锁
     *
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 拆箱要判空，防止NPE
        return BooleanUtil.isTrue(flag);
    }
    /**
     * 释放锁
     *
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 更新商铺数据（更新时，更新数据库，删除缓存）
     *
     * @param shop
     * @return
     */
    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id=shop.getId();
        if(id==null){
            return  Result.fail("店铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }


}
