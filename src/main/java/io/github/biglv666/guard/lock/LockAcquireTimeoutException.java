package io.github.biglv666.guard.lock;

/**
 * 锁等待超时异常 —— {@link LockAcquirePolicy#THROW} 策略下获取锁失败时抛出。
 *
 * @author Guard Team
 * @since 0.1.0
 */
public class LockAcquireTimeoutException extends RuntimeException {

    /**
     * 获取失败的完整锁键（含前缀）。
     */
    private final String key;

    public LockAcquireTimeoutException(String key, String message) {
        super(message);
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
