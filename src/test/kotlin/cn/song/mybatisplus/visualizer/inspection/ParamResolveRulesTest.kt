package cn.song.mybatisplus.visualizer.inspection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-02 参数可解析性三规则的**纯逻辑内核**用例（零平台依赖 → testFast 快桶）。
 *
 * 正/反例逐条钉住 0 误报口径；薄壳 PSI 接线另由 [ParamResolveInspectionTest]（平台桶）覆盖。
 *
 * 反向验证锚点：摘掉 [ParamResolveRules.unknownParamPropertyFindings] 的成员比对分支 /
 * [ParamResolveRules.unreferencedParamNames] 的根名比对 / [ParamResolveRules.refidVerdict] 的
 * MISSING 分支，对应用例恰红；还原后全绿。
 */
class ParamResolveRulesTest {

    // ------------------------------------------------------------------
    // 规则①：#{root.prop} 属性段缺失
    // ------------------------------------------------------------------

    @Test
    fun 规则1_正例_属性不在成员集报() {
        val findings = ParamResolveRules.unknownParamPropertyFindings(
            text = "select * from t where a = #{p.noem} and b = #{p.name}",
            paramMemberNames = mapOf("p" to setOf("id", "name")),
        )
        assertEquals(1, findings.size)
        assertEquals("#{p.noem}", findings[0].token)
        assertEquals("p.noem", findings[0].messageArgs[0])
        assertEquals("p", findings[0].messageArgs[1])
        assertEquals(SqlInspectionRule.PARAM_PROPERTY, findings[0].rule)
        assertTrue(findings[0].start >= 0)
    }

    @Test
    fun 规则1_反例_Map型参数不进表即不判() {
        // #{map.key}：map 类型是 Map → 调用方不会把它放进 paramMemberNames → 内核侧无从命中
        val findings = ParamResolveRules.unknownParamPropertyFindings(
            text = "select * from t where k = #{map.anyKey}",
            paramMemberNames = emptyMap(),
        )
        assertTrue("Map 参数的 key 静态不可知，必须沉默", findings.isEmpty())
    }

    @Test
    fun 规则1_反例_成员命中与深层路径首段命中不报() {
        val findings = ParamResolveRules.unknownParamPropertyFindings(
            text = "select * from t where a = #{p.name} and b = #{p.addr.city} and c = #{p.tags[0]}",
            paramMemberNames = mapOf("p" to setOf("name", "addr", "tags")),
        )
        assertTrue("首段命中即放行（深层不判）：$findings", findings.isEmpty())
    }

    @Test
    fun 规则1_反例_bind声明的同名根不判() {
        val findings = ParamResolveRules.unknownParamPropertyFindings(
            text = "select * from t where a = #{p.x}",
            paramMemberNames = mapOf("p" to setOf("id")),
            declaredRoots = setOf("p"),
        )
        assertTrue("bind/foreach 声明遮蔽参数语义，不判：$findings", findings.isEmpty())
    }

    @Test
    fun 规则1_反例_下标根与非标识符段不判() {
        val findings = ParamResolveRules.unknownParamPropertyFindings(
            text = "select * from t where a = #{arr[0].x} and b = #{q.x}",
            paramMemberNames = mapOf("q" to setOf("id")),
        )
        // arr 是下标根（不在表内）不判；q.x 的 x 不在成员集 → 只此一条
        assertEquals(1, findings.size)
        assertEquals("#{q.x}", findings[0].token)
    }

    // ------------------------------------------------------------------
    // 规则②：@Param 声明未引用
    // ------------------------------------------------------------------

    @Test
    fun 规则2_正例_声明未引用报() {
        val unreferenced = ParamResolveRules.unreferencedParamNames(
            paramNames = listOf("userName", "station"),
            referencedRoots = setOf("userName", "param1"),
            statementText = "select * from t where name = #{userName}",
        )
        assertEquals(listOf("station"), unreferenced)
    }

    @Test
    fun 规则2_反例_paramN别名算引用() {
        val unreferenced = ParamResolveRules.unreferencedParamNames(
            paramNames = listOf("x", "y"),
            referencedRoots = setOf("param2"),
            statementText = "select * from t where a = #{param2}",
        )
        // param2 = 第 2 个参数（y）的别名 → y 算引用；x 无任何引用形态仍应报
        assertEquals(listOf("x"), unreferenced)
    }

    @Test
    fun 规则2_反例_整词兜底不误报() {
        // OGNL 属性根取不到（解析失败形态）时，语句文本里整词出现过即算引用
        val unreferenced = ParamResolveRules.unreferencedParamNames(
            paramNames = listOf("flowKey"),
            referencedRoots = emptySet(),
            statementText = "select * from t where k = ??? and c = flowKey",
        )
        assertTrue("词边界兜底（宁漏报不误报）：$unreferenced", unreferenced.isEmpty())
    }

    @Test
    fun 规则2_词边界_子串出现不算引用() {
        // userIdHistory 包含 userId 子串但不是整词 → 不算引用（仍报）
        val unreferenced = ParamResolveRules.unreferencedParamNames(
            paramNames = listOf("userId"),
            referencedRoots = setOf("userIdHistory"),
            statementText = "select * from t where h = #{userIdHistory}",
        )
        assertEquals(listOf("userId"), unreferenced)
    }

    @Test
    fun 规则2_引用根收集_含OGNL属性根() {
        val roots = ParamResolveRules.referencedRootsOf(
            tokenRoots = listOf("name"),
            attributeExpressions = listOf("p.startTime != null", "ids"),
        )
        assertTrue("token 根在场：${roots}", "name" in roots)
        assertTrue("OGNL test 根在场：$roots", "p" in roots)
        assertTrue("foreach collection 直引根在场：$roots", "ids" in roots)
    }

    // ------------------------------------------------------------------
    // 规则③：<include refid> 片段解析
    // ------------------------------------------------------------------

    @Test
    fun 规则3_正例_本地与同namespace都未命中报MISSING() {
        val verdict = ParamResolveRules.refidVerdict(
            refid = "base_where",
            currentNamespace = "com.acme.UserMapper",
            localSqlIds = setOf("other"),
            sameNamespaceSqlIds = emptySet(),
            qualifiedLookup = { _, _ -> null },
        )
        assertEquals(ParamResolveRules.RefidVerdict.MISSING, verdict)
    }

    @Test
    fun 规则3_反例_本文件命中() {
        val verdict = ParamResolveRules.refidVerdict(
            refid = "base_where",
            currentNamespace = "com.acme.UserMapper",
            localSqlIds = setOf("base_where"),
            sameNamespaceSqlIds = emptySet(),
            qualifiedLookup = { _, _ -> null },
        )
        assertEquals(ParamResolveRules.RefidVerdict.RESOLVED, verdict)
    }

    @Test
    fun 规则3_反例_同namespace其它文件命中() {
        // MyBatis 片段表键 = namespace.id，同 namespace 跨文件合法
        val verdict = ParamResolveRules.refidVerdict(
            refid = "base_where",
            currentNamespace = "com.acme.UserMapper",
            localSqlIds = emptySet(),
            sameNamespaceSqlIds = setOf("base_where"),
            qualifiedLookup = { _, _ -> null },
        )
        assertEquals(ParamResolveRules.RefidVerdict.RESOLVED, verdict)
    }

    @Test
    fun 规则3_动态refid判不了() {
        val verdict = ParamResolveRules.refidVerdict(
            refid = "\${tablePrefix}_cols",
            currentNamespace = "com.acme.UserMapper",
            localSqlIds = emptySet(),
            sameNamespaceSqlIds = emptySet(),
            qualifiedLookup = { _, _ -> null },
        )
        assertEquals(ParamResolveRules.RefidVerdict.UNDECIDED, verdict)
    }

    @Test
    fun 规则3_限定引用_目标命中与未命中与不可见() {
        fun verdict(refid: String, lookup: (String, String) -> Boolean?) = ParamResolveRules.refidVerdict(
            refid = refid,
            currentNamespace = "com.acme.UserMapper",
            localSqlIds = emptySet(),
            sameNamespaceSqlIds = emptySet(),
            qualifiedLookup = lookup,
        )
        assertEquals(
            ParamResolveRules.RefidVerdict.RESOLVED,
            verdict("com.acme.CommonMapper.cols", { ns, id -> ns == "com.acme.CommonMapper" && id == "cols" }),
        )
        assertEquals(
            ParamResolveRules.RefidVerdict.MISSING,
            verdict("com.acme.CommonMapper.nope", { _, id -> id == "cols" }),
        )
        assertEquals(
            ParamResolveRules.RefidVerdict.UNDECIDED,
            verdict("com.acme.InvisibleMapper.cols", { _, _ -> null }),
        )
    }

    @Test
    fun 规则3_限定引用指向当前namespace走片段表() {
        val verdict = ParamResolveRules.refidVerdict(
            refid = "com.acme.UserMapper.base_where",
            currentNamespace = "com.acme.UserMapper",
            localSqlIds = setOf("base_where"),
            sameNamespaceSqlIds = emptySet(),
            qualifiedLookup = { _, _ -> null },
        )
        assertEquals(ParamResolveRules.RefidVerdict.RESOLVED, verdict)
    }
}
