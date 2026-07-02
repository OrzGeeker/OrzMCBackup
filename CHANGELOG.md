# Changelog

## v0.1.5 (2026-07-02)

### Changed
- CI lint/coverage 升级至 JDK 25，本地与 CI 保持一致
- detekt 升级至 `2.0.0-alpha.3`（插件 ID 变更为 `dev.detekt`），完全支持 JDK 25
- ktlint 升级至 `14.2.0`
- Gradle Wrapper 升级至 `9.6.1`
- Shadow 升级至 `9.4.3`
- JUnit 升级至 `6.1.1`
- CI Actions：checkout 升级至 v7，codecov-action 升级至 v7

### Fixed
- 修复 ktlint 14.2.0 新增的代码风格检查（链式调用换行、参数格式等）
- 修复 detekt 2.x 配置属性名称变更（threshold → allowedLines/allowedComplexity 等）
- 移除 detekt JDK ≤ 21 的 skip 逻辑，detekt 2.x 原生支持 JDK 25
- 修复当世界目录直接作为输入时（如 `~/Downloads/world`），`discoverMiscParents` 遗漏 input 自身导致
  world 级别杂项文件（`level.dat`、`data/`、`datapacks/` 等）未被复制的问题

### Testing
- 新增 `Paper26StructureTest` 3 个回归测试：深层嵌套中间目录、world 直接输入 + copyMisc=false、
  空 dimensions 目录的杂项文件保留

## v0.1.0 (2025-06-17)

### Features
- 双用途架构：CLI 工具 + 发布到 Maven Central 的库
- Minecraft Java 世界优化引擎，支持 InhabitedTime 阈值、强制加载列表、矩形区域过滤
- 全面支持 Minecraft 26.1+ 格式（dimensions/ 目录结构）
- 完整的 MCA 文件读写（Region/Entities/POI）
- NBT force-load 解析器，支持新旧两种格式（chunk_tickets.dat / chunks.dat）
- ZIP 压缩输出、原地替换、Dry-run 预览模式
- 多维度并行与区域级并行处理

### Dependencies
- Kotlin `2.4.0`, Gradle `9.5.1`, Shadow `9.4.2`, Kover `0.9.8`
- JUnit `6.1.0`, Picocli `4.7.7`, Dokka `2.2.0`
- kotlinx-coroutines-core `1.11.0`, lz4-java `1.8.0`

### Testing
- MemoryFS + MemoryMcaIOFactory 实现纯内存 E2E 测试
- 真实 MCA 夹具覆盖三种 Minecraft 格式
- CI 矩阵：3 OS (Ubuntu/Windows/macOS) × 3 JDK (17/21/25)
- Kover 覆盖率门禁：line coverage ≥ 75%

### CI/CD
- `test-matrix.yml` — 三 OS × 三 JDK 测试 + 覆盖率报告
- `release-app.yml` — CLI shadow JAR 发布到 GitHub Release
- `release-lib.yml` — 库发布到 Maven Central Portal
- Dependabot 自动管理 Gradle 和 Actions 依赖更新

### Quality
- 新增：ktlint + detekt 静态分析
- 新增：.editorconfig 统一编码风格
- 新增：Kover 覆盖率门禁
- 新增：Version Catalog 统一依赖版本管理
