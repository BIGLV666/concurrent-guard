package io.github.biglv666.guard.lock;

import io.github.biglv666.guard.GuardProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 锁释放异常隔离测试：释放阶段抛出的运行时异常不应覆盖业务返回值或业务异常。
 *
 * @author Guard Team
 * @since 0.2.1
 */
class LockTemplateUnlockFailureTest {

    @Test
    void unlockFailureDoesNotOverrideBusinessResult() {
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
                        throw new IllegalStateException("模拟解锁失败");
                    }
                };
            }
        };

        LockTemplate template = new LockTemplate(List.of(executor), new GuardProperties.Lock(), null, null);
        assertEquals("ok", template.withLocalLock("k", () -> "ok"));
    }
}
