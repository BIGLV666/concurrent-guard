# Guard Spring Boot Starter

轻量级并发防护 Spring Boot Starter：**注解式幂等（防重复提交）** 与 **注解式分布式锁**。
与 [api-governance](../api-governance-spring-boot-starte)（流量治理）、[OutboxPro](../OutboxPro)（消息可靠性）定位互补。

## 特性

- **幂等** `@Idempotent`：Redis setnx + TTL 原子占位；业务异常默认回滚占位（允许重试）；支持自定义 `IdempotentPolicy` 策略
- **分布式锁** `@DistributedLock`：基于 Redisson，看门狗自动续期；锁粒度由 SpEL key 决定；THROW / SKIP / CUSTOM 三种获取失败策略；另有程序化 `LockTemplate`
- **事件 SPI**：幂等拒绝、锁超时统一发布 `GuardRejectedEvent`（Spring ApplicationEvent），业务方订阅后自由接告警
- **指标**：宿主有 Micrometer 时自动上报 `guard_rejected_total{type=...}`
- **快速失败**：key 的 SpEL 配置错误在首次调用即抛清晰异常，不静默回退

## 快速开始

```xml
<dependency>
    <groupId>io.github.biglv666</groupId>
    <artifactId>guard-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

幂等需要 `spring-boot-starter-data-redis`；分布式锁需要 `redisson`（或 `redisson-spring-boot-starter`）。两者都按需引入，缺哪个哪个功能不装配。

### 幂等

```java
@Idempotent(key = "#order.orderId", ttl = 30, timeUnit = TimeUnit.SECONDS)
@PostMapping("/order")
public Result submit(@RequestBody Order order) {
    // TTL 窗口内同 orderId 的重复请求被拒绝
    // 业务抛异常时占位自动回滚，允许重试（rollbackOnException = false 可关闭）
    return doSubmit(order);
}
```

### 分布式锁

```java
// 锁粒度由 key 决定：不同 skuId 完全并行，同 skuId 互斥
@DistributedLock(key = "'stock:' + #skuId", waitTime = 2)
public void deductStock(Long skuId, int count) { ... }

// 细粒度场景用程序化 API
lockTemplate.withLock("stock:1001", () -> doDeductStock());
```

### 自定义幂等策略

```java
@Bean
public IdempotentPolicy idempotentPolicy() {
    return new MyDatabaseUniqueConstraintPolicy(); // 覆盖默认的 RedisSetNxIdempotentPolicy
}
```

### 事件订阅（接告警）

```java
@EventListener
public void onGuardRejected(GuardRejectedEvent event) {
    alertClient.send(event.getType() + " key=" + event.getKey() + " method=" + event.getMethod());
}
```

## 配置

```yaml
guard:
  enabled: true                 # 总开关
  idempotent:
    enabled: true
    key-prefix: "guard:idempotent:"
    fail-open: true             # Redis 故障时 true=放行（可用性优先）false=拒绝（一致性优先）
  lock:
    enabled: true
    key-prefix: "guard:lock:"
```

## 行为约定

| 场景 | 行为 |
|---|---|
| 业务异常（幂等，默认） | 释放占位，允许立即重试 |
| 业务异常（`rollbackOnException = false`） | 占位保留至 TTL，窗口内重试被拒绝 |
| Redis 故障（幂等） | 按 `fail-open` 放行或拒绝，不抛基础设施异常 |
| 容器无 `IdempotentPolicy` / `RedissonClient` Bean | 启动不崩溃，首次使用抛出带修复指引的异常 |
| `leaseTime = -1`（默认） | Redisson 看门狗自动续期，持锁至方法结束 |
| 锁释放 | finally 中 `isHeldByCurrentThread()` 校验后释放，不误释放他人锁 |

## 构建

```bash
./mvnw clean verify        # 全量测试（需本机 Docker，Testcontainers 起 Redis）
./mvnw clean install
```
