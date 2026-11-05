package io.github.biglv666.guard.lock;

import io.github.biglv666.guard.GuardProperties;
import io.github.biglv666.guard.event.GuardEventType;
import io.github.biglv666.guard.event.GuardRejectedEvent;
import io.github.biglv666.guard.internal.GuardEventSupport;
import io.github.biglv666.guard.metrics.GuardMetrics;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 程序化代码块加锁模板。
 *
 * <p>{@link #withLock(String, Supplier)} 使用 Redis 分布式锁；
 * {@link #withLocalLock(String, Supplier)} 使用当前 JVM 内的本地锁。
 * 两者都只保护 Lambda 执行期间的代码，适合细粒度锁定局部业务逻辑。</p>
 *
 * <p>锁执行器按 {@link LockType} 选择：本地锁不依赖 Redisson，
 * Redis 客户端仅在实际调用 Redis 路径时懒获取。</p>
 *
 * <p>超时与中断会发布 {@link GuardRejectedEvent} 并计入拒绝指标，
 * 与 {@link LockAspect} 的声明式路径保持一致。</p>
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

    private final Map<LockType, LockExecutor> executors;
    private final GuardProperties.Lock properties;
    /** 可选：宿主无 Micrometer 时为 null，锁耗时上报为空操作。 */
    private final GuardMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 标准构造器：按 {@link LockType} 索引可用的锁执行器。
     *
     * @param executors      锁执行器列表（至少包含本地锁执行器）
     * @param properties     锁模块配置
     * @param metrics        指标上报器，允许为 null
     * @param eventPublisher 事件发布器，允许为 null（直接实例化时）
     * @since 0.2.1
     */
    public LockTemplate(List<LockExecutor> executors,
                        GuardProperties.Lock properties,
                        GuardMetrics metrics,
                        ApplicationEventPublisher eventPublisher) {
        this.executors = index(executors);
        this.properties = properties;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 仅本地锁的便捷构造器：不依赖 Redisson，适合单实例或测试场景。
     *
     * @param localLockManager 本地锁管理器
     * @param properties       锁模块配置
     * @since 0.2.1
     */
    public LockTemplate(LocalLockManager localLockManager, GuardProperties.Lock properties) {
        this(List.of(new LocalLockExecutor(localLockManager)), properties, null, null);
    }

    /**
     * 兼容旧版直接实例化方式。
     *
     * @deprecated 请使用接收 {@link LockExecutor} 列表的构造器；旧构造器依赖 Redisson 类型。
     */
    @Deprecated
    public LockTemplate(ObjectProvider<RedissonClient> redissonProvider,
                        GuardProperties.Lock properties) {
        this(legacyExecutors(redissonProvider, new LocalLockManager()), properties, null, null);
    }

    /**
     * 兼容 0.1.0 直接实例化方式（无指标上报）。
     *
     * @deprecated 请使用接收 {@link LockExecutor} 列表的构造器；旧构造器依赖 Redisson 类型。
     */
    @Deprecated
    public LockTemplate(ObjectProvider<RedissonClient> redissonProvider,
                        GuardProperties.Lock properties,
                        LocalLockManager localLockManager) {
        this(legacyExecutors(redissonProvider, localLockManager), properties, null, null);
    }

    /**
     * 兼容旧版直接实例化方式。
     *
     * @deprecated 请使用接收 {@link LockExecutor} 列表的构造器；旧构造器依赖 Redisson 类型。
     */
    @Deprecated
    public LockTemplate(ObjectProvider<RedissonClient> redissonProvider,
                        GuardProperties.Lock properties,
                        LocalLockManager localLockManager,
                        GuardMetrics metrics) {
        this(legacyExecutors(redissonProvider, localLockManager), properties, metrics, null);
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
        return execute(LockType.SYNCHRONIZED, key, waitTime, -1, unit, action);
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
        return execute(LockType.REDIS, key, waitTime, leaseTime, unit, action);
    }

    /**
     * 尝试加锁，超时返回 {@link Optional#empty()}，不抛超时异常。
     * 中断仍抛 {@link LockAcquireInterruptedException}；基础设施故障仍按 fail-open/fail-close 处理。
     *
     * <p>注意：业务返回 null 与超时都会得到 empty，无法区分；需要区分时请使用带 onTimeout 的重载。</p>
     *
     * @since 0.3.0
     */
    public <T> Optional<T> tryWithLock(String key, Supplier<T> action) {
        return tryWithLock(key, DEFAULT_WAIT_SECONDS, -1, TimeUnit.SECONDS, action);
    }

    /**
     * 尝试加锁，超时返回 {@link Optional#empty()}。
     *
     * @since 0.3.0
     */
    public <T> Optional<T> tryWithLock(String key, long waitTime, long leaseTime,
                                       TimeUnit unit, Supplier<T> action) {
        return tryExecute(LockType.REDIS, key, waitTime, leaseTime, unit, action);
    }

    /**
     * 尝试本地加锁，超时返回 {@link Optional#empty()}。
     *
     * @since 0.3.0
     */
    public <T> Optional<T> tryWithLocalLock(String key, Supplier<T> action) {
        return tryExecute(LockType.SYNCHRONIZED, key, DEFAULT_WAIT_SECONDS, -1, TimeUnit.SECONDS, action);
    }

    /**
     * 尝试本地加锁，超时返回 {@link Optional#empty()}。
     *
     * @since 0.3.0
     */
    public <T> Optional<T> tryWithLocalLock(String key, long waitTime, TimeUnit unit, Supplier<T> action) {
        return tryExecute(LockType.SYNCHRONIZED, key, waitTime, -1, unit, action);
    }

    /**
     * 支持受检异常的 Redis 锁执行。
     *
     * @since 0.3.0
     */
    public <T> T callWithLock(String key, long waitTime, long leaseTime,
                              TimeUnit unit, Callable<T> action) throws Exception {
        return call(LockType.REDIS, key, waitTime, leaseTime, unit, action);
    }

    /**
     * 支持受检异常的本地锁执行。
     *
     * @since 0.3.0
     */
    public <T> T callWithLocalLock(String key, long waitTime, TimeUnit unit,
                                   Callable<T> action) throws Exception {
        return call(LockType.SYNCHRONIZED, key, waitTime, -1, unit, action);
    }

    /**
     * 获取锁超时时执行回退逻辑，而不是抛异常。
     *
     * @since 0.3.0
     */
    public <T> T withLock(String key, long waitTime, long leaseTime, TimeUnit unit,
                          Supplier<T> action, Supplier<T> onTimeout) {
        return executeWithFallback(LockType.REDIS, key, waitTime, leaseTime, unit, action, onTimeout);
    }

    /**
     * 本地锁获取超时时执行回退逻辑。
     *
     * @since 0.3.0
     */
    public <T> T withLocalLock(String key, long waitTime, TimeUnit unit,
                               Supplier<T> action, Supplier<T> onTimeout) {
        return executeWithFallback(LockType.SYNCHRONIZED, key, waitTime, -1, unit, action, onTimeout);
    }

    private <T> Optional<T> tryExecute(LockType lockType, String key, long waitTime, long leaseTime,
                                       TimeUnit unit, Supplier<T> action) {
        try {
            return Optional.ofNullable(execute(lockType, key, waitTime, leaseTime, unit, action));
        } catch (LockAcquireInterruptedException | LockAcquireFailureException e) {
            throw e;
        } catch (LockAcquireTimeoutException e) {
            return Optional.empty();
        }
    }

    private <T> T call(LockType lockType, String key, long waitTime, long leaseTime,
                       TimeUnit unit, Callable<T> action) throws Exception {
        try {
            return execute(lockType, key, waitTime, leaseTime, unit, () -> {
                try {
                    return action.call();
                } catch (Exception e) {
                    throw new CheckedExceptionWrapper(e);
                }
            });
        } catch (CheckedExceptionWrapper e) {
            throw e.unwrap();
        }
    }

    private <T> T executeWithFallback(LockType lockType, String key, long waitTime, long leaseTime,
                                      TimeUnit unit, Supplier<T> action, Supplier<T> onTimeout) {
        try {
            return execute(lockType, key, waitTime, leaseTime, unit, action);
        } catch (LockAcquireInterruptedException | LockAcquireFailureException e) {
            throw e;
        } catch (LockAcquireTimeoutException e) {
            return onTimeout.get();
        }
    }

    /**
     * 受检异常包装器，仅在 callWithLock 内部穿过 Supplier 使用。
     */
    private static final class CheckedExceptionWrapper extends RuntimeException {
        private CheckedExceptionWrapper(Exception cause) {
            super(cause);
        }

        private Exception unwrap() {
            return (Exception) getCause();
        }
    }

    /**
     * 按锁类型执行加锁逻辑，并统一处理超时/中断上报与锁释放。
     */
    private <T> T execute(LockType lockType, String key, long waitTime, long leaseTime,
                          TimeUnit unit, Supplier<T> action) {
        String fullKey = properties.getKeyPrefix() + key;
        LockExecutor executor = executor(lockType);
        LockHandle handle;
        try {
            handle = executor.acquire(fullKey, waitTime, leaseTime, unit);
        } catch (LockAcquireInterruptedException e) {
            reject(fullKey, lockType, "锁等待被中断");
            throw e;
        } catch (LockAcquireFailureException e) {
            // 基础设施故障必须走 fail-open/fail-close，不能按竞争超时处理
            return handleAcquireFailure(lockType, fullKey, action, e);
        } catch (LockAcquireTimeoutException e) {
            reject(fullKey, lockType, "锁等待超时");
            throw e;
        } catch (LockConfigurationException e) {
            // 配置错误不受 fail-open 影响
            throw e;
        } catch (RuntimeException e) {
            return handleAcquireFailure(lockType, fullKey, action, e);
        }
        recordAcquire(lockType, handle.acquireNanos());
        long holdStart = System.nanoTime();
        try {
            T result = action.get();
            if (result instanceof CompletionStage<?>) {
                throw new LockConfigurationException("LockTemplate 不支持返回 CompletionStage/CompletableFuture 的代码块："
                        + "锁必须由获取线程释放，异步完成线程无法安全解锁 - key: " + fullKey);
            }
            return result;
        } finally {
            if (metrics != null) {
                metrics.recordLockHold(lockType.name(), System.nanoTime() - holdStart);
            }
            closeHandle(handle, fullKey);
        }
    }

    private static void closeHandle(LockHandle handle, String fullKey) {
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (RuntimeException e) {
            // 释放异常不应覆盖业务异常或业务返回值
            log.warn("锁释放异常 - key: {}", fullKey, e);
        }
    }

    private LockExecutor executor(LockType lockType) {
        LockExecutor executor = executors.get(lockType);
        if (executor == null) {
            throw new LockConfigurationException("未装配锁执行器: " + lockType
                    + " - 请检查 guard.lock.enabled 与 Redisson 依赖；本地锁请使用 withLocalLock");
        }
        return executor;
    }

    /**
     * 锁基础设施故障处理：按 {@code guard.lock.fail-open} 放行或拒绝。
     */
    private <T> T handleAcquireFailure(LockType lockType, String fullKey,
                                       Supplier<T> action, RuntimeException cause) {
        if (properties.isFailOpen()) {
            log.warn("锁基础设施故障，fail-open 跳过加锁（本次不做互斥保护） - key: {}, 错误: {}",
                    fullKey, cause.getMessage());
            if (metrics != null) {
                metrics.incrementDegraded(GuardEventType.LOCK_DEGRADED.name());
            }
            publish(GuardEventType.LOCK_DEGRADED, fullKey, lockType,
                    "锁基础设施故障，fail-open: " + cause.getMessage());
            return action.get();
        }
        log.warn("锁基础设施故障，fail-close 拒绝 - key: {}, 错误: {}", fullKey, cause.getMessage());
        if (metrics != null) {
            metrics.incrementRejected(GuardEventType.LOCK_DEGRADED.name());
            metrics.incrementDegraded(GuardEventType.LOCK_DEGRADED.name());
        }
        publish(GuardEventType.LOCK_DEGRADED, fullKey, lockType,
                "锁基础设施故障，fail-close: " + cause.getMessage());
        throw new LockAcquireFailureException(fullKey, "获取锁失败（基础设施故障）: " + fullKey, cause);
    }

    /**
     * 上报一次成功获取锁的耗时（含等待时间）；超时失败不计入耗时分布。
     */
    private void recordAcquire(LockType lockType, long acquireNanos) {
        if (metrics != null) {
            metrics.recordLockAcquire(lockType.name(), acquireNanos);
        }
    }

    /**
     * 发布拒绝事件并累加拒绝指标，与声明式切面保持一致。
     */
    private void reject(String fullKey, LockType lockType, String reason) {
        if (metrics != null) {
            metrics.incrementRejected(GuardEventType.LOCK_TIMEOUT.name());
        }
        publish(GuardEventType.LOCK_TIMEOUT, fullKey, lockType, reason);
    }

    private void publish(GuardEventType type, String fullKey, LockType lockType, String reason) {
        GuardEventSupport.publish(eventPublisher, new GuardRejectedEvent(
                type, fullKey, "LockTemplate#" + lockType.name(), reason));
    }

    private static Map<LockType, LockExecutor> index(List<LockExecutor> executors) {
        Map<LockType, LockExecutor> indexed = new EnumMap<>(LockType.class);
        if (executors != null) {
            for (LockExecutor executor : executors) {
                if (executor != null && executor.type() != null) {
                    indexed.put(executor.type(), executor);
                }
            }
        }
        return indexed;
    }

    /**
     * 旧构造器兼容：本地锁始终存在；仅当传入 Redisson 客户端提供者时才创建 Redis 执行器。
     * 该分支在 classpath 无 Redisson 时不会被触发，因此本地锁仍可独立使用。
     */
    private static List<LockExecutor> legacyExecutors(ObjectProvider<RedissonClient> redissonProvider,
                                                      LocalLockManager localLockManager) {
        List<LockExecutor> executors = new ArrayList<>();
        executors.add(new LocalLockExecutor(localLockManager != null ? localLockManager : new LocalLockManager()));
        if (redissonProvider != null) {
            executors.add(new RedissonLockExecutor(redissonProvider));
        }
        return executors;
    }
}
