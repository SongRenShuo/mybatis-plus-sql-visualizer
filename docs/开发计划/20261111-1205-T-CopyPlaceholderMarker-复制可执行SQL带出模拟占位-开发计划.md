# T-CopyPlaceholderMarker：复制可执行 SQL 带出模拟占位——开发计划

- 立项：2026-11-11
- 范围：`ui/components/CopyExecutableSqlAction.kt`、`share/SqlShareManager.kt`、测试两处
- 触发：T-MbgExamplePreview 收尾复查时发现——`{{表达式}}` 占位是预览专用标记，复制链没有处理

## 一、现象

MBG Example 的预览产物是 `WHERE ( {{criterion.condition}} ? )`（T-MbgExamplePreview R1 的占位形态）。
点「复制可执行 SQL」后，剪贴板里原样带出 `{{…}}`，粘到数据库必然语法错，且没有一句说明这些段是什么。

现状分两条链：

- 预览面板的复制（`CopyExecutableSqlAction.assembleProduct`）：已有 `-- contains ${} raw segments (mock values)`
  头部标记，但文案对占位场景不准确——产物里不是「mock value」，而是压根没填；
- 分享面板的复制（`SqlShareManager` 调 `ExecutableSqlFormatter.format`）：**没有任何标记**。

## 二、根因

`{{表达式}}` 由 `TextSqlNode` 在预览占位模式下产出（`emptyDollarPlaceholder=true`），它不是 SQL 注释，
`SqlCommentStripper.strip` 不会改它；两条复制链都只认自己的入参，没有识别这个形态。

## 三、方案

1. 新增常量（与既有 marker 同形态、同语言）：

   ```
   -- contains N unresolved ${} placeholders shown as {{...}}; replace them before executing
   ```

   计数 = 产物里 `{{` 的出现次数，让读者知道有几处要补。

2. `assembleProduct`：strip 后的产物含 `{{` → 用这条新标记**替代**旧的 `DOLLAR_RAW_MARKER`
   （同一件事不说两遍）；不含占位时旧的 raw 标记行为逐字节不变。

3. `SqlShareManager` 的两条「复制可执行 SQL」复用同一常量（面板侧已有
   `SqlPreviewPanel.hasSimulationPlaceholders()` 读口）。

4. 文案用英文：注释会随产物走到 PR / 聊天 / 工单里，与既有 marker 同语言；UI toast 仍走 bundle 中文。

## 四、验证

- 纯函数 `assembleProduct`：
  - 含占位 + `hasDollarRawSegments=true` → 头部只有新标记（计数正确）且不含旧标记；
  - 无占位 + `hasDollarRawSegments=true` → 仍是旧标记；
  - 两者皆无 → 产物逐字节不变。
- share 链：`hasSimulationPlaceholders()` 为真时产物头部带标记（抽纯函数可测）。
- 反向：摘掉占位分支 → 含占位用例转红。

## 五、边界（不做）

- 不把 `{{expr}}` 替换成 `/*expr*/`：那样产物「看起来能执行」而语义错位，比显式占位更危险。
- 复制不弹确认框：弹窗克制只留给执行（T-IG-03 定过），复制无不可逆后果。
- 不拒绝复制：用户常常就是想拿去手工补。
- 不动执行 / EXPLAIN 链：那条链有确认框，T1 已给它加了更强文案。

## 六、实施记录

（待填）