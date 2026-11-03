package io.github.biglv666.guard.lock;

/**
 * 已获取锁的句柄。
 *
 * <p>锁执行器在成功获取锁后返回该句柄，调用方必须在 finally 中关闭：
 * 关闭动作负责释放底层锁并清理本地锁槽位。关闭实现应当是幂等的，
 * 重复关闭不应产生副作用。</p>
 *
 * @author Guard Team
 * @since 0.2.1
 */
public interface LockHandle extends AutoCloseable {

    /**
     * 获取锁实际耗时（含等待时间），单位纳秒，用于指标上报。
     *
     * @return 从开始尝试获取到成功持有锁的耗时
     */
    long acquireNanos();

    /**
     * 释放锁并清理相关资源。实现应保证幂等，且不向调用方抛出异常
     * （释放失败由实现记录日志）。
     */
    @Override
    void close();
}
