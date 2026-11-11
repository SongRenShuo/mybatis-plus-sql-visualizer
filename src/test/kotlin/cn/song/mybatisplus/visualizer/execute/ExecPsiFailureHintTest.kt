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
     * F6（2026-11-11 第三轮验收）：**落点自动改写表**。
     *
     * 真机读数钉死了这一档：`.idea/sql-visualizer/exec` 上 `dirPsi=false / dirExcluded=true /
     * dirUnderIgnored=true / inContent=false`，而 `.idea` 被 `.gitignore` 忽略是**正常设置**——
     * 「报错让用户改配置」没有可改的东西，只能换落点。
     */
    @Test
    fun `落点改写_主落点可用时不换`() {
        assertFalse(
            "健康机器上零成本、零误判：探针说能建 PSI 就绝不能换落点",
            shouldRelocateExecToRoot(alreadyRoot = false, dirCanHostPsi = true),
        )
    }

    @Test
    fun `落点改写_主落点建不出PSI时换项目根`() {
        assertTrue(
            "真机形态：目录级探针判否 ⇒ 自动改写到项目根，用户无感",
            shouldRelocateExecToRoot(alreadyRoot = false, dirCanHostPsi = false),
        )
    }

    @Test
    fun `落点改写_已在项目根就不再换`() {
        // 防递归：项目根也建不出 PSI 时只能回到失败文案（带成因与蓝字入口），不能再换
        assertFalse(shouldRelocateExecToRoot(alreadyRoot = true, dirCanHostPsi = false))
        assertFalse(shouldRelocateExecToRoot(alreadyRoot = true, dirCanHostPsi = true))
    }

    /**
     * F6：成因必须**按阶段**消费。
     *
     * 真机第三轮的症状正是「成因被并发的下一个 `createTempPsiFile` 入口清掉」——面板只剩红字。
     * 现在成因绑着它所属的阶段：阶段对不上就当过期丢弃（不会把「目录不可用」的入口安到方言失败上），
     * 阶段对得上则取一次即清。
     */
    @Test
    fun `成因按阶段消费_对不上即丢弃`() {
        DataSourceSelector.lastTempFileHintRecord =
            TempFileHintRecord("dirNoPsi", ExecPsiFailureCause.DIR_NOT_INDEXED)
        assertNull(
            "文案里没有该阶段 ⇒ 当过期丢弃（否则会把目录入口安到别的失败上）",
            DataSourceSelector.consumeTempFileHintFor("创建临时 SQL 文件失败：findPsiAfterHeal: name=x"),
        )
        assertNull("丢弃后记录已清空", DataSourceSelector.consumeTempFileHintFor("创建临时 SQL 文件失败：dirNoPsi: dir=x"))
    }

    @Test
    fun `成因按阶段消费_对得上取一次即清`() {
        DataSourceSelector.lastTempFileHintRecord =
            TempFileHintRecord("dirNoPsi", ExecPsiFailureCause.DIR_NOT_INDEXED)
        assertEquals(
            ExecPsiFailureCause.DIR_NOT_INDEXED,
            DataSourceSelector.consumeTempFileHintFor("创建临时 SQL 文件失败：dirNoPsi: dir=x"),
        )
        assertNull("只消费一次", DataSourceSelector.consumeTempFileHintFor("创建临时 SQL 文件失败：dirNoPsi: dir=x"))
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
