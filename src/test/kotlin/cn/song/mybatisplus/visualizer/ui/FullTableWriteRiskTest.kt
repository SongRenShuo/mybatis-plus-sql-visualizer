package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-FullTableWriteRisk：无 WHERE 的写语句风险判据（纯文本）。
 *
 * 语义与各转换器里既有的「⚠ 无 WHERE 条件，将删除全表数据！」对齐——XML 预览链此前没有这一档，
 * 而它的产物**随勾选实时变化**：勾掉 `_parameter != null` 后 `delete from CUST_ADDRESS where …`
 * 立刻变成 `delete from CUST_ADDRESS`（真机 2026-11-12 截图），面板却只挂「预览未完整还原」。
 */
class FullTableWriteRiskTest {

    @Test
    fun `无WHERE的DELETE与UPDATE报风险`() {
        assertEquals(FullTableWriteRisk.DELETE, fullTableWriteRisk("delete from CUST_ADDRESS"))
        assertEquals(FullTableWriteRisk.UPDATE, fullTableWriteRisk("update CUST_ADDRESS set STATUS = ?"))
        assertEquals(FullTableWriteRisk.DELETE, fullTableWriteRisk("DELETE FROM CUST_ADDRESS;"))
    }

    @Test
    fun `带WHERE不报`() {
        assertNull(fullTableWriteRisk("delete from CUST_ADDRESS where ID = ?"))
        assertNull(fullTableWriteRisk("update CUST_ADDRESS set STATUS = ?\nwhere ID = ?"))
        assertNull(fullTableWriteRisk("delete from t where id in (select id from u where x = 1)"))
        assertNull(fullTableWriteRisk("delete from t where exists (select 1 from u)"))
    }

    @Test
    fun `MBG无调用点骨架带WHERE不报`() {
        // 真机默认产物：`<where>` 非空 ⇒ 有 WHERE（`{{}}` 是 ${} 占位，不是缺 WHERE）
        assertNull(fullTableWriteRisk("delete from CUST_ADDRESS WHERE ( {{criterion.condition}} ? )"))
    }

    @Test
    fun `字面量里的where不算条件`() {
        assertEquals(FullTableWriteRisk.UPDATE, fullTableWriteRisk("update t set c = 'where'"))
        assertEquals(FullTableWriteRisk.DELETE, fullTableWriteRisk("delete from t where_archive = 1"))
        // 双写引号转义仍在字面量内
        assertEquals(FullTableWriteRisk.UPDATE, fullTableWriteRisk("update t set c = 'a''where b'"))
    }

    @Test
    fun `字面量不掩盖真实条件`() {
        assertNull(fullTableWriteRisk("delete from t where c = 'where'"))
        assertNull(fullTableWriteRisk("update t set c = 'x where y' where id = ?"))
    }

    @Test
    fun `注释里的where不算条件`() {
        assertEquals(FullTableWriteRisk.DELETE, fullTableWriteRisk("delete from t -- keep the where clause please"))
        assertEquals(FullTableWriteRisk.DELETE, fullTableWriteRisk("delete from t /* where x */"))
        assertNull(fullTableWriteRisk("delete from t /* x */ where id = ?"))
    }

    @Test
    fun `查询与DDL不报`() {
        assertNull(fullTableWriteRisk("select * from t"))
        assertNull(fullTableWriteRisk("truncate table t"))
        assertNull(fullTableWriteRisk("insert into t (a) values ('where')"))
    }

    @Test
    fun `空文本与未还原骨架不报`() {
        assertNull(fullTableWriteRisk(""))
        assertNull(fullTableWriteRisk("   "))
        assertNull(fullTableWriteRisk("{{criterion.condition}}"))
    }

    @Test
    fun `只看首条语句_分页COUNT并列在后`() {
        assertEquals(
            FullTableWriteRisk.DELETE,
            fullTableWriteRisk("delete from t;\nSELECT COUNT(*) FROM (select 1) TOTAL"),
        )
        // 首条只读、第二条写：按首条判（面板的 COUNT 行是并列渲染的第二条，风险恒在主语句上）
        assertNull(fullTableWriteRisk("select 1;\ndelete from t"))
    }

    @Test
    fun `大小写与换行不影响判定`() {
        assertEquals(FullTableWriteRisk.UPDATE, fullTableWriteRisk("UpDaTe t\n  SET a = 1"))
        assertNull(fullTableWriteRisk("update t\n  set a = 1\n  WhErE id = ?"))
    }
}
