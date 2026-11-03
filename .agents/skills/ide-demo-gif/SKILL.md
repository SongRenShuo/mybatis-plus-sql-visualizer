---
name: ide-demo-gif
description: "IntelliJ/JetBrains IDE 功能演示 GIF 录制：脚本重放+帧稳定采样+无损中间产物转制。当用户要求录制、补拍或重录 IDE 插件功能演示动图，把 IDE 内操作过程做成循环 GIF，给文档站/README/发行说明配功能演示图时使用。Windows + pyautogui 桌面自动化；**同一时刻只允许一个 GUI 操作者**——主控或用户明确指派的子代理都可以做，但禁止两路并发操作桌面（合成输入会互相打死）。不适用于网页或移动端录屏。"
when_to_use: "用户说「录一张演示 GIF」「补拍/重录 demo 动图」「给功能页配演示图」且对象是 IDE 内的操作画面时。网页录屏用 browser-use，不要用本 skill。"
---

# IDE 演示 GIF 录制

脚本重放 + 帧稳定采样的录制管线。核心思想：动作序列由 Python 脚本一次性重放（贝塞尔轨迹+紧凑间隔，不经逐步往返），成片时间轴由「画面是否稳定」锚定（wait_stable 吸收 IDE 加载抖动），中间产物是无损 mp4，可反复调参重转制。

**工作目录＝插件仓根**（`F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer`），本文所有相对路径都相对它。

工具分三层：

| 位置 | 内容 | 权威性 |
|---|---|---|
| `.agents/skills/ide-demo-gif/scripts/` | 通用基建：`gifkit` `shot` `assemble` `scene_template` `locate` `verify` `black_underlay` `clipread` | **权威源**（改这里，再同步到 `record/` 工作副本） |
| `record/` | 场景脚本 `scene_*.py`、`BASELINE.md`、`preflight.py`/`prep_env.py`、`lib/nav.py`、`_run_elev.bat` | 项目侧工作位置（场景动作、坐标、沙盒预置） |
| `record/` 里的 `anchor.py` `robot.py` `mcpnav.py` | 图像锚点 / robot-server 组件树 / IDE MCP 开文件 | 项目侧，**不在** skill scripts 内（未通用化） |

`record/` 下同名基建是 skill `scripts/` 的工作副本——改通用件改 skill 那份，再 cp 回 `record/`，别两边各改。

## 前置条件

- Windows；Python 3.10+ 装 `mss PIL pyautogui numpy`；PATH 里有 `ffmpeg`
- 目标 IDE 可启动（JetBrains runIde 沙盒或日常实例）；若项目有并行任务登记机制，跑 gradle 前先登记
- 主智能体执行：录制期独占键鼠，不委派子代理
- **录制脚本与目标 IDE 必须同权限运行**：沙盒被提权（管理员）拉起时，非提权脚本的合成输入会被 Windows UIPI 静默挡掉（症状＝光标能动、点击/按键/快捷键全无效）——见坑表 33

## 成品默认规格（文档站验证过的基线）

| 项 | 值 |
|------|------|
| 构图 | 窗口外框 1370×984 @ 屏幕 (277,25)；**录制区域再内缩 8px 去阴影** → `(285,26,1354,974)`（见坑 49） |
| IDE 缩放 / 编辑器字号 | 120% / 13pt（13pt 是 2026.2 默认值，别再调大） |
| 主题 | Islands Dark（或目标项目定的官方暗色基准） |
| 导出 | 15fps，宽 1280（多投放位共用的那张 1440），4~6MB 预算 |
| 文件 | `<功能名>.gif` + 同名 `.png` poster（关键定格帧） |
| 循环 | 首尾帧一致，接缝 diff ≤1 |

投放宽度决定导出宽度：导出宽 ≥ 显示宽 × 1.8 才锐利。换投放位/构图后，脚本里标过的坐标全部要重标。

## 环境就位清单

1. 启动目标 IDE。**平台决定开关**：录 262 沙盒用插件仓 `master` 的标准命令 `./gradlew runIde -PgifRecord=true -PgifRobotServer=true -Pmpvs.licenseAgent=C:/Users/USER/.7t/patch/qwdn-afb.jar --args=<demo 绝对路径>`（gifRecord 切 262；**gifRobotServer 绝不能漏**——prepareSandbox 每次重建沙盒 plugins/ 目录，漏了它 robot-server 就被清掉，2026-10-25 重启漏带导致 robot 全废重装）；**录 243 默认平台（构建 since-build 基线）用 dev 树 `.local/start-ui243.ps1`**（runIde + `-PgifRobotServer=true`、**不带 gifRecord**、init 脚本独立沙盒 + 并行登记）。启用组件定位直接靠 `-PgifRobotServer=true`（机制 A，见标定节）；不要手工拼 `idea64.exe …` 参数（见坑表 32）。**正式站点动图开录前必须验许可**——不止「标题栏无 `Start Free Trial`」，还要确认沙盒 `config/idea.key` 存在（`build/` 被 clean 过就没了，见坑 48）且 `//div[@accessiblename='Database']` 能命中。试用横幅母片只可用于本地/dev 验证，不得同名覆盖正式图。沙盒 IDE 是 java.exe，不是 idea64.exe；身份只认沙盒日志落点。沙盒可能提权，录制脚本也要同权限，且提权运行必须加 `-WindowStyle Hidden`（否则黑控制台盖住 IDE，见坑表 33/34）。
2. 主题切暗色基准；缩放 120%；编辑器字号确认默认 13pt。沙盒 config 可能被清理（options 只剩 updates.xml）——Islands Dark 与 13pt 是 2026.2 默认值不用动，唯二要重设的是 IDE Scale 120%（Appearance→Zoom，下拉无 120 预设但可直接输入）与关目录树，重设自动持久化。
3. 窗口定位到录制区域：Win32 `MoveWindow(hwnd, 277, 25, 1370, 984)`（脚本须 SetProcessDpiAwareness(2)）。**等 IDE 完全空闲后再移**——启动期/改完缩放后 AWT 最小高度钳制会把窗口弹回。移完用 `GetWindowRect` 断言 `(277,25,1647,1009)`，**差一点都不行**：外框与录制区域错位会让顶部标题栏或左侧 stripe 被切掉（2026-10-25 实测把窗口挪到 (263,5) 后顶部被切 20px，成片看起来「IDE 没录全」）。
   **录制区域取 `(285,26,1354,974)`，不是外框本身**——外框四边各带 8px 圆角阴影（实测左/右/下各 8px 均值 214~226 的浅色带），照外框录会在成片四周留一圈白边。内缩 8px 后四边均值降到 40~47（IDE 深色本体），画面铺满无白边。高度须为偶数（`Recorder.start` 有断言），974 可以、975 不行。
4. 关项目目录树（画面只留编辑器+弹窗/工具窗）。工具窗宽度、底部面板等布局调整会持久化进沙盒 config，改一次后续录制直接沿用。**要录「运行期捕获」类画面，先把面板的「捕捉」开关打开**（悬停工具栏才现形的绿色三角，见坑表 36），并读一眼诊断行确认在收条目。
5. 系统输入法切**英文态**——中文态的 typewrite 走组合输入，候选框入镜且提交不稳。**take 前再看一眼任务栏**，会话中途可能被误触回中文（含 Shift 的快捷键如 Shift+Esc 会把它翻回去）。根治：过滤词/输入串用 `gifkit.paste` 剪贴板粘贴，完全不碰 IME；数字与方向键本来就不走组合态。**注意 `force_en_us()` 那种只调 `ActivateKeyboardLayout` 的写法对前台 IDE 无效**（见坑表 41）。
6. 每个 dashboard/监听类工具窗 tab 就位，空态确认。
7. **排查置顶覆盖层**：ZCode 自动化面板、PixPin 等常驻应用若是置顶态会盖住沙盒窗口，坐标点击会打进覆盖层。EnumWindows 核对前台窗口标题与 rect；需要时让 IDE 持续保持 TOPMOST 干完活再撤（TOPMOST→NOTOPMOST 舞步结束就回到非置顶带，对置顶覆盖层无效）。**反过来也要留意**：主窗口的 TOPMOST 会盖住设置对话框等独立窗口（见坑表 39）。
8. **常驻截图工具录制期临时退出**（PixPin 实录为头号干扰源）：全局热键劫持面广（Ctrl+Shift+A/F12、Alt+1 等），其弹窗对合成点击免疫成僵尸弹窗，低层钩子还随机吞合成事件（症状=输入时灵时不灵）。`taskkill //IM PixPin.exe //F` 后全部恢复，录完重启（安装路径先 wmic 查；提权进程如 Snipaste 杀不掉，一般不捣乱）。ZCode 桌面通知 toast 会抢焦点顶掉打开中的浮层——动作链必须在单脚本内一次跑完，不留跨调用间隙。
9. **录「执行类」画面（EXPLAIN / 执行 SQL / 真实结果）前，核对 IDE 数据源的连接参数与应用配置逐字一致**。本项目实测：应用 `application.yml` 的 MySQL URL 带 `sessionVariables=sql_mode=ANSI_QUOTES`，而 `.idea/dataSources.xml` 里的同名数据源没有 → 同一句 `FROM "user"` 应用能跑、IDE 侧报 `1064`，白查数小时（详见坑表 29）。两边 URL 拉平后**需重启 IDE 生效**（`.idea/` 通常已 gitignore，属本机状态不进仓）。
10. **要拍设置页里「首屏之下」的分组，先请用户把设置页滚到位**（合成滚动是 no-op，见坑表 35）——这条要在开工前就问，别等到录的时候才发现够不着。

## 工作流

### 1. 标定

在本项目优先按**组件身份 → 图像模板 → 几何坐标**取点，别一开始就抄旧录制脚本里的 `(x,y)`。`robot.py` 查询时用 `/xpath/components`，XPath 必须唯一：多命中与零尺寸组件会拒绝点击；服务端坐标相对父组件，客户端已沿 `ancestor::div` 累加。先用 `python record/robot.py probe` 确认本机 8580 服务，再用 `python record/robot.py find "(//div[@accessiblename='SQL 历史'])[1]"` 看实际组件。robot 找不到（如捕捉按钮无可访问名称）时，先在 IDE 悬停使控件渲染，再从**带文字／图标**的小区域用 `python record/anchor.py capture <名称> L T W H` 标模板，调用 `require_anchor(name, region=...)`；纯色模板会被拒绝。每次点击后用真实截图／状态断言验证变化，找不到即停，不退回猜测坐标。

`python .agents/skills/ide-demo-gif/scripts/shot.py shot L T W H out.png` 抓帧，逐控件量坐标（弹窗控件必须弹窗打开态现标）。gutter 图标用 `gifkit.find_gutter_icons` 像素检测动态取；写进 scene 脚本顶部常量。**坐标系有三套，逐级换算**：屏幕物理像素（点击用）＝ region 截图相对坐标 +origin ＝ 裁剪局部图坐标 + 裁剪偏移。从裁剪图上量坐标忘了加裁剪偏移是本轮实测最大的点错来源（点到相邻菜单项）。验证「复制/转换」类按钮真实行为时读剪贴板核对：`python .agents/skills/ide-demo-gif/scripts/clipread.py`（gifkit 只写不读）。

新开对话框/浮层有 1~2s 落位动画，期间控件坐标漂移——像素检测目标用「双次扫描一致（差 <12px）才点击」，扫描带只框目标列并跳过无关行；编辑器内 anchor=last 的右键菜单项在 caret 低位时会溢出窗口下缘，prep 里 goto+Ctrl+Down 把目标行推到视口上部（菜单全高 ~640px）再右键。

**弹窗按钮行：按窗口底边推算，别目测**——工具与实测口径见下文「自检与定位标准件」的 `locate.button_row_y`（本轮目测值偏低约 48px、连点三次全落空）。

**开文件两条路径各有坑，按目标文件标定扫掠 y**。坐标扫掠（点 Go-to-File 结果行）是可靠路径——**Return 打不开结果行**（JBR 吞键），必须点行，且行位在 region y 350~366 浮动，故扫掠 3 点；但**弹窗关掉后扫掠的余点会落到编辑器代码上**，触发那个会粘住的文档浮层（见下节）。键盘路径（`↓` 选中首条 + Return）能避开浮层，却**会开错文件**（实测把 `settings-1.1.0.xsd` 当首条结果打开）。取舍：用扫掠，但把扫掠 y **标定到该文件的空白行**——写死的 350/358/366 只对旧目标有效，各文件排版不同。

**面板展开态会跨浮窗保留，take 前必须归一**。预览面板的折叠区（如「预设」行）展开状态**跨预览保留**：不归一的话，take 里那一下点击反而把已展开的收起（末帧仍是收起态，实测踩过）。状态判据用条带灰度标准差，**阈值必须按暗色主题定**：Islands Dark 下面板底色均值仅 28、收起态本底噪声就有 13，照「空白≈0」的直觉取阈值 8 会误判成展开、归一化循环连点三次反把状态弄反（实测收起 13.09 / 展开 34.95 → 阈值取 22）。

### 2. 编写 scene 脚本

复制 `.agents/skills/ide-demo-gif/scripts/scene_template.py` 到 `record/scene_<编号>_<功能>.py`，填常量与动作序列。硬规则：

- **自包含就位**：open_file/goto_line/图标检测全在 `Recorder.start()` 之前；take 中途只留演示动作
- **先戳一遍**：每个分镜动作（gutter/弹窗/补全/菜单）先手动验证真实存在，别照文档摆拍
- 打字场景全键盘化：goto 定位自带光标落点，End/Enter/键入完成，鼠标只做演示滑动
- goto_line 居中有 ±2 行漂移，不要依赖它给点击定位
- 监听/捕捉类按钮必须在被监听对象（控制台/服务）存在之后点击，否则监听不建立
- 循环无缝：结尾回跳起始行或 undo 还原现场
- 对未聚焦子窗口（对话框/浮层/通知气泡）的第一次合成点击会被「点击聚焦」吞掉（只激活不触发）——同目标连点两次，或先点一次无害处激活再点目标；关键 UI 状态出现后做像素/窗口枚举自检，不满足即重试或中止，杜绝盲飞

### 3. 录制与转制

```bash
python scene_tN.py                                    # 产出无损 mp4 + curlog
python assemble.py perf/tN.mp4 perf/tN.gif --width 1280 --poster perf/tN.png
```

转制后核对 `decoded 帧数 == Recorder 打印的 frames`（assemble 已修残留帧 bug，但核对是最后防线）。poster 需要指定帧时直接从 mp4 抽：`ffmpeg -i mp4 -vf "select='eq(n\,N)',scale=1280:-2" poster.png`。

**入库分层（本项目）**：可复用的 `record/scene_*.py`、`anchor.py`、`robot.py`、bootstrap 与 `_run_elev.bat` 逐项检查后显式 `git add`；`record/perf/` 存 MP4/转制帧、`record/shots/` 存过程截图、`record/templates/` 存本机模板、`record/robot-plugins/` 存下载插件，均是被 git 忽略的本地产物。一次性 `_probe*.py` / `probe_*.py`、进程检查 PS1 等**新文件放 `record/tmp/`**（本机忽略目录），不要散落在根目录；若探针需要导入 `gifkit` 等根目录模块，显式把 `record/` 加进 `sys.path`。**移入 tmp 的探针统一带幂等兜底块**（2026-10-19 定例）：`sys.path` 初始化不要只取 `dirname(__file__)`——从 record 根移进 tmp 后会只剩 tmp 在路径里、`import ac/bc_common` 直接失败。模板：

```python
# [tmp-fix] ensure script dir and record/ root on sys.path (idempotent)
import os as _os, sys as _sys
_p = _os.path.dirname(_os.path.abspath(__file__))
for _q in (_p, _os.path.dirname(_p)):
    if _q not in _sys.path:
        _sys.path.insert(0, _q)
```

（`ROOT=Path(__file__).resolve().parents[1]` 的写法在 tmp 下正好指向 record/，无需改；硬编码绝对路径的也无需改。批量改 tmp 文件时注意 PowerShell 5.1 按 ANSI 读无 BOM 的 .ps1——脚本内容必须纯 ASCII，否则中文注释会变乱码并与下一行粘连。）已跟踪的历史 probe 文件不受忽略规则影响。提权封装生成的 `record/_elev_run.log` 也忽略。正式 GIF+PNG 在文档站 `public/demo/` 入库；先核对截图无账号、许可证和项目隐私。不要 `git add record/` 全包暂存，按明确路径提交正式脚本。

### 4. 验收

- 抽帧检查动作节点/定格/poster 三处内容正确；循环接缝 ≤1（结构性跳变场景除外，需注明）
- **动画是否在播只能以真实可见渲染（用户目视 / 真实浏览器前台）为准**。IAB（ZCode 内嵌浏览器）**能正常播放 GIF**（2026-09-15 用户实测）；但 canvas 像素采样与单帧截图**不能**作为动画判据——`canvas.drawImage` 对动画 GIF 只绘制首帧（Chromium 行为），像素指纹恒定会产生「静止」假象（2026-09-15 实证：误判 IAB 不播 GIF）。自动验证只做非动画侧：资产多帧（ffprobe 帧数 >60）+ 播放组件结构就绪态 + 断图回落 + 亮暗色 + 实际列宽下文字锐利；**投放位的最终动画确认必须由用户真实可见渲染完成**
- **断图回落形态由投放位组件决定，验收预期按组件取**：GifPlayer 直出形态（功能详解页，2026-09-15 起取消 poster 兜底）= 失败回落灰色占位盒；首页叠层形态（FeatureAlternate/DocsShowcase）= 失败回落 poster 常驻
- 投放位建议优先「GIF 随 SSR 初始直出」的文档流 img——hydration 后 JS 动态插入 + absolute 叠层淡入的 GIF 在部分 Chromium 环境存在解码停帧（2026-09-15 用户真实浏览器实证：首页直出结构动画正常、详解页动态插入结构静止，直出后修复）
- **站点重建后必须重启本地 preview 服务器**（sirv 启动时预扫文件表，陈旧映射对重新生成过的页面返回 404）
- 体积超预算优先降静态保留（--hold），不降宽不降帧（暗色低动态画面 0.9MB 也能全帧锐利，低体高不算缺陷）

## 自检与定位标准件（`$SKILL/scripts/locate.py` / `$SKILL/scripts/verify.py`，`$SKILL=.agents/skills/ide-demo-gif`）

2026-10-12 沉淀。**动机**：本轮 4 张成品跑了 8 次 take、外加二十来轮探针，返工几乎全落在三类**可自检**问题上——点击落空、末帧被浮层挡住、成片时长骤短。这两件就是用来把这三类**在开录前或点击当场**拦下，而不是录完才发现。

> **进阶方向（2026-10-13 专项调研，报告在插件仓 `docs/调研报告/20261013-2130-演示录制点击定位提效工具调研-调研报告.md`；2026-10-18 机制修正）**：本节的 `locate.py` 是**几何推断**（窗口矩形 / 底部偏移 / 像素剖面），控件一换布局就失效。**两条提效路线已落地成模块**（本项目 `record/`；不在本 skill 的 `scripts/`）：① `anchor.py` —— **图像锚点**（`cv2.matchTemplate` 灰度+多尺度，把关键点击点从「算坐标」改成「匹配小模板图」，天然免疫漂移与 CJK 双宽；需装 `opencv-python`——**本机原先缺 `cv2`，`pyautogui.locateCenterOnScreen(confidence=)` 在此之前根本用不了**；⚠️ 纯色区做模板会退化成「到处都 1.0」，`save_template` 已加低方差拒绝）；② `robot.py` —— **组件树定位**（IDE 内 robot-server 的 `POST /xpath/components` 用 XPath 找 Swing 组件，返回的 x/y **相对父组件**，须沿 XPath 祖先链累加到屏幕物理坐标；实测历史页签定位并点击成功）。
>
> **robot-server 装入只有机制 A 可用（2026-10-18 实测定案）**：`build.gradle.kts` 的 `localPlugin(...)` 在 `-PgifRobotServer=true` 时把 robot-server 随 prepareSandbox 装进沙盒——**现已与 `gifRecord` 解耦、可独立生效**。旧机制 B（`bootstrap-robot-server.py` 拷进沙盒 `config/plugins/`）**已否决**：runIde 显式钉死 `-Didea.plugins.path=<沙盒>/plugins`，`config/plugins` 不在加载路径（idea.log 零 robot 痕迹、8580 拒连）。另注意 **`-PgifRecord=true` 会把沙盒切到 262**——录 243 默认平台时不要带它；dev 树验收沙盒标准启动＝`.local/start-ui243.ps1`（runIde + `-PgifRobotServer=true`、无 gifRecord、独立 init 脚本沙盒 + 登记表）。**Windows UIA 路线已实测否决**（UIA 只看得到 Swing 顶层 `SunAwtFrame`、children=0，`jabswitch` 已开亦然）。**robot 对 JBPopup 弹层（ListPopup/右键菜单/More 折叠菜单）不可达**，弹层内定位用下表「像素带菜单定位」或键盘 Down×(n−1)+Enter（ListPopup 初始选中第一项）。

**定位三件套 `locate.py`（不靠目测）**

| 函数 | 用途 | 实测口径 |
|---|---|---|
| `window_rect(title=/pid=, pick='largest'/'smallest')` | 取窗口屏幕矩形。**对话框/浮窗不是进程主窗口**，`MainWindowHandle` 看不到，必须 EnumWindows 按 PID 取 | 设置对话框 (399,79)/1125×875 |
| `button_row_y(rect, offset=39)` | 弹窗底部按钮行 y ＝ **底边 − 39**（弹窗高度随内容变，**目测必错**） | 本轮目测值偏低 48px、连点三次全落空；按底边算两次都对 |
| `bright_band_y(src, x0, x1, y0, y1, thr=35)` | 像素剖面：逐行取均值找最亮连续段（亮文字带） | 按钮文字行均值 47~60、深色背景 26 |
| `bright_band_x(src, y0, y1, x0, x1, thr=45)` | 列向对偶：按钮行上按列找**亮簇**（按钮文字） | 右对齐按钮的相对 x 随弹窗宽度变，**写死必错** |
| `pid_by_cmdline(marker)` | 按命令行特征找 PID（如沙盒路径 `MPSV-GifFresh`） | **`window_rect(pid=None)` 会匹配到别人的同尺寸窗口**（本轮撞上 ZCode 的 `ZCodeTpsFooter` 993×519，据此算出的 x 落到 IDE 边界外、点进了那个窗口） |
| `ensure_foreground(hwnd)` | 置顶 + 点击取焦 + **校验前台句柄** | **鼠标点击只看 z 序，键盘快捷键要求焦点**——沙盒不在前台时 `Ctrl+Shift+N`/`Alt+Shift+Q` 会静默送给别的窗口（症状＝开文件没反应、预览不出、不报错） |
| **像素带菜单定位**（2026-10-18 沉淀，主仓 `record/probe_menu_pix.py`） | 弹出菜单项点击坐标，双帧 diff 推算 | 打开菜单截帧 A → `Down×1` 截帧 B → `ImageChops.difference(A,B).getbbox()`＝首项高亮带 → 首项屏幕 y＝菜单区 origin_y+bbox.top、项高＝bbox 高（24px；带 padding 的带为 48px 取半）→ 第 n 项 y＝首项 y+(n−1)×项高+项高/2。**天然免疫 hover 读图的 24~48px 漂移**。**ListPopup 初始选中＝第一项**：键盘命中第 n 项＝`Down×(n−1)+Enter`，别按「无初始选中」直觉数 |

⚠️ 暗色主题下**别用「空白≈0」的直觉设阈值**：面板底色均值仅 28、收起态本底噪声就有 13（实测收起 13.09 / 展开 34.95 → 阈值取 22）。

**自检标准件 `verify.py`**

```python
from verify import Guard
g = Guard()
g.mark("preview", region=(367,166,1190,702))
g.require("preview", msg="预览未就绪/被浮层遮挡")   # 开录前断言：不符就中止，别录坏画面
r.start()
g.after_click((1256, 829), name="preview", msg="点『执行 SQL』落空")
```

- `fingerprint()` / `differs()` 用 **8×8 分块最大差**（不是全图均值差——小区域变化会被稀释，与 `gifkit`/`assemble` 静段判定的 16×16 分块同源）。
- `after_click()` 无可见变化即判落空（坐标偏 / 未聚焦子窗口吞首击 / 上游开关把整组置灰，症状完全相同），可按 `retries` 重试。
- `require()` 是**开录前**的闸门：浮层没清、面板没开、停错页，一律当场中止。

**本构图的现成数值不写在这里**，写在项目侧 `record/BASELINE.md`（构图基线坐标表）：同一构图下的坐标只标一次，新片先查表、缺的用 `locate.py` 现算、算完回填。构图一改整表作废。

## 设置对话框（Settings）五条硬边界

设置类画面（MCP 门控、捕获诊断、显示模式这类「在设置页点开关」的演示）会撞上五条实测边界，每条都白烧过轮次：

1. **设置页内容滚动（2026-10-28 口径修正）**：`pyautogui.scroll` 真实滚轮**有效**（先把鼠标 moveTo 内容区再滚，约 0.3px/档，40×`scroll(-100)`≈2.5s）；`PostMessage(WM_MOUSEWHEEL)`、PageDown（会切左树）、Tab 逐格（30px/22 次）都不可用。**滚动位置不跨「关→开」保留**，滚动段只能放 take 内。详见坑 35。
2. **对话框是模态的**：它开着时**对其它窗口的点击一律无效**。要关 SQL 预览浮窗、点编辑器，**必须先关对话框**——否则会陷入「反复点浮窗取消按钮毫无反应」的死循环。
3. **`Esc` 是对话框的取消键**：拿它去关搜索框的补全下拉会**连整个设置对话框一起关掉**。补全下拉用「点别处」或方向键收，别用 Esc。
4. **对话框不是进程主窗口**：`Get-Process.MainWindowHandle` 看不到它。须 `EnumWindows` + `GetWindowThreadProcessId` 按 IDE 的 PID 过滤，再 `GetWindowRect` 取边界（实测设置对话框 (399,79)/1125×875，与沙盒放大态 (399,0)/1129×1039 两种）。
5. **内容不能合成滚动、且会被主窗口的 TOPMOST 压住**（2026-10-13 实测，见坑表 35/39）：`pyautogui.scroll` 与给对话框 hwnd 发 `WM_MOUSEWHEEL` **都是 no-op**（前后整屏截图 md5 相同），首屏之下的分组只能**请用户人工滚到位**；同时 `ensure_foreground` 给主窗口设的 `TOPMOST` 会让对话框**永远被盖住**——take 前要 `SetWindowPos(mw, HWND_NOTOPMOST)` + 真实点击对话框标题栏取焦，且**别把主窗口置前**。

另：**设置页搜索框过滤不稳**——两词查询（`SQL Visualizer`）会报 "Nothing to show" 并把首词标红，单词查询（`Visualizer`）不筛选只自动选中根节点，`MCP` 这类才正常过滤；补全下拉还会吞掉输入。**要「树展开到某页」，手动展开 + 拖树滚动条比走搜索稳**；若已过滤出插件各页，左树**行位会随树内容变**（过滤前后差几十像素），点行前先截图核位。

## 成片时长与收尾落点（两个画面陷阱）

- **裸屏捕获不含光标**：`Recorder` 只采像素，**鼠标移动不产生任何像素变化**，于是被 `assemble.py` 的静段裁剪整段吃掉——纯凝视型 take 转出的成片实测只有 **3.6s**（首版；take 本身的 mp4 是完整的），凝视动作全没了。**片子要有长度，take 里必须安排真实 UI 变化**（浮窗弹出、面板展开、结果网格出现、临时文件执行这类）。这也解释了同类成片时长差异悬殊：有真变化的能到 7.5s，只有鼠标滑动的只有 3.6s。
- **IDE/插件的 documentation 浮层会「粘住」**：鼠标压到编辑器里的标识符会弹出列文档浮层（Data Source / Schema / Table / Column 那种），**鼠标离开也不退**（Esc 可关），末帧右侧被整块挡掉。触发点常常是**开文件时坐标扫掠的余点**——弹窗关掉后余点落到代码上。
- **收尾鼠标停「无浮层空白区」**：预览面板左栏、底部原生控制台都安全；**别停在编辑器代码上**（弹文档浮层），也**别停在结果网格上**（弹列类型浮层，如 `partitions: MEDIUMTEXT`）。落点选错只毁末帧，但末帧通常就是 poster。

## MCP 辅助录制（IDE 内置 MCP Server）

**用途**：把「准备现场」从坐标点击换成语义调用——开文件、起运行配置、查数据源、执行 SQL。**不替代**录制本身（GIF 要真实的鼠标轨迹与逐帧动画，MCP 是「瞬间完成」录不出过程）。

### 接入（一次性，需用户授权）

IDE 设置 → **Tools → MCP Server → Enable MCP Server**。会弹权限确认框（授予第三方应用「读取所有打开项目文件 + 触发 IDE 动作 + 执行终端命令」），点 Enable。

- 启用后端点 = **`http://127.0.0.1:64342/sse`**。注意**端口从 64442 变成 64342**。
- 启用后自动带 token，**不再 401**；未启用时任何请求都是 401，且 token 存在 IDE 凭据库、拿不到明文。
- ZCode 侧配置：`~/.zcode/cli/config.json` 的 `mcp.servers.idea` 写成
  `{"type":"sse","url":"http://127.0.0.1:64342/sse","enabled":true}`，**需重启会话才加载**。

### 录制脚本里的用法（`record/mcpnav.py`）

```python
from mcpnav import open_file, open_paths
open_file("mybatis-plus-demo/src/main/resources/mapper/UserMapper.xml")  # 项目内相对路径
open_paths()   # 返回当前激活文件 + 全部打开文件，可用于开录前断言没开错
```

**这是本轮最大的提效点**：demo 工程里同名文件极多，`Ctrl+Shift+N` + 方向键一轮踩了三次（`UserMapper.xml` 开成 `NativeUserMapper.xml`、`UserMapper.java` 开成 mpjdemo 模块的、`UserRepository.java` 方向键落到 `UserMapper.java`），每次都要截图发现再重来。`open_file_in_editor` 吃 `filePath` + `projectPath`，一次到位。

**它没有行号参数**——跳行仍用 `goto_line`。

### 对录制有用的工具

| 工具 | 用途 |
|---|---|
| `open_file_in_editor` | 按路径精确打开并激活页签（`filePath` 相对项目根 + `projectPath`） |
| `get_all_open_file_paths` | 开录前断言没开错文件 |
| `get_run_configurations` / `execute_run_configuration` | 起 demo 应用（替代 `Ctrl+Shift+F10` + 盲等编译） |
| `create_database_connection` / `edit_database_connection` | 建/改数据源（`name`/`dbms`/`url`/`needToCheckDs`，凭据可写进 URL） |
| `list_database_connections` / `list_database_schemas` / `test_database_connection` | 查连接与 schema、验连通 |
| `execute_sql_query` | 直接跑 SQL 验数据 |

### 三个实测约束

1. **必须传 `projectPath`**，否则报「Unable to determine the target project」。
2. **参数名严格**：`open_file_in_editor` 是 `filePath`（不是 `path`）；`execute_tool` 是 `command`（但它要的是工具名不是 action id，调 IDE Action 走不通）；`search_text` 是 `q`。猜错直接报 `No argument is passed for required parameter`。
3. **`needToCheckDs: true` 会超时**（它在等连接探测甚至凭据弹窗）。批量建源用 `false`，之后单独 `test_database_connection`。

### 与 robot-server 的分工

| | robot-server（8580） | IDE MCP（64342） |
|---|---|---|
| 组件树/坐标 | ✅ 主窗组件树 + 相对坐标 | ❌ 只有数据源/SQL 这类语义接口 |
| 开文件/起应用 | ❌ | ✅ |
| 执行 SQL | ❌ | ✅ |
| 独立弹窗（balloon、原生 FileChooser） | ❌ 不在主窗树里 | ❌ |

**结论**：现场准备用 MCP，组件定位与点击用 robot-server，两者互补。**`computer-use` 技能对 IntelliJ 无效**（见坑 55），别在它上面花时间。

## 坑表（现象 → 原因 → 处置）

1. GIF 里混入旧布局尾帧 → assemble 解帧目录没清 → 已修，仍要核对帧数一致
2. 监听列表恒空 → 捕捉按钮点在控制台出现之前 → 先启动被监听对象再点捕捉，之后主动触发动作
3. 画面里出现输入法候选框 → IME 中文态组合输入 → 录制期锁英文态，打字前看任务栏指示器
4. 点击落点偏/点进错误文件 → goto 居中漂移、裁剪坐标忘加偏移，或外部鼠标干扰 → 全键盘化 + `pyautogui.click(x,y)` 原子落点 + 录制期确认系统空闲 + 坐标逐级换算后截图核对落点
5. IDE 抢不到前台/点到后面的窗口 → SetForegroundWindow 不改 Z 序；DWM 阴影边不算可视区 → TOPMOST→NOTOPMOST 舞步 + 坐标留在可视区内
6. 窗口移到位又被弹回 → AWT 最小高度钳制（改缩放后重算） → 等 IDE 空闲后再 MoveWindow，或重启让缩放从配置读入
7. 输入的文本没出现/变样 → press('enter') 被 JBR 吞、IME 组合态 → Enter 用 keyDown/keyUp('return')，多行文本用剪贴板粘贴
8. 误打进编辑器的文字 Ctrl+Z 撤不掉、关标签重开还是脏 → 撤销栈异常时 undo 无效，且关闭重开会复用内存脏 Document → 右键编辑器 > Local History > Show History，选污染前时间点 Revert（按内容快照回滚，不依赖撤销栈）；磁盘是否真脏以 `git status` 为准
9. IDE 快捷键无响应、屏幕弹出截图/取色覆盖层 → PixPin 等常驻截图工具的全局热键劫持（实测 Ctrl+Shift+A=截图、Ctrl+Alt+S=取色）→ 避开撞键组合；开 Settings 走 IDE 工具栏齿轮菜单（齿轮 → Settings…），不赌快捷键
10. 置顶覆盖层（自动化面板/截图工具）盖住沙盒，点击打进无关应用 → TOPMOST→NOTOPMOST 舞步结束即回到非置顶带，压不过置顶层 → 点前 EnumWindows 核对目标窗口标题；让 IDE 持续 TOPMOST 干完再撤；前台化用 AttachThreadInput+SetForegroundWindow（按 hwnd 定向，勿 FindWindow 取第一个）
11. 活动态像素判定失灵（该亮不亮/恒亮）→ 判定带没盖住目标元素，或阈值量到的是别的元素（曾把「聚焦下拉边框的亮蓝」当成「活动标签高亮」）→ 先沿判定带扫一条像素列打印真实 RGB，确认元素颜色与位置后，用**活动/非活动两态都量一遍**再定规则和阈值
12. runIde 构建超长疑似卡死（daemon 低 CPU、产物目录长时间无变化、日志停在同一行）→ gradle client/daemon 锁死或 jar 插桩卡住 → 杀 client 和相关 daemon 后重跑（第二次全 UP-TO-DATE 很快）；跑构建时输出直写日志文件（`> abs/path.log 2>&1`），别用 `-q` 加管道（缓冲到结束才可见）
13. 合成点击对对话框按钮/浮层菜单项/通知气泡「点了没反应」（悬停高亮会动但按下无效，或根本无反应）→ 未聚焦子窗口的第一击被「点击聚焦」吞掉；PixPin 等低层钩子也会随机吞事件 → 同目标连点两次（第一击激活第二击触发），或先点无害处激活窗口再点目标；**先排除 PixPin**（taskkill 后若恢复即钩子问题）
14. 浮层/对话框开着一开就不响应任何输入（僵尸态）→ 常驻截图工具低层钩子吞事件或焦点被外部 toast 抢走 → 先 taskkill PixPin 类工具；动作链收进单脚本一次跑完，不留跨工具调用间隙
15. 右键菜单底项（anchor=last 的功能项）超出窗口下缘被削半行 → 菜单全高 ~640px 从 caret 锚点向下展开 → prep 里 goto 目标行 + Ctrl+Down 数次把目标行推到视口上部（y≈340）再右键
16. 新开对话框里像素检测到的控件坐标点上去落空 → 对话框有 1~2s 落位动画，检测和点击之间位置漂了 → 双次扫描一致（<12px）才点击；扫描带只框目标列并跳过易误检行（见标定节）
17. 录制成片窗口底边透出别的窗口内容（亮色条带）→ 窗口 rect 底部 ~9px 是 DWM 阴影/隐形边框区，z 序下方窗口从这透出 → `python .agents/skills/ide-demo-gif/scripts/black_underlay.py [IDE窗口标题关键字]`：纯黑垫底窗插在沙盒窗口 z 序正下方遮蔽；后台常驻，录完随会话清理
18. canvas 像素采样判定 GIF 动画「不播」但用户实视在播 → `canvas.drawImage` 对动画 GIF 只绘制首帧（Chromium 行为），像素指纹恒定是方法假象 → 动画判据只能是**真实可见渲染（用户目视 / 真实浏览器前台，IAB 正常播）**；canvas 采样与单帧截图不可作动画判据。自动侧只验资产多帧（ffprobe 帧数 >60）+ 组件结构就绪态 + 断图回落 + 亮暗色。DOM 就绪≠动画在播（2026-09-15 实证：动态插入叠层结构下 GIF 停帧但 ready class/opacity 1 全绿）
19. ctypes 调 SetWindowPos 传 TOPMOST 报 1400 假失败（MoveWindow 却正常）→ 未声明 argtypes 时 -1 被当 32 位 int 塞进 64 位句柄参数 → `u32.SetWindowPos.argtypes = [c_void_p, c_void_p, ...]` 后传 `ctypes.c_void_p(-1)`；或直接用 MoveWindow
20. IDE 启动后弹「语言包可用/试用推荐」等通知气球，点不动还入镜 → 气球对合成点击免疫（同坑 13）→ 同位置连点两次（Don't ask again/✕），或重启 IDE 前先点掉；带按钮的气球不会自动过期，必须清掉再录
21. 投放进页面的 GIF 动画静止但组件 DOM 全绿（ready class/opacity 1、img complete）→ GIF 以「hydration 后 JS 动态插入 + absolute 叠层」渲染，部分 Chromium 环境解码停帧；首页「SSR 初始直出」结构动画正常 → 投放优先文档流 img 直出（对齐已验证结构），动画最终确认交给真实可见渲染（2026-09-15 文档站实证，详见文档站 docs/开发笔记/20260915-1300-GIF动画播放验证与渲染结构-开发笔记.md；注意 IAB 能正常播 GIF，勿以 canvas 采样判动画）
22. 设置页内容滚不动（滚轮 / PageDown / 拖页面滚动条全无效）→ Swing 设置页对滚轮免疫，且**只有左树的滚动条可拖** → 拉大对话框（见「设置对话框四条硬边界」）；⚠️ **判「滚不动」前先确认目标不在极限位**——我一度用「树滚不动」断定滚轮全坏，其实那次树本就在底部、且 pyautogui 正数＝向上滚，方向也搞反了
23. 点设置对话框以外的窗口（浮窗取消、编辑器）毫无反应 → **对话框是模态的**，屏蔽了对其它窗口的点击 → 先关对话框再操作别的窗口
24. 用 Esc 关搜索框补全下拉，结果**整个设置对话框被关掉** → `Esc` 是对话框的取消键 → 补全下拉用点别处或方向键收
25. 按窗口标题 / `MainWindowHandle` 找不到设置对话框 → 对话框**不是进程主窗口** → `EnumWindows` + `GetWindowThreadProcessId` 按 PID 过滤再 `GetWindowRect`
26. 成片只有 3~4 秒（脚本动作却有 10 秒上下）、安排的凝视动作全没了 → **裸屏捕获不含光标**，鼠标移动无像素变化被静段裁剪整段吃掉 → take 内必须安排真实 UI 变化（浮窗弹出/面板展开/结果网格/临时文件执行）
27. 末帧被一大块「Data Source / Schema / Table / Column」浮层挡住 → 鼠标压编辑器标识符触发的文档浮层**会粘住**（离开不退，Esc 可关）；触发点常是**开文件扫掠的余点** → 收尾鼠标停预览左栏 / 原生控制台等空白区，别停代码也别停结果网格
28. 点弹窗底部按钮连点多次都不中 → 弹窗高度随内容变、按钮行位置浮动，**目测写死必错**（实测偏低约 48px） → 按弹窗底边推算（`bottom − 39`）或用像素剖面定位按钮行
29. 录执行类画面报语法错，但应用侧明明能跑 → **IDE 数据源连接参数与应用 URL 不一致**（如缺 `sessionVariables=sql_mode=ANSI_QUOTES`）→ 两边 URL 逐字拉平后**重启 IDE** 生效；顺带记住：同一段 SQL 的合法性取决于连接 sql_mode，判「示例坏了」之前先对两处 URL
30. **沙盒 IDE 自己重启后，跑着的其实是日常 IDE**（2026-10-12 实测，据此误判过一轮）：许可/插件变更会触发 `#c.i.u.Restarter - run restarter`，它以**新进程**拉起 IDE，但**丢掉沙盒的 `-Didea.config.path` 等参数** → 拉起的是**日常 IDE**，沙盒就此消失（`runIde` 任务随之 exit 7，而日志末尾只有一行无害的 ObjectTree SEVERE，极易误判成「IDE 重启了、还在沙盒里」）。处置：重新拉起沙盒，并尽量避免在沙盒里触发需要重启的操作。
31. **「怎么判在不在沙盒里」只有一个可靠判据：日志落点**（2026-10-12，用户连纠两次后定案）。按命令行判**不成立**——正确启动的沙盒命令行可能只是一个裸 `idea64.exe`（路径全在 vmoptions 文件里）；按窗口标题判**不成立**——起错的日常 IDE 同样有主窗口、同样不弹许可（用的就是日常那份有效许可）、标题同样像正常工程。**唯一硬判据**：起完立刻核 `build/MPSV-<名>/…/log/idea.log` 的 mtime 是否在刷新，且日常 `%LOCALAPPDATA%\JetBrains\IntelliJIdea<ver>\log\idea.log` 不动；反了就是起错了。核 `ls` **必须带 `-t`**：不带 `-t` 时 `idea.log` 会被 `tail` 按字典序截掉（`i` < `t`），据此曾得出完全相反的结论。
32. **启动沙盒统一用 `./gradlew runIde -PgifRecord=true --args=<demo 工程绝对路径>`**（用户 2026-10-13 定调）。它同时做两件事：**重建并安装插件**（改了插件代码必须走它才生效）+ **把平台 `bin/idea64.exe.vmoptions` 里的 agent/参数带进 JVM**（进程命令行可逐字核对）。⚠️ **2026-10-25 修正**：IntelliJ Platform Gradle Plugin **2.x 不再 merge 沙盒 `config/idea64.exe.vmoptions`**（`IntelliJPlatformArgumentProvider` 只读平台 `product-info.json` 指明的 `bin/idea64.exe.vmoptions`）——所以把 agent 行写进沙盒 `config/` 那份**对 2.x 无效**，必须写平台 `bin/` 那份，或用 `jvmArgumentProviders` 显式传（详见坑 48）。

  ```bash
  ./gradlew runIde -PgifRecord=true --args=F:/code/.../mybatis-plus-sql-visualizer-demo
  ```

  **启动约定（用户 2026-10-13 定调、2026-10-13 再次强调）：沙盒只许用「上面这条命令」起，不许用任何「自己拼参数的 `idea64.exe …`」形式。** 两者区别就在这里——前者是「命令」，后者是「自定义命令指定参数」，而后者全都会「看起来正常」地起错：

  - `idea64.exe "-Djb.vmOptionsFile=…\sandbox.vmoptions"`：启动器**不解析该命令行参数**，静默退回默认配置 = **起了一个日常 IDE**（2026-10-12 用户连纠两次，定调严重问题）。
  - `idea64.exe -Didea.config.path=<沙盒>/config -Didea.system.path=… -Didea.plugins.path=…`：看似把沙盒三路径都指了，但**绕过 gradle**（插件改动不重装）且**不读沙盒 `config/idea64.exe.vmoptions`**（用户保留的 agent/参数被静默漏掉）。项目记忆 `runide-demo-verify-workflow` 里那条「手工启动沙盒 IDE 的可用命令」**已作废**，勿再照做。
  - `IDEA_VM_OPTIONS` + `record/sandbox_start.cmd`：口径作废（同上，只读 `record/sandbox.vmoptions`）；该 `.cmd` 文件头亦自述「FALLBACK ONLY」，不再作应急备选。

  **许可/插件变更会让 IDE 自己重启（`Restarter`）——别顺着走**：它会丢掉沙盒的 `-Didea.config.path`，把 IDE 拉成**日常 IDE**（坑 30），`runIde` 任务随之 exit 7。**正确处置＝杀掉那个进程，重新跑上面这条 gradle 命令**，不要接受它的重启结果。

  起完**只认日志落点**判身份（见 31），并顺手看一眼进程参数里**有没有**用户保留的那个 agent，用来确认走的是 gradle 这条路。
33. **沙盒 IDE 前台正常但合成输入完全无效**（2026-10-13 实测）：`pyautogui.moveTo` 光标确实移动，但左键点工具窗页签（如「SQL 历史」）页签不切换、编辑器内右键不弹上下文菜单、键盘 `Ctrl+Shift+A` 不弹 Search Everywhere；`GetForegroundWindow()` 一度恒返回 `Shell_TrayWnd`（任务栏），点任务栏上的 IDE 按钮后它才等于 IDE 主窗口，但置前后输入仍不生效 → **沙盒 IDE 进程与它的父进程 Gradle daemon 都是提权（管理员）进程，而自动化脚本进程非提权**，Windows **UIPI**（User Interface Privilege Isolation）会拦掉低完整性进程发往高完整性窗口的合成输入。「光标能动但点击/按键一律无效」这个组合就是 UIPI 的指纹 → **让录制脚本以提权身份运行**：本机 `Start-Process -Verb RunAs` 可静默成功（实测 `Start-Process cmd -ArgumentList '/c echo ok > <文件>' -Verb RunAs` 后文件确实生成）；提权进程的 SendInput 能送达提权窗口、也能送达更低完整性的窗口（Chrome 背景等），故**整条录制脚本提权跑即可**，不必只提权一部分。推荐封装本仓 `record/_run_elev.bat`（把场景脚本名作参数：`cd /d %~dp0` + `python %1 > _elev_run.log 2>&1`），调用 `powershell -NoProfile -Command "Start-Process -FilePath 'cmd.exe' -ArgumentList '/c','<绝对路径>\_run_elev.bat','scene_xxx.py' -Verb RunAs -Wait"`。

  **排查顺序**：先排除常规干扰（PixPin/Snipaste 类钩子、模态对话框、IDE 忙），**然后必查提权**。查提权别只看窗口标题或命令行（两者都看不出提权与否），要看进程 token 的 `TokenElevation`：`OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION=0x1000)` + `OpenProcessToken(TOKEN_QUERY=0x8)` + `GetTokenInformation(TokenElevation=20)`；或用 `CreateToolhelp32Snapshot` + `Process32First/Next` 走父子链（实测 `java.exe(IDE) → java.exe(Gradle daemon)` 两级都 `elevated=True`，脚本自身 `elevated=False`）。

  **附带结论**：非提权进程**杀不掉**提权进程（`taskkill` 报拒绝访问），所以一旦沙盒被提权拉起，「关掉重开」这条路对非提权会话是走不通的——要么提权操作，要么请用户手工关。
34. **提权脚本每次都会弹一个黑控制台窗口，盖在 IDE 之上**（2026-10-13 实测，本轮大量「点击落空」的**共同根因**）：`Start-Process … -Verb RunAs` 起的 cmd 会显示控制台窗口，后续所有合成点击/滚轮都打到那个黑窗上——症状是「点 A 变 B、点树行错位、滚轮完全无效」，而且**从现象上极难归因**（会误判成坐标漂、控件位置变）。处置＝调用一律加 **`-WindowStyle Hidden`**：

  ```powershell
  Start-Process -FilePath 'cmd.exe' -ArgumentList '/c','<绝对路径>\_run_elev.bat','scene_xxx.py' -Verb RunAs -WindowStyle Hidden -Wait
  ```

  加后黑窗不再出现、点击恢复可靠。
35. **设置页「合成滚动」口径修正（2026-10-28 批4 实测）**：`PostMessage(WM_MOUSEWHEEL)`、PageDown、Tab 仍是 no-op 或有害（PageDown 会切左树选项），但 **`pyautogui.scroll` 真实滚轮有效**——前提是先把鼠标 `moveTo` 内容区（如 (1200,700)）再滚。速度约 **0.3px/档**：40×`scroll(-100)` ≈ 2.5s 从页顶滚到目标行，放进 take 可接受（滚动过程入镜反而自然）。**滚动位置不跨「关→开」保留**——重开设置回到页顶，所以滚动必须在每次 take 内做。**左树行位随树内容与过滤状态漂移**（同一行的 y 在过滤前后差几十像素），点树行前必须截图现核；先用搜索框过滤再按过滤后的行位点。体积上滚动段是大头：滚动循环的 sleep 压到 0.01s（快滚省帧又自然）。
36. **「捕捉」类开关是悬停才渲染的工具窗标题栏动作**（2026-10-13 实测，本项目 SQL 日志面板）：它**不在常规工具栏里**，必须把鼠标**悬停在工具窗工具栏那一行**才现形，形态是**绿色三角 ▶**（tooltip「捕捉」），点一下变红色方块＝捕获中。**没开捕捉时面板只有空态文案、不收任何条目**——「控制台里明明有 SQL、列表却空」是**正常现象**，别误判成插件缺陷（本轮据此白查过一轮）。开启后空态会换成实时诊断行，能直接读出捕获状态（`捕捉中 | 增强捕获：已挂载 pid=… | 已收事件 N 条` / `控制台：已挂载 N 个，已扫描 N 行，产出 N 条`）——**开录前先读这行确认在收条目**。
37. **take 内绝不点工具窗工具栏的任何按钮**：悬停态下「捕捉 / 清空 / 设置」相邻且坐标随面板宽度漂，实测点「清空」三次都误中「设置」，把整个设置对话框弹出来毁掉整条 take。清空、排序这类动作一律移到 prep，或干脆不做。
38. **要录「运行期捕获」类画面，被录应用必须从 IDE 里跑**（2026-10-13 实测）：外部 `mvnd spring-boot:run` 起的进程，插件的捕获链挂不上（列表恒空）；`Ctrl+Shift+F10` 于主类、从 IDE 里跑才行。且该进程是提权 IDE 的子进程，**非提权 `taskkill` 杀不掉**，杀它也要走提权脚本。
39. **设置对话框是独立窗口，主窗口的 `TOPMOST` 会一直压着它**（2026-10-13 实测）：`ensure_foreground` 会给主窗口设 `TOPMOST`，此后设置对话框即使 `SetForegroundWindow` 到前台也**始终被主窗口盖住**——表现为「take 里怎么点都只拍到主窗口」。处置＝take 前先 `SetWindowPos(mw, HWND_NOTOPMOST)` 撤置顶，再 `SetForegroundWindow(dlg)` **并补一次真实点击对话框标题栏取焦**（`SetForegroundWindow` 受前台锁限制常静默失败，真实点击才稳）。**别把主窗口置前**——那会把对话框整块盖住，take 前半段拍不到「改设置」那半。
40. **CJK 是双宽，锚点按单宽估算会落到相邻 token 上**（2026-10-13 实测，白查一整轮）：`-- 设备信息表（对齐插件测试 car_device_info）` 这类中英混排注释里，`对齐插件测试`（屏幕 x≈544-652）与 `car_device_info`（≈657-822）相邻；按单宽估算取 x 会落在**中文词**上，动作于是把「对齐插件测试」当表名执行 → `1146 Table … doesn't exist` → 结果网格空。**两层教训**：①锚点必须在**放大裁剪图**上量（CJK 双宽会让整图读数系统性偏）；②**判「插件缺陷」前先看产物侧证据**——那次真因就写在 IDE 控制台的 **Output 页**（执行的 SQL 原文 + 报错行），只看「网格没数据」会误判成插件缺陷、白派一轮子智能体。
41. **两个输入层陷阱**（2026-10-13 实测）：① `goto_col` 里连发方向键**会被丢键**——`for _ in range(col-1): keyDown/keyUp('right')` 无间隔时实测 31 次只生效 24 次（列号短 7），每步补 `time.sleep(0.02)` 后稳定；② **`force_en_us` 只调 `ActivateKeyboardLayout` 无效**——它只改**调用线程**的键盘布局，对前台 IDE 不起作用（`typewrite('schema.sql')` 仍被中文 IME 转成「上车吗。sql」）；正解＝给前台窗口 `PostMessage(WM_INPUTLANGCHANGEREQUEST, 0, hkl)`，或干脆**用剪贴板粘贴代替 typewrite**（本轮所有「打开文件」都改成剪贴板粘贴后不再出乱码）。
42. **探针/截图文件名含 `\u`+十六进制样片段会让 Python 直接语法崩**（2026-10-18 实测）：`imgu19k-*`/`u04`/`u20`/`u12isol` 这类名字在 Windows py 编译时命中 `SyntaxError: (unicode error) truncated \uXXXX escape`——报错指向字符串字面量（如 `"...\imgu19k-1.png"`），极难联想到文件名。**处置＝文件名避开 `\u` 前缀形态**（用 `img*`、`img_u12isol*` 这类无转义前缀），或字符串前加 `r''` 且文件名不含 `\u` 序列。
43. **同名文件会开错模块**（2026-10-18 实测）：demo 仓有两个 `UserServiceImpl.java`（mpjdemo/mybatisplusdemo 两模块），Ctrl+Shift+N 开到的与锚点行号表对应的**不是同一个**——goto(line=36) 落进了 Javadoc 行，预览触发失败。**处置＝open_file 查询带模块可分辨段，或改用该文件内唯一文本 token 落点**（`goto_text`），不要依赖跨文件的行号表。
44. **IDE 索引期（右上 Analyzing...）动作不生效**：dumb 模式下快捷键/动作静默无效（探针表现为「Alt+Shift+Q 后预览不出、无报错」）。**处置＝动作前等索引完成**——轮询右上角 Analyzing 区域亮度（亮色文字簇 < 阈值即空闲），最多等 90s。
45. **「点了没反应」的三连排查**（2026-10-18 UI-023 专项定案，按序）：①win32 `EnumWindows` 按标题枚举——目标窗口可能**已创建但 z 序压在主窗后**（截屏不可见、枚举可见，rect 常与主窗全等；取证＝最小化主窗→全屏截图→恢复，`SetForegroundWindow` 受前台锁常静默失败）；②**全屏帧**——`ImageGrab.grab()` 无参；`bbox=rect` 只截面板会把右下 toast 裁掉（「无通知」假象的头号来源）；③**在役包核验**——javap 沙盒 `plugins/<plugin>/lib/*.jar`（JDK8 javap 读不了 21 字节码，用 `.jdks/ms-21`）确认修复符号在役 + jar mtime 与 IDE 启动时序比对；「javap 过 build 产物」不算数（installPlugin 时点与探针时点错 2 小时即全盘误诊，本轮实锤）。
46. **阳性对照是弹层排障的最快定案手段**：怀疑「菜单点击链路失效」时，先像素点一个**必有可见反应**的相邻项（如「分享」弹对话框）——对照阳性＝链路无恙、问题在目标项内部；对照阴性＝链路失效。一轮实验替代数小时静态推演。
47. **设置页搜索对插件自定义 Configurable 无索引**（2026-10-18 补充坑表 22/「搜索框过滤不稳」）：搜插件页的自定义控件词（如「清除预览 SQL 历史」）报 "Nothing to show"——自定义 Configurable 未实现内容搜索。**要定位插件设置页，用页名/控件名短词**，或左树手动展开 + 拖树滚动条（树上滚动条可拖）。GUI 改设置开关三条路全堵时（滚轮 no-op/搜索无索引/对话框拉到上限仍装不下），兜底＝读沙盒 `config/options/<plugin>.xml` 记录状态，不冒充 GUI 通过。
48. **补丁 agent 加载了却仍弹 `Start Free Trial`：缺的是 `idea.key`，不是 agent**（2026-10-25 实测，本轮误判了三轮）。症状极具误导性——进程命令行里 `-javaagent:…qwdn-afb.jar` 逐字在、`jcmd <pid> VM.class_hierarchy` 能列出 53 个 `com.janetfilter.*` 类（连 `plugins.power` 都在）、界面却照样弹试用横幅。**根因**：补丁只绕过许可**签名校验**，本身不产生许可；沙盒 `config/idea.key` 不存在（日常 IDE 那份在 `%APPDATA%\JetBrains\IntelliJIdea<ver>\idea.key`，5300 字节，`certificate-key` 开头）。**为什么突然缺**：`build/` 一被 clean（发版流程必做），沙盒目录整个消失，config 里那份 key 随之没了。**处置**：把日常的 `idea.key` 拷进沙盒 `config/`，重启沙盒。**判定成功的两条硬证据**（缺一不可，别只看横幅）：①右上角 `Start Free Trial` 消失；②`//div[@accessiblename='Database']` 能命中 1 个 `SquareStripeButton`（Ultimate 能力解锁的判据）。**另附一条 API 事实**：IntelliJ Platform Gradle Plugin 2.x 的 `IntelliJPlatformArgumentProvider` 只读**平台自身** `bin/idea64.exe.vmoptions`（`vmOptionsFilePath`），**不再 merge 沙盒 `config/idea64.exe.vmoptions`**——所以坑 32 里「gradle 会合并沙盒 config 的 vmoptions」这条对 2.x 已不成立；稳妥做法是把 agent 行写进平台 `bin/` 那份，或在 `buildSrc/src/main/kotlin/mpv-runide.gradle.kts` 的 `runIde` 块里用 `jvmArgumentProviders` 显式传（本项目已支持 `-Pmpvs.licenseAgent=<jar 绝对路径>`）。**另有一个假线索**：`~/.7t/logs/7-tool.log` 里那些 `[license] generate ok` 是独立 GUI 工具「7-Tool」写的，与沙盒 javaagent 无关，别拿它当 agent 生效的证据。
49. **成片四周一圈白边 / 顶部像被切掉：录制区域没跟窗口外框对齐，或照外框录进了圆角阴影**（2026-10-25 用户两次指出）。两个独立问题，要一起修：
    - **对齐**：`REGION` 的左上角必须等于 `GetWindowRect(hwnd)` 的左上角。挪过窗口却没同步 `REGION` 就会错位——实测把窗口移到 (263,5) 而 `REGION` 仍是 (277,25)，顶部标题栏被切 20px、左侧 stripe 被切 14px，成片看起来「IDE 没录全」。**开录前必做**：`MoveWindow(hwnd, 277, 25, 1370, 984)` 后用 `GetWindowRect` 断言 `(277,25,1647,1009)`。
    - **阴影**：外框四边各有 8px 圆角阴影（左/右/下实测均值 214~226 的浅色带，看起来就是白边）。录制区域要比外框**再内缩 8px**：`(285, 26, 1354, 974)`。内缩后四边均值降到 40~47（IDE 深色本体），画面铺满。高度须偶数（`Recorder.start` 断言 `w%2==0 and h%2==0`），975 会被拒、974 可以。
    - 自检判据（比目视快）：裁一张 `shot(REGION)` 后算四边各 4px 带的平均亮度，**任何一边 >120 就是有白边**；同时看顶部有没有工程名/分支/运行按钮那一行，没有就是对齐错了。
50. **`Ctrl+G` 跳行输入框留在画面上（`Go to Line:Column` 挂着 `56:35`/`211`/`21:26`）**（2026-10-25，三张片同源）。根因是 `goto()` 里那个 Enter 会被 IME 或时序吞掉；**当时的像素护栏不可靠**——弹窗本体是深灰、与编辑器只差 3~5 个灰度级，用「亮块占比」判恒 False（实测有弹窗也判无），所以从来没过兜底。**修法＝`goto_col` 末尾无条件按 Esc**：跳转已经生效，Esc 只关输入框、不撤销光标位置，比任何「先判断再兜底」都稳。判据若一定要写，用**输入框的蓝色 focus 边框**（`b-r>60 & b>120`，弹窗开着时计数 >150），别用亮度。
51. **预览弹窗要等 ~3s 才画完，等不够会录成半透明错位态**（2026-10-25）。症状很怪：弹窗本体看不见、只有左侧参数列勾选框浮在编辑器上。`trigger_preview(wait=1.2)` / `2.6s` 都不够，统一用 **3.4~3.6s**。
52. **裸屏捕获不含光标 → 纯 `moveTo` 凝视段零像素变化，会被 assemble 当静止裁光**（2026-10-25，某片裁到只剩 3.1s，低于 6s 下限）。要撑时长必须用**真实 GUI 变化**（填参数值让 SQL 实时改、切换页签、滚一段），不能靠鼠标游走。
53. **demo 工程里同名文件极多，`Ctrl+Shift+N` + 方向键必踩**（2026-10-25 一轮踩三次：`UserMapper.xml` 开成 `NativeUserMapper.xml`、`UserMapper.java` 开成 mpjdemo 模块的、`UserRepository.java` 方向键落到 `UserMapper.java`）。**正解＝接 IDE 内置 MCP**：设置 → Tools → MCP Server → Enable（授权第三方读文件/触发动作/执行终端）→ 端点 `http://127.0.0.1:64342/sse`（**端口从 64442 变 64342**，启用后自动带 token，不再 401）→ `open_file_in_editor` 吃 `filePath`（项目内相对路径）+ `projectPath`，一次到位零歧义。封装见插件仓 `record/mcpnav.py`（`open_file()` / `open_paths()`）。注意它**没有行号参数**，跳行仍用 `goto_line`。
54. **IDE 右下角的气球（balloon）关不掉时，打开一次设置对话框就能清**（2026-10-25 实测两次）。`Download Full Line Code Completion model`、`IDE error occurred` 这类常驻气球：× 只在悬停时出现、robot-server 查不到（独立 `BalloonImpl` 窗口不在主窗组件树）、Esc 也无效。但 `Ctrl+Alt+S` 打开设置再关掉，气球会消失。`disabled_plugins.txt` 手写会被 IDE 启动时重写覆盖，别指望它。
55. **`computer-use` 技能对 IntelliJ 无效，别在它上面浪费时间**（2026-10-25 实测）。IntelliJ 是自绘 Swing，Windows UIA 无障碍树只暴露窗口壳——`getAXState()` 对主窗只返回 **7 个元素**（窗口、标题栏、关闭/最大化/最小化、两个系统菜单），编辑器、工具窗、树节点、对话框内部控件全部不可见。所以 `computer-use` 在这里退化成「只能按坐标点」，没有任何语义优势。**IDE 内部控件只能用 robot-server**（跑在 IDE 进程内，能拿真实组件树与相对坐标）。
56. **新建数据源（尤其 PG）的三个必踩坑**（2026-10-25）：① 首次连报 `Incomplete Configuration / Driver files are not downloaded`——必须点 **`Download Driver Files`** 装驱动（PG 是 `postgresql-42.7.13`），不装永远连不上；② 随后弹 `Please provide database credentials`，**User 与 Password 两个框坐标极易点偏**（密码会静默填进 User 框，界面上看着「有内容」其实错了）——**正解是点 User 框 → 填 → 按一次 `Tab` → 填密码**，别靠点密码框；③ 凭据填对后树里才出现 `1 of 3`、`list_database_schemas` 才返回 schema，之前一直显示 `0 of 3` + `No databases selected`。**手工写 `dataSources.xml` 建源不行**（`database-info` 全空、平台不做 introspection），要用 IDE 界面或 MCP 的 `create_database_connection`。
57. **判断「插件拦了执行」还是「执行真的到了库」看报错文本**（2026-10-25）。插件侧的拦截是中文业务文案（`没有与该 SQL 方言（X）匹配的已配置数据源` / `没有可用数据源`）；**数据库真的执行过**则是驱动原生错误（`[42P01] ERROR: relation "user" does not exist`、`[42S02] Table ... doesn't exist`）。两者含义完全不同，别混为一谈——前者是插件没放行，后者是方言/SQL 与目标库不符，而后者才是「执行成功到达」的证据。
58. **打开任何 `.sql` 文件都可能弹「SQL dialect is not configured.」橙色横幅污染画面**（2026-10-25，n05 导出回放脚本收尾要打开 .sql 展示注释头，横幅每次都在）。正解＝把 demo 工程**项目级 SQL Dialect** 设为 PostgreSQL（Settings → 横幅 `Change dialect to...` → Project SQL Dialect → PostgreSQL → OK，写进 `.idea/sqldialects.xml`）。**给单个文件设映射没用**：录制会删掉重建同名文件，新文件不吃旧映射（曾以为配好了，重录又弹）。另外「Cancel」只关当次横幅，下次打开照弹。
59. **原生保存框（FileChooser）位置/形态会漂移**：同一条导出链，先后出现过「保存(S) 左置小窗」和「打开(O) 右置宽窗」两种形态，坐标点击必落空（2026-10-25 n05 连废两条）。**正解＝按 Enter 提交默认按钮**（保存(S) 恒带焦点），且**只按一次**——多按的 Return 会在对话框关闭后落到刚抢到前台的浏览器上（实测把 Chrome 拉到了画面正中）。OK → 保存框之间要等够 ~3s（FileChooser 加载期「正在处理...」时按键被吞）。
60. **浏览器类窗口会在任何对话框关闭的空档抢前台**（2026-10-25 Chrome、magpie 仪表盘都犯过）：抢来之后录制区全是浏览器页面，且不会自己退回去。录制前把 Chrome/Edge 全部最小化；脚本里在「保存框关闭后」「录制开始前」两处显式夺回 IDE 前台——先按放一次 Alt 再 `SetForegroundWindow`（绕前台锁），封装见 `scene_a15_sql_snippets.py` 的 `focus_ide()` 与 `scene_n05_replay.py` 同名函数。
61. **`goto_col`/`goto` 的 Ctrl+G 会往文档里塞杂散空行**（2026-10-25 schema.sql、UserServiceImpl.java 先后中招：文档比磁盘多 1~3 行，固定行号定位全部错位，光标落空行上插件直接报「未在光标处找到可预览的 SQL 源」）。**定位只用 `Ctrl+Home` + `post_wheel` 固定档数**，不要 goto。文件弄脏后的恢复顺序是死的：**先关页签（IDEA 关闭脏文档时会自动保存！）、再 `git checkout` 恢复磁盘、再 `Ctrl+Alt+Y` 同步 VFS、再重开**——顺序反了（先 checkout 后关页签）脏内容会再写回磁盘一次。
62. **给 SQL 文件绑定数据源会让「粘性行」（Sticky Lines）复活**（2026-10-25 a15：右键菜单突然弹不出来，右键点落在粘性行上被吃掉）。开录前 `Ctrl+Shift+A` 搜「Sticky Lines」→ **Disable for SQL**。关掉后编辑区第一行位置还会变（顶部多出「Script ▾」上下文工具行 ~35px），锚点必须重新量。
63. **插件的执行通道文件 `.idea/sql-visualizer/exec/mpsql-exec.sql` 的数据源绑定存在 `.idea/data_source_mapping.xml`（DataSourcePerFileMappings）**——「抽样 10 行 / COUNT / EXPLAIN」这类快捷模板走的是这个 exec 通道，不看当前编辑文件的绑定。运行中手改 XML **不会生效**（组件在内存，IDE 退出还会覆盖回去）；正确切换路径：打开 exec 文件 → 会话选择器 `<data source> ▾` → 菜单里只有「最近使用 + All」，**All 的子菜单必须真实鼠标移动悬停才展开**（`moveTo` 带 duration、别用键盘 Right）→ 选数据源 → Use Default Schema → 选 schema。切完页签标题出现 `[mpj_demo@localhost]` 后缀即成功。
64. **插件的方言闸门对快捷模板同样生效**：插件方言「手动指定」为 PostgreSQL 而数据源是 MySQL 时，抽样/COUNT/EXPLAIN 全部 fail-closed，通知文案「已阻止执行：当前 SQL 由手动方言 PostgreSQL 渲染…」（2026-10-25 a15 连废两条才定位）。设置 UI 里「数据库方言」下拉**不写** `dialect` 键（它改的是别的），真正的键在沙盒 `config/options/mp-sql-visualizer.xml` 的 `dialect` + `dialectAuto`——**改它必须重启沙盒 IDE**（运行中改会被内存态覆盖回去）。
65. **弹出子菜单的像素量测必须两段式**（2026-10-25 a15 连废五条）：子菜单面板底色（~34）本身高于编辑器底色（~23），「与编辑器底比差异」的判据会把**整块面板连成一簇**，簇中心落在两项之间的空白上——点击落空、菜单关闭，还可能误开 Diagrams 之类。正解：先框面板范围（行均值 > 编辑器底 + 3），再以**面板内中位数**为底找文字行（> 面板底 + 6），聚簇 gap≈8 并**过滤 1~2px 的边框孤立簇**，取最后一簇＝第三项。别写死子菜单项坐标：行高随构图在 25~41px 之间变。
66. **2026.2 新 UI 主窗标题不再携带文件名**——「读窗口标题判当前活动编辑器」的旧判据恒 False（2026-10-25 r10 踩过，prep 误判「日志没打开」实际开着）。改用**激活标签探测**：激活标签底部有高亮下划线，但坐标是窗口相对系（base.grab()，y≈68..76），别拿屏幕系行号去扫——扫到 y 92+ 全是上下文工具行/编辑器文字，会把标签文字误当下划线。另：**页签总数超宽时会折叠进 More 弹出菜单**，折叠标签不渲染、像素探测不到——录制前把多余页签关掉，保证目标标签在可见区；标签条扫描窗也要放宽（旧值 x≤805 装不下 6 个页签）。
67. **Find Action（Ctrl+Shift+A）搜索框有跨次残留**：上一轮搜过的词会留在框里，新词 paste 上去拼成「旧词+新词」搜出 Nothing to show（2026-10-25 实测「sql.log Open File」）。打开后先 Ctrl+A+Backspace 清空再输入。且 **Ctrl+Shift+N 在 2026.2 keymap 弹的是「File Open Actions」窄搜索**（不是 Go to File），搜动作名常 Nothing to show——**开最近文件用 Ctrl+E**（Recent Files 支持打字过滤，列表按最近使用排序、首项=当前激活文件）。
68. **沙盒的 ACP 认证态丢失（或沙盒重建）后，AI 面板会卡「Choose authentication method」**（只列 JetBrains/Anthropic Console——adapter 带 `--hide-claude-auth` 裁掉了自管项，UI 无解）。正解＝关 IDE 后写 `<sandbox>/config/options/acpAgents.xml`（完整模板见全局记忆 pitfall-acp-agent-auth-mode.md：`<component name="AcpAgentSettings">` + `agent_auth` map 里 `acp.registry.claude-acp` 等键 = `MANAGED_EXTERNALLY`），重启后面板显示 `Configured authentication: Managed by agent`、模型选择器显示 agent 自己配置的模型（Claude Agent 读 `~/.claude/settings.json` 的 DeepSeek 端点）。**真实调用前先手工跑一轮**：New Chat → 贴 PROMPT → **Enter 发送**（发送按钮坐标随面板布局漂，Enter 稳）；首条消息偶尔出「Cancelled by user」，重发即可。判定真跑通看 `idea.log` 的 `[MCP-AUDIT] tool=<名> status=OK`。
69. **录 ACP agent 通话画面时，Claude Code 自己会往对话流里插一条计费 Warning 横幅**（2026-10-26 r10 实测）：「Warning: We're changing auto mode to no longer charge for classifier requests in Claude Code… because your requests go through api.deepseek.com, which isn't compatible…」。它出现在 agent 回复区、**滚不掉也裁不掉**（与 `mcp: idea/sqlv_check_sql` 调用卡同区）。这是 agent 侧配置产生的内容、不是插件缺陷，验收时按「可接受的第三方提示」对待并在交付说明里点出；要彻底消除只能换非 DeepSeek 转发的端点。另：**AI 面板占满右侧全高时输入框贴底（窗口相对 y≈835~900）**，旧坐标会点到「More room for your prompts」空白区——paste 的文字会落到**编辑器**里污染源文件（实测把提示词打进 MybatisPlusDemoApplication.java 第 20 行），开录前必须截图核对输入框位置。
70. **设置页复选框三行间距仅 28px，且 label 点击不触发**（2026-10-28 批4 两轮点偏）：「增强捕获脱敏」「展示与导出脱敏」上下相邻，按整图目测点 label 把前者当后者点了两次；这批 JBCheckBox **点标签文字不切换**，必须点勾选框本体。处置：robot `component_screen_center` 现取中心后 **x−388**（本体在标签左侧约 388px），或按放大截图逐行量。**robot 组件树不暴露 selected 状态**——开关现状用像素采样判（勾选态蓝底：b>110 且 b−r>40 的像素 >25），prep 里按采样把开关归一化成已知态再进 take。
71. **插件/IDE 的提示与确认框是独立 JDialog，抢前台吞掉一切点击**（2026-10-28 a45 三轮未过的真凶）：「源码跳转配置」「Add Files to Git」弹出后 `GetForegroundWindow` 就是它，对设置页/复选框的所有点击都被吞，且**每次勾选都会重新弹一次**。处置：场景里封装 `close_window(title)`——`EnumWindows` 按标题精确匹配 → `PostMessage(WM_CLOSE)`，在「点开关」「点 OK」之间各插一次。「Add Files to Git」里勾一次 **Don't ask again** 可根治复发（注意勾选框/Cancel 的 y 要按窗口 rect 的 `bottom−24` 算，整图目测会点到窗外）。三个补充：①**压在主窗后面需要交互的对话框**，`SetForegroundWindow` 受前台锁常静默失败——用 `AttachThreadInput(前台线程, 目标线程, true)` + `SetForegroundWindow` + `false` 三步（实测把压在 IDE 后的保存框提到前台后点击才生效）；②**同名窗口可能叠多个**（如「导出回放脚本」的选项框与原生保存框同标题），按宽度区分或逐个枚举处理；③对话框关闭的空档浏览器会抢前台——抢回后先按标题确认再继续。
72. **多次试录会叠出多个隐藏的 JDialog 实例**（2026-10-28 n11 实测 6 个「回滚 SQL」叠在同一 rect）：robot 按文本找按钮（`//div[@text='执行快照查询']`）一次匹配 6 个，点击可能落在隐藏实例上毫无效果。处置：录前按标题把同名窗口全部 `WM_CLOSE` 清空（循环到清不动为止）；场景里点击前用 `find_components` 数量断言唯一，>1 就先清场。
73. **多选对话框初始勾选状态不固定**（a45 模块对话框：上一轮可能全空、也可能全勾）→ 「逐行取消/补勾」必错。正解＝**「全不选 → 只勾目标」**（两个按钮在对话框底部一行），与初始状态无关，也不用逐行核对。
74. **IDE 内启动被录应用：可靠链＝点运行配置下拉箭头 → Recent Configurations 选配置名 → 点绿三角**（2026-10-28 实测）。绿三角的屏幕 x 随配置名长度漂（配置名长则右移），写死必错；`Ctrl+Shift+F10` 实测无效；**点配置名文字只会切换配置不启动**。**外部 mvnd/gradle 起的进程不进 IDE 控制台**——插件「运行期捕获」收不到事件（诊断行显示 控制台：已挂载 0 个），必须 IDE 内跑；先按 `netstat` 找 PID、核对命令行含 demo 路径再杀遗留进程（非提权杀不掉提权子进程，见坑 33）。
75. **执行控制台按 exec 文件复用、不跟随预览绑定数据源**（2026-10-28 录制现场实证，插件待修）：预览绑 `mp_demo@localhost`，快照/执行却跑在 `mpj_demo` 的旧控制台上（`Unknown column 'deleted'`；mp_demo.user 有 deleted 列，mpj_demo.user 没有）。Close All Sessions / 关逻辑删除注入都改不了绑定。**录制侧绕过＝给控制台所在库的目标表临时补缺失列**（`ALTER TABLE ... ADD COLUMN deleted TINYINT DEFAULT 0`，录完 `DROP COLUMN` 还原），快照查询即出结果。
76. **EXPLAIN 跨次对比的三个前提**（2026-10-28 n03）：①设置「预览面板内结构化渲染 EXPLAIN」默认关，且该开关在 SQL 预览页**首屏之下**（配合坑 35 的真实滚轮）；②「跨会话保留原始 EXPLAIN 计划」默认关，不开则基线列表为空、对比条不渲染；③**造真实差异的通用手法＝先跑一次存基线 → 改环境（建/删索引、改数据量）→ 再跑**，否则对比条只有「计划无变化」。对比条右对齐，**默认 940 宽弹窗下整块被裁到屏幕外**（robot 算出 x≈2569），且该弹窗不可拖拽改尺寸 → `SetWindowPos(hwnd, 300,40,1330,940)` 拉宽拉进录制区。
77. **成片体积超预算的收缩顺序**：先 `--fps 13` + `--hold 1.2 --tail 2.0`（n12 从 3.6MB→1.8MB）；**滚动段是体积大头**，滚动循环的 sleep 压到 0.01s（快滚既省帧又自然）；仍超再降静态保留，不降宽不降帧率下限。
78. **白屏/浏览器污染的自检：亮帧扫描**（2026-10-28 抓到三张 Chrome 抢前台的废片）：逐帧算灰度均值，**连续多帧 >110** ＝ 录制区整段是浏览器/白屏（对话框关闭空档被抢前台）。比逐帧目视快且不漏；配合「按窗口标题抢前台 + 校验前台句柄」的处置（坑 71）。

79. **沙盒 IDE 构建静默死亡：真源是沙盒外部工程配置的 `LANGUAGE_LEVEL`，不是 demo `.idea`**（2026-10-31 实测，跨会话复现两次）。症状三连：`log/build-log/build.log` 停在 `Pre-loaded process ready`（无编译行、无错误行）；运行配置报 `Process not started by some reasons. Probably build process failed`；MCP `build_project` 180s/300s 都 `timedOut:true problems:[]`。真因＝`<sandbox>/system/projects/<proj>.<hash>/external_build_system/modules/<module>.xml` 里 `LANGUAGE_LEVEL="JDK_17"`（由该模块 pom 的 `maven.compiler.release=17` 导入）与项目 SDK 8 冲突，编译中止。**改 demo `.idea/compiler.xml` 对沙盒完全无效**（沙盒读 `system/projects/...` 那份，两套配置）。改成 `JDK_1_8` 后通过。判据链三步：Build Output 有编译行 → `execute_run_configuration` 返回 `fullOutputPath` → `curl :8081/user/list` 200。
80. **Database 控制台（DbConsole）执行的 SQL 不进捕捉面**（2026-10-31 实测）：诊断行恒「控制台：已挂载 0 个」，而在 Run 控制台里跑的应用能被挂上。要 DDL / DML 素材**必须让应用自己执行**（定时任务 / 接口 / 启动期脚本），别指望在 Database 控制台敲。应用已在跑时开捕捉能挂上，但历史条目不回扫——要重新触发一次动作。
81. **平台 runner 枚举按 exec 文件方言过滤**（2026-10-31 实测）：有匹配方言的数据源时只给它（`[M36] runners=1` → 静默单跑，数据源选择器**永不弹**）；没有匹配源时回退「全部源」（`runners=6` → 弹选择器）。做「方言不一致」演示必须让**渲染方言没有对应数据源**——临时把该方言的源移出 `.idea/dataSources.xml`（录完还原）。
82. **`FROM "user"` 要跑通的两个硬条件**（2026-10-31）：①目标库有全字段（`mp_demo` 有 13 列；`mpj_demo` 只有 `id/name/age/sex/head_img` 五列，跑不了实体链）；②数据源 URL 带 `sessionVariables=sql_mode=ANSI_QUOTES`。MCP `create_database_connection` **拒**带该参数的 URL → 先纯 URL 建源、再改 `dataSources.xml` 补参数。
83. **放行态信息行会被真实报错覆盖**（2026-10-31）：`showStatus()` 走下一拍 EDT，真实执行错误随后覆盖同一 infoLabel。想让「方言不一致」这类提示**停在画面里**，执行必须成功——选错目标库导致报错时，提示行只在画面闪一帧。
84. **模态对话框开着时，`Ctrl+A`/键入会打到对话框；对话框一关，同样的键就打到编辑器**（2026-10-31 两次把 demo 源文件清空/插入垃圾）。任何「全选+替换」前先断言目标窗口存在性与焦点在预期控件（`EnumWindows` 按标题查对话框在不在）；误伤恢复顺序＝**先关页签 → `git checkout` → 重开**，且 **IDE 退出时会把脏文档写回盘**，所以 IDE 退出后要再 `git checkout` 一次并核 `git status`。
85. **全屏 topmost 应用在前台时，点 IDE 区域会打到它**（2026-10-31 magpie 全屏，两次）：合成输入前必须断言 `GetForegroundWindow() == IDE hwnd`；不一致先 Alt 按放 + `SetForegroundWindow`，再断言一次。发现被抢前台就停手，别连续补点。
86. **工具窗宽度决定 chip 是否可点**（2026-10-31）：窄窗下 `[SchedulerDemoTask.java:40]` 被截断，点上去只选中条目；把工具窗拖宽后 chip 完整可见、坐标全变。**录制前先定宽再标坐标**，中途改宽等于整批坐标作废。
87. **调用栈弹层是「预览面板 meta 行悬停」触发，不是列表 chip 点击**（2026-10-31）：`StackFrameHover` 400ms 防抖，鼠标停在「调用栈:」上才弹；日志列表 chip 点了只选中条目。弹层标题带线程名（`调用栈 · scheduling-1`），帧行点击跳源码。
88. **预设 combo 三坑**（2026-10-31）：①选**同值项不触发回填**（combo 已是 `tom-query` 时再选它是 no-op）——切换必须经过另一项；②首击可能只给焦点不开弹层（「点了没弹就再点」）；③JBPopup 弹层里鼠标点项在边缘反复落空 → 走键盘 `Up/Down + Enter`，并用像素断言（输入框有字）兜底重试。
89. **poster 选帧与接缝口径**（2026-10-31）：`assemble.py` 默认 poster 取 kept frame #N，**不一定是关键定格**（要按内容选帧）；光标 overlay 会烧进画面，收尾落点要避开关键文字（R1 首版光标压在「方言不一致」信息行上，重录）。接缝 diff：结构性变化（首帧纯预览 / 末帧带提示）允许 >1 并登记（实测 6.06）；同态场景应 <1（0.03 / 0.18 / 0.33 三例）。抽帧核对用 `ffmpeg -vf "select=eq(n,N)"` + PIL crop/zoom，比整图目视快且能定位到行。

## 定位优先序（2026-10-26 调研定案，先读这节再写脚本）

调研报告：`docs/调研/20261026-GUI自动化工具选型-调研报告.md`（含三份子报告 1361 行）。
定量事实：坑表 52 条里 **50% 是「定位/坐标」**；82 个 scene 脚本中**纯 robot 定位的 30 个零硬编码坐标**，
纯坐标的 43 个全靠 `(x, y)`；返工轮次与「是否用 robot」强负相关（不用 robot 的 a15/r10 正是最难的两张）。

**写脚本按这个顺序取点，越靠前越稳：**

1. **`robot.py` 组件树（首选）**：`find_components("//div[@accessiblename='…']")`。
   **关键：近一半组件没有 accessiblename，只有 className**——实测 225 个可定位组件里 111 个（49%）
   只能靠 class。查不到时立刻换 `//div[contains(@class,'XxxPanel')]` 再试，别急着退回坐标。
   例：AI 面板输入框 = `//div[contains(@class,'AIAssistantInputEditorTextField')]`。
2. **弹出菜单用菜单原语**：`menu_items()` 列全部项、`click_menu_item(-1)` 点末项、`has_menu()` 判有无。
   实测右键菜单 `ActionMenuItem` 20 项全部可从组件树拿到精确几何——**不要再写「像素扫描找末项 +
   两段式量测子菜单」那套**（a15 曾为此返工五轮、约 100 行）。菜单项无 name/text 字段，只能按序号取。
3. **`anchor.py` 模板（兜底）**：只用于 robot 真查不到的纯图形元素。模板必须按 **120% ideScale** 标定
   （实测：屏幕缩放偏离 1.0 时，不缩放模板得分掉到 0.68~0.78，低于 0.85 阈值 → 表现为「明明在屏幕上却找不到」）。
4. **硬编码坐标（最后手段）**：写完必须配一条 `verify.Guard.require()` 断言，否则布局一变就是白录。

**正在推进的能力（报告 §5 第二期）**：robot-server 有未启用的 `/js/execute` 通道（反编译实证 + 20 项沙盒实测），
可进程内触发 Action、读弹窗内部状态、30fps 截图、合成鼠标事件。接通后可用「等真实状态」替代 `sleep(3.6)` 这种猜时间。

**已证伪，不要浪费时间**：任何 UIA 路线（pywinauto/FlaUI/WinAppDriver/White），UIA 对 IDE 只见 `SunAwtFrame`、
children=0；computer-use（三视图各 2 节点）；视觉模型逐帧定位（小图标 0/4）；Airtest/SikuliX（与 anchor.py 同构）。

## 环境还原

```
□ 被录应用停掉（面板 ■ 或按端口杀进程，勿按进程名）
□ IDE 关窗（runIde 随之退出）；用了临时开关的确认仓库无残留改动
□ 编辑器/工程若被 take 污染，立即还原（Ctrl+Z 无效时走 Local History > Revert，见坑表 8；磁盘真脏才动 git）
□ 并行任务登记 done --name 注销
□ IME 按需切回
```

## 权威源约定

**本 skill 就在插件仓内**：`.agents/skills/ide-demo-gif/`（2026-10-31 从 `~/.agents/skills/` 下沉，全局那份已删——同名时用户级优先，留着会让工作区副本永不生效）。录制一律**从插件仓根开始**。

- `$SKILL/scripts/`（`$SKILL=.agents/skills/ide-demo-gif`）是通用录制／转制基建的**权威源**；
- `record/` 是场景脚本与项目侧件（`anchor.py`/`robot.py`/`mcpnav.py`/`BASELINE.md`/`preflight.py`/`prep_env.py`/`lib/nav.py`/`_run_elev.bat`）的工作位置；
- 同名基建（gifkit/shot/assemble/locate/verify/scene_template/black_underlay/clipread）在 `record/` 有工作副本：**改 skill 那份 → cp 回 `record/`**，不要双向各改；
- 正式脚本 `git add` 明确路径提交，过程探针放被忽略的 `record/tmp/`；
- **验证本 skill 是否被当前 cwd 发现**（不用等新会话）：
  `node --experimental-sqlite "<ZCode>/resources/glm/zcode.cjs" --cwd <插件仓根> skills list`，
  列表里应出现 `- ide-demo-gif (project/agents)` 并带 `.agents/skills/ide-demo-gif/SKILL.md` 绝对路径；
  换成别的仓做 cwd 时它应当消失（这就是「skill 集合随 cwd 变」的反证）。注意 `zcode -p "<prompt>"`
  在 ZCode 应用运行期间不可用（它要初始化 `~/.zcode/cli/db/db.sqlite`，被运行中的应用占着，
  报 `SQLite migration initialization failed`）——发现用 `skills list` 验，模型侧触发才需真会话。
- 录制操作手册：插件仓 `docs/录制/演示GIF录制指南-说明文档.md`；点击定位调研：`docs/调研报告/20261013-2130-演示录制点击定位提效工具调研-调研报告.md`；本轮经验：`docs/经验总结/20261031-*-第十五轮演示动图补录与录制链根因-经验总结.md`。
