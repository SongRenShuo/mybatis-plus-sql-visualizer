package cn.song.mybatisplus.visualizer.log;

import com.intellij.ui.SimpleColoredComponent;

import java.awt.Point;
import javax.swing.JList;

/**
 * T-Nplus1Aggregate D235：N+1 嫌疑徽章<b>点击命中判定</b>（几何层，无状态纯查询——不进 UI 门、
 * 不改任何状态，UI 接线层据此保持薄）。
 *
 * <p>前提修正（计划册 §三.1 实测证伪）：徽章不是画在 rail 上的矩形，而是头部元信息行
 * （{@code RailCellRendererBase.headerLine}，SimpleColoredComponent）里的<b>文本片段</b>——平台无现成
 * 片段点击命中，这里按「片段 tag」实现：徽章片段渲染时挂 {@link NplusOneAggregateKt#NPLUS_ONE_BADGE_TAG}
 * （SqlEntryPresentation.headerBadgeFragments → {@code append(text, attributes, tag)}），
 * 点击时按被点条目<b>重渲染一次</b>（共享渲染器实例只持有最后渲染行，须重绑被点条目），
 * 用 {@code SimpleColoredComponent.getFragmentTagAt(x)} 做片段级命中——语义等价计划册的
 * 「命中区=徽章绘制矩形」。
 *
 * <p>命中带只覆盖元信息行纵向范围：SQL 区/错误行的点击让给既有单击预览职责（不进过滤态）。
 * API 依据（javap，ideaIU 2024.3.7.1 lib/app-client.jar 编译基线）：
 * {@code append(String, SimpleTextAttributes, Object)} / {@code getFragmentTagAt(int)} 均在。
 */
public final class NplusOneBadgeClickKt {

    private NplusOneBadgeClickKt() {}

    /**
     * 判定 point 是否命中某条目头部元信息行上的 N+1 嫌疑徽章片段；命中返回该条目，否则 null。
     */
    static LogSqlEntry nplusOneBadgeEntryAt(JList<?> list, RailCellRendererBase renderer, Point point,
                                            java.util.Set<String> suspects) {
        int index = list.locationToIndex(point);
        if (index < 0) return null;
        Object element = list.getModel().getElementAt(index);
        if (!(element instanceof LogSqlEntry entry)) return null;
        if (!suspects.contains(entry.structureKey())) return null;
        java.awt.Rectangle cellBounds = list.getCellBounds(index, index);
        if (cellBounds == null) return null;
        int yInCell = point.y - cellBounds.y;
        Object rendered = renderer.getListCellRendererComponent(
                list, entry, index, list.isSelectedIndex(index), false);
        if (!(rendered instanceof RailCellRendererBase comp)) return null;
        SimpleColoredComponent header = comp.headerLine;
        // 只认元信息行纵向带（headerLine 首选高 + 4px 容差；SQL 区点击让给单击预览）
        if (yInCell > header.getPreferredSize().height + 4) return null;
        int xInHeader = point.x - cellBounds.x - comp.getInsets().left - SqlListTypeColor.BAR_WIDTH;
        if (!NplusOneAggregateKt.NPLUS_ONE_BADGE_TAG.equals(header.getFragmentTagAt(xInHeader))) return null;
        return entry;
    }
}
