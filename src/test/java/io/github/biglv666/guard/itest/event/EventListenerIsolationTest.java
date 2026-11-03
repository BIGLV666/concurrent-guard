package io.github.biglv666.guard.itest.event;

import io.github.biglv666.guard.event.GuardRejectedEvent;
import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.idempotent.IdempotentRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 事件监听器隔离测试：业务方监听器抛异常时，不能覆盖 Guard 自身的拒绝异常，
 * 也不能影响首次请求的正常返回。
 *
 * @author Guard Team
 * @since 0.2.1
 */
@SpringBootTest(classes = EventListenerIsolationTest.App.class,
        properties = "guard.idempotent.store=local")
class EventListenerIsolationTest {

    @Autowired
    private App.Service service;

    @Test
    void listenerExceptionDoesNotMaskGuardRejection() {
        assertEquals("ok:k", service.submit("k"));
        IdempotentRejectedException rejection = assertThrows(IdempotentRejectedException.class,
                () -> service.submit("k"));
        assertEquals("请求处理中，请勿重复提交", rejection.getMessage());
    }

    @SpringBootApplication
    static class App {

        @Component
        static class Service {
            @Idempotent(key = "#k")
            public String submit(String k) {
                return "ok:" + k;
            }
        }

        @Component
        static class ThrowingListener {
            @EventListener
            public void on(GuardRejectedEvent event) {
                throw new IllegalStateException("模拟告警监听器故障");
            }
        }
    }
}
