package io.github.biglv666.guard.itest.chaos.network;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.idempotent.IdempotentPolicy;
import io.github.biglv666.guard.idempotent.IdempotentRejectedException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网络故障混沌测试（Toxiproxy 真实代理）：应用与 Redis 之间插入代理，
 * 切断连接 / 注入超高延迟，观察真实网络层故障下的降级行为与恢复。
 *
 * <p>观察到的真实行为：
 * <ul>
 *   <li>断网（connection cut）：fail-close 在命令超时窗口内拒绝；fail-open 放行；恢复后自愈；</li>
 *   <li>高延迟（300ms，低于命令超时）：功能不降级，只是变慢（验证代理路径不误伤正常流量）。</li>
 * </ul>
 *
 * @author Guard Team
 * @since 0.2.1
 */
@Testcontainers
class NetworkFaultChaosTest {

    /**
     * 共享网络：Redis 与 Toxiproxy 同网，代理上游用网络别名寻址。
     * Network 未实现 Startable，不能挂 @Container，改为懒启动 + @AfterAll 关闭。
     */
    static final org.testcontainers.containers.Network NETWORK = org.testcontainers.containers.Network.newNetwork();

    @org.junit.jupiter.api.AfterAll
    static void closeNetwork() {
        NETWORK.close();
    }

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withNetwork(NETWORK)
            .withNetworkAliases("chaos-redis")
            .withExposedPorts(6379);

    @Container
    static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0")
                    .asCompatibleSubstituteFor("shopify/toxiproxy"))
            .withNetwork(NETWORK);

    private static ToxiproxyContainer.ContainerProxy proxy;

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        // 上游使用共享网络内的别名（从 toxiproxy 容器视角可达）；
        // 宿主映射端口从代理容器内不可达（Docker Desktop NAT）
        proxy = TOXIPROXY.getProxy("chaos-redis", 6379);
        registry.add("spring.data.redis.host", proxy::getContainerIpAddress);
        registry.add("spring.data.redis.port", proxy::getProxyPort);
        registry.add("spring.data.redis.timeout", () -> "1s");
        registry.add("spring.data.redis.connect-timeout", () -> "1s");
    }

    /**
     * 混沌测试共用应用。
     */
    @SpringBootApplication
    static class ChaosApp {

        @Component
        static class ChaosService {
            // static：Bean 会被 CGLIB 代理（Objenesis 创建，不初始化实例字段），经代理读取实例字段为 null
            static final java.util.concurrent.atomic.AtomicInteger submitCalls = new java.util.concurrent.atomic.AtomicInteger();

            @Idempotent(key = "#k")
            public String submit(String k) {
                submitCalls.incrementAndGet();
                return "ok:" + k;
            }
        }
    }

    /**
     * fail-close：断网期间拒绝，恢复后自愈。
     */
    @Nested
    @SpringBootTest(classes = NetworkFaultChaosTest.ChaosApp.class, properties = "guard.idempotent.fail-open=false")
    class FailCloseTest {

        @Autowired
        private ChaosApp.ChaosService service;

        @Autowired
        private IdempotentPolicy policy;

        @Test
        void recoversAfterNetworkCut() {
            assertEquals("ok:before-cut", service.submit("before-cut"));

            proxy.setConnectionCut(true);
            try {
                // static 计数器被同 JVM 的多个嵌套上下文共享，断言必须相对快照而非绝对值
                int callsBefore = ChaosApp.ChaosService.submitCalls.get();
                long start = System.currentTimeMillis();
                IdempotentRejectedException e = assertThrows(IdempotentRejectedException.class,
                        () -> service.submit("during-cut"));
                assertTrue(e.getMessage().contains("服务暂不可用"));
                assertTrue(System.currentTimeMillis() - start < 10_000, "断网应在超时窗口内快速失败");
                assertEquals(callsBefore, ChaosApp.ChaosService.submitCalls.get());
            } finally {
                proxy.setConnectionCut(false);
            }

            // 恢复后自愈（Lettuce 自动重连）
            String probe = "probe-" + UUID.randomUUID();
            boolean ready = false;
            for (int i = 0; i < 50 && !ready; i++) {
                try {
                    ready = policy.tryAcquireToken(probe + i, Duration.ofSeconds(5)) != null;
                } catch (Exception ignored) {
                    // 重连窗口
                }
                if (!ready) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            assertTrue(ready, "网络恢复后应可重新占位");
            assertEquals("ok:after-recovery", service.submit("after-recovery"));
        }
    }

    /**
     * 高延迟低于命令超时：功能不降级（Latency toxic 不应造成误伤）。
     */
    @Nested
    @SpringBootTest(classes = NetworkFaultChaosTest.ChaosApp.class, properties = "guard.idempotent.fail-open=false")
    class LatencyTest {

        @Autowired
        private ChaosApp.ChaosService service;

        @Test
        void latencyBelowTimeoutDoesNotBreakProtection() throws Exception {
            proxy.toxics().latency("lat", ToxicDirection.DOWNSTREAM, 300).setToxicity(1.0f);
            try {
                String key = "lat-" + UUID.randomUUID();
                assertEquals("ok:" + key, service.submit(key));
                assertThrows(IdempotentRejectedException.class, () -> service.submit(key));
                assertEquals(1, ChaosApp.ChaosService.submitCalls.get());
            } finally {
                proxy.toxics().get("lat").remove();
            }
        }
    }
}
