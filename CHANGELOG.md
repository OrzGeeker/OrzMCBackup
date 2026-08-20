# Changelog

## Unreleased

## v0.3.0 (2026-08-20)

### Performance (A9/A11/A12/A13/A15)
- **BufferedRafAccess 绝对位置（A9）**：`pos` 改为绝对文件位置，大块读直接旁路缓冲区
  （`delegate.seek+readFully`），消除「整块读 + 尾部再读」的双次 seek 模式。新增 `RandomAccessTest`
  6 项（大读旁路 / 连续大读 / 大读后小读 / 旁路后重定位 / 越界 EOF / 混合计划）。
- **零分配 chunk 计数（A11）**：`McaReader.count()` / `McaIOFactory.count()` 直接扫 offset/size 表，
  不再为计数分配 `McaEntry` 对象；`McaUtils.countTotalChunks` 改用之。
- **misc 文件集合过滤（A12）**：`WorldMerger` misc 收集用 `Set` 判定 region 成员，消除 O(n²) contains。
- **padding 零缓冲（A13）**：`McaWriter` 复用预清零 `ByteArray(4096)` 写 sector padding，不再每次分配。
- **region 2 GiB 溢出守卫（A15）**：Anvil 偏移表为 3 字节，无法寻址 >2GiB；超限抛
  `IllegalStateException`，避免 offset 溢出写坏 header。
- **MemoryFS 路径组件匹配（A18）**：`list`/`walk` 由字符串前缀匹配改为 `Path` 组件级匹配，修复
  Windows `\` 分隔下「/mem/world` 误含 `/mem/world2`」的真实 bug。新增 `MemoryFSTest` 3 项。

### Robustness (A6/A16/A17/A19/A20)
- **错误处理模型统一（A6）**：in-place 替换失败由抛 `InPlaceReplacementException` 冒泡出 `run()`
  改为记录 InPlace 错误进 `OptimizeReport.errors`，与其余错误路径（resolveOutputDir / copyMiscFiles /
  handleZipOutput）的「收集不抛」模型一致；`createDirectories` 失败跳过该子树替换，输入世界不受影响；
  删除 `InPlaceReplacementException` 异常类。`--strict` 下错误升级为退出码 1 的语义不变。
- **ForceLoad FileSystem 感知（A16）**：`ForceLoad.parse(fs, dim, strict)` 直接经 `FileSystem` 读取
  force-load 文件（`Optimizer` 的 MemoryFS 路径不再落盘）；`NbtForceLoader` 新增 `parse(bytes)` 字节重载。
- **copy 不跟随符号链接（A17）**：`RealFileSystem.copy` 加 `NOFOLLOW_LINKS`，链接按链接复制而非目标。
- **Cleaner 统一删除（A19）**：`RealFileSystem.deleteTreeWithRetry` 委托 `Cleaner`；`clearDosAttributes`
  补 `setSystem(false)`（清理 Windows system 属性）。
- **错误消息英文化（A20）**：`Optimizer` CopyMisc 阶段的中文错误消息改为英文，报告/日志统一英文。

### Architecture (A7/A8/A10)
- **可选 fsync（A10）**：`IOOptions.syncOnFinalize`（默认 true）+ CLI `--no-fsync` 选项；关闭时跳过
  region 写完成的 `fd.sync()`，换取更快但稍弱的持久性保证。
- **sink 强类型化（A7）**：`OptimizerRequestBuilder.progressSink`/`reportSink` 由 `Any?` 运行时类型分发
  改为强类型属性（`ProgressSink`/`ReportSink?`）；Path/String 便捷转换保留为同名函数重载。
- **DimensionContext 生命周期拆分（A8）**：progress 生命周期抽为 `ProgressTracker`（total/interval/
  emit/processed 计数器聚合），`DimensionContext` 字段 17→12，进度状态内聚可测。

### Testing (T10-T13)
- **Cleaner 直接单测（T10）**：`CleanerTest` 4 项——普通文件 no-op / Windows 只读属性清理（环境门控）
  / 只读文件树删除 / 缺根路径返回 false。
- **RangePattern 处置（T11）**：确认生产死代码（仅测试引用），删除 `RangePattern` 及 4 个测试。
- **夹具缺失不再静默跳过（T12）**：`McaReaderTest`/`InhabitedThresholdTest` 由 `assumeTrue` 改 `assertTrue`，
  夹具缺失即失败，与其它夹具测试行为一致。
- **NBT 深度边界（T13）**：`NbtForceLoaderTest` 增深度超限拒绝（`maxCompoundDepth` 生效）+ 边界内正常解析。

### CI (C5/C6/C7/C8/C12/C14/C15/C16/C18/C19/C20/C21/C22)
- **缓存去重（C6）**：移除 setup-java `cache: gradle`，Gradle 依赖缓存统一由 setup-gradle 承担。
- **命令合并（C7）**：lint 两步并为 `ktlintCheck detekt`；coverage 生成 + 验证并为一条命令，省一次 JVM 启停。
- **detekt 版本（C8）**：2.0 正式版未发布（最新 alpha.3 < 当前 alpha.6），保持 `2.0.0-alpha.6`；
  detekt.yml 为 2.0 专属配置，version catalog 钉死版本，正式版发布后升级。
- **sha256 自证去除（C12）**：release-lib 移除「生成后立即本地反验」的同义反复步骤，保留产物与
  `.asc` 签名存在性检查；真实校验应在 Portal 返回后回下载。
- **ci-retry 流式（C16）**：输出 `tee` 到临时文件流式打印，长任务日志边跑边出，不再全量缓存到变量。
- **权限最小化（C19）**：release-lib 补 `permissions: contents: read`（test-matrix 已有）。
- **版本统一（C20）**：三工作流 setup-gradle 统一 `@v6.2.0`。
- **.gitattributes（C22）**：新增 `*.sh text eol=lf` 等换行约束，避免 Windows 检出 CRLF 破坏脚本。
- **构建缓存（C5）**：`settings.gradle.kts` 显式 `buildCache { local }`；本地缓存目录
  `~/.gradle/caches/build-cache-1` 由 setup-gradle 的 GitHub Actions 缓存持久化，实现跨 job / 跨 PR 编译复用。
- **ci-retry 正则补全（C14）**：重试判定追加 `50[234]`、`Bad Gateway`、`Gateway Timeout`、`codeload`
  等网关层特征（冷缓存并发时 GitHub codeload 拉取也可能 5xx）。
- **ci-retry 指数退避（C15）**：固定 15s 改为 `base * 2^(attempt-1) * jitter(0.5~1.5)`，默认重试 5 次，
  冷缓存并发重试不再同拍集中打爆限流窗口。
- **pre-commit ktlint 固定（C18）**：`.pre-commit-config.yaml` 用 `additional_dependencies` 钉死
  `com.pinterest.ktlint:ktlint-cli:14.2.0`，杜绝 hook 与项目版本漂移。
- **Dependabot grouping（C21）**：`dependabot.yml` 按领域聚合 gradle 依赖更新（quality-tooling /
  kotlin-and-coroutines / runtime-and-test）+ actions 单组，避免 10 个独立 PR × 全矩阵 churn。

### Dependencies (D1)
- **lz4-java 坐标迁移**：`org.lz4:lz4-java` 项目已官方重定位到 `at.yawk.lz4:lz4-java`（原坐标真实版本
  止于 `1.8.0`，`1.8.1` 仅为 relocation 占位符 POM，无真实 jar）。Gradle 对 Maven relocation 的
  capability 冲突处理会把新旧两模块同时放入依赖图，导致解析失败并连带 config-cache 序列化报错
  （`__classpathSnapshot__` 无法写入）。迁移至 `at.yawk.lz4:lz4-java:1.11.2`——包结构
  `net.jpountz.lz4` / `net.jpountz.xxhash` 与 `Automatic-Module-Name: org.lz4.java` 均不变，
  纯 catalog 改动、无代码变更；`:core:test :app:test` 全绿，config-cache 恢复可存储。

### Security (A2)
- **解压炸弹防护**：`McaEntry.allDataUncompressed` 对解压后数据总量设硬上限
  `MAX_UNCOMPRESSED_CHUNK_LENGTH`（64MB，远高于任何合法 chunk payload < 1MB）。此前压缩长度上限
  8MB 只约束压缩态，一个极小的高压缩比 payload（如全零）可膨胀到数 GB 直接 OOM。修复：ZLIB/GZIP
  走有界读取（`readBounded`），LZ4 在分配目标缓冲区**之前**校验声明长度（防损坏表头 OOM），并对累计
  解压量累加校验。超限 chunk 走既有安全保留路径（原字节透传 + Pattern 错误上报），绝不丢弃。
  新增 `DecompressionBombTest`（core，3 项：ZIP 炸弹抛错 / LZ4 声明超限先于分配抛错 / 炸弹端到端安全保留）。

### Fixed (A1)
- **输入/输出目录重叠守卫**：抽取 `OverlapGuard`（统一复用 `WorldMerger.overlaps` 逻辑，单一事实源），
  `Optimizer.run` 在任何写入前校验 input 与 output 相同 / 嵌套 / 祖先 / 符号链接别名，
  `--force` 覆盖前即拒绝（此前 `input == output` 且带 `--force` 会先清空输入再处理，风险不可逆）。
  in-place 与 dry-run 使用全新临时目录，天然不重叠。新增 `OptimizerInputValidationTest` 4 项
  （相等 / 嵌套 / 祖先拒绝 + 独立目录带 force 正常）。

### Testing (T1)
- 新增 `MainCliE2ETest`（app，in-JVM 端到端）：把此前仅有未提交 `cli_tests.ps1` 脚本守护的 CLI 语义
  固化为 12 项 JUnit 测试——dry-run 不写输出 / zip-output 产出时间戳 zip 并删除输出目录 / CSV 报告头
  / 未知报告格式静默回退 JSON / 非空输出无 `--force` 拒绝（`--strict` 退出 1、输出原样）/ 缺输出参数
  `--strict` 退出 1 / in-place 保留+剔除 / 经典布局 DIM 数据逐文件保留 / merge `base==output` 别名拒绝
  / 损坏 patch region 回退 base 槽位 / 非法 `--progress-mode` 解析失败。

### Performance (A5)
- **并行度不再平方**：此前维度层（`processInParallel`）与 region 层并行同时开启时，线程数为
  `parallelism²`，同一磁盘上无吞吐增益反而可能劣化。修复：维度按序处理，并行度只在 region 层生效
  （热点路径，每个任务一个 `.mca`），`--parallelism` 并行路径仍完整覆盖。

### Fixed (A3/A4/T4)
- **并行错误收集线程安全（A3）**：`DefaultOptimizer.run` 的 `errors` 由普通 `ArrayList` 改为
  `CopyOnWriteArrayList`——`record()` 会从 region 并行 worker 线程并发调用，此前可能丢条目或抛
  `ConcurrentModificationException`。
- **MetricsSink 双重计数（A4）**：移除按维度累加的 `incProcessed/incRemoved`，指标只在 `run()`
  以最终报告总数上报一次；此前多维度并行时聚合统计重复计数。
- **单元素相对路径的 zip 归档自包含（T4）**：`Compressor.compressToTimestampZip` 的
  `root.parent ?: root` 在 `root` 为单元素相对路径（parent 为 null）时回退到 root 本身，归档会写入
  被压缩树内部，`Files.walk` 遂把归档自身纳入。修复：取 `root.toAbsolutePath().parent`，
  保证归档始终落在被遍历树之外。

### Testing (T2–T9)
- 新增 `ExternalCompressionTest`（core，T2）：外部压缩 chunk（EXT_GZIP/EXT_ZLIB/EXT_RAW/EXT_LZ4，
  即 `.mcc` 外存标记字节 -127..-124）的 `isExternal()` 数据安全分支——`--remove-unknown` 剔除 /
  默认保留，端到端验证标记字节在重写后存活（不会误删外部存储 chunk）。
- 新增 `MainCliProgressModeTest`（app，T3）：通过注入 `LoggerSink` 断言 Off 零进度输出、Global
  输出 `进度：X%（n/total）`、Region 逐文件行、`--progress-interval-ms` 时间节流触发。
- 新增 `OptimizerInPlaceFailureTest`（core，T8）：in-place 替换失败契约——copy 失败、cleanup 失败、
  region 目录创建失败均抛 `InPlaceReplacementException`，已替换/已删除部分不回滚（不掩盖破坏性结果）。
- 新增 `JsonTestParser`（core，T5）：无 JSON 依赖下用最小严格解析器把序列化报告解析回值模型，
  证明输出是**合法 JSON**（此前仅 `string.contains`）。
- `MainCliE2ETest` 追加（T6/T7）：`--remove-unknown` 外部 chunk 保留/剔除、`--parallelism 4`
  完整输出、merge 并行与串行逐字节一致、未知进度模式拒绝。
- `WorldMergerTest` 追加（T9）：8 region × parallelism 4 多轮 merge 输出逐字节一致且等于串行结果。

### Quality Gate
- **koverVerify 绑定 `check`**：core/app 均加 `tasks.check { dependsOn("koverVerify") }`，
  coverage 阈值（core 75% / app 50%）不达标时 `./gradlew check` 直接失败，门禁不再可被绕过。

### CI (C1–C4 / C9–C11)
- **test-matrix**：macOS 裁剪为单 JDK（C1）；JDK 矩阵降为 `['17','21']`（C2）；push 仅触发 main +
  `concurrency` 取消旧 run + `docs/**`、`*.md` 路径过滤（C3）；coverage 并入 ubuntu Java 17 job
  复用已跑测试，不再二次运行整套套件（C4）。
- **release-lib**：Maven Central Portal 上传后解析 `deploymentId` 并轮询 `/api/v1/publisher/status`
  直到 SUCCESS/FAILED，FAILED 即 exit 1（C9）；GPG 私钥改经 `ORG_GRADLE_PROJECT_signingKey`
  环境变量注入，不再明文出现在进程命令行（C10）；测试前加 `ktlintCheck detekt` 门禁（C11）。
- **release-app**：同样加 `ktlintCheck detekt` 门禁（C11）。

### Docs
- 新增 `docs/architecture-review.md`：资深架构师 + QA 综合审查报告——优先级路线图（P0–P3）、
  架构审计 A1–A20、测试审计 T1–T13、CI 审计 C1–C23（全部带 file:line 证据），附修复状态跟踪表。
- 站点落地页新增该报告入口（`doc.html?file=architecture-review.md`）。
- 修复状态表更新：A3/A4/A5、C1–C4、C9–C11、T2–T9、koverVerify 质量门禁全部标记 ✅。

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
