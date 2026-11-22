---
name: pitfall-windows-cpu-attribution-diagnosis
description: 用户报「任务管理器总 CPU 很高但按 CPU 降序单进程最高才几个点」时的排查法与判据——三方对账（Processor
  Information 才是任务管理器表头源）、四条被实测排除的猜测、真凶是 IDEA 构建链多个同名 JVM 被进程页归并/平滑，以及
  Get-Counter 串循环把百分比放大 4 倍的测量陷阱
metadata:
  node_type: memory
  type: project
  originSessionId: session_4396b1e9289976f4785418600b2fa7f4
---

20261028 用户贴任务管理器截图：表头 CPU 88%，但按 CPU 降序排下来最高一项只有 4.0%，前十七行加起来约 12%，剩下的七八成「不知道归谁」。这是本机（6C12T i5-10400F / 32GB）的一次完整归因，结论是**总量是真的，行是钝的，真凶是 IDEA 正在跑构建链**。

**三方对账（必须同时读，缺一就会判错）**
1. `\Processor Information(_Total)\% Processor Time` —— Win10/11 任务管理器表头的真正来源。本次 87.98%，与用户截图的 88% 逐位对上。
2. `\Processor(_Total)\% Processor Time` —— 传统计时口径，同刻 73.87%。**两个口径本身可以差十几个点**，别拿 ② 去对表头说「任务管理器在骗人」。
3. 各进程 `Process.TotalProcessorTime` 时间差累加 ÷ 间隔 ÷ 逻辑核数 —— 判「有没有归不到进程的占用」的唯一硬手段。本次 5s 窗口累加 = 88.2%，与 ① 一致 ⇒ **不存在隐藏消费者**，不是内核/驱动/虚拟机在偷跑。

**四条被实测排除的猜测（各自判据）**
- 驱动或硬件中断风暴：`% DPC Time` ≤1.9%、`% Interrupt Time` ≤2.3%、`System` 进程仅 0.6~3.5% ⇒ 排除。
- 降频导致百分比虚高：`Processor Frequency` 恒 2904MHz、`% of Maximum Frequency` 100、`% Processor Utility` 130（在睿频）⇒ 排除。
- 虚拟机藏活：无 vmmem/wsl/docker 进程，只有 session 0/1，无第二用户会话。
- 权限盲区（未提权任务管理器读不到提权进程）：**已实测否定**——`Get-Process` 实例数 839 与 perflib 838 对得上，且该 Taskmgr 自身 TokenIntegrityLevel 是 High；没有读不到的进程。
- 正面证据：`\System\Processor Queue Length` 冲到 **63** 才是「真饱和」的硬读数（线程排队等核）。

**「单进程都不高」的成因**
真凶是 IDEA 构建链的多个 JVM 同时跑：Kotlin 编译守护进程（`kotlin-daemon.jar`，本次**两个实例**）、JPS javac worker（`-Djps.java.compiling.tool=Javac`）、Maven 导入器（`-Didea.maven.embedder.version`）、插件验证器（`-Dplugin.verifier.home.dir=…mpv-verifier-home`）、Gradle 守护进程，再加 idea64 本体（涨到 5.6GB）。它们**全部叫 java.exe**，进程页里被归并/平滑成一行「OpenJDK Platform binary」，用户看到的那行只有 0.2% / **0.6MB**——内存列 0.6MB 本身就是线索：有活儿的 JVM 不可能只有 0.6MB 私有工作集，那只是一个已完全换出的小 worker。
正确看法：**「详细信息」页按 PID 一行**，或资源监视器。进程页的表头总量准、单行钝，不要用行去反驳表头。
（「归并 + 平滑」这条呈现机制是推断，本次没取到任务管理器内部逻辑的实证；已被证伪的只是「权限读不到」那一条。）

**测量陷阱（这次全踩了）**
- `Get-Counter` 每次调用自己要等一个采样窗口（约 1s）。把 4 次 `Get-Counter` + 1.5s `Sleep` 串进循环，实际周期变成 ~6.6s，仍拿 1.5 当除数 ⇒ 单进程百分比被放大 4 倍多，算出 369% 和负 gap 这种荒谬读数。要么把多个计数器放进同一个 `-Counter @(...)` 只调一次，要么用墙钟实测周期当除数。
- `Win32_Processor.LoadPercentage` 不可信：同刻 65 vs PDH 16.6，差 4 倍。
- PowerShell 内联命令经 Bash 传递时 `$_`/`$a` 会先被 bash 展开（报 `Missing type name after '['` / `An expression was expected after '('`），一律写 `.ps1` 再 `-File` 执行。
- `runas /trustlevel:0x20000` 在非交互环境起不来（引号嵌套 + UAC），别拿它做「降权对照」实验。
- 取进程 TokenIntegrityLevel：`GetTokenInformation(cls=25)` 先传 len=0 拿所需长度，SID 最后一个 subauthority 在偏移 `8+(cnt-1)*4`；只给 4 字节缓冲会得 `ERROR_INSUFFICIENT_BUFFER(122)`，极易误读成「权限被拒」（本次第一版所有进程都被误报成 Low）。

**顺带测到的机器状态**
内存 88%（free 3.9GB）、页错误 67,402/s、pages/sec 3,098 ⇒ 构建和 IDE 在换页上互拖；841 进程 / 16,155 线程 / 403,634 句柄。最刺眼的是 **Kotlin 编译守护进程 PID 41364 累计 CPU 480,884 秒 ≈ 134 CPU 小时**（10/24 起 4 天没回收，工作集被压到 2.3MB），同批 javac worker 11704/13120 也只剩 2.2MB 工作集还挂着。

**Why**：用户问的是「为什么我看到的这么高」，答案分两层——总量真高（三方一致，不许和稀泥说「测量不准」），而单行看不出来是呈现层的问题；同时必须先把驱动/降频/虚拟机/权限四个常见替罪羊逐条证伪，否则很容易给出「换个工具看就好了」这种没用的答复。

**How to apply**：下次再遇「总占用高、单进程低」，按上表四步走：三方对账 → 排四坑 → 看 Processor Queue Length 定饱和 → 用「详细信息」页/按 PID 找真凶。给用户结论时明确标注哪条是实测、哪条是推断。资源侧处置见 [[parallel-build-resource-rules]] R23。

**20261029 内存侧同族复发：headless CDP 浏览器孤儿（占 20.5GB 提交量，不是 CPU）**

用户报「内存和 CPU 长时间较高」。实测 `sysCommit 68.11/74.67GB（91%）、free 6.36GB`，但按进程名聚合后最大单项是 `chrome 587 进程 / 20.52GB 提交`。拆开看：**65 个浏览器主进程里 64 个是 `--headless=new --remote-debugging-port=93xx/96xx --user-data-dir=…Temp/cdp-dd*` 或 `…jvtools-home/.tmp-hot/accept/profile*` 的自动化残留**，最早 10-27 22:43、最晚 10-28 16:53，全部父进程已死；7 个进程「提交量 0.5~1.4GB 而工作集只有 9~15MB」＝页面已被换出到 pagefile，是纯占位的僵尸。同族还有一条 Edge `--headless=new --user-data-dir=Temp/puppeteer-bounty-*`。

判据与处置（可复用）：
1. **按树根聚合**比按进程名有用——`chrome` 587 进程听起来像用户开了一堆标签，按 `--type=` 分组后 `browser-main` 竟有 65 个，正常只该有 1 个。
2. **「提交量高 + 工作集接近 0」= 已换出的僵尸**，这条比 CPU 更早暴露问题；反过来「工作集高」才是真的在用。
3. 杀前双证：`Get-NetTCPConnection` 查这些 CDP 端口**只有 Listen 没有 Established**；主进程 `ParentProcessId` 已不存在。两者同时成立才是无主孤儿。
4. **例外必须逐个看窗口标题**：本轮 `chrome.exe --version --do-not-de-elevate`（PID 18768）命令行看着像残留，`MainWindowTitle` 却是用户真实页面「字数统计…jvtools」，杀了就关掉用户浏览器；同样 Edge 35528 是 95 个页面的真实窗口。**只能杀 `--headless` 且父进程已死的树**，用 `taskkill /F /T /PID <root>`。
5. 处置读数：进程 1029→428、提交量 68.11→41.03GB（释放 25.6GB，其中 Chrome 20.5GB）、free 6.36→12.23GB、pagefile 用量 5.3→2.6GB（Windows 自动把 43.8GB 缩到 34.9GB）、空闲 CPU 3%。**提交量降下来后并行构建才不会再撞内存墙**（[[parallel-build-resource-rules]] R16/R24）。
6. 残留 profile 目录同时吃磁盘：`Temp/cdp-dd*` 约 272MB，`jvtools-home/.tmp-hot/accept` 达 **10.3GB**（64 个 profile + 截图），清理前先确认验收证据是否还要。
7. 预防：CDP 自动化脚本收尾必须 `Browser.close` 或 `taskkill /F /T`；起构建门禁前先跑一遍孤儿扫描（`chrome/msedge` 主进程里 `--headless` 且父进程已死的树）。

同轮还量到两个次要点位：ZCode 自身 7 个 `app-server` 会话（每个 250~330MB，合计约 2GB）+ 13 个 plugin-host；Gradle daemon 常驻约 2.8~4GB。前者关掉不用的窗口即可回收，后者属正常开销。
