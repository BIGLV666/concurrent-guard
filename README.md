# Guard Spring Boot Starter

[![Maven Central](https://img.shields.io/maven-central/v/io.github.biglv666/guard-spring-boot-starter)](https://central.sonatype.com/artifact/io.github.biglv666/guard-spring-boot-starter) [![CI](https://github.com/BIGLV666/concurrent-guard/actions/workflows/ci.yml/badge.svg)](https://github.com/BIGLV666/concurrent-guard/actions/workflows/ci.yml)

轻量级并发防护 Spring Boot Starter：**注解式幂等（防重复提交）** 与 **注解式分布式锁**。
与 [api-governance](../api-governance-spring-boot-starter)（流量治理）、[OutboxPro](../OutboxPro)（消息可靠性）定位互补。

## 特性

- **幂等** `@Idempotent`：Redis setnx + TTL 原子占位（令牌归属校验，回滚/保存不误伤他人占位）；业务异常默认回滚占位（允许重试）；REPLAY 模式支持 TTL 窗口重放、慢请求完成哨兵与可选等待模式；`condition`/`unless` 条件防护；异步 `CompletionStage` 完成后才保存/释放；`store=local` 可切换进程内策略（无 Redis 依赖）；支持自定义 `IdempotentPolicy` 策略
- **锁** `@DistributedLock`：支持 `REDIS` 分布式锁与 `SYNCHRONIZED` JVM 本地锁；锁粒度由 SpEL key 决定；THROW / SKIP / CUSTOM 三种获取失败策略；另有支持 Lambda 代码块的程序化 `LockTemplate`
- **默认 key 精确化**：0.3.0 起方法级默认键包含参数类型，重载方法不再互撞；`guard.compat.legacy-default-key=true` 可回退旧格式
- **事件 SPI**：幂等拒绝、锁超时统一发布 `GuardRejectedEvent`（Spring ApplicationEvent），业务方订阅后自由接告警
- **指标**：宿主有 Micrometer 时自动上报拒绝计数 `guard_rejected_total`、降级计数 `guard_degraded_total`、重放计数 `guard_replayed_total`、锁获取耗时 `guard_lock_acquire`、锁持有时长 `guard_lock_hold`
- **健康检查**：宿主有 actuator 时自动注册 `GuardHealthIndicator`，展示幂等策略、锁执行器与降级配置
- **快速失败 + 启动期校验**：key 的 SpEL 配置错误在启动阶段集中校验（语法/变量名/属性名/位置变量），
  默认一次性列出所有问题并阻止启动；首次调用仍保留兜底快速失败，不静默回退

## 快速开始

```xml
<dependency>
    <groupId>io.github.biglv666</groupId>
    <artifactId>guard-spring-boot-starter</artifactId>
    <version>0.3.0</version>
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

### 幂等结果重放（REPLAY 模式）

```java
// 窗口内的重复请求直接返回首次的处理结果，而不是收到异常
// 适用于支付回调、状态查询等"重复请求应得到相同响应"的场景
@Idempotent(key = "#order.orderId", mode = IdempotentMode.REPLAY, ttl = 30, timeUnit = TimeUnit.SECONDS)
public Result submit(@RequestBody Order order) { ... }
```

REPLAY 模式行为：首个请求正常返回后，返回值被序列化保存（默认 Jackson，可自定义 `ResultCodec` Bean 覆盖）；
窗口内的重复请求拿到首次结果（不执行业务方法）；首个请求**仍在处理中**时，重复请求按拒绝处理，
或配置 `waitMillis` 在等待窗口内轮询直至拿到结果（见下）；
业务异常的回滚行为与 REJECT 模式一致（`rollbackOnException`），异常本身不会被重放。
返回值必须是默认 Jackson 及其已发现模块可序列化/反序列化的类型（例如 `LocalDateTime`、`Instant` 等
JSR-310 时间类型需要类路径包含 `jackson-datatype-jsr310`）；也可通过自定义 `ResultCodec` 支持其他类型。
REPLAY 的 TTL 必须不小于业务最大耗时，否则占位过期后新请求会重新进入业务（幂等窗口失效，业务可能重复执行）；
占位的回滚与结果保存均带归属令牌校验，不会误删或覆盖其他请求的占位（0.2.0 的覆盖串扰问题已修复）。
业务耗时仅略超 TTL 时，结果会写入短期哨兵（默认 5 秒，可配 `guard.idempotent.replay-grace-millis`），
过期窗口内到达的重复请求仍可重放，不必把 TTL 配得过大；占位被新请求接管（而非过期）时不写哨兵，不会串扰。
`waitMillis > 0` 时，处理中的重复请求每 50ms 轮询一次重放结果（含哨兵），窗口内拿到首次结果则直接返回，
超时或被中断则拒绝；等待会持有当前调用线程，建议配置为业务可接受的短暂窗口；仅 REPLAY 模式可用。
自定义 `IdempotentPolicy` 需实现 `saveResult`/`loadResult` 并覆写 `supportsReplay()` 才能配合 REPLAY 使用，
否则首次调用即抛出带修复指引的异常。

### 条件幂等（condition / unless）

```java
// 只有 amount > 0 的请求才做幂等；false 时直接执行业务，不占位
@Idempotent(key = "#order.orderId", condition = "#order.amount > 0")
public Result submit(@RequestBody Order order) { ... }

// 业务返回 null 或失败结果时不保留占位，允许后续请求重试
@Idempotent(key = "#order.orderId", unless = "#result == null")
public Result query(@RequestBody Order order) { ... }
```

- `condition` 在 key 解析/占位之前求值，false 时完全跳过幂等防护；
- `unless` 在业务成功返回后求值，可用 `#result`；true 时释放占位且不保存 REPLAY 结果；
- 表达式语法错误启动期即失败；`unless` 运行期求值失败只记 warn，不影响业务返回值。

### 异步方法

`@Idempotent` 支持返回 `CompletionStage` / `CompletableFuture` 的方法：占位保存、异常回滚、
`unless` 判断都会等异步任务真正完成后执行，不会在方法返回 Future 时提前处理。

```java
@Idempotent(key = "#orderId", mode = IdempotentMode.REPLAY)
public CompletableFuture<Result> submit(String orderId) {
    return CompletableFuture.supplyAsync(() -> doSubmit(orderId));
}
```

`@DistributedLock` **不支持**异步返回：Redisson/本地锁都要求由获取线程释放，
异步完成线程无法安全解锁。启动期校验会直接报错，请在异步任务内部使用 `LockTemplate`，
或改用幂等/其他并发控制方案。响应式 `Mono` / `Flux` 同样暂不支持。

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

0.3.0 新增：

```java
// 超时返回 Optional.empty()，不抛异常
Optional<Order> order = lockTemplate.tryWithLock("order:" + orderId, () -> loadOrder(orderId));

// 支持受检异常
Order order2 = lockTemplate.callWithLock("order:" + orderId, 3, -1, TimeUnit.SECONDS,
        () -> loadOrderChecked(orderId));

// 超时执行回退逻辑
Order order3 = lockTemplate.withLock("order:" + orderId, 3, -1, TimeUnit.SECONDS,
        () -> loadOrder(orderId), () -> cachedOrder(orderId));
```

`tryWithLock` / `callWithLock` / 回退重载同样有 `withLocalLock` / `tryWithLocalLock` / `callWithLocalLock` 版本。
`LockTemplate` 的代码块如果返回 `CompletionStage` 会快速失败，原因与注解锁相同：锁必须由获取线程释放。

### SpEL key 校验

`@Idempotent` / `@DistributedLock` 的 key 是字符串表达式，写错时过去只能在首次调用时暴露。
0.2.2 起默认在应用启动阶段扫描所有 Bean 的注解方法并集中校验：

```yaml
guard:
  validation:
    enabled: true          # 默认开启启动期扫描
    fail-fast: true        # true=启动失败；false=只记 error 日志
    property-check: warn   # off / warn / strict
```

可发现的问题：

- 表达式语法错误；
- 变量名拼写错误（错误信息会列出可用变量）；
- `#p0` / `#a0` 位置变量越界；
- `T(...)` 类型引用、`new ...` 构造器（`SimpleEvaluationContext` 安全限制）；
- 属性/方法名拼写（`warn` 只告警，`strict` 视为错误）；属性检查遵循 SpEL 实际访问规则，
  只认 public getter 与 public 字段，private 字段没有 getter 会被提示（strict 下视为错误）；
- `ttl <= 0`、`waitMillis` 用于 REJECT、`CUSTOM` 未指定 handler、
  `SKIP` 用于基本类型返回值等注解属性组合问题。

错误会一次性聚合，示例：

```text
Guard 注解校验失败，共 2 处：
1. com.foo.OrderService#submit(Order) -> @Idempotent key 表达式: 变量 #oder 不存在；可用变量: order, p0, a0, root
2. com.foo.StockService#deduct(Long) -> @DistributedLock key 表达式: 变量 #skuId 不存在；可用变量: sku, p0, a0, root
```

也可以在业务方单测中直接校验，无需启动 Spring：

```java
SpelKeyResolver resolver = new SpelKeyResolver();
SpelKeyResolver.ValidationResult result =
        resolver.validate(OrderService.class.getMethod("submit", Order.class), "#order.id");
assertFalse(result.hasIssues());
```

SpEL 上下文支持：

- 方法参数名（要求编译期 `-parameters`，Spring Boot 默认开启）；
- `#p0` / `#a0` 位置别名；
- `#root` / `#this`；
- 实例方法调用（如 `#order.getId()`）；
- 仍不开放 `T(...)`、静态方法和构造器，避免表达式注入。

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

### 指标

宿主引入 Micrometer（通常随 actuator）时自动上报，无需配置：

| 指标 | 类型 | tag | 含义 |
|---|---|---|---|
| `guard_rejected_total` | Counter | `type` | 拒绝/超时请求数（`IDEMPOTENT_REJECTED` / `IDEMPOTENT_DEGRADED`（fail-close 拒绝）/ `LOCK_TIMEOUT`） |
| `guard_degraded_total` | Counter | `type=IDEMPOTENT_DEGRADED` | 策略故障降级次数（fail-open 放行与 fail-close 拒绝都计入；放行不计入 `guard_rejected_total`） |
| `guard_replayed_total` | Counter | `type=IDEMPOTENT_REPLAYED` | REPLAY 模式下重放首次结果的次数 |
| `guard_lock_acquire` | Timer | `type`（`REDIS` / `SYNCHRONIZED`） | 成功获取锁的耗时分布（含等待，Prometheus 渲染为 `guard_lock_acquire_seconds_*`） |
| `guard_idempotent_ttl_exceeded_total` | Counter | `type=IDEMPOTENT_TTL_EXCEEDED` | 业务耗时超过幂等 TTL 的次数（占位可能提前过期） |
| `guard_lock_hold` | Timer | `type`（`REDIS` / `SYNCHRONIZED`） | 锁持有时长（从获取成功到释放） |

### 健康检查（可选）

宿主引入 actuator 时自动注册 `GuardHealthIndicator`，`/actuator/health` 会包含：

```json
{
  "guard": {
    "status": "UP",
    "details": {
      "idempotentStore": "redis",
      "idempotentPolicy": "io.github.biglv666.guard.idempotent.RedisSetNxIdempotentPolicy",
      "idempotentPolicyPresent": true,
      "idempotentFailOpen": true,
      "lockExecutors": ["SYNCHRONIZED", "REDIS"],
      "unavailableLockExecutors": [],
      "lockFailOpen": false
    }
  }
}
```

该指示器只反映配置/装配状态，不重复探测 Redis 连通性；Redis 连通性由 Spring Boot 自带的 Redis health contributor 负责。

### 拒绝异常的 HTTP 映射（可选）

Web 应用中，拒绝异常未被处理时默认返回 500。推荐用全局异常处理器映射为更语义化的状态码（不依赖 starter 强绑 Web 依赖）：

```java
@RestControllerAdvice
public class GuardExceptionAdvice {

    // 幂等拒绝 → 409 Conflict；remainingTtlMillis 提示调用方还需等待多久（-1 表示未知）
    @ExceptionHandler(IdempotentRejectedException.class)
    public ResponseEntity<Map<String, Object>> onIdempotentRejected(IdempotentRejectedException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "IDEMPOTENT_REJECTED");
        body.put("message", e.getMessage());
        body.put("remainingTtlMillis", e.getRemainingTtlMillis());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    // 锁等待超时 → 503，提示调用方稍后重试
    @ExceptionHandler(LockAcquireTimeoutException.class)
    public ResponseEntity<Map<String, String>> onLockTimeout(LockAcquireTimeoutException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", "LOCK_TIMEOUT", "message", e.getMessage()));
    }
}
```

## 配置

```yaml
guard:
  enabled: true                 # 总开关
  compat:
    legacy-default-key: false   # true=沿用 0.2.x 的 类名#方法名 默认键（滚动升级临时使用）
  validation:
    enabled: true               # 启动期扫描注解/SpEL，默认开启
    fail-fast: true             # 发现错误时启动失败；false=只记 error 日志
    property-check: warn        # off / warn / strict
  idempotent:
    enabled: true
    store: redis                # redis（默认）= Redis 占位；local = 进程内占位（单实例，无 Redis 依赖）
    key-prefix: "guard:idempotent:"
    fail-open: true             # Redis 故障时 true=放行（可用性优先）false=拒绝（一致性优先）
                                # 注意：配置错误（无策略 Bean / REPLAY 不支持 / ttl 非法）不受 fail-open 影响，一律快速失败
    replay-grace-millis: 5000   # REPLAY 慢请求完成哨兵保留时长（Redis 与本地策略均生效）
    local-max-entries: 65536    # 本地策略过期清理阈值（非硬上限，仅 store=local 生效）
  lock:
    enabled: true
    key-prefix: "guard:lock:"
    fail-open: false            # 锁基础设施故障时 false=拒绝（默认），true=跳过加锁放行
```

## 行为约定

| 场景 | 行为 |
|---|---|
| 业务异常（幂等，默认） | 校验占位归属后释放，允许立即重试；不会误删 TTL 耗尽后其他请求新写入的占位 |
| 业务异常（`rollbackOnException = false`） | 占位保留至 TTL，窗口内重试被拒绝 |
| Redis 故障（幂等） | 按 `fail-open` 放行或拒绝，不抛基础设施异常；降级计入 `guard_degraded_total` |
| key 表达式求值为 null/空白 | 快速失败抛 `KeyResolveException`（0.1.0 行为：静默回退方法级默认键，会导致防护粒度意外变粗） |
| `@Idempotent` 配置错误（ttl ≤ 0、waitMillis 负值或用于 REJECT、无策略 Bean、REPLAY 不支持等） | 首次调用抛带修复指引的 `IllegalStateException`，不进降级、不被 fail-open 吞掉 |
| `store = local` | 进程内占位（单实例部署，无跨进程互斥能力），幂等/REPLAY/哨兵/剩余 TTL 语义与 Redis 策略对齐；条目数超过 `local-max-entries` 触发过期扫描 |
| REPLAY 模式，重复请求且业务已完成 | 直接返回首次结果，不执行业务方法 |
| REPLAY 模式，首个请求仍在处理中 | 重复请求按拒绝处理（不等待）；`waitMillis > 0` 时在等待窗口内每 50ms 轮询重放结果，超时或被中断则拒绝 |
| REPLAY 模式，TTL 小于业务最大耗时 | 占位过期后新请求可重新进入业务（幂等窗口失效，业务可能重复执行）；结果保存带令牌校验，不会覆盖串扰；业务略超 TTL 时结果写入短期哨兵键，过期窗口内重复请求仍可重放 |
| REPLAY 模式，慢请求完成哨兵 | 占位已过期（非被接管）时结果写入哨兵键（默认保留 5 秒，可配 `guard.idempotent.replay-grace-millis`），过期窗口内重复请求重放哨兵结果；占位被新请求接管时不写哨兵 |
| REPLAY 模式，自定义 `ResultCodec` 保存原始字符串 | 避免产生以 `__guard:processing__:` 开头的内容（该前缀为处理中令牌保留值）；默认 Jackson JSON 不受影响 |
| REPLAY 模式，结果保存/反序列化失败 | 保存失败退回拒绝行为并记 warn；反序列化失败拒绝请求并发布降级事件 |
| REPLAY 模式，策略不支持重放 / 无 `ResultCodec` | 首次调用前抛出带修复指引的 `IllegalStateException` |
| 容器无 `IdempotentPolicy` / `RedissonClient` Bean | 本地锁可正常使用；Redis 功能在首次实际调用时抛出带修复指引的异常（幂等侧即使 fail-open 也不放行） |
| classpath 无 Redisson | 本地锁模块仍正常装配；Redis 锁调用在首次使用时提示缺少执行器/RedissonClient |
| 启动期注解/SpEL 校验 | 默认 fail-fast，一次性列出所有错误；`guard.validation.enabled=false` 可关闭，`fail-fast=false` 只记日志 |
| 事件监听器抛异常 | 只记 warn，不覆盖 Guard 拒绝异常，也不影响业务返回值 |
| `LockTemplate` 锁超时/中断 | 与注解切面一致：发布 `GuardRejectedEvent` 并计入 `guard_rejected_total` |
| Redis 锁基础设施故障 | 按 `guard.lock.fail-open`：false（默认）拒绝并抛 `LockAcquireFailureException`；true 跳过加锁放行，发布 `LOCK_DEGRADED` 并计降级指标 |
| 幂等业务耗时超过 TTL | 记录 warn 并累加 `guard_idempotent_ttl_exceeded_total`；建议增大 TTL 或检查慢业务 |
| Redis key 含非法花括号 | 抛 `IdempotentKeyException`，属于数据/配置错误，**不受 fail-open 影响** |
| 重载方法未显式写 key | 0.3.0 默认键包含参数类型，不再互撞；`guard.compat.legacy-default-key=true` 可回退旧格式 |
| `@Idempotent(condition=false)` | 跳过幂等防护，直接执行业务，不占位 |
| `@Idempotent(unless=true)` | 业务成功后释放占位且不保存 REPLAY 结果，允许后续请求重试 |
| 幂等方法返回 `CompletionStage` | 占位保存/异常回滚/unless 判断等异步完成后执行 |
| 锁方法返回 `CompletionStage` | 启动期快速失败；锁必须由获取线程释放，请在异步任务内使用 `LockTemplate` |
| `LockTemplate` 代码块返回 `CompletionStage` | 快速失败并释放锁，避免跨线程解锁 |
| `leaseTime = -1`（默认） | Redis 锁使用 Redisson 看门狗自动续期，持锁至方法结束；本地锁忽略此属性 |
| 锁等待被中断 | 恢复中断标记并抛 `LockAcquireInterruptedException`（`LockAcquireTimeoutException` 子类，现有异常处理器无需改动），与锁竞争超时区分 |
| 锁释放 | Redis 锁在 finally 中校验当前线程持有后释放；本地锁在 finally 中释放并清理锁槽位 |
| Redisson 初始化 | 不在 `LockAspect` / `LockTemplate` 实例化时要求 `RedissonClient`；仅实际使用 Redis 路径时校验 |

## 从 0.2.0 升级

- **自定义 `IdempotentPolicy` 零改动可运行**：新增的令牌链路（`tryAcquireToken` / `releaseIfOwned`）、
  重放读写（`saveResultForReplay` / `loadResultForReplay`，按归属分流主键与慢请求哨兵）与剩余 TTL 查询
  （`remainingTtlMillis`）均为 default 方法，旧实现自动获得无归属校验的兼容语义；
  实现令牌方法可获得"回滚/保存不误伤他人占位"的严格保护（默认 Redis 策略与本地策略均已实现）。
- **配置错误语义收紧**：无 `IdempotentPolicy` Bean、REPLAY 策略不支持、`ttl <= 0`、
  `waitMillis` 用于 REJECT 等在 0.2.0 会被 fail-open 静默放行或无校验，0.2.1 起一律快速失败抛 `IllegalStateException`。
- **指标变化**：fail-open 降级放行不再计入 `guard_rejected_total`，改计新增的
  `guard_degraded_total`；基于拒绝计数配置的告警规则不受影响。
- **锁中断语义**：等待加锁被中断现在抛 `LockAcquireInterruptedException`
  （`LockAcquireTimeoutException` 子类），现有 `LockAcquireTimeoutException` 处理器无需改动。

## 从 0.2.2 升级

- **默认 key 变更（需要关注）**：方法级默认键从 `类名#方法名` 改为 `类名#方法名(参数类型)`，
  修复重载方法互撞。滚动升级期间如旧占位尚未过期，可临时设置
  `guard.compat.legacy-default-key=true`，等 TTL 窗口过去后恢复 `false`。
- **异步幂等**：`@Idempotent` 支持 `CompletionStage`/`CompletableFuture`，结果保存与回滚
  延迟到异步完成；REPLAY 重放会返回已完成的 Future。
- **异步锁明确不支持**：`@DistributedLock` 返回 `CompletionStage` 会在启动期报错，
  `LockTemplate` 代码块返回 `CompletionStage` 会在运行期快速失败；锁必须由获取线程释放。
- **新增 LockTemplate API**：`tryWithLock`、`callWithLock`、超时回退重载，以及对应的本地锁版本。
- **新增健康检查/指标**：actuator 存在时注册 `GuardHealthIndicator`；新增 `guard_lock_hold`。
- **condition / unless**：新增幂等前置条件与后置排除表达式，`unless` 可用 `#result`。
- **属性校验更贴近 SpEL**：只认 public getter / public 字段；private 字段没有 getter 会被提示。
- **健康检查更严格**：幂等启用但无策略 Bean、或锁启用但无执行器时返回 DOWN；
  Redis 执行器缺少客户端时会在 `unavailableLockExecutors` 中展示。

## 从 0.2.1 升级

- **新增启动期校验，默认 fail-fast**：SpEL 语法错误、变量名错误、非法注解属性组合会在启动时
  一次性报出。若存量项目暂时无法修复，可先设置 `guard.validation.fail-fast=false`（只记日志）
  或 `guard.validation.enabled=false`（关闭扫描），修复后再恢复默认。
- **本地锁不再要求 Redisson 在 classpath**：锁模块拆分为本地锁与 Redisson 锁两个装配单元；
  仅使用 `LockType.SYNCHRONIZED` / `withLocalLock` 的部署可以不引入 Redisson。
- **`LockTemplate` 拒绝上报对齐切面**：程序化加锁超时/中断现在也会发布事件并计入拒绝指标。
- **事件监听器隔离**：监听器异常不再覆盖 Guard 拒绝异常；告警逻辑故障请查看 warn 日志。
- **新增锁故障降级开关**：`guard.lock.fail-open` 默认 `false`（fail-close）；开启后 Redis 锁
  基础设施故障会跳过加锁放行，并发布 `LOCK_DEGRADED`、计入降级指标。
- **新增 TTL 超时观测**：业务耗时超过幂等 TTL 时记录 warn 并累加
  `guard_idempotent_ttl_exceeded_total`。
- **集群花括号 key 快速失败**：无法安全映射 hash-tag 的业务 key 会在占位时抛
  `IdempotentKeyException`（`IllegalArgumentException` 子类），且不受 `fail-open` 影响；
  合法 tag 会复用，不再触发 CROSSSLOT。

## 真实场景注意事项

以下限制与搭配约定来自真实部署中最常见的踩坑（部分由故障注入测试固化，见混沌测试套件）：

- **自调用绕过（最高频误用）**：同一个 Bean 内部调用（`this.method()`）不经过 Spring AOP 代理，
  `@Idempotent` / `@DistributedLock` 会**静默失效**。需要防护的方法必须通过代理调用（注入自身代理、
  拆分到另一个 Bean、或使用 `AopContext.currentProxy()`）。
- **与 `@Transactional` 的顺序**：`IdempotentAspect` 为 `@Order(0)`、`LockAspect` 为 `@Order(100)`，
  两者都运行在事务拦截器（默认最低优先级）之外——占位/加锁在事务开始前、结果保存与占位释放/锁释放在
  事务提交之后。同一方法同时使用两种注解时，幂等判定先于锁竞争。这保证"事务回滚后重放已保存结果"的
  窗口不存在；自定义切面如需与它们配合，请先确认优先级关系。
- **Redis Cluster**：慢请求完成哨兵键采用 hash-tag 与主键同 slot，多键 Lua 与 MGET 不会触发 CROSSSLOT。
  业务 key 无花括号时使用完整 key 作为 tag；已有合法非空 tag（如 `{tenant}:order:1`）时复用该 tag。
  空 tag（`foo{}bar`）、不配对花括号（`foo{bar` / `foo}bar`）无法保证同 slot，会在占位时快速失败，
  请移除花括号或使用合法 tag。
- **网络故障行为**：Redis 断连/超时时按 `fail-open` 放行或 fail-close 拒绝（命令超时建议显式配置，
  如 `spring.data.redis.timeout=2s`，避免默认 60s 挂死请求线程）；重启后自动重连自愈。
  REPLAY 等待模式在查询故障时立即短路拒绝，不会以 50ms 间隔风暴式发布降级事件。
- **`waitMillis` 的线程占用**：等待持有当前调用线程（servlet 场景占用请求线程池容量），
  高并发同 key 场景应配置小窗口或使用默认拒绝语义。
- **`store = local` 的边界**：进程内策略无跨进程互斥能力，仅适用于显式确认单实例部署的场景。

## 构建

```bash
./mvnw clean verify        # 全量测试（需本机 Docker，Testcontainers 起 Redis）
./mvnw clean install
```

运行 CI 或发布前请先阅读 [CI_PITFALLS.md](CI_PITFALLS.md)；
项目内提供了 `ci-preflight` skill，会在 CI 相关操作前提醒执行检查清单。
