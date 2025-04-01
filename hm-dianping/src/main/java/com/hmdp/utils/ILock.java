package com.hmdp.utils;

/**
 * Created with IntelliJ IDEA.
 *
 * @Author: zhangyu
 * @Date: 2025/04/01/22:38
 * @Description:
 */
public interface ILock {
    boolean tryLock(long timeoutSec);
    void unlock();
}
