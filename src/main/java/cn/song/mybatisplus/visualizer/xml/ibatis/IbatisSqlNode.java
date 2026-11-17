package cn.song.mybatisplus.visualizer.xml.ibatis;

/**
 * XML 脚本节点（iBatis 的动态树）基类。
 *
 * <p>iBatis 2 的节点只有文本与动态标签两类（{@link IbatisTextNode} / {@link IbatisTagNode}）。
 */
public abstract class IbatisSqlNode {

    /** 只允许本包内的具体节点类型继承（对应 Kotlin sealed 层级）。 */
    IbatisSqlNode() {
    }
}
