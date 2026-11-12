package cn.song.mybatisplus.visualizer.ui.inlay

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel
// T-M53-II D2-5：`showResult`/`clear` 已搬为 ui 包内的同包扩展函数（原 SqlPreviewPanel 成员），
// 跨包（ui.inlay）调用点需显式导入。
import cn.song.mybatisplus.visualizer.ui.clear
import cn.song.mybatisplus.visualizer.ui.showResult
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import cn.song.mybatisplus.visualizer.xml.XmlStatementKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * 预览面板的语句类型载体接线测试（T-InsertColumnInlay O-44b）。
 *
 * provider 侧的作用域/闸门由 [PreviewInsertColumnInlayProviderTest] 钉住，本类钉**主路径的另一端**：
 * 面板在真实渲染链上是否真把 `xmlModel.kind` 写进了预览编辑器（Editor + PSI 文件双载体）。
 *
 * 为什么单列一类：这条接线是「inlay 静默不出现」的唯一剩余入口——锚点写错会泄漏（有用例拦），
 * 而**载体没写**只会表现为真机上什么都不显示、零异常零日志（计划 §1.2 订正记的正是这一类症状）。
 * 载体在两条渲染路径上都要写（新建 `assemblePreviewEditor` / 复用 `refreshCompletionContext`），
 * 故用例既覆盖首次预览（新建编辑器），也覆盖**不 clear 的二次预览**（编辑器复用路径）。
 *
 * 生命周期照 `EditorWhitespaceSettingsTest` 既有形态（单面板 + 收尾 `clear()`，不 `dispose()`——
 * 建两个面板各 dispose 一次会撞 `SqlExecutor.releaseForPanel()` 单例的重复释放）。
 */
class InsertColumnInlayPanelWiringTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // T-PreviewInlayNativeYield：测试平台（IU 2024.3.7.1）上 DatabaseTools 的 SQL 列名 inlay 在位，
        // 让位门会整体短路 provider。本类验的是面板装配（语句类型载体 → INSERT 才出提示），
        // 故先钉死「原生不在位」；让位门自身由 NativeColumnInlayYieldGateTest 覆盖。
        NativeColumnInlayYieldGate.resetForTest()
        NativeColumnInlayYieldGate.probe = { false }
    }

    override fun tearDown() {
        try {
            NativeColumnInlayYieldGate.resetForTest()
        } finally {
            super.tearDown()
        }
    }

    private val insertMapper = """
        <mapper namespace="com.example.BizMapper">
          <insert id="insertOne">
            INSERT INTO t_biz (id, name) VALUES (#{id}, #{name})
          </insert>
        </mapper>
    """.trimIndent()

    private val selectMapper = """
        <mapper namespace="com.example.BizMapper">
          <select id="selectOne" resultType="map">
            SELECT id, name FROM t_biz
          </select>
        </mapper>
    """.trimIndent()

    /** 走真实 XML 预览链取 PreviewResult（照 XmlPreviewPanelTest 的 xmlResultOf 形态）。 */
    private fun xmlResult(source: String): PreviewResult {
        val parsed = XmlMapperParser().parse(source)
        val model = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        val rendered = model.renderSql()
        return PreviewResult(
            sql = rendered.sql,
            compactSql = rendered.sql.replace(Regex("\\s+"), " ").trim(),
            unresolvedParams = emptyList(),
            paramTable = null,
            wrapperType = null,
            tableName = null,
            xmlModel = model,
        )
    }

    private fun panelKind(panel: SqlPreviewPanel): XmlStatementKind? =
        PreviewInlayScope.previewStatementKind(panel.psiFileForTest(), panel.editorForTest())

    fun testINSERT预览把语句类型写进编辑器与PSI双载体() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(xmlResult(insertMapper))
            assertEquals(
                "面板须把 xmlModel.kind 写进预览编辑器（Editor 优先、PSI 文件兜底）——" +
                    "没写则真机上 inlay 静默不出现（零异常零日志）",
                XmlStatementKind.INSERT,
                panelKind(panel),
            )
        } finally {
            panel.clear()
        }
    }

    fun test非INSERT预览不写INSERT载体() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(xmlResult(selectMapper))
            assertEquals(
                "非 INSERT 预览的载体不得是 INSERT（否则同一编辑器上会按 INSERT 产提示）",
                XmlStatementKind.SELECT,
                panelKind(panel),
            )
        } finally {
            panel.clear()
        }
    }

    fun test二次预览复用编辑器时载体随预览改写() {
        // 不 clear：showSql 走 existing 分支复用既有编辑器，载体须由 refreshCompletionContext 改写——
        // 漏写即「INSERT 预览后切 SELECT 预览，旧编辑器仍按 INSERT 产提示」
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(xmlResult(insertMapper))
            assertEquals("首次预览（新建编辑器路径）", XmlStatementKind.INSERT, panelKind(panel))
            panel.showResult(xmlResult(selectMapper))
            assertEquals("二次预览（编辑器复用路径）", XmlStatementKind.SELECT, panelKind(panel))
        } finally {
            panel.clear()
        }
    }
}
