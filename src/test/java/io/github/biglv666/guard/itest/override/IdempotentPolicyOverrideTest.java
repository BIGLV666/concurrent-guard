package io.github.biglv666.guard.itest.override;

import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.idempotent.IdempotentPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 幂等策略覆盖测试：用户注册自定义 {@link IdempotentPolicy} Bean 时，
 * 默认的 Redis setnx 策略不装配，占位与回滚均走自定义实现。
 * 自定义策略不依赖 Redis，无需容器。
 *
 * @author Guard Team
 * @since 0.1.0
 */
@SpringBootTest(classes = IdempotentPolicyOverrideTest.App.class)
class IdempotentPolicyOverrideTest {

    @Autowired
    private CountingPolicy policy;

    @Autowired
    private App.OverrideService service;

    @Test
    void customPolicyHandlesAcquireAndRelease() {
        assertEquals("ok:a", service.handle("a"));
        assertEquals("ok:b", service.handle("b"));
        assertEquals(2, policy.acquireCount.get());

        // 业务异常时切面同样通过自定义策略回滚
        assertThrows(IllegalStateException.class, () -> service.failing("f"));
        assertEquals(1, policy.releaseCount.get());
    }

    /**
     * 测试应用：注册计数策略覆盖默认实现。
     */
    @SpringBootApplication
    static class App {

        @Bean
        public CountingPolicy idempotentPolicy() {
            return new CountingPolicy();
        }

        @Component
        static class OverrideService {
            @Idempotent(key = "#k")
            public String handle(String k) {
                return "ok:" + k;
            }

            @Idempotent(key = "#k")
            public String failing(String k) {
                throw new IllegalStateException("boom");
            }
        }
    }

    /**
     * 自定义策略：恒允许通过，仅计数，不依赖 Redis。
     */
    static class CountingPolicy implements IdempotentPolicy {
        final AtomicInteger acquireCount = new AtomicInteger();
        final AtomicInteger releaseCount = new AtomicInteger();

        @Override
        public boolean tryAcquire(String key, Duration ttl) {
            acquireCount.incrementAndGet();
            return true;
        }

        @Override
        public void release(String key) {
            releaseCount.incrementAndGet();
        }
    }
}
