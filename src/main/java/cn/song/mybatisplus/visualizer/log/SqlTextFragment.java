package cn.song.mybatisplus.visualizer.log;

import com.intellij.ui.SimpleTextAttributes;

/**
 * 着色文本片段（T-LogListFreeze-04）：列表条目渲染数据单元——文本 + 属性，供
 * {@link com.intellij.ui.SimpleColoredComponent#append} 直接消费。
 * 取代原 HTML <code>&lt;span&gt;</code> 通道：渲染成本从「BasicHTML 解析 + CSS 属性解析 + 文档布局」
 * 降为轻量文本测量，文本原样保留（无 HTML 转义面，注入场景不复存在）。
 */
public record SqlTextFragment(String text, SimpleTextAttributes attributes, Object tag) {

    /**
     * 历史 2 参形态（Kotlin 默认参 Java 等价）：无片段标记。tag 的语义：
     * D235 透传给 {@code SimpleColoredComponent.append(text, attributes, tag)} 的片段标记
     * （当前仅 N+1 嫌疑徽章点击命中判定使用，{@code NplusOneAggregateKt.NPLUS_ONE_BADGE_TAG}；
     * null = 无标记）。
     */
    public SqlTextFragment(String text, SimpleTextAttributes attributes) {
        this(text, attributes, null);
    }
}
