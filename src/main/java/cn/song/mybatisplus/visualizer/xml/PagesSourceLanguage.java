package cn.song.mybatisplus.visualizer.xml;

/**
 * maintainPage {@code <datasource>} 的源语言（T-PagesHql）。
 *
 * <p>自研框架的 {@code <datasource>} 既可能是原生 SQL，也可能是 HQL——两者都长得像 SQL，
 * 必须靠「根标识符与 entityClass 的关系」等证据判定，不能按「有 entityClass 就是 HQL」
 * 一刀切（demo 的 {@code orderMaintain} 就是 entityClass=Order + 原生 SQL {@code FROM t_order}）。</p>
 */
public enum PagesSourceLanguage {
    SQL,
    HQL
}
