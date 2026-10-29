---
name: project-releaseperf8-verifier-home-shared
description: T-ReleasePerf8 第三期——verifier home 外迁机器级共享已实施，发行 detached 树 3.33GB
  冷下载税整类消除；直连提速证伪
metadata:
  node_type: memory
  type: project
  originSessionId: session_1df534c4-7a7e-491d-843e-9c067806e0e8
---

T-ReleasePerf8（20261024，**已合 dev `29824fb7`（ff）并 push origin/dev；工作树 rp8、分支 t-releaseperf8 本地+远端均已删**；8 笔提交内容全在 dev；占号 O-150/D170~D171/V193~V196）：

- **核心认知修正**：发行日 releaseGate 31~55min 的真因=verifier home 在 `build/` 下 → 换工作树（发行 detached 验证树）=全量冷下载 3.33GB × 代理窗速（快窗 14MB/s=3m50s、慢窗 2MB/s=30m）。warm home 下载 0 B；跨树跨 10 天 131 个依赖插件 build 号 130 个一致（97.5% 复用）。
- **实施**：verifyGate/verifyMarketplace home 缺省迁 `GRADLE_USER_HOME/mpv-verifier-home/{gate,marketplace}`（gate/market 分家；verifyPlugin 报告生成器维持 build/ 原位）；`-PverifierHomeRoot=build` 一词回退。并发防线=doFirst 锁探测（`VerifierHomeGuard.kt`，探到外部 PluginVerifierMain 指向同一 home 即硬失败；探测自身故障降级 WARN——主防线=全量门禁互斥定例）。共享 home 可从任一暖树把 `build/verify-home-marketplace` 拷入播种。
- **权威轮**：releaseGate BUILD SUCCESSFUL 11m15s 全绿（testShards 8116/0/0/10、六 verdict Compatible 计数逐字一致、e2e 26/0、双 verifier 下载 0 B）；暖窗地板 675s（较 T7 613s 的 +62s=测试基数 6522→8116 自然增长）。
- **N2 直连提速销案**：curl 双轮交错，代理 13.3 MB/s > 直连 7.7 MB/s——本机网络形态下代理反而快，慢窗对策是别在冷 home 重付下载。
- **新 flake/机制缺口（同日已修 `48049395`，随 master↔dev 双向同步入双分支）**：`AgentManagerPremainReattachTest.testP4_attach装载关再开_原端口rebind接住回连重建会话` 曾因 `resumePremainChannel` 的**无界 join** 挂 75min——pause→resume 竞态下 resume 先翻回 `paused=false`，旧 accept 循环失去唯一退出条件（30s tick 里 `isChannelExpired` 恒 false）无限 continue；修法=accept 循环**代际让位**（`PremainChannel.acceptGeneration`，三启动点取新代+三处失主检查+finally 按代际分流）+ resume **有界预算兜底**（`PREMAIN_RESUME_JOIN_BUDGET_MS`=40s internal var 测试缝，超时关监听判不可恢复回落 attach）；回归 `testP5_*` 两条 18/0/0/0，反向摘代际/摘预算各恰 1 红。**坑**：JUnit3 反射把 Kotlin 非捕获 lambda 合成方法（`testXxx$lambda$N`）当测试方法——测试内线程体必须捕获外部变量+显式语句。
- **实施三坑**：①doFirst lambda 引用脚本顶层函数 → CC「cannot serialize」（第三次实证）→ 逻辑挪 buildSrc 独立 .kt；②PS 5.1 多行 script block 行尾 `-and` 续行**静默失灵**（Where-Object 恒空无报错）→ 单行拼接；③`$found = ... | ForEach-Object` 捕获即吞输出、`if` 无 else → 守卫探到冲突反而静默放行。另：`dev-support/` 下 PS 工具必须全 ASCII（PS5.1 把 UTF-8 无 BOM 中文按 ANSI 误读炸解析）；PS stdout 重定向输出 `#< CLIXML` 噪声须清洗。新树跑 releaseGate 需 `-Pe2eDemoDir=<join demo>` 或先落 `.local/local.env`（MPSV_DEMO_ROOT，.local 不入 git）。

相关：[[project-release-workflow]]、dev-release-gate-timing-floor（T6/T7 前两期结论）。
