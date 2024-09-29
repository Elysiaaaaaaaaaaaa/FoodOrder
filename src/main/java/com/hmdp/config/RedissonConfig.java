package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

//配置Redisson
@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient(){
//        配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.31.128:6379");
//        创建RedissonClient对象
        return Redisson.create(config);
    }
//    配置布隆过滤器
//    @Bean
//    public RBloomFilter<String> bloomFilter(RedissonClient redissonClient){
//        RBloomFilter<String> bloomFilter=redissonClient.getBloomFilter("bloom");
//        bloomFilter.tryInit(1000000L,0.01);
//        return bloomFilter;
//    }

}
