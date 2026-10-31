---
name: ide-ui-acceptance
description: "IntelliJ/JetBrains IDE 插件功能 UI 验收：runIde 沙盒 + robot-server/图像锚点/像素带定位 + 证据分级 + 批次化验收 + 缺陷登记与批末统一修复 + 定向测试收口替代 GUI。当用户要求对 IDE 插件做功能验收、沙盒走查、合流后全功能验收、UI 走查时使用。Windows + pyautogui 桌面自动化，主智能体亲自操作 GUI，子代理只写代码不碰桌面。不适用于纯后端测试（直接跑测试即可）、网页验收（用 browser-use）或演示 GIF 录制（用 ide-demo-gif）。"
when_to_use: "用户说「验收」「走查」「沙盒里验一遍」「合流后把新功能都验掉」「UI 验收」「继续验收直到修完」且对象是 IDE 插件功能时。"
---

# IDE 插件功能 UI 验收

> **位置与工作目录**：本 skill 已下沉到插件仓 `<repo>/.agents/skills/ide-ui-acceptance/`（2026-10-31，全局那份已删）。**cwd＝插件仓根**，文中 `record/`、`.local/` 均相对它。与录制 skill（`ide-demo-gif`）共用 `record/` 下的 `anchor.py`/`robot.py`/`mcpnav.py`/`_run_elev.bat`。

2026-10-16~18 dev 合流后全功能沙盒验收战役（44 组、30 缺陷、十批+四专项批、十轮全量门禁）沉淀的完整方法。核心思想：**验收是证据工程，不是点一点**——每项按「真实操作→判别点→证据帧→状态」入账，未执行不预填通过，GUI 被平台阻断的行为语义降级为定向测试收口并如实标注。

## 四条铁律

1. **证据分级，不冒充通过**：真机 GUI 实测 > 代码/字节码可判 > 定向测试收口 > 平台面限制登记。每一级都要在账面写明是哪一级；「静态面」「单测支撑」「真机闭环」是三种不同结论，禁止混写。反向验证（摘掉修复→必红）缺一不可，且反向探针必须完整还原——只还原一半的探针会假绿。
2. **主控亲自操作 GUI**，子代理只写代码/跑定向测试，绝不并发操作桌面（两路合成输入互相打死）。用户授权「发现问题→批末统一修」：验收期只登记缺陷（UI-0xx 表），修复交独立工作树子代理，批末合流→重启沙盒→复验原路径。
3. **等待后台通知期间主控继续验收其他项**（用户 2026-10-18 明令）：长命令（构建/测试/重启）一律后台，不空等；GUI 受阻的项立即转测试收口或登记，不死磕。
4. **收口读数时效性**：主控并行写文档期间不碰 `src/main`；一旦改了生产代码，已拿到的绿全部过期必须复跑。每完成一件可提交的就立即提交（掉线不丢账）。

## 环境就位

1. **并行登记**：跑任何 gradle 前先 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`；有活跃任务→`register --name <任务名> --sandbox <独立沙盒目录>`；跑完 `done --name <任务名>`（**禁裸 `done`**，会注销全部）。构建并发上限 2 路、全量 testShards 单路收口。
2. **沙盒启动**：dev 树标准命令＝`.local/start-ui243.ps1`（内部：merge 冲突预检→登记→`gradlew -I .local/<沙盒>.init.gradle runIde --args=<demo 绝对路径> -PgifRobotServer=true --no-configuration-cache`）。要点：
   - **init 脚本设独立 sandboxContainer**（如 `MPSV-MasterMerge-UI243`）+ `autoReload.set(false)`——防止后台修复构建改变在验版本；
   - **`-PgifRobotServer=true` 装 robot-server（机制 A，与 gifRecord 解耦）**；拷 `config/plugins` 的机制 B 已否决（runIde 钉死 `-Didea.plugins.path`）；
   - **`-PgifRecord=true` 会切 262 沙盒**——验 243 基线不要带它；
   - 起完只认日志落点判身份：`build/<沙盒名>/…/log/idea.log` mtime 在刷新（`ls -t`）。
3. **提权与输入**：沙盒 IDE 是提权进程，非提权 SendInput 被 UIPI 拦（光标能动、点击/按键全无效）。**整条探针提权跑**：`record/_run_elev.bat`（内容必须纯 ASCII）+ `Start-Process -Verb RunAs -WindowStyle Hidden`。提权进程杀得掉别的提权进程；非提权杀不掉。
4. **验收前置检查**：IDE 全屏+置顶（`SetWindowPos(HWND_TOPMOST)` 后读回 ex=0x8 才算落实）；demo 工程干净、端口无监听；**沙盒在役包核验**（重大）：javap 沙盒 `plugins/<plugin>/lib/*.jar` 确认被验修复符号在役（JDK8 javap 读不了 21 字节码，用 `~/.jdks/ms-21`），jar mtime 必须晚于修复提交且早于 IDE 启动——**「javap 过 build 产物」不作数**，installPlugin 时点与探针时点错位 2 小时即全盘误诊（实锤过）。
5. **IDE 状态归一**：等索引完成（右上 Analyzing 消失，轮询区域亮度，最多 90s）再触发动作；关闭 Distraction Free Mode（`Ctrl+Shift+A → Exit Distraction Free Mode`）；工具窗被收起时用 Find Action 搜工具窗名重开；重启后会话页签自动恢复可直接用。

## 定位法优先级（禁手工换算坐标）

**robot-server 组件树 > anchor 图像模板 > 像素带 > window_rect 几何**，任何路线都不落手工目测坐标：

- **robot.py**（8580）：`/xpath/components` XPath 必须唯一；返回 x/y **相对父组件**，沿祖先链累加到屏幕。**对 JBPopup 弹层（ListPopup/右键菜单/More 菜单）不可达**——弹层直接换下一条路线。Find Action（Ctrl+Shift+A）**不索引插件自定义 Action**，插件动作走快捷键/菜单。
- **anchor.py**：`cv2.matchTemplate` 图像模板，免疫漂移与 CJK 双宽；纯色模板会退化（低方差拒绝）；先在 IDE 悬停使控件渲染再取模板。
- **像素带菜单定位**（弹层唯一可靠定位，`record/probe_menu_pix.py`）：打开菜单截帧 A → `Down×1` 截帧 B → `ImageChops.difference(A,B).getbbox()`＝首项高亮带 → 首项 y＝菜单区 origin_y+bbox.top、项高＝bbox 高（24px，padding 带为 48px 取半）→ 第 n 项 y＝首项 y+(n−1)×项高+项高/2。**键盘路线并行可用：ListPopup 初始选中＝第一项，`Down×(n−1)+Enter` 命中第 n 项**（右键菜单的键盘导航不可靠，别用）。
- **几何**：`window_rect(pid=/title=)`（对话框不是主窗口，必须 EnumWindows）；弹窗按钮行 y＝底边−39；preview 类浮窗 rect 要做**视口过滤**（最小 600×400 + 上限≤1600×950，防把 IDE 内部 content frame 当浮窗）；注意 rect 语义是 `(l,t,r,b)` 四元组不是 (x,y,w,h)。

**双判据护栏**：开文件/弹窗类断言用「像素 diff<阈值 **OR** robot 文本命中」双判据——深色半透明弹窗下纯像素 diff 会假阴性。同目标连点两次（首击被「点击聚焦」吞掉）。

## 触发链路速查（本项目预览类功能）

- **编辑器入口**：光标落点用 `goto_text(唯一 token)`（跨文件行号表不可靠——同名文件会开错模块，open_file 查询带模块可分辨段）；快捷键 `Alt+Shift+Q`；Find Action 对插件动作无效。
- **gutter 入口**：gutter 图标位置用 `gifkit.find_gutter_icons` 像素检测动态取；**右键 gutter 图标在合成输入下不弹菜单**（平台 getRightButtonClickAction 接线对合成输入不响应，左键正常）——左键开预览做对照。
- **列表/历史**：日志列表右键菜单＝**先左键选中条目、再右键条目正文**（右键空白头部只出横幅）；工具窗页签/按钮坐标随面板宽度漂，每次截图现量。
- **「生成 CRUD SQL」类编辑器右键动作**：菜单项在册可用截图留证，但弹层内交互不可靠——**行为语义改由生成器单测收口**。
- **执行类**：数据源绑定缺失时可能弹「选择数据源」选择器——「面板无变化」类断言必须是「面板变化 OR 预期弹层在场」二者取一（实测过把选择器弹出误判成无变化）。

## 截图与证据纪律

- **全屏帧是默认**（`ImageGrab.grab()` 无参）：toast 在屏幕右下、独立窗口可能置后——`bbox=rect` 只拍面板内部会把它们裁掉，制造「无通知/无输出」假象（实锤过一整轮误诊）。
- **「点了没反应」三连排查**（按序）：①`EnumWindows` 按标题枚举——目标窗口可能已创建但 **z 序压在主窗后**（截屏不可见、枚举可见）；取证＝最小化主窗→全屏截图→恢复（`SetForegroundWindow` 受前台锁常静默失败）；②全屏帧找 toast/气球；③javap 在役 jar 核包。
- **阳性对照**：怀疑链路失效时，先点一个必有可见反应的相邻项（如「分享」弹对话框）——阳性＝链路无恙问题在目标项；阴性＝链路失效。一轮实验替代数小时推演。
- 帧命名带组号与动作序（`img<组>-<序>-<动作>.png`），统一落 `build/<沙盒>-shots/`（build 生命周期、不入 git）；**文件名避开 `\u`+十六进制样片段**（`imgu19k`/`u04`/`u20` 会命中 Python `truncated \uXXXX escape` 语法崩，用 `img*`/`img_u12isol*` 无转义前缀）。
- 语义级断言（通知文案/SQL 文本）拿不到组件树时降级为「截图+区域指纹」，并在账面写明降级，绝不冒充已核对文本。

## GUI 阻塞的替代收口（本轮最大提效）

平台交互债（JBPopup 弹层、面板内 EditorTextField、设置页滚轮 no-op/搜索无索引）阻断的行为语义，按序降级：

1. **定向测试收口**：优先找**既有测试类**覆盖同一语义（生成器/管理器/过滤器/持久化 roundtrip），独立沙盒登记后 `--tests <FQCN> --no-configuration-cache` 跑定向，读数入账时写明「测试收口，GUI 面板交互仍待」。本轮九类 142 例收掉了 U05/U12/U13/U19/U20/U23/U29/U35/U36 的行为面。
2. **代码/字节码可判面**：调用点落点、EP 声明、PersistentState `@Storage` 声明、javap 在役包核验——写明「静态面」。
3. **平台面限制登记**：确实在本环境无法闭环的（如「Terminate 确认弹窗阻断正常退出→强杀不 flush PersistentState→重启保留无法验证」），登记限制 + 用 roundtrip 单测兜底，不判产品失败、也不冒充通过。
4. **真机人工兜底**：合成输入无法定性的事项（窗口 z 序、右键 gutter 菜单），登记「待真机人工一次点击」并给出精确操作步骤。

**写测试收口时的坑**：断言要用判别性读数（`assertSame` 逼出真差异）；「…Yet」命名缺口钉成契约、非判别性计数、直调内部入口绿≠真实分派路径绿——每条新断言自问「摘掉修复它红不红」。

## 语料与夹具

- **万条/大文本语料**：合成文件显式标 synthetic（编号、哨兵词如 `UI_MATCH` 恰 N 条、五种耗时循环），落盘前用**在役 JAR 的同类**（如 `SqlLogFileReader`/`HistorySearchService`）读回核数；**首版探针的期望值要对照源码常量**（如 `HARD_MAX_RESULTS=100`——期望 1000 被钳到 100 是探针错不是产品错）。
- **「插件生成的坏 SQL」夹具**：预览入口不可点达时，在 demo 仓临时加 `@Select("SELECT 错列 FROM …")` + 触发端点，验完 `git checkout` 撤销并确认干净。
- **PG/非默认方言**：手写 `.idea/dataSources.xml` **注册表条目**可行（模型文件被 DAS 隔离）；驱动树整拷沙盒 `config/jdbc-drivers/<Driver>/` 免下载；口令经 dbx MCP 先验再走 IDE Change Credentials（Until restart）；**首连前的源不进控制台选择器**，先经 Data Sources 对话框触碰一次。
- demo 应用**必须从 IDE 里 Run**（外部启动捕获链挂不上）；控制台通道条目无方言/耗时/源码（那是增强 agent 通道才有）——「列表没这些列」先查通道再疑缺陷。
- **Database 控制台执行的 SQL 不进捕捉面**（2026-10-31 实测）：诊断行恒「已挂载 0 个」。要 SQL 条目必须让应用自己执行（定时任务/接口）。
- **沙盒构建静默死亡先查外部工程配置**（2026-10-31）：`build.log` 停在 `Pre-loaded process ready` + 运行配置报 `Process not started` ⇒ 查 `<sandbox>/system/projects/<proj>.<hash>/external_build_system/modules/<module>.xml` 的 `LANGUAGE_LEVEL`（改 demo `.idea/compiler.xml` 无效）。

## 批次组织（大验收不乱）

- **先出 44 组矩阵**：每行＝ID｜功能与必验子场景｜主要操作与判别点｜状态/证据/缺陷。状态只有 通过/部分/未验/阻断 四种；「部分」必须列出已验与未验子项。
- **按共享前置分组**：无需重启的一批（当前沙盒）；要重启的打包（**一次重启验 N 组持久性**——重启前截屏留底，重启后逐项断言保留）；脚本型一批（探针子代理预备+BC_RUNBOOK，主控串行执行）；视觉一批。
- **每批节奏**：冻结生产 SHA（写进文档）→ 合流定向（`--no-configuration-cache`，逐类核对 XML，不把独立读数相加冒充合流绿）→ 后台 runIde 新包 → GUI 验收 → 缺陷登记 → 独立树修复（定向绿+反向恰 N 红）→ 批末合流 → 重启复验原路径。
- **全量门禁**：全部批次完成后单独收口（`testShards --continue --no-configuration-cache` + `verifyGate` 三目标，独立沙盒+登记隔离）。分片竞态处置＝**牺牲品漂移+隔离单类绿+改动面与冲突写链零交集＝概率竞态**：不动 src、红分片纯重跑（≤3 次）后按分片合并完整读数并如实注明重跑；三次仍不绿才升级竞态专项（届时全量作废重跑）。
- **应用退出等待**：runIde 退出常卡「Waiting for process detach」——90 秒有界等待→仍活着则 JDK 线程现场取证→原生 Kill 结束应用→残留确认弹 Disconnect；每次都核对 PID/父进程/命令行归属，只动自己的进程。

## 验收侧坑速查（先疑探针，再疑插件）

本轮批 C 一次同批失败 4 组，逐支排查后**全部是探针缺陷而非插件缺陷**——「先疑探针」是硬纪律：

- **期望模型错误**：①OGNL 空串/零值「该出条件没出」——未勾选动态条件时填值本就不触发（勾选才把 `<if>` 加入 WHERE），插件行为正确；②「期望 3 个历史文件」——自动保存实为**按日聚合单文件**；③「列表空」——捕捉开关没开（空态文案/诊断行可判）。判「缺陷」前先核对实现的期望模型。
- **rect 语义**：预览 rect 是 `(l,t,r,b)` 四元组——当 (x,y,w,h) 用会点进参数区（实测）。
- **断言设计**：「填参后 SQL 应变」错——占位形态下填参不改 SQL，联动语义是勾选复选框；「面板无变化」可能是选择器弹出了——断言写「面板变化 OR 预期弹层在场」。
- **错误文案会撒谎**：`click_button` 失败文案把「anchor 缺模板」误写成「robot 未启」——探针的失败文案必须精确到真实原因，误导性文案留档会让下一轮排障白烧。
- **robot 按类名查控件先核真实类名**：转换输入框实际是 `EditorComponentImpl`（`EditorTextField/JBTextField` 均不存在）——用 robot 逐个类名探一遍再写死。
- **Paste 落点自证**：粘贴类操作必须带「面板已变化」自证（像素指纹/文本 diff），工具回执不算落地证据；Esc 误关面板后样本可能粘进编辑器内存——磁盘是否污染以 `git status` 为准，内存污染杀 IDE 即弃。
- **GUI 改设置三条路全堵的兜底**：滚轮 no-op＋搜索对自定义 Configurable 无索引（"Nothing to show"）＋对话框拉到上限仍装不下时，改读沙盒 `config/options/<plugin>.xml` 记录状态并如实标注「XML 兜底记录，非 GUI 操作」。

## 缺陷登记与修复流程

1. **登记**：UI-0xx 行＝发现批次/用例｜复现（实际 vs 预期，带 frame）｜修复分支/提交｜定向验证（含反向红数）｜沙盒复验。登记≠修复，先登记继续验收。
2. **修复**：独立工作树+分支（扁平名）；「每完成一件可提交的就立即提交」；修复必带**定向测试 + 反向验证**（临时摘修复→恰好红 N 例→恢复绿）；禁跑全量、禁 clean、禁 `--stop`。
3. **接手掉线代理**：先 `git status` + 搜临时探针/RED PROBE 注释，确认没停在「先红」中间态（生产代码被改直写=摘掉安全闸门，照原样提交等于事故）。
4. **回填写工作树副本**：派活写明「计划文档回写你工作树里的副本，主仓副本只读」。
5. 合并后**原路径复验**：用当初登记缺陷的同一操作路径在新包重放，通过才闭环；复验只覆盖原路径，不外推整组。

## 收尾清单

```
□ 44 组审计表终版（通过/部分/未验/阻断如实）
□ 临时探针清单登记（留 record/ 按先例，注明可删）
□ 证据帧归档说明（build/*-shots/ 生命周期）
□ dev 树 git status 干净 + 领先笔数盘点（不 push、不发布、dev 不回流 master）
□ 沙盒现场态记录（方言/激活状态/口令时效/工具窗布局），下批重启前恢复项列明
□ 后台任务/登记表注销核对（done --name 逐个）
□ 新坑/新定例回写 skill 与记忆
```
