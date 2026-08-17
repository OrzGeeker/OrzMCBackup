# PaperMC 地图备份恢复：全量 + 优化备份的槽位级合并典型场景案例

> 记录时间：2026-08-17
>
> 场景：PaperMC 26.2 服务器，使用 OrzMCBackup 的"地图备份优化"（InhabitedTime 阈值）后，需要把优化备份与更早的全量备份合并，恢复"全量最新"地图。
>
> 相关代码：`merge` 子命令（`WorldMerger.kt` / `MergeCommand.kt`）；一次性原型脚本 `merge_worlds.py`（纯 stdlib Python，等价实现，便于离线复跑）。

---

## 1. 场景背景

**问题**：优化备份按 InhabitedTime 阈值逐区块裁剪，会删除低活跃区块。如果后续只有"优化备份 + 更早的全量备份"两份数据，且优化备份对应的那期全量备份已丢失，那么直接用优化备份的 region 文件**同名覆盖**全量备份，会把部分占用的 region 文件直接盖掉完整 region 文件，导致大量 chunk 槽位变空 → 地图出现"空洞/地形回退"。

**本案例的真实数据**：

| 目录 | 来源 | 大小 | 文件数 | 说明 |
|------|------|------|--------|------|
| `world` | 08-12 全量备份 | 18.64 GB | 28,451 | 所有 chunk 完整 |
| `world_backup` | 08-15 全量备份经 OrzMCBackup 优化 | 2.18 GB | 10,768 | 仅保留 InhabitedTime > 5 分钟的 chunk |
| `world_recovered` | 合并结果 | ~18 GB | 29,044 | 08-15 活跃 chunk + 08-12 其余全部 |

**数据丢失边界（必须向用户说明）**：08-12 到 08-15 之间、玩家累计活跃 ≤ 5 分钟的 chunk（含其中的改动、低活跃建筑）在优化备份中已被丢弃，只能回退到 08-12 版本。这是优化备份的固有限制，任何合并方案都无法还原这些 chunk 的 08-15 数据。

---

## 2. 核心知识点

### 2.1 Anvil region 文件（.mca）格式

- **8 KiB 头部**：前 4 KiB 是位置表（1024 个槽位 × 4 字节 = 3 字节扇区偏移 + 1 字节扇区长度），后 4 KiB 是时间戳表（1024 × 4 字节）。
- **载荷**：每个 chunk 从扇区（4 KiB）边界开始，内容是 `4 字节长度 + 1 字节压缩类型 + NBT 数据`。
- **空槽位**：位置表三项偏移全为 0 表示该槽位无 chunk。优化/合并器对"被剔除的槽位"就是置 0 偏移。
- **压缩类型**：RAW(3)/ZLIB(2)/GZIP(1)/LZ4(4)；`McaEntry` 负责解压，`McaWriter` 按原始字节复制时零损耗。

### 2.2 Paper 26.1+ 世界目录结构（嵌套）

```
world/
├─ level.dat / players/ / data/ / datapacks/ / generated/ ...   # 世界级杂项
└─ dimensions/minecraft/
   ├─ overworld/region|entities|poi/*.mca
   ├─ the_nether/region|entities|poi/*.mca
   └─ the_end/region|entities|poi/*.mca
```

杂项文件（`level.dat` 等）按 08-15 更新版覆盖；只有 region/entities/poi 下的 `.mca` 需要槽位级合并。

### 2.3 OrzMCBackup 优化的语义

- 对每个 chunk 评估 `InhabitedTime`（玩家累计活跃 tick，`>` 阈值保留，1 秒 = 20 tick）。
- **被剔除的槽位**在重写后的 region 文件中置 0 偏移；若某 region 全部被剔除，则**不生成该 `.mca` 文件**（惰性写入器）。
- entities/poi 与 region 按同一决策**锁步写入**：保留的 chunk 带其 entities/poi，剔除则无。
- 因此优化备份中"存在的 region 文件"其保留槽位在语义上是 08-15 的最新状态。

### 2.4 槽位级合并算法

对每个同时存在于 base 与 patch 的 region 文件，逐槽位（0..1023）决定来源：

```
槽位来源 = patch 有该槽 ? patch（08-15，更新） : base 有该槽 ? base（08-12） : 空
```

- region 只在 base → 原样复制 base（含其 entities/poi）。
- region 只在 patch → 复制 patch 的 region + 其 entities/poi 同名兄弟文件。
- **entities/poi 锁步**：槽位来源为 patch → 取 patch 的 entities/poi 该槽（无则空，即 08-15 该 chunk 无实体）；来源为 base → 取 base 对应槽。**绝不能直接复制 base 的 entities/poi**，否则 patch 来源槽会残留 08-12 旧实体。
- 合并后全空的 entities/poi 文件**不生成**（与 MC 约定一致，也避免 08-12 空文件残留）。

### 2.5 目录互斥校验（alias guard）

base/patch/output 三个目录必须互不重叠：`overlaps()` 对绝对化归一化后的路径做相等/前缀包含判定，任一两两重叠（如 `output == base` 加 `--force`）都会先于任何写入被拒绝，避免源世界被误覆盖。

### 2.6 损坏 patch 回退

若 patch 的 region 文件损坏（无法打开/解析），**不丢弃 base 数据**：base 对应 region 及其 entities/poi 会按字节复制到输出（`copyBaseIfPresent`），并在报告中记录 MCA 错误。同时，为跳过冗余 base→out 复制（见 3.2）的公共 .mca 文件，其 base 副本必须由该回退路径恢复，保证任何损坏情形下输出都完整。

---

## 3. 处理方法

### 3.1 为什么不能同名文件覆盖

同名覆盖是用户最初的失败尝试：把部分占用的优化 region 直接盖掉完整 region，未覆盖的槽位在位置表里变 0，游戏按"未生成区块"重新生成 → 空洞/地形回退。合并必须以 **chunk 槽位粒度** 进行。

### 3.2 推荐工作流

```bash
# 构建
./gradlew :app:shadowJar --no-daemon
# 合并（BASE=08-12 全量，PATCH=08-15 优化，OUTPUT=恢复结果）
java -jar app/build/libs/backup.jar merge E:\recover\world E:\recover\world_backup E:\recover\world_recovered \
  --report --progress-mode Global
```

合并过程：① 复制 base → output 保住 base 全部 chunk（**跳过 patch 也存在的 `region/entities/poi/*.mca` 的冗余复制**，这些文件会在③由合并结果重写）；② 逐文件覆盖 patch：region 槽位级合并（可 `--parallelism N` 并行，输出仍确定）、entities/poi 锁步、杂项文件覆盖；③ 删除 `session.lock`；④ 输出 `MergeReport`。

### 3.3 一次性原型脚本

`merge_worlds.py` 用纯 stdlib 实现同一算法，适合在无构建环境的机器上离线复跑（`--verify` 附带槽位数复核）。

---

## 4. 对比验收方法

### 4.1 统计对齐

对比脚本输出与合并报告的 `MergeReport`：

| 指标 | 含义 |
|------|------|
| `mergedRegions` | 同时存在于 base/patch、做了槽位级合并的 region 数 |
| `patchSlots` / `baseSlots` | 取自 patch（08-15）与 base（08-12）的槽位数 |
| `linkedEntities` / `linkedPoi` | 锁步写入的 entities/poi 条目数 |
| `copiedFiles` / `overlayFiles` | patch 独有的 region 文件 / 覆盖的杂项文件 |
| `errors` | 错误数，验收要求为 0 |

### 4.2 槽位数复核（防空洞核心检查）

对每个共同 region，输出槽位数必须 = `|patch ∪ base|`（并集），且 patch 槽位取新、空槽位被 base 填满。本案例中此前出空洞的 region 全部恢复：

| region | 优化备份中 | 合并后 |
|--------|-----------|--------|
| `r.-1.-15.mca` | 18/1024 | 1024/1024 |
| `r.-1.-10.mca` | 274/1024 | 1009/1024（= base 全集） |
| `r.-1.-21.mca` | 27/1024 | 1024/1024 |

### 4.3 与已验证输出逐字节比对

对两份独立实现（Python 原型 vs 插件 `merge`）的输出做 .mca 级比对：
- 共同存在的文件：**逐槽位比对 payload 原始字节与时间戳**，要求完全一致。
- 文件清单差异：只允许"空文件（0 chunk）与缺失文件"等价差异——插件按"全空不生成"约定删除了空 entities/poi 文件，游戏内二者等价。

### 4.4 游戏内验收

服务器停止后把合并结果复制到世界目录启动：
- tp 到此前"空洞"区域确认无空洞、玩家建筑仍在；
- 检查下界/末地；
- 可选：用 Chunky/Mapcrafter 渲染合并前后对比。

---

## 5. 本案例实测结果

| 验证项 | 结果 |
|--------|------|
| 合并 region 数 | 1,244 |
| patch 槽位（08-15 新数据） | 233,021 |
| base 填充槽位（08-12） | 463,657 |
| entities / poi 锁步合并 | 98,384 / 10,022 条目 |
| 杂项覆盖 | 7,353 |
| 共同 region 槽位复核 | 10,713 个，0 不一致 |
| 插件 vs Python 输出逐槽位比对 | 零 payload/时间戳差异 |
| 报告 errors | 0 |

## 6. 结论

- 优化备份 + 更早全量备份的恢复，正确方法是 **chunk 槽位级合并**（patch 优先、base 填充、entities/poi 锁步），而非同名覆盖。
- 插件 `merge` 子命令实现了该算法，并已在真实数据上与 Python 原型输出做到逐字节一致、`errors=0`。
- 已通过统计对齐、槽位并集复核、跨实现逐字节比对三重验收；最终以游戏内加载作为上线验收。
