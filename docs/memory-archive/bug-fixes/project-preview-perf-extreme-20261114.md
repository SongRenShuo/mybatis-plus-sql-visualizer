---
name: project-preview-perf-extreme-20261114
description: 2026-11-13 晚~14 凌晨「性能做到极致」整轮台账——预览打开从 4190/5119ms 压到稳定态 55~75ms、表名反查 3737~4737ms 压到 0~34ms、索引规模 8688 表/16804 类降到 812~1044 表、注解索引重建 4737ms→187ms；含逐笔提交、真机读数、以及「首开 695ms 里装配只 9ms、剩下是引擎 EDT 排队」的诚实结论与剩余项（console-visible 4.6s 里 1.07s 是平台建 DbConsole）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4043a4bb-4569-4ef9-8e28-84e10cd22b20
---

承接 [[project-startup-read-lock-freeze-20261113]]：冻结与首开修完后，用户继续「打开预览面板还是太慢」，
又追「全在后台线程会不会导致 IDEA 卡顿」「dumb 的问题无法解决掉吗」「性能控制在极致」。

## 逐笔提交（master，均已推双仓）

| 提交 | 内容 |
|---|---|
| `c96759946` | 表名反查「查一个表名扫一遍」→ **一次扫描建全表索引**；失效基准由 `PsiModificationTracker` 改「项目结构版本 + TTL(5min)」（我们自己的 exec/临时文档写入会顶掉旧判据 ⇒ 每次打开都重扫） |
| `e99750f53` | 建索引挪出调用方线程（`ReadAction.nonBlocking{}.inSmartMode(project).expireWith(project).submit(AppExecutorUtil…)`），调用方**至多等 800ms**，超预算降级、下次命中 |
| `59e58ab93` | **EDT 一律不等**（`isDispatchThread` 只派发）；不吞 `ProcessCanceledException`（吞掉会让非阻塞读动作退化成不可撤销长读者）；撤销后 10s 冷却；`expireWith(project)` |
| `47634d4c5` | `@TableName` 兜底反查把 FQN 传给注解索引 ⇒ 恒空（见 [[pitfall-annotation-index-simple-name-and-qualifiedname]]） |
| `ad292a169` | 实体解析缓存「TTL 内一律命中 \|\| stamp 未变」是**伪缓存**（改了注解不生效）→ 改 `clazz.text.hashCode()` 内容指纹 |
| `a02ad51cf` | 面板 dispose 兜底释放预览编辑器与 autopopup 监听（退出 IDE 的 4 条 ObjectTree 泄漏） |
| `589be7dcd` | 用户首测日志里三处浪费（见 [[pitfall-warm-fanout-and-index-entry-churn]]） |
| `ee8c69e9a` | 反查候选源换**平台注解索引** + 联表候选改「缓存优先」（见下） |
| `231cd2065` | 表名注解按 ORM 分源（MP `@TableName` + JPA `javax/jakarta @Table`）+ 未命中回补全项目推断 |
| `cc6745550` / `02bccd045` | exec 文件方言映射改父目录 / **惰性控制台**（见 [[pitfall-mapped-exec-file-write-triggers-dumb]]） |

## 关键读数（沙盒 cust-big-demo，16804 类）

- 表名反查 `resolve=` 段：**3737/4737ms → 0~34ms**；`request→dialog`：**4190/5119ms → 首次 501ms、稳定态 8/20/32/254ms**。
- 反查索引：`tables=8688`（全表推断）→ `812`（仅 MP 注解）→ 加 JPA 源 `1037/1044`；**稳态建成 4737ms → 187ms**；
  冷态首趟仍 ~5.1s（JIT/索引加载，在后台）。
- 联表候选：冷态首次 **374ms → 0~2ms**（缓存优先 + 后台 warm）。
- 用户验收会话（多模块 demo，IU-2024.3.7.1 沙盒）：`request→dialog` 1256（冷）/ **14 / 21 / 465ms**；
  `construct` 冷 256~386ms vs 热 15~26ms；`console-visible` 4613（首次）/ 337 / 370 / 706ms。

## 两个诚实结论（别把它写成「修好了」）

1. **首开 695ms 里装配只 9ms**（`[open-perf] total=9ms`），其余是引擎侧 EDT 排队（启动后 JPS 收尾）⇒
   「装配预热」方案对本会话首开**收益不成立**（计划 §四 P-1 的证伪条件成立），价值只在引擎忙完后的点击。
2. **`console-visible` 首次 4.6s 的构成**：我们的 exec 写+dumb ~0.8~1.3s（已可消）、平台建 DbConsole
   `attachConsole` ~1.07s、JPS 建模型 785ms（都不是我们能改的）。

## 剩余 / 待办

- 惰性控制台下「预览打开时右侧控制台区域为空」的观感取舍待用户拍板；折中方案＝照样准备控制台但不写文件内容。
- `warmCandidates cached=0` 在「索引尚未就绪时预热」会出现（候选链既有行为，下一次 `refreshCompletionContext` 补）。
- 冷 `EntityResolver.resolve` 一次 50~190ms（之后 0~1ms）；想再压得把实体解析预热到文件打开时，收益已很小。

计划文件：`docs/开发计划/20261113-0839-T-StartupReadLockFreeze…`（§二十二 22.1~22.15）、
`20261113-2152-T-PreviewAssemblyWarm…`、`20261113-2245-T-PreviewNoReindex…`、
`20261114-0020-T-PreviewExecWriteDefer…`。
