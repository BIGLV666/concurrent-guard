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
 *   <li>装配与配置校验（策略 Bean 存在、REPLAY 支持重放、ttl 合法）——
 *       属于编程/配置错误，在降级路径之外直接抛出带修复指引的 {@link IllegalStateException}，
 *       绝不被 fail-open 吞掉；</li>
 *   <li>通过 {@link IdempotentPolicy} 带令牌占位：占位失败抛 {@link IdempotentRejectedException}；
 *       策略实现自身故障按 fail-open（放行不占位）/ fail-close（拒绝）降级；</li>
 *   <li>REPLAY 模式下占位失败时先尝试结果重放：窗口内业务已完成的同 key 请求
 *       直接返回首次结果，仍在处理中或结果不可用时退回拒绝；</li>
 *   <li>占位成功后执行业务：正常返回则占位保留至 TTL（REPLAY 模式下同时保存序列化结果）；
 *       业务异常时按 {@code rollbackOnException} 决定是否释放占位
 *       （释放与保存均校验占位归属，不会误伤其他请求的占位；释放失败仅记日志，不吞原异常）。</li>
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

    /**
     * tryReplay 的哨兵返回值：表示"无可重放结果"（仍在处理中 / 查询或反序列化失败），
     * 与"重放结果本身为 null"（void 方法）区分。
     */
    private static final Object REPLAY_NONE = new Object();

    private final IdempotentPolicyProvider policyProvider;
    private final ResultCodecProvider codecProvider;
    private final SpelKeyResolver keyResolver;
    private final GuardProperties.Idempotent properties;
    private final GuardMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;

    public IdempotentAspect(IdempotentPolicyProvider policyProvider,
                            ResultCodecProvider codecProvider,
                            SpelKeyResolver keyResolver,
                            GuardProperties.Idempotent properties,
                            GuardMetrics metrics,
                            ApplicationEventPublisher eventPublisher) {
        this.policyProvider = policyProvider;
        this.codecProvider = codecProvider;
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
        IdempotentMode mode = idempotent.mode();

        // 装配与配置校验：策略缺失、REPLAY 不支持、ttl 非法属于编程/配置错误，
        // 必须在降级路径之外快速失败，避免被 fail-open 当作基础设施故障静默放行
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("@Idempotent 的 ttl 必须大于 0，当前配置: "
                    + idempotent.ttl() + " " + idempotent.timeUnit() + " - method: " + method.getName());
        }
        IdempotentPolicy policy = requirePolicy();
        ResultCodec codec = null;
        if (mode == IdempotentMode.REPLAY) {
            codec = requireReplaySupport(policy);
        }

        String token;
        try {
            token = policy.tryAcquireToken(key, ttl);
        } catch (Exception e) {
            // 策略实现自身故障：统一降级，不把基础设施故障直接抛给业务
            if (!degradeOnPolicyFailure(key, method, e)) {
                throw new IdempotentRejectedException(key, "服务暂不可用，请稍后重试");
            }
            // fail-open：不占位直接放行，本次请求不做幂等防护
            return pjp.proceed();
        }

        if (token == null) {
            if (mode == IdempotentMode.REPLAY) {
                Object replayed = tryReplay(policy, codec, key, method);
                if (replayed != REPLAY_NONE) {
                    return replayed;
                }
            }
            reject(key, method, idempotent.message());
        }

        try {
            Object result = pjp.proceed();
            if (mode == IdempotentMode.REPLAY) {
                saveResultQuietly(policy, codec, key, token, result, ttl);
            }
            return result;
        } catch (Throwable businessError) {
            if (idempotent.rollbackOnException()) {
                releaseQuietly(policy, key, token, businessError);
            }
            throw businessError;
        }
    }

    /**
     * REPLAY 模式的重复请求处理：尝试加载并反序列化首次结果。
     *
     * <p>结果查询或反序列化故障按一致性优先处理（拒绝请求并发布降级事件），
     * 避免在无法确认结果的情况下放行导致业务重复执行。
     *
     * @return 重放的返回值；无可重放结果时返回 {@link #REPLAY_NONE} 哨兵
     */
    private Object tryReplay(IdempotentPolicy policy, ResultCodec codec, String key, Method method) {
        String payload;
        try {
            payload = policy.loadResult(key);
        } catch (Exception e) {
            log.warn("幂等结果重放查询故障，fail-close 拒绝 - key: {}, method: {}, 错误: {}",
                    key, method.getName(), e.getMessage());
            publish(GuardEventType.IDEMPOTENT_DEGRADED, key, method, "结果重放查询故障，fail-close: " + e.getMessage());
            metrics.incrementRejected(GuardEventType.IDEMPOTENT_DEGRADED.name());
            metrics.incrementDegraded(GuardEventType.IDEMPOTENT_DEGRADED.name());
            return REPLAY_NONE;
        }
        if (payload == null) {
            // 首个请求仍在处理中（占位值还是处理中令牌），无结果可重放
            return REPLAY_NONE;
        }
        try {
            Object replayed = codec.deserialize(payload, method.getGenericReturnType());
            metrics.incrementReplayed(GuardEventType.IDEMPOTENT_REPLAYED.name());
            return replayed;
        } catch (Exception e) {
            log.warn("幂等重放结果反序列化失败，拒绝本次请求 - key: {}, method: {}, 错误: {}",
                    key, method.getName(), e.getMessage());
            publish(GuardEventType.IDEMPOTENT_DEGRADED, key, method, "重放结果反序列化失败: " + e.getMessage());
            metrics.incrementRejected(GuardEventType.IDEMPOTENT_DEGRADED.name());
            metrics.incrementDegraded(GuardEventType.IDEMPOTENT_DEGRADED.name());
            return REPLAY_NONE;
        }
    }

    /**
     * 保存业务结果供重复请求重放。保存前校验占位归属（令牌不符时跳过写入，
     * 避免覆盖其他请求的占位）；保存失败仅记 warn：
     * 占位键保持"处理中"令牌，窗口内重复请求退回拒绝行为，不影响业务返回值。
     */
    private void saveResultQuietly(IdempotentPolicy policy, ResultCodec codec,
                                   String key, String token, Object result, Duration ttl) {
        try {
            policy.saveResultIfOwned(key, token, codec.serialize(result), ttl);
        } catch (Exception e) {
            log.warn("幂等结果保存失败，窗口内重复请求将退回拒绝行为 - key: {}, 错误: {}", key, e.getMessage());
        }
    }

    /**
     * REPLAY 模式的前置校验：策略不支持重放、或容器中没有 ResultCodec 时，
     * 在占位前抛出清晰的配置错误（而非静默降级为拒绝模式）。
     *
     * @return 校验通过的结果编解码器
     */
    private ResultCodec requireReplaySupport(IdempotentPolicy policy) {
        if (!policy.supportsReplay()) {
            throw new IllegalStateException(
                    "@Idempotent(mode = REPLAY) 要求 IdempotentPolicy 支持结果重放，"
                            + "但当前策略 " + policy.getClass().getName() + " 的 supportsReplay() 返回 false。"
                            + "请实现 saveResult/loadResult 并覆写 supportsReplay()，或改用默认的"
                            + " RedisSetNxIdempotentPolicy / mode = REJECT。");
        }
        return requireCodec();
    }

    /**
     * 懒获取结果编解码器：REPLAY 模式下容器中没有 ResultCodec 时抛出清晰的配置错误。
     */
    private ResultCodec requireCodec() {
        ResultCodec codec = codecProvider.getIfAvailable();
        if (codec == null) {
            throw new IllegalStateException(
                    "@Idempotent(mode = REPLAY) 需要容器中存在 ResultCodec Bean 用于结果序列化。"
                            + "请引入 Jackson 依赖（自动装配默认的 JacksonResultCodec），或注册自定义 ResultCodec。");
        }
        return codec;
    }

    /**
     * 结果编解码器的延迟解析入口，隔离 ObjectProvider 使切面便于单测。
     *
     * @since 0.2.0
     */
    @FunctionalInterface
    public interface ResultCodecProvider {
        ResultCodec getIfAvailable();
    }

    /**
     * 策略故障降级决策：fail-open 返回 true（放行）并记告警日志，降级计入
     * {@code guard_degraded_total}（放行不是拒绝，不计入 {@code guard_rejected_total}）；
     * fail-close 发布降级事件、同时累加降级与拒绝指标并返回 false（调用方将拒绝请求）。
     */
    private boolean degradeOnPolicyFailure(String key, Method method, Exception cause) {
        if (properties.isFailOpen()) {
            log.warn("幂等策略故障，fail-open 放行（本次不做幂等防护） - key: {}, method: {}, 错误: {}",
                    key, method.getName(), cause.getMessage());
            metrics.incrementDegraded(GuardEventType.IDEMPOTENT_DEGRADED.name());
            return true;
        }
        log.warn("幂等策略故障，fail-close 拒绝 - key: {}, method: {}, 错误: {}",
                key, method.getName(), cause.getMessage());
        publish(GuardEventType.IDEMPOTENT_DEGRADED, key, method, "幂等策略故障，fail-close: " + cause.getMessage());
        metrics.incrementRejected(GuardEventType.IDEMPOTENT_DEGRADED.name());
        metrics.incrementDegraded(GuardEventType.IDEMPOTENT_DEGRADED.name());
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
     * 业务异常后释放占位。释放前校验占位归属（令牌不符时跳过删除，
     * 避免误删 TTL 耗尽后其他请求新写入的占位）。
     * 释放失败仅记 warn，绝不覆盖正在传播的业务异常。
     */
    private void releaseQuietly(IdempotentPolicy policy, String key, String token, Throwable businessError) {
        try {
            policy.releaseIfOwned(key, token);
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
