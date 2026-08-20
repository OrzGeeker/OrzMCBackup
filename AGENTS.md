# OrzMCBackup — Agent 协作指南

> 本文是**跨 Agent 工具的单一事实源**（AGENTS.md 通用标准）：Claude Code、Codex、pi.dev、Grok、
> Gemini CLI、Cursor、Aider 等均会读取本文件。请先阅读本文再开始任何改动。
>
> 各工具的薄壳入口：`CLAUDE.md`（Claude Code / Grok / pi.dev）、`GEMINI.md`（Gemini CLI）均引用本文件。
> 项目可移植技能位于 `.agents/skills/`（Agent Skills Standard，跨工具可用）。

## 项目概览

Kotlin/Gradle 多模块工程，用于**优化 Minecraft Java 世界**：扫描各维度 region/entities/poi 的 MCA 文件，
根据 InhabitedTime 阈值、强制加载列表保留区块并重写输出，另提供「优化备份 + 旧全量备份」的**槽位级合并恢复**
（`merge` 子命令）。双用途：CLI 工具（picocli Shadow JAR）+ 库（Maven Central `io.github.wangzhizhou:backup-core`）。

核心价值：真实 PaperMC 世界（18.6GB / 21,688 个 `.mca` / 3,162,906 区块）优化后降至 **2.18GB
（-88.3%**，剔除 92.7% 区块），且零非预期数据丢失（六档阈值逐字节验证）。

## 技术栈与约束

- Kotlin `2.4.10`，Gradle `9.7.0`（Wrapper），JDK **17-29**（构建脚本显式阻止 JDK 30+）
- 依赖：`org.lz4:lz4-java` 1.8.0、`kotlinx-coroutines-core` 1.11.0、`info.picocli` 4.7.7（仅 app）
- 质量工具：ktlint `14.2.0`、detekt `2.0.0-alpha.6`、Kover `0.9.9`、JUnit `6.1.3`、Dokka `2.2.0`
- 所有版本/group 由根 `build.gradle.kts` + `gradle/libs.versions.toml` 统一注入；产物目标 Java 17
- 版本目录（`gradle/libs.versions.toml`）是版本号唯一事实源——改版本只改这里

## 常用命令

```bash
# 构建 CLI fat JAR（产物 app/build/libs/backup.jar，文件名固定；版本写 manifest，--version 可查）
./gradlew :app:shadowJar --no-daemon

# 运行代码风格和静态分析检查
./gradlew ktlintCheck detekt --no-daemon

# 运行所有测试
./gradlew :core:test :app:test --no-daemon

# 运行单个测试类 / 方法
./gradlew :core:test --tests "com.jokerhub.orzmc.MemoryE2ETest" --no-daemon
./gradlew :core:test --tests "com.jokerhub.orzmc.MemoryE2ETest.end-to-end optimize with MemoryFS and MemoryMcaIOFactory" --no-daemon

# 生成覆盖率报告 / 门槛验证
./gradlew :core:koverXmlReport --no-daemon
./gradlew :core:koverVerify :app:koverVerify --no-daemon

# 发布库到本地 Maven / 生成 Maven Central 上传 bundle
./gradlew :core:publishToMavenLocal --no-daemon
./gradlew :core:portalBundle --no-daemon -Pversion=X.Y.Z \
  -Psigning.keyId=<KEY_ID> -Psigning.password=<PASSWORD> -Psigning.key=<KEY_BASE64>

# 打印测试资源路径（调试 CI 夹具问题）
./gradlew :core:printTestPaths --no-daemon
```

## 模块与包结构

两个模块：`core`（库）和 `app`（CLI 入口）。`app` **依赖 `core`**；`core` 的公共 API 变更会影响 `app`。

- **`core/src/main/kotlin/com/jokerhub/orzmc/world/`** — 优化管道引擎
  - `Optimizer` / `DefaultOptimizer` / `OptimizerEngine` — 管道编排器
  - `DimensionProcessor` — 按维度循环处理 region .mca
  - `OptimizerConfig.kt` — 全部配置数据类（`OptimizerRequest` 等）+ Builder 模式
  - `FileSystem`（`RealFileSystem`/`MemoryFS`）、`McaIOFactory`（`DefaultMcaIOFactory`/`MemoryMcaIOFactory`）
  - `ForceLoad` / `NbtForceLoader` — 强制加载解析，按优先级探测 `chunk_tickets.dat`（26.1+）→ `chunks.dat`（旧版）
  - `Compressor`（ZIP）、`Cleaner`（含 Windows DOS 属性）、`ProgressSink`、`ReportSink`/`ReportIO`/`OptimizeReport`、
    `MetricsSink`、`LoggerSink`、`Errors.kt`
  - `WorldMerger` / `MergeReport` / `MergeReportIO` — merge 槽位级合并（v0.2.0）
- **`core/src/main/kotlin/com/jokerhub/orzmc/mca/`** — Anvil 区域文件格式
  - `McaReader`（8KiB 头部解析）、`McaWriter`（扇区对齐写入）、`McaEntry`（解压：RAW/ZLIB/GZIP/LZ4 + xxhash 校验）、
    `RandomAccess`（`RafAccess`/`BufferedRafAccess`/`MemoryAccess`）
- **`core/src/main/kotlin/com/jokerhub/orzmc/patterns/`** — 区块保留策略
  - `InhabitedTimePattern`（字节级扫描 `InhabitedTime` long，`>` 语义，`threshold=0` 移除未曾访问区块）、
    `ListPattern`（坐标保留列表，用于强制加载）、`RangePattern`（矩形区域；**库 API，尚未接入优化器管道与 CLI**）
- **`app/src/main/kotlin/com/jokerhub/orzmc/cli/`** — `Main.kt`（backup 命令 + 子命令分发）、`MergeCommand.kt`

## 关键设计决策

1. **抽象可测试性**：`FileSystem` + `McaIOFactory` 双抽象，全管道可在 `MemoryFS` 内存中运行；
   `McaMemoryBuilder`（testFixtures）在内存构建合成 MCA。
2. **错误容错**：非致命错误收集在 `OptimizeReport.errors`，`strict` 模式升级为退出码 1。
3. **进度报告**：两种限流——按区块数（`progressInterval`）或按时间（`progressIntervalMs`，优先）。
4. **可扩展性**：`parallelism` 驱动 region 层并行（每个任务一个 `.mca`）；维度按序处理，避免并行度平方
   （A5 后不再有维度级并行）。
5. **快速 InhabitedTime 检查**：字节级扫描 NBT 标签，不完整反序列化。
6. **惰性 MCA 写入**：仅当至少保留一个区块才创建输出 writer，避免空 MCA 文件。
7. **26.1+ 兼容**：ForceLoad 探测 `chunk_tickets.dat` → `chunks.dat`；维度发现递归支持 `dimensions/` 嵌套；
   `discoverMiscParents` 自动发现 world 级杂项源，确保 `level.dat`/`players/`/`data/` 等在直接传入世界目录时也正确复制。
8. **merge 数据安全**：patch 优先、base 填空、entities/poi 锁步；三目录互斥校验（alias guard，`OverlapGuard`）；损坏 patch 回退 base。
9. **输入/输出重叠守卫（`OverlapGuard`）**：`Optimizer.run` 与 `WorldMerger.run` 共用的重叠校验单一事实源，
   在 `--force` 覆盖前拒绝 input==output/嵌套/祖先/符号链接别名，防止不可逆清空源世界；RealFileSystem 走
   `toRealPath`（解析别名），MemoryFS 走词法 normalize。
10. **解压炸弹防护**：`McaEntry.allDataUncompressed` 对解压总量设 64MB 硬上限（ZLIB/GZIP 有界读取；
    LZ4 分配前校验声明长度 + 累计校验），超限走安全保留路径（原字节透传 + Pattern 错误），绝不丢弃。

## 编码规范与质量门槛

- 代码风格：ktlint（含 `--format` 自动修复）+ `.editorconfig` 统一；detekt（根 `detekt.yml`）静态分析
- 覆盖率门槛：core **≥75%**、app **≥50%**（`koverVerify` 已绑定 `check`，`./gradlew check` 不达标即失败，CI 强制）
- CI 矩阵：JDK 17/21/25 × Ubuntu/macOS/Windows（`test-matrix.yml`）；lint/coverage 用 JDK 25；
  发布工作流（`release-lib.yml` / `release-app.yml`）用 **Temurin 21**，产物目标 Java 17
- 提交前请确保 `./gradlew ktlintCheck detekt --no-daemon` 通过

## 测试模式

- 端到端测试用 `MemoryFS` + `MemoryMcaIOFactory`，快速且无需磁盘
- `McaMemoryBuilder`（testFixtures）编程构建合成 MCA 数据
- 真实 MCA 夹具在 `core/src/test/resources/Fixtures/`（含 `Fixtures/merge/` 合并夹具对，可
  `python tools/gen_merge_fixtures.py` 重新生成）；`TestPaths` 定位磁盘夹具
- 关键测试类：`Paper26StructureTest`（18 测试，26.1+ 目录结构）、`FixtureCompatibilityTest`、
  `WorldMergerTest`、`RealMcaMergeTest`（真实 Anvil 夹具全链路）、`MainCliMergeTest`、`MainDispatchTest`、
  `DecompressionBombTest`（解压炸弹防护）、`MainCliE2ETest`（CLI 端到端 12 项）、`OptimizerInputValidationTest`（含重叠守卫）
- 文件系统/IO 可注入故障：`FailingFileSystem` 模拟 I/O 错误

## 文档导航与同步

用户文档（中文）：
- `README.md` — 快速开始、CLI 参考、库用法（**改代码后必须同步**）
- `docs/FEATURES.md` — 功能点全量梳理（v0.2.3）
- `docs/threshold-benchmark-report.md` — 六档阈值性能与效果基准、逐字节完整性验证
- `docs/papermc-map-backup-recovery-case.md` — merge 真实案例与算法
- `docs/real-world-backup-validation.md` — 真实世界备份验证报告
- `docs/paper-26.1-world-migration-report.md`、`docs/world-directory-structure-comparison.md` — 格式迁移研究
- `docs/market-positioning-analysis.md` — 市场定位与产品化分析
- `docs/architecture-review.md` — 架构 + 测试 + CI 综合审查报告（P0–P3 路线图、A1–A20/T1–T13/C1–C23 审计项与修复状态跟踪）

**维护铁律**：修改实现后，必须同步检查 `README.md` 与 `docs/FEATURES.md` 是否过期（CLI 选项、默认值、
版本号、行为描述）。版本号只改 `gradle/libs.versions.toml`，README/AGENTS.md 里引用的工具链版本需手工核对。

**站点渲染**：GitHub Pages 由 `docs/.nojekyll` 关闭 Jekyll，`.md` 直接访问会显示**原始文本**。
落地页 `docs/index.html` 的站内文档链接必须统一为 `doc.html?file=xxx.md`（如
`doc.html?file=threshold-benchmark-report.md`），由 `docs/doc.html`（本地托管的 marked+DOMPurify，
`docs/vendor/` 为固定版本）客户端渲染。`.md` 仍是唯一事实源；`docs/vendor/` 勿删，新增文档只需在
落地页按上述格式加链接。

## 协作规则（多 Agent / 多工具并行）

- **模块边界**：`core` 与 `app` 相对独立。`core` 的公共 API（`OptimizerRequest`/`OutputOptions` 等）变更期间，
  避免 `app` 侧并行改动；其余场景可并行。
- **并行安全命令**：`./gradlew :core:test ...` / `:app:test ...`（不同模块、不同测试类）、grep/只读探索可并行。
- **串行命令**：`:app:shadowJar`、`:core:publishToMavenLocal`、`:core:portalBundle`、`koverVerify` 全量、
  `git` 写操作——一次只由一个 Agent 执行。
- **同一文件并行编辑**：使用 `git worktree` 隔离，改完由主流程合并，避免互相覆盖。
- **分支纪律**：默认分支 `main`；功能改动先建分支/PR，CI 绿后合并。
- **文档与实现一致性**：任何改 README/docs 的 Agent 必须对照 `Main.kt`/`MergeCommand.kt`/构建文件逐项核对。
