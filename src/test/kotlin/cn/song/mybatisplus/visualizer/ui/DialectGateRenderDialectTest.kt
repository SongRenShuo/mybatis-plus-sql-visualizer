package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UI-004：底部方言闸门「渲染方言」取值。
 *
 * 现场：顶部 `方言: MySQL（已绑定数据源）`、SQL 文本已 MySQL、右上 `已绑定: mp_demo@localhost`，
 * 底部却报「当前 SQL 按 PostgreSQL 渲染，所选数据源是 MySQL」。两份方言事实分裂：
 * `PreviewResult.effectiveDialect`（生成时固化，XML 载体无法被 rerenderForExecutionTarget 重写）
 * 与 `currentDialectDecision`（随绑定/刷新重算）。
 *
 * 判定面：
 *  - 表驱动：方言无关载体（XML 原文/纯文本）取最新决策——旧 effectiveDialect=PG、当前 decision=MySQL
 *    时必须报 MySQL，不得报 PG；方言敏感载体（模型/批量/分页）仍取渲染事实；
 *  - 生产接线守卫：闸门请求确实走该纯函数；刷新按钮确实重算方言证据；方言无关载体的证据对齐
 *    与底部提示重判确实挂在刷新链上。
 *
 * 纯逻辑层零平台依赖，可进 testFast 快桶。
 */
class DialectGateRenderDialectTest {

    // ------------------------------------------------------------------
    // 纯函数：渲染方言取值
    // ------------------------------------------------------------------

    @Test
    fun `方言无关载体_旧渲染方言PG与当前决策MySQL_取MySQL`() {
        assertEquals(
            "MySQL",
            DialectGateRenderDialect.resolve(
                effectiveDialectName = "PostgreSQL",
                decisionDialectName = "MySQL",
                dialectNeutral = true,
            ),
        )
    }

    @Test
    fun `方言敏感载体_渲染事实优先_保留PG`() {
        assertEquals(
            "PostgreSQL",
            DialectGateRenderDialect.resolve(
                effectiveDialectName = "PostgreSQL",
                decisionDialectName = "MySQL",
                dialectNeutral = false,
            ),
        )
    }

    @Test
    fun `方言无关载体_决策无方言名_回落渲染事实`() {
        assertEquals(
            "PostgreSQL",
            DialectGateRenderDialect.resolve(
                effectiveDialectName = "PostgreSQL",
                decisionDialectName = null,
                dialectNeutral = true,
            ),
        )
    }

    @Test
    fun `方言敏感载体_渲染事实缺席_回落决策`() {
        assertEquals(
            "MySQL",
            DialectGateRenderDialect.resolve(
                effectiveDialectName = null,
                decisionDialectName = "MySQL",
                dialectNeutral = false,
            ),
        )
    }

    @Test
    fun `两边都缺席_返回null由调用方兜底`() {
        assertNull(DialectGateRenderDialect.resolve(null, null, dialectNeutral = true))
        assertNull(DialectGateRenderDialect.resolve("  ", "", dialectNeutral = false))
    }

    @Test
    fun `两边一致_任一载体都返回同一方言`() {
        assertEquals(
            "MySQL",
            DialectGateRenderDialect.resolve("MySQL", "MySQL", dialectNeutral = true),
        )
        assertEquals(
            "MySQL",
            DialectGateRenderDialect.resolve("MySQL", "MySQL", dialectNeutral = false),
        )
    }

    // ------------------------------------------------------------------
    // 纯函数：载体是否与插件渲染方言无关
    // ------------------------------------------------------------------

    @Test
    fun `XML原文与纯文本是方言无关载体`() {
        assertTrue(
            DialectGateRenderDialect.isDialectNeutralCarrier(
                hasPreviewModel = false, hasBatchModel = false, hasPaginationLimitLine = false,
            ),
        )
    }

    @Test
    fun `模型与批量载体是方言敏感`() {
        assertFalse(
            DialectGateRenderDialect.isDialectNeutralCarrier(
                hasPreviewModel = true, hasBatchModel = false, hasPaginationLimitLine = false,
            ),
        )
        assertFalse(
            DialectGateRenderDialect.isDialectNeutralCarrier(
                hasPreviewModel = false, hasBatchModel = true, hasPaginationLimitLine = false,
            ),
        )
    }

    @Test
    fun `分页LIMIT行是方言敏感_即便无模型`() {
        assertFalse(
            DialectGateRenderDialect.isDialectNeutralCarrier(
                hasPreviewModel = false, hasBatchModel = false, hasPaginationLimitLine = true,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 生产接线守卫（源码扫描）——纯函数绿证明不了生产链真的接上了
    // ------------------------------------------------------------------

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return dir
        }
        return dir
    }

    private fun source(relative: String): String = File(repoRoot(), relative).readText()

    private val uiRoot = "src/main/kotlin/cn/song/mybatisplus/visualizer/ui"

    @Test
    fun `闸门请求必须经纯函数取渲染方言`() {
        val t = source("$uiRoot/SqlPreviewPanelExecution.kt")
        val fn = t.substringAfter("internal fun SqlPreviewPanel.executionDialectGateRequest")
            .substringBefore("internal fun SqlPreviewPanel.onExecutionDialectGate")
        assertTrue("闸门渲染方言未走 DialectGateRenderDialect.resolve（会继续优先陈旧 effectiveDialect）",
            fn.contains("DialectGateRenderDialect.resolve("))
        assertTrue("闸门请求未携带当前决策方言", fn.contains("decisionDialectName = currentDialectDecision?.dialectName"))
        assertTrue("闸门请求未按载体类型判定方言无关性",
            fn.contains("DialectGateRenderDialect.isDialectNeutralCarrier("))
    }

    @Test
    fun `刷新按钮必须重算方言证据`() {
        val t = source("$uiRoot/SqlPreviewPanelSqlView.kt")
        val fn = t.substringAfter("internal fun SqlPreviewPanel.refreshView()")
            .substringBefore("internal fun SqlPreviewPanel.formatSqlSetting")
        assertTrue("刷新未重算方言（改 IDEA 方言后顶部/底部停在旧值）",
            fn.contains("refreshDialectStatusRow(includeSourceFile = true)"))
    }

    @Test
    fun `刷新链必须对齐方言无关载体并重判底部提示`() {
        val t = source("$uiRoot/DialectStatusRefresh.kt")
        assertTrue("缺方言无关载体的证据对齐", t.contains("adoptDialectForNeutralCarrier(chosen)"))
        assertTrue("缺底部方言提示重判", t.contains("refreshDialectGateStatusRow(boundDialect)"))
        assertTrue("刷新链未把源文件交给决策链", t.contains("includeSourceFile"))
        // 重判只允许替换「方言提示信息行」，不得清执行结果/真实错误
        val fn = t.substringAfter("private fun SqlPreviewPanel.refreshDialectGateStatusRow")
            .substringBefore("internal fun shouldRerenderForBoundDialect")
        assertTrue("重判前未校验纯信息态（会抹掉执行错误）", fn.contains("isInfoOnlyState()"))
        assertTrue("MATCH 时未清掉红条", fn.contains("resultPanel.showEmpty()"))
    }

    @Test
    fun `来源文件句柄必须在 setSourceContext 落位`() {
        val t = source("$uiRoot/SqlPreviewPanel.kt")
        val fn = t.substringAfter("fun setSourceContext(").substringBefore("fun setSourceLocation(")
        assertTrue("setSourceContext 未保留来源文件（刷新读不到 IDEA 方言映射）",
            fn.contains("sourceVirtualFile = context?.hostVirtualFile"))
    }

    @Test
    fun `方言提示原文必须被记录以便安全重判`() {
        val t = source("$uiRoot/SqlPreviewPanelExecution.kt")
        assertTrue("放行态未记录提示原文", t.contains("lastDialectGateMessage = message"))
    }
}
