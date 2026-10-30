package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlin.math.abs

/**
 * M37.1 日志↔源码双向深链的纯逻辑部分（P-立项-06 N-2）。
 *
 * 正向（日志→源码）F-44 已实现行级精确跳转；本文件补三块：
 *  - [EntrySourceMatcher]：**反向**（源码→日志）——按锚点文件+行号窗口反查日志条目；
 *  - [SourcePathResolver]：sourceFile 相对路径 → VirtualFile，加多源根容错；
 *  - [LogEntryPreviewOpener]：反查条目打开预览弹窗（与 SqlLogPanel.openPreview 同链路的独立实现）。
 *
 * 反查数据源由调用方提供（实时场景传内存 buffer 快照；历史 .sql 文件读取属 IO，
 * 须调用方在后台线程完成——本文件零 IO、零 PSI，纯函数可单测）。
 */
object EntrySourceMatcher {

    /** 行号窗口：拦截器记录的**调用处**行与链锚点行的最大允许偏差（方法体典型长度）。 */
    internal const val DEFAULT_WINDOW = 30

    /** 返回条目上限（弹窗展示防刷屏）。 */
    internal const val MAX_RESULTS = 20

    /**
     * 反查日志条目：同文件条目按时间倒序，行号窗口内（|Δ| ≤ [window]）优先，其余同文件条目随后。
     *
     * 窗口而非等值匹配——[SqlSourceInterceptor] 记录的是 SQL 执行的调用处行，
     * 可能与 Wrapper 链锚点行不同（链起点之下的语句行）。
     * sourceLine=0（旧条目未知行）与锚点行差值天然超窗，自动排除。
     *
     * @param sourceFilePath 锚点文件的相对项目根路径（null/空 = 无法反查，返回空）
     * @param anchorLine     锚点行号（1-based；≤0 返回空）
     */
    fun match(
        entries: List<LogSqlEntry>,
        sourceFilePath: String?,
        anchorLine: Int,
        window: Int = DEFAULT_WINDOW,
    ): List<LogSqlEntry> {
        if (sourceFilePath.isNullOrBlank() || anchorLine <= 0) return emptyList()
        val key = normalize(sourceFilePath)
        val sameFile = entries
            .filter { it.sourceFile != null && sameFile(normalize(it.sourceFile), key) }
            .sortedByDescending { it.timestamp }
        if (sameFile.isEmpty()) return emptyList()
        val inWindow = sameFile.filter { abs(it.sourceLine - anchorLine) <= window }
        val outWindow = sameFile.filter { abs(it.sourceLine - anchorLine) > window }
        return (inWindow + outWindow).take(MAX_RESULTS)
    }

    /**
     * 同一文件判定（T-JumpPath-03）：两种路径形态段对齐后缀互含。
     *
     * 锚点=项目根相对（多模块含模块前缀，如 `mod/src/main/java/cn/song/a/S.java`），
     * 条目 sourceFile=拦截器包式路径（相对源码根，如 `cn/song/a/S.java`）——
     * 等值比较恒不命中，「最近执行记录」恒空（真机实锤）。段对齐（`/` 为界）
     * 杜绝 `xmod/...` 之类后缀假命中。
     */
    internal fun sameFile(a: String, b: String): Boolean =
        a == b || a.endsWith("/$b") || b.endsWith("/$a")

    /** 路径规约：统一正斜杠、去首尾空白与尾斜杠（Windows 反斜杠来源兼容）。 */
    internal fun normalize(path: String): String =
        path.trim().replace('\\', '/').trimEnd('/')
}

/**
 * sourceFile 相对路径 → VirtualFile（M37.1 多源根容错）。
 *
 * 三级策略按精确度递减：① 项目根直拼（历史面板 `src/main/java/...` 形态，F-44 既有语义）；
 * ② 各模块 content root 直拼；③ 各模块 **source root** 直拼（T-JumpPath-02 收编旧
 * `SqlLogPanel.findSourceFile` 语义——拦截器标记路径是包式相对**源码根**
 * `cn/song/a/S.java`，Maven/Gradle 标准布局下前两级均缺 `src/main/java` 段，
 * 缺本策略实时拦截器条目多模块工程全形态解析失败）。只查模块声明的根
 * （O(模块数) 次路径查找），不遍历文件树，无慢操作风险。
 *
 * **线程约定（T-PsiCaptureProtocol T-PCP-03 起为硬断言）**：调用方保证持有读权限——EDT 直读，
 * 或后台线程显式包 `ReadAction`（模块模型读取在两者下均合法）。原先只写在 KDoc 里「靠约定」，
 * 现在入口处 `ApplicationManager.getApplication().assertReadAccessAllowed()` 硬断言：将来若被
 * 后台裸调，违约暴露在**调用点**而不是后续平台模型读取处。同族 [EntryJumpResolver] /
 * [CallerMethodRangeResolver] 的调用点由守卫规则 `edtReadContract`
 * （`convention/BannedApiSourceScanTest`）把关；协议全文见 `docs/架构设计/PSI跨线程读取协议.md`。
 */
object SourcePathResolver {

    fun resolveVirtualFile(project: Project, relativePath: String): VirtualFile? {
        // T-PCP-03：调用面协议断言（EDT 直读 / ReadAction 内读）——后台裸调即违约
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val rel = EntrySourceMatcher.normalize(relativePath)
        if (rel.isEmpty()) return null
        val fs = LocalFileSystem.getInstance()
        project.basePath?.let { base ->
            fs.findFileByPath("${EntrySourceMatcher.normalize(base)}/$rel")?.let { return it }
        }
        return runCatching {
            val modules = ModuleManager.getInstance(project).modules
            modules.asSequence()
                .flatMap { ModuleRootManager.getInstance(it).contentRoots.asSequence() }
                .map { it.path }
                .firstNotNullOfOrNull { root -> fs.findFileByPath("$root/$rel") }
                ?: modules.asSequence()
                    .flatMap { ModuleRootManager.getInstance(it).sourceRoots.asSequence() }
                    .map { it.path }
                    .firstNotNullOfOrNull { root -> fs.findFileByPath("$root/$rel") }
        }.getOrNull()
    }

    /**
     * O-145 多模块来源恢复入口：返回全部候选，不把模块枚举顺序冒充来源证据。
     *
     * 顺序仍与 [resolveVirtualFile] 的三级策略一致（项目根、content roots、source roots），并按
     * VirtualFile 路径去重。旧导航继续调用单值方法，首命中行为完全不变；只有来源上下文链使用本入口。
     */
    fun resolveVirtualFiles(project: Project, relativePath: String): List<VirtualFile> {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val rel = EntrySourceMatcher.normalize(relativePath)
        if (rel.isEmpty()) return emptyList()
        val fs = LocalFileSystem.getInstance()
        val candidates = linkedMapOf<String, VirtualFile>()

        fun collect(path: String) {
            fs.findFileByPath(path)?.let { candidates.putIfAbsent(it.path, it) }
        }

        project.basePath?.let { base -> collect("${EntrySourceMatcher.normalize(base)}/$rel") }
        runCatching {
            val modules = ModuleManager.getInstance(project).modules
            modules.asSequence()
                .flatMap { ModuleRootManager.getInstance(it).contentRoots.asSequence() }
                .forEach { root -> collect("${root.path}/$rel") }
            modules.asSequence()
                .flatMap { ModuleRootManager.getInstance(it).sourceRoots.asSequence() }
                .forEach { root -> collect("${root.path}/$rel") }
        }
        return candidates.values.toList()
    }
}

/**
 * 反查条目打开预览弹窗（M37.1）——与 [SqlLogPanel.openPreview] / `HistorySqlLogPanel` 同链路。
 *
 * T-InParamRec：识别通道收编为 [LogSqlEntry.toPreviewResult] 统一入口（此前是 M37.1 时代的
 * 独立识别/回退双分支实现，T-HistParam F3 统一识别通道时漏接本入口）——字面量恢复
 * （`literalParamRecovery` 开关）与零参数/数量不匹配分流警告自此三口同源。
 */
object LogEntryPreviewOpener {

    fun open(project: Project, entry: LogSqlEntry) {
        val state = PluginSettings.getInstance().state
        val result = entry.toPreviewResult(
            formatSql = state.logFormatSql,
            literalRecovery = state.literalParamRecovery,
            capturedDialect = entry.dialectName,
        )
        // M37.1：携带来源位置——预览面板可感知/回显 SQL 出处
        SqlPreviewService.showResult(project, result, sourceLocation = entry.sourceFile)
    }
}
