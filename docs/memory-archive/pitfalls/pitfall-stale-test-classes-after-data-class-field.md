---
name: pitfall-stale-test-classes-after-data-class-field
description: 给 Kotlin data class（如 PluginSettings.State）加字段后整类测试 NoSuchMethodError: State.<init>——陈旧 build/classes/kotlin/test，删测试编译产物重跑即愈
metadata:
  type: project
---

**Why:** 20261114 宽定向回归整类红：`PluginSettingsPanelBindingTest` 全部用例 `NoSuchMethodError: 'void PluginSettings$State.<init>(boolean, String, …)'`——给 `State`（Kotlin data class）加字段（localUpdateDir）后构造器 arity 变化，而共享工作树的 `build/classes/kotlin/test` 里是**加字段前编译的旧测试类**（并行车道/合流后的主类已重编），JVM 加载旧测试类调新主类构造器即炸。与 [[pitfall-shared-worktree-cross-lane-test-pollution]] 同根的另一形态：不是源码污染，是编译产物代差。

**How to apply:**
- 见到「整类测试 NoSuchMethodError 指向某个 data class 的 `<init>`」→ 不要怀疑代码，先删 `build/classes/kotlin/test` + `build/tmp/compileTestKotlin`（必要时 `build/classes/java/test`）重跑；别 `--rerun-tasks` 全量重建（慢得多）。
- 多路会话共用 master 工作树时此坑高发——干净读数照旧另起 `.worktrees/<task>` 工作树。
