---
name: project-display-mask-and-degraded-preview-gaps
description: UI-021（INSERT VALUES 展示脱敏对位错位，已修）与 UI-022（曾判「降级态动态条件联动失效」，20261020 沙盒实测不成立）两缺陷定案
metadata:
  node_type: memory
  type: project
  originSessionId: sess_13bd728a-c47f-4204-a267-f528b9f57f9d
---

2026-10-18 T-DevFullUI 全量验收产出的两个「真实缺陷」。**UI-021 已修（`ca5300c9`，定向 22/0/0 + 反向恰 3 红）**；**UI-022 于 2026-10-20 被沙盒实测推翻**：

**UI-021 — INSERT VALUES 展示脱敏列名-值对位错位**
- 现象：`displayMaskEnabled=true` 时 `INSERT ... VALUES ('ui_mask_probe','Secret123X','mask@probe.test',…)` 展示为 `'ui_mask_probe', 'Secret123X', '***'`——password 列的 Secret123X **漏打**，紧随的 email 值**误打**。
- 根因：`DisplayMasker.columnHintBefore` 只取「字面量前 48 字符内最后一个标识符」当列名 hint；VALUES 里字面量之间没有列名，于是**前一个字面量值被当列名 hint**（Secret123X 的 hint 不含关键词→漏打；email 的 hint=Secret123X，lowercase 含 `secret`→误打）。
- 判别实证：JDK21 直调 `build/classes/kotlin/main` 的 `DisplayMasker.INSTANCE.maskSqlText`（classpath 要 kotlin-stdlib-2.0.20.jar）；对照 `WHERE password='Secret123X'` 正确打码。测试盲区=`DisplayMaskerTest` 17 例无 INSERT VALUES 场景。
- 修复：`INSERT_COLUMNS` 正则解析列清单，maskSqlText/parameterize 按 LITERAL 出现序取列名，解析失败/超列数逐位回退旧规则；增 5 例。

**UI-022 — 「降级模式（内存 PSI）下动态条件勾选联动失效」→ 2026-10-20 判为不成立**
- 原现象：降级横幅在场时勾选 XML 动态条件复选框，SQL 不追加 WHERE；同面板完整模式同操作立即出现 `WHERE status = 1`。当时只做了披露面最小修复（横幅补「参数联动与动态条件勾选在降级期不生效」）。
- **翻转证据**：用户 2026-10-20 在 243 沙盒按验收配方实测，降级横幅在场时**勾选复选框与参数输入都正常生效**（截图可判）；插件侧两条 headless 用例（降级态改 `paramValues` 后 `renderSql`、降级态点条件复选框）也全绿。故 2026-10-18 那次的「不生效」很可能是当时看到的**是首开降级之前的旧渲染**或操作时机问题，不能作为缺陷证据。
- 处置：旧文案里那句断言已从横幅正文移除（只留在悬停提示）；两例留作回归护栏。**横幅层面真正的问题是「首开必降级」**（方言重解析与预热 PSI 的竞态），已单独修，详见 [[project-degraded-preview-ux]] 的 P4 节。

关联：[[pitfall-fake-green-test-assertions]]（headless 白盒绿不能当 GUI 缺陷已修）、[[runide-demo-verify-workflow]]（真机取证姿势）。
