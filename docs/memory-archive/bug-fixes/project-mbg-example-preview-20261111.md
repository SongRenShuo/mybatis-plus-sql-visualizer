---
name: project-mbg-example-preview-20261111
description: T-MbgExamplePreview（20261111）——MBG Example 预览失真 M1+M2 已合 master（merge df845f6e）并推双仓，合并态 475 例 0 红、工作树与分支已清理，剩真机 PSI 链路与首屏验收
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f586a157-4652-4c9d-b73f-14df449f2952
---

2026-11-11 用户报：MBG 生成的 `selectByExample` 预览产物是 `WHERE (AND OR (AND … ORDER BY 1`。
七条定因与四组实测读数 → [[pitfall-xml-preview-mock-param-blindspots]]。

**已实施并合入 master**（工作树 `.worktrees/t-mbg-example-preview`，分支 `t-mbg-example-preview`，
主仓 merge 提交 `df845f6e`；合流基线 `6ad04dd2`，期间 master 又到 `8dcb668f`）：

| 提交 | 内容 |
| --- | --- |
| `7ad0d5ba`（master） | 计划文件 + 索引登记 |
| `2d8798a9` | M1 六项 |
| `6fb76205` | M2 调用点反推 |
| `54de5f86` | CHANGELOG `[Unreleased]` + 计划实施记录 + 索引状态 |
| `86b70ad0` | trim 体内粘连用例 + 反向验证读数 |
| `8e4c5ed8` | 分支内 merge master（文档冲突解决） |
| `79f6d5db` | 合并态暴露的注解链断言修正 |

产物：默认 `WHERE ( {{criterion.condition}} ? )`；调用点命中时 `WHERE ( cust_id = ? )`。

实现要点（与计划的偏差已回填计划 §九）：

- T1 占位 `{{表达式}}` 由 `XmlSqlRenderer.render(emptyDollarPlaceholder)` 控制、**默认 false**，只有 `XmlPreviewModel.renderSql` 传 true；`DynamicContext` 新增该字段，Trim/Foreach 三个过滤上下文**必须全部转发**（漏一层则该层体内的 `${}` 仍输出空串）。执行/EXPLAIN 闸门 `confirmDollarRawSegments` 读 `hasSimulationPlaceholders()`（编辑器文本含 `{{`）转向 `execute.confirm.simPlaceholder.message`。
- T2 第一版做在 `ForeachSqlNode` 层，与根 `appendSql` 的段分隔叠加成双空格（`IN ( ?  ,  ? )`，8 例金样红）；最终落在 `TrimSqlNode.FilteredDynamicContext.appendSql`——相邻两段都无空白才补一个空格。
- T4 默认分支＝「含 `#{}` 的分支里**最先出现**者」，不是「参数最多」：按参数最多 MBG 会选 `betweenValue`（2 个 `#{}`），先出现才选到 `singleValue`。
- T5 `resolveExampleProfile`：`parameterType` 全限定名优先，短名按 namespace 同包补全；判据是类里有 `oredCriteria` 字段或 `createCriteria()` 方法。
- M2 **不猜列名**：MBG 的 `andXxxYyy` 方法体固定一句 `addCriterion("cust_id =", value, "custId")`，直接读这个常量；分支类别按方法名后缀（`IsNull`/`In`/`Between`/其余）。`ReferencesSearch` 取首个调用点 → example 实参变量 → 同方法体 `and*/or*` → `resolveMethod()` 宿主类以 `Example` 结尾 → 读常量；任何一步失败返回 null（退回占位，不报错）。
- M2 生效前提：`MockIterable(size, elements)` 可带元素；`XmlPreviewModel.collectionElements` 优先于「展开 N 次的无属性元素」，键与 foreach 的 `collectionExpr` 同源（`oredCriteria` / `criteria.criteria`）。

**合并态验证**：清 `build/classes` 后跑 `xml.*` + preview/guards 共 463 例，唯一红是
`AnnotationSqlParserTest > 非script纯文本_dollar属性链求值为空时提示`——注解链（非 script 纯文本）走
同一条预览渲染链，占位对它同样生效，断言已改为 `SELECT {{x.y}} FROM t`（`79f6d5db`）。
`BundlePlaceholderArity` / `GiantFileBudget` / `GuardSelfTestCoverage` 绿。

**L21 复发**：合并进 master 后第一次跑出大批 `NoSuchMethodError: from$default`——主仓 `build/classes`
是别的分支的旧字节码（`from()` 加了 `profile` / `collectionElements` 参数）。处置＝`rm -rf build/classes
build/tmp/compileKotlin build/tmp/compileTestKotlin` 再跑。

**收尾（2026-11-11）**：主仓 `git merge t-mbg-example-preview` 无冲突落地 `df845f6e`；合并态清 classes 后
**46 suite / 475 例 / 0 失败 / 0 错误**（`xml.*` 全包 + preview 链 + 三个守卫类）；工作树
`.worktrees/t-mbg-example-preview` 与分支已删；双仓 master = `3c17cff1`（`push-all.ps1 -Branch master`，
日志走 `x-access-token:***@` 令牌通路、全程无弹窗）。同轮一并推走的还有并行会话的 `8dcb668f` / `3bbada6d`。

**not run / 待用户**：PSI 链路真机命中率（`ReferencesSearch` 在有索引真项目、`addCriterion` 常量、跨模块 Example 类）；真机首屏四步验收写在计划 §九「待用户验收」。

操作事实：IDEA MCP 现在能列多个项目，但每次调用要带 `projectPath`；已打开的是
`mybatis-plus-sql-visualizer-demo`，其中**没有** `CtCustmanagerRel*`（对照 `**/*.java` 能正常返回 8+ 条），
说明报障项目是另一个，PSI 链路在本机无法验证。

收尾走完的下一站：[[project-copy-placeholder-marker-20261111]]（复查时发现的复制链缺口，同轮立项实施）。

计划文件：`docs/开发计划/20261111-1043-T-MbgExamplePreview-MBG-Example动态SQL预览失真-开发计划.md`。
关联：[[pitfall-reverse-probe-fixture-layer-mismatch]]、[[pitfall-git-checkout-reverts-uncommitted-probe]]、[[xml-engine]]。