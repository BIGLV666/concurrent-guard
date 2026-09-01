package io.github.biglv666.guard.lock;

import io.github.biglv666.guard.GuardProperties;
import io.github.biglv666.guard.metrics.GuardMetrics;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 程序化代码块加锁模板。
 *
 * <p>{@link #withLock(String, Supplier)} 使用 Redis 分布式锁；
 * {@link #withLocalLock(String, Supplier)} 使用当前 JVM 内的本地锁。
 * 两者都只保护 Lambda 执行期间的代码，适合细粒度锁定局部业务逻辑。</p>
 *
 * <p>Redis 客户端不会在模板实例化时校验，而是在首次调用 Redis 方法时懒获取。
 * 本地锁不依赖 Redisson，且本地锁的租期参数不适用。</p>
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
    private final LocalLockManager localLockManager;
    /** 可选：宿主无 Micrometer 时为 null，锁耗时上报为空操作。 */
    private final GuardMetrics metrics;

    /** 兼容旧版直接实例化方式。 */
    public LockTemplate(ObjectProvider<RedissonClient> redissonProvider,
                        GuardProperties.Lock properties) {
        this(redissonProvider, properties, new LocalLockManager(), null);
    }

    /** 兼容 0.1.0 直接实例化方式（无指标上报）。 */
    public LockTemplate(ObjectProvider<RedissonClient> redissonProvider,
                        GuardProperties.Lock properties,
                        LocalLockManager localLockManager) {
        this(redissonProvider, properties, localLockManager, null);
    }

    public LockTemplate(ObjectProvider<RedissonClient> redissonProvider,
                        GuardProperties.Lock properties,
                        LocalLockManager localLockManager,
                        GuardMetrics metrics) {
        this.redissonProvider = redissonProvider;
        this.properties = properties;
        this.localLockManager = localLockManager;
        this.metrics = metrics;
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

    /** 使用 JVM 本地锁保护局部代码块，锁范围仅限 Lambda 执行期间。 */
    public <T> T withLocalLock(String key, Supplier<T> action) {
        return withLocalLock(key, DEFAULT_WAIT_SECONDS, TimeUnit.SECONDS, action);
    }

    /** 使用 JVM 本地锁保护无返回值代码块。 */
    public void withLocalLock(String key, Runnable action) {
        withLocalLock(key, DEFAULT_WAIT_SECONDS, TimeUnit.SECONDS, () -> { action.run(); return null; });
    }

    /** 使用指定等待时间的 JVM 本地锁保护代码块；本地锁不使用 leaseTime。 */
    public <T> T withLocalLock(String key, long waitTime, TimeUnit unit, Supplier<T> action) {
        String fullKey = properties.getKeyPrefix() + key;
        LocalLockManager.Handle handle = localLockManager.acquire(fullKey);
        boolean locked = false;
        long acquireStart = System.nanoTime();
        try {
            locked = handle.lock().tryLock(waitTime, unit);
            if (!locked) throw timeout(fullKey, waitTime, unit);
            recordAcquire(LockType.SYNCHRONIZED, acquireStart);
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw timeout(fullKey, waitTime, unit);
        } finally {
            if (locked) handle.lock().unlock();
            handle.close();
        }
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
        long acquireStart = System.nanoTime();
        try {
            locked = lock.tryLock(waitTime, leaseTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("LockTemplate 锁等待被中断 - key: {}", fullKey);
        }
        if (!locked) {
            throw timeout(fullKey, waitTime, unit);
        }
        recordAcquire(LockType.REDIS, acquireStart);
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
     * 上报一次成功获取锁的耗时（含等待时间）；超时失败不计入耗时分布。
     */
    private void recordAcquire(LockType lockType, long acquireStart) {
        if (metrics != null) {
            metrics.recordLockAcquire(lockType.name(), System.nanoTime() - acquireStart);
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
