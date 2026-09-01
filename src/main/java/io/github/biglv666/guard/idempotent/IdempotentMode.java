package io.github.biglv666.guard.idempotent;

/**
 * 幂等模式 —— 决定 TTL 窗口内重复请求的处理方式。
 *
 * @author Guard Team
 * @since 0.2.0
 */
public enum IdempotentMode {

    /**
     * 拒绝模式（默认）：TTL 窗口内的重复请求一律抛出 {@link IdempotentRejectedException}，
     * 只做"防重复提交"，不关心首次请求的结果。
     */
    REJECT,

    /**
     * 重放模式：首个请求业务正常返回后，将返回值序列化写入占位键；
     * 窗口内的重复请求若业务已完成，则直接返回首次的结果（不再调用业务方法）；
     * 若首次请求仍在处理中，则仍按拒绝处理（不等待、不阻塞）；
     * 业务异常的回滚行为与 {@code rollbackOnException} 保持一致，异常本身不会被重放。
     */
    REPLAY
}
