---
name: project-ibatis-param-class-quote-20261111
description: T-IbatisParamClassQuote（20261111 已实施并合 master e06fb3f50）——iBatis 裸 `#x#` 参数缺引号，根因＝链上唯一类型源是 `#x:jdbcType#`、`parameterClass` 零消费者；已落 resolver+接线，Heavy 6 例 + 同链回归 55 例全绿、反向恰 1 红；not run 真机复验（沙盒 beta.2 无本轮类）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f586a157-4652-4c9d-b73f-14df449f2952
---

2026-11-11 用户真机验收（demo `ibatis2-demo`）报：`selectTwoSegment` 的
`WHERE USER_NAME = #username#` 填 `1213`，预览成 `WHERE USER_NAME = 1213`——**缺引号**。

**根因**：iBatis 预览链唯一的参数类型来源是占位符自带 jdbcType——
`IbatisParamTypeHints.hintOf(jdbcType)` 只在 `#x:VARCHAR#` / `#x:INTEGER:0#` 这档产 `ParamHint`；
裸 `#x#` 的 jdbcType 为 null → `ParamHint()`（`quote=null`）→ 面板 `NUMBER_REGEX` 启发式，
`1213` 长得像数字就裸渲染。而 iBatis 2 的真实语义是 **`parameterClass` 反射**：
`User.username` 是 String → `setString` → 必须字符串字面量。

**设计规则（可复用）**：预览链的参数类型来源必须与引擎的真实来源对齐——MyBatis 是 mapper 方法签名
（`XmlParamTypeResolver`），iBatis 是 `parameterClass` 的字段类型；只靠占位符里手写的 jdbcType 会漏掉
最常见的裸占位形态。`IbatisStatement.attributes`（解析器已存的整张属性 Map）里本来就有
`parameterClass`，但全仓零消费者；`preview()` 过去连 `project` 都不收，唯一调用方
`IbatisStatementPreview.previewTag(tag)` 手里同时有 `tag.project` 与 `tag.getAttributeValue("parameterClass")`。

**demo 侧事实**（报障现场）：sqlMap 在 `ibatis2-demo/src/main/resources/sqlmap/User.xml`；
`sqlmap-config.xml` 有 `<typeAlias alias="User" type="cn.song.ibatis2demo.model.User"/>`（别名与类简单名同名
⇒ 不读别名表也能靠短名命中）；对照语句 `selectThreeSegment` 是 `#age:INTEGER:0#`（带 jdbcType，一直正确）。

## 落地（`cb4ec1ed3`，文档 `53eab6041`，合 master `e06fb3f50`）

- 新 `ui/IbatisParameterClassResolver.kt`：`parameterClass` → 类（全限定名 `findClass` 优先；
  短名/别名走 `PsiShortNamesCache`，**唯一命中才用，多候选弃权**）→ 属性路径逐段解析字段 →
  `ParamHint`（String/UUID 引号、数值/布尔裸、日期族引号+temporal、List/Map/未知不产提示）。
  `resolveStrict` 是不吞异常的诊断入口，`resolve` 用它并吞成空表（生产降级）。
- `IbatisXmlPreviewService.preview(project, parameterClass)`；`toPreviewResult(propertyHints)` 装配优先级
  **jdbcType 胜出 → 属性类型补位 → `ParamHint()`**；`rerender` 闭包捕获首屏那批 hints。
- `IbatisStatementPreview.previewTag` 传 `tag.project` + `tag.getAttributeValue("parameterClass")`
  —— Java DAO 调用行预览（`previewResolved` → `previewTag`）与 XML 语句行同源覆盖。
- 测试 `heavy/IbatisParamClassQuoteHeavyTest`（6 例）：String 加引号端到端 `'1213'`、Integer 裸、
  `#age:VARCHAR#` 压过 Integer 字段、类解析不到维持启发式、无 project 不产 hint、诊断链。
  **必须走 Heavy**，Light 跨文件索引不可用（同类先例 `EntityClassFinderHeavyTest`）。

## 实施中证伪的自设前提

第一版 `hintOfType` 用「`resolve()?.qualifiedName` + FQN 集合」匹配 → Heavy 全红。诊断读数
`field=username type=PsiType:String typeHint=null`：**无 SDK 的测试环境里 `resolve()` 返回 null**，
只剩简单名 `String`。修法与通用教训见 [[pitfall-psiclasstype-resolve-null-simple-name]]。

## 读数

- Heavy 6 例全绿；同链回归 `IbatisXmlPreviewServiceTest`(44) + `DegradationBannerTest`(5) +
  `SqlPreviewPanelDynamicBatchTest` 全绿。
- 反向：摘掉 `?: propertyHints[name]` 补位分支 → `testBareParameterQuotedByParameterClass` 恰 1 红
  （6 例中 1 失败），还原复绿。
- **not run**：真机复验——沙盒在役 `2026.11.12-beta.2` 的 jar 内 `IbatisParameterClassResolver`
  MISSING（上一轮 `MbgCriterionMock` / `MbgExampleCallResolver` 在）。判别法与日志位置见
  [[pitfall-sandbox-jar-staleness-and-window-enum]]。要复测须先关沙盒再构建新包（沙盒锁 jar，
  `buildPlugin` 卡 prepareSandbox，见 [[pitfall-preparesandbox-locked-by-running-runide]]）。

## 仍开的待验证

用户真机项目里 `parameterClass` 是类短名还是别名（短名多候选即弃权、退回现状）；`parameterMap`
场景是否另有类型来源；本仓 buildSrc 未按包名分 heavy 桶（Heavy 类与普通类同跑 `test`）。

计划：`docs/开发计划/20261111-1242-T-IbatisParamClassQuote-iBatis裸参数缺引号-开发计划.md`。
关联：[[project-ibatis-call-preview-20261111]]、[[project-ibatis2-gap-fix-round]]、[[xml-engine]]。
