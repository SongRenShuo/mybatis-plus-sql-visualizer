---
name: project-module-scoped-last-used-and-log-bgt
description: T-ModuleScopedLastUsed + T-SqlLogToolbarEdt（20261024 立项并实施，沙盒 243 验收后与 T-ExplainConsoleFirst 一起合为 `6aac532a`，未 push）——「上次使用数据源」从项目级单键改三层（活控制台 > 模块级 > 项目级兜底）；日志工具栏 11 个动作逐个补 ActionUpdateThread.BGT，EDT 抢占告警 3~4s→0 条
metadata:
  node_type: memory
  type: project
  originSessionId: session_b380d49669e298cff6e310c72afca927
---

# 模块级 last-used + 日志工具栏 BGT（2026-10-24）

## 一、用户问题的正确答案：不是「全局」，是「项目级」

用户问「sql 预览窗口右上角灰色的『上次使用』的数据源应该是全局的吧，是否应该按照模块隔离更合适」。
**先纠正前提**：`DataSourceSelector` 的 last-used 落在
`PropertiesComponent.getInstance(project)`（键 `mpsql.visualizer.last.runner` / `…runner.id`），
随 `.idea/workspace.xml` —— **项目级一份**，同项目各模块共享，跨项目不共享。

真正的问题不是「太全局」，而是**两套记忆粒度不一致**：

| 机制 | 存储 | 粒度 | 消费方 |
|---|---|---|---|
| last-used 双键 | `PropertiesComponent(project)` | 项目级 | 指示行灰字、执行/EXPLAIN 静默复用 |
| `ModuleDataSourceBindingStore` | `mp-sql-visualizer-datasource.xml` | **模块级** | **只喂方言决策**（`Dialect.kt:964`） |

模块级学习早就在跑（`DataSourceSelectorConsole.kt:540` 执行成功后 `remember(moduleKey, id)`），
但**没参与「上次使用」这条更靠前的取源链路** ⇒ 在 A 模块选源，切到 B 模块灰字仍显示同一个源。

## 二、方案与硬约束

三层记忆：**活控制台绑定 > 模块级 last-used > 项目级兜底**。

- 读：模块级命中即用，**未命中回落项目级**（不能返回「无」）。
- 写：有 moduleKey 时**同时写两层**——项目级是无模块上下文入口的兜底。
- 删：单模块清理只清该层，不牵连其它模块与兜底。
- **硬约束**：moduleKey 为 null/blank 时**必须回落项目级原键**。`sourceModuleKey` 在纯 `.sql`
  文件、非 Java 源等入口为 null，硬隔离会让这些入口退化成「每次未绑定」，比改前更糟。

## 三、两处实现发现（都在计划外）

1. **`PropertiesComponent` 没有「列举全部键」的 API**——`javap` 实证只有
   `unsetValue/getValue/setValue/getValues/setValues`，无 `getAllKeys`。计划里原打算前缀扫描清
   全部模块桶，做不到 ⇒ 改由插件**自己登记模块键清单**
   （`mpsql.visualizer.last.runner.modules`，逗号分隔、写入时去重），清理时按清单精确 unset。

2. **「命中哪一层」不能用两个解析结果比较**——见 [[pitfall-tier-probe-via-fallback-read]]。

## 四、日志工具栏 BGT：P-1 前提被字节码修正

实测日志（沙盒 `idea.log`）：

```
20:34:28  3981 ms total to grab EDT 10 times to expand
          DefaultActionGroup#expandGroup@cn.song.mybatisplus.visualizer.log.toolbar
20:35:23  3174 ms ... （同上）
```

计划里写的「未覆写 `getActionUpdateThread()` 默认是 EDT」**只对了一半**。243 字节码实证
（`AnAction.getActionUpdateThread()`）：

```
UpdateInBackground && isUpdateInBackground()  → BGT
ActionClassMetaData.isDefaultUpdate(this)     → BGT
otherwise                                     → EDT
```

而 `isDefaultUpdate` 判的是 `ReflectionUtil.getMethodDeclaringClass(clazz, "update", [AnActionEvent])`
**是否等于 `AnAction` 本身** —— 即「有没有覆写 `update()`」。所以不是「全组一刀切」，
而是**覆写了 update 的那批动作才落 EDT，得逐个补 BGT**（组本体也要）。

附修：`currentSortLabel()` 旧实现每次 update 都 `sortOptions()` 新建 7 个 `SortOption` + 7 次
bundle 取值（而它在每次工具栏展开的 update 路径上）⇒ 改 `sortModeLabelKey(mode)` 静态查表。

明确不做：不动 T-UiCoord F2 的单行布局预算、不动 `updateActionsImmediately` 调用点、
不用反射压告警（那是真实开销）。

## 五、沙盒 243 验收（2026-10-24，jar 22:48 / 23:20）

- **BGT 通过**：本次会话 `expandGroup@...log.toolbar` EDT 抢占告警 **0 条**（改前每次 2 条/3~4s）；
  剩余 `ActionUpdater` 告警全是平台自身组件（`CWMUserCirclesGroup`/`ToolWindowHeader`/`DockToolWindowAction`）。
  三态文案（捕捉/排序/慢查询）在同会话正常刷新。
- **模块级文案通过**：灰字显示「数据源（**本模块**上次使用）：jpa_demo@localhost」。
- **执行计划选源弹 5 个源**（`jpa_demo`/`mp_demo`×2/`kotlin_demo`/`selfdev_demo`），链路
  选源→建会话→attach→内嵌控制台→提交→出结果完整。
- ⚠️ 但同轮抓出两个真缺陷（已修）：模块级文案冒称、执行计划 EDT 慢操作（见
  [[pitfall-tier-probe-via-fallback-read]] 与 [[project-explain-console-first]] W8）。

## 六、读数

定向 **10 类 102 / 0 / 0 / 0**。反向探针：分层读取退化（只读项目级）恰 3 红；删一个 BGT
覆写恰 1 红（首版守卫因正则剥 `Action` 后缀致 `filter` 全程跳过，探针不红——见
[[pitfall-fake-green-test-assertions]] ㊸）；删 W8 预热恰 1 红；模块级误判缺陷版恰 1 红。

## 七、遗留

`currentSortLabel` 去分配的**独立收益未单独测量**（两项同批提交）。想分离验证可临时把 BGT
改回 EDT 跑一次对照。

相关：[[project-explain-console-first]]（同批合并）、[[project-explain-fail-ux]]（跨模块错源为何重要）、
[[pitfall-fake-green-test-assertions]]。
