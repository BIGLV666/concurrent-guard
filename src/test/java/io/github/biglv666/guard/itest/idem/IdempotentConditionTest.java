package io.github.biglv666.guard.itest.idem;

import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.idempotent.IdempotentRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code @Idempotent(condition/unless)} 行为测试。
 *
 * @author Guard Team
 * @since 0.3.0
 */
@SpringBootTest(classes = IdempotentConditionTest.App.class,
        properties = "guard.idempotent.store=local")
class IdempotentConditionTest {

    @Autowired
    private App.Service service;

    @BeforeEach
    void reset() {
        App.Service.conditionalCalls.set(0);
        App.Service.unlessCalls.set(0);
    }

    @Test
    void conditionFalseBypassesIdempotentProtection() {
        assertEquals("ok:a", service.conditional("a", false));
        assertEquals("ok:a", service.conditional("a", false));
        assertEquals(2, App.Service.conditionalCalls.get());
    }

    @Test
    void conditionTrueKeepsIdempotentProtection() {
        assertEquals("ok:b", service.conditional("b", true));
        assertThrows(IdempotentRejectedException.class, () -> service.conditional("b", true));
        assertEquals(1, App.Service.conditionalCalls.get());
    }

    @Test
    void unlessTrueReleasesPlaceholderForRetry() {
        assertEquals("skip-1", service.unless("skip-1"));
        assertEquals("skip-1", service.unless("skip-1"));
        assertEquals(2, App.Service.unlessCalls.get());
    }

    @Test
    void unlessFalseKeepsPlaceholder() {
        assertEquals("ok:keep-1", service.unless("keep-1"));
        assertThrows(IdempotentRejectedException.class, () -> service.unless("keep-1"));
        assertEquals(1, App.Service.unlessCalls.get());
    }

    @SpringBootApplication
    static class App {

        @Component
        static class Service {
            static final AtomicInteger conditionalCalls = new AtomicInteger();
            static final AtomicInteger unlessCalls = new AtomicInteger();

            @Idempotent(key = "#k", condition = "#enabled")
            public String conditional(String k, boolean enabled) {
                conditionalCalls.incrementAndGet();
                return "ok:" + k;
            }

            @Idempotent(key = "#k", unless = "#result.startsWith('skip')")
            public String unless(String k) {
                unlessCalls.incrementAndGet();
                return k.startsWith("skip") ? k : "ok:" + k;
            }
        }
    }
}
