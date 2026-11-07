package cn.song.mybatisplus.visualizer.testutil

/**
 * DB 集成测试的连接信息取值口径（T-CiDbConn，2026-11-05）。
 *
 * 背景：DB 集成用例原先固定「demo 的 `.idea/dataSources.xml` 取 URL + 硬编码 root/root」，
 * 这只在「本机有 root/root 的 MySQL」时成立。要把这条腿搬上 CI（夜间全量）或指向共享测试实例
 * （DBX「开发测试连接」分组），必须能覆盖连接串与凭据，且**本机行为逐字不变**。
 *
 * 取值优先级（逐项独立回退）：
 *   1. system property `mpvDbUrl` / `mpvDbUser` / `mpvDbPassword`（buildSrc 从 -PmpvDb* 透传）；
 *   2. 调用方传入的 XML/缺省值（即改动前的行为）。
 *
 * 为什么用 system property 而不是 env：Gradle 侧已把 -P 值写进 Test 任务的 systemProperty，
 * 测试 JVM 直接可见；env 在 fork 的测试进程里还要额外透传，且和既有的 mpDemoProjectDir 口径不一致。
 */
object DbTestCredentials {

    fun url(fromXmlOrDefault: String): String = override("mpvDbUrl") ?: fromXmlOrDefault

    fun user(fromXmlOrDefault: String): String = override("mpvDbUser") ?: fromXmlOrDefault

    fun password(fromXmlOrDefault: String): String = override("mpvDbPassword") ?: fromXmlOrDefault

    private fun override(key: String): String? =
        System.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
}
