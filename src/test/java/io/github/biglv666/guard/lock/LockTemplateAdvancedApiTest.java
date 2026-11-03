package io.github.biglv666.guard.lock;

import io.github.biglv666.guard.GuardProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LockTemplate 0.3.0 新 API：tryWithLock、Callable、超时回退。
 *
 * @author Guard Team
 * @since 0.3.0
 */
class LockTemplateAdvancedApiTest {

    @Test
    void tryWithLockReturnsValueOnSuccess() {
        LockTemplate template = template(successExecutor(LockType.REDIS));
        assertEquals(Optional.of("v"), template.tryWithLock("k", () -> "v"));
    }

    @Test
    void tryWithLockReturnsEmptyOnTimeout() {
        LockTemplate template = template(timeoutExecutor(LockType.REDIS));
        assertFalse(template.tryWithLock("k", () -> "v").isPresent());
    }

    @Test
    void withLockUsesFallbackOnTimeout() {
        LockTemplate template = template(timeoutExecutor(LockType.REDIS));
        assertEquals("fallback", template.withLock("k", 1, -1, TimeUnit.SECONDS,
                () -> "v", () -> "fallback"));
    }

    @Test
    void callWithLockPropagatesCheckedException() {
        LockTemplate template = template(successExecutor(LockType.REDIS));
        assertThrows(IOException.class, () -> template.callWithLock("k", 1, -1, TimeUnit.SECONDS,
                () -> {
                    throw new IOException("checked");
                }));
    }

    @Test
    void tryWithLocalLockWorks() {
        LockTemplate template = template(successExecutor(LockType.SYNCHRONIZED));
        assertTrue(template.tryWithLocalLock("k", () -> "v").isPresent());
    }

    private LockTemplate template(LockExecutor executor) {
        return new LockTemplate(List.of(executor), new GuardProperties.Lock(), null, null);
    }

    private LockExecutor successExecutor(LockType type) {
        return new LockExecutor() {
            @Override
            public LockType type() {
                return type;
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
                    }
                };
            }
        };
    }

    private LockExecutor timeoutExecutor(LockType type) {
        return new LockExecutor() {
            @Override
            public LockType type() {
                return type;
            }

            @Override
            public LockHandle acquire(String key, long waitTime, long leaseTime, TimeUnit unit) {
                throw new LockAcquireTimeoutException(key, "timeout");
            }
        };
    }
}
