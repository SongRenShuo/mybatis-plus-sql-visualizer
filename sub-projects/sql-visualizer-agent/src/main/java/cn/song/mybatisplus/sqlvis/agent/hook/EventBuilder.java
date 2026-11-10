package cn.song.mybatisplus.sqlvis.agent.hook;

import java.lang.reflect.Method;

/**
 * execute 族判定与影响行数提取（§4.2 #5：getUpdateCount 直取）。
 */
final class EventBuilder {

    /**
     * T-HiddenBugPerfScan 券 R：getUpdateCount 反射缓存由静态 AtomicReference<Method> 改
     * {@link ClassValue}（照 {@link UrlResolver} 券 04 同款范式，禁回退静态强引用）——
     * Method 反向强引用 declaringClass（驱动类）→ classloader，devtools 重启/fat-jar 重部署
     * 场景下旧 driver classloader 被钉住（UrlResolver KDoc 记载的同型泄漏面）；且 reload 后
     * 同名新 Statement 的 invoke 会抛 IllegalArgumentException。ClassValue 的值随 Class
     * 生命周期回收，无静态泄漏面。
     */
    private static final Object METHOD_ABSENT = new Object();

    private static final ClassValue<Object> UPDATE_COUNT_METHOD = new ClassValue<Object>() {
        @Override
        protected Object computeValue(Class<?> type) {
            try {
                return type.getMethod("getUpdateCount");
            } catch (Throwable t) {
                return METHOD_ABSENT;
            }
        }
    };

    static boolean isExecuteFamily(String name) {
        return "execute".equals(name) || "executeQuery".equals(name) || "executeUpdate".equals(name)
                || "executeLargeUpdate".equals(name) || "executeBatch".equals(name)
                || "executeLargeBatch".equals(name);
    }

    /**
     * 影响行数：executeUpdate/executeLargeUpdate 取返回值；execute(boolean) 为 **false** 时
     * 反射 raw.getUpdateCount()（缓存 Method）；batch 求和（SUCCESS_NO_INFO=-2 跳过）；查询 null。
     *
     * <p>JDBC 语义：execute() 返回 true = 首结果为 ResultSet（此时 getUpdateCount() 恒为 -1，
     * 无意义）；返回 false = 首结果为更新计数（此时 getUpdateCount() 才是真实行数）。
     * 旧实现条件写成 TRUE 故 SELECT 恒产出 -1 并显示为「(-1 rows)」——本方法同时兜底负值。
     */
    static Long extractUpdateCount(String op, Object result, Object rawStatement) {
        if ("executeUpdate".equals(op) && result instanceof Integer) {
            return nonNegative(((Integer) result).longValue());
        }
        if ("executeLargeUpdate".equals(op) && result instanceof Long) {
            return nonNegative((Long) result);
        }
        if ("executeBatch".equals(op) && result instanceof int[]) {
            return sumBatch((int[]) result);
        }
        if ("executeLargeBatch".equals(op) && result instanceof long[]) {
            long sum = 0;
            for (long v : (long[]) result) {
                if (v >= 0) {
                    sum += v;
                }
            }
            return sum;
        }
        if ("execute".equals(op) && Boolean.FALSE.equals(result)) {
            try {
                final Object cached = UPDATE_COUNT_METHOD.get(rawStatement.getClass());
                if (cached == METHOD_ABSENT) {
                    return null;
                }
                return nonNegative(((Integer) ((Method) cached).invoke(rawStatement)).longValue());
            } catch (Throwable ignore) {
                return null;
            }
        }
        return null;
    }

    /** 负值 = 无有效计数（JDBC getUpdateCount 的 -1 语义）→ null，不上报垃圾值。 */
    private static Long nonNegative(long v) {
        return v >= 0 ? v : null;
    }

    private static Long sumBatch(int[] arr) {
        long sum = 0;
        for (int v : arr) {
            if (v >= 0) {
                sum += v;
            }
        }
        return sum;
    }

    private EventBuilder() {
    }
}
