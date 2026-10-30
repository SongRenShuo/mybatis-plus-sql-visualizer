package cn.song.mybatisplus.sqlvis.agent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 语句面/prepare 面登记名单（T-JRebelPremainCrash B 案：从 {@link StatementWeaver} 上提）。
 *
 * <p><b>为什么必须独立成类。</b>{@link DriverRegistry} 的两个谓词
 * （{@code isStatementWeavable} / {@code isPrepareWeavable}）只需要知道「类名在不在表里」，
 * 此前它们经 {@code StatementWeaver.specOf} 触发了 {@code StatementWeaver} 的类初始化——
 * 而该类 {@code implements Opcodes}（重定位后的 ASM），方法体里还有 6 处
 * {@code getstatic Opcodes.LONG|DOUBLE|TOP:Ljava/lang/Integer;}（装箱字段，不可内联），
 * 于是「查个名字」也要 ASM 全程可解析。第三方 agent（JRebel 等）接管 premain 类加载时，
 * 这条路径一旦解析不到 ASM 就抛 {@code NoClassDefFoundError}，且异常从 premain 逃逸会让
 * 目标 JVM 直接启动失败（见计划 20261022-0905 §2.9/§2.11）。
 *
 * <p>本类**零 ASM 依赖**：只持有点分类名与 {@link StatementWeaver.Kind} 形态数组。
 * 注意 {@code Kind} 虽写在 {@code StatementWeaver} 内，但它是**嵌套枚举**——编译产物为独立的
 * {@code StatementWeaver$Kind.class}，引用它只初始化该枚举自身，**不会**触发外层
 * {@code StatementWeaver} 的 {@code <clinit>}，因此 ASM 不可见时本类的查询路径依然可用。
 * 这条前提由 demo 仓 {@code reproC} 与 agent 侧
 * {@code WeaveSpecsIsolationTest} 双向钉住。
 *
 * <p>名单的实证口径（javap 实测方法名+描述符 + 真实驱动字节织入 + JVM 校验，
 * 见 {@code StatementWeaverVendorCoverageTest}）与未登记理由仍写在
 * {@link StatementWeaver} 的类注释里，避免两处各说一套。
 */
final class WeaveSpecs {

    /** 类名（点分）→ 允许形态；驱动语句面（计划 §三 S1）与 prepare 面（S3）。 */
    private static final Map<String, StatementWeaver.Kind[]> SPECS =
            new HashMap<String, StatementWeaver.Kind[]>();

    private static void putSpec(StatementWeaver.Kind[] kinds, String... classes) {
        for (String c : classes) {
            SPECS.put(c, kinds);
        }
    }

    static {
        final StatementWeaver.Kind[] statementFace = {
                StatementWeaver.Kind.EXEC_PARAM,
                StatementWeaver.Kind.EXEC_CTX,
                StatementWeaver.Kind.BATCH,
                StatementWeaver.Kind.ADD_BATCH,
                StatementWeaver.Kind.CLEAR_BATCH,
                // T-ConnCastSafety：参数捕获必须自持（连接代理出厂关闭后，setXxx 只能靠织入拦）。
                // 只在方法头插一行回调，不新增局部/出口，达梦出口 chop_frame 那类限制与它无关。
                StatementWeaver.Kind.PARAM};
        putSpec(statementFace,
                "com.mysql.cj.jdbc.StatementImpl",
                "com.mysql.cj.jdbc.ClientPreparedStatement",
                "com.mysql.cj.jdbc.ServerPreparedStatement",
                "com.mysql.jdbc.StatementImpl",
                "com.mysql.jdbc.PreparedStatement",
                "com.mysql.jdbc.ServerPreparedStatement",
                "org.h2.jdbc.JdbcStatement",
                "org.h2.jdbc.JdbcPreparedStatement",
                "org.h2.jdbc.JdbcCallableStatement",
                // PostgreSQL / openGauss 5.x·7.x（这两个版本整体回到 org.postgresql.* 包；2.x/3.x
                // 是 org.opengauss.*，见下）——PgPreparedStatement 继承 PgStatement、
                // PgCallableStatement 继承 PgPreparedStatement（javap 实测）
                "org.postgresql.jdbc.PgStatement",
                "org.postgresql.jdbc.PgPreparedStatement",
                "org.postgresql.jdbc.PgCallableStatement",
                // 华为 GaussDB 商用驱动（gaussdbjdbc 506.0.0.b058）：完全重定位，jar 内
                // `org/postgresql` 条目数为 0、与 pgjdbc 无继承关系，故必须单列。方法形态与
                // pgjdbc 一致（T-GaussCapture 用真实 jar 字节织入 + JVM 链接期校验实测；
                // PgCallableStatement 继承 PgPreparedStatement，不单列）
                "com.huawei.gaussdb.jdbc.jdbc.PgStatement",
                "com.huawei.gaussdb.jdbc.jdbc.PgPreparedStatement",
                // 华为 openGauss 品牌驱动（opengaussjdbc 506.0.T35）：与商用版同形同构，独立包名
                "com.huawei.opengauss.jdbc.jdbc.PgStatement",
                "com.huawei.opengauss.jdbc.jdbc.PgPreparedStatement",
                // 社区 openGauss 2.x/3.x（org.opengauss:opengauss-jdbc）：这代整体重定位到
                // org.opengauss.*，Class.forName("org.postgresql.Driver") 解析不到——此前连接面
                // 早已登记 org.opengauss.Driver，语句面却是空的，用户侧表现为「看着支持、一条不出」。
                // 3.0.0 真实字节织入 + JVM 校验通过；5.x/7.x 已整体回到 org.postgresql.*，不在此列
                "org.opengauss.jdbc.PgStatement",
                "org.opengauss.jdbc.PgPreparedStatement",
                // Oracle thin（OracleStatement 带参执行族 + 批处理；PS/Callable 各自覆盖无参 execute 族）
                "oracle.jdbc.driver.OracleStatement",
                "oracle.jdbc.driver.OraclePreparedStatement",
                "oracle.jdbc.driver.OracleCallableStatement",
                // SQL Server（SQLServerPreparedStatement 覆盖无参族并桥接带参族；
                // SQLServerCallableStatement 无自有 execute 族——继承 SQLServerPreparedStatement 即覆盖，不单列）
                "com.microsoft.sqlserver.jdbc.SQLServerStatement",
                "com.microsoft.sqlserver.jdbc.SQLServerPreparedStatement",
                // 人大金仓 KingbaseES（kingbase8 9.0.1）：完全重定位的独立实现，jar 内
                // `org/postgresql` 条目数为 0、与 pgjdbc 无继承关系，故必须单列。方法形态与
                // pgjdbc 一致（真实 jar 字节织入 + JVM 链接期校验，见
                // StatementWeaverVendorCoverageTest；KbCallableStatement 继承 KbPreparedStatement，
                // 不单列）
                "com.kingbase8.jdbc.KbStatement",
                "com.kingbase8.jdbc.KbPreparedStatement",
                // 海量 Vastbase（vastbase-jdbc 2.16.1v）：同为完全重定位（`org/postgresql`
                // 条目数为 0、与 pgjdbc 无继承关系），语句类借了 Pg* 旧名但包在 cn.com.vastbase 下
                "cn.com.vastbase.jdbc.PgStatement",
                "cn.com.vastbase.jdbc.PgPreparedStatement",
                // 瀚高 HighGo（HgdbJdbc 6.2.5）：完全重定位（`org/postgresql` 条目数为 0、
                // 与 pgjdbc 无继承关系），包形 com.highgo.jdbc.jdbc.Pg* 与华为 GaussDB 商用驱动同构
                "com.highgo.jdbc.jdbc.PgStatement",
                "com.highgo.jdbc.jdbc.PgPreparedStatement");
        // MariaDB（mariadb-java-client 3.3.3，T-CaptureCoverage 券 01）：3.x 全部重写为自有类
        // （`org.mariadb.jdbc.Statement` / `ClientPreparedStatement` / `ServerPreparedStatement`
        // 均 implements java.sql.* 而非继承 MySQL `StatementImpl`，包名与 com.mysql 无交集），
        // 故必须单列——只登记 connect 面时 attach 后一条 SQL 不出。三个类各自覆盖
        // execute/executeQuery/executeUpdate/addBatch/clearBatch/executeBatch 全套（javap 实测），
        // 与 statementFace 全形态一致。
        putSpec(statementFace,
                "org.mariadb.jdbc.Statement",
                "org.mariadb.jdbc.ClientPreparedStatement",
                "org.mariadb.jdbc.ServerPreparedStatement");
        // SQLite（sqlite-jdbc 3.50.3.0，T-CaptureCoverage 券 02）：`JDBC3Statement` 自有
        // execute/executeQuery/executeUpdate/executeLargeUpdate 全族，`JDBC3PreparedStatement`
        // 覆盖 PS 无参族。**无 Callable 自有 execute 族**（`JDBC3Connection.prepareCall` 返回
        // 的 `JDBC3PreparedStatement` 子类不新增 execute 形态），故不单列。
        putSpec(statementFace,
                "org.sqlite.jdbc3.JDBC3Statement",
                "org.sqlite.jdbc3.JDBC3PreparedStatement");
        // OceanBase Connector/J（oceanbase-client 2.4.19）：与 MySQL 协议兼容但类体系自成一套
        // （com.oceanbase.jdbc.*，不复用 com.mysql.cj.*），URL 前缀 `jdbc:oceanbase:` 唯一，故必须
        // 单列。OceanBaseStatement（纯语句，`implements java.sql.Statement, Cloneable`）自带
        // execute/executeQuery/executeUpdate/executeLargeUpdate/addBatch/clearBatch/executeBatch 全族；
        // JDBC4PreparedStatement / JDBC4ServerPreparedStatement 两者 `extends BasePrepareStatement
        // extends OceanBaseStatement`，虽继承大父类但各有**自有 execute 族**（javap 实测：前者
        // execute/executeQuery/executeUpdate/addBatch/clearBatch/executeBatch/executeLargeBatch，
        // 后者 execute/executeQuery/executeUpdate/executeLargeUpdate/addBatch/executeBatch），
        // 父类织入覆盖不到它们的覆盖点，故两个 PS 类必须单列。
        // CallableStatement 不单列（D3 定案）：JDBC4ServerPreparedStatement 继承链已覆盖 execute 族。
        putSpec(statementFace,
                "com.oceanbase.jdbc.OceanBaseStatement",
                "com.oceanbase.jdbc.JDBC4PreparedStatement",
                "com.oceanbase.jdbc.JDBC4ServerPreparedStatement");
        // 达梦 DM8（DmJdbcDriver18 8.1.3.140）：出口处 javac 对已死局部发 chop_frame，旧织入形态
        // （出口读原局部）字节级 VerifyError；T-DmWeave 改为「入口捕获 this/首参 → 出口读自有槽」后，
        // **timed 面**（EXEC_PARAM/EXEC_CTX/BATCH）可通过 JVM 校验并落钩。
        // ADD_BATCH/CLEAR_BATCH 出口非 timed（仍读原局部）在达梦上仍是 VerifyError
        // （DmdbStatement.addBatch(String) chop_frame(2)、clearBatch() chop_frame(1) 字节探针实证），
        // 故**不登记**——达梦批处理事件一期不采（MyBatis 主路径走 PS，无影响）。
        // DmdbStatement（纯语句）/ DmdbPreparedStatement（PS 自有 execute 族）用同一 timed 面；
        // DmdbCallableStatement 无自有 execute 族（继承 DmdbPreparedStatement），不单列。
        final StatementWeaver.Kind[] dmTimedFace = {
                StatementWeaver.Kind.EXEC_PARAM,
                StatementWeaver.Kind.EXEC_CTX,
                StatementWeaver.Kind.BATCH,
                // 参数面是入口插桩（无出口、无局部、无帧补丁），不受 chop_frame 限制
                StatementWeaver.Kind.PARAM};
        putSpec(dmTimedFace,
                "dm.jdbc.driver.DmdbStatement",
                "dm.jdbc.driver.DmdbPreparedStatement");
        final StatementWeaver.Kind[] prepareFace = {StatementWeaver.Kind.PREPARE};
        putSpec(prepareFace,
                "com.mysql.cj.jdbc.ConnectionImpl",
                "com.mysql.jdbc.ConnectionImpl",
                "org.h2.jdbc.JdbcConnection",
                // PG 连接（prepareStatement/prepareCall 出口登记 PS 实例 → SQL）
                // 同为 openGauss 5.x/7.x 的连接面
                "org.postgresql.jdbc.PgConnection",
                // 华为 GaussDB 商用 / openGauss 品牌连接（两族均为完全重定位的独立实现；
                // getURL() 实测 public，UrlResolver 的反射兜底可用）
                "com.huawei.gaussdb.jdbc.jdbc.PgConnection",
                "com.huawei.opengauss.jdbc.jdbc.PgConnection",
                // 社区 openGauss 2.x/3.x 连接（5.x/7.x 走上面的 org.postgresql.PgConnection）
                "org.opengauss.jdbc.PgConnection",
                // Oracle 连接基类（T4CConnection 继承其 prepareStatement/prepareCall，包私有亦可 retransform）
                "oracle.jdbc.driver.PhysicalConnection",
                "com.microsoft.sqlserver.jdbc.SQLServerConnection",
                // 达梦连接（prepareStatement/prepareCall 出口登记 PS→SQL；该类字节探针通过校验）
                "dm.jdbc.driver.DmdbConnection",
                // 信创 PG 系三族连接（T-XcDialects3 券 07）：金仓 / 海量 / 瀚高的连接类均为完全
                // 重定位的独立实现（`org/postgresql` 条目数为 0、与 pgjdbc 无继承关系），prepare
                // 面单列；getURL() 形态与 pgjdbc 一致，UrlResolver 反射兜底可用
                "com.kingbase8.jdbc.KbConnection",
                "cn.com.vastbase.jdbc.PgConnection",
                "com.highgo.jdbc.jdbc.PgConnection");
        // MariaDB 连接（T-CaptureCoverage 券 01）：`org.mariadb.jdbc.Connection` 是 3.x 自有连接类
        // （非 MySQL ConnectionImpl 子类），prepareStatement/prepareCall 出口在此登记 PS→SQL。
        putSpec(prepareFace, "org.mariadb.jdbc.Connection");
        // SQLite 连接（T-CaptureCoverage 券 02）：`JDBC3Connection` 自有 prepareStatement/prepareCall。
        putSpec(prepareFace, "org.sqlite.jdbc3.JDBC3Connection");
        // OceanBase 连接（T-CaptureCoverage 券 03）：`OceanBaseConnection implements ConnectionImpl`
        // 且自有 6 个 prepareStatement 出口 + 3 个 prepareCall 出口（javap 实测），非 MySQL
        // ConnectionImpl 子类，故 prepare 面单列。
        putSpec(prepareFace, "com.oceanbase.jdbc.OceanBaseConnection");
    }

    /**
     * 兜底面形态（T-CaptureCatchAll 券 01/06）：接口匹配命中、但**不在白名单**的类走这一档。
     *
     * <p><b>为什么比白名单的 {@code statementFace} 少两个形态。</b>白名单类都有真实驱动字节的
     * 字节层实证（织入 + 隔离 loader 定义 + 初始化过 JVM 校验），故可以放开 {@code ADD_BATCH}/
     * {@code CLEAR_BATCH}——这两个形态在出口处读**原方法局部**（{@code ALOAD 0/1}）。而未知驱动
     * 的编译器可能对已死局部发 {@code chop_frame}（达梦 {@code DmdbStatement.addBatch(String)}
     * 即此形态，见 {@link StatementWeaver} 类注释），此时出口读原局部会 {@code VerifyError}，
     * 结果是**整类零钩子**——连本可织上的 execute 面也一起丢。
     *
     * <p>故兜底面只取「入口捕获自有槽」的 timed 面（EXEC_PARAM/EXEC_CTX/BATCH）与入口插桩的
     * 参数面（PARAM），它们不读原方法局部，对未知字节形态最稳。代价是**兜底驱动的批处理 SQL
     * 文本不累积**（addBatch(String) 未织）——如实登记为已知缺口，不静默假装覆盖。
     *
     * <p>premain 阶段绝不内联这一档（见 {@link AgentMain} 的券 08 策略）：兜底面只在 attach /
     * 启动后补挂路径上使用，单类失败由 JVM 拒绝 retransform 后原字节保留。
     */
    private static final StatementWeaver.Kind[] CATCH_ALL_STATEMENT_FACE = {
            StatementWeaver.Kind.EXEC_PARAM,
            StatementWeaver.Kind.EXEC_CTX,
            StatementWeaver.Kind.BATCH,
            StatementWeaver.Kind.PARAM};

    /** 兜底面 prepare 形态（接口匹配命中的 {@code java.sql.Connection} 实现）。 */
    private static final StatementWeaver.Kind[] CATCH_ALL_PREPARE_FACE = {
            StatementWeaver.Kind.PREPARE};

    /** 类名（点分）→ 织入形态 spec；未登记返回 null。 */
    static StatementWeaver.Kind[] specOf(String dottedClassName) {
        return SPECS.get(dottedClassName);
    }

    /** 兜底语句面形态（只读；调用方不得修改）。 */
    static StatementWeaver.Kind[] catchAllStatementFace() {
        return CATCH_ALL_STATEMENT_FACE;
    }

    /** 兜底 prepare 面形态（只读；调用方不得修改）。 */
    static StatementWeaver.Kind[] catchAllPrepareFace() {
        return CATCH_ALL_PREPARE_FACE;
    }

    /** 已登记类名快照（仅供厂商对齐守卫遍历；生产路径不使用）。 */
    static Set<String> specClassNames() {
        return Collections.unmodifiableSet(SPECS.keySet());
    }

    private WeaveSpecs() {
    }
}
