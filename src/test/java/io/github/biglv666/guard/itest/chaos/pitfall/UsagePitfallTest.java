package io.github.biglv666.guard.itest.chaos.pitfall;

import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.idempotent.IdempotentRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 真实项目高频误用场景的行为固化测试（{@code store=local}，无 Redis）：
 *
 * <ul>
 *   <li><b>自调用绕过</b>：同一个 Bean 内未注解方法调用 {@code @Idempotent} 方法，
 *       调用不经过 Spring AOP 代理，防护静默失效——这是 Spring 代理机制的固有限制，
 *       本测试固化该真实行为并在 README 显著提示；</li>
 *   <li><b>跨方法同 key</b>：两个方法解析出相同幂等键时互相排斥——
 *       这是"key 是语义必需项"的自然结果，误用会造成无关方法互锁。</li>
 * </ul>
 *
 * @author Guard Team
 * @since 0.2.1
 */
@SpringBootTest(classes = UsagePitfallTest.App.class, properties = "guard.idempotent.store=local")
class UsagePitfallTest {

    @Autowired
    private App.PitfallService service;

    @Test
    void selfInvocationBypassesProtection() {
        String key = "self-" + System.nanoTime();
        // 直接调用注解方法：防护生效
        assertEquals("ok:" + key, service.inner(key));
        assertThrows(IdempotentRejectedException.class, () -> service.inner(key));
        assertEquals(1, App.PitfallService.uncoveredInnerCalls.get());

        String key2 = "self2-" + System.nanoTime();
        // 自调用：uncovered 不经过代理，inner 的防护完全失效，两次都执行业务
        assertEquals("ok:" + key2, service.uncovered(key2));
        assertEquals("ok:" + key2, service.uncovered(key2));
        assertEquals(3, App.PitfallService.uncoveredInnerCalls.get(), "应为 1 次直接调用 + 2 次自调用");
    }

    @Test
    void crossMethodSameKeyMutuallyExclusive() {
        String key = "cross-" + System.nanoTime();
        assertEquals("ok:" + key, service.methodA(key));
        // methodB 解析出相同的幂等键：被 methodA 的占位拒绝（跨方法互斥语义）
        // 异常携带完整键（含 guard:idempotent: 前缀），便于定位互锁来源
        IdempotentRejectedException e = assertThrows(IdempotentRejectedException.class,
                () -> service.methodB(key));
        assertEquals("guard:idempotent:" + key, e.getKey());
        assertEquals(0, App.PitfallService.methodBCalls.get());
    }

    /**
     * 测试应用。
     */
    @SpringBootApplication
    static class App {

        @Component
        static class PitfallService {
            // static：Bean 会被 CGLIB 代理（Objenesis 创建，不初始化实例字段），经代理读取实例字段为 null
            static final AtomicInteger uncoveredInnerCalls = new AtomicInteger();
            static final AtomicInteger methodBCalls = new AtomicInteger();

            /** 未注解方法：调用 inner 不经过代理，防护被绕过。 */
            public String uncovered(String k) {
                return inner(k);
            }

            @Idempotent(key = "#k")
            public String inner(String k) {
                uncoveredInnerCalls.incrementAndGet();
                return "ok:" + k;
            }

            @Idempotent(key = "#k")
            public String methodA(String k) {
                return "ok:" + k;
            }

            @Idempotent(key = "#k")
            public String methodB(String k) {
                methodBCalls.incrementAndGet();
                return "ok:" + k;
            }
        }
    }
}
