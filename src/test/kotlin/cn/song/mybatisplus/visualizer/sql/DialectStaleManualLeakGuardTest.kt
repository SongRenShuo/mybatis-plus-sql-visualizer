package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * T-BatchTestLeak（O-93）守卫探针：全局手动方言残值不得污染 auto 模式渲染。
 *
 * 背景：PaginationSimTest 方言矩阵曾泄漏 `state.dialect="Oracle"`（tearDown 未还原），
 * 而 [DialectRegistry.effective] 在 project=null（psi 渲染链）+ auto=true 时仍返回手动值，
 * 导致同 JVM 后续的 SqlPreviewServiceBatchTest 5 例方言/引用 ComparisonFailure
 * （upsert 降级纯 INSERT + 反引号变双引号），且失败集合随装箱顺序 3~8 浮动。
 *
 * 本探针把「前驱污染操作 → 目标行为不受污染」固化为确定性顺序断言（同 JVM 内显式写残值）：
 *  - 探针一：`effective(project=null)` 在 auto=true 时必须忽略手动残值回退 MySQL
 *    （修复前返回 OracleDialect——反向验证锚点）；
 *  - 探针二：端到端——残值在位时 saveOrUpdate 批量预览仍渲染 MySQL upsert
 *    （ON DUPLICATE KEY UPDATE + 反引号保留字引用）。
 */
class DialectStaleManualLeakGuardTest : BasePlatformTestCase() {

    private var savedDialect: String = PluginSettings.DEFAULT_DIALECT
    private var savedDialectAuto: Boolean = true

    override fun setUp() {
        super.setUp()
        val state = PluginSettings.getInstance().state
        savedDialect = state.dialect
        savedDialectAuto = state.dialectAuto
    }

    override fun tearDown() {
        val state = PluginSettings.getInstance().state
        state.dialect = savedDialect
        state.dialectAuto = savedDialectAuto
        super.tearDown()
    }

    /** 模拟前驱类的污染操作：写全局手动方言残值、auto 保持默认 true（不还原，由本类 tearDown 收口）。 */
    private fun leakStaleManualDialect() {
        val state = PluginSettings.getInstance().state
        state.dialect = "Oracle"
        state.dialectAuto = true
    }

    fun testEffectiveNullProjectAutoModeIgnoresStaleManualDialect() {
        leakStaleManualDialect()
        assertEquals(
            "auto 模式下 project=null（psi 渲染链）必须忽略手动残值回退 MySQL",
            MySqlDialect.INSTANCE,
            DialectRegistry.INSTANCE.effective(null as com.intellij.openapi.project.Project?),
        )
    }

    fun testEffectiveNullProjectManualModeStillHonorsManualDialect() {
        // 对照组：手动模式（auto=false）语义不变——psi 渲染链仍取手动值
        val state = PluginSettings.getInstance().state
        state.dialect = "Oracle"
        state.dialectAuto = false
        assertEquals(OracleDialect.INSTANCE, DialectRegistry.INSTANCE.effective(null as com.intellij.openapi.project.Project?))
    }

    fun testBatchUpsertPreviewImmuneToStaleManualDialect() {
        leakStaleManualDialect()
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private StationOccupyInfoService stationOccupyInfoService;

                public void saveOrUpdate() {
                    StationOccupyInfoEntity entity = new StationOccupyInfoEntity();
                    stationOccupyInfoService.saveOrUpdate(entity);
                }

                static class StationOccupyInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private String table;
                }

                static class StationOccupyInfoService {
                    public void saveOrUpdate(StationOccupyInfoEntity entity) {}
                }
            }
            """.trimIndent(),
        )
        val anchor = PsiTreeUtil.findChildOfType(myFixture.file, PsiMethodCallExpression::class.java)
            ?: PsiTreeUtil.getDeepestFirst(myFixture.file)
        val result = SqlPreviewService(project).preview(anchor as PsiElement)
        assertNotNull("saveOrUpdate 应产出预览结果", result)
        assertTrue(
            "残值污染下 upsert 仍应为 MySQL 形态: ${result!!.sql}",
            result.sql.contains("ON DUPLICATE KEY UPDATE"),
        )
        assertTrue(
            "残值污染下保留字列仍应反引号引用: ${result.sql}",
            result.sql.contains("`table`"),
        )
    }
}
