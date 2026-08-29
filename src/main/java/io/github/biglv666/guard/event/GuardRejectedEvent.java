package io.github.biglv666.guard.event;

import java.time.Instant;

/**
 * Guard 拒绝事件 —— 本 starter 对外唯一的集成 SPI。
 *
 * <p>幂等拒绝、锁等待超时等"请求被拦截"的时刻都会以 Spring
 * {@link org.springframework.context.ApplicationEvent} 的形式发布，
 * 业务方通过 {@code @EventListener} 订阅后可自由转发到钉钉/企微等告警渠道，
 * 从而与本 starter 保持零耦合（不依赖任何具体告警实现）。
 *
 * <pre>
 * &#64;EventListener
 * public void onGuardRejected(GuardRejectedEvent event) {
 *     alertClient.send(event.getType() + " @ " + event.getKey());
 * }
 * </pre>
 *
 * @author Guard Team
 * @since 0.1.0
 */
public class GuardRejectedEvent {

    /**
     * 事件类型。
     */
    private final GuardEventType type;

    /**
     * 被拒绝的完整缓存键（含前缀）。
     */
    private final String key;

    /**
     * 被拦截的方法描述，格式为「全限定类名#方法名」。
     */
    private final String method;

    /**
     * 拒绝原因的人类可读描述。
     */
    private final String reason;

    /**
     * 事件发生时间。
     */
    private final Instant timestamp;

    public GuardRejectedEvent(GuardEventType type, String key, String method, String reason) {
        this.type = type;
        this.key = key;
        this.method = method;
        this.reason = reason;
        this.timestamp = Instant.now();
    }

    public GuardEventType getType() {
        return type;
    }

    public String getKey() {
        return key;
    }

    public String getMethod() {
        return method;
    }

    public String getReason() {
        return reason;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "GuardRejectedEvent{type=" + type + ", key='" + key + "', method='" + method
                + "', reason='" + reason + "', timestamp=" + timestamp + '}';
    }
}
