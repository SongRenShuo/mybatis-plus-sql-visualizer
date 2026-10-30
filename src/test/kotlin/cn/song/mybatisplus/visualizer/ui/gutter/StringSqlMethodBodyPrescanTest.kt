package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals

/**
 * 券04（T-PerfSweep28-04，R28-5）：StringSqlMethodGutter 的方法体引号预筛。
 *
 * 判据：
 *  ① 无引号方法体（30 个方法、零字符串字面量）→ 预筛直接短路，全子树遍历零触达
 *     （bodyScanCount 测试缝；修复前为 30×2 趟 = 60）；
 *  ② 含字符串 SQL 的方法标记产出与修复前一致（GutterIconDedupTest 全绿兼任）。
 *
 * 修法偏离说明见开发计划 §三 T-PerfSweep28-04：原计划的「文件级特征门控」因候选方法名集
 * 含 update/delete/execute 等业务高频词而精度趋零，改为方法体引号预筛（精度 100%）。
 */
class StringSqlMethodBodyPrescanTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        StringSqlMethodGutterLineMarkerProvider.bodyScanCount.set(0)
    }

    private fun methodNameLeaves(): List<PsiIdentifier> =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
            .filter { it.parent is PsiMethod && (it.parent as PsiMethod).nameIdentifier === it }

    /**
     * 本 Provider 只实现快速路径 `getLineMarkerInfo`（无 collectSlowLineMarkers 重载），
     * 故按 leaf 逐个直调——两趟模拟 daemon 两次 pass。
     */
    private fun runPasses(provider: StringSqlMethodGutterLineMarkerProvider = StringSqlMethodGutterLineMarkerProvider()) {
        repeat(2) {
            methodNameLeaves().forEach { provider.getLineMarkerInfo(it) }
        }
    }

    fun test无引号方法体_预筛零子树遍历() {
        val methods = (1..30).joinToString("\n") { i ->
            "    int m$i(int a) { return a + $i * 2; }"
        }
        myFixture.configureByText("Plain.java", """
            class Plain {
        $methods
            }
        """.trimIndent())
        // 前置：方法名叶子确实被喂给 provider（否则零触达是空绿）
        assertEquals("夹具应有 30 个方法名叶子", 30, methodNameLeaves().size)
        runPasses()
        assertEquals("无引号方法体应零全子树遍历（引号预筛短路）", 0, StringSqlMethodGutterLineMarkerProvider.bodyScanCount.get())
    }

    fun test含字符串SQL_标记产出与遍历均发生() {
        myFixture.configureByText("WithSql.java", """
            class WithSql {
                int m1(java.util.List<String> ids) {
                    return jdbc.queryForList("select * from t_user where id = ?", ids).size();
                }
            }
        """.trimIndent())
        runPasses()
        assertEquals("含字符串 SQL 的方法应发生过全子树遍历（预筛放行）", 2, StringSqlMethodGutterLineMarkerProvider.bodyScanCount.get())
    }
}
