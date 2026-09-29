package io.github.biglv666.guard;

import io.github.biglv666.guard.idempotent.IdempotentAspect;
import io.github.biglv666.guard.idempotent.IdempotentPolicy;
import io.github.biglv666.guard.idempotent.JacksonResultCodec;
import io.github.biglv666.guard.idempotent.RedisSetNxIdempotentPolicy;
import io.github.biglv666.guard.idempotent.ResultCodec;
import io.github.biglv666.guard.internal.SpelKeyResolver;
import io.github.biglv666.guard.lock.LockAspect;
import io.github.biglv666.guard.lock.LockTemplate;
import io.github.biglv666.guard.lock.LocalLockManager;
import io.github.biglv666.guard.metrics.GuardMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
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
 *   <li>幂等模块：classpath 存在 Spring Data Redis 且 {@code guard.idempotent.enabled=true}
 *       时装配；默认策略 {@link RedisSetNxIdempotentPolicy} 可被用户注册的
 *       {@link IdempotentPolicy} Bean 覆盖；</li>
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
     * 指标上报器：宿主存在 MeterRegistry 时自动生效，否则为空操作。
     */
    @Bean
    @ConditionalOnMissingBean
    public GuardMetrics guardMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        return new GuardMetrics(registryProvider);
    }

    /**
     * 幂等模块装配：仅当 classpath 存在 Spring Data Redis 时生效。
     */
    @ConditionalOnClass(RedisOperations.class)
    @ConditionalOnProperty(prefix = "guard.idempotent", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class IdempotentConfiguration {

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

        @Bean
        public IdempotentAspect idempotentAspect(ObjectProvider<IdempotentPolicy> policyProvider,
                                                 ObjectProvider<ResultCodec> codecProvider,
                                                 SpelKeyResolver keyResolver,
                                                 GuardProperties guardProperties,
                                                 GuardMetrics guardMetrics,
                                                 ApplicationEventPublisher eventPublisher) {
            return new IdempotentAspect(policyProvider::getIfAvailable, codecProvider::getIfAvailable,
                    keyResolver, guardProperties.getIdempotent(), guardMetrics, eventPublisher);
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
     * 锁模块装配：本地锁不依赖 Redisson，Redis 客户端在实际使用 Redis 锁时懒获取。
     */
    @ConditionalOnClass(RedissonClient.class)
    @ConditionalOnProperty(prefix = "guard.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class LockConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public LocalLockManager localLockManager() {
            return new LocalLockManager();
        }

        @Bean
        public LockAspect lockAspect(ObjectProvider<RedissonClient> redissonProvider,
                                     ObjectProvider<ApplicationContext> applicationContextProvider,
                                     SpelKeyResolver keyResolver,
                                     GuardProperties guardProperties,
                                     GuardMetrics guardMetrics,
                                     ApplicationEventPublisher eventPublisher,
                                     LocalLockManager localLockManager) {
            return new LockAspect(redissonProvider, applicationContextProvider, keyResolver,
                    guardProperties.getLock(), guardMetrics, eventPublisher, localLockManager);
        }

        @Bean
        public LockTemplate lockTemplate(ObjectProvider<RedissonClient> redissonProvider,
                                         GuardProperties guardProperties,
                                         LocalLockManager localLockManager,
                                         GuardMetrics guardMetrics) {
            return new LockTemplate(redissonProvider, guardProperties.getLock(), localLockManager, guardMetrics);
        }
    }
}




