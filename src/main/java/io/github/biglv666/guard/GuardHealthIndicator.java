package io.github.biglv666.guard;

import io.github.biglv666.guard.idempotent.IdempotentPolicy;
import io.github.biglv666.guard.lock.LockExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.List;

/**
 * Guard 配置健康指示器：展示当前装配的幂等策略、锁执行器与降级开关。
 *
 * <p>本指示器只反映配置/装配状态，不主动探测 Redis 连通性——Redis 连通性由
 * Spring Boot 自带的 Redis health contributor 负责，避免重复网络探测。</p>
 *
 * @author Guard Team
 * @since 0.3.0
 */
public class GuardHealthIndicator implements HealthIndicator {

    private final ObjectProvider<IdempotentPolicy> policyProvider;
    private final ObjectProvider<LockExecutor> executorProvider;
    private final GuardProperties properties;

    public GuardHealthIndicator(ObjectProvider<IdempotentPolicy> policyProvider,
                                ObjectProvider<LockExecutor> executorProvider,
                                GuardProperties properties) {
        this.policyProvider = policyProvider;
        this.executorProvider = executorProvider;
        this.properties = properties;
    }

    @Override
    public Health health() {
        IdempotentPolicy policy = policyProvider.getIfAvailable();
        List<LockExecutor> executors = executorProvider.orderedStream().toList();
        List<String> lockExecutors = executors.stream()
                .map(executor -> executor.type().name())
                .toList();
        List<String> unavailableLockExecutors = executors.stream()
                .filter(executor -> !executor.isAvailable())
                .map(executor -> executor.type().name())
                .toList();

        boolean idempotentMissing = properties.getIdempotent().isEnabled() && policy == null;
        boolean lockMissing = properties.getLock().isEnabled() && executors.isEmpty();
        Health.Builder builder = idempotentMissing || lockMissing ? Health.down() : Health.up();

        return builder
                .withDetail("idempotentStore", properties.getIdempotent().getStore())
                .withDetail("idempotentPolicy", policy != null ? policy.getClass().getName() : "none")
                .withDetail("idempotentPolicyPresent", policy != null)
                .withDetail("idempotentFailOpen", properties.getIdempotent().isFailOpen())
                .withDetail("lockExecutors", lockExecutors)
                .withDetail("unavailableLockExecutors", unavailableLockExecutors)
                .withDetail("lockFailOpen", properties.getLock().isFailOpen())
                .build();
    }
}
