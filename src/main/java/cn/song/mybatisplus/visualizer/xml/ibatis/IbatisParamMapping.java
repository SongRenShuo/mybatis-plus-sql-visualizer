package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;

/**
 * {@code #...#} 内联参数解析结果（对应引擎 {@code ParameterMapping} 的 SQL 文本相关字段）。
 *
 * <p>只有 propertyName / jdbcTypeName / nullValue 影响面板可见面；javaType/mode/numericScale
 * 只影响 JDBC 绑定（setParameters），对 SQL 文本零影响——但保留下来供接线车道做类型提示。
 *
 * @param propertyName 参数属性名
 * @param jdbcTypeName {@code #p:TYPE#} 第二段；缺省 null
 * @param nullValue    {@code #p:TYPE:NULL值#} 第三段哨兵；缺省 null
 * @param javaTypeName 只影响 JDBC 绑定；缺省 null
 * @param mode         只影响 JDBC 绑定；缺省 null
 * @param numericScale 只影响 JDBC 绑定；缺省 null
 */
public record IbatisParamMapping(
        String propertyName,
        @Nullable String jdbcTypeName,
        @Nullable String nullValue,
        @Nullable String javaTypeName,
        @Nullable String mode,
        @Nullable Integer numericScale) {

    public IbatisParamMapping {
        java.util.Objects.requireNonNull(propertyName, "propertyName");
    }

    /** 只有属性名的便捷构造（{@code #name#} 无附加段的形态）。 */
    public IbatisParamMapping(String propertyName) {
        this(propertyName, null, null, null, null, null);
    }

    /** 属性名 + jdbcType 两段形态（{@code #name:TYPE#}）。 */
    public IbatisParamMapping(String propertyName, @Nullable String jdbcTypeName) {
        this(propertyName, jdbcTypeName, null, null, null, null);
    }

    /** 三段形态（{@code #name:TYPE:NULL值#}，简单语法 size>=5 的产物）。 */
    public IbatisParamMapping(String propertyName, @Nullable String jdbcTypeName, @Nullable String nullValue) {
        this(propertyName, jdbcTypeName, nullValue, null, null, null);
    }

    /** 五段形态（parameterMap 声明投影：property/jdbcType/nullValue/javaType/mode；numericScale 缺省）。 */
    public IbatisParamMapping(String propertyName,
                              @Nullable String jdbcTypeName,
                              @Nullable String nullValue,
                              @Nullable String javaTypeName,
                              @Nullable String mode) {
        this(propertyName, jdbcTypeName, nullValue, javaTypeName, mode, null);
    }
}
