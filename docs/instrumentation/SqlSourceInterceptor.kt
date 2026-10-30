/*
 * SQL Visualizer —— 实时日志「精确跳转源码」拦截器（可选，运行期注入）。
 *
 * 为什么需要它：
 *   IDE 控制台里的 SQL 日志（p6spy / MyBatis / Hibernate 输出）本身不含 file:line 调用位置，
 *   所以插件此前只能「按表名猜实体」做近似跳转，经常跳不准。本拦截器在**应用 JVM 的 SQL 执行线程**
 *   用调用栈取出真实调用点（哪个 Service/Controller 的哪一行触发了这条 SQL），打印一行标记到 stdout，
 *   插件侧的 SqlLogConsoleBridge 解析该标记后写入条目，即可获得与「SQL 历史」一致的精确 file:line 跳转。
 *
 * 本文件是**自包含示例**：只依赖 MyBatis-Plus + JDK，不依赖 SQL Visualizer 插件本身。
 * 把它复制到你的项目里，注册进 MybatisPlusInterceptor 拦截器链即可（见文件末尾用法）。
 *
 * 标记行格式（须与插件 SqlSourceMarker 保持一致，3~5 段）：
 *     SQLVIS-SOURCE|<相对项目根的路径，正斜杠>|<行号>
 *     SQLVIS-SOURCE|<相对项目根的路径，正斜杠>|<行号>|<mapper 语句 id>
 *     SQLVIS-SOURCE|<相对项目根的路径，正斜杠>|<行号>|<mapper 语句 id>|<请求标签>
 * 例：SQLVIS-SOURCE|src/main/java/com/example/service/UserService.java|87|com.example.mapper.UserMapper.selectById
 * 第 4 段为 mapper 语句 id（ms.getId()，null 省略）；第 5 段为请求标签（MDC 兜底，
 * 仅在第 4 段在场时输出——段位固定不可跳位）。旧版 3 段标记仍被插件完整支持。
 */

package com.example.demo.sqlvisualizer // ← 改成你自己的包名

import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor
import org.apache.ibatis.executor.Executor
import org.apache.ibatis.mapping.BoundSql
import org.apache.ibatis.mapping.MappedStatement
import org.apache.ibatis.session.ResultHandler
import org.apache.ibatis.session.RowBounds
import java.sql.SQLException

/**
 * 在 SQL 执行前后，捕获调用栈里的第一个「用户代码帧」，打印源码标记行。
 *
 * @param appPackages 可选：只把调用点限定在这些包前缀内（如 "com.example."），
 *                    避免落到第三方库 / 框架代理上。留空则取第一个非框架帧。
 */
class SqlSourceInterceptor(
    private val appPackages: Set<String> = emptySet(),
) : InnerInterceptor {

    override fun beforeQuery(
        executor: Executor,
        ms: MappedStatement,
        parameter: Any?,
        rowBounds: RowBounds,
        resultHandler: ResultHandler<*>,
        boundSql: BoundSql,
    ) {
        emitSource(ms?.id)
    }

    override fun beforeUpdate(executor: Executor, ms: MappedStatement, parameter: Any?) {
        emitSource(ms?.id)
    }

    /**
     * 把当前线程调用栈里的真实调用点打印成标记行（3~5 段）：
     * 第 4 段为 mapper 语句 id（ms.getId()，null 省略）；第 5 段为请求标签（MDC 兜底，
     * 仅在第 4 段在场时输出——段位固定不可跳位）。
     */
    private fun emitSource(mapperStatement: String?) {
        val src = locateSource() ?: return
        // 必须打印到 stdout —— 插件的控制台桥捕获的就是 stdout/stderr 文本
        val sb = StringBuilder("SQLVIS-SOURCE|").append(src.first).append('|').append(src.second)
        if (!mapperStatement.isNullOrEmpty()) {
            sb.append('|').append(mapperStatement)
            val tag = requestTagFromMdc()
            if (!tag.isNullOrEmpty()) sb.append('|').append(tag)
        }
        println(sb)
    }

    /** 反射读取 MDC 请求标签（org.slf4j.MDC.get("sqlvisRequestTag")），无 slf4j 时静默返回 null。 */
    private fun requestTagFromMdc(): String? = try {
        val v = Class.forName("org.slf4j.MDC")
            .getMethod("get", String::class.java)
            .invoke(null, "sqlvisRequestTag")
        v?.toString()
    } catch (_: Throwable) {
        null
    }

    /** 沿调用栈跳过框架帧和拦截器自身，取第一个用户代码帧。 */
    private fun locateSource(): Pair<String, Int>? {
        val stack = Thread.currentThread().stackTrace
        for (frame in stack) {
            val cn = frame.className
            if (isFrameworkFrame(cn)) continue
            // 跳过拦截器自身（运行时类名比对，拷贝/改包后依旧生效）——
            // 拦截器注册在业务包内时，不跳过则位置会被打成本文件的 locateSource 行
            if (cn == javaClass.name) continue
            if (appPackages.isNotEmpty() && appPackages.none { cn.startsWith(it) }) continue
            val fileName = frame.fileName ?: continue
            val pkg = cn.substringBeforeLast('.').replace('.', '/')
            // 必须用正斜杠：插件侧按 `"$basePath/$sourceFile"` 拼接 VFS 路径，反斜杠在 Windows 下解析失败
            val rel = if (pkg.isNotEmpty()) "$pkg/$fileName" else fileName
            val line = frame.lineNumber
            if (line < 0) continue
            return rel to line
        }
        return null
    }

    /**
     * 框架 / 基础设施帧一律跳过（与本插件 `CallerSourceLocator.isFrameworkFrame` 的规则保持同步——
     * 插件侧新增前缀时本示例副本也须同步，`LocatorSkipPrefixSyncTest` 以源码扫描锁定两者差异集合）。
     * T-AgentResiduals R1（2026-09-22）起插件侧按 agent 侧 `StackLocator` 逐项补齐，本示例同步补齐。
     * 唯一刻意差异：插件自身包前缀（`cn.song.mybatisplus.visualizer.`）在本示例里由
     * 上面 `cn == javaClass.name` 的自身帧跳过替代——本文件是拷贝到用户工程里的独立示例。
     * 若你的工程把业务代码放在 `com.alibaba.` / `org.apache.` / `com.google.` 等宽包下，
     * 请删掉对应那几行——它们是为数据层插件/基础设施帧准备的（漏收即跳进框架里）。
     */
    private fun isFrameworkFrame(cn: String): Boolean =
        cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("jdk.") ||
            cn.startsWith("sun.") || cn.startsWith("com.sun.") || cn.startsWith("jdk.internal.") ||
            cn.startsWith("com.intellij.") || cn.startsWith("org.jetbrains.") ||
            // 插件自身模块（agent 侧是 cn.song.mybatisplus.sqlvis.）
            cn.startsWith("cn.song.mybatisplus.sqlvis.") ||
            // 驱动家族
            cn.startsWith("com.mysql.") || cn.startsWith("org.mariadb.") || cn.startsWith("org.postgresql.") ||
            cn.startsWith("org.h2.") || cn.startsWith("org.hsqldb.") || cn.startsWith("org.sqlite.") ||
            cn.startsWith("com.microsoft.sqlserver.") || cn.startsWith("oracle.jdbc.") ||
            cn.startsWith("dm.jdbc.") || cn.startsWith("com.gbasedbt.") || cn.startsWith("com.kingbase8.") ||
            // 信创 PG 系新族：海量 Vastbase（cn.com.vastbase.*）/ 瀚高 HighGo（com.highgo.*）
            cn.startsWith("cn.com.vastbase.") || cn.startsWith("com.highgo.") ||
            // OceanBase（com.oceanbase.*）：独立实现非 MySQL 分叉（URL 前缀 jdbc:oceanbase: 唯一）
            cn.startsWith("com.oceanbase.") ||
            // 华为 GaussDB 商用 / openGauss 品牌（同挂 com.huawei.）与社区 openGauss 2.x/3.x
            cn.startsWith("com.huawei.") || cn.startsWith("org.opengauss.") ||
            // 连接池 / ORM / Spring 数据层
            cn.startsWith("com.zaxxer.") || cn.startsWith("com.alibaba.druid.") ||
            cn.startsWith("com.mchange.") || cn.startsWith("org.apache.commons.dbcp2.") ||
            cn.startsWith("org.apache.tomcat.") || cn.startsWith("org.apache.ibatis.") ||
            cn.startsWith("com.baomidou.") || cn.startsWith("org.hibernate.") ||
            cn.startsWith("org.springframework.") || cn.startsWith("org.aspectj.") ||
            cn.startsWith("net.sf.cglib.") || cn.startsWith("org.objectweb.asm.") ||
            cn.startsWith("com.sun.proxy.") || cn.startsWith("org.jboss.") ||
            // 数据层插件 / 基础设施家族补齐（漏收即被误选为用户帧——PageHelper 实证）
            cn.startsWith("com.github.pagehelper.") || cn.startsWith("org.mybatis.") ||
            cn.startsWith("net.sf.jsqlparser.") || cn.startsWith("com.alibaba.") ||
            cn.startsWith("org.apache.") || cn.startsWith("com.google.") ||
            cn.startsWith("com.fasterxml.") || cn.startsWith("io.netty.") ||
            cn.startsWith("io.lettuce.") || cn.startsWith("redis.clients.") ||
            cn.startsWith("io.micrometer.") || cn.startsWith("io.seata.") ||
            cn.startsWith("org.slf4j.") || cn.startsWith("ch.qos.") ||
            cn.startsWith("org.ehcache.") || cn.startsWith("net.sf.ehcache.") ||
            cn.startsWith("com.github.benmanes.") || cn.startsWith("org.quartz.") ||
            cn.startsWith("com.netflix.") || cn.startsWith("org.elasticsearch.") ||
            cn.startsWith("com.mongodb.") ||
            cn.startsWith("com.github.yulichang.") ||
            cn.startsWith("\$Proxy")

    private fun String.substringBeforeLast(delimiter: Char): String {
        val idx = lastIndexOf(delimiter)
        return if (idx < 0) this else substring(0, idx)
    }
}

/*
 * —— 用法 ——
 *
 * Kotlin（Spring Boot 配置类）：
 *     @Bean
 *     fun mybatisPlusInterceptor(): MybatisPlusInterceptor {
 *         val i = MybatisPlusInterceptor()
 *         i.addInnerInterceptor(SqlSourceInterceptor(setOf("com.example."))) // 限定你的业务包
 *         // 其余拦截器（分页、乐观锁等）按需 addInnerInterceptor(...)
 *         return i
 *     }
 *
 * 原生 MyBatis（无 MP）也可改用 org.apache.ibatis.plugin.Interceptor 在 Executor 的
 * query/update 处做同样的事；原理完全一致。
 *
 * 注册后运行应用，控制台会多出形如 `SQLVIS-SOURCE|...|行号` 的标记行（每条 SQL 一行），
 * 插件会自动识别并让「跳转到源码」精确落到调用处。标记行不影响 SQL 执行，也无性能负担。
 */
