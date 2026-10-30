package cn.song.mybatisplus.sqlvis.agent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T-CaptureCoverage 券 05：**14 方言 × 三面对账守卫**。
 *
 * <p>动因：T-CaptureCoverage 的缺口（MariaDB / SQLite 只有 connect 面、OceanBase 三面全空）
 * 是靠人工 grep 全仓 `mariadb|sqlite|oceanbase` 才发现的——用户能选这个方言、日志角标也有，
 * 但增强捕获一条 SQL 不出，是典型的「看着支持、实际不出」静默缺口。本类把它机器化。
 *
 * <p>方言→驱动族映射**硬编码在本测试里**（agent 子项目不依赖根项目的 Kotlin 方言模块，
 * 也无法在测试里反射 `DialectRegistry`）——表即事实：新增方言/换驱动包名时这张表必须跟着改，
 * 漏改的形态正是「方言可选、捕获静默」。
 *
 * <p><b>为什么按「面」判定而不是按厂商前缀判定</b>：{@code StatementWeaverVendorCoverageTest}
 * 的厂商对齐守卫只看「有没有任一 spec 以该厂商前缀开头」，同族只要有一条 prepare 面条目就整族
 * 判绿——「只补了 prepare 面、语句面漏登记」这种半覆盖它看不见（T-CaptureCoverage 实测：
 * 删掉 MariaDB 语句面三条、保留 `org.mariadb.jdbc.Connection` 后该守卫仍绿）。本类对每个方言
 * **分别**要求语句面与 prepare 面各有条目，堵住这个半覆盖缺口。
 */
public class DialectCaptureCoverageTest {

    /** 插件 14 方言（`DialectRegistry.dialects` 的顺序）→ 驱动族类前缀。 */
    private static final Map<String, String[]> DIALECT_DRIVER_FAMILIES =
            new LinkedHashMap<String, String[]>();

    static {
        put("MySQL", "com.mysql.");
        put("PostgreSQL", "org.postgresql.");
        put("Oracle", "oracle.jdbc.");
        put("SQL Server", "com.microsoft.");
        put("H2", "org.h2.");
        put("SQLite", "org.sqlite.");
        put("MariaDB", "org.mariadb.");
        put("DM（达梦）", "dm.jdbc.");
        put("KingBase（人大金仓）", "com.kingbase8.");
        put("OceanBase", "com.oceanbase.");
        put("openGauss", "org.opengauss.");
        put("GaussDB", "com.huawei.gaussdb.");
        put("HighGo", "com.highgo.");
        put("Vastbase", "cn.com.vastbase.");
    }

    /** 形态样本（负例造名单用）：只含 EXEC_PARAM = 语句面；只含 PREPARE = prepare 面。 */
    private static final StatementWeaver.Kind[] STATEMENT_FACE = {StatementWeaver.Kind.EXEC_PARAM};
    private static final StatementWeaver.Kind[] PREPARE_FACE = {StatementWeaver.Kind.PREPARE};

    private static void put(String dialect, String... prefixes) {
        DIALECT_DRIVER_FAMILIES.put(dialect, prefixes);
    }

    // ---------- 本路（MariaDB / SQLite）直证 ----------

    /**
     * 券 01/02 直证：MariaDB 5 个类、SQLite 2 个类必须各自命中正确的一面，且 SPECS 里在位。
     *
     * <p>只断言「覆盖」不够——登记到错误的一面（语句面类进了 prepare 面）会让 attach 后
     * 谓词判定反了，同样是零事件。
     */
    @Test
    public void MariaDB与SQLite两族_语句面与prepare面均已覆盖() {
        for (String dotted : new String[]{
                "org.mariadb.jdbc.Statement",
                "org.mariadb.jdbc.ClientPreparedStatement",
                "org.mariadb.jdbc.ServerPreparedStatement",
                "org.sqlite.jdbc3.JDBC3Statement",
                "org.sqlite.jdbc3.JDBC3PreparedStatement"}) {
            assertNotNull(dotted + " 未登记进 StatementWeaver.SPECS", StatementWeaver.specOf(dotted));
            assertTrue(dotted + " 应命中语句面谓词", DriverRegistry.isStatementWeavable(dotted));
            assertFalse(dotted + " 不得同时命中 prepare 面", DriverRegistry.isPrepareWeavable(dotted));
        }
        for (String dotted : new String[]{
                "org.mariadb.jdbc.Connection",
                "org.sqlite.jdbc3.JDBC3Connection"}) {
            assertNotNull(dotted + " 未登记进 StatementWeaver.SPECS", StatementWeaver.specOf(dotted));
            assertTrue(dotted + " 应命中 prepare 面谓词", DriverRegistry.isPrepareWeavable(dotted));
            assertFalse(dotted + " 不得同时命中语句面", DriverRegistry.isStatementWeavable(dotted));
        }
    }

    // ---------- 14 方言对账 ----------

    /**
     * 每个方言的**语句面**与**prepare 面**都必须有驱动族条目，或在 `VENDOR_NOT_COVERED` 显式认领。
     *
     * <p>失败文案给出方言名 + 驱动族前缀 + 缺的是哪一面，让人一眼看出是「整族漏登记」还是
     * 「只登记了一半」。
     */
    @Test
    public void 全方言三面对账_每个方言的两面都必须有驱动族条目() {
        final Map<String, StatementWeaver.Kind[]> specs = specKinds();
        final Map<String, String> claims = StatementWeaverVendorCoverageTest.VENDOR_NOT_COVERED;

        // 提取有效性自检（防空绿）：方言表必须齐 14 项、SPECS 必须达到既有量级
        assertTrue("方言表少于 14 项（疑似断言失效）：" + DIALECT_DRIVER_FAMILIES.size(),
                DIALECT_DRIVER_FAMILIES.size() >= 14);
        assertTrue("SPECS 提取到的类过少（疑似断言失效）：" + specs.size(), specs.size() >= 20);

        final List<String> failures = new ArrayList<String>();
        for (Map.Entry<String, String[]> e : DIALECT_DRIVER_FAMILIES.entrySet()) {
            final String dialect = e.getKey();
            final String[] prefixes = e.getValue();
            if (claimed(prefixes, claims)) {
                continue;
            }
            if (!covered(prefixes, specs, true)) {
                failures.add(dialect + "：驱动族 " + Arrays.toString(prefixes)
                        + " 在 SPECS **语句面**零条目");
            }
            if (!covered(prefixes, specs, false)) {
                failures.add(dialect + "：驱动族 " + Arrays.toString(prefixes)
                        + " 在 SPECS **prepare 面**零条目");
            }
        }
        assertTrue("以下方言「看着支持、实际不出」（可选中、角标可见，但增强捕获拿不到对应面的"
                + " SQL/源码位置），请补齐语句面/prepare 面或在 VENDOR_NOT_COVERED 显式认领：\n  "
                + join(failures), failures.isEmpty());
    }

    /** 对账守卫判别力自证：只覆盖一面（或整族缺失）的合成方言必须被检出。 */
    @Test
    public void 对账守卫判别力_只覆盖一面或整族缺失必须被检出() {
        // 整族缺失
        final Map<String, StatementWeaver.Kind[]> onlyMaria = new LinkedHashMap<String, StatementWeaver.Kind[]>();
        onlyMaria.put("org.mariadb.jdbc.Statement", STATEMENT_FACE);
        onlyMaria.put("org.mariadb.jdbc.Connection", PREPARE_FACE);
        final String[] fake = {"com.fakedb."};
        assertFalse("合成方言 com.fakedb.* 整族缺失，语句面必须判未覆盖",
                covered(fake, onlyMaria, true));
        assertFalse("合成方言 com.fakedb.* 整族缺失，prepare 面必须判未覆盖",
                covered(fake, onlyMaria, false));

        // 半覆盖：只有 prepare 面条目（正是「只补 prepare、语句面漏登记」的形态）
        final Map<String, StatementWeaver.Kind[]> half = new LinkedHashMap<String, StatementWeaver.Kind[]>();
        half.put("com.fakedb.jdbc.FakeConnection", PREPARE_FACE);
        assertTrue("只有 prepare 面条目时 prepare 面应判已覆盖", covered(fake, half, false));
        assertFalse("只有 prepare 面条目时语句面必须判未覆盖（半覆盖缺口）",
                covered(fake, half, true));

        // 显式认领后整族跳过
        final Map<String, String> claim = new LinkedHashMap<String, String>();
        claim.put("com.fakedb.", "测试用认领");
        assertTrue("显式认领后必须判为已认领", claimed(fake, claim));
    }

    // ---------- 纯函数 ----------

    /** 真实 SPECS 快照：类名 → 形态数组（面按形态判定，与 {@link DriverRegistry} 谓词同义）。 */
    private static Map<String, StatementWeaver.Kind[]> specKinds() {
        final Map<String, StatementWeaver.Kind[]> map = new LinkedHashMap<String, StatementWeaver.Kind[]>();
        for (String name : StatementWeaver.specClassNames()) {
            map.put(name, StatementWeaver.specOf(name));
        }
        return map;
    }

    /**
     * 驱动族在指定面上是否有条目。面按形态数组判定（含 {@code PREPARE} 即 prepare 面，否则语句面），
     * 与 {@link DriverRegistry} 的两谓词同义——不用谓词直接查，是为了让合成名单（未登记进 SPECS）
     * 也能造出「半覆盖」负样本。
     */
    private static boolean covered(String[] prefixes, Map<String, StatementWeaver.Kind[]> specs,
                                   boolean statementFace) {
        for (Map.Entry<String, StatementWeaver.Kind[]> e : specs.entrySet()) {
            for (String prefix : prefixes) {
                if (!e.getKey().startsWith(prefix)) {
                    continue;
                }
                if (hasPrepare(e.getValue()) != statementFace) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasPrepare(StatementWeaver.Kind[] kinds) {
        if (kinds == null) {
            return false;
        }
        for (StatementWeaver.Kind k : kinds) {
            if (k == StatementWeaver.Kind.PREPARE) {
                return true;
            }
        }
        return false;
    }

    /** 驱动族是否在 `VENDOR_NOT_COVERED` 显式认领（认领语义与厂商对齐守卫一致）。 */
    private static boolean claimed(String[] prefixes, Map<String, String> notCovered) {
        for (String prefix : prefixes) {
            if (notCovered.containsKey(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String join(List<String> lines) {
        final StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) {
                sb.append("\n  ");
            }
            sb.append(line);
        }
        return sb.toString();
    }
}
