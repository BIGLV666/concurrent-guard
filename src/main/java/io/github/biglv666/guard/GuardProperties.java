package io.github.biglv666.guard;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Guard 并发防护组件的总配置属性，配置前缀为 {@code guard}。
 *
 * <p>包含两个功能模块各自的开关与参数：
 * <ul>
 *   <li>{@link Idempotent}：幂等（防重复提交）</li>
 *   <li>{@link Lock}：分布式锁</li>
 * </ul>
 *
 * <p>典型配置示例：
 * <pre>
 * guard:
 *   enabled: true
 *   idempotent:
 *     enabled: true
 *     key-prefix: "guard:idempotent:"
 *     fail-open: true
 *   lock:
 *     enabled: true
 *     key-prefix: "guard:lock:"
 * </pre>
 *
 * @author Guard Team
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "guard")
public class GuardProperties {

    /**
     * 总开关：为 false 时所有切面均不装配，等于未引入本 starter。
     */
    private boolean enabled = true;

    /**
     * 幂等模块配置。
     */
    private final Idempotent idempotent = new Idempotent();

    /**
     * 分布式锁模块配置。
     */
    private final Lock lock = new Lock();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Idempotent getIdempotent() {
        return idempotent;
    }

    public Lock getLock() {
        return lock;
    }

    /**
     * 幂等（防重复提交）模块配置项。
     */
    public static class Idempotent {

        /**
         * 幂等模块开关：为 false 时不装配幂等切面。
         */
        private boolean enabled = true;

        /**
         * Redis 键前缀，最终键 = 前缀 + 注解 SpEL 解析值（或方法级默认键）。
         * 多应用共用同一 Redis 时可通过前缀隔离。
         */
        private String keyPrefix = "guard:idempotent:";

        /**
         * Redis 故障时的降级策略：
         * true = fail-open（放行业务请求，优先可用性）；
         * false = fail-close（拒绝请求并抛出异常，优先一致性）。
         *
         * <p>注意：配置错误（无 IdempotentPolicy Bean、REPLAY 策略不支持、ttl 非法）
         * 不受 fail-open 影响，一律快速失败抛 {@link IllegalStateException}。
         */
        private boolean failOpen = true;

        /**
         * 慢请求完成哨兵的保留时长（毫秒），默认 5000。
         * REPLAY 模式下业务耗时略超占位 TTL 时，结果写入哨兵键供过期窗口内的
         * 重复请求重放；超过该窗口则与"TTL 过期后重新执行"语义一致。
         * 仅对默认 {@code RedisSetNxIdempotentPolicy} 生效，自定义策略按自身实现处理。
         */
        private long replayGraceMillis = 5000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public boolean isFailOpen() {
            return failOpen;
        }

        public void setFailOpen(boolean failOpen) {
            this.failOpen = failOpen;
        }

        public long getReplayGraceMillis() {
            return replayGraceMillis;
        }

        public void setReplayGraceMillis(long replayGraceMillis) {
            this.replayGraceMillis = replayGraceMillis;
        }
    }

    /**
     * 分布式锁模块配置项。
     */
    public static class Lock {

        /**
         * 分布式锁模块开关：为 false 时不装配锁切面与 LockTemplate。
         */
        private boolean enabled = true;

        /**
         * Redisson 键前缀，最终键 = 前缀 + 注解 SpEL 解析值（或方法级默认键）。
         */
        private String keyPrefix = "guard:lock:";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }
    }
}
