# Changelog

## Unreleased

### Added
- 新增 `merge` 子命令：以 chunk 槽位粒度合并"优化备份 + 更早全量备份"，恢复"全量最新"地图。
  对每个同时存在于 base/patch 的 region 文件逐槽位取源（patch 优先、base 填充、entities/poi 锁步），
  防止同名文件覆盖导致的地图空洞/地形回退。核心实现在 `WorldMerger.kt`，CLI 入口 `MergeCommand.kt`，
  报告序列化 `MergeReportIO.kt`。

### Docs
- 新增 `docs/papermc-map-backup-recovery-case.md`：基于真实 PaperMC 26.2 世界（08-12 全量备份 +
  08-15 优化备份）的槽位级合并典型场景案例，含背景数据、Anvil region 格式与合并算法知识点、
  处理方法、统计对齐/槽位并集复核/跨实现逐字节比对三重验收方法与实测结果。

### CI
- `test-matrix.yml` 覆盖率任务同时生成并上传 `:app` 模块的 kover 报告，使 CLI 代码纳入 CodeCov
  diff 覆盖率统计（此前仅上传 `:core`，app 新代码在 patch 中一律按 0% 计入）。

### Testing
- 新增 `MergeReportIOTest`（core）：覆盖 `toText` 统计/错误列表两种形态与 `toOptimizeReport` 映射。
- `WorldMergerTest` 新增分支覆盖：输入非目录、非空输出无 `--force` 拒绝、`--force` 清空重跑、
  patch-only region 复制 entities/poi 兄弟、陈旧 entities 文件删除、`reportSink` 回调。
- 新增 `MainCliMergeTest`（app）：真实临时目录端到端跑 `merge` CLI，验证槽位并集、杂项覆盖、
  `session.lock` 移除与非空输出拒绝。

### Fixed
- 修复 `--copy-misc` 在 picocli 4.7 `negatable = true` 下开关反转：裸写 `--copy-misc` 被解析为
  `false`（杂项文件全部不备份），`--no-copy-misc` 反而生效。增加 `fallbackValue = "true"` 修正。
- 修复 `copyMiscFiles`/`countMiscFiles` 整棵跳过 `region/entities/poi` 子树导致其中非 `.mca` 文件
  （如 `r.0.2.mca.bak`、`r.0.-4.mca.<id>.backup`）被静默丢弃的问题：改为仅跳过会被维度处理器
  重写的顶层 `.mca`（`rel.nameCount == 2`），`.bak`/`.backup` 等照常复制。
- 重构杂项跳过逻辑为共享辅助函数 `miscRel`/`skipMatchers`，修复 `countMiscFiles` 与
  `copyMiscFiles` 对 `session.lock` 计数不一致导致的进度总数虚高；移除 `copyMiscFiles` 未使用的
  `miscTotal` 参数。

### Testing
- 新增 `RealWorldPatternTest`（MemoryFS）：region/entities/poi 内非 `.mca` 保留、零字节 `level*.dat`
  保留、根级/维度级 `death-chests.yml` 保留、全剔除 region 文件消失（惰性写入器）。
- `MainCliCopyMiscTest` 新增裸 `--copy-misc` 启用杂项复制回归测试。
- 磁盘夹具 `Fixtures/world-26-1` 扩展（`region/r.0.0.mca.bak`、零字节 `level<数字>.dat`、
  `death-chests.yml`），`FixtureCompatibilityTest` 新增对应存在性断言。

### Docs
- 新增 `docs/real-world-backup-validation.md`：基于真实 PaperMC 26.1+ 世界（`E:\test\world`，
  29,046 文件 / 14.3 GB）的备份验证报告，含磁盘占用前后对比、丢失文件分类、两处缺陷与两轮
  代码审查记录，以及 1/2/3/4/5 分钟 InhabitedTime 阈值优化效果对比。

## v0.1.6 (2026-07-04)

### Fixed
- 修复 Windows 上 `session.lock` 被服务端进程锁定导致 `CopyMisc` 复制失败报 WARN 的问题
  - `skipGlobs` 支持 glob 通配符（如 `session.lock`、`*.lock`），默认跳过 `session.lock`
  - 避免运行时锁文件在备份中产生无意义的错误日志

### Testing
- 新增 glob 模式匹配测试：验证 `session.lock` 被跳过，其他 `.lock` 文件仍正常复制
- 更新现有测试断言以反映 `session.lock` 不再被复制

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
