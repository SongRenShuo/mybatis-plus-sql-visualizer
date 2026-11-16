package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-IbatisParamClassQuote（L34）：短名候选收窄的纯逻辑用例。
 *
 * 背景：`parameterClass="User"` 在真实多模块工程里会命中多个同名类（demo 项目三个 `User`），
 * 首版「唯一命中才用」等于常态弃权，引号修复在真机不生效。收窄规则＝唯一命中直接用；
 * 多候选按「语句所在模块」过滤，仍不唯一则弃权（贴错类型比不贴更糟）。
 */
class IbatisParamCandidatePickTest {

    private val candidates = listOf("ibatis2-demo:User", "mpsv-walk-proj:User", "mybatis-plus-demo:User")

    private val moduleOf: (String) -> String? = { it.substringBefore(':') }

    @Test
    fun `唯一命中直接采用`() {
        assertEquals("only", IbatisParameterClassResolver.pickCandidate(listOf("only"), null) { null })
    }

    @Test
    fun `多候选按模块收窄到语句所在模块`() {
        assertEquals("ibatis2-demo:User", IbatisParameterClassResolver.pickCandidate(candidates, "ibatis2-demo", moduleOf))
        assertEquals("mybatis-plus-demo:User", IbatisParameterClassResolver.pickCandidate(candidates, "mybatis-plus-demo", moduleOf))
    }

    @Test
    fun `同模块内仍多候选则弃权`() {
        assertNull(IbatisParameterClassResolver.pickCandidate(listOf("m:a", "m:b"), "m", moduleOf))
    }

    @Test
    fun `无模块信息时多候选弃权`() {
        assertNull(IbatisParameterClassResolver.pickCandidate(candidates, null, moduleOf))
    }

    @Test
    fun `模块不匹配时弃权`() {
        assertNull(IbatisParameterClassResolver.pickCandidate(candidates, "no-such-module", moduleOf))
    }

    @Test
    fun `空候选返回null`() {
        assertNull(IbatisParameterClassResolver.pickCandidate(emptyList<String>(), "m", moduleOf))
    }
}