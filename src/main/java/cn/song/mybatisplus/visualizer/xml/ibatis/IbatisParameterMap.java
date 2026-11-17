package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@code <parameterMap id class>} 声明（DTD 里 {@code class} 是 {@code #REQUIRED}，运行期仍可能缺失）。
 *
 * @param id         parameterMap 的 id
 * @param className  class 属性；缺省 null
 * @param parameters {@code <parameter>} 声明清单（按出现顺序）
 */
public record IbatisParameterMap(
        String id,
        @Nullable String className,
        List<IbatisParameterDef> parameters) {

    public IbatisParameterMap {
        parameters = List.copyOf(parameters);
    }
}
