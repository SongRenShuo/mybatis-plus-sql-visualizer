package cn.song.mybatisplus.visualizer.inspection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 禁用 API 规则管理器单元测试（T-BannedApiCheck S1，纯逻辑——直接构造，无 Application 依赖）。
 *
 * 覆盖 CRUD + 判重 + 上限 + 持久化 round-trip（loadState/getState）+ 匹配表重建 + 变更通知。
 */
class BannedApiRuleManagerTest {

    /** 每用例独立实例（不触 Application 级单例，防跨测试类串台）。 */
    private fun newManager(): BannedApiRuleManager = BannedApiRuleManager()

    private fun rule(
        className: String = "com.acme.LegacyDao",
        classRegex: Boolean = false,
        methodName: String = "",
        description: String = "新代码请用 NewDao",
        level: String = BannedApiRule.LEVEL_WARNING,
        id: String = "",
    ) = BannedApiRule(
        id = id,
        className = className,
        classRegex = classRegex,
        methodName = methodName,
        description = description,
        level = level,
    )

    // ---------- CRUD ----------

    @Test
    fun `新增规则_自动分配id并重建匹配表`() {
        val m = newManager()
        assertEquals(BannedApiRuleManager.AddResult.ADDED, m.addRule(rule(methodName = "save")))
        val saved = m.rules.single()
        assertTrue("自动分配 id", saved.id.isNotBlank())
        assertTrue("记录创建时间", saved.createdAt > 0)
        assertEquals("首尾空白被裁剪", "com.acme.LegacyDao", saved.className)
        assertFalse("新增后匹配表非空", m.compiledRules.isEmpty)
        assertNotNull(
            "新规则立即参与匹配",
            m.compiledRules.match("com.acme.LegacyDao", "save"),
        )
    }

    @Test
    fun `同目标重复新增_幂等跳过_说明不同也不算新规则`() {
        val m = newManager()
        m.addRule(rule(description = "第一次说明"))
        assertEquals(
            BannedApiRuleManager.AddResult.DUPLICATE,
            m.addRule(rule(description = "第二次说明")),
        )
        assertEquals(1, m.rules.size)
    }

    @Test
    fun `非法规则拒入表`() {
        val m = newManager()
        assertEquals(BannedApiRuleManager.AddResult.INVALID, m.addRule(rule(className = " ")))
        assertEquals(BannedApiRuleManager.AddResult.INVALID, m.addRule(rule(description = " ")))
        assertEquals(BannedApiRuleManager.AddResult.INVALID, m.addRule(rule(classRegex = true, className = "[bad")))
        assertTrue("非法规则一条都不入库", m.rules.isEmpty())
        assertTrue("匹配表保持空", m.compiledRules.isEmpty)
    }

    @Test
    fun `修改规则_按id覆盖并保留创建时间`() {
        val m = newManager()
        m.addRule(rule(methodName = "save"))
        val saved = m.rules.single()
        val result = m.updateRule(
            saved.copy(methodName = "delete.*", description = "改成删除", level = BannedApiRule.LEVEL_ERROR),
        )
        assertEquals(BannedApiRuleManager.UpdateResult.UPDATED, result)
        val updated = m.rules.single()
        assertEquals("id 不变", saved.id, updated.id)
        assertEquals("createdAt 不变", saved.createdAt, updated.createdAt)
        assertEquals("delete.*", updated.methodName)
        assertEquals(BannedApiRule.LEVEL_ERROR, updated.level)
        assertNull("旧方法名不再命中", m.compiledRules.match("com.acme.LegacyDao", "save"))
        assertNotNull("新方法名命中", m.compiledRules.match("com.acme.LegacyDao", "deleteById"))
    }

    @Test
    fun `修改不存在的id_返回NOT_FOUND`() {
        val m = newManager()
        assertEquals(
            BannedApiRuleManager.UpdateResult.NOT_FOUND,
            m.updateRule(rule(id = "missing")),
        )
    }

    @Test
    fun `修改成与另一条同目标_返回DUPLICATE`() {
        val m = newManager()
        m.addRule(rule(methodName = "save"))
        m.addRule(rule(methodName = "load"))
        val second = m.rules[1]
        assertEquals(
            BannedApiRuleManager.UpdateResult.DUPLICATE,
            m.updateRule(second.copy(methodName = "save")),
        )
    }

    @Test
    fun `删除与清空_返回变化并重建匹配表`() {
        val m = newManager()
        m.addRule(rule(methodName = "save"))
        val id = m.rules.single().id
        assertTrue("删除已有规则", m.removeRule(id))
        assertFalse("重复删除无变化", m.removeRule(id))
        assertTrue("删光后匹配表为空", m.compiledRules.isEmpty)

        m.addRule(rule(methodName = "save"))
        assertTrue("清空有变化", m.clearRules())
        assertFalse("空表再清空无变化", m.clearRules())
        assertTrue(m.rules.isEmpty())
    }

    @Test
    fun `上限拒新增_先到先得`() {
        val m = newManager()
        repeat(BannedApiRuleManager.MAX_RULES) { i ->
            assertEquals(
                BannedApiRuleManager.AddResult.ADDED,
                m.addRule(rule(className = "com.acme.Dao$i")),
            )
        }
        assertEquals(
            BannedApiRuleManager.AddResult.LIMIT_REACHED,
            m.addRule(rule(className = "com.acme.OneMore")),
        )
        assertEquals(BannedApiRuleManager.MAX_RULES, m.rules.size)
    }

    // ---------- 持久化 round-trip ----------

    @Test
    fun `持久化round-trip_规则与匹配表完整恢复`() {
        val source = newManager()
        source.addRule(rule(methodName = "save.*", level = BannedApiRule.LEVEL_ERROR))
        source.addRule(rule(className = """com\.acme\.old\..*""", classRegex = true))

        val restored = BannedApiRuleManager()
        restored.loadState(source.getState())

        assertEquals(2, restored.rules.size)
        assertNotNull("恢复后命中精确规则", restored.compiledRules.match("com.acme.LegacyDao", "saveBatch"))
        assertNotNull("恢复后命中正则规则", restored.compiledRules.match("com.acme.old.Foo", "anything"))
        assertEquals(
            "error 级别一并恢复",
            BannedApiRule.LEVEL_ERROR,
            restored.compiledRules.match("com.acme.LegacyDao", "save")?.level,
        )
    }

    @Test
    fun `loadState_补齐缺失id并归一无知级别`() {
        val restored = BannedApiRuleManager()
        restored.loadState(
            BannedApiRuleManager.State(
                rules = mutableListOf(
                    BannedApiRule(className = "com.acme.A", description = "说明", level = "fatal"),
                ),
            ),
        )
        val rule = restored.rules.single()
        assertTrue("缺 id 就地补齐", rule.id.isNotBlank())
        assertEquals("未知级别归一为 warning", BannedApiRule.LEVEL_WARNING, rule.level)
    }

    // ---------- 变更通知 ----------

    @Test
    fun `变更通知_增改删都触发_注销后不再触发`() {
        val m = newManager()
        var calls = 0
        val listener: () -> Unit = { calls++ }
        m.addListener(listener)

        m.addRule(rule(methodName = "save"))
        assertEquals("新增触发一次", 1, calls)
        m.updateRule(m.rules.single().copy(methodName = "load"))
        assertEquals("修改触发一次", 2, calls)
        m.removeRule(m.rules.single().id)
        assertEquals("删除触发一次", 3, calls)

        m.removeListener(listener)
        m.addRule(rule(methodName = "save"))
        assertEquals("注销后不触发", 3, calls)
    }
}
