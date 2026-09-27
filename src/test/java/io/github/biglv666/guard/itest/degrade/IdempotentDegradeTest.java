package io.github.biglv666.guard.itest.degrade;

import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.idempotent.IdempotentPolicy;
import io.github.biglv666.guard.idempotent.IdempotentRejectedException;
import org.junit.jupiter.api.Nested;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等降级测试：自定义策略持续故障（模拟 Redis 不可用）时，
 * 按 fail-open / fail-close 两种配置验证行为。策略故障不依赖真实 Redis，无需容器。
 *
 * <p>两个测试上下文通过 {@link Nested} 内部类声明（JUnit 5 不会发现静态嵌套测试类，
 * 静态嵌套写法会导致整个测试静默不被执行）；计数器一律使用 Bean 实例字段
 * （static 字段会被同 JVM 的多个上下文共享，不能用于断言）。
 *
 * @author Guard Team
 * @since 0.1.0
 */
class IdempotentDegradeTest {

    /**
     * fail-open：策略故障时放行业务请求（可用性优先），本次请求不做幂等防护。
     */
    @Nested
    @SpringBootTest(classes = DegradeApp.class, properties = "guard.idempotent.fail-open=true")
    class FailOpenTest {
        @Autowired
        private DegradeApp.DegradeService service;

        @Autowired
        private DegradeApp.FailingPolicy policy;

        @Test
        void policyFailureAllowsBusinessRequest() {
            assertEquals("ok:k1", service.handle("k1"));
            assertEquals(1, policy.attempts.get());
        }
    }

    /**
     * fail-close：策略故障时拒绝请求（一致性优先），抛幂等拒绝异常而非基础设施异常。
     */
    @Nested
    @SpringBootTest(classes = DegradeApp.class, properties = "guard.idempotent.fail-open=false")
    class FailCloseTest {
        @Autowired
        private DegradeApp.DegradeService service;

        @Autowired
        private DegradeApp.FailingPolicy policy;

        @Test
        void policyFailureRejectsRequest() {
            IdempotentRejectedException e = assertThrows(IdempotentRejectedException.class,
                    () -> service.handle("k2"));
            assertTrue(e.getMessage().contains("服务暂不可用"));
            // 策略确实被调用过一次后才降级拒绝
            assertEquals(1, policy.attempts.get());
            // 抛出的是降级拒绝而非业务返回值：拒绝发生在业务执行之前，业务方法必然未执行
        }
    }

    /**
     * 降级测试共用应用：注册恒故障策略。
     */
    @SpringBootApplication
    static class DegradeApp {

        @Bean
        public FailingPolicy idempotentPolicy() {
            return new FailingPolicy();
        }

        @Component
        static class DegradeService {

            @Idempotent(key = "#k")
            public String handle(String k) {
                // 注意：Bean 被 CGLIB 代理（Objenesis 创建，不初始化实例字段），
                // 不能用实例字段计数；是否执行由调用方返回值/异常判定
                return "ok:" + k;
            }
        }

        /**
         * 恒故障策略：任何调用都抛异常，模拟基础设施不可用。
         * 仅实现旧版 tryAcquire/release，同时验证令牌 SPI 的 default 委托兼容性。
         */
        static class FailingPolicy implements IdempotentPolicy {
            final AtomicInteger attempts = new AtomicInteger();

            @Override
            public boolean tryAcquire(String key, Duration ttl) {
                attempts.incrementAndGet();
                throw new IllegalStateException("redis down");
            }

            @Override
            public void release(String key) {
                throw new IllegalStateException("redis down");
            }
        }
    }
}
