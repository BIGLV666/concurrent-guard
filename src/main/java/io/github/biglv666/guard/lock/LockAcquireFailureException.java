package io.github.biglv666.guard.lock;

/**
 * 锁基础设施故障：Redis 断连、命令超时、Redisson 内部异常等。
 *
 * <p>继承 {@link LockAcquireTimeoutException}，现有按超时异常配置的全局处理器
 * 无需改动即可覆盖；需要区分"竞争超时"与"基础设施故障"时可精确捕获本类型。
 * 是否放行由 {@code guard.lock.fail-open} 决定，默认 fail-close。</p>
 *
 * @author Guard Team
 * @since 0.2.2
 */
public class LockAcquireFailureException extends LockAcquireTimeoutException {

    public LockAcquireFailureException(String key, String message, Throwable cause) {
        super(key, message);
        if (cause != null) {
            initCause(cause);
        }
    }
}
