package io.github.biglv666.guard;

import io.github.biglv666.guard.idempotent.IdempotentAspect;
import io.github.biglv666.guard.idempotent.IdempotentPolicy;
import io.github.biglv666.guard.idempotent.JacksonResultCodec;
import io.github.biglv666.guard.idempotent.LocalIdempotentPolicy;
import io.github.biglv666.guard.idempotent.RedisSetNxIdempotentPolicy;
import io.github.biglv666.guard.idempotent.ResultCodec;
import io.github.biglv666.guard.internal.GuardAnnotationValidator;
import io.github.biglv666.guard.internal.SpelKeyResolver;
import io.github.biglv666.guard.lock.LockAspect;
import io.github.biglv666.guard.lock.LockExecutor;
import io.github.biglv666.guard.lock.LockTemplate;
import io.github.biglv666.guard.lock.LocalLockExecutor;
import io.github.biglv666.guard.lock.LocalLockManager;
import io.github.biglv666.guard.lock.RedissonLockExecutor;
import io.github.biglv666.guard.metrics.GuardMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Guard 并发防护组件的自动装配入口。
 *
 * <p>装配规则：
 * <ul>
 *   <li>{@code guard.enabled=false} 关闭一切；</li>
 *   <li>幂等模块：{@code guard.idempotent.enabled=true} 时装配切面；
 *       策略按 {@code guard.idempotent.store} 选择——{@code redis}（默认）需要
 *       Spring Data Redis 与 StringRedisTemplate，{@code local} 用进程内策略（无 Redis 依赖）；
 *       自定义 {@link IdempotentPolicy} Bean 可覆盖两者；</li>
 *   <li>锁模块：{@code guard.lock.enabled=true} 时装配，支持 Redis 锁与 JVM 本地锁；
 *       容器中缺少 {@link RedissonClient} 不影响本地锁，只有实际使用 Redis 锁时才抛出清晰配置错误。</li>
 * </ul>
 *
 * @author Guard Team
 * @since 0.1.0
 */
@AutoConfiguration(after = RedisAutoConfiguration.class)
@ConditionalOnProperty(prefix = "guard", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(GuardProperties.class)
public class GuardAutoConfiguration {

    /**
     * SpEL key 解析器（按 Method 缓存编译结果），用户可覆盖以自定义解析行为。
     */
    @Bean
    @ConditionalOnMissingBean
    public SpelKeyResolver spelKeyResolver() {
        return new SpelKeyResolver();
    }

    /**
     * 启动期注解/SpEL 校验器：单例初始化完成后扫描注解方法，
     * 默认 fail-fast 一次性列出所有配置错误。
     *
     * @since 0.2.2
     */
    @Bean
    @ConditionalOnMissingBean
    public GuardAnnotationValidator guardAnnotationValidator(ConfigurableListableBeanFactory beanFactory,
                                                             SpelKeyResolver keyResolver,
                                                             GuardProperties guardProperties) {
        return new GuardAnnotationValidator(beanFactory, keyResolver, guardProperties.getValidation());
    }

    /**
     * 指标上报器：宿主存在 MeterRegistry 时自动生效，否则为空操作。
     */
    @Bean
    @ConditionalOnMissingBean
    public GuardMetrics guardMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        return new GuardMetrics(registryProvider);
    }

    /**
     * 健康检查：仅在宿主引入 actuator 时注册，展示配置/装配状态。
     *
     * @since 0.3.0
     */
    @ConditionalOnClass(HealthIndicator.class)
    static class HealthConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public GuardHealthIndicator guardHealthIndicator(ObjectProvider<IdempotentPolicy> policyProvider,
                                                         ObjectProvider<LockExecutor> executorProvider,
                                                         GuardProperties guardProperties) {
            return new GuardHealthIndicator(policyProvider, executorProvider, guardProperties);
        }
    }

    /**
     * 幂等模块装配：切面始终装配（本地策略不依赖 Redis）；
     * 策略按 {@code guard.idempotent.store} 分流——local 装配进程内策略，
     * redis（默认）走 {@link RedisIdempotentPolicyConfiguration}。
     */
    @ConditionalOnProperty(prefix = "guard.idempotent", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class IdempotentConfiguration {

        /**
         * 本地幂等策略（进程内占位，无跨进程互斥能力）：store=local 显式启用，
         * 面向不依赖 Redis 的单实例部署。用户注册自定义 IdempotentPolicy Bean 时不装配。
         */
        @Bean
        @ConditionalOnMissingBean(IdempotentPolicy.class)
        @ConditionalOnProperty(prefix = "guard.idempotent", name = "store", havingValue = "local")
        public IdempotentPolicy localIdempotentPolicy(GuardProperties guardProperties) {
            GuardProperties.Idempotent props = guardProperties.getIdempotent();
            return new LocalIdempotentPolicy(
                    Duration.ofMillis(props.getReplayGraceMillis()), props.getLocalMaxEntries());
        }

        @Bean
        public IdempotentAspect idempotentAspect(ObjectProvider<IdempotentPolicy> policyProvider,
                                                 ObjectProvider<ResultCodec> codecProvider,
                                                 SpelKeyResolver keyResolver,
                                                 GuardProperties guardProperties,
                                                 GuardMetrics guardMetrics,
                                                 ApplicationEventPublisher eventPublisher) {
            return new IdempotentAspect(policyProvider::getIfAvailable, codecProvider::getIfAvailable,
                    keyResolver, guardProperties.getIdempotent(), guardProperties.getCompat(),
                    guardMetrics, eventPublisher);
        }
    }

    /**
     * Redis 幂等策略装配：仅当 classpath 存在 Spring Data Redis、容器有
     * StringRedisTemplate 且 store=redis（默认）时生效。
     */
    @ConditionalOnClass(RedisOperations.class)
    @ConditionalOnProperty(prefix = "guard.idempotent", name = "store", havingValue = "redis", matchIfMissing = true)
    static class RedisIdempotentPolicyConfiguration {

        /**
         * 默认幂等策略（Redis setnx + 令牌归属 + 慢请求完成哨兵）：
         * 用户注册自定义 IdempotentPolicy Bean 时此处不装配。
         * 依赖 StringRedisTemplate（Redis 自动配置提供），故声明在 RedisAutoConfiguration 之后判定。
         */
        @Bean
        @ConditionalOnMissingBean(IdempotentPolicy.class)
        @ConditionalOnBean(StringRedisTemplate.class)
        public IdempotentPolicy idempotentPolicy(StringRedisTemplate redisTemplate,
                                                 GuardProperties guardProperties) {
            return new RedisSetNxIdempotentPolicy(redisTemplate,
                    Duration.ofMillis(guardProperties.getIdempotent().getReplayGraceMillis()));
        }
    }

    /**
     * 结果编解码器装配：仅当 classpath 存在 Jackson 时生效。
     * 业务方注册自定义 ResultCodec Bean 时此处不装配。
     */
    @ConditionalOnClass(ObjectMapper.class)
    static class ResultCodecConfiguration {

        @Bean
        @ConditionalOnMissingBean(ResultCodec.class)
        public ResultCodec resultCodec() {
            return new JacksonResultCodec();
        }
    }

    /**
     * 本地锁模块装配：不依赖 Redisson，classpath 中没有 Redisson 时仍可正常使用。
     */
    @ConditionalOnProperty(prefix = "guard.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class LocalLockConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public LocalLockManager localLockManager() {
            return new LocalLockManager();
        }

        @Bean
        @ConditionalOnMissingBean
        public LocalLockExecutor localLockExecutor(LocalLockManager localLockManager) {
            return new LocalLockExecutor(localLockManager);
        }

        @Bean
        @ConditionalOnMissingBean
        public LockAspect lockAspect(ObjectProvider<LockExecutor> executorProvider,
                                     ObjectProvider<ApplicationContext> applicationContextProvider,
                                     SpelKeyResolver keyResolver,
                                     GuardProperties guardProperties,
                                     GuardMetrics guardMetrics,
                                     ApplicationEventPublisher eventPublisher) {
            return new LockAspect(executorProvider.orderedStream().toList(), applicationContextProvider,
                    keyResolver, guardProperties.getLock(), guardProperties.getCompat(),
                    guardMetrics, eventPublisher);
        }

        @Bean
        @ConditionalOnMissingBean
        public LockTemplate lockTemplate(ObjectProvider<LockExecutor> executorProvider,
                                         GuardProperties guardProperties,
                                         GuardMetrics guardMetrics,
                                         ApplicationEventPublisher eventPublisher) {
            return new LockTemplate(executorProvider.orderedStream().toList(),
                    guardProperties.getLock(), guardMetrics, eventPublisher);
        }
    }

    /**
     * Redisson 分布式锁执行器装配：仅在 classpath 存在 Redisson 时生效。
     * 与本地锁配置分离，保证无 Redisson 的部署仍可使用本地锁。
     */
    @ConditionalOnClass(RedissonClient.class)
    @ConditionalOnProperty(prefix = "guard.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class RedissonLockConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public RedissonLockExecutor redissonLockExecutor(ObjectProvider<RedissonClient> redissonProvider) {
            return new RedissonLockExecutor(redissonProvider);
        }
    }
}




