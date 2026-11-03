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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LockTemplate} 程序化加锁路径的拒绝上报测试：
 * 超时与中断必须与声明式 {@link LockAspect} 一样发布事件并累加拒绝指标。
 *
 * @author Guard Team
 * @since 0.2.1
 */
class LockTemplateRejectionTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final List<GuardRejectedEvent> events = new ArrayList<>();

    @Test
    void timeoutPublishesEventAndRejectedCounter() {
        LockTemplate template = template(new FailingExecutor(
                new LockAcquireTimeoutException("guard:lock:k", "获取锁超时")));

        assertThrows(LockAcquireTimeoutException.class,
                () -> template.withLocalLock("k", () -> "v"));

        assertEquals(1, events.size());
        GuardRejectedEvent event = events.get(0);
        assertEquals(GuardEventType.LOCK_TIMEOUT, event.getType());
        assertEquals("guard:lock:k", event.getKey());
        assertTrue(event.getReason().contains("超时"), "实际原因: " + event.getReason());
        assertEquals(1.0, registry.get(GuardMetrics.REJECTED_COUNTER)
                .tag("type", GuardEventType.LOCK_TIMEOUT.name()).counter().count());
    }

    @Test
    void interruptionPublishesEventAndRejectedCounter() {
        LockTemplate template = template(new FailingExecutor(
                new LockAcquireInterruptedException("guard:lock:k", "锁等待被中断")));

        assertThrows(LockAcquireInterruptedException.class,
                () -> template.withLocalLock("k", () -> "v"));

        assertEquals(1, events.size());
        assertTrue(events.get(0).getReason().contains("中断"), "实际原因: " + events.get(0).getReason());
        assertEquals(1.0, registry.get(GuardMetrics.REJECTED_COUNTER)
                .tag("type", GuardEventType.LOCK_TIMEOUT.name()).counter().count());
    }

    private LockTemplate template(LockExecutor executor) {
        GuardMetrics metrics = new GuardMetrics(provider(registry));
        return new LockTemplate(List.of(executor), new GuardProperties.Lock(), metrics,
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

    /**
     * 固定抛出获取失败的执行器，用于验证模板的上报路径。
     */
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
