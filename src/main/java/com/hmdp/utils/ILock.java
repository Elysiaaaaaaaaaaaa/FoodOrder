package com.hmdp.utils;

public interface ILock {
    /**
     * 尝试获取锁
     * @param timeoutSec
     * @return true就是获取成功，false获取失败
     */
    boolean tryLock(long timeoutSec);
    void unLock();
}
