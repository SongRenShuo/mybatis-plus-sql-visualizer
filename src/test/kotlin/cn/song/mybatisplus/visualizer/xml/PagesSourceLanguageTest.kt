package cn.song.mybatisplus.visualizer.xml

import cn.song.mybatisplus.visualizer.psi.jpa.HqlEntityNameExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-PagesHql：`<datasource>` 源语言判定与 `#{}` 预处理（纯函数，无 PSI/工程）。
 *
 * 判据口径见 [PagesSourceLanguageDetector]：默认 SQL 保守，只有明确证据才判 HQL。
 */
class PagesSourceLanguageTest {

    private fun detect(
        sql: String,
        entityClassName: String? = null,
        declaredType: String? = null,
        probe: (String) -> PagesEntityProbe? = { null },
    ): PagesSourceDetection = PagesSourceLanguageDetector.detect(
        sql = sql,
        entityClassName = entityClassName,
        declaredType = declaredType,
        rootIdentifierOf = HqlEntityNameExtractor::extract,
        probeEntity = probe,
    )

    // ------------------------------------------------------------------
    // 判定：显式类型
    // ------------------------------------------------------------------

    @Test
    fun `type为hql_显式判定HQL`() {
        val d = detect("select x from Whatever x", declaredType = "hql")
        assertEquals(PagesSourceLanguage.HQL, d.language)
        assertEquals(PagesSourceEvidence.EXPLICIT_TYPE, d.evidence)
    }

    @Test
    fun `type为sql_显式判定SQL`() {
        val d = detect("select x from BaseLocation x", entityClassName = "com.x.BaseLocation", declaredType = "sql")
        assertEquals(PagesSourceLanguage.SQL, d.language)
        assertEquals(PagesSourceEvidence.EXPLICIT_SQL, d.evidence)
    }

    // ------------------------------------------------------------------
    // 判定：entityClass 相关性（用户样本形态）
    // ------------------------------------------------------------------

    @Test
    fun `根标识符等于entityClass简单名_判定HQL`() {
        val d = detect(
            sql = "SELECT location.id FROM BaseLocation location WHERE location.type NOT IN ('N','M')",
            entityClassName = "com.vtradex.logimaster.common.model.entity.location.BaseLocation",
        )
        assertEquals(PagesSourceLanguage.HQL, d.language)
        assertEquals(PagesSourceEvidence.ROOT_IS_ENTITY_CLASS, d.evidence)
        assertEquals("BaseLocation", d.rootIdentifier)
    }

    @Test
    fun `根标识符等于Entity注解名_判定HQL`() {
        val d = detect(
            sql = "from LocationRoot r",
            entityClassName = "com.x.BaseLocation",
            probe = { PagesEntityProbe("LocationRoot", "base_location") },
        )
        assertEquals(PagesSourceLanguage.HQL, d.language)
        assertEquals(PagesSourceEvidence.ROOT_IS_ENTITY_CLASS, d.evidence)
    }

    @Test
    fun `根标识符等于entityClass表名_反判SQL`() {
        // demo 的 orderMaintain 形态：entityClass=Order，但语句是原生 SQL FROM t_order
        val d = detect(
            sql = "SELECT o.id FROM t_order o",
            entityClassName = "cn.song.mybatisplusdemo.entity.Order",
            probe = { PagesEntityProbe("Order", "t_order") },
        )
        assertEquals(PagesSourceLanguage.SQL, d.language)
        assertEquals(PagesSourceEvidence.ROOT_IS_TABLE, d.evidence)
    }

    // ------------------------------------------------------------------
    // 判定：无 entityClass 时的实体反查
    // ------------------------------------------------------------------

    @Test
    fun `无entityClass_根名解析为实体且表名不同_判定HQL`() {
        val d = detect(
            sql = "select b.id from BaseLocation b",
            probe = { name ->
                if (name == "BaseLocation") PagesEntityProbe("BaseLocation", "base_location") else null
            },
        )
        assertEquals(PagesSourceLanguage.HQL, d.language)
        assertEquals(PagesSourceEvidence.ROOT_RESOLVES_ENTITY, d.evidence)
    }

    @Test
    fun `无entityClass_根名与表名相同_保持SQL`() {
        val d = detect(
            sql = "select u.id from user u",
            probe = { name ->
                if (name == "user") PagesEntityProbe("user", "user") else null
            },
        )
        assertEquals(PagesSourceLanguage.SQL, d.language)
        assertEquals(PagesSourceEvidence.DEFAULT_SQL, d.evidence)
    }

    @Test
    fun `无entityClass_无探针_保持SQL`() {
        val d = detect("select u.id from user u")
        assertEquals(PagesSourceLanguage.SQL, d.language)
        assertEquals(PagesSourceEvidence.DEFAULT_SQL, d.evidence)
    }

    @Test
    fun `HQL专属记号加权_表名与根名相同也判HQL`() {
        val d = detect(
            sql = "select u from user u join fetch u.roles",
            probe = { name ->
                if (name == "user") PagesEntityProbe("user", "user") else null
            },
        )
        assertEquals(PagesSourceLanguage.HQL, d.language)
        assertEquals(PagesSourceEvidence.ROOT_RESOLVES_ENTITY, d.evidence)
    }

    @Test
    fun `实体探针抛异常_不炸判定_回落SQL`() {
        // 根名与 entityClass 简单名不同 → 走探针路径；探针抛异常必须被吞掉并回落 SQL
        val d = detect(
            sql = "select x from SomethingElse x",
            entityClassName = "com.x.Whatever",
            probe = { error("boom") },
        )
        assertEquals(PagesSourceLanguage.SQL, d.language)
    }

    // ------------------------------------------------------------------
    // #{} 预处理
    // ------------------------------------------------------------------

    @Test
    fun `框架占位符_合法标识符沿用原名`() {
        val r = PagesFrameworkPlaceholder.rewrite("where orgId = #{SESSION_BASE_ORGAN}")
        assertEquals("where orgId = :SESSION_BASE_ORGAN", r.text)
        assertEquals("SESSION_BASE_ORGAN", r.displayNames["SESSION_BASE_ORGAN"])
    }

    @Test
    fun `框架占位符_非法标识符用合成名并保留展示名`() {
        val r = PagesFrameworkPlaceholder.rewrite("where a = #{user.dept.id} and b = #{x y}")
        assertTrue(r.text.contains(":__p_1"))
        assertTrue(r.text.contains(":__p_2"))
        assertEquals("user.dept.id", r.displayNames["__p_1"])
        assertEquals("x y", r.displayNames["__p_2"])
    }

    @Test
    fun `框架占位符_同名重复复用同一参数`() {
        val r = PagesFrameworkPlaceholder.rewrite("a = #{ORG} or b = #{ORG}")
        assertEquals("a = :ORG or b = :ORG", r.text)
        assertEquals(1, r.displayNames.size)
    }

    @Test
    fun `框架占位符_单引号字面量内不替换`() {
        val r = PagesFrameworkPlaceholder.rewrite("where note = '#{NOT_A_PARAM}' and id = #{ID}")
        assertEquals("where note = '#{NOT_A_PARAM}' and id = :ID", r.text)
        assertEquals(setOf("ID"), r.displayNames.keys)
    }

    @Test
    fun `框架占位符_空表达式原样保留且不产生参数`() {
        val r = PagesFrameworkPlaceholder.rewrite("where a = #{}")
        assertEquals("where a = #{}", r.text)
        assertTrue(r.displayNames.isEmpty())
    }

    @Test
    fun `无占位符_原样返回`() {
        val sql = "select * from t where a = :b"
        val r = PagesFrameworkPlaceholder.rewrite(sql)
        assertEquals(sql, r.text)
        assertTrue(r.displayNames.isEmpty())
    }
}
