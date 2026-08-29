package io.github.biglv666.guard.idempotent;

/**
 * 幂等拒绝异常 —— TTL 窗口内同 key 的重复请求被拒绝时抛出。
 *
 * <p>消息默认来自 {@link Idempotent#message()}，业务方可捕获后转换为统一的
 * 「请勿重复提交」响应；不捕获则按普通运行时异常进入全局异常处理。
 *
 * @author Guard Team
 * @since 0.1.0
 */
public class IdempotentRejectedException extends RuntimeException {

    /**
     * 被拒绝的完整幂等键（含前缀），便于上层日志与排查。
     */
    private final String key;

    public IdempotentRejectedException(String key, String message) {
        super(message);
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
