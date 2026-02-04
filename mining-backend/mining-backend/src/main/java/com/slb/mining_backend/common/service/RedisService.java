package com.slb.mining_backend.common.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class RedisService {

    private final RedisTemplate redisTemplate;


    public RedisService(RedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 将一个键值对存入 Redis，并设置有效期。
     * @param key 键
     * @param value 值
     * @param timeout 有效期时长
     * @param timeUnit 时间单位
     */
    public void set(String key, String value, long timeout, TimeUnit timeUnit) {
        redisTemplate.opsForValue().set(key, value, timeout, timeUnit);
    }

    /**
     * 检查指定的键是否存在于 Redis 中。
     * @param key 键
     * @return 如果存在返回 true
     */
    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

}
