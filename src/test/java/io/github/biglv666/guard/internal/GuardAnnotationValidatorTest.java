package io.github.biglv666.guard.internal;

import io.github.biglv666.guard.GuardAutoConfiguration;
import io.github.biglv666.guard.GuardValidationException;
import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.lock.DistributedLock;
import io.github.biglv666.guard.lock.LockAcquirePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动期注解/SpEL 校验集成测试。
 *
 * <p>覆盖：变量名错误、属性名严格模式、非法注解属性、fail-fast 开关与禁用开关。</p>
 *
 * @author Guard Team
 * @since 0.2.2
 */
class GuardAnnotationValidatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GuardAutoConfiguration.class))
            .withPropertyValues("guard.idempotent.store=local");

    @Test
    void validAnnotationsStartSuccessfully() {
        runner.withUserConfiguration(ValidApp.class).run(context ->
                assertThat(context).hasNotFailed());
    }

    @Test
    void unknownSpelVariableFailsStartupWithAvailableNames() {
        runner.withUserConfiguration(InvalidVariableApp.class).run(context -> {
            assertValidationFailure(context, "#oder", "order", "p0");
        });
    }

    @Test
    void invalidTtlFailsStartup() {
        runner.withUserConfiguration(InvalidTtlApp.class).run(context ->
                assertValidationFailure(context, "ttl"));
    }

    @Test
    void customPolicyWithoutHandlerFailsStartup() {
        runner.withPropertyValues("guard.idempotent.enabled=false")
                .withUserConfiguration(InvalidCustomHandlerApp.class)
                .run(context -> assertValidationFailure(context, "CUSTOM", "handler"));
    }

    @Test
    void skipPolicyWithPrimitiveReturnFailsStartup() {
        runner.withPropertyValues("guard.idempotent.enabled=false")
                .withUserConfiguration(InvalidSkipPrimitiveApp.class)
                .run(context -> assertValidationFailure(context, "SKIP", "基本类型"));
    }

    @Test
    void propertyTypoOnlyWarnsByDefault() {
        runner.withUserConfiguration(PropertyTypoApp.class).run(context ->
                assertThat(context).hasNotFailed());
    }

    @Test
    void propertyTypoFailsInStrictMode() {
        runner.withPropertyValues("guard.validation.property-check=strict")
                .withUserConfiguration(PropertyTypoApp.class)
                .run(context -> assertValidationFailure(context, "oderId"));
    }

    @Test
    void failFastFalseKeepsApplicationStarting() {
        runner.withPropertyValues("guard.validation.fail-fast=false")
                .withUserConfiguration(InvalidVariableApp.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void validationCanBeDisabled() {
        runner.withPropertyValues("guard.validation.enabled=false")
                .withUserConfiguration(InvalidVariableApp.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    private static void assertValidationFailure(AssertableApplicationContext context, String... fragments) {
        assertThat(context).hasFailed();
        Throwable root = rootCause(context.getStartupFailure());
        assertThat(root).isInstanceOf(GuardValidationException.class);
        for (String fragment : fragments) {
            assertThat(root.getMessage()).contains(fragment);
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    static class Order {
        private final String id;

        Order(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ValidApp {
        @Bean
        Service service() {
            return new Service();
        }

        static class Service {
            @Idempotent(key = "#order.id")
            public String submit(Order order) {
                return order.getId();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class InvalidVariableApp {
        @Bean
        Service service() {
            return new Service();
        }

        static class Service {
            @Idempotent(key = "#oder.id")
            public String submit(Order order) {
                return order.getId();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class InvalidTtlApp {
        @Bean
        Service service() {
            return new Service();
        }

        static class Service {
            @Idempotent(key = "#order.id", ttl = 0)
            public String submit(Order order) {
                return order.getId();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class InvalidCustomHandlerApp {
        @Bean
        Service service() {
            return new Service();
        }

        static class Service {
            @DistributedLock(key = "#order.id", acquirePolicy = LockAcquirePolicy.CUSTOM)
            public String submit(Order order) {
                return order.getId();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class InvalidSkipPrimitiveApp {
        @Bean
        Service service() {
            return new Service();
        }

        static class Service {
            @DistributedLock(key = "#order.id", acquirePolicy = LockAcquirePolicy.SKIP)
            public int submit(Order order) {
                return 1;
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PropertyTypoApp {
        @Bean
        Service service() {
            return new Service();
        }

        static class Service {
            @Idempotent(key = "#order.oderId")
            public String submit(Order order) {
                return order.getId();
            }
        }
    }
}
