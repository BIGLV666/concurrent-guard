package io.github.biglv666.guard.internal;

import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 注解 key 的 SpEL 解析器。
 *
 * <p>支持两种写法：
 * <ul>
 *   <li>纯表达式：{@code @Idempotent(key = "#orderId")}</li>
 *   <li>模板表达式：{@code @Idempotent(key = "order:#{#order.id}")}，便于在键里携带字面前缀</li>
 * </ul>
 *
 * <p>表达式按 {@link Method} 编译一次并缓存，求值使用只读数据绑定的
 * {@link SimpleEvaluationContext}（不暴露类型引用、静态方法等能力，防止表达式注入），
 * 上下文变量为方法参数名（要求编译期开启 {@code -parameters}，
 * spring-boot-starter-parent 默认开启）。
 *
 * <p>与限流 starter 的可选后缀不同：幂等与锁的 key 是语义必需项，
 * 因此解析/求值失败一律快速失败（抛 {@link KeyResolveException}），
 * 避免静默回退到方法级 key 导致防护粒度意外变粗。
 *
 * @author Guard Team
 * @since 0.1.0
 */
public class SpelKeyResolver {

    private final ExpressionParser parser = new SpelExpressionParser();

    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * 编译结果按 Method 缓存；值为 null 表示该注解未声明 key 表达式（方法级默认键）。
     */
    private final ConcurrentMap<CacheKey, Expression> expressionCache = new ConcurrentHashMap<>();

    /**
     * 解析注解 key 表达式。
     *
     * @param method        被拦截的方法
     * @param args          方法实际入参（与方法参数名按位置对应）
     * @param keyExpression 注解声明的 key 表达式；允许为 null 或空白，表示方法级默认键
     * @return 解析出的 key 值；keyExpression 为空白时返回 null
     * @throws KeyResolveException 表达式编译或求值失败时抛出（快速失败，不静默回退）
     */
    public String resolve(Method method, Object[] args, String keyExpression) {
        if (keyExpression == null || keyExpression.isBlank()) {
            return null;
        }
        CacheKey cacheKey = new CacheKey(method, keyExpression);
        Expression expression = expressionCache.computeIfAbsent(cacheKey, this::compile);
        try {
            SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
            String[] paramNames = parameterNameDiscoverer.getParameterNames(method);
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length && i < args.length; i++) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }
            Object value = expression.getValue(context);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            throw new KeyResolveException("key 表达式求值失败 - method: " + method.getName()
                    + ", key: '" + keyExpression + "'", e);
        }
    }

    /**
     * 编译表达式；模板表达式（含 #{...}）按模板解析，否则按纯表达式解析。
     * 编译失败直接抛出，不缓存失败结果——key 是必需语义，应尽早暴露配置错误。
     */
    private Expression compile(CacheKey cacheKey) {
        try {
            if (cacheKey.keyExpression().contains("#{")) {
                return parser.parseExpression(cacheKey.keyExpression(), new TemplateParserContext());
            }
            return parser.parseExpression(cacheKey.keyExpression());
        } catch (Exception e) {
            throw new KeyResolveException("key 表达式编译失败 - method: " + cacheKey.method().getName()
                    + ", key: '" + cacheKey.keyExpression() + "'", e);
        }
    }

    /**
     * 缓存条目：方法 + 表达式原文（同一方法可被不同注解表达式复用，二者共同作为缓存键）。
     */
    private record CacheKey(Method method, String keyExpression) {
    }

    /**
     * key 解析失败异常：表示注解配置错误或表达式求值出错，属于编程错误，应快速失败暴露。
     */
    public static class KeyResolveException extends RuntimeException {
        public KeyResolveException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
