package io.github.biglv666.guard.itest.replay;

import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.idempotent.IdempotentMode;
import io.github.biglv666.guard.idempotent.IdempotentPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REPLAY 模式配置错误测试（不依赖 Redis，无需容器）：
 * 自定义 {@link IdempotentPolicy} 未支持结果重放（supportsReplay 保持默认 false）时，
 * {@code mode = REPLAY} 的方法在首次调用处快速失败，且错误信息带修复指引。
 *
 * @author Guard Team
 * @since 0.2.0
 */
@SpringBootTest(classes = IdempotentReplayUnsupportedPolicyTest.App.class)
class IdempotentReplayUnsupportedPolicyTest {

    @Autowired
    private App.ReplayService service;

    @Test
    void replayOnUnsupportedPolicyFailsFastWithGuidance() {
        // 占位前快速失败：提示实现 saveResult/loadResult 或改用 REJECT
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.handle("k-" + System.nanoTime()));
        assertTrue(error.getMessage().contains("supportsReplay"),
                "错误信息应包含 supportsReplay 修复指引，实际: " + error.getMessage());
    }

    /**
     * 测试应用：注册不支持结果重放的自定义策略。
     */
    @SpringBootApplication
    static class App {

        @Bean
        public IdempotentPolicy idempotentPolicy() {
            return new NoReplayPolicy();
        }

        @Component
        static class ReplayService {
            @Idempotent(key = "#k", mode = IdempotentMode.REPLAY)
            public String handle(String k) {
                return "never";
            }
        }
    }

    /**
     * 自定义策略：仅实现占位与释放，不实现结果重放（supportsReplay 保持默认 false）。
     */
    static class NoReplayPolicy implements IdempotentPolicy {
        final Set<String> placeholders = ConcurrentHashMap.newKeySet();

        @Override
        public boolean tryAcquire(String key, Duration ttl) {
            return placeholders.add(key);
        }

        @Override
        public void release(String key) {
            placeholders.remove(key);
        }
    }
}
