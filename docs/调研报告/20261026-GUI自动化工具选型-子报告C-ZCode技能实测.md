# ZCode 内置技能（computer-use / 视觉模型）对 IDE 演示录制可用性实测

- 日期：2026-10-26
- 被测环境：IntelliJ IDEA 2026.2.1 沙盒，窗口 `mybatis-plus-sql-visualizer-demo – schema.sql [mpj_demo@localhost]`，PID 10840，矩形 (277,25)-(1647,1009)，1370×984，屏幕 1920×1080
- 被测对象：ZCode 插件 `computer-use` 0.6.5（运行期由 `zcode-cua` 提供执行端）、`deepseek-eyes` MCP、其余官方插件
- 性质：只读实测。未改动任何项目文件，临时脚本已删除

---

## 一、computer-use 的底层机制与能力边界

### 1.1 官方定位：无障碍优先，坐标是最后兜底

`SKILL.md` 原文的顺序非常明确：

- 「Accessibility is the primary action path. It is semantic, precise, works on a background app and does not steal the user's focus.」
- 「Keyboard input is the fallback: use `pressKey` only when no element expresses the operation」→「Coordinates are the **last** fallback, for canvas, games and Electron content accessibility cannot see.」

因此它本身不是「视觉定位工具」，而是一个**无障碍树驱动器**：`get_app_state` 返回的是文本化的 AX 树，`elements()` 返回 `{index, kind, title, value, actions}`。坐标点击只是留给无障碍看不见的自绘内容的逃生口——而那个逃生口，恰恰要求**用模型自己的眼睛在截图里挑像素**（「Choose `x` and `y` only by looking at the current returned raster」）。

### 1.2 Windows 后端确认走 UIA（COM），不内置 CV/OCR

三个独立证据：

1. 原生插件 `ax_native.node` 的导入表只含 `USER32/GDI32/OLE32/OLEAUT32/D3D11/ADVAPI32/SHELL32` 与 WinRT 捕获接口，**没有任何 OpenCV / OCR / 模板匹配依赖**；
2. 该二进制中的宽字符串里有唯一一条自动化框架报错：`CoCreateInstance(CUIAutomation) failed`——这是 UIA 的 COM 类厂，直接证明 Windows 侧走 `CUIAutomation`；
3. 同插件 `package.json` 的全部运行期依赖只有 `koffi`（FFI）、`sharp`（位图编解码）、`semver`、`detect-libc`——`sharp` 仅用于把帧编码成 PNG，不含识别逻辑。

截图能力是有的（Windows Graphics Capture + D3D11 帧池），但**截图只是产出位图交给模型看**，SDK 自身不做任何元素识别。这一点决定了它对 Swing 的态度：无障碍找不到 → 只能退化成坐标，而坐标由模型目测得出，SDK 不提供任何纠错。

### 1.3 一个此前未记录的执行约束

`agent.computerUse.*` 在**子代理内直接拒绝**：

```
MCP tool returned an error: Computer Use is not available in subagent
```

与 `SKILL.md` 的「Main agent only. Never delegate Computer Use to a subagent.」一致。对本项目意味着：它无法被塞进 `record/` 之类的无人值守批处理管线，必须由主智能体在会话里逐步驱动。

---

## 二、实测读数

### 2.1 无障碍树：对 IDE 内部完全不可见（推翻/确认结论）

用与 `CUIAutomation` 等价的 `System.Windows.Automation`（.NET UIAutomationClient）对 PID 10840 做全树枚举：

| 探测方式 | 结果 |
|---|---|
| `RawViewWalker` 全树 | **2 个节点**：`Window`/`SunAwtFrame`（窗口本体）+ `Pane`/`JBRCustomTitleBarControls` |
| `ControlViewWalker` 全树 | 同上，**2 个** |
| `ContentViewWalker` 全树 | 同上，**2 个** |
| `FindAll(Descendants, TrueCondition)` | **1 个**（只剩 `JBRCustomTitleBarControls`，bounds 1498,25,141,40） |
| `EnumChildWindows` 真实子 HWND | **1 个**（`JBRCustomTitleBarControls`） |
| MSAA `AccessibleObjectFromWindow(OBJID_CLIENT)` | `S_OK`，但 `accChildCount = 1`，逐层展开得到的 7 个「子节点」**name 全空、role 全 -1** |
| Java Access Bridge（`windowsaccessbridge-64.dll`） | `getAccessibleContextFromHWND` 返回 **0（失败）**，无法绑定窗口 |

要点两条：

- **此前的「7 个窗口壳元素」结论应修正为更差**：本次三视图实测只有 **2 个节点**，且唯一有意义的叶子是 IDE 自绘标题栏控件容器。连「窗口+标题栏+关闭/最大化/最小化+两个系统菜单」这套标准壳都没有——因为 IDEA 2026 用无边框自绘标题栏，连系统按钮都画在 Swing 里。
- 编辑器、工具窗、树节点、按钮、输入框**一个都没有**。`AIAssistantChatPanel`、`EditorTabLabel`、`ActionButton` 这些名字在 UIA 树里零命中。

对照组证明这不是我探针的问题——同一次运行里：

| 进程 | UIA 后代节点数 |
|---|---|
| magpie-windows-amd64 | 1787 |
| 7-tool | 211 |
| msedge | 176 |
| chrome | 134 |
| Weixin | 36 |
| Taskmgr | 15 |
| ZCode | 9 |
| **java（被测算 IDE）** | **1** |

探针有效，IDE 就是空树。

### 2.2 「是不是 JAB 没开」——开了也救不了 computer-use

`javaaccessbridge.dll` 已经加载进 IDE 进程（`Get-Process -Module` 命中），但 JAB 开关未启用（无 `JavaAccessBridge` 注册项/配置目录），所以 `getAccessibleContextFromHWND` 失败。

即使启用 JAB 让 Swing 树可被 JAB 客户端读到，**也解决不了 computer-use**：computer-use 的 Windows 后端只调用 `CUIAutomation`，不走 JAB。要让 UIA 看见 Swing，需要 UIA-JAB 桥——ZCode 的 `ax_native.node` 里没有任何 JAB 相关导入或字符串，等于没有这座桥。

作为交叉参照，项目内已有的 robot-server（跑在 IDE 进程内，直接读 Swing 组件对象）对同一时刻、同一窗口返回 **297 个组件**，含 `AIAssistantChatPanel`(364×859)、`AiChatInputActionToolbar`、`ChatHistorySelectorButton` 等 17 个 AiChat 相关组件。同一棵树、三种读法：UIA 1 / JAB 0 / 进程内 297。

### 2.3 视觉定位（grounding）实测

因为 computer-use 在子代理内不可调用，且其后端视觉工作本质上就是「把位图交给模型看并挑像素」，我按同一机制做等价的盲测：只看 1370×984 整窗截图、**不看标注**、先写下像素框，再把框裁出来逐张核对。

判定方法：每个预测框单独裁出、放大 3 倍后逐个目视核对框内到底是什么（`crop_check.png`）。命中＝框内就是目标控件；落空＝框内是别的东西或纯背景。另用像素边界扫描独立量了 AI 输入框的上下边界做旁证。

需要说明的一个等价性前提：做这次盲测的模型**就是**驱动 computer-use 的同一个会话模型。computer-use 在无障碍失效后走的也正是「把位图交给这个模型、由它挑像素」，所以这里的命中率可以直接当作 computer-use 坐标兜底路径的精度上限。

| # | 目标 | 目标实际尺寸 | 盲测结果 | 判定 |
|---|---|---|---|---|
| A | AI Assistant 输入框 | ~357×65 大字区 | (963,793,1320,858) | **命中**（裁图清晰含 placeholder 全文） |
| C | `schema.sql` 页签 | ~230×26 | (66,46,200,72) | **命中**（裁图含页签文字，右侧略裁） |
| E | `Claude Agent` 选择器 | ~167×20 | (963,918,1130,938) | **命中** |
| F | `New Chat` 按钮 | ~95×22 | (1150,50,1245,72) | **命中**（精确） |
| J | 状态栏行/列区 | ~60×24 细条 | (940,958,1000,982) | **命中**（细条仍准） |
| B | 编辑器行号旁的「运行」箭头 | ~24×24 | (60,118,84,142) | **落空**：裁出来是行号 `57`，箭头在别处 |
| D | 数据库工具窗图标 | ~20×20 | (16,514,36,534) | **落空**：裁出来是纯背景 |
| G | Commit 按钮 | ~25×24 | (243,88,268,112) | **偏移**：只切到 `Tx Au`（工具栏文字左半），按钮不在框内 |
| H | Maven 工具窗图标 | ~24×24 | (16,83,36,107) | **偏移**：切到一个圆形图标边缘，非目标 |

**读数**：整体元素 5/9 命中，**大字区/文本类目标 5/5 全中，小图标类目标 0/4 全败**。误判幅度不是几个像素，而是 D 完全落在空白、G 偏进相邻工具栏文字、B 完全点到了另一个东西（行号）。

这条失败模式与本项目近 10 轮的失败记录**完全同构**：点空不是因为坐标算错，而是因为「自绘 UI 里 20~24px 的图标，在 1370×984 的画布上没有任何可供语言描述的锚点」。模型能可靠回答「输入框在哪」（它占了画面 1/80 的面积且有文字），不能可靠回答「第三个 24px 图标是 Run 还是 Debug」（它在缩小后的截图里只有几个像素块）。

关键词外推：`pressKey` 到达 IDE 后走的是同一套输入注入，无障碍找不到元件时仍等价于盲点坐标。仅 A~J 这九个目标的结果就足以判定：**computer-use 不能承担本项目的驱动**。

### 2.4 各路径耗时实测（同一局机器，同窗口）

| 路径 | 操作 | 实测均值 |
|---|---|---|
| `mss` 抓窗 | 1370×984 抓一帧 | **19.7 ms** |
| `pyautogui.screenshot` | 同上 | 39.3 ms |
| OpenCV 灰度单尺度匹配 | 全窗找 95×22 模板 | **49.3 ms** |
| OpenCV 5 尺度匹配 | 同上 | 196.5 ms |
| robot-server 查询 `//*`（297 组件） | HTTP 往返 + 全树序列化 | **128.7 ms** |
| robot + 截图一轮 | 定位+取帧 | 183.4 ms |
| `pyautogui.moveTo`（`PAUSE=0`） | 单次移动 | <1 ms（默认 PAUSE=0.1s 时为 108 ms） |

作为对照，computer-use 单步的实际耗时取决于**模型往返**（`get_app_state` → 模型读树 → 决策 → 下发动作），量级为秒；技能文档还要求「Observe once, act, then observe again」，即每一步至少两次模型往返。它无法进入「60fps 级录制」的时间预算。

### 2.5 模板匹配的精度/鲁棒性（顺带校准）

用同帧自裁模板回搜：`TM_CCOEFF_NORMED` **1.0000 精确命中 @(1150,50)**，噪声模板最高仅 −0.06，判别间隔极大（0.85 阈值有充足余量）。这解释了为什么项目里 `anchor.py` 的「灰度+多尺度+找不到就返回 None 绝不猜坐标」是当前最稳的一条路——它有真值可核，而模型目测没有。

注意一个实现陷阱：对同一模板做多尺度重采样时，`INTER_AREA` 缩放到 0.9× 会把分数压到负值（实测 −0.43），单尺度必须是**原尺寸不缩放**先跑。这不是模型问题，是 `anchor.py` 里值得复核的实现细节。

### 2.6 deepseek-eyes 视觉后端：当前不可用

`mcp__deepseek-eyes__describe_ui` 与 `analyze_image` 两次调用均返回：

```
Error code: 400 - Model id : Qwen/Qwen3-VL-8B-Instruct , has no provider supported
```

追到服务端源码（`F:\code\Python\Github\other\deepseek-eyes\deepseek_eyes\server.py`）确认：默认模型写死 `Qwen/Qwen3-VL-8B-Instruct`（`DEFAULT_MODEL`），可被环境变量 `VISION_MODEL` 覆盖，但**当前 shell 与 MCP 配置的 env 里都没有 `VISION_MODEL`**，且不继承 `MODELSCOPE_API_KEY`（该变量只在 MCP 进程 env 里，我这个 shell 里读不到）。换模型的路也堵着：ModelScope 推理端点现存 37 个模型里视觉模型只剩 `OpenGVLab/InternVL3_5-241B-A28B` 与 `PaddlePaddle/ERNIE-4.5-VL-28B-A3B-PT` 两个，且两者实测都返回：

```
For account security, please complete your profile via email verification.
ModelScope Magicubes is only available to verified accounts.
```

即**账号未实名，视觉推理额度不可用**。结论：`deepseek-eyes` 这条链路今天不具备可用性，属于配置/账号层阻塞，不是接口设计问题（修法是把 `VISION_MODEL` 指向仍可用的模型并完成实名验证，或换一个自带视觉通道的 provider）。

### 2.7 其余 ZCode 内置插件盘点

`C:\Users\USER\.zcode\cli\plugins\cache\zcode-plugins-official\` 下共 17 个：`android-emulator`、`browser-use`、`computer-use`、`document-skills`、`documents`、`image-search`、`ios-simulator`、`node-repl-host`、`pdf`、`plugin-creator`、`presentations`、`restore-legacy-sessions`、`skill-creator`、`spreadsheets`、`visualize`、`zcode-cua`、`zcode-guide`。

- **没有第二个具备「桌面截图 + 元素定位」能力的插件**。`browser-use` 只对 Web 页面有效，对 Swing 窗口无用；`image-search` 是联网图搜（HTTP MCP，走官方 JWT），不识别本地截图；`documents`/`pdf`/`presentations`/`spreadsheets` 是文档解析；`visualize` 是生成可视化；`zcode-cua` 是 computer-use 的执行端（同一套）；`android-emulator`/`ios-simulator` 只对移动端。
- 唯一还能提供视觉理解的是**会话模型本身的多模态读图**（本次 2.3 的盲测就是它做的）。这条路可用，但没有独立 MCP 工具包装，且同样受「小图标不可靠」的限制。

---

## 三、与现有方案逐项对比

| 维度 | computer-use 0.6.5 | 现有方案（robot-server 为主，anchor 兜底） | 纯几何/目测（近 10 轮失败的做法） |
|---|---|---|---|
| **元素定位方式** | UIA 无障碍树；对本 IDE 实测 **1~2 节点**，退化为模型目测像素 | 组件身份（XPath/类名），**297 组件**，直给相对父坐标 | 窗口矩形 + 固定偏移 + 人眼估值 |
| **定位准确率** | 大字区 5/5，小图标 0/4（实测 2.3） | 组件级唯一命中，靠 XPath 约束，不靠像素 | 小目标基本靠运气 |
| **单步耗时** | 秒级（≥2 次模型往返/步），无法进 60fps 预算 | 定位 128.7ms + 抓帧 19.7ms ≈ **150ms**（本地函数调用，无模型） | <1ms（但错） |
| **脚本复用性** | **不能**：`Computer Use is not available in subagent`；必须主智能体会话内逐步驱动，无法落成 `record/` 里的固定脚本 | **能**：`robot.py`/`anchor.py` 是纯 Python 模块，`click_component(xpath)` 可直接写进场景脚本，可重复跑 | 能跑，但每轮都要人调坐标 |
| **失败可恢复** | 有 `ELEMENT_UNAVAILABLE`/`STALE_STATE` 与 `actionSent` 语义，但**元素本来就取不到**，异常只在坐标层触发，等于点空了也不会被判定为「目标不存在」 | 多命中/零尺寸**拒绝点击**（`robot.py` 已实现），`verify.after_click` 校验落空；`anchor` 找不到返回 `None` 而不猜 | 无，点空即继续 |
| **对 IDE 重启/布局变化的鲁棒性** | 与布局无关的部分（a11y）看不见；能看见的部分全靠像素，布局一变即失效 | 与坐标无关（按组件身份查找），重启/换主题/改缩放仍有效；XPath 依赖类名，插件大版本升级需复核 | 全失效 |

一句话：**computer-use 在本项目上唯一「能用」的能力是「模型看图说个大概位置」，而这正是现有方案里最不可靠、也已经被 `anchor.py` + `robot.py` 替代掉的那一环。**

---

## 四、结论

### 4.1 能不能用？不能。

不能用于本项目的录制驱动。逐环定位断点：

1. **无障碍环（它设计上的主路径）彻底不可用**：UIA 三视图对 IDE 只返回 2 个节点，MSAA 返回无名无角色的 7 个空壳，JAB 绑定失败。它没有任何降级到 JAB 的桥。
2. **坐标兜底环不可靠**：退化后只能靠模型目测像素。本次盲测小图标类目标 0/4 命中，偏差量级到「完全点到别的东西」（行号 `57`）。本项目 63 个 ≤40×40px 的小控件正是最需要精确定位的那批。
3. **集成环不可用**：子代理内直接拒绝调用，无法进批处理管线；单步秒级耗时与「再观察一次」的强制回路，既不能脚本化也不能进录制时间预算。

### 4.2 关键实测读数（供引用）

- UIA 对沙盒 IDE：Raw/Control/Content 三视图各 **2 节点**，`Descendants` **1 节点**，`EnumChildWindows` **1 个**；同机对照组 magpie 1787 / chrome 134 / weixin 36 —— 探针有效。
- MSAA：`S_OK` 但 `accChildCount=1`，7 个展开节点 name 全空、role 全 -1。
- JAB：`getAccessibleContextFromHWND` 返回 0，`javaaccessbridge.dll` 已加载但桥未启用；启用也救不了 UIA-only 的 computer-use。
- robot-server 同时刻同窗口：**297 组件**，含 17 个 AiChat 组件。同一棵树三种读法 1 / 0 / 297。
- 视觉 grounding 盲测：整体 **5/9**，文本大字区 **5/5**，小图标 **0/4**。
- 耗时：mss 19.7ms、cv2 单尺度 49.3ms、robot-server 128.7ms、robot+抓帧 183.4ms；computer-use 单步为模型往返量级（秒）。
- `deepseek-eyes`：`Qwen/Qwen3-VL-8B-Instruct` 无 provider（400）；ModelScope 未实名，备用 VL 模型同样 403 类拒绝 —— 当前不可用。
- 模板匹配判别间隔健康：同帧自裁模板 **1.0000**，噪声模板最高 −0.06。

### 4.3 视觉方案部分可用时的落点

如果只问「视觉模型能用在哪」，答案是有边界的：

- **适合：一次性标定 / 一次性核对。** 本次 5/5 命中的都是文本、大色块、有明确语义的控件。用它做「这个按钮现在大概在哪，帮我截个模板」是可行的——把它的输出当**候选框**，再用模板匹配或 robot-server 确认，两步交叉。
- **适合：异常诊断的旁路。** 截图 + 让模型描述「画面现在是什么状态」（弹窗？空态？还在加载？）是它擅长的，作为失败时的取证比作为驱动更值。
- **不适合：每步驱动。** 小图标定位不可靠、单步秒级、且无法脚本化——这三条任意一条都足以否掉它做驱动。
- **不适合：替代 robot-server / anchor。** 后两者分别提供「组件身份」和「像素级真值校验」，都是确定性路径；视觉模型只能提供概率性候选，在录制这种要求 100% 可复现的场景里，概率性结果不能当主路。

### 4.4 建议（不改变现有路线）

现有三件套（`robot.py` 组件身份主路 + `anchor.py` 模板兜底 + `verify.py` 点击后校验）在本次对比里全面胜出，无需为 computer-use 让路。可以加固的两个点：

1. 复核 `anchor.py` 多尺度实现里的 `INTER_AREA` 重采样：0.9× 缩放会把自匹配分数压到 −0.43，应保证「原尺寸单尺度先跑、命中即返回」，多尺度只在前者未过阈值时启用。
2. 小图标（≤24px）优先走 robot-server 组件身份，`anchor` 模板只在组件未渲染时兜底；这两类目标恰是视觉模型和目测都最不可靠的地方。

---

## 附：实测方法与环境说明

- UIA 探测：PowerShell + `System.Windows.Automation`（`UIAutomationClient`/`UIAutomationTypes`），`RawViewWalker`/`ControlViewWalker`/`ContentViewWalker` 三视图全树遍历 + `FindAll(Descendants)` + `EnumChildWindows` 交叉验证。
- MSAA 探测：Python `ctypes` + `oleacc.AccessibleObjectFromWindow(OBJID_CLIENT)` + `AccessibleChildren`，vtbl 直接调用 `get_accChildCount`/`get_accName`/`get_accRole`。
- JAB 探测：Python `ctypes` 加载 `jbr/bin/windowsaccessbridge-64.dll`，`Windows_run()` 后 `getAccessibleContextFromHWND`。
- computer-use 机制：读 `SKILL.md` + `docs/computer-use.md`，并对 `ax_native.node` 做 PE 导入表解析与 ASCII/UTF-16 字符串提取（命中 `CoCreateInstance(CUIAutomation) failed`）。
- 视觉盲测：先只读整窗截图写下像素框，再裁剪放大逐张核对；真值用代码扫描边界独立得出，不靠目测。
- 耗时：每项 5~20 次取均值，同机同窗口连续测量。
- 未执行任何点击/键入的破坏性动作；`pyautogui` 仅移动光标与截图。
