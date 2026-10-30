package cn.song.mybatisplus.sqlvis.agent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 已知 JDBC 驱动类登记表（§4.2 #1 插桩主路径）：只对表内类的
 * {@code connect(String, Properties)} 做方法出口织入——字节码面最小（6~10 方法缩到 1 方法/类），
 * 对驱动版本漂移最不敏感。连接池（Hikari/Druid）底层走 driver.connect，复用路径自动覆盖。
 *
 * 国产库（达梦/GBASE/Kingbase）形态登记为策略条目：类名命中即织入，签名不匹配安全跳过。
 *
 * T-StatementWeave：新增**语句面**与 **prepare 面**——attach（agentmain）路径对已加载的
 * 语句实现类 retransform，钩子挂在 execute 族方法体上，随每次执行触发，与连接/语句对象的
 * 创建时间无关（DebugTools 类工具「attach 即生效」的织入点模型）。类名为 javap+zip 实测
 * （mysql-connector-j 8.0.33 / h2 2.3.232，见开发计划 20261005-1148 §二）；织不上的类
 * 安全降级为无钩子（与驱动面同一契约），版本漂移不炸宿主。
 */
public final class DriverRegistry {

    private static final Set<String> DRIVER_CLASSES;

    static {
        Set<String> s = new HashSet<String>();
        // MySQL / MariaDB
        s.add("com.mysql.jdbc.Driver");
        s.add("com.mysql.cj.jdbc.Driver");
        s.add("com.mysql.cj.jdbc.NonRegisteringDriver");
        s.add("org.mariadb.jdbc.Driver");
        // PostgreSQL / openGauss 兼容。社区 openGauss 驱动的包组织按版本分界（T-GaussCapture
        // 实测）：2.x/3.x 整体重定位到 org.opengauss.*（3.0.0 内 org/postgresql 条目数为 0），
        // 5.x/7.x 才回到 org.postgresql.*（7.0.0 的 META-INF/services 声明 org.postgresql.Driver）。
        // 两边的语句面/prepare 面各自登记在 WeaveSpecs，勿只登记一半。
        s.add("org.postgresql.Driver");
        s.add("org.opengauss.Driver");
        // H2 / HSQL / SQLite（嵌入式，测试矩阵主力）
        s.add("org.h2.Driver");
        s.add("org.hsqldb.jdbcDriver");
        s.add("org.hsqldb.jdbc.JDBCDriver");
        s.add("org.sqlite.JDBC");
        // SQL Server / Oracle
        s.add("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        s.add("oracle.jdbc.OracleDriver");
        s.add("oracle.jdbc.driver.OracleDriver");
        // 国产库（策略表登记：不可用时降级非阻断）
        // 达梦（dm.jdbc.driver.DmDriver）**刻意不登记 connect 面**（T-ConnCastSafety 2026-10-28）：
        // ① 它已在语句面（DmdbStatement/DmdbPreparedStatement）+ prepare 面（DmdbConnection）全覆盖，
        //    采集不依赖 connect 织入；
        // ② 该类的 connect 出口织入 T-DmWeave 实测 `failed=1 [DmDriver=VerifyError]`（达梦 javac 在出口
        //    对已死局部发 chop_frame）。跑在 attach 路径只是记一次失败（原字节保留，安全），但**premain
        //    路径同样的坏字节会让驱动类定义直接失败**→宿主 `NoClassDefFoundError`、应用起不来。
        //    红线是「不得让宿主起不来」，故宁可少一个粗粒度 URL 兜底（达梦 prepare 面已带 URL）也不留这颗雷。
        s.add("com.gbasedbt.jdbc.Driver");
        s.add("com.kingbase8.Driver");
        // 信创 PG 系两族（T-XcDialects3 券 07）：海量 Vastbase / 瀚高 HighGo 的驱动类均
        // implements java.sql.Driver 且非 PG 子类——jar 为完全重定位的独立实现（`org/postgresql`
        // 条目数为 0、与 pgjdbc 无继承关系），connect 面单列登记，签名不匹配安全跳过。
        // 金仓 com.kingbase8.Driver 已在上方；三族语句面/prepare 面在 WeaveSpecs，勿只登记一半。
        s.add("cn.com.vastbase.Driver");
        s.add("com.highgo.jdbc.Driver");
        // OceanBase Connector/J（oceanbase-client 2.4.19）：独立实现非 MySQL 分叉——与 MySQL 协议
        // 兼容但类体系自成一套（com.oceanbase.jdbc.*，不复用 com.mysql.cj.*），URL 前缀
        // `jdbc:oceanbase:` 唯一（com.oceanbase.jdbc.UrlParser 常量池实证）。语句面/prepare 面在
        // WeaveSpecs，勿只登记一半。
        s.add("com.oceanbase.jdbc.Driver");
        DRIVER_CLASSES = Collections.unmodifiableSet(s);
        // T-StatementWeave 语句面/prepare 面名单在 StatementWeaver.SPECS（单一真相，形态标注
        // 跟类走）；本表只保留驱动 connect 面。两新面谓词委托 specOf 判定。
    }

    public static boolean isWeavable(String className) {
        return DRIVER_CLASSES.contains(className);
    }

    /**
     * 语句面：execute 族织入对象（T-StatementWeave）。
     * 名单单一真相在 [WeaveSpecs]（织入形态标注跟着类走，防两处漂移）——本谓词委托判定。
     *
     * <p><b>T-JRebelPremainCrash B 案</b>：此前委托 {@code StatementWeaver.specOf}，而该类
     * {@code implements Opcodes} 且方法体引用 {@code Opcodes.LONG/DOUBLE/TOP}（{@code Integer}
     * 装箱字段，不可内联）——「查名字」会触发其类初始化，从而要求重定位 ASM 全程可解析。
     * 第三方 agent 接管 premain 类加载时该路径会抛 NCDFE / ClassCircularityError，且异常
     * 从 premain 逃逸会让目标 JVM 启动失败。改委托零 ASM 依赖的 [WeaveSpecs] 后，名单查询
     * 不再需要 ASM；真正的 ASM 需求只落在 {@code weave()}（已在 transformer 的 try 内）。
     */
    public static boolean isStatementWeavable(String className) {
        final StatementWeaver.Kind[] spec = WeaveSpecs.specOf(className);
        return spec != null && !hasPrepare(spec);
    }

    /** prepare 面：prepareStatement/prepareCall 出口登记对象（T-StatementWeave S3）。 */
    public static boolean isPrepareWeavable(String className) {
        final StatementWeaver.Kind[] spec = WeaveSpecs.specOf(className);
        return spec != null && hasPrepare(spec);
    }

    private static boolean hasPrepare(StatementWeaver.Kind[] spec) {
        for (StatementWeaver.Kind k : spec) {
            if (k == StatementWeaver.Kind.PREPARE) {
                return true;
            }
        }
        return false;
    }

    /**
     * 已登记驱动类名快照（仅供测试守卫遍历「连接面已登记但语句面/prepare 面漏登记」的厂商缺口；
     * 生产路径不使用）。返回自身为不可变集合，无泄漏面。
     */
    static java.util.Set<String> driverClassNames() {
        return DRIVER_CLASSES;
    }

    private DriverRegistry() {
    }
}
