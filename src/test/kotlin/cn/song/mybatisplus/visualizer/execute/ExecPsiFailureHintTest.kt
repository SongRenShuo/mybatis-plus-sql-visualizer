package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    ) = classifyExecPsiFailure(excluded, regHideExcluded, fileTypesIgnored, underIgnored, psiLangId, canPsiActual)

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
        // 走到这里说明实际原因在本次判定面之外（vf 失效等）——宁可只报文案，也不指错设置页
        assertEquals(ExecPsiFailureCause.UNKNOWN, classify())
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

    @Test
    fun `忽略类原因压过方言原因`() {
        // 目录被忽略时平台压根不给 PSI，此时「语言」往往仍能选中（MySQL 或 null）——不能据此判方言
        assertEquals(
            ExecPsiFailureCause.IGNORED_BY_FILE_TYPES,
            classify(fileTypesIgnored = true, psiLangId = "MySQL", canPsiActual = false),
        )
    }
}
