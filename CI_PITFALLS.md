# CI 问题清单（CI Pitfalls）

> 每次 CI 失败后，把根因和修复方式追加到这里。
> 运行 CI、`./mvnw verify`、GitHub Actions 发布或 Maven Central 发布前，先读本文件。
>
> 最后更新：2026-11-05

## 发布前检查清单

- [ ] `./mvnw clean verify` 本地通过（需要 Docker）。
- [ ] `mvnw` 有可执行权限（`ls -l mvnw`）。
- [ ] 发布版本不是 `-SNAPSHOT`。
- [ ] 发布步骤使用 `./mvnw`，不要使用 runner 的系统 `mvn`。
- [ ] Linux 下 Redis Cluster 测试使用 host 网络；宿主机端口 7100-7102、17100-17102 未被占用。
- [ ] GitHub Actions secrets 已配置：
  `CENTRAL_USERNAME`、`CENTRAL_PASSWORD`、`GPG_PRIVATE_KEY`、`GPG_PASSPHRASE`。
- [ ] 发布 tag 为 `v<version>`（例如 `v0.3.0`），且 tag 指向包含版本号变更的提交。

## 已知问题

### 1. Central 发布失败：Bundle has content that does NOT have a .pom file

**现象**

`Publish signed artifacts` 阶段失败：

```text
Deployment ... failed
common:
 - Bundle has content that does NOT have a .pom file: io/github/biglv666/guard-spring-boot-starter
```

**根因**

GitHub Actions runner 的系统 Maven 升级到 3.10.x 后，
`central-publishing-maven-plugin` 会把 `maven-metadata-local.xml` / `_remote.repositories`
打进 Central bundle，Central 校验因此拒绝。

**修复**

- 发布步骤必须使用项目 wrapper：`./mvnw -B deploy -Prelease ...`；
- 或显式固定 Maven 3.9.16；
- 验证阶段同样使用 `./mvnw`。

**关联**

- 失败 run：`41945824147`、`41946360622`、`41947035059`
- 成功 run：`41947541450`

### 2. Redis Cluster 测试在 CI 连接 127.0.0.1:7100 被拒绝

**现象**

`RedisClusterCrossSlotChaosTest` 失败：

```text
Cannot retrieve cluster partitions from [redis://localhost:7100]
Connection refused: localhost/127.0.0.1:7100
```

**根因**

集群容器使用固定宿主端口映射，在 GitHub Actions 的 Docker 环境下宿主无法访问映射端口；
另外 Redis 节点需要显式绑定 `0.0.0.0`，否则容器内可能只监听回环地址。

**修复**

- Linux（包括 GitHub Actions）使用 `withNetworkMode("host")`；
- macOS/Windows Docker Desktop 保留固定端口绑定方案；
- 三个 Redis 节点启动参数加入 `--bind 0.0.0.0 --protected-mode no`。

**关联**

- 修复提交：`789b126`、`aceaab8`

### 3. `mvnw` 在 Linux runner 上没有可执行权限

**现象**

```text
./mvnw: Permission denied
```

**修复**

```bash
chmod +x mvnw
git update-index --chmod=+x mvnw
```

### 4. 测试日志中的 GuardAnnotationValidator ERROR 不一定是失败

`GuardAnnotationValidatorTest` 中有一个 `fail-fast=false` 用例，会故意输出：

```text
Guard 注解校验失败，共 1 处
```

只要该测试通过，这条 ERROR 是预期日志，不要据此判断 CI 失败。

## 维护约定

- CI 失败后先定位根因，再把“现象 / 根因 / 修复 / 关联 run”追加到本文件。
- 不要通过删除、跳过或禁用测试来绕过 CI；先修根因。
- 修改 `.github/workflows/*.yml`、Testcontainers、Redis Cluster、发布插件或 Maven 版本后，
  重新检查本文件是否需要更新。
