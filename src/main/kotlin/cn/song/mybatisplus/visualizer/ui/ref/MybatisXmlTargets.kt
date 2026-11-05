package cn.song.mybatisplus.visualizer.ui.ref

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import java.lang.ref.WeakReference

/**
 * T-XmlGap W7：mapper XML 内的引用解析目标集合（不注册任何 inspection/annotator——红线）。
 *
 * 解析面（计划 §六 W7 行）：`refid` → `<sql id>`、`resultMap`/`extends` → `<resultMap id>`、
 * `parameterMap` → `<parameterMap id>`、`namespace` → Java 接口、statement `id` → 接口方法、
 * `resultType`/`type`/`ofType`/`parameterType`/`javaType`/`typeHandler` → `PsiClass`、
 * `jdbcType` → `JdbcType` 枚举常量、`property` → 实体字段。
 *
 * 依赖红线：生产依赖图**零 mybatis**（build.gradle.kts 实测），故 `jdbcType` 只能按**类名**
 * 运行时解析（`JavaPsiFacade.findClass("org.apache.ibatis.type.JdbcType")`——用户工程自带
 * MyBatis 时才命中），禁止在代码里 `import org.apache.ibatis.*`。
 */
object MybatisXmlTargets {

    /** 属性值宿主的属性名（`refid` / `resultMap` …）。 */
    fun attributeName(value: XmlAttributeValue): String? = (value.parent as? XmlAttribute)?.name

    /** 属性值宿主所属标签名（select / resultMap / include …）。 */
    fun ownerTagName(value: XmlAttributeValue): String? =
        ((value.parent as? XmlAttribute)?.parent as? XmlTag)?.name

    /** 根 `<mapper>` 的 namespace（无则 null）。 */
    fun rootNamespace(file: PsiFile?): String? = (file as? XmlFile)?.rootTag?.getAttributeValue("namespace")

    /** 根标签名 == `mapper`（W7 引用的根守卫，与注入链 [XmlTag] 祖先判定同口径）。 */
    fun isMapperFile(file: PsiFile?): Boolean = (file as? XmlFile)?.rootTag?.name == "mapper"

    /** 同一文件内按 `id` 找标签的 `id` 属性值元素（跳转目标）。 */
    fun findIdAttributeInFile(file: PsiFile?, tagName: String, id: String): XmlAttributeValue? {
        val xmlFile = file as? XmlFile ?: return null
        val root = xmlFile.rootTag ?: return null
        val tags = PsiTreeUtil.findChildrenOfType(root, XmlTag::class.java)
        for (tag in tags) {
            if (tag.name == tagName && tag.getAttributeValue("id") == id) {
                return tag.getAttribute("id")?.valueElement
            }
        }
        return null
    }

    /**
     * T-MapperNavShortcut：按 namespace + 语句 id 定位**语句标签本身**（返回全部同名标签，
     * 含 `databaseId` 多方言变体）。
     *
     * 与 [findIdAttributeInFile] 的分工：那条服务 XML 内部引用（返回 `id` 属性值元素，Ctrl+B
     * 落在值上）；这条服务 Java → XML 导航（返回标签，落点由调用方决定）。
     *
     * 文件定位复用 [findMapperFileByNamespace]（单槽 namespace memo + 清单 TTL 缓存），
     * 不新建缓存、不引监听器。语句标签限定 `<mapper>` 直接子级（`subTags`），与
     * `log/MapperXmlLocator` 同口径。
     */
    fun resolveStatementTags(project: Project?, namespace: String?, id: String): List<XmlTag> {
        val name = id.trim()
        if (project == null || namespace.isNullOrEmpty() || name.isEmpty()) return emptyList()
        val file = findMapperFileByNamespace(project, namespace) ?: return emptyList()
        val root = file.rootTag ?: return emptyList()
        return root.subTags.filter { it.name in STATEMENT_TAGS && it.getAttributeValue("id") == name }
    }

    /** MyBatis 四类语句标签（`<mapper>` 直接子级；与 `XmlSqlGutterLineMarkerProvider` 同集合）。 */
    internal val STATEMENT_TAGS: Set<String> = setOf("select", "insert", "update", "delete")

    /**
     * 解析 `ns.id` / 同文件 `id` 形态的标识引用 → 目标 `id` 属性值元素。
     *
     * @param contextFile 引用所在文件（无 `ns.` 前缀时在同文件里找）
     */
    fun resolveIdReference(
        project: Project,
        contextFile: PsiFile?,
        tagName: String,
        rawRefId: String,
    ): XmlAttributeValue? {
        val refId = rawRefId.trim()
        if (refId.isEmpty()) return null
        val dot = refId.lastIndexOf('.')
        if (dot > 0) {
            val ns = refId.substring(0, dot)
            val id = refId.substring(dot + 1)
            val target = findMapperFileByNamespace(project, ns) ?: return null
            return findIdAttributeInFile(target, tagName, id)
        }
        return findIdAttributeInFile(contextFile, tagName, refId)
    }

    /**
     * 按 namespace 找 mapper XML 文件（索引 + 根元素预筛，文件清单单槽 TTL 缓存 +
     * **namespace→文件结果级 memo**，见 [namespaceSlot]）。
     *
     * F96（T-PerfSweep26）：只有文件清单缓存时，同一 namespace 的重复 resolve 每次都要
     * 逐文件 `PsiManager.findFile` + `rootTag.namespace` 比对；查找用法/重命名会批量触发，
     * 成本相乘。结果级 memo 以**文件清单代**为戳（清单重建即失效），负结果一并缓存。
     */
    fun findMapperFileByNamespace(project: Project, namespace: String): XmlFile? {
        if (namespace.isEmpty()) return null
        val listing = mapperXmlListing(project)
        namespaceSlot?.let { slot ->
            if (slot.project.get() === project &&
                slot.listingGeneration == listing.generation &&
                slot.namespace == namespace
            ) {
                return slot.file?.takeIf { it.isValid }
            }
        }
        namespaceScanCount.incrementAndGet()
        var found: XmlFile? = null
        for (vf in listing.files) {
            if (!vf.isValid) continue
            val xmlFile = PsiManager.getInstance(project).findFile(vf) as? XmlFile ?: continue
            if (xmlFile.rootTag?.getAttributeValue("namespace") == namespace) {
                found = xmlFile
                break
            }
        }
        namespaceSlot = NamespaceSlot(
            project = WeakReference(project),
            listingGeneration = listing.generation,
            namespace = namespace,
            file = found,
        )
        return found
    }

    /**
     * 解析类名（`resultType` / `parameterType` / `typeHandler` …）→ [PsiClass]。
     *
     * 三条路线：全限定名直接 findClass；短名先走 namespace 接口所在文件的 import 表；再退到
     * 短名索引（dumb 态索引不可用 → 返回 null，不报红——引用本身是 soft）。
     */
    fun resolveClass(project: Project?, context: XmlAttributeValue, rawName: String): PsiClass? {
        val name = rawName.trim().substringBefore('<').substringBefore('[')
        if (project == null || name.isEmpty()) return null
        classResolveCount.incrementAndGet()
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        val shortName = name.substringAfterLast('.')

        if (name.contains('.')) {
            facade.findClass(name, scope)?.let { return it }
        }
        // namespace 接口所在文件的 import 表（短名/别名最常见来源）
        val ns = rootNamespace(context.containingFile)
        if (ns != null) {
            val nsClass = facade.findClass(ns, scope)
            val nsFile = nsClass?.containingFile
            if (nsFile is com.intellij.psi.PsiJavaFile) {
                for (imp in nsFile.importList?.allImportStatements.orEmpty()) {
                    // 新平台 API：qualifiedName 已由 importReference 承担（PsiImportStatement 同名方法已移除）
                    val qn = imp.importReference?.qualifiedName ?: continue
                    if (qn.substringAfterLast('.') == shortName) {
                        facade.findClass(qn, scope)?.let { return it }
                    }
                }
            }
            // 同包（namespace 接口包内）
            val pkg = nsClass?.qualifiedName?.substringBeforeLast('.', "")
            if (!pkg.isNullOrEmpty()) {
                facade.findClass("$pkg.$shortName", scope)?.let { return it }
            }
        }
        if (DumbService.isDumb(project)) return null
        return PsiShortNamesCache.getInstance(project)
            .getClassesByName(shortName, scope)
            .firstOrNull()
    }

    /** `jdbcType="VARCHAR"` → `org.apache.ibatis.type.JdbcType` 的枚举常量字段。 */
    fun resolveJdbcTypeField(project: Project?, rawName: String): PsiField? {
        val name = rawName.trim()
        if (project == null || name.isEmpty()) return null
        val enumClass = JavaPsiFacade.getInstance(project)
            .findClass(JDBC_TYPE_FQN, GlobalSearchScope.allScope(project)) ?: return null
        return enumClass.findFieldByName(name, false)
    }

    /** statement `id` → namespace 接口的同名方法。 */
    fun resolveInterfaceMethod(project: Project?, namespace: String?, methodName: String): PsiMethod? {
        val name = methodName.trim()
        if (project == null || namespace.isNullOrEmpty() || name.isEmpty()) return null
        val iface = JavaPsiFacade.getInstance(project)
            .findClass(namespace, GlobalSearchScope.allScope(project)) ?: return null
        return iface.findMethodsByName(name, true).firstOrNull()
    }

    /** `property="a.b"` / `<arg property>` 的**首段**字段解析（深层链后续可扩展）。 */
    fun resolvePropertyField(project: Project?, holderClass: PsiClass?, rawProperty: String): PsiField? {
        if (project == null || holderClass == null) return null
        val first = rawProperty.trim().substringBefore('.').substringBefore('[')
        if (first.isEmpty()) return null
        var cls: PsiClass? = holderClass
        var depth = 0
        while (cls != null && depth++ < MAX_PROPERTY_DEPTH) {
            cls.findFieldByName(first, true)?.let { return it }
            cls = cls.superClass
        }
        return null
    }

    /** 属性链解析：逐段下钻，返回末段字段（能解析多少算多少）。 */
    fun resolvePropertyChain(project: Project?, holderClass: PsiClass?, rawProperty: String): PsiField? {
        if (project == null || holderClass == null) return null
        val segments = rawProperty.trim().split('.').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        var cls: PsiClass? = holderClass
        var last: PsiField? = null
        for (seg in segments) {
            val field = resolveFieldInHierarchy(cls, seg.substringBefore('[')) ?: return if (last != null) last else null
            last = field
            cls = (field.type as? com.intellij.psi.PsiClassType)?.resolve()
        }
        return last
    }

    private fun resolveFieldInHierarchy(start: PsiClass?, name: String): PsiField? {
        var cls = start
        var depth = 0
        while (cls != null && depth++ < MAX_PROPERTY_DEPTH) {
            cls.findFieldByName(name, true)?.let { return it }
            cls = cls.superClass
        }
        return null
    }

    private const val JDBC_TYPE_FQN = "org.apache.ibatis.type.JdbcType"
    private const val MAX_PROPERTY_DEPTH = 16

    // ------------------------------------------------------------------
    // mapper XML 文件清单（单槽 TTL 缓存；模式同 log/MapperXmlLocator，禁引监听器）
    // ------------------------------------------------------------------

    private class FileListSlot(
        val project: WeakReference<Project>,
        val files: List<VirtualFile>,
        val atMs: Long,
        val generation: Long,
    )

    /**
     * F96（T-PerfSweep26）：namespace → mapper 文件的**单槽结果级 memo**。
     *
     * 失效戳=文件清单代 [FileListSlot.generation]（清单重建即换代，含新增/删除 mapper XML 后
     * TTL 过期重扫）；文件对象自身 invalid（被删/移动）时回落重查。单槽容量=最近一个 namespace
     * ——引用解析天然按 refid 顺序访问、局部性强，且槽内只持一个 XmlFile 强引用（PSI 强引用
     * 生命周期与项目一致，不额外钉住文件）。
     */
    private class NamespaceSlot(
        val project: WeakReference<Project>,
        val listingGeneration: Long,
        val namespace: String,
        val file: XmlFile?,
    )

    /** TTL 上界（毫秒）：新增 mapper XML 后至多一个窗口内解析不到（过期即重扫）。 */
    internal var fileListTtlMs: Long = 5_000L

    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    internal val indexScanCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** F96 测试缝：namespace→文件实查次数（memo 命中不计数）。 */
    internal val namespaceScanCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** F97 测试缝：`resolveClass` 实际执行次数（批内 memo 命中不计数）。 */
    internal val classResolveCount = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var fileListSlot: FileListSlot? = null

    @Volatile
    private var namespaceSlot: NamespaceSlot? = null

    /** 文件清单 + 其代（代在每次重扫时递增；namespace memo 以此为失效戳）。 */
    private class Listing(val files: List<VirtualFile>, val generation: Long)

    private var listingGenerationCounter: Long = 0L

    internal fun clearFileListCacheForTest() {
        fileListSlot = null
        namespaceSlot = null
    }

    private fun mapperXmlListing(project: Project): Listing {
        val now = clockMs()
        fileListSlot?.let { slot ->
            if (slot.project.get() === project && now - slot.atMs <= fileListTtlMs && slot.files.all { it.isValid }) {
                return Listing(slot.files, slot.generation)
            }
        }
        indexScanCount.incrementAndGet()
        val files = runCatching {
            val xmlFileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getStdFileType("XML")
            FileTypeIndex.getFiles(xmlFileType, GlobalSearchScope.projectScope(project))
                .asSequence()
                .mapNotNull { vf -> PsiManager.getInstance(project).findFile(vf) as? XmlFile }
                .filter { it.rootTag?.name == "mapper" }
                .mapNotNull { it.virtualFile }
                .toList()
        }.getOrDefault(emptyList())
        val generation = ++listingGenerationCounter
        fileListSlot = FileListSlot(WeakReference(project), files, now, generation)
        return Listing(files, generation)
    }

    /** 测试缝：`PsiElement` 归属的 mapper 文件（供用例断言解析落点）。 */
    internal fun ownerFileOf(element: PsiElement): PsiFile? = element.containingFile
}
