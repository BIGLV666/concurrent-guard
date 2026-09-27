package io.github.biglv666.guard.itest.nopolicy;

import io.github.biglv666.guard.idempotent.Idempotent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容器中缺少 {@code IdempotentPolicy} Bean 的配置错误测试：
 * 即使 fail-open（默认放行降级）也不得把该编程错误吞成静默放行，
 * 首次调用必须抛出带修复指引的 {@link IllegalStateException}。
 * 通过排除 Redis 自动配置使默认策略的 {@code @ConditionalOnBean(StringRedisTemplate)} 不成立，
 * 从而模拟"无策略 Bean"的容器。测试应用独立成包，避免组件扫描互相污染。
 *
 * @author Guard Team
 * @since 0.2.1
 */
@SpringBootTest(classes = IdempotentMissingPolicyTest.App.class, properties = {
        // 排除 Redis 自动配置 → 无 StringRedisTemplate → 默认策略不装配 → 幂等切面无可用策略
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "guard.idempotent.fail-open=true"
})
class IdempotentMissingPolicyTest {

    @Autowired
    private App.IdemService service;

    @Test
    void missingPolicyFailsFastEvenWithFailOpen() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.handle("k1"));
        assertTrue(e.getMessage().contains("IdempotentPolicy"),
                "错误信息应包含 IdempotentPolicy 修复指引，实际: " + e.getMessage());
        // 业务方法从未执行：配置错误没有被打成 fail-open 放行
        assertEquals(0, App.IdemService.executed.get());
    }

    /**
     * 测试应用：只有幂等服务组件，不注册任何 IdempotentPolicy。
     */
    @SpringBootApplication
    static class App {

        @Component
        static class IdemService {
            // static：Bean 会被 CGLIB 代理（Objenesis 创建，不初始化实例字段），经代理读取实例字段为 null
            static final AtomicInteger executed = new AtomicInteger();

            @Idempotent(key = "#k")
            public String handle(String k) {
                executed.incrementAndGet();
                return "ok:" + k;
            }
        }
    }
}
