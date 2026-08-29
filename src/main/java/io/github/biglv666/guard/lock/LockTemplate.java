package io.github.biglv666.guard.lock;

import io.github.biglv666.guard.GuardProperties;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁的程序化 API —— 与切面共享同一套锁语义，覆盖不适合打注解的细粒度场景：
 *
 * <pre>
 * String result = lockTemplate.withLock("stock:1001", () -> doDeductStock());
 * </pre>
 *
 * <p>与注解方式的差异：此处 key 直接传入业务键（自动拼配置前缀），
 * 获取锁失败统一抛 {@link LockAcquireTimeoutException}，由调用方自行决定降级方式。
 * 释放同样在 finally 中以 {@code isHeldByCurrentThread()} 保护。
 *
 * @author Guard Team
 * @since 0.1.0
 */
public class LockTemplate {

    private static final Logger log = LoggerFactory.getLogger(LockTemplate.class);

    /**
     * 默认等待时间（秒），与 {@link DistributedLock#waitTime()} 默认值一致。
     */
    private static final long DEFAULT_WAIT_SECONDS = 3;

    private final ObjectProvider<RedissonClient> redissonProvider;
    private final GuardProperties.Lock properties;

    public LockTemplate(ObjectProvider<RedissonClient> redissonProvider,
                        GuardProperties.Lock properties) {
        this.redissonProvider = redissonProvider;
        this.properties = properties;
    }

    /**
     * 以默认参数（等待 3 秒、看门狗续期）执行加锁逻辑。
     *
     * @param key    业务锁键（自动拼配置前缀，无需带 guard:lock: 前缀）
     * @param action 持锁期间执行的业务逻辑
     * @param <T>    返回类型
     * @return 业务逻辑返回值
     * @throws LockAcquireTimeoutException 等待超时未获取到锁
     */
    public <T> T withLock(String key, Supplier<T> action) {
        return withLock(key, DEFAULT_WAIT_SECONDS, -1, TimeUnit.SECONDS, action);
    }

    /**
     * 以无返回值形式执行加锁逻辑。
     *
     * @param key    业务锁键（自动拼配置前缀）
     * @param action 持锁期间执行的业务逻辑
     * @throws LockAcquireTimeoutException 等待超时未获取到锁
     */
    public void withLock(String key, Runnable action) {
        withLock(key, DEFAULT_WAIT_SECONDS, -1, TimeUnit.SECONDS, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 完整参数的加锁执行。
     *
     * @param key       业务锁键（自动拼配置前缀）
     * @param waitTime  获取锁最长等待时间
     * @param leaseTime 持锁时间；-1 表示启用 Redisson 看门狗自动续期
     * @param unit      时间单位
     * @param action    持锁期间执行的业务逻辑
     * @param <T>       返回类型
     * @return 业务逻辑返回值
     * @throws LockAcquireTimeoutException 等待超时未获取到锁
     */
    public <T> T withLock(String key, long waitTime, long leaseTime, TimeUnit unit, Supplier<T> action) {
        String fullKey = String.format("%s%s", properties.getKeyPrefix(), key);
        RLock lock = requireClient().getLock(fullKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(waitTime, leaseTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("LockTemplate 锁等待被中断 - key: {}", fullKey);
        }
        if (!locked) {
            throw timeout(fullKey, waitTime, unit);
        }
        try {
            return action.get();
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (IllegalMonitorStateException e) {
                log.warn("锁释放异常（租期可能已过期被自动释放）- key: {}", fullKey, e);
            }
        }
    }

    /**
     * 构造锁等待超时异常，消息用占位符格式化。
     */
    private static LockAcquireTimeoutException timeout(String fullKey, long waitTime, TimeUnit unit) {
        String message = String.format("获取锁超时: %s（等待 %d %s）", fullKey, waitTime, unit);
        return new LockAcquireTimeoutException(fullKey, message);
    }

    private RedissonClient requireClient() {
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException(
                    "LockTemplate 需要容器中存在 RedissonClient Bean。"
                            + "请引入 redisson 依赖并注册 RedissonClient（或使用 redisson-spring-boot-starter）；"
                            + "若暂不使用分布式锁，可配置 guard.lock.enabled=false。");
        }
        return client;
    }
}
