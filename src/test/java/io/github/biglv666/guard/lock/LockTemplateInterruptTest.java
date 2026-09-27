package io.github.biglv666.guard.lock;

import io.github.biglv666.guard.GuardProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LockTemplate} 本地锁等待被中断的单元测试（不依赖 Redis）：
 * 中断应抛 {@link LockAcquireInterruptedException}（而非误报为锁超时），且中断标记被恢复。
 *
 * @author Guard Team
 * @since 0.2.1
 */
class LockTemplateInterruptTest {

    @Test
    void localLockWaitInterruptedThrowsInterruptException() throws Exception {
        // withLocalLock 不触碰 RedissonClient，传 null 即可
        LockTemplate template = new LockTemplate(null, new GuardProperties.Lock(), new LocalLockManager(), null);

        CountDownLatch held = new CountDownLatch(1);
        Thread holder = new Thread(() -> template.withLocalLock("k", () -> {
            held.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        assertTrue(held.await(5, TimeUnit.SECONDS));

        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                template.withLocalLock("k", () -> "v");
            } catch (Throwable t) {
                error.set(t);
            }
        });
        waiter.start();
        // 等待线程已阻塞在 tryLock 上再中断
        Thread.sleep(100);
        waiter.interrupt();
        waiter.join(5000);
        holder.join(5000);

        assertTrue(error.get() instanceof LockAcquireInterruptedException,
                "等待被中断应抛 LockAcquireInterruptedException，实际: " + error.get());
        assertTrue(error.get().getMessage().contains("被中断"),
                "异常消息应标明中断而非超时，实际: " + error.get().getMessage());
        // 中断标记已被恢复，调用方可感知取消
        assertTrue(waiter.isInterrupted());
    }
}
