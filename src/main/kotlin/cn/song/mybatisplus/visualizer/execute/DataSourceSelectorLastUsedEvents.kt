package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * last-used 数据源记忆的变更广播（D202 拆分自 [DataSourceSelector]，行数预算见 `GiantFileBudgetTest`）。
 *
 * 立项动因（UX 换源重渲染）：用户在某模块换了数据源后，**仍然开着的预览面板**头部绑定行与方言
 * 徽章停留在旧源上，只能关开预览才更新。写入侧（`DataSourceSelector.rememberRunner` /
 * `forgetLastRunner` / `forgetAllLastUsed`）广播一次，存活的预览面板订阅后只刷新头部
 * （绑定行 + 方言徽章），不重渲染 SQL、不新增常驻轮询。
 *
 * 订阅方用 `project.messageBus.connect(this)` 绑定生命周期；[TOPIC] 为项目级。
 */
object LastUsedChangeEvents {

    /** 记忆变更监听（函数式接口，订阅即实现）。 */
    fun interface Listener {
        fun onLastUsedChanged()
    }

    /** last-used 记忆变更 Topic（项目级）。 */
    val TOPIC: Topic<Listener> = Topic.create(
        "MPVS last used data source changed",
        Listener::class.java,
        Topic.BroadcastDirection.NONE,
    )

    /** 广播记忆变更（异常全吞：诊断通知绝不能影响执行主链）。 */
    fun notifyChanged(project: Project) {
        runCatching {
            if (!project.isDisposed) {
                project.messageBus.syncPublisher(TOPIC).onLastUsedChanged()
            }
        }
    }
}
