package io.github.biglv666.guard.internal;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 方法级默认键测试：0.3.0 默认包含参数类型，避免重载方法互撞；
 * 兼容开关仍可回退旧格式。
 *
 * @author Guard Team
 * @since 0.3.0
 */
class GuardKeyUtilsTest {

    @Test
    void newDefaultKeyIncludesParameterTypes() throws Exception {
        Method stringMethod = Sample.class.getMethod("submit", String.class);
        Method longMethod = Sample.class.getMethod("submit", Long.class);

        String stringKey = GuardKeyUtils.methodKey(stringMethod, false);
        String longKey = GuardKeyUtils.methodKey(longMethod, false);

        assertEquals(Sample.class.getName() + "#submit(java.lang.String)", stringKey);
        assertEquals(Sample.class.getName() + "#submit(java.lang.Long)", longKey);
        assertNotEquals(stringKey, longKey);
    }

    @Test
    void legacyDefaultKeyKeepsOldFormat() throws Exception {
        Method stringMethod = Sample.class.getMethod("submit", String.class);
        Method longMethod = Sample.class.getMethod("submit", Long.class);

        assertEquals(Sample.class.getName() + "#submit", GuardKeyUtils.methodKey(stringMethod, true));
        assertEquals(GuardKeyUtils.methodKey(stringMethod, true), GuardKeyUtils.methodKey(longMethod, true));
    }

    @Test
    void fullKeyUsesSelectedDefaultFormat() throws Exception {
        Method method = Sample.class.getMethod("submit", String.class);
        assertEquals("p:" + Sample.class.getName() + "#submit(java.lang.String)",
                GuardKeyUtils.fullKey("p:", null, method, false));
        assertEquals("p:" + Sample.class.getName() + "#submit",
                GuardKeyUtils.fullKey("p:", null, method, true));
        assertEquals("p:explicit", GuardKeyUtils.fullKey("p:", "explicit", method, false));
    }

    static class Sample {
        @SuppressWarnings("unused")
        public String submit(String value) {
            return value;
        }

        @SuppressWarnings("unused")
        public String submit(Long value) {
            return String.valueOf(value);
        }
    }
}
