package io.github.biglv666.guard.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JVM 本地锁执行器，不依赖 Redisson。
 *
 * <p>通过 {@link LocalLockManager} 管理按业务 key 隔离的 {@link java.util.concurrent.locks.ReentrantLock}
 * 槽位；锁只保证当前 JVM 内互斥，不能替代多实例环境下的分布式锁。</p>
 *
 * @author Guard Team
 * @since 0.2.1
 */
public class LocalLockExecutor implements LockExecutor {

    private static final Logger log = LoggerFactory.getLogger(LocalLockExecutor.class);

    private final LocalLockManager localLockManager;

    public LocalLockExecutor(LocalLockManager localLockManager) {
        this.localLockManager = localLockManager;
    }

    @Override
    public LockType type() {
        return LockType.SYNCHRONIZED;
    }

    @Override
    public LockHandle acquire(String key, long waitTime, long leaseTime, TimeUnit unit) {
        long start = System.nanoTime();
        LocalLockManager.Handle handle = localLockManager.acquire(key);
        boolean locked = false;
        try {
            locked = handle.lock().tryLock(waitTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.close();
            throw new LockAcquireInterruptedException(key, "锁等待被中断: " + key);
        } catch (RuntimeException e) {
            handle.close();
            throw e;
        }
        if (!locked) {
            handle.close();
            throw new LockAcquireTimeoutException(key,
                    String.format("获取锁超时: %s（等待 %d %s）", key, waitTime, unit));
        }
        long acquireNanos = System.nanoTime() - start;
        return new LocalHandle(handle, acquireNanos, key);
    }

    /**
     * 本地锁句柄：关闭时先解锁，再释放 {@link LocalLockManager} 的引用计数槽位。
     * 关闭失败只记录日志，不影响调用方正在传播的业务异常。
     */
    private static final class LocalHandle implements LockHandle {

        private final LocalLockManager.Handle handle;
        private final long acquireNanos;
        private final String key;
        private final AtomicBoolean closed = new AtomicBoolean();

        private LocalHandle(LocalLockManager.Handle handle, long acquireNanos, String key) {
            this.handle = handle;
            this.acquireNanos = acquireNanos;
            this.key = key;
        }

        @Override
        public long acquireNanos() {
            return acquireNanos;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                handle.lock().unlock();
            } catch (RuntimeException e) {
                log.warn("本地锁释放异常 - key: {}", key, e);
            } finally {
                handle.close();
            }
        }
    }
}
