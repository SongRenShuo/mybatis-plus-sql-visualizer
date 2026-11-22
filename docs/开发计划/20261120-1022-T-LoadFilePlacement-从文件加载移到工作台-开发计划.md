# T-LoadFilePlacement：把「从文件加载」从通用预览弹窗移到独立工作台 开发计划

- 立项时间：2026-11-20 10:22
- 任务源（用户原话）：「上次在sql预览窗口直接加了从文件加载 按钮 我认为这个地方的这个 按钮不合适 因为这个sql预览窗口是通用的 是否有更优的解决方案」
- 关联：`20261118-1247-T-StandaloneDialogOpen`（工作台与弹窗的职责边界）、`20261118-1653-T-PreviewPolish`（本轮把该按钮加进弹窗的那一笔）
- 状态：**已实施（master `0611f6ab2`），待 GUI 验收**

## 一、问题：能力放错了层

### 1.1 事实：这个弹窗是**全插件共用**的

`SqlPreviewService.showResult` 的调用方（`grep -rl` 实测 21 个文件）：

| 类别 | 入口 |
|---|---|
| 语句预览 | `ConvertToSqlAction`（Wrapper / mapper / iBatis / 注解 SQL / pages 五路） |
| gutter 图标 | 18 个 `*GutterLineMarkerProvider`（MyBatis、iBatis、JPA/HQL、JPQL、NamedQuery、HBM、JdbcTemplate、String SQL、CriteriaQuery、XmlInline、PagesXml…） |
| 日志/历史 | `SqlLogPanel`、`HistorySqlLogPanel`、`EntrySourceMatcher` |
| 其它 | `CrudSqlPreviewFlow`、独立工作台「在弹框中打开」 |

也就是说，这个弹窗的定位是**「只读地展示一条已经渲染好的 SQL」**——它不负责「从哪弄来这段 SQL」。

### 1.2 「从文件加载」的语义与之冲突

- 它是**输入源能力**（取文本），不是展示能力；
- 放在通用弹窗里，18 个 gutter 入口打开时都白背一个按钮，用户会问「我点它会把当前这条 SQL 换成文件内容吗？」——语义歧义；
- 更糟的是它**静默替换**当前内容：原预览是「某条 mapper 语句的渲染结果」，一点按钮就变成「另一个文件的内容」，来源、方言、参数体系全换，但弹窗外观没变。

### 1.3 结论

不是按钮本身没用，是**层位错了**：它属于「输入区」，而输入区只有一个——独立工作台的输入编辑器。

## 二、候选方案

### 方案 A：移到工作台输入工具栏（推荐）

工作台输入区上方那排（`解析预览 / 执行 / 格式化 / 清空 / 在弹框中打开`）加「从文件加载…」；弹窗里**删掉**该动作。

- 优点：能力归位到「取文本」的那一层；通用弹窗恢复纯粹；与「粘贴」「贴入即识别」同处一区，用户心智一致；
- 缺点：在弹窗里想换文件时要回页签（但弹窗本来就是"看结果"的地方，换输入本就该回工作台）。

### 方案 B：弹窗内条件显示（仅工作台来源才出现）

给 `showResult` 加来源枚举或布尔，工作台开窗时带 `allowFileLoad=true`。

- 优点：弹窗里仍能换文件；
- 缺点：**仍然把输入能力塞进展示层**，只是缩小了暴露面；`showResult` 参数继续膨胀；且「同一个弹窗在不同入口长得不一样」本身是维护负担。不推荐。

### 方案 C：A + 拖放（加分项）

A 之外再支持把 XML 文件拖到工作台输入区（`VirtualFileDropHandler` / `TransferHandler`）。属于同一能力面的第二种入口，成本低但需真机验证 DnD。

### 方案 D：维持现状

不推荐，理由见 §一。

### 推荐：A（可选追加 C）

## 三、实施拆解（方案 A）

- **D-1 抽出读文件能力**：新建 `ui/standalone/MapperFileLoader`（或并入既有 `MissingSqlFragmentSupport` 同族文件）
  - `loadText(project, file)`（ReadAction + `VfsUtilCore.loadText`）；
  - `chooseAndLoad(project, component)`（`FileChooser` + xml 过滤器）；
  - 现有 `SqlPreviewDialog.loadFileIntoPreview` 与 `MissingFragmentDialog.pickFromFile` 两处重复的读取逻辑一并收拢（后者只多一步 `extractFragment`）。
- **D-2 工作台加入口**：`SqlStandalonePreviewPanel` 工具栏加 `LoadFileAction`
  - 选中文件 → 文本写入输入编辑器（**替换**当前输入）→ 直接 `resolveFromInput()`；
  - 状态条如实标注来源：「已从 <文件名> 加载」；
  - 写输入必须走 `inputEditor.setSql`（既有程序性写入通道，抑制补全 autopopup）。
- **D-3 弹窗移除该动作**：`SqlPreviewDialog.createActions` 删「从文件加载…」；`showResult` 的 `autoParseOnPaste` 通道**保留**（贴入即识别仍然有用，它服务于"空弹窗"场景）。
- **D-4 文案与清理**：删 `dialog.preview.loadFile` / `dialog.preview.loadFile.title` 两键（en/zh）；新增 `standalone.action.loadFile` / `standalone.action.loadFile.title` / `standalone.status.loadedFile`。
- **D-5 测试**
  - `SqlPreviewDialogTest` 的动作数断言 5 → 4（并更新文案）；
  - 新增工作台用例：注入文件路径（测试缝）→ 输入区内容变为文件文本、识别结果随之产出；
  - 反向验证：把动作重新加回弹窗 / 去掉工作台按钮，对应用例必须变红。

## 四、待验证前提（P-x）

- **P-1**：工作台替换用户输入是否需要确认框。倾向**不确认**（点击=明确意图），但若输入非空且与文件内容差异很大，状态条必须写清「已替换原输入」——除非用户反馈需要确认。
- **P-2**：`FileChooser` 的文件类型过滤用 `createSingleFileDescriptor("xml")`；iBatis 文件可能叫 `*_SqlMap.xml` 也在 xml 内，无需额外过滤（与弹窗旧实现一致）。
- **P-3**：文件较大（> `MAX_TEXT_LENGTH` 100k）时的降级：既有 `resolveFromInput` 已有超长分支（写状态条不回显），加载时应先判长度再写入，避免把编辑器撑爆。
- **P-4**：`MissingFragmentDialog`（补齐片段框）里的「从文件选择…」**保留**——它不是通用弹窗，是「补一段内容」的专用框，语义正确；但应复用 D-1 的读取实现。

## 五、验收（A-x）

- **A-1** 从任意 gutter 图标/日志/历史打开预览弹窗：底部只有 `执行 SQL / 复制 SQL / 复制 SQL 并关闭 / 取消` 四项，**没有**「从文件加载」。
- **A-2** 工作台工具栏出现「从文件加载…」：选一个 mapper XML → 输入区变为文件文本、预览区随之出结果、状态条写「已从 <文件名> 加载」。
- **A-3** 加载超长文件（>100k）→ 不写入、状态条给「过长」提示，编辑器内容不变。
- **A-4** 补齐片段框的「从文件选择…」仍可用，且只抽对应 `<sql id>` 内容（回归）。
- **A-5** 空弹窗贴入识别（`autoParseOnPaste`）不受影响。

## 六、风险

- `SqlPreviewDialogTest` 的动作数断言是本轮刚改过的（4→5），这次要**回改成 4**——两次改动都属"契约随能力变化"，注释里要写清为什么变，避免下一个人以为被谁偷偷改过。
- 工作台工具栏目前已有 5 个动作（`解析预览/执行/格式化/清空/在弹框中打开`），再加一个会挤——P-3 顺带确认窄侧栏下是否溢出；溢出由平台 `ActionToolbar` 自动折叠，可接受。
- 拖放（方案 C）不进本轮；单独评估（DnD 在工具窗页签上的可用性需真机验证）。

## 七、实施记录

实施于 2026-11-20，master 提交 `0611f6ab2`（代码+测试），文档随后的提交回填本节与 `CHANGELOG.md`。

### 7.1 逐项落点

- **D-1**：新增 `ui/standalone/MapperFileLoader`——`choose(project, titleKey)` 弹单文件 xml 选择框并读回文本，
  `loadText(file)` 包读权限（243 编译目标下 `ReadAction.compute(ThrowableComputable)` 唯一重载）与失败/空文本兜底。
  `MissingFragmentDialog.pickFromFile` 改为复用（P-4：该框保留，语义是「补一段内容」而非通用展示）。
- **D-2**：`SqlStandalonePreviewPanel` 输入工具栏加 `LoadFileAction`（`MpvsIcons.MapperXml`）：选文件 → `inputEditor.setSql`
  替换输入 → `resolveFromInput()`。来源挂在 `loadedFileName` 字段上，状态行以「识别结果 ｜ 已从 X 加载」呈现——
  异步解析会覆盖加载提示，不挂住来源用户就分不清「这是文件里的还是我贴的」（P-1：替换不弹确认，但替换既有输入时
  文案写明「原输入已替换」；P-3：超长文件不写入，只给提示）。工具栏动作序列抽成 `inputActions` 字段，用例据此断言
  接线（只测动作类存在抓不到「忘了加进 group」）。
- **D-3**：`SqlPreviewDialog.createActions` 删该动作（5→4）；连带删除随之失效的 `loadFileIntoPreview` / `reparseText`
  与只写不读的 `suppressPasteReparse`。`autoParseOnPaste` 通道**保留**（A-5 不受影响）。
- **D-4**：删 `dialog.preview.loadFile` / `.title`；新增 `standalone.action.loadFile` / `.description` / `.title` 与
  `standalone.status.loadedFile` / `.replaced` / `.tooLong`，中英同步。
- **D-5**：`SqlPreviewDialogTest` 动作数 5→4（注释写明 4→5→4 两次变化的原因，防后来者以为被偷改）+ 新增
  「弹窗无该入口」用例（按旧文案中英字面量兜底断言，键已删）；新增 `SqlStandaloneLoadFileTest` 4 例：替换输入并解析出语句
  （含来源文案）、替换既有输入的文案、超长不写入、工具栏挂载。

### 7.2 读数

- 定向：`SqlPreviewDialogTest` 13 + `SqlStandaloneLoadFileTest` 4 + `SqlStandalonePreviewPanelDialogTest` 5 = **22 例 0 红**
  （`build/test-results/test` XML 实测计数，非仅看退出码）。
- 反向验证（恢复后复跑全绿）：
  1. 把该动作加回弹窗 → `testConstructionTimeActionArrayHasNoNullSlots` + `testDialogHasNoLoadFromFileAction` 变红；
  2. 从工作台 `inputActions` 去掉 `LoadFileAction()` → `test从文件加载入口挂在输入工具栏` 变红。
  合计**恰 3 红**（17 例 3 红），与预期一一对应；恢复后无残留探针（`grep RED PROBE` 为空、`git diff` 空）。
- `testFast` 全桶 **5107 例 3 红**，全部在 `O147HistoryUpgradeTest`，根因是本机 C 盘可用仅 449MB（`IOException: 磁盘空间不足`，
  与 2026-11-06 告急同形态），与本次改动无关；全量门禁按约定交给 CI。

### 7.3 附带修与遗留

- `SqlStandalonePreviewPanelDialogTest.test在弹框中打开_未识别文本也弹窗` 的瞬时断言按 **LESSONS L90** 改成轮询
  （本轮本地首跑即复现：拿到弹窗时面板异步装配未完成，`currentWarning` 读到空串，同 src 重跑全绿）；
  `iBatis 片段不报 Unknown element` 同步改成「装配期内持续观察」——成功识别时 warning 本来可能为空，不能要求非空。
- `docs/验收文档/20261119-1130-…` 的 A9「弹窗从文件加载」属 `2026.11.19-beta.2` 版本快照，保留不动；新形态以本节为准。
- 待办：A-1~A-5 的 GUI 验收待用户；方案 C（拖放）按 §六 不进本轮。
