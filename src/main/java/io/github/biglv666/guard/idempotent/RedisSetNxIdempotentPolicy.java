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
 * <p>占位值固定为"处理中"标记；结果重放（{@code mode = REPLAY}）时，业务正常完成后
 * 该值会被序列化的返回值覆盖（继承 TTL），供窗口内的重复请求重放。
 *
 * @author Guard Team
 * @since 0.1.0
 */
public class RedisSetNxIdempotentPolicy implements IdempotentPolicy {

    /**
     * 占位阶段的"处理中"标记值。结果重放模式下，业务正常完成后该值会被
     * 序列化的返回值覆盖；读到此值即代表首个请求仍在处理中。
     */
    static final String PROCESSING_MARKER = "processing";

    private final StringRedisTemplate redisTemplate;

    public RedisSetNxIdempotentPolicy(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryAcquire(String key, Duration ttl) {
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, PROCESSING_MARKER, ttl);
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public void release(String key) {
        redisTemplate.delete(key);
    }

    /**
     * 默认策略支持结果重放：结果与占位复用同一 key，
     * 占位成功后 value 从"处理中标记"改写为序列化的业务结果。
     */
    @Override
    public boolean supportsReplay() {
        return true;
    }

    /**
     * 保存结果：直接覆盖占位值（同一 key），并重新设置 TTL。
     * 覆盖写保证"结果已保存"与"处理中标记"可区分：重复请求读到
     * 处理中标记以外的内容即视为可重放（见 {@link IdempotentAspect} 的标记约定）。
     */
    @Override
    public void saveResult(String key, String payload, Duration ttl) {
        redisTemplate.opsForValue().set(key, payload, ttl);
    }

    /**
     * 加载结果：读到占位阶段的处理中标记时返回 null（表示结果尚未写入，仍在处理中）。
     */
    @Override
    public String loadResult(String key) {
        String value = redisTemplate.opsForValue().get(key);
        return PROCESSING_MARKER.equals(value) ? null : value;
    }
}
