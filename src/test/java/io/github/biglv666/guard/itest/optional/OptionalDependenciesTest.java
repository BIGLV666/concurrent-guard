package io.github.biglv666.guard.itest.optional;

import io.github.biglv666.guard.GuardAutoConfiguration;
import io.github.biglv666.guard.lock.LocalLockExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可选依赖缺失测试：classpath 没有 actuator 健康检查类型时，
 * Guard 仍应正常装配，不因 @ConditionalOnClass 配置类加载失败。
 *
 * @author Guard Team
 * @since 0.3.0
 */
class OptionalDependenciesTest {

    @Test
    void guardStartsWithoutActuatorHealthTypes() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GuardAutoConfiguration.class))
                .withPropertyValues("guard.idempotent.enabled=false")
                .withClassLoader(new FilteredClassLoader(HealthIndicator.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LocalLockExecutor.class);
                });
    }
}
