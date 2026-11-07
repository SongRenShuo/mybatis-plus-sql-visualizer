---
name: project-sql-insight-campaign
description: T-SqlInsight 专题终态 + 20261024 回流前置合并（五轮共 75 笔全并入，差量 0/186）+ 功能验收文档已提交——31 工作包全实现、资源预算未达如实留档；含「master 合并期间持续前进」与 master 侧潜伏红灯两条教训
metadata:
  node_type: memory
  type: project
  originSessionId: sess_ff02b66b-55c2-4e81-926e-95b5b1a6d62c
---

分支 `t-sql-insight`（工作树 `F:/code/Java/Gitee/SongRenShuo/mpsv-sql-insight`，自 dev@c3fc0f52；gate 专用树 `mpsv-sql-insight-gate` 跑 detached 冻结）。文档：调研报告 `20261016-1716-*`、开发计划同名 T-SqlInsight、验收台账 `20261016-2344-*`、自动化验收 `20261019-1140-*`、人工手册 `20261022-1500-*`、**本轮新增功能验收 `20261024-1500-*`**。

## 20261024 回流前置合并（用户令：先并 master 再谈回流）

**遗漏已确认**：分支 merge-base 长期停在 `cf0c7deb`，此后只同步过 dev（末次 `a7340fd1`），master 侧 67 笔整体缺席。用户问「是不是被遗漏了」→ 实测确认 → 令「先将主分支合并到 t-sql-insight，为后面回流做准备」。

**五轮合并（共 75 笔，终态差量 0/186）**：

| 轮 | 提交 | 内容 |
|---|---|---|
| 1 | `0f5a4dc5` | master 67 笔（截至 `51922b10`）。冲突 3 处手工裁决 |
| 2 | `f6a769c3` | 合并期间新落的 2 笔（T-ExplainFailUx W4），零冲突 |
| 3 | `70a71469` | T-ExplainSwitchNative W5~W7（撤自研下拉/原生选择器/取消自动重试） |
| 4 | `e19a5cd5` | W8~W10（方言闸红条换源不执行/渲染方言回落） |
| 5 | `a4363cc5` | W8-hotfix（撤回切换专用枚举，见 [[project-explain-fail-ux]]） |

冲突只在第 1 轮，3 处：①`AgentManagerPremainReattachTest.kt` 双侧同位置各加测试（本分支 attach 端口安全闸用例 vs master 的 P5 两条挂死回归）→ 全保留；②`docs/变更记录.md` 双侧各追加条目 → 时间倒序 union；③`docs/索引/开发计划.md` master 新增 26 行全保留，但 **master 侧 T-SqlInsight 那行仍写「功能未开工」（过时）** → 取本分支已实施版覆盖。第 2~5 轮零冲突。

**自动合并的 Kotlin 源文件必须逐项验「两侧保护都在」**（编译绿不代表语义对）：
- `AgentManager.kt`：本分支 attach HELPER 安全闸（3 处日志点）与 master premain 代际让位 `acceptGeneration` + `PREMAIN_RESUME_JOIN_BUDGET_MS` 共存。
- `SqlPreviewPanelExecution.kt`：本分支 `consolePrepareGeneration` 代际守卫与 master 的 `prepareDefaultConsole` 改动静默共存（该函数三侧同名同体，是同一函数非误并——查过 merge-base/master/insight 三份 `git show` 才确认）。

**约束核对**：计划 §2.2 要求「不直接从 master 吸收未进入 dev 的提交」。实测 `master...dev` 为 18/0（dev 完全含 master），本轮并入内容都已先经 dev 同步链，未绕过。

**读数（`mpsv-sql-insight-gate` detached 树，Docker 显式禁用 `-PmpvDockerTests=false`）**：
- `f6a769c3`：testShards **8487/0/0/10**（812 类，28m38s）+ agent **230/0/0/0** + buildPlugin 成功 + verifyGate 三目标 243/253/262 全 Compatible（override-only 与 internal 均 0；提示计数 243=5/43/98、253=6/54/96、262=8/130/96）。产物 ZIP 7,912,727 字节 sha256 `4d2b6572…`；主 JAR 含洞察类 148 个（sql/insight 57 + ui/insight 48 + log/insight 43）。
- 内嵌 agent 148 条目含 `WeaveSpecs`；**其项目类集合与 `sub-projects` 构建产物完全一致（45 个），字节差异仅来自打包内联的 102 个 ASM 类** ⇒ agent 读数可覆盖发行包（此比对法是「内嵌 jar 与已测 jar 是否同一份代码」的可复用判据）。
- `70a71469`：**8670/1/0/15**（810 类，20m43s）。那 1 红是 master 侧潜伏红灯，见 [[pitfall-guard-expectation-drift-latent-red]]；已单独提交 `33978428` 修复，**按用户指示门禁不再复跑**，故完整全量缺一次，文档如实写「1 红已定位并修复、未复跑」而非全量绿。
- 第 4、5 轮后只做编译 + 定向（`PrepareSingleFlightGateTest` 26/0/0/0、`ExplainAskPasswordChannelGuardTest` 16/0/0/0）。

**教训 1：master 在合并期间持续前进，逐笔追赶没有终点。** 本轮五轮合并是被动追着 master 走的（67→2→4→2 笔），第 4、5 轮每次都是「刚写完文档 master 又推了 2 笔，且那 2 笔恰好推翻我刚写进文档的形态」。做法：每轮合并前先 `git log --oneline master ^<branch>` 看有没有新笔；**回流前必须再跑一次 `git merge-base --is-ancestor master t-sql-insight`**，别拿合并那一刻的快照去回流。

**教训 2：合并期间落进来的功能迭代会反复推翻验收文档的预期。** T-ExplainSwitchNative 从 W5 推进到 W8-hotfix，四次改写同一节内容（自研下拉→纯文本+链接→必弹选择器→取消自动重试→W8 想列全部源→hotfix 撤回）。写验收文档时对这类「正在演进的交互」要么等收敛再写，要么在正文显式标「旧预期已作废」。

## 功能验收文档（`docs/验收文档/20261024-1500-T-SqlInsight-合并master后验收-验收文档.md`，提交 `1b69079e` + `e87d5136` + `01e505c1` + `451564cb`）

用户明确要「功能验收文档，要有 UI 验收路径」，不是合并记录（见 [[feedback-acceptance-doc-means-functional]]）。结构：结论摘要（11 行功能 × 实现状态 × 自动化证据 × GUI 验收，GUI 全标「未执行」）→ 准备（装包/版本核对/demo 启动/入口顺序）→ 十分钟冒烟 12 步 → **逐项功能验收 4.1~4.11**（九项洞察 + 数据源指示行与换源入口 + 选错源就地引导，每节一张「功能点/路径与操作/预期」表）→ 10 条人工定性项 → 正常现象排除 → 不覆盖范围 → 记录模板 → 自动化证据附录 → 合并影响 → 状态去向。

九项洞察入口（预览「更多」菜单，插在「回滚 SQL」之后）：分析SQL / 参数场景 / 渲染解释 / 运行SQL分析 / 导入离线Trace / 对象使用与影响 / 比较本地Schema快照 / 优化候选 / 比较SELECT结果。

**制品时效坑**：那个可用 ZIP 构建于 `f6a769c3`，不含后三轮改动 ⇒ 走查 4.10/4.11 前必须重新 `buildPlugin`。文档里已显式标注。

## 交付终态（20261019 口径）

**交付去向（用户 2026-10-19 明令）**：全部工作结束后**不合并 dev**，成果保留在该分支与工作树；是否进入 dev/master/发布由用户另行决定。计划 §2.2、§8.1、台账、变更记录、MEMORY、索引、调研报告均已改写为该口径。**2026-10-24 用户已转向「为回流做准备」，该禁令的适用范围需用户明确后再动。**

**范围与终态**：31 个工作包（W00–W03、W10–W17、W20–W24、W30–W35、W40–W47）、17 项 SI 需求全部有生产入口与消费者；独立只读审计（需求→产物对照）未发现新增阻断。

**功能门禁（第六轮，冻结树 = 重写后 `b79983a0`）**：五片+external+agent 共 **8325/0/0/10**，新增 65 类 336 方法全部实际入场；打包 content audit PASS；同一 ZIP 在 243/253/262 离线 verifyGate 均 Compatible（含 warning 计数，非零警告）。优化后 agent 在 JDK 8/17/21 真实运行零丢失功能通过。

**资源预算（未达，如实留档）**：默认 500 请求窗口、warm10000、9 个独立 JVM（none/sql/tracing 各 3）在排他窗口跑完，采集完整零丢失；tracing 相对仅 SQL 捕获 P95 **+27.17%**、CPU **+14.28pp**、RSS +1.44MiB ⇒ 延迟与 CPU 均超初始预算（≤5%、≤5pp），内存达标。此前两组独立对照（+8.85%/+9.54pp、−5.97%/+7.31pp）CPU 亦持续偏高。按计划采取范围收口＝**请求追踪/事务追踪默认关闭、用户显式开启才承担成本**，不删质量计数、不丢事件换低耗时。归档 `.local/sql-insight-final-budget-500-fail.tar.gz`。

**必须保留的未验证边界**：GUI 验收未执行（本轮仍未做）；Docker 5 例按既定条件跳过；请求观测仅 Servlet 线程内；事务仅代理连接生命周期（非池租借/XA）；结果比较为两次顺序读非原子快照；精确源码 range 未知时定位整语句；性能环境为时点采样而非连续空机认证。T-ExplainSwitchNative W5~W10 的社区版布局、两通道重试叠加亦未验。

**踩坑索引**：[[pitfall-filter-branch-rewrite-detection]]（历史重写）、[[mp-core-no-selectas]]（反向别名生成旧契约红）、[[pitfall-fake-green-test-assertions]]（反向探针）、[[pitfall-guard-expectation-drift-latent-red]]（本轮 master 潜伏红灯）、[[pitfall-parallel-session-batch-staging]]（并行会话抢占计时窗口）、[[project-agent-enhanced-capture-consent]]（agent 通道）、[[pitfall-merge-doc-union-resolution]]（本轮合并裁决法）、[[pitfall-pause-resume-socket-channel-races]]（premain 通道，本轮两侧改动交叠面）、[[feedback-acceptance-doc-means-functional]]。

## 🆕20261029 第七轮 GUI 续走（合并 6ae97fe9 之后，文档 ab3dc704+fd3d1e51）

独立沙盒 build/MPSV-Insight-Accept（启动脚本 .local/start-insight-ui243.ps1：idea.key 预置 + init 脚本给 runIde 挂 qwdn javaagent——本分支 build 不接 -Pmpvs.licenseAgent；robot-server 已 bootstrap）。走查结果：**4.1 分析SQL / 4.2 渲染解释 / 4.3 运行SQL分析 / 4.4 离线Trace / 4.5 对象使用与影响 / 4.6 Schema比较 / 4.7 优化候选 / 4.8 SELECT比较 全部通过**（含负例：非法 JSON 导入已拒绝、invalid_schema_snapshot 双侧拒绝、动态 mapper DYNAMIC_MAPPER_SKIPPED 不猜测）；**063db9d6f 勾选即时生效修复 GUI 复验通过**（勾选后 4.5s 保持不被弹回）。**零新缺陷**。剩余登记：4.9/4.10 绑定态/4.11 依赖沙盒数据源凭据（IDE 级安全存储，需 Data Sources 对话框 Custom JAR + Change Credentials）；agent 确认链 enable=true 需运行流量。坑：.ps1 中文注释非 BOM 被 ANSI 解码吃行（脚本必须纯 ASCII）；ps1 里 Allowlist 名单要与新登记名同步。

## 🆕数据源凭据已配好（20261029 深夜，`8828cdb0`）

凭据阻塞已解除：Find Action「data sources」误中「Import Data Sources」→ Spring 导入 jpa/kotlin/mpj/orm/selfdev 等 localhost 源 → Data Sources 对话框为 mpj_demo@localhost 填 root/root → Download Driver Files（connector-j 9.7.0）→ **Test Connection Succeeded**，Apply 持久化。4.10/4.11 部分证据到手：指示行「上次使用」态、记忆源不可用弃记忆走「选择数据源」弹层（T-SI-02）、执行确认框（模拟值填充）、**方言冲突不阻断新文案「方言不一致：…」（8619a3aff 修复 GUI 形态首次取证）**。剩余：方言设置改 MySQL 后走「已绑定」态/执行计划内嵌控制台/DML 影响面试算/切换数据源→Database 工具窗验证。⚠️坑：分辨率/缩放中途变化后 raw pyautogui 场景坐标错位——场景必须开头 SetProcessDpiAwareness(2)；Trust 弹框是无标题小窗，用 EnumWindows 空标题+尺寸过滤定位再按窗口 rect 相对偏移点击。

## 🆕20261031 实用度评估（用户问「一直没合并是因为要评定功能实用度，你分析下」）

**分支规模实测**：214 笔 / **202 文件 +15120/−319**；主代码＝`sql/insight/SqlSemanticAnalyzer.kt` 822 行 + `ui/insight` 16 类 + `sql/insight` 7 类 + `log/insight` 8 类；测试新增 53 个测试文件；当轮门禁 `9eeeedbf` 846 类 **8631 例 0 失败**。

**九项功能三档判断（本会话给出的推荐）**：

| 档 | 功能 | 理由 |
|---|---|---|
| **真增量、值得合** | ①分析 SQL（语义结构）②参数场景/渲染解释 ⑤对象使用与影响 | ①是我方从「转 SQL」走向「解释 SQL」的唯一新资产；⑤项目内 SQL 使用面统计现有能力完全没有 |
| **与七环重叠、价值待验证** | ⑥本地 Schema 比较 ⑦优化候选 ⑧SELECT 结果比较 | 我方已有 EXPLAIN 建议 / SQL Diff / Schema 校验的另一种形态，属「另一种做法」而非新能力面 |
| **重且带资源代价** | ③运行 SQL 分析 ④离线 Trace 导入 ⑨写 SQL 影响面 | 依赖 agent 请求追踪，而**追踪因延迟/CPU 超预算已默认关闭**——默认关的功能合进来用户发现不了，却要背 15120 行维护面 |

**推荐方案 A（分档合并）**：只合 ①/②/⑤ 三项 + 其依赖的 `sql/insight` 包，③④⑥⑦⑧⑨ 留在分支。备选 B＝214 笔全合 + 运行洞察类默认关；C＝先补完 GUI 走查再定。

⚠️ **评估口径订正**：做这个评估时我引用的是 `20261024-1500` 验收文档的「GUI 未执行」列（6 项）。但本文件前面已记录 **20261029 第七/八轮把九项 + 关联功能全部走通、零未修缺陷**，`20261024` 表里的「未执行」已过时——**下次再评估实用度须以 20261029/第八轮为准，别再拿旧表当「未验证」的证据**。

## 🆕20261031 用户定案走方案 C：dev 已并入分支做 GUI 走查基线（`bef08646`）

用户放弃「A 分档合并 / B 整体合入」，选 **C＝先补 GUI 走查再定**，并令「先把 dev 合并到 t-sql-insight，准备好走查内容，等录制任务结束后派子智能体去该工作树 GUI 验收」。

**合并读数**：`merge-base` 仍是 `5aec84d8`（很旧）；合并前 `dev..insight = 214`、`insight..dev = 129`；合并后 HEAD `bef08646`（+ 走查准备单 `2e7b934e`）。`git merge-tree` 预演只有 **4 处冲突**：

| 文件 | 性质与解法 |
|---|---|
| `ui/SqlPreviewPanelExecution.kt` | **唯一硬骨头**：dev 已按 D202 把它拆到 `SqlPreviewPanelExecutionExplain.kt`（2409→1552），本分支在原文件上有 3 处真实增量。**取 dev 结构（`git checkout --theirs`）后手工回贴增量**：①`prepareDefaultConsole` 的准备链代际守卫（`val generation = ++consolePrepareGeneration` + `acceptsDelivery()`，落地到 onConsole 开头 / invokeLater / onEditor / onDialectGate 四处判据）②`prepareConsoleForTest` 测试缝（`SqlPreviewPrepareCallbackLifecycleTest` 依赖，故必须保留；做法＝把两个回调从调用参数**提取成局部 val**，调用前 `prepareConsoleForTest?.let { dispatch(sql, onConsole, onEditor); return }`）③`detachConsole` 末尾的洞察文档解绑（`insightController?.bind(null)` + `bindAuxiliaryInsightDocument(null)`，包在 `if (!previewDisposed)` 内） |
| `ui/SqlPreviewTopRowLayoutTest.kt` | 「更多」菜单项数 **十八 → 十九项**（本分支 9 项洞察入口 + dev 的「导出优化留痕」居末）；期望串改为「…/DML 影响面/导出优化留痕 十九项且顺序稳定」 |
| `docs/MEMORY.md` | 2 块：架构索引表取并集（本分支「SQL洞察支持范围」+ dev「子系统-预览执行链/展示脱敏链」）；验收文档计数按**实测 57 篇**统一 |
| `docs/变更记录.md` | 台账取并集并按行首日期**倒序重排**（dev 的 10-26 条目排在本分支 10-25 之前） |

**又一次 `git add -A` 事故（本仓第 12 次同族）**：合并收口用 `git add -A`，把该工作树里 **82 个本地录制探针脚本**（`record/d_*.py`、`i_*.py`、`w*.py` 等，一直处于未跟踪态）卷进合并提交。处置＝`git rm --cached <清单>` + `git commit --amend -F`；探针恢复未跟踪、`scene_*.py` 场景脚本不受影响。详见 [[pitfall-parallel-session-batch-staging]]。

**走查准备单已落档**：`docs/验收文档/20261031-2010-T-SqlInsight-GUI走查准备单-验收文档.md`（`2e7b934e`）——不重复功能细节（指向 `20261024-1500` §四），只解决三件事：①七项环境检查（沙盒 `idea.key`／`-Pmpvs.licenseAgent`／corretto-17／`recentProjects.xml` 预置／`dataSources.xml` 从 `dev-support/test-demo/templates/` 复制／MySQL `mp_demo`+`mpj_demo`／构建起沙盒）②走查顺序（先冒烟四项 → 六项未执行 → 合并带入项按 W12 新形态 → dev 新能力顺带确认）③记录模板与三条红线（只看不写库、不顺手改码、环境受限如实登记）。

**串行依赖（用户指定）**：两张 GIF 重录（`code-completion.gif` 内容错位、`log-community.gif` 二维码未载完）**先在主工作树 master 做**（`build/` 各自独立，与 dev 不冲突），**录制结束才派 GUI 走查代理**——两者都要抢 `runIde` + GUI 前台；而录制本身又要等 #18 设置收敛代理的 gradle 跑完（并发上限 2 + GUI 抢焦点）。

**⚠️ 本次评估方法论复现了同一个错**：我给用户的「六项 GUI 从未走查」是抄 `20261024-1500` 的「未执行」列，而本文件已记录 **20261029 第七/八轮九项 + 关联功能全部走通、零未修缺陷**（见下节）。**下次评估实用度必须以下面「第八轮」节为准**。

## 🆕第八轮（20261029 深夜，`73b46596`）——4.9/4.10/4.11 全闭环

方言根因＝注入文件被 IDEA 判 PostgreSQL（.idea\sql-visualizer\prev 路径映射），修法＝编辑器 intention「Change dialect to…」→ SQL Dialects 表把 mybatis-plus-demo\src 与 OrderMapper.xml 改 MySQL（项目级 None 不覆盖注入文件，必须改路径映射）。此后：4.10 已绑定态（执行一次→「已绑定： mpj_demo@localhost」）、4.11 执行后内嵌原生控制台形态 A + 执行失败就地呈现带库/源归因（[42S22] Unknown column…当前库=mpj_demo）、4.9 DML 试算链全通（SELECT 明确拒绝「仅 UPDATE/DELETE」；UPDATE 试算真库 COUNT，失败如实呈现——mpj_demo.user 无 deleted 列属数据问题）。**insight 九项洞察+关联功能 GUI 走查至此全部通过、零未修缺陷**。⚠️坑：分辨率/缩放中途变化后必须 SetProcessDpiAwareness(2) 且场景全程一致；JBPopup 菜单键盘 End 直达底项比坐标点选可靠；确认类对话框 Enter 触发默认键比坐标点 OK 可靠。
