package io.github.biglv666.guard.lock;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 方法级锁注解。需要只保护局部代码时，请使用 {@link LockTemplate} 的 Lambda API。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {
    /** 锁键 SpEL；为空时使用类名与方法名作为默认键。 */
    String key() default "";

    /** 锁实现类型，默认 Redis 以兼容已有用法。 */
    LockType type() default LockType.REDIS;

    /** 获取锁的最长等待时间。 */
    long waitTime() default 3;

    /** 持锁时间；Redis 为 -1 时使用看门狗，本地锁忽略此属性。 */
    long leaseTime() default -1;

    /** 等待与持锁时间单位。 */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /** 获取锁失败时的处理策略。 */
    LockAcquirePolicy acquirePolicy() default LockAcquirePolicy.THROW;

    /** CUSTOM 策略的回退处理器。 */
    Class<? extends LockAcquireFallbackHandler> handler() default LockAcquireFallbackHandler.class;
}
