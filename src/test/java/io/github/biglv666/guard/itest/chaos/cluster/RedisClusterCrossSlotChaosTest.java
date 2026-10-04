package io.github.biglv666.guard.itest.chaos.cluster;

import io.github.biglv666.guard.idempotent.RedisSetNxIdempotentPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Redis Cluster 混沌测试（单容器 3 主节点，节点以 {@code host.docker.internal}
 * + 固定同号端口宣告，绕开 Docker Desktop 的端口 NAT）：
 * <ol>
 *   <li><b>故意制造 CROSSSLOT</b>：用 0.2.1 之前的哨兵键命名（前缀拼接，与主键不同 slot）
 *       执行多键 Lua 脚本，观察集群真实抛出 CROSSSLOT 错误——
 *       这正是此前单机容器测试无法暴露的静默失效根因；</li>
 *   <li>验证当前实现的 hash-tag 哨兵键（{@code {主键}:result}）与主键同 slot，
 *       多键 Lua 与 MGET 在集群下正常工作。</li>
 * </ol>
 *
 * @author Guard Team
 * @since 0.2.1
 */
@Testcontainers
class RedisClusterCrossSlotChaosTest {

    private static final int P1 = 7100;
    private static final int P2 = 7101;
    private static final int P3 = 7102;

    /**
     * 三个节点运行在同一容器（共享回环与网络命名空间），宣告 {@code 127.0.0.1:710x}：
     * 节点间经容器内回环互通，宿主经固定同号端口绑定（7100-7102）以相同地址访问，
     * Windows/macOS 的 NAT 限制由此绕开。
     */
    private static final String STARTUP_SCRIPT = """
            redis-server --port 7100 --cluster-enabled yes --cluster-config-file /tmp/nodes-7100.conf --cluster-node-timeout 2000 --cluster-announce-ip 127.0.0.1 --cluster-announce-port 7100 --cluster-announce-bus-port 17100 --save '' &
            redis-server --port 7101 --cluster-enabled yes --cluster-config-file /tmp/nodes-7101.conf --cluster-node-timeout 2000 --cluster-announce-ip 127.0.0.1 --cluster-announce-port 7101 --cluster-announce-bus-port 17101 --save '' &
            redis-server --port 7102 --cluster-enabled yes --cluster-config-file /tmp/nodes-7102.conf --cluster-node-timeout 2000 --cluster-announce-ip 127.0.0.1 --cluster-announce-port 7102 --cluster-announce-bus-port 17102 --save '' &
            sleep 1
            for i in $(seq 1 60); do
              redis-cli --cluster create 127.0.0.1:7100 127.0.0.1:7101 127.0.0.1:7102 --cluster-yes > /tmp/create.log 2>&1
              redis-cli -p 7100 cluster info 2>/dev/null | grep -q cluster_state:ok && break
              sleep 1
            done
            if redis-cli -p 7100 cluster info 2>/dev/null | grep -q cluster_state:ok; then echo CLUSTER_OK; fi
            tail -f /dev/null
            """;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> CLUSTER = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withCommand("sh", "-c", STARTUP_SCRIPT)
            .waitingFor(Wait.forLogMessage(".*CLUSTER_OK.*", 1))
            .withCreateContainerCmdModifier(cmd -> {
                var ports = new com.github.dockerjava.api.model.Ports();
                for (int p : new int[]{P1, P2, P3, 17100, 17101, 17102}) {
                    ports.bind(new com.github.dockerjava.api.model.ExposedPort(p),
                            com.github.dockerjava.api.model.Ports.Binding.bindPort(p));
                }
                cmd.getHostConfig().withPortBindings(ports);
            });

    /**
     * 直接用原始键列表执行与 {@link RedisSetNxIdempotentPolicy} 同款的结果保存脚本。
     */
    private static final RedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('get', KEYS[1]) "
                    + "if current == ARGV[1] then "
                    + "redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3]) return 1 end "
                    + "if not current then "
                    + "redis.call('set', KEYS[2], ARGV[2], 'PX', ARGV[4]) return 2 end "
                    + "return 0", Long.class);

    @Test
    void crossSlotObservedWithLegacyNamingAndHashTagFixWorks() {
        // 宿主连接固定绑定的 7100；节点拓扑宣告 host.docker.internal:710x（宿主可解析可达）
        String seed = CLUSTER.getHost() + ":" + P1;
        RedisClusterConfiguration config = new RedisClusterConfiguration(java.util.List.of(seed));
        config.setMaxRedirects(3);

        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        try {
            factory.afterPropertiesSet();
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();

            RedisSetNxIdempotentPolicy policy = new RedisSetNxIdempotentPolicy(template, Duration.ofSeconds(10));
            String key = "clu-" + UUID.randomUUID();

            // 1) 正常占位：单键操作在集群下无障碍
            String token = policy.tryAcquireToken(key, Duration.ofSeconds(10));
            assertNotNull(token);

            // 2) 故意制造异常：旧版哨兵键命名（前缀拼接 → 与主键不同 slot）执行多键 Lua
            String legacySentinel = "guard:idempotent:result:" + key;
            RuntimeException crossSlot = assertThrows(RuntimeException.class,
                    () -> template.execute(SAVE_SCRIPT, java.util.List.of(key, legacySentinel),
                            token, "{\"legacy\":true}", "10000", "5000"));
            // Spring 将 Redis 集群错误包装为 RedisSystemException("Error in execution")，
            // CROSSSLOT 细节在 cause 链中
            StringBuilder messages = new StringBuilder();
            for (Throwable t = crossSlot; t != null; t = t.getCause()) {
                messages.append(t.getMessage()).append(" | ");
            }
            assertTrue(messages.toString().contains("CROSSSLOT"),
                    "旧命名应触发集群 CROSSSLOT 错误，异常链: " + messages);

            // 3) hash-tag 修复后：多键 Lua（归属/过期分流）与 MGET（主键+哨兵同读）正常
            policy.saveResultForReplay(key, token, "{\"fixed\":true}", Duration.ofSeconds(10));
            assertEquals("{\"fixed\":true}", policy.loadResultForReplay(key));

            // 4) 令牌归属保护在集群下同样正常：结果已覆盖占位后，旧令牌的释放
            //    不应删除主键（结果仍可重放、TTL 仍在），防止误删他人结果
            policy.releaseIfOwned(key, token);
            assertEquals("{\"fixed\":true}", policy.loadResultForReplay(key));
            assertTrue(policy.remainingTtlMillis(key) > 0);
        } finally {
            factory.destroy();
        }
    }
}
