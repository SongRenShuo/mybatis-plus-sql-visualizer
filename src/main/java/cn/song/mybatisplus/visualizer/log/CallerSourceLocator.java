package cn.song.mybatisplus.visualizer.log;

import kotlin.Pair;
import java.util.Set;

/**
 * 调用点定位（F-44-6 可靠跳转）：在应用 JVM（SQL 执行线程）中，沿当前线程栈向下跳过
 * JDK / IntelliJ 平台 / 本插件 / MyBatis-Plus / MyBatis / Hibernate / Spring 基础设施帧，
 * 取第一个用户代码帧，还原为「相对项目根目录的源文件路径 + 行号」。
 *
 * <p>跳过顺序说明：栈顶是拦截器/MyBatis 框架帧，向下走第一个非框架帧即「调用 Mapper 的用户方法」
 * （通常是 Service / Controller 中触发 SQL 的那一行），正是应跳转到的代码位置。
 *
 * <p><b>与 agent 侧 {@code StackLocator} 的关系（事实描述，勿再假设两者必然同值）</b>：
 * 两侧仍是<b>各自独立的两份实现</b>（agent 禁依赖 IDE 类，本侧也绝不引 agent 代码——本侧是降级主路径，
 * 必须在 agent 缺席时独立可用），但<b>前缀集合自 T-AgentResiduals R1（2026-09-22）起已同集</b>：
 * 本侧原只收 JDK / 平台 / 本插件 / MyBatis 系 15 项，agent 侧另收驱动族 / 连接池 / 数据层插件族
 * 三十余项，于是同一次执行两侧可能给出<b>不同位置</b>（标记行指到框架帧）。现本侧按 agent 侧
 * {@code FRAME_SKIP_PREFIXES} 逐项补齐（含 {@code com.alibaba.} / {@code org.apache.} / {@code com.google.} /
 * {@code com.fasterxml.} / {@code io.netty.} 等宽前缀——宽前缀的取舍依据见下），唯一刻意差异只剩<b>各自的自身包</b>
 * （本侧 {@code cn.song.mybatisplus.visualizer.} vs agent 侧 {@code cn.song.mybatisplus.sqlvis.}，语义等价）。
 * 两侧仍<b>在不同时机取栈</b>（本侧拦截器在 SQL 生成期，agent 在 JDBC 执行期），故同一次执行的两处位置
 * <b>仍可能不同</b>（帧构成不同），但<b>跳过规则不再构成差异来源</b>。跨侧漂移由
 * {@code LocatorSkipPrefixSyncTest}（本侧测试）以源码扫描 + 差异登记锁定：任一侧新增/删除前缀而未同步登记
 * 即转红。
 */
public final class CallerSourceLocator {

    public static final CallerSourceLocator INSTANCE = new CallerSourceLocator();

    private CallerSourceLocator() {
    }

    public static Pair<String, Integer> locate() {
        return locateFrom(Thread.currentThread().getStackTrace(), Set.of());
    }

    public static Pair<String, Integer> locate(Set<String> appPackages) {
        return locateFrom(Thread.currentThread().getStackTrace(), appPackages);
    }

    public static Pair<String, Integer> locateFrom(StackTraceElement[] stack, Set<String> appPackages) {
        for (StackTraceElement frame : stack) {
            String cn = frame.getClassName();
            if (isFrameworkFrame(cn)) continue;
            if (!appPackages.isEmpty() && appPackages.stream().noneMatch(it -> cn.startsWith(it))) continue;
            String fileName = frame.getFileName();
            if (fileName == null) continue;
            String rel = classNameToRelativePath(cn, fileName);
            if (rel == null) continue;
            int line = frame.getLineNumber();
            if (line < 0) continue;
            return new Pair<>(rel, line);
        }
        return null;
    }

    /**
     * 框架 / 基础设施帧（一律跳过，不视为调用点）。
     *
     * <p><b>副本定性（T-PerfSweep7 4d，按 20260923 性能调研报告发现 5 登记）</b>：本类（含本函数）在
     * src/main 内<b>无生产调用点</b>（全仓 grep 仅 {@code LocatorSkipPrefixSyncTest}（log.agent 包）与本侧
     * 单测引用）——存在性是 {@code LocatorSkipPrefixSyncTest} 跨侧同集守卫的一部分（该守卫登记的
     * 4 份「跳过规则」副本中的第 2 份，源码扫描锁定两侧前缀同集），<b>删除本副本 = 删守卫的覆盖对象之一</b>，
     * 去留属设计定夺事项（疑死代码/设计遗留，只登记不处理），勿顺手删除。
     *
     * <p>本侧独立副本，<b>前缀集合与 agent 侧 {@code StackLocator.FRAME_SKIP_PREFIXES} 同集</b>
     * （T-AgentResiduals R1 起；唯一差异是各自的自身包前缀，见类注释）。分组与 agent 侧逐行对齐，
     * 便于两侧对照；<b>改任一侧必须同步另一侧</b>，否则 {@code LocatorSkipPrefixSyncTest} 转红。
     *
     * <p>为什么收宽前缀（{@code com.alibaba.} / {@code org.apache.} / {@code com.google.} / {@code com.fasterxml.} / {@code com.baomidou.} 等）：
     * 与 agent 侧同一条依据——数据层插件 / 基础设施帧一旦漏收就被误选为「调用点」，跳转直接跳到框架里
     * （PageHelper 实证）。代价是用户业务代码若恰好落在这些宽包下会被跳过，此时靠 {@code appPackages}
     * 限定（调用方显式传业务包）兜住；本侧与 agent 侧同取舍，避免两侧判定分叉。
     *
     * <p>{@code com.github.yulichang.}（MPJ，mybatis-plus-join 全族）= T-AgentSourceLocateStale T-02 补入：
     * MPJ 的 {@code MPJInterceptor} 是 MyBatis 插件链一环、对全应用所有查询是同一帧，未收即被误选为调用点。
     */
    private static boolean isFrameworkFrame(String cn) {
        return cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("jdk.") ||
            cn.startsWith("sun.") || cn.startsWith("com.sun.") || cn.startsWith("jdk.internal.") ||
            cn.startsWith("com.intellij.") || cn.startsWith("org.jetbrains.") ||
            // 自身模块（agent 侧对应项是 cn.song.mybatisplus.sqlvis.）
            cn.startsWith("cn.song.mybatisplus.visualizer.") || cn.startsWith("cn.song.mybatisplus.sqlvis.") ||
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
            // T-AgentSourceLocateStale T-02：MPJ（mybatis-plus-join）全族
            cn.startsWith("com.github.yulichang.") ||
            cn.startsWith("$Proxy");
    }

    public static String classNameToRelativePath(String className, String fileName) {
        int lastDot = className.lastIndexOf('.');
        String packagePath = lastDot >= 0 ? className.substring(0, lastDot).replace('.', '/') : "";
        return packagePath.isEmpty() ? fileName : packagePath + "/" + fileName;
    }
}
