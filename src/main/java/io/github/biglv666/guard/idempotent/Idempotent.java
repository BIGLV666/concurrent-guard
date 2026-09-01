package io.github.biglv666.guard.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 幂等注解 —— 防重复提交。
 *
 * <p>方法调用前先以 SpEL 解析出的 key 在 Redis 执行 setnx 占位：
 * 占位成功则执行业务方法并保留占位至 TTL 到期；TTL 窗口内的同 key 重复请求
 * 抛出 {@link IdempotentRejectedException}。
 *
 * <p>业务方法抛出异常时，默认回滚（删除）占位键，允许调用方重试；
 * 通过 {@link #rollbackOnException()} 可关闭该行为（异常后同样占用 TTL，
 * 适用于"失败也不能立刻重试"的场景，如资金扣减）。
 *
 * <p>示例：
 * <pre>
 * &#64;Idempotent(key = "#order.orderId", ttl = 30, timeUnit = TimeUnit.SECONDS)
 * &#64;PostMapping("/order")
 * public Result submit(@RequestBody Order order) { ... }
 * </pre>
 *
 * @author Guard Team
 * @since 0.1.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * 幂等键的 SpEL 表达式，方法参数名可作为变量（如 {@code "#orderId"}），
     * 支持模板写法 {@code "order:#{#order.id}"}。
     *
     * <p>为空时使用方法级默认键（全限定类名#方法名），
     * 即整个方法在 TTL 内只允许一个请求进入。
     */
    String key() default "";

    /**
     * 占位时长。建议略大于业务方法的正常完成时间，
     * 过短可能放过慢请求的重复提交，过长会拒绝正常的后续请求。
     */
    long ttl() default 10;

    /**
     * 占位时长的时间单位，默认秒。
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 业务方法抛出异常时是否回滚（删除）占位键：
     * true（默认）= 允许失败后立即重试；
     * false = 异常同样占用 TTL，TTL 内的重试都会被拒绝。
     */
    boolean rollbackOnException() default true;

    /**
     * 重复请求被拒绝时，{@link IdempotentRejectedException} 携带的提示信息。
     */
    String message() default "请求处理中，请勿重复提交";

    /**
     * 幂等模式：
     * {@link IdempotentMode#REJECT}（默认）= 重复请求直接拒绝；
     * {@link IdempotentMode#REPLAY} = 业务正常完成后，窗口内的重复请求重放首次的返回值
     * （首次仍在处理中时仍拒绝；异常回滚行为不变）。
     */
    IdempotentMode mode() default IdempotentMode.REJECT;
}
