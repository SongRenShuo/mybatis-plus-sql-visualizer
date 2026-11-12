package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-ExecPsiGate 13.10：失败原因判定的表驱动用例。
 *
 * 三种成因在用户侧长得**一模一样**（都是 `findFile` 返回 null、vf 完全有效），只有判定对了，
 * 面板上那颗蓝色入口才会指到对的地方。所以这里把「观测值组合 → 原因」逐档钉死，
 * 防止将来有人改判据顺序把入口指偏（入口指偏比不给入口更糟：用户会去改一个无关的设置）。
 */
class ExecPsiFailureHintTest {

    private fun classify(
        excluded: Boolean = false,
        regHideExcluded: Boolean = false,
        fileTypesIgnored: Boolean = false,
        underIgnored: Boolean = false,
        psiLangId: String? = "MySQL",
        canPsiActual: Boolean = true,
        dirCanHostPsi: Boolean? = null,
    ) = classifyExecPsiFailure(
        excluded, regHideExcluded, fileTypesIgnored, underIgnored, psiLangId, canPsiActual, dirCanHostPsi,
    )

    @Test
    fun `excluded 且 Registry 开启判为排除`() {
        assertEquals(
            ExecPsiFailureCause.EXCLUDED_ROOT,
            classify(excluded = true, regHideExcluded = true),
        )
    }

    @Test
    fun `excluded 但 Registry 关闭不算排除`() {
        // 平台 isExcludedOrIgnored 走 isUnderIgnored，此时「排除」本身不挡 PSI
        assertEquals(
            ExecPsiFailureCause.DIALECT_UNAVAILABLE,
            classify(excluded = true, regHideExcluded = false, psiLangId = "MySQL", canPsiActual = false),
        )
    }

    @Test
    fun `文件类型忽略模式优先于 VCS 忽略`() {
        // 两者都能让文件进忽略集；指向「文件类型」设置页可操作，指向 .gitignore 会误导
        assertEquals(
            ExecPsiFailureCause.IGNORED_BY_FILE_TYPES,
            classify(fileTypesIgnored = true, underIgnored = true),
        )
    }

    @Test
    fun `只进忽略集且非文件类型规则判为 VCS 忽略`() {
        assertEquals(
            ExecPsiFailureCause.IGNORED_BY_VCS,
            classify(underIgnored = true),
        )
    }

    @Test
    fun `既没排除也没忽略时按语言侧归因`() {
        assertEquals(ExecPsiFailureCause.NO_LANGUAGE, classify(psiLangId = null, canPsiActual = false))
        assertEquals(
            ExecPsiFailureCause.DIALECT_UNAVAILABLE,
            classify(psiLangId = "MySQL", canPsiActual = false),
        )
    }

    @Test
    fun `全都正常时不给入口`() {
        // 走到这里说明实际原因在本次判定面之外（vf 失效等）——文案仍要带兜底入口（见 UI 侧），
        // 但**不许**把它指到某个具体设置页：入口指偏比不给入口更糟
        assertEquals(ExecPsiFailureCause.UNKNOWN, classify())
    }

    /**
     * F5（2026-11-11 用户验收）：**文件级四项全绿、目录级探针判否** = 真机上唯一还没被覆盖的形态。
     *
     * 那一轮的读数：`excluded=false / underIgnored=false / regHideExcluded=false / psiLang=GenericSQL /
     * canPsiActual=true` 却 `findFile` 恒 null。若这一档仍落 UNKNOWN，面板上就只剩红字——
     * 用户的原话是「执行sql错误，没有引导用户调整配置信息」。
     */
    @Test
    fun `文件级全绿但目录探针判否时判为目录不可用`() {
        assertEquals(ExecPsiFailureCause.DIR_NOT_INDEXED, classify(dirCanHostPsi = false))
    }

    @Test
    fun `目录探针判否不得被误报成方言问题`() {
        // 这种机器上 psiLang / canPsiActual 往往都正常；先判语言就会把人送到 SQL 方言设置页
        assertEquals(
            ExecPsiFailureCause.DIR_NOT_INDEXED,
            classify(psiLangId = "GenericSQL", canPsiActual = true, dirCanHostPsi = false),
        )
    }

    @Test
    fun `忽略类成因优先于目录探针`() {
        // 两者都成立时指向「文件类型」/「.gitignore」是可操作的，落到目录不可用就只剩兜底了
        assertEquals(
            ExecPsiFailureCause.IGNORED_BY_FILE_TYPES,
            classify(fileTypesIgnored = true, dirCanHostPsi = false),
        )
        assertEquals(
            ExecPsiFailureCause.IGNORED_BY_VCS,
            classify(underIgnored = true, dirCanHostPsi = false),
        )
    }

    @Test
    fun `没探过目录时不参与判定`() {
        // 文件级调用方（例如预热链）拿不到目录结论，传 null 必须退回原有语义
        assertEquals(ExecPsiFailureCause.UNKNOWN, classify(dirCanHostPsi = null))
        assertEquals(ExecPsiFailureCause.DIALECT_UNAVAILABLE, classify(canPsiActual = false, dirCanHostPsi = null))
    }

    /**
     * 13.12：预览侧「自动兜底 + 只告知一次」的去重闸门。
     *
     * 落点判定每次预览/预热都会跑；若每次都提示就变成新噪音源，用户只需要知道一次。
     * 这里钉「首个调用方 true、其后一律 false」——纯逻辑，不依赖面板实例。
     */
    @Test
    fun `预览兜底提示只出一次`() {
        val gate = cn.song.mybatisplus.visualizer.ui.PreviewFallbackNoticeGate()
        assertTrue("首个调用方应当提示", gate.shouldNotify())
        assertFalse("第二次不应再提示", gate.shouldNotify())
        assertFalse("第三次同理", gate.shouldNotify())
    }

    /**
     * F6（2026-11-11 用户定案，两次同一口径）：**落点不改写、失败不引导改 `.gitignore`**。
     *
     * - 不改写：真机已证明 `.idea/sql-visualizer/exec` 上 `dirPsi=false`，但自动换落点会把项目配置
     *   的问题藏起来，必须保持「报错 + 让用户点」。所以 `createTempPsiFile` 里不得出现自动改写的调用。
     * - 不引导 `.gitignore`：`.gitignore` 忽略 `.idea/` 是正常设置，删它是错误引导；忽略模式的可控
     *   入口统一指向 Settings → Editor → File Types。
     *
     * 这里用**标识符级**扫描（不比对空白/格式），只钉「这些东西不许回来」。
     */
    @Test
    fun `落点不许自动改写_提示不许引导改 gitignore`() {
        val execSource = java.io.File(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt",
        ).readText()
        for (banned in listOf("shouldRelocateExecToRoot", "notifyExecRootFallbackOnce")) {
            assertFalse(
                "自动改写落点的实现不得回归（用户 2026-11-11 定案：兜底要用户点）: $banned",
                execSource.contains(banned),
            )
        }
        for (uiFile in listOf(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelEditorLifecycle.kt",
        )) {
            val ui = java.io.File(uiFile).readText()
            assertFalse(
                "失败/降级提示不得再挂「打开 .gitignore」入口（用户 2026-11-11 定案）: $uiFile",
                ui.contains("execute.tempFile.action.gitignore"),
            )
        }
    }

    /**
     * F6：成因必须**按失败文案**匹配，且**读方不清空**。
     *
     * 真机第四轮的症状是「直接点执行没有蓝字」：同一次失败被渲染两次（abort 持有者 + complete
     * 排队意图，两路带同一条文案），原来「读一次就清」导致第二次退化成纯红字并覆盖掉蓝字。
     */
    @Test
    fun `成因按文案匹配_非 PSI 失败不借用`() {
        DataSourceSelector.lastTempFileHintRecord =
            TempFileHintRecord("findPsiAfterHeal", ExecPsiFailureCause.IGNORED_BY_VCS)
        assertNull(
            "写出/刷新类失败与「为什么建不出 PSI」无关，不得借用该成因",
            DataSourceSelector.consumeTempFileHintFor("创建临时 SQL 文件失败：writeFile: boom"),
        )
    }

    @Test
    fun `成因可重复读取_同一次失败渲染两次都拿得到`() {
        DataSourceSelector.lastTempFileHintRecord =
            TempFileHintRecord("findPsiAfterHeal", ExecPsiFailureCause.IGNORED_BY_VCS)
        val msg = "执行失败：创建临时 SQL 文件失败：findPsiAfterHeal: name=mpsql-exec.sql"
        assertEquals(
            ExecPsiFailureCause.IGNORED_BY_VCS,
            DataSourceSelector.consumeTempFileHintFor(msg),
        )
        assertEquals(
            "第二次渲染必须仍拿到（否则蓝字被纯红字覆盖）",
            ExecPsiFailureCause.IGNORED_BY_VCS,
            DataSourceSelector.consumeTempFileHintFor(msg),
        )
    }

    /**
     * F8：**复现「直接点执行没有蓝字」**（2026-11-11 第五轮）。
     *
     * 真机前四轮的故障点都在「这条文案该挂哪颗蓝字」这一步返回了 null，而它一为 null 就等于面板
     * 只剩红字。这里按生产的真实形态造文案（`execute.tempFile.failed` 包一层），**连续渲染两次**
     * ——旧实现第二次就丢（共享字段被读方清空），F8 把成因编进文案后两次都在。
     */
    @Test
    fun `复现蓝字消失_成因编进文案后渲染两次都拿得到`() {
        // 真机形态：共享字段已经丢了（被并发入口/读方清空、或动态重载后是另一份类实例）
        DataSourceSelector.lastTempFileHintRecord = null
        val reason = withExecHint("findPsiAfterHeal: name=mpsql-exec.sql", ExecPsiFailureCause.IGNORED_BY_VCS)
        val message = "创建临时 SQL 文件失败：$reason"
        repeat(2) { round ->
            assertEquals(
                "第 ${round + 1} 次渲染必须仍拿到成因（只靠共享字段时这里恒为 null ⇒ 面板退化成纯红字）",
                ExecPsiFailureCause.IGNORED_BY_VCS,
                resolveTempFileHintCause(message),
            )
            assertFalse("展示文案里不得残留标记", parseExecHintMessage(message).text.contains("[hint="))
        }
    }

    @Test
    fun `成因标记与文案互逆`() {
        val plain = "findPsiAfterHeal: name=x"
        val marked = withExecHint(plain, ExecPsiFailureCause.DIR_NOT_INDEXED)
        val parsed = parseExecHintMessage(marked)
        assertEquals(ExecPsiFailureCause.DIR_NOT_INDEXED, parsed.cause)
        assertEquals("剥掉标记必须回到原文", plain, parsed.text)
        // 无标记 ⇒ 原样返回，且不误判成因
        assertNull(parseExecHintMessage(plain).cause)
        assertEquals(plain, withExecHint(plain, null))
    }

    /**
     * F9（2026-11-11 第六轮）：**所有执行回调都必须经过 `showTempFileError`**。
     *
     * 真机最后一层缺口：面板里五个执行调用点只有两处接了对的回调，主执行路径（`executePrepared`
     * 与全链 `SqlExecutor.execute`）直连 `resultPanel.showError`——于是「直接点执行」永远拿不到
     * 成因与蓝字入口，而「更多 → 切换数据源」那条走的是已接线的那两处，所以只有它正常。
     * 用户的原话是「点击更多-切换数据源 才正常」，正是这条分叉。
     */
    @Test
    fun `执行回调不得绕过 showTempFileError`() {
        val src = java.io.File(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt",
        ).readText()
        val bypass = Regex("""onError = \{\s*errorMsg ->\s*resultPanel\.showError""")
        assertFalse(
            "执行回调直连 resultPanel.showError 会丢掉临时文件失败的成因与蓝字入口",
            bypass.containsMatchIn(src),
        )
    }

    @Test
    fun `成因标记容错_未知枚举名不炸`() {
        val parsed = parseExecHintMessage("boom [hint=NOT_A_CAUSE]")
        assertNull("未知成因名只能当没有，不许抛", parsed.cause)
        assertEquals("boom", parsed.text)
    }

    @Test
    fun `忽略类原因压过方言原因`() {
        // 目录被忽略时平台压根不给 PSI，此时「语言」往往仍能选中（MySQL 或 null）——不能据此判方言
        assertEquals(
            ExecPsiFailureCause.IGNORED_BY_FILE_TYPES,
            classify(fileTypesIgnored = true, psiLangId = "MySQL", canPsiActual = false),
        )
    }
}
