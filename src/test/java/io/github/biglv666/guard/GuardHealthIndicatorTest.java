package io.github.biglv666.guard;

import io.github.biglv666.guard.idempotent.IdempotentPolicy;
import io.github.biglv666.guard.idempotent.LocalIdempotentPolicy;
import io.github.biglv666.guard.lock.LockExecutor;
import io.github.biglv666.guard.lock.LockHandle;
import io.github.biglv666.guard.lock.LockType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link GuardHealthIndicator} 单元测试：健康状态与配置详情。
 *
 * @author Guard Team
 * @since 0.3.0
 */
class GuardHealthIndicatorTest {

    @Test
    void reportsAssembledGuardComponents() {
        GuardProperties properties = new GuardProperties();
        properties.getIdempotent().setStore("local");
        IdempotentPolicy policy = new LocalIdempotentPolicy(Duration.ofSeconds(5), 1024);
        LockExecutor executor = new LockExecutor() {
            @Override
            public LockType type() {
                return LockType.SYNCHRONIZED;
            }

            @Override
            public LockHandle acquire(String key, long waitTime, long leaseTime, TimeUnit unit) {
                throw new UnsupportedOperationException();
            }
        };

        GuardHealthIndicator indicator = new GuardHealthIndicator(
                provider(policy), provider(executor), properties);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("local", health.getDetails().get("idempotentStore"));
        assertEquals(policy.getClass().getName(), health.getDetails().get("idempotentPolicy"));
        assertEquals(true, health.getDetails().get("idempotentPolicyPresent"));
        assertEquals(List.of("SYNCHRONIZED"), health.getDetails().get("lockExecutors"));
        assertEquals(false, health.getDetails().get("lockFailOpen"));
    }

    @Test
    void reportsDownWhenIdempotentEnabledButPolicyMissing() {
        GuardProperties properties = new GuardProperties();
        LockExecutor executor = new LockExecutor() {
            @Override
            public LockType type() {
                return LockType.SYNCHRONIZED;
            }

            @Override
            public LockHandle acquire(String key, long waitTime, long leaseTime, TimeUnit unit) {
                throw new UnsupportedOperationException();
            }
        };

        GuardHealthIndicator indicator = new GuardHealthIndicator(
                provider(null), provider(executor), properties);
        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(false, health.getDetails().get("idempotentPolicyPresent"));
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public java.util.stream.Stream<T> orderedStream() {
                return java.util.stream.Stream.of(value);
            }
        };
    }
}
