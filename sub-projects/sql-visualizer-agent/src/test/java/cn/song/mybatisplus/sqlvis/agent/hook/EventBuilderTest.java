package cn.song.mybatisplus.sqlvis.agent.hook;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

/**
 * 影响行数提取语义（T-AgentRowsCount）：JDBC `execute()` 返回 true = 首结果为 ResultSet
 * （此时 getUpdateCount() 恒 -1，无意义）；返回 false = 首结果为更新计数。
 *
 * 回归背景（用户实测「SELECT 显示 (-1 rows)」）：旧实现把条件写成 `Boolean.TRUE`，
 * 于是每次 SELECT 都上报 -1；本测试锁死该语义，并覆盖负值一律不上报。
 */
public class EventBuilderTest {

    /** 反射缝假 Statement：可控 getUpdateCount 返回值（public static 保证反射 invoke 不被访问检查拒绝）。 */
    public static class FakeStatement {
        private final int updateCount;

        public FakeStatement(int updateCount) {
            this.updateCount = updateCount;
        }

        public int getUpdateCount() {
            return updateCount;
        }
    }

    @Test
    public void execute返回true_首结果为ResultSet_不上报计数() {
        // 旧实现的 bug 面：SELECT 走 execute() → true → 旧代码上报 getUpdateCount()=-1
        assertNull("ResultSet 结果不得上报影响行数",
                EventBuilder.extractUpdateCount("execute", Boolean.TRUE, new FakeStatement(-1)));
    }

    @Test
    public void 无静态Method强引用字段_券R红线() {
        // T-HiddenBugPerfScan 券 R：UPDATE_COUNT_METHOD 曾是 static AtomicReference<Method>——
        // Method 反向强引用 declaringClass（驱动类）→ classloader，热部署/重部署场景钉住旧
        // loader（券 04 红线：禁静态强引用驱动类；UrlResolver 同期已改 ClassValue，此处漏网）。
        // 结构断言：摘掉 ClassValue 改造（回归静态强引用）本例必红。
        for (Field f : EventBuilder.class.getDeclaredFields()) {
            assertFalse("EventBuilder 不得有静态 AtomicReference 字段（券 R/04 红线）: " + f.getName(),
                    f.getType().getName().contains("AtomicReference"));
            assertNotEquals("EventBuilder 不得有静态 Method 类型字段（券 R/04 红线）: " + f.getName(),
                    Method.class, f.getType());
        }
    }

    @Test
    public void 无getUpdateCount方法的类_负缓存收敛为null() {
        // METHOD_ABSENT 哨兵：ClassValue 未命中每次重算须显式缓存否定结果
        class NoCountStatement {
            public void doNothing() {
            }
        }
        assertNull(EventBuilder.extractUpdateCount("execute", Boolean.FALSE, new NoCountStatement()));
        assertNull("负缓存路径重复调用稳定", EventBuilder.extractUpdateCount("execute", Boolean.FALSE, new NoCountStatement()));
    }

    @Test
    public void execute返回false_首结果为更新计数_上报真实行数() {
        assertEquals(Long.valueOf(7L),
                EventBuilder.extractUpdateCount("execute", Boolean.FALSE, new FakeStatement(7)));
        assertEquals("0 行合法（0 = 无行被修改）", Long.valueOf(0L),
                EventBuilder.extractUpdateCount("execute", Boolean.FALSE, new FakeStatement(0)));
    }

    @Test
    public void execute返回false_但驱动回负值_不上报() {
        assertNull(EventBuilder.extractUpdateCount("execute", Boolean.FALSE, new FakeStatement(-1)));
    }

    @Test
    public void executeUpdate与LargeUpdate_取值并守负() {
        assertEquals(Long.valueOf(3L), EventBuilder.extractUpdateCount("executeUpdate", 3, null));
        assertEquals(Long.valueOf(9L), EventBuilder.extractUpdateCount("executeLargeUpdate", 9L, null));
        assertNull("负值不得上报（-1 无有效计数语义）",
                EventBuilder.extractUpdateCount("executeUpdate", -1, null));
        assertNull("类型不符不上报", EventBuilder.extractUpdateCount("executeUpdate", "3", null));
    }

    @Test
    public void batch求和_跳过负值与SUCCESS_NO_INFO() {
        // SUCCESS_NO_INFO = -2（驱动无法提供行数）→ 跳过（既有语义）
        assertEquals(Long.valueOf(5L), EventBuilder.extractUpdateCount("executeBatch", new int[]{2, -2, 3}, null));
        assertEquals(Long.valueOf(0L), EventBuilder.extractUpdateCount("executeBatch", new int[]{-2}, null));
        assertEquals(Long.valueOf(4L),
                EventBuilder.extractUpdateCount("executeLargeBatch", new long[]{4L, -2L}, null));
    }

    @Test
    public void 查询族与反射失败_不上报() {
        assertNull("executeQuery 无影响行数",
                EventBuilder.extractUpdateCount("executeQuery", new Object(), null));
        assertNull("rawStatement 无 getUpdateCount 时不得抛错",
                EventBuilder.extractUpdateCount("execute", Boolean.FALSE, "not-a-statement"));
    }
}
