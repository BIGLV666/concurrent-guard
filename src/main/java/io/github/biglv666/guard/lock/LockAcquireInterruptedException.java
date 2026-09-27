package io.github.biglv666.guard.lock;

/**
 * 锁等待被中断异常 —— 等待获取锁期间当前线程被 {@code Thread#interrupt()} 中断时抛出，
 * 与"等待超时"区分（超时抛父类 {@link LockAcquireTimeoutException}）。
 *
 * <p>继承自 {@link LockAcquireTimeoutException}：已按超时异常配置的全局处理器
 * （如 {@code @ExceptionHandler(LockAcquireTimeoutException.class)}）无需改动即可覆盖本异常；
 * 需要单独处理取消/关闭场景时可精确捕获本类型。异常抛出前中断标记已被恢复。
 *
 * @author Guard Team
 * @since 0.2.1
 */
public class LockAcquireInterruptedException extends LockAcquireTimeoutException {

    public LockAcquireInterruptedException(String key, String message) {
        super(key, message);
    }
}
