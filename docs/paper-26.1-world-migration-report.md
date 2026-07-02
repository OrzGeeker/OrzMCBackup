# Paper 26.1 世界目录结构迁移报告

> 记录时间：2026-07-02
> 迁移版本：Paper 26.1.2
> 数据状态：迁移完成（首次启动后）

---

## 目录

- [1. 迁移概览](#1-迁移概览)
- [2. 迁移前：旧版目录结构（Paper 26.1 之前）](#2-迁移前旧版目录结构paper-261-之前)
- [3. 迁移后：新版目录结构（Paper 26.1+）](#3-迁移后新版目录结构paper-261)
- [4. 变化明细](#4-变化明细)
- [5. 🧹 可清理的遗留垃圾](#5--可清理的遗留垃圾)
- [6. 🐛 迁移 Bug 与注意事项](#6--迁移-bug-与注意事项)
- [7. 命名空间对照](#7-命名空间对照)
- [8. 备份插件兼容性](#8-备份插件兼容性)
- [9. 新旧 Paper 与 Vanilla 三方对比参考](#9-新旧-paper-与-vanilla-三方对比参考)

---

## 1. 迁移概览

### 什么是 Paper 26.1 的世界目录迁移

Paper 26.1 废弃了旧版 Bukkit/CraftBukkit 继承的三目录平铺结构（`world/` + `world_nether/` + `world_the_end/`），改为与 Vanilla 原版一致的**单目录嵌套维度结构**（`world/dimensions/minecraft/{overworld,the_nether,the_end}/`）。

### 总体变化一览

| 项目 | 迁移前 | 迁移后 |
|------|--------|--------|
| **独立世界目录数** | 3 个 | 1 个 `world/` |
| **服务端根目录** | `world/` + `world_nether/` + `world_the_end/` | 只有 `world/` |
| **总大小** | ~2.0 GB | ~2.1 GB |
| **`level.dat` 格式** | 旧版 NBT（~1.5 KB） | gzip NBT（466 bytes） |
| **维度目录名** | `DIM-1` / `DIM1` | `the_nether` / `the_end` |

### 维度对应关系

```
旧版                                新版
──────────────────────────────────────────────────
world/                            → world/dimensions/minecraft/overworld/
world_nether/DIM-1/entities       → world/dimensions/minecraft/the_nether/entities
world_nether/DIM-1/region         → world/dimensions/minecraft/the_nether/region
world_nether/DIM-1/poi            → world/dimensions/minecraft/the_nether/poi
world_the_end/DIM1/entities       → world/dimensions/minecraft/the_end/entities
world_the_end/DIM1/region         → world/dimensions/minecraft/the_end/region
world_the_end/DIM1/poi            → world/dimensions/minecraft/the_end/poi
```

---

## 2. 迁移前：旧版目录结构（Paper 26.1 之前）

### 总览

服务端根目录 `run/` 下有**三个独立的世界目录**。

| 目录 | 大小 | 对应维度 |
|------|------|----------|
| `world/` | **1.7 GB** | Overworld |
| `world_nether/` | **235 MB** | Nether (DIM-1) |
| `world_the_end/` | **65 MB** | The End (DIM1) |

### `run/world/` — 主世界

```
world/
├── .thanos                  (129 bytes)  插件优化标记
├── death-chests.yml         (11 bytes)   插件配置
├── level.dat                (1.5 KB)     主世界核心数据
├── level.dat_old            (1.5 KB)     level.dat 自动备份
├── paper-world.yml                        Paper 世界配置
├── uid.dat                                世界 UUID
│
├── region/                  (1.4 GB, 5,345 个 .mca)
├── entities/                (205 MB, 4,391 个 .mca)
├── poi/                     (46 MB,  3,456 个 .mca)
│
├── data/                    (9.9 MB, 1,996 个文件)
│   ├── chunks.dat
│   ├── idcounts.dat
│   ├── Mansion_index.dat
│   ├── map_0.dat ~ map_*.dat
│   └── ...
│
├── playerdata/              (8.4 MB, 1,760 个 .dat)
│   ├── <UUID>.dat
│   └── <UUID>.dat_old
│
├── stats/                   (8.0 MB, 1,162 个 .json)
├── advancements/            (19 MB,  1,162 个 .json)
├── generated/               (404 KB)
│   └── minecraft/structures/
│
└── datapacks/
    └── bukkit/

注意：根目录有 233 个零字节 level*.dat 孤立文件（垃圾残留）。
```

### `run/world_nether/` — 下界

```
world_nether/
└── DIM-1/
    ├── bluemap.id
    ├── region/              (469 个 .mca)
    ├── entities/            (351 个 .mca)
    ├── poi/                 (51 个 .mca)
    └── data/
        ├── chunks.dat
        ├── Fortress_index.dat
        ├── raids_nether.dat
        ├── raids.dat
        └── world_border.dat
```

### `run/world_the_end/` — 末地

```
world_the_end/
└── DIM1/
    ├── bluemap.id
    ├── region/              (1,517 个 .mca)
    ├── entities/            (529 个 .mca)
    ├── poi/                 (114 个 .mca)
    └── data/
        ├── chunks.dat
        ├── EndCity_index.dat
        ├── raids_end.dat
        └── world_border.dat
```

### 旧版结构关键特征

1. **三个平级目录**：`world/`、`world_nether/`、`world_the_end/` 在 `run/` 下各自独立
2. **维度目录名使用旧格式**：下界叫 `DIM-1`，末地叫 `DIM1`（Bukkit 遗产）
3. **每个维度下有自己的 `region/`、`entities/`、`poi/`** 三件套
4. **元数据只存在于主世界**：`level.dat`、`playerdata/`、`stats/`、`advancements/`、`data/` 全部归 `world/`
5. **孤立零字节 `level*.dat` 文件 ×233**：历史残留垃圾

---

## 3. 迁移后：新版目录结构（Paper 26.1+）

```
run/
└── world/                              2.1 GB  ← 唯一世界目录
    ├── level.dat                       (466 bytes, gzip NBT)
    ├── level.dat_old
    ├── session.lock                    ← 新增
    ├── .thanos
    ├── death-chests.yml
    │
    ├── data/                           ← 世界级全局数据
    │   └── minecraft/
    │       ├── custom_boss_events.dat
    │       ├── random_sequences.dat
    │       ├── scoreboard.dat
    │       ├── stopwatches.dat
    │       ├── world_clocks.dat
    │       └── maps/                   ← 原 data/map_*.dat 移入此处
    │
    ├── players/                        ← 合并 playerdata/stats/advancements
    │   ├── advancements/    (1162 文件)
    │   ├── data/            (1762 文件)
    │   └── stats/           (1162 文件)
    │
    ├── generated/                       ← 未变
    │   └── minecraft/structures/
    │
    ├── datapacks/bukkit/                ← 未变
    │
    └── dimensions/                     ← 核心变化
        └── minecraft/
            ├── overworld/              1.7 GB
            │   ├── paper-world.yml     ← 每个维度一份
            │   ├── region/      5,345  .mca
            │   ├── entities/    4,391  .mca
            │   ├── poi/         3,456  .mca
            │   └── data/
            │       ├── minecraft/      ← 维度世界数据
            │       └── paper/          ← Paper 专用元数据
            │
            ├── the_nether/             236 MB
            │   ├── paper-world.yml
            │   ├── region/       469  .mca
            │   ├── entities/     351  .mca
            │   ├── poi/           51  .mca
            │   └── data/
            │       ├── minecraft/
            │       └── paper/
            │
            └── the_end/                65 MB
                ├── paper-world.yml
                ├── region/       1,517 .mca
                ├── entities/      529 .mca
                ├── poi/           114 .mca
                └── data/
                    ├── minecraft/      ← 含 ender_dragon_fight.dat 专属
                    └── paper/
```

### 每个维度的 `data/` 文件详情

#### `data/minecraft/`（新版 Paper = Vanilla）

| 文件 | Overworld | Nether | The End |
|------|-----------|--------|---------|
| `world_gen_settings.dat` | ✅ | ✅ | ✅ |
| `game_rules.dat` | ✅ | ✅ | ✅ |
| `weather.dat` | ✅ | ✅ | ✅ |
| `world_border.dat` | ✅ | ✅ | ✅ |
| `world_clocks.dat` | ✅ | ✅ | ✅ |
| `chunk_tickets.dat` | ✅ | ✅ | ✅ |
| `raids.dat` | ✅ | ✅ | ✅ |
| `scheduled_events.dat` | ✅ | ✅ | ✅ |
| `wandering_trader.dat` | ✅ | ❌ | ❌ |
| `ender_dragon_fight.dat` | ❌ | ❌ | ✅ |

#### `data/paper/`（Paper 特有 ⚠️ Vanilla 无）

| 文件 | Overworld | Nether | The End |
|------|-----------|--------|---------|
| `level_overrides.dat` | ✅ | ✅ | ✅ |
| `metadata.dat` | ✅ | ✅ | ✅ |
| `persistent_data_container.dat` | ✅ | ✅ | ✅ |

---

## 4. 变化明细

### 4.1 ✅ 正确迁移的数据

| 数据 | 旧位置 | 新位置 | 完整性 |
|------|--------|--------|--------|
| Overworld region | `world/region/` | `dimensions/…/overworld/region/` | ✅ 5,345 文件不变 |
| Overworld entities | `world/entities/` | `dimensions/…/overworld/entities/` | ✅ 4,391 文件不变 |
| Overworld poi | `world/poi/` | `dimensions/…/overworld/poi/` | ✅ 3,456 文件不变 |
| Nether region | `world_nether/DIM-1/region/` | `dimensions/…/the_nether/region/` | ✅ 469 文件不变 |
| Nether entities | `world_nether/DIM-1/entities/` | `dimensions/…/the_nether/entities/` | ✅ 351 文件不变 |
| Nether poi | `world_nether/DIM-1/poi/` | `dimensions/…/the_nether/poi/` | ✅ 51 文件不变 |
| End region | `world_the_end/DIM1/region/` | `dimensions/…/the_end/region/` | ✅ 1,517 文件不变 |
| End entities | `world_the_end/DIM1/entities/` | `dimensions/…/the_end/entities/` | ✅ 529 文件不变 |
| End poi | `world_the_end/DIM1/poi/` | `dimensions/…/the_end/poi/` | ✅ 114 文件不变 |
| Player stats | `world/stats/` | `players/stats/` | ✅ 1,162 文件 |
| Advancements | `world/advancements/` | `players/advancements/` | ✅ 1,162 文件 |
| Player data | `world/playerdata/` | `players/data/` | ✅ 1,762 文件 |
| Map data | `world/data/map_*.dat` | `data/minecraft/maps/*.dat` | ✅ |
| `.thanos` | `world/.thanos` | 原位 | ✅ |
| `death-chests.yml` | `world/death-chests.yml` | 原位 | ✅ |
| `datapacks/bukkit` | `world/datapacks/bukkit` | 未变 | ✅ |

### 4.2 🔄 结构性变化

| 变化 | 旧 | 新 |
|------|----|----|
| **维度目录名** | `DIM-1` / `DIM1`（数字命名空间） | `the_nether` / `the_end`（命名化） |
| **玩家数据布局** | `playerdata/` + `stats/` + `advancements/` 三个平级目录 | 统一归入 `players/{advancements,data,stats}/` |
| **维度元数据** | `world_nether/DIM-1/data/` 和 `world_the_end/DIM1/data/` 各自独立 | 统一到 `dimensions/…/{overworld,the_nether,the_end}/data/` |
| **元数据命名空间** | 无（直接放文件） | `data/minecraft/` + `data/paper/` 两层命名空间 |
| **`level.dat` 格式** | 旧版 NBT（~1.5 KB） | 新版 gzip NBT（466 bytes），更紧凑 |
| **`paper-world.yml`** | 只在 `world/` 根目录 | 每个维度各一份 |

### 4.3 ⚠️ 迁移中清理消失的数据

| 数据 | 位置 | 说明 |
|------|------|------|
| `uid.dat` | `world/uid.dat` | **已删除** — Paper 26.1 不再需要 |
| `paper-world.yml` | `world/paper-world.yml` | 被各维度的独立版本替代 |
| `Mansion_index.dat` | `world/data/` | 已移除（功能合并到 chunk 数据） |
| `Fortress_index.dat` | `world_nether/DIM-1/data/` | 已移除 |
| `EndCity_index.dat` | `world_the_end/DIM1/data/` | 已移除 |
| `raids_nether.dat` / `raids_end.dat` | 旧各维度 data/ | 合并到 `data/minecraft/raids.dat` |
| `bluemap.id` | `world_nether/DIM-1/` + `world_the_end/DIM1/` | **已丢失** — BlueMap 需重新生成 |
| `idcounts.dat` | `world/data/` | 已移除 |
| `chunks.dat` | 旧各维度 data/ | 已移除（功能合并） |
| `world_nether/` | 独立目录 | **整目录删除** |
| `world_the_end/` | 独立目录 | **整目录删除** |

---

## 5. 🧹 可清理的遗留垃圾

以下文件在迁移后**安全可清理**，不影响服务端正常运行。删除前建议先确认文件内容。

| 项目 | 位置 | 数量 | 大小 | 清理理由 |
|------|------|------|------|----------|
| `level*.dat` 零字节文件 | `world/` 根目录 | **233 个** | 0 KB | 旧版 Paper 自动备份机制遗留（每次保存创建一个随机后缀 `.dat` 文件），全部零字节且无实际内容，新版 Paper 不再使用此机制。清理命令：`find world/ -name 'level*.dat' -size 0 -delete` |
| `paper-world.yml`（旧版） | `world/` 根目录 | **1 个** | ~1 KB | 迁移后每个维度已有独立配置，根目录旧版不再被读取 |
| `uid.dat` | `world/` 根目录 | **1 个** | ~几十 bytes | 新版 Paper 已废弃此文件，迁移过程通常自动删除，若残留可安全删除 |

---

## 6. 🐛 迁移 Bug 与注意事项

### 迁移机制：增量多步完成

Paper 26.1 的世界迁移是**多次重启增量完成**的：

| 重启次数 | 处理内容 | 迁移路径 |
|----------|---------|----------|
| 第 1 次 | Overworld | `VanillaWorldMigration` ✅ |
| 第 2 次 | Nether | `VanillaWorldMigration` ✅ |
| 第 3 次 | The End | `LegacyCraftBukkitWorldMigration` ✅ |

每次重启处理一个旧版世界目录，三次后全部迁移完成，不再需要迁移。

### 本次遇到的具体问题

首次启动时，overworld 和 nether 迁移成功，但 **the_end** 的 `LegacyCraftBukkitWorldMigration` 路径未能生成 `world_gen_settings.dat`，导致 crash：

```
java.lang.IllegalStateException: Unable to read or access the world gen settings file
for dimension minecraft:the_end. ./world/dimensions/minecraft/the_end/data/minecraft/world_gen_settings.dat
```

**原因**：旧版 `world_the_end/DIM1/data/` 目录在迁移前已经为空/缺失，Paper 的 CraftBukkit 迁移代码无法从空目录推导出 `world_gen_settings.dat`。

**标准解法**：只需**再重启 2 次**，Paper 会自动重试并完成完整迁移（三数据正常启动三次还是三次重启）。

**已执行的修复**：手动从 nether 复制了 `world_gen_settings.dat` 到 the_end 对应位置（overworld/nether/end 三维度此文件内容一致，均为全局生成配置），跳过了后续重启步骤，不影响任何功能。

---

## 7. 命名空间对照

| Minecraft 概念 | 旧版路径 | 新版路径 |
|----------------|---------|---------|
| Overworld region | `world/region/r.*.mca` | `dimensions/minecraft/overworld/region/r.*.mca` |
| Nether region | `world_nether/DIM-1/region/r.*.mca` | `dimensions/minecraft/the_nether/region/r.*.mca` |
| End region | `world_the_end/DIM1/region/r.*.mca` | `dimensions/minecraft/the_end/region/r.*.mca` |
| Overworld data | `world/data/*.dat` | `data/minecraft/*.dat`（全局）+ `dimensions/…/overworld/data/minecraft/*.dat`（维度级） |
| Player UUID data | `world/playerdata/<UUID>.dat` | `players/data/<UUID>.dat` |
| Player stats | `world/stats/<UUID>.json` | `players/stats/<UUID>.json` |
| Advancements | `world/advancements/<UUID>.json` | `players/advancements/<UUID>.json` |

---

## 8. 备份插件兼容性

### backup-core（WorldMaintenanceService）

备份插件的 `Optimizer.discoverDimensions()` 逻辑与新版结构兼容，无需修改：

1. `world/` 根目录没有 `region/` 子目录 → 不是 dimension dir ✅
2. 递归 `walk(world)` 找到 `world/dimensions/minecraft/overworld/region/` → 发现 overworld ✅
3. 同理发现 `the_nether/` 和 `the_end/` ✅
4. `discoverMiscParents()` 从嵌套维度向上走到 `world/` → 收集 `level.dat`、`players/`、`data/`、`generated/` 等 misc ✅
5. `excludePaths` 防止维度内部文件被双重复制 ✅

**数据文件数验证**：各维度 region/entities/poi 的 .mca 数量在迁移前后完全一致。

---

## 9. 新旧 Paper 与 Vanilla 三方对比参考

| 维度 | 项目 | 旧版 Paper | 新版 Paper | Vanilla |
|------|------|-----------|-----------|---------|
| **全局** | 世界目录数 | 3 | 1 | 1 |
| **全局** | 维度嵌套结构 | ❌ 无 | ✅ `dimensions/minecraft/{name}/` | ✅ 同 |
| **全局** | 维度名 | `DIM-1` / `DIM1` | `the_nether` / `the_end` | ✅ 同 |
| **全局** | `session.lock` | ❌ | ✅ | ✅ |
| **全局** | `uid.dat` | ✅ | ❌ | ❌ |
| **全局** | `data/paper/` | ❌ | ✅ **Paper 特有** | ❌ |
| **Overworld** | `region/entities/poi` | `world/` 根目录 | `dimensions/.../overworld/` | ✅ 同 |
| **Overworld** | 玩家数据 | `playerdata/` + `stats/` + `advancements/` | `players/{data,stats,advancements}/` | ✅ 同 |
| **Overworld** | map 数据 | `data/map_*.dat` | `data/minecraft/maps/*.dat` | ✅ 同 |
| **All** | `data/minecraft/` 命名空间 | ❌ 扁平堆放 | ✅ | ✅ |
| **All** | `paper-world.yml` | `world/` 根目录 | 每个维度各一份 | ❌ |
| **N/A** | `level*.dat` 零字节垃圾 | ✅ 233个 | ✅ 233个未清理 | ❌ |

### 一句话结论

> **新版 Paper 26.1+ 的世界目录结构与 Vanilla 完全一致**，唯一差异是每个维度下多了 `data/paper/` 目录（包含 3 个 Paper 专用元数据文件）和 `paper-world.yml` 配置文件。旧版 Paper 的三目录平铺 + `DIM-1`/`DIM1` 命名是 Bukkit/CraftBukkit 的历史遗产，自 Paper 26.1 起正式废弃。

---

*报告结束。建议清理 `level*.dat` 零字节垃圾文件（233 个）。*
