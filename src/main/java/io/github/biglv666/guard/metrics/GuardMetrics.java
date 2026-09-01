package io.github.biglv666.guard.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.TimeUnit;

/**
 * Guard 指标上报器 —— 对 Micrometer 的薄封装。
 *
 * <p>宿主应用引入 micrometer-core（通常随 actuator 一起）时上报以下指标；
 * 未引入时所有上报为空操作，零开销：
 * <ul>
 *   <li>{@code guard_rejected_total}（Counter，tag: type）：拒绝/超时请求数；</li>
 *   <li>{@code guard_replayed_total}（Counter，tag: type）：REPLAY 模式重放次数；</li>
 *   <li>{@code guard_lock_acquire}（Timer，tag: type=锁类型）：锁获取耗时分布（含 Prometheus 自动单位秒）。</li>
 * </ul>
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

    /**
     * 计数器名称：REPLAY 模式下结果被重放的请求总数。
     *
     * @since 0.2.0
     */
    public static final String REPLAYED_COUNTER = "guard_replayed_total";

    /**
     * Timer 名称：锁获取耗时分布。Prometheus 侧自动渲染为
     * {@code guard_lock_acquire_seconds_count/_sum/_max}。
     *
     * @since 0.2.0
     */
    public static final String LOCK_ACQUIRE_TIMER = "guard_lock_acquire";

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

    /**
     * 累加一次重放计数（REPLAY 模式下重复请求命中首次结果）。
     *
     * @param type 事件类型，作为指标 tag「type」的值
     * @since 0.2.0
     */
    public void incrementReplayed(String type) {
        if (registry == null) {
            return;
        }
        registry.counter(REPLAYED_COUNTER, "type", type).increment();
    }

    /**
     * 记录一次锁获取耗时。仅统计成功获取（含等待时间）；超时失败已计入
     * {@link #incrementRejected}，不重复记录。
     *
     * @param lockType      锁类型名（{@code REDIS} / {@code SYNCHRONIZED}），作为指标 tag「type」的值
     * @param waitNanos     从开始尝试获取到成功持有的耗时（纳秒）
     * @since 0.2.0
     */
    public void recordLockAcquire(String lockType, long waitNanos) {
        if (registry == null) {
            return;
        }
        registry.timer(LOCK_ACQUIRE_TIMER, "type", lockType).record(waitNanos, TimeUnit.NANOSECONDS);
    }
}
