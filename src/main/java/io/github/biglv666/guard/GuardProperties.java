package io.github.biglv666.guard;

import org.springframework.beans.factory.InitializingBean;
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
public class GuardProperties implements InitializingBean {

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

    /**
     * 启动期注解/SpEL 校验配置。
     *
     * @since 0.2.2
     */
    private final Validation validation = new Validation();

    /**
     * 兼容性开关。
     *
     * @since 0.3.0
     */
    private final Compat compat = new Compat();

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

    public Validation getValidation() {
        return validation;
    }

    public Compat getCompat() {
        return compat;
    }

    /**
     * 启动期配置校验：非法值在容器启动时直接失败，避免运行期出现难以排查的行为。
     *
     * @throws IllegalStateException 配置非法时
     * @since 0.2.2
     */
    @Override
    public void afterPropertiesSet() {
        String store = idempotent.getStore();
        if (!"redis".equals(store) && !"local".equals(store)) {
            throw new IllegalStateException("guard.idempotent.store 只能是 redis 或 local（小写），当前: "
                    + store);
        }
        if (idempotent.getReplayGraceMillis() < 0) {
            throw new IllegalStateException("guard.idempotent.replay-grace-millis 不能为负，当前: "
                    + idempotent.getReplayGraceMillis());
        }
        if (idempotent.getLocalMaxEntries() <= 0) {
            throw new IllegalStateException("guard.idempotent.local-max-entries 必须大于 0，当前: "
                    + idempotent.getLocalMaxEntries());
        }
        if (idempotent.getKeyPrefix() == null) {
            throw new IllegalStateException("guard.idempotent.key-prefix 不能为 null");
        }
        if (lock.getKeyPrefix() == null) {
            throw new IllegalStateException("guard.lock.key-prefix 不能为 null");
        }
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
         * 幂等策略存储类型：
         * {@code redis}（默认）= 使用 Redis 占位（多实例安全，要求 Redis 环境）；
         * {@code local} = 使用进程内占位（单实例部署，不依赖 Redis，无跨进程互斥能力）。
         * 注册了自定义 {@code IdempotentPolicy} Bean 时本配置不生效。
         */
        private String store = "redis";

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
         * REPLAY 模式下业务耗时略超占位 TTL 时，结果写入哨兵供过期窗口内的
         * 重复请求重放；超过该窗口则与"TTL 过期后重新执行"语义一致。
         * 对默认 Redis 策略与本地策略均生效。
         */
        private long replayGraceMillis = 5000;

        /**
         * 本地策略的过期清理阈值（条目数）：{@code store=local} 时条目数超过该值
         * 触发一次过期扫描。这是清理触发线而非硬上限，条目长期存活且持续增长
         * 的场景应改用 Redis 策略。
         */
        private int localMaxEntries = 65536;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getStore() {
            return store;
        }

        public void setStore(String store) {
            this.store = store;
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

        public int getLocalMaxEntries() {
            return localMaxEntries;
        }

        public void setLocalMaxEntries(int localMaxEntries) {
            this.localMaxEntries = localMaxEntries;
        }
    }

    /**
     * 兼容性配置项。
     *
     * @since 0.3.0
     */
    public static class Compat {

        /**
         * 是否沿用 0.2.x 的方法级默认键 {@code 类名#方法名}。
         *
         * <p>0.3.0 起默认使用包含参数类型的格式，避免重载方法互撞。
         * 滚动升级期间如需保持旧键（例如旧占位尚未过期），可临时设为 true，
         * 待 TTL 窗口过去后恢复 false。</p>
         */
        private boolean legacyDefaultKey = false;

        public boolean isLegacyDefaultKey() {
            return legacyDefaultKey;
        }

        public void setLegacyDefaultKey(boolean legacyDefaultKey) {
            this.legacyDefaultKey = legacyDefaultKey;
        }
    }

    /**
     * 启动期注解/SpEL 校验配置项。
     *
     * <p>校验在 Spring 容器单例初始化完成后执行一次，只影响启动阶段，不增加请求期开销。
     * 默认对语法错误、变量名错误、注解属性组合错误快速失败；属性名拼写检查默认只告警，
     * 避免泛型/动态代理场景误报。</p>
     *
     * @since 0.2.2
     */
    public static class Validation {

        /**
         * 是否启用启动期扫描校验，默认 true。
         */
        private boolean enabled = true;

        /**
         * 发现错误时是否阻止应用启动：
         * true（默认）= 抛出 {@code GuardValidationException}；
         * false = 只记录 error 日志，继续启动。
         */
        private boolean failFast = true;

        /**
         * 属性名拼写检查级别：
         * {@code off} = 只校验语法和变量名；
         * {@code warn}（默认）= 属性/方法名疑似写错时记录 warn，不阻断启动；
         * {@code strict} = 属性/方法名找不到时视为错误（受 fail-fast 控制）。
         */
        private String propertyCheck = "warn";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isFailFast() {
            return failFast;
        }

        public void setFailFast(boolean failFast) {
            this.failFast = failFast;
        }

        public String getPropertyCheck() {
            return propertyCheck;
        }

        public void setPropertyCheck(String propertyCheck) {
            this.propertyCheck = propertyCheck;
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

        /**
         * 锁基础设施故障时的降级策略：
         * false（默认）= fail-close，拒绝请求并抛 {@code LockAcquireFailureException}；
         * true = fail-open，跳过加锁直接执行业务并记录降级事件/指标。
         *
         * <p>注意：fail-open 会放弃互斥保证，只适合"可用性优先且业务能容忍并发"的场景；
         * 配置错误（缺少执行器/RedissonClient、CUSTOM 未指定 handler）不受本开关影响。</p>
         *
         * @since 0.2.2
         */
        private boolean failOpen = false;

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
    }
}
