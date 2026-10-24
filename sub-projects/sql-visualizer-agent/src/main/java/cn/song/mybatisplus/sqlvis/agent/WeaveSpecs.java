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
                StatementWeaver.Kind.CLEAR_BATCH};
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
                // PostgreSQL / openGauss 兼容（openGauss 2.x 走 PG 驱动）——PgPreparedStatement
                // 继承 PgStatement、PgCallableStatement 继承 PgPreparedStatement（javap 实测）
                "org.postgresql.jdbc.PgStatement",
                "org.postgresql.jdbc.PgPreparedStatement",
                "org.postgresql.jdbc.PgCallableStatement",
                // Oracle thin（OracleStatement 带参执行族 + 批处理；PS/Callable 各自覆盖无参 execute 族）
                "oracle.jdbc.driver.OracleStatement",
                "oracle.jdbc.driver.OraclePreparedStatement",
                "oracle.jdbc.driver.OracleCallableStatement",
                // SQL Server（SQLServerPreparedStatement 覆盖无参族并桥接带参族；
                // SQLServerCallableStatement 无自有 execute 族——继承 SQLServerPreparedStatement 即覆盖，不单列）
                "com.microsoft.sqlserver.jdbc.SQLServerStatement",
                "com.microsoft.sqlserver.jdbc.SQLServerPreparedStatement");
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
                StatementWeaver.Kind.BATCH};
        putSpec(dmTimedFace,
                "dm.jdbc.driver.DmdbStatement",
                "dm.jdbc.driver.DmdbPreparedStatement");
        final StatementWeaver.Kind[] prepareFace = {StatementWeaver.Kind.PREPARE};
        putSpec(prepareFace,
                "com.mysql.cj.jdbc.ConnectionImpl",
                "com.mysql.jdbc.ConnectionImpl",
                "org.h2.jdbc.JdbcConnection",
                // PG 连接（prepareStatement/prepareCall 出口登记 PS 实例 → SQL）
                "org.postgresql.jdbc.PgConnection",
                // Oracle 连接基类（T4CConnection 继承其 prepareStatement/prepareCall，包私有亦可 retransform）
                "oracle.jdbc.driver.PhysicalConnection",
                "com.microsoft.sqlserver.jdbc.SQLServerConnection",
                // 达梦连接（prepareStatement/prepareCall 出口登记 PS→SQL；该类字节探针通过校验）
                "dm.jdbc.driver.DmdbConnection");
    }

    /** 类名（点分）→ 织入形态 spec；未登记返回 null。 */
    static StatementWeaver.Kind[] specOf(String dottedClassName) {
        return SPECS.get(dottedClassName);
    }

    /** 已登记类名快照（仅供厂商对齐守卫遍历；生产路径不使用）。 */
    static Set<String> specClassNames() {
        return Collections.unmodifiableSet(SPECS.keySet());
    }

    private WeaveSpecs() {
    }
}
