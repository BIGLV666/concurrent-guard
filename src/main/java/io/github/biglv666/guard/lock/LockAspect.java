package io.github.biglv666.guard.lock;

import io.github.biglv666.guard.GuardProperties;
import io.github.biglv666.guard.event.GuardEventType;
import io.github.biglv666.guard.event.GuardRejectedEvent;
import io.github.biglv666.guard.internal.GuardEventSupport;
import io.github.biglv666.guard.internal.GuardKeyUtils;
import io.github.biglv666.guard.internal.SpelKeyResolver;
import io.github.biglv666.guard.metrics.GuardMetrics;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link DistributedLock} 的方法级切面。
 *
 * <p>根据注解的 {@link LockType} 选择对应的 {@link LockExecutor}：
 * {@link LockType#REDIS} 使用 Redisson 分布式锁，{@link LockType#SYNCHRONIZED} 使用当前 JVM 内的本地锁。
 * 本地锁不要求 classpath 中存在 Redisson；Redis 客户端仅在实际执行 Redis 路径时懒获取。</p>
 *
 * <p>锁获取失败统一按 THROW、SKIP、CUSTOM 策略处理，并在 finally 中安全释放已获取的锁。</p>
 *
 * <p>切面顺序：{@code @Order(100)} 使本切面运行在事务拦截器（默认最低优先级）之外，
 * 并位于 {@code @Idempotent} 切面（{@code @Order(0)}）之内——同一方法同时使用两种注解时，
 * 先做幂等判定，再竞争锁。</p>
 *
 * @author Guard Team
 * @since 0.1.0
 */
@Aspect
@Order(100)
public class LockAspect {

    private static final Logger log = LoggerFactory.getLogger(LockAspect.class);

    private final Map<LockType, LockExecutor> executors;
    private final ObjectProvider<ApplicationContext> applicationContextProvider;
    private final SpelKeyResolver keyResolver;
    private final GuardProperties.Lock properties;
    private final GuardProperties.Compat compat;
    private final GuardMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 自定义回退处理器的解析缓存：类型 -> 实例（优先容器 Bean，其次无参构造实例化）。
     */
    private final Map<Class<? extends LockAcquireFallbackHandler>, LockAcquireFallbackHandler> handlerCache =
            new ConcurrentHashMap<>();

    /**
     * 标准构造器：按 {@link LockType} 索引可用的锁执行器。
     *
     * @param executors                       锁执行器列表（至少包含本地锁执行器）
     * @param applicationContextProvider      用于解析自定义回退处理器 Bean
     * @param keyResolver                     SpEL key 解析器
     * @param properties                      锁模块配置
     * @param metrics                         指标上报器
     * @param eventPublisher                  事件发布器
     * @since 0.2.1
     */
    public LockAspect(List<LockExecutor> executors,
                      ObjectProvider<ApplicationContext> applicationContextProvider,
                      SpelKeyResolver keyResolver,
                      GuardProperties.Lock properties,
                      GuardProperties.Compat compat,
                      GuardMetrics metrics,
                      ApplicationEventPublisher eventPublisher) {
        this.executors = index(executors);
        this.applicationContextProvider = applicationContextProvider;
        this.keyResolver = keyResolver;
        this.properties = properties;
        this.compat = compat;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 兼容旧版直接实例化方式。
     *
     * @deprecated 请使用接收 {@link LockExecutor} 列表的构造器；旧构造器依赖 Redisson 类型。
     */
    @Deprecated
    public LockAspect(ObjectProvider<RedissonClient> redissonProvider,
                      ObjectProvider<ApplicationContext> applicationContextProvider,
                      SpelKeyResolver keyResolver,
                      GuardProperties.Lock properties,
                      GuardMetrics metrics,
                      ApplicationEventPublisher eventPublisher) {
        this(legacyExecutors(redissonProvider, new LocalLockManager()), applicationContextProvider,
                keyResolver, properties, new GuardProperties.Compat(), metrics, eventPublisher);
    }

    /**
     * 兼容旧版直接实例化方式。
     *
     * @deprecated 请使用接收 {@link LockExecutor} 列表的构造器；旧构造器依赖 Redisson 类型。
     */
    @Deprecated
    public LockAspect(ObjectProvider<RedissonClient> redissonProvider,
                      ObjectProvider<ApplicationContext> applicationContextProvider,
                      SpelKeyResolver keyResolver,
                      GuardProperties.Lock properties,
                      GuardMetrics metrics,
                      ApplicationEventPublisher eventPublisher,
                      LocalLockManager localLockManager) {
        this(legacyExecutors(redissonProvider, localLockManager), applicationContextProvider,
                keyResolver, properties, new GuardProperties.Compat(), metrics, eventPublisher);
    }

    /**
     * 环绕拦截所有标注 {@code @DistributedLock} 的方法。
     *
     * @param pjp             连接点
     * @param distributedLock 方法上的锁注解（由切点表达式自动绑定）
     * @return 业务方法返回值；SKIP 策略超时时返回 null
     * @throws Throwable 业务异常原样传播；THROW 策略超时抛 {@link LockAcquireTimeoutException}
     */
    @Around(value = "@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint pjp, DistributedLock distributedLock) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        String spelKey = keyResolver.resolve(method, pjp.getArgs(), distributedLock.key());
        String key = GuardKeyUtils.fullKey(properties.getKeyPrefix(), spelKey, method,
                compat != null && compat.isLegacyDefaultKey());
        LockType lockType = distributedLock.type();
        LockExecutor executor = executors.get(lockType);
        if (executor == null) {
            throw new LockConfigurationException("未装配锁执行器: " + lockType
                    + " - 请检查 guard.lock.enabled 与 Redisson 依赖；本地锁请使用 type = LockType.SYNCHRONIZED");
        }
        if (isCompletionStage(method)) {
            throw new LockConfigurationException("@DistributedLock 不支持返回 CompletionStage/CompletableFuture 的方法："
                    + "锁必须由获取线程释放，异步完成线程无法安全解锁 - method: " + method.getName()
                    + "。请在异步任务内部使用 LockTemplate，或改用幂等/其他并发控制方案。");
        }

        LockHandle handle = null;
        long holdStart = 0L;
        try {
            try {
                handle = executor.acquire(key, distributedLock.waitTime(),
                        distributedLock.leaseTime(), distributedLock.timeUnit());
                holdStart = System.nanoTime();
            } catch (LockAcquireInterruptedException e) {
                metrics.incrementRejected(GuardEventType.LOCK_TIMEOUT.name());
                publish(key, method, "锁等待被中断");
                // 中断意味着调用方取消/线程关闭，与锁竞争超时语义不同：
                // 无论获取失败策略如何，都以中断异常终止（中断标记已由执行器恢复）
                throw e;
            } catch (LockAcquireFailureException e) {
                // 基础设施故障必须走 fail-open/fail-close，不能按竞争超时处理
                return handleAcquireFailure(pjp, key, method, e);
            } catch (LockAcquireTimeoutException e) {
                metrics.incrementRejected(GuardEventType.LOCK_TIMEOUT.name());
                publish(key, method, "锁等待超时");
                return handleTimeout(pjp, distributedLock, key, method);
            } catch (LockConfigurationException e) {
                // 配置错误不受 fail-open 影响
                throw e;
            } catch (RuntimeException e) {
                return handleAcquireFailure(pjp, key, method, e);
            }
            // 成功获取（含等待时间）计入锁耗时分布；超时失败已计入拒绝指标，不重复记录
            metrics.recordLockAcquire(lockType.name(), handle.acquireNanos());
            return pjp.proceed();
        } finally {
            if (handle != null) {
                metrics.recordLockHold(lockType.name(), System.nanoTime() - holdStart);
            }
            closeHandle(handle, key, method);
        }
    }

    /**
     * 异步结果挂接：锁必须持有到 CompletionStage 真正完成，而不是方法返回时刻。
     */
    private static boolean isCompletionStage(Method method) {
        return CompletionStage.class.isAssignableFrom(method.getReturnType());
    }

    private static void closeHandle(LockHandle handle, String key, Method method) {
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (RuntimeException e) {
            // 释放异常不应覆盖业务异常或业务返回值
            log.warn("锁释放异常 - key: {}, method: {}", key, method.getName(), e);
        }
    }

    /**
     * 按注解策略处理获取锁失败。
     */
    private Object handleTimeout(ProceedingJoinPoint pjp, DistributedLock distributedLock,
                                 String key, Method method) throws Throwable {
        switch (distributedLock.acquirePolicy()) {
            case SKIP:
                log.info("获取锁超时，SKIP 跳过执行 - key: {}, method: {}", key, method.getName());
                return null;
            case CUSTOM:
                LockAcquireFallbackHandler handler = resolveHandler(distributedLock.handler());
                return handler.handle(new LockAcquireFallbackHandler.LockAcquireContext(key, method, pjp.getArgs()));
            case THROW:
            default:
                throw new LockAcquireTimeoutException(key,
                        "获取锁超时: " + key + "（等待 " + distributedLock.waitTime() + " "
                                + distributedLock.timeUnit() + "）");
        }
    }

    /**
     * 锁基础设施故障处理：按 {@code guard.lock.fail-open} 放行或拒绝。
     * fail-open 会放弃互斥保证，只适合业务能容忍并发的场景。
     */
    private Object handleAcquireFailure(ProceedingJoinPoint pjp, String key, Method method,
                                        RuntimeException cause) throws Throwable {
        if (properties.isFailOpen()) {
            log.warn("锁基础设施故障，fail-open 跳过加锁（本次不做互斥保护） - key: {}, method: {}, 错误: {}",
                    key, method.getName(), cause.getMessage());
            metrics.incrementDegraded(GuardEventType.LOCK_DEGRADED.name());
            publish(GuardEventType.LOCK_DEGRADED, key, method,
                    "锁基础设施故障，fail-open: " + cause.getMessage());
            return pjp.proceed();
        }
        log.warn("锁基础设施故障，fail-close 拒绝 - key: {}, method: {}, 错误: {}",
                key, method.getName(), cause.getMessage());
        metrics.incrementRejected(GuardEventType.LOCK_DEGRADED.name());
        metrics.incrementDegraded(GuardEventType.LOCK_DEGRADED.name());
        publish(GuardEventType.LOCK_DEGRADED, key, method,
                "锁基础设施故障，fail-close: " + cause.getMessage());
        throw new LockAcquireFailureException(key, "获取锁失败（基础设施故障）: " + key, cause);
    }

    /**
     * 解析自定义回退处理器：优先取容器中的 Bean（可注入依赖），否则无参构造实例化；结果按类型缓存。
     * （key 为通配符泛型时 computeIfAbsent 会产生捕获不兼容，故用 get/putIfAbsent 两步写法）
     */
    private LockAcquireFallbackHandler resolveHandler(Class<? extends LockAcquireFallbackHandler> handlerClass) {
        LockAcquireFallbackHandler cached = handlerCache.get(handlerClass);
        if (cached != null) {
            return cached;
        }
        LockAcquireFallbackHandler created = createHandler(handlerClass);
        LockAcquireFallbackHandler existing = handlerCache.putIfAbsent(handlerClass, created);
        return existing != null ? existing : created;
    }

    /**
     * 实际创建回退处理器：优先取容器中的 Bean，其次反射无参构造。
     */
    private LockAcquireFallbackHandler createHandler(Class<? extends LockAcquireFallbackHandler> handlerClass) {
        ApplicationContext context = applicationContextProvider != null
                ? applicationContextProvider.getIfAvailable() : null;
        if (context != null) {
            Map<String, ? extends LockAcquireFallbackHandler> beans = context.getBeansOfType(handlerClass);
            if (!beans.isEmpty()) {
                return beans.values().iterator().next();
            }
        }
        try {
            return handlerClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法创建锁回退处理器 " + handlerClass.getName()
                    + "：容器中无该类型 Bean，且缺少无参构造方法", e);
        }
    }

    private void publish(String key, Method method, String reason) {
        publish(GuardEventType.LOCK_TIMEOUT, key, method, reason);
    }

    private void publish(GuardEventType type, String key, Method method, String reason) {
        GuardEventSupport.publish(eventPublisher, new GuardRejectedEvent(type, key,
                method.getDeclaringClass().getName() + "#" + method.getName(), reason));
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
