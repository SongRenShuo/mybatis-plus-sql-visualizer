# T-PreviewPolish：首开降级误报 / 弹窗选文件 / 缺失片段补齐 / 按钮本地化（开发计划 + 实施记录）

- 立项：2026-11-18（用户四条报障后当场实施）
- 任务源（用户原话）：「问题一 简化模式的情况还是有 问题二 我希望这个弹窗 也可以直接选择xml 这样方便一些 还有 全量测试交给ci」＋「还有这个弹框上的按钮 没有适配插件的语言」
- 状态：**已实施**（全量门禁按用户令交 CI，本地只跑定向）

## 一、问题一：简化模式横幅仍有

### 1.1 根因（二轮定位）

第一轮修的是「产物在途被标成已消费」（`fallbackDegradationReason`：在途 → TIMEOUT 宽限静默）。用户复测仍有横幅，且文案是「完整编辑器尚未就绪」= `PRODUCT_CONSUMED` 档——说明这次不是「在途」，而是**产物确实已被消费**：

- 面板编辑器在 `removeNotify`（标签切换 / 工具窗隐藏 / 重排）时释放；
- 重建时 `assemblePreviewEditor` 的 `prewarmed == null` 且预热 future 已完成 → 旧逻辑直接判 `PRODUCT_CONSUMED` → 立即弹「未就绪」硬失败横幅。

即：这是**编辑器生命周期**问题，不是预热慢。

### 1.2 处置

`assemblePreviewEditor` 的兜底分支：产物不可用且预热已结束（或从未预热）时，**自动补一轮预热**（`retryPrewarm()`），把语义拉回「在途」→ 走宽限期静默；新产物发布时复位 `prewarmAutoRetried`，保证下次重建还能自动补。只自动补一次，防预热持续失败时的自动循环。

## 二、问题二：弹窗支持选 XML 文件

`SqlPreviewDialog` 底部新增「从文件加载…」：`FileChooser`（xml 过滤器）→ 读文本 → 走**与粘贴同一条识别链**（`StandaloneSqlSourceResolver`）→ 回填面板并交还手动。选文件只是换个取文本入口，不存在第二套口径。

## 三、问题三：缺失 `<include>` 片段（上一轮 A+C 券的落地）

- **C 占位**：iBatis 侧 `SqlMapScriptBuilder.buildInclude` 找不到片段时不再 `emptyList()`（残句），改为插入 `/* TODO: missing <sql id="…"> */` 文本节点——结构完整、可读、不引入占位符（`?` 位点不受影响）。
- **A 补齐**：工作台底部新增「缺少片段：xxx —— 点此补上内容」，点击弹 `MissingFragmentDialog`（多行输入），补齐内容存 `fragmentOverrides`（**不污染用户输入**），解析前拼到文本前；补齐后立即重渲染，缺失集合自然收敛。
- **MyBatis 侧已对齐（用户 2026-11-18 二次拍板「可以加」）**：`XmlIncludeExpander` 的两条缺失分支（同文件、跨命名空间/歧义）统一走 `replaceWithMissingPlaceholder`；`XmlCrossNsFragmentResolverTest` 4 处 SQL 文本断言改为「含 TODO 占位」，警告文案断言原样保留。

## 四、问题四：对话框按钮未本地化

`MissingFragmentDialog` 用平台默认 OK/Cancel（随 IDE 语言包，插件语言独立）。补 `setOKButtonText` / `setCancelButtonText`（键 `standalone.missingFragment.ok` / `dialog.preview.cancel`）。

## 五、验证读数（本地定向；全量按用户令交 CI）

- `PreviewFallbackReasonTest` 5/5（在途→TIMEOUT / 失败→失败原因 / 已交付→已消费 / 无载体→已消费 / 显式优先）
- `MissingSqlFragmentSupportTest` 8/8（检出、namespace 同名不误报、排序、包壳与整段两种补齐形态、补齐后收敛）
- `StandaloneSqlSourceResolverTest` 30/30（含「缺失 include 渲染 TODO 占位」新例）
- `SqlStandalonePreviewPanelDialogTest` 5/5、`WarningVisibilityTest` 4/4、`XmlFlavorDetectorTest` 9/9、`StandaloneTabRegistryGuardTest` 5/5
- 横幅/降级/本地化相关：`SqlPreviewPanelDegradedUxTest`、`DegradationBannerTest`、`i18n.*` 全绿
- 合计 **89 例 0 红**
- **反向验证**：撤掉「在途→TIMEOUT」与「TODO 占位」两处 → 各精确红 1 条；恢复后全绿

## 六、测试夹具的一处调整（非绕过）

`SqlPreviewPanelDegradedUxTest.degradePanel()` 要的是**稳定降级态**；自动补预热会让该态变瞬时。夹具显式置 `prewarmAutoRetried = true` 保留原语义，并写明「这是测试意图」。

## 七、遗留

- ~~MyBatis 侧缺失 include 仍是「抹掉 + 报错」~~ → 已对齐（见 §三）。
- 选文件入口只加在弹窗；工作台页签仍以粘贴为主（如需可同款加一个）。
- 自动补预热只补一次：若预热本身持续失败，仍需用户点「重试」（这是有意的，避免自动循环）。
