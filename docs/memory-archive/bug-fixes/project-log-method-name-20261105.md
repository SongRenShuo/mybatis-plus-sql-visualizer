---
name: project-log-method-name-20261105
description: T-LogMethodName（20261105 已实施并合 master d9fe476e，定向 137/0/0/0 + 三探针反向验证）——SQL 列表来源后显示「类名#方法名」：并排两段定案、两通道 mapperStatement 语义差异与「来源类≠方法类」场景表、设置触点与缓存键陷阱
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e6ec07cf-a286-4630-ab10-7c98cfacd38a
---

需求（用户 2026-11-05）：SQL 日志新增开关（默认关），打开后在列表来源位置后显示方法名，形态参考 IDEA 原生 `Class#method`。计划册 `docs/开发计划/20261105-1254-T-LogMethodName-SQL列表来源后显示类名与方法名-开发计划.md`（§3.3 场景表、§四 改动清单、§6.4 真机步骤）。

**用户拍板**：① 并排两段 `[OrderServiceImpl.java:41] [UserMapper#selectById]`，不把 `#方法` 插进文件片段；② 方法名只用现有 `LogSqlEntry.mapperStatement`，不扩展注入拦截器标记。

**来源类与方法名的类为什么常常不同**（「不能合并」的根据）：`sourceFile:line` 是调用点，`mapperStatement` 是执行的语句——控制台桥＝`ms.getId()`（mapper 接口），agent＝调用栈业务帧（与来源同一帧）。不一致是常态：Service/Controller 调 mapper、MP `IService` 内置 CRUD（实现全在 `com.baomidou` 被当框架帧跳过）、自有基类/工具类/数据权限拦截器、Spring AOP（CGLIB 无 SourceFile 属性 → 该帧被 `continue` 跳过，位置上飘一层）、mapper default 方法委托另一个 mapper、异步/线程池/定时任务、XML namespace。agent 侧「文件名≠类名」也会看起来不一致：Kotlin 顶层 `Foo.kt` vs `FooKt`、内部/匿名类 `Foo.java` vs `Foo$1`（`StackLocator.simpleMethodKey` 只剥包名、保留 `$`）。反向坑：同名不同包的 `UserMapper` 简单类名分不出。

**实施**（2026-11-05）：分支 `t-log-method-name` 自 master `26604b7d` 切出（立项时 `6491b4b4`），工作树 `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-lmn`；计划册提交 `1d77a21e`。代码＝`LogEntryRendering.mapperStatementLabel`（先 `#` 再最后 `.`；任一段空/无分隔符→null，宁可不显示）+ `formatMethodFragment`（开关短路，属性对齐来源片段）；`SqlEntryPresentation.headerSourceFragments` 与 `metadata` 两处同口径；`HeaderStaticCacheKey` 增 `methodInSource`；设置侧 State 字段/复选框字段/面板行/三向绑定/搜索索引/双语 tooltip + 复选框计数 47→48。历史页与「最近执行」弹窗共用 `RailCellRendererBase`，自动跟随。

**实施与合并（2026-11-05 当日完成）**：实现提交 `0f9e199c`（16 文件 +375/−9）。定向 8 类 **137/0/0/0**（走 `dev-support/gradle-gate.ps1` 排队，登记 `T-LogMethodName`/沙盒 `MPSV-lmn`；8 个类都有结果 XML，排除「过滤未命中」的空绿）。三探针反向验证各红一条且只红预期那条：①缓存键 `methodInSource` 置 false → `HeaderFragmentCacheTest.test来源后方法名开关切换后缓存失效重算` 红；②注释 `buildSearchableFields()` 登记 → `SettingsPanelSearchableGuardTest` 断言一红（zh/en 双语同报 `settings.log.showMethod`）；③注释开关短路 → `LogMethodNameFragmentTest.test开关关闭时不产出方法名片段` 红（`expected:<1> but was:<2>`）。恢复后 `grep RED PROBE` 零命中并复跑回到 137/0/0/0。

**合入 master `d9fe476e`**：`--ff-only` 失败——master 已被并行会话推进到 `e20edae7`（立项时 `6491b4b4` → 建树时 `26604b7d` → 合并时 `e20edae7`，一天内三次前进）；先 `git merge-tree --write-tree master t-log-method-name` 预演（exit 0 无冲突，且 master 那三笔只碰 `settings.gradle.kts`/`dev-support/ci`/发行与经验文档），再 `git merge --no-ff -F <信息文件>`。主仓工作树当时压着并行会话的 ` M docs/开发计划/20261105-1241-T-CrossChannelDedupe-…md`，合并未触碰、事后仍是 ` M` 未暂存。

**用户当轮口径**：合并后**只要求编译通过、不跑测试**（`compileKotlin compileTestKotlin`），故合并态没有测试读数；分支 `t-log-method-name` 与工作树 `-lmn` 的清理在编译通过后执行。未推远端。

**已知取舍**：来源段是头部最后片段，窄面板顺序截断时新段最先被裁，故默认关闭＝opt-in 兜底；真机视觉走查（计划 §6.4 七步）交用户，未做。

**Why**：同一句「显示方法名」在两条通道上指向不同的类，拼串会造出不存在的成员；而显示类设置的几处接线（缓存键/搜索索引/计数断言）都不在编译器视野里。

**How to apply**：接手先 `git log` 实测分支状态；任何「列表头部新增片段」需求都先查缓存键维度。相关：[[pitfall-new-capability-hits-repo-wide-convention-guards]]、[[feedback-display-format-follows-settings]]、[[parallel-build-resource-rules]]。
