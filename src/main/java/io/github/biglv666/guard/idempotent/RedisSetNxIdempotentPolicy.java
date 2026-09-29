package io.github.biglv666.guard.idempotent;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 默认幂等策略 —— Redis setnx + TTL + 令牌归属校验。
 *
 * <p>使用 {@link StringRedisTemplate#opsForValue()#setIfAbsent(key, value, ttl)}
 * 在一次原子操作内完成"占位 + 设置过期"，不存在先 set 再 expire 的竞态窗口。
 * 占位值是本次请求的专属令牌（{@code __guard:processing__:<uuid>} 前缀），
 * 释放与结果保存均通过 Lua 脚本先校验令牌再操作：
 * <ul>
 *   <li>{@link #releaseIfOwned}：仅当占位值仍为本次令牌时删除，避免 TTL 耗尽后
 *       误删其他请求新写入的占位（否则会放行重复请求，破坏窗口内互斥）；</li>
 *   <li>{@link #saveResultIfOwned}：仅当占位值仍为本次令牌时覆盖写入结果，
 *       避免先完成的慢请求覆盖新请求的占位导致结果串扰。</li>
 * </ul>
 *
 * <p>注意：令牌校验解决的是"回滚/保存误伤他人占位"，不延长占位有效期；
 * TTL 仍应配置为大于业务最大耗时，否则占位过期后新请求会重新进入业务（幂等窗口失效）。
 * 兼容性：0.2.0 及之前版本写入的占位值 {@value #PROCESSING_MARKER} 在
 * {@link #loadResult} 中仍按"处理中"识别，滚动升级窗口内的旧键可自然过渡至 TTL 淘汰。
 *
 * @author Guard Team
 * @since 0.1.0
 */
public class RedisSetNxIdempotentPolicy implements IdempotentPolicy {

    /**
     * 旧版占位阶段的"处理中"标记值，仅用于 {@link #loadResult} 识别升级前写入的旧键。
     * 新占位一律使用 {@link #PROCESSING_TOKEN_PREFIX} 前缀的令牌值，
     * 业务返回值不会与其冲突。
     */
    static final String PROCESSING_MARKER = "processing";

    /**
     * 占位令牌前缀。令牌值形如 {@code __guard:processing__:<uuid>}；
     * 序列化后的 JSON 结果以 {@code { [ " " 数字 true false null}} 开头，
     * 不会以该前缀开头，因此 {@link #loadResult} 可安全区分"处理中"与"结果已保存"。
     * 自定义 {@link ResultCodec} 若保存原始字符串，应避免产生以该前缀开头的内容。
     */
    static final String PROCESSING_TOKEN_PREFIX = "__guard:processing__:";

    /**
     * 释放脚本：占位值与令牌一致时删除，否则不操作（占位已过期或已易主）。
     */
    private static final RedisScript<Long> RELEASE_IF_OWNED_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) "
                    + "else return 0 end", Long.class);

    /**
     * 结果保存脚本：占位值与令牌一致时覆盖写入结果并重设 TTL，否则不操作。
     * 比较与写入在同一 Lua 脚本内执行，不存在"校验后被抢占"的竞态窗口。
     */
    private static final RedisScript<Long> SAVE_RESULT_IF_OWNED_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3]) return 1 "
                    + "else return 0 end", Long.class);

    /**
     * 过期哨兵写入脚本：仅当占位已过期（键不存在）时写入哨兵键；
     * 占位被其他请求接管（键存在但令牌不同）时不写，避免结果串扰。
     */
    private static final RedisScript<Long> SAVE_RESULT_IF_EXPIRED_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == false then "
                    + "redis.call('set', KEYS[2], ARGV[1], 'PX', ARGV[2]) return 1 "
                    + "else return 0 end", Long.class);

    /**
     * 哨兵键前缀：慢请求完成结果与主占位分离存放，避免过期窗口内新占位被结果污染。
     */
    static final String EXPIRED_RESULT_PREFIX = "guard:idempotent:result:";

    /**
     * 哨兵结果的最大保留时长：过期窗口内到达的重复请求可重放，
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
     * 默认策略支持结果重放：结果与占位复用同一 key，
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
     * 保存结果前校验占位归属：仅覆盖仍由本次令牌持有的占位。
     * SPI 契约方法，语义等同 {@link #trySaveResultIfOwned}（忽略返回值）。
     */
    @Override
    public void saveResultIfOwned(String key, String token, String payload, Duration ttl) {
        trySaveResultIfOwned(key, token, payload, ttl);
    }

    /**
     * 保存结果前校验占位归属：仅覆盖仍由本次令牌持有的占位，
     * 防止慢请求覆盖其他请求的占位造成结果串扰。
     *
     * @return 实际写入时返回 true（占位仍归本次所有）；未写入返回 false
     *         （占位已过期或已易主，调用方视情况走 {@link #saveResultIfExpired}）
     */
    public boolean trySaveResultIfOwned(String key, String token, String payload, Duration ttl) {
        Long written = redisTemplate.execute(SAVE_RESULT_IF_OWNED_SCRIPT, List.of(key),
                token, payload, Long.toString(ttl.toMillis()));
        return Long.valueOf(1L).equals(written);
    }

    /**
     * 占位已过期时写入慢请求完成哨兵：仅当键确实不存在（过期）而非被接管时写入，
     * 哨兵保留时长取 {@code min(ttl, expiredResultTtl)}，避免无限延长幂等窗口。
     */
    @Override
    public void saveResultIfExpired(String key, String token, String payload, Duration ttl) {
        long millis = Math.min(ttl.toMillis(), expiredResultTtl.toMillis());
        if (millis <= 0) {
            return;
        }
        redisTemplate.execute(SAVE_RESULT_IF_EXPIRED_SCRIPT,
                List.of(key, EXPIRED_RESULT_PREFIX + key),
                payload, Long.toString(millis));
    }

    /**
     * 加载慢请求完成哨兵中的结果。
     */
    @Override
    public String loadExpiredResult(String key) {
        return redisTemplate.opsForValue().get(EXPIRED_RESULT_PREFIX + key);
    }

    /**
     * 占位的剩余有效时间（毫秒），供拒绝异常携带"还需等待多久"提示。
     * 键不存在或已过期时返回 -1（未知），与不支持查询的语义一致。
     */
    @Override
    public long remainingTtlMillis(String key) {
        Long millis = redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
        // Redis 语义：-2 键不存在，-1 无过期时间；对调用方都视为"未知"
        return millis == null || millis <= 0 ? -1 : millis;
    }

    /**
     * 加载结果：读到处理中令牌（或旧版 {@value #PROCESSING_MARKER} 标记）时返回 null，
     * 表示结果尚未写入、首个请求仍在处理中。
     */
    @Override
    public String loadResult(String key) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null || value.equals(PROCESSING_MARKER)
                || value.startsWith(PROCESSING_TOKEN_PREFIX)) {
            return null;
        }
        return value;
    }
}
