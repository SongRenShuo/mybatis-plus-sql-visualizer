---
name: project-dialect-gate-release-on-conflict
description: 方言冲突不再阻断执行（用户定案）——三处修复、边界保留、真机验证证据
metadata:
  node_type: memory
  type: project
  originSessionId: session_502360ae808bfb35c69ac231233cbe92
---

# 方言冲突放行：方言不拦执行（2026-10-25 用户定案并实施）

## 用户定案原文

> 我认为方言不能影响最终的执行，哪怕方言是错的，用户手动选择了数据源，你可以正常提示方言不一致，但是还是要能成功执行的。

## 三处修复

**`8619a3af` fix(execute): 方言冲突不再阻断执行 + 报错文案改报渲染方言**

1. **闸门放行**（`DataSourceSelector.allowDialectGate`）：`MANUAL_CONFLICT` / `RERENDER_UNAVAILABLE` 从「红字 + 阻断」改成「红字 + 放行」。UI 仍显示方言不一致与「切换数据源」入口。
   **边界保留阻断**：`TARGET_UNKNOWN_BLOCK` / `RENDER_UNKNOWN_BLOCK` —— 那时有一边方言**未知**，写操作可能把 PG 语法打到 MySQL 库上，属于「无从判断」而非「判断出冲突」。
2. **报错文案**（`DataSourceSelectorResults`）：原来只从目标数据源反推方言，目标源是上次用的 MySQL 源 → 报「方言（MySQL）」，而用户眼前预览写着 `方言: PostgreSQL（手动指定）`、SQL 也是 PG 语法，自相矛盾。改为优先用 `dialectGateRequest.renderedDialectName`。

**`e7a27700` feat(execute): runner 枚举为空时按目标方言重映射 exec 文件再枚举一次**

3. 平台 `getAttachDataSourceRunners` 按 **psiFile 已映射的 SQL 方言**筛 runner，数据源在平台侧没建好方言映射时 `runners=0`。兜底＝把 exec 文件重映射到目标源方言再枚举一次，命中即递归回同一分派逻辑，控制台/结果表/审计全部复用（不新建执行通道）。新增 `ExecDialectRouting.remapExecFileDialect`。

## 真机验证（PG 方言 + MySQL 目标源冲突）

```
[M36] runners=0
[M36] runners empty → remapped exec file to target dialect, retry runners=0
（补 PG 凭据后）
[M36] runners=3
```

界面弹出「选择数据源」列出三条 PG 源；选中后**执行真的送到数据库**：

```
执行失败: [42P01] ERROR: relation "user" does not exist
位置: 163 [当前库=public, 数据源=pg_demo@114.66.55.245, 数据源默认库=dbx_test]
```

不再被插件拦在「没有匹配数据源」，交给数据库判定——正是用户要的语义。

## 读数

- 定向 `execute.*` + `SqlPreviewPanelDialectGate*` 全绿
- **反向验证**：把放行改回阻断 → 新增用例恰 1 红（精确命中）
- 新增守卫用例 `unknown dialect on either side still blocks writes`（守住保留阻断的边界）

## 判据（区分「插件拦了」与「执行真到了库」）

| 报错形态 | 含义 |
|---|---|
| `没有与该 SQL 方言（X）匹配的已配置数据源` | 插件没放行 |
| `[42P01] ERROR: relation ... does not exist` | **执行真的到了库**，方言/SQL 与目标库不符 |
| `[42S02] Table ... doesn't exist` | 同上（MySQL 侧） |

## 遗留

`dialect-gate.gif` 原计划演示「阻断态 + 两出口」，但按新语义不再阻断。可录形态变成「提示冲突但执行成功（数据库报 relation 不存在）」。**要录哪种需产品确认**；若要「阻断态」，得构造「一边方言未知」的场景（那是保留阻断的分支）。

## ⚠️ 2026-11-02 复查：这条「放行」把执行链打死了（P0，未修）

`allowDialectGate` 里 `if (verdict.status != MATCH && !needsConfirmation) onSubmission?.invoke(BLOCKED)` 没跟着收窄——**放行也报 BLOCKED**。而 consumer 传进来的 `onSubmission` 正是 `QueuedExecuteIntent.onSubmission`（`complete()`），于是意图被标完成 → `isPrepareActive()` 恒 false → 准备链静默早退：不 attachConsole、不释放单飞门 → 60s 内执行/换源全部排队，15s 后报「数据库控制台准备超时，请重新选择数据源」。

**定案本身没错**（方言不拦执行），错的是「放行」复用了「阻断」的结算通道。用户现场＝GaussDB 渲染 + PostgreSQL 源（`RERENDER_UNAVAILABLE`）。详见 [[project-dialectgatepass-dead-chain]]。

相关：[[project-ide-mcp-recording-helper]]、[[project-docs-gif-rerecord-1025]]、[[project-dialectgatepass-dead-chain]]
