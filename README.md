# Guard Spring Boot Starter

轻量级并发防护 Spring Boot Starter：**注解式幂等（防重复提交）** 与 **注解式分布式锁**。
与 [api-governance](../api-governance-spring-boot-starte)（流量治理）、[OutboxPro](../OutboxPro)（消息可靠性）定位互补。

## 特性

- **幂等** `@Idempotent`：Redis setnx + TTL 原子占位；业务异常默认回滚占位（允许重试）；支持自定义 `IdempotentPolicy` 策略
- **锁** `@DistributedLock`：支持 `REDIS` 分布式锁与 `SYNCHRONIZED` JVM 本地锁；锁粒度由 SpEL key 决定；THROW / SKIP / CUSTOM 三种获取失败策略；另有支持 Lambda 代码块的程序化 `LockTemplate`
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

幂等需要 `spring-boot-starter-data-redis`。Redis 锁需要 `redisson`（或 `redisson-spring-boot-starter`），本地锁不需要 Redisson。锁组件本身按配置装配，Redisson 采用延迟获取：只有实际使用 Redis 锁时才要求容器中存在 `RedissonClient`。

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
// 默认 type = REDIS；锁粒度由 key 决定：不同 skuId 完全并行，同 skuId 互斥
@DistributedLock(key = "'stock:' + #skuId", waitTime = 2)
public void deductStock(Long skuId, int count) { ... }

// 单实例场景可以使用 JVM 本地锁，不依赖 Redisson
@DistributedLock(key = "'stock:' + #skuId", type = LockType.SYNCHRONIZED)
public void deductStockLocally(Long skuId, int count) { ... }

// 只有局部代码需要锁时，使用 Lambda 代码块
lockTemplate.withLocalLock("stock:1001", () -> doDeductStock());

// 程序化 Redis 锁
lockTemplate.withLock("stock:1001", () -> doDeductStock());
```


### 锁类型与使用范围

`@DistributedLock` 用于保护整个方法，`type` 声明锁实现：

| 类型 | 实现 | 适用场景 |
|---|---|---|
| `LockType.REDIS` | Redisson 分布式锁 | 多实例部署、跨进程互斥 |
| `LockType.SYNCHRONIZED` | JVM 内 `ReentrantLock` | 单实例部署、低延迟本地互斥 |

本地锁只在当前 JVM 内生效，不能替代多实例环境下的分布式锁。不同锁类型使用相同 key 时也不是同一把锁，不应混用来保护同一资源。

需要只保护方法中的一段代码时，使用 `LockTemplate`：

```java
public void process(String id) {
    validate(id);

    lockTemplate.withLocalLock("resource:" + id, () -> {
        updateSharedState(id); // 只有 Lambda 内的代码处于锁保护范围
    });

    publishEvent(id);
}
```

`withLocalLock` 支持返回值和自定义等待时间：

```java
Order order = lockTemplate.withLocalLock(
        "order:" + orderId,
        500,
        TimeUnit.MILLISECONDS,
        () -> createOrder(orderId)
);
```

`withLock` 是程序化 Redis 锁 API。Redis 锁的默认等待时间为 3 秒，`leaseTime = -1` 时使用 Redisson 看门狗续期。

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
| 容器无 `IdempotentPolicy` / `RedissonClient` Bean | 本地锁可正常使用；Redis 功能在首次实际调用时抛出带修复指引的异常 |
| `leaseTime = -1`（默认） | Redis 锁使用 Redisson 看门狗自动续期，持锁至方法结束；本地锁忽略此属性 |
| 锁释放 | Redis 锁在 finally 中校验当前线程持有后释放；本地锁在 finally 中释放并清理锁槽位 |
| Redisson 初始化 | 不在 `LockAspect` / `LockTemplate` 实例化时要求 `RedissonClient`；仅实际使用 Redis 路径时校验 |

## 构建

```bash
./mvnw clean verify        # 全量测试（需本机 Docker，Testcontainers 起 Redis）
./mvnw clean install
```
