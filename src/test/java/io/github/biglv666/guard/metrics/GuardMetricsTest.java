package io.github.biglv666.guard.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link GuardMetrics} 单元测试：指标名称、tag 与无注册表时的空操作行为。
 *
 * @author Guard Team
 * @since 0.2.0
 */
class GuardMetricsTest {

    @Test
    void reportsCounterAndTimerWithRegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GuardMetrics metrics = new GuardMetrics(provider(registry));

        metrics.incrementRejected("LOCK_TIMEOUT");
        metrics.incrementRejected("LOCK_TIMEOUT");
        metrics.incrementReplayed("IDEMPOTENT_REPLAYED");
        metrics.recordLockAcquire("REDIS", 1_500_000);

        assertEquals(2.0, registry.get(GuardMetrics.REJECTED_COUNTER).tag("type", "LOCK_TIMEOUT").counter().count());
        assertEquals(1.0, registry.get(GuardMetrics.REPLAYED_COUNTER).tag("type", "IDEMPOTENT_REPLAYED").counter().count());
        // 1.5ms 以纳秒记录，Prometheus 侧渲染为秒
        assertEquals(0.0015,
                registry.get(GuardMetrics.LOCK_ACQUIRE_TIMER).tag("type", "REDIS").timer().totalTime(TimeUnit.SECONDS),
                1e-9);
    }

    @Test
    void noOpsWithoutRegistry() {
        // 无 MeterRegistry 时所有上报为空操作，不应抛异常
        GuardMetrics metrics = new GuardMetrics(provider(null));
        metrics.incrementRejected("LOCK_TIMEOUT");
        metrics.incrementReplayed("IDEMPOTENT_REPLAYED");
        metrics.recordLockAcquire("REDIS", 1_000);
    }

    private ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
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
}
