package io.github.biglv666.guard.event;

/**
 * Guard 拒绝事件的类型枚举。
 *
 * <p>每种类型对应一类"请求被防护组件拦截"的场景，业务方可据此路由不同的告警渠道。
 *
 * @author Guard Team
 * @since 0.1.0
 */
public enum GuardEventType {

    /**
     * 幂等拒绝：同一幂等键的请求在 TTL 窗口内重复到达，被 {@code @Idempotent} 切面拒绝。
     */
    IDEMPOTENT_REJECTED,

    /**
     * 幂等降级：Redis 故障且配置为 fail-close，请求被拒绝以保一致。
     */
    IDEMPOTENT_DEGRADED,

    /**
     * 幂等重放：REPLAY 模式下重复请求直接返回了首次的处理结果。
     * 仅计入指标（{@code guard_replayed_total}），不发布拒绝事件——重放不是拒绝。
     *
     * @since 0.2.0
     */
    IDEMPOTENT_REPLAYED,

    /**
     * 锁等待超时：获取分布式锁在 waitTime 内未成功，按注解策略处理。
     */
    LOCK_TIMEOUT,

    /**
     * 锁降级：锁基础设施故障（Redis 断连、命令超时等），按
     * {@code guard.lock.fail-open} 放行或拒绝。
     *
     * @since 0.2.2
     */
    LOCK_DEGRADED,

    /**
     * 幂等业务耗时超过 TTL。仅用于指标 tag 与日志，不发布拒绝事件。
     *
     * @since 0.2.2
     */
    IDEMPOTENT_TTL_EXCEEDED
}
