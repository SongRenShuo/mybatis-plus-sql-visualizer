package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * T-M55-01 跳转双目标：mapper 语句 id → 项目内 mapper XML 语句节点的定位器。
 *
 * 条目 [LogSqlEntry.mapperStatement]（拦截器 `ms.getId()`，形如 `com.example.mapper.UserMapper.selectById`）
 * 拆 namespace + id 后，经 [FileTypeIndex] 按文件类型扫描项目内 XML（仿 psi/jpa/HbmXmlEntityResolver.kt
 * 的 FileTypeIndex 先例），PSI 校验 `<mapper namespace>` 匹配后定位同 id 的语句标签
 * （select/insert/update/delete，直接子级——沿 ui/gutter/XmlSqlGutterLineMarkerProvider.kt 范式），
 * 换算语句起始行号供 [com.intellij.openapi.fileEditor.OpenFileDescriptor] 直跳。
 *
 * 已知边界（首批记录，不弹选择器）：多模块同名 mapper（同 namespace 同 id）取索引序第一个并 LOG.info 留痕。
 * MP 内置注入语句（Wrapper CRUD）XML 通常不存在 → locate 返回 null，调用方回退 Java 跳转，无劣化。
 *
 * 线程约定：[locate] 自带 [ReadAction] 包裹，EDT（右键菜单）与后台线程均可直调；
 * 内部为索引查询 + PSI 只读，无写操作。
 */
object MapperXmlLocator {

    private val LOG = Logger.getInstance(MapperXmlLocator::class.java)

    /** 定位结果：mapper XML 文件 + 语句标签起始行（0-based，OpenFileDescriptor 直用）。 */
    data class Location(val virtualFile: VirtualFile, val line: Int)

    /**
     * 定位语句在 mapper XML 中的位置；任一环失败返回 null（调用方回退 Java 跳转）。
     * @param statementId 完整语句 id（`namespace.id`，如 `com.example.mapper.UserMapper.selectById`）
     */
    fun locate(project: Project, statementId: String): Location? {
        val (namespace, id) = splitStatementId(statementId) ?: return null
        return ReadAction.compute<Location?, RuntimeException> {
            locateUnderRead(project, namespace, id)
        }
    }

    /** 拆 `namespace.id`（按最后一个 `.` 分段——namespace 本身含点，语句 id 惯例不含）；形态不符返回 null。 */
    internal fun splitStatementId(statementId: String): Pair<String, String>? {
        val s = statementId.trim()
        if (s.isEmpty()) return null
        val idx = s.lastIndexOf('.')
        if (idx <= 0 || idx == s.length - 1) return null
        return s.substring(0, idx) to s.substring(idx + 1)
    }

    /** 读上下文内的定位主体（清单槽新鲜度 → memo → 逐文件 namespace/id 校验 → 行号换算）。 */
    private fun locateUnderRead(project: Project, namespace: String, id: String): Location? {
        // 先取**新鲜**清单槽：TTL 过期 / 任一文件失效 / 项目不匹配都会换代（既有 T-PS5-05 语义）。
        // memo 挂在槽实例上，换代即整体失效——命中路径与清单新鲜度严格同代，不会让过期结果
        // 跳过重扫（既有 TTL 用例钉死该语义）。
        val slot = currentFileListSlot(project)
        val memoKey = namespace to id
        slot.memo[memoKey]?.let { cached -> return cached as? Location }
        var first: Pair<XmlFile, XmlTag>? = null
        var extraHits = 0
        for (vf in slot.files) {
            statementScanCount.incrementAndGet()
            val file = PsiManager.getInstance(project).findFile(vf) as? XmlFile ?: continue
            val tag = findStatementTag(file, namespace, id) ?: continue
            val hit = file to tag
            if (first == null) first = hit else extraHits++
        }
        if (extraHits > 0) {
            LOG.info("MapperXmlLocator: 语句 $namespace.$id 命中 ${extraHits + 1} 个 mapper XML（多模块同名），取第一个：${first?.first?.virtualFile?.path}")
        }
        val location = first?.let { (file, tag) ->
            val vf = file.virtualFile ?: return@let null
            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return@let null
            Location(vf, document.getLineNumber(tag.textRange.startOffset))
        }
        // 负结果以 MISS 哨兵承载（ConcurrentHashMap 不接受 null 值）
        slot.memo[memoKey] = location ?: MISS
        return location
    }

    /**
     * T-PS5-05：mapper XML 文件列表单槽缓存条目（项目弱引用 + 文件列表 + 时间戳 +
     * 券01 语句定位结果 memo）。
     *
     * memo 与清单**同生共死**：条目每次换代即新建空 memo（对照 MybatisXmlTargets F96
     * namespaceSlot 的 listingGeneration 同代失效先例）。memo 键为 `namespace to id`，
     * 值 [Location] 或 [MISS] 哨兵（负结果一并缓存——非 Mapper 方法必然落空，修前每趟重付全程）。
     */
    private class FileListSlot(
        val project: java.lang.ref.WeakReference<Project>,
        val files: List<VirtualFile>,
        val atMs: Long,
    ) {
        val memo = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Any>()
    }

    /**
     * T-PS5-05：缓存 TTL 上界（毫秒）。新增 mapper XML 后至多一个窗口内跳转仍按旧列表
     * （删除由 [VirtualFile.isValid] 即时兜底：任一缓存文件失效即刻重扫）。
     * 测试注入短窗驱动过期语义。
     */
    internal var fileListTtlMs: Long = 5_000L

    /** T-PS5-05 测试缝：时间源（默认系统时钟）。 */
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    /** T-PS5-05 测试缝：索引全扫计数（断言命中路径零遍历）。 */
    internal val indexScanCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 券01（T-PerfSweep28-01）测试缝：语句定位的逐文件根标签访问计数（断言 memo 命中零遍历）。 */
    internal val statementScanCount = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var fileListSlot: FileListSlot? = null

    /** memo 负结果哨兵（`namespace.id` 在当前清单代内不存在对应语句）。 */
    private val MISS = Any()

    /** T-PS5-05 测试缝：清空文件列表缓存（用例隔离；生产链路无调用点）。 */
    internal fun clearFileListCacheForTest() {
        fileListSlot = null
    }

    /**
     * T-PS5-05：mapper XML 的 VirtualFile 列表（索引 + PSI 根元素预筛）——**单槽缓存**。
     *
     * 为什么要缓存：大项目（数千 XML）每次跳转都全项目扫描索引 + 逐个 PSI 解析根元素判
     * `<mapper>`，首跳百毫秒级且发生在 EDT ReadAction 内。缓存只存**已筛出的 mapper XML**
     * （VirtualFile 而非 PsiFile：PSI 可能被平台失效，取用前统一 [VirtualFile.isValid] 校验）。
     *
     * 失效纪律（**禁引 PsiTreeChangeListener/EditorFactoryListener**——红线族维持 0）：
     *  - TTL 上界（默认 5s，新增文件最多滞后一个窗口）；
     *  - 取用时 `isValid` 全检，任一失效（删除/改名）即重扫一次；
     *  - 项目身份不匹配（多项目同开）不命中，重扫。
     *
     * 单槽 + 项目弱引用（不挂项目 Disposable、不加监听器）：无跨项目泄漏，项目关闭后条目随
     * GC 回收；多项目交替访问退化为重扫（与无缓存等价，无正确性风险）。
     */
    private fun currentFileListSlot(project: Project): FileListSlot {
        val now = clockMs()
        fileListSlot?.let { slot ->
            if (slot.project.get() === project &&
                now - slot.atMs <= fileListTtlMs &&
                slot.files.all { it.isValid }
            ) {
                return slot
            }
        }
        indexScanCount.incrementAndGet()
        val files = runCatching {
            val xmlFileType = FileTypeManager.getInstance().getStdFileType("XML")
            FileTypeIndex.getFiles(xmlFileType, GlobalSearchScope.projectScope(project))
                .asSequence()
                .mapNotNull { vf -> PsiManager.getInstance(project).findFile(vf) as? XmlFile }
                .filter { it.rootTag?.name == "mapper" }
                .mapNotNull { it.virtualFile }
                .toList()
        }.getOrDefault(emptyList())
        val slot = FileListSlot(java.lang.ref.WeakReference(project), files, now)
        fileListSlot = slot
        return slot
    }

    /**
     * 单文件内定位语句标签：namespace 属性精确匹配（[splitStatementId] 的 namespace 段），
     * 语句标签（[XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS] 同款集合）为 `<mapper>` 直接子级且 id 相等。
     */
    private fun findStatementTag(file: XmlFile, namespace: String, id: String): XmlTag? {
        val root = file.rootTag ?: return null
        if (root.getAttributeValue("namespace") != namespace) return null
        return root.subTags.firstOrNull {
            it.name in STATEMENT_TAGS && it.getAttributeValue("id") == id
        }
    }

    private val STATEMENT_TAGS = setOf("select", "insert", "update", "delete")
}
