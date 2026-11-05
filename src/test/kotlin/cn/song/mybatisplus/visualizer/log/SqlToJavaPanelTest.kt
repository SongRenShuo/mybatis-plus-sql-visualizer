package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator
import cn.song.mybatisplus.visualizer.reverse.SqlToJavaConverter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * 「SQL → Java」tab 面板行为测试（T-M36.4；T-M37 P1 扩展）。
 *
 * Light fixture 下项目无实体类 → 实体查找返回空 → 回落字符串列名 QueryWrapper 路径可测；
 * 异步路径（AUTO 形态后台查找实体）用轮询等待（UIUtil.dispatchAllInvocationEvents +
 * 超时兜底）；QUERY 形态为同步路径（直测）。
 *
 * 覆盖边界说明（T-M37 P1；T-FixEntityAmbiguity 收窄）：
 *  - 副表多候选弹 [SqlSelectionDialog] 的 UI 交互在 Light 下无法真实弹出（无实体 → 候选恒空），
 *    面板级仅覆盖候选为空 → 回落路径；多候选逻辑（findSubEntityCandidates 候选列表/别名键）在
 *    converter 层直测（本项目测试工程无实体类，候选列表恒为空）。
 *  - 主表多候选「开关关闭静默取首 + 状态栏明示候选」（T-FixEntityAmbiguity）已补面板级测试
 *    （testMultipleCandidatesManualOffShowsAmbiguousStatus）——Light 索引不可用无法真实实体夹具
 *    （M27 实证，见 EntityClassFinderHeavyTest 头注），经 [SqlToJavaPanel.renderForTest] 缝注入
 *    双 mock 候选直调 render；开关开启的弹窗分支仍属纯 UI 分发，靠代码走查 + 真机走查兜底。
 */
class SqlToJavaPanelTest : BasePlatformTestCase() {

    private lateinit var panel: SqlToJavaPanel

    override fun setUp() {
        super.setUp()
        panel = SqlToJavaPanel(project)
    }

    override fun tearDown() {
        panel.dispose()
        super.tearDown()
    }

    private fun awaitResult(): String {
        var result = panel.resultTextForTest()
        var waited = 0
        while (result.isBlank() && waited < 100) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
            result = panel.resultTextForTest()
            waited++
        }
        return result
    }

    /** 等待状态行出现目标文本（T-TestPerf7 S2：解析失败路径只更新状态不写结果区，
     *  等结果文本的用例会恒空转到 5s 轮询上限——等本用例真正等待的信号，断言零改动）。 */
    private fun awaitStatusContains(substring: String) {
        var waited = 0
        while (!panel.statusForTest().contains(substring) && waited < 100) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
            waited++
        }
    }

    /** 等待结果区包含目标文本（区分新旧结果——切换形态重生成的异步路径）。 */
    private fun awaitContains(substring: String): String {
        var result = panel.resultTextForTest()
        var waited = 0
        while (!result.contains(substring) && waited < 100) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
            result = panel.resultTextForTest()
            waited++
        }
        return result
    }

    fun testConvertNoEntityFallsBackToQueryWrapper() {
        // 手动选择开关关闭 → 0 候选直接回落（开关开启的弹窗路径由 EntitySelectionDialogTest 覆盖）
        val state = PluginSettings.getInstance().state
        val original = state.reverseManualEntitySelection
        state.reverseManualEntitySelection = false
        try {
            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'")
            panel.convertForTest()
            val result = awaitResult()
            assertTrue(result, result.contains("new QueryWrapper<User>()"))
            assertTrue(result, result.contains(".eq(\"name\", \"张三\")"))
            assertTrue(panel.statusForTest(), panel.statusForTest().contains("未找到对应实体"))
        } finally {
            state.reverseManualEntitySelection = original
        }
    }

    fun testConvertParseErrorShowsStatus() {
        panel.inputEditorForTest().setSql("SELECT * FROM t WHERE a = 'abc")
        panel.convertForTest()
        // T-TestPerf7 S2：本用例等的是「状态行报错」这一信号（结果区恒空是预期形态），
        // 原先 awaitResult 等结果文本每轮烧满 5s 上限（实测 6.4s vs 本类 p50 0.13s）；
        // 改等状态行信号，断言与失败形态零改动——生产回归（状态不出错文案）时照样超时后红
        awaitStatusContains("无法解析")
        assertEquals("", panel.resultTextForTest())
        assertTrue(panel.statusForTest(), panel.statusForTest().contains("无法解析"))
    }

    fun testConvertQueryFormatSyncPath() {
        // QUERY 形态：跳过实体查找（同步路径直测）
        val state = PluginSettings.getInstance().state
        val original = state.reverseWrapperFormat
        state.reverseWrapperFormat = PluginSettings.REVERSE_QUERY
        try {
            panel.inputEditorForTest().setSql("SELECT * FROM user WHERE age > 18")
            panel.convertForTest()
            val result = panel.resultTextForTest()
            assertTrue(result, result.contains("new QueryWrapper<User>()"))
            assertTrue(result, result.contains(".gt(\"age\", 18)"))
        } finally {
            state.reverseWrapperFormat = original
        }
    }

    fun testFormatInputSql() {
        val raw = "SELECT * FROM user WHERE a = 1 AND b = 2"
        panel.inputEditorForTest().setSql(raw)
        // 格式化按钮走 FormatAction——直接验证 SqlFormatter 幂等接入（按钮触发同路径）
        val formatted = cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter.format(raw)
        panel.inputEditorForTest().setSql(formatted)
        panel.convertForTest()
        val result = awaitResult()
        assertTrue(result, result.contains("new QueryWrapper<User>()"))
    }

    fun testPanelDisposeReleasesEditors() {
        // dispose 幂等（重复调用不抛）
        panel.dispose()
        panel.dispose()
    }

    fun testBundleKeysPresent() {
        // tab 标题与按钮文案 bundle key 存在（防拼写回归）
        assertTrue(MpSqlVisualizerBundle.message("log.tab.sqlToJava").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("reverse.panel.convert").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("reverse.panel.status.noEntity").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("reverse.panel.copy").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("reverse.panel.formatCode").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("reverse.copy.done").isNotBlank())
        // T-M37 P1：日志列表右键「发送到 SQL → Java 转换」key（中英 bundle 均存在）
        assertTrue(MpSqlVisualizerBundle.message("log.panel.sendToReverse").isNotBlank())
    }

    // ------------------------------------------------------------------
    // 日志列表联动 setInputSql（T-M37 P1）
    // ------------------------------------------------------------------

    fun testSetInputSqlWritesInputArea() {
        val sql = "SELECT id FROM user WHERE id = 1"
        panel.setInputSql(sql)
        // 写入输入区 + 清空结果区与状态行提示
        assertEquals(sql, panel.inputEditorForTest().text)
        assertEquals("", panel.resultTextForTest())
        assertEquals(" ", panel.statusForTest())
    }

    fun testSetInputSqlThenConvertGeneratesResult() {
        // 发送 SQL 后用户点「转换」→ 完整链路产出（输入区已写入，无需再手动 setSql）
        panel.setInputSql("SELECT id, name FROM user WHERE name = '张三'")
        panel.convertForTest()
        val result = awaitResult()
        assertTrue(result, result.contains("new QueryWrapper<User>()"))
        assertTrue(result, result.contains(".eq(\"name\", \"张三\")"))
    }

    // ------------------------------------------------------------------
    // 副表多候选（T-M37 P1）——Light 无实体 → 回落路径 + converter 层直测
    // ------------------------------------------------------------------

    fun testConvertJoinFallsBackToMpjQueryWithoutEntities() {
        // 无实体：手动选择开关关闭 → 副表候选为空 → 跳过（该表列回落字符串），主表也无候选 → MPJ_QUERY raw join
        val state = PluginSettings.getInstance().state
        val original = state.reverseManualEntitySelection
        state.reverseManualEntitySelection = false
        try {
            panel.inputEditorForTest().setSql("SELECT t.id, t1.city FROM user t LEFT JOIN address t1 ON t1.user_id = t.id WHERE t.status = 1")
            panel.convertForTest()
            val result = awaitResult()
            assertTrue(result, result.contains("new MPJQueryWrapper<>"))
            assertTrue(result, result.contains("leftJoin(\"address t1 ON t1.user_id = t.id\")"))
        } finally {
            state.reverseManualEntitySelection = original
        }
    }

    fun testPickCallerWithoutMpjOnJoinShowsStatusNotThrow() {
        // T-PickMpjErr：多表 JOIN + 无 MPJ 能力调用类 → 生成期 SqlReverseException 须转状态栏
        // 错误提示（真机曾从选类 elementChosen 逃逸成 EDT UnhandledException 红叹号）
        val state = PluginSettings.getInstance().state
        val original = state.reverseManualEntitySelection
        state.reverseManualEntitySelection = false
        try {
            panel.inputEditorForTest().setSql("SELECT t.id, t1.city FROM user t LEFT JOIN address t1 ON t1.user_id = t.id WHERE t.status = 1")
            panel.convertForTest()
            val fallback = awaitResult()
            assertTrue(fallback, fallback.contains("new MPJQueryWrapper<>"))
            // 选中无 MPJ 能力的调用类（Light 环境无 MPJ 索引，callerMpj=false）→ 重渲染触发业务拒绝
            // ——T-FixMpjStale 起重生成异步化，轮询等状态行错误提示
            panel.callerFieldForTest().pickForTest("UserService", "com.demo.UserService")
            awaitStatusContains("无 MPJ 能力")
            assertTrue(panel.statusForTest(), panel.statusForTest().contains("暂不支持的 SQL 结构"))
            assertTrue(panel.statusForTest(), panel.statusForTest().contains("无 MPJ 能力"))
            // 结果区保留上次成功内容，未被错误分支改写
            assertTrue(panel.resultTextForTest(), panel.resultTextForTest().contains("new MPJQueryWrapper<>"))
        } finally {
            state.reverseManualEntitySelection = original
            panel.callerFieldForTest().clearForTest()
        }
    }

    fun testFindSubEntityCandidatesKeysByAlias() {
        val result = SqlToJavaConverter.parse(
            "SELECT t.id, t1.city FROM user t LEFT JOIN address t1 ON t1.user_id = t.id",
        )
        assertEquals(1, result.joins.size)
        val candidates = ReadAction.compute<Map<String, List<SqlToJavaConverter.EntityCandidate>>, Throwable> {
            SqlToJavaConverter.findSubEntityCandidates(project, result.joins)
        }
        // 别名键存在（Light 无实体 → 空列表；面板层据此回落字符串列名）
        assertTrue(candidates.containsKey("t1"))
        assertTrue(candidates["t1"].orEmpty().isEmpty())
        // 向后兼容：findSubEntities 仍返回同语义结果（空副实体表）
        val sub = ReadAction.compute<Map<String, JavaCodeGenerator.EntityTarget>, Throwable> {
            SqlToJavaConverter.findSubEntities(project, result.joins)
        }
        assertTrue(sub.isEmpty())
    }

    fun testFindSubEntityCandidatesSkipsNoAliasJoin() {
        // 无别名的 join 不归属（P1 默认别名规则）→ 结果 map 为空
        val result = SqlToJavaConverter.parse(
            "SELECT t.id, a.city FROM user t LEFT JOIN address ON a.user_id = t.id",
        )
        assertTrue(result.joins.isNotEmpty())
        val candidates = ReadAction.compute<Map<String, List<SqlToJavaConverter.EntityCandidate>>, Throwable> {
            SqlToJavaConverter.findSubEntityCandidates(project, result.joins)
        }
        assertTrue(candidates.isEmpty())
    }

    // ------------------------------------------------------------------
    // T-M49：唯一可推断时跳过实体选择弹窗
    // ------------------------------------------------------------------

    @Test
    fun `test canAutoInferEntitySelection_uniqueMainAndSingleSub_returnsTrue`() {
        // 主表 1 候选 + 每个副表各 1 候选 → 唯一可推断 → 跳过弹窗
        val mainCandidates = listOf(mockCandidate("User"))
        val subCandidates = mapOf("t1" to listOf(mockCandidate("Address")), "t2" to listOf(mockCandidate("Order")))
        assertTrue(
            "主表 1 候选 + 所有副表各 1 候选应唯一可推断",
            panel.canAutoInferEntitySelection(mainCandidates, subCandidates),
        )
    }

    @Test
    fun `test canAutoInferEntitySelection_multipleMainCandidates_returnsFalse`() {
        // 主表多候选 → 有歧义 → 弹窗
        val mainCandidates = listOf(mockCandidate("User"), mockCandidate("UserEntity"))
        val subCandidates = mapOf("t1" to listOf(mockCandidate("Address")))
        assertTrue(
            "主表多候选应有歧义（弹窗）",
            !panel.canAutoInferEntitySelection(mainCandidates, subCandidates),
        )
    }

    @Test
    fun `test canAutoInferEntitySelection_multipleSubCandidates_returnsFalse`() {
        // 副表多候选 → 有歧义 → 弹窗
        val mainCandidates = listOf(mockCandidate("User"))
        val subCandidates = mapOf("t1" to listOf(mockCandidate("Address"), mockCandidate("AddressEntity")))
        assertTrue(
            "副表多候选应有歧义（弹窗）",
            !panel.canAutoInferEntitySelection(mainCandidates, subCandidates),
        )
    }

    @Test
    fun `test canAutoInferEntitySelection_emptySubCandidates_returnsFalse`() {
        // 副表 0 候选 → 不可推断 → 弹窗
        val mainCandidates = listOf(mockCandidate("User"))
        val subCandidates = mapOf("t1" to emptyList<SqlToJavaConverter.EntityCandidate>())
        assertTrue(
            "副表 0 候选应弹窗",
            !panel.canAutoInferEntitySelection(mainCandidates, subCandidates),
        )
    }

    @Test
    fun `test canAutoInferEntitySelection_noSubTables_returnsTrue`() {
        // 主表 1 候选 + 无副表（空 map）→ all 对空集合返回 true → 唯一可推断
        val mainCandidates = listOf(mockCandidate("User"))
        assertTrue(
            "主表 1 候选 + 无副表应唯一可推断",
            panel.canAutoInferEntitySelection(mainCandidates, emptyMap()),
        )
    }

    /** 构造轻量 EntityCandidate（Proxy PsiClass 仅 getName 有值；Kotlin `.name` 编译为 getName()）。 */
    private fun mockCandidate(className: String): SqlToJavaConverter.EntityCandidate =
        SqlToJavaConverter.EntityCandidate(
            psiClass = java.lang.reflect.Proxy.newProxyInstance(
                com.intellij.psi.PsiClass::class.java.classLoader,
                arrayOf(com.intellij.psi.PsiClass::class.java),
            ) { _, method, _ ->
                if (method.name == "getName" || method.name == "name") className else null
            } as com.intellij.psi.PsiClass,
            tableInfo = cn.song.mybatisplus.visualizer.model.TableInfo(
                entityClassName = className,
                tableName = "user",
                columns = emptyList(),
            ),
        )

    // ------------------------------------------------------------------
    // T-FixEntityAmbiguity：多候选静默取首的状态栏明示（面板级补测——销掉「靠代码走查」缺口）
    // ------------------------------------------------------------------

    fun testMultipleCandidatesManualOffShowsAmbiguousStatus() {
        // 真实实体夹具在 Light 索引不可用（M27 实证，见 EntityClassFinderHeavyTest 头注）——
        // 经 renderForTest 缝直调注入同表双候选。开关关闭 → 不弹窗静默取首生成，但状态栏须
        // 明示全部候选与已选项（ab0d8fb 修复：此前静默取首被真机误判为「映射缺陷」）
        val state = PluginSettings.getInstance().state
        val original = state.reverseManualEntitySelection
        state.reverseManualEntitySelection = false
        try {
            val result = SqlToJavaConverter.parse("SELECT id, name FROM user WHERE name = '张三'")
            val candidates = listOf(mockCandidate("User"), mockCandidate("UserEntity"))
            panel.renderForTest(result, candidates, cn.song.mybatisplus.visualizer.reverse.ReverseWrapperFormat.QUERY)
            val status = panel.statusForTest()
            assertTrue(status, status.contains("匹配到多个实体"))
            assertTrue(status, status.contains("User、UserEntity"))
            assertTrue(status, status.contains("已选 User"))
            // 取首生成 = 字段子集实体的字符串列名回落形态（真机误判「映射缺陷」的来源样貌）
            val code = panel.resultTextForTest()
            assertTrue(code, code.contains("new QueryWrapper<User>()"))
            assertTrue(code, code.contains(".eq(\"name\", \"张三\")"))
        } finally {
            state.reverseManualEntitySelection = original
        }
    }

    // ------------------------------------------------------------------
    // 形态下拉（T-M36.6）
    // ------------------------------------------------------------------

    fun testOptionsRowUsesStretchLayout() {
        // T-M39 增量：选项（形态下拉/调用类/VO）独立成行 GridBag 拉伸——
        // 防回退到原 BorderLayout.EAST + FlowLayout 三控件挤一行（窄窗口下拉被挤压截断）
        val combo = panel.formatComboForTest()
        val row = combo.parent
        assertTrue("选项行应为 GridBag 拉伸布局（窄窗口各自收缩互不挤压）", row.layout is java.awt.GridBagLayout)
        assertEquals("调用类输入框应位于同一选项行", row, panel.callerFieldForTest().parent)
        assertEquals("VO 输入框应位于同一选项行", row, panel.voFieldForTest().parent)
    }

    fun testFormatComboCellTooltipShowsFullLabel() {
        // T-M39 五次增量：弹出列表长选项截断时 hover 应显示完整文案（JList cell renderer tooltip）
        val combo = panel.formatComboForTest()
        val renderer = combo.renderer as ListCellRenderer<Any>
        for (i in 0 until combo.model.size) {
            val option = combo.model.getElementAt(i)
            // DefaultListCellRenderer 要求非 null list（读 componentOrientation）
            val cell = renderer.getListCellRendererComponent(JList<Any>(), option, i, false, false)
            assertEquals("第 $i 项 tooltip 应为完整文案", option.toString(), (cell as javax.swing.JComponent).toolTipText)
        }
    }

    fun testFormatComboTooltipTracksSelection() {
        // 选中态（关闭态）截断时 hover 显示完整文案——combo tooltip 跟随选中项
        val combo = panel.formatComboForTest()
        assertEquals("初始选中项 tooltip 应为完整文案", combo.selectedItem.toString(), combo.toolTipText)
        combo.selectedItem = combo.model.getElementAt(combo.model.size - 1)
        assertEquals("切换后 tooltip 应跟随", combo.selectedItem.toString(), combo.toolTipText)
    }

    fun testClassPickerPlaceholderTooltip() {
        // 占位文案截断时 hover 显示完整提示；选中后不遮蔽字段级全限定名 tooltip
        val caller = panel.callerFieldForTest()
        assertNull(caller.toolTipText)
        assertEquals(
            "占位态文本标签 tooltip 应为完整占位文案",
            MpSqlVisualizerBundle.message("reverse.panel.callerHint"),
            caller.textTooltipForTest(),
        )
        caller.pickForTest("UserService", "com.demo.UserService")
        assertNull("选中态文本标签不应有 tooltip（字段级全限定名不遮蔽）", caller.textTooltipForTest())
    }

    fun testFormatComboInitialFromSettings() {
        val state = PluginSettings.getInstance().state
        val original = state.reverseWrapperFormat
        try {
            state.reverseWrapperFormat = PluginSettings.REVERSE_WRAPPERS
            // 面板重建后下拉应反映设置值（创建时 refreshFormatFromSettings）
            val panel2 = SqlToJavaPanel(project)
            try {
                assertEquals(PluginSettings.REVERSE_WRAPPERS, panel2.selectedFormatForTest())
            } finally {
                panel2.dispose()
            }
        } finally {
            state.reverseWrapperFormat = original
        }
    }

    fun testFormatComboWriteThroughAndRegenerate() {
        val state = PluginSettings.getInstance().state
        val original = state.reverseWrapperFormat
        try {
            state.reverseWrapperFormat = PluginSettings.REVERSE_QUERY
            panel.refreshFormatFromSettings()
            assertEquals(PluginSettings.REVERSE_QUERY, panel.selectedFormatForTest())

            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'")
            panel.convertForTest()
            val queryResult = panel.resultTextForTest()
            assertTrue(queryResult, queryResult.contains("new QueryWrapper<User>()"))

            // 模拟用户下拉选择 WRAPPERS → 写穿设置 + 立即重生成（无实体 → Wrappers.query 字符串形态）
            panel.selectFormatForTest(PluginSettings.REVERSE_WRAPPERS)
            assertEquals(PluginSettings.REVERSE_WRAPPERS, state.reverseWrapperFormat)
            val wrappersResult = awaitContains("Wrappers.<User>query()")
            assertTrue(wrappersResult, wrappersResult.contains(".eq(\"name\", \"张三\")"))

            // 再切 LAMBDA（无实体 → 回落 new QueryWrapper；无候选缓存 → 异步完整流程）
            panel.selectFormatForTest(PluginSettings.REVERSE_LAMBDA)
            assertEquals(PluginSettings.REVERSE_LAMBDA, state.reverseWrapperFormat)
            val lambdaResult = awaitContains("new QueryWrapper<User>()")
            assertTrue(lambdaResult, lambdaResult.contains(".eq(\"name\", \"张三\")"))
        } finally {
            state.reverseWrapperFormat = original
        }
    }

    // ------------------------------------------------------------------
    // 调用类 / VO（T-M36.7；T-M39 增量改类选择器）
    // ------------------------------------------------------------------

    fun testClassPickerSelectAndClear() {
        val caller = panel.callerFieldForTest()
        // 初始：空值 → 占位提示 + 无类图标
        assertEquals("", caller.text)
        assertNull("空值不应显示类图标", caller.iconForTest())
        // 模拟 Goto Class 选择器选中（类图标 + 简单类名 + tooltip 全限定名）
        caller.pickForTest("UserService", "com.demo.UserService")
        assertEquals("UserService", caller.text)
        assertEquals("com.demo.UserService", caller.toolTipText)
        assertNotNull("选中后应显示类图标（IDEA 原生观感）", caller.iconForTest())
        // 模拟 ✕ 清除
        caller.clearForTest()
        assertEquals("", caller.text)
        assertNull(caller.toolTipText)
        assertNull(caller.iconForTest())
    }

    fun testClassPickerWholeFieldClickable() {
        // 整块可点：iconLabel/textLabel/按钮宿主均挂鼠标监听 + 手型光标（Swing 事件不冒泡——
        // 仅挂父面板时子组件区域点击失效，用户反馈「只能点三点」）
        val targets = panel.pickerClickTargetsForTest()
        assertEquals("可点击子组件应为 3 个（图标 + 文本 + 按钮宿主）", 3, targets.size)
        targets.forEach { target ->
            assertTrue("${target.javaClass.simpleName} 应挂鼠标监听", target.mouseListeners.isNotEmpty())
            assertEquals(
                "${target.javaClass.simpleName} 应有手型光标",
                java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR).type,
                target.cursor.type,
            )
        }
        // 清除按钮 12×12 紧凑尺寸（16px 偏大反馈）
        val button = panel.pickerActionButtonForTest()
        assertEquals(12, button.preferredSize.width)
        assertEquals(12, button.preferredSize.height)
        assertEquals(12, button.icon.iconWidth)
    }

    fun testHintLabelsShowWhenEmptyAndHideOnContent() {
        // 空文档 → 提示可见且灰字；输入 SQL 后 → 隐藏（结果区同理）
        assertTrue("初始输入区空 → 提示应可见", panel.inputHintLabelForTest().isVisible)
        assertTrue("初始结果区空 → 提示应可见", panel.resultHintLabelForTest().isVisible)
        panel.inputEditorForTest().setSql("SELECT id FROM user")
        assertTrue("输入 SQL 后输入区提示应隐藏", !panel.inputHintLabelForTest().isVisible)
        assertTrue("结果区仍空 → 提示保持可见", panel.resultHintLabelForTest().isVisible)
        panel.inputEditorForTest().setSql("")
        assertTrue("清空输入 → 提示恢复可见", panel.inputHintLabelForTest().isVisible)
    }

    fun testClassPickerSelectionRegenerates() {
        val state = PluginSettings.getInstance().state
        val original = state.reverseWrapperFormat
        try {
            state.reverseWrapperFormat = PluginSettings.REVERSE_QUERY
            panel.refreshFormatFromSettings()
            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'")
            panel.convertForTest()
            assertTrue(panel.resultTextForTest().contains("new QueryWrapper"))
            // 选中调用类 → 自动重新生成（已有转换结果时；含调用语句）——T-FixMpjStale 起为
            // 后台反查 MPJ 能力后异步重生成，轮询等目标片段
            panel.callerFieldForTest().pickForTest("UserService", "com.demo.UserService")
            val result = awaitContains("userService.list")
            // 清除 → 回到纯表达式
            panel.callerFieldForTest().clearForTest()
            val plain = panel.resultTextForTest()
            assertTrue(plain, !plain.contains("UserService"))
        } finally {
            state.reverseWrapperFormat = original
        }
    }
}