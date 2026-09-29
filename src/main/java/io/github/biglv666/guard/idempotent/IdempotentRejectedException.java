package io.github.biglv666.guard.idempotent;

/**
 * 幂等拒绝异常 —— TTL 窗口内同 key 的重复请求被拒绝时抛出。
 *
 * <p>消息默认来自 {@link Idempotent#message()}，业务方可捕获后转换为统一的
 * 「请勿重复提交」响应；不捕获则按普通运行时异常进入全局异常处理。
 * {@link #getRemainingTtlMillis()} 携带占位的剩余有效时间（毫秒），
 * 业务方可据此提示调用方还需等待多久（-1 表示策略不支持查询）。
 *
 * @author Guard Team
 * @since 0.1.0
 */
public class IdempotentRejectedException extends RuntimeException {

    /**
     * 被拒绝的完整幂等键（含前缀），便于上层日志与排查。
     */
    private final String key;

    /**
     * 占位剩余有效时间（毫秒）；策略不支持查询时为 -1。
     */
    private final long remainingTtlMillis;

    public IdempotentRejectedException(String key, String message) {
        this(key, message, -1);
    }

    public IdempotentRejectedException(String key, String message, long remainingTtlMillis) {
        super(message);
        this.key = key;
        this.remainingTtlMillis = remainingTtlMillis;
    }

    public String getKey() {
        return key;
    }

    /**
     * 占位剩余有效时间（毫秒），可用于提示调用方还需等待多久；策略不支持查询时返回 -1。
     *
     * @since 0.2.1
     */
    public long getRemainingTtlMillis() {
        return remainingTtlMillis;
    }
}
