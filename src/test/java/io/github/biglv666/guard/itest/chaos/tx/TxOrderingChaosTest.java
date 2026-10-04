package io.github.biglv666.guard.itest.chaos.tx;

import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.idempotent.IdempotentMode;
import io.github.biglv666.guard.idempotent.LocalIdempotentPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切面与事务顺序的确定性验证（{@code store=local}，无 Redis）：
 * 自定义策略在占位与保存时记录"当前线程是否存在活跃事务"。
 * {@code IdempotentAspect} 声明了 {@code @Order(0)}（优先于默认最低优先级的事务拦截器），
 * 因此占位获取必须发生在事务开始之前、结果保存发生在事务提交之后——
 * 两者观察到的都应是无事务。若未来有人调整切面顺序，本测试会立刻变红。
 *
 * @author Guard Team
 * @since 0.2.1
 */
@SpringBootTest(classes = TxOrderingChaosTest.App.class, properties = "guard.idempotent.store=local")
class TxOrderingChaosTest {

    @Autowired
    private App.TxService service;

    @Autowired
    private App.RecordingPolicy policy;

    @Test
    void placeholderAndSaveHappenOutsideTransaction() {
        String key = "tx-" + System.nanoTime();
        assertEquals("ok:" + key, service.replay(key));
        assertFalse(policy.txActiveOnAcquire, "占位获取应发生在事务开始之前");
        assertFalse(policy.txActiveOnSave, "结果保存应发生在事务提交之后");
        // 重放链路不受影响
        assertEquals("ok:" + key, service.replay(key));
        assertEquals(1, App.TxService.calls.get());
    }

    /**
     * 测试应用：启用事务管理（ResourcelessTransactionManager 无需真实资源），
     * 注册记录事务状态的本地策略。
     */
    @SpringBootApplication
    @EnableTransactionManagement
    static class App {

        @Bean
        public PlatformTransactionManager transactionManager() {
            // 空操作事务管理器（无需真实资源）：继承 AbstractPlatformTransactionManager
            // 以获得 TransactionSynchronizationManager 的标准事务状态记账
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() {
                    return new Object();
                }

                @Override
                protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
                }

                @Override
                protected void doCommit(DefaultTransactionStatus status) {
                }

                @Override
                protected void doRollback(DefaultTransactionStatus status) {
                }
            };
        }

        @Bean
        public RecordingPolicy idempotentPolicy() {
            return new RecordingPolicy();
        }

        @Component
        static class TxService {
            // static：Bean 会被 CGLIB 代理（Objenesis 创建，不初始化实例字段），经代理读取实例字段为 null
            static final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

            @Transactional
            @Idempotent(key = "#k", mode = IdempotentMode.REPLAY)
            public String replay(String k) {
                calls.incrementAndGet();
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                        "业务方法应处于事务内");
                return "ok:" + k;
            }
        }

        /**
         * 记录占位/保存时事务活跃状态的本地策略。
         */
        static class RecordingPolicy extends LocalIdempotentPolicy {
            volatile boolean txActiveOnAcquire;
            volatile boolean txActiveOnSave;

            RecordingPolicy() {
                super(Duration.ofSeconds(5), 1024);
            }

            @Override
            public String tryAcquireToken(String key, Duration ttl) {
                txActiveOnAcquire = TransactionSynchronizationManager.isActualTransactionActive();
                return super.tryAcquireToken(key, ttl);
            }

            @Override
            public void saveResultForReplay(String key, String token, String payload, Duration ttl) {
                txActiveOnSave = TransactionSynchronizationManager.isActualTransactionActive();
                super.saveResultForReplay(key, token, payload, ttl);
            }
        }
    }
}
