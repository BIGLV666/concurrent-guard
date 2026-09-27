package io.github.biglv666.guard.itest.idem;

import io.github.biglv666.guard.event.GuardEventType;
import io.github.biglv666.guard.event.GuardRejectedEvent;
import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.idempotent.IdempotentRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等模块集成测试（Testcontainers 真实 Redis）：
 * 覆盖占位互斥、TTL 过期、异常回滚、方法级 key、并发唯一成功、Redis 占位值与拒绝事件。
 * 测试应用独立成包，避免组件扫描互相污染。
 *
 * @author Guard Team
 * @since 0.1.0
 */
@Testcontainers
@SpringBootTest(classes = IdempotentIntegrationTest.App.class)
class IdempotentIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private App.IdemService service;

    @Autowired
    private App.EventCollector collector;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearEvents() {
        collector.events.clear();
    }

    @Test
    void duplicateRejectedThenAllowedAfterTtl() throws Exception {
        String key = "ttl-" + UUID.randomUUID();
        assertEquals("ok:" + key, service.fastTtl(key));

        IdempotentRejectedException rejected =
                assertThrows(IdempotentRejectedException.class, () -> service.fastTtl(key));
        assertNotNull(rejected.getKey());
        assertEquals(1, collector.count(GuardEventType.IDEMPOTENT_REJECTED));

        Thread.sleep(450);
        assertEquals("ok:" + key, service.fastTtl(key));
    }

    @Test
    void concurrentSameKeyOnlyOneSuccess() throws Exception {
        String key = "conc-" + UUID.randomUUID();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    service.submit(key);
                    success.incrementAndGet();
                } catch (IdempotentRejectedException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, success.get());
        assertEquals(threads - 1, rejected.get());
    }

    @Test
    void differentKeysAllSucceed() {
        String a = "diff-" + UUID.randomUUID();
        String b = "diff-" + UUID.randomUUID();
        assertEquals("ok:" + a, service.submit(a));
        assertEquals("ok:" + b, service.submit(b));
    }

    @Test
    void businessExceptionReleasesKeyForRetry() {
        String key = "retry-" + UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> service.failingWithRollback(key));
        // 占位已回滚：第二次调用应再次进入业务方法（而非被幂等拒绝）
        assertThrows(IllegalStateException.class, () -> service.failingWithRollback(key));
        assertEquals(2, service.rollbackAttempts.get());
    }

    @Test
    void businessExceptionWithoutRollbackKeepsKey() {
        String key = "noretry-" + UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> service.failingNoRollback(key));
        // 占位未回滚：TTL 窗口内重试被幂等拒绝，业务方法不再执行
        assertThrows(IdempotentRejectedException.class, () -> service.failingNoRollback(key));
        assertEquals(1, service.noRollbackAttempts.get());
    }

    @Test
    void methodLevelKeyBlocksSecondCall() {
        assertEquals("ok", service.methodLevel("x"));
        assertThrows(IdempotentRejectedException.class, () -> service.methodLevel("y"));
        assertEquals(1, service.methodLevelCalls.get());
    }

    @Test
    void placeholderValueStoredInRedis() {
        String key = "redis-" + UUID.randomUUID();
        service.submit(key);
        assertNotNull(redisTemplate.opsForValue().get("guard:idempotent:" + key));
    }

    @Test
    void zeroTtlFailsFastAsConfigError() {
        // ttl <= 0 属于配置错误：快速失败抛 IllegalStateException，不进降级路径、不执行业务
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.zeroTtl("z"));
        assertTrue(e.getMessage().contains("ttl"),
                "错误信息应包含 ttl 修复指引，实际: " + e.getMessage());
        assertEquals(0, App.IdemService.zeroTtlCalls.get());
    }

    /**
     * 测试应用：组件扫描限定在本包，自动装配 Guard。
     */
    @SpringBootApplication
    static class App {

        /**
         * 幂等测试目标服务。
         */
        @Component
        static class IdemService {
            // static：Bean 会被 CGLIB 代理（Objenesis 创建，不初始化实例字段），经代理读取实例字段为 null
            static final AtomicInteger rollbackAttempts = new AtomicInteger();
            static final AtomicInteger noRollbackAttempts = new AtomicInteger();
            static final AtomicInteger methodLevelCalls = new AtomicInteger();
            static final AtomicInteger zeroTtlCalls = new AtomicInteger();

            @Idempotent(key = "#orderId")
            public String submit(String orderId) {
                return "ok:" + orderId;
            }

            @Idempotent(key = "#orderId", ttl = 300, timeUnit = TimeUnit.MILLISECONDS)
            public String fastTtl(String orderId) {
                return "ok:" + orderId;
            }

            @Idempotent(key = "#orderId")
            public String failingWithRollback(String orderId) {
                rollbackAttempts.incrementAndGet();
                throw new IllegalStateException("boom");
            }

            @Idempotent(key = "#orderId", rollbackOnException = false)
            public String failingNoRollback(String orderId) {
                noRollbackAttempts.incrementAndGet();
                throw new IllegalStateException("boom");
            }

            @Idempotent
            public String methodLevel(String arg) {
                methodLevelCalls.incrementAndGet();
                return "ok";
            }

            @Idempotent(key = "#orderId", ttl = 0)
            public String zeroTtl(String orderId) {
                zeroTtlCalls.incrementAndGet();
                return "ok:" + orderId;
            }
        }

        /**
         * Guard 拒绝事件收集器，用于断言事件 SPI。
         * （GuardRejectedEvent 是普通载荷事件，用 @EventListener 注解订阅而非实现接口）
         */
        @Component
        static class EventCollector {
            final List<GuardRejectedEvent> events = Collections.synchronizedList(new ArrayList<>());

            @EventListener
            public void on(GuardRejectedEvent event) {
                events.add(event);
            }

            long count(GuardEventType type) {
                return events.stream().filter(e -> e.getType() == type).count();
            }
        }
    }
}
