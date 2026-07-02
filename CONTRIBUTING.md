# Contributing to OrzMCBackup

感谢您对 OrzMCBackup 的关注！欢迎通过 Issue 和 PR 贡献代码。

## 开发流程

1. **Fork 本仓库** 并创建你的特性分支
2. **本地开发**：参考 [CLAUDE.md](CLAUDE.md) 中的常用命令
3. **提交 PR** 前确保：
   - `./gradlew ktlintCheck --no-daemon` 通过（代码风格）
   - `./gradlew detekt --no-daemon` 通过（静态分析）
   - `./gradlew :core:test :app:test --no-daemon` 通过（全部测试）
   - `./gradlew :core:koverVerify --no-daemon` 通过（覆盖率门禁 ≥ 75%）

## 代码风格

- 遵循 [ktlint](https://github.com/pinterest/ktlint) 标准规则（已在 `.editorconfig` 中配置）
- Kotlin 使用标准命名约定（camelCase 函数/变量，SCREAMING_SNAKE_CASE 常量）
- 4 空格缩进，120 字符行宽上限
- **允许**在 CLI 入口（Main.kt）使用 wildcard import（`com.jokerhub.orzmc.world.*`、`picocli.CommandLine.*`）
- 版本号（如 Minecraft 26.1）导致的下划线命名：使用 `@Suppress("ktlint:standard:function-naming")` 注解

## 测试要求

- 新功能应包含单元测试
- 使用 `MemoryFS` + `MemoryMcaIOFactory` 实现快速内存测试（无需磁盘 I/O）
- 测试夹具位于 `core/src/testFixtures/`，可供两个模块共享
- 真实 MCA 夹具文件在 `core/src/test/resources/Fixtures/`
- 当前行覆盖率目标：≥ 75%

## PR 规范

- 使用 [Conventional Commits](https://www.conventionalcommits.org/) 格式：
  - `feat:` 新功能
  - `fix:` 修复
  - `chore:` 杂项（依赖更新、重构等）
  - `ci:` CI/CD 变更
  - `docs:` 文档
  - `build:` 构建系统
- PR 标题简洁描述变更内容
- 关联相关 Issue（如有）

## 构建要求

- JDK 17+（推荐 JDK 25，与 CI lint/coverage 一致）
- Dependabot 自动管理依赖更新；手动更新请确保 Version Catalog (`gradle/libs.versions.toml`) 同步更新
