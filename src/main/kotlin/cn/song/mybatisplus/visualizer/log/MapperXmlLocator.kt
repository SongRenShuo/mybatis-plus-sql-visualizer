package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * T-M55-01 跳转双目标：mapper 语句 id → 项目内 mapper XML 语句节点的定位器。
 *
 * ## 定位链（F1''' 起走平台索引，2026-11-15 精华采纳刀）
 *
 * 拆 `namespace.id` → [MapperNamespaceIndex.getMapperXmlFiles]（自定义 fileBasedIndex，
 * namespace 在索引期已摊平，查询毫秒级、失效全交平台增量）→ PSI 校验 `<mapper namespace>`
 * 匹配后定位同 id 的语句标签（select/insert/update/delete，直接子级——沿
 * ui/gutter/XmlSqlGutterLineMarkerProvider.kt 范式），换算语句起始行号供
 * [OpenFileDescriptor] 直跳。
 *
 * 历史（三轮演进，均见 `docs/开发计划/20261114-2131-T-GutterFirstOpenDelay-*.md`）：
 *  - T-PS5-05：FileTypeIndex 全扫 + 单槽 TTL 缓存 + 语句 memo（当时修「每跳全扫」）；
 *  - F1''：清单构建异步化 + serve stale + 头扫化（修「慢趟被大工程首扫+JPS 写盘饿死」）；
 *  - F1'''：MPJ 精华采纳——自定义索引替换整套清单状态机（头扫挪进索引器、TTL/异步/重跑
 *    机制整体退役；「清单未就绪」窗口由平台增量管理，provider 层 F1'/F1-b 失败分支兜底）。
 *
 * 已知边界（首批记录，不弹选择器）：多模块同名 mapper（同 namespace 同 id）取索引序第一个并
 * LOG.info 留痕。MP 内置注入语句（Wrapper CRUD）XML 通常不存在 → locate 返回 null，
 * 调用方回退 Java 跳转，无劣化。dumb/索引未就绪期可能返回 null——provider 层 F1' 失败分支
 * （正缓存/dumb 乐观产出）兜底，「还没查到」不会固化为「真没有」。
 *
 * 线程约定：[locate] 自带 [ReadAction] 包裹，EDT（右键菜单）与后台线程均可直调；
 * FileBasedIndex 查询 + PSI 只读，无写操作。
 */
object MapperXmlLocator {

    private val LOG = Logger.getInstance(MapperXmlLocator::class.java)

    /** 定位三态（T-MapperGutterEvict P2.1 同构，2026-11-16）：确定命中 / 确定没有 / 暂时查不了。 */
    sealed interface Lookup {
        class Found(val location: Location) : Lookup
        object Absent : Lookup
        object Unknown : Lookup
    }

    /** 测试缝：替换三态定位（生产＝null）。矩阵用例用它驱动 Unknown / Absent 分支。 */
    internal var lookupOverrideForTest: ((Project, String) -> Lookup)? = null

    /**
     * 三态定位——供 gutter provider 区分「暂时查不了」与「确定没有」：
     * 前者保留已确认的图标（F1' 的 S2 兜底），后者**收回正缓存并摘图标**（P2.1）。
     * `IndexNotReadyException`（索引重建中）归 [Lookup.Unknown]；`ProcessCanceledException` 仍上抛。
     */
    fun locateOrUnknown(project: Project, statementId: String): Lookup {
        lookupOverrideForTest?.let { override -> return override(project, statementId) }
        val location = try {
            locate(project, statementId)
        } catch (e: IndexNotReadyException) {
            return Lookup.Unknown
        }
        return if (location != null) Lookup.Found(location) else Lookup.Absent
    }

    /** 定位结果：mapper XML 文件 + 语句标签起始行（0-based，OpenFileDescriptor 直用）。 */
    data class Location(val virtualFile: VirtualFile, val line: Int)

    /**
     * 「语句存在」三态（T-MapperPreviewOrder，2026-11-26）：给预览 provider 用——只要
     * 「有没有同名语句」，不需要行号，因此**不建 document**（[locate] 里的
     * `PsiDocumentManager.getDocument` 在慢趟每方法一次是重活；预览与跳转拆成两个 provider
     * 后，预览这边只付索引 + PSI 子树查找）。
     *
     * 语义与 [locateOrUnknown] 同一套（Found / Absent / Unknown），测试缝共用；
     * Found 的 [Location.line] 填 0（预览侧不读行号）。
     */
    fun existsOrUnknown(project: Project, statementId: String): Lookup {
        lookupOverrideForTest?.let { override -> return override(project, statementId) }
        val (namespace, id) = splitStatementId(statementId) ?: return Lookup.Absent
        return try {
            ReadAction.compute<Lookup, RuntimeException> {
                for (vf in MapperNamespaceIndex.getMapperXmlFiles(project, namespace)) {
                    val file = PsiManager.getInstance(project).findFile(vf) as? XmlFile ?: continue
                    if (findStatementTag(file, namespace, id) != null) return@compute Lookup.Found(Location(vf, 0))
                }
                Lookup.Absent
            }
        } catch (e: IndexNotReadyException) {
            Lookup.Unknown
        }
    }

    /**
     * 定位语句在 mapper XML 中的位置；任一环失败返回 null（调用方回退 Java 跳转）。
     * @param statementId 完整语句 id（`namespace.id`，如 `com.example.mapper.UserMapper.selectById`）
     */
    fun locate(project: Project, statementId: String): Location? {
        val (namespace, id) = splitStatementId(statementId) ?: return null
        return ReadAction.compute<Location?, RuntimeException> {
            val candidates = MapperNamespaceIndex.getMapperXmlFiles(project, namespace)
            var first: Pair<XmlFile, XmlTag>? = null
            var extraHits = 0
            for (vf in candidates) {
                val file = PsiManager.getInstance(project).findFile(vf) as? XmlFile ?: continue
                val tag = findStatementTag(file, namespace, id) ?: continue
                val hit = file to tag
                if (first == null) first = hit else extraHits++
            }
            if (extraHits > 0) {
                LOG.info("MapperXmlLocator: 语句 $namespace.$id 命中 ${extraHits + 1} 个 mapper XML（多模块同名），取第一个：${first?.first?.virtualFile?.path}")
            }
            first?.let { (file, tag) ->
                val vf = file.virtualFile ?: return@let null
                val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return@let null
                Location(vf, document.getLineNumber(tag.textRange.startOffset))
            }
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

    /**
     * 单文件内定位语句标签：namespace 属性精确匹配（[splitStatementId] 的 namespace 段），
     * 语句标签（[STATEMENT_TAGS] 同款集合）为 `<mapper>` 直接子级且 id 相等。
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
