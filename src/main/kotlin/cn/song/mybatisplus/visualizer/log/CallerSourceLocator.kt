package cn.song.mybatisplus.visualizer.log

/**
 * 调用点定位（F-44-6 可靠跳转）：在**应用 JVM**（SQL 执行线程）中，沿当前线程栈向下跳过
 * JDK / IntelliJ 平台 / 本插件 / MyBatis-Plus / MyBatis / Hibernate / Spring 基础设施帧，
 * 取第一个用户代码帧，还原为「相对项目根目录的源文件路径 + 行号」。
 *
 * 与 [SqlLogConsoleBridge.extractSourceLocation]（在 IDE 控制台线程取栈，永远取不到应用帧）相反，
 * 本定位器在应用线程执行，能拿到真实调用位置。供运行期 [SqlSourceInterceptor] 使用，
 * 并抽为纯函数 [locateFrom] 便于单测。
 *
 * 跳过顺序说明：栈顶是拦截器/MyBatis 框架帧，向下走第一个非框架帧即「调用 Mapper 的用户方法」
 * （通常是 Service / Controller 中触发 SQL 的那一行），正是应跳转到的代码位置。
 *
 * **与 agent 侧 `StackLocator` 的关系（事实描述，勿再假设两者必然同值）**：
 * 两侧仍是**各自独立的两份实现**（agent 禁依赖 IDE 类，本侧也绝不引 agent 代码——本侧是降级主路径，
 * 必须在 agent 缺席时独立可用），但**前缀集合自 T-AgentResiduals R1（2026-09-22）起已同集**：
 * 本侧原只收 JDK / 平台 / 本插件 / MyBatis 系 15 项，agent 侧另收驱动族 / 连接池 / 数据层插件族
 * 三十余项，于是同一次执行两侧可能给出**不同位置**（标记行指到框架帧）。现本侧按 agent 侧
 * `FRAME_SKIP_PREFIXES` 逐项补齐（含 `com.alibaba.` / `org.apache.` / `com.google.` /
 * `com.fasterxml.` / `io.netty.` 等宽前缀——宽前缀的取舍依据见下），唯一刻意差异只剩**各自的自身包**
 * （本侧 `cn.song.mybatisplus.visualizer.` vs agent 侧 `cn.song.mybatisplus.sqlvis.`，语义等价）。
 * 两侧仍**在不同时机取栈**（本侧拦截器在 SQL 生成期，agent 在 JDBC 执行期），故同一次执行的两处位置
 * **仍可能不同**（帧构成不同），但**跳过规则不再构成差异来源**。跨侧漂移由
 * `LocatorSkipPrefixSyncTest`（本侧测试）以源码扫描 + 差异登记锁定：任一侧新增/删除前缀而未同步登记
 * 即转红。
 */
object CallerSourceLocator {
    /** 在调用线程执行，返回 (相对路径, 行号) 或 null（无应用帧）。 */
    fun locate(appPackages: Set<String> = emptySet()): Pair<String, Int>? =
        locateFrom(Thread.currentThread().stackTrace, appPackages)

    /** 纯函数：从给定栈定位（单测用，避免依赖运行期线程栈）。 */
    fun locateFrom(stack: Array<StackTraceElement>, appPackages: Set<String> = emptySet()): Pair<String, Int>? {
        for (frame in stack) {
            val cn = frame.className
            if (isFrameworkFrame(cn)) continue
            if (appPackages.isNotEmpty() && appPackages.none { cn.startsWith(it) }) continue
            val fileName = frame.fileName ?: continue
            val rel = classNameToRelativePath(cn, fileName) ?: continue
            val line = frame.lineNumber
            if (line < 0) continue
            return rel to line
        }
        return null
    }

    /**
     * 框架 / 基础设施帧（一律跳过，不视为调用点）。
     *
     * **副本定性（T-PerfSweep7 4d，按 20260923 性能调研报告发现 5 登记）**：本类（含本函数）在
     * src/main 内**无生产调用点**（全仓 grep 仅 `LocatorSkipPrefixSyncTest`（log.agent 包）与本侧
     * 单测引用）——
     * 存在性是 `LocatorSkipPrefixSyncTest` 跨侧同集守卫的一部分（该守卫登记的 4 份「跳过规则」
     * 副本中的第 2 份，源码扫描锁定两侧前缀同集），**删除本副本 = 删守卫的覆盖对象之一**，
     * 去留属设计定夺事项（疑死代码/设计遗留，只登记不处理），勿顺手删除。
     *
     * 本侧独立副本，**前缀集合与 agent 侧 `StackLocator.FRAME_SKIP_PREFIXES` 同集**
     * （T-AgentResiduals R1 起；唯一差异是各自的自身包前缀，见类注释）。分组与 agent 侧逐行对齐，
     * 便于两侧对照；**改任一侧必须同步另一侧**，否则 `LocatorSkipPrefixSyncTest` 转红。
     *
     * 为什么收宽前缀（`com.alibaba.` / `org.apache.` / `com.google.` / `com.fasterxml.` / `com.baomidou.` 等）：
     * 与 agent 侧同一条依据——数据层插件 / 基础设施帧一旦漏收就被误选为「调用点」，跳转直接跳到框架里
     * （PageHelper 实证）。代价是用户业务代码若恰好落在这些宽包下会被跳过，此时靠 `appPackages`
     * 限定（调用方显式传业务包）兜住；本侧与 agent 侧同取舍，避免两侧判定分叉。
     *
     * `com.github.yulichang.`（MPJ，mybatis-plus-join 全族）= T-AgentSourceLocateStale T-02 补入：
     * MPJ 的 `MPJInterceptor` 是 MyBatis 插件链一环、对全应用所有查询是同一帧，未收即被误选为调用点。
     */
    private fun isFrameworkFrame(cn: String): Boolean =
        cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("jdk.") ||
            cn.startsWith("sun.") || cn.startsWith("com.sun.") || cn.startsWith("jdk.internal.") ||
            cn.startsWith("com.intellij.") || cn.startsWith("org.jetbrains.") ||
            // 自身模块（agent 侧对应项是 cn.song.mybatisplus.sqlvis.）
            cn.startsWith("cn.song.mybatisplus.visualizer.") || cn.startsWith("cn.song.mybatisplus.sqlvis.") ||
            // 驱动家族
            cn.startsWith("com.mysql.") || cn.startsWith("org.mariadb.") || cn.startsWith("org.postgresql.") ||
            cn.startsWith("org.h2.") || cn.startsWith("org.hsqldb.") || cn.startsWith("org.sqlite.") ||
            cn.startsWith("com.microsoft.sqlserver.") || cn.startsWith("oracle.jdbc.") ||
            cn.startsWith("dm.jdbc.") || cn.startsWith("com.gbasedbt.") || cn.startsWith("com.kingbase8.") ||
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
            // T-AgentSourceLocateStale T-02：MPJ（mybatis-plus-join）全族
            cn.startsWith("com.github.yulichang.") ||
            cn.startsWith("\$Proxy")

    /** 类名 + 源文件名 → 相对项目根路径（如 com.example.User + User.java → com/example/User.java）。 */
    fun classNameToRelativePath(className: String, fileName: String): String? {
        val packagePath = className.substringBeforeLast('.').replace('.', '/')
        return if (packagePath.isNotEmpty()) "$packagePath/$fileName" else fileName
    }
}
