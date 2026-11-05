package io.github.biglv666.guard.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redisson 分布式锁执行器。
 *
 * <p>仅在 classpath 存在 Redisson 且容器中存在 {@link RedissonClient} 时使用；
 * 客户端采用懒获取，实例化本执行器不会强制要求 RedissonClient Bean。</p>
 *
 * @author Guard Team
 * @since 0.2.1
 */
public class RedissonLockExecutor implements LockExecutor {

    private static final Logger log = LoggerFactory.getLogger(RedissonLockExecutor.class);

    private final ObjectProvider<RedissonClient> redissonProvider;

    public RedissonLockExecutor(ObjectProvider<RedissonClient> redissonProvider) {
        this.redissonProvider = redissonProvider;
    }

    @Override
    public LockType type() {
        return LockType.REDIS;
    }

    @Override
    public boolean isAvailable() {
        return redissonProvider != null && redissonProvider.getIfAvailable() != null;
    }

    @Override
    public LockHandle acquire(String key, long waitTime, long leaseTime, TimeUnit unit) {
        RLock lock = requireClient().getLock(key);
        long start = System.nanoTime();
        boolean locked;
        try {
            locked = lock.tryLock(waitTime, leaseTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquireInterruptedException(key, "锁等待被中断: " + key);
        }
        if (!locked) {
            throw new LockAcquireTimeoutException(key,
                    String.format("获取锁超时: %s（等待 %d %s）", key, waitTime, unit));
        }
        return new RedissonHandle(lock, System.nanoTime() - start, key);
    }

    /**
     * 懒获取 RedissonClient：功能启用但未配置时，在首次加锁处抛出清晰的配置错误。
     */
    private RedissonClient requireClient() {
        RedissonClient client = redissonProvider != null ? redissonProvider.getIfAvailable() : null;
        if (client == null) {
            throw new LockConfigurationException(
                    "检测到 Redis 锁调用，但容器中不存在 RedissonClient Bean。"
                            + "请引入 redisson 依赖并注册 RedissonClient（或使用 redisson-spring-boot-starter）；"
                            + "若暂不使用分布式锁，可配置 guard.lock.enabled=false，"
                            + "或使用 type = LockType.SYNCHRONIZED 的本地锁。");
        }
        return client;
    }

    /**
     * Redis 锁句柄：关闭时仅释放当前线程持有的锁；租期已过期或连接故障时只记日志，
     * 避免释放异常覆盖业务异常。
     */
    private static final class RedissonHandle implements LockHandle {

        private final RLock lock;
        private final long acquireNanos;
        private final String key;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RedissonHandle(RLock lock, long acquireNanos, String key) {
            this.lock = lock;
            this.acquireNanos = acquireNanos;
            this.key = key;
        }

        @Override
        public long acquireNanos() {
            return acquireNanos;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (RuntimeException e) {
                log.warn("锁释放异常（租期可能已过期或连接中断） - key: {}", key, e);
            }
        }
    }
}
