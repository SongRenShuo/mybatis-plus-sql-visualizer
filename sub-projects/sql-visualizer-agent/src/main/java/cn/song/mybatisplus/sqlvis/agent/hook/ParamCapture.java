package cn.song.mybatisplus.sqlvis.agent.hook;

import cn.song.mybatisplus.sqlvis.agent.util.Desensitizer;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 参数捕获（§4.2 #4 主策略）：setXxx 显式签名枚举白名单——**禁用 startsWith("set") 宽泛匹配**
 * （DebugTools 误记 setFetchSize/setMaxRows/setQueryTimeout 坑）。白名单外（含参数个数/类型不符）
 * 返回 false，调用方继续走其他分支。
 */
public final class ParamCapture {

    /** 白名单方法：参数个数 2~3 且首参为 Integer（parameterIndex）。 */
    private static final java.util.Set<String> WHITELIST = new java.util.HashSet<String>(java.util.Arrays.asList(
            "setString", "setInt", "setLong", "setDouble", "setFloat", "setBigDecimal",
            "setBoolean", "setByte", "setShort", "setDate", "setTime", "setTimestamp",
            "setObject", "setNull", "setBytes", "setURL", "setArray", "setBlob", "setClob",
            "setNString", "setAsciiStream", "setBinaryStream", "setCharacterStream"));

    private final Map<Integer, Object[]> captured = new TreeMap<Integer, Object[]>();

    /**
     * 织入通道入口（T-ConnCastSafety）：语句面把白名单 setter 的 {@code (int index, T value)}
     * 首两参直接送进来——连接代理关闭后，参数捕获不能再依赖语句代理。
     * 与 {@link #tryCapture} 同口径（值渲染、默认 toString 降级、脱敏在 {@link #snapshot} 做），
     * 差别只是这里已经知道「哪个方法被调过」，类型名按值形态推断。
     */
    public void capture(int index, Object value) {
        final String typeName = value == null ? "NULL" : simpleTypeName(value);
        captured.put(index, new Object[]{renderValue(value), typeName});
    }

    /** 是否 setter 白名单方法（织入器按此决定要不要在方法头插参数钩子）。 */
    public static boolean isSetter(String methodName) {
        return WHITELIST.contains(methodName);
    }

    /**
     * 尝试捕获；命中白名单返回 true（调用方直接放行原调用），否则 false。
     * 形态：setXxx(int parameterIndex[, T value][, Calendar/int scaleOrType])。
     */
    public boolean tryCapture(String methodName, Object[] args) {
        if (!WHITELIST.contains(methodName)) {
            return false;
        }
        if (!(args[0] instanceof Integer) || args.length < 2 || args.length > 3) {
            return false;
        }
        final boolean isSetNull = "setNull".equals(methodName);
        final Object value = isSetNull ? null : args[1];
        String typeName = null;
        if (value != null) {
            typeName = simpleTypeName(value);
        } else if (isSetNull) {
            typeName = "NULL";
        } else if (args.length >= 3) {
            typeName = simpleTypeName(args[2]);
        }
        captured.put((Integer) args[0], new Object[]{renderValue(value), typeName});
        return true;
    }

    /**
     * 快照：按参数序号排序输出 [index, valueText|null, type|null]。
     * 脱敏在采集层执行（§4.5）：按 SQL 中第 n 个 `?` 的前置列名提示 + 值形态打码。
     */
    public List<String[]> snapshot(String sql, boolean desensitize) {
        final List<String[]> out = new ArrayList<String[]>(captured.size());
        for (Map.Entry<Integer, Object[]> e : captured.entrySet()) {
            final String valueText = (String) e.getValue()[0];
            final String type = (String) e.getValue()[1];
            final String masked = desensitize
                    ? Desensitizer.mask(columnHint(sql, e.getKey()), valueText)
                    : valueText;
            out.add(new String[]{String.valueOf(e.getKey()), masked, type});
        }
        return out;
    }

    public int size() {
        return captured.size();
    }

    /** SQL 中第 placeholderIndex 个 `?` 的前置标识符（列名提示；解析失败 null 不打码）。 */
    public static String columnHint(String sql, int placeholderIndex) {
        if (sql == null || sql.isEmpty()) {
            return null;
        }
        int seen = 0;
        int idx = -1;
        // 简易扫描：跳过引号字面量后数 `?`（字符串字面量内的 ? 不占位）
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inSingle) {
                if (c == '\'') inSingle = false;
                continue;
            }
            if (inDouble) {
                if (c == '"') inDouble = false;
                continue;
            }
            if (c == '\'') {
                inSingle = true;
            } else if (c == '"') {
                inDouble = true;
            } else if (c == '?') {
                if (++seen == placeholderIndex) {
                    idx = i;
                    break;
                }
            }
        }
        if (idx < 0) {
            return null;
        }
        final StringBuilder id = new StringBuilder();
        for (int i = idx - 1; i >= 0; i--) {
            char c = sql.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '"') {
                id.insert(0, c);
            } else if (id.length() > 0) {
                break;
            }
        }
        final String s = id.toString().replace("\"", "");
        return s.isEmpty() ? null : s;
    }

    private static String renderValue(Object v) {
        if (v == null) {
            return null;
        }
        // 复杂对象（流/Blob/Clob/Array）不 toString 防炸栈，仅形态标注
        if (v instanceof Blob || v instanceof Clob || v instanceof java.io.InputStream
                || v instanceof java.io.Reader || v instanceof Array) {
            return "<" + simpleTypeName(v) + ">";
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).toPlainString();
        }
        final String s = String.valueOf(v);
        // 驱动私有类型未覆写 toString 时产出 类名@哈希 垃圾值（oracle.sql.Datum 族、达梦/金仓
        // 私有类型实测形态）——降级为形态标注，防止面板出现不可读文本（T-AgentDialectLoop）
        if (isDefaultToString(v, s)) {
            return "<" + simpleTypeName(v) + ">";
        }
        return s;
    }

    /** 判定字符串是否为未覆写的默认 Object.toString 形态（全限定类名@十六进制哈希）。 */
    private static boolean isDefaultToString(Object v, String s) {
        final String name = v.getClass().getName();
        if (s.length() <= name.length() || s.charAt(name.length()) != '@' || !s.startsWith(name)) {
            return false;
        }
        for (int i = name.length() + 1; i < s.length(); i++) {
            final char c = s.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f') && (c < 'A' || c > 'F')) {
                return false;
            }
        }
        return true;
    }

    private static String simpleTypeName(Object v) {
        final String n = v.getClass().getName();
        final int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(dot + 1);
    }
}
