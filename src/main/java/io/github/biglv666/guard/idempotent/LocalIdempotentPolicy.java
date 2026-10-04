package io.github.biglv666.guard.idempotent;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地幂等策略 —— 进程内 {@link ConcurrentHashMap} 实现，不依赖 Redis。
 *
 * <p>与 {@code LockType.SYNCHRONIZED} 定位对称：面向单实例部署，
 * 通过 {@code guard.idempotent.store=local} 显式启用（绝不自动回退——
 * 本地策略没有跨进程互斥能力，静默回退会给出虚假的安全感）。
 * 除存储介质外，语义与 {@link RedisSetNxIdempotentPolicy} 对齐：
 * 令牌归属校验的释放、按归属/过期分流的结果保存、慢请求完成哨兵、剩余 TTL 查询。
 *
 * <p>内存管理：过期条目在读取时惰性判废；条目数超过 {@code maxEntries}
 * （清理阈值，非硬上限）时触发一次过期扫描。所有条目长期存活且持续增长的场景
 * （远超阈值的活跃并发 key）不适用本策略，应使用 Redis 策略。
 *
 * <p>线程安全：所有读改写都通过 {@code compute} 在桶锁内完成，
 * 令牌比较与写入之间不存在竞态窗口。
 *
 * @author Guard Team
 * @since 0.2.1
 */
public class LocalIdempotentPolicy implements IdempotentPolicy {

    /**
     * 慢请求完成哨兵键前缀，与 Redis 版语义一致：哨兵条目 = 该前缀 + 完整占位键。
     * 占位键自带 {@code guard:idempotent:} 配置前缀，与哨兵前缀天然不冲突。
     */
    static final String EXPIRED_RESULT_PREFIX = "guard:idempotent:result:";

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * 哨兵结果的最大保留时长（宽限窗口），与 Redis 版 {@code replay-grace-millis} 对应。
     */
    private final Duration expiredResultTtl;

    /**
     * 清理阈值：条目数超过该值时在下一次占位前触发一次过期扫描（非硬上限）。
     */
    private final int maxEntries;

    /** 上次过期扫描时间戳：以 1 秒为节流窗口，避免持续超阈时每次占位都全表扫描。 */
    private volatile long lastSweepAt;

    public LocalIdempotentPolicy(Duration expiredResultTtl, int maxEntries) {
        this.expiredResultTtl = expiredResultTtl;
        this.maxEntries = Math.max(maxEntries, 16);
    }

    @Override
    public boolean tryAcquire(String key, Duration ttl) {
        return tryAcquireToken(key, ttl) != null;
    }

    /**
     * 占位成功时生成专属令牌写入条目；同 key 已有未过期占位时失败返回 null。
     * 通过引用比较判定结果：compute 返回本次新建条目即占位成功，
     * 返回原条目即已有未过期占位（引用比较对线程局部的新建对象是安全的）。
     */
    @Override
    public String tryAcquireToken(String key, Duration ttl) {
        maybeSweep();
        long expireAt = System.currentTimeMillis() + ttl.toMillis();
        String token = "__guard:processing__:" + UUID.randomUUID();
        Entry fresh = new Entry(token, expireAt);
        Entry result = entries.compute(key, (ignored, current) -> {
            if (current != null && current.expireAt > System.currentTimeMillis()) {
                return current;
            }
            return fresh;
        });
        return result == fresh ? token : null;
    }

    @Override
    public void release(String key) {
        entries.remove(key);
    }

    /**
     * 释放前校验占位归属：仅当条目仍由本次令牌持有时删除。
     */
    @Override
    public void releaseIfOwned(String key, String token) {
        entries.compute(key, (ignored, current) ->
                current != null && token != null && token.equals(current.value) ? null : current);
    }

    @Override
    public boolean supportsReplay() {
        return true;
    }

    @Override
    public void saveResult(String key, String payload, Duration ttl) {
        long expireAt = System.currentTimeMillis() + ttl.toMillis();
        entries.compute(key, (ignored, current) -> new Entry(payload, expireAt));
    }

    /**
     * 按占位归属分流写入：令牌匹配覆盖写主条目；条目过期或缺失写哨兵条目
     * （保留 {@code min(ttl, 宽限上限)}）；被其他请求接管则不写。
     *
     * <p>归属判定用 peek + compute 完成（ConcurrentHashMap 禁止在 compute 内
     * 再修改本映射，哨兵写入必须在 compute 之外）：归属写分支在 compute 内
     * 原子复验令牌，杜绝"判定后被抢占"的竞态；哨兵分支的判定与写入之间
     * 主条目可能被新请求重新占位——此时写入哨兵仍是良性的（过期窗口内的
     * 旧结果反而可被后续重复请求重放），与 Redis 版原子语义的这点差异可接受。
     */
    @Override
    public void saveResultForReplay(String key, String token, String payload, Duration ttl) {
        long now = System.currentTimeMillis();
        long expireAt = now + ttl.toMillis();
        long grace = Math.max(Math.min(ttl.toMillis(), expiredResultTtl.toMillis()), 0);

        Entry current = entries.get(key);
        boolean owned = current != null && current.expireAt > now
                && token != null && token.equals(current.value);
        if (owned) {
            // compute 内原子复验令牌：仍归本次所有才覆盖写
            entries.compute(key, (ignored, live) ->
                    live != null && token != null && token.equals(live.value)
                            ? new Entry(payload, expireAt) : live);
        } else if ((current == null || current.expireAt <= now) && grace > 0) {
            entries.put(EXPIRED_RESULT_PREFIX + key, new Entry(payload, now + grace));
        }
    }

    /**
     * 依次读取主条目与哨兵条目：主条目上已保存的结果优先；
     * 仍在处理中（值为本次或他人的处理中令牌）或均不存在时返回 null。
     */
    @Override
    public String loadResultForReplay(String key) {
        long now = System.currentTimeMillis();
        Entry main = entries.get(key);
        if (main != null && main.expireAt > now && !isProcessingValue(main.value)) {
            return main.value;
        }
        Entry sentinel = entries.get(EXPIRED_RESULT_PREFIX + key);
        return sentinel != null && sentinel.expireAt > now ? sentinel.value : null;
    }

    /**
     * 加载主条目结果：仍在处理中（处理中令牌）时返回 null。
     */
    @Override
    public String loadResult(String key) {
        Entry main = entries.get(key);
        if (main == null || main.expireAt <= System.currentTimeMillis()) {
            return null;
        }
        return isProcessingValue(main.value) ? null : main.value;
    }

    /**
     * 主条目的剩余有效时间（毫秒）；条目不存在或已过期返回 -1（未知）。
     */
    @Override
    public long remainingTtlMillis(String key) {
        Entry main = entries.get(key);
        if (main == null) {
            return -1;
        }
        long remaining = main.expireAt - System.currentTimeMillis();
        return remaining > 0 ? remaining : -1;
    }

    /**
     * 条目数超过清理阈值时执行一次过期扫描（以 1 秒节流，并发触发是良性的重复清理）。
     */
    private void maybeSweep() {
        if (entries.size() <= maxEntries) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSweepAt < 1000) {
            return;
        }
        lastSweepAt = now;
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expireAt <= now) {
                it.remove();
            }
        }
    }

    /** 当前条目数量（含未过期与待清理条目），主要用于监控和测试。 */
    int size() {
        return entries.size();
    }

    /**
     * 判断占位值是否表示"处理中"：处理中令牌，或旧版固定标记（跨策略对齐）。
     */
    private static boolean isProcessingValue(String value) {
        return value != null
                && (value.equals("processing") || value.startsWith("__guard:processing__:"));
    }

    /**
     * 幂等条目：存储内容（处理中令牌或序列化结果）与过期时间戳。
     */
    private static final class Entry {
        final String value;
        final long expireAt;

        Entry(String value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }
    }
}
