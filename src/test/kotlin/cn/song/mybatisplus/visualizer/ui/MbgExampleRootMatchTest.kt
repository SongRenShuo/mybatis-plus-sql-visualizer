package cn.song.mybatisplus.visualizer.ui

import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-PreviewOpenPerfM2 D4：MBG `and*` 调用必须只收**本次 selectByExample 实参那个 example** 的条件。
 *
 * 真机形态（2026-11-12 用户工程）：MBG 生成的服务实现里一个方法常有两条链
 * `exampleNA.createCriteria().andX(); ... selectByExample(exampleNA);` 与 `exampleNB...`。
 * 旧判定按「变量类型名以 Example 结尾」收，会把另一次查询的条件并进来，产物拼成
 * `WHERE (status = ? AND cust_name LIKE ? AND tenant_id = ? AND create_time BETWEEN ? AND tenant_id = ?)`
 * ——两个 Example 的 AND 条件合成一条永不成立的谓词（真机 `[m2] trace … [example06A:5]`，5 条里 2 条属于 06B）。
 *
 * 这里用 Light PSI 钉住判定：同名链收、别家链弃、`Criteria alias = example.createCriteria()` 的
 * 分步写法仍收（别名追溯初始化表达式）。
 */
class MbgExampleRootMatchTest : BasePlatformTestCase() {

    private val src = """
        class Criteria {
            Criteria andTenantIdEqualTo(Object v) { return this; }
            Criteria andCustNoEqualTo(Object v) { return this; }
            Criteria andStatusEqualTo(Object v) { return this; }
            Criteria andCreateTimeBetween(Object a, Object b) { return this; }
        }
        class CustExample {
            Criteria createCriteria() { return new Criteria(); }
        }
        class Svc {
            void run() {
                CustExample exampleNA = new CustExample();
                exampleNA.createCriteria().andTenantIdEqualTo(1).andCustNoEqualTo(2);
                CustExample exampleNB = new CustExample();
                exampleNB.createCriteria().andStatusEqualTo(3).andCreateTimeBetween(4, 5);
                Criteria alias = exampleNA.createCriteria();
                alias.andStatusEqualTo(6);
            }
        }
    """.trimIndent()

    private fun acceptedArgs(exampleName: String): List<String> {
        myFixture.configureByText("Svc.java", src)
        val file = myFixture.file
        return PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java)
            .filter { it.methodExpression.referenceName?.startsWith("and") == true }
            .filter {
                MbgExampleCallResolver.rootedAtExample(it.methodExpression.qualifierExpression, exampleName)
            }
            .map { call -> call.argumentList.expressions.joinToString(",") { it.text } }
            .sorted()
    }

    fun testOnlySameExampleChainAccepted() {
        // exampleNA 的两条链式条件 + 由它 createCriteria() 派生的 alias 条件
        assertEquals(listOf("1", "2", "6"), acceptedArgs("exampleNA"))
    }

    fun testOtherExampleChainRejected() {
        // exampleNB 自己的两条——收 exampleNA 时绝不能进来（D4 的判别点）
        assertEquals(listOf("3", "4,5"), acceptedArgs("exampleNB"))
    }

    fun testUnknownNameAcceptsNothing() {
        assertEquals(emptyList<String>(), acceptedArgs("exampleNotExist"))
    }
}
