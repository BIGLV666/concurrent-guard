package io.github.biglv666.guard.idempotent;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 默认幂等策略 —— Redis setnx + TTL + 令牌归属校验 + 慢请求完成哨兵。
 *
 * <p>使用 {@link StringRedisTemplate#opsForValue()#setIfAbsent(key, value, ttl)}
 * 在一次原子操作内完成"占位 + 设置过期"，不存在先 set 再 expire 的竞态窗口。
 * 占位值是本次请求的专属令牌（{@code __guard:processing__:<uuid>} 前缀），关键操作均为原子脚本：
 * <ul>
 *   <li>{@link #releaseIfOwned}：仅当占位值仍为本次令牌时删除，避免 TTL 耗尽后
 *       误删其他请求新写入的占位（否则会放行重复请求，破坏窗口内互斥）；</li>
 *   <li>{@link #saveResultForReplay}：单脚本按归属决定写入位置——令牌匹配覆盖写主键、
 *       占位已过期写短期哨兵、被其他请求接管则不写（避免结果串扰）；</li>
 *   <li>{@link #loadResultForReplay}：一次 MGET 同时读取主键与哨兵，主键结果优先，
 *       其次哨兵结果（业务略超 TTL 的边界窗口内仍可重放）。</li>
 * </ul>
 *
 * <p>注意：令牌校验解决的是"回滚/保存误伤他人占位"，不延长占位有效期；
 * TTL 仍应配置为不小于业务最大耗时，否则占位过期后新请求会重新进入业务（幂等窗口失效，
 * 哨兵只提供 {@code replay-grace-millis} 的宽限重放，不恢复窗口内互斥）。
 * 兼容性：0.2.0 及之前版本写入的占位值 {@value #PROCESSING_MARKER} 在读取时
 * 仍按"处理中"识别，滚动升级窗口内的旧键可自然过渡至 TTL 淘汰。
 *
 * @author Guard Team
 * @since 0.1.0
 */
public class RedisSetNxIdempotentPolicy implements IdempotentPolicy {

    /**
     * 旧版占位阶段的"处理中"标记值，仅用于读取时识别升级前写入的旧键。
     * 新占位一律使用 {@link #PROCESSING_TOKEN_PREFIX} 前缀的令牌值，
     * 业务返回值不会与其冲突。
     */
    static final String PROCESSING_MARKER = "processing";

    /**
     * 占位令牌前缀。令牌值形如 {@code __guard:processing__:<uuid>}；
     * 序列化后的 JSON 结果以 {@code { [ " " 数字 true false null}} 开头，
     * 不会以该前缀开头，因此可安全区分"处理中"与"结果已保存"。
     * 自定义 {@link ResultCodec} 若保存原始字符串，应避免产生以该前缀开头的内容。
     */
    static final String PROCESSING_TOKEN_PREFIX = "__guard:processing__:";

    /**
     * 慢请求完成哨兵键：与主键在 Redis Cluster 下必须散列到同一 slot。
     *
     * <p>无花括号的主键：哨兵键为 {@code {主键}:result}，以完整主键作为 hash-tag，
     * slot 与主键一致。主键本身带合法非空 hash-tag（如 {@code {tenant}:order:1}）时，
     * 复用该 tag 生成 {@code {tenant}:result:{tenant}:order:1}，避免旧实现
     * {@code {{tenant}:order:1}:result} 取到错误 tag 而触发 CROSSSLOT。</p>
     *
     * <p>空 tag（{@code foo{}bar}）、不配对花括号（{@code foo{bar} / foo}bar）无法保证
     * 同 slot，直接快速失败并提示业务键移除花括号或使用合法 tag。</p>
     *
     * @throws IllegalArgumentException 主键包含无法安全映射的花括号
     */
    static String sentinelKeyOf(String key) {
        String tag = hashTagOf(key);
        if (tag == null) {
            return "{" + key + "}:result";
        }
        return "{" + tag + "}:result:" + key;
    }

    /**
     * 提取 Redis Cluster 使用的 hash-tag；无花括号时返回 null。
     */
    private static String hashTagOf(String key) {
        int open = key.indexOf('{');
        int close = key.indexOf('}');
        if (open < 0) {
            if (close >= 0) {
                throw invalidHashTag(key, "存在 '}' 但没有 '{'");
            }
            return null;
        }
        int tagEnd = key.indexOf('}', open + 1);
        if (tagEnd < 0) {
            throw invalidHashTag(key, "'{' 没有配对的 '}'");
        }
        if (tagEnd == open + 1) {
            throw invalidHashTag(key, "hash-tag 不能为空");
        }
        return key.substring(open + 1, tagEnd);
    }

    private static IllegalArgumentException invalidHashTag(String key, String reason) {
        return new IllegalArgumentException("业务 key 包含无法安全映射的 Redis Cluster 花括号（" + reason
                + "）: " + key + "；请移除花括号，或使用非空且配对的形式（如 {tenant}:order:1）");
    }

    /**
     * 判断哨兵键是否与主键同 slot（仅用于测试与诊断；实际 slot 由 Redis CRC16 计算）。
     */
    public static boolean isSameClusterSlot(String key) {
        String tag = hashTagOf(key);
        String sentinel = sentinelKeyOf(key);
        String expected = tag == null ? "{" + key + "}" : "{" + tag + "}";
        return sentinel.startsWith(expected + ":result");
    }

    /**
     * 释放脚本：占位值与令牌一致时删除，否则不操作（占位已过期或已易主）。
     */
    private static final RedisScript<Long> RELEASE_IF_OWNED_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) "
                    + "else return 0 end", Long.class);

    /**
     * 结果保存脚本（单次往返完成归属判断与写入）：
     * 令牌匹配 → 覆盖写主键并重设 TTL（返回 1）；
     * 主键不存在（占位已过期）→ 写哨兵键，保留时长取宽限值（返回 2）；
     * 占位被其他请求接管 → 不写（返回 0），避免结果串扰。
     * 比较与写入在同一 Lua 脚本内执行，不存在"校验后被抢占"的竞态窗口。
     */
    private static final RedisScript<Long> SAVE_RESULT_FOR_REPLAY_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('get', KEYS[1]) "
                    + "if current == ARGV[1] then "
                    + "redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3]) return 1 end "
                    + "if not current then "
                    + "redis.call('set', KEYS[2], ARGV[2], 'PX', ARGV[4]) return 2 end "
                    + "return 0", Long.class);

    /**
     * 哨兵结果的最大保留时长（宽限窗口）：过期窗口内到达的重复请求可重放，
     * 超过该窗口则与"TTL 过期后重新执行"语义一致，不无限延长幂等保护。
     */
    private final Duration expiredResultTtl;

    private final StringRedisTemplate redisTemplate;

    public RedisSetNxIdempotentPolicy(StringRedisTemplate redisTemplate) {
        this(redisTemplate, Duration.ofSeconds(5));
    }

    /**
     * @param expiredResultTtl 慢请求完成哨兵的保留时长上限（建议秒级，
     *                         仅覆盖"业务略超 TTL"的边界窗口，不替代合理的 TTL 配置）
     */
    public RedisSetNxIdempotentPolicy(StringRedisTemplate redisTemplate, Duration expiredResultTtl) {
        this.redisTemplate = redisTemplate;
        this.expiredResultTtl = expiredResultTtl;
    }

    @Override
    public boolean tryAcquire(String key, Duration ttl) {
        return tryAcquireToken(key, ttl) != null;
    }

    /**
     * 占位成功时生成专属令牌并作为占位值写入，令牌与占位在同一原子操作内落库。
     */
    @Override
    public String tryAcquireToken(String key, Duration ttl) {
        // 提前校验花括号，避免 REPLAY 保存/读取阶段才暴露集群 slot 问题
        sentinelKeyOf(key);
        String token = PROCESSING_TOKEN_PREFIX + UUID.randomUUID();
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(ok) ? token : null;
    }

    @Override
    public void release(String key) {
        redisTemplate.delete(key);
    }

    /**
     * 释放前校验占位归属：仅删除仍由本次令牌持有的占位。
     */
    @Override
    public void releaseIfOwned(String key, String token) {
        redisTemplate.execute(RELEASE_IF_OWNED_SCRIPT, List.of(key), token);
    }

    /**
     * 默认策略支持结果重放：结果与占位复用同一主键，
     * 占位成功后 value 从"处理中令牌"改写为序列化的业务结果。
     */
    @Override
    public boolean supportsReplay() {
        return true;
    }

    @Override
    public void saveResult(String key, String payload, Duration ttl) {
        redisTemplate.opsForValue().set(key, payload, ttl);
    }

    /**
     * 单脚本完成归属判断与结果写入：
     * 令牌匹配覆盖写主键；占位已过期写哨兵（保留 {@code min(ttl, 宽限上限)}）；
     * 占位被接管则不写。哨兵与主占位分离存放，过期窗口内的新占位不会被旧结果污染。
     */
    @Override
    public void saveResultForReplay(String key, String token, String payload, Duration ttl) {
        long grace = Math.min(ttl.toMillis(), expiredResultTtl.toMillis());
        redisTemplate.execute(SAVE_RESULT_FOR_REPLAY_SCRIPT, List.of(key, sentinelKeyOf(key)),
                token, payload, Long.toString(ttl.toMillis()), Long.toString(Math.max(grace, 0)));
    }

    /**
     * 一次 MGET 同时读取主键与哨兵：主键上的已保存结果优先（0.2.0 旧标记视为处理中），
     * 其次慢请求完成哨兵中的结果；两者都不可用（仍在处理中 / 均不存在）返回 null。
     */
    @Override
    public String loadResultForReplay(String key) {
        List<String> values = redisTemplate.opsForValue()
                .multiGet(List.of(key, sentinelKeyOf(key)));
        if (values == null || values.isEmpty()) {
            return null;
        }
        String main = values.get(0);
        if (main != null && !isProcessingValue(main)) {
            return main;
        }
        return values.size() > 1 ? values.get(1) : null;
    }

    /**
     * 加载主键结果：读到处理中令牌（或旧版 {@value #PROCESSING_MARKER} 标记）时返回 null，
     * 表示结果尚未写入、首个请求仍在处理中。
     */
    @Override
    public String loadResult(String key) {
        String value = redisTemplate.opsForValue().get(key);
        return isProcessingValue(value) ? null : value;
    }

    /**
     * 占位的剩余有效时间（毫秒），供拒绝异常携带"还需等待多久"提示。
     * 键不存在或无过期时间时返回 -1（未知），与不支持查询的语义一致。
     */
    @Override
    public long remainingTtlMillis(String key) {
        Long millis = redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
        // Redis 语义：-2 键不存在，-1 无过期时间；对调用方都视为"未知"
        return millis == null || millis <= 0 ? -1 : millis;
    }

    /**
     * 判断占位值是否表示"处理中"：当前版本的令牌值，或旧版本的固定标记。
     */
    private static boolean isProcessingValue(String value) {
        return value != null
                && (value.equals(PROCESSING_MARKER) || value.startsWith(PROCESSING_TOKEN_PREFIX));
    }
}
