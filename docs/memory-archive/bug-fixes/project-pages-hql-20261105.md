---
name: project-pages-hql-20261105
description: T-PagesHql（20261105 立项，**20261106 已随 v2026.11.6-beta.1 发行**）——<pages> 的
  <datasource> 实为 HQL，按 entityClass 与语句根标识符的关系判定源语言（非充分条件），命中复用 previewHbmNamedQuery 转换
metadata:
  node_type: memory
  type: project
  originSessionId: sess_b3cc9910-a706-454e-9c1a-c72b2b589784
---

# T-PagesHql（maintainPage 的 `<datasource>` 识别 HQL）

## 结论

- **不是入口问题**：`<pages>` 分派、gutter 图标、列信息页签都正常；问题是 `PagesXmlPreviewService.toResult` 把 `<datasource>` 的 CDATA 原文**直接当原生 SQL**（不转实体→表、属性→列、参数），真实执行会报表不存在。
- `entityClass` 现在只喂「补全上下文」（`resolveEntityContext` → MyBatis `EntityResolver`），不参与语言判定。所以用户看到「表：base_location」会误以为 HQL 已识别。
- 这是 F-45 的历史欠账：`docs/memory-archive/bug-fixes/project-pages-xml-preview.md:16、60` 已写明 datasource 内容是 HQL、并把「HQL → SQL 转换预览（复用 HqlConverter）」列为后续项；测试还把「HQL 原样展示」钉成契约（`ui/PagesXmlPreviewServiceTest.kt:172-191`）。

## 判据：entityClass 是相关性信号，不是充分条件

反例（demo 自带）：`orderMaintain` 的 `entityClass=Order`，`<datasource>` 却是原生 SQL `FROM t_order`。⇒「有 entityClass ⇒ HQL」必误判。

判定顺序（首命中即定，默认 SQL）：
1. `<datasource type="hql|jpql">` → HQL（解析器现在把该属性丢掉了）
2. 根标识符 == entityClass 实体名（简单名或 `@Entity(name)`）→ HQL
3. 根标识符 == 该实体表名 → SQL
4. 根名解析为持久化实体且「≠ 其表名」或命中 HQL 记号（`JOIN FETCH`/`MEMBER OF`/`?1`）→ HQL
5. 其余 → SQL（宁可漏判，不可错判）

## 方案要点

- **复用 `previewHbmNamedQuery`**（`ui/SqlPreviewService.kt:1157-1206`，`XmlInlineSqlPreviewService` 已在用同一条）而不是新写 pages 专用转换；参数名序、IN 参数、方言决策、HBM 兜底它都已处理，重写必分叉。
- `#{...}` 先预处理成 `:name` 再进管道（字面量内不替换），复用 HqlConverter 的命名参数链。
- 新增 `PreviewResult.sourceLanguage/originalSql`：meta 行「HQL（已转换）」中性标记 + 「原文」只读页签；转换器降级说明（隐式关联路径等）压成一条进 warning——否则用户无法确认插件到底转没转。
- 三态开关（自动/仅 SQL/仅 HQL）作逃生舱；判错代价不对称（漏判=现状，错判=给出可执行错 SQL）。
- 最关键的待验证前提 P-4：样本 `location.nodeAddress.postcode` 的 `nodeAddress` 若为 `@ManyToOne`，现有转换器只给「需 JOIN」注释、产物不可执行（`HqlConverter.kt:370-402`）；`@Embedded` 才能直接展平。

## 状态：已实施并随 v2026.11.6-beta.1 发行（2026-11-06）

- 实施提交 `98554a4f`（主修）+ `d04ffa2d`（泛化）+ `8ce33ee4`（删零产出 `SourceLanguage.JPQL` 常量，`EnumReachabilityGuardTest` 红）。
- **泛化落地范围比原计划大**：页面标签名不再限定 `maintainPage`（listPage 等任意页面标签都认），`<dataValue>` 这类**无 entityClass 的通用 SQL 载体**同样走该判定。
- 设置项落地为三档「自研框架数据源语言（HQL/SQL）」：自动（默认，判不准按 SQL）/ 仅 SQL / 仅 HQL。
- 面板 meta 行「来源: HQL（已转换）」+「原文」只读页签；三种降级（dumb 态 / 根实体解析不了 / 转换异常）回退原文并提示，不报错。
- `#{SESSION_BASE_ORGAN}` 预处理成 `:SESSION_BASE_ORGAN` 走既有命名参数链；字面量内不替换；与 HQL 自带 `:x` 同名时合并为同一参数。

计划=`docs/开发计划/20261105-1516-T-PagesHql-maintainPage数据源HQL识别与实体推断转换-开发计划.md`。

相关：[[project-pending-plans]]、[[project-mapper-nav-shortcut-20261105]]
