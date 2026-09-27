package io.github.biglv666.guard.itest.ownership;

import io.github.biglv666.guard.idempotent.IdempotentPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 幂等占位归属校验集成测试（Testcontainers 真实 Redis）：
 * 验证默认策略的令牌链路——释放与结果保存只作用于仍由本次令牌持有的占位，
 * TTL 耗尽后占位被其他请求接管时不会被误删/误覆盖（0.2.0 的竞态缺陷回归测试）。
 * 测试应用独立成包，避免组件扫描互相污染。
 *
 * @author Guard Team
 * @since 0.2.1
 */
@Testcontainers
@SpringBootTest(classes = IdempotentOwnershipTest.App.class)
class IdempotentOwnershipTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private IdempotentPolicy policy;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final Duration TTL = Duration.ofSeconds(10);

    @Test
    void acquireReturnsDistinctTokensAndIsExclusive() {
        String key = "own-token-" + UUID.randomUUID();
        String first = policy.tryAcquireToken(key, TTL);
        assertNotNull(first);
        // 令牌带处理中前缀，不可能是旧接口的"无令牌"哨兵
        assertNotEquals(IdempotentPolicy.NO_OWNER_TOKEN, first);
        // 同 key 互斥：第二次占位失败返回 null
        assertNull(policy.tryAcquireToken(key, TTL));
        // 不同 key 的令牌彼此独立
        String other = policy.tryAcquireToken("own-token-" + UUID.randomUUID(), TTL);
        assertNotNull(other);
        assertNotEquals(first, other);
    }

    @Test
    void releaseDoesNotDeleteForeignPlaceholder() {
        String key = "own-release-" + UUID.randomUUID();
        String mine = policy.tryAcquireToken(key, TTL);
        assertNotNull(mine);

        // 模拟 TTL 耗尽后占位被其他请求接管：占位值已易主
        String foreign = "__guard:processing__:foreign-" + UUID.randomUUID();
        redisTemplate.opsForValue().set(key, foreign, TTL);

        // 过期请求的回滚不得删除新请求的占位（否则会放行重复请求）
        policy.releaseIfOwned(key, mine);
        assertEquals(foreign, redisTemplate.opsForValue().get(key));

        // 令牌匹配时正常删除
        policy.releaseIfOwned(key, foreign);
        assertNull(redisTemplate.opsForValue().get(key));
    }

    @Test
    void saveResultDoesNotClobberForeignPlaceholder() {
        String key = "own-save-" + UUID.randomUUID();
        String mine = policy.tryAcquireToken(key, TTL);
        assertNotNull(mine);

        // 模拟 TTL 耗尽后占位被其他请求接管
        String foreign = "__guard:processing__:foreign-" + UUID.randomUUID();
        redisTemplate.opsForValue().set(key, foreign, TTL);

        // 先完成的慢请求保存结果不得覆盖新请求的占位（否则会导致结果串扰）
        policy.saveResultIfOwned(key, mine, "{\"slow\":true}", TTL);
        assertEquals(foreign, redisTemplate.opsForValue().get(key));

        // 令牌匹配时正常写入结果
        policy.saveResultIfOwned(key, foreign, "{\"owner\":true}", TTL);
        assertEquals("{\"owner\":true}", redisTemplate.opsForValue().get(key));
    }

    @Test
    void loadResultDistinguishesProcessingTokenFromSavedResult() {
        String key = "own-load-" + UUID.randomUUID();
        String token = policy.tryAcquireToken(key, TTL);
        assertNotNull(token);
        // 占位仍是处理中令牌：无结果可重放
        assertNull(policy.loadResult(key));

        // 兼容旧版本写入的占位标记：同样视为处理中
        String legacyKey = "own-legacy-" + UUID.randomUUID();
        redisTemplate.opsForValue().set(legacyKey, "processing", TTL);
        assertNull(policy.loadResult(legacyKey));

        // 令牌匹配后保存结果：可正常加载
        policy.saveResultIfOwned(key, token, "payload", TTL);
        assertEquals("payload", policy.loadResult(key));
    }

    /**
     * 测试应用：仅装配 Guard 自动配置，无额外组件。
     */
    @SpringBootApplication
    static class App {
    }
}
