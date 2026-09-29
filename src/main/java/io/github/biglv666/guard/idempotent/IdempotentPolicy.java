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
 * <p>占位归属：切面统一走 {@link #tryAcquireToken} / {@link #releaseIfOwned} /
 * {@link #saveResultIfOwned} 的令牌链路。只实现 {@link #tryAcquire} / {@link #release} 的
 * 旧实现无需任何改动即可继续工作（default 方法委托到旧方法），但此时不具备占位归属校验，
 * TTL 耗尽后其他请求的回滚可能误删占位；建议实现令牌链路以获得严格的归属保护。
 *
 * @author Guard Team
 * @since 0.1.0
 */
public interface IdempotentPolicy {

    /**
     * 旧版（不支持令牌）实现的占位令牌哨兵值：占位成功但策略无法提供归属令牌。
     * 切面据此走无归属校验的旧语义（直接删除/直接覆盖），与 0.x 行为一致。
     *
     * @since 0.2.1
     */
    String NO_OWNER_TOKEN = "__guard:no-owner-token__";

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
     * 带归属令牌的占位获取，切面的标准入口。
     *
     * <p>占位成功时返回本次请求的专属令牌，供 {@link #releaseIfOwned} 与
     * {@link #saveResultIfOwned} 校验占位归属；占位失败返回 null。
     * 默认实现委托到 {@link #tryAcquire}，令牌为 {@link #NO_OWNER_TOKEN}
     * （无归属校验语义，兼容旧实现）。
     *
     * @param key 完整幂等键（已含配置前缀）
     * @param ttl 占位保持时长
     * @return 占位成功返回非 null 令牌；占位失败返回 null
     * @throws Exception 实现自身故障时抛出，由切面统一降级处理
     * @since 0.2.1
     */
    default String tryAcquireToken(String key, Duration ttl) {
        return tryAcquire(key, ttl) ? NO_OWNER_TOKEN : null;
    }

    /**
     * 校验占位归属后释放（回滚）指定 key 的占位，用于业务方法异常后的重试放行。
     *
     * <p>仅当占位仍属于 {@code token} 对应的本次请求时才删除，避免 TTL 耗尽后
     * 误删其他请求新写入的占位。默认实现委托到 {@link #release}（无归属校验）。
     *
     * @param key   完整幂等键（已含配置前缀）
     * @param token {@link #tryAcquireToken} 返回的令牌
     * @throws Exception 实现自身故障时抛出；切面仅记录告警，不影响原异常传播
     * @since 0.2.1
     */
    default void releaseIfOwned(String key, String token) {
        release(key);
    }

    /**
     * 校验占位归属后保存业务方法正常返回的结果，供窗口内的重复请求重放。
     *
     * <p>仅当占位仍属于 {@code token} 对应的本次请求时才写入，避免 TTL 耗尽后
     * 覆盖其他请求的占位造成结果串扰。默认实现委托到 {@link #saveResult}（无归属校验）。
     *
     * <p>仅在 {@link #supportsReplay()} 为 true 时会被调用，且调用发生在
     * {@link #tryAcquireToken} 占位成功、业务方法正常返回之后。
     *
     * @param key     完整幂等键（与占位键相同）
     * @param token   {@link #tryAcquireToken} 返回的令牌
     * @param payload 序列化后的结果内容（由 {@link ResultCodec} 编码）
     * @param ttl     结果保持时长（与占位 TTL 一致）
     * @throws Exception 实现自身故障时抛出；切面仅记录告警，不影响业务返回值
     * @since 0.2.1
     */
    default void saveResultIfOwned(String key, String token, String payload, Duration ttl) {
        saveResult(key, payload, ttl);
    }

    /**
     * 慢请求完成哨兵：占位已过期（令牌校验发现键不存在）时，
     * 把本次结果写入短期哨兵键，供过期窗口内到达的重复请求重放。
     *
     * <p>仅当占位已过期（不是被其他请求接管）时才应写入；被接管时写入会造成结果串扰，
     * 由默认策略在 Lua 脚本内区分两种情况。默认实现为空操作（不支持哨兵的策略安全降级为拒绝）。
     *
     * @param key     完整幂等键（与占位键相同）
     * @param token   {@link #tryAcquireToken} 返回的令牌
     * @param payload 序列化后的结果内容
     * @param ttl     结果保持时长
     * @throws Exception 实现自身故障时抛出；切面仅记录告警
     * @since 0.2.1
     */
    default void saveResultIfExpired(String key, String token, String payload, Duration ttl) {
        // 默认不支持哨兵：空实现，过期窗口内的重复请求退回拒绝
    }

    /**
     * 查询慢请求完成哨兵中的结果。
     *
     * @param key 完整幂等键（与占位键相同）
     * @return 哨兵中的序列化结果；无哨兵时返回 null
     * @throws Exception 实现自身故障时抛出
     * @since 0.2.1
     */
    default String loadExpiredResult(String key) {
        return null;
    }

    /**
     * 查询指定 key 占位的剩余有效时间（毫秒）。
     *
     * <p>用于拒绝异常中携带"还需等待多久"的提示。默认返回 -1 表示未知，
     * 支持 TTL 查询的策略应覆写。
     *
     * @param key 完整幂等键（已含配置前缀）
     * @return 剩余毫秒数；-1 表示未知或不支持
     * @since 0.2.1
     */
    default long remainingTtlMillis(String key) {
        return -1;
    }

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
