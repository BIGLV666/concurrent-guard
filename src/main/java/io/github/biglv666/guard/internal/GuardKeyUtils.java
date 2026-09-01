package io.github.biglv666.guard.internal;

import java.lang.reflect.Method;

/**
 * Guard 键工具 —— 统一"方法级默认键"与"完整键拼接"规则，供幂等与锁切面共用。
 *
 * @author Guard Team
 * @since 0.1.0
 */
public final class GuardKeyUtils {

    private GuardKeyUtils() {
    }

    /**
     * 方法级默认键：{@code 全限定类名#方法名}。
     *
     * <p>用于注解未声明 key 表达式的场景，效果是整个方法维度互斥/幂等。
     *
     * @param method 被拦截的方法
     * @return 方法级默认键
     */
    public static String methodKey(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }

    /**
     * 拼接完整键：{@code 前缀 + SpEL 解析值}。
     *
     * <p>{@code spelKey} 为 null 仅表示注解未声明 key 表达式，此时回退方法级默认键；
     * 表达式已声明但求值为 null/空白的场景由 {@link SpelKeyResolver} 快速失败，不会进入本方法。
     *
     * @param prefix  配置的键前缀
     * @param spelKey SpEL 解析出的业务键；null 表示未声明表达式
     * @param method  被拦截的方法
     * @return 完整键
     */
    public static String fullKey(String prefix, String spelKey, Method method) {
        return prefix + (spelKey != null ? spelKey : methodKey(method));
    }
}
