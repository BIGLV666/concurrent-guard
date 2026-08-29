package io.github.biglv666.guard.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SpelKeyResolver} 单元测试：参数变量、模板表达式、空表达式与快速失败行为。
 *
 * @author Guard Team
 * @since 0.1.0
 */
class SpelKeyResolverTest {

    private final SpelKeyResolver resolver = new SpelKeyResolver();

    /**
     * 供表达式解析的目标方法载体。
     */
    static class Sample {
        @SuppressWarnings("unused")
        public String order(String orderId) {
            return orderId;
        }

        @SuppressWarnings("unused")
        public void nothing() {
        }
    }

    private java.lang.reflect.Method method(String name, Class<?>... params) throws NoSuchMethodException {
        return Sample.class.getMethod(name, params);
    }

    @Test
    void resolvesParameterVariable() throws NoSuchMethodException {
        String key = resolver.resolve(method("order", String.class), new Object[]{"42"}, "#orderId");
        assertEquals("42", key);
    }

    @Test
    void resolvesTemplateExpression() throws NoSuchMethodException {
        String key = resolver.resolve(method("order", String.class), new Object[]{"42"}, "order:#{#orderId}");
        assertEquals("order:42", key);
    }

    @Test
    void blankExpressionReturnsNull() throws NoSuchMethodException {
        assertNull(resolver.resolve(method("order", String.class), new Object[]{"42"}, ""));
        assertNull(resolver.resolve(method("order", String.class), new Object[]{"42"}, null));
    }

    @Test
    void unknownVariableEvaluatesToNull() throws NoSuchMethodException {
        // 求值为 null 时返回 null，调用方回退方法级默认键
        assertNull(resolver.resolve(method("order", String.class), new Object[]{"42"}, "#missing"));
    }

    @Test
    void brokenExpressionFailsFast() throws NoSuchMethodException {
        assertThrows(SpelKeyResolver.KeyResolveException.class,
                () -> resolver.resolve(method("order", String.class), new Object[]{"42"}, "#order..id"));
    }
}
