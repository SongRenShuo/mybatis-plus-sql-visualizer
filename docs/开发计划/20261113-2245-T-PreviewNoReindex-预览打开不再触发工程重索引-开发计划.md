# T-PreviewNoReindex：预览打开不再触发工程重索引（dumb mode）

- 立项：2026-11-13 22:45
- 任务源：用户首测日志（2026-11-13 22:20~22:22 默认沙盒）显示**每次打开预览都会 `enter dumb mode`**；用户令「走」
- 关联计划：`20261113-0839-T-StartupReadLockFreeze…` §二十二（预览链全部读数）、`20261113-2152-T-PreviewAssemblyWarm…` §八（用户首测）

## 一、现象与证据（可复现，4/4）

```
22:21:02,184  [M36] exec file updated via document+save
22:21:03,265  #c.i.o.p.DumbServiceImpl - enter dumb mode [mybatis-plus-sql-visualizer-demo]
22:21:03,280  [M36] dialect pre-mapped to MySQL for mpsql-exec.sql
22:21:03,573  [M36][O-145] session created
22:21:04,644  [M36] attachConsole -> {}
22:21:05,049  [T-PreviewFast] request→console-visible: 4619ms
```

同一会话四次预览，四次都出现 `enter dumb mode`（22:21:03 / :15 / :42 / :51），紧跟我们的
「exec 落盘 + 方言映射」。后果不止那 4.6s：**每次预览后索引相关能力（含本插件自己的反查/补全/候选预热）
都会短暂失效**，也解释了控制台 392/1053/316ms 的抖动。

三段成本归属（同一窗口）：

| 段 | 量级 | 归属 |
|---|---|---|
| `enter dumb mode` + 重解析 | 0.3~1.5s | **我们的写盘/方言映射** ← 本任务 |
| `attachConsole`（平台建 DbConsole） | ~1.07s | 平台，不碰 |
| JPS `BUILDER_PROCESS` | 785ms | 引擎建模型，不碰 |

## 二、关键未知（必须先取证，别按直觉改）

预览临时文件按设计**每次换名**（`mpsql-preview-<pid>-<seq>.sql`，见 `SqlPreviewPanel` 的 KDoc：固定名会撞
`MemoryDiskConflictResolver` 的模态冲突——**这条不能退回去**），落在 `.idea/sql-visualizer/preview/`；
方言映射按**父目录**做（`SqlPreviewPanel.nextPreviewTempFileName` 附近的 KDoc 明确：按文件映射会
「每轮白付一次 setMapping（`SqlDialectPusher` push all dumb）」）。

那么 `enter dumb mode` 到底是谁触发的？两个候选：

- **C1 文件新增**：新临时文件写入 → VFS 事件 → pushed properties（`.idea/` 理论上被平台排除索引，待证）。
- **C2 方言映射写入**：`DialectPreMapping.ensureMapped` 若真的写了映射，`SqlDialectPusher` 会 push-all →
  dumb。当前日志**无法区分**「跳过」与「真写」——`DataSourceSelector.kt:2416` 在 `ensureMapped` 返回 true
  时一律打 `dialect pre-mapped`，而 true 同时代表「已相等跳过」和「写成功」。

## 三、方案（两步，先证后改）

**S0 取证（本任务第一步，只加埋点不改行为）**
1. 把方言映射日志分成两态：`dialect mapping: skipped(already=<id>)` / `dialect mapping: wrote <id>`
   （`ensureMapped` 返回「跳过/写入」两值或用一次性计数透出）。
2. 预览文件建立/复用分支各记一行：`preview temp: created <name>` / `preview temp: reused <name>`。
3. 沙盒跑 3~4 次预览，看 `enter dumb mode` 与哪条同拍出现。

**S1 按取证结果改**（候选，取其一或组合）
- 若 C2 成立：让「已映射」成为**真正短路**——`configuredLanguageId` 探测失败（null）时**不写**（当前是
  「保守照写」，代价是每次 push-all dumb），改为「沿用上一次成功的映射结果」并把探测失败记一行；
  另把「目录映射」结果按项目缓存（目录 vf 稳定），避免每轮重复 setMapping。
- 若 C1 成立：让预览目录**显式排除索引**（`ProjectFileIndex` 的 excluded 语义），或复用「已存在且无 document」
  的旧文件（`projectPreviewFile` 已有 existing 分支）——注意**不能退回固定名**（模态冲突）。

## 四、验收

- **A1**：沙盒连开 4 次预览，日志里 `enter dumb mode` 出现 **0 次**（改前 4/4）。
- **A2**：`request→console-visible` 首次/后续分别下降（改前 4619 / 392·1053·316ms），不做硬性目标。
- **A3**：预览功能零回归——SQL 正确、方言正确、执行/复制/参数面板不变；`MemoryDiskConflictResolver`
  模态冲突**不得复现**（这是临时文件换名设计的初衷）。
- **A4**：工程重建索引后（平台自身的 dumb 期），预览链照常降级不报错。

## 五、风险

- 改「映射跳过条件」可能让某类方言路由在首轮不生效（探测失败 → 不写 → 语言停留在推断值）。缓解：探测
  失败时保留一次写，但把「push-all dumb」成本挪到**预览打开之前**（文件打开时预热映射），并与 A3 一起验。
- 改临时文件落点/排除语义会碰 inlay 作用域锚点（`PreviewInlayScope` 按文件名形态判定）与
  `PreviewUnknownColumnAnnotator`——两者都按名字形态匹配，改名不受影响，但**落点变化**要让它们的
  作用域解析跟着改。

## 六、实施记录

待实施。落地后回填：取证读数（谁触发 dumb）、改动与提交哈希、定向用例、沙盒 A1~A4、not run 清单。