package io.github.biglv666.guard.idempotent;

import java.time.Duration;

/**
 * 幂等策略 SPI —— 幂等占位的获取与释放。
 *
 * <p>默认实现为 {@link RedisSetNxIdempotentPolicy}（Redis setnx + TTL）。
 * 业务方注册自定义 {@link IdempotentPolicy} Bean 即可覆盖默认实现，
 * 例如改用数据库唯一约束、本地缓存或双写校验等策略：
 *
 * <pre>
 * &#64;Bean
 * public IdempotentPolicy idempotentPolicy() {
 *     return new MyCustomPolicy();
 * }
 * </pre>
 *
 * <p>策略实现约定：
 * <ul>
 *   <li>{@link #tryAcquire} 必须是原子的（占位与过期时间同一生效），保证并发下只有一个调用方成功；</li>
 *   <li>实现自身故障时应抛出异常而非返回 true/false，由切面统一按 fail-open/fail-close 降级；</li>
 *   <li>{@link #release} 只应删除本次占位，不应影响其他调用方的占位。</li>
 * </ul>
 *
 * @author Guard Team
 * @since 0.1.0
 */
public interface IdempotentPolicy {

    /**
     * 尝试为指定 key 获取幂等占位。
     *
     * @param key 完整幂等键（已含配置前缀）
     * @param ttl 占位保持时长
     * @return true = 占位成功，允许执行业务；false = 已存在占位，应拒绝
     * @throws Exception 实现自身故障时抛出，由切面统一降级处理
     */
    boolean tryAcquire(String key, Duration ttl);

    /**
     * 释放（回滚）指定 key 的幂等占位，用于业务方法异常后的重试放行。
     *
     * @param key 完整幂等键（已含配置前缀）
     * @throws Exception 实现自身故障时抛出；切面仅记录告警，不影响原异常传播
     */
    void release(String key);

    /**
     * 是否支持 {@link IdempotentMode#REPLAY} 结果重放。
     *
     * <p>默认返回 false（保持 0.1.0 自定义策略的兼容性）：策略未声明支持而注解使用了
     * REPLAY 模式时，切面在首次调用处抛出带修复指引的异常，不会静默降级为拒绝模式。
     * 支持重放的策略应同时实现 {@link #saveResult} 与 {@link #loadResult} 并覆写本方法返回 true。
     *
     * @return true = 支持结果重放
     * @since 0.2.0
     */
    default boolean supportsReplay() {
        return false;
    }

    /**
     * 保存业务方法正常返回的结果，供窗口内的重复请求重放。
     *
     * <p>仅在 {@link #supportsReplay()} 为 true 时会被调用，且调用发生在
     * {@link #tryAcquire} 占位成功、业务方法正常返回之后。
     *
     * @param key     完整幂等键（与占位键相同）
     * @param payload 序列化后的结果内容（由 {@link ResultCodec} 编码）
     * @param ttl     结果保持时长（与占位 TTL 一致）
     * @throws Exception 实现自身故障时抛出；切面仅记录告警，不影响业务返回值
     * @since 0.2.0
     */
    default void saveResult(String key, String payload, Duration ttl) {
        throw new UnsupportedOperationException("当前 IdempotentPolicy 未实现结果重放（supportsReplay=false）");
    }

    /**
     * 加载此前保存的结果，用于重复请求的重放。
     *
     * @param key 完整幂等键（与占位键相同）
     * @return 序列化的结果内容；结果尚未写入（首个请求仍在处理中）或已过期时返回 null
     * @throws Exception 实现自身故障时抛出；切面统一按 fail-open/fail-close 语义降级
     * @since 0.2.0
     */
    default String loadResult(String key) {
        throw new UnsupportedOperationException("当前 IdempotentPolicy 未实现结果重放（supportsReplay=false）");
    }
}
