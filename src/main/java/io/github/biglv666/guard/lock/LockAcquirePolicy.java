package io.github.biglv666.guard.lock;

/**
 * 分布式锁获取失败（等待超时）的处理策略。
 *
 * @author Guard Team
 * @since 0.1.0
 */
public enum LockAcquirePolicy {

    /**
     * 抛出 {@link LockAcquireTimeoutException}（默认）：适用于"拿不到锁就不能做"的强一致场景。
     */
    THROW,

    /**
     * 静默跳过：不执行业务方法，直接返回 null。
     * 适用于"已有别的执行者在做，跳过即可"的定时任务/异步去重场景。
     */
    SKIP,

    /**
     * 自定义回调：交由 {@link DistributedLock#handler()} 指定的
     * {@link LockAcquireFallbackHandler} 处理，以其返回值作为方法返回值。
     */
    CUSTOM
}
