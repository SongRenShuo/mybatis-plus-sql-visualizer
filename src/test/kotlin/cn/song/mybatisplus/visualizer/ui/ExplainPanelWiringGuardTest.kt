// 自 master src/test/kotlin/.../ui/ExplainPanelWiringGuardTest.kt 移植
// （k2j 守卫适配：扫描根 src/main/java + .java、面板全体 SqlPreviewPanel* 源文件并集；
//   函数体标记改 Java 声明形态；具名实参断言改 Java 位置实参/局部变量等价形态）
package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R33 EXPLAIN 结构化通道**接线守卫**（源码扫描，照 McpErrorPositionTextTest 先例）。
 *
 * 只钉纯判定（ExplainPanelChannelTest）不钉接线的话，把 `SqlPreviewPanel.explainSql` 里的
 * 分派调用删了判定就是死代码（空绿）——本守卫钉四件事：
 *  1. explainSql 真的经 `ExplainPanelChannel.decide` 分派；
 *  2. 直送通道收敛为 `submitExplainDirect` 单函数且**至少两处复用**（初始回退 + 回到原生
 *     控制台入口——双通道并存的守恒判据：结构化通道绝不成为唯一路径）；
 *  3. 结构化视图经非模态对话框展示（ExplainPlanDialog/showExplainDialog），不替换编辑器；
 *  4. 索引建议真的接入面板侧消费（ExplainIndexAdvisor.advise 在 SqlPreviewPanel 有调用点）。
 */
class ExplainPanelWiringGuardTest {

    @Test
    fun `面板explainSql经过通道判定`() {
        val source = sqlPreviewPanelSource()
        assertTrue(
            "SqlPreviewPanel.explainSql 必须经 ExplainPanelChannel.decide 分派（否则判定成死代码）",
            source.contains("ExplainPanelChannel.decide"),
        )
    }

    @Test
    fun `直送通道收敛单函数且回退与入口两处复用`() {
        val source = sqlPreviewPanelSource()
        val occurrences = Regex("submitExplainDirect\\(").findAll(source).count()
        assertTrue(
            "submitExplainDirect 必须收敛直送链并被回退+回到控制台入口两处复用（实际 $occurrences 处）",
            occurrences >= 3, // 定义 1 + 调用 ≥ 2
        )
        assertTrue(
            "直送链必须保留既有 SqlExecutor.execute 行为（双通道并存，不替换）",
            source.contains("SqlExecutor.execute"),
        )
    }

    @Test
    fun `结构化视图经非模态对话框展示不替换编辑器`() {
        val source = sqlPreviewPanelSource()
        assertTrue(
            "结构化渲染结果必须经 ExplainPlanDialog/showExplainDialog 展示",
            source.contains("new ExplainPlanDialog(") && source.contains("showExplainDialog("),
        )
        assertFalse(
            "结构化路径不得再调用 embedExplainView 替换预览编辑器",
            source.contains("embedExplainView"),
        )
    }

    @Test
    fun `索引建议接入面板侧消费`() {
        val source = sqlPreviewPanelSource()
        assertTrue(
            "ExplainIndexAdvisor.advise 必须在面板侧有消费点（联动建议条）",
            source.contains("ExplainIndexAdvisor.advise"),
        )
    }

    /**
     * R33 真机走查修复接线钉：结构化探测前必须做未回填 `?` 占位探测并显式传给判定——
     * `hasUnfilledParams` 是带默认值的参数（漏传编译不红），删掉这行接线判定层就是死参数，
     * 语法错场景会重新滑进结构化探测（真机 `SQLException: … SQL syntax` 复现）。
     */
    @Test
    fun `结构化探测前钉占位回填检查`() {
        val source = sqlPreviewPanelSource()
        // k2j：Java 门面无具名实参——占位探测以局部变量承载（master：
        // hasUnfilledParams = placeholderPositions(explainSql).isNotEmpty()），decide 调用按位置传参
        assertTrue(
            "submitExplainOrStructured 必须用串感知占位探测喂 hasUnfilledParams（否则残留 ? 重新下发探测）",
            source.contains("boolean hasUnfilledParams = !PlaceholderPositionsKt.placeholderPositions(explainSql).isEmpty();") &&
                source.contains("hasUnfilledParams);"),
        )
    }

    /**
     * T-M53-II D2-5：面板的组合式重构把 EXPLAIN 分派链（explainSql / submitExplainDirect /
     * submitExplainOrStructured / embedExplainView）逐字符搬出到同包协作文件，故读取面取
     * **面板全体源文件**的并集——只读主文件会在成员搬走后变成空扫描（断言假红或假绿的同一根因）。
     * R61 接线钉：跨次对比必须真的在结构化 onPlan 链上——基线存储（lastStructuredExplain）+
     * 对比计算（ExplainPlanDiff.diff）+ 基线滚动更新 + 视图入参（diff = planDiff）四件齐全；
     * 摘掉任一件对比条即静默消失（无编译期保护的 wiring 面，同 hasUnfilledParams 教训）。
     */
    @Test
    fun `R61_跨次对比接线在位`() {
        val source = sqlPreviewPanelSource()
        assertTrue(
            "结构化 onPlan 必须计算跨次 diff（否则对比逻辑成死代码）",
            source.contains("ExplainPlanDiff.diff("),
        )
        assertTrue(
            "会话级基线必须滚动更新（否则永远比第一次）",
            source.contains("p.lastStructuredExplain = new kotlin.Pair<>(explainSql, outcome.raw());"),
        )
        assertTrue(
            "diff 必须经构造参数进结构化视图（否则对比条永不出现）",
            source.contains("PlanDiff planDiff = baselines.isEmpty()") && source.contains("planDiff,"),
        )
    }

    @Test
    fun `R90_真实提交捕获上下文且成功回调接持久化与选择器`() {
        val source = sqlPreviewPanelSource()
        val begin = source.indexOf("captureExplainPlanHistory(p, history, planKey, ds);")
        val submit = source.indexOf("ExplainPanelChannel.executeStructured(", begin)
        val complete = source.indexOf("completeExplainPlanHistory(p, historyF, historyRequest, outcome.raw());", submit)
        assertTrue("上下文必须在提交前固定并由成功回调消费", begin >= 0 && submit > begin && complete > submit)
        assertTrue(source.contains("originalPreview != null ? originalPreview.getHistoryEntryId() : \"\""))
        assertTrue(source.contains("return p.explainPlanHistorySession.begin("))
        assertTrue(source.contains("DataSourceSettings.getUniqueId(ds)"))
        assertTrue(source.contains("boolean retentionEnabled = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance()"))
        assertTrue(source.contains(".getState().getExplainPlanRetentionEnabled());"))
        assertTrue(source.contains("historyCompletion.baselines(),"))
        assertTrue(source.contains("historyCompletion.saved());"))
        val service = File(repoRoot(), "src/main/java/cn/song/mybatisplus/visualizer/ui/SqlPreviewService.java").readText()
        assertTrue(service.contains("result.setHistoryEntryId(manager.add(entry).getId());"))
        assertTrue(service.contains("ExplainPlanKey.projectIdentity("))
    }

    /**
     * T-UI030：无已绑数据源时**直接弹既有数据源选择器**（prepareConsole，只绑定不执行），
     * 不再只发一条「已回退原生控制台」通知。摘掉这行接线，用户又回到「告知失败、没有下一步」。
     */
    @Test
    fun `T_UI030_无数据源直接走既有选择器且只绑定不执行`() {
        val source = sqlPreviewPanelSource()
        assertTrue(
            "NO_DATA_SOURCE 必须真的调用 promptDataSourceThenRetryStructured（只定义函数不算接线）",
            source.contains("promptDataSourceThenRetryStructured(p, proj, dialect, explainSql, dialectGateRequest);"),
        )
        assertTrue(
            "绑定必须复用 DataSourceSelector.prepareConsole（executeAfterPrepare=false：只绑定不执行任何 SQL）",
            source.contains("DataSourceSelector.prepareConsole("),
        )
        assertTrue(
            "重试路径必须显式禁止再弹选择器（防取消→再弹→再取消循环；k2j：位置实参 allowDataSourcePrompt=false）",
            source.contains("dialectGateRequest, false)"),
        )
    }

    /**
     * T-UI030：取消选择的收口走纯判定 GIVE_UP（只给说明、不再弹第二次）。
     * 摘掉这个分支，取消后会被当成「继续等待」或重新拉起选择器。
     */
    @Test
    fun `T_UI030_取消选择由GIVE_UP收口不再弹第二次`() {
        val source = sqlPreviewPanelSource()
        assertTrue(
            "轮询必须消费 dataSourceBindTick 的 GIVE_UP 分支",
            source.contains("GIVE_UP"),
        )
        assertTrue(
            "GIVE_UP 必须调用取消说明（未执行 + 下一步），而不是再次 prepareConsole",
            source.contains("notifyExplainDataSourceNotBound("),
        )
        val guidance = File(
            repoRoot(),
            "src/main/java/cn/song/mybatisplus/visualizer/execute/ExplainFallbackGuidance.java",
        ).readText()
        assertTrue(
            "准备链终结仍未绑定必须判 GIVE_UP（取消即收口）",
            guidance.contains("return DataSourceBindTick.GIVE_UP;"),
        )
    }

    /**
     * T-UI030：开关关闭时通知必须带「启用结构化并重试」动作，且只在方言确有结构化面时出现。
     */
    @Test
    fun `T_UI030_开关关闭通知带启用并重试动作`() {
        val source = sqlPreviewPanelSource()
        assertTrue(
            "开关关闭时必须经纯判定决定是否提示（TEXT/XML/Community 不噪音）",
            source.contains("shouldOfferStructuredEnable("),
        )
        assertTrue(
            "通知必须真的 addAction（不是只有一段说明文字）",
            source.contains(".addAction(") &&
                source.contains("panel.explain.structured.enableAndRetry"),
        )
        assertTrue(
            "动作清单必须由 structuredExplainDisabledActions 生成（空表/死循环不算挂上动作）",
            source.contains("structuredExplainDisabledActions(onEnableAndRetry)"),
        )
        assertTrue(
            "动作回调必须写开关并重试（enableStructuredExplainAndRetry）",
            source.contains("enableStructuredExplainAndRetry(") &&
                source.contains("setExplainStructuredRender(true)"),
        )
    }

    @Test
    fun `打开控制台按钮双语文案在场`() {
        for (path in listOf(
            "src/main/resources/messages/MpSqlVisualizerBundle.properties",
            "src/main/resources/messages/MpSqlVisualizerBundle_zh.properties",
        )) {
            val text = File(repoRoot(), path).readText()
            assertTrue("$path 缺 panel.explain.openConsole（按钮会显示原始 key）", text.contains("panel.explain.openConsole="))
        }
    }

    /**
     * T-PreviewSideQuery P1 接线钉：预估影响行数必须走 [PreviewSideQuery]，且函数体内**不得**
     * 出现换入控制台/执行链调用。只测 DmlImpactSideQueryPanelTest 的 Result 编排不够——
     * 把 `PreviewSideQuery.run` 换成 `embedConsole` 时用例仍绿，正是本批真机修复前的形态。
     */
    @Test
    fun `P1_预估影响行数走旁路查询且不换入控制台`() {
        val source = sqlPreviewPanelSource()
        val estimate = functionBody(source, "public static void estimateDmlImpact(")
        assertTrue(
            "estimateDmlImpact 必须调用 PreviewSideQuery.run（否则又回到控制台执行）",
            estimate.contains("PreviewSideQuery.run("),
        )
        assertFalse(
            "estimateDmlImpact 不得换入控制台（只读试算不能顶掉预览编辑器）",
            estimate.contains("embedConsole(") || estimate.contains("SqlExecutor.execute("),
        )
        val retry = functionBody(source, "public static void promptDataSourceThenRetryImpact(")
        assertTrue(
            "绑定后重试必须显式禁止二次弹选择器（k2j：estimateDmlImpact(p, false) 位置实参）",
            retry.contains("estimateDmlImpact(p, false)"),
        )
        assertTrue(
            "「更多」菜单必须仍挂着预估影响行数入口（否则功能不可达）",
            source.contains("MpSqlVisualizerBundle.message(\"panel.dmlImpact\")"),
        )
    }

    /**
     * T-PreviewSideQuery P3a 接线钉：执行视图头部由 embedConsole(EXECUTE) 添加、由
     * backToPreview 移除。真机曾出现「点返回只重渲染 SQL、头部还在」——当时只测了 SQL 刷新，
     * 没钉头部移除顺序。
     */
    @Test
    fun `P3a_返回预览移除执行视图头部`() {
        val source = sqlPreviewPanelSource()
        val back = functionBody(source, "public static void backToPreview(")
        val remove = back.indexOf("p.consoleHost.remove(p.executeViewHeader);")
        val clear = back.indexOf("p.executeViewHeader = null;")
        val refresh = back.indexOf("SqlPreviewPanelSqlViewKt.refreshView(p);")
        assertTrue(
            "返回预览必须先移除 executeViewHeader（实际源码：$back）",
            remove >= 0 && clear > remove && refresh > clear,
        )
        assertTrue(
            "执行视图头部必须由 embedConsole 在 EXECUTE 模式添加",
            source.contains("executeViewHeader") && source.contains("ConsoleEmbedMode.EXECUTE"),
        )
        for (path in listOf(
            "src/main/resources/messages/MpSqlVisualizerBundle.properties",
            "src/main/resources/messages/MpSqlVisualizerBundle_zh.properties",
        )) {
            val text = File(repoRoot(), path).readText()
            for (key in listOf("panel.executeViewHint", "panel.backToPreview", "dml.impact.inline")) {
                assertTrue("$path 缺 $key（界面会显示原始 key）", text.contains("$key="))
            }
        }
    }

    /**
     * T-ExplainConsoleFirst W4（2026-10-24）：只读动作必须走 `previewSqlForReadonly()`，
     * 不得再裸读 `currentEditorSql()`。
     *
     * 为什么：执行计划回到内嵌原生控制台后，`mpsql-exec.sql` 的 document 会被 EXPLAIN 语句
     * 顶掉，而 `nativeEditorDoc` 指向它 ⇒ `currentEditorSql()` 漂移。只读动作（回滚 SQL /
     * 导出回放 / 复制 AI 上下文 / 导出可移植 SQL / 预估影响行数）若裸读它，用户在执行视图里
     * 点这些按钮会拿到 EXPLAIN 语句而不是自己的 SQL。（`1a1cf724` 当初弃用形态 A 正是这个
     * 原因——不钉住这条，形态 A 重新上线就会回归。）
     *
     * 反向验证：把任一动作改回 `currentEditorSql()` 必红。
     */
    @Test
    fun `W2_只读动作取词必须走预览快照`() {
        val source = sqlPreviewPanelSource()
        val readonlyActions = listOf(
            "public static void showRollbackSql(",
            "public static void exportReplayScript(",
            "public static void copyAsAiContext(",
            "public static void exportPortableSql(",
            "public static void estimateDmlImpact(",
        )
        for (marker in readonlyActions) {
            val body = functionBody(source, marker)
            assertTrue(
                "$marker 必须走 previewSqlForReadonly()（执行视图下才拿得到预览 SQL）",
                body.contains("previewSqlForReadonly(p)"),
            )
            assertFalse(
                "$marker 不得裸读 currentEditorSql()（执行视图下会漂移成 EXPLAIN 语句）",
                body.contains("currentEditorSql(p)"),
            )
        }
        // 执行链本身必须仍读真实编辑器（用户手改要生效），不得走快照
        val execute = functionBody(source, "public static void executeSql(")
        assertTrue(
            "executeSql 必须执行编辑器真实文本（不得用预览快照顶替用户手改）",
            execute.contains("p.nativeEditorDoc") || execute.contains("p.editor"),
        )
    }

    /**
     * T-ExplainConsoleFirst W1（2026-10-24）：形态 B 的旁路 EXPLAIN 不得回流。
     *
     * `submitExplainDirect` 必须转调 `submitExplainDirectConsole`（内嵌原生控制台），
     * 且函数体内不得出现 `PreviewSideQuery.run`——一旦回流，十二轮换源拉锯的根因
     * （插件自建 JDBC 通道 + 平台「选中即关联」短路）就全回来。
     */
    @Test
    fun `W1_执行计划直送不得回流旁路查询`() {
        val source = sqlPreviewPanelSource()
        val direct = functionBody(source, "public static void submitExplainDirect(")
        assertTrue(
            "submitExplainDirect 必须转调 submitExplainDirectConsole（形态 A）",
            direct.contains("submitExplainDirectConsole("),
        )
        assertFalse(
            "submitExplainDirect 不得再调 PreviewSideQuery.run（形态 B 已退役）",
            direct.contains("PreviewSideQuery.run("),
        )
    }

    /**
     * T-ExplainConsoleFirst W8（2026-10-24 沙盒验收实测）：`submitExplainDirectConsole` 进执行链前
     * **必须先预热 exec 临时文件**。
     *
     * 实测症状：点「执行计划」刷 9 条 `SEVERE - SlowOperations ... Plugin to blame: SQL Visualizer`，
     * 栈为 `explainSql → submitExplainDirectConsole → SqlExecutor.execute → chooseAndPrepare →
     * createTempPsiFile → PsiManagerImpl.findFile`——`createTempPsiFile` 的冷路径（VFS refresh +
     * `PsiManager.findFile`）在 EDT 上跑。`1a1cf724` 后 EXPLAIN 走旁路查询不经控制台链，故从未暴露；
     * W1 把 EXPLAIN 切回控制台后它重新进入热路径。
     *
     * 反向验证：删掉预热调用 → 必须红。
     */
    @Test
    fun `W8_执行计划进链前必须预热exec文件`() {
        val body = functionBody(sqlPreviewPanelSource(), "public static void submitExplainDirectConsole(")
        assertTrue(
            "submitExplainDirectConsole 必须预热 exec 文件（否则 createTempPsiFile 冷路径在 EDT 做 VFS/PSI 慢操作）",
            body.contains("prewarmExecTempFileOnBackgroundThread("),
        )
        val prewarmCall = body.indexOf("prewarmExecTempFileOnBackgroundThread(")
        // W8-fix 后结构是**异步续接**：`SqlExecutor.execute` 在 `continueChain` 闭包里（源码位置在前），
        // 预热提交在闭包外、且池线程跑完之后才 `invokeLater(continueChain)`。
        // 故不能按文本位置断言「预热在 execute 之前」——那是首版同步写法才成立的形态。
        // 改钉真正的先后机制（池线程块内：先预热调用、后续接调用）：
        val poolBlock = body.indexOf("executeOnPooledThread")
        assertTrue(
            "预热调用必须位于池线程块内（实际 prewarm=$prewarmCall, poolBlock=$poolBlock）",
            prewarmCall > poolBlock,
        )
        val continueCall = body.indexOf("invokeLater(continueChain")
        assertTrue(
            "预热完成后才续接执行链（continueChain 必须排在预热调用之后：prewarm=$prewarmCall, continue=$continueCall）",
            continueCall > prewarmCall,
        )
        assertTrue(
            "预热必须投在池线程（EDT 上做 VFS refresh/findFile 就是被修的那个问题）",
            body.contains("executeOnPooledThread"),
        )
        // W8-fix：**EDT 上不得阻塞等预热**——首版 `future.get(8s)` 在慢盘/索引期会冻 UI，
        // 且与既有 prewarm 的双层预算等待嵌套。改异步续接后 EDT 上一个 get 都不该有。
        assertFalse(
            "submitExplainDirectConsole 内不得出现 Future.get / 任何阻塞等待（EDT 会被冻住）",
            body.contains(".get(") && body.contains("TimeUnit"),
        )
        assertTrue(
            "预热完成后必须回 EDT 续接执行链（异步续接而非阻塞）",
            body.contains("invokeLater") && body.contains("continueChain"),
        )
        // 直接调 inner 而不是外层 prewarmExecTempFile：后者内部会再 submit 一个池线程并 get(30s)，
        // 从池线程调它就成了「池线程等池线程」的嵌套等待。
        assertFalse(
            "不得从池线程调用外层 prewarmExecTempFile（它内部再 submit + get，形成嵌套等待）",
            body.contains("prewarmExecTempFile("),
        )
        assertTrue(
            "必须走「调用方已在 BGT」的专用入口（同步跑完、不自带预算等待）",
            body.contains("prewarmExecTempFileOnBackgroundThread("),
        )
    }

    /** 截取从 [marker] 开始到下一个同类静态方法声明之前的源码段。 */
    private fun functionBody(source: String, marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("找不到方法声明：$marker", start >= 0)
        val tail = source.substring(start + marker.length)
        val next = listOf("\n    public static", "\n    private static")
            .map { tail.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        return source.substring(start, if (next == null) source.length else start + marker.length + next)
    }

    private fun sqlPreviewPanelSource(): String =
        panelSourceFiles().joinToString("\n") { File(repoRoot(), it).readText() }

    private fun panelSourceFiles(): List<String> =
        File(repoRoot(), "src/main/java/cn/song/mybatisplus/visualizer/ui").listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("SqlPreviewPanel") && it.extension == "java" }
            .map { "src/main/java/cn/song/mybatisplus/visualizer/ui/${it.name}" }
            .sorted()

    /** 仓库根定位（同 McpErrorPositionTextTest：从 user.dir 上溯到含 src/main/java 的目录）。 */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/java").isDirectory) return dir
            dir = dir.parentFile
        }
        error("未定位到仓库根（src/main/java 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }
}
