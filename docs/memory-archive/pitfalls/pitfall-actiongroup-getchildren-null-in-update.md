---
name: pitfall-actiongroup-getchildren-null-in-update
description: 2026.3 平台把 `ActionGroup.getChildren(null)` 记为插件错误并抛 Throwable「Do not call getChildren(null)」——动作组的可用性判定若挂在菜单更新路径上，绝不能在那里展开组
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f586a157-4652-4c9d-b73f-14df449f2952
---

# 2026.3：`getChildren(null)` = 插件错误（D8，`ab9a8c308`）

真机日志（用户日常 IDE 2026.3）：

```
java.lang.Throwable: Do not call `getChildren(null)`. Do not expand action groups manually.
  at DefaultActionGroup.reportGetChildrenForNullEvent
  at cn.song.mybatisplus.visualizer.execute.NewDataSourceEntry.buildNativeDriverActions(NewDataSourceEntry.kt:150)
  at NewDataSourceEntry.isAvailable$…
  at ChooseDataSourceTakeoverGroup.getChildren(ChooseDataSourceTakeover.kt:178)
```

## 机制

`ChooseDataSourceTakeoverGroup.getChildren` 是**菜单更新路径**（每次弹层更新都会走）；我们的
`isAvailable(project)` 在里面判「原生新建数据源入口是否可用」，实现却是 `buildNativeDriverActions(project) != null`
——也就是**为了判可用性去展开原生驱动组**。2026.3 对这种手写展开直接 `Logger.error`（表现为插件错误）。

## 修法（两层，缺一不可）

1. **高频路径不展开组**：`isAvailable` 只做**类可达判定**（`Class.forName` 三个 Database 插件类都拿到即算可用），
   不碰 `ActionGroup.getChildren`。
2. **真要取子项时传真实事件**：`AnActionEvent.createFromDataContext("MpSqlVisualizer", null, DataContext.EMPTY_CONTEXT)`
   → `actionGroup.getChildren(event)`；只放在用户**点击**那一刻执行，并 `runCatching` 兜底（失败回落「直开
   数据源和驱动程序窗口」）。

## 一个编译期事实

243 编译目标上 `ActionGroup.getChildActionsOrStubs()` **不存在**（Unresolved reference），别照 2026.3 的新
推荐 API 写——这条仓里是 243 基线（`intellijIdeaUltimate("2024.3.7.1")`）。

相关：[[reference-intellij-native-new-datasource-entry]]、[[project-ds-list-add-source-20261111]]。
