package cn.song.mybatisplus.visualizer.inspection

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * 规则序列化 + inspection 注册形态平台测试（T-BannedApiCheck S1/S3）。
 *
 *  - **序列化**：规则是独立 storage 的 XmlSerializer 载体，必须 round-trip 无损
 *    （字段全是基本类型/String，`classRegex` 这类 boolean 标记丢了就等于把精确规则变成正则规则）；
 *  - **注册**：`<localInspection>` 的属性（key/bundle/groupKey/enabledByDefault/level/shortName）
 *    在平台侧解析成什么样，只有 ExtensionPoint 读到才算数——插件首个 inspection，注册形态错了
 *    平台不会报错（检查静默不出现）。
 *
 * 属平台桶（`com.intellij` 引用），不在 `testFast` 快桶内，定向跑。
 */
class BannedApiRuleSerializationTest : BasePlatformTestCase() {

    // ---------- XmlSerializer round-trip ----------

    fun test规则XmlSerializer往返_字段无损() {
        val rule = BannedApiRule(
            className = """com\.acme\.legacy\..*""",
            classRegex = true,
            methodName = "save.*",
            description = "改用 NewDao，旧 DAO 已停止维护",
            level = BannedApiRule.LEVEL_ERROR,
            createdAt = 1700000000000L,
        ).apply { ensureId() }

        val xml = XmlSerializer.serialize(rule)
        val back = XmlSerializer.deserialize(xml, BannedApiRule::class.java)

        assertEquals("全部字段 round-trip 一致", rule, back)
        assertTrue("classRegex 标记必须保住（丢了会把正则当精确 FQN）", back.classRegex)
    }

    fun test状态XmlSerializer往返_规则列表完整() {
        val state = BannedApiRuleManager.State(
            mutableListOf(
                BannedApiRule(className = "com.acme.A", description = "说明 A").apply { ensureId() },
                BannedApiRule(className = "com.acme.B", methodName = "x.*", description = "说明 B", level = BannedApiRule.LEVEL_ERROR).apply { ensureId() },
            ),
        )
        val xml = XmlSerializer.serialize(state)
        val back = XmlSerializer.deserialize(xml, BannedApiRuleManager.State::class.java)
        assertEquals("规则条数一致", 2, back.rules.size)
        assertEquals("规则内容逐字段一致", state.rules, back.rules)
    }

    // ---------- localInspection EP 注册形态 ----------

    private fun bannedApiEps(): List<LocalInspectionEP> =
        LocalInspectionEP.LOCAL_INSPECTION.extensionList.filter { it.getShortName() == "BannedApi" }

    fun test注册_EP存在且属性解析正确() {
        val eps = bannedApiEps()
        assertEquals("plugin.xml 应注册恰好一个 BannedApi 检查", 1, eps.size)
        val ep = eps.single()

        // 平台解析 EP 文案走 IDE 语言（DynamicBundle），不跟插件语言设置——故按两语言取值集合断言，
        // 关键是「不是键原文」（键没进 bundle 时 getDisplayName 会原样返回键）
        val displayName = ep.getDisplayName()
        assertTrue(
            "显示名应是 bundle 的中英任一文案（不是键原文）：$displayName",
            displayName in setOf(
                MpSqlVisualizerBundle.message("inspection.bannedApi.displayName"),
                MpSqlVisualizerBundle.messageEn("inspection.bannedApi.displayName"),
            ),
        )
        val groupName = ep.getGroupDisplayName()
        assertTrue(
            "分组显示名应是 bundle 的中英任一文案（不是 groupKey 原文）：$groupName",
            groupName in setOf(
                MpSqlVisualizerBundle.message("inspection.bannedApi.groupName"),
                MpSqlVisualizerBundle.messageEn("inspection.bannedApi.groupName"),
            ),
        )
        assertTrue("默认启用（规则为空时零行为）", ep.enabledByDefault)
        assertEquals(
            "默认级别 WARNING（逐规则级别由 ProblemHighlightType 表达）",
            HighlightDisplayLevel.WARNING,
            ep.getDefaultLevel(),
        )
        assertTrue("实例化得到 BannedApiInspection", ep.instantiateTool() is BannedApiInspection)
    }
}
