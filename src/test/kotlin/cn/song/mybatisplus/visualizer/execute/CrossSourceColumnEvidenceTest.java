package cn.song.mybatisplus.visualizer.execute;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * T-ExplainConsoleFirst W7 护栏：{@code Unknown column} 跨源升格判据（{@link CrossSourceColumnEvidence}）。
 *
 * <p>事故背景：用户实测 {@code [42S22][1054] Unknown column 'first_name' in 'field list'}，上下文行三处
 * 读数全是 {@code mp_demo}——<b>源没选错</b>，错的是「SQL 与库」这一对（{@code first_name} 属 jpa_demo 的同名
 * user 表）。既有分类把 {@code Unknown column} 一律排除在 WRONG_TARGET 外，这里按证据补上。</p>
 *
 * <p>护栏重心在<b>不误判</b>：拼错列名（任何源都没有）必须维持不升格，否则会把用户引导去换源。</p>
 *
 * <p>K2J 承载注（收口勘误）：master 为 .kt 直取，但 K2 下 Kotlin 侧显式构造
 * {@code java.util.function.Function<String, Set<?>>} 与生产灵活平台类型
 * {@code (MutableSet<String!>..Set<String>?)>} 在泛型不变位恒不等（SAM 构造器泛型写
 * {@code java.util.Set} 不做映射展开），25 错根因——本件改 Java 承载（测试树 .kt/.java 混放惯例，
 * Master90「Java 承载」先例），互操作归零。</p>
 */
public class CrossSourceColumnEvidenceTest {

    private static final String ERR = "[42S22][1054] Unknown column 'first_name' in 'field list'";
    private static final String SQL = "SELECT id, first_name FROM user";

    /** 当前源（mp_demo）的 user 表：无 first_name。 */
    private static final Function<String, Set<String>> CURRENT = t ->
            "user".equalsIgnoreCase(t) ? new LinkedHashSet<>(Arrays.asList("id", "username", "status")) : null;

    /** 另一源（jpa_demo）的 user 表：有 first_name。 */
    private static final Function<String, Set<String>> HOLDER = t ->
            "user".equalsIgnoreCase(t) ? new LinkedHashSet<>(Arrays.asList("id", "first_name", "last_name")) : null;

    /** 当前源其实有这一列（报错另有原因：大小写敏感/权限/别名遮蔽）。 */
    private static final Function<String, Set<String>> CURRENT_HAS = t ->
            "user".equalsIgnoreCase(t) ? new LinkedHashSet<>(Arrays.asList("id", "first_name")) : null;

    /** 缓存未热：查不到该表的列集（null）。 */
    private static final Function<String, Set<String>> COLD = t -> null;

    @Test
    public void testColumnAbsentHereButPresentInOtherSourcePromotes() {
        CrossSourceColumnEvidence.Evidence ev = CrossSourceColumnEvidence.assess(ERR, SQL, CURRENT, List.of(HOLDER));
        Assert.assertNotNull("当前源无此列、另一源有 → 必须升格", ev);
        Assert.assertEquals("first_name", ev.column());
        Assert.assertEquals("user", ev.table());
    }

    @Test
    public void testTypoColumnInNoSourceDoesNotPromote() {
        String typo = "[42S22][1054] Unknown column 'frist_name' in 'field list'";
        Assert.assertNull(
                "拼错列名换源解决不了，不得引导用户去换源",
                CrossSourceColumnEvidence.assess(typo, SQL, CURRENT, List.of(HOLDER)));
    }

    @Test
    public void testCurrentSourceHasColumnDoesNotPromote() {
        // 报错另有原因（大小写敏感/权限/别名遮蔽），不归因换源
        Assert.assertNull(
                "当前源有该列时不得归因为选错源",
                CrossSourceColumnEvidence.assess(ERR, SQL, CURRENT_HAS, List.of(HOLDER)));
    }

    @Test
    public void testColdCacheCannotProveAbsenceDoesNotPromote() {
        Assert.assertNull(
                "缓存未热 = 无从证明当前源没有这一列，诚实不升格",
                CrossSourceColumnEvidence.assess(ERR, SQL, COLD, List.of(HOLDER)));
    }

    @Test
    public void testNoOtherSourceHoldsColumnDoesNotPromote() {
        Assert.assertNull(
                "另一源也没这列（或没有其它源）→ 换源解决不了，不升格",
                CrossSourceColumnEvidence.assess(ERR, SQL, CURRENT, List.of()));
        Assert.assertNull(
                "其它源存在但不带该列 → 不升格",
                CrossSourceColumnEvidence.assess(ERR, SQL, CURRENT, List.of(CURRENT)));
    }

    @Test
    public void testNoTableNameFromSqlDoesNotPromote() {
        Assert.assertNull(
                "SQL 解析不出表名时不做任何判定（不猜）",
                CrossSourceColumnEvidence.assess(ERR, "not a sql", CURRENT, List.of(HOLDER)));
    }

    @Test
    public void testNonUnknownColumnTextDoesNotPromote() {
        Assert.assertNull(
                "其它错误形态不走本判据",
                CrossSourceColumnEvidence.assess(
                        "[42S02][1146] Table 'mp_demo.x' doesn't exist", SQL, CURRENT, List.of(HOLDER)));
    }

    @Test
    public void testAliasPrefixStrippedToLastSegment() {
        Assert.assertEquals(
                "u.first_name → first_name",
                "first_name",
                CrossSourceColumnEvidence.unknownColumnOf("Unknown column 'u.first_name' in 'where clause'"));
        Assert.assertEquals(
                "无前缀原样返回",
                "area_id",
                CrossSourceColumnEvidence.unknownColumnOf("[42S22][1054] Unknown column 'area_id' in 'on clause'"));
        Assert.assertNull(
                "非该形态返回 null",
                CrossSourceColumnEvidence.unknownColumnOf("[42S02][1146] Table 'x' doesn't exist"));
    }

    /**
     * 端到端形态：错误文本里带插件拼的 {@code [当前库=…]} 上下文行时，列名解析仍要正确
     * （{@code extractConsoleError} 会把上下文行拼在末尾再交给本判据）。
     */
    @Test
    public void testErrorTextWithContextLineStillParsesColumn() {
        String withCtx = ERR + "\n[当前库=mp_demo，数据源=mp_demo@localhost，数据源默认库=mp_demo]";
        CrossSourceColumnEvidence.Evidence ev = CrossSourceColumnEvidence.assess(withCtx, SQL, CURRENT, List.of(HOLDER));
        Assert.assertNotNull("上下文行不得干扰列名解析", ev);
        Assert.assertEquals("first_name", ev.column());
    }
}
