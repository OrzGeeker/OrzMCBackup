# OrzMCBackup 功能点梳理

> 基于 v0.2.3 代码库 + Unreleased（A1/A2/T1 增强），2026-08 版

## 一、项目定位

Minecraft Java 版世界优化工具，双用途：
- **CLI 工具**：Shadow JAR，基于 picocli，单命令行入口
- **库（Library）**：发布到 Maven Central，artifact `io.github.wangzhizhou:backup-core`

**核心能力**：扫描世界的 region / entities / POI 的 MCA 文件，根据 InhabitedTime（玩家活跃时间）阈值或强制加载列表移除区块，重写输出。

---

## 二、核心优化引擎

### 2.1 优化器接口与入口

| 功能点 | 说明 |
|--------|------|
| `OptimizerEngine` 接口 | 定义 `run(OptimizerRequest)` 和 `run(Path, lambda)` 两种入口 |
| `DefaultOptimizer` 单例 | 完整实现：维度发现 → 区块计数 → 逐个维度处理 → 输出处理 |
| `Optimizer` 静态入口 | Java 兼容包装，提供 `@JvmStatic` 的 `run` 方法 |

### 2.2 配置模型

**`OptimizerRequest`** 聚合 6 大子配置：

| 配置类 | 字段 | 功能 |
|--------|------|------|
| `FilterOptions` | `inhabitedThresholdSeconds=300` | 活跃时间阈值（秒） |
| | `removeUnknown=false` | 是否移除外部压缩/不可解析的区块 |
| | `strict=false` | 严格模式（I/O 错误提升为退出码 1） |
| `OutputOptions` | `inPlace=false` | 原地替换模式 |
| | `zipOutput=false` | 输出打包为 ZIP |
| | `force=false` | 强制覆盖非空输出目录 |
| | `copyMisc=true` | 复制非 MCA 的杂项文件 |
| | `dryRun=false` | 预览模式（只统计不写入） |
| `ProgressOptions` | `interval=1000` | 按区块数的进度回调间隔 |
| | `intervalMs=0` | 按毫秒的进度回调间隔（>0 时优先） |
| `RuntimeOptions` | `parallelism=1` | region 级并行线程数（维度按序处理，A5） |
| `Hooks` | `onError` | 错误回调 lambda |
| | `reportSink` | 报告输出接收器 |
| | `metricsSink` | 指标收集接收器 |
| `IOOptions` | `fs=RealFileSystem` | 文件系统抽象（可注入内存实现） |
| | `ioFactory=DefaultMcaIOFactory` | MCA 读写器工厂 |

**Builder 模式**：`OptimizerRequestBuilder` 提供流畅 API，支持分组配置（`filter { }`、`output { }` 等）。

### 2.3 输入/输出目录重叠守卫（`OverlapGuard`，数据安全）

- 抽取自 `WorldMerger.overlaps` 的共享守卫（单一事实源），`Optimizer.run` 与 `WorldMerger.run` 复用。
- `Optimizer.run` 在**任何写入之前**校验 input 与 output 相同 / 嵌套 / 祖先 / 符号链接别名，`--force`
  覆盖前即拒绝（`input == output` 且带 `--force` 时会先清空输入，不可逆）。
- 路径解析：`RealFileSystem` 走 `toRealPath`（解析符号链接/junction 别名）；`MemoryFS` 走词法
  normalize（`toRealPath` 会把内存数据物化到临时目录，产生假别名）。
- in-place 与 dry-run 使用全新临时目录，天然不重叠，不触发守卫。

---

## 三、世界扫描与维度发现

### 3.1 维度发现（`DefaultOptimizer.discoverDimensions`）

- 递归扫描输入目录
- 任意包含 `region/` 子目录的路径视为一个维度
- **兼容 Minecraft 26.1+** 的 `dimensions/minecraft/<dim>/` 嵌套结构
- 也兼容传统平面布局（`<world>/region/`）

### 3.2 杂项父目录发现（`DefaultOptimizer.discoverMiscParents`）

- 从每个维度的父目录链向上遍历，收集非维度目录作为杂项源
- 当输入目录本身不是维度但包含 `dimensions/` 子目录时（26.1+ 直接传入世界目录的场景），将 input 也加入杂项源
- 确保 world 级别的 `level.dat`、`players/`、`data/`、`datapacks/` 等文件在 `--copy-misc` 时正确复制

### 3.3 强制加载区块解析（`ForceLoad` + `NbtForceLoader`）

两级探测（按优先级）：
1. **`data/minecraft/chunk_tickets.dat`**（26.1+ 新版格式）
   - 解析 `data.tickets[].chunk_pos`，筛选 `type == "minecraft:forced"`
2. **`data/chunks.dat`**（旧版格式）
   - 解析 `data.Forced`（LongArray，内联存储 x/z 对）

**NBT 解析器特性**：
- 支持所有 12 种标准 NBT 标签类型
- GZip 压缩 NBT 文件读取
- 安全限制：`maxArraySize=10MB`、`maxListLength=65536`、`maxCompoundDepth=64`
- 解析失败不中断处理（除非 strict 模式）

### 3.4 区块总数统计（`McaUtils.countTotalChunks`）

- 遍历所有维度的所有 `.mca` 文件
- 校验文件大小 >= 8192 字节（至少一个完整头部）
- 错误容错（单文件损坏不影响整体处理）

---

## 四、区块保留策略（ChunkPattern）

### 4.1 `InhabitedTimePattern` — 活跃时间模式

- 基于 `InhabitedTime` NBT long 值做 `>` 比较（严格大于，非 >=）
- **`threshold=0` 时移除未被玩家访问过的区块**（即 `InhabitedTime == 0`）
- **字节级扫描**：搜索 `[TAG_Long(1)][name_length(2)]["InhabitedTime"(13)]` 模式，直接读后面 8 字节
  - 无需完整 NBT 反序列化，性能更高
- `removeUnknown` 标志控制外部压缩/不可解析区块的处理

### 4.2 `ListPattern` — 坐标列表模式

- 根据全局 `(x, z)` 坐标列表保留区块
- 用于强制加载区块的保留，也可用于自定义保留列表

### 4.3 模式组合

优化器始终合并两种模式：`ListPattern(forced) + InhabitedTimePattern(ticks, removeUnknown)`
- 顺序：按列表顺序评估
- **匹配任意一个模式 → 保留区块**
- 均不匹配 → 移除区块

---

## 五、维度处理（DimensionProcessor）

### 5.1 目录结构重建

- 创建输出维度的 `region/`、`entities/`、`poi/` 子目录
- 仅当输入中有对应目录时才创建（兼容维度没有 entities/poi 的情况）

### 5.2 区块级处理

| 功能点 | 说明 |
|--------|------|
| MCA 文件验证 | 检查文件大小 >= 8192 字节 |
| 三文件联动 | 处理一个区块时，同时读写 `*.mca` + `entities/*.mca` + `poi/*.mca` |
| 惰性写入 | 仅当至少一个区块被保留时才创建 `McaWriter`，避免写入空文件 |
| 扇区对齐 | 写入数据填零至 4KiB 边界，符合 Minecraft Anvil 格式 |
| 头部刷新 | `finalizeFile()` 确保位置表和时戳表写入文件头部 |
| 资源清理 | try/finally 确保所有 reader/writer 关闭 |
| 区域级并行 | 可选线程池并行处理多个 .mca 文件 |

### 5.3 错误种类

独立错误常量：`ERR_MCA`、`ERR_ENTITIES`、`ERR_POI`、`ERR_ENTRIES`、`ERR_PATTERN`、`ERR_WRITE`、`ERR_WRITE_ENTITIES`、`ERR_WRITE_POI`、`ERR_FINALIZE`、`ERR_FINALIZE_ENTITIES`、`ERR_FINALIZE_POI`。并行失败类型为字符串字面量 `"Parallel"`（`DimensionProcessor.kt`、`WorldMerger.kt`），非常量

---

## 六、输出处理

### 6.1 原地替换（In-Place）

1. 处理到临时目录 `thanos-*` 或 `thanos-dry-*`
2. 将新 MCA 文件从临时目录复制回源目录
3. 删除已不存在于输出的 MCA 文件
4. 清理临时目录

### 6.2 杂项文件复制（Copy Misc）

杂项文件来源来自 **两个渠道**（`miscSources = tasks + miscParents`）：
1. **维度目录**（`tasks`）：每个维度的 `region/`、`entities/`、`poi/` 以外的文件
2. **杂项父目录**（`miscParents`，由 `discoverMiscParents` 发现）：非维度的中间目录，用于保留 world 级别的配置文件

**复制规则**：
- 遍历每个源的目录树，排除 `region/`、`entities/`、`poi/` 顶层子目录
- 通过 `dimSet`（所有维度路径的集合）排除已作为维度处理的路径，避免重复
- 通过 `OutputOptions.copyMisc` 开关控制（默认 `true`）

**26.1+ 世界目录直接输入场景**：
- 例如输入 `~/Downloads/world`（而非 `~/Downloads`），world 目录本身会被 `discoverMiscParents` 加入
  杂项源，从而正确复制 `level.dat`、`players/`、`data/`、`datapacks/`、`generated/` 等 world 级别文件

### 6.3 ZIP 打包

- 通过 `Compressor.compressToTimestampZip` 将输出目录打包为 `yyyyMMddHHmmss.zip`
- 使用标准 `ZipOutputStream`

### 6.4 清理器（Cleaner）

- Windows DOS 属性清除：`clearDosAttributes()` 移除只读/隐藏属性
- 带重试的目录树删除：`deleteTreeWithRetry(root, attempts, sleepMs)`
- 逆序遍历（先清空子文件再删目录）

### 6.5 预览模式（Dry Run）

`dryRun=true` 时只扫描和统计，不写入任何文件，不改动输入目录。

---

## 七、merge 槽位级合并（v0.2.0）

### 7.1 场景与算法

当仅剩"优化备份（按 InhabitedTime 裁剪过）+ 更早全量备份"两份数据时，用 `merge` 以 **chunk 槽位粒度**合并，恢复"全量最新"地图，避免同名文件覆盖导致的地图空洞/地形回退。

```
槽位来源 = patch 有该槽 ? patch（更新） : base 有该槽 ? base : 空
```

- **patch 优先、base 填空槽**；entities/poi 与 region **锁步**（槽位来源为 patch → 取 patch 的 entities/poi 该槽，来源为 base → 取 base 对应槽），绝不能直接复制 base 的 entities/poi。
- 合并后全空的 entities/poi 文件**不生成**；仅 base 存在的 region → 原样复制 base（含其 entities/poi）；仅 patch 存在 → 复制 patch region + 同名 entities/poi 兄弟文件。
- 杂项文件（`level.dat` 等）按 patch（更新版）覆盖。

### 7.2 CLI 参数（`merge BASE PATCH OUTPUT`）

| 参数 | 默认 | 功能 |
|------|------|------|
| `-f`/`--force` | false | 覆盖非空输出目录 |
| `--progress-mode` | Off | Off/Global/Region |
| `--parallelism` | 1 | 并行合并 region 文件的线程数（>1 时逐 region 独立写文件，输出仍确定） |
| `--progress-interval` | 1000 | 进度回调间隔（文件数） |
| `--report` | false | 打印合并统计与错误 |
| `--report-file` / `--report-format` | null / json | 报告写文件（json/csv） |

### 7.3 库 API（`WorldMerger` / `MergeReport` / `MergeReportIO`）

- 入口：`WorldMerger.run(MergeRequest)` → `MergeReport`；`MergeReportIO.write(report, path, format)` 落盘 JSON/CSV。
- `MergeReport` 字段语义：
  - `mergedRegions`：base/patch 共有、做了槽位级合并的 region 数
  - `copiedFiles` / `overlayFiles`：patch 独有的 region 文件 / 覆盖的杂项文件数
  - `patchSlots` / `baseSlots`：取自 patch 与 base 的槽位数
  - `linkedEntities` / `linkedPoi`：锁步写入的 entities/poi 条目数
  - `errors`：非致命错误（`OptimizeError`）列表
- **性能**：`copyTree` 阶段跳过 patch 也存在的 `region/entities/poi/*.mca` 的 base→out 冗余复制（overlay 阶段会用合并结果重写）；损坏 patch region 时回退复制 base 对应文件，保证不丢数据。
- **有损告诫**：`Hooks.reportSink` 会把 `MergeReport` 经 `MergeReportIO.toOptimizeReport` 映射为 `OptimizeReport`（`processedChunks`/`removedChunks` 实为 patch/base 槽位计数），需完整字段时直接用 `MergeReportIO.write`。

### 7.4 安全性

- base/patch/output 三目录**互不重叠**校验（任一重叠即拒绝，复用 `OverlapGuard`，见 2.3）。
- 输入非目录、输出不可写、非空输出未加 `--force` 均记录错误并返回非 0 退出码。
- 逐 region 独立写文件，并行（`--parallelism > 1`）不改变输出字节。

---

## 八、MCA 文件格式库（`mca/` 包）

### 7.1 随机访问抽象（`RandomAccess`）

三层实现：
| 实现类 | 特点 |
|--------|------|
| `RafAccess` | 包装 `java.io.RandomAccessFile`，直接系统调用 |
| `BufferedRafAccess` | **8KiB 对齐读缓冲**，减少系统调用，适合连续扇区访问 |
| `MemoryAccess` | 基于 `ByteArray` 的内存模式，用于测试 |

### 7.2 MCA 读取器（`McaReader`）

- 解析文件名 `r.x.z.mca` 提取区域坐标
- 读取 8KiB 头部（4KiB 位置表 + 4KiB 时间戳表）
- 位置表解码：`(offset_in_sectors << 8) \| size_in_sectors`
- `entries()` 返回所有非空扇区条目
- 支持文件模式和内存模式打开

### 7.3 MCA 写入器（`McaWriter`）

- 空文件初始化（首 8192 字节填零）
- `writeEntry()`：4KiB 对齐写入
- `finalizeFile()`：刷新 8KiB 头部并 fsync

### 7.4 MCA 条目（`McaEntry`）

**坐标计算**：
- `regionIndex()` → 扇区槽位 0-1023
- `xPos()` / `zPos()` → 区域内局部坐标（`index % 32` / `index / 32`）
- `globalX()` / `globalZ()` → 世界空间坐标

**压缩格式**（9 种）：
- 标准：`GZIP`、`ZLIB`、`RAW`、`LZ4`
- 扩展（外部存储）：`EXT_GZIP`、`EXT_ZLIB`、`EXT_RAW`、`EXT_LZ4`
- 自定义：`CUSTOM`（128 字节名称）

**数据访问**：
- `serializedBytes()` → 完整头部+压缩数据，供写入用
- `dataBytes()` → 解析为 `(CompressionMethod, ByteArray, customName?)`
- `allDataUncompressed()` → 完整解压为原始字节
- `isExternal()` → 检查是否为外部压缩格式

**解压炸弹防护**（`MAX_UNCOMPRESSED_CHUNK_LENGTH` = 64MB）：
- 压缩长度上限 8MB 只约束压缩态；极小高压缩比 payload（如全零）可膨胀到数 GB 导致 OOM。
- ZLIB/GZIP 走 `readBounded`（解压总量超限即抛错）；LZ4 在分配目标缓冲区**之前**校验声明长度，
  并对累计解压量累加校验（防损坏表头 OOM）。
- 超限 chunk 走安全保留路径：原字节透传 + Pattern 错误上报，**绝不丢弃**。

**LZ4 解码**：
- 读取 LZ4Block 格式：魔数 `LZ4Block` + 1 byte token + 压缩/解压长度 + xxhash32
- Token `0x10` = 原始（未压缩）、`0x20` = LZ4 压缩
- 使用 `net.jpountz.lz4.LZ4Factory.safeInstance().safeDecompressor()`
- xxhash 校验和种子 `0x9747b28c`，比较时 `mask & 0x0FFFFFFF`

---

## 九、进度与报告

### 8.1 进度报告（`ProgressSink` + `ProgressEvent`）

**12 个进度阶段**：
`Init → Discover → DimensionStart → RegionStart → ChunkProgress → DimensionEnd → Finalize → CopyMisc → CopyMiscProgress → Compress → Cleanup → Done`

**双模式节流**：
- 按区块数：`processed % progressInterval == 0`
- 按时间：`now - lastEmit >= progressIntervalMs`（时间模式优先）

**实现**：`NoopProgressSink`（丢弃）、`CallbackProgressSink`（包装 lambda）

### 8.2 报告输出（`OptimizeReport` + `ReportIO` + `ReportSink`）

**报告字段**：
- `processedChunks`：已处理区块总数
- `removedChunks`：已移除区块总数
- `errors`：非致命错误列表（`path` + `kind` + `message`）

**三种序列化格式**：
| 格式 | 特点 |
|------|------|
| JSON | 完整结构，含所有错误详情 |
| CSV | 第一行汇总统计，后续行错误详情 |
| Text | 人类可读的纯文本格式 |

**报告接收器**：`FileReportSink`（写入文件）、`NoopReportSink`（丢弃）

### 8.3 日志接收器（`LoggerSink`）

- `info()` → stdout
- `warn()` / `error()` → stderr

### 8.4 指标收集（`MetricsSink`）

- `incProcessed(n)`、`incRemoved(n)`、`recordError(error)`
- `NoopMetricsSink`（默认丢弃，保留扩展点）

---

## 十、文件系统抽象（`FileSystem`）

### 9.1 接口方法（14 个）

`isDirectory`、`isRegularFile`、`createTempDirectory`、`exists`、`list`、`walk`、`createDirectories`、`deleteIfExists`、`copy`、`write`、`read`、`size`、`deleteTreeWithRetry`、`toRealPath`

### 9.2 `RealFileSystem`（生产用）

- 包装 `java.nio.file.Files`
- `deleteTreeWithRetry` 调用 `Cleaner.clearDosAttributes`（Windows 兼容）

### 9.3 `MemoryFS`（测试用）

- 基于 `ConcurrentHashMap<String, ByteArray>`，线程安全
- `toRealPath()` 将内存数据物化到临时目录（供需要真实路径的 API）

---

## 十一、错误处理体系（`Errors.kt`）

自定义异常层次（全部继承自 `OptimizeException` → `RuntimeException`）：

| 异常类 | 场景 |
|--------|------|
| `InputNotDirectoryException` | 输入路径不是目录 |
| `OutputRequiredException` | 需要输出目录但未提供（非原地模式） |
| `OutputNotEmptyException` | 输出目录非空且未设置 `--force` |
| `OutputAccessDeniedException` | 输出目录无写入权限 |
| `CompressionFailedException` | ZIP 打包失败 |
| `InvalidWorldStructureException` | 世界目录结构不符合预期 |
| `ForceLoadedParseException` | 强制加载文件解析失败 |
| `AggregateOptimizeException` | 收集多个 `OptimizeError` 统一上报 |

非致命错误收集在 `OptimizeReport.errors` 中，不中断处理流程。

---

## 十二、CLI 入口（`Main.kt`）

`Main.dispatch(args)` 将首参为 `merge` 的参数路由到 `MergeCommand`（merge 子命令，见第七节），其余走 backup 主命令；
`main()` 仅对非 0 退出码调用 `System.exit`，`dispatch` 本身可被测试直接调用。

### 11.1 参数体系

| 参数 | 类型 | 默认值 | 功能 |
|------|------|--------|------|
| `WORLD_DIR` | 位置参数（必填） | — | 世界目录 |
| `OUTPUT_DIR` | 位置参数（可选） | — | 输出目录 |
| `-t`/`--inhabited-time-seconds` | 选项 | 300 | 活跃时间阈值 |
| `--remove-unknown` | 开关 | false | 移除未知压缩区块 |
| `--progress-mode` | 枚举 | Region | Off/Global/Region |
| `--in-place` | 开关 | false | 原地处理 |
| `--zip-output` | 开关 | false | 输出打包为 ZIP |
| `-f`/`--force` | 开关 | false | 强制覆盖 |
| `--strict` | 开关 | false | 严格模式 |
| `--report` | 开关 | false | 打印摘要报告 |
| `--report-file` | 字符串 | null | 报告文件路径 |
| `--report-format` | 枚举 | json | json/csv |
| `--progress-interval` | 整数 | 1000 | 进度回调间隔（区块数） |
| `--progress-interval-ms` | 整数 | 0 | 进度回调间隔（毫秒） |
| `--parallelism` | 整数 | 1 | region 并行线程数（维度按序处理） |
| `--copy-misc` | 布尔 | true | 复制杂项文件 |
| `--dry-run` | 开关 | false | 预览模式 |

### 11.2 进度显示

- **Off**：不输出
- **Region**（默认）：按区域文件粒度显示中文状态
- **Global**：百分比进度 `[进度：X%(A/B)]`

### 11.3 退出码

- 0：成功
- 1：strict 模式下出错，或任何 `OptimizeException`

---

## 十三、测试体系

### 12.1 测试核心策略

**可测试性设计**：`FileSystem` + `McaIOFactory` 抽象使全管道可在内存中运行。
- `MemoryFS`（内存文件系统）
- `MemoryMcaIOFactory` + `MemoryMcaWriter`（内存 MCA 读写）
- `McaMemoryBuilder`（testFixtures，编程构建合成 MCA 数据）

### 12.2 测试类型

| 测试类 | 类型 | 覆盖场景 |
|--------|------|----------|
| `MemoryE2ETest` | 端到端 | 全管道：世界创建 → 优化 → 报告 |
| `MemoryParallelE2ETest` | 端到端 | 并行模式全管道 |
| `McaReaderTest` | 单元 | 文件/内存打开、条目解析、坐标提取 |
| `McaWriterTest` | 单元 | 写入单/多区块、头部完整性、`count()`、`syncOnFinalize` |
| `RandomAccessTest` | 单元 | 缓冲/旁路大读、EOF 边界、混合读计划 |
| `CleanerTest` | 单元 | DOS 属性清理、只读文件树删除、缺根语义 |
| `MemoryFSTest` | 单元 | `list`/`walk` 直接子级与组件级匹配（Windows 反斜杠安全） |
| `NbtForceLoaderTest` | 单元 | 新版/旧版强制加载 NBT 解析、数组/列表/深度边界 |
| `Lz4InvalidTest` | 单元 | 损坏 LZ4 数据的容错 |
| `OptimizerApiTest` | 单元 | API 入口组合 |
| `OptimizerConfigParamTest` | 参数化 | 所有配置组合 |
| `OptimizerInputValidationTest` | 单元 | 无效输入的错误处理 + input/output 重叠守卫（相等/嵌套/祖先拒绝，独立目录带 force 正常） |
| `OptimizerOutputModeTest` | 单元 | 4 种输出模式 |
| `ForceLoadedListTest` | 功能 | 强制加载保留 |
| `ForceLoadedOverrideThresholdTest` | 功能 | 强制加载覆盖阈值 |
| `InhabitedThresholdTest` | 功能 | 阈值 300/0/-1 场景 |
| `MemoryFSTest` | 单元 | MemoryFS 正确性 |
| `McaMemoryParamTest` | 参数化 | MCA 读写多样化组合 |
| `IoTimingTest` | 性能 | I/O 行为验证 |
| `LoggerSinkTest` | 单元 | 日志输出到 stdout/stderr |
| `ReportIOTest` | 单元 | JSON/CSV/Text 序列化 |
| `MainCliCopyMiscTest` | CLI | `--copy-misc` 处理 |
| `MainCliCopyMiscWindowsTest` | CLI | Windows 杂项复制 |
| `MainCliReportTest` | CLI | 报告生成 |
| `MainCliStrictExitCodeTest` | CLI | 严格模式退出码 |
| `Paper26StructureTest` | 功能 | Paper 26.1+ 世界目录结构（18 个测试） |
| `FixtureCompatibilityTest` | 功能 | 真实 MCA 夹具兼容性 |
| `WorldMergerTest` | 单元 | merge 槽位级合并（含损坏 patch 回退、copy/write 失败、并行一致性、进度边界等） |
| `MergeReportIOTest` | 单元 | MergeReport JSON/CSV/Text 序列化、父目录自动创建 |
| `RealMcaMergeTest` | 集成 | 用提交的真实 Anvil 夹具对走生产 `RealFileSystem`+`DefaultMcaIOFactory` 全链路合并 |
| `MainCliMergeTest` | CLI | merge 子命令 JSON/CSV 报告、进度模式、错误退出码 |
| `MainDispatchTest` | CLI | `Main.dispatch` 子命令分发（merge/backup/无参）退出码 |
| `RealWorldPatternTest` | 回归 | region/entities/poi 内非 `.mca` 文件（`.bak`/`.backup`）逐字节保留（miscRel 修复） |
| `CompressorTest` | 回归 | ZIP 打包条目使用正斜杠分隔符（ZIP 规范），Windows 跨平台可读 |
| `DecompressionBombTest` | 安全 | 解压炸弹防护：ZLIB 高压缩比 payload 超限抛错、LZ4 声明超限先于分配抛错、炸弹 chunk 端到端安全保留 |
| `MainCliE2ETest` | CLI | in-JVM 端到端：dry-run / zip-output / CSV 报告 / 未知格式回退 JSON / force 语义 / 缺输出参数 / in-place / 经典布局 DIM 数据 / merge 别名拒绝与损坏 patch 回退 / 非法 progress-mode |

### 12.3 辅助工具

- **`FailingFileSystem`**：模拟 I/O 错误，测试失败恢复
- **`TestHelper`**：测试工具函数
- **`TestPaths`**：定位基于磁盘的测试夹具目录
- **`TestTmp`**：临时目录管理
- **`PrintTestPaths`**：Gradle 任务，打印测试资源路径（CI 调试）

---

## 十四、CI/CD 流水线

| 工作流 | 触发条件 | 内容 |
|--------|----------|------|
| `test-matrix.yml` | push→main / PR | JDK `['17','21']` × ubuntu/windows + macOS 单 JDK 测试；concurrency 取消旧 run；`docs/**`、`*.md` 路径过滤；coverage 并入 ubuntu Java 17 job（复用已跑测试，`:core:koverVerify :app:koverVerify`，lint 使用 JDK 25） |
| `release-lib.yml` | tag `v*` / manual | 签名并发布库到 Maven Central Portal |
| `release-app.yml` | tag / manual | 构建 Shadow JAR + GitHub Release |
| `dependabot.yml` | 每日 | 自动检查依赖更新 |

---

## 十五、构建配置

| 模块 | 特性 |
|------|------|
| 根项目 | Kotlin 2.4.10、Gradle 9.7.0、JDK 17-29 校验 |
| `core` | 库发布、签名、Dokka（Javadoc JAR）、test-fixtures、kover 门槛 75% |
| `app` | Shadow JAR（fat JAR）、picocli CLI、kover 门槛 50% |

### 依赖

- **核心运行时**：Kotlin stdlib、`org.lz4:lz4-java:1.8.0`
- **并发**：`kotlinx-coroutines-core:1.11.0`
- **CLI**：`info.picocli:picocli:4.7.7`
- **测试**：JUnit Jupiter 6.1.3 + Platform Launcher

---

## 十六、关键架构决策总结

1. **抽象可测试性**：`FileSystem` + `McaIOFactory` 双抽象层，全管道可在内存测试
2. **惰性 MCA 写入**：仅在有保留区块时创建 writer，避免空文件
3. **字节级 InhabitedTime 扫描**：不解析完整 NBT，只搜 `TAG_Long` + 名称匹配合成模式，直接读 8 字节值
4. **严格 `>` 语义**：`InhabitedTime == threshold` 的区块被移除（保留），`threshold=0` 移除非活跃区块
5. **错误容错**：非致命错收集在报告中不中断，`strict` 模式升级为退出码 1
6. **双节流进度**：支持按区块数和按毫秒两种进度汇报频率
7. **MCA 读写缓冲**：8KiB 对齐读缓冲减少系统调用
8. **26.1+ 兼容**：递归维度发现 + 新版 `chunk_tickets.dat` 优先探测
9. **模式链而非单一策略**：`ListPattern` + `InhabitedTimePattern` 链式评估
