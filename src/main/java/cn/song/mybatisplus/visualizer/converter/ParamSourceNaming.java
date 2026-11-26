package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.WrapperArg;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 逐列展开条件的参数键命名（D255 T-SetEntityParamKeyCollision）。
 *
 * <p>{@code setEntity(entity)}、{@code setUpdateEntity(entity)}、{@code updatable(entity)} 这类
 * 「实体全列」API 在静态近似下会展开成多条 {@code col = ?}。这些占位符如果共用匿名键 {@code "?"}，
 * 预览参数面板会为每列各建一个输入框，却全部读写同一个 {@code paramValues["?"]}：改一个框全列跟着变，
 * 清除只能写回第一个框。</p>
 *
 * <p>这里统一生成「实体实参前缀 + 列名」的参数键（与 {@code BatchOperationConverter.idSource} 的既有惯例一致）：
 * {@code setEntity(query)} → {@code query.id}、{@code setEntity(condition)} → {@code condition.status}。
 * 实参不可命名（字面量、{@code new Xxx()}、静态可求值表达式等）时回退 {@code entity.<列名>}。</p>
 *
 * <p>原 Kotlin {@code internal object ParamSourceNaming}：本仓 Java 端口按「无状态工具 + 静态方法」
 * 承载（与 {@link cn.song.mybatisplus.visualizer.psi.TableNameAnnotations} 同形态），
 * Kotlin 测试侧仍以 {@code ParamSourceNaming.prefixOf(...)} 静态调用。</p>
 */
public final class ParamSourceNaming {

    /** 实参无法充当前缀时的兜底值。 */
    public static final String FALLBACK_PREFIX = "entity";

    /**
     * 可安全充当参数键前缀的形态：标识符或点链（{@code query}、{@code this.query}、{@code req.user}）。
     * 带括号/空格/{@code new} 之类的表达式文本一律不接受——它们既不适合出现在 tooltip 的 {@code #source}
     * 里，也会让导入参数的末段匹配产生难以预期的歧义。
     */
    private static final Pattern PREFIX_REGEX = Pattern.compile(
            "^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$");

    private ParamSourceNaming() {
    }

    /** 从事先解析好的实参里取实体前缀（wrapper 调用链形态）。 */
    public static String prefixOf(List<WrapperArg> args) {
        WrapperArg first = args == null || args.isEmpty() ? null : args.get(0);
        if (!(first instanceof WrapperArg.ValueArg valueArg)) return sanitize(null);
        if (!(valueArg.getValue() instanceof SqlValue.Placeholder placeholder)) return sanitize(null);
        return sanitize(placeholder.getSource());
    }

    /** 清洗前缀文本：合法标识符/点链原样保留，其余回退 {@link #FALLBACK_PREFIX}。 */
    public static String sanitize(String raw) {
        String text = raw == null ? "" : raw.trim();
        return PREFIX_REGEX.matcher(text).matches() ? text : FALLBACK_PREFIX;
    }

    /** 单列参数键：{@code prefix.columnName}。 */
    public static String source(String prefix, String columnName) {
        return prefix + "." + columnName;
    }

    /**
     * 同一条语句里多次展开同一实体时的去重前缀：第一次用原前缀，之后 {@code query#2}、{@code query#3}。
     * MP 运行时后一次 {@code setEntity} 覆盖前一次，静态近似会展开两份；键必须区分开，
     * 否则两份同名条件又回到「改一个全变」。
     */
    public static String uniquePrefix(String base, int sequence) {
        return sequence <= 1 ? base : base + "#" + sequence;
    }
}
