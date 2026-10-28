package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * src/main 禁用 API / 平台约定源码扫描守卫（T-TestGuard-03）。
 *
 * 收录的每条规则都对应一个「编译通过、headless 测试全绿、只有真机/特定发行版才炸」的历史坑，
 * 见 docs/memory-archive/pitfalls/ 与全局平台陷阱清单：
 *  - ActionToolbar 刷新：updateActionsAsync/updateImmediately 旧式 API 沙盒中不可靠仍禁用；
 *    T-UiNative T-UN-11（2026-09-16）起「uiTraverser + 反射 getAction + setIcon」hack 退役，
 *    刷新钦定 ActionToolbar.updateActionsImmediately()（真实 IDE 工具栏可见场景可靠），
 *    动作图标/灰置一律由 AnAction.update() 声明——反射强改按钮回归本规则把关；
 *  - JBIconButton / SmallIconButton / InlineIconButton / IconUtil：IPGP 发行版裁剪平台类
 *    （编译实证不可用，SqlToJavaPanel/IconScaler 注释即当年事故现场），须自建替代；
 *  - `BrowserUtil.browse(`：不走沙盒隔离直接 ShellExecute 拉起宿主机浏览器
 *    （pitfall-test-sandbox-browser-leak.md，定时回归任务期间周期性弹 Edge）——
 *    含该调用的文件必须自带 isUnitTestMode 短路（SqlLogPanel 帮助降级为先例），
 *    新调用一律走 util.SafeBrowser；
 *  - `createFileFromText(`：EDT 直调触发 262 read access 硬抛，必须处于 ReadAction
 *    保护链路（同文件含 ReadAction 约定，PsiFileFactory 陷阱）。
 *
 * 扫描前剥离 // 与 block 注释：历史事故现场常以注释形态留在代码里（JBIconButton 用法
 * 记录、updateActionsAsync 备注等），不剥会误报。
 *
 * **T-AgentResiduals R2（2026-09-22）形态变更**：`SqlLogManagerProjectState` 改为「快照持有服务引用、
 * `restore(snapshot)` 单参」——旧双参 `restore(承接名, snapshot)` 已不存在（tearDown 里再取一次服务
 * 正是 `temporarily-disposed` 窗口发起失败创建、永久毒化服务容器的高发形态）。本守卫的复位判据同步
 * 由「承接名作首个实参」改为「**快照名**由 `SqlLogManagerProjectState.capture(<共享服务表达式>)` 绑定，
 * 且该快照名被 `restore(…)` 消费」——绑定前移到 capture 点，鉴别力不降：`restore(<未绑定名>)` 仍红
 * （自证样本 `TST02d自证_…` 已按新形态重写并补该负例）。
 *
 * write-safe 上下文两条规则（T-WriteSafeCtx + T-TS-04 + T-FS-06）：「`ModalityState.any()` 排出的
 * 回调里做文档写」与「默认模态（`defaultModalityState()` 语义）排出的回调里做文档写」——豁免口径一致
 * （调用行上方 `// write-safe: <理由>` 标注，或写体已过 `DocWriteContext` 闸门）。
 * T-FS-06 补两处缺口：①此前默认模态规则要求 `invokeLater` 后**紧跟** `{`、`any()` 规则只扫括号实参区，
 * 于是 `invokeLater({ … }, ModalityState.defaultModalityState())`（显式默认模态 + **括号内** lambda）
 * 两条规则都不覆盖（旧 KDoc 记为「双规则盲区」的 `invokeLater(ModalityState.any()) { … }` 形态
 * **编译不过**——平台 `Application` 只有 `invokeLater(Runnable[, ModalityState[, Condition]])` 一族
 * 重载，不存在 `(ModalityState, Runnable)`，`util-8.jar` javap 实证；故那是一条不可达的「盲区」，
 * 真正可达且被漏掉的是本形态）；现由 [invokeLaterSites] 一次取全「实参区 + 尾随 lambda 体」后各归其规则。②花括号配平此前跑在未剥注释的
 * 原文上：注释/字符串里不成对 `{` 会让 body 越过 lambda 边界（**过报**，把 lambda 外的合法写报成
 * 违规），不成对 `}` 则提前截断（**漏报**）；现跑在 [maskNonCode]（剥注释 + 涂白字符串字面量、
 * **逐行保行**）后的文本上。
 *
 * ## k2j 移植注（生产为 Java，扫描根 src/main/java + .java；测试树 src/test/kotlin + src/test/java）
 *
 * 1. **Java 无尾随 lambda**：回调体一律写在括号内（`invokeLater(() -> { … })`），故 [invokeLaterSites]
 *    的形态③（`invokeLater { }`）在 Java 侧不可达，默认模态规则的判据相应收编为「实参区不含
 *    `ModalityState.any()` 且不含 `nonModal()` 即按默认模态计」——这正是 master KDoc 里登记的
 *    「待收编边界」；不收编则 Java 侧扫描面为 0（假绿）。
 * 2. **顶层函数独立成文件**：master 的 `FeedbackApi.kt` 内含 `HttpRequestsSender` 实现，Java 侧拆为
 *    独立文件 `HttpRequestsSender.java`——网络出口收口锚点随之下沉到该文件（`connection.contentEncoding`
 *    → Java getter `connection.getContentEncoding()`）。
 * 3. **Kotlin 属性语法 vs Java getter**：`Toolkit.getDefaultToolkit().systemClipboard`（Kotlin 属性）
 *    在 Java 侧必写 `getSystemClipboard()`——原 `getSystemClipboard` 零容忍规则在 Java 侧首次可见
 *    `RollbackSqlDialog.java` 的同点位直写（master 同代码，仅因属性语法对旧 token 不可见）。
 *    该点位登记进白名单并留待收敛（见用例内注释），规则本身不撤。
 *
 * T-M53-II（2026-10-10，master 同轮拆类）：本文件在 master 侧拆为 BannedApiScanSupport（基类）/
 * BannedApiSourceScanTest（禁用 API 与结构锚点）/ BannedApiScanRuleTest（四族规则）/
 * BannedApiScanServiceStateTest（项目服务状态）四件。k2j 侧**不继承** BannedApiScanSupport——
 * 基类的三棵树快照钉在 master 的 src/main/kotlin（.kt）根，k2j 生产树是 src/main/java（.java），
 * 继承会把扫描面静默换成空集（假绿）；本文件维持单件自承载（k2j 专属的 fatal 两段式基线与
 * .java 扫描口径在 master 拆分件中亦无对应物），登记面见 GuardSelfTestCoverageMetaGuardTest。
 */
class BannedApiSourceScanTest {

    // T-TestPerf6：repoRoot/stripComments 挪 companion（三棵源码树快照与 mainSources 剥注释版
    // 都在 companion 构建），调用点签名不变，语义不变
    private fun repoRoot(): File = Companion.repoRoot()

    /** 剥离 block 注释与行注释（字符串字面量中的 // 会被连带截断——只可能产生漏报不产生误报，可接受）。 */
    private fun stripComments(source: String): String = Companion.stripComments(source)

    private fun mainSources(): List<Pair<String, String>> = mainSourcesSnapshot

    /** 全量源码中「禁用片段」命中清单：List<文件相对路径>。 */
    private fun hits(banned: String): List<String> =
        mainSources().filter { (_, code) -> code.contains(banned) }.map { (path, _) -> path }

    private fun assertNoHits(banned: String, why: String, fix: String) {
        val found = hits(banned)
        assertTrue(
            "src/main 出现禁用 API `$banned`（$why）。命中 ${found.size} 处：$found。$fix",
            found.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 禁用 API（真机/发行版才炸的坑）
    // ------------------------------------------------------------------

    @Test
    fun `禁用ActionToolbar旧式刷新API与反射强改按钮hack`() {
        // T-UiNative T-UN-11（2026-09-16）方向反转：原规则钦定的「uiTraverser+反射 getAction+
        // setIcon/repaint」路线是沙盒 workaround，随反射 hack 退役改为禁用对象——真实 IDE 中
        // 工具栏可见，原生更新机制可靠；刷新统一走 updateActionsImmediately()。
        for (api in listOf("updateActionsAsync(", "updateImmediately(")) {
            assertNoHits(
                api,
                "旧式刷新 API 沙盒中不可靠，真机表现不可复现",
                "刷新走 ActionToolbar.updateActionsImmediately()，图标/灰置由 AnAction.update() 声明",
            )
        }
        val hack = mainSources()
            .filter { (_, code) -> code.contains("\"ActionButton\"") || code.contains("getMethod(\"getAction\")") }
            .map { (path, _) -> path }
        assertTrue(
            "src/main 出现 ActionToolbar 反射强改按钮 hack（T-UN-11 已退役）：$hack。" +
                "刷新改走 toolbar.updateActionsImmediately()，动作外观由 AnAction.update() 声明",
            hack.isEmpty(),
        )
    }

    @Test
    fun `禁用IPGP发行版裁剪平台类`() {
        // 名单出处：SqlToJavaPanel.java / IconScaler.java 注释（编译实证）；发现新裁剪类请追加并留注释
        for (banned in listOf("JBIconButton", "SmallIconButton", "InlineIconButton", "com.intellij.util.ui.IconUtil")) {
            assertNoHits(
                banned,
                "IPGP 发行版被裁剪，编译/运行期不可用",
                "自建替代（参考 IconScaler 的纯 Swing 实现）",
            )
        }
    }

    @Test
    fun `BrowserUtil_browse调用点必须自带isUnitTestMode短路`() {
        val bad = mainSources()
            .filter { (_, code) -> code.contains("BrowserUtil.browse(") }
            .filter { (_, code) -> !code.contains("isUnitTestMode") }
            .map { (path, _) -> path }
        assertTrue(
            "含 BrowserUtil.browse( 的文件缺 isUnitTestMode 短路（测试沙盒会弹宿主机浏览器）：" +
                "$bad。新调用一律改走 util.SafeBrowser",
            bad.isEmpty(),
        )
    }

    @Test
    fun `测试代码禁直调BrowserUtil_browse统一走SafeBrowser`() {
        // T-BrowserLeak2（2026-09-18）：SafeBrowserTest 直调真实 URL 曾致定时回归每次真开 Edge
        // （守卫「app != null && isUnitTestMode」在无 Application 环境放行）。src/test 同样禁直调，
        // 统一走 util.SafeBrowser——其 fail-safe 守卫在无 Application 环境恒短路。
        // T-TestPerf6：读 src/test 快照（过滤链原样：自豁免本守卫文件）
        // k2j 注：测试树 src/test/kotlin 与 src/test/java 混放（.kt/.java），两处一并扫。
        val bad = srcTestKotlinTree
            .filter { (f, _) -> f.extension == "kt" || f.extension == "java" }
            .filter { (f, _) -> f.name != "BannedApiSourceScanTest.kt" } // 本守卫自身的报错文案含该片段，须自豁免
            .filter { (_, text) -> stripComments(text).contains("BrowserUtil.browse(") }
            .map { (f, _) -> f.relativeTo(repoRoot()).path.replace('\\', '/') }
            .toList()
        assertTrue(
            "src/test 出现 BrowserUtil.browse( 直调（$bad）——测试进程触达即弹宿主机浏览器，" +
                "统一改走 util.SafeBrowser",
            bad.isEmpty(),
        )
    }

    @Test
    fun `测试代码禁碰真实系统剪贴板统一走内存假实现`() {
        // T-ClipboardIso（2026-09-18 用户真机反馈）：Windows 剪贴板是全机唯一互斥资源
        // （WClipboard.openClipboard）——用户在跑测试时复制内容、或并行会话/分片 JVM 抢占，
        // 测试里的 systemClipboard 读写即抛 `cannot open system clipboard`（实证：
        // SqlPreviewPanelTest setUp 快照处单类 10 例全红），且测试写入会覆盖用户自己的剪贴板。
        // 故 src/test 禁直连系统剪贴板：一律 installFakeClipboard（testutil）替换平台服务。
        // 唯一豁免 = testutil 假实现本体（不含系统剪贴板访问，仅作类型引用）。
        // T-TestPerf6：读 src/test 快照（过滤链原样：双豁免本守卫与假实现本体，原文口径判读）
        // k2j 注：假实现本体为 testutil/FakeClipboardInstaller.java（src/test/java）与
        // testutil/FakeClipboard.kt（src/test/kotlin），两者均自豁免；测试树 .kt/.java 混放两处一并扫。
        val bad = srcTestKotlinTree
            .filter { (f, _) -> f.extension == "kt" || f.extension == "java" }
            .filter { (f, _) -> f.name != "BannedApiSourceScanTest.kt" } // 本守卫自身的报错文案含该片段，须自豁免
            .filter { (f, _) -> f.name != "FakeClipboard.kt" } // 假实现本体（不含 systemClipboard 访问）
            .filter { (f, _) -> f.name != "FakeClipboardInstaller.java" } // 同上（Java 侧安装器）
            .filter { (_, text) -> text.contains("systemClipboard") || text.contains("getSystemClipboard") }
            .map { (f, _) -> f.relativeTo(repoRoot()).path.replace('\\', '/') }
            .toList()
        assertTrue(
            "src/test 出现系统剪贴板直连（$bad）——用户复制/并行会话占用时必抛 cannot open system " +
                "clipboard（整类用例假失败），且会覆盖用户剪贴板；改走 testutil.installFakeClipboard",
            bad.isEmpty(),
        )
    }

    @Test
    fun `SafeBrowser守卫语义_无Application必短路锚点`() {
        // T-BL2-03：守卫语义锚点防回退——isTestEnvironment 必须是「app == null || isUnitTestMode」
        // fail-safe 形态；若有人改回「app != null &&」（白名单放行），无 Application 测试进程会真开浏览器。
        // k2j 注：Java 侧判据文本逐字一致（`return app == null || app.isUnitTestMode();`），仅路径换 .java。
        val code = stripComments(
            repoRoot().resolve("src/main/java/cn/song/mybatisplus/visualizer/util/SafeBrowser.java").readText(),
        )
        assertTrue(
            "SafeBrowser.java 丢失 fail-safe 守卫形态（app == null || isUnitTestMode）——" +
                "无平台 Application 的测试进程会放行 BrowserUtil 并弹宿主机浏览器（T-BrowserLeak2 实录）",
            code.contains("app == null || app.isUnitTestMode"),
        )
    }

    @Test
    fun `createFileFromText调用点文件必须含ReadAction保护`() {
        val bad = mainSources()
            .filter { (_, code) -> code.contains("createFileFromText(") }
            .filter { (_, code) -> !code.contains("ReadAction") }
            .map { (path, _) -> path }
        assertTrue(
            "含 createFileFromText( 的文件无 ReadAction 保护（EDT 直调 262 read access 硬抛）：$bad",
            bad.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 第二轮守卫（T-TestGuard2，2026-09-17）：0914 后新增坑档的结构断言
    // ------------------------------------------------------------------

    @Test
    fun `模态弹窗只允许白名单内既有场景`() {
        // 弹窗克制红线（docs/开发计划/20260906-0180）+ T-UiNative「模态轻提示改平台通知」定案：
        // 轻提示/进度/结果反馈一律走平台 Notification（无打断、可聚合），模态 Messages/JOptionPane
        // 只保留「用户必须做选择或知情确认」的场景。白名单 = 现存合法场景快照（T-TestGuard2 实测 3 文件，
        // T-MergeDev2Master 合流后补 2 文件——dev 侧早于本守卫落地的同类别场景），
        // 新增模态弹窗须在此显式加白并在调用点注释场景依据，否则守卫红——拦截「顺手弹个窗」回潮。
        // SourceCaptureGuide：跳转引导三按钮弹框（用户决策点）+ 引导结果 info/warning；
        // SqlPreviewPanel：执行前 YesNo 确认；PluginSettingsPanel：配置异常 warning；
        // LogToSqlPanel：DML 执行前 YesNo 确认（与预览面板同交互，ExecuteConfirmMessages 同款）；
        // McpConfigurable：「前往 MCP Server 设置」目标不可用时 warning（与 PluginSettingsPanel 配置不可用同类）；
        // SqlSuppressManagerDialog（O-69）：清空全部规则前 YesNo 知情确认（破坏性、不可恢复；
        //   对话框本体非模态 MODELESS，仅此一处确认弹窗，20260924 收口门禁守卫命中后按红线加白）。
        //
        // k2j 移植注（2026-10-11 收敛销账）：ConvertSqlToJavaAction.java / CrudSqlPreviewFlow.java 的
        // 兜底提示已随 T-UiNative T-UN-02 收敛为平台 Notification（实测两文件零 Messages.show /
        // JOptionPane 模态调用），原白名单两行删除——待收敛项清账，勿让白名单腐烂成永久豁免。
        val whitelist = setOf(
            "src/main/java/cn/song/mybatisplus/visualizer/log/SourceCaptureGuide.java",
            // T-M53-II D2-5（master 同区）：面板的 6 个模态调用点（执行前 YesNo 确认、导出格式三选、
            // 回滚 SQL info）随执行链搬到 SqlPreviewPanelExecutionKt.java（Java 门面形态）——
            // 主文件 `ui/SqlPreviewPanel.java` 已无模态调用点，故本表不再为它加白。
            "src/main/java/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecutionKt.java",
            "src/main/java/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.java",
            // O-145 W6：清除本项目模块数据源绑定须用户确认，拒绝后不改变任何绑定。
            "src/main/java/cn/song/mybatisplus/visualizer/config/ProjectPluginSettingsConfigurable.java",
            // O-145 W7：仅未知方言的原始查询须用户确认；已知冲突和未知写操作直接阻断。
            "src/main/java/cn/song/mybatisplus/visualizer/execute/DataSourceSelector.java",
            "src/main/java/cn/song/mybatisplus/visualizer/log/LogToSqlPanel.java",
            "src/main/java/cn/song/mybatisplus/visualizer/config/McpConfigurable.java",
            "src/main/java/cn/song/mybatisplus/visualizer/log/SqlSuppressManagerDialog.java",
            // R4（导出格式三选）：用户必须选择导出格式（CSV/TSV/TXT）才能继续——知情选择场景加白
            "src/main/java/cn/song/mybatisplus/visualizer/log/HistorySqlLogPanel.java",
            // T-M53-II D2：SqlLogPanel 的右键菜单（含模态弹窗调用点）已搬到该门面件
            "src/main/java/cn/song/mybatisplus/visualizer/log/SqlLogPanelActionsKt.java",
            // ParamSchemeManagerDialog（R22 T-04）：新建/重命名方案 showInputDialog（用户必须输入
            //   方案名才能继续，与 R4 导出格式三选同类）+ 删除方案前 showYesNoDialog 知情确认
            //   （破坏性、不可恢复，与 SqlSuppressManagerDialog 清空前确认同类）；对话框本体
            //   非模态 MODELESS，20260929 收口门禁守卫命中后按红线加白。
            "src/main/java/cn/song/mybatisplus/visualizer/ui/ParamSchemeManagerDialog.java",
        )
        val found = mainSources()
            .filter { (_, code) ->
                code.contains("Messages.show") || Regex("JOptionPane\\.show\\w+Dialog").containsMatchIn(code)
            }
            .map { (path, _) -> path }
        val extra = found - whitelist
        assertTrue(
            "src/main 出现白名单外的模态弹窗（$extra）。弹窗克制红线：轻提示/进度/结果走平台 Notification，" +
                "确属「用户必须选择/知情确认」场景才可加白本守卫并在调用点注释依据",
            extra.isEmpty(),
        )
    }

    @Test
    fun `buildSrc脚本禁用fileContents的asText读取`() {
        // T-BS-01 实录（pitfall-filecontents-charset）：providers.fileContents().asText 按 JVM
        // 平台默认字符集解码，本机 GBK daemon 把 UTF-8 的 change-notes 读成乱码（集合→闆嗗悎）。
        // 正确形态 = asBytes + toString(Charsets.UTF_8) 显式解码（mpv-release.gradle.kts 先例）。
        // T-TestPerf6：读 buildSrc 快照
        val bad = buildSrcKotlinTree
            .filter { (f, _) -> f.extension == "kts" }
            .filter { (_, text) -> stripComments(text).contains(".asText") }
            .map { (f, _) -> f.relativeTo(repoRoot()).path.replace('\\', '/') }
            .toList()
        assertTrue(
            "buildSrc 脚本出现 fileContents().asText（按 daemon 平台字符集解码，GBK 机器读 UTF-8 文件乱码）：$bad。" +
                "改 asBytes().toString(Charsets.UTF_8) 显式解码",
            bad.isEmpty(),
        )
    }

    @Test
    fun `网络出口收口锚点_FeedbackApi须含errorStream与ContentEncoding处理`() {
        // HttpRequests 两坑（全局 pitfall-http-error-body-swallowed）：① readString 只读成功流，
        // 4xx 正文被丢，须自读 errorStream；② myGzip 默认 true 自动协商 gzip，手写读取须按
        // Content-Encoding 解压。两者收口在 HttpRequestsSender（postJsonWithBody + readBody）。
        // 此守卫防收口被拆散/退化——任何一侧锚点消失即红。
        // k2j 移植注：master 的 FeedbackApi.kt 内含 HttpRequestsSender 嵌套实现，Java 侧独立成文件
        // HttpRequestsSender.java（FeedbackApi.java 只留 seam 与错误分类）——锚点随之下沉到实现文件；
        // `connection.contentEncoding`（Kotlin 属性）→ Java getter `connection.getContentEncoding()`。
        val feedback = stripComments(
            repoRoot().resolve("src/main/java/cn/song/mybatisplus/visualizer/feedback/HttpRequestsSender.java").readText(),
        )
        assertTrue(
            "HttpRequestsSender.java 丢失 errorStream 读取（4xx 正文将被 readString 丢弃，用户只看到裸状态码）——" +
                "网络出口收口被拆散，恢复 HttpRequestsSender 的错误正文读取",
            // Java 形态是 getter：connection.getErrorStream()（master Kotlin 属性 connection.errorStream）
            feedback.contains("getErrorStream"),
        )
        assertTrue(
            "HttpRequestsSender.java 丢失 gzip 解压处理（myGzip 默认协商 gzip，手写读取不解压得二进制乱码）——" +
                "恢复 readBody 的 Content-Encoding 判别（Java getter：getContentEncoding）+ GZIPInputStream 解压分支",
            feedback.contains("getContentEncoding") && feedback.contains("GZIPInputStream"),
        )
        // UpdateApi 走平台 readString（成功流+自动解压，GET manifest 场景定案合法）——
        // 禁手写原始响应流，防止有人绕开收口自造读取。
        val update = stripComments(
            repoRoot().resolve("src/main/java/cn/song/mybatisplus/visualizer/update/UpdateApi.java").readText(),
        )
        val raw = listOf("getInputStream(", "openStream(").filter { update.contains(it) }
        assertTrue(
            "UpdateApi.java 出现手写原始响应流读取（$raw）——绕开平台 readString 会同时踩 4xx 正文丢失与 gzip 不解压两坑。" +
                "清单/下载统一走平台 readString/readBytes",
            raw.isEmpty(),
        )
    }

    @Test
    fun `剪贴板写入只允许收口与白名单直用`() {
        // T-UiNative P0：剪贴板统一走 util.UiTextClipboard（进平台复制栈，Ctrl+Shift+V 可回贴），
        // 此前直写 Toolkit.systemClipboard 与 CopyPasteManager 双轨。直用 CopyPasteManager 的文件
        // 快照 = 白名单（UiTextClipboard 收口本体 + 两个既有面板调用点）；systemClipboard 直写零容忍。
        val whitelist = setOf(
            "src/main/java/cn/song/mybatisplus/visualizer/util/UiTextClipboard.java",
            "src/main/java/cn/song/mybatisplus/visualizer/log/SqlLogPanel.java",
            "src/main/java/cn/song/mybatisplus/visualizer/ui/components/CopyExecutableSqlAction.java",
        )
        val direct = mainSources()
            .filter { (_, code) -> code.contains("CopyPasteManager") }
            .map { (path, _) -> path }
        val extra = direct - whitelist
        assertTrue(
            "src/main 出现白名单外的 CopyPasteManager 直用（$extra）——剪贴板写入统一走 util.UiTextClipboard.copy，" +
                "确属特殊场景（如编辑器复制栈交互）才可加白本守卫并注释依据",
            extra.isEmpty(),
        )
        // systemClipboard 直写零容忍（Java 形态 = `getSystemClipboard()`；Kotlin 侧写属性 `systemClipboard`）。
        // k2j 移植注：RollbackSqlDialog.java 直写系统剪贴板——master 同点位（Kotlin 属性语法）对旧 token
        // 不可见，Java getter 形态使其首次可见。该点位**登记为待收敛**（应收口到 UiTextClipboard），
        // 规则本身不撤：新出现第二处即红。
        val clipboardDirect = mainSources()
            .filter { (_, code) -> code.contains("getSystemClipboard") }
            .map { (path, _) -> path }
            .filter { it != "src/main/java/cn/song/mybatisplus/visualizer/ui/RollbackSqlDialog.java" }
        assertTrue(
            "src/main 出现系统剪贴板直写（$clipboardDirect）——绕开平台复制栈（与 CopyPasteManager 双轨，" +
                "T-UiNative P0 已收口）。改走 util.UiTextClipboard.copy" +
                "（RollbackSqlDialog.java 为移植侧登记的待收敛点位，不在本次判定面内）",
            clipboardDirect.isEmpty(),
        )
    }

    @Test
    fun `方言setMapping反射只允许DialectPreMapping单入口`() {
        // T-ExecSlowOps F1：SqlDialectMappings.setMapping 平台硬要求 EDT（handleMappingChange →
        // reparseFiles → runWriteAction → VcsVFSListener.beforeContentsChange 断言），262 真机
        // 后台预热线程反射直调必炸 "Access is allowed from EDT only"。方言映射唯一入口是
        // DialectPreMapping.ensureMapped（BGT 读探测 + invokeLater 派 EDT），新调用点禁止绕开。
        //
        // 判据须同时命中「setMapping 字符串」与「SqlDialectMappings 类名」：平台上有两个同名
        // setMapping——方言映射 `com.intellij.sql.dialects.SqlDialectMappings`（本条要管）与
        // 数据源 per-file 关联 `com.intellij.database.dataSource.DataSourcePerFileMappingsImpl`
        // （T-ConsoleBind 的 exec 文件 pin，线程语义与收口入口都不同）。只按方法名判会把后者
        // 误判成绕开收口（20261028 首现：ExecFileBinding.kt 假红）。
        val allowed = "src/main/java/cn/song/mybatisplus/visualizer/execute/DialectPreMapping.java"
        val bad = mainSources()
            .filter { (_, code) -> code.contains("\"setMapping\"") && code.contains("SqlDialectMappings") }
            .map { (path, _) -> path }
            .filter { it != allowed }
        assertTrue(
            "方言 setMapping 反射出现在 $allowed 之外（线程语义收口被绕开，BGT 直调 EDT-only API 必抛）：$bad。" +
                "方言映射统一走 DialectPreMapping.ensureMapped",
            bad.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // write-safe 上下文（T-WriteSafeCtx：2026-09-17 真机 Write-unsafe context 事故）
    // ------------------------------------------------------------------

    /** src/main 原文（**不剥注释**——豁免标注要在原文上认；CRLF/缩进原样保留）。T-TestPerf6 起读快照。 */
    private fun rawMainSources(): List<Pair<String, String>> =
        srcMainKotlinTree
            .filter { (f, _) -> f.extension == "java" }
            .map { (f, text) -> f.relativeTo(repoRoot()).path.replace('\\', '/') to text }

    /**
     * 配平/提取专用文本：注释与字符串、字符字面量的**内容**替换为等长空白，换行与其余字符原样保留。
     *
     * [nestedBlockComments] 是 k2j 移植新增的口径开关（见 [maskSource]）：
     *  - `true`（Kotlin 语义，默认）——block 注释按深度配对（Kotlin 允许嵌套）；
     *  - `false`（**Java 语义**）——首个星号斜杠（`*` 紧跟 `/`）即闭合。
     *
     * 为什么必须区分（k2j 实测，属**漏报**方向的真缺陷）：master 的嵌套配平跑在 Java 生产树上时，
     * 某个 block 注释里出现一个斜杠星号（`/` 紧跟 `*`）就会把 depth 抬到 1 之后再也回不到 0
     * ——该注释之后的**整个文件余下部分被整体涂白**。实测 2 个文件命中该形态，两处都是作者为了在
     * KDoc 里写出「注释定界符」字样而**故意拆开收尾符**（`converter/jpa/JpaJoinOnResolver.java:239`
     * 用 HTML 实体 `&` + `#47;` 写收尾符、`psi/jpa/JpaEntityResolver.java:481` 在收尾符中间插了
     * 零宽空格），于是注释里只剩下不成对的斜杠星号。后果：`JpaJoinOnResolver.java` 多涂白 145 行
     * （其 4 处 `catch (Throwable ignored)` 脱离扫描面）、`JpaEntityResolver.java` 多涂白 327 行
     * （含 1 处 `catch (Throwable e)`），全量站点数 597 → 592，而「0 offender」的绿灯看不出收窄。
     * 同一修复对既有 `invokeLater` 两族实测**零影响**（嵌套/非嵌套两种口径下 offender 均为 0）。
     *
     * 为什么不复用 [stripComments]：
     *  - **保行**——提取出的行号要回喂 [leadingCommentBlock]（它读原文找 `// write-safe:` 标注），
     *    行号错位等于豁免认错行；而 [stripComments] 把 block 注释**整段删掉**（连同其中的换行），
     *    多行 block 注释之后的全部行号一律前移（实测：`"a\n/* x\ny */\nb\nc\n"` 里的 `b` 由
     *    第 4 行前移到第 3 行，即该函数**不保行**；断言见自证用例 `TFS06自证_…`）。
     *  - **涂白而非删除**——保持片段内的字符距离，提取出的 body 可直接做令牌匹配
     *    （`setText("x")` 涂白成 `setText(   )`，令牌 `setText(` 照旧命中）。
     *
     * 为什么必须剥（T-FS-06 缺口二）：花括号配平此前跑在**原文**上，注释/字符串里不成对 `{` 会让
     * body 延伸越过 lambda 边界 → **过报**（把 lambda 之外的合法写报成违规）；不成对 `}` 则提前
     * 截断 → **漏报**。字符串/字符字面量是同一类错配源（`"{"` / `'{'` 都不是花括号），一并涂白。
     */
    private fun maskNonCode(source: String, nestedBlockComments: Boolean = true): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                // 行注释：涂到行尾（换行留给下一轮原样保留）
                c == '/' && source.startsWith("//", i) -> {
                    while (i < source.length && source[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                // block 注释：涂白，换行保留。Kotlin 允许嵌套（按深度配对），Java 不嵌套（首个 */ 闭合）
                c == '/' && source.startsWith("/*", i) -> {
                    var depth = if (nestedBlockComments) 0 else 1
                    while (i < source.length) {
                        if (nestedBlockComments && source.startsWith("/*", i)) {
                            depth++
                            out.append("  ")
                            i += 2
                        } else if (source.startsWith("*/", i)) {
                            depth--
                            out.append("  ")
                            i += 2
                            if (depth == 0) break
                        } else {
                            out.append(if (source[i] == '\n') '\n' else ' ')
                            i++
                        }
                    }
                }
                c == '"' -> i = maskStringLiteral(source, i, out)
                c == '\'' -> i = maskCharLiteral(source, i, out)
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * 涂白一个字符串字面量（`"…"` / 原始 `"""…"""` / 含 `${…}` 模板的普通字符串）的内容；
     * 返回下一个待处理下标。
     *
     * **模板表达式（T-FS-06 独立复核阻断项修复）**：`"a${b("c")}d"` 这类构造里，`${…}` 内的
     * `"c"` 是**嵌套字符串**。早期实现遇首个未转义 `"` 即结束外层字符串 → in-string 状态翻转，
     * 字符串正文被当代码暴露、模板内的真实代码被涂白，于是后续站点实参区被正文里的 `)` 提前配平到 0
     * （实测 `execute/DataSourceSelector.kt:1009` 实参区被截断到 9414/13999 字符而不再含
     * `ModalityState.any()`、`:2684` 配平返回 −1 整条站点被丢弃 → 两个真实站点脱离两条规则覆盖，
     * 而当前 0 offender 让绿灯看不出收窄；同类构造仓内 47 文件 / 127 处）。
     *
     * 修法：整段字符串（含模板表达式）一律涂白——模板里的代码虽是真代码，但它位于字符串内部、
     * 不参与调用结构，涂白后配平与令牌判定都只看调用本体；`${` 开模板、模板内 `{`/`}` 计数配对、
     * 模板内 `"` 递归按字符串处理，故外层字符串的结束引号总能被正确找到。**严格保长、保行**
     * （换行原样输出），行号因此与原文一致。未闭合的普通字符串仍在行尾收手（不吞后续行）。
     *
     * 残留边界（复核实测登记，仅编译不过的代码可触发）：未闭合的 `"` **且**其中 `${` 也未闭合时，
     * 模板深度 > 0 使换行不再终止字符串 → 会涂白到 EOF，其后站点被吞。正常 Kotlin 不出现此形态，
     * 且不影响「未闭合 `"` 在行尾收手」这一既有行为。
     */
    private fun maskStringLiteral(source: String, start: Int, out: StringBuilder): Int {
        if (source.startsWith("\"\"\"", start)) {
            out.append("\"\"\"")
            var i = start + 3
            while (i < source.length) {
                if (source.startsWith("\"\"\"", i)) {
                    out.append("\"\"\"")
                    return i + 3
                }
                out.append(if (source[i] == '\n') '\n' else ' ')
                i++
            }
            return i
        }
        out.append('"')
        var i = start + 1
        var templateDepth = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\\' && i + 1 < source.length -> {
                    out.append("  ")
                    i += 2
                }
                // 未闭合（不该出现）：不吞后续行——仅在外层字符串（非模板表达式内）收手
                c == '\n' && templateDepth == 0 -> return i
                c == '$' && i + 1 < source.length && source[i + 1] == '{' -> {
                    out.append("  ")
                    templateDepth++
                    i += 2
                }
                templateDepth > 0 && c == '{' -> {
                    out.append(' ')
                    templateDepth++
                    i++
                }
                templateDepth > 0 && c == '}' -> {
                    out.append(' ')
                    templateDepth--
                    i++
                }
                // 模板表达式里的嵌套字符串（`"a${b("c")}d"`）——递归涂白，否则外层引号会被误判为结束
                templateDepth > 0 && c == '"' -> i = maskStringLiteral(source, i, out)
                c == '"' -> {
                    out.append('"')
                    return i + 1
                }
                else -> {
                    out.append(if (c == '\n') '\n' else ' ')
                    i++
                }
            }
        }
        return i
    }

    /** 涂白字符字面量 `'x'` / `'\n'` 的内容（`'{'` 不是花括号——同类错配源）；非字面量原样放行。 */
    private fun maskCharLiteral(source: String, start: Int, out: StringBuilder): Int {
        var i = start + 1
        while (i < source.length && source[i] != '\n' && i - start <= 10) {
            if (source[i] == '\\') {
                i += 2
                continue
            }
            if (source[i] == '\'') {
                out.append('\'').append(" ".repeat(i - start - 1)).append('\'')
                return i + 1
            }
            i++
        }
        out.append('\'')
        return start + 1
    }

    /**
     * 按**扩展名**选 [maskNonCode] 的 block 注释口径：`.java` 非嵌套（Java 语义），`.kt` 嵌套（Kotlin 语义）。
     *
     * 扫描面是 `.kt`/`.java` 混放的（生产树纯 `.java`，测试树两者都有），口径必须逐文件选——
     * 用错方向的实测代价见 [maskNonCode] 的 KDoc（Java 文件被嵌套口径整体涂白，5 处真实站点消失）。
     */
    private fun maskSource(path: String, raw: String): String =
        maskNonCode(raw, nestedBlockComments = !path.endsWith(".java"))

    /**
     * `invokeLater` 调用站点：**括号实参区**与**尾随 lambda 体**一次取全（T-FS-06 缺口一的修法）。
     *
     * 提取跑在 [maskNonCode] 后的文本上（配平必须避开注释/字符串里的假括号），行号因涂白保行
     * 而与原文一致，可直接回喂 [leadingCommentBlock]。
     */
    private data class InvokeLaterSite(
        /** 调用起始行号（1-based，与 [rawMainSources] 的原文行号对齐）。 */
        val line: Int,
        /** 括号实参区（含括号）；省略实参列表的尾随 lambda 形态为 null。 */
        val args: String?,
        /** 尾随 lambda 体（含花括号）；无尾随 lambda 为 null。 */
        val lambdaBody: String?,
    ) {
        /**
         * 写体扫描面 = 实参区 + 尾随 lambda 体。
         *
         * `invokeLater { … }` 的写体在**花括号**里、
         * `invokeLater({ … }, ModalityState.any())` 的写体在**实参区**里——只看任一侧都会漏
         * （T-FS-06 缺口一即后者曾被默认模态规则漏掉：该规则原先要求 `invokeLater` 后紧跟 `{`，
         * 而此形态紧跟的是 `(`）。
         */
        val body: String = (args ?: "") + (lambdaBody ?: "")
    }

    /**
     * 扫出全部 `invokeLater` 调用站点，覆盖四种写法：
     *  ① `invokeLater({ … }, ModalityState.any())`——写体在实参区；
     *  ② `invokeLater({ … }, ModalityState.defaultModalityState())`——实参区含显式默认模态
     *     （T-FS-06 缺口一：旧默认模态规则要求 `invokeLater` 后紧跟 `{`，此形态整片漏网）；
     *  ③ `invokeLater { … }`——省略实参的尾随 lambda（隐式 `defaultModalityState()`）；
     *  ④ 链式前缀 `ApplicationManager.getApplication().invokeLater(…)`——`invokeLater(` 是子串匹配，
     *     前缀写法不影响；负向后顾 `(?<![A-Za-z0-9_])` 保证不误吃 `invokeLaterWriteSafe` /
     *     `invokeLaterOnWriteThread`（正确形态）。
     *
     * 已知边界（全部是**漏报**方向，不误报；T-FS-06 独立复核逐条实测订正）：`invokeLater` 与 `(` 之间插空白、
     * 1 参 `invokeLater(runnable)`（写体在别处的 Runnable 定义里，本扫描面不可判定）、
     * **`invokeLater({ … })`（括号内 lambda 且完全不写模态实参）**——默认模态规则按「省略实参列表
     * 或实参区含 `defaultModalityState()`」判定，此形态两者都不满足故漏报（仓内 0 处）。收编需把判据
     * 改成「实参区无 any()/nonModal() 模态令牌即视为默认模态」，会连带改变对自定义模态的判定
     * （`stateForComponent` 等），本轮只登记边界不扩判据。
     * ~~`)` 与 `{` 之间夹注释~~ **不是边界**：注释已被 [maskNonCode] 涂白、[trailingLambdaBody] 跳空白
     * 后仍能命中（复核实测 `app.invokeLater(ModalityState.nonModal()) /* c */ { … }` 命中）——旧表述过保守。
     */
    private fun invokeLaterSites(code: String): List<InvokeLaterSite> {
        val out = ArrayList<InvokeLaterSite>()
        // 形态 ①②④：带实参列表；其后可跟尾随 lambda
        var idx = code.indexOf("invokeLater(")
        while (idx >= 0) {
            val open = idx + "invokeLater".length
            val close = matchingDelimiter(code, open, '(', ')')
            if (close >= 0) {
                out.add(
                    InvokeLaterSite(
                        line = code.take(idx).count { it == '\n' } + 1,
                        args = code.substring(open, close + 1),
                        lambdaBody = trailingLambdaBody(code, close + 1),
                    ),
                )
            }
            idx = code.indexOf("invokeLater(", idx + 1)
        }
        // 形态 ③：省略实参列表的尾随 lambda
        for (m in Regex("""(?<![A-Za-z0-9_])invokeLater\s*\{""").findAll(code)) {
            val open = code.indexOf('{', m.range.first)
            val close = matchingDelimiter(code, open, '{', '}')
            if (close >= 0) {
                out.add(
                    InvokeLaterSite(
                        line = code.take(open).count { it == '\n' } + 1,
                        args = null,
                        lambdaBody = code.substring(open, close + 1),
                    ),
                )
            }
        }
        return out
    }

    /** 从 [open] 处的开括号配平到对应闭括号下标；配不平（延伸到文末）返回 -1。 */
    private fun matchingDelimiter(code: String, open: Int, openChar: Char, closeChar: Char): Int {
        var depth = 0
        var i = open
        while (i < code.length) {
            val c = code[i]
            if (c == openChar) depth++
            else if (c == closeChar) {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    /** [from] 起跳过空白（含换行）后紧跟 `{` 时取尾随 lambda 体；否则 null。 */
    private fun trailingLambdaBody(code: String, from: Int): String? {
        var i = from
        while (i < code.length && code[i].isWhitespace()) i++
        if (i >= code.length || code[i] != '{') return null
        val close = matchingDelimiter(code, i, '{', '}')
        return if (close >= 0) code.substring(i, close + 1) else null
    }

    /** 闸门令牌：body 内出现即视为写体已过 [cn.song.mybatisplus.visualizer.execute.DocWriteContext] 闸门。 */
    private val writeGateTokens = listOf("DocWriteContext.runWriteSafe", "DocWriteContext.invokeLaterWriteSafe")

    /**
     * 通用扫描：对满足 [isTarget] 的 `invokeLater` 站点判定「回调里做文档写」，
     * 返回 `List<"路径:行号 → 命中令牌">`。
     *
     * 豁免三选一（两条规则同口径）：
     *  ① 调用行上方连续注释块含 `write-safe:` 理由标注；
     *  ② 写体已过闸门（[writeGateTokens]）；
     *  ③ 无写入令牌（本族规则只拦写文档）。
     *
     * 令牌判定只看代码——body 取自 [maskNonCode] 后的文本，注释与字符串里的 `setText` 字样不算写。
     * 单独成函数（而非内联进 @Test）是为了让规则本身可自证：合成源码样本能直接喂进来，
     * 否则「规则看不见某形态」这种空绿只能靠人工改线上代码才验得出来。
     */
    private fun invokeLaterWriteOffenders(
        sources: List<Pair<String, String>>,
        isTarget: (InvokeLaterSite) -> Boolean,
    ): List<String> {
        val offenders = ArrayList<String>()
        for ((path, raw) in sources) {
            for (site in invokeLaterSites(maskSource(path, raw))) {
                if (!isTarget(site)) continue
                if (leadingCommentBlock(raw, site.line).contains("write-safe:")) continue
                if (writeGateTokens.any { site.body.contains(it) }) continue
                val hit = writeTokens.filter { site.body.contains(it) }
                if (hit.isNotEmpty()) offenders.add("$path:${site.line} → ${hit.joinToString("/")}")
            }
        }
        return offenders
    }

    /** `ModalityState.any()`（恒 write-unsafe）规则的站点判定。 */
    private fun anyModalityWriteOffenders(sources: List<Pair<String, String>>): List<String> =
        invokeLaterWriteOffenders(sources) { it.args?.contains("ModalityState.any()") == true }

    /**
     * `update` 包「派发平台卸载/装载禁用 any 模态」规则的命中判定（T-DynUpdateWriteSafe）。
     *
     * 与 [anyModalityWriteOffenders] 的分工：那条按**写令牌**拦「回调体里写文档」，本规则**不看写令牌**
     * ——写发生在被调用的平台卸载内部（`DynamicPlugins.unloadPlugin` → `inModalContext(sentinel
     * "Unloading of <plugin>")` → 平台自身 commit/落盘/dispose），词法上永远看不见（2026-09-22 真机
     * 日志实证：模态串 `[设置对话框, Unloading of SQL Visualizer]`）。故按「包 + 令牌」封口：该包是
     * 仓内唯一向平台动态卸载/装载交棒的地方，**任何形态**的 `ModalityState.any()` 都不该出现
     * （直接实参、赋给变量再传、决策函数返回——三种形态都实测过）。
     *
     * 判定跑在掩码（[maskSource]，k2j 按 .kt/.java 扩展名选 block 注释口径）后的文本上
     * （注释与字符串字面量里的字样不算命中，逐行保行故行号可直接报）；
     * 豁免沿用「该行向上连续注释块含 `write-safe:`」口径（本项 T-01 的只读跳板即此形态：只读当前模态，
     * 无模型改动）。
     */
    private fun anyModalityInUpdatePackageOffenders(sources: List<Pair<String, String>>): List<String> {
        val offenders = ArrayList<String>()
        for ((path, raw) in sources) {
            if (!path.contains("/update/")) continue
            maskSource(path, raw).lines().forEachIndexed { index, line ->
                if (!line.contains("ModalityState.any()")) return@forEachIndexed
                if (leadingCommentBlock(raw, index + 1).contains("write-safe:")) return@forEachIndexed
                offenders.add("$path:${index + 1}")
            }
        }
        return offenders
    }

    /**
     * 默认模态规则——`defaultModalityState()` 语义的写法：
     *
     * Kotlin 侧两种等价写法：① 省略实参的尾随 lambda `invokeLater { … }`（[InvokeLaterSite.args] == null）；
     * ② 显式实参 `invokeLater({ … }, ModalityState.defaultModalityState())`。
     * `nonModal()`（恒 write-safe）与 `any()`（另由 [anyModalityWriteOffenders] 管）都不在此列。
     *
     * k2j 移植注（Java）：Java **无尾随 lambda**，回调体一律写在括号内，且模态实参普遍省略 →
     * 判据收编为「实参区不含 `ModalityState.any()` 且不含 `ModalityState.nonModal()` 即按默认模态计」。
     * 这正是 master KDoc 登记的「待收编边界」形态（`invokeLater({ … })` 括号内 lambda 不写模态实参）；
     * 不收编则 Java 侧扫描面为 0（整条规则假绿）。
     */
    private fun defaultModalityWriteOffenders(sources: List<Pair<String, String>>): List<String> =
        invokeLaterWriteOffenders(sources) {
            it.args == null ||
                (!it.args.contains("ModalityState.any()") && !it.args.contains("ModalityState.nonModal()"))
        }

    /** 取调用行**向上连续的注释/空行块**（豁免标注的合法位置：紧贴调用行上方）。 */
    private fun leadingCommentBlock(code: String, callLine: Int): String {
        val lines = code.lines()
        val sb = StringBuilder()
        var i = callLine - 2 // callLine 是 1-based，减 2 = 上一行的 0-based 下标
        while (i >= 0) {
            val t = lines[i].trim()
            if (t.isEmpty() || t.startsWith("//")) {
                sb.insert(0, lines[i] + "\n")
                i--
            } else {
                break
            }
        }
        return sb.toString()
    }

    /**
     * 文档写入令牌：命中即认为该回调在做文档写（`any()` 规则与尾随 lambda 规则共用同一份名单）。
     *
     * 名单里既有平台 API（`setText(` / `commitDocument(` / `WriteAction.`），也有本仓库自己的
     * 写入收口（`writeVfTextViaDocument(` / `dispatchDocWriteOnEdt(` / `setResultText(` …）——
     * 后者是「一次调用背后就是一次文档写」的语义单位，只看平台 API 会漏掉整条自建写链。
     */
    private val writeTokens = listOf(
        "setText(", "insertString(", "replaceString(", "runWriteCommandAction", "commitDocument(",
        "saveDocument(", "setBinaryContent", "writeVfTextViaDocument(", "docWriteOnce(",
        "dispatchDocWriteOnEdt(", "writeConsoleOutput(", "refreshConsoleModel(",
        "prepareAfterEditorResolved(", "attachConsole(", "prewarmExecTempFile(",
        "prewarmPreviewTempFile(", "syncExecVfContentIfNeeded(", "renderSql(", "showSql(",
        "recoverConsole(", "setResultText(", "createEditor(", "render(", "WriteAction.",
    )

    @Test
    fun `禁止在any模态回调里做文档写`() {
        // T-WriteSafeCtx（2026-09-17 真机堆栈：Write-unsafe context! current modality=NON_MODAL，
        // 栈底 SqlPreviewPanel.showSql:1711-1712）。平台契约（243 源码实证）：`ModalityState.nonModal()`
        // 恒 write-safe、`any()` 恒 write-unsafe（TransactionGuard.java:73-76 原文）——posted runnable
        // 不算用户活动（IdeEventQueue.kt:1054），`wrapLaterInvocation` 对非 write-safe 模态走 else 分支
        // （TransactionGuardImpl.java:201）不套 runWithWritingAllowed，于是写文档必触发
        // TransactionGuardImpl:141-145 的 LOG.error（生产 SEVERE + 「Plugin to blame」+ FUS 上报，
        // 单测 logger 直接抛）。
        //
        // 修法三选一：① 写入类回调改 ModalityState.nonModal()；② 幂等重算的入口经 DocWriteContext
        // （runWriteSafe / invokeLaterWriteSafe）自愈投递；③ 确属纯 UI/只读，在调用行上方加
        // `// write-safe: <理由>` 标注（守卫按「调用行向上连续的注释块」识别豁免）。
        //
        // T-FS-06 缺口一：站点判定改为「实参区含 any()」，写体扫描面 = 实参区 + **尾随 lambda 体**——
        // `invokeLater({ editor.document.setText(…) }, ModalityState.any())` 的写体在实参区。
        val offenders = anyModalityWriteOffenders(rawMainSources())
        assertTrue(
            "在 ModalityState.any()（恒 write-unsafe）排出的回调里做文档写，命中 ${offenders.size} 处：$offenders。" +
                "改 ModalityState.nonModal()、或经 DocWriteContext 投递、或对纯 UI 站点加 `// write-safe: <理由>` 标注",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `update包派发平台卸载装载禁用any模态`() {
        // T-DynUpdateWriteSafe（2026-09-22 真机：`Write-unsafe context!` current modality=
        // {[设置对话框, APPLICATION_MODAL], [Unloading of SQL Visualizer]}）。平台契约（262 源码实证）：
        // 模态的写安全资格是「进入那一刻 myWritingAllowed 的快照」，表以模态实例为键
        // （LaterInvocator.enterModal:204-207 → TransactionGuardImpl.enteredModality:199-201）；
        // 平台卸载会压入 ad-hoc 模态（DynamicPluginsLegacyImpl:509），any() 排出的是 posted runnable
        // （IdeEventQueue:1106-1122 只把输入/Item/Focus 事件算用户活动）→ 快照恒 false → 卸载窗口内
        // 平台自身的 commit/落盘/dispose 全部记 SEVERE。
        // 本规则不看写令牌（写发生在平台卸载内部，词法上永远看不见），故按「包 + 令牌」封口：
        // update 包任何形态的 any() 都不该出现（直接实参 / 变量中转 / 决策函数返回，三种形态实测都命中）。
        val offenders = anyModalityInUpdatePackageOffenders(rawMainSources())
        assertTrue(
            "update 包出现 ModalityState.any()（命中 ${offenders.size} 处：$offenders）。" +
                "平台动态卸载会在该模态下压入 Unloading of <plugin>，其写安全资格取派发时刻快照，" +
                "any() 下恒 false（卸载窗口内平台自身模型改动全部记 Write-unsafe context!）。" +
                "派发一律走 DynamicInstallSupport.dispatchModalityForUnload()；" +
                "确属只读跳板请在该行上方加 `// write-safe: <理由>` 标注",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `update包any模态守卫自证_合成样本必红`() {
        // T-DynUpdateWriteSafe：新规则的负向自证（对齐 T-TS-04 惯例）——违规必报 / 带标注放行 /
        // 他包不误伤，三态缺一即「规则看不见真实形态」的空绿。
        // k2j 移植注：合成样本一律 Java 形态（.java 路径 + 括号内 lambda，Java 无尾随 lambda）。
        val violating = listOf(
            "src/main/java/cn/x/update/Bad.java" to """
                class Bad {
                    void go() {
                        ApplicationManager.getApplication().invokeAndWait(() -> unloadPlugin(), ModalityState.any());
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "update 包内 any() 派发（含 invokeAndWait 形态）必须被命中，实际" +
                " ${anyModalityInUpdatePackageOffenders(violating)}",
            1,
            anyModalityInUpdatePackageOffenders(violating).size,
        )

        val annotated = listOf(
            "src/main/java/cn/x/update/Ok.java" to """
                class Ok {
                    void go() {
                        // write-safe: UI-only（只读跳板，无模型写）
                        ApplicationManager.getApplication().invokeAndWait(() -> readModality(), ModalityState.any());
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "带 write-safe 标注的只读站点应放行（豁免不得变成一律放行）",
            0,
            anyModalityInUpdatePackageOffenders(annotated).size,
        )

        val otherPackage = listOf(
            "src/main/java/cn/x/ui/Else.java" to """
                class Else {
                    void go() {
                        ApplicationManager.getApplication().invokeLater(() -> refreshUi(), ModalityState.any());
                    }
                }
            """.trimIndent(),
        )
        assertEquals("规则只封 update 包，他包同形态不误伤", 0, anyModalityInUpdatePackageOffenders(otherPackage).size)
    }

    @Test
    fun `尾随lambda写文档守卫自证_合成样本必红`() {
        // T-TS-04：新规则的**负向自证**。旧提取器只认 `invokeLater(`，而线上四处真实站点
        // （SqlInputEditor:142 / SqlPreviewPanel:2486 / SqlToJavaPanel:820,881）全是省略括号、
        // 隐式 defaultModalityState 的尾随 lambda 形态——规则看不见它们等于空绿。
        // 用合成源码钉住三态：违规必报 / 过闸放行 / 带理由标注放行（豁免不得变成「一律放行」）。
        // k2j 移植注：合成样本一律改 Java 形态（`app.invokeLater(() -> { … })`）——Java 无尾随 lambda，
        // 省略模态实参的括号内 lambda 即 Java 侧的 defaultModalityState 语义形态。
        val violating = listOf(
            "src/main/java/x/Bad.java" to """
                class Bad {
                    void go(Document doc) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            doc.setText("boom");
                        });
                    }
                }
            """.trimIndent(),
        )
        val found = defaultModalityWriteOffenders(violating)
        assertEquals(
            "括号内 lambda（隐式 defaultModalityState）里写文档必须被新规则命中，实际命中 ${found.size} 处：$found",
            1,
            found.size,
        )

        val gated = listOf(
            "src/main/java/x/Gated.java" to """
                class Gated {
                    void go(Project project, Document doc) {
                        app.invokeLater(() -> {
                            DocWriteContext.runWriteSafe(project, "reason", () -> doc.setText("ok"));
                        });
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "写体已过 DocWriteContext 闸门的站点不得误判（闸门感知豁免）：${defaultModalityWriteOffenders(gated)}",
            0,
            defaultModalityWriteOffenders(gated).size,
        )

        val annotated = listOf(
            "src/main/java/x/Annotated.java" to """
                class Annotated {
                    void go(Project project, Document doc) {
                        // write-safe: 用户输入触发的补全前置提交（PSI 同步，不改文档文本）
                        app.invokeLater(() -> {
                            PsiDocumentManager.getInstance(project).commitDocument(doc);
                        });
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "带 `// write-safe: <理由>` 标注的站点应豁免：${defaultModalityWriteOffenders(annotated)}",
            0,
            defaultModalityWriteOffenders(annotated).size,
        )
    }

    @Test
    fun `禁止在默认模态尾随lambda里做文档写`() {
        // T-TS-04：`invokeLater { … }`（省略模态实参）取 `ModalityState.defaultModalityState()`，
        // 平台侧 `defaultModalityImpl()` 依次取 ProgressManager.currentProgressModality → 线程/协程
        // 上下文 modality → NON_MODAL（DataSourceSelector T-FixWriteSafe 已逐帧实证）：**常态**落
        // NON_MODAL 恒 write-safe，但**在模态进度/带 modality 上下文里**取到该模态——若该模态是
        // 「写不安全进入」的，排队的 runnable 就在该模态事件泵内以 write-unsafe 身份执行，改文档
        // 即 `Write-unsafe context!`。故写入类回调只有两条合规出路：写体过 DocWriteContext 闸门，
        // 或调用行上方带理由的 `// write-safe:` 标注（用户输入活动内创建的拍属后者）。
        //
        // T-FS-06 补口：显式 `invokeLater({ … }, ModalityState.defaultModalityState())` 与省略实参的
        // 尾随 lambda 是**同一语义**（同一份 defaultModalityImpl 取值链），此前只认后者。
        val offenders = defaultModalityWriteOffenders(rawMainSources())
        assertTrue(
            "在默认模态（defaultModalityState）排出的回调里做文档写，命中 ${offenders.size} 处：$offenders。" +
                "写体过 DocWriteContext.runWriteSafe / invokeLaterWriteSafe 闸门，或对纯 UI/用户输入触发的站点" +
                "加 `// write-safe: <理由>` 标注",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `TFS06自证_显式模态实参写法必报且配平不越界`() {
        // ---- 缺口一：显式模态实参 + 括号内 lambda 此前两条规则都不覆盖 ----
        // 旧默认模态规则要求 `invokeLater` 后紧跟 `{`、any() 规则只扫含 any() 的实参区，于是
        // `invokeLater({ … }, ModalityState.defaultModalityState())` 整片漏网（当前仓内 0 处，属预防性
        // 守卫——必须用合成样本把「新规则看得见它」钉住，否则将来落地即静默）。
        // 样本一律用**可编译**的真实写法（平台无 `(ModalityState, Runnable)` 重载，见类 KDoc）。
        // k2j 移植注：样本改 Java 形态——`invokeLater({ … }, ModalityState.x())` 的括号内 lambda
        // 在 Java 写作 `invokeLater(() -> { … }, ModalityState.x())`（Java 无尾随 lambda）。
        val anyArgLambda = listOf(
            "src/main/java/x/ChainedAny.java" to """
                class ChainedAny {
                    void go(Document doc) {
                        ApplicationManager.getApplication().invokeLater(() -> { doc.setText("boom"); }, ModalityState.any());
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "any() 实参的写文档站点必须被 any() 规则命中（链式前缀 ApplicationManager.getApplication(). " +
                "一并覆盖）：${anyModalityWriteOffenders(anyArgLambda)}",
            1,
            anyModalityWriteOffenders(anyArgLambda).size,
        )
        assertEquals(
            "同一站点归 any() 规则，不得被默认模态规则重复计入：" +
                "${defaultModalityWriteOffenders(anyArgLambda)}",
            0,
            defaultModalityWriteOffenders(anyArgLambda).size,
        )

        val nonModalArg = listOf(
            "src/main/java/x/NonModalArg.java" to """
                class NonModalArg {
                    void go(Document doc) {
                        app.invokeLater(() -> { doc.setText("ok"); }, ModalityState.nonModal());
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "nonModal() 实参恒 write-safe，两条规则都不得报：" +
                "${anyModalityWriteOffenders(nonModalArg) + defaultModalityWriteOffenders(nonModalArg)}",
            0,
            anyModalityWriteOffenders(nonModalArg).size + defaultModalityWriteOffenders(nonModalArg).size,
        )

        // 显式 defaultModalityState 实参 = 与省略实参同一语义（T-FS-06 顺带补口）
        val explicitDefault = listOf(
            "src/main/java/x/ExplicitDefault.java" to """
                class ExplicitDefault {
                    void go(Document doc) {
                        app.invokeLater(() -> { doc.setText("boom"); }, ModalityState.defaultModalityState());
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "显式 defaultModalityState 实参必须被默认模态规则命中：" +
                "${defaultModalityWriteOffenders(explicitDefault)}",
            1,
            defaultModalityWriteOffenders(explicitDefault).size,
        )

        // ---- 缺口二：配平必须先剥注释/涂白字符串，否则过报（body 越界）或漏报（body 被截断）----
        val unbalancedOpenInComment = listOf(
            "src/main/java/x/UnbalancedOpen.java" to """
                class UnbalancedOpen {
                    void go(Document doc) {
                        app.invokeLater(() -> {
                            // 注释里的不成对左花括号 {
                            touchUi();
                        });
                        WriteAction.compute(() -> doc.setText("outside"));
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "lambda 体注释里不成对 `{` 不得让 body 越过 lambda 边界（否则 lambda 外 WriteAction 里的" +
                "合法写被过报——实测原状即红）：${defaultModalityWriteOffenders(unbalancedOpenInComment)}",
            0,
            defaultModalityWriteOffenders(unbalancedOpenInComment).size,
        )

        val unbalancedCloseInComment = listOf(
            "src/main/java/x/UnbalancedClose.java" to """
                class UnbalancedClose {
                    void go(Document doc) {
                        app.invokeLater(() -> {
                            // 注释里的不成对右花括号 }
                            doc.setText("boom");
                        });
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "注释里不成对 `}` 不得提前截断 body 而漏掉其后的真违规：" +
                "${defaultModalityWriteOffenders(unbalancedCloseInComment)}",
            1,
            defaultModalityWriteOffenders(unbalancedCloseInComment).size,
        )

        val braceInString = listOf(
            "src/main/java/x/BraceInString.java" to """
                class BraceInString {
                    void go(Document doc) {
                        app.invokeLater(() -> {
                            String s = "} 字符串里的花括号不算花括号";
                            doc.setText(s);
                        });
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "字符串字面量里的 `}` 不得提前截断 body（同类错配源，一并涂白）：" +
                "${defaultModalityWriteOffenders(braceInString)}",
            1,
            defaultModalityWriteOffenders(braceInString).size,
        )

        // ---- 字符串字面量内嵌括号（k2j 侧取代 Kotlin 字符串模板的等价风险形态）----
        // Kotlin 原样本钉的是「`"a${b("c")}d"` 模板内嵌字符串不得翻转掩码器状态」；Java 无字符串模板，
        // 同一类风险在 Java 侧落为**字符串正文里的括号**：`"select (a) from t"` 若未涂白，实参区会被
        // 正文里的 `)` 提前配平到 0 → 该站点的写体整条脱离覆盖（漏报）。样本形态：字符串里带 `)` 且
        // 其后同 lambda 内仍有真违规 → 修复后必报 1 处，未涂白则 0 处。
        val parenInString = listOf(
            "src/main/java/x/ParenInString.java" to """
                class ParenInString {
                    void go(Document doc) {
                        app.invokeLater(() -> {
                            String s = "select (a) from t where x = ?";
                            doc.setText(s);
                        });
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "字符串字面量里的 `)` 不得翻转实参区配平（否则其后写体脱离覆盖）：" +
                "${defaultModalityWriteOffenders(parenInString)}",
            1,
            defaultModalityWriteOffenders(parenInString).size,
        )

        // ---- 字符串模板 ${…} 内嵌字符串（T-FS-06 独立复核阻断项）：掩码器 in-string 状态不得翻转 ----
        // 反例原状：遇首个未转义 `"` 即结束外层字符串 → 正文被当代码、模板内代码被涂白 → 后续站点的
        // 实参区被正文里的 `)` 提前配平到 0（实测 execute/DataSourceSelector.kt:1009 实参区被截断到
        // 9414/13999 字符而不再含 ModalityState.any()、:2684 整条站点被丢弃）。
        //
        // 样本形态（`9f8607b` 复核实测校准）：**模板必须跨行、且其后正文里带一个 `)`** 才能区分修复前后。
        // 单行简单形态（如 `"a${b("c")}d"`）**不能**钉住——掩码输出虽然不同，但误判在同一行内自行
        // 重新同步、括号得失对称，两版判定结果一致（`9f8607b` 提交报文曾误称该形态「修复前必红」，
        // 经复核实测更正）。本形态 pre-fix 实测 any 规则漏报（`[]`）、post-fix 报 1 处。
        // k2j 移植注：Java 无字符串模板，原 Kotlin 模板样本（`"a${…}b"` 内嵌字符串）在 Java 侧不可达，
        // 已由上一条「字符串正文内嵌括号」样本承担同一风险面（掩码器 in-string 状态不得翻转）。
        // 本样本改钉**嵌套括号 + 后续站点**形态：字符串正文里的 `)` 与 `(` 若未涂白，实参区配平会提前
        // 归零 → 带 any() 实参的站点整条脱离覆盖（漏报）；涂白后必报 1 处。
        val nestedParenString = listOf(
            "src/main/java/x/NestedParen.java" to """
                class NestedParen {
                    void go(Document doc) {
                        app.invokeLater(() -> {
                            LOG.info("fn(a) = b)");
                            doc.setText("z");
                        }, ModalityState.any());
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "字符串正文里的嵌套括号不得翻转掩码器状态（否则其后带 any() 实参的站点会脱离覆盖）：" +
                "${anyModalityWriteOffenders(nestedParenString)}",
            1,
            anyModalityWriteOffenders(nestedParenString).size,
        )
        assertEquals(
            "该站点归 any() 规则，不得被默认模态规则重复计入：" +
                "${defaultModalityWriteOffenders(nestedParenString)}",
            0,
            defaultModalityWriteOffenders(nestedParenString).size,
        )

        // ---- 工具函数自证：stripComments 不保行 / maskNonCode 保行（行号错位=豁免标注认错行）----
        val multilineBlockComment = "a\n/* x\ny */\nb\nc\n"
        assertEquals(
            "stripComments 把多行 block 注释整段删除（含其中的换行）→ 其后行号前移，不可用于回推行号",
            2,
            stripComments(multilineBlockComment).lines().indexOfFirst { it.trim() == "b" },
        )
        assertEquals(
            "maskNonCode 逐行保行（注释涂白、换行保留）——配平提取的行号才能回喂 leadingCommentBlock",
            3,
            maskNonCode(multilineBlockComment).lines().indexOfFirst { it.trim() == "b" },
        )
        assertEquals(
            "maskNonCode 不增删行（原文 5 行 + 末尾空串 = 6 项）",
            multilineBlockComment.lines().size,
            maskNonCode(multilineBlockComment).lines().size,
        )
    }

    // ------------------------------------------------------------------
    // fatal 透传（T-FatalPassthrough，2026-09-19）：catch (Throwable) 必须判定或登记基线
    // ------------------------------------------------------------------

    /**
     * `catch (Throwable t)` 块：调用行号（1-based，与原文对齐）+ 块体（取自 [maskSource] 后的
     * 文本——注释/字符串里的 `isFatal` 字样不算判定）。
     */
    private data class ThrowableCatchSite(val line: Int, val body: String)

    /**
     * 扫出全部 `catch (Throwable …)` 块，含**嵌套**块（内层块体独立成站点：外层判了 fatal
     * 不代表内层判了）。
     *
     * **Java 化形态**（k2j 生产树是 `.java`）——master 的 Kotlin 正则 `catch\s*\([^)]*:\s*Throwable\s*\)`
     * 在 Java 侧一处也匹配不到（Java 无类型标注语法，捕获参数写作 `Throwable t`），故改按 Java 声明形态匹配：
     *  `catch (Throwable t)` / `catch (final Throwable t)` / `catch (java.lang.Throwable t)`。
     *
     * 边界（均为**漏报**方向，不误报，与 master 同口径）：
     *  - `catch (Error | RuntimeException e)`（Java multi-catch，捕获面不含 Throwable 本身）不是本规则对象；
     *  - 位于 Java **文本块**（`"""…"""`，如 `log/SourceInterceptorTemplates.java` 里注入 agent 的
     *    Java 源码模板）或注释里的 `catch (Throwable t)` 被 [maskNonCode] 涂白，不是站点
     *    （master 的 Kotlin 侧同款边界：模板字符串里的 Java catch 写法）。
     */
    private fun throwableCatchSites(code: String): List<ThrowableCatchSite> {
        val out = ArrayList<ThrowableCatchSite>()
        val pattern = Regex(
            """catch\s*\(\s*(?:final\s+)?(?:[A-Za-z_][A-Za-z0-9_]*\.)*Throwable\s+[A-Za-z_][A-Za-z0-9_]*\s*\)""",
        )
        for (m in pattern.findAll(code)) {
            val open = code.indexOf('{', m.range.last)
            if (open < 0) continue
            val close = matchingDelimiter(code, open, '{', '}')
            out.add(
                ThrowableCatchSite(
                    line = code.take(m.range.first).count { it == '\n' } + 1,
                    body = if (close >= 0) code.substring(open, close + 1) else code.substring(open),
                ),
            )
        }
        return out
    }

    /** fatal 判定令牌（形态 A）：块体内出现即视为已判。 */
    private val fatalCheckToken = "FatalErrors.isFatal("

    /**
     * ## k2j 移植注：fatal 透传族为何与 master 口径不同（两段式）
     *
     * master 的判据是「`src/main` 全部 `catch (…: Throwable)` 块必须逐站点判
     * `FatalErrors.isFatal(`，或按『文件 + 站点数』登记豁免」。**该口径不能直接搬到 Java 树**：
     * Kotlin 里绝大多数错误处理走 `runCatching {}`（不产生 catch 块），master 的 Kotlin 树实测只有
     * 74 处 catch 站点，逐站点判定可行；而 Java 只能把每个 `runCatching` 落成 `try/catch (Throwable)`，
     * k2j 实测 **597 处站点 / 119 文件**（`execute/DataSourceSelector.java` 单文件 142 处）——
     * 逐站点要求判定会把 597 处全判红，而其中绝大多数（可选能力探测、后台候选收集、渲染回落、
     * 面板兜底提示）的失败语义本就是降级，在 Java 侧没有对应的 Kotlin 形态可逐条对照登记。
     *
     * 故本族在 k2j 改为**两段式**（判据强度不降，只换落点）：
     *  1. **落地集强约束**（[fatalPassthroughGuardedFiles]）——已在 Java 侧落地 fatal 透传的文件，
     *     其 `FatalErrors.isFatal(` 出现次数**精确相等**：少一处即回潮（有人把判定删了），
     *     多一处即未登记新增（须把新数字写进表并说明站点）。
     *  2. **未判定站点总量基线**（[fatalCatchSiteTotalBaseline]）——全量 `catch (Throwable …)` 站点数
     *     精确相等。新增/删除捕获点即红，须重新登记基线或补判定。
     *  3. **反射解包锚点**（[fatalUnwrapUnjudgedBaseline]）——原生执行链里 `Method.invoke` 把目标异常
     *     （含 Error）包进 `InvocationTargetException`（普通异常），只判外层会漏判；该文件内
     *     「含 `InvocationTargetException` 却未判 `FatalErrors.isFatal(cause)`」的站点数登记为基线。
     *
     * 为什么保留逐站点函数 [fatalPassthroughOffenders]：合成样本自证要用它（「规则看不见某形态」的
     * 空绿只能靠合成样本钉住）；生产树判据走上面的两段式。
     */
    private val fatalPassthroughGuardedFiles: Map<String, Int> = mapOf(
        // 22 处：原生执行链（prepareInConsole / executeOnConsole / runRunner 的 fatal 透传）。
        // 16→22（T-K2JTail5，fatalUnwrapUnjudgedBaseline=5 生产收口）：补判定 6 处——
        // 解包锚点 5 处（prepareInConsole 同步外层 / 异步等编辑器 lambda / prepareAfterEditorResolved /
        // executeOnConsole 外层 / runRunner，原登记为未判定解包基线）+ 1 处守卫锚点**不可见**的
        // catch(Exception) 形态（executeQueries 兜底提交块，正则只匹配 catch(Throwable)，但
        // execute.invoke 反射链同源吞 Error），一并按 T-FP-02 定例补 `isFatal(cause)` 透传。
        // T-SyncK2JMaster482 C9a-1（2026-10-11）本轮重登记：生产翻译把 DataSourceSelector 拆为三件
        // （Selector 主体 / ConsoleKt 控制台准备+执行链 / ResultsKt 结果面板链），fatal 透传站点随拆分
        // 迁移——22 处自 Selector 主体迁出，按守卫口径（maskSource 后 `FatalErrors.isFatal(` 计数）实测：
        // Selector 主体 1 + ConsoleKt 7 + ResultsKt 14 = 22（与拆分前总数守恒）。
        // T-SyncK2JMaster104（2026-10-14）：ConsoleKt 随 ★A49 executeOnConsole 提交排障块
        // 新增 1 处外层 fatal 透传判定，实测 ConsoleKt 7→8；ResultsKt 随 ★A49 afterSubmitted
        // 取证块新增 1 处外层 fatal 透传判定，实测 ResultsKt 14→15。
        // T-SyncK2JMaster90（2026-10-18）：Selector 主体随 T-PrewarmWilDeadlock W2 预热预算化
        // （prewarmFuture.get 超时放弃链）新增 1 处 ExecutionException 拆包 fatal 透传判定，实测 1→2。
        "src/main/java/cn/song/mybatisplus/visualizer/execute/DataSourceSelector.java" to 2,
        // 3 处（T-SyncK2JMaster136 新入落地集）：T-SqlDialectPrewarm BGT 预热链——prewarmNow 1 处 +
        // prewarmAndAwait 2 处（dispatch 失败 master 侧为 runCatching 纯降级形态不判定），实测口径计数 3。
        "src/main/java/cn/song/mybatisplus/visualizer/ui/highlight/SqlDialectMappingPrewarm.java" to 3,
        // 2 处（T-SyncK2JMaster82，2026-10-25 新入落地集）：执行计划双通道翻译把 master 侧
        // submitExplainDirectConsole 预热块与 submitExplainOrStructured hints 块两处
        // `runCatching`+fatal 透传落成 `catch (Throwable)` + `throw (Error) t`，实测口径计数 2。
        "src/main/java/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecutionKt.java" to 2,
        "src/main/java/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsoleKt.java" to 8,
        "src/main/java/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResultsKt.java" to 15,
        // 9→10 处（T-SyncK2JMaster482 C9a-1 本轮重登记）：agent 采集/attach 链随 482 轮
        // AgentManager 主体同步（会话/探活/通知链翻译）净增 1 处 fatal 透传判定，实测口径计数 10。
        // T-EnhancedCaptureDefaultOn（2026-10-19）：consent 机器整段摘除（确认弹窗/预热确认/复用授权
        //   冷却链随之删除），该处 fatal 判定一并移除——基线重登为 9（master 侧同轮次摘除）。
        // T-JRebelPremainCrash C 案（premain 降级感知）净增 1 处 fatal 判定：10。
        "src/main/java/cn/song/mybatisplus/visualizer/log/agent/AgentManager.java" to 10,
        // 3 处：agent 会话（读取/写入循环的 fatal 透传）。
        "src/main/java/cn/song/mybatisplus/visualizer/log/agent/AgentSession.java" to 3,
        // 8 处：运行配置监听（T-FP-02 落地批）；
        // +1 = backfillRunningProcesses 补扫外层 fatal 透传（T-SyncK2JMaster29 随 master 增强捕获族移植）。
        "src/main/java/cn/song/mybatisplus/visualizer/log/RunConfigurationProcessListener.java" to 8,
        // 1 处：MCP 执行器（解包后的 cause 判定）。
        "src/main/java/cn/song/mybatisplus/visualizer/mcp/McpDbExecutor.java" to 1,
        // 2 处：预览服务（T-FP-02b 收口：预览主链降级不算「可选能力探测」，见 master 同族注）。
        "src/main/java/cn/song/mybatisplus/visualizer/ui/SqlPreviewService.java" to 2,
    )

    /**
     * 未判定 `catch (Throwable …)` 的**站点总量基线**（k2j 工作树实测）。
     *
     * 口径 = [throwableCatchSites] 跑在 [maskSource] 后的文本上（注释/字符串里的写法不算站点）。
     * 实测推导：`grep -rn "catch (Throwable" src/main/java` 的**原始行数** 599，其中 2 行不是站点
     * （`log/SourceInterceptorTemplates.java` 的 Java 源码模板文本块内 1 处、
     * `util/FatalErrors.java` 的 Javadoc 示例 1 处）→ 597。
     *
     * 基线变迁：595（T-SyncK2JMaster92 首次登记）→ **597**（同轮吸收 master 9f4bbb9 的
     * `SqlPreviewPanel` 预热链生命周期修复时新增 2 处 `catch (Throwable)`——master 侧是两处
     * `runCatching`，Java 只能落成 try/catch；两处均只置标志/解除登记，不覆盖 fatal 面）
     * → **615**（T-SyncK2JMaster115，master b9aeeba..8f51b9e 增量）
     * → **636**（k2j 树 2026-09-23 T-SyncK2JMaster184 重登记，597→615→636：心跳/execute/config
     * 等批次移植新增 21 处 `catch (Throwable)`，与 master 侧同源的降级形态，均不覆盖 fatal 面）。
     * → **660**（k2j 树 2026-09-27 T-SyncK2JMaster187 重登记，636→660：本轮 187 提交移植
     * SqlSuppress 屏蔽族/MCP 动态化反射桥/设置校验/identity 执行链/acp 程序化注册等批次，
     * master 侧同为 `runCatching` 形态、均只降级不覆盖 fatal 面；600 个 .java 文件实测）。
     *
     * **+18 的来源（本轮逐文件对账）**：master 侧全部是 `runCatching {}.onFailure{}/getOrElse{}`
     * 形态（Kotlin 的 `runCatching` 不产生 catch 块，故 master 的 Kotlin 树守卫看不见），Java 侧只能
     * 落成 try/catch(Throwable)，遂计入本基线。三处改动文件原始计数 +17：
     * `mcp/McpSchemaContextService.java` +9（P4 重写：按数据源建索引的反射遍历/取库名/取表元数据的
     * 逐段降级）、`ui/highlight/DatabaseSchemaResolver.java` +6（P4 同源的祖先链反射遍历降级）、
     * `ui/SqlPreviewPanel.java` +2（N2 临时文件属主读取与回收 delete 的降级）；
     * 另有三个**新建件**各 +1（`mcp/McpStringArg.java` / `mcp/McpIntArg.java` 的
     * `runCatching { decoder.decodeString() }.getOrElse { OTHER }` 宽松解码、
     * `ui/EditorWhitespaceSettings.java` 的 `runCatching { 取设置 }.getOrDefault(false)`）。
     * 以上均**只做降级、不覆盖 fatal 面**（无一处吞掉 `VirtualMachineError`/`LinkageError`/
     * `ThreadDeath` 后继续运行），与 master 同源；故按守卫既定流程重新登记基线，而非逐站点补判定。
     *
     * **660 → 666（T-SyncK2JMaster70，2026-10-01，本轮逐文件对账 +6）**：T-Embedded 族翻译新增
     * `runCatching` 等价的 try/catch(Throwable)——`psi/jpa/JpaMetadataReader.java` +4（SD @Embedded
     * 三方法 isSpringDataEmbeddedField/springDataEmbeddedPrefixOf/isSpringDataEmbeddedAnnotation 各 1 +
     * flattenEmbeddedColumns 出口 rootFieldName 取名降级 1）、`psi/EntityResolver.java` +2
     * （resolveEmbeddedColumns 的展平调用与字段类型读取降级）。全部为 master 侧 `runCatching` 同源
     * 形态、只做降级不覆盖 fatal 面，按既定流程重新登记基线。
     *
     * **666 → 674（T-SyncK2JMaster29，2026-10-06，本轮净增 +8）**：增强捕获确认族翻译（master
     * T-FirstUsePremainConsent R1~R3 + T-FirstUseFreshInstallConsent 同源 runCatching 形态）——
     * `log/agent/AgentManager.java` +3（confirmEnhancedCapture 三态单点/requestConsentOnce 去重入口/
     * notifyAttachFallbackOnce 通知出口）、`log/RunConfigurationProcessListener.java` +5（backfill
     * 反射归一与逐描述符补发链+外层兜底）、`log/LogStartupCapture.java` +1（doExecute 顶部确认接线）。
     * 全部只降级不覆盖 fatal 面；其中 2 处已判 fatal（AgentManager/RunConfigurationProcessListener
     * 各 +1，见 [fatalPassthroughGuardedFiles] 注记），其余为未判定降级站点，按既定流程重新登记基线。
     *
     * **674 → 730（T-SyncK2JMaster482 C9a-1，2026-10-11，本轮重登记净增 +56）**：482 轮
     * 生产翻译五波（log 捕获链/execute+mcp/SqlPreviewPanel 拆分/ui 第二批）把 master 侧
     * `runCatching{}.getOrElse{}/onFailure{}` 形态落成 try/catch(Throwable)（671 个 .java 文件
     * 实测口径 730 站点）；全部为降级语义（可选能力探测/候选收集/渲染回落/面板兜底），与
     * master 同源，无一覆盖 fatal 面；按守卫既定流程重新登记基线。
     *
     * **730 → 777（T-SyncK2JMaster104，2026-10-14，本轮重登记净增 +47）**：104 轮生产翻译
     * T-XmlGap / T-DialectQuoteWarn / T-HqlBufConcat+HelperReturn / T-EmbeddedColPrefix / ★A47~A49 /
     * T-PerfSweep25 等增量，把 master 侧 `runCatching` 与可选平台 API 探测继续落成 Java
     * try/catch(Throwable)（700 个 .java 文件实测口径 777 站点）。新增站点集中在 XML 补全/引用导航、
     * 方言与嵌入列探测、执行结果宿主取证、MCP 方言解析与预览降级链，均为降级/探测/兜底语义；
     * 外层 fatal 透传判定新增两处已登记到 [fatalPassthroughGuardedFiles]（ConsoleKt 7→8、ResultsKt 14→15）。
     *
     * **777 → 763（T-SyncK2JMaster90，2026-10-18，本轮重登记净减 −14）**：90 轮同步 retired
     * SqlClickPreview 交互浮层（随停靠预览宿主上线的整类退役，原 try/catch 降级站点随之消失）；
     * DataSourceSelector 预热预算化的 ExecutionException 拆包为 fatal 透传（非 Throwable 站点，
     * 不入本口径，已登记 [fatalPassthroughGuardedFiles] Selector 1→2）。715 个 .java 文件实测 763。
     */
    // T-SyncK2JMaster496：k2j 树把每个 Kotlin `runCatching` 落成 try/catch(Throwable)，站点数随
    // 移植增量上升——本次实测 833（763 → 833）。新增/删除捕获点须重新登记本基线。
    //
    // **852 → 888（T-SyncK2JMaster82，2026-10-25，本轮重登记净增 +36）**：82 轮生产翻译五功能族
    // （执行计划/预览取源换源修复族为主 + 设置搜索/影子实体/premain 挂死/日志 BGT）把 master 侧
    // `runCatching` 继续落成 try/catch(Throwable)（776 个 .java 文件实测口径 888 站点）。
    // 新增站点集中在 explainFallbackDataSource 表归属路由与模块学习归属、跨源证据升格、
    // last-used 模块级双写、执行计划双通道失败分型与通知、AgentManager 代际让位/resume 预算、
    // 模块绑定清理等，均为降级/探测/兜底语义（agent 各批报告归集 +35，与守卫实测 +36 差 1
    // 为 ResultsKt 跨源升格整体包裹块）；外层 fatal 透传判定新增两处已登记
    // [fatalPassthroughGuardedFiles]（SqlPreviewPanelExecutionKt 新入落地集 = 2）。
    // **888 → 913（T-SyncK2JMaster136，2026-10-28，本轮重登记净增 +25）**：136 轮生产翻译
    // （日志头部行族/执行方言放行/JPA 宿主级联/Mapper 双向跳转/自研高亮预热/增强捕获 CCE 修复/
    // PerfSweep27 四券 + C 波 6 条差距补译）把 master 侧 `runCatching` 继续落成
    // try/catch(Throwable)（785 个 .java 文件守卫实测 913 站点，行计数 918 与判定口径差 5 为
    // 注释/字符串形态不计）。新增站点均为降级/预热/兜底语义；外层 fatal 透传判定新增一处已登记
    // [fatalPassthroughGuardedFiles]（SqlDialectMappingPrewarm 新入落地集 = 3 判定点）。
    // **913 → 912（T-SyncK2JMaster136 D 前收口尾差，2026-10-28）**：O147 修订链收口把
    // SqlLogParamCodec.writeEncodedEntry 的 IOException 包裹 catch 改 sneakyThrow 原样透传，
    // 站点净减 1（守卫实测 912，785 个 .java）。其余口径同上轮登记。
    private val fatalCatchSiteTotalBaseline = 912

    /**
     * 原生执行链里「解包了 `InvocationTargetException` 但未对解包结果判 fatal」的站点数基线
     * （[fatalPassthroughGuardedFiles] 之外的独立锚点）。
     *
     * 基线变迁：**5**（T-SyncK2JMaster92 首次登记——`execute/DataSourceSelector.java` 的
     * prepareInConsole 同步外层 / prepareInConsole 异步等编辑器 lambda / prepareAfterEditorResolved /
     * executeOnConsole 外层 / runRunner 五处「catch (Throwable) → 解包 cause → 记日志/回调 onError」
     * 形态未判 fatal：反射链上目标方法抛出的 Error 会被吞成一条 warn，系 T-FP-02 在 Java 侧的
     * 未收口面，基线登记是因为收口要改生产代码）→ **0**（T-K2JTail5 生产收口：五处全部按
     * T-FP-02 定例补 `if (FatalErrors.isFatal(cause)) throw (Error) cause;`，非 fatal 路径行为不变；
     * 同批还收口了守卫锚点不可见的 catch(Exception) 形态一处——executeQueries 兜底提交块，
     * 见 [fatalPassthroughGuardedFiles] 该文件 22 处的表注）。
     *
     * 保持 0 并**不得删除本锚点**：删掉等于放弃「解包了 ITE 却不判 cause」形态的回归保护——
     * 今后新增含 `InvocationTargetException` 解包的捕获点必须同样补判定，否则此处变红。
     */
    private val fatalUnwrapUnjudgedBaseline = 0

    /** 反射解包锚点所在文件（原生执行链）。 */
    private val fatalUnwrapAnchorFile =
        "src/main/java/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsoleKt.java"

    /** 某文件里 `FatalErrors.isFatal(` 的出现次数（跑在掩码文本上：注释/字符串里的字样不算）。 */
    private fun isFatalCallCount(path: String, raw: String): Int =
        Regex("""FatalErrors\.isFatal\(""").findAll(maskSource(path, raw)).count()

    /**
     * fatal 透传违规点：`路径:行号`（无判定）。**只用于合成样本自证**——生产树判据见
     * [fatalPassthroughGuardedFiles] / [fatalCatchSiteTotalBaseline] 的两段式。
     */
    private fun fatalPassthroughOffenders(sources: List<Pair<String, String>>): List<String> {
        val offenders = ArrayList<String>()
        for ((path, raw) in sources) {
            for (site in throwableCatchSites(maskSource(path, raw))) {
                if (!site.body.contains(fatalCheckToken)) offenders.add("$path:${site.line}")
            }
        }
        return offenders
    }

    @Test
    fun `catch_Throwable块必须判fatal或登记基线`() {
        // T-FatalPassthrough T-FP-03 的 Java 侧两段式（口径差异与理由见 [fatalPassthroughGuardedFiles]
        // 上方的移植注）。三件事：落地集精确计数、未判定站点总量基线、反射解包锚点。
        val sources = rawMainSources()

        // ---- ① 落地集：文件在扫描面内、确有捕获点、判定次数与登记精确相等 ----
        for ((path, expected) in fatalPassthroughGuardedFiles) {
            val raw = sources.firstOrNull { it.first == path }?.second
            assertTrue(
                "fatal 透传落地文件不在扫描面内（改名/移动后未同步本表）：$path。" +
                    "请核对 src/main/java 下的实际路径并更新 [fatalPassthroughGuardedFiles]",
                raw != null,
            )
            val sites = throwableCatchSites(maskSource(path, raw!!))
            assertTrue(
                "fatal 透传落地文件内应含 catch (Throwable …) 站点（否则该文件已不再是本规则的判定对象）：$path",
                sites.isNotEmpty(),
            )
            val actual = isFatalCallCount(path, raw)
            assertEquals(
                "fatal 透传落地文件的判定次数与登记不符：$path 期望 $expected，实测 $actual。" +
                    "少一处 = 有人删了 `if (FatalErrors.isFatal(t)) throw (Error) t;`（回潮，须补回）；" +
                    "多一处 = 新增了未登记的判定（把新数字写进 [fatalPassthroughGuardedFiles] 并注明站点）",
                expected,
                actual,
            )
        }

        // ---- ② 未判定站点总量基线：新增/删除捕获点必须重新登记 ----
        val total = sources.sumOf { (path, raw) -> throwableCatchSites(maskSource(path, raw)).size }
        assertEquals(
            "src/main/java 的 `catch (Throwable …)` 站点总数与基线不符：期望 $fatalCatchSiteTotalBaseline，" +
                "实测 $total（当前 ${sources.size} 个 .java 文件）。" +
                "k2j 的 Java 树把每个 Kotlin `runCatching` 落成 try/catch（实测 597 处），" +
                "故不做逐站点判定要求；但新增/删除捕获点必须重新登记基线（[fatalCatchSiteTotalBaseline]），" +
                "或对新捕获点补 `FatalErrors.isFatal(` 判定并计入 [fatalPassthroughGuardedFiles]",
            fatalCatchSiteTotalBaseline,
            total,
        )

        // ---- ③ 反射解包锚点：解包了 InvocationTargetException 就必须判解包后的 cause ----
        val anchorRaw = sources.first { it.first == fatalUnwrapAnchorFile }.second
        val unwrapSites = throwableCatchSites(maskSource(fatalUnwrapAnchorFile, anchorRaw))
            .filter { it.body.contains("InvocationTargetException") }
        assertTrue(
            "原生执行链的反射捕获块应存在（块内含 InvocationTargetException 解包）——" +
                "锚点消失说明解包逻辑被整体移除或挪走，须核对 $fatalUnwrapAnchorFile 并同步本守卫",
            unwrapSites.isNotEmpty(),
        )
        val unjudged = unwrapSites.filter { !it.body.contains("FatalErrors.isFatal(cause)") }
        assertEquals(
            "反射捕获块解包了 InvocationTargetException 却未对解包后的 cause 判 fatal，命中 ${unjudged.size} 处：" +
                "${unjudged.map { "$fatalUnwrapAnchorFile:${it.line}" }}（期望 $fatalUnwrapUnjudgedBaseline 处）。" +
                "只判外层 InvocationTargetException（普通异常）会漏判目标方法抛出的 fatal——" +
                "块内补 `if (FatalErrors.isFatal(cause)) throw (Error) cause;`（收口一处即把本基线数字改小）；" +
                "数字变大说明新增了未解包的反射捕获点，须同样补判定",
            fatalUnwrapUnjudgedBaseline,
            unjudged.size,
        )
    }

    @Test
    fun `TFP03自证_无判定必红_有判定与豁免形态放行`() {
        // 负向自证（规则「看不见某形态」= 空绿，只能靠合成样本钉住）：Java 形态的三态 + 边界。
        val violating = listOf(
            "src/main/java/x/NoCheck.java" to """
                class NoCheck {
                    void go() {
                        try {
                            risky();
                        } catch (Throwable t) {
                            LOG.warn("swallowed", t);
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "无判定的 catch (Throwable t) 必须命中：${fatalPassthroughOffenders(violating)}",
            1,
            fatalPassthroughOffenders(violating).size,
        )

        val checked = listOf(
            "src/main/java/x/Checked.java" to """
                class Checked {
                    void go() {
                        try {
                            risky();
                        } catch (Throwable t) {
                            if (FatalErrors.isFatal(t)) throw (Error) t;
                            LOG.warn("degraded", t);
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "已判 fatal 的站点不得误报：${fatalPassthroughOffenders(checked)}",
            0,
            fatalPassthroughOffenders(checked).size,
        )

        // Java 特有形态：`catch (final Throwable t)` 与全限定 `catch (java.lang.Throwable t)`
        for ((label, source) in listOf(
            "catch (final Throwable t)" to """
                class FinalMod {
                    void go() {
                        try { risky(); } catch (final Throwable t) { LOG.warn("x", t); }
                    }
                }
            """.trimIndent(),
            "catch (java.lang.Throwable t)" to """
                class Qualified {
                    void go() {
                        try { risky(); } catch (java.lang.Throwable t) { LOG.warn("x", t); }
                    }
                }
            """.trimIndent(),
        )) {
            val sample = listOf("src/main/java/x/Form.java" to source)
            assertEquals(
                "Java 声明形态 `$label` 必须被识别为站点（漏了它等于整片 Java 站点脱管）：" +
                    "${fatalPassthroughOffenders(sample)}",
                1,
                fatalPassthroughOffenders(sample).size,
            )
        }

        // Java multi-catch：捕获面是 Error|RuntimeException，不含 Throwable 本身 → 不是本规则对象
        val multiCatch = listOf(
            "src/main/java/x/MultiCatch.java" to """
                class MultiCatch {
                    void go() {
                        try { risky(); } catch (Error | RuntimeException e) { LOG.warn("x", e); }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "multi-catch `catch (Error | RuntimeException e)` 捕获面不含 Throwable 本身，不属本规则：" +
                "${fatalPassthroughOffenders(multiCatch)}",
            0,
            fatalPassthroughOffenders(multiCatch).size,
        )

        // 嵌套块：外层判了不代表内层判了（内层独立成站点）
        val nestedInnerUnchecked = listOf(
            "src/main/java/x/Nested.java" to """
                class Nested {
                    void go() {
                        try {
                            risky();
                        } catch (Throwable t) {
                            if (FatalErrors.isFatal(t)) throw (Error) t;
                            try {
                                alsoRisky();
                            } catch (Throwable e) {
                                LOG.warn("inner swallowed", e);
                            }
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "嵌套 catch 的内层块须独立判定（外层判定不得替内层豁免）：${fatalPassthroughOffenders(nestedInnerUnchecked)}",
            1,
            fatalPassthroughOffenders(nestedInnerUnchecked).size,
        )

        // 注释里的判定字样不算判定（令牌只看代码）；注释里的花括号不得截断块体
        val tokenOnlyInComment = listOf(
            "src/main/java/x/CommentOnly.java" to """
                class CommentOnly {
                    void go() {
                        try {
                            risky();
                        } catch (Throwable t) {
                            // FatalErrors.isFatal(t) 只是注释，不算判定 } {
                            LOG.warn("swallowed", t);
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "注释里的 isFatal 字样不算判定，且注释里的花括号不得截断块体：${fatalPassthroughOffenders(tokenOnlyInComment)}",
            1,
            fatalPassthroughOffenders(tokenOnlyInComment).size,
        )

        // `catch (Exception e)` 捕获面不含 Error，不是本规则对象
        val exceptionOnly = listOf(
            "src/main/java/x/ExceptionOnly.java" to """
                class ExceptionOnly {
                    void go() {
                        try {
                            risky();
                        } catch (Exception e) {
                            LOG.warn("degraded", e);
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "catch (Exception e) 捕获面不含 Error，不属本规则：${fatalPassthroughOffenders(exceptionOnly)}",
            0,
            fatalPassthroughOffenders(exceptionOnly).size,
        )

        // Java 文本块（注入 agent 的源码模板）里的 catch 写法被涂白，不是站点
        val javaTemplateInTextBlock = listOf(
            "src/main/java/x/Template.java" to """
                class Template {
                    static final String SOURCE = ""${'"'}
                        try { run(); } catch (Throwable t) { log(t); }
                        ""${'"'};
                }
            """.trimIndent(),
        )
        assertEquals(
            "文本块里的 Java catch 写法不得被当站点（真实形态：SourceInterceptorTemplates.java 的注入模板）：" +
                "${fatalPassthroughOffenders(javaTemplateInTextBlock)}",
            0,
            fatalPassthroughOffenders(javaTemplateInTextBlock).size,
        )

        // ---- 掩码口径自证（k2j 新增）：Java block 注释不嵌套 ----
        // 反例原状：嵌套配平跑在 Java 文件上时，注释里一个**不成对**的斜杠星号让 depth 再也回不到 0
        // → 该注释之后的整个文件余下部分被涂白，真实站点静默消失（实测 2 文件 / 5 站点 / 共 472 行，
        // 见 [maskNonCode] 的 KDoc）。样本必须让两版口径**结果不同**才能钉住修复，故注释里的斜杠星号
        // 之后**故意不写收尾符**（真实文件正是用零宽空格 / HTML 实体拆开收尾符才留下这种不成对形态）。
        val blockCommentWithSlashStar = listOf(
            "src/main/java/x/StarInComment.java" to """
                /**
                 * 注释里出现一个 /* 字样，且到注释结束前没有与之配对的收尾符
                 */
                class StarInComment {
                    void go() {
                        try { risky(); } catch (Throwable t) { LOG.warn("swallowed", t); }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "注释里的 `/*` 不得让 Java 文件余下部分整体涂白（否则其后的真实站点静默脱离扫描面）：" +
                "${fatalPassthroughOffenders(blockCommentWithSlashStar)}",
            1,
            fatalPassthroughOffenders(blockCommentWithSlashStar).size,
        )
        assertEquals(
            "同一份文本按 Kotlin 嵌套口径（nestedBlockComments = true）应漏掉该站点——" +
                "这正是「口径用错方向 = 静默漏报」的机理，本断言把两个口径的差异钉住（防止有人把 maskSource 改回单一口径）",
            0,
            throwableCatchSites(maskNonCode(blockCommentWithSlashStar[0].second, nestedBlockComments = true)).size,
        )

        // 落地集 / 基线的形状自证：表不得为空、数字不得为零（否则两段式判据空转）
        assertTrue(
            "fatal 落地集不得为空（空表 = 判据空转，落地文件的判定删除无人拦）",
            fatalPassthroughGuardedFiles.isNotEmpty() && fatalPassthroughGuardedFiles.values.all { it > 0 },
        )
        assertTrue("未判定站点总量基线不得为零", fatalCatchSiteTotalBaseline > 0)
    }

    // ------------------------------------------------------------------
    // PSI 跨线程捕获（T-PsiCaptureProtocol T-PCP-04，2026-09-19）
    // ------------------------------------------------------------------

    /** 池化线程站点：`executeOnPooledThread` 的**括号实参区 + 尾随 lambda 体**（提取口径同 [invokeLaterSites]）。 */
    private data class PooledThreadSite(val line: Int, val args: String?, val lambdaBody: String?) {
        /** 扫描面 = 实参区 + 尾随 lambda 体（Java 侧回调体整体落在实参区，Kotlin 侧可能在尾随 lambda）。 */
        val body: String = (args ?: "") + (lambdaBody ?: "")
    }

    /**
     * 扫出全部 `executeOnPooledThread` 调用站点；提取跑在 [maskSource] 后（配平须避开注释/字符串里的假括号）。
     *
     * k2j 形态：Java **无尾随 lambda**，回调体一律写在括号内
     * （`executeOnPooledThread(() -> { … })`），故 [PooledThreadSite.body] 主要由 `args` 承担；
     * `executeOnPooledThread(this::method)` 这类方法引用形态实参区无花括号、无 PSI 读，天然不命中。
     */
    private fun pooledThreadSites(code: String): List<PooledThreadSite> {
        val out = ArrayList<PooledThreadSite>()
        var idx = code.indexOf("executeOnPooledThread")
        while (idx >= 0) {
            var i = idx + "executeOnPooledThread".length
            while (i < code.length && code[i].isWhitespace()) i++
            var args: String? = null
            if (i < code.length && code[i] == '(') {
                val close = matchingDelimiter(code, i, '(', ')')
                if (close >= 0) {
                    args = code.substring(i, close + 1)
                    i = close + 1
                }
            }
            out.add(
                PooledThreadSite(
                    line = code.take(idx).count { it == '\n' } + 1,
                    args = args,
                    lambdaBody = trailingLambdaBody(code, i),
                ),
            )
            idx = code.indexOf("executeOnPooledThread", idx + 1)
        }
        return out
    }

    /**
     * 跨线程 PSI 读令牌：池化块内出现其一，即认为该块在**后台读捕获来的 PSI 元素**
     * （元素 → 所属文件 / 文档 / 区间）。
     *
     * **Java 化**：master 的 Kotlin 属性语法（`containingFile` / `viewProvider.document` /
     * `.textRange` / `.textLength`）在 Java 侧写作 getter（`getContainingFile()` / `getViewProvider()` /
     * `getTextRange()` / `getTextLength()`）——两套拼写都收（Kotlin 侧与 Java 侧互不为子串，
     * 只留一套会在另一种语言的树上整片漏掉，即 master KDoc 里那种「规则看不见某形态」的空绿）。
     *
     * `findChildrenOfType(` 是 Java 侧的**块内重取 PSI** 标记：Kotlin 侧该形态紧跟着
     * `tag.textRange.startOffset` 直读故被上面的区间令牌命中；Java 侧读取下沉到助手
     * （`ConvertToSqlAction` 的 `lineOf(tag)`），块内只剩重取调用可见——不收这个令牌，
     * master 明确登记为豁免的那一形态（文件级捕获 + 块内重取）在 Java 侧会**整片不可见**。
     */
    private val psiReadTokens = listOf(
        "getContainingFile(", "containingFile",
        "getViewProvider(", "viewProvider",
        "getTextRange(", "getTextLength(", ".textRange", ".textLength",
        "findChildrenOfType(",
    )

    /**
     * 两段式校验令牌：池化块内出现其一即视为已走 T-PCP-01 快照校验。
     *
     *  - `PsiSnapshot`——T-PCP-01 工具本体（`PsiSnapshot.capture` / `PsiSnapshot.Validity` 判定）；
     *  - `validatedRecentAnchor(` / `validatedMethodRows(`——两个 gutter 站点的**校验收口助手**
     *    （`ui/gutter` 包内两个 provider 文件，内部先 `validate()` 再读；新增收口助手须登记进本清单）。
     */
    private val psiValidationTokens = listOf("PsiSnapshot", "validatedRecentAnchor(", "validatedMethodRows(")

    /** 豁免记录：[sites] = 提交时该文件的**命中站点数**（漂移即红），[reason] 必填。 */
    private data class PsiCaptureExemption(val sites: Int, val reason: String)

    /**
     * 池化线程内读 PSI 的**豁免登记**（k2j 工作树实测重登记）。
     *
     * 口径：本规则只认「元素级捕获 + 后台读」这一形态（T-PCP-01 的适用面）。豁免条目是
     * **文件级捕获 + 块内重取 PSI**——EDT 侧捕获 `PsiFile`，池化块内 `PsiTreeUtil.findChildrenOfType`
     * 重新取 `XmlTag` 再算行号，失效面由 `ReadAction` + 平台 reparse 语义承担。
     *
     * k2j 站点数与 master 不同（master Kotlin 侧 4 处，k2j Java 侧 **2 处**：`:507` / `:577`）：
     * 另 5 处池化块（`:124` / `:372` / `:411` / `:457` / `:619`）把 PSI 读整体交给
     * `WrapperLocator` / `AnnotationSqlPreviewService` / `*GutterLineMarkerProvider` 等助手，
     * 块内无任何 PSI 读令牌——这是 master KDoc 已登记的**漏报边界**（读被挪进被调函数即脱离扫描面），
     * 不是 k2j 新引入的缺口。
     */
    private val psiCaptureExemptions: Map<String, PsiCaptureExemption> = mapOf(
        "src/main/java/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.java" to
            PsiCaptureExemption(
                3,
                "文件级捕获 + 块内重取 PSI（XmlTag 候选/行号，T-M29.6 / T-Perf-14；O-72 内嵌 SQL 载体候选 +1），非元素级快照；计划未含本文件",
            ),
    )

    /**
     * 池化线程内读 PSI 未走校验的违规点：`路径:行号 → 命中令牌`。
     *
     * 豁免三选一：① 池化块内出现 [psiValidationTokens]（已走两段式校验）；② 调用行上方连续注释块含
     * `psi-capture: <理由>` 标注；③ 文件在 [psiCaptureExemptions] 内。
     *
     * **边界（如实登记，两个方向都有）**：
     *  - **漏报**：PSI 读被挪进池化块**调用的另一个函数**（块内看不见读令牌）即脱离扫描面——
     *    本规则只看池化块本体，与既有的 `invokeLater` 两条规则同口径。k2j 侧该形态是**主流**
     *    （33 个池化站点里 31 个把 PSI 操作交给助手），故本规则的实际判定面只有 2 处。
     *  - **过报**：块内对**块内新解析**的对象读 PSI 也会命中——遇到时应把该读挪出块外或加
     *    `psi-capture:` 标注说明其非捕获元素。
     *  - 覆盖的执行原语只有 `executeOnPooledThread`（k2j 实测 33 处；`runAsync` 的命中全是原生图表 API
     *    `queryUpdate()…runAsync()`——2 处真实调用 + 2 处注释提及，非池化原语，不在扫描面）。
     */
    private fun psiCaptureOffenders(
        sources: List<Pair<String, String>>,
        skipExemptions: Boolean = true,
    ): List<String> {
        val offenders = ArrayList<String>()
        for ((path, raw) in sources) {
            if (skipExemptions && psiCaptureExemptions.containsKey(path)) continue
            for (site in pooledThreadSites(maskSource(path, raw))) {
                val read = psiReadTokens.filter { site.body.contains(it) }
                if (read.isEmpty()) continue
                if (psiValidationTokens.any { site.body.contains(it) }) continue
                if (leadingCommentBlock(raw, site.line).contains("psi-capture:")) continue
                offenders.add("$path:${site.line} → ${read.joinToString("/")}")
            }
        }
        return offenders
    }

    @Test
    fun `池化线程内读PSI须走两段式快照校验或登记豁免`() {
        // T-PsiCaptureProtocol T-PCP-04：EDT 捕获 PsiElement → `executeOnPooledThread` 内读该元素
        // （所属文件 / 文档 / 区间）这条链，捕获与读取之间的写操作会让元素失效——只包 ReadAction
        // 只解决「读权限」，不解决「元素是否仍有效」。本规则要求这类读取走 T-PCP-01 的 PsiSnapshot
        // 校验（或 `psi-capture:` 标注 / 豁免登记）。
        // k2j 落地形态：两个 gutter provider 各自把快照传给池化块内的校验收口助手
        // （`validatedRecentAnchor` / `validatedMethodRows`），`actions/ConvertToSqlAction.java`
        // 2 处登记豁免。
        val sources = rawMainSources()
        val offenders = psiCaptureOffenders(sources)
        assertTrue(
            "池化线程内读捕获的 PSI 却无快照校验，命中 ${offenders.size} 处：$offenders。" +
                "改走 PsiSnapshot.capture（EDT 侧）+ validate()（后台侧），或对确属「文件级捕获 + 块内重取」" +
                "的站点在 [psiCaptureExemptions] 登记文件 + 站点数 + 理由，或加 `// psi-capture: <理由>` 标注",
            offenders.isEmpty(),
        )

        // 扫描面保鲜：池化站点数为 0 时本规则恒绿（k2j 实测 33 处）——空面必须红
        val pooledTotal = sources.sumOf { (path, raw) -> pooledThreadSites(maskSource(path, raw)).size }
        assertTrue(
            "executeOnPooledThread 站点数为 0——扫描面为空会让本规则静默空绿（实测应为 33 处）。" +
                "检查 `executeOnPooledThread` 是否被改名/被平台封装替换，并同步 [pooledThreadSites]",
            pooledTotal > 0,
        )

        // 豁免表保鲜：路径在扫描面内、命中站点数与登记一致、理由非空
        for ((path, exemption) in psiCaptureExemptions) {
            val source = sources.firstOrNull { it.first == path }
            assertTrue(
                "豁免表条目指向不存在的源文件（改名/删除后未同步）：$path",
                source != null,
            )
            val actual = psiCaptureOffenders(listOf(source!!), skipExemptions = false).size
            assertEquals(
                "豁免文件的命中站点数与登记不符（新增/删除跨线程 PSI 读须补校验或重新登记理由）：$path",
                exemption.sites,
                actual,
            )
            assertTrue("豁免理由不得为空：$path", exemption.reason.isNotBlank())
        }
    }

    @Test
    fun `TPCP04自证_池化块读PSI无校验必红_三种放行形态`() {
        // 负向自证（规则「看不见某形态」= 空绿，只能靠合成样本钉住）：违规必报 + 校验/标注/豁免三态放行。
        // k2j 样本一律 Java 形态（回调体在括号内）。
        val violating = listOf(
            "src/main/java/x/BgRead.java" to """
                class BgRead {
                    void go(PsiElement elt) {
                        app.executeOnPooledThread(() -> {
                            ReadAction.compute(() -> elt.getContainingFile().getTextRange().getStartOffset());
                        });
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "池化块内读捕获元素的 PSI 属性必须命中：${psiCaptureOffenders(violating)}",
            1,
            psiCaptureOffenders(violating).size,
        )

        // Java 侧读取下沉到助手的形态（k2j 真实形态）：块内重取 PSI 也必须命中
        val refetch = listOf(
            "src/main/java/x/Refetch.java" to """
                class Refetch {
                    void go(PsiFile file) {
                        app.executeOnPooledThread(() -> {
                            ReadAction.compute(() -> PsiTreeUtil.findChildrenOfType(file, XmlTag.class));
                        });
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "块内从捕获的 PsiFile 重取 PSI（findChildrenOfType）必须命中——否则 Java 侧真实形态整片脱管：" +
                "${psiCaptureOffenders(refetch)}",
            1,
            psiCaptureOffenders(refetch).size,
        )

        val validated = listOf(
            "src/main/java/x/Validated.java" to """
                class Validated {
                    void go(PsiElement elt, Project project, PsiSnapshot snapshot) {
                        app.executeOnPooledThread(() -> {
                            Object anchor = ReadAction.compute(
                                    () -> ValidatedKt.validatedRecentAnchor(project.getBasePath(), snapshot));
                            log(anchor.toString() + elt.getContainingFile().getName());
                        });
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "已走校验收口助手的站点不得误报：${psiCaptureOffenders(validated)}",
            0,
            psiCaptureOffenders(validated).size,
        )

        val inlineSnapshotCheck = listOf(
            "src/main/java/x/InlineCheck.java" to """
                class InlineCheck {
                    void go(PsiElement elt, PsiSnapshot snapshot) {
                        app.executeOnPooledThread(() -> {
                            boolean valid = snapshot != null && snapshot.validate() instanceof PsiSnapshot.Validity.Valid;
                            if (valid) log(elt.getContainingFile().getTextRange().getStartOffset());
                        });
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "块内直接用 PsiSnapshot 判定的站点不得误报：${psiCaptureOffenders(inlineSnapshotCheck)}",
            0,
            psiCaptureOffenders(inlineSnapshotCheck).size,
        )

        val annotated = listOf(
            "src/main/java/x/Annotated.java" to """
                class Annotated {
                    void go(PsiElement elt) {
                        // psi-capture: 该元素在块内重新解析（非 EDT 捕获），失效面由平台 reparse 承担
                        app.executeOnPooledThread(() -> {
                            log(elt.getContainingFile().getName());
                        });
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "带 `// psi-capture: <理由>` 标注的站点应豁免：${psiCaptureOffenders(annotated)}",
            0,
            psiCaptureOffenders(annotated).size,
        )

        // 豁免表生效（用真实豁免路径喂合成样本，证明表被真正查过而不是白名单空转）
        val exemptedPath = "src/main/java/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.java"
        val exempted = listOf(
            exemptedPath to """
                class Exempted {
                    void go(PsiFile file) {
                        app.executeOnPooledThread(() -> {
                            ReadAction.compute(() -> PsiTreeUtil.findChildrenOfType(file, XmlTag.class));
                        });
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "豁免路径的命中站点须被计数（否则豁免表空转、漂移检测失效）：" +
                "${psiCaptureOffenders(exempted, skipExemptions = false)}",
            1,
            psiCaptureOffenders(exempted, skipExemptions = false).size,
        )
        assertEquals(
            "豁免表内的路径不得报违规：${psiCaptureOffenders(exempted)}",
            0,
            psiCaptureOffenders(exempted).size,
        )

        // 池化块内不读 PSI（纯数据/纯 IO）不得命中
        val noPsiRead = listOf(
            "src/main/java/x/NoPsi.java" to """
                class NoPsi {
                    void go(String path) {
                        app.executeOnPooledThread(() -> {
                            String text = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
                            log(text);
                        });
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "池化块内无 PSI 读不得命中：${psiCaptureOffenders(noPsiRead)}",
            0,
            psiCaptureOffenders(noPsiRead).size,
        )

        // 工具函数自证：Java 形态（括号内 lambda）站点提取 + 行号与原文对齐（保行掩码）
        val sites = pooledThreadSites(maskSource(violating[0].first, violating[0].second))
        assertEquals("executeOnPooledThread 的 Java 括号内 lambda 形态应被提取：$sites", 1, sites.size)
        assertEquals("提取行号须与原文对齐（保行掩码）", 3, sites[0].line)
        assertTrue(
            "Java 侧回调体落在实参区——body 必须覆盖实参区（只看尾随 lambda 会得到空 body，规则恒绿）：" +
                sites[0].body.take(80),
            sites[0].body.contains("getContainingFile("),
        )
    }

    // ------------------------------------------------------------------
    // 跳转族读权限约定（T-PsiCaptureProtocol T-PCP-03，2026-09-19）
    // ------------------------------------------------------------------

    /** 跳转族调用令牌：读平台模型（ModuleManager / LocalFileSystem），调用方须持读权限。 */
    private val jumpFamilyCallTokens = listOf(
        "SourcePathResolver.resolveVirtualFile(",
        "EntryJumpResolver.resolve(",
        "EntryJumpResolver.resolveCandidates(",
    )

    /**
     * 跳转族调用点的**读权限白名单**（EDT 直读；新增调用点须显式包 `ReadAction`，否则守卫红）。
     *
     * 本族读 `ModuleManager`/`LocalFileSystem` 平台模型（**不碰 PSI/Document**），故不适用 T-PCP-01
     * 的元素快照——要的是「调用方持读权限」这条约定：EDT 直读合法，后台调用必须自包 `ReadAction`
     * （`log/CallerMethodRangeResolver.java` 即后者先例，文件内含 `ReadAction` 故自动通过）。
     *
     * k2j 实测（`grep` 5 个调用点 / 4 个文件）：`CallerMethodRangeResolver.java`（1 处）/
     * `EntryJumpResolver.java`（2 处）/ `SqlLogPanel.java`（1 处）三个文件内已含 `ReadAction` 自动通过；
     * **只有 `HistorySqlLogPanel.java`（1 处）需要显式加白**（EDT 右键跳转，平台菜单回调在 EDT）。
     */
    private val jumpFamilyReadAllowedFiles = setOf(
        // 族本体：内部转发，读权限由调用方保证（现网调用方均在 EDT）
        "src/main/java/cn/song/mybatisplus/visualizer/log/EntryJumpResolver.java",
        // O-119 跳转原语收口后的族本体（resolveJumpTarget/resolveXmlJumpTarget 静态承载；
        // 调用点=SqlLogPanel/HistorySqlLogPanel/RecentExecutionsDialog 的 EDT 菜单回调链）
        "src/main/java/cn/song/mybatisplus/visualizer/log/LogEntryJumpOps.java",
        // EDT 右键跳转（平台菜单回调在 EDT）
        "src/main/java/cn/song/mybatisplus/visualizer/log/SqlLogPanel.java",
        "src/main/java/cn/song/mybatisplus/visualizer/log/HistorySqlLogPanel.java",
    )

    /** 跳转族调用点缺读权限的违规文件（无 `ReadAction` 且不在白名单）。 */
    private fun edtReadContractOffenders(sources: List<Pair<String, String>>): List<String> =
        sources
            .filter { (_, code) -> jumpFamilyCallTokens.any { code.contains(it) } }
            .filter { (_, code) -> !code.contains("ReadAction") }
            .map { (path, _) -> path }
            .filter { it !in jumpFamilyReadAllowedFiles }

    @Test
    fun `跳转族调用点须持读权限_后台调用须包ReadAction`() {
        // T-PCP-03：`EntryJumpResolver`/`SourcePathResolver` 一族的 KDoc 约定（「调用方保证持有读权限」）
        // 原先无工具保障——一旦被后台线程裸调，读 ModuleManager 的违约只在真机偶发。本规则把约定固化为
        // 「文件内出现 ReadAction（自包权限）或在 EDT 调用者白名单内」；配套运行期硬断言见下一段。
        val sources = rawMainSources()
        // 扫描面保鲜：调用点全被改名/收编后本规则恒绿——至少要有族本体在扫描面内
        val callers = sources.filter { (_, code) -> jumpFamilyCallTokens.any { code.contains(it) } }
        assertTrue(
            "跳转族调用点在 src/main/java 一处也找不到——扫描面为空会让本规则静默空绿（实测 5 个调用点 / " +
                "4 个文件）。核对 [jumpFamilyCallTokens] 与生产侧的调用形态是否已改名",
            callers.isNotEmpty(),
        )
        val offenders = edtReadContractOffenders(mainSources())
        assertTrue(
            "跳转族调用点缺读权限（文件内无 ReadAction 且不在 EDT 白名单），命中 ${offenders.size} 处：$offenders。" +
                "后台调用须显式包 ReadAction，EDT 调用点加入 [jumpFamilyReadAllowedFiles] 并注明依据",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `TEDTRC自证_跳转族调用点无ReadAction必红_白名单与自包权限放行`() {
        // 独立复核㉗：`edtReadContractOffenders` 此前**只有主用例直接引用、无合成样本**（被元守卫登记为
        // 「受机器保护的豁免」——缺口因此不可见）。此处关闭该缺口：三态各归其位。
        // k2j 移植注：合成样本一律 Java 形态（.java 路径）；白名单样本对齐 k2j 白名单文件
        // log/SqlLogPanel.java（master 为 SqlLogPanel.kt）。
        val violating = listOf(
            "src/main/java/cn/x/BadJump.java" to """
                class BadJump {
                    void go(Project project, Module module) {
                        SourcePathResolver.resolveVirtualFile(project, module);
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "文件内无 ReadAction 的跳转族调用点必须被命中：${edtReadContractOffenders(violating)}",
            listOf("src/main/java/cn/x/BadJump.java"),
            edtReadContractOffenders(violating),
        )

        val wrapped = listOf(
            "src/main/java/cn/x/WrappedJump.java" to """
                class WrappedJump {
                    void go(Project project, PsiElement element) {
                        ReadAction.compute(() -> EntryJumpResolver.resolve(project, element));
                    }
                }
            """.trimIndent(),
        )
        assertTrue(
            "自包 ReadAction 的调用点应放行：${edtReadContractOffenders(wrapped)}",
            edtReadContractOffenders(wrapped).isEmpty(),
        )

        val whitelisted = listOf(
            "src/main/java/cn/song/mybatisplus/visualizer/log/SqlLogPanel.java" to """
                class SqlLogPanel {
                    void go(Project project, PsiElement element) {
                        EntryJumpResolver.resolveCandidates(project, element);
                    }
                }
            """.trimIndent(),
        )
        assertTrue(
            "EDT 白名单文件应放行（白名单不得失效，否则主用例会因过报而红）：${edtReadContractOffenders(whitelisted)}",
            edtReadContractOffenders(whitelisted).isEmpty(),
        )
    }

    @Test
    fun `跳转族读权限运行期断言锚点`() {
        // 运行期硬断言锚点（T-PCP-03）：`SourcePathResolver.resolveVirtualFile` 入口必须保留
        // `assertReadAccessAllowed()`——源码级规则只管「调用点有没有 ReadAction 字样」，
        // 断言把违约暴露在实际调用链上（后台裸调即抛，不静默读模型）。
        //
        // k2j 移植注：master 的锚点文件是 `log/EntrySourceMatcher.kt`（Kotlin 侧 `SourcePathResolver`
        // 与该文件同名不同物，锚点落在其中）；Java 侧 `SourcePathResolver` 独立成文件，锚点随之下沉到
        // `log/SourcePathResolver.java`（同本文件类 KDoc 移植注 2 的「顶层对象独立成文件」模式）。
        val code = stripComments(
            repoRoot().resolve("src/main/java/cn/song/mybatisplus/visualizer/log/SourcePathResolver.java").readText(),
        )
        assertTrue(
            "SourcePathResolver.resolveVirtualFile 丢失 assertReadAccessAllowed() 入口断言——" +
                "跳转族的读权限约定退回「只写在 KDoc 里」的靠约定状态（T-PCP-03）",
            code.contains("assertReadAccessAllowed()"),
        )
    }

    // ------------------------------------------------------------------
    // 项目服务级状态复位（T-StabilityTail T-ST-02d，2026-09-19）
    // ------------------------------------------------------------------

    /**
     * src/test 源码（**原文**，不剥注释——`// project-state:` 豁免标注要在原文上认；掩码由调用方
     * 按需生成）。T-TestPerf6 起读快照。扫描 `src/test/kotlin` + `src/test/java`
     * （k2j 的测试树是 `.kt` 为主、`.java` 混放）。本守卫自身排除在外（其合成样本与报错文案含被扫片段）。
     *
     * 与剥注释版 [mainSources]（src/main 快照）的口径差异：既有两条测试树规则做的是「片段是否出现」判定，
     * 剥注释可避免注释里的字样误报；本族要认**注释里的豁免标注**并做掩码配平，必须在原文上跑。
     */
    private fun testSources(): List<Pair<String, String>> =
        srcTestKotlinTree
            .filter { (f, _) -> f.extension == "kt" || f.extension == "java" }
            .filter { (f, _) -> f.name != "BannedApiSourceScanTest.kt" }
            .map { (f, text) -> f.relativeTo(repoRoot()).path.replace('\\', '/') to text }

    /**
     * 扫描面保鲜断言：路径解析失败（`src/test/kotlin` 改名 / `user.dir` 异常）时 [testSources] 返回
     * **空列表**，而规则对空列表恒 0 违规 → 主用例会**静默空绿**（T-ST-02d 独立复核条件项）。
     * k2j 历史上出过「扫描根不匹配 → 命中 0 文件 → 断言恒真」的静默假绿，故本断言是本族的第一道闸。
     * 空面自证见 `TST02d自证_…`（空列表必须让本断言变红）。
     */
    private fun assertTestSourcesResolved(sources: List<Pair<String, String>>) {
        assertTrue(
            "扫描面为空——src/test/kotlin 与 src/test/java 下未解析到任何 .kt/.java（路径改名 / user.dir 异常？），" +
                "空扫描面会让本用例静默空绿：" +
                "root=${repoRoot().resolve("src/test/kotlin")} + ${repoRoot().resolve("src/test/java")}",
            sources.isNotEmpty(),
        )
    }

    /**
     * 初始化入口站点：`setUp` / `@Before` 的**函数体**（含花括号）。
     *
     * [body] 与 [bodyStart] 取自 [maskSource] 后的文本（**保行**）：体在掩码文本中的起始下标 + 体内容，
     * 命中下标回推行号即 `code.take(bodyStart + offset)` 的换行数——行号与原文一致，可直接回喂
     * [leadingCommentBlock] 认 `// project-state:` 标注。
     */
    private data class InitEntrySite(val name: String, val line: Int, val body: String, val bodyStart: Int)

    /** 初始化入口站点：`setUp` / `@Before` / `@BeforeEach`（T-ST-02 族判据用）。 */
    private fun initEntrySites(code: String): List<InitEntrySite> =
        entrySites(code, "setUp", "Before|BeforeEach")

    /**
     * 收尾入口站点：`tearDown` / `@After` / `@AfterEach`（T-AgentResiduals R2 的
     * 「收尾窗口不得取服务」规则用）。提取口径与 [initEntrySites] 同源（同一份 [entrySites]），
     * Kotlin 与 Java 双声明形态同样都认，只换函数名与注解词。
     */
    private fun teardownEntrySites(code: String): List<InitEntrySite> =
        entrySites(code, "tearDown", "After|AfterEach")

    /**
     * 扫出入口站点（函数名 [funName] / 注解词 [annotationWords] 参数化），**Kotlin 与 Java 两种声明形态都认**
     * （k2j 测试树混放）：
     *  ① `override fun <名>()`（Kotlin，平台测试基类形态，仓内主流）；
     *  ② `protected void <名>() throws Exception`（Java，`super.<名>()` 调用点因缺少返回类型声明
     *     不会被误认）；
     *  ③ `@<注解>`（含全限定）标注的入口——Kotlin 取 `fun <名>(`，Java 取 `<名>(` 的**方法名紧邻 `(`**
     *     形态（不是「注解后第一个标识符」，否则 `@Before public void init()` 会把名字取成 `public`）。
     *
     * 定位 `(参数)` 与 `{体}` 都跑在 [maskSource] 后的文本上（注释/字符串里的括号不参与配平，
     * 与 [invokeLaterSites] / [pooledThreadSites] 同口径）。
     *
     * 已知边界（漏报方向）：注解与方法之间夹了带括号的注解（`@Before @SuppressWarnings("x") void init()`）
     * 会把名字取成 `SuppressWarnings`——体仍取对（`functionBody` 落在同一个花括号上），只影响入口名。
     */
    private fun entrySites(code: String, funName: String, annotationWords: String): List<InitEntrySite> {
        val out = ArrayList<InitEntrySite>()
        val seenBodyStarts = HashSet<Int>()
        fun add(name: String, body: IntRange) {
            if (!seenBodyStarts.add(body.first)) return
            out.add(
                InitEntrySite(name, lineOf(code, body.first), code.substring(body.first, body.last + 1), body.first),
            )
        }
        // ① Kotlin <funName>
        for (m in Regex("""(?<![A-Za-z0-9_])fun\s+$funName\s*\(""").findAll(code)) {
            functionBody(code, m.range.last)?.let { add(funName, it) }
        }
        // ② Java <funName>（返回类型声明形态；`super.<funName>()` 无 `void` 前缀故不误认）
        for (m in Regex("""(?<![A-Za-z0-9_])void\s+$funName\s*\(""").findAll(code)) {
            functionBody(code, m.range.last)?.let { add(funName, it) }
        }
        // ③ 注解入口（Kotlin `fun <名>(` / Java `<名>(`，取更靠前的那个）
        val annotationPattern = Regex("""@(?:[A-Za-z_][A-Za-z0-9_]*\.)*(?:$annotationWords)\b""")
        val kotlinFunPattern = Regex("""(?<![A-Za-z0-9_])fun\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val javaMethodPattern = Regex("""(?<![A-Za-z0-9_.])([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        for (m in annotationPattern.findAll(code)) {
            val from = m.range.last + 1
            val kotlinMatch = kotlinFunPattern.find(code, from)
                ?.takeIf { it.range.first - m.range.last <= 300 }
            val javaMatch = javaMethodPattern.find(code, from)
                ?.takeIf { it.range.first - m.range.last <= 300 }
            val picked: Pair<Int, String>? = when {
                kotlinMatch != null && javaMatch != null ->
                    if (kotlinMatch.range.first <= javaMatch.range.first) {
                        (kotlinMatch.range.last + 1) to kotlinMatch.groupValues[1]
                    } else {
                        javaMatch.range.last to javaMatch.groupValues[1]
                    }
                kotlinMatch != null -> (kotlinMatch.range.last + 1) to kotlinMatch.groupValues[1]
                javaMatch != null -> javaMatch.range.last to javaMatch.groupValues[1]
                else -> null
            }
            if (picked == null) continue
            functionBody(code, picked.first)?.let { add(picked.second, it) }
        }
        return out
    }

    /** 1-based 行号（掩码文本保行，与原文一致）。 */
    private fun lineOf(code: String, index: Int): Int = code.take(index).count { it == '\n' } + 1

    /**
     * 从 [from] 起定位 `(参数)` + `{体}`，返回体下标区间；[maxGap] 内找不到或配不平返回 null。
     *
     * [from] 是**待扫起点**（`(` 本身或其后一位），不是定长偏移：Java 方法声明在 `)` 与 `{` 之间
     * 可以夹 `throws Exception`，故这里靠「第一个 `(` → 配平 → 其后第一个 `{`」定位而非等宽匹配。
     *
     * 表达式体函数（`@Before fun setUp() = ErrorFingerprint.reset()`，Kotlin 侧 1 处）**不取体**：
     * 其 `)` 与下一个 `{` 之间隔着 `= …`，若不拦就会把**下一个函数的花括号**当成它的体
     * （扫错区域＝把别人的写记到这个入口名下）。Java 的 `throws` 子句不含 `=`，不受此拦影响。
     */
    private fun functionBody(code: String, from: Int, maxGap: Int = 400): IntRange? {
        var i = from
        while (i < code.length && i - from <= maxGap && code[i] != '(' && code[i] != '{') i++
        if (i >= code.length || code[i] != '(') return null
        val closeParen = matchingDelimiter(code, i, '(', ')')
        if (closeParen < 0) return null
        var j = closeParen + 1
        while (j < code.length && j - closeParen <= maxGap && code[j] != '{') j++
        if (j >= code.length || code[j] != '{') return null
        val gap = code.substring(closeParen + 1, j)
        if (gap.contains('=') || gap.contains("fun ")) return null
        val close = matchingDelimiter(code, j, '{', '}')
        return if (close >= 0) j..close else null
    }

    /**
     * 服务查找调用令牌：平台直取 `project.getService(`，以及仓内诊断 wrapper
     * `AlreadyDisposedDiagnostics.projectService(`（仓内测试的**主流**取服务形态——wrapper 的形参是
     * `(project, serviceClass, trigger)`、返回 `T`，与 `getService` 同形承接）。
     *
     * 为什么两个都要认（T-ST-02d 独立复核**阻断 1**）：已修泄漏类里 `getService(` 命中数为 **0**
     * （全走 `projectService(...)`）——只认 `getService(` 时规则对这些类**整类不可见**，
     * 把真泄漏类的 `restore` 行删掉仍绿（复核实证）。两个令牌不互相包含，故各自独立搜索。
     */
    private val serviceLookupTokens = listOf("getService(", "projectService(")

    /**
     * 服务对象绑定名：初始化入口里**直接承接**服务查找返回值的标识符，两种形态都认——
     *  ① `manager = project.getService(SqlLogManager::class.java)` / Java
     *     `manager = project.getService(SqlLogManager.class)` → `manager`；
     *  ② `manager = AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.manager")`
     *     → `manager`（wrapper 形态，仓内主流；wrapper 的 trigger 实参在掩码文本里已涂白）。
     *
     * Java 的**声明并赋值**形态（`SqlLogManager manager = project.getService(…)`）同样命中：
     * 判据只要求「赋值号左侧是一个标识符、右侧到查找调用前只有一个接收者前缀」，与声明类型无关。
     *
     * 只认「赋值号与查找调用之间除接收者外没有别的调用」的形态——
     * `snapshot = SqlLogManagerProjectState.capture(project.getService(…))` 承接的是**包装结果**（快照）
     * 而非服务本体，不登记；换行形态的 wrapper（`state = SqlLogManagerProjectState.capture(` 换行后才是
     * `projectService(…)`）承接行只有空白，正则天然不匹配——这也是**有意**的（承接的是快照）。
     * 直取形态（`project.getService(…).field = x`）另由固定锚点覆盖。
     *
     * 边界（漏报方向）：多行赋值（`val x =` / `SqlLogManager x =` 换行后才是查找调用）不识别。
     */
    private fun serviceBoundNames(body: String): List<String> {
        val names = ArrayList<String>()
        for (token in serviceLookupTokens) {
            var idx = body.indexOf(token)
            while (idx >= 0) {
                val stmtStart = body.lastIndexOfAny(charArrayOf('\n', ';'), idx - 1) + 1
                val head = body.substring(stmtStart, idx)
                val m = Regex("""(?:^|[^A-Za-z0-9_.])([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]*)?=\s*([A-Za-z0-9_.\s]*)$""")
                    .find(head)
                if (m != null && m.groupValues[2].trim().endsWith(".")) names.add(m.groupValues[1])
                idx = body.indexOf(token, idx + 1)
            }
        }
        return names.distinct()
    }

    /**
     * 服务写操作令牌（会改共享实例状态的方法名，正则片段）：`clear()` / `setXxx()` / `startXxx()` /
     * `addXxx()` …。
     *
     * 名单按仓内既有泄漏点的**形态**取：`entries.dedupeEnabled = false` 是赋值形态（另由成员赋值规则管），
     * `setCapturingForTest(false)` / `startCapture()` / `entries.clear()` 是调用形态。名单是**过报方向**
     * 的可调项：业务方法名若与 `addXxx/removeXxx/registerXxx/markXxx` 撞名会被命中，此时加
     * `// project-state: <理由>` 标注放行（见 [projectServiceStateOffenders]）。
     *
     * 名单里**不含** `get[A-Z]…`（Java 侧属性读普遍写作 getter，收进来会把只读访问整片误报）。
     */
    private val serviceMutatorNamePattern =
        "clear|clearAll|clearForTest|reset|resetForTest|restore|loadState|" +
            "set[A-Z][A-Za-z0-9_]*|start[A-Z][A-Za-z0-9_]*|stop[A-Z][A-Za-z0-9_]*|" +
            "enable[A-Z][A-Za-z0-9_]*|disable[A-Z][A-Za-z0-9_]*|toggle[A-Z][A-Za-z0-9_]*|" +
            "add[A-Z][A-Za-z0-9_]*|remove[A-Z][A-Za-z0-9_]*|put[A-Z][A-Za-z0-9_]*|" +
            "mark[A-Z][A-Za-z0-9_]*|record[A-Z][A-Za-z0-9_]*|register[A-Z][A-Za-z0-9_]*|" +
            "unregister[A-Z][A-Za-z0-9_]*|attach[A-Z][A-Za-z0-9_]*|detach[A-Z][A-Za-z0-9_]*"

    /**
     * 服务写操作命中：`List<命中下标（相对 [body]） → 命中片段>`。
     *
     * [anchor] 是「服务表达式」的正则：绑定名形态带 `(?<![A-Za-z0-9_.])` 前置（防 `xmanager.` 误配），
     * 或直取形态 `getService(…).`。两种写形态：①成员赋值（`manager.entries.dedupeEnabled = false`，
     * 以 `(?!=)` 排除 `==`）；②变更类方法调用（`manager.setCapturingForTest(true)` / `manager.entries.clear()`）。
     * 链式成员（`manager.entries.dedupeEnabled`）由中间链片段吞掉——泄漏点常写在 buffer 字段上。
     */
    private fun serviceWriteHits(body: String, anchor: String): List<Pair<Int, String>> {
        val chain = """(?:[A-Za-z_][A-Za-z0-9_]*\s*\.\s*)*"""
        val out = ArrayList<Pair<Int, String>>()
        for (m in Regex("""$anchor$chain[A-Za-z_][A-Za-z0-9_]*\s*(?:=|\+=|-=)(?!=)""").findAll(body)) {
            out.add(m.range.first to m.value.replace(Regex("\\s+"), " "))
        }
        for (m in Regex("""$anchor$chain(?:$serviceMutatorNamePattern)\s*\(""").findAll(body)) {
            out.add(m.range.first to m.value.replace(Regex("\\s+"), " "))
        }
        return out.distinct()
    }

    /**
     * 服务**自带**复位口的方法名（**只在与承接名配对时才算复位**，见 [hasBoundServiceReset]）。
     *
     * 名单按「服务对象自身的复位入口」取：`clearForTest` / `resetForTest` / `clearAll` / `loadState`。
     * 名单是过报方向的可调项——某服务的业务方法若与这些名字撞名且被当成复位口，会**漏报**该类的写，
     * 发现时收窄本名单。
     */
    private val projectStateResetMethodPattern = "clearForTest|clearAll|resetForTest|loadState"

    /**
     * 存复原 wrapper（本轮唯一的**全量**复位口）：`SqlLogManagerProjectState.restore(<快照名>)`。
     *
     * **T-AgentResiduals R2 起为单参形态**：`restore(snapshot)`——快照自持服务引用，`tearDown` 不再
     * `project.getService(…)`（那正是 `temporarily-disposed` 窗口发起失败创建、把服务永久毒化、
     * 同片后续取用点成片变红的高发形态，T-ST-02b2 已证）。旧的 `restore(<承接名>, snapshot)` 双参形态
     * **已随 API 删除**，本判据不再认它（认一个已不存在的形态等于给自己留一条永不触发的绿灯）。
     *
     * 刻意**不含** `SqlLogManagerProjectState.capture(`——只有 capture 没有 restore 正是「存了快照忘了还原」
     * 的半成品（capture 只登记快照名，见 [snapshotBindings]；豁免仍必须由 restore 触发）；
     * 也**不含** `SqlLogManager.restoreCaptureStateForTest(`——它只回滚
     * `capturing` / `userArmed` / `waitingForConsole` 三个字段，而 [SqlLogManagerProjectState.capture] 的快照是
     * **16 个字段**（buffer 的 `dedupeEnabled` / `paused` / `filterKeywords` / `maxEntries` / 条目内容 /
     * 轮询态…全不在内），**3/16 的部分复位不足以豁免**对共享实例的写（T-ST-02d 独立复核条件项）。
     * 排除的代价为零：仓内无测试类单独依赖它（唯一直调在 [SqlLogManagerProjectState] 的 restore 体内，
     * 该文件无初始化入口故不在判定面）。只做部分复位而不走 wrapper 全量复位的类会红——补 wrapper
     * 或加 `// project-state: <理由>` 标注。
     */
    private val projectStateRestoreWrapper = "SqlLogManagerProjectState.restore"

    /** 存快照入口（只用于**绑定快照名**，本身不算复位证据——见 [projectStateRestoreWrapper]）。 */
    private val projectStateCaptureCall = "SqlLogManagerProjectState.capture"

    /**
     * 初始化入口里的「快照名 → capture 实参文本」绑定（R2 单参 restore 的判据依据）。
     *
     * 形态：`<快照名> = SqlLogManagerProjectState.capture(<实参>)`（`val` / `private val` / `var` 均可，
     * 实参可跨行）。实参文本用 [matchingDelimiter] 配平取全，供调用方判定「这个快照是从**共享服务**
     * 捕获的」（实参含承接名或服务查找调用），而不是从别的对象 / 自建实例捕获的。
     *
     * 边界（漏报方向）：赋值目标与 capture 调用之间夹别的东西（如 `a = b = capture(…)`）不识别；
     * 快照名与承接名同名（`manager = capture(manager)`）时两者都登记，判定仍正确。
     */
    private fun snapshotBindings(body: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var idx = body.indexOf(projectStateCaptureCall)
        while (idx >= 0) {
            val open = idx + projectStateCaptureCall.length
            val close = if (open < body.length && body[open] == '(') matchingDelimiter(body, open, '(', ')') else -1
            if (close >= 0) {
                val stmtStart = body.lastIndexOfAny(charArrayOf('\n', ';'), idx - 1) + 1
                val head = body.substring(stmtStart, idx)
                // 赋值目标紧贴 capture 调用（`val state = ` / `serviceState = ` 两种写法都认）
                val m = Regex("""(?:^|[^A-Za-z0-9_.])([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]*)?=\s*$""").find(head)
                if (m != null) out.add(m.groupValues[1] to body.substring(open, close + 1))
            }
            idx = body.indexOf(projectStateCaptureCall, idx + 1)
        }
        return out
    }

    /**
     * 复位证据判定（T-ST-02d 独立复核**阻断 2** 的修法 + T-AgentResiduals R2 形态变更）：
     * **绑定到承接服务对象的那个标识符**（或其快照），而非文件级 `contains`。任一形态成立即视为有复位：
     *  ① `<承接名>.<复位口>(…)`——对该服务对象自身的复位调用（[projectStateResetMethodPattern]）；
     *  ② `SqlLogManagerProjectState.restore(<快照名>)`——R2 单参存复原 wrapper，其中
     *     `<快照名>` 须在初始化入口里由 `SqlLogManagerProjectState.capture(<共享服务表达式>)` 绑定
     *     （[snapshotBindings]；实参含承接名或服务查找调用才算「共享服务」）。
     *
     * 为什么必须绑定：旧口径是「文件里出现任一复位令牌即豁免」，于是 `PluginSettings.loadState(snapshot)` /
     * `JoinFkRegistry.clearForTest()` / `PluginCacheManager.clearAll(project)` / `FirstCaptureGuide.resetForTest()`
     * 这类**另一个对象**的复位会把真泄漏静默洗成绿（复核实测四个样本全绿）。绑定后这些调用不再豁免；
     * 同时**不丢覆盖面**——服务自身确实有该复位口时仍认（如 `RollbackSnapshotStore.clearForTest`）。
     *
     * R2 把绑定从 restore 点**前移到 capture 点**（restore 不再带承接名）：`restore(<未绑定名>)` /
     * `restore(<从别处捕获的快照名>)` 都不算证据——自证样本 `TST02d自证_…` 两条负例钉住这一点。
     *
     * 残留边界（漏报方向，如实登记）：按**名**匹配不按实例——同文件两个顶层类用同名标识符时，
     * A 类的复位会豁免 B 类的写（实测：仓内含 >1 顶层声明的测试文件 6 个，但**每个文件里带初始化入口
     * 的顶层类都只有 1 个**（0 个文件存在两个入口类），故当前无实际影响）；
     * 承接名作为**实参**传给别的对象的复位口（`SomeRegistry.clearForTest(manager)`）也按「该对象参与复位」
     * 收编为复位。两种形态都要求承接名在**初始化入口**里被登记过（见 [serviceBoundNames] 的边界）。
     */
    private fun hasBoundServiceReset(
        code: String,
        boundNames: Collection<String>,
        snapshots: List<Pair<String, String>>,
    ): Boolean {
        for (name in boundNames) {
            val id = Regex.escape(name)
            if (Regex("""(?<![A-Za-z0-9_.])$id\s*\.\s*(?:$projectStateResetMethodPattern)\s*\(""").containsMatchIn(code)) {
                return true
            }
        }
        for ((snapshotName, captureArg) in snapshots) {
            // 快照必须是从**共享服务**捕获的：实参里出现承接名，或直接是服务查找调用
            val fromSharedService = boundNames.any { captureArg.contains(it) } ||
                serviceLookupTokens.any { captureArg.contains(it) }
            if (!fromSharedService) continue
            if (Regex(
                    """(?<![A-Za-z0-9_.])${Regex.escape(projectStateRestoreWrapper)}\s*\(\s*""" +
                        """${Regex.escape(snapshotName)}\s*[,)]""",
                ).containsMatchIn(code)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * 初始化入口里改**项目服务级**共享状态却无复位证据的违规点：`路径:行号 → 命中片段`。
     *
     * **判据（T-ST-02d）**：某测试类的 `setUp`/`@Before` 体内出现对服务查找
     * （`project.getService(…)` 或 `AlreadyDisposedDiagnostics.projectService(…)`）返回对象的写操作
     * （成员赋值或变更类方法调用），而**类内没有对该承接名的复位调用**（见 [hasBoundServiceReset]）→ 红。
     * 单独成函数以便合成样本自证（见 `TST02d自证_…`）。
     *
     * **豁免**：写入行上方连续注释块含 `// project-state: <理由>`（与 `write-safe:` / `psi-capture:` 同族逃生舱）。
     *
     * **覆盖面**：规则对**任意**服务生效（`getService(SqlPreviewService.class)` 同样在扫描面）。
     *
     * **为什么值得一条守卫**：light 测试项目在同一分片 JVM 内**跨测试类复用同一个 `ProjectImpl`**，
     * 故 `project.getService(SqlLogManager.class)` 是**全片共享**单例；人工审计的复位结论会随代码演进
     * 失效——新增测试类即重排分片邻居，泄漏是否发作取决于邻居，红总是「本类隔离跑绿、分片跑红」。
     *
     * **已知边界**（如实登记，两个方向都有）：
     *  - **漏报｜扫描面只含初始化入口**：写落在测试方法体或 helper 函数里不在扫描面（历史泄漏中
     *    `McpHistoryIngestTest` 的 `recordMcpExecution` 属此形态，靠该类的 capture/restore 兜住）。
     *    收编到「类内任意写」的代价：复位令牌是**类级**判定，靠 `try/finally` 重赋值复位的合法类
     *    会被误判（该形态仓内已有：`AgentListenSyncTest` 的 `finally` 分支），故本轮不收编。
     *  - **漏报｜绑定形态**：多行赋值、helper 返回（`private fun store() = project.getService(…)`）不绑定；
     *    换行形态的 wrapper（`state = SqlLogManagerProjectState.capture(` 换行后才是 `projectService(…)`）
     *    不登记为**服务承接名**是有意的（承接的是快照而非服务本体），该形态由 [snapshotBindings] 收编：
     *    capture 实参含服务查找调用即视为「快照来自共享服务」，`restore(<该快照名>)` 算复位。
     *  - **漏报｜复位判定按名匹配**：复位须落在承接名上，但按**名**不按实例。
     *  - **漏报｜部分复位不算证据**：`SqlLogManager.restoreCaptureStateForTest(…)` 只回滚 3/16 字段，
     *    已在 [projectStateRestoreWrapper] 处排除。
     *  - **不判（非误报）**：自建实例（`new SqlLogManager(project)` 不进容器）无服务查找承接 → 不绑定；
     *    只读访问（`entries.snapshot()` / `entries.size()` / `manager.isCapturing()`）不命中两种写形态。
     *  - **过报方向**：业务方法名与 [serviceMutatorNamePattern] 撞名且类内确无复位 → 红，此时加
     *    `// project-state: <理由>` 标注。**精度证据只能来自合成样本**（`TST02d自证_…`）——
     *    真实源码上的「0 处」是**空绿**，不构成精度证据，不得拿来论证低误报率。
     */
    private fun projectServiceStateOffenders(sources: List<Pair<String, String>>): List<String> {
        val offenders = ArrayList<String>()
        for ((path, raw) in sources) {
            val code = maskSource(path, raw)
            val writes = ArrayList<Pair<Int, String>>()
            val boundNames = LinkedHashSet<String>()
            val snapshots = ArrayList<Pair<String, String>>()
            for (site in initEntrySites(code)) {
                val names = serviceBoundNames(site.body)
                boundNames += names
                snapshots += snapshotBindings(site.body)
                val anchors = names.map { """(?<![A-Za-z0-9_.])""" + Regex.escape(it) + """\s*\.\s*""" } +
                    listOf(
                        """(?<![A-Za-z0-9_])getService\s*\([^()]*\)\s*\.\s*""",
                        """(?<![A-Za-z0-9_])projectService\s*\([^()]*\)\s*\.\s*""",
                    )
                for (anchor in anchors) {
                    for ((offset, hit) in serviceWriteHits(site.body, anchor)) {
                        writes.add((site.bodyStart + offset) to hit)
                    }
                }
            }
            if (writes.isEmpty()) continue
            if (hasBoundServiceReset(code, boundNames, snapshots)) continue
            for ((offset, hit) in writes) {
                val line = lineOf(code, offset)
                if (leadingCommentBlock(raw, line).contains("project-state:")) continue
                offenders.add("$path:$line → $hit")
            }
        }
        return offenders
    }

    /**
     * 服务承接名绑定面（`路径:行号 → 承接名`）——防「绑定逻辑退化 → 无 writes → offenders 恒 0」的
     * **静默空绿**：k2j 实测 3 处（`EpBridgeSinglePathTest` / `SqlConsoleBridgeFilterProviderTest` /
     * `PreviewEndToEndTest`）。绑定面一旦为空，本族的判据整体失效而绿灯照旧，故主用例断言其非空。
     */
    private fun serviceBindingSurface(sources: List<Pair<String, String>>): List<String> {
        val out = ArrayList<String>()
        for ((path, raw) in sources) {
            val code = maskSource(path, raw)
            for (site in initEntrySites(code)) {
                for (name in serviceBoundNames(site.body)) out.add("$path:${site.line} → $name")
            }
        }
        return out
    }

    @Test
    fun `初始化入口改项目服务级状态须复位`() {
        // T-StabilityTail T-ST-02d：把「改共享服务状态须复位」这条人工审计结论固化为源码扫描守卫。
        // 判据与误报边界见 [projectServiceStateOffenders] 的 KDoc。
        val sources = testSources()
        // 路径解析失败 = 空扫描面 = 规则恒 0 违规的**空绿**（自证见 TST02d自证_… 的空面样本）
        assertTestSourcesResolved(sources)
        // 初始化入口提取面保鲜：入口为 0（正则口径与测试声明形态不匹配）时本规则同样恒绿
        val entries = sources.sumOf { (path, raw) -> initEntrySites(maskSource(path, raw)).size }
        assertTrue(
            "初始化入口站点数为 0——测试树里一处 setUp/@Before 都没提取到（实测应为 75 处）。" +
                "检查 [initEntrySites] 的 Kotlin/Java 声明形态是否与测试树实际写法脱节",
            entries > 0,
        )
        // 绑定面保鲜：承接名一个都没绑上时，规则对任何类都不产生 writes，绿灯毫无意义
        val binding = serviceBindingSurface(sources)
        assertTrue(
            "服务承接名绑定面为空——没有任何测试类被 [serviceBoundNames] 绑定到项目服务" +
                "（实测应为 3 处：EpBridgeSinglePathTest / SqlConsoleBridgeFilterProviderTest / PreviewEndToEndTest）。" +
                "绑定面为空 = 本规则对全部测试类恒绿，属静默空绿，须核对绑定正则与取服务形态",
            binding.isNotEmpty(),
        )
        val offenders = projectServiceStateOffenders(sources)
        assertTrue(
            "测试类在 setUp/@Before 里改了项目服务级共享状态（project.getService(…) 或 " +
                "AlreadyDisposedDiagnostics.projectService(…) 返回对象）却无**对该服务对象的复位调用**，" +
                "命中 ${offenders.size} 处：$offenders。light 测试项目在同一分片 JVM 内跨测试类复用同一个 " +
                "ProjectImpl，这类改动会串台到同片后续测试类（发作与否取决于分片邻居，新增测试类即重排）。" +
                "复位走 testutil.SqlLogManagerProjectState.capture（setUp）+ restore（tearDown）、" +
                "或服务自带的 clearForTest/resetForTest/loadState 等复位口（须调在承接该服务的标识符上）；" +
                "确属幂等安全或非共享实例的写，在写入行上方加 `// project-state: <理由>` 标注",
            offenders.isEmpty(),
        )
    }

    /**
     * 收尾入口（`tearDown` / `@After` / `@AfterEach`）里**不得发起项目服务查找**：
     * `路径:行号 → 命中片段`。
     *
     * **为什么单列一条（T-AgentResiduals R2，2026-09-22）**：`@After` / `tearDown` 正是「项目
     * temporarily-disposed」窗口的**高发时段**；此刻才首次创建某个订阅 `project.messageBus` 的服务，
     * 平台 `LazyInstanceHolder` 会把 `CannotInitialize` 缓存下来、**该服务在该 ProjectImpl 上永久重抛**
     * （T-ST-02b2 已证：一次失败创建 → 同片后续所有取用点成片变红，`testShards` shard3 flake 的
     * 最后一个来源正是 `SqlLogManagerProjectState.restore` 的**旧双参形态**在 tearDown 里再取一次服务）。
     *
     * 修法固定为「**收尾窗口不触碰服务容器**」：需要在 tearDown 里复原的共享服务，在 setUp 里连**引用**
     * 一起存进快照（`SqlLogManagerProjectState.capture` 现自持 manager 引用、`restore(snapshot)` 单参），
     * 于是 tearDown 全程零 `getService`。
     *
     * **豁免**：查找行上方连续注释块含 `// project-state: <理由>`（与上一条规则同族逃生舱；
     * 例如确需在收尾里探测另一个服务的状态，且该服务在本类 setUp 中已创建——请在标注里写明）。
     *
     * 单独成函数以便合成样本自证（规则「看不见某形态」= 空绿，只能靠合成样本钉住，
     * 见 `R2自证_收尾窗口取服务必红`）。
     */
    private fun teardownServiceLookupOffenders(sources: List<Pair<String, String>>): List<String> {
        val offenders = ArrayList<String>()
        for ((path, raw) in sources) {
            val code = maskSource(path, raw)
            for (site in teardownEntrySites(code)) {
                for (token in serviceLookupTokens) {
                    var idx = site.body.indexOf(token)
                    while (idx >= 0) {
                        val line = lineOf(code, site.bodyStart + idx)
                        if (!leadingCommentBlock(raw, line).contains("project-state:")) {
                            offenders.add("$path:$line → ${site.name} 里的 $token")
                        }
                        idx = site.body.indexOf(token, idx + 1)
                    }
                }
            }
        }
        return offenders
    }

    @Test
    fun `收尾窗口不得取项目服务`() {
        // T-AgentResiduals R2：见 [teardownServiceLookupOffenders] 的 KDoc。
        // k2j 移植注：[testSources] 即原文扫描面（不剥注释、.kt/.java 双根、本守卫自身排除，
        // T-TestPerf6 起读快照）；豁免标注要在原文上认——剥注释版会让 `// project-state:` 豁免永不生效。
        val sources = testSources()
        assertTestSourcesResolved(sources)
        val offenders = teardownServiceLookupOffenders(sources)
        assertTrue(
            "tearDown/@After 里出现项目服务查找，命中 ${offenders.size} 处：$offenders。" +
                "收尾正是「项目 temporarily-disposed」窗口的高发时段，此刻首次创建订阅 project.messageBus 的" +
                "服务会让平台把 CannotInitialize 缓存下来、该服务在本项目实例上永久重抛（同片后续用例成片变红）。" +
                "需要复原的共享服务请在 setUp 里连**引用**一起存（SqlLogManagerProjectState.capture 自持 manager " +
                "引用，restore(snapshot) 单参），tearDown 全程不碰服务容器；确需收尾探测的加 " +
                "`// project-state: <理由>` 标注",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `R2自证_收尾窗口取服务必红_存快照形态放行`() {
        // 负向自证：违规必报 + 三类合法形态放行 + 豁免标注只豁免被标注的那一行。
        // k2j 移植注：master 的 Kotlin 样本逐字保留；补一条 Java `@After public void` 形态样本
        // （k2j 测试树混放，收尾入口提取对 Java 声明形态同样生效——见 [teardownEntrySites]）。
        val violating = listOf(
            "src/test/kotlin/x/TearDownLookupSampleTest.kt" to """
                class TearDownLookupSampleTest : BasePlatformTestCase() {
                    override fun tearDown() {
                        SqlLogManagerProjectState.restore(
                            AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "tearDown.restore"),
                            null,
                        )
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        val found = teardownServiceLookupOffenders(violating)
        assertEquals("tearDown 里取项目服务必须命中：$found", 1, found.size)
        assertTrue(
            "命中点须锚在**取服务那一行**（行号靠保行掩码回推）：$found",
            found[0].endsWith("TearDownLookupSampleTest.kt:4 → tearDown 里的 projectService("),
        )

        // 合法形态 ①：R2 后的单参 restore（快照自持引用）——tearDown 零服务查找
        val restored = listOf(
            "src/test/kotlin/x/TearDownSnapshotSampleTest.kt" to """
                class TearDownSnapshotSampleTest : BasePlatformTestCase() {
                    private var state: SqlLogManagerProjectState.Snapshot? = null

                    override fun setUp() {
                        super.setUp()
                        state = SqlLogManagerProjectState.capture(project.getService(SqlLogManager::class.java))
                    }

                    override fun tearDown() {
                        SqlLogManagerProjectState.restore(state)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "R2 形态（快照持有引用、tearDown 只 restore）不得误报：${teardownServiceLookupOffenders(restored)}",
            0,
            teardownServiceLookupOffenders(restored).size,
        )

        // 合法形态 ②：@After 形态同样在扫描面内（且它才是不经平台基类的纯 JUnit 类收尾口）
        val afterForm = listOf(
            "src/test/kotlin/x/AfterSampleTest.kt" to """
                class AfterSampleTest {
                    @After
                    fun cleanup() {
                        JoinFkRegistry.clearForTest()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "@After 里不取服务不得误报：${teardownServiceLookupOffenders(afterForm)}",
            0,
            teardownServiceLookupOffenders(afterForm).size,
        )

        // 扫描面自证：@After 形态确实被提取（否则上面那个 0 是「入口没被识别」的空绿）
        assertEquals(
            "@After 入口应被提取：${teardownEntrySites(maskSource(afterForm[0].first, afterForm[0].second))}",
            1,
            teardownEntrySites(maskSource(afterForm[0].first, afterForm[0].second)).size,
        )

        // 合法形态 ②的 Java 侧（k2j）：`@After public void cleanup()` 与 Java `void tearDown()` 同样在扫描面
        val javaAfterForm = listOf(
            "src/test/java/x/JavaAfterSampleTest.java" to """
                class JavaAfterSampleTest {
                    @After
                    public void cleanup() {
                        JoinFkRegistry.clearForTest();
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "Java @After 入口应被提取（Java 声明形态不收，整个 src/test/java 收尾扫描面就恒绿）：" +
                "${teardownEntrySites(maskSource(javaAfterForm[0].first, javaAfterForm[0].second)).map { it.name }}",
            listOf("cleanup"),
            teardownEntrySites(maskSource(javaAfterForm[0].first, javaAfterForm[0].second)).map { it.name },
        )

        val javaTearDownLookup = listOf(
            "src/test/java/x/JavaTearDownLookupSampleTest.java" to """
                class JavaTearDownLookupSampleTest {
                    void tearDown() {
                        SqlLogManager manager = project.getService(SqlLogManager.class);
                        manager.setCapturingForTest(false);
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "Java `void tearDown()` 里取项目服务必须同样命中：${teardownServiceLookupOffenders(javaTearDownLookup)}",
            1,
            teardownServiceLookupOffenders(javaTearDownLookup).size,
        )

        // 豁免标注：只豁免被标注的那一行
        val annotated = listOf(
            "src/test/kotlin/x/AnnotatedTearDownSampleTest.kt" to """
                class AnnotatedTearDownSampleTest : BasePlatformTestCase() {
                    override fun tearDown() {
                        // project-state: 本类 setUp 已创建该服务，此处只读探测（不发起创建）
                        val m = AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "tearDown.probe")
                        m.setCapturingForTest(false)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "带 `// project-state: <理由>` 标注的取服务应豁免：${teardownServiceLookupOffenders(annotated)}",
            0,
            teardownServiceLookupOffenders(annotated).size,
        )

        // 空扫描面自证：路径解析失败 = 空绿，主用例的非空断言必须能拦住
        assertEquals("空扫描面下规则恒 0 违规", 0, teardownServiceLookupOffenders(emptyList()).size)
    }

    @Test
    fun `TST02d自证_初始化入口写共享服务不复位必红_边界态放行`() {
        // 负向自证（规则「看不见某形态」= 空绿，只能靠合成样本钉住）：违规必报 + 复位/标注/自建实例/
        // 只读四类放行 + 两条**漏报边界**如实钉住（方法体内写、capture-only）。
        // k2j 移植注：测试树是 `.kt` 为主 + `.java` 混放，故下面既有 master 的 Kotlin 样本
        // （逐字保留，证明 Kotlin 侧判据未被移植改动），也补 Java 声明形态样本
        // （`void setUp()` / `@Before public void init()` / `@org.junit.Before`）。
        val violating = listOf(
            "src/test/kotlin/x/LeakySampleTest.kt" to """
                class LeakySampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager

                    override fun setUp() {
                        super.setUp()
                        manager = project.getService(SqlLogManager::class.java)
                        manager.entries.dedupeEnabled = false
                    }
                }
            """.trimIndent(),
        )
        val found = projectServiceStateOffenders(violating)
        assertEquals(
            "setUp 里改项目服务级状态且类内无复位调用必须命中：$found",
            1,
            found.size,
        )
        assertTrue(
            "命中点须锚在**写入那一行**（行号靠保行掩码回推，不得落在绑定行或入口行）：$found",
            found[0].endsWith("LeakySampleTest.kt:7 → manager.entries.dedupeEnabled ="),
        )

        // ---- Java 声明形态：`protected void setUp() throws Exception` ----
        val javaViolating = listOf(
            "src/test/java/x/JavaLeakySampleTest.java" to """
                class JavaLeakySampleTest extends BasePlatformTestCase {
                    private SqlLogManager manager;

                    @Override
                    protected void setUp() throws Exception {
                        super.setUp();
                        manager = project.getService(SqlLogManager.class);
                        manager.entries.dedupeEnabled = false;
                    }
                }
            """.trimIndent(),
        )
        val javaFound = projectServiceStateOffenders(javaViolating)
        assertEquals(
            "Java 的 `void setUp() throws Exception` 入口必须与 Kotlin `fun setUp()` 同等命中：" +
                "$javaFound（Java 侧声明形态不收，整个 src/test/java 扫描面就恒绿）",
            1,
            javaFound.size,
        )
        assertTrue(
            "Java 侧命中点须锚在**写入那一行**：$javaFound",
            javaFound[0].endsWith("JavaLeakySampleTest.java:8 → manager.entries.dedupeEnabled ="),
        )

        // ---- Java 注解入口：`@Before public void init()`（方法名须取 init，不是 public）----
        val javaBeforeForm = listOf(
            "src/test/java/x/JavaBeforeSampleTest.java" to """
                class JavaBeforeSampleTest {
                    private SqlLogManager manager;

                    @Before
                    public void init() {
                        manager = AlreadyDisposedDiagnostics.projectService(project, SqlLogManager.class, "setUp.manager");
                        manager.entries.maxEntries = 10;
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "Java 的 @Before 入口（wrapper 取服务形态）必须命中：${projectServiceStateOffenders(javaBeforeForm)}",
            1,
            projectServiceStateOffenders(javaBeforeForm).size,
        )
        assertEquals(
            "Java 注解入口名须取紧邻 `(` 的方法名（取成 `public` 说明用的是「注解后第一个标识符」口径）：" +
                "${initEntrySites(maskSource(javaBeforeForm[0].first, javaBeforeForm[0].second)).map { it.name }}",
            listOf("init"),
            initEntrySites(maskSource(javaBeforeForm[0].first, javaBeforeForm[0].second)).map { it.name },
        )

        // ---- 全限定注解 `@org.junit.Before` ----
        val javaQualifiedBefore = listOf(
            "src/test/java/x/JavaQualifiedBeforeSampleTest.java" to """
                class JavaQualifiedBeforeSampleTest {
                    private SqlLogManager manager;

                    @org.junit.Before
                    public void init() {
                        manager = project.getService(SqlLogManager.class);
                        manager.entries.paused = true;
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "全限定注解 @org.junit.Before 的入口必须同样命中：${projectServiceStateOffenders(javaQualifiedBefore)}",
            1,
            projectServiceStateOffenders(javaQualifiedBefore).size,
        )

        // ---- Java 直取形态（getService(…).mutator()，不经绑定名）----
        val javaDirectForm = listOf(
            "src/test/java/x/JavaDirectSampleTest.java" to """
                class JavaDirectSampleTest {
                    @Before
                    public void init() {
                        project.getService(SqlLogManager.class).setCapturingForTest(true);
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "Java 直取形态 `project.getService(…).setXxx()` 必须命中（绑定名为空时全靠该锚点）：" +
                "${projectServiceStateOffenders(javaDirectForm)}",
            1,
            projectServiceStateOffenders(javaDirectForm).size,
        )

        // 变更类方法调用形态（既有泄漏点里 `setCapturingForTest` / `entries.clear()` 即此形态）
        val violatingCalls = listOf(
            "src/test/kotlin/x/LeakyCallSampleTest.kt" to """
                class LeakyCallSampleTest : BasePlatformTestCase() {
                    override fun setUp() {
                        super.setUp()
                        val manager = project.getService(SqlLogManager::class.java)
                        manager.setCapturingForTest(true)
                        manager.entries.clear()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "变更类方法调用（setXxx / clear）同样是写共享状态：${projectServiceStateOffenders(violatingCalls)}",
            2,
            projectServiceStateOffenders(violatingCalls).size,
        )

        // 复位调用（testutil 存复原，R2 单参形态：快照自持服务引用）→ 放行
        val restored = listOf(
            "src/test/kotlin/x/RestoredSampleTest.kt" to """
                class RestoredSampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager
                    private var state: SqlLogManagerProjectState.Snapshot? = null

                    override fun setUp() {
                        super.setUp()
                        manager = project.getService(SqlLogManager::class.java)
                        state = SqlLogManagerProjectState.capture(manager)
                        manager.entries.dedupeEnabled = false
                    }

                    override fun tearDown() {
                        SqlLogManagerProjectState.restore(state)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "类内有 capture/restore 存复原的写不得误报：${projectServiceStateOffenders(restored)}",
            0,
            projectServiceStateOffenders(restored).size,
        )

        // R2 形态的鉴别力（负例 1）：restore 的快照名**未在初始化入口由 capture 绑定** → 不算复位证据
        val unboundSnapshotRestore = listOf(
            "src/test/kotlin/x/UnboundSnapshotSampleTest.kt" to """
                class UnboundSnapshotSampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager

                    override fun setUp() {
                        super.setUp()
                        manager = project.getService(SqlLogManager::class.java)
                        manager.entries.dedupeEnabled = false
                    }

                    override fun tearDown() {
                        SqlLogManagerProjectState.restore(state)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "restore(<未由 capture 绑定的快照名>) 不得算复位证据（否则认令牌就等于一律放行）：" +
                "${projectServiceStateOffenders(unboundSnapshotRestore)}",
            1,
            projectServiceStateOffenders(unboundSnapshotRestore).size,
        )

        // R2 形态的鉴别力（负例 2）：快照是从**别的对象**（自建实例，非容器服务）捕获的 → 不算复位证据
        val snapshotFromOtherObject = listOf(
            "src/test/kotlin/x/SnapshotFromOtherSampleTest.kt" to """
                class SnapshotFromOtherSampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager
                    private var state: SqlLogManagerProjectState.Snapshot? = null

                    override fun setUp() {
                        super.setUp()
                        manager = project.getService(SqlLogManager::class.java)
                        state = SqlLogManagerProjectState.capture(SqlLogManager(project))
                        manager.entries.dedupeEnabled = false
                    }

                    override fun tearDown() {
                        SqlLogManagerProjectState.restore(state)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "快照从自建实例（SqlLogManager(project)，不进容器）捕获时不得豁免对共享服务的写：" +
                "${projectServiceStateOffenders(snapshotFromOtherObject)}",
            1,
            projectServiceStateOffenders(snapshotFromOtherObject).size,
        )

        // 自建实例（不进容器）→ 不判为泄漏
        val selfBuilt = listOf(
            "src/test/kotlin/x/SelfBuiltSampleTest.kt" to """
                class SelfBuiltSampleTest : BasePlatformTestCase() {
                    override fun setUp() {
                        super.setUp()
                        val manager = SqlLogManager(project)
                        manager.entries.dedupeEnabled = false
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "自建实例（SqlLogManager(project) 不进容器）不是共享状态，不得误报：${projectServiceStateOffenders(selfBuilt)}",
            0,
            projectServiceStateOffenders(selfBuilt).size,
        )

        // 只读访问（快照 / size / 属性读）→ 不判
        val readOnly = listOf(
            "src/test/kotlin/x/ReadOnlySampleTest.kt" to """
                class ReadOnlySampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager
                    private var before = 0

                    override fun setUp() {
                        super.setUp()
                        manager = project.getService(SqlLogManager::class.java)
                        before = manager.entries.snapshot().size
                        assertEquals(0, manager.entries.size())
                        assertFalse(manager.isWaitingForConsole)
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "只读访问（snapshot/size/属性读）不得误判为写：${projectServiceStateOffenders(readOnly)}",
            0,
            projectServiceStateOffenders(readOnly).size,
        )

        // Java 只读形态：getter 读不得被判成写（`get[A-Z]…` 不在变更类名单内）
        val javaReadOnly = listOf(
            "src/test/java/x/JavaReadOnlySampleTest.java" to """
                class JavaReadOnlySampleTest {
                    private SqlLogManager manager;

                    @Before
                    public void init() {
                        manager = project.getService(SqlLogManager.class);
                        int n = manager.getEntries().snapshot().size();
                        boolean capturing = manager.isCapturing();
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "Java getter 只读访问不得误判为写（若把 get[A-Z] 收进变更类名单会整片误报）：" +
                "${projectServiceStateOffenders(javaReadOnly)}",
            0,
            projectServiceStateOffenders(javaReadOnly).size,
        )

        // `// project-state: <理由>` 标注 → 放行（豁免不得变成「一律放行」：只豁免被标注的那一行）
        val annotated = listOf(
            "src/test/kotlin/x/AnnotatedSampleTest.kt" to """
                class AnnotatedSampleTest : BasePlatformTestCase() {
                    override fun setUp() {
                        super.setUp()
                        val manager = project.getService(SqlLogManager::class.java)
                        // project-state: 该字段写入幂等（置为默认值），且本类不做状态断言
                        manager.entries.dedupeEnabled = false
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "带 `// project-state: <理由>` 标注的写应豁免：${projectServiceStateOffenders(annotated)}",
            0,
            projectServiceStateOffenders(annotated).size,
        )

        // `@Before` 形态（纯 JUnit4 测试类）也在扫描面内
        val beforeForm = listOf(
            "src/test/kotlin/x/BeforeSampleTest.kt" to """
                class BeforeSampleTest {
                    private lateinit var manager: SqlLogManager

                    @Before
                    fun init() {
                        manager = project.getService(SqlLogManager::class.java)
                        manager.entries.maxEntries = 10
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "@Before 入口的写必须同样命中（初始化入口不只 setUp 一种）：${projectServiceStateOffenders(beforeForm)}",
            1,
            projectServiceStateOffenders(beforeForm).size,
        )

        // ---- 边界如实钉住（漏报方向，KDoc「已知边界」段登记）----
        // ① 测试方法体内的写不在扫描面（收编到「类内任意写」会误判 try/finally 重赋值复位的合法类）
        val methodBodyWrite = listOf(
            "src/test/kotlin/x/MethodBodySampleTest.kt" to """
                class MethodBodySampleTest : BasePlatformTestCase() {
                    fun testX() {
                        val manager = project.getService(SqlLogManager::class.java)
                        manager.entries.dedupeEnabled = false
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "边界登记：测试方法体内的写不在扫描面（本规则只覆盖初始化入口）：" +
                "${projectServiceStateOffenders(methodBodyWrite)}",
            0,
            projectServiceStateOffenders(methodBodyWrite).size,
        )
        // ② 只 capture 不 restore 是「半成品」——capture 不算复位证据，必须红
        val captureOnly = listOf(
            "src/test/kotlin/x/CaptureOnlySampleTest.kt" to """
                class CaptureOnlySampleTest : BasePlatformTestCase() {
                    private lateinit var state: SqlLogManagerProjectState.Snapshot

                    override fun setUp() {
                        super.setUp()
                        val manager = project.getService(SqlLogManager::class.java)
                        state = SqlLogManagerProjectState.capture(manager)
                        manager.entries.dedupeEnabled = false
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "只 capture 不 restore 不算复位证据（存了快照忘了还原正是半成品形态）：" +
                "${projectServiceStateOffenders(captureOnly)}",
            1,
            projectServiceStateOffenders(captureOnly).size,
        )

        // ---- T-ST-02d 独立复核阻断 1：wrapper 取服务形态必须能绑定 ----
        // 复核实证：只认 `getService(` 时，把真泄漏类的 `restore` 行整行删掉规则仍 offenders=0 绿。
        val wrapperBound = listOf(
            "src/test/kotlin/x/WrapperBoundSampleTest.kt" to """
                class WrapperBoundSampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager

                    override fun setUp() {
                        super.setUp()
                        manager = AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.manager")
                        manager.entries.dedupeEnabled = false
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "wrapper 承接（AlreadyDisposedDiagnostics.projectService）必须与 getService 同等绑定——" +
                "否则删掉真泄漏类的 restore 行也不红（复核阻断 1 复核实证）：" +
                "${projectServiceStateOffenders(wrapperBound)}",
            1,
            projectServiceStateOffenders(wrapperBound).size,
        )
        assertTrue(
            "wrapper 形态的命中点须锚在**写入那一行**（不得落在 wrapper 承接行）：" +
                "${projectServiceStateOffenders(wrapperBound)}",
            projectServiceStateOffenders(wrapperBound)[0]
                .endsWith("WrapperBoundSampleTest.kt:7 → manager.entries.dedupeEnabled ="),
        )

        // wrapper 承接 + wrapper 全量复位（R2 单参形态，快照由 wrapper 取服务表达式捕获）→ 绿
        val wrapperRestored = listOf(
            "src/test/kotlin/x/WrapperRestoredSampleTest.kt" to """
                class WrapperRestoredSampleTest : BasePlatformTestCase() {
                    private var state: SqlLogManagerProjectState.Snapshot? = null

                    override fun setUp() {
                        super.setUp()
                        state = SqlLogManagerProjectState.capture(
                            AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.capture")
                        )
                        AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.write")
                            .entries.dedupeEnabled = false
                    }

                    override fun tearDown() {
                        SqlLogManagerProjectState.restore(state)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "wrapper 取服务（换行形态）+ SqlLogManagerProjectState.restore(<快照名>) 全量复位不得误报：" +
                "${projectServiceStateOffenders(wrapperRestored)}",
            0,
            projectServiceStateOffenders(wrapperRestored).size,
        )

        // ---- T-ST-02d 独立复核阻断 2：**别的对象**的复位调用不得洗掉真泄漏 ----
        val otherObjectResets = listOf(
            "src/test/kotlin/x/OtherObjectResetSampleTest.kt" to """
                class OtherObjectResetSampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager

                    override fun setUp() {
                        super.setUp()
                        manager = AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.manager")
                        manager.entries.dedupeEnabled = false
                    }

                    override fun tearDown() {
                        PluginSettings.loadState(snapshot)
                        JoinFkRegistry.clearForTest()
                        PluginCacheManager.clearAll(project)
                        FirstCaptureGuide.resetForTest()
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "另一个对象的复位调用（PluginSettings.loadState / JoinFkRegistry.clearForTest / " +
                "PluginCacheManager.clearAll / FirstCaptureGuide.resetForTest）不得豁免本类对共享服务的写" +
                "（旧口径下这四个样本把真泄漏洗成绿——复核阻断 2 复核实证）：" +
                "${projectServiceStateOffenders(otherObjectResets)}",
            1,
            projectServiceStateOffenders(otherObjectResets).size,
        )

        // 承接名自身的复位口仍算复位证据（绑定判定不得把覆盖面一起砍掉）——真实形态：
        // RollbackSnapshotStoreTest 的 `project.getService(RollbackSnapshotStore::class.java)` + clearForTest()
        val boundServiceReset = listOf(
            "src/test/kotlin/x/BoundResetSampleTest.kt" to """
                class BoundResetSampleTest : BasePlatformTestCase() {
                    private lateinit var store: RollbackSnapshotStore

                    override fun setUp() {
                        super.setUp()
                        store = project.getService(RollbackSnapshotStore::class.java)
                        store.markRollbackExecuted(entry)
                    }

                    override fun tearDown() {
                        store.clearForTest()
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "承接名自身的复位口（store.clearForTest()）必须仍算复位证据（② 选型不得丢覆盖面）：" +
                "${projectServiceStateOffenders(boundServiceReset)}",
            0,
            projectServiceStateOffenders(boundServiceReset).size,
        )
        // 防「复位口样本空绿」：同一份样本去掉复位行必须红——证明上面那个 0 是「写被识别 + 复位被认」，
        // 而不是「写形态没命中」的空绿。
        val boundResetMissing = listOf(
            boundServiceReset[0].first to boundServiceReset[0].second.replace("store.clearForTest()", ""),
        )
        assertEquals(
            "同一份样本去掉 store.clearForTest() 必须红（否则上一条 0 是空绿）：" +
                "${projectServiceStateOffenders(boundResetMissing)}",
            1,
            projectServiceStateOffenders(boundResetMissing).size,
        )

        // ---- T-ST-02d 独立复核条件项：部分复位（3/15 字段）不算复位证据 ----
        val partialReset = listOf(
            "src/test/kotlin/x/PartialResetSampleTest.kt" to """
                class PartialResetSampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager

                    override fun setUp() {
                        super.setUp()
                        manager = AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.manager")
                        manager.entries.dedupeEnabled = false
                    }

                    override fun tearDown() {
                        manager.restoreCaptureStateForTest(false, false, false)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "部分复位（restoreCaptureStateForTest 只回滚 3/15 字段）不足以豁免对 buffer 字段的写，必须红：" +
                "${projectServiceStateOffenders(partialReset)}",
            1,
            projectServiceStateOffenders(partialReset).size,
        )

        // ---- 空扫描面自证：路径解析失败 = 空绿，主用例的非空断言必须能拦住 ----
        assertEquals(
            "空扫描面下规则恒 0 违规——这正是「路径解析失败 = 静默空绿」的机理",
            0,
            projectServiceStateOffenders(emptyList()).size,
        )
        var emptyScanRed = false
        try {
            assertTestSourcesResolved(emptyList())
        } catch (e: AssertionError) {
            emptyScanRed = true
        }
        assertTrue(
            "扫描面为空时非空断言必须变红（否则 src/test 路径解析失败会静默空绿）",
            emptyScanRed,
        )

        // 工具函数自证：初始化入口提取覆盖 Kotlin setUp / Kotlin @Before / Java void setUp /
        // Java @Before 四种形态，行号与原文对齐（保行掩码）
        val setUpSites = initEntrySites(maskSource(violating[0].first, violating[0].second))
        assertEquals("Kotlin setUp 入口应被提取：$setUpSites", 1, setUpSites.size)
        assertEquals("入口行号须与原文对齐（保行掩码）", 4, setUpSites[0].line)
        assertEquals("入口名", "setUp", setUpSites[0].name)
        val beforeSites = initEntrySites(maskSource(beforeForm[0].first, beforeForm[0].second))
        assertEquals("@Before 入口应被提取：$beforeSites", 1, beforeSites.size)
        assertEquals("入口名取函数名", "init", beforeSites[0].name)
        val javaSetUpSites = initEntrySites(maskSource(javaViolating[0].first, javaViolating[0].second))
        assertEquals("Java `void setUp()` 入口应被提取：$javaSetUpSites", 1, javaSetUpSites.size)
        assertEquals("Java 入口名", "setUp", javaSetUpSites[0].name)
        assertEquals("Java 入口行号须与原文对齐", 5, javaSetUpSites[0].line)
    }

    private companion object {
        private fun repoRoot(): File {
            var dir = File(System.getProperty("user.dir")).absoluteFile
            repeat(6) {
                // k2j 生产树为 src/main/java（.java），仓库根探测与 master 的 src/main/kotlin 不同
                if (File(dir, "src/main/java").isDirectory) return dir
                dir = dir.parentFile ?: return@repeat
            }
            error("未定位到仓库根（src/main/java 不存在于 user.dir 及上层）: " + System.getProperty("user.dir"))
        }

        /** 剥离 block 注释与行注释（字符串字面量中的 // 会被连带截断——只可能产生漏报不产生误报，可接受）。 */
        private fun stripComments(source: String): String =
            source
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .lineSequence()
                .joinToString("\n") { it.substringBefore("//") }

        // T-TestPerf6：三棵源码树快照——同一次测试 JVM 内文件内容恒定，全部扫描点共享一次
        // 遍历+读取（原口径 mainSources 每个断言各付一遍全仓、src/test 三处与 buildSrc 一处内联
        // 各付一遍）；各使用处的过滤/豁免/判读口径在使用点原样保留，快照只消除重复文件系统 IO。
        private val CHAR_BS: Char = '\\'

        private fun snapshotTree(rel: String): List<Pair<File, String>> =
            File(repoRoot(), rel).walkTopDown()
                .filter { it.isFile }
                .map { it to it.readText() }
                .toList()

        // k2j：main 树=src/main/java（.java）、测试树=src/test/kotlin 与 src/test/java（.kt/.java
        // 混放）两处并扫、buildSrc 与 master 同为 .kt——快照名沿用 master（减少后续轮合并噪声）。
        private val srcMainKotlinTree: List<Pair<File, String>> by lazy { snapshotTree("src/main/java") }
        private val srcTestKotlinTree: List<Pair<File, String>> by lazy {
            listOf("src/test/kotlin", "src/test/java")
                .flatMap { rel ->
                    File(repoRoot(), rel).takeIf { it.isDirectory }?.walkTopDown()?.toList() ?: emptyList()
                }
                .filter { it.isFile }
                .map { it to it.readText() }
        }
        private val buildSrcKotlinTree: List<Pair<File, String>> by lazy { snapshotTree("buildSrc/src/main/kotlin") }

        private val mainSourcesSnapshot: List<Pair<String, String>> by lazy {
            srcMainKotlinTree
                .filter { (f, _) -> f.extension == "java" }
                .map { (f, text) -> f.relativeTo(repoRoot()).path.replace('\\', '/') to stripComments(text) }
        }
    }
}
