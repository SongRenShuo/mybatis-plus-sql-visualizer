---
name: project-toolbar-icon-semantics
description: T-ToolbarIconSemantics（20261019 已实施）——四页签工具栏图标语义优化；用户定案「全部克隆为插件自带 SVG」以抹平 243/262 形态差异，含 MpvsIcons 注册表、21 组克隆+1 组自绘资源、定向 71/0 读数与守卫反向验证；附内联 SVG 预览图必须保留根 fill="none" 否则出现「蒙层」假象
metadata:
  node_type: memory
  type: project
  originSessionId: sess_92db21c12b1aced9f267fc30e9f7158f
---

# 四页签工具栏图标语义优化（T-ToolbarIconSemantics，已实施）

20261019 用户原话：「插件图标很多不够形象，例如第一眼看到不知道作用」→ 调研推荐 → 「开始实施调整代码」→ 追加「四个页面（SQL 日志 / 日志转 SQL / SQL→Java / SQL 历史）的图标都需要同步调整」。计划 `docs/开发计划/20261019-1315-T-ToolbarIconSemantics-插件工具栏图标语义与风格优化-开发计划.md`（dev 树），索引已登记并改为「已实施」。**代码已接线、定向测试已绿；未提交、未推送。**

## 最关键的定案：不引用 AllIcons，全部克隆成插件 SVG

用户中途一句「如果这些图标统一用我拍板的图，克隆成 svg 图标是否更合适 这样不同版本的 idea 也是相同的样式」直接改了实现路线。**这条是对的**：`AllIcons` 字段在 243 回落经典实心资源、262 用 expui 线性资源，同一按钮跨版本形态必然不同，靠平台字段永远做不到一致。

最终实现：
- `ui/icons/MpvsIcons.kt` 为唯一入口，按语义命名常量（`CaptureStart`/`Suppress`/`ScrollToEnd`/`TypeFilter`/`Export`…），内部 `IconLoader.getIcon("/icons/toolbar/<name>.svg", MpvsIcons::class.java)`，暗色由同目录 `_dark.svg` 自动选中。
- `src/main/resources/icons/toolbar/` 下 **21 组共 42 个克隆 SVG**（保留 JetBrains Apache 2.0 版权注释）：run/stop/pause/resume/clear/settings/search/dockedPreview/scrollToEnd/sortDuration/filter/help/paste/copy/format/export/back/refresh/diff/editSource/mapperXml。
- 唯一自绘：`eyeOff.svg`+`_dark.svg`（平台图标集无「眼睛+斜线」：`inspectionsEye` 纯眼睛、`toggleVisibility` 眼睛+列表、`hide` 横线、`hidePassed` 漏斗+彩点）。眼睛轮廓直接复用 `expui/general/inspections/inspectionsEye.svg` 的 circle+path，后加 `M3.2 12.8L12.8 3.2` 斜线；亮 `#6C707E`+`DB5860`、暗 `#CED0D6`+`DB5C5C`。
- 对照图证据：`_icon_probe/sheet11.png`（按 262/243 分节）、`sheet_cloned.png`（实际克隆资源 72px 放大）。

**由此带来的录屏收益**：243/253/262 渲染完全相同，动图**不必按 IDE 版本分别录**；但暗色是另一组文件，仍建议单独录一条。

## 最终选型（用户逐轮点名，八项）

| 按钮 | 选型 | 落点 |
|---|---|---|
| 捕捉开始 | `expui/run/run.svg` 绿三角（**用户最终拍板；我先推红点 `Profiler.Rec` 被否**） | `MpvsIcons.CaptureStart` |
| 捕捉停止 | `expui/run/stop.svg` 红方块 | `CaptureStop` |
| 增强暂停/恢复 | `expui/run/pause.svg` / `expui/run/resume.svg` | `AgentPause` / `AgentResume` |
| 清空 | `expui/general/delete.svg` | `Clear`（四页签共用） |
| 屏蔽规则 | 自绘眼+红斜线（用户选红斜线版，灰斜线与粗眼版均否） | `Suppress` |
| 滚动到底 | `objectBrowser/sortByType.svg`（**用户两次确认「就是滚动到底」**；我两次提示更像「按类型排序」仍被指定） | `ScrollToEnd`（日志+历史） |
| 停靠预览 | `expui/general/previewVertically.svg`（原 `Actions.PreviewDetails` 的 expui 侧） | `DockedPreview`（日志+历史） |
| SQL 类型过滤 | `expui/general/filter.svg` +「全部」文本，不再留 chevron（**用户最终定案「不用调整了」**；曾出 7 个候选 A~G，未采纳） | `TypeFilter` |
| 导出 | `expui/general/export.svg` | `Export`（历史右键原为 `EditSource`，日志右键原本无图标，两处统一） |

**别再推这些**（用户逐个换掉）：自绘「红点+捕获弧」捕捉、`Profiler.Rec` 捕捉开始、`Process.ProgressPause/Resume` 灰阶弱化、自绘「列表+红斜杠」屏蔽、灰斜线版本。

## 跨页只统一三组

停靠预览、滚动到底、导出。日志转 SQL 与 SQL→Java 的转换/格式化/复制/清空本就一致，**不改图形**，只把字面量收口到 `MpvsIcons`（对照表在计划 §11.2~§11.5，逐按钮列「实施前 / 实施后 / 动图取景」，是重录动图的直接依据）。

## 实施与验证

- 改 4 个面板（`SqlLogPanel` / `HistorySqlLogPanel` / `LogToSqlPanel` / `SqlToJavaPanel`）+ 新增 `ToolbarIconSemanticsTest` + 同步改 6 个既有测试的图标断言（`HelpUiTest`、`HelpDialogTest`、`HistorySqlLogPanelScrollToEndTest`、`HistorySqlLogPanelPreviewTest`、`SqlLogPanelLayoutTest`、`SqlLogTailFollowTest`）。
- 定向读数：**8 类 71 例 0/0/0**。
- 守卫反向：把 `MpvsIcons.Search` 改回 `AllIcons.Actions.Search` → 恰 1 红且报错精确指到 `SqlLogPanel.kt -> e.presentation.icon = AllIcons.`；还原后 4/0/0。
- 守卫规则：四面板禁止裸 `e.presentation.icon = AllIcons.` 赋值，白名单只放 `MpvsIcons`（类选择字段的 12px 缩放图标另行放行，仍用 `AllIcons.General.Ellipsis`/`Actions.Cancel`）。
- 未做：全量 `testShards`、`verifyGate`、runIde 真机双版本双主题视觉验收。

## 工具链上的坑（本轮实测，比图标本身更可复用）

- **`testFast` 自带固定包含清单（`fastBucketClasses`），叠加 `--tests` 会整体失败**：报 `No tests found for given includes` 并把整桶类名一口气打出来。定向测试一律用标准 `./gradlew test --tests "<FQCN>"`。
- Gradle 真返回值可能被管道吃掉（`| tail` 时偶见退出码 2 而无输出），但 **BUILD 结果仍可从任务日志判断**；更稳的读数法是解析 `build/test-results/test/*.xml` 的 tests/failures/errors。
- 对照图渲染：**Edge `--headless=new --no-sandbox --no-first-run` + 每次全新 `--user-data-dir`** 才能稳定出图；Chrome 本机反复失败。`--window-size` 高度不够会静默截断末尾小节（用户因此反馈「类型过滤下拉下面的看不到了」），宁可给大。
- **混用 243 经典资源与 262 expui 资源于同一张对照图会误导**（用户看的是 262）——必须按 IDE 版本分节，各节带实际 16px 尺寸条。
- 字段→资源的映射可从 `AllIcons$Xxx.class` 的 `<clinit>` 反解（putstatic 前两条 LDC），细节见 [[reference-platform-artifact-keymap-lookup]]。副产品：`Actions.ClearCash` 在 243 无经典变体（可能不渲染，禁用于清空）；`General.ArrowDown` 262 是 chevron/243 是三角；`General.Settings` 243 扳手/262 齿轮——这些跨版本差异正是「克隆」路线的动因。
- 新建文件默认 LF：本轮 52 个文件（含 42 个 SVG）须 PowerShell `ReadAllText`+`WriteAllText(UTF8 no BOM)` 批量转 CRLF，见 [[project-line-ending-policy]]。
- 索引 `docs/索引/开发计划.md` 会被并行会话并发改写（本轮一次 "File has been modified since read"、一次内容被追加），Edit 前重读。

## 后续：增强暂停按钮已按方案 B 临时全隐藏（2026-10-19）

`T-AgentPauseVisibility`（`8b797098`）把 `AgentPauseAction` 恒置不可见/不可用，动作对象仍留在 `toolbarGroup`。因此本计划里「增强暂停只在增强捕获开启时可见 / 开启时工具栏 11 个按钮」的口径**已全部改写为「方案 B 临时全隐藏、工具栏恒 10 个可见动作」**（计划 §四 表第 2 行、待验证前提第 7 条、拍板表第 3/4 行已同步）。图标冲突检查继续按「可见集合」而非「动作集合」。详见 [[project-agent-pause-visibility]]。

## 20261022 追加两枚 Mapper 配对跳转图标（未提交）

用户要「mapper.java ↔ mapper.xml 方法跳转」的 SVG（来源与定位过程见 [[reference-platform-artifact-keymap-lookup]]），随后要「转一份成项目里现有的命名风格」，最终称 `mapperMethod` / `mapperStatement` 落进 `src/main/resources/icons/toolbar/`，`MpvsIcons` 加 `MapperMethod` / `MapperStatement` 两项，并登记进 `ToolbarIconSemanticsTest` 的加载清单（4 个用例 4/0/0/0；反向删掉 `mapperMethod.svg` 恰 1 红，报 `MapperMethod 宽度 expected:<16> but was:<1>`——即资源缺失退化为 1×1，这条断言真能兜住）。

**用户在配色上连纠两次，这条比图标本身重要**：

1. 我先做了「近黑主体 + `_dark` 提亮」两份，用户先说只要一份 → 我把主体直接改成中性灰 `#C7CAD1→#9DA0A6`，并**删掉了 `_dark`**。
2. 用户随即质问「你是不是留错了，`icon/mybatis_java.svg` 和 `icon/mybatis_xml.svg` 才是正确的内容，现在怎么是灰色的」——**「不要浅色版本」指的是不要我造的光栅/主题变体，不是允许我改原图配色**。已恢复：鸟身 `#1A1A1A` 近黑 + `#000000→#6D5656` 渐变，品牌色带 `#D40000`(红) / `#00CBCC`(青)。

**转化允许改的只有包裹层**：去掉 `<!DOCTYPE>`（外链实体解析风险）、补 `viewBox="0 0 16 16"`（原图只有 width/height）、渐变 id `1/244/540` → `mpvsGradA/B/C`（纯数字 id 拼进同文档会撞）。几何与配色一律原样搬运，`path` 的 `d`+`transform` 与源图 list 全等。

**遗留风险**：项目惯例是每个图标带 `_dark` 兄弟（近黑图形在深色主题下几乎不可见）。用户本轮明确不要变体，故这两个只有单份原色文件；若深色主题下糊进背景，补 `_dark` 只需换颜色值、几何不动。

## 20261022 同轮：自研「跳转图标」六组候选与 16px 几何红线

用户随后要求「参考 sql 预览图标风格（代码+放大镜）自己设计几组跳转图标：代码 + 跳转符号」，**明确「不考虑其余三套方案」**（只要 DEFAULT 小鸟这一套来源）。已出 A~F 六组，落在会话可视化目录 `jump-icons/`（`index.html` 对照页 + 六个 SVG，未入库、未定选型）。

风格基准取自 `icons/sqlGutter.svg`：代码符号绿 `#4FB477`、辅助元素紫 `#B48EFF`、浅色垫底 `#F4EEFF`、线宽 1.6~1.8 + round 端点。徽标圆心/半径直接照抄 `(11.4, 11.0) r=3.38`。

**四轮渲染实测得到的几何红线（比配色更可复用）**：

1. **徽标内不能放细线图形**。圆直径 6.8px、描边 1.6 ⇒ 内部可用不到 4px，线宽 1.5 的箭头必然糊成一团；参考图里放大镜「柄」之所以成立靠的是**圆+柄的整体轮廓**，不是圆内细节。徽标内一律改用**实心三角/实心形状**。
2. **16px 装不下「完整代码符号 + 完整箭头」**。`< / >` 本身已占约 13px 宽，右侧硬塞箭头必然压到 `>` 上（A/C/E/F 首版全部重叠）。让位只有两条路：徽标压在右下角（与参考图放大镜同位），或代码整体缩小左移。
3. **`transform=rotate` 加工小三角会退化成直角三角形**（C 首版 `rotate(-45)` 实测），小尺寸下别用旋转，直接写坐标。
4. 自绘多笔触箭头在 16px 下不可读（F 首版双三角太碎、C 的折线叠加错乱）。

**验证方法定例**：六组必须**真渲染**才判得出好坏——Chrome `--headless --disable-gpu --no-sandbox --hide-scrollbars --screenshot=` + **每次全新 `--user-data-dir`**（复用同一 profile 目录会静默不出图，本次连续失败 5 次都是这个原因）；对照页同时排 16px 实际尺寸与 180~256px 放大两档，只看放大图会漏掉「糊成一团」，只看 16px 会漏掉「几何重叠」。详见 [[reference-platform-artifact-keymap-lookup]]、[[feedback-ui-match-existing]] 的「不要浅色版本」条。

## 关联

- 动作顺序与「不要自建『更多』」：[[project-sql-log-tail-follow-gap]]。
- 「用户说参考 XX / 照抄既有形态」的通用口径：[[feedback-ui-match-existing]]。
- 图标式 ActionToolbar 只设 text 不等于显示文字：[[feedback-ui-match-existing]]。
- 计划登记与状态：[[project-pending-plans]]。
