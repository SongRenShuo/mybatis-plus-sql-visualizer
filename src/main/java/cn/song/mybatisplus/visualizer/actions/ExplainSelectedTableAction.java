// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/actions/SelectedTableSqlTemplateActions.kt 移植
package cn.song.mybatisplus.visualizer.actions;

/** EXPLAIN 执行计划：{@code EXPLAIN SELECT * FROM <选中表>}。 */
public class ExplainSelectedTableAction extends SelectedTableSqlTemplateAction {

    /** 动作 ID（plugin.xml 注册 id 与设置页 ShortcutEditor 共用）。 */
    public static final String ACTION_ID = "cn.song.mybatisplus.visualizer.ExplainSelectedTable";

    public ExplainSelectedTableAction() {
        super(
                "action.cn.song.mybatisplus.visualizer.ExplainSelectedTable.text",
                SelectedTableTemplateKind.EXPLAIN);
    }
}
