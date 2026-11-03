package io.github.biglv666.guard.lock;

import java.util.concurrent.TimeUnit;

/**
 * 锁执行器 SPI：把"获取哪种锁"与"如何执行加锁逻辑"解耦。
 *
 * <p>本接口使 {@link LockAspect} 与 {@link LockTemplate} 不再直接依赖 Redisson 类型，
 * 从而支持"只使用本地锁、classpath 中没有 Redisson"的部署方式。
 * 默认提供两个实现：
 * <ul>
 *   <li>{@link LocalLockExecutor}：JVM 本地锁，始终可用；</li>
 *   <li>{@link RedissonLockExecutor}：Redis 分布式锁，仅在 classpath 存在 Redisson 时装配。</li>
 * </ul>
 *
 * <p>实现约定：获取失败必须抛出 {@link LockAcquireTimeoutException}（等待超时）
 * 或 {@link LockAcquireInterruptedException}（等待被中断），不得静默返回未加锁的句柄。
 *
 * @author Guard Team
 * @since 0.2.1
 */
public interface LockExecutor {

    /**
     * 该执行器负责的锁类型。
     */
    LockType type();

    /**
     * 获取锁。
     *
     * @param key       完整锁键（含配置前缀）
     * @param waitTime  最长等待时间
     * @param leaseTime 持锁时间；{@code -1} 表示使用 Redisson 看门狗自动续期（本地锁忽略）
     * @param unit      时间单位
     * @return 已持有的锁句柄，调用方必须在 finally 中关闭
     * @throws LockAcquireTimeoutException     等待超时未获取到锁
     * @throws LockAcquireInterruptedException 等待期间线程被中断（中断标记已恢复）
     */
    LockHandle acquire(String key, long waitTime, long leaseTime, TimeUnit unit);
}
