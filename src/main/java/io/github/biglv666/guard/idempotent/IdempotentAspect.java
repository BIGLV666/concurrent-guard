package io.github.biglv666.guard.idempotent;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 幂等切面 —— {@code @Idempotent} 的核心实现。
 *
 * <p>执行流程：
 * <ol>
 *   <li>解析 key（SpEL 或方法级默认键），拼接配置前缀；</li>
 *   <li>通过 {@link IdempotentPolicy} 尝试占位：占位失败抛 {@link IdempotentRejectedException}；
 *       策略故障按 fail-open（放行不占位）/ fail-close（拒绝）降级；</li>
 *   <li>占位成功后执行业务：正常返回则占位保留至 TTL；业务异常时按
 *       {@code rollbackOnException} 决定是否释放占位（释放失败仅记日志，不吞原异常）。</li>
 * </ol>
 *
 * <p>拒绝与降级拒绝都会发布 {@link GuardRejectedEvent} 并累加 Micrometer 指标。
 *
 * @author Guard Team
 * @since 0.1.0
 */
@Aspect
public class IdempotentAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);

    private final IdempotentPolicyProvider policyProvider;
    private final SpelKeyResolver keyResolver;
    private final GuardProperties.Idempotent properties;
    private final GuardMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;

    public IdempotentAspect(IdempotentPolicyProvider policyProvider,
                            SpelKeyResolver keyResolver,
                            GuardProperties.Idempotent properties,
                            GuardMetrics metrics,
                            ApplicationEventPublisher eventPublisher) {
        this.policyProvider = policyProvider;
        this.keyResolver = keyResolver;
        this.properties = properties;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 环绕拦截所有标注 {@code @Idempotent} 的方法。
     *
     * @param pjp        连接点
     * @param idempotent 方法上的幂等注解（由切点表达式自动绑定）
     * @return 业务方法返回值
     * @throws Throwable 业务异常原样传播；重复请求抛 {@link IdempotentRejectedException}
     */
    @Around(value = "@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        String spelKey = keyResolver.resolve(method, pjp.getArgs(), idempotent.key());
        String key = GuardKeyUtils.fullKey(properties.getKeyPrefix(), spelKey, method);
        Duration ttl = Duration.ofMillis(idempotent.timeUnit().toMillis(idempotent.ttl()));

        boolean acquired;
        try {
            acquired = requirePolicy().tryAcquire(key, ttl);
        } catch (Exception e) {
            // 策略实现自身故障：统一降级，不把基础设施故障直接抛给业务
            acquired = degradeOnPolicyFailure(key, method, e);
            if (!acquired) {
                throw new IdempotentRejectedException(key, "服务暂不可用，请稍后重试");
            }
            // fail-open：不占位直接放行，本次请求不做幂等防护
            return pjp.proceed();
        }

        if (!acquired) {
            reject(key, method, idempotent.message());
        }

        try {
            return pjp.proceed();
        } catch (Throwable businessError) {
            if (idempotent.rollbackOnException()) {
                releaseQuietly(key, businessError);
            }
            throw businessError;
        }
    }

    /**
     * 策略故障降级决策：fail-open 返回 true（放行）并记告警日志；
     * fail-close 发布降级事件、累加指标并返回 false（调用方将拒绝请求）。
     */
    private boolean degradeOnPolicyFailure(String key, Method method, Exception cause) {
        if (properties.isFailOpen()) {
            log.warn("幂等策略故障，fail-open 放行（本次不做幂等防护） - key: {}, method: {}, 错误: {}",
                    key, method.getName(), cause.getMessage());
            metrics.incrementRejected(GuardEventType.IDEMPOTENT_DEGRADED.name());
            return true;
        }
        log.warn("幂等策略故障，fail-close 拒绝 - key: {}, method: {}, 错误: {}",
                key, method.getName(), cause.getMessage());
        publish(GuardEventType.IDEMPOTENT_DEGRADED, key, method, "幂等策略故障，fail-close: " + cause.getMessage());
        metrics.incrementRejected(GuardEventType.IDEMPOTENT_DEGRADED.name());
        return false;
    }

    /**
     * 拒绝重复请求：发布事件、累加指标并抛出携带注解消息的异常。
     */
    private void reject(String key, Method method, String message) throws IdempotentRejectedException {
        publish(GuardEventType.IDEMPOTENT_REJECTED, key, method, "TTL 窗口内重复请求");
        metrics.incrementRejected(GuardEventType.IDEMPOTENT_REJECTED.name());
        throw new IdempotentRejectedException(key, message);
    }

    /**
     * 业务异常后释放占位。释放失败仅记 warn，绝不覆盖正在传播的业务异常。
     */
    private void releaseQuietly(String key, Throwable businessError) {
        try {
            requirePolicy().release(key);
        } catch (Exception releaseError) {
            log.warn("幂等占位回滚失败，TTL 内重试可能被拒绝 - key: {}, 业务异常: {}, 回滚错误: {}",
                    key, businessError.getClass().getSimpleName(), releaseError.getMessage());
        }
    }

    private void publish(GuardEventType type, String key, Method method, String reason) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new GuardRejectedEvent(type, key,
                    method.getDeclaringClass().getName() + "#" + method.getName(), reason));
        }
    }

    /**
     * 懒获取策略 Bean：功能启用但容器中没有 IdempotentPolicy 时，
     * 在首次使用处抛出清晰的配置错误（而非启动期崩溃或静默跳过）。
     */
    private IdempotentPolicy requirePolicy() {
        IdempotentPolicy policy = policyProvider.getIfAvailable();
        if (policy == null) {
            throw new IllegalStateException(
                    "检测到 @Idempotent 注解，但容器中不存在 IdempotentPolicy Bean。"
                            + "请引入 spring-boot-starter-data-redis 并配置 Redis 连接"
                            + "（默认使用 RedisSetNxIdempotentPolicy），或注册自定义 IdempotentPolicy。");
        }
        return policy;
    }

    /**
     * 策略的延迟解析入口，隔离 ObjectProvider 使切面便于单测。
     */
    @FunctionalInterface
    public interface IdempotentPolicyProvider {
        IdempotentPolicy getIfAvailable();
    }
}
