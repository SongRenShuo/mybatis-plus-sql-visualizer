---
name: pitfall-ibatis-dao-call-double-icon-and-direct-call-green
description: iBatis DAO 调用行出现两枚 gutter 图标的根因＝JdbcTemplateMethodDetector 的纯方法名白名单与 SqlMapExecutor 入口名完全重叠；另一条＝GotoDeclarationHandler 直调单测绿但真机 Ctrl+Click 无效，断言必须走平台真实入口
metadata:
  node_type: memory
  type: project
  originSessionId: sess_c0d561d8-362b-43ca-913b-a6bfb07cc567
---

# iBatis DAO 行的双图标 / Ctrl+Click 假绿（2026-11-10 真机第二轮反馈）

## 一、同一调用行出两枚图标：方法名白名单重叠

用户截图：`UserDaoImpl.java` 第 32/37/42/47/52 行（每个 `return client.queryForXxx("User.xxx", …)`）左侧各**两枚**图标。

根因不是重复注册（`plugin.xml` 里 3 个 iBatis provider 各一条，核对过）：

- `psource/JdbcTemplateMethodDetector.kt` 的方法名白名单含 `queryForObject` / `queryForList` / `queryForMap` / `update` / `delete` ——与 `SqlMapExecutor` 的 statementId 入口名**完全重叠**；
- 该判定是**纯方法名、O(1)、不查接收者类型**（这是它的设计取舍，为了热路径零 resolve）；
- 于是 `client.queryForList("User.selectById", …)` 被 **JdbcTemplate 预览通道** 与 **iBatis Java→XML 通道**同时认领，两枚 `LineMarkerInfo` 叠在同一枚方法名 identifier 上。

**修法方向**（不要改 iBatis provider，让它优先）：JdbcTemplate 侧让位，用**廉价门**而非类型解析——文件级（该 Java 文件 import/字段含 `com.ibatis.sqlmap.client.SqlMapClient`）或调用级（首参是符合 `ns.id`/裸 id 形态的 String 字面量，不校验存在性，存在性交给 iBatis 侧的目标兜底）。仓内既有「家族守卫排除制防双图标」先例可照抄（`StringSqlMethodGutterLineMarkerProvider`）。

**推广**：任何「按方法名白名单认领调用行」的 gutter provider，遇到另一框架的同名 API（JDBC 模板 vs iBatis、MyBatis vs JPA 的 `delete`/`update` 撞名）都会双图标。加新 ORM 支持时要**逐个列出现有方法名白名单与本框架 API 的交集**，而不是只看自己新写的 provider。

## 二、Ctrl+Click 不跳，但单测 15 例全绿

`IbatisDaoStatementGotoDeclarationHandler` 已注册（`plugin.xml` 尾部块），L8 的 `IbatisDaoStatementGotoDeclarationHandlerTest` 15 例全绿；真机上 **Ctrl+Click 字符串实参无反应**，而 **gutter 箭头点击正常**。

差异在于两条路径不同：

- 单测**直调** `handler.getGotoDeclarationTargets(element, offset, editor)`——只证明「解析逻辑对」；
- 真实 Ctrl+Click 走平台 `GotoDeclarationAction`（收集各 `GotoDeclarationHandler` + Java 侧自己的 handler 参与，光标在方法调用的 String 字面量上时可能给出多候选或被他方结果短路）——证明的是「分派链对」。

**纪律**：凡「用户点/看到」的行为，断言必须走**平台真实入口**（`GotoDeclarationAction.findTargetElement`／真实 `LineMarkerInfo` 收集／真实 `PreviewResult`→面板渲染）；直调内部函数绿只是逻辑证据，不是行为证据。本轮四条缺陷里至少两条是「直调绿、真机红」。

相关：[[pitfall-multi-statement-preview-first-keyword]]、[[project-ibatis2-gap-fix-round]]、`reference-intellij-navigation-action-mechanism`（GotoDeclarationHandler 与 PsiReference 的优先关系）。

## 三、修完调用行还会剩一遍（2026-11-10 真机第三轮，`63853e5d`）

上面两条修完后用户又报两条，暴露的是**同一类能力的锚点矩阵没走完**：

1. **DAO 方法定义行仍有预览图标且点不开**——不是 JdbcTemplate 通道（已让位），是
   `StringSqlMethodGutterLineMarkerProvider`：它锚**方法签名名**、扫方法体里「方法名在白名单 + 有字符串实参」的调用，
   `client.queryForList("User.selectAll")` 命中、接收者 `SqlMapClient` 又归类 UNKNOWN 不被家族排除 → 方法定义行亮图标；
   点开按 SQL 解析方法体，语句 id 不是 SQL，必然空。修法＝同一个 `IbatisCallYieldGate` 也加到方法定义锚点。
   **锚点矩阵**：调用行 / 方法定义行 / XML 语句行，三格各有 provider，改一格必须核另两格。
2. **XML 里 Ctrl+Click 报 Cannot find declaration**——XML→Java 方向**从未接 GTD**：只有 Java→XML 的 handler +
   XML 的 gutter 箭头；`IbatisXmlIdReferenceContributor` 只造 `refid`/`resultMap`/`parameterMap`，语句自己的 `id` 没造引用。
   修法＝新增 `IbatisXmlStatementGotoDeclarationHandler`（`id` 值与起始标签名 token 双锚），目标集复用
   `IbatisJavaCallSites.findAllCallLiterals`（词索引，多调用点弹候选，上限 20）。
   **入口矩阵**：gutter 点击 / GTD（Ctrl+Click、Ctrl+B）/ PsiReference，且**方向是两格**——两个方向各一个 handler。

**为什么用 GTD 而不是给 `id` 造引用**：`PsiReference.resolve()` 在高亮期被反复调用，而这条解析是一次全项目词索引搜索——
造引用等于把索引查询搬进 daemon 热路径。

**测试口径**（本轮定型，见 `IbatisGotoRealEntryTest`）：真 `executeOnPooledThread` + `CountDownLatch` 里调
`GotoDeclarationAction.findAllTargetElements` 与 `GotoDeclarationOrUsageHandler2.testGTDUOutcome`。EDT 上跑会被
`Navigatable.navigationRequest` 的 BGT 断言拒（且异常被平台记成测试失败）；`ReadAction.nonBlocking().executeSynchronously()`
在 EDT 会被**内联**，仍在 EDT。

教训已入仓账本 `docs/LESSONS.md` **L26**。

## 四、沙盒验收把这三条闭了环（2026-11-10）

子代理在独立沙盒（`build/MPSV-IbatisFix4`，在役 jar 先核过含 `IbatisXmlStatementGotoDeclarationHandler`
且 mtime 晚于修复提交）实测：

- **A1 图标归位：通过**——取证手法值得复用：对 gutter 列按插件 svg 的品牌色**逐行聚类**（预览绿/紫、
  Java 侧箭头红、XML 侧箭头青），比"看截图数图标"硬：定义行与调用行各恰 1 枚、**紫色像素 0**、无一行两枚。
- **A2/A3 双向跳转：都通过**——这正是「直调绿/真机红」的反面：改走平台真实入口后，真机行为与用例一致。
- 两次「Cannot find declaration」是探针把光标落在语句上方**注释行**，不是产品缺陷——判"跳不动"前先核光标落点。
- A4 的失败读数作废（脏 buffer，见 [[pitfall-buffer-ghost-document-fakes-xml-parse-error]]）；A5~A7 未执行。