package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.log.MapperXmlLocator
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisJavaToXmlLineMarkerProvider.resetResolvedCacheForTest

/**
 * k2j 互操作（T-SyncK2JMaster136 B2 移植）：k2j 两 Provider 为 Java 类实现 LineMarkerProvider——
 * getLineMarkerInfo 恒 null、导航全走 collectSlowLineMarkers(List, Collection)（与 master
 * Kotlin 侧同形态）；indexScanCount 在 k2j 为 AtomicInteger（master 侧同义计数器）。
 */
class MapperNavigationLineMarkerProvidersTest : BasePlatformTestCase() {

    private val javaProvider = MapperJavaToXmlLineMarkerProvider()
    /** T-MapperPreviewOrder：方法行预览图标独立 provider（plugin.xml 注册在 javaProvider 之前）。 */
    private val previewProvider = MapperMethodPreviewLineMarkerProvider()
    private val xmlProvider = MapperXmlToJavaLineMarkerProvider()

    override fun setUp() {
        super.setUp()
        MapperXmlToJavaLineMarkerProvider.resetResolvedCacheForTest()
        // T-MapperClassJump：类名→XML 的会话级缓存同样要清，防跨用例命中（同工程同 namespace 串号）
        MapperJavaToXmlLineMarkerProvider.resetClassCacheForTest()
    }

    override fun tearDown() {
        try {
            MapperXmlToJavaLineMarkerProvider.resetResolvedCacheForTest()
            MapperJavaToXmlLineMarkerProvider.resetClassCacheForTest()
        } finally {
            super.tearDown()
        }
    }

    private fun addMapper(): PsiFile = myFixture.addFileToProject(
        "com/demo/UserMapper.java",
        """
        package com.demo;
        public interface UserMapper {
            int count();
            int insertOne();
            int updateOne();
            int deleteOne();
            int missing();
        }
        """.trimIndent(),
    )

    private fun addXml(body: String, namespace: String = "com.demo.UserMapper"): PsiFile =
        myFixture.addFileToProject("mapper/UserMapper.xml", "<mapper namespace=\"$namespace\">\n$body\n</mapper>")

    /**
     * 收集 marker（未过滤全量）。T-MapperMethodSqlPreview 起方法行有「预览 + 跳转」两枚，
     * 基线用例只关心跳转——[markers] 默认过滤预览，[allMarkers] 供双图标用例断言。
     */
    private fun allMarkers(provider: LineMarkerProvider, file: PsiFile): List<LineMarkerInfo<*>> {
        val elements = PsiTreeUtil.collectElements(file) { it.firstChild == null }.toMutableList()
        val result = mutableListOf<LineMarkerInfo<*>>()
        provider.collectSlowLineMarkers(elements, result)
        return result
    }

    /**
     * 收集 marker。T-MapperNavNamespaceJump 起 XML 侧多了一枚「根标签 → 接口类」图标，
     * 既有用例均以语句图标为基线——默认把它过滤掉（[includeRoot] 显式打开供新用例断言）。
     * T-MapperClassJump / T-MapperMethodSqlPreview 的类名图标与方法行预览图标同理默认排除。
     */
    private fun markers(
        provider: LineMarkerProvider,
        file: PsiFile,
        includeRoot: Boolean = false,
    ): List<LineMarkerInfo<*>> {
        val withoutClassAndPreview = allMarkers(provider, file)
            .filterNot(::isMapperClassMarker)
            .filterNot(::isMethodPreviewMarker)
        return if (includeRoot) withoutClassAndPreview else withoutClassAndPreview.filterNot(::isRootMapperMarker)
    }

    private fun isMapperClassMarker(info: LineMarkerInfo<*>): Boolean {
        val id = info.element as? com.intellij.psi.PsiIdentifier ?: return false
        val owner = id.parent as? com.intellij.psi.PsiClass ?: return false
        return owner.nameIdentifier === id
    }

    /** T-MapperMethodSqlPreview：方法行 SQL 预览图标（tooltip 是唯一稳定判据——锚点与跳转同 element）。 */
    private fun isMethodPreviewMarker(info: LineMarkerInfo<*>): Boolean =
        info.lineMarkerTooltip == MpSqlVisualizerBundle.message("gutter.mapperMethodPreview.tooltip")

    private fun isRootMapperMarker(info: LineMarkerInfo<*>): Boolean {
        val token = info.element as? XmlToken ?: return false
        val tag = token.parent as? XmlTag ?: return false
        return tag.name == "mapper" && tag.parentTag == null
    }

    private fun <T : PsiElement> navigate(marker: LineMarkerInfo<T>) {
        marker.navigationHandler!!.navigate(null, marker.element!!)
    }

    fun testJavaMethodNavigatesToMatchingXmlStatement() {
        val java = addMapper()
        val xml = addXml("  <select id=\"count\">SELECT 1</select>")
        val marker = markers(javaProvider, java).single()
        assertSame(MpvsIcons.MapperMethod, marker.icon)
        assertEquals("count", marker.element?.text)

        navigate(marker)
        val manager = FileEditorManager.getInstance(project)
        assertEquals(xml.virtualFile, manager.selectedFiles.single())
        val editor = manager.selectedTextEditor!!
        assertEquals(1, editor.document.getLineNumber(editor.caretModel.offset))
    }

    fun testXmlStatementNavigatesToMatchingJavaMethod() {
        val java = addMapper()
        val xml = addXml("  <select id=\"count\">SELECT 1</select>")
        val marker = markers(xmlProvider, xml).single()
        assertSame(MpvsIcons.MapperStatement, marker.icon)

        navigate(marker)
        val manager = FileEditorManager.getInstance(project)
        assertEquals(java.virtualFile, manager.selectedFiles.single())
        val method = PsiTreeUtil.findChildrenOfType(java, PsiMethod::class.java).first { it.name == "count" }
        assertEquals(method.textOffset, manager.selectedTextEditor!!.caretModel.offset)
    }

    fun testXmlClosingTagsDoNotProduceDuplicateMarkers() {
        addMapper()
        val xml = addXml("<select id=\"count\">\nSELECT 1\n</select>")
        val marker = markers(xmlProvider, xml).single()
        assertEquals(xml.text.indexOf("select"), marker.element!!.textRange.startOffset)
    }

    fun testAllFourStatementKindsProduceBothMarkers() {
        val java = addMapper()
        val xml = addXml(
            """
            <select id="count">SELECT 1</select>
            <insert id="insertOne">INSERT INTO t VALUES (1)</insert>
            <update id="updateOne">UPDATE t SET a = 1</update>
            <delete id="deleteOne">DELETE FROM t</delete>
            """.trimIndent(),
        )
        assertEquals(listOf("count", "insertOne", "updateOne", "deleteOne"),
            markers(javaProvider, java).map { it.element?.text })
        assertEquals(listOf("select", "insert", "update", "delete"),
            markers(xmlProvider, xml).map { it.element?.text })
    }

    fun testMismatchedNamespaceProducesNoMarkers() {
        val java = addMapper()
        val xml = addXml("<select id=\"count\">SELECT 1</select>", "com.demo.OtherMapper")
        assertEmpty(markers(javaProvider, java))
        assertEmpty(markers(xmlProvider, xml))
    }

    fun testMismatchedIdProducesNoMarkers() {
        val java = addMapper()
        val xml = addXml("<select id=\"unknown\">SELECT 1</select>")
        assertEmpty(markers(javaProvider, java))
        assertEmpty(markers(xmlProvider, xml))
    }

    fun testMissingXmlProducesNoJavaMarker() {
        assertEmpty(markers(javaProvider, addMapper()))
    }

    fun testMissingJavaProducesNoXmlMarker() {
        assertEmpty(markers(xmlProvider, addXml("<select id=\"count\">SELECT 1</select>")))
    }

    fun testFragmentsAndNestedStatementsProduceNoXmlMarkers() {
        addMapper()
        val xml = addXml(
            """
            <sql id="count">SELECT 1</sql>
            <resultMap id="count" type="map"/>
            <select id="unknown"><select id="count">SELECT 1</select></select>
            """.trimIndent(),
        )
        assertEmpty(markers(xmlProvider, xml))
    }

    fun testMapperNestedInOtherRootProducesNoXmlMarker() {
        addMapper()
        val xml = myFixture.addFileToProject(
            "other.xml",
            """<page namespace="com.demo.UserMapper"><mapper><select id="count">SELECT 1</select></mapper></page>""",
        )
        assertEmpty(markers(xmlProvider, xml))
    }

    fun testFastPathDoesNotProduceMarkers() {
        // F1''' 起定位走平台 fileBasedIndex（getContainingFiles），fast path 不再持有任何
        // 本插件自建扫描计数缝——本用例保留「fast path 零产出」的行为面（原 indexScanCount
        // 断言随 T-PS5-05 单槽机制退役删除）。
        val java = addMapper()
        val xml = addXml("<select id=\"count\">SELECT 1</select>")
        for ((provider, file) in listOf(javaProvider to java, xmlProvider to xml)) {
            for (element in PsiTreeUtil.collectElements(file) { it.firstChild == null }) {
                assertNull(provider.getLineMarkerInfo(element))
            }
        }
    }

    // ------------------------------------------------------------------
    // F1（T-GutterFirstOpenDelay）：解析失败不摘图标——正缓存判定矩阵
    // dumb 判定走 isDumbProvider 测试缝（承 MbgExampleCallResolver 定例），矩阵与夹具真实
    // dumb 态解耦（夹具方法体在读操作内，waitForSmartMode 不可用）。
    // ------------------------------------------------------------------

    fun testF1ResolveSuccessProducesMarkerAndSeedsPositiveCache() {
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { false }
        val xmlProvider = MapperXmlToJavaLineMarkerProvider()
        addMapper()
        val xml = addXml("<select id=\"count\">SELECT 1</select>")
        val marker = markers(xmlProvider, xml).single()
        assertSame(MpvsIcons.MapperStatement, marker.icon)
        assertTrue(
            "解析成功必须记入会话级正缓存",
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains(cacheKey(statementId = "count")),
        )
    }

    fun testF1ResolveUnknownWithCacheHitStillProducesMarker() {
        // 冷窗形态（S2）：查询**没算完**（Unknown），不是「确定没有」——已确认过的图标必须留着。
        MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.add(cacheKey(statementId = "count"))
        MybatisXmlTargets.methodLookupOverrideForTest = { _, _, _ -> MybatisXmlTargets.Unknown.UNKNOWN }
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { false }
        val xmlProvider = MapperXmlToJavaLineMarkerProvider()
        val xml = addXml("<select id=\"count\">SELECT 1</select>")
        val marker = markers(xmlProvider, xml).single()
        assertSame(MpvsIcons.MapperStatement, marker.icon)
        assertTrue(
            "Unknown 不得动缓存",
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains(cacheKey(statementId = "count")),
        )
    }

    /**
     * P2.1（T-MapperGutterEvict，2026-11-16）：确定不存在时图标必须消失并收回缓存——
     * 早期 F1 缓存只进不出，删掉接口方法 / 改 `<mapper namespace>` 后图标靠旧记忆续命。
     */
    fun testF1ResolveAbsentEvictsPositiveCacheAndProducesNoMarker() {
        MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.add(cacheKey(statementId = "count"))
        MybatisXmlTargets.methodLookupOverrideForTest = { _, _, _ -> MybatisXmlTargets.Absent.ABSENT }
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { false }
        val xmlProvider = MapperXmlToJavaLineMarkerProvider()
        val xml = addXml("<select id=\"count\">SELECT 1</select>")
        assertEmpty(markers(xmlProvider, xml))
        assertFalse(
            "确定不存在必须收回正缓存，否则下一趟慢 pass 还会把图标画回来",
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains(cacheKey(statementId = "count")),
        )
    }

    fun testF1ResolveFailureWithoutCacheProducesNoMarkerAndLeavesCacheClean() {
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { false }
        val xmlProvider = MapperXmlToJavaLineMarkerProvider()
        addMapper()
        val xml = addXml("<select id=\"ghost\">SELECT 1</select>")
        assertEmpty(markers(xmlProvider, xml))
        assertFalse(
            "「真没有」的负例不得污染正缓存",
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains(cacheKey(statementId = "ghost")),
        )
    }

    fun testF1DumbModeOptimisticallyProducesMarkerWithoutCaching() {
        // F1-b：dumb 窗内解析失败也乐观产出（不摘图标），且失败路径不得入正缓存。
        // dumb 判定走 isDumbProvider 测试缝（承 MbgExampleCallResolver 定例），不依赖平台真实 dumb 态。
        // 缝是伴生共享（Java/XML 两侧同读），用完即还原，防测试顺序泄漏。
        val original = MapperXmlToJavaLineMarkerProvider.isDumbProvider
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { true }
        try {
            val xmlProvider = MapperXmlToJavaLineMarkerProvider()
            addMapper()
            val xml = addXml("<select id=\"ghost\">SELECT 1</select>")
            val marker = markers(xmlProvider, xml)
            assertEquals("dumb 窗内解析失败须乐观产出（F1-b）", 1, marker.size)
            assertSame(MpvsIcons.MapperStatement, marker.single().icon)
            assertFalse("乐观产出不得污染正缓存",
                MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains(cacheKey(statementId = "ghost")))
        } finally {
            MapperXmlToJavaLineMarkerProvider.isDumbProvider = original
        }
    }

    // ------------------------------------------------------------------
    // F1'（T-GutterFirstOpenDelay Java 侧）：MapperXmlLocator.locate 瞬时落空不缺席——
    // 真机 2026-11-15 实证：冷窗/索引临界期慢趟首跑 locate 落空 → 慢趟结果固化 →
    // Java→XML 图标长期缺席（CustChannelMapper/CustAddressMapper 全方法行，daemon 重跑才恢复）。
    // 判定与 XML 侧同构：成功入共享正缓存 / 失败+缓存命中 / 失败+dumb 乐观 / 其余不产出。
    // ------------------------------------------------------------------

    fun testF1JavaLocateSuccessProducesMarkerAndSeedsPositiveCache() {
        val java = addMapper()
        addXml("<select id=\"count\">SELECT 1</select>")
        val marker = markers(javaProvider, java).single()
        assertSame(MpvsIcons.MapperMethod, marker.icon)
        assertTrue(
            "locate 成功必须记入共享正缓存（Java 侧失败分支靠它双向受益）",
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains(cacheKey(statementId = "count")),
        )
    }

    fun testF1JavaLocateUnknownWithCacheHitStillProducesMarker() {
        // 冷窗形态：locate **没算完**（Unknown）——缓存命中必须照常产出，缓存不动。
        MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.add(cacheKey(statementId = "count"))
        MapperXmlLocator.lookupOverrideForTest = { _, _ -> MapperXmlLocator.Unknown.UNKNOWN }
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { false }
        val marker = markers(javaProvider, addMapper())
        assertEquals("Unknown + 缓存命中必须照常产出（图标不缺席）", 1, marker.size)
        assertEquals("count", marker.single().element?.text)
        assertTrue(
            "Unknown 不得动缓存",
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains(cacheKey(statementId = "count")),
        )
    }

    /** P2.1（T-MapperGutterEvict，2026-11-16）：Java 侧确定不存在时摘图标并收回缓存。 */
    fun testF1JavaLocateAbsentEvictsPositiveCacheAndProducesNoMarker() {
        MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.add(cacheKey(statementId = "count"))
        MapperXmlLocator.lookupOverrideForTest = { _, _ -> MapperXmlLocator.Absent.ABSENT }
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { false }
        assertEmpty(markers(javaProvider, addMapper()))
        assertFalse(
            "确定不存在必须收回正缓存（删掉 XML 语句 / 改 namespace 后图标不得续命）",
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains(cacheKey(statementId = "count")),
        )
    }

    fun testF1JavaDumbOptimisticallyProducesMarkerWithoutPollutingCache() {
        // F1-b Java 侧：dumb 窗内 locate 落空也乐观产出；失败路径不得污染正缓存。
        val original = MapperXmlToJavaLineMarkerProvider.isDumbProvider
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { true }
        try {
            val marker = markers(javaProvider, addMapper())
            assertEquals("dumb 窗内 locate 落空须乐观产出（接口 5 个方法）", 5, marker.size)
            assertFalse("乐观产出不得污染正缓存",
                MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains(cacheKey(statementId = "missing")))
        } finally {
            MapperXmlToJavaLineMarkerProvider.isDumbProvider = original
        }
    }

    fun testF1JavaCacheMissAndSmartProducesNoMarkerNegative() {
        // 负例不回归：真没有对应 XML（缓存空 + smart）依旧不产出。显式置 smart 缝防兄弟用例泄漏。
        val original = MapperXmlToJavaLineMarkerProvider.isDumbProvider
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { false }
        try {
            assertEmpty(markers(javaProvider, addMapper()))
        } finally {
            MapperXmlToJavaLineMarkerProvider.isDumbProvider = original
        }
    }

    /**
     * 正缓存键测试构造（T-PerfSweep32 券02 同步）：键带 project.locationHash 前缀后，
     * 断言必须与生产 MapperXmlToJavaLineMarkerProvider.cacheKey 同源，不得再裸拼 namespace#id
     * （照 IbatisNavigationLineMarkerProvidersTest 的券04 同款先例）。
     */
    private fun cacheKey(namespace: String = "com.demo.UserMapper", statementId: String): String =
        MapperXmlToJavaLineMarkerProvider.cacheKey(project, namespace, statementId)

    // ---------- T-MapperNavNamespaceJump：<mapper> 根标签 → 接口类 ----------

    /** 竞品（MPJ/MyBatisX/MyBatisPlus）都能在「接口没有方法」时跳到类；本用例钉住我方同能力。 */
    fun test根标签出图标_接口无方法也可跳() {
        myFixture.addFileToProject("com/demo/EmptyMapper.java", "package com.demo;\npublic interface EmptyMapper {}")
        val xml = myFixture.addFileToProject("mapper/EmptyMapper.xml", "<mapper namespace=\"com.demo.EmptyMapper\"/>")
        val ms = markers(xmlProvider, xml, includeRoot = true)
        assertEquals("根标签应恰 1 枚跳类图标（接口无方法也要有）", 1, ms.size)
        assertEquals("根标签图标复用 MapperStatement", MpvsIcons.MapperStatement, ms.single().icon)
    }

    fun test根标签namespace不存在不出图标() {
        val xml = myFixture.addFileToProject("mapper/GhostMapper.xml", "<mapper namespace=\"com.demo.GhostMapper\"/>")
        assertEmpty("namespace 指向不存在的类不应出死图标", markers(xmlProvider, xml, includeRoot = true))
    }

    fun test根标签与语句图标共存() {
        addMapper()
        val xml = addXml("<select id=\"count\">select 1</select>")
        val ms = markers(xmlProvider, xml, includeRoot = true)
        assertEquals("根标签 1 枚 + 语句 1 枚应共存", 2, ms.size)
    }

    // ---------- T-MapperClassJump：Mapper 接口类名 → XML ----------

    private fun collectFor(provider: LineMarkerProvider, leaves: List<PsiElement>): List<LineMarkerInfo<*>> {
        val result = mutableListOf<LineMarkerInfo<*>>()
        provider.collectSlowLineMarkers(leaves.toMutableList(), result)
        return result
    }

    private fun classNameIdentifier(file: PsiFile): com.intellij.psi.PsiIdentifier =
        PsiTreeUtil.collectElementsOfType(file, com.intellij.psi.PsiIdentifier::class.java)
            .first {
                it.parent is com.intellij.psi.PsiClass &&
                    (it.parent as com.intellij.psi.PsiClass).nameIdentifier === it
            }

    fun test接口类名_出跳XML图标() {
        val java = addMapper()
        addXml("<select id=\"count\">select 1</select>")
        val ms = collectFor(javaProvider, listOf(classNameIdentifier(java)))
        assertEquals("接口类名应恰 1 枚跳 XML 图标", 1, ms.size)
        assertEquals("类名图标复用 MapperMethod", MpvsIcons.MapperMethod, ms.single().icon)
    }

    fun test接口类名_无XML不出图标() {
        val java = addMapper()
        assertEmpty("接口没有对应 XML 时类名不出死图标", collectFor(javaProvider, listOf(classNameIdentifier(java))))
    }

    // ---------- T-MapperMethodSqlPreview：方法行 SQL 预览图标（2026-11-25 用户需求） ----------

    private fun methodNameLeaves(file: PsiFile): List<PsiIdentifier> =
        PsiTreeUtil.collectElementsOfType(file, PsiIdentifier::class.java)
            .filter { it.parent is PsiMethod && (it.parent as PsiMethod).nameIdentifier === it }

    private val previewTooltip: String
        get() = MpSqlVisualizerBundle.message("gutter.mapperMethodPreview.tooltip")

    fun testJavaMethodRowHasPreviewAndNavigationMarkers() {
        val java = addMapper()
        addXml("  <select id=\"count\">SELECT 1</select>")
        val leaves = methodNameLeaves(java).filter { it.text == "count" }
        // T-MapperPreviewOrder：预览与跳转是两个 provider，合并顺序 = provider 注册顺序（预览先）。
        val all = collectFor(previewProvider, leaves) + collectFor(javaProvider, leaves)
        assertEquals("count 方法行应恰 2 枚（预览 + 跳转）", 2, all.size)
        assertEquals("预览图标在前（provider 顺序，与 XML 语句行同构）", previewTooltip, all[0].lineMarkerTooltip)
        assertSame("跳转图标保持既有样式", MpvsIcons.MapperMethod, all[1].icon)
        assertEquals("跳转 tooltip 不变", MpSqlVisualizerBundle.message("gutter.mapperMethod.tooltip"), all[1].lineMarkerTooltip)
    }

    fun testJavaMethodPreviewMarkerAbsentWithoutXml() {
        val java = addMapper()
        assertEmpty("无 XML 语句的方法行不得出预览图标（不出点了必空的入口）", collectFor(previewProvider, methodNameLeaves(java)))
    }

    fun testJavaMethodPreviewMarkerFollowsDumbOptimisticBranch() {
        val original = MapperXmlToJavaLineMarkerProvider.isDumbProvider
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { true }
        try {
            val java = addMapper()
            val ms = collectFor(previewProvider, methodNameLeaves(java).filter { it.text == "count" })
            assertEquals("dumb 窗乐观产出：预览 1 枚（与跳转同口径）", 1, ms.size)
            assertEquals("预览图标沿用 F1-b 乐观分支", previewTooltip, ms.single().lineMarkerTooltip)
        } finally {
            MapperXmlToJavaLineMarkerProvider.isDumbProvider = original
        }
    }

    fun testJavaClassRowHasNoPreviewMarker() {
        val java = addMapper()
        addXml("<select id=\"count\">select 1</select>")
        assertEmpty("类名行不出预览（类名不是语句）", collectFor(previewProvider, listOf(classNameIdentifier(java))))
        val nav = collectFor(javaProvider, listOf(classNameIdentifier(java)))
        assertEquals("类跳转仍在", 1, nav.size)
        assertSame(MpvsIcons.MapperMethod, nav.single().icon)
    }

    fun testJavaMethodPreviewMarkerFilteredByMapperXmlGroup() {
        val settings = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance()
        val before = settings.state
        try {
            // k2j 适配：Java State.copy() 无具名参，拷贝后逐字段 setter
            val gateOff = before.copy()
            gateOff.showGutterIcon = true
            gateOff.gutterGroupMapperXml = false
            settings.loadState(gateOff)
            val java = addMapper()
            addXml("<select id=\"count\">SELECT 1</select>")
            val leaves = methodNameLeaves(java)
            assertEmpty(
                "mapperXml 组关闭时预览与跳转同灭",
                collectFor(previewProvider, leaves) + collectFor(javaProvider, leaves),
            )
        } finally {
            settings.loadState(before)
        }
    }
}
