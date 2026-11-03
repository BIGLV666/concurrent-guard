package io.github.biglv666.guard.lock;

/**
 * 锁配置错误：缺少执行器、缺少 RedissonClient、CUSTOM 未指定 handler 等。
 *
 * <p>这类错误属于编程/配置问题，不受 {@code guard.lock.fail-open} 影响，
 * 始终快速失败并给出修复指引。</p>
 *
 * @author Guard Team
 * @since 0.2.2
 */
public class LockConfigurationException extends IllegalStateException {

    public LockConfigurationException(String message) {
        super(message);
    }

    public LockConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
