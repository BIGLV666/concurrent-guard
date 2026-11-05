package io.github.biglv666.guard.idempotent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis Cluster 哨兵键 hash-tag 测试：
 * 无 tag、合法 tag、空 tag、不配对花括号。
 *
 * @author Guard Team
 * @since 0.2.2
 */
class RedisSentinelKeyTest {

    @Test
    void keyWithoutBracesUsesWholeKeyAsHashTag() {
        assertEquals("{order:1}:result", RedisSetNxIdempotentPolicy.sentinelKeyOf("order:1"));
        assertTrue(RedisSetNxIdempotentPolicy.isSameClusterSlot("order:1"));
    }

    @Test
    void existingHashTagIsReused() {
        String key = "{tenant}:order:1";
        assertEquals("{tenant}:result:{tenant}:order:1", RedisSetNxIdempotentPolicy.sentinelKeyOf(key));
        assertTrue(RedisSetNxIdempotentPolicy.isSameClusterSlot(key));
    }

    @Test
    void invalidBracesFailFast() {
        assertThrows(IdempotentKeyException.class,
                () -> RedisSetNxIdempotentPolicy.sentinelKeyOf("foo{}bar"));
        assertThrows(IdempotentKeyException.class,
                () -> RedisSetNxIdempotentPolicy.sentinelKeyOf("foo{bar"));
        assertThrows(IdempotentKeyException.class,
                () -> RedisSetNxIdempotentPolicy.sentinelKeyOf("foo}bar"));
    }
}
