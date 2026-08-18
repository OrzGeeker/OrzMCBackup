# CLAUDE.md

> 本文件是 **Claude Code**（以及同样识别 `CLAUDE.md` 的工具，如 Grok / pi.dev）的项目入口。
> 跨工具共享的项目知识统一维护在 **AGENTS.md**（单一事实源）；本文件只放 Claude Code 专属内容。

## 项目知识（导入 AGENTS.md）

@AGENTS.md

## Claude Code 专属

### 可移植技能（`.agents/skills/`，Agent Skills Standard，跨工具可用）

| 技能 | 用途 |
|------|------|
| `build-cli` | 构建 CLI fat JAR（`:app:shadowJar`） |
| `test` | 模块级 / 单类 / 单方法测试 |
| `lint-fix` | ktlint / detekt 检查与修复 |
| `update-docs` | 文档维护：改 README/docs 后与实现逐项核对、更新版本 |

### 本地设置

- `.claude/settings.local.json` — 本机权限允许项（git、部分 gradle 测试命令、WebSearch），**不入库**；
  共享权限项应放入 `.claude/settings.json`
- 发布相关密钥（`CENTRAL_TOKEN`、GPG）为 GitHub Actions Secrets，本地无需配置

### 协作提示

- 需要并行/子代理任务时，用上面的可移植技能按模块拆分工作（core / app / docs）
- 常用命令、模块结构、设计决策、文档同步铁律见 `AGENTS.md`
