---
name: pitfall-preview-prepare-console-silent-skip
description: 预览打开时控制台不预热的两条静默提前返回——editor 尚未建好（注解预览链异步）与 Database 不可用；表现为「未绑定数据源」要等点执行才挂上；修法＝SQL 源三级回落 + 入口锚点日志
metadata:
  node_type: memory
  type: project
  originSessionId: sess_7df4acee-c963-4e0d-9a96-cbe306b5ad33
---

# 预览打开后控制台不预热：两条静默路径（20261102 262 沙盒验收抓出）

用户现象：`@Select` 注解预览打开后右侧一直「未绑定数据源」，要**先点一次「执行 SQL」**原生控制台才挂上。

## 根因

`prepareDefaultConsole()`（`showResult` 末尾调用）里有两处**静默 return**，日志里完全不可见：

1. `val sql = editor?.document?.text?.takeIf { it.isNotBlank() } ?: return`
   注解预览链（`ConvertToSqlAction.previewAnnotation`）的回退编辑器是**异步**建的，`showResult` 走到这里时 `editor` 仍为 null → 直接返回，`SqlExecutor.prepareConsole` 根本没被调用。
   实证：`[M36] prepareDefaultConsole: editor=false, sqlLen=-1, dbAvailable=true`。
2. `SqlExecutor.prepareConsole` 里 `if (!ExecuteSqlSupport.isAvailable()) { callback(false); return }` 同样不打日志。

两条都命中时，表现就是「预览没有控制台、也没有任何报错」。

## 修法

- SQL 源三级回落：`nativeEditorDoc?.text → editor?.document?.text → current?.sql`（`current` 是本次预览结果，`showResult` 已先写入）。
- 入口补一行锚点：`[M36] prepareDefaultConsole: editor=?, sqlLen=?, dbAvailable=?`——真机排障时这三个值直接区分「SQL 源没拿到」与「Database 不可用」。

## 判据

- 面板「未绑定数据源」+ 无控制台：先看这行锚点，`editor=false` 就是本条；`dbAvailable=false` 是插件根本没加载 Database。
- 与 `runners=0`（[[pitfall-runners-zero-means-unbound-not-unconnected]]）区分：那条会打 `runners empty (execute=true, registered=N)`，本条连 `runners=` 都不会出现。

提交 `96d854fd`。关联 [[project-embedded-native-console-scope]]（控制台承载面）、[[pitfall-platform-async-reparse-invalidates-captured-psi]]（同类「异步没落地就用」）。
