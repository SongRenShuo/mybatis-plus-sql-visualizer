// T-SyncK2JMaster187 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/XmlParamBackfillHints.kt 移植
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.xml.ParamKind;
import cn.song.mybatisplus.visualizer.xml.ParamTypeInfo;
import cn.song.mybatisplus.visualizer.xml.ParameterMapping;
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * XML 预览参数回填提示组装（O-81 T-XmlListParamSplit）：面板 XML 分支
 * {@link SqlPreviewPanel}{@code .renderSql} 每帧对全部 {@code #{}} source 组装 {@link ParamHint}
 * （quote / inList），供 {@code renderParamValue} 回填渲染。
 *
 * <p>拆分决策双通道（先红读数实证，见计划 §二）：</p>
 * <ul>
 *   <li><b>签名通道（R1 主修复）</b>：{@code paramTypeFor(source).kind == COLLECTION}（mapper 方法签名
 *       {@code List<String>} 等，{@code XmlParamTypeResolver} 产物）→ 逗号输入<b>无条件</b>按多元素渲染——
 *       用户输入 {@code PI,WP} 期望 {@code IN ('PI', 'WP')}，不应再押注 SQL 形态探测；
 *       引号按元素类别派生（{@code List<String>}→TEXT→引号、{@code List<Long>}→NUMBER→裸渲染，
 *       与 O-78 foreach item 派生同口径）——升级前集合恒引号，{@code List<Long>} 输入 {@code 1,2} 出
 *       {@code ('1','2')}（先红读数实证）；</li>
 *   <li><b>形态通道（升级前行为）</b>：{@code ?} 被 {@code SqlPreviewPanel.detectInClauseParams} 判进
 *       {@code IN (...)} 区间 → 拆分。签名信息缺失（接口不可解析 / Map 入参）时的唯一通道。</li>
 * </ul>
 *
 * <p>显式 {@code typeHandler} 的集合映射不拆（自定义序列化形态，逗号拆分反而破坏语义——宁缺勿错）。</p>
 *
 * <p>纯函数零平台依赖（xml/sql 层类型），可独立单测（testFast 桶）。
 * Kotlin 顶层函数 {@code xmlBackfillHints} → 本门面类静态方法。</p>
 */
public final class XmlParamBackfillHints {

    private XmlParamBackfillHints() {
    }

    /** Kotlin 默认参数等价重载：{@code mappings} 缺省为空表。 */
    @NotNull
    public static List<ParamHint> xmlBackfillHints(@NotNull XmlPreviewModel xml,
                                                   @NotNull List<String> sources,
                                                   @NotNull Set<Integer> shapeInListIndices) {
        return xmlBackfillHints(xml, sources, shapeInListIndices, List.of());
    }

    @NotNull
    public static List<ParamHint> xmlBackfillHints(@NotNull XmlPreviewModel xml,
                                                   @NotNull List<String> sources,
                                                   @NotNull Set<Integer> shapeInListIndices,
                                                   @NotNull List<ParameterMapping> mappings) {
        List<ParamHint> result = new ArrayList<>(sources.size());
        for (int index = 0; index < sources.size(); index++) {
            String source = sources.get(index);
            ParamTypeInfo info = xml.paramTypeFor(source);
            if (info != null && info.kind() == ParamKind.COLLECTION && !hasExplicitTypeHandler(mappings, source)) {
                // 引号按元素类别派生（List<String>→TEXT→引号、List<Long>→NUMBER→裸渲染）；
                // 元素类别缺失 → quoteFor 兜底 → 恒引号（Kotlin 双层 elvis 形态）
                ParamKind elementKind = info.elementKind();
                Boolean quote;
                if (elementKind != null) {
                    quote = elementKind.isQuoteRequired();
                } else {
                    Boolean quoted = xml.quoteFor(source);
                    quote = quoted != null ? quoted : Boolean.TRUE;
                }
                // T-DateLiteralBackfill：List<LocalDate>/List<LocalDateTime> 的每个元素产 ANSI 字面量
                result.add(new ParamHint(quote, null, true, elementKind == ParamKind.TEMPORAL));
            } else {
                Boolean quoted = xml.quoteFor(source);
                Boolean quote = quoted != null ? quoted : Boolean.TRUE;
                // T-DateLiteralBackfill：LocalDate/LocalDateTime 参数产 ANSI 字面量（info 缺失按非时间态）
                result.add(new ParamHint(quote, null, shapeInListIndices.contains(index),
                        info != null && info.kind() == ParamKind.TEMPORAL));
            }
        }
        return result;
    }

    /** 该 {@code #{}} 映射是否显式声明了 typeHandler（{@code #{p,typeHandler=X}} 形态）。 */
    private static boolean hasExplicitTypeHandler(@NotNull List<ParameterMapping> mappings, @NotNull String source) {
        for (ParameterMapping mapping : mappings) {
            if (source.equals(mapping.property())) {
                return mapping.attributes() != null && mapping.attributes().containsKey("typeHandler");
            }
        }
        return false;
    }
}
