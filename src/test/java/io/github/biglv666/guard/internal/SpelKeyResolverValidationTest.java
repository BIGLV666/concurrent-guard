package io.github.biglv666.guard.internal;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SpelKeyResolver#validate(Method, String, SpelKeyResolver.PropertyCheckMode)}
 * 单元测试：语法、变量名、位置别名、属性/方法名与安全限制。
 *
 * @author Guard Team
 * @since 0.2.2
 */
class SpelKeyResolverValidationTest {

    private final SpelKeyResolver resolver = new SpelKeyResolver();

    @Test
    void validExpressionsHaveNoIssues() throws Exception {
        Method method = Service.class.getMethod("submit", Order.class, String.class);
        for (String expression : new String[]{
                "#order.id", "order:#{#orderId}", "#p0.id", "#a1", "#root[0]",
                "#order.getId()"}) {
            SpelKeyResolver.ValidationResult result = resolver.validate(method, expression);
            assertFalse(result.hasIssues(), expression + " 应通过校验，实际: " + result);
        }
    }

    @Test
    void unknownVariableReportsAvailableNames() throws Exception {
        Method method = Service.class.getMethod("submit", Order.class, String.class);
        SpelKeyResolver.ValidationResult result = resolver.validate(method, "#oder.id");
        assertTrue(result.hasErrors());
        String message = result.errors().get(0);
        assertTrue(message.contains("#oder"), message);
        assertTrue(message.contains("order"), message);
        assertTrue(message.contains("p0"), message);
    }

    @Test
    void positionalVariableOutOfRangeFails() throws Exception {
        Method method = Service.class.getMethod("submit", Order.class, String.class);
        SpelKeyResolver.ValidationResult result = resolver.validate(method, "#p2.id");
        assertTrue(result.hasErrors());
        assertTrue(result.errors().get(0).contains("越界"), result.errors().toString());
    }

    @Test
    void propertyTypoIsWarningByDefaultAndErrorInStrictMode() throws Exception {
        Method method = Service.class.getMethod("submit", Order.class, String.class);
        SpelKeyResolver.ValidationResult warn = resolver.validate(method, "#order.oderId",
                SpelKeyResolver.PropertyCheckMode.WARN);
        assertFalse(warn.hasErrors());
        assertTrue(warn.warnings().get(0).contains("oderId"), warn.warnings().toString());

        SpelKeyResolver.ValidationResult strict = resolver.validate(method, "#order.oderId",
                SpelKeyResolver.PropertyCheckMode.STRICT);
        assertTrue(strict.hasErrors());
    }

    @Test
    void privateFieldWithoutGetterIsReported() throws Exception {
        Method method = Service.class.getMethod("submitPrivate", PrivateOrder.class);
        SpelKeyResolver.ValidationResult warn = resolver.validate(method, "#order.id",
                SpelKeyResolver.PropertyCheckMode.WARN);
        assertFalse(warn.hasErrors());
        assertTrue(warn.warnings().get(0).contains("id"), warn.warnings().toString());

        SpelKeyResolver.ValidationResult strict = resolver.validate(method, "#order.id",
                SpelKeyResolver.PropertyCheckMode.STRICT);
        assertTrue(strict.hasErrors(), "private 字段无 getter 在 strict 下应报错");
    }

    @Test
    void recordAccessorIsAccepted() throws Exception {
        Method method = Service.class.getMethod("submitRecord", OrderRecord.class);
        SpelKeyResolver.ValidationResult result = resolver.validate(method, "#order.id");
        assertFalse(result.hasIssues(), result.toString());
    }

    @Test
    void methodTypoIsReported() throws Exception {
        Method method = Service.class.getMethod("submit", Order.class, String.class);
        SpelKeyResolver.ValidationResult result = resolver.validate(method, "#order.getOderId()",
                SpelKeyResolver.PropertyCheckMode.WARN);
        assertTrue(result.warnings().get(0).contains("getOderId"), result.warnings().toString());
    }

    @Test
    void typeReferenceAndConstructorAreRejected() throws Exception {
        Method method = Service.class.getMethod("submit", Order.class, String.class);
        assertTrue(resolver.validate(method, "T(java.lang.Runtime)").hasErrors());
        assertTrue(resolver.validate(method, "new java.lang.String('x')").hasErrors());
    }

    @Test
    void syntaxErrorIsReported() throws Exception {
        Method method = Service.class.getMethod("submit", Order.class, String.class);
        assertTrue(resolver.validate(method, "#order..id").hasErrors());
    }

    @Test
    void blankExpressionIsIgnored() throws Exception {
        Method method = Service.class.getMethod("submit", Order.class, String.class);
        assertFalse(resolver.validate(method, "  ").hasIssues());
        assertFalse(resolver.validate(method, null).hasIssues());
    }

    static class Order {
        private final String id;

        Order(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    record OrderRecord(String id) {
    }

    static class PrivateOrder {
        private final String id;

        PrivateOrder(String id) {
            this.id = id;
        }
    }

    static class Service {
        @SuppressWarnings("unused")
        public String submit(Order order, String orderId) {
            return orderId;
        }

        @SuppressWarnings("unused")
        public String submitRecord(OrderRecord order) {
            return order.id();
        }

        @SuppressWarnings("unused")
        public String submitPrivate(PrivateOrder order) {
            return "private";
        }
    }
}
