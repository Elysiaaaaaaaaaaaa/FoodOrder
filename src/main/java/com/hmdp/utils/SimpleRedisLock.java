package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
//    要获取锁首先要获取RedisTemplate的对象
    private StringRedisTemplate stringRedisTemplate;
//    锁名字
    private String name;
    private static final String KEY_PREFIX="lock:";
    private String ID_PREFIX= UUID.randomUUID().toString(true)+"-";
//    锁的键
    private final String key = KEY_PREFIX+name;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
//        默认路径是resource目录下
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
//        锁的名字配前缀组成键，值是线程号
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, threadId, timeoutSec, TimeUnit.SECONDS);
//        由于Boolean是包装类，可能会有拆箱操作，那么就可能会有空指针异常，Boolean.TRUE是一个静态常量，表示布尔值为true的Boolean对象，它与success比较，看他们引用的对象是否相同
        return Boolean.TRUE.equals(success);

    }
    @Override
    public void unLock() {
//    调用Lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX+name),ID_PREFIX+Thread.currentThread().getId());
    }
//    @Override
//    public void unLock() {
////        获取线程id
//        String threadId = ID_PREFIX+Thread.currentThread().getId();
////        获取锁中线程的id
//        String id = stringRedisTemplate.opsForValue().get(threadId);
//        if (!threadId.equals(id)){
//            stringRedisTemplate.delete(key);
//        }
//    }
}
