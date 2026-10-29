---
name: project-fieldfill-panel-ux
description: FieldFill 参数面板最终形态（b6a88945 已提交）——名称+[fill=...]+普通值同一行、模拟值行随开关显隐、模拟值优先且普通框置灰、说明行灰字；含一个通用教训：用户说「同一行」指整条输入行
metadata:
  node_type: memory
  type: project
  originSessionId: session_7c4f9878c3e52efd1a834ad34e2354ac
---

# FieldFill 参数面板布局与语义（2026-10-19 用户三次定案）

## 用户的原话与结论（按时间顺序）

1. 「`[fill=INSERT]` 是不是错位了」「FieldFill 安全模拟有什么用」→ 解释三行分组。
2. 拍板：**「order.create_time [fill=INSERT]」这样比较合适，并且模拟值相关应该只有勾选 FieldFill 安全模拟时才显示**。
3. **首版 `8bd0c478` 被判「没完成」**——只把 `[fill=...]` 并进字段名行，普通参数值输入仍留在下一行；用户贴截图「角标与字段名同行没完成 目前不少一行」。他要的是**名称 + 角标 + 普通值三者同一行**。修正 `9c1a929c`。
4. **`b6a88945`（第三轮）**：「出现模拟值时 原本的值输入框应该置灰 防止迷惑用户」+「这行字改成灰色说明并优化下内容 目前有点难理解」。

## 布局落点（最终形态 `9c1a929c`）

- `SqlPreviewPanelParamPanel.buildBatchColumnRow`：
  - **名称+角标+值同一行**：`addCell(checkBox, 0, 0)` 与 `addCell(field, 1, 0)`（gridy 都是 0）。角标**并入复选框 HTML 文本**——`checkBox.text = fieldFillText("${col.source} [fill=${col.fill}]", font, JBUI.scale(200))`，注解 tooltip 与 source tooltip 用 `joinToString("\n")` 合并。
  - **模拟值行 gridy=1**（`addCell(simulationRow, 0, 1, 2)`），`isVisible = fieldFillEnabled`。
  - 固定 fill 列（非 toggle）仍用 `fieldFillNameCell(name, fill)`：FlowLayout 里 `[标签] + JBLabel("[fill=…]")`，与值输入同一行。
- `SqlPreviewPanel` 三个字段：`fieldFillFields`（模拟值输入）、**`fieldFillNormalFields`（source → 普通输入框，用于置灰）**、`fieldFillSimulationComponents`（模拟值行）；`rebuildLeftPanel` 里三者一起 `clear()`。
- `applyFieldFillSimulationVisibility()`：可见性 + `syncFieldFillNormalEnabled()` + revalidate/repaint；在安全模拟复选框 `addActionListener` 里、`renderSql()` 之前调用。
- `fieldFillText(text, font, limitPx = JBUI.scale(260))`：fill 列复选框传 **200px**。

## 模拟值优先 + 普通框置灰（`b6a88945`）

- `syncFieldFillNormalEnabled()`：`overridden = fieldFillEnabled && fieldFillValues[source] 非空` → `field.isEnabled = !overridden`；tooltip 换成 `fieldFill.simulation.overrides`（「已由模拟值覆盖；清空模拟值后可改回手填」）。
- **语义随之改为「模拟值优先」**：`fieldFillOverlay` 里 `explicitValues = if (fieldFillEnabled) paramValues.filterKeys { fieldFillValues[it].isNullOrBlank() } else paramValues`——被模拟值覆盖的普通参数从 explicitValues 剔除。清空模拟值/关开关后普通参数立即生效。
  - ⚠️ 这与旧口径「显式参数优先」相反，回答用户时必须用新口径。
  - **🔴 b6a88945 只改了一半（UI-031，2026-10-19 用户真机捉出）**：面板对所有 fill 字段都给模拟行并置灰普通框，但模型层 `FieldFillSimulation.overlay` 里还有一道 `applies(kind, fill)` 闸门（UPDATE 只认 `fill=UPDATE/INSERT_UPDATE`）⇒ UPDATE 预览中 `[fill=INSERT]` 的 `create_time` 模拟值被**静默忽略**、SQL 回落灰框值（用户截图：灰框 `111`、模拟值 `111212`、生成 `create_time = '111'`）。**修法**：删 `applies`，INSERT/UPDATE 下模拟值非空即覆盖（UPSERT 仍不模拟，UI 已披露），explicit 优先守卫与面板预剔一并去掉，由模型单点裁决。既有契约用例 `testEntityCrudActionSpecFeedsInsertAndUpdatePanels` 按新契约改成「两语句下手填值均生效」。读数 47/0/0（含 CRUD 生成器），反向恢复旧闸门**恰 3 红**。
  - **通用教训**：UI 承诺（置灰+tooltip「已由模拟值覆盖」）与取值层必须是**同一个判据**；改优先级时面板侧与模型侧要同时改，否则用户看到的和生成的对不上。
- 模拟值输入 `DocumentListener.changed()` 里加 `syncFieldFillNormalEnabled()`；`fieldFillOverlay` 在 preview 代际失效分支里也调一次。
- **说明行改灰**：`fieldFillStatus.foreground = UIUtil.getContextHelpForeground()`（无效值仍 `JBColor.RED`，测试断言两态颜色）。
- 文案重写（zh/en 同步，新增 `fieldFill.simulation.overrides`）：
  - status：「仅用于预览：用手填内容模拟自动填充效果，不执行真实的填充处理器，结果可能与实际运行不同；UPSERT 不模拟。」
  - enabled.tooltip：「用你填写的值模拟自动填充；不执行 MetaObjectHandler，结果可能与实际运行不同」
  - value.tooltip：「模拟字面量；有值时普通参数框置灰，由模拟值生效；数值列仅接受数字；不接受分号或脚本」

## 测试改动要点

- 布局断言三处（控件集合 / `Simulation input has its own row` / `Input must be scroll-reachable`）都要 `if (!p.fieldFillEnabled)` 跳过——隐藏组件 bounds 为 0，硬断言假红。
- `testFillBadgeAndValueStayOnOneRow`：`assertSame(cb.parent, 值输入.parent)` + 手动 `row.setSize/doLayout()` 后比较 `cb.y` 与 `field.y`，**容差 ≤8px**（复选框行内 3px inset、输入框 5px；严格等值假红，实测 `cb.y=3, field.y=33` 才是真分行的判别值）。
- `testSimulationValueOverridesNormalInputAndRestoresOnClear` / `testFreshPreviewRecallsLiteralButNotEnableStateAndSimulationWinsWhenPresent`：断言置灰、模拟值覆盖普通参数、清空后恢复。
- **长错误高度断言要 source 够长**：`longSource = "auditCounterWithoutWhitespace".repeat(8)`（4 次那版在文案变短后不再折行，`Long error must increase…` 假红）。
- 反向读数：把值输入改回 `if (hasFill) 1 else 0` → 恰 3 红；摘角标 → 2 红；摘显隐刷新 → 3 红；普通输入永不置灰 → 2 红；模拟值不优先 → 2 红。

读数：`FieldFillSimulationPanelTest` 13/0/0/0 + `FieldFillSimulationTest` 3/0/0/0。

## 教训

用户说「同一行」时，指的往往是**整个"字段名+注解+值"输入行**，不是「把这个标签挪到名字旁边」。首版只做标签合并就提交，返工一轮。改这类参数行布局前先问清楚：同一行包含哪些控件。

## 语义提醒（回答用户「这功能有什么用」时的标准口径）

`FieldFillSimulation` 是**只认手填字面量**的叠加层，不是 `MetaObjectHandler` 模拟器：不执行用户类、不读时钟/账号、UPSERT 不填、数值列只收数字、拒绝分号/反斜杠/控制字符；开关不持久化，每次新预览默认关。**模拟值在场时它优先**（对应普通框已置灰）；模拟值为空则普通参数照常生效。

相关：[[project-ai-context-v2-unreadable]]（同轮 AI 上下文修复）、[[feedback-ui-match-existing]]（UI 形态须与既有对齐）、[[pitfall-fake-green-test-assertions]]（隐藏组件 bounds=0 的假绿/假红形态）；提交链见 [[project-dml-impact-editor-replacement]]。
