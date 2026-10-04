package io.github.biglv666.guard.itest.localstore;

import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.idempotent.IdempotentMode;
import io.github.biglv666.guard.idempotent.IdempotentPolicy;
import io.github.biglv666.guard.idempotent.IdempotentRejectedException;
import io.github.biglv666.guard.idempotent.LocalIdempotentPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@code guard.idempotent.store=local} 端到端集成测试（排除 Redis 自动配置）：
 * 无 Redis 环境下进程内策略完整支撑幂等与 REPLAY 链路（含剩余 TTL）。
 * 测试应用独立成包，避免组件扫描互相污染。
 *
 * @author Guard Team
 * @since 0.2.1
 */
@SpringBootTest(classes = LocalIdempotentStoreTest.App.class, properties = {
        // 排除 Redis 自动配置，验证本地策略完全无 Redis 依赖
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "guard.idempotent.store=local"
})
class LocalIdempotentStoreTest {

    @Autowired
    private App.IdemService service;

    @Autowired
    private IdempotentPolicy policy;

    @Test
    void localPolicyIsAssembledAndBlocksDuplicates() {
        assertInstanceOf(LocalIdempotentPolicy.class, policy);

        String key = "local-" + System.nanoTime();
        assertEquals("ok:" + key, service.submit(key));
        IdempotentRejectedException e =
                assertThrows(IdempotentRejectedException.class, () -> service.submit(key));
        // 本地策略同样提供剩余 TTL
        assertTrue(e.getRemainingTtlMillis() > 0,
                "拒绝异常应携带剩余 TTL，实际: " + e.getRemainingTtlMillis());
        assertEquals(1, App.IdemService.submitCalls.get());
    }

    @Test
    void localReplayReturnsFirstResult() {
        String key = "local-replay-" + System.nanoTime();
        assertEquals("ok:" + key, service.replay(key));
        assertEquals("ok:" + key, service.replay(key));
        assertEquals(1, App.IdemService.replayCalls.get());
    }

    @Test
    void localRollbackAllowsRetry() {
        String key = "local-rb-" + System.nanoTime();
        assertThrows(IllegalStateException.class, () -> service.failing(key));
        assertEquals("ok:" + key, service.failing(key));
        assertEquals(2, App.IdemService.failCalls.get());
    }

    /**
     * 测试应用：组件扫描限定在本包，store=local 装配本地策略。
     */
    @SpringBootApplication
    static class App {

        @Component
        static class IdemService {
            // static：Bean 会被 CGLIB 代理（Objenesis 创建，不初始化实例字段），经代理读取实例字段为 null
            static final AtomicInteger submitCalls = new AtomicInteger();
            static final AtomicInteger replayCalls = new AtomicInteger();
            static final AtomicInteger failCalls = new AtomicInteger();

            @Idempotent(key = "#k")
            public String submit(String k) {
                submitCalls.incrementAndGet();
                return "ok:" + k;
            }

            @Idempotent(key = "#k", mode = IdempotentMode.REPLAY)
            public String replay(String k) {
                replayCalls.incrementAndGet();
                return "ok:" + k;
            }

            @Idempotent(key = "#k")
            public String failing(String k) {
                failCalls.incrementAndGet();
                if (failCalls.get() < 2) {
                    throw new IllegalStateException("boom");
                }
                return "ok:" + k;
            }
        }
    }
}
