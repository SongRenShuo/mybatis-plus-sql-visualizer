package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlText
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * F47/V38：注入器喂入税基准对照（性能十七轮 §5.1 选型实测）。
 *
 * 喂入面：`multiHostInjector` 全局 EP + dumb-aware → 任意 XML 族文件每个 XmlText/
 * XmlAttributeValue 每高亮 pass 喂入一次 [SqlMapperInjector.getLanguagesToInject]，
 * 首判 [XmlSynHiGate.shouldYield] = 1 次 app 级服务查找 + runCatching。本测试构造
 * mapper XML（喂入形态同 .idea/pom 等一切 XML 族——喂入不看内容），逐元素走三种取形：
 *
 *  - BASELINE：现状 shouldYield（每元素 1 次 [cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance] 服务查找）；
 *  - A 快照：设置读改快照字段（@Volatile 直读，设置 apply/loadState 时刷新）；
 *  - B memo：per-psi-vfs-epoch 记忆（CHM<PsiFile,(epoch,result)>，每元素 containingFile + 查表 + epoch 比对）。
 *
 * 判定：计数断言硬性（BASELINE 每元素恰好 1 次服务查找=喂入税在案）；耗时打印（V38-READINGS
 * 行）供选型对照——赢家按读数落生产码，本测试保留三形持续可比。
 */
class XmlSynHiGateYieldCostTest : BasePlatformTestCase() {

    /** 构造 mapper XML：500 个 if 元素（语句文本 + test 属性 = 1000 喂入位）。 */
    private fun openMapperFile(): XmlFile {
        val sb = StringBuilder("<mapper namespace=\"n\">\n")
        repeat(500) { i ->
            sb.append("  <select id=\"s$i\">\n")
            sb.append("    <if test=\"a != $i\">SELECT * FROM t$i WHERE c = $i</if>\n")
            sb.append("  </select>\n")
        }
        sb.append("</mapper>")
        val dir = FileUtil.createTempDirectory("f47bench", "", true)
        val ioFile = File(dir, "mapper.xml").apply { writeText(sb.toString()) }
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)!!
        return com.intellij.psi.PsiManager.getInstance(project).findFile(vf) as XmlFile
    }

    private fun feedElements(file: XmlFile): List<com.intellij.psi.PsiElement> =
        (PsiTreeUtil.collectElementsOfType(file, XmlText::class.java).toList()
                + PsiTreeUtil.collectElementsOfType(file, XmlAttributeValue::class.java).toList())
            .sortedBy { it.textRange.startOffset }

    // ---- 候选 A：快照字段（测试内同形实现——生产落点经 loadState 刷新，读形相同=单 @Volatile 直读） ----
    private val snapshotHolder = java.util.concurrent.atomic.AtomicBoolean(true)

    // ---- 候选 B：per-psi-vfs-epoch memo（测试内同形实现：CHM<PsiFile, entry>，entry=(epoch,result)） ----
    private class MemoEntry(val epoch: Long, val result: Boolean)
    private val yieldMemo = ConcurrentHashMap<com.intellij.psi.PsiFile, MemoEntry>()
    private fun memoYield(element: com.intellij.psi.PsiElement): Boolean {
        val file = element.containingFile
        val vfEpoch = file.virtualFile?.modificationCount ?: 0L
        val hit = yieldMemo[file]
        if (hit != null && hit.epoch == vfEpoch) return hit.result
        val fresh = XmlSynHiGate.shouldYield(project)
        yieldMemo[file] = MemoEntry(vfEpoch, fresh)
        return fresh
    }

    fun testF47_喂入税基准对照_计数与耗时() {
        val file = openMapperFile()
        val elements = feedElements(file)
        assertTrue("喂入元素须成规模（.XmlText+XmlAttributeValue）", elements.size >= 900)

        // 计数证据（修复后守卫）：生产 shouldYield（快照形态）整 pass 零服务查找；
        // 旧形态（每元素 runCatching+getInstance）在测试内同形仿制，计数 == 元素数仍在案。
        val counter = intArrayOf(0)
        cn.song.mybatisplus.visualizer.config.PluginSettings.getInstanceProbe = { counter[0]++ }
        try {
            elements.forEach { XmlSynHiGate.shouldYield(project) }
            assertEquals("快照形态：整 pass 零 app 级服务查找（反向验证：摘快照退回旧读形 → ==元素数 → 红）", 0, counter[0])
            elements.forEach {
                @Suppress("UNUSED_EXPRESSION")
                runCatching {
                    cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.xmlSynHiEnabled
                }.getOrDefault(true)
            }
            assertEquals("旧形态同形仿制：每元素恰一次服务查找（喂入税计数证据，与修复前基线一致）", elements.size, counter[0])
        } finally {
            cn.song.mybatisplus.visualizer.config.PluginSettings.getInstanceProbe = null
        }

        // 耗时对照：暖机后测各形 ns/元素（最小值口径，噪声鲁棒；绝对值只作选型参照不进断言）。
        fun bench(label: String, passes: Int, block: (com.intellij.psi.PsiElement) -> Any) {
            repeat(50) { elements.forEach { block(it) } } // 暖机
            var best = Long.MAX_VALUE
            repeat(passes) {
                val t0 = System.nanoTime()
                elements.forEach { block(it) }
                val per = (System.nanoTime() - t0) / elements.size
                if (per < best) best = per
            }
            println("V38-READINGS $label ns/element=$best elements=${elements.size}")
        }

        bench("BASELINE-emulated-old-shouldYield", 40) {
            runCatching {
                cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.xmlSynHiEnabled
            }.getOrDefault(true)
        }
        bench("POSTFIX-production-shouldYield", 40) { XmlSynHiGate.shouldYield(project) }
        bench("A-snapshot-volatile-read", 40) { snapshotHolder.get() }
        bench("B-psi-vfs-epoch-memo", 40) { memoYield(it) }
    }

    /**
     * F47 修复后守卫：喂入 pass 零服务查找（快照直读）。
     * 反向验证：摘掉快照（退回每元素 runCatching+getInstance）→ 计数 == 元素数 → 红。
     */
    fun testF47_修复后_喂入pass零服务查找() {
        val file = openMapperFile()
        val elements = feedElements(file)
        val counter = intArrayOf(0)
        cn.song.mybatisplus.visualizer.config.PluginSettings.getInstanceProbe = { counter[0]++ }
        try {
            elements.forEach { XmlSynHiGate.shouldYield(project) }
            assertEquals("快照形态：整 pass 零 app 级服务查找", 0, counter[0])
        } finally {
            cn.song.mybatisplus.visualizer.config.PluginSettings.getInstanceProbe = null
        }
    }

    /** F47 快照刷新漏斗：设置切换（经 loadState，生产 apply 契约）即时生效且可逆。 */
    fun testF47_快照漏斗_设置切换生效且可逆() {
        val settings = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance()
        val old = settings.state.xmlSynHiEnabled
        try {
            settings.loadState(settings.state.copy(xmlSynHiEnabled = false))
            assertEquals("开关关 → 全族让位（快照已刷新）", true, XmlSynHiGate.shouldYield(project))
            settings.loadState(settings.state.copy(xmlSynHiEnabled = true))
            assertEquals(
                "开关开（测试环境无平台注入器/无三方）→ 不让位（快照已刷新）",
                false, XmlSynHiGate.shouldYield(project),
            )
        } finally {
            settings.loadState(settings.state.copy(xmlSynHiEnabled = old))
        }
    }
}
