package io.github.biglv666.guard.lock;

import io.github.biglv666.guard.GuardProperties;
import io.github.biglv666.guard.event.GuardEventType;
import io.github.biglv666.guard.event.GuardRejectedEvent;
import io.github.biglv666.guard.metrics.GuardMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 锁基础设施故障降级测试：fail-open 放行、fail-close 拒绝、配置错误不受 fail-open 影响。
 *
 * @author Guard Team
 * @since 0.2.2
 */
class LockTemplateFailureTest {

    @Test
    void failOpenSkipsLockAndReportsDegraded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<GuardRejectedEvent> events = new ArrayList<>();
        LockTemplate template = template(new FailingExecutor(new IllegalStateException("redis down")),
                true, registry, events);

        assertEquals("v", template.withLocalLock("k", () -> "v"));
        assertEquals(1, events.size());
        assertEquals(GuardEventType.LOCK_DEGRADED, events.get(0).getType());
        assertEquals(1.0, registry.get(GuardMetrics.DEGRADED_COUNTER)
                .tag("type", GuardEventType.LOCK_DEGRADED.name()).counter().count());
    }

    @Test
    void failCloseRejectsWithLockAcquireFailureException() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<GuardRejectedEvent> events = new ArrayList<>();
        LockTemplate template = template(new FailingExecutor(new IllegalStateException("redis down")),
                false, registry, events);

        assertThrows(LockAcquireFailureException.class,
                () -> template.withLocalLock("k", () -> "v"));
        assertEquals(1, events.size());
        assertEquals(GuardEventType.LOCK_DEGRADED, events.get(0).getType());
        assertEquals(1.0, registry.get(GuardMetrics.REJECTED_COUNTER)
                .tag("type", GuardEventType.LOCK_DEGRADED.name()).counter().count());
        assertEquals(1.0, registry.get(GuardMetrics.DEGRADED_COUNTER)
                .tag("type", GuardEventType.LOCK_DEGRADED.name()).counter().count());
    }

    @Test
    void configurationErrorIsNotAffectedByFailOpen() {
        LockTemplate template = template(new FailingExecutor(new LockConfigurationException("no client")),
                true, new SimpleMeterRegistry(), new ArrayList<>());

        assertThrows(LockConfigurationException.class,
                () -> template.withLocalLock("k", () -> "v"));
    }

    private LockTemplate template(LockExecutor executor, boolean failOpen,
                                  SimpleMeterRegistry registry, List<GuardRejectedEvent> events) {
        GuardProperties.Lock properties = new GuardProperties.Lock();
        properties.setFailOpen(failOpen);
        GuardMetrics metrics = new GuardMetrics(provider(registry));
        return new LockTemplate(List.of(executor), properties, metrics,
                event -> events.add((GuardRejectedEvent) event));
    }

    private static ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        return new ObjectProvider<>() {
            @Override
            public MeterRegistry getIfAvailable() {
                return registry;
            }

            @Override
            public MeterRegistry getIfUnique() {
                return registry;
            }

            @Override
            public MeterRegistry getObject() {
                return registry;
            }

            @Override
            public MeterRegistry getObject(Object... args) {
                return registry;
            }
        };
    }

    private static final class FailingExecutor implements LockExecutor {

        private final RuntimeException failure;

        private FailingExecutor(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public LockType type() {
            return LockType.SYNCHRONIZED;
        }

        @Override
        public LockHandle acquire(String key, long waitTime, long leaseTime, TimeUnit unit) {
            throw failure;
        }
    }
}
