// 自 master src/test/kotlin/.../convention/ExplainAskPasswordChannelGuardTest.kt 移植
// （k2j 守卫适配：扫描根 src/main/java + .java；askPassword 表态在 Java 承载里是位置实参，
//   断言改「注释锚点 + 邻近裸布尔实参」判据；源码取 rawMainSources 保留注释锚）
package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-ExplainPwdPrompt W3 守卫（2026-10-24）：`McpDbExecutor.executeQuery` 的 `askPassword`
 * 通道表态对账。
 *
 * 背景：`askPassword` 是**必填**参数（编译期强迫每个调用点表态），但编译期挡不住「显式传错」——
 * 面板 UI 通道（结构化执行计划/预估行数）若被改回 false，数据源未存密码时退回「红字不弹框」
 * 缺陷（沙盒实测 2026-10-23）；MCP 工具通道若被改成 true，程序性调用弹模态密码框即挂死
 * （零弹窗红线）。本守卫把三个调用点的表态钉死在源码上，反向：改掉任一表态必红。
 */
class ExplainAskPasswordChannelGuardTest : BannedApiScanSupport() {

    /** k2j：注释锚是判据的一半（Java 位置实参无具名形态），故取不剥注释的原文树。 */
    private fun sourceOf(suffix: String): String =
        rawMainSources().firstOrNull { it.first.endsWith(suffix) }?.second
            ?: error("源文件不在快照中：$suffix")

    /** 锚点之后 400 字符内须出现「独占一行的裸布尔实参」（Java 位置实参形态）。 */
    private fun bareBooleanAfter(code: String, anchor: String, kw: String): Boolean {
        val idx = code.indexOf(anchor)
        if (idx < 0) return false
        val slice = code.substring(idx, (idx + 400).coerceAtMost(code.length))
        return Regex("(?m)^\\s*$kw\\s*,\\s*\\r?$").containsMatchIn(slice)
    }

    @Test
    fun `面板执行计划结构化通道必须允许询问密码`() {
        val code = sourceOf("execute/ExplainPanelChannel.java")
        assertTrue(
            "ExplainPanelChannel 的 McpDbExecutor.executeQuery 须带 askPassword = true" +
                "（UI 上下文：数据源未存密码时交平台原生密码框，T-ExplainPwdPrompt）",
            bareBooleanAfter(
                code,
                "T-ExplainPwdPrompt：面板 UI 上下文——数据源未存密码时交平台原生密码框",
                "true",
            ),
        )
    }

    @Test
    fun `预估行数与EXPLAIN直送旁路必须允许询问密码`() {
        val code = sourceOf("ui/PreviewSideQuery.java")
        assertTrue(
            "PreviewSideQuery 的 McpDbExecutor.executeQuery 须带 askPassword = true" +
                "（UI 上下文，T-ExplainPwdPrompt）",
            bareBooleanAfter(
                code,
                "T-ExplainPwdPrompt：面板 UI 上下文——数据源未存密码时交平台原生密码框",
                "true",
            ),
        )
    }

    @Test
    fun `MCP工具通道必须保持零弹窗`() {
        val code = sourceOf("mcp/SqlVisualizerExplainTool.java")
        assertTrue(
            "SqlVisualizerExplainTool 的 McpDbExecutor.executeQuery 必须显式 askPassword = false" +
                "（MCP 零弹窗红线：程序性调用弹模态密码框即挂死，T-ExplainPwdPrompt）",
            bareBooleanAfter(code, "T-ExplainPwdPrompt：MCP 通道", "false"),
        )
    }

    @Test
    fun `提交链必须带模块学习回落取源`() {
        val code = sourceOf("ui/SqlPreviewPanelExecutionKt.java")
        assertTrue(
            "submitExplainOrStructured 必须接 explainFallbackDataSource(proj, sourceModuleKey, sql) 回落" +
                "（绑定链 runner 枚举被方言路由卡死时，结构化通道靠模块学习/last-used 记忆取源到达，" +
                "T-ExplainPwdPrompt W4 + T-DialectInferPollution W9 模块归属修正）",
            code.contains("explainFallbackDataSource(") &&
                code.contains("p.sourceModuleKey, explainSql)"),
        )
    }

    @Test
    fun `参数未回填不得回退直送`() {
        val code = sourceOf("ui/SqlPreviewPanelExecutionKt.java")
        // DirectFallback(UNFILLED_PARAMS) 分支必须先于「其余回退直送」的 else 被单独拦下：
        // 回退直送在无绑定形态必失败且弹误导性「未绑定数据源」红字（沙盒实测 2026-10-24）。
        val unfilledIdx = code.indexOf("FallbackReason.UNFILLED_PARAMS")
        val elseIdx = code.indexOf("notifyExplainStructuredFallback(p, proj, fb.reason(), null);")
        assertTrue(
            "submitExplainOrStructured 须有 UNFILLED_PARAMS 专用分支（不回退直送，T-ExplainPwdPrompt W5）",
            unfilledIdx >= 0,
        )
        assertTrue(
            "UNFILLED_PARAMS 分支必须出现在回退直送的 else 之前（否则未回填参数仍会弹误导红字）",
            elseIdx < 0 || unfilledIdx in 0 until elseIdx,
        )
    }

    @Test
    fun `认证失败不得弹红字模态框`() {
        val code = sourceOf("ui/SqlPreviewPanelExecutionKt.java")
        // AUTH 出口走轻通知（notifyExplainAuthFailure）——平台凭据对话框已呈现失败与出口，
        // Abort 后再弹红字框是二次打断（用户报障 2026-10-24：「点终止还是出现执行计划的弹框」）。
        // 提取全部 AUTH 分支体，逐一断言切片内不得出现 showExplainErrorDialog。
        // T-ExplainConsoleFirst W1 修订：直送链改回内嵌原生控制台后，AUTH 的呈现由平台凭据链
        // 原生负责，故 AUTH 分支只剩结构化通道一处，轻通知纪律不变。
        val starts = Regex("ExplainFailureKind\\.AUTH\\)").findAll(code).map { it.range.first }.toList()
        assertTrue("应存在 AUTH 分支（结构化 onFailed）", starts.isNotEmpty())
        val branchEnds = Regex("ExplainFailureKind\\.(WRONG_TARGET|CANCELLED|OTHER)\\)")
            .findAll(code).map { it.range.first }.toList()
        val offenders = starts.count { s ->
            val end = branchEnds.firstOrNull { it > s } ?: code.length
            code.substring(s, end).contains("showExplainErrorDialog")
        }
        assertTrue(
            "AUTH 分支内不得调 showExplainErrorDialog（红字模态框=二次打断，须走轻通知）：命中 $offenders 处",
            offenders == 0,
        )
        assertTrue(
            "AUTH 轻通知出口 notifyExplainAuthFailure 必须存在",
            code.contains("notifyExplainAuthFailure("),
        )
    }

    @Test
    fun `EXPLAIN闸门重渲染后必须自动重提交一次`() {
        val code = sourceOf("ui/SqlPreviewPanelExecutionKt.java")
        // T-DialectInferPollution W7（用户真机报障 2026-10-24）：渲染方言与目标数据源不一致
        // （last-used 被竞品测试数据源 dbx_test/PG 残留占据，MySQL 预览按 PG 渲染）时，闸门
        // AUTO_RERENDER_REQUIRED 重渲染成功后若断链让用户重按「执行计划」，第二次点击在开关
        // 关闭形态还会落进直送链弹「未绑定数据源」红字——重渲染成功必须自动重提交一次
        // （rerendered 参数防循环），重渲染失败/已重渲染过才回落红条。
        // k2j：Java 位置实参——自动重提交 = 尾两参 false, true（allowDataSourcePrompt=false, rerendered=true，
        // 生产为多行调用，按行锚判定）。
        val gateHit = code.contains("AUTO_RERENDER_REQUIRED") && code.contains("!rerendered")
        val recursion = Regex("(?m)^\\s*false, true\\);\\s*$").containsMatchIn(code)
        val param = code.contains("boolean rerendered")
        assertTrue(
            "submitExplainOrStructured 的闸门回调须有 AUTO_RERENDER_REQUIRED 自动重提交分支（W7）",
            gateHit,
        )
        assertTrue(
            "submitExplainOrStructured 须带 rerendered 参数且自动重提交时置位（防闸门循环）",
            param && recursion,
        )
    }

    @Test
    fun `直送链不得再做插件侧选源`() {
        val code = sourceOf("ui/SqlPreviewPanelExecutionKt.java")
        // T-ExplainConsoleFirst W1（2026-10-24）：直送链改回内嵌原生控制台（形态 A）后，
        // 「用哪条源」交还平台——插件侧**不得**再调 explainFallbackDataSource（模块学习 /
        // last-used / 表归属路由那一整套）。十二轮（W8~W12）的拉锯根因正是插件在自己算源：
        // 平台「选中即关联」让 runner 枚举第二次起恒为单源，插件算得再准也改不了平台执行目标。
        // 本守卫把「形态 B 的取源逻辑悄悄回流」钉成红。
        val body = funBody(code, "public static void submitExplainDirect(")
        assertTrue("submitExplainDirect 必须仍存在", body.isNotEmpty())
        assertTrue(
            "直送链不得再调 explainFallbackDataSource（插件侧选源已随形态 A 退役）",
            !body.contains("explainFallbackDataSource"),
        )
        assertTrue(
            "直送链必须转调 submitExplainDirectConsole（内嵌原生控制台＝形态 A）",
            body.contains("submitExplainDirectConsole("),
        )
        // 零源判定仍须在场（一条源都没有时给引导，而不是空跑）
        assertTrue(
            "零源判定须保留（registeredDataSourceCount 或测试缝）",
            body.contains("registeredDataSourceCount") || body.contains("explainTargetForTest"),
        )
    }

    /** 取静态方法体（从声明行到下一个同类静态声明之前）。 */
    private fun funBody(code: String, decl: String): String {
        val start = code.indexOf(decl)
        if (start < 0) return ""
        val nextDecl = Regex("\\r?\\n    (public|private) static")
            .find(code, start + decl.length)?.range?.first ?: code.length
        return code.substring(start, nextDecl)
    }

    @Test
    fun `回落取源必须做表归属路由`() {
        val resolver = sourceOf("ui/highlight/DatabaseSchemaResolver.java")
        val selector = sourceOf("execute/DataSourceSelector.java")
        val chain = sourceOf("ui/SqlPreviewPanelExecutionKt.java")
        // T-DialectInferPollution W10（用户沙盒实测两连错源：wms_inventory_jar_demo/order_entity
        // 被 last-used/学习源硬套）：模块学习与 last-used 证据都可能陈旧，而「表在哪个库」是更强
        // 的归属事实。resolver 须提供只读缓存的表归属路由入口；取源函数（DataSourceSelector 侧）
        // 须接线（候选源不持有表而唯一其他源持有时自动路由；多源/零源不猜）；两条 EXPLAIN 链须
        // 传预览 SQL。
        assertTrue(
            "DatabaseSchemaResolver 须有 cachedOwnerIndexes/ownerIndexesForSources 表归属路由入口（W10）",
            resolver.contains("cachedOwnerIndexes") && resolver.contains("ownerIndexesForSources"),
        )
        assertTrue(
            "explainFallbackDataSource 须接线表归属路由（cachedOwnerIndexes，W10）",
            selector.contains("cachedOwnerIndexes"),
        )
        assertTrue(
            "表归属路由须传预览 SQL（否则无表可验证）",
            Regex("explainFallbackDataSource\\(\\s*\\r?\\n\\s*proj, p\\.sourceModuleKey, explainSql\\)")
                .containsMatchIn(chain),
        )
    }

    @Test
    fun `排队执行意图必须有自有有界结算`() {
        val gate = sourceOf("execute/PrepareSingleFlightGate.java")
        val selectorResults = sourceOf("execute/DataSourceSelectorResultsKt.java")
        val selector = sourceOf("execute/DataSourceSelector.java")
        // T-DialectInferPollution W11（用户真机「点执行 SQL 卡住」）：QUEUE_ONLY 排队意图原本
        // 只能等 owner 60s 逃生被下一链接管，排队者自身无触发点 → UI 永远「执行中…」。排队意图
        // 须有自己的超时结算（expirePendingExecute，门与 owner 不动）。
        assertTrue(
            "PrepareSingleFlightGate 须有 expirePendingExecute 排队自有结算（W11）",
            gate.contains("expirePendingExecute"),
        )
        assertTrue(
            "chooseAndPrepare（DataSourceSelectorResultsKt）的 QUEUE_ONLY 分支须起排队超时结算 Timer（W11）",
            selectorResults.contains("PENDING_EXECUTE_TIMEOUT_MS") &&
                selectorResults.contains("expirePendingExecute"),
        )
        assertTrue(
            "排队超时常量与文案须定义在 DataSourceSelector（W11）",
            selector.contains("PENDING_EXECUTE_TIMEOUT_MS = "),
        )
    }

    @Test
    fun `缓存热但表不在须引导选择而非错源执行`() {
        val code = sourceOf("execute/DataSourceSelector.java")
        // T-DialectInferPollution W12（用户沙盒实测）：候选源不持有预览的表且缓存热（有源已
        // 内省出表）→ 表不存在于任何已连库，错源执行必败——须返回 null 引导选择数据源；
        // 缓存全冷（无法证明）才保持候选。
        assertTrue(
            "须有 cacheHasAnyTable 缓存热度判定（W12）",
            code.contains("cacheHasAnyTable"),
        )
        val hotIdx = code.indexOf("cacheHasAnyTable(project)")
        val nullReturn = code.indexOf("return null", hotIdx)
        assertTrue(
            "缓存热零命中须 return null 引导选择（W12）",
            hotIdx >= 0 && nullReturn in 0 until (hotIdx + 400),
        )
    }

    @Test
    fun `选错源失败不得弹红字模态框`() {
        val code = sourceOf("ui/SqlPreviewPanelExecutionKt.java")
        // T-ExplainFailUx W1（用户沙盒实测诉求：「这种情况就不应该弹窗了吧，应该直接引导用户
        // 更换数据源」）：WRONG_TARGET 档（表不在当前源）须走就地引导 showExplainWrongTarget，
        // 不得调 showExplainErrorDialog 弹模态。按分支起点切片断言（同 AUTH 纪律）。
        // T-ExplainConsoleFirst W1 修订：直送链改回内嵌原生控制台后，选错源的呈现由平台控制台
        // 原生负责，故 WRONG_TARGET 分支只剩结构化通道一处，就地引导纪律不变。
        val starts = Regex("ExplainFailureKind\\.WRONG_TARGET\\)").findAll(code).map { it.range.first }.toList()
        assertTrue("应存在 WRONG_TARGET 分支（结构化 onFailed）", starts.isNotEmpty())
        val branchEnds = Regex("ExplainFailureKind\\.(CANCELLED|OTHER)\\)")
            .findAll(code).map { it.range.first }.toList()
        val offenders = starts.count { s ->
            val end = branchEnds.firstOrNull { it > s } ?: code.length
            code.substring(s, end).contains("showExplainErrorDialog")
        }
        assertTrue(
            "WRONG_TARGET 分支内不得调 showExplainErrorDialog（选错源须就地引导换源，W1）：命中 $offenders 处",
            offenders == 0,
        )
        assertTrue(
            "WRONG_TARGET 就地引导出口 showExplainWrongTarget 必须存在且接原生能力（W1+W12）",
            code.contains("showExplainWrongTarget") && code.contains("openDatabaseToolWindow"),
        )
    }

    @Test
    fun `换源一律走平台原生工具栏不得自建入口`() {
        val chain = sourceOf("ui/SqlPreviewPanelExecutionKt.java")
        val panel = sourceOf("ui/SqlPreviewPanelParamPanelKt.java")
        // T-ExplainSwitchNative W12（2026-10-24 用户实测三图定案）：图三证明平台原生工具栏自带
        // `Data Sources` 菜单（Detach Data Source + 全部源）就是理想形态；且平台
        // getForcedDataSourceForExec 的关联无公开清除 API，插件自建入口第二次起必被短路成单源
        // （日志实证 runners 5→1）。纪律：插件**不得**自建任何换源 UI。
        assertTrue(
            "指示行不得再渲染自研换源控件（W12）",
            !panel.contains("BIND_STATE_SWITCH_NAME") && !panel.contains("buildDataSourceCombo") &&
                !panel.contains("switchDataSourceViaNativeChooser"),
        )
        assertTrue(
            "自研换源函数必须整体删除（W12）",
            !chain.contains("switchDataSourceViaNativeChooser"),
        )
        assertTrue(
            "红条换源动作须指向平台原生能力（打开 Database 工具窗口，W12）",
            chain.contains("openDatabaseToolWindow(proj"),
        )
    }

    @Test
    fun `渲染方言缺失时不得判成未知阻断执行`() {
        val code = sourceOf("ui/SqlPreviewPanelExecutionKt.java")
        // T-ExplainSwitchNative W10（用户实测「为什么 sql 无法执行」）：effectiveDialect 为 null
        // 只发生在「渲染链没记方言」的载体，而渲染器 byName(null) 的既有语义是回落 MySQL
        // （Dialect.kt:908-909）——闸门请求侧必须同口径回落，否则 RENDER_UNKNOWN_BLOCK 会阻断执行。
        // k2j：Java 位置实参形态——executionDialectGateRequest 体内以 MySqlDialect.INSTANCE 兜底。
        val body = funBody(code, "public static ExecutionDialectGate.Request executionDialectGateRequest(")
        assertTrue("闸门请求体必须存在（W10）", body.isNotEmpty())
        assertTrue(
            "renderedDialectName 为 null 时须回落 MySqlDialect（W10）",
            body.contains("MySqlDialect.INSTANCE"),
        )
    }

    @Test
    fun `方言闸红条的换源动作须指向原生能力`() {
        val code = sourceOf("ui/SqlPreviewPanelExecutionKt.java")
        // T-ExplainSwitchNative W9→W12：红条按钮曾接 executeSql（执行链，会被同一闸门再次阻断，
        // 表现为「点了没反应」）；W12 定案改指向平台原生能力——打开 Database 工具窗口换源。
        val gateIdx = code.indexOf("public static void showDialectGateError(")
        assertTrue("须有方言闸红条入口（W9）", gateIdx >= 0)
        val body = code.substring(gateIdx, (gateIdx + 2400).coerceAtMost(code.length))
        assertTrue(
            "红条换源动作须走 openDatabaseToolWindow（W12）",
            body.contains("openDatabaseToolWindow(proj"),
        )
        assertTrue(
            "红条换源动作不得走 executeSql 执行链（W9 定例）",
            !body.contains("executeSql(p, true)"),
        )
    }
}
