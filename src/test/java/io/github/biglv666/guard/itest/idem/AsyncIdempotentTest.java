package io.github.biglv666.guard.itest.idem;

import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.idempotent.IdempotentMode;
import io.github.biglv666.guard.idempotent.IdempotentRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 异步幂等测试：REPLAY 结果必须等 CompletableFuture 真正完成后才保存，
 * 完成前重复请求仍应被拒绝。
 *
 * @author Guard Team
 * @since 0.3.0
 */
@SpringBootTest(classes = AsyncIdempotentTest.App.class,
        properties = "guard.idempotent.store=local")
class AsyncIdempotentTest {

    @Autowired
    private App.Service service;

    @BeforeEach
    void reset() {
        App.Service.calls.set(0);
    }

    @Test
    void replayResultIsSavedOnlyAfterAsyncCompletion() throws Exception {
        String key = "async-" + System.nanoTime();
        CompletableFuture<String> first = service.submit(key);
        // 首个请求仍在执行：重复请求应被拒绝
        assertThrows(IdempotentRejectedException.class, () -> service.submit(key));

        assertEquals("ok:" + key, first.get(5, TimeUnit.SECONDS));

        // 等异步回调保存结果（通常已完成，轮询避免调度抖动）
        String replayed = null;
        for (int i = 0; i < 100 && replayed == null; i++) {
            try {
                replayed = service.submit(key).get(5, TimeUnit.SECONDS);
            } catch (IdempotentRejectedException e) {
                Thread.sleep(20);
            }
        }
        assertEquals("ok:" + key, replayed);
        assertEquals(1, App.Service.calls.get(), "重放不应再次执行业务");
    }

    @SpringBootApplication
    static class App {

        @Component
        static class Service {
            static final AtomicInteger calls = new AtomicInteger();

            @Idempotent(key = "#k", mode = IdempotentMode.REPLAY, ttl = 10)
            public CompletableFuture<String> submit(String k) {
                return CompletableFuture.supplyAsync(() -> {
                    calls.incrementAndGet();
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "ok:" + k;
                });
            }
        }
    }
}
