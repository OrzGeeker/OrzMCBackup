# Changelog

## v0.2.3 (2026-08-20)

### Fixed
- **ZIP 打包条目的路径分隔符**：`Compressor.kt` 用系统文件分隔符（Windows 为 `\`）拼条目名，
  违反 ZIP 规范（条目必须用 `/`），生成的 zip 在部分工具/系统上无法正确读取。修复：统一转正斜杠。
  新增 `CompressorTest` 回归测试。
- **`--version` 输出 "unspecified"**：`findProperty("version")` 在未显式 `-Pversion` 时返回
  Gradle 内建的占位值 `"unspecified"`（非 null），使 `?: "0.1.0"` 回退逻辑失效。修复：仅接受非空
  且非 `"unspecified"` 的值，否则回退 `0.1.0`（`build.gradle.kts`）。
- **shadowJar 产物文件名随版本漂移**：`archiveVersion` 默认跟随 `project.version`，产物在
  `backup-0.2.3.jar` 与 `backup.jar` 之间漂移，脚本引用不稳定。修复：固定为空，产物恒为
  `backup.jar`，版本只写入 manifest `Implementation-Version`（`app/build.gradle.kts`）。
- **经典布局维度杂项数据**：非 26.1+ `dimensions/` 布局（如 `DIM-1/`）下的维度数据
  （`data/chunks.dat`、`Fortress_index.dat`、`world_border.dat` 等）此前可能未被正确复制。
  修复 misc 相对路径处理，逐字节保留（`RealWorldPatternTest` 回归实证）。

### Performance
- **六档 InhabitedTime 阈值真实世界基准**（0/60/120/180/240/300s）：18.6GB / 21,688 `.mca` /
  3,162,906 区块的 26.2 恢复合并世界，HDD 实测各档 15–27 分钟；`-t 300` 输出 2.18GB（-88.3%），
  剔除 92.7% 区块，非预期丢失 0。**60 秒为收益拐点**（剔除 91.3%，相当于 t300 的约 98%）；
  阈值几乎不影响备份耗时，只影响体积。完整数据见 `docs/threshold-benchmark-report.md`。

### Testing
- 新增 `CompressorTest`（core）：ZIP 条目正斜杠 + 条目完整性回归。
- 新增 `RealWorldPatternTest`（core）：经典布局 DIM 数据逐字节保留回归。
- CLI 端到端 9 项测试全部通过（dry-run / zip-output / csv / force 语义 / 缺输出参数 / strict+损坏
  region / in-place / 经典布局 DIM 数据保留 / `--version`+`--help`），脚本 `cli_tests.ps1`。

### Docs
- 新增 `docs/threshold-benchmark-report.md`：六档阈值性能与效果基准、逐字节完整性验证
  （0 新文件 / 0 槽位伪造 / 0 锁步违规 / removedChunks 与槽位差严格吻合）、新旧 jar 输出逐字节一致。
- `README.md` / `docs/index.html` / `docs/market-positioning-analysis.md` /
  `docs/real-world-backup-validation.md` 对齐 18.6GB 真实数据（-88.3% / 92.7% / 0 丢失）。
- 站点文档可读性：落地页站内文档链接统一走新增的 `docs/doc.html` 渲染器
  （`doc.html?file=xxx.md`，本地托管 marked + DOMPurify）。修复 `docs/.nojekyll` 下直链 `.md`
  显示原始文本的问题；跨文档链接、GFM 表格、目录导航均可用，`.md` 保持唯一事实源。

## v0.2.0 (2026-08-17)

### Added
- 新增 `merge` 子命令：以 chunk 槽位粒度合并"优化备份 + 更早全量备份"，恢复"全量最新"地图。
  对每个同时存在于 base/patch 的 region 文件逐槽位取源（patch 优先、base 填充、entities/poi 锁步），
  防止同名文件覆盖导致的地图空洞/地形回退。核心实现在 `WorldMerger.kt`，CLI 入口 `MergeCommand.kt`，
  报告序列化 `MergeReportIO.kt`。
- `WorldMerger.run(MergeRequest)` 作为公开库 API 供直接复用；`MergeReportIO.write` 提供无损
  JSON/CSV 落盘（区别于 `Hooks.reportSink` 经 `toOptimizeReport` 的有损映射）。

### Performance
- `copyTree` 阶段跳过 patch 也存在的 `region/entities/poi/*.mca` 的 base→out 冗余复制（overlay
  阶段会用合并结果重写这些文件）；损坏 patch region 时经 `copyBaseIfPresent` 回退复制 base 对应
  文件，保证任何情形下不丢数据。
- `merge` 支持 `--parallelism N` 并行合并 region 文件（默认 1 保持旧行为；各 region 独立写文件，
  输出逐字节确定，已用真实数据与顺序模式交叉验证一致）。真实数据（1,244 个共同 region）实测：
  顺序 20m21s → `--parallelism 4` 16m30s（-19%，瓶颈在同盘 IO 争用）。

### Docs
- 新增 `docs/papermc-map-backup-recovery-case.md`：基于真实 PaperMC 26.2 世界（08-12 全量备份 +
  08-15 优化备份）的槽位级合并典型场景案例，含背景数据、Anvil region 格式与合并算法知识点、
  处理方法、统计对齐/槽位并集复核/跨实现逐字节比对三重验收方法与实测结果。补充目录互斥校验
  （alias guard）与损坏 patch 回退说明、`linkedEntities`/`linkedPoi` 指标。
- 新增 `docs/real-world-backup-validation.md`：基于真实 PaperMC 26.1+ 世界（`E:\test\world`，
  29,046 文件 / 14.3 GB）的备份验证报告，含磁盘占用前后对比、丢失文件分类、两处缺陷与两轮
  代码审查记录，以及 1/2/3/4/5 分钟 InhabitedTime 阈值优化效果对比。
- `README.md` / `docs/FEATURES.md` 全量对齐：新增 merge 库用法示例（Kotlin+Java）、merge 功能
  小节、`--parallelism` 参数说明，修正版本号（Gradle 9.7.0 / Kotlin 2.4.10 / Shadow 9.6.1 /
  JUnit 6.1.3）与覆盖率门槛描述。

### CI
- `test-matrix.yml` 覆盖率任务同时生成并上传 `:app` 模块的 kover 报告，使 CLI 代码纳入 CodeCov
  diff 覆盖率统计（此前仅上传 `:core`，app 新代码在 patch 中一律按 0% 计入）。
- 新增 `:app:koverVerify` 门槛（覆盖率 ≥ 50%），coverage 任务改为 `:core:koverVerify
  :app:koverVerify`。

### Testing
- 新增 `MergeReportIOTest`（core）：覆盖 `toText` 统计/错误列表两种形态、`toOptimizeReport` 映射、
  CSV 写出与父目录自动创建。
- `WorldMergerTest` 新增分支覆盖：目录重叠（patch 嵌套在 out 内）拒绝、损坏 patch region 回退
  base、输出目录不可写、copy/write/finalize 失败容错（FailingFileSystem/注入故障 ioFactory）、
  reader 打开异常跳过、进度边界（Init/Done 恰好一次）、standalone patch entities/poi 忽略、
  并行（`parallelism=2`）与顺序槽位一致。
- 新增 `MainCliMergeTest`（app）：真实临时目录端到端跑 `merge` CLI，验证槽位并集、杂项覆盖、
  `session.lock` 移除、非空输出拒绝、JSON/CSV 报告文件、`--progress-mode Global` 与缺失输入目录
  非零退出码。
- 新增 `MainDispatchTest`（app）：`Main.dispatch` 对 `merge`/backup/无参的退出码分发。
- 新增 `RealMcaMergeTest`（core）+ 提交真实 Anvil 格式夹具对 `Fixtures/merge/`（`tools/gen_merge_fixtures.py`
  生成，README 记录重生成命令）：走生产 `RealFileSystem`+`DefaultMcaIOFactory` 验证槽位合并、
  entities/poi 锁步、base-only region 保留与 level.dat 覆盖。

### Fixed
- 修复 `--copy-misc` 在 picocli 4.7 `negatable = true` 下开关反转：裸写 `--copy-misc` 被解析为
  `false`（杂项文件全部不备份），`--no-copy-misc` 反而生效。增加 `fallbackValue = "true"` 修正。
- 修复 `copyMiscFiles`/`countMiscFiles` 整棵跳过 `region/entities/poi` 子树导致其中非 `.mca` 文件
  （如 `r.0.2.mca.bak`、`r.0.-4.mca.<id>.backup`）被静默丢弃的问题：改为仅跳过会被维度处理器
  重写的顶层 `.mca`（`rel.nameCount == 2`），`.bak`/`.backup` 等照常复制。
- 重构杂项跳过逻辑为共享辅助函数 `miscRel`/`skipMatchers`，修复 `countMiscFiles` 与
  `copyMiscFiles` 对 `session.lock` 计数不一致导致的进度总数虚高；移除 `copyMiscFiles` 未使用的
  `miscTotal` 参数。
- 新增 `RealWorldPatternTest`（MemoryFS）：region/entities/poi 内非 `.mca` 保留、零字节 `level*.dat`
  保留、根级/维度级 `death-chests.yml` 保留、全剔除 region 文件消失（惰性写入器）。
- `MainCliCopyMiscTest` 新增裸 `--copy-misc` 启用杂项复制回归测试。
- 磁盘夹具 `Fixtures/world-26-1` 扩展（`region/r.0.0.mca.bak`、零字节 `level<数字>.dat`、
  `death-chests.yml`），`FixtureCompatibilityTest` 新增对应存在性断言。

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
