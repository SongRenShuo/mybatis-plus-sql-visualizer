package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlText
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import java.io.File

/**
 * T-PerfSweep16 F37 接线级测试（批次 7 教训：lexer/纯函数级测试探不到接线断裂）——
 * 注入器经**真实平台管线**（elementsToInjectIn 键匹配 → 元素级喂入 → startInjecting/addPlace/
 * doneInjecting 落地含 ParserDefinition）收集注入位：
 *  - mapper 文件：语句文本 + 白名单属性注入位均收集（按 [SqlMapperLanguage] 过滤计数，
 *    免受同环境其他 EP 注入器——Spring/Angular 族——对同文件的注入噪声）；
 *  - 非 mapper XML：零注入位；
 *  - shouldYield 让位态（置设置开关关 / D68 平台探针判定会注入）：零注入位。
 *
 * D68 注：平台让位线已改 per-host 探测（真实 EP 枚举）——接线测试的注入位断言经
 * [XmlSynHiGate.foreignInjectionProbe] 缝固定为「平台不注入」，免受测试环境 EP 内容漂移
 * 影响；「平台会注入」让位形态单列一例（缝置 true → 零注入位）。
 *
 * 形态注（两坑实证）：
 *  1. 文件须**物理临时文件**（LocalFileSystem）——enumerate 管线对非物理文件
 *     （configureByText 的 LightVirtualFile）按「非物理不注入」契约直接短路（243
 *     InjectedLanguageUtilBase.enumerate 实证）；
 *  2. enumerate 前须文档已提交——VFS refresh 刚落的文件 document 未提交时管线静默空转
 *     （首跑真红 0 位实证），显式 commitDocument 后稳定。
 */
class SqlMapperInjectorWiringTest : BasePlatformTestCase() {

    private val mapper = """<mapper namespace="n">
  <select id="a">SELECT * FROM t WHERE a = 1
    <if test="a &gt; 1">AND b = 2</if>
  </select>
</mapper>"""

    /** D68 前置探针缝原件（tearDown 还原）。 */
    private val originalProbe = XmlSynHiGate.foreignInjectionProbe

    override fun setUp() {
        super.setUp()
        // D68：接线级断言钉「本注入器」行为——平台 per-host 探针读真实 EP（内容随测试环境漂移），
        // 经缝固定为「平台不注入」；平台让位形态见 testF68_平台探针让位_零注入位。
        XmlSynHiGate.foreignInjectionProbe = { _, _ -> false }
        InjectedLanguageManager.getInstance(project)
            .registerMultiHostInjector(SqlMapperInjector(), testRootDisposable)
    }

    override fun tearDown() {
        XmlSynHiGate.foreignInjectionProbe = originalProbe
        super.tearDown()
    }

    /** 物理临时文件形态打开 mapper XML（enumerate 管线只对物理文件跑注入探针）。 */
    private fun openPhysicalFile(content: String): XmlFile {
        val dir = FileUtil.createTempDirectory("ps16wiring", "", true)
        val ioFile = File(dir, "mapper.xml").apply { writeText(content) }
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            ?: throw IllegalStateException("temp file not in VFS")
        myFixture.configureFromExistingVirtualFile(vf)
        val file = myFixture.file as XmlFile
        // 首跑真红实证：VFS refresh 刚落的文件 document 未提交 → enumerate 管线静默零位
        PsiDocumentManager.getInstance(project).getDocument(file)?.let { doc ->
            if (!PsiDocumentManager.getInstance(project).isCommitted(doc)) {
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        }
        // dumb 态说明：物理文件入 VFS 触发索引 → 测试运行于 dumb 态。注入器已声明 dumb-aware
        // （纯词法动作不依赖索引），dumb 态下管线照常喂入——无需等 smart。
        return file
    }

    /**
     * 注入 [SqlMapperLanguage] 的注入位总数：对每个喂入候选宿主（XmlText/XmlAttributeValue）
     * 走真实 enumerate 管线计数；按语言过滤——同环境其他 EP 注入器对同文件的注入不计入。
     */
    private fun myLanguagePlaceCount(file: XmlFile): Int {
        val manager = InjectedLanguageManager.getInstance(project)
        val hosts = PsiTreeUtil.collectElements(file) { it is XmlText || it is XmlAttributeValue }
        var count = 0
        for (host in hosts) {
            manager.enumerateEx(host, file, false) { injectedPsi, places ->
                if (injectedPsi.language == SqlMapperLanguage) count += places.size
            }
        }
        return count
    }

    fun testF37_mapper文件_语句文本与白名单属性注入位均收集() {
        val file = openPhysicalFile(mapper)
        // 语句文本（select 直属文本 + if 内嵌文本）+ test 属性值各 1 位；namespace/id 属性不在
        // 白名单、语句尾部纯空白文本段去首尾后为空 → 均零位
        assertEquals("mapper 文件应收集语句文本与 test 属性注入位", 3, myLanguagePlaceCount(file))
    }

    fun testF37_非mapper文件_零注入位() {
        val notMapper = mapper.replace("<mapper namespace=\"n\">", "<configuration>")
        val file = openPhysicalFile(notMapper)
        assertEquals("非 mapper 文件零注入位（根守卫）", 0, myLanguagePlaceCount(file))
    }

    /**
     * F79 刀一（管线级判别）：非 mapper XML 的高亮喂入 pass 全程探针零咨询——
     * 修复前 probe 跑在 mapper 判定之前，每宿主白付全链（计数 >0 → 红）。
     */
    fun testF79_非mapper文件_管线级探针零咨询() {
        val notMapper = mapper.replace("<mapper namespace=\"n\">", "<configuration>")
        val file = openPhysicalFile(notMapper)
        val consulted = intArrayOf(0)
        XmlSynHiGate.foreignInjectionProbe = { _, _ -> consulted[0]++; false }
        try {
            assertEquals("非 mapper 文件零注入位（根守卫）", 0, myLanguagePlaceCount(file))
            assertEquals("非 mapper 文件管线喂入全程：探针零咨询（修复前每宿主白付 → 红）", 0, consulted[0])
        } finally {
            XmlSynHiGate.foreignInjectionProbe = originalProbe
        }
    }

    fun testF37_让位态_零注入位() {
        val file = openPhysicalFile(mapper)
        val settings = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance()
        val old = settings.state.xmlSynHiEnabled
        // F47 起闸门读快照（loadState 唯一刷新漏斗）——测试按生产契约经 loadState 切换
        settings.loadState(settings.state.copy(xmlSynHiEnabled = false))
        try {
            assertEquals("设置开关关闭 → shouldYield 让位 → 零注入位（反向验证）", 0, myLanguagePlaceCount(file))
        } finally {
            settings.loadState(settings.state.copy(xmlSynHiEnabled = old))
        }
    }

    fun testF68_平台探针让位_零注入位() {
        val file = openPhysicalFile(mapper)
        // D68 关键让位形态（管线级）：探针判定平台会注入该宿主 → 全族让位 → 零注入位
        XmlSynHiGate.foreignInjectionProbe = { _, _ -> true }
        try {
            assertEquals("平台探针判定会注入 → 零注入位（反向验证：摘掉让位分支 → 3 位 → 红）", 0, myLanguagePlaceCount(file))
        } finally {
            XmlSynHiGate.foreignInjectionProbe = { _, _ -> false }
        }
    }

    fun testF37_pluginXml_multiHostInjector_EP接线在场() {
        // EP 声明冻结但接线不可丢——钉住 multiHostInjector 声明（同 typedHandler EP 测试口径）
        val xml = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")!!
            .readBytes().toString(Charsets.UTF_8)
        org.junit.Assert.assertTrue(
            "plugin.xml 须含 <multiHostInjector implementation=…SqlMapperInjector>",
            Regex("<multiHostInjector\\s+implementation=\"cn\\.song\\.mybatisplus\\.visualizer\\.ui\\.inject\\.SqlMapperInjector\"")
                .containsMatchIn(xml),
        )
    }

    // ------------------------------------------------------------------
    // T-XmlGap W1：区间级互补（管线级）
    // ------------------------------------------------------------------

    private val paramMapper = """<mapper namespace="n">
  <select id="a">SELECT * FROM t WHERE a = #{id} AND b = 2</select>
</mapper>"""

    /** 本语言注入产物文本清单（同一会话多 place 会拼成一个注入文件）。 */
    private fun myLanguageInjectedTexts(file: XmlFile): List<String> {
        val manager = InjectedLanguageManager.getInstance(project)
        val hosts = PsiTreeUtil.collectElements(file) { it is XmlText || it is XmlAttributeValue }
        val out = mutableListOf<String>()
        for (host in hosts) {
            manager.enumerateEx(host, file, false) { injectedPsi, _ ->
                if (injectedPsi.language == SqlMapperLanguage) out += injectedPsi.text
            }
        }
        return out
    }

    /**
     * W1 核心（管线级）：外来注入器只占 `#{}` 区间 → 我方**仍注入余下区间**，且注入文本里
     * 不再含 `#{}`（那一段留给对方）。旧宿主级形态在此为 0 注入位（整条语句无色）——本用例即判别位。
     *
     * 形态坑（本轮真红实证）：**缝必须在 `openPhysicalFile` 之前设**——平台在文件入 VFS/配置编辑器
     * 阶段就会算一次注入并把结果缓存在 PSI 上，之后再改缝也拿不到新结果（首跑两例皆红的根因）。
     */
    fun testW1_外来只占参数区间_我方仍注入余下区间() {
        val pattern = Regex("""[#$]\{[^}]*}""")
        withRangeSeam({ host -> pattern.findAll(host.text).map { TextRange(it.range.first, it.range.last + 1) }.toList() }) {
            val file = openPhysicalFile(paramMapper)
            val texts = myLanguageInjectedTexts(file)
            assertEquals("语句文本须有注入位且为单会话拼接产物", 1, texts.size)
            val injected = texts.single()
            org.junit.Assert.assertTrue("余下区间仍被注入（SQL 文本在场）", injected.contains("SELECT * FROM t"))
            org.junit.Assert.assertTrue("` AND b = 2` 尾部区间仍被注入", injected.contains("AND b = 2"))
            org.junit.Assert.assertFalse("被占的 `#{}` 段须留给对方，不得出现在我方注入文本里", injected.contains("#{id}"))
        }
    }

    /** 反向：外来注入器占满整宿主 → 我方在语句文本上零着色（保守线保留）。 */
    fun testW1_外来占满整宿主_语句文本零着色() {
        withRangeSeam({ host -> InjectRangePlan.yieldAll(host.textLength) }) {
            val file = openPhysicalFile(paramMapper)
            val texts = myLanguageInjectedTexts(file)
            org.junit.Assert.assertFalse(
                "整宿主被占 → 语句文本不得由我方注入（反向验证：摘掉互补计算改用旧整宿主让位 → 同样无语句文本，本用例钉住不回归）",
                texts.any { it.contains("SELECT * FROM t") },
            )
        }
    }

    /**
     * 区间缝注入 + 还原（**须包住 openPhysicalFile**，见用例 KDoc 的缓存坑）。
     *
     * 同时把**布尔缝置空**：本类 setUp 把布尔缝钉成 `false`（固定「平台不注入」环境），而
     * [XmlSynHiGate.occupiedRanges] 会优先采纳被覆写的布尔缝——不清空它，区间缝永远不被咨询
     * （本轮两例同红的第二层根因）。
     */
    private fun <T> withRangeSeam(seam: (com.intellij.psi.PsiElement) -> List<TextRange>, block: () -> T): T {
        val originalRanges = XmlSynHiGate.foreignOccupiedRangesProbe
        val originalBool = XmlSynHiGate.foreignInjectionProbe
        XmlSynHiGate.foreignInjectionProbe = null
        XmlSynHiGate.foreignOccupiedRangesProbe = { _, h -> seam(h) }
        try {
            return block()
        } finally {
            XmlSynHiGate.foreignOccupiedRangesProbe = originalRanges
            XmlSynHiGate.foreignInjectionProbe = originalBool
        }
    }
}
