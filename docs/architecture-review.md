# 架构与质量全面审查报告

> **审查对象**：OrzMCBackup（core 库 + app CLI）
> **审查日期**：2026-08-20
> **审查方法**：3 路独立 Agent 并行审计（架构/性能、测试/QA、CI/发版），全部基于代码事实（file:line 证据）
> **产出**：本报告 + P0 修复实施（见文末「修复状态」）

本报告是项目长期演进的事实基线。条目按**严重度/ROI**排序，已实施或部分实施的条目会在「修复状态」标注。

---

## 一、优先级路线图

| 优先级 | 条目 | 一句话说明 |
|---|---|---|
| **P0（数据安全 / 必须）** | A1 | Optimizer 缺 input/output 重叠守卫，`--force` 下可摧毁源世界 |
| **P0（数据安全 / 必须）** | A2 | 解压炸弹：压缩长度有界、解压长度无界，恶意/损坏 chunk 可 OOM |
| **P0（回归防护 / 必须）** | T1 | 声称的 CLI 端到端脚本 `cli_tests.ps1` 不在仓库，9 个 CLI 语义无回归守护 |
| P1（并行正确性） | A2' / A3 / A4 | errors 列表非线程安全、MetricsSink 双重计数、并行度平方（parallelism²） |
| P1（发布正确性） | C13 | Maven Central Portal 异步上传 2xx 即当成功，无发布后验证 |
| P1（CI 成本） | C1 / C2 / C3 | macOS×3JDK 无价值、JDK25×3OS 冗余、push+PR 双份矩阵无 concurrency |
| P2（健壮性） | A8 / A18 / T3 / T2 | 8KB 缓冲过小、MemoryFS 前缀匹配串目录、进度模式零断言、EXT_* 零覆盖 |
| P3（工程优化） | 其余 A/T/C 条目 | 缓冲/线程池/工具链/密钥注入/版本固定等 |

---

## 二、架构审计（core 引擎与 app CLI）

### 架构映射速览

| 类 | 位置 | 职责 |
|---|---|---|
| `Optimizer` / `DefaultOptimizer` | Optimizer.kt:43 / 58 | 静态门面 / 管道编排：发现维度→计数→处理→misc/zip/in-place |
| `DimensionContext` | Optimizer.kt:16 | 17 字段共享上下文（"上帝对象"，见 A7） |
| `DimensionProcessor` | DimensionProcessor.kt:17 | 单维度/单 region 的 chunk 过滤+写回（含 region 级并行） |
| `OptimizerRequest` + 六层 options | OptimizerConfig.kt:6 | 配置模型：filter/output/progress/runtime/hooks/io |
| `WorldMerger`/`DefaultMerger` | WorldMerger.kt:39/44 | merge 子命令引擎，chunk-slot 级合并 |
| `McaReader` / `McaEntry` / `McaWriter` | mca/*.kt | Anvil 头解析 / 只读 chunk 视图 / 扇区对齐写回 |
| `RandomAccess` 族 | RandomAccess.kt | 随机读抽象，8KB 缓冲包装 |
| `FileSystem` / `RealFileSystem` / `MemoryFS` | FileSystem.kt | 文件系统抽象（生产 + 测试） |

数据流：`Optimizer.run` → 发现维度 → `countTotalChunks` → `processDimensions`（串行/维度级并行）→
`DimensionProcessor.process`（region 级并行）→ 逐 chunk：读→解压匹配→保留则写回 → misc 复制/zip/in-place 回填。

### 设计问题 / 风险

**A1.【P0-高】Optimizer 缺少 input/output 重叠守卫，`--force` 下可摧毁源世界**
- 证据：`WorldMerger.kt:84-87` 有 `overlaps()` 守卫（`toRealPath` 追踪符号链接）；但 `Optimizer.kt:217-257` 的
  `resolveOutputDir` 在 force 时直接 `fs.walk(output).forEach { fs.deleteIfExists(it) }`（236-241），整个 Optimizer
  无任何 output==input/祖先/符号链接别名校验。
- 后果：`backup world world --force`（或 output 是 input 的父目录）会先删空输入世界再处理，**不可逆数据丢失**。
- 建议：在 `run()` 入口复用 `WorldMerger.overlaps` 的逻辑，对 `input`/`output` 做 `toRealPath` 比较
  （in-place/dry-run 的 temp 目录天然不重叠，排除），命中即报错返回。

**A2.【P0-中】解压炸弹：压缩长度有界、解压长度无界**
- 证据：`McaEntry.kt:153` 只限制压缩后 `MAX_VALID_CHUNK_LENGTH=8MB`；`allDataUncompressed`（130-141）
  `InflaterInputStream.readBytes()` 无上限读到 EOF。高压缩比（如全零）8MB chunk 可解压出数 GB，
  `InhabitedTimePattern.kt:29` 直接调用。
- 后果：恶意/损坏 chunk 触发 **OOM**。
- 建议：解压时带上限（逐块读并累计长度），超阈值抛异常走「安全保留」路径（`DimensionProcessor.shouldKeep`
  捕获后保留原始字节，不丢数据）。

**A3.【P1-中】Optimizer 的 `errors` 列表非线程安全，并行下丢失/损坏错误记录**
- 证据：`Optimizer.kt:120` `mutableListOf`，`record()`（131）无同步；`WorldMerger.kt:69` 用了
  `synchronized(errors)`，两者不一致。
- 后果：ArrayList 并发写可能丢元素甚至 `ArrayIndexOutOfBounds`。
- 建议：统一 `CopyOnWriteArrayList` 或对 `errors` 同步。

**A4.【P1-中】MetricsSink 的 processed 计数双重/竞态累加**
- 证据：`DimensionProcessor.kt:139` 返回**跨维度全局累计**的 AtomicLong 快照，`Optimizer.kt:297/320`
  又对它 `incProcessed(result.processed)`。串行下两维度各 100 块 → 300（应为 200）。
- 后果：注入自定义 MetricsSink 的库用户得到错误指标。
- 建议：`DimensionResult.processed` 改为本维度增量（差量），或 metrics 直接读最终原子值。

**A5.【P1-中】并行度被平方放大：维度池 × region 池 = parallelism² 线程**
- 证据：`Optimizer.kt:308-310` 维度池 `parallelism`；`processSingleDimension` 把同一 `ctx.parallelism`
  当 region 并行度（365），`DimensionProcessor.kt:62-64` 每维度再建 `parallelism` 池。
- 后果：`parallelism=8` → 最多 64 并发 region 线程，CPU 过度订阅、文件句柄打满。
- 建议：明确一层并行，或共享全局线程池并限制总并发数。

**A6.【P1-中】错误处理模型不一致：一部分 throw、一部分 record**
- 证据：`Errors.kt` 9 个异常绝大多数未被使用；唯独 `handleInPlaceReplacement`（`Optimizer.kt:371-417`）
  抛 `InPlaceReplacementException` 冒泡出 `run()`，绕过 report/error 列表。
- 后果：in-place 失败时 CLI 拿到异常而非结构化 `OptimizeError`。
- 建议：统一「收集错误不抛」或「全部抛异常由外层聚合」。

**A7.【P2-低】配置模型六层 + `Any?` 类型不安全的 sink 字段**
- 证据：`OptimizerConfig.kt:6-15` 六层嵌套；`progressSink: Any?`/`reportSink: Any?`（123-136/158-178）
  用 `when(value)` 运行时类型分发。
- 建议：`progressSink`/`reportSink` 强类型为 `ProgressSink`/`ReportSink`；扁平 setter 与 DSL 保留一种。

**A8.【P2-低】`DimensionContext` 是 17 字段上帝对象**
- 证据：`Optimizer.kt:16-34`。建议按生命周期拆分。

### 性能特征

**A9.【P1-中】`BufferedRafAccess` 8KB 固定缓冲，大 chunk 读退化为大量 syscall**
- 证据：`RandomAccess.kt:57-127`，`bufferSize=8192`（60）。读 1MB 压缩 chunk 约 128 次 seek+read；
  3M chunk ≈ 数亿次系统调用。
- 建议：大块读取绕过缓冲 `delegate.readFully(dst)`，或缓冲提升到 64~256KB。

**A10.【P2-低】`McaWriter.finalizeFile` 对每个 region `fd.sync()`**
- 证据：`McaWriter.kt:67`。几千个文件各 fsync 一次是尾延迟瓶颈。
- 建议：提供可选 `--no-fsync`，默认保留安全行为。

**A11.【P2-低】`countTotalChunks` 完整扫 header 又分配 1024 个 McaEntry 仅计数**
- 证据：`McaUtils.kt:27-30`。建议直接从 offset/size 表计数。

**A12.【P2-低】`overlayPatch` 的 misc 过滤 O(n²)**
- 证据：`WorldMerger.kt:267` `files.filter { !regionFiles.contains(p) }`。
- 建议：先 `regionFiles.toSet()`。

**A13.【P2-低】`McaWriter.writeEntry` 每次分配零填充 pad 数组**
- 证据：`McaWriter.kt:41-42`。建议静态缓存零数组分片写。

**A14.【P2-低】`discoverDimensions` 对 walk 每个路径都 `resolve("region")+isDirectory`**
- 证据：`Optimizer.kt:70-74`。建议按 `isDirectory` 过滤后再判断、walk 剪枝。

**A15.【P2-低】`McaWriter` 用 `offsets[idx] = start.toInt()`，超 2GB 溢出**
- 证据：`McaWriter.kt:45`。`toInt()` 溢出成负数产生损坏 header。
- 建议：对 `start` 做范围断言，溢出时显式报错。

### 安全 / 健壮性

**A16.【P2-中】`ForceLoad` 绕过 `FileSystem` 抽象直连真实磁盘**
- 证据：`ForceLoad.kt:29` `dimension.resolve(relPath).toFile()`。建议改收 `FileSystem`（或 bytes）。

**A17.【P2-低】`Files.copy` 对符号链接默认行为未显式约束**
- 证据：`FileSystem.kt:120-127` 未传 `LinkOption.NOFOLLOW_LINKS`。建议 misc 复制对 symlink 显式处理。

**A18.【P1-中】`MemoryFS.list` 前缀匹配会串目录**
- 证据：`FileSystem.kt:203-209` 字符串 `startsWith(base)`；`/mem/world` 会误包含 `/mem/world2`。
- 建议：按 `Path.getNameCount` + 组件相等判断。

**A19.【P2-低】`Cleaner` 与 `RealFileSystem.deleteTreeWithRetry` 逻辑重复**
- 证据：`Cleaner.kt:27-49` 与 `FileSystem.kt:145-167` 几乎逐行相同；`clearDosAttributes` 未清 `system` 属性。

**A20.【P2-低】中文硬编码错误消息（i18n 不一致）**
- 证据：`Optimizer.kt:462/467` 中文，其余英文。

### 正面确认（设计优点，非问题）

- **逐 chunk 流式处理，峰值内存低**：`processSingleRegion` 一次只解压/写一个 chunk，18GB/3M chunk 场景下
  内存与最大单 chunk 成正比。
- **损坏/未知压缩默认安全保留**：`McaEntry.kt:92-94`/137-138 → `DimensionProcessor.kt:174-188` 捕获后保留原始字节。
- **外部压缩 chunk 有明确语义**：`isExternal()`（`McaEntry.kt:143-149`）默认保留、`removeUnknown=true` 才删除。
- **损坏 offset/len 防死循环**：`RandomAccess.kt:112-116` 越尾抛 `EOFException`。
- **NBT 解析有界**：`NbtForceLoader.kt:23-25` 数组/列表/深度上限。
- **merge 锁步语义正确且无数据丢失**：`WorldMerger.mergeRegion`（356-466）；孤儿 entities/poi 处理（113-121）。
- **session.lock 处理完善**：跳过复制 + 合并后显式删除（`WorldMerger.kt:139-140`）。
- **并行输出内容确定**：region 间独立、region 内按 index 顺序写回，并行不改变输出字节。
- **alias guard 在 merge 中做得好**：`WorldMerger.kt:186-207` 区分 RealFileSystem（toRealPath）与 MemoryFS（词法）。

---

## 三、测试与 QA 审计

### 概览

- 测试规模：core 28 文件 / 134 方法；app 6 文件 / 17 方法（参数化如 `McaMemoryParamTest` 再展开），共约 151+ 用例。
- 测试层次：单元（MemoryFS 内存）/ 真实 IO（`RealFileSystem` + 真实夹具）/ CLI（`CommandLine.execute()` in-JVM）。
- 关键事实：**CHANGELOG 声称的 CLI 端到端脚本 `cli_tests.ps1` 不存在于仓库中**（见 T1）。
- 覆盖率门槛：core ≥75%、app ≥50%（Kover line coverage）；`koverVerify` 独立 task，**不绑定 `check`/`test`**，
  仅 CI 的 coverage job 显式运行（core/build.gradle.kts:49-59、app/build.gradle.kts:56-66、test-matrix.yml:101-119）。

### 发现清单（按风险排序）

**T1.【P0-高】CLI 端到端脚本 `cli_tests.ps1` 缺失**
- 证据：`CHANGELOG.md:28-29` 声称「CLI 端到端 9 项测试全部通过（dry-run / zip-output / csv / force 语义 /
  缺输出参数 / strict+损坏 region / in-place / 经典布局 DIM 数据保留 / --version+--help），脚本 `cli_tests.ps1`」；
  但全仓无 `.ps1`、`git log --all -- '*cli_tests*'` 无历史。benchmark 用的 `run_thresholds.ps1`/`snapshot.ps1`
  也在外部目录未提交。
- 影响：9 个语义（尤其 zip-output、in-place、dry-run、force、缺输出参数、strict+损坏）在 JVM 测试未全部覆盖，
  CI 无法执行，回归无法被阻断。
- 建议：改写为 in-JVM `CommandLine(Main()).execute()` 测试（**本次 P0 已实施**）。

**T2.【P1-高】外部压缩（EXT_GZIP/EXT_ZLIB/EXT_RAW/EXT_LZ4）与 `isExternal()` 路径零覆盖**
- 证据：`McaMemoryBuilder.kt:12` 只支持 `RAW/ZLIB/GZIP/LZ4`；`McaEntry.kt:143-149` + `InhabitedTimePattern.kt:28`
  的 external 分支无任何测试构造。这是「removeUnknown=true 时是否误删 .mcc 外部存储 chunk」的数据安全路径。
- 建议：McaMemoryBuilder 增加 EXT_* 压缩方法（header byte -127..-124），覆盖两态保留/删除。

**T3.【P1-高】进度模式 Off/Global/Region 的渲染逻辑未断言**
- 证据：`Main.kt:130-191` 大量 `when(progressMode)` 分支；现有测试只验证「能跑完不出错」，Off 多数用例在用，
  从未断言三种模式**打印了什么**。
- 建议：捕获 stdout，分别断言 Off 无进度输出、Global 含 `进度：X%`、Region 含 `处理区块文件：`。

**T4.【P1-中】`Compressor` 边界场景缺失**
- 证据：`CompressorTest.kt` 仅 1 测试。未覆盖：空目录、`root.parent ?: root` 分支、同秒时间戳冲突、压缩失败回滚。

**T5.【P1-中】JSON/CSV 报告无「解析回读」校验（仅字符串 contains）**
- 证据：`ReportIOTest.kt`/`MergeReportIOTest.kt` 全用 `assertTrue(json.contains(...))`，从未用解析器验证合法 JSON。
- 建议：对 `toJson` 做 parse round-trip；补完整转义用例（换行/制表符/引号/反斜杠/控制字符）。

**T6.【P1-中】backup 命令多个 CLI 选项无端到端测试**
- 证据：`--in-place`/`--zip-output`/`--dry-run`/`--remove-unknown`/`--progress-interval-ms`/`--report-format csv`/
  `--parallelism>1` 仅在 core 层有单元测试，app 层只覆盖 report-json、strict、copy-misc、--version/--help。
  非法 `--report-format` 静默回退 json（Report.kt:140-145）无人验证。

**T7.【P1-中】MergeCommand 的 CLI 错误路径与并行未覆盖**
- 证据：`MainCliMergeTest.kt` 覆盖缺 base、非空输出、报告、Global 进度；损坏 patch region、alias（base==output）、
  `--parallelism>1`、非法 `--progress-mode` 未在 CLI 层测。

**T8.【P1-中】in-place 失败路径与 entities/poi 替换未测**
- 证据：`handleInPlaceReplacement`（`Optimizer.kt:371-417`）会抛异常并删除未保留 .mca；现有测试仅成功路径。
  清理/复制失败、entities/poi 目录处理、stale .mca 移除未测。

**T9.【P1-中】并行 merge 的确定性与线程安全回归不足**
- 证据：`WorldMergerTest.kt:565-587` 仅 1 个 parallelism=2、2 region 用例。
- 建议：多 region（≥8）parallelism=4 重复运行断言输出逐字节一致。

**T10.【P2-中低】Windows 专项（DOS 属性 / Cleaner）无直接单元测试**
- 证据：`MainCliCopyMiscWindowsTest.kt` 用 `assumeTrue(isWindows())`，非 Windows 静默跳过；`Cleaner.clearDosAttributes`
  无直接测试。

**T11.【P2-中低】`RangePattern` 已测试但未暴露（生产死代码）**
- 证据：4 个测试，但生产/CLI 只用 `ListPattern + InhabitedTimePattern`（`Optimizer.kt:347-351`）。
- 建议：CLI 暴露矩形保留选项并加 e2e 测试，或删除。

**T12.【P2-低】真实夹具测试可静默跳过**
- 证据：`McaReaderTest.kt:14`、`InhabitedThresholdTest.kt:14` 用 `assumeTrue(fixtures missing)`；其它夹具测试直接失败，
  行为不一致。建议统一为失败。

**T13.【P2-低】NBT 解析深度/其他边界**
- 证据：`NbtForceLoader` 有 `maxCompoundDepth`（115-117）但 `NbtForceLoaderTest` 只测数组/列表上限，深度超限未测。

### 测试质量评估

- **断言真实性**：核心算法测试（WorldMergerTest 29 用例、NbtForceLoaderTest、CorruptedChunk*）有精确断言；
  但 `FixtureCompatibilityTest`/`Paper26StructureTest` 多为「不抛异常 + 有产出」冒烟，未验证具体计数/内容。
- **集成分层完整**：单元 → 真实 IO 集成 → CLI in-JVM 三层齐全；**缺失进程级 e2e**（不 spawn 真实 backup.jar、
  不测 System.exit/manifest 版本）。这是最薄弱的层。

---

## 四、CI 与发版审计

### 成本与矩阵

**C1.【P1-高】macOS × 3 JDK 几乎无真实价值**
- 证据：`test-matrix.yml:76-100`。全仓仅一个 OS 相关测试 `MainCliCopyMiscWindowsTest.kt`（macOS/Ubuntu 上 skip）；
  生产 LZ4 用纯 Java `LZ4Factory.safeInstance()`（`McaEntry.kt:173-174`），无原生依赖。macOS 分钟成本是 Linux 10 倍。
- 建议：macOS 裁剪为 1 个 JDK 或不保留。

**C2.【P1-高】JDK 25 在矩阵跑满 3 OS 冗余**
- 证据：`test-matrix.yml:30,55,81`。产物目标固定 Java 17；JDK25 工具链兼容已由 lint/coverage job（JDK 25）承担。
- 建议：矩阵降为 `['17','21']`。

**C3.【P1-高】push + PR 双份矩阵、无 concurrency、无路径过滤**
- 证据：`test-matrix.yml:3-7` 同时 `push: branches: ['**']` + `pull_request`，无 `concurrency`。
  每次 PR push 跑 2×11=22 job，纯 docs 改动也跑全量。
- 建议：只保留 `pull_request`（+ push 到 main）；加 `concurrency: group/cancel-in-progress`；加 `paths-ignore: ['docs/**','*.md']`。

**C4.【P1-中】coverage job 二次运行整套测试**
- 证据：`test-matrix.yml:117` koverXmlReport 以 test 为依赖再跑一遍。全量套件至多被跑 12 遍。
- 建议：coverage 只跑 ubuntu 单 JDK，并与矩阵中 ubuntu-25 job 合并。

### 缓存与速度

**C5.【P1-中】无远程 Build Cache，跨 PR 编译无复用**
- 证据：`gradle.properties:4` 仅本地缓存，`settings.gradle.kts` 无 `buildCache { remote }`。每个 job 从零编译。
- 建议：接入远程 Build Cache，或利用 setup-gradle 的跨分支缓存键。

**C6.【P2-低】`cache: gradle` 与 setup-gradle 缓存重复**
- 证据：setup-java `cache: gradle` 与 `gradle/actions/setup-gradle` 职责重叠。建议去掉前者。

**C7.【P2-低】`--no-daemon` 让 lint/coverage 各启动两次 JVM**
- 建议：合并为单条 `./gradlew ktlintCheck detekt`、`./gradlew :core:koverXmlReport :core:koverVerify :app:koverXmlReport :app:koverVerify`。

### lint / coverage

**C8.【P2-低-中】detekt `2.0.0-alpha.6` 当质量门禁**
- 证据：`libs.versions.toml:6`。alpha 有稳定性风险（v0.1.5 记录过属性名变更）。建议尽快升正式版。

### 发版（release-app / release-lib）

**C9.【P0-高】Maven Central Portal 异步上传：HTTP 2xx 即当成功，无发布后验证**
- 证据：`release-lib.yml:82-90` curl 返回 2xx 就 break 视为成功，未解析 deployment ID、不轮询状态。
  Portal 201 仅表示「bundle 已接收」，后续签名/坐标校验失败 workflow 仍绿。
- 建议：提取 deployment ID，轮询 `GET /api/v1/publisher/status?id=<id>` 直到 SUCCESS/FAILED，FAILED 时 exit 1。

**C10.【P1-中】GPG 私钥经 `-Psigning.key` 传入命令行**
- 证据：`release-lib.yml:46`。明文出现在进程命令行，是已知反模式。
- 建议：改用 `ORG_GRADLE_PROJECT_signingKey` 环境变量注入（`core/build.gradle.kts:139-141` 兼容）。

**C11.【P1-中】release 工作流跳过 lint/detekt/kover 门禁**
- 证据：tag push 不触发 test-matrix；release 工作流只跑 `:core:test :app:test`。
- 建议：release job 测试前加 `ktlintCheck detekt`（或至少 `koverVerify`）。

**C12.【P2-低】本地 sha256 自校验是「自证」式同义反复**
- 证据：`release-lib.yml:47-53` 生成后 62-66 立即用同一批本地文件反验，无法发现上传损坏。`.sha256` 不进入 bundle。
- 建议：保留 `unzip -t` 与 `.asc` 存在性检查即可；真要校验应在 Portal 返回后回下载再验。

**C13.【已确认正确】`backup.jar` 固定名 + 版本注入链路健全**
- 证据：`app/build.gradle.kts:68-82`、`release-app.yml:51-53`、`build.gradle.kts:21-24`（"unspecified" 回落）、
  两处 tag 格式校验。**无需改动。**

### ci-retry.sh

**C14.【P1-中】判定标准缺 502/503/Bad Gateway 特征**
- 证据：`tools/ci-retry.sh:18` 正则未含 `502|503|504`、`Bad Gateway|Service Unavailable|Gateway Timeout`、`codeload`。
- 建议：正则追加。

**C15.【P1-中】固定 15s 退避、无指数/jitter**
- 证据：`tools/ci-retry.sh:12-13,42`。9 job 冷缓存并发时 3×15s 可能熬不过限流窗口。
- 建议：指数退避 + jitter，默认尝试 4-5 次。

**C16.【P2-低】输出全量缓存内存、非流式**
- 证据：`tools/ci-retry.sh:25`。建议 tee 到临时文件流式打印。

**C17.【已确认正确】只对瞬时网络错误重试，真实失败立即上报**
- 证据：`tools/ci-retry.sh:34`。

### 迭代速度 / 安全 / 维护

**C18.【P1-中】pre-commit 的 ktlint 版本与项目 14.2.0 可能漂移**
- 证据：`.pre-commit-config.yaml:16-20` `jlleitschuh/ktlint-pre-commit-hook rev: v1.4.1` 未固定 ktlint 版本。
- 建议：hook 固定 `additional_dependencies` 到 `com.pinterest.ktlint:ktlint-cli:14.2.0`。

**C19.【P2-低-中】权限最小化缺失**
- 证据：test-matrix 与 release-lib 无 `permissions` 块。
- 建议：加 `permissions: contents: read`；release-app 保留 `contents: write`。

**C20.【P2-低】workflow 版本漂移：setup-gradle `@v6.2.0` vs `@v6`**
- 建议：统一到同一精确版本；关键写权限 action 钉 commit SHA。

**C21.【P1-中】Dependabot 无 grouping，10 PR × 全矩阵 churn**
- 证据：`dependabot.yml:5-33`。建议加 `groups:` 聚合。

**C22.【P2-低】无 `.gitattributes`，Windows bash 脚本换行潜在风险**
- 建议：加 `*.sh text eol=lf` 等约束。

**C23.【已确认正确】secrets 使用整体健康**
- CODECOV_TOKEN 空串 fallback（test-matrix.yml:103-137）、CENTRAL_TOKEN 经 env 传入、signing 经项目属性（唯一需改点 C10）。

---

## 五、修复状态

P0 条目已在审查当轮实施；P1 条目已按路线图顺序实施（并行正确性 → 发布正确性 → CI 成本 → 质量门禁 → 测试补强）：

| 条目 | 状态 | 变更 |
|---|---|---|
| A1 Optimizer 重叠守卫 | ✅ 已实施 | `OverlapGuard` 复用 merge 的 toRealPath 语义；`DefaultOptimizer.run` 入口守卫 + 单测 |
| A2 解压炸弹防护 | ✅ 已实施 | `McaEntry` 解压带上限，超限抛异常走安全保留路径 + 单测 |
| A3 并行错误收集线程安全 | ✅ 已实施 | `errors` 由 `ArrayList` 改 `CopyOnWriteArrayList`（region 并行 worker 并发 record） |
| A4 MetricsSink 双重计数 | ✅ 已实施 | 移除按维度 incProcessed/incRemoved，指标只在 `run()` 以最终报告上报一次 |
| A5 并行度平方 | ✅ 已实施 | 移除维度层并行，并行度只在 region 层生效（热点路径，单盘无增益） |
| A6 错误处理模型统一 | ✅ 已实施 | in-place 替换失败由抛 `InPlaceReplacementException` 冒泡改为记录 InPlace 错误进 `report.errors`（与 resolveOutputDir/copyMiscFiles/handleZipOutput 收集不抛一致）；createDirectories 失败跳过该子树保持输入不受影响；删除 `InPlaceReplacementException` 异常类 |
| C9 Portal 异步上传验证 | ✅ 已实施 | 解析 `deploymentId` + 轮询 `/api/v1/publisher/status` 至 SUCCESS/FAILED，FAILED 即 exit 1 |
| C10 GPG 私钥经 env 注入 | ✅ 已实施 | `ORG_GRADLE_PROJECT_signingKey` 环境变量；build.gradle 兼容非点号属性 |
| C11 release 缺 lint/detekt 门禁 | ✅ 已实施 | release-lib / release-app 测试前加 `ktlintCheck detekt` |
| C1 macOS × 3 JDK 冗余 | ✅ 已实施 | macOS 裁剪为单 JDK `['17']` |
| C2 JDK 25 矩阵冗余 | ✅ 已实施 | ubuntu / windows 矩阵降为 `['17','21']` |
| C3 push+PR 双份矩阵 / 无 concurrency | ✅ 已实施 | push 仅 main；`concurrency` 取消旧 run；`paths-ignore: docs/**、*.md` |
| C4 coverage 二次运行整套 | ✅ 已实施 | coverage 并入 ubuntu Java 17 job，复用同步已跑测试 |
| T1 CLI 端到端测试 | ✅ 已实施 | 将 cli_tests.ps1 的 9 个语义改写为 in-JVM `CommandLine.execute()` 测试 |
| T2 EXT_* 外部压缩覆盖 | ✅ 已实施 | `ExternalCompressionTest`：removeUnknown 剔除/默认保留 + 标记字节回读存活 |
| T3 进度模式渲染断言 | ✅ 已实施 | `MainCliProgressModeTest`：Off 零输出 / Global `进度：X%（n/total）` / Region 逐文件 / interval-ms |
| T4 Compressor 边界 | ✅ 已实施 | `compressToTimestampZip` 单元素相对路径归档自包含修复（`root.toAbsolutePath().parent`） |
| T5 报告解析回读 | ✅ 已实施 | `JsonTestParser` 最小严格解析器，报告输出证明为合法 JSON（不再仅 contains） |
| T6 backup CLI 选项补测 | ✅ 已实施 | E2E 追加 remove-unknown / parallelism>1 / zip 语义 |
| T7 merge CLI 错误路径 | ✅ 已实施 | E2E 追加 alias 拒绝、并行==串行逐字节一致、非法 progress-mode 拒绝 |
| T8 in-place 失败契约 | ✅ 已实施 | `OptimizerInPlaceFailureTest`：copy / cleanup / createDirectories 失败记录 InPlace 错误进 `report.errors` 不抛（A6 统一收集）；createDirectories 失败输入不受影响 |
| T9 并行确定性 | ✅ 已实施 | `WorldMergerTest` 8 region × parallelism 4 多轮输出逐字节一致且等于串行 |
| 质量门禁 koverVerify → check | ✅ 已实施 | core/app `tasks.check { dependsOn("koverVerify") }`，阈值不达标即 fail |
| C5 构建缓存 | ✅ 已实施 | `settings.gradle.kts` 显式 `buildCache { local }`；本地缓存目录由 setup-gradle 的 Actions 缓存持久化，实现跨 job / 跨 PR 复用 |
| C14 ci-retry 正则补全 | ✅ 已实施 | `ci-retry.sh` 正则追加 `50[234]`、`Bad Gateway`、`Gateway Timeout`、`codeload` |
| C15 ci-retry 指数退避 | ✅ 已实施 | `base * 2^(attempt-1) * jitter(0.5~1.5)`，默认重试 5 次 |
| C18 pre-commit ktlint 固定 | ✅ 已实施 | `.pre-commit-config.yaml` 钉死 `ktlint-cli:14.2.0` |
| C21 Dependabot grouping | ✅ 已实施 | `dependabot.yml` 按领域聚合 gradle 依赖 + actions 单组 |

P2 条目已按路线图实施（性能 → 健壮性 → 架构 → 测试 → CI/工程）：

| 条目 | 状态 | 变更 |
|---|---|---|
| A9 BufferedRafAccess 绝对位置 | ✅ 已实施 | `pos` 绝对位置 + 大块读旁路，消除「整块读+尾部再读」的双次 seek 模式 |
| A10 syncOnFinalize 可选 | ✅ 已实施 | `IOOptions.syncOnFinalize` + `--no-fsync` CLI 选项；未开时跳过 `fd.sync()` |
| A11 零分配 chunk 计数 | ✅ 已实施 | `McaReader.count()` / `McaIOFactory.count()`，不分配 `McaEntry` |
| A12 misc 文件集合过滤 | ✅ 已实施 | `regionFiles.toSet()` + `!regionSet.contains(p)`，消除 O(n²) |
| A13 padding 零缓冲 | ✅ 已实施 | 复用预清零 `ByteArray(4096)` 写 sector padding |
| A14 维度发现短路 | ✅ 已实施 | 既有代码已满足（`isDirectory` + `isDimensionDir` 短路），无改动 |
| A15 region 2 GiB 溢出守卫 | ✅ 已实施 | offset 表 3 字节无法寻址 >2GiB，超限抛 `IllegalStateException` |
| A16 ForceLoad FileSystem 感知 | ✅ 已实施 | `ForceLoad.parse(fs, dim, strict)` + `NbtForceLoader.parse(bytes)` 字节重载 |
| A17 copy 不跟随符号链接 | ✅ 已实施 | `RealFileSystem.copy` 加 `NOFOLLOW_LINKS` |
| A18 MemoryFS 路径组件匹配 | ✅ 已实施 | `list`/`walk` 改 `key.parent`/`Path.startsWith`，修复 Windows `\` 分隔前缀串匹配 bug |
| A19 Cleaner 统一删除 | ✅ 已实施 | `deleteTreeWithRetry` 委托 `Cleaner`；`clearDosAttributes` 补 `setSystem(false)` |
| A20 中文错误消息改英文 | ✅ 已实施 | `Optimizer` CopyMisc 错误消息英文化 |
| A7 sink 强类型化 | ✅ 已实施 | `progressSink`/`reportSink` 由 `Any?` 运行时分发改强类型属性；Path/String 便捷走函数 |
| A8 DimensionContext 生命周期拆分 | ✅ 已实施 | progress 生命周期抽为 `ProgressTracker`，DimensionContext 17→12 字段 |
| T10 Cleaner 直接单测 | ✅ 已实施 | `CleanerTest`：DOS 属性清理（Windows 门控）+ 只读树删除 + 缺根语义 |
| T11 RangePattern 处置 | ✅ 已实施 | 判定为生产死代码，删除 `RangePattern` 及 4 个测试（可 git 恢复） |
| T12 夹具缺失改失败 | ✅ 已实施 | `McaReaderTest`/`InhabitedThresholdTest` 由 `assumeTrue` 改 `assertTrue`，不再静默跳过 |
| T13 NBT 深度边界 | ✅ 已实施 | `NbtForceLoaderTest` 增深度超限拒绝 + 边界内正常解析两测 |
| C6 cache 去重 | ✅ 已实施 | 移除 setup-java `cache: gradle`，缓存统一由 setup-gradle 承担 |
| C7 命令合并 | ✅ 已实施 | lint 两步并为 `ktlintCheck detekt`；coverage 生成+验证并为一条命令 |
| C8 detekt 版本 | ⏸️ 保持 alpha.6 | 2.0 正式版未发布（最新 alpha.3 < 当前 alpha.6）；detekt.yml 为 2.0 专属配置，降 1.23.8 报 11 个无效属性。version catalog 已钉死，正式版发布后升级 |
| C12 sha256 自证去除 | ✅ 已实施 | release-lib 移除本地 sha256 生成与反验，保留产物 + `.asc` 存在性检查 |
| C16 ci-retry 流式 | ✅ 已实施 | 输出 `tee` 到临时文件流式打印，不再缓存全量到变量 |
| C19 permissions 最小化 | ✅ 已实施 | test-matrix 已有 `contents: read`；release-lib 补同；release-app 保留 `contents: write` |
| C20 setup-gradle 版本统一 | ✅ 已实施 | 三工作流统一 `@v6.2.0` |
| C22 .gitattributes | ✅ 已实施 | `*.sh text eol=lf` + 文本类扩展名显式声明 |

剩余待办：C8 detekt 2.0 正式版升级（2.0 正式版发布后）；C9/C13 Portal 发布后回下载验证（待真正发布时执行）；P3 其余条目。
