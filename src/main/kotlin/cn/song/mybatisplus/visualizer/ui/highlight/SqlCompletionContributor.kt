package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.sql.SqlClause
import cn.song.mybatisplus.visualizer.sql.detectClause
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext
import java.util.WeakHashMap

private val LOG = Logger.getInstance(SqlCompletionContributor::class.java)

/**
 * SQL 预览代码补全（M9-03，方案 A：Community 兼容自定义补全）。
 *
 * 为原生 SQL 编辑器（插件预览面板，language="SQL" + IS_PREVIEW_EDITOR 守卫）提供 Ctrl+Space 补全：
 *  - SQL 关键字（[SqlLexer.KEYWORDS]）
 *  - 当前预览实体的表名 / 列名 / 表别名（来自 [SqlCompletionContext]，经 Editor/PSI 双载体 userData 传递）
 *  - M16：列名/表名补全项**展示注释**（withTailText 灰色尾缀）且**支持注释关键词检索**
 *    （withLookupString——输入"设备名"可命中 `name` 列）；**位置感知**过滤
 *    （FROM/JOIN 后只提示表名、WHERE/ON/HAVING/GROUP BY/ORDER BY 后只提示列名）
 *  - T-DbSchemaCompletion：列候选加入**数据源 schema 列**（库里存在而实体类没有的列），
 *    同名列注释/类型取数据源，tailText 追加 DB 类型串（候选合并见 [mergeColumnCandidates]）
 *
 * M16.2 修复（M16 回归）：
 *  - 上下文载体由**仅 PSI 文件 userData** 改为 **Editor userData 优先 + PSI 兜底**（[resolveContext]）——
 *    嵌入式编辑器中用户输入后 `PsiDocumentManager` commit 会重建非项目 PSI 文件（userData 丢失），
 *    而 Editor 对象编辑期间稳定不重建；
 *  - **ctx 为 null 时不再直接 return**：关键字补全不依赖上下文（恢复 M9 语义，`if (ctx != null)` 包裹实体补全）；
 *  - `detectClause` 防御：offset clamp 到文本长度 + 空文本/异常回退 UNKNOWN（旧 PSI offset 与新文档
 *    长度不一致时 `substring` 越界抛异常 → Contributor 异常被 IDE 吞掉 → 补全静默失效）。
 *
 * T-M27-01（M27）：`detectClause` / `SqlClause` 迁入 `sql.SqlTextAnalysis` 纯函数模块
 * （零 PSI/IDE 依赖），本类改调公共 API——语义与防御行为不变（预览编辑器补全依赖，回归测试兜底）。
 *
 * 补全来源抽象见 [SqlCompletionProvider]（预留 Database 方案 B 切换点）。
 * 注册见 plugin.xml `<completion.contributor>`。
 */
class SqlCompletionContributor : CompletionContributor() {

    companion object {
        /**
         * Document → SqlCompletionContext 映射（WeakHashMap，文档释放时自动清除）。
         *
         * 解决原生 SQL PSI 文件 commitDocument 重建后 PSI userData 丢失的问题：
         * 预览编辑器创建时存入，补全时按 Document 查找。
         */
        private val documentContexts = WeakHashMap<Document, SqlCompletionContext>()

        fun registerContext(document: Document, ctx: SqlCompletionContext) {
            documentContexts[document] = ctx
            LOG.debug("[SqlCompletion] registerContext: doc=${document.hashCode()}, table=${ctx.tableName}, mapSize=${documentContexts.size}")
        }
    }

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val prefix = result.prefixMatcher
                    val provider = SqlCompletionProvider.instance

                    // ★ 调试：每次补全触发时打印关键信息
                    // 券03（T-PerfSweep27-03）：isDebugEnabled 门控包裹——autopopup 下 addCompletions
                    // 每敲一个标识符字符触发一次，急切插值（simpleName/文本截取/双 getUserData/
                    // viewProvider.document/containsKey）在 debug 关闭态全部照付。门控后这些读取
                    // 也一并免除；debug 开启态输出逐字不变。
                    if (LOG.isDebugEnabled) {
                        val editor = parameters.editor
                        val position = parameters.position
                        val containingFile = position.containingFile
                        LOG.debug("[SqlCompletion] addCompletions triggered")
                        LOG.debug("[SqlCompletion]   editor=${editor.javaClass.simpleName}, doc=${editor.document.hashCode()}")
                        LOG.debug("[SqlCompletion]   position=${position.javaClass.simpleName}, text='${position.text?.take(30)}'")
                        LOG.debug("[SqlCompletion]   containingFile=${containingFile?.name}, lang=${containingFile?.language?.id}")
                        LOG.debug("[SqlCompletion]   editorUserData=${editor.getUserData(SqlCompletionContext.KEY)?.tableName}")
                        LOG.debug("[SqlCompletion]   psiUserData=${containingFile?.getUserData(SqlCompletionContext.KEY)?.tableName}")
                        LOG.debug("[SqlCompletion]   documentContexts.size=${documentContexts.size}")
                        val docFromViewProvider = containingFile?.viewProvider?.document
                        LOG.debug("[SqlCompletion]   docFromViewProvider=${docFromViewProvider?.hashCode()}, inMap=${documentContexts.containsKey(docFromViewProvider)}")
                    }

                    // 关键字（M16.2：不依赖上下文——ctx 缺失/丢失时关键字补全仍生效，恢复 M9 语义）
                    provider.keywords().forEach { word ->
                        if (prefix.prefixMatches(word)) result.addElement(LookupElementBuilder.create(word))
                    }

                    // 当前实体表/列/别名（M16：位置感知 + 注释展示/检索）
                    // M16.2：双载体读取（Editor userData 优先、PSI 兜底）；ctx 为 null 时跳过实体补全
                    // M34 修复：level 3 兜底再补一次 editor.document 直接查（PSI viewProvider document
                    // 与注册 document 可能因 commit 重建不同实例）
                    val ctx = resolveContext(parameters.editor.getUserData(SqlCompletionContext.KEY), parameters.position)
                        ?: documentContexts[parameters.editor.document]
                    if (LOG.isDebugEnabled) LOG.debug("[SqlCompletion]   resolveContext result=${ctx?.tableName}")
                    if (ctx == null) return
                    // 子句判定（T-M27-01：纯函数迁入 SqlTextAnalysis，语义与 M16.2 防御行为不变；
                    // 异常/无文件回退 UNKNOWN）
                    val clause = runCatching {
                        detectClause(
                            parameters.position.containingFile?.text.orEmpty(),
                            parameters.position.textRange.startOffset,
                        )
                    }.getOrElse { SqlClause.UNKNOWN }

                    // 表名：全位置提示（主表 + 联表候选，前缀过滤——T-M27.4 用户需求
                    // "表名也支持提示"：输入表名前缀即可命中，不再限定 FROM/JOIN 后；
                    // 候选与主表去重）
                    provider.tableName(ctx)?.takeIf { prefix.prefixMatches(it) }?.let { table ->
                        result.addElement(withComment(LookupElementBuilder.create(table), provider.tableComment(ctx)))
                    }
                    provider.candidateTables(ctx).forEach { t ->
                        if (t.tableName != ctx.tableName && prefix.prefixMatches(t.tableName)) {
                            result.addElement(withComment(LookupElementBuilder.create(t.tableName), t.comment))
                        }
                    }
                    // 列名：WHERE/ON/HAVING/GROUP BY/ORDER BY 后、SELECT 列位或子句无法判定
                    if (clause != SqlClause.TABLE) {
                        // T-DbSchemaCompletion：候选 = 实体列 ∪ 数据源 schema 列。此前数据源列只用于
                        // 覆盖同名列注释，**库里存在而实体类没有的列补不出来**（用户实测缺口）。
                        // 数据源列由预览预热写入 SqlCompletionContext（columnsCached 只读缓存），
                        // 本处零遍历——autopopup 每敲一个标识符字符都走这里（T-Perf-09 红线）；
                        // 同名列注释/类型取数据源（M35 优先级），tailText 追加 DB 类型串标注来源。
                        mergeColumnCandidates(ctx).forEach { candidate ->
                            if (prefix.prefixMatches(candidate.name)) {
                                result.addElement(columnLookupElement(candidate))
                            }
                        }
                    }
                    // SELECT 列位：聚合函数（M16 位置感知补充）
                    if (clause == SqlClause.SELECT_COLUMN) {
                        AGGREGATE_FUNCTIONS.forEach { fn ->
                            if (prefix.prefixMatches(fn)) result.addElement(LookupElementBuilder.create(fn))
                        }
                    }
                    // 表别名：仅子句无法判定时（全量候选）
                    if (clause == SqlClause.UNKNOWN) {
                        provider.tableAlias(ctx)?.takeIf { prefix.prefixMatches(it) }
                            ?.let { result.addElement(LookupElementBuilder.create(it)) }
                    }
                }
            },
        )
    }

    /**
     * 补全项注释装饰（M16 注释展示/检索；T-M16.3-02 修复）：
     * [LookupElementBuilder] **不可变**——`withTailText`/`withLookupString` 返回**新实例**，
     * 原实现丢弃返回值（`builder.withTailText(...)` 结果未使用）导致注释 tailText 从不展示——
     * 用户实测「字段可补全但看不到注释」根因。链式应用：注释灰色尾缀 + 注释关键词检索。
     */
    internal fun withComment(builder: LookupElementBuilder, comment: String?): LookupElementBuilder =
        if (comment != null) builder.withTailText("  $comment").withLookupString(comment) else builder

    /** SELECT 列位补充的聚合函数（M16）。 */
    private val AGGREGATE_FUNCTIONS = listOf("COUNT", "SUM", "MAX", "MIN", "AVG")

    /**
     * 上下文读取（三级回退）：
     *  1. Editor userData（编辑器实例未变时最快）
     *  2. PSI 文件 userData（编辑器重建但 PSI 未变时）
     *  3. Document → Context 映射（PSI 文件 commitDocument 重建后最终兜底）
     *
     * 抽为 internal 纯函数供测试。
     */
    internal fun resolveContext(editorContext: SqlCompletionContext?, position: PsiElement): SqlCompletionContext? {
        editorContext?.let {
            LOG.debug("[SqlCompletion] resolveContext: hit level 1 (editor userData), table=${it.tableName}")
            return it
        }
        position.containingFile?.getUserData(SqlCompletionContext.KEY)?.let {
            LOG.debug("[SqlCompletion] resolveContext: hit level 2 (PSI userData), table=${it.tableName}")
            return it
        }
        // 最终兜底：通过 Document 映射查找
        val doc = position.containingFile?.viewProvider?.document
        if (doc == null) {
            LOG.debug("[SqlCompletion] resolveContext: miss (doc is null)")
            return null
        }
        val ctx = documentContexts[doc]
        LOG.debug("[SqlCompletion] resolveContext: hit level 3 (document map)=${ctx != null}, table=${ctx?.tableName}")
        return ctx
    }
}
