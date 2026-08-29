package io.github.biglv666.guard.lock;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁注解 —— 基于 Redisson 的方法级互斥。
 *
 * <p>锁粒度由 key 决定而非方法本身：不同 key 的调用完全并行，
 * 只有同 key 的调用互斥。例如 {@code key = "'stock:' + #skuId"}
 * 实现按 SKU 维度的库存互斥扣减。
 *
 * <p>leaseTime 默认 -1，即启用 Redisson 看门狗：持锁期间每 10 秒自动续期，
 * 方法执行多久锁就持有多久，进程崩溃后锁随默认 30 秒租期自动释放；
 * 显式指定 leaseTime 后看门狗不生效，须自行评估执行时长上限。
 *
 * <p>示例：
 * <pre>
 * &#64;DistributedLock(key = "'stock:' + #skuId", waitTime = 2, timeUnit = TimeUnit.SECONDS)
 * public void deductStock(Long skuId, int count) { ... }
 * </pre>
 *
 * @author Guard Team
 * @since 0.1.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    /**
     * 锁键的 SpEL 表达式，方法参数名可作为变量（如 {@code "#orderId"}），
     * 支持模板写法 {@code "order:#{#order.id}"}。
     *
     * <p>为空时使用方法级默认键（全限定类名#方法名），即整个方法维度互斥。
     */
    String key() default "";

    /**
     * 获取锁的最长等待时间；等待超时按 {@link #acquirePolicy()} 处理。
     */
    long waitTime() default 3;

    /**
     * 持锁时间；-1（默认）表示启用 Redisson 看门狗自动续期，
     * 其余值表示固定租期（到期自动释放，不再续期）。
     */
    long leaseTime() default -1;

    /**
     * 等待与持锁时间的时间单位，默认秒。
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 获取锁失败（等待超时）时的处理策略。
     */
    LockAcquirePolicy acquirePolicy() default LockAcquirePolicy.THROW;

    /**
     * {@link LockAcquirePolicy#CUSTOM} 策略下的回调处理器类型。
     * 优先从容器获取该类型的 Bean，不存在时通过无参构造实例化。
     * 仅 CUSTOM 策略需要配置。
     */
    Class<? extends LockAcquireFallbackHandler> handler() default LockAcquireFallbackHandler.class;
}
