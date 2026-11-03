package io.github.biglv666.guard.internal;

import io.github.biglv666.guard.event.GuardRejectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Guard 事件发布的安全封装。
 *
 * <p>事件监听器属于业务方扩展点，监听器自身抛出的异常不应反向影响
 * 被防护方法的执行结果或原始异常传播。因此统一在此捕获运行时异常并记录日志。</p>
 *
 * @author Guard Team
 * @since 0.2.1
 */
public final class GuardEventSupport {

    private static final Logger log = LoggerFactory.getLogger(GuardEventSupport.class);

    private GuardEventSupport() {
    }

    /**
     * 发布拒绝/降级事件；发布器为空或监听器异常时静默降级为日志，不向调用方抛出。
     *
     * @param publisher 事件发布器，允许为 null（直接实例化切面/模板时）
     * @param event     待发布事件
     */
    public static void publish(ApplicationEventPublisher publisher, GuardRejectedEvent event) {
        if (publisher == null) {
            return;
        }
        try {
            publisher.publishEvent(event);
        } catch (RuntimeException e) {
            log.warn("Guard 事件监听器执行失败，已忽略以避免影响主流程 - event: {}, 错误: {}",
                    event, e.getMessage(), e);
        }
    }
}
