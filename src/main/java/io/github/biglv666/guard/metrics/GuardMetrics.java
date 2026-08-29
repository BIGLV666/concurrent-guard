package io.github.biglv666.guard.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Guard 指标上报器 —— 对 Micrometer 的薄封装。
 *
 * <p>宿主应用引入 micrometer-core（通常随 actuator 一起）时，
 * 拒绝/超时事件会累加到计数器 {@code guard_rejected_total}（tag: type=事件类型）；
 * 未引入时所有上报为空操作，零开销。
 *
 * <p>后续如需接告警，可在应用侧基于该指标配置阈值规则，
 * 或直接订阅 {@link io.github.biglv666.guard.event.GuardRejectedEvent} 做事件级告警。
 *
 * @author Guard Team
 * @since 0.1.0
 */
public class GuardMetrics {

    /**
     * 计数器名称：被防护组件拒绝/超时的请求总数。
     */
    public static final String REJECTED_COUNTER = "guard_rejected_total";

    private final MeterRegistry registry;

    public GuardMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registry = registryProvider.getIfAvailable();
    }

    /**
     * 累加一次拒绝计数。
     *
     * @param type 事件类型，作为指标 tag「type」的值
     */
    public void incrementRejected(String type) {
        if (registry == null) {
            return;
        }
        registry.counter(REJECTED_COUNTER, "type", type).increment();
    }
}
