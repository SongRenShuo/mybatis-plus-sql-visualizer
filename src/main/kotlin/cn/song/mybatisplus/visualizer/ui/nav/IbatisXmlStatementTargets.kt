package cn.song.mybatisplus.visualizer.ui.nav

import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisStatementKind
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * T-Ibatis2Support 券 F（车道 L8）：iBatis 2.x `<sqlMap>` 的 id → 标签定位**单一事实源**。
 *
 * 供 [IbatisDaoStatementGotoDeclarationHandler]（Java DAO → XML 语句）与
 * `cn.song.mybatisplus.visualizer.ui.ref.IbatisXmlIdReferenceContributor`（XML 内部 id 引用）共用，
 * 避免两处各写一份标签名表与落点口径。
 *
 * ## 门控成本（性能红线）
 *
 * 热路径（reference contributor 的每个 `XmlAttributeValue`、每次 `resolve()`）**只走 O(1) 判定**：
 * [isSqlMapFile] 是一次 `rootTag?.name` 字符串比较。`xml/ibatis/IbatisXmlRecognizer.recognize(file)`
 * 内含 `SqlMapClient` 的 classpath 索引查询（`JavaPsiFacade.findClass`），**严禁**在本对象或调用方
 * 热路径上调用——iBatis 与 MyBatis 由根标签名天然互斥（`sqlMap` vs `mapper`），无需三选二识别。
 * 同文件 id 查找（[findIdAttributeInFile]）是文件内标签遍历，与 `ui/ref/MybatisXmlTargets` 同口径。
 *
 * 跨文件（`ns.id` / 裸 id）查找只发生在用户按键触发的 goto 路径（不进 daemon/索引热路径，见
 * [IbatisDaoStatementGotoDeclarationHandler] 类注释），靠**文件清单单槽 TTL 缓存** +
 * **结果级单槽 memo** 把重复实查压成查表。
 *
 * ## 缓存失效口径（全部廉价路径，不引监听器）
 *
 * 1. 项目身份不一致（`WeakReference<Project>` 比较）→ 重建；
 * 2. 文件清单 TTL（默认 5s，[fileListTtlMs]）过期 → 重扫；
 * 3. 参与上次扫描的文件某个 `isValid` 变假（被删/移动）→ 重扫；
 * 4. 清单换代（[Listing.generation] 递增）→ 结果级 memo（namespace→文件、裸 id→落点）一并失效。
 *
 * 与 `log/MapperXmlLocator`、`psi/XmlCrossNsFragmentResolver` 同款形态：**失败退化为「本次不接管」**
 * （返回空/ null），绝不抛穿。
 *
 * ## 落点口径
 *
 * 一律返回**语句 `id` 的属性值元素**（`XmlAttributeValue`）——与
 * `ui/ref/MybatisXmlTargets.findIdAttributeInFile` 一致（Ctrl+B / 引用落点都落在值上）。
 */
internal object IbatisXmlStatementTargets {

    /** iBatis 语句映射文件根标签名（`sql-map-2.dtd`）。MyBatis 3 的根是 `mapper`——两者互斥。 */
    const val SQL_MAP_ROOT_TAG: String = "sqlMap"

    /** 根标签名 == `sqlMap`（热路径唯一门控；O(1)，零索引、零识别器调用）。 */
    fun isSqlMapFile(file: PsiFile?): Boolean = (file as? XmlFile)?.rootTag?.name == SQL_MAP_ROOT_TAG

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
     * - `ns.id`：按**最后一个** `.` 切 namespace / statementId，namespace 匹配项目内 sqlMap 文件的
     *   根 `namespace` 属性（[findSqlMapFileByNamespace]）；
     * - 裸 `id`：在全部 sqlMap 文件里找语句标签（iBatis `useStatementNamespaces` 默认 false →
     *   statement id 全局唯一）。命中多文件时**与 [preferredNamespace] 相同的文件优先**，其余按
     *   清单序（多目标交平台弹选择器）。
     *
     * @param preferredNamespace 引用点所属类的限定名（无则 null；仅用于裸 id 的排序偏好）
     */
    fun resolveStatementAnchors(
        project: Project?,
        rawStatementId: String,
        preferredNamespace: String? = null,
    ): List<XmlAttributeValue> {
        val statementId = rawStatementId.trim()
        if (project == null || statementId.isEmpty()) return emptyList()
        val dot = statementId.lastIndexOf('.')
        if (dot > 0 && dot < statementId.length - 1) {
            val namespace = statementId.substring(0, dot).trim()
            val id = statementId.substring(dot + 1).trim()
            if (namespace.isEmpty() || id.isEmpty()) return emptyList()
            val file = findSqlMapFileByNamespace(project, namespace) ?: return emptyList()
            return statementAnchorsIn(file, id)
        }
        return bareStatementAnchors(project, statementId, preferredNamespace)
    }

    /**
     * 按 namespace 找 sqlMap 文件（文件清单 TTL 缓存 + 单槽结果级 memo）。
     *
     * memo 以**清单代**为失效戳（清单重建即换代），负结果一并缓存——与
     * `ui/ref/MybatisXmlTargets.findMapperFileByNamespace` 同形态，只是根标签判据换成 `sqlMap`。
     */
    fun findSqlMapFileByNamespace(project: Project, namespace: String): XmlFile? {
        val ns = namespace.trim()
        if (ns.isEmpty()) return null
        val listing = sqlMapListing(project)
        namespaceSlot?.let { slot ->
            if (slot.project.get() === project &&
                slot.listingGeneration == listing.generation &&
                slot.namespace == ns
            ) {
                return slot.file?.takeIf { it.isValid }
            }
        }
        namespaceScanCount.incrementAndGet()
        var found: XmlFile? = null
        for (vf in listing.files) {
            if (!vf.isValid) continue
            val xmlFile = PsiManager.getInstance(project).findFile(vf) as? XmlFile ?: continue
            if (xmlFile.rootTag?.getAttributeValue("namespace")?.trim() == ns) {
                found = xmlFile
                break
            }
        }
        namespaceSlot = NamespaceSlot(WeakReference(project), listing.generation, ns, found)
        return found
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** `<sqlMap>` 直接子级里的语句标签（`id` 字面值命中全部返回，含 `<selectKey>` 之外的重复 id 变体）。 */
    private fun statementAnchorsIn(file: XmlFile, id: String): List<XmlAttributeValue> {
        val root = file.rootTag ?: return emptyList()
        return root.subTags
            .filter { isStatementTag(it.name) && it.getAttributeValue("id") == id }
            .mapNotNull { it.getAttribute("id")?.valueElement }
    }

    /** 裸 statement id：全项目 sqlMap 扫一遍（结果级单槽 memo），同 namespace 的文件优先。 */
    private fun bareStatementAnchors(
        project: Project,
        id: String,
        preferredNamespace: String?,
    ): List<XmlAttributeValue> {
        val listing = sqlMapListing(project)
        val cached = statementIdSlot
        val hits: List<Pair<String?, XmlAttributeValue>> =
            if (cached != null &&
                cached.project.get() === project &&
                cached.listingGeneration == listing.generation &&
                cached.statementId == id &&
                cached.hits.all { it.second.isValid }
            ) {
                cached.hits
            } else {
                statementScanCount.incrementAndGet()
                val collected = ArrayList<Pair<String?, XmlAttributeValue>>()
                for (vf in listing.files) {
                    if (!vf.isValid) continue
                    val xmlFile = PsiManager.getInstance(project).findFile(vf) as? XmlFile ?: continue
                    val anchors = statementAnchorsIn(xmlFile, id)
                    if (anchors.isEmpty()) continue
                    val ns = xmlFile.rootTag?.getAttributeValue("namespace")?.trim()
                    for (anchor in anchors) collected.add(ns to anchor)
                }
                statementIdSlot = StatementIdSlot(WeakReference(project), listing.generation, id, collected)
                collected
            }
        if (preferredNamespace.isNullOrEmpty()) return hits.map { it.second }
        return hits.sortedBy { if (it.first == preferredNamespace) 0 else 1 }.map { it.second }
    }

    // ------------------------------------------------------------------
    // 文件清单与结果级 memo（单槽 + TTL；失效口径见类注释）
    // ------------------------------------------------------------------

    private class FileListSlot(
        val project: WeakReference<Project>,
        val files: List<VirtualFile>,
        val atMs: Long,
        val generation: Long,
    )

    private class NamespaceSlot(
        val project: WeakReference<Project>,
        val listingGeneration: Long,
        val namespace: String,
        val file: XmlFile?,
    )

    private class StatementIdSlot(
        val project: WeakReference<Project>,
        val listingGeneration: Long,
        val statementId: String,
        val hits: List<Pair<String?, XmlAttributeValue>>,
    )

    private class Listing(val files: List<VirtualFile>, val generation: Long)

    /** 清单 TTL 上界（毫秒）：新增/删除 sqlMap XML 后至多一个窗口内解析不到（过期即重扫）。 */
    internal var fileListTtlMs: Long = 5_000L

    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    /** 测试缝：文件清单实扫次数（memo/TTL 命中不计数）。 */
    internal val indexScanCount = AtomicInteger(0)

    /** 测试缝：namespace → 文件实查次数。 */
    internal val namespaceScanCount = AtomicInteger(0)

    /** 测试缝：裸 statement id 实扫次数。 */
    internal val statementScanCount = AtomicInteger(0)

    @Volatile
    private var fileListSlot: FileListSlot? = null

    @Volatile
    private var namespaceSlot: NamespaceSlot? = null

    @Volatile
    private var statementIdSlot: StatementIdSlot? = null

    private var listingGenerationCounter: Long = 0L

    /** 测试缝：清空全部缓存（用例隔离；生产链路无调用点）。 */
    internal fun clearCachesForTest() {
        fileListSlot = null
        namespaceSlot = null
        statementIdSlot = null
    }

    private fun sqlMapListing(project: Project): Listing {
        val now = clockMs()
        fileListSlot?.let { slot ->
            if (slot.project.get() === project &&
                now - slot.atMs <= fileListTtlMs &&
                slot.files.all { it.isValid }
            ) {
                return Listing(slot.files, slot.generation)
            }
        }
        indexScanCount.incrementAndGet()
        val files = runCatching {
            val xmlFileType = FileTypeManager.getInstance().getStdFileType("XML")
            FileTypeIndex.getFiles(xmlFileType, GlobalSearchScope.projectScope(project))
                .asSequence()
                .mapNotNull { vf -> PsiManager.getInstance(project).findFile(vf) as? XmlFile }
                .filter { it.rootTag?.name == SQL_MAP_ROOT_TAG }
                .mapNotNull { it.virtualFile }
                .toList()
        }.getOrDefault(emptyList())
        val generation = ++listingGenerationCounter
        fileListSlot = FileListSlot(WeakReference(project), files, now, generation)
        return Listing(files, generation)
    }
}
