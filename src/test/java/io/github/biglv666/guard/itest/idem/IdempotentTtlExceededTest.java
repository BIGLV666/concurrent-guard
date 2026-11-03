package io.github.biglv666.guard.itest.idem;

import io.github.biglv666.guard.event.GuardEventType;
import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.metrics.GuardMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 业务耗时超过幂等 TTL 时，应记录 warn 与
 * {@code guard_idempotent_ttl_exceeded_total} 指标。
 *
 * @author Guard Team
 * @since 0.2.2
 */
@SpringBootTest(classes = IdempotentTtlExceededTest.App.class,
        properties = "guard.idempotent.store=local")
class IdempotentTtlExceededTest {

    @Autowired
    private App.Service service;

    @Autowired
    private SimpleMeterRegistry registry;

    @Test
    void slowBusinessExceedingTtlIsCounted() {
        assertEquals("ok:k", service.slow("k"));
        assertEquals(1.0, registry.get(GuardMetrics.TTL_EXCEEDED_COUNTER)
                .tag("type", GuardEventType.IDEMPOTENT_TTL_EXCEEDED.name()).counter().count());
    }

    @SpringBootApplication
    static class App {

        @Bean
        public SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Component
        static class Service {
            @Idempotent(key = "#k", ttl = 20, timeUnit = TimeUnit.MILLISECONDS)
            public String slow(String k) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "ok:" + k;
            }
        }
    }
}
