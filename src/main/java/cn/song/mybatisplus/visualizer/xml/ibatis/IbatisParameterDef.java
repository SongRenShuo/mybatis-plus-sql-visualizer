package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;

/**
 * {@code <parameterMap>} 内的 {@code <parameter>} 声明。
 *
 * @param property   参数属性名
 * @param javaType   javaType 属性原值；缺省 null
 * @param jdbcType   jdbcType 属性原值；缺省 null
 * @param nullValue  nullValue 哨兵原值；缺省 null
 * @param mode       mode 属性原值；缺省 null
 * @param typeHandler typeHandler 属性原值；缺省 null
 */
public record IbatisParameterDef(
        String property,
        @Nullable String javaType,
        @Nullable String jdbcType,
        @Nullable String nullValue,
        @Nullable String mode,
        @Nullable String typeHandler) {
}
