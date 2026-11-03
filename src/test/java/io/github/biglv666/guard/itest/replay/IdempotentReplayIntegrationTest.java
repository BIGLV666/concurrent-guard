package io.github.biglv666.guard.itest.replay;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.idempotent.IdempotentMode;
import io.github.biglv666.guard.idempotent.IdempotentRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等结果重放（{@code mode = REPLAY}）集成测试（Testcontainers 真实 Redis）：
 * 覆盖正常重放（POJO 类型还原）、处理中重复拒绝、异常回滚组合、void 方法、TTL 过期。
 * 测试应用独立成包，避免组件扫描互相污染。
 *
 * @author Guard Team
 * @since 0.2.0
 */
@Testcontainers
// 本测试包含 REJECT + waitMillis 的运行时快速失败用例，关闭启动期校验以保留首次调用语义
@SpringBootTest(classes = IdempotentReplayIntegrationTest.App.class,
        properties = "guard.validation.enabled=false")
class IdempotentReplayIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private App.ReplayService service;

    @Test
    void duplicateReplaysFirstResultWithoutReexecution() {
        String key = "replay-" + UUID.randomUUID();
        App.OrderResult first = service.order(key, 7);
        assertEquals(new App.OrderResult(key, 7), first);

        // 窗口内重复请求：拿到首次结果，业务方法不再执行
        App.OrderResult second = service.order(key, 999);
        assertEquals(first, second);
        assertEquals(1, service.orderCalls.get());

        // 不同 key 正常执行
        service.order("other-" + key, 1);
        assertEquals(2, service.orderCalls.get());
    }

    @Test
    void inFlightDuplicateRejectedThenReplayedAfterCompletion() throws Exception {
        String key = "flight-" + UUID.randomUUID();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        service.beginSlow();
        try {
            Future<String> first = pool.submit(() -> service.slow(key));
            assertTrue(service.entered.await(5, TimeUnit.SECONDS));

            // 首个请求仍在处理中：重复请求按拒绝处理（不等待、不阻塞）
            assertThrows(IdempotentRejectedException.class, () -> service.slow(key));
            assertEquals(1, service.slowCalls.get());

            // 释放处理中标记，首个请求完成并保存结果
            service.finishSlow();
            assertEquals("slow:" + key, first.get(5, TimeUnit.SECONDS));
            // 处理完成后：同一窗口内的请求重放首次结果
            assertEquals("slow:" + key, service.slow(key));
            assertEquals(1, service.slowCalls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void exceptionWithRollbackAllowsRetryInReplayMode() {
        String key = "rb-" + UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> service.failingWithRollback(key));
        // 占位回滚后重试再次执行业务；成功后结果可重放
        assertEquals("ok:" + key, service.failingWithRollback(key));
        assertEquals(2, service.rollbackAttempts.get());
        assertEquals("ok:" + key, service.failingWithRollback(key));
        assertEquals(2, service.rollbackAttempts.get());
    }

    @Test
    void exceptionWithoutRollbackStillRejectedInReplayMode() {
        String key = "norb-" + UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> service.failingNoRollback(key));
        // 占位未回滚：重试被拒绝，业务方法不再执行（异常不重放）
        assertThrows(IdempotentRejectedException.class, () -> service.failingNoRollback(key));
        assertEquals(1, service.noRollbackAttempts.get());
    }

    @Test
    void voidMethodReplaysNull() {
        String key = "void-" + UUID.randomUUID();
        service.voidAction(key);
        service.voidAction(key);
        assertEquals(1, service.voidCalls.get());
    }

    @Test
    void replayExpiresWithTtl() throws Exception {
        String key = "ttl-" + UUID.randomUUID();
        assertEquals("ok:" + key, service.fastTtl(key));
        Thread.sleep(450);
        // TTL 已过：结果与占位一起消失，重新执行业务
        assertEquals("ok:" + key, service.fastTtl(key));
        assertEquals(2, service.fastTtlCalls.get());
    }

    @Test
    void slowResultReplayedFromSentinelWithinGraceWindow() throws Exception {
        String key = "grace-" + UUID.randomUUID();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        service.beginSlow();
        try {
            // 业务耗时 600ms 但占位 TTL 只有 200ms：首个请求执行期间占位必然过期
            Future<String> first = pool.submit(() -> service.slowTtl(key));
            assertTrue(service.entered.await(5, TimeUnit.SECONDS));

            // 等待占位过期（200ms TTL + 余量），再释放业务让其完成并写哨兵
            Thread.sleep(350);
            service.finishSlow();
            assertEquals("slow-ttl:" + key, first.get(5, TimeUnit.SECONDS));

            // 过期窗口内（默认哨兵保留 5 秒）：重复请求从哨兵重放结果，业务方法不重复执行
            assertEquals("slow-ttl:" + key, service.slowTtl(key));
            assertEquals(1, service.slowTtlCalls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void waitingDuplicateReplaysOnceFirstCompletes() throws Exception {
        String key = "wait-" + UUID.randomUUID();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        service.beginSlow();
        try {
            Future<String> first = pool.submit(() -> service.waitingSlow(key));
            assertTrue(service.entered.await(5, TimeUnit.SECONDS));

            // 300ms 后释放首个请求（独立线程：单线程池已被首个请求占用，不能排队）：
            // 主线程的重复请求（waitMillis = 2000）应在等待窗口内轮询到首次结果并直接返回
            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                service.finishSlow();
            });
            releaser.start();

            // 主线程立即发起重复请求：应阻塞在等待窗口内轮询，直至首个请求完成
            long start = System.currentTimeMillis();
            assertEquals("slow:" + key, service.waitingSlow(key));
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed >= 250, "重复请求应等待首个请求完成后重放，实际仅 " + elapsed + "ms");

            releaser.join(5000);
            assertEquals("slow:" + key, first.get(5, TimeUnit.SECONDS));
            assertEquals(1, service.waitingCalls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void waitingDuplicateTimesOutToReject() throws Exception {
        String key = "waitout-" + UUID.randomUUID();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        service.beginSlow();
        try {
            Future<String> first = pool.submit(() -> service.waitingSlow(key));
            assertTrue(service.entered.await(5, TimeUnit.SECONDS));

            // waitMillis = 200 小于首个请求的剩余处理时间：等待超时后按拒绝处理
            IdempotentRejectedException e = assertThrows(IdempotentRejectedException.class,
                    () -> service.shortWait(key));
            assertTrue(e.getRemainingTtlMillis() > 0,
                    "超时拒绝应携带剩余 TTL，实际: " + e.getRemainingTtlMillis());
            assertEquals(0, App.ReplayService.shortWaitCalls.get(), "超时后业务方法不应执行");
        } finally {
            service.finishSlow();
            pool.shutdownNow();
        }
    }

    @Test
    void rejectModeWithWaitMillisFailsFast() {
        // waitMillis 仅支持 REPLAY 模式：配置错误快速失败
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.rejectModeWithWait("x"));
        assertTrue(e.getMessage().contains("waitMillis"),
                "错误信息应包含 waitMillis 指引，实际: " + e.getMessage());
    }

    @Test
    void largePayloadReplayRoundTrip() {
        // 1MB 结果的完整保存与重放：不炸、不截断、业务只执行一次
        String key = "big-" + UUID.randomUUID();
        String expected = key + "x".repeat(1_000_000);
        assertEquals(expected, service.largePayload(key));
        assertEquals(expected, service.largePayload(key));
        assertEquals(1, App.ReplayService.largeCalls.get());
    }

    /**
     * 测试应用：组件扫描限定在本包，自动装配 Guard（默认 JacksonResultCodec）。
     */
    @SpringBootApplication
    static class App {

        /**
         * REPLAY 模式测试目标服务。
         */
        @Component
        static class ReplayService {
            // static：Bean 会被 CGLIB 代理（Objenesis 创建，不初始化实例字段），经代理读取实例字段为 null
            static final AtomicInteger orderCalls = new AtomicInteger();
            static final AtomicInteger slowCalls = new AtomicInteger();
            static final AtomicInteger rollbackAttempts = new AtomicInteger();
            static final AtomicInteger noRollbackAttempts = new AtomicInteger();
            static final AtomicInteger voidCalls = new AtomicInteger();
            static final AtomicInteger fastTtlCalls = new AtomicInteger();
            static final AtomicInteger slowTtlCalls = new AtomicInteger();
            static final AtomicInteger waitingCalls = new AtomicInteger();
            static final AtomicInteger shortWaitCalls = new AtomicInteger();
            static final AtomicInteger largeCalls = new AtomicInteger();

            static volatile CountDownLatch entered = new CountDownLatch(1);
            static volatile CountDownLatch processing = new CountDownLatch(1);

            void beginSlow() {
                entered = new CountDownLatch(1);
                processing = new CountDownLatch(1);
            }

            void finishSlow() {
                processing.countDown();
            }

            @Idempotent(key = "#orderId", mode = IdempotentMode.REPLAY, ttl = 60)
            public OrderResult order(String orderId, int seq) {
                orderCalls.incrementAndGet();
                return new OrderResult(orderId, seq);
            }

            @Idempotent(key = "#orderId", mode = IdempotentMode.REPLAY, ttl = 60)
            public String slow(String orderId) throws InterruptedException {
                slowCalls.incrementAndGet();
                entered.countDown();
                processing.await(5, TimeUnit.SECONDS);
                return "slow:" + orderId;
            }

            @Idempotent(key = "#orderId", mode = IdempotentMode.REPLAY)
            public String failingWithRollback(String orderId) {
                rollbackAttempts.incrementAndGet();
                if (rollbackAttempts.get() < 2) {
                    throw new IllegalStateException("boom");
                }
                return "ok:" + orderId;
            }

            @Idempotent(key = "#orderId", mode = IdempotentMode.REPLAY, rollbackOnException = false)
            public String failingNoRollback(String orderId) {
                noRollbackAttempts.incrementAndGet();
                throw new IllegalStateException("boom");
            }

            @Idempotent(key = "#orderId", mode = IdempotentMode.REPLAY, ttl = 60)
            public void voidAction(String orderId) {
                voidCalls.incrementAndGet();
            }

            @Idempotent(key = "#orderId", mode = IdempotentMode.REPLAY, ttl = 300, timeUnit = TimeUnit.MILLISECONDS)
            public String fastTtl(String orderId) {
                fastTtlCalls.incrementAndGet();
                return "ok:" + orderId;
            }

            @Idempotent(key = "#orderId", mode = IdempotentMode.REPLAY, ttl = 200, timeUnit = TimeUnit.MILLISECONDS)
            public String slowTtl(String orderId) throws InterruptedException {
                slowTtlCalls.incrementAndGet();
                entered.countDown();
                processing.await(5, TimeUnit.SECONDS);
                return "slow-ttl:" + orderId;
            }

            @Idempotent(key = "#orderId", mode = IdempotentMode.REPLAY, ttl = 60, waitMillis = 2000)
            public String waitingSlow(String orderId) throws InterruptedException {
                waitingCalls.incrementAndGet();
                entered.countDown();
                processing.await(5, TimeUnit.SECONDS);
                return "slow:" + orderId;
            }

            @Idempotent(key = "#orderId", mode = IdempotentMode.REPLAY, ttl = 60, waitMillis = 200)
            public String shortWait(String orderId) {
                shortWaitCalls.incrementAndGet();
                return "slow:" + orderId;
            }

            @Idempotent(key = "#orderId", waitMillis = 100)
            public String rejectModeWithWait(String orderId) {
                return "ok:" + orderId;
            }

            @Idempotent(key = "#orderId", mode = IdempotentMode.REPLAY, ttl = 60)
            public String largePayload(String orderId) {
                largeCalls.incrementAndGet();
                return orderId + "x".repeat(1_000_000);
            }
        }

        /**
         * 重放结果的 POJO 载荷：验证反序列化按方法返回类型还原。
         */
        static class OrderResult {
            final String orderId;
            final int seq;

            @JsonCreator
            OrderResult(@JsonProperty("orderId") String orderId, @JsonProperty("seq") int seq) {
                this.orderId = orderId;
                this.seq = seq;
            }

            public String getOrderId() {
                return orderId;
            }

            public int getSeq() {
                return seq;
            }

            @Override
            public boolean equals(Object o) {
                if (!(o instanceof OrderResult that)) {
                    return false;
                }
                return seq == that.seq && Objects.equals(orderId, that.orderId);
            }

            @Override
            public int hashCode() {
                return Objects.hash(orderId, seq);
            }
        }
    }
}
