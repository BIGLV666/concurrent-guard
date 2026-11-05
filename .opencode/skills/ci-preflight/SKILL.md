---
name: CI Preflight
description: 在运行 CI、./mvnw verify、GitHub Actions 发布、Maven Central 发布，或排查 CI 失败前，先读取项目根目录 CI_PITFALLS.md 并执行发布前检查清单。
---

# CI Preflight

## 何时使用

- 准备运行 `./mvnw clean verify` 或 `./mvnw clean install`
- 准备 push 代码、打 tag、发布 GitHub Release / Maven Central
- 排查 GitHub Actions 失败
- 修改 `.github/workflows/*.yml`、Testcontainers、Redis Cluster、发布插件或 Maven 版本

## 工作流

1. 读取项目根目录的 `CI_PITFALLS.md`。
2. 按文件中的「发布前检查清单」逐项确认。
3. 如果本次改动涉及 CI workflow、发布配置、Testcontainers/Redis Cluster、Maven 版本，
   先检查「已知问题」是否命中。
4. 如果 CI 失败：
   - 先定位根因，不要直接重跑或删除测试；
   - 把「现象 / 根因 / 修复 / 关联 run」追加到 `CI_PITFALLS.md`；
   - 再重新运行 CI。
5. 发布前确认发布步骤使用 `./mvnw`，不要使用 runner 的系统 `mvn`。

## 约束

- 不要通过删除、跳过或禁用测试来绕过 CI。
- 不要提交 `.classpath`、`.factorypath`、`.project`、`.settings/` 等 IDE 临时文件。
- 本 skill 只负责 CI 前置检查；业务改动仍按 `coding-workflow` 和 `testskill` 执行。
