package io.github.biglv666.guard.idempotent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalIdempotentPolicy} 单元测试（无 Spring、无 Redis）：
 * 占位互斥、令牌归属释放、结果保存/哨兵分流、剩余 TTL、过期清理与并发唯一性。
 *
 * @author Guard Team
 * @since 0.2.1
 */
class LocalIdempotentPolicyTest {

    private static final Duration TTL = Duration.ofSeconds(10);

    @Test
    void acquireIsExclusiveAndTokensAreDistinct() {
        LocalIdempotentPolicy policy = new LocalIdempotentPolicy(Duration.ofSeconds(5), 1024);
        String key = "k-" + UUID.randomUUID();
        String first = policy.tryAcquireToken(key, TTL);
        assertNull(policy.tryAcquireToken(key, TTL), "同 key 二次占位应失败");
        String other = policy.tryAcquireToken("k-" + UUID.randomUUID(), TTL);
        assertNotEquals(first, other);
    }

    @Test
    void concurrentAcquireOnlyOneWinner() throws Exception {
        LocalIdempotentPolicy policy = new LocalIdempotentPolicy(Duration.ofSeconds(5), 1024);
        String key = "conc-" + UUID.randomUUID();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (policy.tryAcquireToken(key, TTL) != null) {
                        winners.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, winners.get(), "并发同 key 占位应只有一人成功");
    }

    @Test
    void releaseIfOwnedKeepsForeignPlaceholder() {
        LocalIdempotentPolicy policy = new LocalIdempotentPolicy(Duration.ofSeconds(5), 1024);
        String key = "rel-" + UUID.randomUUID();
        String mine = policy.tryAcquireToken(key, TTL);

        // 模拟占位易主：直接覆写条目值
        String foreign = "__guard:processing__:foreign";
        policy.saveResult(key, foreign, TTL);

        // 令牌不匹配不应删除（条目仍在：剩余 TTL 可查）；
        // loadResultForReplay 把处理中令牌视为"无结果"，用剩余 TTL 判断条目存续
        policy.releaseIfOwned(key, mine);
        assertTrue(policy.remainingTtlMillis(key) > 0, "易主占位不应被本次令牌释放");
        // 令牌匹配才删除
        policy.releaseIfOwned(key, foreign);
        assertEquals(-1, policy.remainingTtlMillis(key), "令牌匹配后应删除占位");
    }

    @Test
    void saveResultForReplayRoutesByOwnership() throws Exception {
        LocalIdempotentPolicy policy = new LocalIdempotentPolicy(Duration.ofSeconds(5), 1024);
        String key = "save-" + UUID.randomUUID();
        String token = policy.tryAcquireToken(key, Duration.ofMillis(1));
        assertNull(policy.loadResultForReplay(key), "处理中令牌不是可重放结果");

        // 场景一：令牌匹配（在 TTL 内保存）→ 写主条目
        policy.saveResultForReplay(key, token, "{\"r\":1}", TTL);
        assertEquals("{\"r\":1}", policy.loadResultForReplay(key));

        // 场景二：占位已过期 → 写哨兵，主条目上无内容
        String staleKey = "stale-" + UUID.randomUUID();
        String staleToken = policy.tryAcquireToken(staleKey, Duration.ofMillis(1));
        Thread.sleep(20);
        policy.saveResultForReplay(staleKey, staleToken, "{\"r\":2}", TTL);
        assertEquals("{\"r\":2}", policy.loadResultForReplay(staleKey));
        assertEquals(-1, policy.remainingTtlMillis(staleKey), "主条目已过期，剩余 TTL 未知");

        // 场景三：占位被接管（易主令牌）→ 不写哨兵也不覆盖
        String foreignKey = "foreign-" + UUID.randomUUID();
        policy.tryAcquireToken(foreignKey, TTL);
        policy.saveResultForReplay(foreignKey, "__guard:processing__:foreign", "{\"r\":3}", TTL);
        assertNull(policy.loadResultForReplay(foreignKey), "易主占位不应产生可重放结果");
    }

    @Test
    void remainingTtlTracksPlaceholder() {
        LocalIdempotentPolicy policy = new LocalIdempotentPolicy(Duration.ofSeconds(5), 1024);
        String key = "ttl-" + UUID.randomUUID();
        assertEquals(-1, policy.remainingTtlMillis(key));
        policy.tryAcquireToken(key, TTL);
        long remaining = policy.remainingTtlMillis(key);
        assertTrue(remaining > 0 && remaining <= TTL.toMillis(),
                "剩余 TTL 应在 (0, " + TTL.toMillis() + "] 区间，实际: " + remaining);
    }

    @Test
    void sweepRemovesExpiredEntriesOverThreshold() throws Exception {
        // 阈值压到最小值 16，超过即触发过期扫描（1 秒节流）
        LocalIdempotentPolicy policy = new LocalIdempotentPolicy(Duration.ofSeconds(5), 16);
        for (int i = 0; i < 20; i++) {
            policy.tryAcquireToken("batch1-" + i, Duration.ofMillis(1));
        }
        // 1ms TTL 的条目此刻已全部过期；越过节流窗口后写入大批次（40 个）：
        // 即使批次中途触发过一次扫描清掉一部分，剩余新增仍会把条目数顶回阈值以上，
        // 保证最后一步触发扫描时两批过期条目被一并清理，仅剩新占位
        Thread.sleep(1100);
        for (int i = 0; i < 40; i++) {
            policy.tryAcquireToken("batch2-" + i, Duration.ofMillis(1));
        }
        Thread.sleep(1100);
        String trigger = "trigger";
        assertNotNull(policy.tryAcquireToken(trigger, Duration.ofSeconds(10)));
        assertEquals(1, policy.size(), "过期条目应被扫描清理，仅剩未过期占位");
    }
}
