package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * Created with IntelliJ IDEA.
 *
 * @Author: zhangyu
 * @Date: 2025/04/01/22:40
 * @Description:
 */
public class SimpleRedisLock implements  ILock{

    /**
     * RedisTemplate
     */
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 锁的名称
     */
    private String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    /**
     * 获取锁
     *
     * @param timeoutSec 超时时间
     * @return
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        String id = Thread.currentThread().getId() + "";
        // SET lock:name id EX timeoutSec NX
        Boolean result = stringRedisTemplate.opsForValue()
                .setIfAbsent("lock:" + name, id, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result);
    }

    /**
     * 释放锁
     */
    @Override
    public void unlock() {
        stringRedisTemplate.delete("lock:" + name);
    }
}
