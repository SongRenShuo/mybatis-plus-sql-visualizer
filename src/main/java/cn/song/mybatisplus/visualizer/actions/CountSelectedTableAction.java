// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/actions/SelectedTableSqlTemplateActions.kt 移植
package cn.song.mybatisplus.visualizer.actions;

/** 行数统计：{@code SELECT COUNT(*) FROM <选中表>}。 */
public class CountSelectedTableAction extends SelectedTableSqlTemplateAction {

    public static final String ACTION_ID = "cn.song.mybatisplus.visualizer.CountSelectedTable";

    public CountSelectedTableAction() {
        super(
                "action.cn.song.mybatisplus.visualizer.CountSelectedTable.text",
                SelectedTableTemplateKind.COUNT);
    }
}
