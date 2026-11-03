package io.github.biglv666.guard.itest.lock;

import io.github.biglv666.guard.GuardAutoConfiguration;
import io.github.biglv666.guard.lock.LocalLockExecutor;
import io.github.biglv666.guard.lock.LockTemplate;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 本地锁独立性回归测试：classpath 中没有 Redisson 时，
 * 本地锁执行器、LockAspect、LockTemplate 仍应正常装配和运行。
 *
 * <p>历史上锁配置整体标注了 {@code @ConditionalOnClass(RedissonClient.class)}，
 * 导致"本地锁不需要 Redisson"的文档承诺失效；本测试固化修复后的装配边界。</p>
 *
 * @author Guard Team
 * @since 0.2.1
 */
class LocalLockWithoutRedissonTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GuardAutoConfiguration.class))
            .withPropertyValues("guard.idempotent.enabled=false")
            .withClassLoader(new FilteredClassLoader(RedissonClient.class));

    @Test
    void localLockWorksWithoutRedissonOnClasspath() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(LocalLockExecutor.class);
            assertThat(context).hasSingleBean(LockTemplate.class);

            LockTemplate template = context.getBean(LockTemplate.class);
            assertEquals("v", template.withLocalLock("local-only", () -> "v"));
        });
    }

    @Test
    void redisLockFailsWithClearGuidanceWhenRedissonAbsent() {
        runner.run(context -> {
            LockTemplate template = context.getBean(LockTemplate.class);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> template.withLock("redis-only", () -> "v"));
            assertThat(error.getMessage()).contains("未装配锁执行器", "REDIS");
        });
    }
}
