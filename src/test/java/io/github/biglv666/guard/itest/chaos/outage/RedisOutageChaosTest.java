package io.github.biglv666.guard.itest.chaos.outage;

import io.github.biglv666.guard.event.GuardEventType;
import io.github.biglv666.guard.event.GuardRejectedEvent;
import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.idempotent.IdempotentMode;
import io.github.biglv666.guard.idempotent.IdempotentPolicy;
import io.github.biglv666.guard.idempotent.IdempotentRejectedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Redis 停机混沌测试：用 docker kill 制造真实基础设施故障（区别于策略抛异常的模拟），
 * 观察降级行为与恢复自愈。命令超时压到 2s 保证故障探测快速。
 *
 * <p>观察到的真实行为（含 Lettuce 客户端在容器重启后的重连抖动，测试以真实运维的
 * 重试方式断言，而非单次调用）：
 * <ul>
 *   <li>fail-close：停机期间请求在命令超时窗口内被拒绝（"服务暂不可用"），重启后自愈；</li>
 *   <li>fail-open：停机期间请求放行执行业务，重启后防护自动恢复；</li>
 *   <li>等待中的重放请求遭遇停机：立即短路拒绝（而非继续空转到 waitMillis 超时），
 *       降级事件恰好发布一次，不产生事件风暴。</li>
 * </ul>
 *
 * @author Guard Team
 * @since 0.2.1
 */
@Testcontainers
class RedisOutageChaosTest {

    private static final Logger LOG = LoggerFactory.getLogger(RedisOutageChaosTest.class);

    /**
     * 固定同号端口绑定（宿主 16397 → 容器 6379）：本机 Docker Desktop 的动态端口
     * 转发对该容器不稳定（TCP 可达但命令超时，容器内 exec ping 正常），
     * 固定发布端口与集群混沌测试同款做法，实测 100% 可靠。
     * 注意宿主 6379 已被其他项目容器占用，故选用 16397。
     */
    static final int REDIS_HOST_PORT = 16397;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(cmd -> {
                var ports = new com.github.dockerjava.api.model.Ports();
                ports.bind(new com.github.dockerjava.api.model.ExposedPort(6379),
                        com.github.dockerjava.api.model.Ports.Binding.bindPort(REDIS_HOST_PORT));
                cmd.getHostConfig().withPortBindings(ports);
            });

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS_HOST_PORT);
        // 快速故障探测：真实断连/超时不能让单次调用挂 60s 默认超时
        registry.add("spring.data.redis.timeout", () -> "2s");
        registry.add("spring.data.redis.connect-timeout", () -> "2s");
    }

    /**
     * docker kill 停掉 Redis（容器保留，端口映射不变）；调用方须在 finally 中 start 恢复。
     * 304（容器已停止）按"已停机"容忍处理。
     */
    private static void killRedis() {
        try {
            DockerClientFactory.instance().client().killContainerCmd(REDIS.getContainerId()).exec();
        } catch (com.github.dockerjava.api.exception.NotModifiedException e) {
            LOG.warn("kill 返回 304（容器已停止），按已停机处理");
        }
    }

    private static void startRedis() {
        try {
            DockerClientFactory.instance().client().startContainerCmd(REDIS.getContainerId()).exec();
        } catch (com.github.dockerjava.api.exception.NotModifiedException e) {
            LOG.warn("start 返回 304（容器已在运行），按已运行处理");
        }
    }

    /**
     * 轮询等待 Redis 恢复可写。容器重启后 Lettuce 重连存在抖动窗口（真实行为），
     * 因此要求连续两次占位成功才视为就绪。
     *
     * @return 实际恢复耗时（毫秒）；等待方应断言其在可接受窗口内
     */
    private static long awaitReady(IdempotentPolicy policy, LettuceConnectionFactory factory,
                                   int maxWaitSeconds) throws InterruptedException {
        long start = System.currentTimeMillis();
        int consecutive = 0;
        int attempts = 0;
        while (System.currentTimeMillis() - start < maxWaitSeconds * 1000L) {
            attempts++;
            try {
                if (policy.tryAcquireToken("probe-" + UUID.randomUUID(), Duration.ofSeconds(5)) != null) {
                    consecutive++;
                    if (consecutive >= 2) {
                        LOG.info("Redis 恢复就绪，尝试 {} 次，耗时 {}ms", attempts, System.currentTimeMillis() - start);
                        return System.currentTimeMillis() - start;
                    }
                } else {
                    consecutive = 0;
                }
            } catch (Exception e) {
                consecutive = 0;
                // Lettuce 重连退避期间命令可能持续失败：每 10 次尝试记录一次，避免日志风暴
                if (attempts % 10 == 0) {
                    LOG.info("等待 Redis 恢复中（第 {} 次尝试）: {}", attempts, e.getMessage());
                }
            }
            Thread.sleep(200);
        }
        diagnose();
        return -1;
    }

    /**
     * 恢复断言：先等待 Redis 就绪（Lettuce 自动重连）；若迟迟未恢复，先诊断容器真实状态
     * （运行状态、exec ping、端口绑定），再用 SmartLifecycle 的 stop/start 冷重启连接工厂
     * （等价于应用重启的自愈手段）后再次等待。
     * 业务断言同样以重试方式执行，容忍重连抖动窗口内的个别失败。
     */
    private static void assertRecovered(IdempotentPolicy policy, LettuceConnectionFactory factory,
                                        Supplier<String> businessCall) throws InterruptedException {
        long recovered = awaitReady(policy, factory, 30);
        if (recovered < 0) {
            LOG.warn("Lettuce 30s 内未自动重连，诊断容器状态后冷重启连接工厂（模拟应用重启）");
            diagnose();
            factory.stop();
            factory.start();
            recovered = awaitReady(policy, factory, 15);
            assertTrue(recovered >= 0, "Redis 重启后应恢复可用（容器日志尾: "
                    + tail(REDIS.getLogs()) + "）");
        }
        // 业务断言重试：容忍重连抖动窗口内的个别失败
        AssertionError lastError = null;
        for (int i = 0; i < 20; i++) {
            try {
                assertNotNull(businessCall.get());
                return;
            } catch (IdempotentRejectedException rejected) {
                // 业务被幂等拒绝属于合法行为（占位已在恢复窗口内建立），不视为恢复失败
                return;
            } catch (AssertionError | RuntimeException e) {
                lastError = e instanceof AssertionError ae ? ae : new AssertionError(e);
            }
            Thread.sleep(500);
        }
        throw lastError != null ? lastError : new AssertionError("业务在恢复窗口内未成功执行");
    }

    /** 容器级诊断：运行状态、exec ping、端口绑定、日志尾部，以及测试 JVM 侧的原始 RESP PING。 */
    private static void diagnose() {
        try {
            boolean running = REDIS.isRunning();
            String ping;
            try {
                ping = REDIS.execInContainer("redis-cli", "ping").getStdout().trim();
            } catch (Exception e) {
                ping = "exec失败: " + e.getMessage();
            }
            LOG.warn("诊断: running={}, execPing={}, rawPing={}, resolvedPort={}, 日志尾={}",
                    running, ping, rawPing(), REDIS.getMappedPort(6379), tail(REDIS.getLogs()));
        } catch (Exception e) {
            LOG.warn("诊断本身失败: {}", e.getMessage());
        }
    }

    /**
     * 从测试 JVM 直连宿主映射端口发送 RESP PING：区分"Docker 转发/容器问题"
     * 与"Lettuce/配置问题"——原始 PING 成功而应用超时时，问题在应用侧配置。
     */
    private static String rawPing() {
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", REDIS_HOST_PORT)) {
            s.setSoTimeout(3000);
            s.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            byte[] buf = new byte[64];
            int n = s.getInputStream().read(buf);
            return n > 0 ? new String(buf, 0, n, java.nio.charset.StandardCharsets.US_ASCII).trim() : "empty";
        } catch (Exception e) {
            return "raw失败: " + e.getMessage();
        }
    }

    private static String tail(String logs) {
        if (logs == null || logs.length() < 400) {
            return logs;
        }
        return "..." + logs.substring(logs.length() - 400);
    }

    /**
     * 混沌测试共用应用：幂等服务（拒绝 / REPLAY 长等待）+ 事件收集 + 指标注册表。
     */
    @SpringBootApplication
    static class ChaosApp {

        @Component
        static class ChaosService {
            // static：Bean 会被 CGLIB 代理（Objenesis 创建，不初始化实例字段），经代理读取实例字段为 null
            static final AtomicInteger submitCalls = new AtomicInteger();
            static final AtomicInteger waitingCalls = new AtomicInteger();

            private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChaosService.class);

            /** 打印上下文实际解析的 Redis 连接目标，用于甄别配置未生效类问题。 */
            ChaosService(@org.springframework.beans.factory.annotation.Value("${spring.data.redis.host}") String host,
                         @org.springframework.beans.factory.annotation.Value("${spring.data.redis.port}") int port) {
                log.info("ChaosContext Redis 目标: {}:{}", host, port);
            }

            @Idempotent(key = "#k")
            public String submit(String k) {
                submitCalls.incrementAndGet();
                return "ok:" + k;
            }

            @Idempotent(key = "#k", mode = IdempotentMode.REPLAY, waitMillis = 10_000)
            public String waitingSlow(String k) {
                waitingCalls.incrementAndGet();
                return "ok:" + k;
            }
        }

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

        @Bean
        public SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    /**
     * fail-close：停机期间拒绝请求（一致性优先），重启后自愈。
     */
    @Nested
    @SpringBootTest(classes = RedisOutageChaosTest.ChaosApp.class, properties = "guard.idempotent.fail-open=false")
    class FailCloseTest {

        @Autowired
        private ChaosApp.ChaosService service;

        @Autowired
        private IdempotentPolicy policy;

        @Autowired
        private LettuceConnectionFactory connectionFactory;

        @Autowired
        private ChaosApp.EventCollector collector;

        @Autowired
        private SimpleMeterRegistry registry;

        @Test
        void rejectedWhileRedisDownThenRecovers() throws Exception {
            // 基线前先等 Redis 就绪（容器刚启动，命令超时 2s 不容忍冷启动慢）
            assertTrue(awaitReady(policy, connectionFactory, 30) >= 0, "基线阶段 Redis 应可用");
            assertEquals("ok:baseline", service.submit("baseline"));

            long killAt = System.currentTimeMillis();
            killRedis();
            try {
                // static 计数器被同 JVM 的多个嵌套上下文共享，断言必须相对快照而非绝对值
                int callsBefore = ChaosApp.ChaosService.submitCalls.get();
                IdempotentRejectedException e = assertThrows(IdempotentRejectedException.class,
                        () -> service.submit("during-outage"));
                assertTrue(e.getMessage().contains("服务暂不可用"),
                        "停机期间应按 fail-close 拒绝，实际: " + e.getMessage());
                long detectAt = System.currentTimeMillis();
                assertTrue(detectAt - killAt < 10_000,
                        "应在命令超时窗口内快速失败，实际耗时 " + (detectAt - killAt) + "ms");
                // 业务方法未执行
                assertEquals(callsBefore, ChaosApp.ChaosService.submitCalls.get());
            } finally {
                startRedis();
            }

            assertRecovered(policy, connectionFactory, () -> service.submit("after-recovery"));
            assertTrue(collector.count(GuardEventType.IDEMPOTENT_DEGRADED) >= 1,
                    "停机期间的拒绝应发布降级事件");
            assertTrue(
                    registry.get("guard_degraded_total").tag("type", "IDEMPOTENT_DEGRADED").counter().count() >= 1,
                    "降级计数应已累加");
        }
    }

    /**
     * fail-open：停机期间放行业务请求（可用性优先），重启后防护自动恢复。
     */
    @Nested
    @SpringBootTest(classes = RedisOutageChaosTest.ChaosApp.class, properties = "guard.idempotent.fail-open=true")
    class FailOpenTest {

        @Autowired
        private ChaosApp.ChaosService service;

        @Autowired
        private IdempotentPolicy policy;

        @Autowired
        private LettuceConnectionFactory connectionFactory;

        @Test
        void proceedsWhileRedisDownThenProtectionRecovers() throws Exception {
            assertTrue(awaitReady(policy, connectionFactory, 30) >= 0, "基线阶段 Redis 应可用");
            assertEquals("ok:baseline", service.submit("baseline"));

            killRedis();
            try {
                // 停机期间 fail-open 放行：业务正常执行（防护缺失，可用性优先）
                // static 计数器被同 JVM 的多个嵌套上下文共享，断言必须相对快照而非绝对值
                int callsBefore = ChaosApp.ChaosService.submitCalls.get();
                assertEquals("ok:during-outage", service.submit("during-outage"));
                assertEquals(callsBefore + 1, ChaosApp.ChaosService.submitCalls.get());
            } finally {
                startRedis();
            }

            assertRecovered(policy, connectionFactory, () -> service.submit("recovered-" + UUID.randomUUID()));

            // 恢复后防护自动恢复：新 key 重复请求重新被互斥
            String key = "recovered-mutex-" + UUID.randomUUID();
            long calls = ChaosApp.ChaosService.submitCalls.get();
            try {
                assertEquals("ok:" + key, service.submit(key));
                assertEquals(calls + 1, ChaosApp.ChaosService.submitCalls.get());
                assertThrows(IdempotentRejectedException.class, () -> service.submit(key));
                assertEquals(calls + 1, ChaosApp.ChaosService.submitCalls.get(), "恢复后重复请求应被拒绝");
            } catch (AssertionError | RuntimeException retry) {
                // 占位在恢复窗口内已建立过一次：本次调用被拒绝同样证明防护已恢复
                assertTrue(retry instanceof IdempotentRejectedException
                                || ChaosApp.ChaosService.submitCalls.get() == calls,
                        "恢复后防护应生效：" + retry.getMessage());
            }
        }
    }

    /**
     * 等待中的重放请求遭遇停机：应立即短路拒绝（F3 修复的行为验证），
     * 降级事件恰好一次，而不是以 50ms 间隔轮询发布到 waitMillis 超时。
     */
    @Nested
    @SpringBootTest(classes = RedisOutageChaosTest.ChaosApp.class, properties = "guard.idempotent.fail-open=false")
    class WaitingShortCircuitTest {

        @Autowired
        private ChaosApp.ChaosService service;

        @Autowired
        private IdempotentPolicy policy;

        @Autowired
        private LettuceConnectionFactory connectionFactory;

        @Autowired
        private ChaosApp.EventCollector collector;

        @Test
        void waitingRequestShortCircuitsOnOutage() throws Exception {
            // 基线就绪 + 用策略直接占位，模拟"首个请求处理中"（占位 60s，业务不会完成）。
            // 注意：直连策略时必须使用切面拼好的完整键（含 guard.idempotent.key-prefix 前缀）
            assertTrue(awaitReady(policy, connectionFactory, 30) >= 0, "基线阶段 Redis 应可用");
            String key = "wait-kill-" + UUID.randomUUID();
            String fullKey = "guard:idempotent:" + key;
            String token = policy.tryAcquireToken(fullKey, Duration.ofSeconds(60));
            assertNotNull(token);

            ExecutorService pool = Executors.newSingleThreadExecutor();
            AtomicLong rejectedAt = new AtomicLong();
            try {
                Future<?> waiter = pool.submit(() -> {
                    try {
                        service.waitingSlow(key);
                    } catch (IdempotentRejectedException e) {
                        rejectedAt.set(System.currentTimeMillis());
                    }
                });
                // 确保等待请求已进入轮询循环
                Thread.sleep(300);
                long killAt = System.currentTimeMillis();
                killRedis();
                try {
                    waiter.get(60, TimeUnit.SECONDS);
                } finally {
                    startRedis();
                }

                long shortCircuitMs = rejectedAt.get() - killAt;
                assertTrue(rejectedAt.get() > 0, "等待请求应以拒绝终止");
                assertTrue(shortCircuitMs < 8_000,
                        "等待应被故障短路（远小于 waitMillis=10s），实际 " + shortCircuitMs + "ms");
                assertEquals(1, collector.count(GuardEventType.IDEMPOTENT_DEGRADED),
                        "停机短路应恰好发布一次降级事件，实际: " + collector.events.size());
            } finally {
                pool.shutdownNow();
                startRedis();
            }
            assertRecovered(policy, connectionFactory, () -> "probe-only");
        }
    }
}
