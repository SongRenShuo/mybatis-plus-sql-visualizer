package cn.song.mybatisplus.visualizer.onboarding

import cn.song.mybatisplus.visualizer.ui.highlight.SqlDialectMappingPrewarm
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 项目打开预热（S2「首用即生效」第一项）：把 SQL 方言映射一次性延迟加载提前到项目打开时做完。
 *
 * ## P-7 查证结论：预热助手已存在，缺的只是「项目打开」这个触发点
 *
 * `ui/highlight/SqlDialectMappingPrewarm`（T-SqlDialectPrewarm S1~S5）已随 master 落地，且在
 * 两处接线：`SqlLogToolWindowFactory.createToolWindowContent`（EDT 有界等待 300ms）与
 * `SqlPreviewPanelEditorLifecycle.prewarmPreviewTempFile`（BGT 首行）。两处都是**用户主动打开
 * 工具窗/触发预览**之后才发生——项目刚打开、用户还没点任何东西时，首查仍可能落在 EDT。
 * 本类补的就是这个缺口：**只做接线，不重复实现预热逻辑**（同一助手、同一弱键去重状态）。
 *
 * ## 线程纪律（红线：预热不得在 EDT 做慢操作）
 *
 * `prewarm` 是 fire-and-forget：内部 `executeOnPooledThread { prewarmNow(project) }`，
 * 真实的首查（`SyntaxHighlighterFactory.getSyntaxHighlighter`）永远在 BGT。
 * **不得**在这里改调 `prewarmAndAwait`（那是给 EDT 装配链用的有界等待，在项目打开路径上等它
 * 等于把启动拖慢 300ms 且毫无收益）。守卫：`onboarding/OnboardingWiringTest`
 * 把「只调 prewarm、不调 prewarmAndAwait / prewarmNow」钉成源码断言。
 *
 * `ProjectActivity` 在平台启动链里跑在池化线程（243 `StartupManagerImpl.doRunPostStartupActivities`
 * 注释原文「Must be executed in a pooled thread outside a project loading modal task」），
 * 即便调用点将来挪到 EDT，`prewarm` 也只是投一个池化任务，不会在 EDT 上做慢操作。
 *
 * ## 「关键索引」为什么不做强制预热
 *
 * 本插件的 mapper 发现走平台 `FileTypeIndex`：该索引由平台在 dumb mode 自行构建，**项目打开期间
 * 正在建**。插件主动「预热索引」只有两条路——等索引建完（拖慢启动、且用户没要求任何功能）或
 * 自己全项目扫文件（重复平台工作）。两条都违背「不能拖慢启动」，故本类只预热方言映射，
 * 索引按平台节奏就绪。mapper 探测的降级形态见 [MapperOnboardingPrimer] 的廉价文件名启发式。
 */
class OnboardingPrewarm : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (project.isDisposed) return
        // Fire-and-forget：真实首查在池化线程；helper 内部自带「单测短路 / 无 SQL FileType 短路 /
        // 每项目一次」三重守卫，重复调用是空转。
        SqlDialectMappingPrewarm.prewarm(project)
    }
}
