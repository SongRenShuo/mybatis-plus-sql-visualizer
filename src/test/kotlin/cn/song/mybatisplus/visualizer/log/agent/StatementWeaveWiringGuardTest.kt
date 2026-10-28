package cn.song.mybatisplus.visualizer.log.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-StatementWeave 接线源码守卫（跨仓：主仓测试直读 agent 子项目源码）。
 *
 * 守卫的是「接线在场」的代码事实——20261005 R2 反向验证实证：字节码常量池判据探不到**调用点
 * 摘除**（方法声明自身留符号），调用点级判据须扫剥注释后的源码（SettingsTruthTest 同款惯例）。
 */
class StatementWeaveWiringGuardTest {

    private fun agentSrc(rel: String) =
        java.io.File("sub-projects/sql-visualizer-agent/src/main/java/cn/song/mybatisplus/sqlvis/agent/$rel")
            .readText()

    /** 剥块注释与行注释——守卫扫代码事实，注释里的「不得如何」不判违规。 */
    private fun stripComments(src: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(src) { m ->
            m.value.replace(Regex("[^\n]"), " ")
        }
        return noBlock.split('\n').joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }
    }

    @Test
    fun test接线守卫_retransform三面_语句面摘除必红() {
        val src = stripComments(agentSrc("AgentMain.java"))
        assertTrue(
            "retransformLoadedDrivers 必须对语句面 retransform（attach 即生效落点，摘掉必红）",
            src.contains("DriverRegistry.isStatementWeavable(name)"),
        )
        assertTrue(
            "retransformLoadedDrivers 必须对 prepare 面 retransform（attach 后新建 PS 的 SQL 登记前提）",
            src.contains("DriverRegistry.isPrepareWeavable(name)"),
        )
        assertTrue(
            "驱动 connect 面不得被语句面改造挤掉",
            src.contains("DriverRegistry.isWeavable(name)"),
        )
    }

    @Test
    fun test接线守卫_transformer三面路由() {
        val src = stripComments(agentSrc("SqlHookTransformer.java"))
        assertTrue("transformer 必须路由语句面到 StatementWeaver", src.contains("StatementWeaver.weave"))
        assertTrue("transformer 必须保留驱动面 ConnectWeaver", src.contains("ConnectWeaver.weave"))
        assertTrue("transformer 必须判定 prepare 面", src.contains("isPrepareWeavable"))
    }

    @Test
    fun test接线守卫_代理层双通道去重门() {
        val src = stripComments(agentSrc("hook/StatementHandler.java"))
        assertTrue(
            "代理层 invoke 原始方法期间必须置 WeaveGate（摘掉=同一条 SQL 双通道双事件）",
            src.contains("WeaveGate.enterProxy()") && src.contains("WeaveGate.exitProxy()"),
        )
    }

    @Test
    fun test接线守卫_回调五件套在场() {
        val src = stripComments(agentSrc("hook/SqlHookCallbacks.java"))
        listOf("onStatementEnter", "onExecuteExit", "onAddBatchExit", "onClearBatchExit", "onStatementPrepare")
            .forEach { key ->
                assertTrue(
                    "SqlHookCallbacks 应声明回调 $key",
                    Regex("public static [\\w<>,\\[\\] .]* $key\\(").containsMatchIn(src),
                )
            }
        assertTrue(
            "onConnection 应记账最近 URL（归属链兜底源）",
            src.contains("UrlResolver.noteConnectUrl"),
        )
    }

    @Test
    fun test接线守卫_F74批串消费复位与clearBatch清账() {
        val callbacks = stripComments(agentSrc("hook/SqlHookCallbacks.java"))
        assertTrue(
            "onExecuteExit 必须 executeBatch 消费批串后复位（F74，摘掉=残串跨批次串包+内存无界，必红）",
            callbacks.contains("ctx.batch.setLength(0)"),
        )
        val weaver = stripComments(agentSrc("StatementWeaver.java"))
        assertTrue(
            "StatementWeaver 必须登记 clearBatch 织入面（F74，摘掉=宿主清批后残串串进下一批事件，必红）",
            weaver.contains("\"clearBatch\""),
        )
        val registry = stripComments(agentSrc("hook/StatementRegistry.java"))
        assertTrue(
            "StatementRegistry 必须提供 clearBatchOf 清账缝（onClearBatchExit 的落点）",
            registry.contains("clearBatchOf"),
        )
    }

    @Test
    fun test接线守卫_登记名单单一真相在WeaveSpecs() {
        // T-JRebelPremainCrash B 案（2026-10-22）：名单单一真相从 StatementWeaver 上提到
        // WeaveSpecs（零 ASM 依赖）——StatementWeaver implements Opcodes 且方法体引用
        // Opcodes.LONG/DOUBLE/TOP（Integer 装箱字段，不可内联），「查名字」触发其类初始化
        // 就会要求重定位 ASM 全程可解析；第三方 agent 接管 premain 类加载时该路径会抛
        // NCDFE/ClassCircularityError 并中止宿主启动（用户现场 JRebel + Corretto 21）。
        //
        // 本守卫的原意（防两份名单漂移）不变，只把落点从 StatementWeaver 改到 WeaveSpecs；
        // 并新增一条反向断言：DriverRegistry **不得**再经 StatementWeaver.specOf 触达 ASM。
        val registry = stripComments(agentSrc("DriverRegistry.java"))
        assertTrue(
            "DriverRegistry 两面谓词必须委托 WeaveSpecs.specOf（单一真相在 WeaveSpecs，防两份名单漂移）",
            registry.contains("WeaveSpecs.specOf"),
        )
        assertFalse(
            "DriverRegistry 不得再走 StatementWeaver.specOf——那会触发 implements Opcodes 的类初始化，" +
                "第三方 agent 干扰 ASM 可见性时 premain 直接抛并中止宿主启动（T-JRebelPremainCrash）",
            registry.contains("StatementWeaver.specOf"),
        )
        val specs = stripComments(agentSrc("WeaveSpecs.java"))
        listOf(
            "com.mysql.cj.jdbc.StatementImpl",
            "com.mysql.cj.jdbc.ClientPreparedStatement",
            "org.h2.jdbc.JdbcPreparedStatement",
            "com.mysql.cj.jdbc.ConnectionImpl",
            "org.h2.jdbc.JdbcConnection",
        ).forEach { cls ->
            assertTrue("WeaveSpecs 应登记 $cls", specs.contains("\"$cls\""))
        }
        // WeaveSpecs 自身必须零 ASM 依赖：出现 org.objectweb.asm 导入即说明解耦被破坏
        assertFalse(
            "WeaveSpecs 必须零 ASM 依赖（否则「查名字」又会要求 ASM 可解析，B 案失效）",
            specs.contains("org.objectweb.asm"),
        )
    }

    @Test
    fun test接线守卫_能力边界通知口径已升级() {
        val zh = javaClass.getResourceAsStream("/messages/MpSqlVisualizerBundle_zh.properties")
            ?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
                lines.firstOrNull { it.startsWith("notification.agent.attachFallback.body=") }
            }
        assertTrue(
            "能力边界通知应升级为「attach 即生效 + 盲区如实」口径",
            zh != null && zh.contains("此后执行的 SQL 即时可见") && zh.contains("不在捕获范围内"),
        )
    }
}
