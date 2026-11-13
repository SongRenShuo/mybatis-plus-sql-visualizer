---
name: project-ibatis-call-preview-20261111
description: T-IbatisCallPreview（20261111）——iBatis DAO 调用行预览图标/快捷键/MCP 已实施（8ad491b8）、已合 master 0ff8cf29 并推双仓；定向 121/0/0、反向恰 2 红；真机验收通过（用户 20261111）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_c0d561d8-362b-43ca-913b-a6bfb07cc567
---

# T-IbatisCallPreview：iBatis DAO 调用行直接预览 SQL（2026-11-11）

## 终态（2026-11-11）

- 分支/工作树：`t-ibatis-call-preview` @ `.worktrees/t-ibatis-call-preview`，实施完毕后**已合 master 并清理**（worktree remove + branch -d）。master 快进到 `0ff8cf29`，双仓推送成功（Gitee origin + GitHub 令牌直连，无弹窗），`origin/master == master`。
- 提交链：`c0cfdc8e` 计划 → `8ad491b8` 实现 → `16e157e7` 顺序守卫+探针+CHANGELOG → `a11dfc0a` 合并态读数 → merges `2c931c72` / `8d47404d` / `0ff8cf29`。
- 定向（合并态）：**121 例 0 失败 0 错误**（IbatisStatementPreviewTest 9 / IbatisNavigationLineMarkerProvidersTest 20 / IbatisXmlGutterLineMarkerProviderTest 14 / IbatisGutterHotPathTest 9 / IbatisXmlPreviewServiceTest 44 / JdbcTemplateIbatisMutualExclusionTest 4 / ConvertToSqlActionTest 9 / McpSourceLocatorRenderAtLineTest 8 / GutterProviderSingleLookupGuardTest 4）。
- 反向：摘预览 marker（`if (false && previewEnabled)`，探针 `dev-support/reverse-probes/ibatis-callpreview-disable-preview-marker.patch`）→ 恰 2 红；恢复 20/0。另有源码顺序守卫 `testiBatis两档回落排在JdbcTemplate之前`。
- 真机验收：**通过（用户 2026-11-11）**——§5.4 的图标/快捷键/MCP 判据无问题。验收中另发现一条无关偏差（`selectTwoSegment` 裸 `#username#` 填数字被裸渲染缺引号，引擎按 `parameterClass` 属性类型决定引号）→ 已另立 `20261111-1242-T-IbatisParamClassQuote`（实施中），不在本任务范围。

## 四类入口（全部同源）

1. **gutter**：调用行两枚 marker——箭头锚方法名 identifier（原样）、预览锚首参**字面量叶子 token**；同一 provider（`IbatisJavaToXmlLineMarkerProvider`）产出、共用一次 `resolveStatementAnchors`；目标不存在两枚都不出；`ibatisXmlEnabled` 只关预览、不关箭头。
2. **共用装配** `ui/gutter/IbatisStatementPreview`：`previewTag` / `previewResolved` / `resolveStatementTag` / `statementOrdinalOf` / `statementNotFound`，外加 `IbatisCallTarget` + `IbatisCallTargets`（`resolve` 两跳、`callsInMethod`，形状门复用 `IbatisCallYieldGate`）。
3. **快捷键 Alt+Shift+Q**：`ConvertToSqlAction` 回落链最前插 `tryIbatisCallFallback` / `tryIbatisMethodFallback`（先于 JDBC 与方法体字符串 SQL）；方法级 0/1/多候选（多走 `SqlSelectionDialog`）。
4. **MCP `sqlv_render_sql`**：`<sqlMap>` 语句行 + Java 调用行 + 方法体候选，`sourceType` 以 `iBatis` 起头；`isXmlStatementTag` 按父标签分流（mapper 仍走 MyBatis 渲染器）。

## 实施期发现的两处真实形态（已各自成文）

- LineMarker 锚只认叶子，锚 `PsiLiteralExpression` 触发平台 error 并连锁红 → [[pitfall-linemarker-anchor-must-be-leaf]]
- `return` 关键字不是调用表达式祖先，行首定位取不到调用 → [[pitfall-caret-line-start-not-in-call-expression]]，测试夹具还踩了 [[reference-filetypeindex-vs-disk-test-files]]

相关：[[pitfall-ibatis-dao-call-double-icon-and-direct-call-green]]、[[project-ibatis2-gap-fix-round]]、[[pitfall-multi-statement-preview-first-keyword]]。