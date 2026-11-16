package cn.song.mybatisplus.visualizer.ui.nav

import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisStatementKind
import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapNamespaceIndex
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import java.util.concurrent.ConcurrentHashMap

/**
 * T-Ibatis2Support 券 F（车道 L8）：iBatis 2.x `<sqlMap>` 的 id → 标签定位**单一事实源**。
 *
 * 供 [IbatisDaoStatementGotoDeclarationHandler]（Java DAO → XML 语句）与
 * `cn.song.mybatisplus.visualizer.ui.ref.IbatisXmlIdReferenceContributor`（XML 内部 id 引用）共用，
 * 避免两处各写一份标签名表与落点口径。
 *
 * ## 定位链（T-IbatisGutterPerf P1 起走自定义索引，2026-11-16）
 *
 * `ns.id` / 裸 `id` → [SqlMapNamespaceIndex] 一次索引查询拿到**候选文件**
 * （`ns:` 键 / `stmt:` 键）→ PSI 侧校验根 `namespace` + 语句 `id` 精确匹配 → 返回 `id` 属性值元素。
 *
 * 历史（三轮演进，均见 `docs/开发计划/20261116-0925-T-IbatisGutterPerf-*`）：
 *  - 券 F：全项目 XML 逐个 PSI 解析筛 sqlMap 文件 + 文件清单单槽 TTL(5s) + 结果级单槽 memo；
 *  - T-IbatisGutterPerf P1：清单与语句两层都换成自定义索引（`all` / `ns:` / `stmt:` 三类键），
 *    TTL / 清单代 / 单槽 memo 整套状态机退役——失效全交平台增量，不再有「清单未就绪」窗口，
 *    也不会出现多工程同开互相踢槽。
 *
 * ## 门控成本（性能红线）
 *
 * 热路径（reference contributor 的每个 `XmlAttributeValue`、每次 `resolve()`）**只走 O(1) 判定**：
 * [isSqlMapFile] 是一次 `rootTag?.name` 字符串比较；同文件 id 查找（[findIdAttributeInFile]）是
 * 文件内标签遍历。`xml/ibatis/IbatisXmlRecognizer.recognize(file)` 内含 `SqlMapClient` 的 classpath
 * 索引查询（`JavaPsiFacade.findClass`），**严禁**在本对象或调用方热路径上调用——iBatis 与 MyBatis
 * 由根标签名天然互斥（`sqlMap` vs `mapper`），无需三选二识别。
 *
 * ## 失败语义（T-IbatisGutterPerf P3）
 *
 * - dumb / 索引未就绪：**不查索引**，返回空（「本次没有」），但不写任何负缓存——就绪后下次照常解析；
 * - 读操作被写操作撤销（`ProcessCanceledException`）：**不吞**，直接上抛让本趟作废、平台自行重跑。
 *   旧实现用 `runCatching{}.getOrDefault(emptyList())` 把取消吞成「空清单」并当正常结果缓存，
 *   慢趟换装后图标粘性缺席（用户 2026-11-16 真机报障：图标出现过一次后无故不再显示）。
 *
 * 回退通道：Registry `sqlvis.gutter.ibatisIndexedResolve=false` 回旧的全项目 XML 扫描（A/B 用，
 * 下一版删除）；日志锚点 `[gutter-perf] ibatis resolve: source=index|scan …`。
 *
 * ## 落点口径
 *
 * 一律返回**语句 `id` 的属性值元素**（`XmlAttributeValue`）——与
 * `ui/ref/MybatisXmlTargets.findIdAttributeInFile` 一致（Ctrl+B / 引用落点都落在值上）。
 */
internal object IbatisXmlStatementTargets {

    /** iBatis 语句映射文件根标签名（`sql-map-2.dtd`）。MyBatis 3 的根是 `mapper`——两者互斥。 */
    const val SQL_MAP_ROOT_TAG: String = "sqlMap"

    /** 回退开关（T-IbatisGutterPerf P5）：false＝回旧的全项目 XML 扫描（A/B 用，下一版删除）。 */
    private const val INDEXED_RESOLVE_REGISTRY_KEY = "sqlvis.gutter.ibatisIndexedResolve"

    private val LOG = Logger.getInstance(IbatisXmlStatementTargets::class.java)

    /** 定位三态（语义见 [lookupStatementAnchors]）：确定存在 / 确定不存在 / 暂时查不了。 */
    sealed interface Lookup {
        class Found(val anchors: List<XmlAttributeValue>) : Lookup
        object Absent : Lookup
        object Unknown : Lookup
    }

    /** 测试缝：替换三态查询（生产＝null）。矩阵用例用它驱动 Unknown / Absent 两个分支。 */
    internal var lookupOverrideForTest: ((Project, String, String?) -> Lookup)? = null

    /** 根标签名 == `sqlMap`（热路径唯一门控；O(1)，零索引、零识别器调用）。 */
    fun isSqlMapFile(file: PsiFile?): Boolean = (file as? XmlFile)?.rootTag?.name == SQL_MAP_ROOT_TAG

    /**
     * `com.ibatis.sqlmap.client.SqlMapExecutor` 里吃 statementId 的入口方法（jar
     * `org.apache.ibatis:ibatis-sqlmap:2.3.4.726` 的 `javap` 实测清单，见
     * `IbatisDaoStatementGotoDeclarationHandler` 类注释：全部重载的 statementId 都在第一参数位；
     * `startBatch`/`executeBatch` 族不吃 statementId 故不在表内）。
     *
     * T-Ibatis2Gap 券 B 收尾：迁入本对象作**单一事实源**——券 B 的 gutter 跳转
     * （`ui/gutter/IbatisJavaToXmlLineMarkerProvider` 与 `IbatisJavaCallSites`）与 L8 的
     * goto handler 共用同一批入口判定。注意：L8 handler 内部仍是 private 副本，待主控
     * 合并后统一改引（本分支不动 L8 文件）。
     */
    val STATEMENT_ENTRY_METHODS: Set<String> = setOf(
        "insert",
        "update",
        "delete",
        "queryForObject",
        "queryForList",
        "queryWithRowHandler",
        "queryForPaginatedList",
        "queryForMap",
    )

    /** 根 `<sqlMap>` 的 namespace（DTD 里 `#IMPLIED`，可为 null / 空串）。 */
    fun rootNamespace(file: PsiFile?): String? =
        (file as? XmlFile)?.rootTag?.getAttributeValue("namespace")?.trim()?.takeIf { it.isNotEmpty() }

    /** 是否 6 类可执行语句标签之一（`select`/`insert`/`update`/`delete`/`statement`/`procedure`）。 */
    fun isStatementTag(tagName: String): Boolean = IbatisStatementKind.byTagName(tagName) != null

    /** 6 类语句标签名（顺序 = `IbatisStatementKind` 声明序；测试与诊断用）。 */
    val statementTagNames: List<String> = IbatisStatementKind.entries.map { it.tagName }

    /**
     * 同文件内按 `id` **字面值**找标签的 `id` 属性值元素（引用/跳转落点）。
     *
     * @param tagName `sql` / `resultMap` / `parameterMap` / 6 类语句标签名
     */
    fun findIdAttributeInFile(file: PsiFile?, tagName: String, id: String): XmlAttributeValue? {
        val xmlFile = file as? XmlFile ?: return null
        val root = xmlFile.rootTag ?: return null
        return PsiTreeUtil.findChildrenOfType(root, XmlTag::class.java)
            .firstOrNull { it.name == tagName && it.getAttributeValue("id") == id }
            ?.getAttribute("id")
            ?.valueElement
    }

    /**
     * XML 内部 id 引用 → **同文件**目标（`include@refid` / `resultMap=` / `parameterMap=` /
     * `resultMap@extends` 四类共用）。
     *
     * 两段式查找（复刻引擎 `XmlParserState.applyNamespace` 的查找序，与
     * `xml/ibatis/SqlMapScriptBuilder` 的 `direct → applyNamespace` 一致）：
     *  1. 先按 refId **原样**找同文件标签；
     *  2. 找不到、且 refId **不含 `.`**、文件 namespace 非空时，再按 `namespace + "." + refId` 找。
     *
     * 目标缺失一律返回 null —— 引用是 **soft**，平台不标红（红线：不做校验器形态）。
     *
     * 跨文件（经 `sqlMapConfig.xml` 的 `<sqlMap resource=…>` 清单）本期不做，见计划册券 F。
     */
    fun resolveSameFileIdReference(
        contextFile: PsiFile?,
        tagName: String,
        rawRefId: String,
    ): XmlAttributeValue? {
        val refId = rawRefId.trim()
        if (refId.isEmpty()) return null
        findIdAttributeInFile(contextFile, tagName, refId)?.let { return it }
        val namespace = rootNamespace(contextFile) ?: return null
        if (refId.contains('.')) return null
        return findIdAttributeInFile(contextFile, tagName, "$namespace.$refId")
    }

    /**
     * `ns.id` / 裸 `id` → 语句标签的 `id` 属性值元素（Java DAO → XML 语句跳转用）。
     *
     * 三态语义见 [lookupStatementAnchors]；本重载只取「找到」的锚，把「确定不存在」与「暂时查不了」
     * 都折叠成空列表（预览 / Ctrl+B / MCP 的口径：查不到就回落，不区分原因）。
     *
     * @param preferredNamespace 引用点所属类的限定名（无则 null；仅用于裸 id 的排序偏好）
     */
    fun resolveStatementAnchors(
        project: Project?,
        rawStatementId: String,
        preferredNamespace: String? = null,
    ): List<XmlAttributeValue> =
        when (val lookup = lookupStatementAnchors(project, rawStatementId, preferredNamespace)) {
            is Lookup.Found -> lookup.anchors
            else -> emptyList()
        }

    /**
     * 定位**三态**（T-IbatisGutterPerf P2.1，2026-11-16 用户报障「改了 namespace 图标不消失」）：
     *
     * - [Lookup.Found]：确定存在；
     * - [Lookup.Absent]：**确定**不存在——索引查询已成功执行、候选面没有该 namespace / 语句；
     *   调用方据此摘图标并**收回正缓存**（缓存不能给已删除/改名的目标续命）；
     * - [Lookup.Unknown]：暂时查不了（dumb / 索引重建中 `IndexNotReadyException` / 候选文件 PSI
     *   瞬时取不到）——调用方据此**保留**已确认的图标、且不动缓存。
     *
     * 为什么必须分三态：慢趟结果按趟整体替换，把「暂时查不了」当「不存在」会造成图标粘性缺席
     * （S2）；反过来把「确定不存在」当「暂时查不了」会让图标永不过期（P2.1 报障）。两者只能靠
     * 「查询是否成功执行」区分，不能靠返回值是否为空。
     *
     * @param preferredNamespace 引用点所属类的限定名（无则 null；仅用于裸 id 的排序偏好）
     */
    fun lookupStatementAnchors(
        project: Project?,
        rawStatementId: String,
        preferredNamespace: String? = null,
    ): Lookup {
        val statementId = rawStatementId.trim()
        if (project == null || statementId.isEmpty()) return Lookup.Absent
        lookupOverrideForTest?.let { override -> return override(project, statementId, preferredNamespace) }
        val indexed = indexedResolveEnabled()
        // dumb / 索引未就绪：不查索引（查询会抛 IndexNotReadyException），按「未知」返回，
        // 且**不留任何负缓存**——这是旧链「空清单被固化」的反面（见类头「失败语义」）。
        if (indexed && DumbService.isDumb(project)) return Lookup.Unknown
        val startedNs = System.nanoTime()
        val dot = statementId.lastIndexOf('.')
        val lookup = try {
            if (dot > 0 && dot < statementId.length - 1) {
                val namespace = statementId.substring(0, dot).trim()
                val id = statementId.substring(dot + 1).trim()
                if (namespace.isEmpty() || id.isEmpty()) Lookup.Absent
                else lookupByNamespace(project, namespace, id, indexed)
            } else {
                lookupByStatementId(project, statementId, preferredNamespace, indexed)
            }
        } catch (e: IndexNotReadyException) {
            // 索引重建中：这是「未知」不是「没有」（PCE 不在此列，它必须上抛让本趟作废）。
            Lookup.Unknown
        }
        logPerf(statementId, lookup, indexed, startedNs)
        return lookup
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** `ns.id`：索引只给候选文件，namespace 与 id 都由 PSI 精确校验（索引键偏差不会错跳）。 */
    private fun lookupByNamespace(
        project: Project,
        namespace: String,
        id: String,
        indexed: Boolean,
    ): Lookup {
        val files = if (indexed) {
            SqlMapNamespaceIndex.getSqlMapFilesByNamespace(project, namespace)
        } else {
            legacySqlMapFiles(project)
        }
        var psiUnavailable = false
        for (vf in files) {
            val file = PsiManager.getInstance(project).findFile(vf) as? XmlFile
            if (file == null) {
                psiUnavailable = true
                continue
            }
            if (file.rootTag?.getAttributeValue("namespace")?.trim() != namespace) continue
            val anchors = statementAnchorsIn(file, id)
            if (anchors.isNotEmpty()) return Lookup.Found(anchors)
        }
        return if (psiUnavailable) Lookup.Unknown else Lookup.Absent
    }

    /** 裸 statement id：索引 `stmt:` 键取候选文件；同 namespace 的文件排首位。 */
    private fun lookupByStatementId(
        project: Project,
        id: String,
        preferredNamespace: String?,
        indexed: Boolean,
    ): Lookup {
        val files = if (indexed) {
            SqlMapNamespaceIndex.getSqlMapFilesByStatementId(project, id)
        } else {
            legacySqlMapFiles(project)
        }
        val hits = ArrayList<Pair<String?, XmlAttributeValue>>()
        var psiUnavailable = false
        for (vf in files) {
            val file = PsiManager.getInstance(project).findFile(vf) as? XmlFile
            if (file == null) {
                psiUnavailable = true
                continue
            }
            val anchors = statementAnchorsIn(file, id)
            if (anchors.isEmpty()) continue
            val ns = file.rootTag?.getAttributeValue("namespace")?.trim()
            for (anchor in anchors) hits.add(ns to anchor)
        }
        if (hits.isEmpty()) return if (psiUnavailable) Lookup.Unknown else Lookup.Absent
        val ordered = if (preferredNamespace.isNullOrEmpty()) hits.map { it.second }
        else hits.sortedBy { if (it.first == preferredNamespace) 0 else 1 }.map { it.second }
        return Lookup.Found(ordered)
    }

    /** `<sqlMap>` 直接子级里的语句标签（`id` 字面值命中全部返回，含 `<selectKey>` 之外的重复 id 变体）。 */
    private fun statementAnchorsIn(file: XmlFile, id: String): List<XmlAttributeValue> {
        val root = file.rootTag ?: return emptyList()
        return root.subTags
            .filter { isStatementTag(it.name) && it.getAttributeValue("id") == id }
            .mapNotNull { it.getAttribute("id")?.valueElement }
    }

    /**
     * 回退通道（Registry `sqlvis.gutter.ibatisIndexedResolve=false`）：旧的全项目 XML 扫描。
     *
     * 仅 A/B 与应急用，下一版删除。**不再包 `runCatching`**：取消/索引未就绪应当上抛或由调用方
     * 的 dumb 门拦住，吞成空清单正是本任务要根治的缺陷（T-IbatisGutterPerf P3）。
     */
    private fun legacySqlMapFiles(project: Project): List<VirtualFile> {
        val xmlFileType = FileTypeManager.getInstance().getStdFileType("XML")
        return FileTypeIndex.getFiles(xmlFileType, GlobalSearchScope.projectScope(project))
            .asSequence()
            .mapNotNull { vf -> PsiManager.getInstance(project).findFile(vf) as? XmlFile }
            .filter { it.rootTag?.name == SQL_MAP_ROOT_TAG }
            .mapNotNull { it.virtualFile }
            .toList()
    }

    /** 索引化开关（生产＝`Registry.is(key, true)`；key 未注册时直接返回默认值）。 */
    internal var indexedResolveEnabledProvider: () -> Boolean = {
        // `is` 是 Kotlin 关键字，调 Java 侧 `Registry.is(String, boolean)` 必须加反引号。
        runCatching { Registry.`is`(INDEXED_RESOLVE_REGISTRY_KEY, true) }.getOrDefault(true)
    }

    private fun indexedResolveEnabled(): Boolean = indexedResolveEnabledProvider()

    /** 每条语句只记一次定位读数（source / 候选命中数 / 首个落点文件 / 耗时）。 */
    private val perfLoggedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun logPerf(
        statementId: String,
        lookup: Lookup,
        indexed: Boolean,
        startedNs: Long,
    ) {
        if (!perfLoggedKeys.add(statementId)) return
        val ms = (System.nanoTime() - startedNs) / 1_000_000
        val anchors = (lookup as? Lookup.Found)?.anchors
        val state = when (lookup) {
            is Lookup.Found -> "found"
            Lookup.Absent -> "absent"
            Lookup.Unknown -> "unknown"
        }
        val file = anchors?.firstOrNull()?.containingFile?.virtualFile?.path ?: "-"
        LOG.info(
            "[gutter-perf] ibatis resolve: source=${if (indexed) "index" else "scan"} " +
                "statementId=$statementId state=$state hits=${anchors?.size ?: 0} file=$file ms=$ms",
        )
    }

    /** 测试缝：清空「每条语句只记一次」的日志去重集（对应旧 `clearCachesForTest` 的调用位）。 */
    internal fun resetPerfLogForTest() {
        perfLoggedKeys.clear()
    }
}