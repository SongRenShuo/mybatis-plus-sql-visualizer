package cn.song.mybatisplus.visualizer.xml.ibatis;

import java.util.List;

/**
 * 一条 {@code #...#} 解析后的文本产物：SQL 文本中的 {@code ?} 与紧随其后的原始文本段
 * （{@code $} 替换后仍是字面量）。
 *
 * @param text     {@code ?} 形态文本
 * @param mappings 按 {@code ?} 出现顺序的内联参数映射
 */
public record IbatisParsedText(String text, List<IbatisParamMapping> mappings) {

    public IbatisParsedText {
        mappings = List.copyOf(mappings);
    }
}
