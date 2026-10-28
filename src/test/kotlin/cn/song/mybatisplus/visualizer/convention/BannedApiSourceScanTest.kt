package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 禁用 API 与结构锚点类守卫（ActionToolbar/IPGP/BrowserUtil/剪贴板/网络出口/方言反射等）
 * （T-M53-II 2026-10-10：成员逐字符搬运；可见性按拆类需要统一放宽为 internal。）
 */
class BannedApiSourceScanTest : BannedApiScanSupport() {

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
        // 名单出处：SqlToJavaPanel.kt / IconScaler.kt 注释（编译实证）；发现新裁剪类请追加并留注释
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
        val bad = srcTestKotlinTree
            .filter { (f, _) -> f.extension == "kt" }
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
        val bad = srcTestKotlinTree
            .filter { (f, _) -> f.extension == "kt" }
            .filter { (f, _) -> f.name != "BannedApiSourceScanTest.kt" } // 本守卫自身的报错文案含该片段，须自豁免
            .filter { (f, _) -> f.name != "FakeClipboard.kt" } // 假实现本体（不含 systemClipboard 访问）
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
        val code = stripComments(
            repoRoot().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/util/SafeBrowser.kt").readText(),
        )
        assertTrue(
            "SafeBrowser.kt 丢失 fail-safe 守卫形态（app == null || isUnitTestMode）——" +
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
        val whitelist = setOf(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SourceCaptureGuide.kt",
            // T-M53-II D2-5：面板的 6 个模态调用点（执行前 YesNo 确认、导出格式三选、
            // 回滚 SQL info）随执行链逐字符搬出到 SqlPreviewPanelExecution.kt——场景依据同下。
            // 主文件 `ui/SqlPreviewPanel.kt` 已无模态调用点，故本表不再为它加白（避免白名单
            // 条目变成「顺手弹个窗」的隐性许可）。
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt",
            // O-145 W6：清除本项目模块数据源绑定须用户确认，拒绝后不改变任何绑定。
            "src/main/kotlin/cn/song/mybatisplus/visualizer/config/ProjectPluginSettingsConfigurable.kt",
            // O-145 W7：仅未知方言的原始查询须用户确认；已知冲突和未知写操作直接阻断。
            "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelector.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogToSqlPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/config/McpConfigurable.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlSuppressManagerDialog.kt",
            // R4（导出格式三选）：用户必须选择导出格式（CSV/TSV/TXT）才能继续——知情选择场景加白
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/HistorySqlLogPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt",
            // T-M53-II D2：SqlLogPanel 的右键菜单（含模态弹窗调用点）已逐字符搬出到该文件
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanelActions.kt",
            // ParamSchemeManagerDialog（R22 T-04）：新建/重命名方案 showInputDialog（用户必须输入
            //   方案名才能继续，与 R4 导出格式三选同类）+ 删除方案前 showYesNoDialog 知情确认
            //   （破坏性、不可恢复，与 SqlSuppressManagerDialog 清空前确认同类）；对话框本体
            //   非模态 MODELESS（DialogWrapper(project, false, MODELESS)，与 SqlSuppressManagerDialog
            //   逐字段同形态），20260929 收口门禁守卫命中后按红线加白。
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ParamSchemeManagerDialog.kt",
            // T-SelfUpdateRollback W2（O-150，2026-10-24）：回滚上一版前的 YesNo 确认。回滚是
            //   重启级动作（调度恢复命令后须重启 IDE 才生效）且会覆盖当前已安装版本，属「重大不可逆
            //   需知情确认」档，与 SqlSuppressManagerDialog 清空前确认同类。调用点
            //   `UpdateSection.rollbackConfirm` 已注明场景依据，并做成 seam 供测试注入免弹窗。
            "src/main/kotlin/cn/song/mybatisplus/visualizer/config/UpdateSection.kt",
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
        for ((path, owner) in mapOf(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/config/ProjectPluginSettingsConfigurable.kt" to "clearModuleBindings",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelector.kt" to "allowDialectGate",
        )) {
            val code = stripComments(repoRoot().resolve(path).readText())
            assertTrue("确认动作应保留在 $owner", code.contains("fun $owner("))
            assertEquals("$path 只允许一处已登记的知情确认", 1,
                Regex("Messages\\.showYesNoDialog\\(").findAll(code).count())
        }
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
        // Content-Encoding 解压。两者收口在 FeedbackApi 的 HttpRequestsSender（postJsonWithBody +
        // readBody）。此守卫防收口被拆散/退化——任何一侧锚点消失即红。
        val feedback = stripComments(
            repoRoot().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/feedback/FeedbackApi.kt").readText(),
        )
        assertTrue(
            "FeedbackApi.kt 丢失 errorStream 读取（4xx 正文将被 readString 丢弃，用户只看到裸状态码）——" +
                "网络出口收口被拆散，恢复 HttpRequestsSender 的错误正文读取",
            feedback.contains("errorStream"),
        )
        assertTrue(
            "FeedbackApi.kt 丢失 gzip 解压处理（myGzip 默认协商 gzip，手写读取不解压得二进制乱码）——" +
                "恢复 readBody 的 contentEncoding 判别 + GZIPInputStream 解压分支",
            feedback.contains("contentEncoding") && feedback.contains("GZIPInputStream"),
        )
        // UpdateApi 走平台 readString（成功流+自动解压，GET manifest 场景定案合法）——
        // 禁手写原始响应流，防止有人绕开收口自造读取。
        val update = stripComments(
            repoRoot().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/update/UpdateApi.kt").readText(),
        )
        val raw = listOf("getInputStream(", "openStream(").filter { update.contains(it) }
        assertTrue(
            "UpdateApi.kt 出现手写原始响应流读取（$raw）——绕开平台 readString 会同时踩 4xx 正文丢失与 gzip 不解压两坑。" +
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
            "src/main/kotlin/cn/song/mybatisplus/visualizer/util/UiTextClipboard.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/components/CopyExecutableSqlAction.kt",
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
        assertNoHits(
            "getSystemClipboard",
            "直写系统剪贴板绕开平台复制栈（与 CopyPasteManager 双轨，T-UiNative P0 已收口）",
            "改走 util.UiTextClipboard.copy",
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
        val allowed = "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DialectPreMapping.kt"
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

    /**
     * O-145 日志来源证据边界：只禁危险的“升级为精确事实”，不禁止未来经验证的 sourceContext。
     * 库标是脱敏展示文本，last agent URL 是另一时刻/连接的事实；源码文件也不是 SQL PSI。
     */
    private fun unsafeLogPreviewEvidence(code: String): List<String> {
        val patterns = listOf(
            Regex("capturedJdbcUrl\\s*=\\s*(?:entry\\.)?databaseName\\b") to "库标反推精确 JDBC URL",
            Regex("capturedJdbcUrl\\s*=\\s*[^\\n]*lastConnectionUrl\\s*\\(") to "最近 agent URL 冒充本条连接",
            Regex("SqlPsiResolution\\.(?:Exact|UniqueInOwner)\\s*\\(") to "运行时源码冒充真实 SQL PSI",
            Regex("hostVirtualFile\\s*=\\s*SourcePathResolver\\.resolveVirtualFile\\s*\\(") to "首命中源码路径冒充唯一来源",
            Regex("SourcePathResolver\\.resolveVirtualFiles\\s*\\([^\\n]*\\)\\s*\\.first(?:OrNull)?\\s*\\(") to "多路径候选取首个冒充唯一来源",
        )
        return patterns.filter { (pattern, _) -> pattern.containsMatchIn(code) }.map { it.second }
    }

    @Test
    fun `O145日志预览不得从库标旧URL或源码路径升级精确来源`() {
        // 三个实际打开入口（实时、历史、反查）都要覆盖，而非只验证合成字符串。
        val paths = listOf("SqlLogPanel.kt", "HistorySqlLogPanel.kt", "EntrySourceMatcher.kt")
        val root = repoRoot().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/log")
        val violations = paths.flatMap { name ->
            val code = stripComments(root.resolve(name).readText())
            unsafeLogPreviewEvidence(code).map { "$name: $it" }
        }
        assertTrue("O-145 日志预览不能伪造精确 JDBC/SQL PSI/唯一源码：$violations", violations.isEmpty())
    }

    @Test
    fun `O145守卫自证_危险推断必红而安全来源展示不禁`() {
        for (sample in listOf(
            "capturedJdbcUrl = entry.databaseName",
            "capturedJdbcUrl = AgentUrlRegistry.lastConnectionUrl(project)",
            "sqlPsiResolution = SqlPsiResolution.Exact(sourcePsi)",
            "hostVirtualFile = SourcePathResolver.resolveVirtualFile(project, entry.sourceFile)",
            "hostVirtualFile = SourcePathResolver.resolveVirtualFiles(project, entry.sourceFile).firstOrNull()",
        )) {
            assertTrue("危险形态未检出：$sample", unsafeLogPreviewEvidence(sample).isNotEmpty())
        }
        assertTrue(unsafeLogPreviewEvidence("sourceLocation = entry.sourceFile").isEmpty())
        assertTrue(unsafeLogPreviewEvidence("sourceContext = verifiedUniqueEvidence").isEmpty())
    }

    @Test
    fun `跳转族读权限运行期断言锚点`() {
        // 运行期硬断言锚点（T-PCP-03）：`SourcePathResolver.resolveVirtualFile` 入口必须保留
        // `assertReadAccessAllowed()`——源码级规则只管「调用点有没有 ReadAction 字样」，
        // 断言把违约暴露在实际调用链上（后台裸调即抛，不静默读模型）。
        val code = stripComments(
            repoRoot().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/log/EntrySourceMatcher.kt").readText(),
        )
        assertTrue(
            "SourcePathResolver.resolveVirtualFile 丢失 assertReadAccessAllowed() 入口断言——" +
                "跳转族的读权限约定退回「只写在 KDoc 里」的靠约定状态（T-PCP-03）",
            code.contains("assertReadAccessAllowed()"),
        )
    }
}
