package io.github.biglv666.guard.lock;

import java.lang.reflect.Method;

/**
 * 分布式锁获取失败的回调处理器 SPI —— 配合 {@link LockAcquirePolicy#CUSTOM} 使用。
 *
 * <p>实现类优先从 Spring 容器获取（可注入依赖），容器中不存在时按无参构造实例化。
 *
 * <pre>
 * &#64;Component
 * public class DeductStockFallback implements LockAcquireFallbackHandler {
 *     public Object handle(LockAcquireContext context) {
 *         return Result.fail("当前有其他请求正在处理该 SKU，请稍后重试");
 *     }
 * }
 * </pre>
 *
 * @author Guard Team
 * @since 0.1.0
 */
public interface LockAcquireFallbackHandler {

    /**
     * 锁等待超时后的回调。
     *
     * @param context 携带锁键、目标方法与入参的上下文
     * @return 作为本次方法调用的返回值
     */
    Object handle(LockAcquireContext context);

    /**
     * 锁获取失败上下文。
     *
     * @param key    完整锁键（含前缀）
     * @param method 目标方法
     * @param args   方法入参
     */
    record LockAcquireContext(String key, Method method, Object[] args) {
    }
}
