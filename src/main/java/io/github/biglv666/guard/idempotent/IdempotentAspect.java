package io.github.biglv666.guard.idempotent;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

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
 * <p>切面顺序：{@code @Order(0)} 使本切面运行在事务拦截器（默认最低优先级）之外——
 * 占位获取发生在事务开始之前，结果保存与占位释放发生在事务提交之后，
 * 避免"事务回滚但重放已保存的结果"的窗口。依赖此顺序的行为请勿随意调整切面优先级。
 *
 * @author Guard Team
 * @since 0.1.0
 */
@Aspect
@Order(0)
public class IdempotentAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);

    /**
     * tryReplay 的哨兵返回值：表示"无可重放结果"（仍在处理中 / 查询或反序列化失败），
     * 与"重放结果本身为 null"（void 方法）区分。
     */
    private static final Object REPLAY_NONE = new Object();

    /**
     * REPLAY 等待模式（{@code waitMillis > 0}）的重放轮询间隔（毫秒）。
     */
    private static final long REPLAY_POLL_INTERVAL_MILLIS = 50;

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

        // 装配与配置校验：策略缺失、REPLAY 不支持、ttl/waitMillis 非法属于编程/配置错误，
        // 必须在降级路径之外快速失败，避免被 fail-open 当作基础设施故障静默放行
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("@Idempotent 的 ttl 必须大于 0，当前配置: "
                    + idempotent.ttl() + " " + idempotent.timeUnit() + " - method: " + method.getName());
        }
        if (idempotent.waitMillis() < 0 || (idempotent.waitMillis() > 0 && mode != IdempotentMode.REPLAY)) {
            throw new IllegalStateException("@Idempotent 的 waitMillis 仅支持 REPLAY 模式且不能为负，"
                    + "当前配置: waitMillis=" + idempotent.waitMillis() + ", mode=" + mode
                    + " - method: " + method.getName());
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
                Object replayed = replayWhileWaiting(policy, codec, key, method, idempotent.waitMillis());
                if (replayed != REPLAY_NONE) {
                    return replayed;
                }
            }
            // reject 返回构建好的异常，调用点显式抛出：
            // 编译器保证该分支必然终止，token 在后续代码中恒非 null
            throw reject(policy, key, method, idempotent.message());
        }

        // 占位成功但存在可重放结果（主键已过期、哨兵中有前一个请求的结果）：
        // 直接重放并释放本次占位，避免业务重复执行
        if (mode == IdempotentMode.REPLAY) {
            Object sentinelReplayed = replaySentinelQuietly(policy, codec, key, method);
            if (sentinelReplayed != REPLAY_NONE) {
                releaseQuietly(policy, key, token, null);
                return sentinelReplayed;
            }
        }

        try {
            long businessStart = System.nanoTime();
            Object result = pjp.proceed();
            warnIfTtlExceeded(businessStart, ttl, key, method);
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
    /**
     * 重放查询中间结果。
     * available=true 表示 payload 可进入反序列化；fatalFailure=true 表示查询故障
     * （fail-close 降级已在本类型构造时发布，等待方应立即终止，避免事件/日志风暴）。
     */
    private record ReplayLookup(String payload, boolean available, boolean fatalFailure) {

        static ReplayLookup found(String payload) {
            return new ReplayLookup(payload, true, false);
        }

        static ReplayLookup notFound() {
            return new ReplayLookup(null, false, false);
        }

        static ReplayLookup failed() {
            return new ReplayLookup(null, false, true);
        }
    }

    /**
     * 单次重放查询：一次调用同时覆盖主键结果与慢请求完成哨兵
     * （首个请求业务略超 TTL 时，过期窗口内仍允许重放而非拒绝）。
     */
    private ReplayLookup lookupReplay(IdempotentPolicy policy, String key, Method method) {
        try {
            String payload = policy.loadResultForReplay(key);
            return payload != null ? ReplayLookup.found(payload) : ReplayLookup.notFound();
        } catch (Exception e) {
            log.warn("幂等结果重放查询故障，fail-close 拒绝 - key: {}, method: {}, 错误: {}",
                    key, method.getName(), e.getMessage());
            publish(GuardEventType.IDEMPOTENT_DEGRADED, key, method, "结果重放查询故障，fail-close: " + e.getMessage());
            metrics.incrementRejected(GuardEventType.IDEMPOTENT_DEGRADED.name());
            metrics.incrementDegraded(GuardEventType.IDEMPOTENT_DEGRADED.name());
            return ReplayLookup.failed();
        }
    }

    /**
     * 反序列化重放结果；失败按一致性优先拒绝该请求并发布降级事件，返回 {@link #REPLAY_NONE}。
     */
    private Object deserializeReplay(ReplayLookup lookup, ResultCodec codec, String key, Method method) {
        try {
            Object replayed = codec.deserialize(lookup.payload(), method.getGenericReturnType());
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

    private Object tryReplay(IdempotentPolicy policy, ResultCodec codec, String key, Method method) {
        ReplayLookup lookup = lookupReplay(policy, key, method);
        return lookup.available() ? deserializeReplay(lookup, codec, key, method) : REPLAY_NONE;
    }

    /**
     * 处理中重复请求的重放等待（{@code waitMillis > 0} 时启用）：
     * 以固定 50ms 间隔轮询重放结果，直到拿到结果或超时。
     * 查询故障或反序列化失败（基础设施/数据问题不会在毫秒级自愈）时立即终止等待，
     * 避免故障期间以 50ms 间隔反复发布降级事件与告警日志；中断时恢复中断标记并终止。
     *
     * <p>注意：等待持有当前调用线程（servlet 场景占用请求线程），
     * {@code waitMillis} 应配置为业务可接受的短暂窗口。
     *
     * @return 重放的返回值；超时/中断/故障/无结果时返回 {@link #REPLAY_NONE} 哨兵
     */
    private Object replayWhileWaiting(IdempotentPolicy policy, ResultCodec codec,
                                      String key, Method method, long waitMillis) {
        if (waitMillis <= 0) {
            return tryReplay(policy, codec, key, method);
        }
        long deadline = System.currentTimeMillis() + waitMillis;
        while (true) {
            ReplayLookup lookup = lookupReplay(policy, key, method);
            if (lookup.available()) {
                return deserializeReplay(lookup, codec, key, method);
            }
            if (lookup.fatalFailure()) {
                return REPLAY_NONE;
            }
            if (System.currentTimeMillis() >= deadline) {
                log.info("幂等重放等待超时，拒绝请求 - key: {}, method: {}, waitMillis: {}",
                        key, method.getName(), waitMillis);
                return REPLAY_NONE;
            }
            try {
                Thread.sleep(REPLAY_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("幂等重放等待被中断，拒绝请求 - key: {}, method: {}", key, method.getName());
                return REPLAY_NONE;
            }
        }
    }

    /**
     * 占位成功后的哨兵回查：主键上是本次自己的处理中令牌，
     * {@code loadResultForReplay} 会跳过它并返回哨兵中的前一个请求结果
     * （说明占位已过期、前一个业务已完成）。查询或反序列化失败按无哨兵处理
     * （正常执行业务），不影响主链路可用性。
     *
     * @return 哨兵中的重放结果；无哨兵时返回 {@link #REPLAY_NONE} 哨兵
     */
    private Object replaySentinelQuietly(IdempotentPolicy policy, ResultCodec codec, String key, Method method) {
        try {
            String payload = policy.loadResultForReplay(key);
            if (payload == null) {
                return REPLAY_NONE;
            }
            Object replayed = codec.deserialize(payload, method.getGenericReturnType());
            metrics.incrementReplayed(GuardEventType.IDEMPOTENT_REPLAYED.name());
            return replayed;
        } catch (Exception e) {
            log.warn("慢请求完成哨兵查询/反序列化失败，按无哨兵继续执行业务 - key: {}, method: {}, 错误: {}",
                    key, method.getName(), e.getMessage());
            return REPLAY_NONE;
        }
    }

    /**
     * 业务耗时超过 TTL 时记录告警与指标：占位可能已在业务完成前过期，
     * 窗口内新请求会重新进入业务，幂等窗口实际失效。
     */
    private void warnIfTtlExceeded(long businessStart, Duration ttl, String key, Method method) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - businessStart);
        if (elapsedMillis > ttl.toMillis()) {
            log.warn("幂等业务耗时超过 TTL，占位可能已过期（建议增大 ttl 或检查慢业务） - key: {}, method: {}, "
                            + "耗时: {}ms, ttl: {}ms",
                    key, method.getName(), elapsedMillis, ttl.toMillis());
            metrics.incrementTtlExceeded(GuardEventType.IDEMPOTENT_TTL_EXCEEDED.name());
        }
    }

    /**
     * 保存业务结果供重复请求重放。单次调用由策略按占位归属分流写入：
     * 归属写主键、过期写哨兵、被接管不写。保存失败仅记 warn：不影响业务返回值。
     */
    private void saveResultQuietly(IdempotentPolicy policy, ResultCodec codec,
                                   String key, String token, Object result, Duration ttl) {
        try {
            policy.saveResultForReplay(key, token, codec.serialize(result), ttl);
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
     * 构建拒绝重复请求的异常：发布事件、累加指标后返回携带注解消息与占位剩余 TTL 的异常，
     * 由调用点显式抛出（保证该分支在类型层面必然终止）。
     * 剩余 TTL 查询失败不影响拒绝本身（降级为 -1 未知）。
     */
    private IdempotentRejectedException reject(IdempotentPolicy policy, String key, Method method, String message) {
        publish(GuardEventType.IDEMPOTENT_REJECTED, key, method, "TTL 窗口内重复请求");
        metrics.incrementRejected(GuardEventType.IDEMPOTENT_REJECTED.name());
        long remainingTtl;
        try {
            remainingTtl = policy.remainingTtlMillis(key);
        } catch (Exception e) {
            remainingTtl = -1;
        }
        return new IdempotentRejectedException(key, message, remainingTtl);
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
            log.warn("幂等占位释放失败，TTL 内重试可能被拒绝 - key: {}, 触发场景: {}, 释放错误: {}",
                    key,
                    businessError != null ? "业务异常 " + businessError.getClass().getSimpleName() : "哨兵重放占位回收",
                    releaseError.getMessage());
        }
    }

    private void publish(GuardEventType type, String key, Method method, String reason) {
        GuardEventSupport.publish(eventPublisher, new GuardRejectedEvent(type, key,
                method.getDeclaringClass().getName() + "#" + method.getName(), reason));
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
