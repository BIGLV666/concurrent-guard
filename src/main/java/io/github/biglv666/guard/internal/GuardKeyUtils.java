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
     * 方法级默认键（0.2.x 旧格式）：{@code 全限定类名#方法名}。
     *
     * <p>该格式无法区分重载方法，0.3.0 起默认使用
     * {@link #methodKey(Method, boolean)} 的新格式；此方法保留用于兼容旧调用方。</p>
     *
     * @param method 被拦截的方法
     * @return 旧格式方法级默认键
     */
    public static String methodKey(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }

    /**
     * 方法级默认键：默认包含参数类型，避免重载方法互撞。
     *
     * @param method           被拦截的方法
     * @param legacyDefaultKey true = 使用 0.2.x 的 {@code 类名#方法名} 旧格式
     * @return 方法级默认键
     * @since 0.3.0
     */
    public static String methodKey(Method method, boolean legacyDefaultKey) {
        if (legacyDefaultKey) {
            return methodKey(method);
        }
        StringBuilder builder = new StringBuilder(method.getDeclaringClass().getName())
                .append('#').append(method.getName()).append('(');
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(parameterTypes[i].getName());
        }
        return builder.append(')').toString();
    }

    /**
     * 拼接完整键：{@code 前缀 + SpEL 解析值}。
     *
     * <p>兼容旧调用方：使用 0.2.x 旧格式的方法级默认键。</p>
     *
     * @param prefix  配置的键前缀
     * @param spelKey SpEL 解析出的业务键；null 表示未声明表达式
     * @param method  被拦截的方法
     * @return 完整键
     */
    public static String fullKey(String prefix, String spelKey, Method method) {
        return fullKey(prefix, spelKey, method, true);
    }

    /**
     * 拼接完整键：{@code 前缀 + SpEL 解析值}；未声明表达式时使用方法级默认键。
     *
     * @param prefix           配置的键前缀
     * @param spelKey          SpEL 解析出的业务键；null 表示未声明表达式
     * @param method           被拦截的方法
     * @param legacyDefaultKey true = 使用 0.2.x 旧格式默认键
     * @return 完整键
     * @since 0.3.0
     */
    public static String fullKey(String prefix, String spelKey, Method method, boolean legacyDefaultKey) {
        return prefix + (spelKey != null ? spelKey : methodKey(method, legacyDefaultKey));
    }
}
