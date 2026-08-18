---
name: update-docs
description: 维护 OrzMCBackup 的文档（README.md / docs/）。修改实现后同步更新文档，并与最新实现逐项核对，确保 CLI 选项、默认值、版本号、行为描述不过期。当改动涉及 CLI 参数、功能、版本、或发现文档与代码不符时使用。
---

# 文档维护

## 适用范围

- `README.md` — 快速开始、CLI 参考、库用法
- `docs/FEATURES.md` — 功能点全量梳理
- `AGENTS.md` / `CLAUDE.md` / `GEMINI.md` — Agent 协作文档（版本号、命令、结构）
- `docs/*.md` 案例与报告

## 核对清单（对照实现逐项检查）

1. **CLI 选项**：README 中的 backup/merge 选项名、默认值、单位，对照
   `app/src/main/kotlin/com/jokerhub/orzmc/cli/Main.kt` 与 `MergeCommand.kt` 逐一核对。
   - 已知易错：merge `--progress-mode` 默认 `Off`（backup 是 `Region`）；merge `--progress-interval`
     单位是**文件**（backup 是区块）；merge 无 `--progress-interval-ms`
2. **版本号**：工具链版本（Kotlin/Gradle/ktlint/detekt/Shadow/JUnit 等）只以
   `gradle/libs.versions.toml` 为准；README 中引用的版本号需手工同步
3. **产物命名**：CLI JAR 恒为 `backup-<version>.jar`（不存在无版本的 `backup.jar`）；本地默认版本 0.1.0，
   CI 按 tag 注入
4. **JDK 范围**：17-29（构建阻止 30+）；发布工作流用 Temurin 21、lint/coverage 用 JDK 25
5. **行为声明**：功能声明必须与代码一致。已知易夸大项：`RangePattern`（矩形区域）是**库内类，
   未接入优化器管道与 CLI**，文档不得声称 CLI 支持矩形范围
6. **链接**：所有相对链接（`LICENSE`、`docs/`、`CONTRIBUTING.md`、`SECURITY.md`、
   `gradle/libs.versions.toml` 等）必须指向真实存在的文件
7. **配置签名**：库用法示例的构造参数个数/类型必须与 `OptimizerConfig.kt` 一致（如
   `OutputOptions` 为 5 参：`inPlace, zipOutput, force, copyMisc, dryRun`）

## 完成标准

- 文档与 `Main.kt` / `MergeCommand.kt` / 构建文件 / 代码实现完全一致
- 无死链；Markdown 表格与标题渲染正常
- 改动涉及 CLI/配置时，同步更新 `docs/FEATURES.md` 对应小节
