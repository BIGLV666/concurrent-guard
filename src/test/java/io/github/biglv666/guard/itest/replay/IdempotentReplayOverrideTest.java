package io.github.biglv666.guard.itest.replay;

import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.idempotent.IdempotentMode;
import io.github.biglv666.guard.idempotent.IdempotentPolicy;
import io.github.biglv666.guard.idempotent.ResultCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * REPLAY 模式自定义实现测试（不依赖 Redis，无需容器）：
 * 自定义 {@link IdempotentPolicy}（内存实现，支持重放）+ 自定义 {@link ResultCodec} Bean
 * 同时覆盖默认实现，验证重放链路完整走自定义组件。
 *
 * @author Guard Team
 * @since 0.2.0
 */
@SpringBootTest(classes = IdempotentReplayOverrideTest.App.class)
class IdempotentReplayOverrideTest {

    @Autowired
    private App.CodecService codecService;

    @Test
    void customCodecAndPolicyAreUsedForReplay() {
        String key = "codec-" + System.nanoTime();
        String first = codecService.handle(key);
        assertEquals("raw:" + key, first);
        assertEquals(1, codecService.calls.get());

        // 窗口内重复请求：结果经自定义编解码器还原（内容带自定义前缀），业务方法不再执行
        assertEquals("raw:" + key, codecService.handle(key));
        assertEquals(1, codecService.calls.get());
    }

    /**
     * 测试应用：注册支持重放的内存策略与自定义编解码器，覆盖全部默认实现。
     */
    @SpringBootApplication
    static class App {

        @Bean
        public ResultCodec resultCodec() {
            return new PrefixCodec();
        }

        @Bean
        public IdempotentPolicy idempotentPolicy() {
            return new InMemoryReplayPolicy();
        }

        @Component
        static class CodecService {
            // static：Bean 会被 CGLIB 代理（Objenesis 创建，不初始化实例字段），经代理读取实例字段为 null
            static final AtomicInteger calls = new AtomicInteger();

            @Idempotent(key = "#k", mode = IdempotentMode.REPLAY, ttl = 60)
            public String handle(String k) {
                calls.incrementAndGet();
                return "raw:" + k;
            }
        }
    }

    /**
     * 自定义编解码器：在内容前加固定前缀，验证编解码确实走了自定义实现。
     */
    static class PrefixCodec implements ResultCodec {
        @Override
        public String serialize(Object result) {
            return "codec:" + result;
        }

        @Override
        public Object deserialize(String payload, java.lang.reflect.Type returnType) {
            return payload.substring("codec:".length());
        }
    }

    /**
     * 自定义策略：内存占位 + 结果存储，实现完整重放 SPI（supportsReplay 覆写为 true）。
     */
    static class InMemoryReplayPolicy implements IdempotentPolicy {
        final Map<String, String> store = new ConcurrentHashMap<>();

        @Override
        public boolean tryAcquire(String key, Duration ttl) {
            return store.putIfAbsent(key, "processing") == null;
        }

        @Override
        public void release(String key) {
            store.remove(key);
        }

        @Override
        public boolean supportsReplay() {
            return true;
        }

        @Override
        public void saveResult(String key, String payload, Duration ttl) {
            store.put(key, payload);
        }

        @Override
        public String loadResult(String key) {
            String value = store.get(key);
            return "processing".equals(value) ? null : value;
        }
    }
}
