package io.github.biglv666.guard.idempotent;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 默认幂等策略 —— Redis setnx + TTL。
 *
 * <p>使用 {@link StringRedisTemplate#opsForValue()#setIfAbsent(key, value, ttl)}
 * 在一次原子操作内完成"占位 + 设置过期"，不存在先 set 再 expire 的竞态窗口；
 * 释放即删除键。
 *
 * <p>占位值固定写入当前毫秒时间戳，仅用于 Redis 侧排查问题，业务无语义。
 *
 * @author Guard Team
 * @since 0.1.0
 */
public class RedisSetNxIdempotentPolicy implements IdempotentPolicy {

    private final StringRedisTemplate redisTemplate;

    public RedisSetNxIdempotentPolicy(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryAcquire(String key, Duration ttl) {
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(System.currentTimeMillis()), ttl);
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public void release(String key) {
        redisTemplate.delete(key);
    }
}
