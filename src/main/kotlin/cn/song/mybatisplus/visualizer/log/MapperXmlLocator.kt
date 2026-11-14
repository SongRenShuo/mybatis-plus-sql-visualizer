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

    /** 读上下文内的定位主体（清单槽可用性 → memo → 逐文件 namespace/id 校验 → 行号换算）。 */
    private fun locateUnderRead(project: Project, namespace: String, id: String): Location? {
        // F1''（T-GutterFirstOpenDelay 续刀，2026-11-15）：**清单构建移出慢趟关键路径**。
        // 真机实证（demo 根工程，scope≈4841 XML）：慢趟首跑在本 ReadAction 里逐个 PSI 解析
        // 全项目 XML 筛 `<mapper>` 根元素，JPS 持续写盘时反复被 PCE 取消 → 慢趟饿死 →
        // Java→XML 图标长期缺席（cust-big-demo 单工程 ~2100 XML 尚可忍受，根工程翻倍即失守）。
        // 新语义：
        //  - 无可用槽 → 只踢后台异步构建 + 立即返回 null，**不写 MISS**（「还没查到」不得固化为「真没有」）；
        //    构建落地后一次性 restart daemon 让慢趟重跑（missedWhilePending 只在异步路径置位）；
        //  - 有槽但 TTL 过期 → **服务旧值** + 后台刷新（stale-while-revalidate：旧列表对本就存在的
        //    文件依然正确，图标不因刷新而缺席；新增文件至多滞后一个刷新周期）；
        //  - 单测模式/测试缝强制同步 = 旧同步语义原样保留（既有 13 例零改动）。
        var slot = currentSlotIfUsable(project)
        if (slot == null) {
            if (useSyncBranch()) {
                rebuildSlotSync(project)
                slot = fileListSlot?.takeIf { it.project.get() === project } ?: return null
            } else {
                missedWhilePending.set(true)
                kickAsyncListBuild(project)
                return null
            }
        } else if (clockMs() - slot.atMs > fileListTtlMs) {
            if (useSyncBranch()) {
                rebuildSlotSync(project)
                slot = fileListSlot?.takeIf { it.project.get() === project } ?: return null
            } else {
                kickAsyncListBuild(project) // serve stale：本轮继续用旧槽
            }
        }
        val memoKey = namespace to id
        slot.memo[memoKey]?.let { cached -> return cached as? Location }
        var first: Pair<XmlFile, XmlTag>? = null
        var extraHits = 0
        for (desc in slot.files) {
            // F1''：namespace 在清单构建期已提取——只对命中的 1~2 个候选付 PSI（全量 PSI 已死）
            if (desc.namespace != namespace) continue
            statementScanCount.incrementAndGet()
            val file = PsiManager.getInstance(project).findFile(desc.vf) as? XmlFile ?: continue
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
        // 负结果以 MISS 哨兵承载（ConcurrentHashMap 不接受 null 值）——只在**有可用槽**时才负缓存
        slot.memo[memoKey] = location ?: MISS
        return location
    }

    /**
     * T-PS5-05：mapper XML 文件列表单槽缓存条目（项目弱引用 + 文件描述符列表 + 时间戳 +
     * 券01 语句定位结果 memo）。
     *
     * F1''（T-GutterFirstOpenDelay 续刀）：列表元素从裸 VirtualFile 升级为 [MapperFileDesc]——
     * namespace 在清单构建期用**原始头扫**提取（不 PSI），慢趟定位只对 namespace 命中的
     * 1~2 个候选做 PSI（此前 4841 个 XML 全量 PSI 解析，根工程首扫数十秒且被 JPS 写盘
     * 反复取消）。
     *
     * memo 与清单**同生共死**：条目每次换代即新建空 memo（对照 MybatisXmlTargets F96
     * namespaceSlot 的 listingGeneration 同代失效先例）。memo 键为 `namespace to id`，
     * 值 [Location] 或 [MISS] 哨兵（负结果一并缓存——非 Mapper 方法必然落空，修前每趟重付全程）。
     */
    private class FileListSlot(
        val project: java.lang.ref.WeakReference<Project>,
        val files: List<MapperFileDesc>,
        val atMs: Long,
    ) {
        val memo = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Any>()
    }

    /** 清单元素：mapper XML 文件 + 构建期提取的根标签 namespace（原始头扫，非 PSI）。 */
    private class MapperFileDesc(val vf: VirtualFile, val namespace: String)

    /**
     * T-PS5-05：缓存 TTL 上界（毫秒）。新增 mapper XML 后至多一个窗口内跳转仍按旧列表
     * （删除由 [VirtualFile.isValid] 即时兜底：任一缓存文件失效即刻重扫）。
     * 测试注入短窗驱动过期语义。F1''：5s→30s——首扫便宜化（头扫+描述符缓存）后避免高频
     * 重建；新增文件至多滞后 30s（serve stale 期间图标不缺席）。
     */
    internal var fileListTtlMs: Long = 30_000L

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
        missedWhilePending.set(false)
        asyncBuildRunning.set(false)
    }

    /** 测试缝：清单构建模式（null=按环境判定：单测=同步保旧语义；生产=异步）。 */
    internal var asyncListBuildForTest: Boolean? = null

    /** 测试缝：清单落地后的 daemon 重启捕获（生产=`DaemonCodeAnalyzer.restart`）。 */
    internal var restartAnalyzerForTest: ((Project) -> Unit)? = null

    /** 异步构建在途旗标（按对象级——清单槽本就是全局单槽）。 */
    private val asyncBuildRunning = java.util.concurrent.atomic.AtomicBoolean()

    /** 「清单未就绪期间有 locate 落空」标记——构建落地后据此一次性重启 daemon。 */
    private val missedWhilePending = java.util.concurrent.atomic.AtomicBoolean()

    /** 本轮 locate 走同步分支还是异步语义：缝 true=异步、false=同步；null=按环境（单测=同步保旧语义）。
     *  注意与 [kickAsyncListBuild] 的内联执行判据分离——缝非 null 时语义按缝、但构建内联执行保确定性。 */
    private fun useSyncBranch(): Boolean =
        asyncListBuildForTest?.let { !it } ?: runCatching {
            com.intellij.openapi.application.ApplicationManager.getApplication()?.isUnitTestMode == true
        }.getOrDefault(true)

    /** 无可用槽/TTL 过期时的后台刷新（幂等：在途即返）；生产走 nonBlocking 读 + 可取消。
     *  测试缝（[asyncListBuildForTest] 非 null）时**内联执行**构建+重启通知（用例确定性）。 */
    private fun kickAsyncListBuild(project: Project) {
        if (!asyncBuildRunning.compareAndSet(false, true)) return
        if (asyncListBuildForTest != null || useSyncBranch()) {
            // 测试缝/单测强制同步：落地 + 按需重启，一次调用内完成（行为确定性）
            runCatching { rebuildSlotSync(project) }
            asyncBuildRunning.set(false)
            fireRestartIfMissed(project)
            return
        }
        runCatching {
            com.intellij.openapi.application.ReadAction.nonBlocking<Unit> {
                runCatching { rebuildSlotSync(project) }
                Unit
            }.inSmartMode(project)
                .expireWith(project)
                .coalesceBy(this, "mapperXmlList:${project.locationHash}")
                .submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
                .onSuccess {
                    asyncBuildRunning.set(false)
                    fireRestartIfMissed(project)
                }
                .onError { t ->
                    asyncBuildRunning.set(false)
                    if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) throw t
                }
        }.onFailure { t ->
            // 调度失败退化为同步构建：清单是必要的，失败不该把它丢掉
            asyncBuildRunning.set(false)
            LOG.info("MapperXmlLocator: 异步清单构建调度失败 ${t.javaClass.simpleName}，退化为同步")
            runCatching { rebuildSlotSync(project) }
            fireRestartIfMissed(project)
        }
    }

    /** 清单落地后的重跑通知：pending 期有 locate 落空过才重启（一次性，防自激环）。 */
    private fun fireRestartIfMissed(project: Project) {
        if (!missedWhilePending.getAndSet(false)) return
        restartAnalyzerForTest?.invoke(project) ?: runCatching {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(
                Runnable {
                    if (!project.isDisposed) {
                        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
                    }
                },
                com.intellij.openapi.application.ModalityState.any(),
            )
        }
    }

    /** 清单构建主体（同步调用点=单测缝/异步落地回调）：头扫提取 namespace + 描述符跨代缓存。 */
    private fun rebuildSlotSync(project: Project) {
        val now = clockMs()
        indexScanCount.incrementAndGet()
        val files = runCatching {
            val xmlFileType = FileTypeManager.getInstance().getStdFileType("XML")
            val candidates: Sequence<MapperFileDesc?> = FileTypeIndex.getFiles(xmlFileType, GlobalSearchScope.projectScope(project))
                .asSequence()
                .mapNotNull { vf ->
                    // F1''：namespace 头扫提取（不 PSI）。曾试过 path+stamp+length 描述符跨代缓存，
                    // 但 LightVirtualFile（内存 fixture/部分 VFS 形态）path 固定、stamp 不可靠，
                    // 跨清单代缓存会毒化——砍掉：TTL 30s 下重建频率低，头扫本身 2~3s 可接受。
                    val ns: String? = namespaceFromHead(vf)
                        // ORM 兼容兜底：头扫窗（16KB）内没看到 `<mapper` 的文件不直接判出局——
                        // 文件名含 Mapper/sqlmap 的（MBG/各代 ORM 生成物的命名惯例）回退一次
                        // PSI 解析取根标签 namespace，保证「根标签在头扫窗之外」的极端文件
                        // 不因头扫窗收窄而丢图标（旧 PSI 全量口径的能力面）。
                        ?: runCatching {
                            if (!looksLikeMapperFile(vf.name)) return@runCatching null
                            (PsiManager.getInstance(project).findFile(vf) as? XmlFile)
                                ?.rootTag?.let { root ->
                                    if (root.name == "mapper") root.getAttributeValue("namespace") else null
                                }?.takeIf { it.isNotBlank() }
                        }.getOrNull()
                    ns?.takeIf { it.isNotEmpty() }?.let { MapperFileDesc(vf, it) }
                }
            candidates.filterNotNull().toList()
        }.getOrDefault(emptyList())
        fileListSlot = FileListSlot(java.lang.ref.WeakReference(project), files, now)
    }

    /** 文件名形态兜底判据：MBG/各代 ORM 生成物的命名惯例（XxxMapper.xml / sqlmap*.xml）。 */
    private fun looksLikeMapperFile(name: String): Boolean =
        name.contains("Mapper", ignoreCase = true) || name.contains("sqlmap", ignoreCase = true)

    /** 头扫字节数：mapper 根标签必在文件头部（XML 声明 + DOCTYPE 之后）。 */
    private const val HEAD_SCAN_BYTES = 16 * 1024

    /** `<mapper` 根标签开锚（`<mappers`/`<mapperX` 不命中）；属性序列任意，namespace 双/单引号都认。 */
    private val MAPPER_TAG_START = Regex("<mapper[\\s>/]")

    private val NS_ATTR = Regex("""namespace\s*=\s*(["'])([^"']+)\1""")

    /** VFS 原始头扫：不 PSI——读文件头 [HEAD_SCAN_BYTES] 字节，`<mapper …>` 开标签内提取 namespace。 */
    private fun namespaceFromHead(vf: VirtualFile): String? = try {
        vf.inputStream.use { ins ->
            val buf = ByteArray(HEAD_SCAN_BYTES)
            val n = ins.readNBytes(buf, 0, buf.size)
            val head = String(buf, 0, n, Charsets.UTF_8)
            namespaceFromHeadText(head)
        }
    } catch (t: Throwable) {
        if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) throw t
        null
    }

    /** 纯文本判定内核（可纯单测）：`<mapper …>` 开标签内的 namespace 属性值；无/空 → null。 */
    internal fun namespaceFromHeadText(head: String): String? {
        val tagStart = MAPPER_TAG_START.find(head) ?: return null
        val tagEnd = head.indexOf('>', tagStart.range.first)
        if (tagEnd < 0) return null
        return NS_ATTR.find(head.substring(tagStart.range.first, tagEnd))?.groupValues?.get(2)
            ?.takeIf { it.isNotBlank() }
    }

    /** 当前可用槽（项目匹配 + 文件全部有效；**不含 TTL 判定**——过期槽 serve stale 由调用方决定）。 */
    private fun currentSlotIfUsable(project: Project): FileListSlot? {
        fileListSlot?.let { slot ->
            if (slot.project.get() === project && slot.files.all { it.vf.isValid }) return slot
        }
        return null
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
