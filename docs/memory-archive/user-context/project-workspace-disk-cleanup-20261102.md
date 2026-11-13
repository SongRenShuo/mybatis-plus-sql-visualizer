---
name: project-workspace-disk-cleanup-20261102
description: 20261102 外层工作区磁盘清理——用户授权 A/B/C2-C6/D/E/F 六组，已执行完毕实回收 51GB（F 盘 22G→73G 可用），含逐项读数、保留面与三条执行期事故
metadata:
  node_type: memory
  type: project
  originSessionId: sess_a30495c3-5673-4a34-ad94-235fcd0ae1f6
---

2026-11-02 用户提出「F:\code\Java\Gitee\SongRenShuo 空间占用过多，先列表格让我确认，不要直接清理」→ 全量实测并交付分类表（构成见 [[reference-workspace-root-layout]]）→ **用户当日授权并已执行完毕**。

## 授权范围（用户原话逐组勾选）

清理 A（构建产物与缓存，**A4 的 `build/libs` 发行包留着**）+ B（录制帧）+ **C2–C6**（C1 `_ide261` 未在授权内）+ D（Chrome profile）+ E（备份）+ F（已合并旧 worktree）。G 类老旧仓库与 `_ide261` 一律未动。

## 实测回收（合计 ≈51GB）

| 项 | 路径 | 释放 |
|---|---|---|
| A1 | `-dev/build` | 13,203.8 MB |
| A2 | `-kotlin-to-java/build` | 6,798.5 MB |
| A3 | `mpsv-sql-insight/build` | 6,129.1 MB |
| A4 | 主仓 `build/`（留 `libs/`） | ≈5,019 MB |
| A5 | `.intellijPlatform/sandbox` | 1,979.4 MB |
| A6 | 四仓 `.gradle` | 706.5 MB（272.5/262.6/132.8/38.6） |
| B1/B2 | 两处 `record/perf` | 5,893.0 + 452.7 MB |
| C2–C6 | 探针目录 | 817.4 MB |
| D | `jvtools-home/.tmp-hot/accept` 61 个 profile | 10,320.8 MB |
| E | `.git-backups` 四个历史库+大日志 | 488 MB |
| F | 5 个已合并 worktree | 17 MB |

**F 盘可用 22GB → 73GB。**

## 保留面（有据可查）

- `build/libs` 18 个 jar / 111 MB（2026.10.23 至最新发行包）。
- `.git-backups` 12 个元数据文件（`orig-heads.txt`/`rel.json`/`remap-shas.py` 等，约 40KB）。
- `record/` 下 git 跟踪内容完好：主仓 172 个、dev 仓 558 个文件；只删了 `.gitignore` 忽略的 `perf/`。
- F 组 5 个 worktree 的**分支全部保留**（`t-bug-bounty-admin`/`t-bounty-budget-ui`/`dev` 等），只移目录。
- 4 个 `node_modules` junction 是先摘链接再删树，`sql-visualizer-admin/node_modules` 目标实测完好（见 [[pitfall-worktree-remove-longpath]]）。

## 三条执行期事故（都值得记住）

1. **删 `build/` 时另一个会话正在跑构建**：`parallel_tasks.py check` 报「无活跃并行任务」，但主仓同时有 11 个源码改动（T-DialectGatePassDeadChain），并在删完后 9 秒启动 `gradlew test` → 变成冷编译。判据与处置见 [[pitfall-build-dir-deletion-needs-process-check]]。
2. **`cmd /c "rd /s /q X"` 在 Git Bash 里只打印横幅不执行**（exit=0、目录原样在），必须写进 `.ps1` 才生效（见 [[pitfall-gitbash-toolchain-path-quirks]]）。
3. **`clean-bcde.ps1` 首跑 ParserError**：脚本里一行中文注释就够炸掉整个脚本（第四次同族，见 [[pitfall-ps51-nobom-chinese-parsererror]]）。

## 未了结

- `mybatis-plus-sql-visualizer/build/MPSV-GifFresh` 留 0 字节空骨架：`IU-2026.2.1/system/compile-server` 被进程占句柄，`rd /s /q` 与 `Remove-Item -Recurse` 都删不掉。等占用进程退出后补一刀即可，不占空间。
- 未清：`_ide261`（6.2G，跨版本真机还要用）、G 类老仓库 1.8G、`mpsv-sql-insight`（298 未提交）、`docs` 四树、`openget`、`mpsv-bounty-budget-docs`（ahead=1）、`sql-visualizer-recording/record/perf`（git 跟踪 42 文件）。

## 后续（同一轮对话）

F 盘清完后用户接着让分析 **C 盘**，并强调「没有我的允许 不可以删除或修改文件」——只交了分档清单、未执行任何删除。C 盘构成与可回收面见 [[reference-c-drive-space-layout]]，授权口径见 [[feedback-no-delete-or-modify-without-approval]]。

**Why:** 「先确认再清理」是用户明确要求，而执行完成后授权状态会翻转——不记下来，下一个会话要么以为还得等授权、要么以为那 51GB 还在。
**How to apply:** 用户再提清理时直接读本条与 [[reference-workspace-root-layout]] 的现状读数，别重扫；未授权项（`_ide261`、G 类仓库）继续不动。
