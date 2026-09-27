package io.github.biglv666.guard.lock;

import io.github.biglv666.guard.GuardProperties;
import io.github.biglv666.guard.event.GuardEventType;
import io.github.biglv666.guard.event.GuardRejectedEvent;
import io.github.biglv666.guard.internal.GuardKeyUtils;
import io.github.biglv666.guard.internal.SpelKeyResolver;
import io.github.biglv666.guard.metrics.GuardMetrics;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link DistributedLock} 的方法级切面。
 *
 * <p>根据注解的 {@link LockType} 选择 Redisson 分布式锁或当前 JVM 内的本地锁。
 * 本地锁不要求容器中存在 {@link RedissonClient}；Redis 客户端仅在实际执行 Redis 路径时懒获取。</p>
 *
 * <p>锁获取失败统一按 THROW、SKIP、CUSTOM 策略处理，并在 finally 中安全释放已获取的锁。</p>
 *
 * @author Guard Team
 * @since 0.1.0
 */
@Aspect
public class LockAspect {

    private static final Logger log = LoggerFactory.getLogger(LockAspect.class);

    private final ObjectProvider<RedissonClient> redissonProvider;
    private final ObjectProvider<ApplicationContext> applicationContextProvider;
    private final SpelKeyResolver keyResolver;
    private final GuardProperties.Lock properties;
    private final GuardMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;
    private final LocalLockManager localLockManager;

    /**
     * 自定义回退处理器的解析缓存：类型 -> 实例（优先容器 Bean，其次无参构造实例化）。
     */
    private final Map<Class<? extends LockAcquireFallbackHandler>, LockAcquireFallbackHandler> handlerCache =
            new ConcurrentHashMap<>();

    /** 兼容旧版直接实例化方式。 */
    public LockAspect(ObjectProvider<RedissonClient> redissonProvider,
                      ObjectProvider<ApplicationContext> applicationContextProvider,
                      SpelKeyResolver keyResolver,
                      GuardProperties.Lock properties,
                      GuardMetrics metrics,
                      ApplicationEventPublisher eventPublisher) {
        this(redissonProvider, applicationContextProvider, keyResolver, properties, metrics,
                eventPublisher, new LocalLockManager());
    }

    public LockAspect(ObjectProvider<RedissonClient> redissonProvider,
                      ObjectProvider<ApplicationContext> applicationContextProvider,
                      SpelKeyResolver keyResolver,
                      GuardProperties.Lock properties,
                      GuardMetrics metrics,
                      ApplicationEventPublisher eventPublisher,
                      LocalLockManager localLockManager) {
        this.redissonProvider = redissonProvider;
        this.applicationContextProvider = applicationContextProvider;
        this.keyResolver = keyResolver;
        this.properties = properties;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
        this.localLockManager = localLockManager;
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
        String key = GuardKeyUtils.fullKey(properties.getKeyPrefix(), spelKey, method);
        LocalLockManager.Handle localHandle = null;
        RLock redisLock = null;
        boolean locked = false;
        boolean interrupted = false;
        long acquireStart = System.nanoTime();
        try {
            if (distributedLock.type() == LockType.SYNCHRONIZED) {
                localHandle = localLockManager.acquire(key);
                try {
                    locked = localHandle.lock().tryLock(distributedLock.waitTime(), distributedLock.timeUnit());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    interrupted = true;
                    log.warn("本地锁等待被中断 - key: {}, method: {}", key, method.getName());
                }
            } else {
                redisLock = requireClient().getLock(key);
                try {
                    locked = redisLock.tryLock(distributedLock.waitTime(), distributedLock.leaseTime(), distributedLock.timeUnit());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    interrupted = true;
                    log.warn("Redis 锁等待被中断 - key: {}, method: {}", key, method.getName());
                }
            }
            if (!locked) {
                metrics.incrementRejected(GuardEventType.LOCK_TIMEOUT.name());
                publish(key, method, interrupted ? "锁等待被中断" : "锁等待超时");
                if (interrupted) {
                    // 中断意味着调用方取消/线程关闭，与锁竞争超时语义不同：
                    // 无论获取失败策略如何，都以中断异常终止（中断标记已在上方恢复）
                    throw new LockAcquireInterruptedException(key,
                            "获取锁等待被中断: " + key);
                }
                return handleTimeout(pjp, distributedLock, key, method);
            }
            // 成功获取（含等待时间）计入锁耗时分布；超时失败已计入拒绝指标，不重复记录
            metrics.recordLockAcquire(distributedLock.type().name(), System.nanoTime() - acquireStart);
            return pjp.proceed();
        } finally {
            if (locked) {
                if (distributedLock.type() == LockType.SYNCHRONIZED) {
                    localHandle.lock().unlock();
                } else if (redisLock != null) {
                    try {
                        if (redisLock.isHeldByCurrentThread()) redisLock.unlock();
                    } catch (IllegalMonitorStateException e) {
                        log.warn("锁释放异常（租期可能已过期被自动释放）- key: {}, method: {}", key, method.getName(), e);
                    }
                }
            }
            if (localHandle != null) localHandle.close();
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
        ApplicationContext context = applicationContextProvider.getIfAvailable();
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
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new GuardRejectedEvent(GuardEventType.LOCK_TIMEOUT, key,
                    method.getDeclaringClass().getName() + "#" + method.getName(), reason));
        }
    }

    /**
     * 懒获取 RedissonClient：功能启用但未配置时，在首次加锁处抛出清晰的配置错误。
     */
    private RedissonClient requireClient() {
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException(
                    "检测到 @DistributedLock 注解，但容器中不存在 RedissonClient Bean。"
                            + "请引入 redisson 依赖并注册 RedissonClient（或使用 redisson-spring-boot-starter）；"
                            + "若暂不使用分布式锁，可配置 guard.lock.enabled=false。");
        }
        return client;
    }
}
