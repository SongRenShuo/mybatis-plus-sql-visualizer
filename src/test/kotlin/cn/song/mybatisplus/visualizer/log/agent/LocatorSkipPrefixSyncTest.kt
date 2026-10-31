package cn.song.mybatisplus.visualizer.log.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 两侧「框架帧跳过前缀」的跨侧同步守卫（保守选项 —— 保持多份实现 + 差异登记，不抽共享常量；
 * 计划 §八 D3 未定案，故不动结构，只把口径钉死）。
 *
 * 背景：agent 侧 `StackLocator` 的 KDoc 曾自称「框架帧过滤规则与 IDE 侧 `CallerSourceLocator` 对齐」，
 * 实测**失实** —— agent 侧另收驱动族 / 连接池 / 数据层插件族二十余项，IDE 侧只收 JDK / 平台 / 本插件 /
 * MyBatis 系。两侧是**独立实现**（agent 禁依赖 IDE 类），且**在不同时机取栈**（IDE 侧拦截器在
 * SQL 生成期、agent 在 JDBC 执行期），故同一次执行的两处位置**可以不同** —— 两侧 KDoc 已改为事实描述。
 *
 * **T-AgentResiduals R1（2026-09-22）**：IDE 侧按 agent 侧 `FRAME_SKIP_PREFIXES` **逐项补齐**（原 15 项 →
 * 与 agent 侧同集），消除「同一执行两侧给出不同位置（标记行指到框架帧）」这条残留；两侧仍各自独立
 * （IDE 侧绝不引 agent 代码），故本守卫继续以**源码文本扫描 + 差异登记**锁定：`AGENT_ONLY` 现为**空集**
 * （差异只剩各自的自身包，见 `IDE_ONLY`）。空集不等于守卫失效——任一侧新增/删除前缀而未同步另一侧，
 * `agent - ide` / `ide - agent` 立即非空即转红；提取有效性另有量级与锚点自检（防空绿）。
 *
 * ## 本仓「跳过规则」副本清单（共 4 份，改任一份都要同步其余——这是防「同步了不生效的那份」的关键）
 *
 * | # | 副本 | 消费者（谁在什么时机用它取栈） |
 * |---|---|---|
 * | 1 | agent `sub-projects/.../util/StackLocator.java#FRAME_SKIP_PREFIXES` | **生效**：agent 在应用 JVM 的 JDBC 执行期取栈，产事件的 `sf`/`sl` 列（增强捕获通道） |
 * | 2 | IDE `src/main/java/.../log/CallerSourceLocator.java#isFrameworkFrame`（k2j） | **src/main 内无调用点**（T-AgentResiduals 新发现，仅被本守卫与其单测引用；疑死代码/设计遗留，本轮只登记不处理） |
 * | 3 | **注入模板** `src/main/java/.../log/SourceInterceptorTemplates.java` 的 Java `isFrameworkFrame`（k2j） | **生效**：由 `SqlSourceInterceptorAutoInjector` 注入用户工程，在应用 JVM 里打印 `SQLVIS-SOURCE` 标记行（IDE 侧解析它得到源码位置） |
 * | 4 | 文档示例 `docs/instrumentation/SqlSourceInterceptor.kt#isFrameworkFrame` | 发给用户手工拷贝的示例副本（不自动注入） |
 *
 * **R1 的教训（R1b 的立项依据）**：R1 只同步了 2 与 4，**漏了真正生效的 3** —— 于是「同一执行两侧
 * 位置不同」的原始症状在注入模板这条链上仍然存在，而守卫全绿（它当时只覆盖 1/2/4）。现本守卫覆盖
 * **全部 4 份**：任一副本增删前缀而未同步即红。
 *
 * 本测试的判据：
 *  1. 各副本的共同前缀集合须互为子集且含锚点（防「两边都提取为空」的空绿）；
 *  2. 差异集合须与下方登记的清单**完全一致** —— 任一副本新增/删除前缀而未同步登记即转红；
 *  3. 注入模板（3）与插件侧（2）**同集**（无刻意差异）；文档示例（4）相对插件侧只缺自身包。
 */
class LocatorSkipPrefixSyncTest {

    /** 从 user.dir 逐级上溯定位仓库根（与 [AgentProtocolSyncTest.repoRoot] 同款）。 */
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/java").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根: ${System.getProperty("user.dir")}")
    }

    private fun readSource(relative: String): String {
        val f = File(repoRoot(), relative)
        if (!f.isFile) throw AssertionError("源文件未找到: $relative（本守卫按源码文本扫描，文件缺失即失去锚点）")
        return f.readText()
    }

    private fun agentSource(): String =
        readSource("sub-projects/sql-visualizer-agent/src/main/java/cn/song/mybatisplus/sqlvis/agent/util/StackLocator.java")

    // k2j：生产树全 Java——CallerSourceLocator.java 的 isFrameworkFrame 是 Java 方法，
    // 提取用 [javaMethodBody]（Kotlin fun 提取器对 Java 声明失配）。
    private fun ideSource(): String =
        readSource("src/main/java/cn/song/mybatisplus/visualizer/log/CallerSourceLocator.java")

    private fun templateSource(): String = readSource("docs/instrumentation/SqlSourceInterceptor.kt")

    /**
     * 注入模板源码（`SourceInterceptorTemplates.java`）——**真正在用户应用里生效的那份**。
     *
     * k2j 形态：master 侧它是 Kotlin 文件里的一段 **Java 源码原始字符串**（`generateInterceptorClass`
     * 的 `"""…"""`，`$Proxy` 写作 `${'$'}Proxy`）；k2j 的模板容器本身是 Java 文件、模板体是 Java 文本块
     * （text block），`$Proxy` 直接字面出现——`[injectedTemplatePrefixes]` 的 `${'$'}` → `$` 还原退化为
     * no-op，保留以兼容两侧口径。取法仍用 Java 方法体（[javaMethodBody]）。
     */
    private fun injectedTemplateSource(): String =
        readSource("src/main/java/cn/song/mybatisplus/visualizer/log/SourceInterceptorTemplates.java")

    /** 注入模板的跳过前缀集合（Java 方法体字面量 + `${'$'}` → `$` 还原）。 */
    private fun injectedTemplatePrefixes(): Set<String> =
        startsWithLiterals(javaMethodBody(injectedTemplateSource(), "isFrameworkFrame"))
            .map { it.replace("\${'\$'}", "$") }
            .toSet()

    // ---------- 断言 ----------

    @Test
    fun 两侧共同前缀互为子集_差异集合与登记清单完全一致() {
        val agent = agentSkipPrefixes(agentSource())
        val ide = startsWithLiterals(javaMethodBody(ideSource(), "isFrameworkFrame"))

        // 提取有效性自检（防空绿）：共同集合须达到量级并含锚点
        val shared = ide.intersect(agent)
        assertTrue("共同前缀集合过小（疑似提取失效）: $shared", shared.size >= 10)
        assertFalse("提取不得把 defaultLoadedFromJar 的 .jar!/.jar/ 当成跳过前缀（实测踩过）", ".jar!" in agent)
        assertFalse("提取不得把 defaultLoadedFromJar 的 .jar!/.jar/ 当成跳过前缀", ".jar/" in agent)
        for (anchor in listOf("java.", "javax.", "org.apache.ibatis.", "com.baomidou.", "com.github.yulichang.")) {
            assertTrue("两侧共同前缀应含 $anchor（实为 $shared）", anchor in shared)
        }
        // 方向一：IDE 侧集合 = 共同集合 ∪ IDE 独有（互为子集的口径）
        assertEquals("IDE 侧独有前缀与登记不符（新增/删除须同步本清单）", IDE_ONLY, ide - agent)
        // 方向二：agent 侧集合 = 共同集合 ∪ agent 独有
        assertEquals("agent 侧独有前缀与登记不符（新增/删除须同步本清单）", AGENT_ONLY, agent - ide)
    }

    @Test
    fun 示例模板跳过规则与插件侧同步() {
        val ide = startsWithLiterals(javaMethodBody(ideSource(), "isFrameworkFrame"))
        val template = startsWithLiterals(functionBody(templateSource(), "isFrameworkFrame"))

        assertEquals("模板出现插件侧没有的前缀（模板已跑偏）", emptySet<String>(), template - ide)
        assertEquals(
            "插件侧新增前缀未同步到示例模板（用户拷贝后位置会算错）",
            TEMPLATE_ABSENT_FROM_PLUGIN_SIDE,
            ide - template,
        )
        assertTrue("模板应含 MPJ 前缀（T-02）", "com.github.yulichang." in template)
    }

    /**
     * **生效副本**（注入模板，副本 3）与插件侧（副本 2）**同集**——T-AgentResiduals R1b。
     *
     * 这是 R1 漏掉的那一份：R1 同步了「参考副本」（IDE `CallerSourceLocator`，`src/main` 内无调用点）
     * 与文档示例，而真正注入到用户工程、在应用 JVM 里打印 `SQLVIS-SOURCE` 标记行的是本份。
     * 漏同步的后果是**原始症状仍在**（标记行指到框架帧 → 用户点跳转落进第三方库），而守卫全绿
     * ——「同步了不生效的那份」正是本条要防的形态。
     *
     * 与文档示例（副本 4）的区别：本份**保留** `cn.song.mybatisplus.visualizer.`（注入物落在用户工程
     * 的包下，但插件自身包前缀留着无害且与插件侧同集），故这里要求**精确相等**而不是「缺自身包」。
     */
    @Test
    fun 注入模板跳过规则与插件侧同集() {
        val ide = startsWithLiterals(javaMethodBody(ideSource(), "isFrameworkFrame"))
        val injected = injectedTemplatePrefixes()

        // 提取有效性自检（防空绿）：Java 原始串里的方法体若提取失败会得到空集/极小集
        assertTrue(
            "注入模板提取到的前缀过少（疑似提取失效——它是 Kotlin 文件里的 Java 原始串，取法见 injectedTemplatePrefixes）：" +
                "${injected.size} 项 $injected",
            injected.size >= 55,
        )
        assertTrue(
            "注入模板应还原出 \$Proxy（原始串里写作 \${'\$'}Proxy，还原失败即说明提取口径不对）：$injected",
            "\$Proxy" in injected,
        )
        assertEquals("注入模板出现插件侧没有的前缀（注入物已跑偏）", emptySet<String>(), injected - ide)
        assertEquals(
            "插件侧新增前缀未同步到注入模板——用户应用里打印的标记行会指到框架帧（R1b 立项症状）",
            emptySet<String>(),
            ide - injected,
        )
        // R1b 补齐锚点：驱动族 / 连接池 / AOP / 数据层插件族 + agent 自身包，逐族点名（防「两侧同时回退」的假绿）
        for (p in listOf(
            "com.mysql.", "org.mariadb.", "org.postgresql.", "org.h2.", "org.hsqldb.", "org.sqlite.",
            "com.microsoft.sqlserver.", "oracle.jdbc.", "dm.jdbc.", "com.gbasedbt.", "com.kingbase8.",
            "com.huawei.", "org.opengauss.",
            "com.alibaba.druid.", "com.mchange.", "org.apache.commons.dbcp2.", "org.apache.tomcat.",
            "net.sf.cglib.", "org.aspectj.", "org.objectweb.asm.", "org.jboss.",
            "com.github.pagehelper.", "org.mybatis.", "net.sf.jsqlparser.",
            "com.alibaba.", "org.apache.", "com.google.", "com.fasterxml.",
            "io.netty.", "io.lettuce.", "redis.clients.", "io.micrometer.", "io.seata.",
            "org.slf4j.", "ch.qos.", "org.ehcache.", "net.sf.ehcache.", "com.github.benmanes.",
            "org.quartz.", "com.netflix.", "org.elasticsearch.", "com.mongodb.",
            "com.sun.", "jdk.internal.", "com.sun.proxy.", "\$Proxy",
            "cn.song.mybatisplus.sqlvis.", "cn.song.mybatisplus.visualizer.",
        )) {
            assertTrue("注入模板应含 $p（R1b 补齐项）", p in injected)
        }
    }

    /**
     * 副本清单自检（防「下一份副本又没进守卫」）：本仓 4 份跳过规则副本，除 IDE 侧（`src/main` 无调用点，
     * 只登记不处理）外，其余 3 份都必须在扫描面内且提取非空。任何一份**改名/搬家/删方法**都会在这里红，
     * 而不是静默从守卫里消失（本轮 R1 漏同步的根因就是「生效副本不在扫描面」）。
     */
    @Test
    fun 四份副本都在扫描面内且提取非空() {
        assertTrue("副本 1（agent StackLocator）应提取到前缀", agentSkipPrefixes(agentSource()).size >= 55)
        assertTrue("副本 2（IDE CallerSourceLocator）应提取到前缀", startsWithLiterals(javaMethodBody(ideSource(), "isFrameworkFrame")).size >= 55)
        assertTrue("副本 3（注入模板 SourceInterceptorTemplates）应提取到前缀", injectedTemplatePrefixes().size >= 55)
        assertTrue("副本 4（文档示例）应提取到前缀", startsWithLiterals(functionBody(templateSource(), "isFrameworkFrame")).size >= 10)
    }

    @Test
    fun agent侧跳过表覆盖MPJ全族() {
        val agent = agentSkipPrefixes(agentSource())
        assertTrue("agent 侧应含 MPJ 全族前缀（T-02）", "com.github.yulichang." in agent)
        // 同族先例仍在（PageHelper 那次「漏收即被误选」的实证不可回退）
        assertTrue("同族先例 com.github.pagehelper. 不得丢失", "com.github.pagehelper." in agent)
        assertTrue("同族先例 com.github.benmanes. 不得丢失", "com.github.benmanes." in agent)
    }

    /**
     * T-AgentResiduals R1 补齐锚点：驱动族 / 连接池 / AOP / 数据层插件族**两侧都要在**。
     *
     * 为什么单独一条（而不是只靠 `AGENT_ONLY` 空集）：`AGENT_ONLY` 是 `agent - ide` 的期望值，
     * 它只证明「两侧一致」，证明不了「一致在**哪一套**上」——有人把两侧**同时**删空（或 IDE 侧整段
     * 退回 15 项而 agent 侧也同步回退）时差集同样为空。本用例按 R1 的实际交付面逐族点名，
     * 把这些前缀钉在两侧：删任一侧即红。
     */
    @Test
    fun R1补齐锚点_驱动族连接池AOP与数据层插件族两侧都在() {
        val agent = agentSkipPrefixes(agentSource())
        val ide = startsWithLiterals(javaMethodBody(ideSource(), "isFrameworkFrame"))
        val expected = listOf(
            // 驱动家族
            "com.mysql.", "org.mariadb.", "org.postgresql.", "org.h2.", "org.hsqldb.",
            "org.sqlite.", "com.microsoft.sqlserver.", "oracle.jdbc.", "dm.jdbc.",
            "com.gbasedbt.", "com.kingbase8.", "com.huawei.", "org.opengauss.",
            // 连接池
            "com.alibaba.druid.", "com.mchange.", "org.apache.commons.dbcp2.", "org.apache.tomcat.",
            // AOP / 字节码
            "net.sf.cglib.", "org.aspectj.", "org.objectweb.asm.", "org.jboss.",
            // 数据层插件 / 基础设施补齐
            "com.github.pagehelper.", "org.mybatis.", "net.sf.jsqlparser.",
            "com.alibaba.", "org.apache.", "com.google.", "com.fasterxml.",
            "io.netty.", "io.lettuce.", "redis.clients.", "io.micrometer.", "io.seata.",
            "org.slf4j.", "ch.qos.", "org.ehcache.", "net.sf.ehcache.", "com.github.benmanes.",
            "org.quartz.", "com.netflix.", "org.elasticsearch.", "com.mongodb.",
            // JDK 内部 / 代理
            "com.sun.", "jdk.internal.", "com.sun.proxy.", "\$Proxy",
        )
        for (p in expected) {
            assertTrue("R1 补齐项 $p 应同时存在于 agent 侧（实为 ${agent.size} 项）", p in agent)
            assertTrue("R1 补齐项 $p 应同时存在于 IDE 侧（实为 ${ide.size} 项）", p in ide)
        }
        // 同集口径的量化锚点：R1 后两侧共同前缀应达 57 项量级（IDE 侧 = 共同集 + 自身包 1 项）
        assertEquals("R1 后 IDE 侧应只比 agent 侧多自身包一项", 1, ide.size - agent.intersect(ide).size)
        assertTrue("R1 后共同前缀应达 55 项以上（实测两侧同集），实为 ${agent.intersect(ide).size}", agent.intersect(ide).size >= 55)
    }

    // ---------- 源码提取 ----------

    /** 取 Kotlin 函数声明（到下一个函数声明为止）的源码片段。 */
    private fun functionBody(text: String, name: String): String {
        val at = text.indexOf("fun $name(")
        if (at < 0) throw AssertionError("未找到函数声明 fun $name( —— 跳过规则搬家后须同步本守卫")
        val tail = text.substring(at + 1)
        val next = NEXT_FUN.find(tail)
        return if (next == null) tail else tail.substring(0, next.range.first)
    }

    /** 片段内所有 `startsWith("…")` 字面量（Kotlin 源码转义还原）。 */
    private fun startsWithLiterals(snippet: String): Set<String> =
        STARTS_WITH.findAll(snippet).map { unescape(it.groupValues[1]) }.toSet()

    /** agent 侧：`FRAME_SKIP_PREFIXES` 数组字面量 + `isFrameworkFrame` 方法体里的 `contains("…")`（`$Proxy`）。 */
    private fun agentSkipPrefixes(java: String): Set<String> {
        val start = java.indexOf("FRAME_SKIP_PREFIXES = {")
        if (start < 0) throw AssertionError("agent 侧未找到 FRAME_SKIP_PREFIXES 数组")
        val end = java.indexOf("};", start)
        if (end < 0) throw AssertionError("agent 侧 FRAME_SKIP_PREFIXES 数组未闭合")
        // 注释行剔除：注释里可能出现被讨论的类名，不得计入前缀集合
        val body = java.substring(start, end).lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        val out = LinkedHashSet<String>()
        STRING_LITERAL.findAll(body).forEach { out.add(unescape(it.groupValues[1])) }
        // `contains("…")` 只在 isFrameworkFrame 方法体内取——同文件 defaultLoadedFromJar 里也有
        // contains(".jar!") / contains(".jar/")，全文件扫会把它们误当跳过前缀（实测踩过）
        CONTAINS_LITERAL.findAll(javaMethodBody(java, "isFrameworkFrame"))
            .forEach { out.add(unescape(it.groupValues[1])) }
        return out
    }

    /** 取 Java 方法体（自声明起至下一个 4 空格缩进的收尾 `}` 为止）。 */
    private fun javaMethodBody(java: String, name: String): String {
        val at = java.indexOf("$name(String")
        if (at < 0) throw AssertionError("未找到 Java 方法声明 $name(String")
        val tail = java.substring(at)
        val end = JAVA_METHOD_END.find(tail)
        return if (end == null) tail else tail.substring(0, end.range.first)
    }

    private fun unescape(raw: String): String = raw.replace("\\\$", "$").replace("\\\\", "\\")

    companion object {
        private val NEXT_FUN = Regex("""\n\s*(?:private\s+|public\s+|internal\s+)?fun\s""")
        private val JAVA_METHOD_END = Regex("\\n    \\}")
        private val STARTS_WITH = Regex("startsWith\\(\"((?:[^\"\\\\]|\\\\.)*)\"\\)")
        private val STRING_LITERAL = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        private val CONTAINS_LITERAL = Regex("contains\\(\"((?:[^\"\\\\]|\\\\.)*)\"\\)")

        /**
         * IDE 侧独有前缀（登记清单，勿凭印象增删）。**只有一项** = 本插件自身包前缀；
         * agent 侧对应项是它自己的包前缀 `cn.song.mybatisplus.sqlvis.` —— 后者 T-AgentResiduals R1
         * 起已**两侧都有**（IDE 侧运行在应用 JVM 里时 agent 帧同样在栈上），故不再计入任一独有清单。
         */
        private val IDE_ONLY = setOf("cn.song.mybatisplus.visualizer.")

        /**
         * agent 侧独有前缀（登记清单）：**R1 起为空集** —— IDE 侧已按 agent 侧逐项补齐，两侧同集
         * （唯一差异是各自的自身包，见 [IDE_ONLY]）。
         *
         * **空集不代表守卫失效**：本清单是 `agent - ide` 的**期望值**，agent 侧新增前缀而 IDE 侧未同步
         * 时该差集立即非空、与空集不等即转红；另有共同集合量级 + 锚点自检防空绿。
         * 若将来确实需要某一侧独有（如某族帧只在一侧取栈点出现），在此登记并写明依据——
         * 差异必须被登记，不能静默漂移。
         */
        private val AGENT_ONLY = emptySet<String>()

        /**
         * 示例模板相对插件侧**刻意缺失**的前缀：本插件自身包 —— 模板拷进用户工程后包名不同，
         * 它用 `cn == javaClass.name` 的自身帧跳过替代（见模板内注释）。
         * 注意 agent 自身包（`cn.song.mybatisplus.sqlvis.`）**不在**此列：模板运行在应用 JVM 里，
         * agent 帧同样在栈上，故模板与插件侧一样收它。
         */
        private val TEMPLATE_ABSENT_FROM_PLUGIN_SIDE = setOf("cn.song.mybatisplus.visualizer.")
    }
}
