package io.github.biglv666.guard.itest.lock;

import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.idempotent.IdempotentRejectedException;
import io.github.biglv666.guard.lock.DistributedLock;
import io.github.biglv666.guard.lock.LockAcquireFallbackHandler;
import io.github.biglv666.guard.lock.LockAcquireInterruptedException;
import io.github.biglv666.guard.lock.LockAcquirePolicy;
import io.github.biglv666.guard.lock.LockAcquireTimeoutException;
import io.github.biglv666.guard.lock.LockTemplate;
import io.github.biglv666.guard.lock.LockType;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分布式锁模块集成测试（Testcontainers 真实 Redis + Redisson）：
 * 覆盖同 key 互斥、异 key 并行、异常释放、SKIP / CUSTOM 策略与 LockTemplate。
 * 测试应用独立成包，避免组件扫描互相污染。
 *
 * @author Guard Team
 * @since 0.1.0
 */
@Testcontainers
// 本测试只给 Redisson 配置了容器地址；幂等侧使用本地策略，避免误连 localhost:6379
@SpringBootTest(classes = LockIntegrationTest.App.class,
        properties = "guard.idempotent.store=local")
class LockIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("guard.test.redis.address",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @Autowired
    private App.LockService service;

    @Autowired
    private LockTemplate lockTemplate;

    @Test
    void sameKeyMutuallyExclusive() throws Exception {
        String key = "m-" + UUID.randomUUID();
        AtomicReference<Throwable> holderError = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try {
                service.slow(key, 800);
            } catch (Throwable t) {
                holderError.set(t);
            }
        });
        holder.start();
        Thread.sleep(200);
        // 持锁线程还剩约 600ms，等待 500ms 必然超时
        assertThrows(LockAcquireTimeoutException.class, () -> service.slow(key, 10));
        holder.join(5000);
        assertNull(holderError.get());
    }

    @Test
    void differentKeysRunInParallel() throws Exception {
        String a = "p-" + UUID.randomUUID();
        String b = "p-" + UUID.randomUUID();
        long start = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> service.slow(a, 400));
        pool.submit(() -> service.slow(b, 400));
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        long elapsed = System.currentTimeMillis() - start;
        // 若被串行化至少需要 800ms
        assertTrue(elapsed < 750, "不同 key 应并行执行，实际耗时 " + elapsed + "ms");
    }

    @Test
    void localAnnotatedLockWorks() {
        String key = "local-method-" + UUID.randomUUID();
        assertEquals("local:" + key, service.local(key, 1));
    }

    @Test
    void idempotentRejectsBeforeWaitingForLock() throws Exception {
        String key = "both-" + UUID.randomUUID();
        java.util.concurrent.atomic.AtomicReference<Throwable> holderError =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread holder = new Thread(() -> {
            try {
                service.both(key);
            } catch (Throwable t) {
                holderError.set(t);
            }
        });
        holder.start();
        // 留出足够时间让首次请求完成幂等占位与加锁
        Thread.sleep(500);

        // 幂等切面 @Order(0) 在锁切面 @Order(100) 之外：
        // 重复请求应立即被幂等拒绝，而不是先等待锁再超时
        assertThrows(IdempotentRejectedException.class, () -> service.both(key));
        holder.join(5000);
        assertNull(holderError.get(), "首次请求不应异常: " + holderError.get());
    }

    @Test
    void interruptedAcquireThrowsInterruptException() throws Exception {
        String key = "i-" + UUID.randomUUID();
        Thread holder = new Thread(() -> service.local(key, 1000));
        holder.start();
        Thread.sleep(200);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                service.local(key, 1);
            } catch (Throwable t) {
                error.set(t);
            }
        });
        waiter.start();
        // 等待线程已阻塞在锁等待上再中断
        Thread.sleep(100);
        waiter.interrupt();
        waiter.join(5000);
        holder.join(5000);

        // 中断与锁竞争超时语义不同：抛中断异常而非 LockAcquireTimeoutException
        assertTrue(error.get() instanceof LockAcquireInterruptedException,
                "等待被中断应抛 LockAcquireInterruptedException，实际: " + error.get());
        assertTrue(waiter.isInterrupted(), "中断标记应被恢复");
    }

    @Test
    void businessExceptionReleasesLock() {
        String key = "e-" + UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> service.failing(key));
        // 异常后锁已释放：再次获取无需等待，快速返回
        assertEquals("ok:" + key, service.slow(key, 10));
    }

    @Test
    void skipPolicyReturnsNullOnTimeout() throws Exception {
        String key = "s-" + UUID.randomUUID();
        Thread holder = new Thread(() -> service.slow(key, 1000));
        holder.start();
        Thread.sleep(200);
        assertNull(service.skip(key, 10));
        holder.join(5000);
    }

    @Test
    void customHandlerInvokedOnTimeout() throws Exception {
        String key = "c-" + UUID.randomUUID();
        Thread holder = new Thread(() -> service.slow(key, 1000));
        holder.start();
        Thread.sleep(200);
        assertEquals("fallback", service.custom(key));
        holder.join(5000);
    }

    @Test
    void lockTemplateExecuteAndTimeout() throws Exception {
        String key = "t-" + UUID.randomUUID();
        assertEquals("v", lockTemplate.withLock(key, () -> "v"));

        AtomicReference<Throwable> holderError = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try {
                // withLock 的第二个参数是等待获取的时间；持锁时长由业务 action 决定，这里睡眠模拟
                lockTemplate.withLock(key, 800, -1, TimeUnit.MILLISECONDS, () -> {
                    try {
                        Thread.sleep(600);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "held";
                });
            } catch (Throwable t) {
                holderError.set(t);
            }
        });
        holder.start();
        Thread.sleep(200);
        assertThrows(LockAcquireTimeoutException.class,
                () -> lockTemplate.withLock(key, 200, -1, TimeUnit.MILLISECONDS, () -> "x"));
        holder.join(5000);
        assertNull(holderError.get());
    }

    @Test
    void localLockTemplateProtectsOnlyLambdaBlock() throws Exception {
        String key = "local-" + UUID.randomUUID();
        AtomicInteger count = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> lockTemplate.withLocalLock(key, () -> {
            int value = count.get();
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            count.set(value + 1);
            return null;
        }));
        pool.submit(() -> lockTemplate.withLocalLock(key, () -> {
            int value = count.get();
            count.set(value + 1);
            return null;
        }));
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(2, count.get());
    }

    /**
     * 测试应用：注册连接容器的 RedissonClient.
     */
    @SpringBootApplication
    static class App {

        @Bean
        public RedissonClient redissonClient(@Value("${guard.test.redis.address}") String address) {
            Config config = new Config();
            config.useSingleServer().setAddress(address);
            return Redisson.create(config);
        }

        /**
         * 锁测试目标服务。
         */
        @Component
        static class LockService {

            @DistributedLock(key = "#k", waitTime = 500, timeUnit = TimeUnit.MILLISECONDS)
            public String slow(String k, long millis) {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "ok:" + k;
            }

            @DistributedLock(key = "#k", type = LockType.SYNCHRONIZED, waitTime = 300,
                    timeUnit = TimeUnit.MILLISECONDS)
            public String local(String k, long millis) {
                try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "local:" + k;
            }

            @DistributedLock(key = "#k", waitTime = 300, timeUnit = TimeUnit.MILLISECONDS,
                    acquirePolicy = LockAcquirePolicy.SKIP)
            public String skip(String k, long millis) {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "ok:" + k;
            }

            @DistributedLock(key = "#k", waitTime = 300, timeUnit = TimeUnit.MILLISECONDS,
                    acquirePolicy = LockAcquirePolicy.CUSTOM, handler = AppFallbackHandler.class)
            public String custom(String k) {
                return "ok:" + k;
            }

            @DistributedLock(key = "#k")
            public String failing(String k) {
                throw new IllegalStateException("boom");
            }

            @Idempotent(key = "#k", ttl = 10)
            @DistributedLock(key = "#k", waitTime = 100, timeUnit = TimeUnit.MILLISECONDS)
            public String both(String k) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "both:" + k;
            }
        }

        /**
         * 自定义回退处理器（注册为 Bean，验证容器 Bean 优先的解析路径）。
         */
        @Component
        static class AppFallbackHandler implements LockAcquireFallbackHandler {
            @Override
            public Object handle(LockAcquireFallbackHandler.LockAcquireContext context) {
                return "fallback";
            }
        }
    }
}

