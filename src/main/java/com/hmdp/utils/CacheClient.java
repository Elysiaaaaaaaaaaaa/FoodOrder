package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
public class CacheClient {
    @Resource
    private final StringRedisTemplate stringRedisTemplate;
//    @Resource
//    private RBloomFilter<String> bloomFilter;
//    @Resource
//    private RedisService redisService;
    //配置线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
//    使用逻辑过期设置
    public void setWithLogicExpire(String key,Object value,Long time,TimeUnit unit){
//        设置逻辑过期
        RedisData redisData=new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
//        写入Redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }
//    用来检测缓存穿透的方法
//    使用缓存空值解决缓存穿透
    public <T,ID> T queryWithPassThrough(String keyPrefix, ID id, Class<T> type, Function<ID,T> dbFallBack,Long time,TimeUnit unit){
        String key = keyPrefix + id;
//        1、从Redis中查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
//        2.判断是否存在
        if (StrUtil.isNotBlank(Json)) {
//        3.存在,直接返回,反序列化，把JSON数据转化为对象
            return JSONUtil.toBean(Json, type);
        }
//        isNotBlank判断为空时也是false，这样的话就会向下运行了，会查询数据库了，所以要在这里添加一个条件,只要它是null就让它执行下面的查询数据库
        if (Json!=null){
            return null;
        }
//        4.不存在,根据id查询数据库
        T t = dbFallBack.apply(id);
        if (t==null){
//        5.不存在,返回false
//            使用缓存null值解决缓存穿透
            stringRedisTemplate.opsForValue().set(key, "",CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return null;
        }
//        6.存在,写入Redis
        this.set(key,t,time,unit);
//        返回店铺信息
        return t;
    }
//    使用布隆过滤器解决缓存穿透
//public void queryByBloom(){
//    Set<String> RedisData = redisService.getDataFromRedis();
//    for (String redisData : RedisData) {
//        bloomFilter.add(redisData);
//    }
//
//}
//




//    使用逻辑过期解决缓存击穿
    public <T, ID> T queryWithLogicalExpire(
            String keyPrefix, ID id, Class<T> type, Function<ID, T> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.存在，直接返回
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        T t = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return t;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock){
            // 6.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    T newR = dbFallback.apply(id);
                    // 重建缓存
                    this.setWithLogicExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4.返回过期的商铺信息
        return t;
    }
//    使用互斥锁解决缓存击穿
    public <T, ID> T queryWithMutex(String keyPrefix, ID id, Class<T> type, Function<ID, T> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(Json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(Json, type);
        }
        // 判断命中的是否是空值
        if (Json != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.实现缓存重建
        // 4.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
//        为什么要初始化为空
        T t = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2.判断是否获取成功
            if (!isLock) {
                // 4.3.获取锁失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 4.4.获取锁成功，根据id查询数据库
            t = dbFallback.apply(id);
            // 5.不存在，返回错误
            if (t == null) {
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误信息
                return null;
            }
            // 6.存在，写入redis
            this.set(key, t, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 7.释放锁
            unlock(lockKey);
        }
        // 8.返回
        return t;
    }



    //    尝试获取锁
    private boolean tryLock(String key){
//        用来判断是否有线程先获得锁了
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
