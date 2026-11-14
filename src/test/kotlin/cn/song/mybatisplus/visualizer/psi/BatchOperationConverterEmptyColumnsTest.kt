package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 Y10（not run 补测）：批量链零列实体降级回归。
 *
 * EntityResolver 对无字段实体产出 columns=emptyList()——五处
 * `table.columns.firstOrNull { it.isId } ?: table.columns.first()` 曾抛
 * NoSuchElementException 穿透 convert；修复后收敛 null（convert 合同允许
 * null = 不预览），buildSelectBatch/buildUpsertBatch 签名随之改可空。
 */
class BatchOperationConverterEmptyColumnsTest : BasePlatformTestCase() {

    fun test零列实体updateBatchById_降级null不抛() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query(java.util.List<EmptyEntity> list) {
                    mapper.updateBatchById(list);
                }

                static class EmptyEntity {
                }
            }
            """.trimIndent(),
        )
        val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "updateBatchById" }
        val result = BatchOperationConverter.convert(call)
        assertNull("零列实体应降级 null（修复前 NoSuchElementException 穿透）", result)
    }
}
