package cn.song.mybatisplus.visualizer.onboarding

import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-NotifyTune 券 A 决策测试：DialectGapTip 的纯函数缝（无平台依赖）——
 * 展示决策四档、探测目标、SQL 承载文件识别面、死路判据、标题不带框架名。
 */
class DialectGapTipTest {

    @Test
    fun `展示决策_开关一次性抑制与让位`() {
        assertTrue(
            "开关开 + 未展示 + 未抑制 + 锚点引导已让位 → 探测",
            DialectGapTip.shouldProbe(
                enabled = true, alreadyShown = false, suppressedForProject = false, anchorTipPending = false,
            ),
        )
        assertFalse(
            "开关关 → 不探测",
            DialectGapTip.shouldProbe(
                enabled = false, alreadyShown = false, suppressedForProject = false, anchorTipPending = false,
            ),
        )
        assertFalse(
            "已展示（每安装一次性）→ 不探测",
            DialectGapTip.shouldProbe(
                enabled = true, alreadyShown = true, suppressedForProject = false, anchorTipPending = false,
            ),
        )
        assertFalse(
            "本项目已抑制 → 不探测",
            DialectGapTip.shouldProbe(
                enabled = true, alreadyShown = false, suppressedForProject = true, anchorTipPending = false,
            ),
        )
        assertFalse(
            "锚点引导待展示（让位）→ 不探测",
            DialectGapTip.shouldProbe(
                enabled = true, alreadyShown = false, suppressedForProject = false, anchorTipPending = true,
            ),
        )
    }

    @Test
    fun `探测目标_仅三类源文件`() {
        assertTrue("java", DialectGapTip.isProbeTarget("UserMapper.java"))
        assertTrue("kt", DialectGapTip.isProbeTarget("UserMapper.kt"))
        assertTrue("xml", DialectGapTip.isProbeTarget("UserMapper.xml"))
        assertFalse("md 不在探测面", DialectGapTip.isProbeTarget("README.md"))
        assertFalse("sql 无宿主文件语义", DialectGapTip.isProbeTarget("init.sql"))
    }

    @Test
    fun `死路判据_仅UNAVAILABLE且无方言名`() {
        assertTrue(
            "UNAVAILABLE + 无方言名 → 提示",
            DialectGapTip.isDialectGap(DialectDecisionEngine.Status.UNAVAILABLE, null),
        )
        assertFalse(
            "UNAVAILABLE 但有兜底名 → 已有可用值，不提示",
            DialectGapTip.isDialectGap(DialectDecisionEngine.Status.UNAVAILABLE, "MySQL"),
        )
        assertFalse(
            "AMBIGUOUS 已在面板内处理",
            DialectGapTip.isDialectGap(DialectDecisionEngine.Status.AMBIGUOUS, null),
        )
        assertFalse(
            "CONFLICT 已在面板内处理",
            DialectGapTip.isDialectGap(DialectDecisionEngine.Status.CONFLICT, null),
        )
        assertFalse(
            "RESOLVED 正常",
            DialectGapTip.isDialectGap(DialectDecisionEngine.Status.RESOLVED, "PostgreSQL"),
        )
        assertFalse(
            "MANUAL 正常",
            DialectGapTip.isDialectGap(DialectDecisionEngine.Status.MANUAL, "Oracle"),
        )
    }

    @Test
    fun `SQL承载文件识别面_覆盖本插件全部支持族`() {
        // MyBatis / MyBatis-Plus / MPJ Wrapper 链
        assertTrue("MP Wrapper 链", DialectGapTip.isSqlBearingFile("UserService.java", "new QueryWrapper<User>()"))
        assertTrue("MPJ Wrapper 链（Kotlin）", DialectGapTip.isSqlBearingFile("UserService.kt", "MPJLambdaWrapper<User>()"))
        // MyBatis 注解 SQL
        assertTrue("注解 SQL", DialectGapTip.isSqlBearingFile("UserMapper.java", "@Select(\"select * from t_user\")"))
        // JPA / HQL
        assertTrue("JPA @Query", DialectGapTip.isSqlBearingFile("UserRepo.java", "@Query(\"select u from User u\")"))
        // Spring JdbcTemplate
        assertTrue("JdbcTemplate", DialectGapTip.isSqlBearingFile("ReportDao.java", "jdbcTemplate.queryForList(sql)"))
        // iBatis 2.x Java 调用
        assertTrue("iBatis SqlMapClient", DialectGapTip.isSqlBearingFile("UserDaoImpl.java", "SqlMapClient client"))
        // XML：mapper / hbm / iBatis sqlMap
        assertTrue("mapper 语句", DialectGapTip.isSqlBearingFile("UserMapper.xml", "<select id=\"selectAll\">"))
        assertTrue("hbm 命名查询", DialectGapTip.isSqlBearingFile("User.hbm.xml", "<hibernate-mapping>"))
        assertTrue("iBatis sqlMap 根", DialectGapTip.isSqlBearingFile("sqlmap-user.xml", "<sqlMap namespace=\"User\">"))
        assertTrue("iBatis statement", DialectGapTip.isSqlBearingFile("sqlmap-user.xml", "<statement id=\"insertUser\">"))
    }

    @Test
    fun `SQL承载文件识别面_非目标不误报`() {
        assertFalse("空文本", DialectGapTip.isSqlBearingFile("UserService.java", ""))
        assertFalse(
            "纯业务 Java",
            DialectGapTip.isSqlBearingFile("UserService.java", "class UserService { void hello() {} }"),
        )
        assertFalse("非目标扩展名", DialectGapTip.isSqlBearingFile("notes.txt", "@Select"))
        assertFalse(
            "普通 XML",
            DialectGapTip.isSqlBearingFile("pom.xml", "<project><dependencies/></project>"),
        )
    }

    @Test
    fun `标题不带框架名`() {
        // 用户 2026-11-11：插件不止支持 MyBatis，提示标题不得写死 MyBatis
        val title = MpSqlVisualizerBundle.message("dialectGapTip.title")
        assertFalse("标题不得含 MyBatis：$title", title.contains("MyBatis", ignoreCase = true))
    }
}