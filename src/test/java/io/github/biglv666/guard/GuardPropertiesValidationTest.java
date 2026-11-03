package io.github.biglv666.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GuardProperties} 启动期配置校验测试。
 *
 * @author Guard Team
 * @since 0.2.2
 */
class GuardPropertiesValidationTest {

    @Test
    void defaultsAreValid() {
        assertDoesNotThrow(() -> new GuardProperties().afterPropertiesSet());
    }

    @Test
    void invalidStoreFails() {
        GuardProperties properties = new GuardProperties();
        properties.getIdempotent().setStore("memcached");
        IllegalStateException error = assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
        assertTrue(error.getMessage().contains("store"));
    }

    @Test
    void negativeReplayGraceFails() {
        GuardProperties properties = new GuardProperties();
        properties.getIdempotent().setReplayGraceMillis(-1);
        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    void nonPositiveLocalMaxEntriesFails() {
        GuardProperties properties = new GuardProperties();
        properties.getIdempotent().setLocalMaxEntries(0);
        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    void nullPrefixesFail() {
        GuardProperties properties = new GuardProperties();
        properties.getIdempotent().setKeyPrefix(null);
        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);

        GuardProperties lockProperties = new GuardProperties();
        lockProperties.getLock().setKeyPrefix(null);
        assertThrows(IllegalStateException.class, lockProperties::afterPropertiesSet);
    }
}
