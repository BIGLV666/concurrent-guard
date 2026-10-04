package io.github.biglv666.guard.itest.chaos.codec;

import io.github.biglv666.guard.event.GuardEventType;
import io.github.biglv666.guard.event.GuardRejectedEvent;
import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.idempotent.IdempotentMode;
import io.github.biglv666.guard.idempotent.IdempotentRejectedException;
import io.github.biglv666.guard.idempotent.ResultCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结果编解码器故障混沌测试（{@code store=local}，无 Redis）：
 * 自定义 {@link ResultCodec} 反序列化必然失败（模拟存储数据损坏 / 类型演进不兼容），
 * 观察真实行为——首次正常执行与保存，重复请求按一致性优先拒绝并恰好发布一次降级事件，
 * 业务方法不重复执行。
 *
 * @author Guard Team
 * @since 0.2.1
 */
@SpringBootTest(classes = CodecFailureChaosTest.App.class, properties = "guard.idempotent.store=local")
class CodecFailureChaosTest {

    @Autowired
    private App.CodecService service;

    @Autowired
    private App.EventCollector collector;

    @Test
    void deserializeFailureRejectsDuplicateWithSingleDegradedEvent() {
        String key = "codec-" + System.nanoTime();
        // 首次：业务执行 + 保存（序列化正常）
        assertEquals("ok:" + key, service.replay(key));
        assertEquals(1, App.CodecService.calls.get());

        // 重复：读到结果但反序列化失败 → fail-close 拒绝 + 一次降级事件
        IdempotentRejectedException e = assertThrows(IdempotentRejectedException.class,
                () -> service.replay(key));
        assertEquals("guard:idempotent:" + key, e.getKey());
        assertEquals(1, App.CodecService.calls.get(), "反序列化失败不应重复执行业务");
        assertEquals(1, collector.count(GuardEventType.IDEMPOTENT_DEGRADED),
                "应恰好发布一次降级事件，实际: " + collector.events.size());
    }

    /**
     * 测试应用：注册反序列化必然失败的编解码器。
     */
    @SpringBootApplication
    static class App {

        @Bean
        public ResultCodec brokenCodec() {
            return new ResultCodec() {
                @Override
                public String serialize(Object result) {
                    return String.valueOf(result);
                }

                @Override
                public Object deserialize(String payload, java.lang.reflect.Type returnType) {
                    throw new IllegalStateException("模拟存储数据损坏：无法反序列化");
                }
            };
        }

        @Component
        static class CodecService {
            // static：Bean 会被 CGLIB 代理（Objenesis 创建，不初始化实例字段），经代理读取实例字段为 null
            static final AtomicInteger calls = new AtomicInteger();

            @Idempotent(key = "#k", mode = IdempotentMode.REPLAY)
            public String replay(String k) {
                calls.incrementAndGet();
                return "ok:" + k;
            }
        }

        @Component
        static class EventCollector {
            final List<GuardRejectedEvent> events = Collections.synchronizedList(new ArrayList<>());

            @EventListener
            public void on(GuardRejectedEvent event) {
                events.add(event);
            }

            long count(GuardEventType type) {
                return events.stream().filter(e -> e.getType() == type).count();
            }
        }
    }
}
