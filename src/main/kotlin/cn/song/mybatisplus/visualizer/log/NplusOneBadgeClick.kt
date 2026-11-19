package cn.song.mybatisplus.visualizer.log

import java.awt.Point
import javax.swing.JList

/**
 * T-Nplus1Aggregate D235：N+1 嫌疑徽章**点击命中判定**（几何层，无状态纯查询——不进 UI 门、
 * 不改任何状态，UI 接线层据此保持薄）。
 *
 * 前提修正（计划册 §三.1 实测证伪）：徽章不是画在 rail 上的矩形，而是头部元信息行
 * （[RailCellRendererBase.headerLine]，SimpleColoredComponent）里的**文本片段**——平台无现成
 * 片段点击命中，这里按「片段 tag」实现：徽章片段渲染时挂 [NPLUS_ONE_BADGE_TAG]
 * （[SqlEntryPresentation.headerBadgeFragments] → `append(text, attributes, tag)`），
 * 点击时按被点条目**重渲染一次**（共享渲染器实例只持有最后渲染行，须重绑被点条目），
 * 用 `SimpleColoredComponent.getFragmentTagAt(x)` 做片段级命中——语义等价计划册的
 * 「命中区=徽章绘制矩形」。
 *
 * 命中带只覆盖元信息行纵向范围：SQL 区/错误行的点击让给既有单击预览职责（不进过滤态）。
 * API 依据（javap，ideaIU 2024.3.7.1 lib/app-client.jar 编译基线）：
 * `append(String, SimpleTextAttributes, Object)` / `getFragmentTagAt(int)` 均在。
 */
internal fun nplusOneBadgeEntryAt(
    list: JList<*>,
    renderer: RailCellRendererBase,
    point: Point,
    suspects: Set<String>,
): LogSqlEntry? {
    val index = list.locationToIndex(point)
    if (index < 0) return null
    val entry = list.model.getElementAt(index) as? LogSqlEntry ?: return null
    if (entry.structureKey !in suspects) return null
    val cellBounds = list.getCellBounds(index, index) ?: return null
    val yInCell = point.y - cellBounds.y
    val comp = renderer.getListCellRendererComponent(list, entry, index, list.isSelectedIndex(index), false)
        as? RailCellRendererBase ?: return null
    val header = comp.headerLine
    // 只认元信息行纵向带（headerLine 首选高 + 4px 容差；SQL 区点击让给单击预览）
    if (yInCell > header.preferredSize.height + 4) return null
    val xInHeader = point.x - cellBounds.x - comp.insets.left - SqlListTypeColor.BAR_WIDTH
    if (header.getFragmentTagAt(xInHeader) != NPLUS_ONE_BADGE_TAG) return null
    return entry
}
