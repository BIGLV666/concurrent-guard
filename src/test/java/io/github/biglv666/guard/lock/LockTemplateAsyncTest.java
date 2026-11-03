package io.github.biglv666.guard.lock;

import io.github.biglv666.guard.GuardProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LockTemplate 异步返回值快速失败测试：锁必须由获取线程释放，
 * 因此模板不尝试跨线程持有/释放锁，发现 CompletionStage 返回值时立即报错并释放锁。
 *
 * @author Guard Team
 * @since 0.3.0
 */
class LockTemplateAsyncTest {

    @Test
    void asyncResultFailsFastAndReleasesLock() {
        AtomicBoolean closed = new AtomicBoolean();
        LockExecutor executor = new LockExecutor() {
            @Override
            public LockType type() {
                return LockType.SYNCHRONIZED;
            }

            @Override
            public LockHandle acquire(String key, long waitTime, long leaseTime, TimeUnit unit) {
                return new LockHandle() {
                    @Override
                    public long acquireNanos() {
                        return 1;
                    }

                    @Override
                    public void close() {
                        closed.set(true);
                    }
                };
            }
        };

        LockTemplate template = new LockTemplate(List.of(executor), new GuardProperties.Lock(), null, null);
        assertThrows(LockConfigurationException.class,
                () -> template.withLocalLock("k", () -> CompletableFuture.completedFuture("v")));
        assertTrue(closed.get(), "快速失败后锁必须已释放");
    }
}
