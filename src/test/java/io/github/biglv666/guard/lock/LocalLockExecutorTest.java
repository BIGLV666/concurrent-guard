package io.github.biglv666.guard.lock;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalLockExecutor} 基础行为测试：获取/释放、竞争超时与句柄幂等关闭。
 *
 * @author Guard Team
 * @since 0.2.1
 */
class LocalLockExecutorTest {

    @Test
    void acquireAndCloseIsIdempotent() {
        LocalLockExecutor executor = new LocalLockExecutor(new LocalLockManager());
        LockHandle handle = executor.acquire("k", 1, -1, TimeUnit.SECONDS);
        assertTrue(handle.acquireNanos() >= 0);
        handle.close();
        assertDoesNotThrow(handle::close);
    }

    @Test
    void contentionTimesOutWithLockAcquireTimeoutException() throws Exception {
        LocalLockExecutor executor = new LocalLockExecutor(new LocalLockManager());
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> holderError = new AtomicReference<>();

        Thread holder = new Thread(() -> {
            try (LockHandle handle = executor.acquire("k", 1, -1, TimeUnit.SECONDS)) {
                held.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (Throwable t) {
                holderError.set(t);
            }
        });
        holder.start();
        assertTrue(held.await(5, TimeUnit.SECONDS));

        assertThrows(LockAcquireTimeoutException.class,
                () -> executor.acquire("k", 50, -1, TimeUnit.MILLISECONDS));

        release.countDown();
        holder.join(5000);
        assertTrue(holderError.get() == null, "持锁线程不应异常: " + holderError.get());
    }
}
