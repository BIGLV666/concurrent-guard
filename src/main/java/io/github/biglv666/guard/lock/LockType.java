package io.github.biglv666.guard.lock;

/**
 * 声明式锁的实现类型。
 *
 * <p>{@link #REDIS} 适用于多实例部署；{@link #SYNCHRONIZED} 仅保证当前 JVM 内互斥。
 * 局部代码块请使用 {@link LockTemplate} 的 Lambda API。</p>
 */
public enum LockType {
    /** 使用 Redisson 分布式锁。 */
    REDIS,
    /** 使用当前 JVM 内的本地锁。 */
    SYNCHRONIZED
}
