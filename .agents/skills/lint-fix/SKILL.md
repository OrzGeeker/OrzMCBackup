---
name: lint-fix
description: 运行 OrzMCBackup 的代码风格与静态分析检查（ktlint / detekt），并自动修复可修复项。当代码改动后需要过 CI 质量门槛、或 ktlintCheck/detekt 报错时使用。
---

# ktlint / detekt 检查与修复

## 自动修复风格问题（ktlint）

```bash
./gradlew ktlintFormat --no-daemon
```

## 检查

```bash
./gradlew ktlintCheck detekt --no-daemon
```

## 单模块快速检查（改动集中在某模块时）

```bash
./gradlew :core:ktlintCheck :core:detekt --no-daemon
./gradlew :app:ktlintCheck :app:detekt --no-daemon
```

## 常见规则要点

- 链式调用换行、参数格式（ktlint 14.2.0 风格）
- detekt 2.x 规则阈值配置在根 `detekt.yml`（`allowedLines`/`allowedComplexity` 等）
- `.editorconfig` 统一缩进/换行；行尾空白与 EOF 由 pre-commit 检查（`.pre-commit-config.yaml`）
- 若 detekt 误报，优先重构代码，而非随意 `@Suppress`（个别跳转/抛错多的函数已加 Suppress 注解）

## 完成标准

`ktlintCheck detekt` 全绿；`ktlintFormat` 只格式化，不改变语义。
