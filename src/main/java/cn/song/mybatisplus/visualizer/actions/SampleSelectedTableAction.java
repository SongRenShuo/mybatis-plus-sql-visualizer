// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/actions/SelectedTableSqlTemplateActions.kt 移植
// T-SyncK2JMaster482 R5 增量：LIMIT 值设置页可配（PluginSettings.sampleLimit，默认 10，钳制 1..10000）
package cn.song.mybatisplus.visualizer.actions;

/** 抽样 N 行（R5：LIMIT 值设置页可配，默认 10；范围钳制 1..10000 防 0/负数与误输入全表）：{@code SELECT * FROM <选中表> LIMIT N}。
 *  T-SyncK2JMaster136 C波 差距5：渲染统一上收 SelectedTableSqlTemplateAction.renderSelectedTableTemplate
 *  （按捕获方言 renderLimit，LIMIT 读取移 actionPerformed）。 */
public class SampleSelectedTableAction extends SelectedTableSqlTemplateAction {

    public static final String ACTION_ID = "cn.song.mybatisplus.visualizer.SampleSelectedTable";

    public SampleSelectedTableAction() {
        super(
                "action.cn.song.mybatisplus.visualizer.SampleSelectedTable.text",
                SelectedTableTemplateKind.SAMPLE);
    }
}
