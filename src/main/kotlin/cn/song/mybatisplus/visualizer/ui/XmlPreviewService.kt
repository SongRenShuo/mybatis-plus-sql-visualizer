package cn.song.mybatisplus.visualizer.ui
import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.context.ActualSqlPsiResolver
import cn.song.mybatisplus.visualizer.context.ModuleIdentityResolution
import cn.song.mybatisplus.visualizer.context.ModuleIdentityResolver
import cn.song.mybatisplus.visualizer.context.PreviewSourceContext
import cn.song.mybatisplus.visualizer.context.PreviewSourceOrigin
import cn.song.mybatisplus.visualizer.context.SqlPsiResolution
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.psi.XmlCrossNsFragmentResolver
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.interceptor.PaginationSimulator
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import cn.song.mybatisplus.visualizer.xml.MockObject
import cn.song.mybatisplus.visualizer.xml.XmlEwExpander
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlParseException
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import cn.song.mybatisplus.visualizer.xml.XmlPreviewProfile
import cn.song.mybatisplus.visualizer.xml.XmlScriptException
import cn.song.mybatisplus.visualizer.xml.XmlStatementKind
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * XML 预览服务（T-M24-04）：mapper 文件文本 → 语句 → 预览模型 → [PreviewResult]。
 *
 * 纯函数（fileText 输入，无 PSI 依赖），gutter / Action 负责取文件文本与定位语句 id；
 * 弹窗与面板完全复用 [SqlPreviewService.showResult] 链路。
 *
 * - [statementId] 为 null 或未命中 → 取文件首个语句（多语句候选选择后续迭代）；
 * - 解析失败（非 mapper / 非良构 / 未知标签）→ 返回带 warning 的空结果（面板红色提示，不抛异常）；
 * - 渲染期错误（foreach collection 为 null、未解析变量等）合并进 warning；
 * - 参数对照表复用 [ParameterReplacer.buildParamTable]（`#{}` 属性名清单，顺序对应 `?`）；
 * - T-M25.2：传入 [project] 时解析 `@class@field` 静态常量引用（用户项目常量类字段初始值，
 *   经 PSI 常量求值器取值）注入渲染——MyBatis mapper 常见 OGNL 常量写法。
 * - T-IC-02：传入 [project] 时另经 [XmlCrossNsFragmentResolver] 解析跨命名空间 include
 *   （`refid="otherNamespace.sqlId"`）并注入展开；无 [project]（纯文本链）传空表，
 *   行为与改动前逐字节一致。
 */
object XmlPreviewService {

    /** T-MbgPreviewParityAndOpenPerf P5：装配分段耗时埋点（超阈值才打一条，正常路径不刷屏）。 */
    private val LOG = Logger.getInstance(XmlPreviewService::class.java)

    /** 分段耗时日志阈值（毫秒）：低于此值不打，避免每次预览都写日志。 */
    private const val PERF_LOG_THRESHOLD_MS = 200L

    /**
     * O-145 W1：只在选定的 mapper 语句域解析实际 SQL 注入；文件内其他语句不能充当 owner。
     * 光标不在该语句内时不传精确偏移，由解析器完整扫描 owner 并对多注入返回歧义。
     * 仅在本次预览中携带 PSI，不写入模型、面板或历史。
     */
    @Suppress("DEPRECATION")
    fun resolveSourceContext(file: XmlFile, tag: XmlTag, caretOffset: Int? = null): PreviewSourceContext {
        val project = file.project
        return ReadAction.compute<PreviewSourceContext, RuntimeException> {
            val validOwner = tag.isValid && tag.containingFile == file &&
                tag.parentTag?.name == "mapper" && tag.name in setOf("select", "insert", "update", "delete")
            val offset = caretOffset?.takeIf { validOwner && it in tag.textRange.startOffset until tag.textRange.endOffset }
            val resolution = if (validOwner) ActualSqlPsiResolver.resolve(file, tag, offset) else SqlPsiResolution.None
            val module = file.virtualFile?.let { ModuleIdentityResolver.resolve(project, it) }
                ?: ModuleIdentityResolution.None
            PreviewSourceContext(
                project = project,
                origin = PreviewSourceOrigin.XML,
                hostPsiFile = file,
                sqlPsiResolution = resolution,
                moduleResolution = module,
            )
        }
    }

    fun preview(
        fileText: String,
        statementId: String?,
        project: Project? = null,
        entityContext: SqlCompletionContext? = null,
        sourceContext: PreviewSourceContext? = null,
    ): PreviewResult {
        // R12 T-PaginationSim：解析产物三元组（namespace/语句 id/语句类型）透出 try 块——分页模拟后处理用
        var parsedRef: Triple<String?, String, XmlStatementKind>? = null
        // T-PreviewOpenPerfM2 M9：本轮是否因 dumb mode 跳过了 M2（决定要不要挂「索引就绪后重算」）
        var m2Skipped = false
        val (model, staticWarnings) = try {
            // T-IC-02：跨命名空间 include 片段表（无 project → 空表，纯文本链行为不变）
            // T-MbgPreviewParityAndOpenPerf P5：分段计时（把「打开慢」归因到具体一段）
            val perfStart = System.currentTimeMillis()
            val crossNamespace = XmlCrossNsFragmentResolver.resolve(fileText, project)
            val parsed = XmlMapperParser().parse(fileText, crossNamespace)
            val statement = parsed.statements.firstOrNull { it.id == statementId } ?: parsed.statements.firstOrNull()
                ?: return emptyResult("未找到可预览的 SQL 语句（mapper 中无 select/insert/update/delete）。")
            parsedRef = Triple(parsed.namespace, statement.id, statement.kind)
            val warnings = mutableListOf<String>()
            val staticValues = resolveStaticFields(fileText, project, warnings)
            // T-M26-B（P-立项-03）：MP 占位展开（${ew.customSqlSegment} 等）——Java 关联 wrapper 实参 → 片段注入
            // O-77 T-EwStatementScope：占位按语句域扫描（statement.rawText），他语句占位不再记账到本语句
            val ewBindings = resolveEwPlaceholders(fileText, statement.rawText, parsed.namespace, statement.id, project, warnings)
            // T-M29.5 / T-PT-01：mapper 方法参数类型 → 类型类别映射（引号由类别派生；
            // Map/解析失败不含键 → 面板兜底引号、无占位提示）
            val paramTypes = XmlParamTypeResolver.resolveTypes(project, parsed.namespace, statement.id)
            // T-MbgExamplePreview R5：MBG Example 参数类型 → 默认勾选档（distinct / orderByClause 不展开、
            // 集合按 1 项）；解析不到一律 null，行为与升级前一致
            val perfCtxParsed = System.currentTimeMillis()
            val profile = resolveExampleProfile(project, statement.parameterType, parsed.namespace)
            val perfProfile = System.currentTimeMillis()
            // T-MbgExamplePreview M2：Example 形态下再从调用点反推 Criterion（andXxx 方法体的
            // addCriterion 常量）——拿到真值后 ${criterion.condition} 不再落占位
            // T-PreviewOpenPerfM2 M8：dumb mode（索引未就绪 / 正在重建）时**跳过 M2 检索**。
            // `ReferencesSearch` 依赖索引，在索引构建期会同步等待——真机 2026-11-12 11:21 冷启动首次
            // 打开 request→dialog 4499ms，而同一时刻 xml 装配只有 417ms；热态同一语句仅 170ms。
            // 跳过后退回占位骨架（`{{criterion.condition}}`），热态打开或下次打开由缓存补齐。
            val m2Allowed = project != null && !DumbService.getInstance(project).isDumb
            m2Skipped = profile != null && !m2Allowed
            if (m2Skipped) LOG.info("[open-perf] m2 skipped: dumb mode (索引未就绪)")
            val collectionElements = if (profile == null || !m2Allowed) {
                emptyMap()
            } else {
                MbgExampleCallResolver.resolve(project, parsed.namespace, statement.id).orEmpty()
            }
            val perfM2 = System.currentTimeMillis()
            val built = XmlPreviewModel.from(
                statement, parsed.errors,
                staticFieldValues = staticValues, extraBindings = ewBindings, paramTypes = paramTypes,
                profile = profile,
                collectionElements = collectionElements,
            )
            logPreviewPerf(perfStart, perfCtxParsed, perfProfile, perfM2)
            built to warnings
        } catch (e: XmlParseException) {
            return emptyResult(e.message ?: "XML 解析失败。")
        } catch (e: XmlScriptException) {
            return emptyResult(e.message ?: "SQL 脚本解析失败。")
        }
        val result = toResult(model, staticWarnings, entityContext, includeRenderErrors = false).let { preview ->
            if (project == null) preview else {
                val decision = sourceContext?.takeIf { it.project == project }
                    ?.let { DialectRegistry.decision(it) } ?: DialectRegistry.decision(project)
                preview.copy(
                    renderedDialect = DialectRegistry.byName(decision.dialectName),
                    dialectDecision = decision,
                )
            }
        }
        val ref = parsedRef ?: return result

        val finalResult = applyPagination(
            result,
            project = project,
            namespace = ref.first,
            statementId = ref.second,
            kind = ref.third,
        )
        // T-PreviewOpenPerfM2 M9：索引未就绪时跳过过 M2 —— 挂「就绪后重算一次」的闭包，
        // 面板收到后由 `DumbService.runWhenSmart` 在 smart mode 重算并刷新
        //（冷态秒出骨架 + 索引就绪后自动补齐真实 Criterion，无需用户手动刷新）。
        return if (m2Skipped) {
            finalResult.copy(
                retryWhenIndexReady = { preview(fileText, statementId, project, entityContext, sourceContext) },
            )
        } else {
            finalResult
        }
    }

    /**
     * R12 T-PaginationSim：XML 语句路径的分页拦截器模拟后处理（mapper 接口方法带 Page/IPage 形参时）。
     * 定位形参后委托 [applyPaginationShared]。
     */
    private fun applyPagination(
        result: PreviewResult,
        project: Project?,
        namespace: String?,
        statementId: String,
        kind: XmlStatementKind,
    ): PreviewResult = runCatching {
        if (!paginationSimEnabled()) return@runCatching result
        val paramName = MapperPageParamLocator.fromStatement(project, namespace, statementId)
            ?: return@runCatching result
        applyPaginationShared(result, project, paramName, kind)
    }.getOrDefault(result)

    /**
     * R12 T-PaginationSim：分页拦截器模拟后处理共享实现（XML 路径与注解路径共用，
     * [pageParamName] 非 null 且语句为 SELECT 时生效）。
     *
     *  - SELECT 语句 → 渲染文本尾部追加分页 LIMIT（方言形态，[DialectRegistry.effective]；
     *    形参运行时值静态不可得 → `LIMIT ?, ?` 占位，参数来源标注 IPage 访问器公式）；
     *  - 同产出 COUNT 包裹附第二条语句（`SELECT COUNT(*) FROM (原SQL) TOTAL`，MP 3.5.17
     *    getOriginalCountSql 逐字节形态，包裹体**不含** LIMIT——运行时 COUNT 先于 LIMIT 执行）；
     *    受 COUNT 子开关 [paginationCountEnabled] 独立控制（T-CountSimToggle，D129：关=只追加
     *    LIMIT 不产 COUNT；总开关在调用方判定，父开关优先）；
     *  - 语句已含 LIMIT（用户手写，V13c 边界）→ 不重复追加、不产 COUNT（预览保守口径，偏离
     *    MP 运行时「字面拼接」的偏离已登记）；
     *  - 非 SELECT（INSERT/UPDATE/DELETE）不分页（对齐 T-M29-03「仅 SELECT 生效」口径）。
     */
    internal fun applyPaginationShared(
        result: PreviewResult,
        project: Project?,
        pageParamName: String?,
        kind: XmlStatementKind,
    ): PreviewResult = runCatching {
        if (pageParamName == null) return@runCatching result
        if (kind != XmlStatementKind.SELECT) return@runCatching result
        if (PaginationSimulator.hasExistingLimit(result.sql)) return@runCatching result
        val limitClause = PaginationSimulator.placeholderLimitClause(pageParamName)
        val limitLine = (result.effectiveDialect ?: DialectRegistry.effective(project = project)).renderLimit(limitClause)
        val countSql = if (paginationCountEnabled()) {
            PaginationSimulator.wrapCountSql(result.sql.replace(WHITESPACE_REGEX, " ").trim())
        } else {
            null
        }
        val extraSources = PaginationSimulator.placeholderParamSources(pageParamName)
        result.copy(
            sql = result.sql.trimEnd() + "\n" + limitLine,
            compactSql = result.compactSql.trimEnd() + " " + limitLine,
            unresolvedParams = result.unresolvedParams + extraSources,
            paramTable = ParameterReplacer.buildParamTable(result.unresolvedParams + extraSources),
            paramHints = result.paramHints + extraSources.map { ParamHint(quote = false) },
            countSql = countSql,
            paginationLimitLine = limitLine,
            paginationParamSources = extraSources,
        )
    }.getOrDefault(result)

    /** 分页模拟开关（测试环境未注册设置服务时按默认开）。 */
    internal fun paginationSimEnabled(): Boolean =
        runCatching { PluginSettings.getInstance().state.paginationSimEnabled }.getOrDefault(true)

    /** 分页 COUNT 子开关（T-CountSimToggle；测试环境未注册设置服务时按默认开，父开关优先在调用方判定）。 */
    internal fun paginationCountEnabled(): Boolean =
        runCatching { PluginSettings.getInstance().state.paginationCountEnabled }.getOrDefault(true)

    /**
     * T-M26-B：MP 占位展开——扫描 `${变量.customSqlSegment/sqlSelect/sqlSet}`，
     * 经 [XmlEwResolver] 关联 Java Mapper 方法 wrapper 实参展开为 SQL 片段，
     * 包装为 MockObject（`ew.customSqlSegment` 属性访问求值）注入 mock bindings。
     *
     * O-77 T-EwStatementScope：扫描域为当前语句原文（[statementText]，include 展开后内容，
     * 不含他语句与注释），识别域对齐解析域——同文件他语句的 ew 占位不再触发本语句的关联与警示；
     * [statementText] 为 null（注解 `<script>` 等非 mapper 解析链构造）回落文件全文，行为不变。
     */
    private fun resolveEwPlaceholders(
        fileText: String,
        statementText: String?,
        namespace: String?,
        statementId: String,
        project: Project?,
        warnings: MutableList<String>,
    ): Map<String, Any?> {
        val placeholders = XmlEwExpander.placeholders(statementText ?: fileText)
        if (placeholders.isEmpty() || project == null) return emptyMap()
        val resolved = XmlEwResolver.resolve(namespace, statementId, placeholders, project, warnings)
        return resolved.mapValues { (_, fragments) -> MockObject(fragments) }
    }

    /**
     * 静态字段引用解析（T-M25.2）：扫描文本中的 `@类全名@字段名`（OGNL 静态访问），
     * 经 PSI 定位用户项目中的常量类与字段初始值（computeConstantExpression）；
     * 无 project（纯文本测试环境）→ 空映射；类/字段缺失或非常量 → warning + 不注入。
     */
    internal fun resolveStaticFields(fileText: String, project: Project?, warnings: MutableList<String>): Map<String, Any?> {
        if (project == null) return emptyMap()
        val refs = STATIC_REF_REGEX.findAll(fileText)
            .map { "${it.groupValues[1]}@${it.groupValues[2]}" }
            .toSet()
        if (refs.isEmpty()) return emptyMap()
        return refs.mapNotNull { ref ->
            val (className, fieldName) = ref.split('@', limit = 2)
            val value = resolveStaticFieldValue(project, className, fieldName)
            if (value == null) {
                warnings += "静态常量 $ref 无法解析（类/字段不存在或非常量），预览按空值处理，可在预览中填写覆盖值"
                null
            } else {
                ref to value
            }
        }.toMap()
    }

    /**
     * T-MbgPreviewParityAndOpenPerf P5：装配分段耗时（总耗时低于阈值不打，正常路径零噪音）。
     *
     * 现场（2026-11-12）`prewarm done` 到首次 `showSql` 之间有 **5.67s 空档**且插件侧零日志，
     * 无法归因。这条把装配切成四段：解析+上下文 / profile（PSI 类解析）/ M2（PSI 调用点检索）/
     * 建模——下次真机复现即可读出是哪一段。
     */
    private fun logPreviewPerf(start: Long, ctxEnd: Long, profileEnd: Long, m2End: Long) {
        val end = System.currentTimeMillis()
        val total = end - start
        if (total < PERF_LOG_THRESHOLD_MS) return
        LOG.info(
            "[open-perf] total=${total}ms parse+ctx=${ctxEnd - start} " +
                "profile=${profileEnd - ctxEnd} m2=${m2End - profileEnd} model=${end - m2End}",
        )
    }

    /**
     * T-MbgExamplePreview R5：`parameterType` 是否为 MBG 生成器的 Example 类。
     *
     * 判据（任一命中即可，都是 MBG 生成模板的固定结构）：类里有 `oredCriteria` 字段，
     * 或有 `createCriteria()` 方法。命中 → [XmlPreviewProfile.MBG_EXAMPLE]；无 project、
     * 空 parameterType、类解析不到 → null（不改任何默认值，行为与升级前一致）。
     *
     * `parameterType` 可能是全限定名（MBG 默认）也可能是短名；短名按 mapper 接口所在包补全
     * （namespace 是 mapper 接口全名，Example 类与之同包）。
     */
    internal fun resolveExampleProfile(
        project: Project?,
        parameterType: String?,
        namespace: String?,
    ): XmlPreviewProfile? {
        if (project == null || parameterType.isNullOrBlank()) return null
        val fqn = if ('.' in parameterType) {
            parameterType
        } else {
            val pkg = namespace?.substringBeforeLast('.', "")
            if (pkg.isNullOrEmpty()) return null else "$pkg.$parameterType"
        }
        val psiClass = runCatching {
            JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
        }.getOrNull() ?: return null
        val isExample = runCatching {
            psiClass.findFieldByName("oredCriteria", true) != null ||
                psiClass.findMethodsByName("createCriteria", true).isNotEmpty()
        }.getOrDefault(false)
        return if (isExample) XmlPreviewProfile.MBG_EXAMPLE else null
    }

    /** 单字段求值：findClass → findFieldByName → 字段初始值常量求值。 */
    internal fun resolveStaticFieldValue(project: Project, className: String, fieldName: String): String? {
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.allScope(project)) ?: return null
        val field = psiClass.findFieldByName(fieldName, false) ?: return null
        val initializer = field.initializer ?: return null
        return JavaPsiFacade.getInstance(project)
            .constantEvaluationHelper.computeConstantExpression(initializer)?.toString()
    }

    /** 预览模型 → [PreviewResult]（T-M25-02 注解预览共用）：初始渲染 + 参数对照 + 错误合并。 */
    internal fun toResult(
        model: XmlPreviewModel,
        extraWarnings: List<String> = emptyList(),
        entityContext: SqlCompletionContext? = null,
        /**
         * T-MbgExamplePreview R6：是否把基线渲染（默认 state）的错误并入 warning。
         *
         * 预览面板传 false——面板随后会按用户当前勾选重渲染，并把本轮错误放进 `xmlErrors`；
         * 两份混装会让告警描述一个与当前产物不一致的状态（用户截图里两条互相矛盾的错误即此形态）。
         * 注解预览链没有交互态，保持 true（默认），行为不变。
         */
        includeRenderErrors: Boolean = true,
    ): PreviewResult {
        val rendered = model.renderSql()
        val errors = model.errors + extraWarnings + if (includeRenderErrors) rendered.errors else emptyList()
        val sql = rendered.sql
        return PreviewResult(
            sql = sql,
            compactSql = sql.replace(WHITESPACE_REGEX, " ").trim(),
            unresolvedParams = rendered.mappings.map { it.property },
            paramTable = ParameterReplacer.buildParamTable(rendered.mappings.map { it.property }),
            wrapperType = null,
            // T-M27.3：XML 预览注入实体上下文（namespace → Mapper 接口 → 主实体）——
            // 预览弹窗 SQL 编辑器补全的表名/列名/注释来源（此前恒 null，仅关键字可补）
            tableName = entityContext?.tableName,
            columns = entityContext?.columns.orEmpty(),
            columnComments = entityContext?.columnComments.orEmpty(),
            tableComment = entityContext?.tableComment,
            warning = errors.joinToString("；").ifEmpty { null },
            xmlModel = model,
            // T-InjectionGate T-IG-01：`${}` raw 拼接事实透传（解析期 token 收集，非最终文本 regex——
            // 成功替换为模拟值后文本里已无 `${}` 痕迹）
            hasDollarRawSegments = model.hasDollarRawSegments,
        )
    }

    private fun emptyResult(message: String): PreviewResult = PreviewResult(
        sql = "",
        compactSql = "",
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = null,
        tableName = null,
        warning = message,
    )

    private val WHITESPACE_REGEX = Regex("\\s+")

    /** T-M25.2：OGNL 静态字段引用 `@类全名@字段名`（类名可含 `$` 内部类）。 */
    private val STATIC_REF_REGEX = Regex("@([A-Za-z_$][\\w.$]*)@(\\w+)")
}
