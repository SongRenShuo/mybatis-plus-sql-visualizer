package cn.song.mybatisplus.visualizer.xml.ibatis

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/**
 * T-IbatisGutterPerf（2026-11-16）：iBatis 2.x `<sqlMap>` 的自定义标量索引。
 *
 * ## 为什么（对位 MyBatis 侧的 `MapperNamespaceIndex`）
 *
 * 旧链 `ui/nav/IbatisXmlStatementTargets.sqlMapListing` 的做法是：`FileTypeIndex` 取**全项目 XML**
 * （demo 根工程实测 2493 个 / 16.58 MB），再对每个文件 `PsiManager.findFile` + 取 `rootTag`
 * 才筛出 303 个 `<sqlMap>`——每 5 秒 TTL 过期就重扫一遍，且被慢趟、Ctrl+B、预览点击三条路径共用。
 * 本索引把「哪些文件是 sqlMap / 各自 namespace / 各自有哪些语句 id」在**索引期**摊平，
 * 运行时一次 `getContainingFiles` 出候选文件，失效全交平台增量（`dependsOnFileContent=true`），
 * 没有 TTL、没有「清单未就绪」窗口、没有进程级单槽被多工程互踢的问题。
 *
 * ## 索引键设计（一份文件可产多键）
 *
 * | 键 | 含义 | 消费方 |
 * |---|---|---|
 * | `all` | 「本文件是 sqlMap」的常量标记 | 裸 id 全量兜底 / 诊断 |
 * | `ns:<namespace>` | 根标签 `namespace` 属性值 | `ns.id` 形态的主查询 |
 * | `stmt:<id>` | 6 类语句标签（`IbatisStatementKind`）的 `id` 属性值 | 裸 id 形态（`useStatementNamespaces=false`，iBatis 默认） |
 *
 * 键名带前缀，故 namespace 或语句 id 字面量等于 `all` 也不会撞键。
 *
 * ## 键的提取（索引期，纯文本，不建 PSI）
 *
 * 索引期拿到的是全文（`FileContent.contentAsText`），按根标签锚 `<sqlMap` 判定是否 iBatis 文件，
 * 再在根标签内的 `namespace` 取键；语句 id 走全文扫描（iBatis 语句可在文件的任意位置）。
 * 提取内核全部是纯函数（[namespaceFromText] / [statementIdsFromText] / [keysForText]），可纯单测。
 *
 * 提取面的两条已知边界（都只有「少收」风险，而少收由 PSI 侧兜底为「本次不出图标」，不会错跳）：
 *  - `<sqlMap` 出现在长注释里会被当成根（多收，PSI 侧 `rootTag` 校验直接否掉）；
 *  - 语句 id 写在 CDATA/注释里会被多收（同上）；写在正则覆盖不到的畸形属性序里会少收。
 *
 * ## 正确性闸门在 PSI 侧
 *
 * 索引只回答「去哪几个文件里找」。`IbatisXmlStatementTargets` 拿到候选文件后仍走
 * `rootTag` + `id` 精确匹配——索引键偶有偏差只影响查得到查不到，不会跳错位置。
 *
 * ## 与 `MapperNamespaceIndex` 的关系（为什么不合并）
 *
 * 复用那个已发行索引要把它的版本从 1 抬到 2 并触发全量重建，还会把 MyBatis 与 iBatis 两个 ORM 域
 * 的失效/失败面耦在一起。新开索引各自独立 bump，代价只是 XML 文件多跑一遍文本扫描（相对整文件
 * PSI 解析可以忽略）。平台 EP `<fileBasedIndex … dynamic="true"/>`（`product.jar` 实证）非卸载屏障，
 * 热更新特性不受影响。
 */
class SqlMapNamespaceIndex : ScalarIndexExtension<String>() {

    override fun getName(): ID<String, Void> = NAME

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    /** 键提取逻辑（锚/正则/键面）变化即 bump，触发平台全量重索引。 */
    override fun getVersion(): Int = 1

    override fun getInputFilter(): DefaultFileTypeSpecificInputFilter =
        DefaultFileTypeSpecificInputFilter(XmlFileType.INSTANCE)

    override fun dependsOnFileContent(): Boolean = true

    override fun getIndexer(): DataIndexer<String, Void, FileContent> = DataIndexer { inputData ->
        indexMap(keysForText(inputData.contentAsText.toString()))
    }

    companion object {

        /** 索引 ID：带插件前缀防跨插件撞名（对位 MyBatis 侧的 `cn.song.mybatisplus.mapperNamespace`）。 */
        val NAME: ID<String, Void> = ID.create("cn.song.mybatisplus.sqlMapNs")

        /** 「本文件是 sqlMap」的常量键。 */
        const val ALL_KEY: String = "all"

        private const val NS_PREFIX = "ns:"
        private const val STMT_PREFIX = "stmt:"

        /** 根标签锚：`<sqlMap` 后必须跟空白 / `>` / `/`（`<sqlMapConfig` 不命中）。 */
        private val SQL_MAP_TAG_START = Regex("<sqlMap[\\s>/]")

        /** 配置文件根锚：`<sqlMapConfig>` 里也有 `<sqlMap resource=…>` 子元素，必须先认出来排掉。 */
        private val SQL_MAP_CONFIG_TAG_START = Regex("<sqlMapConfig[\\s>/]")

        private val NS_ATTR = Regex("""\bnamespace\s*=\s*(["'])([^"']+)\1""")

        /** 6 类语句标签的开锚（`<selectKey>` / `<statementX>` 不命中）；名字表来自 [IbatisStatementKind]。 */
        private val STATEMENT_TAG_START: Regex = run {
            val names = IbatisStatementKind.entries.joinToString("|") { it.tagName }
            Regex("<(?:$names)(?=[\\s>/])")
        }

        private val ID_ATTR = Regex("""\bid\s*=\s*(["'])([^"']+)\1""")

        /**
         * 根 `namespace` → 项目内 sqlMap 文件（索引查询，毫秒级；多模块同名全返回，调用方取第一个
         * 经 PSI 校验命中的文件）。dumb / 索引未就绪期可能抛 `IndexNotReadyException`——
         * 调用方（慢趟 provider）在 dumb 窗不查索引，并保留「正缓存 / 乐观产出」兜底。
         */
        fun getSqlMapFilesByNamespace(project: Project, namespace: String): List<VirtualFile> {
            val ns = namespace.trim()
            if (ns.isEmpty()) return emptyList()
            return query(project, NS_PREFIX + ns)
        }

        /** 语句 `id` → 声明它的 sqlMap 文件（裸 id 形态的主查询）。 */
        fun getSqlMapFilesByStatementId(project: Project, statementId: String): List<VirtualFile> {
            val id = statementId.trim()
            if (id.isEmpty()) return emptyList()
            return query(project, STMT_PREFIX + id)
        }

        /** 项目内全部 sqlMap 文件（裸 id 的清单兜底与诊断用）。 */
        fun getAllSqlMapFiles(project: Project): List<VirtualFile> = query(project, ALL_KEY)

        private fun query(project: Project, key: String): List<VirtualFile> =
            FileBasedIndex.getInstance()
                .getContainingFiles(NAME, key, GlobalSearchScope.projectScope(project))
                .toList()

        /**
         * 纯文本判定内核（可纯单测）：本文件产出的全部索引键；根标签不是 `<sqlMap>` 时返回空集。
         *
         * 语句 id 走**全文**扫描——iBatis 允许语句出现在根标签订阅之后的任意位置；namespace 取
         * 首个 `<sqlMap …>` 开标签内的属性（DTD 规定它是根标签属性）。
         */
        internal fun keysForText(text: String): Set<String> {
            val rootStart = rootTagStart(text) ?: return emptySet()
            val keys = LinkedHashSet<String>(8)
            keys.add(ALL_KEY)
            namespaceFromRootTag(text, rootStart)?.let { keys.add(NS_PREFIX + it) }
            statementIdsFromText(text).forEach { keys.add(STMT_PREFIX + it) }
            return keys
        }

        /** 首个 `<sqlMap …>` 开标签的位置；根不是 `<sqlMap>`（含 `<sqlMapConfig>` 里的子元素）→ null。 */
        internal fun rootTagStart(text: String): Int? {
            val rootStart = SQL_MAP_TAG_START.find(text)?.range?.first ?: return null
            val configStart = SQL_MAP_CONFIG_TAG_START.find(text)?.range?.first
            if (configStart != null && configStart < rootStart) return null
            return rootStart
        }

        /** 首个 `<sqlMap …>` 开标签的 `namespace` 属性值；缺失/空串 → null。 */
        internal fun namespaceFromText(text: String): String? {
            val rootStart = rootTagStart(text) ?: return null
            return namespaceFromRootTag(text, rootStart)
        }

        private fun namespaceFromRootTag(text: String, rootStart: Int): String? {
            val tagEnd = text.indexOf('>', rootStart)
            if (tagEnd < 0) return null
            return NS_ATTR.find(text.substring(rootStart, tagEnd))?.groupValues?.get(2)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

        /**
         * 全文里的 6 类语句标签 `id` 属性值（去重、保序）。
         *
         * 只看开标签内的属性（`>` 前），因此不会把标签体 SQL 里的 `id` 字样误收；跨行属性、属性序任意、
         * 单双引号都认。
         */
        internal fun statementIdsFromText(text: String): Set<String> {
            if (!text.contains("id")) return emptySet()
            val ids = LinkedHashSet<String>(8)
            for (match in STATEMENT_TAG_START.findAll(text)) {
                val tagEnd = text.indexOf('>', match.range.first)
                if (tagEnd < 0) continue
                val id = ID_ATTR.find(text.substring(match.range.first, tagEnd))?.groupValues?.get(2)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: continue
                ids.add(id)
            }
            return ids
        }

        /**
         * 键集 → 索引 value map。平台把 value 类型定为 `Void`（键即全部信息），Kotlin 侧无法直接
         * 造 `Void` 实例，只能借 `associateWith { null }` 的 `Nothing?` 值投过去（`HashMap` 允许 null 值）。
         */
        @Suppress("UNCHECKED_CAST")
        private fun indexMap(keys: Set<String>): Map<String, Void> =
            if (keys.isEmpty()) emptyMap() else keys.associateWith { null } as Map<String, Void>
    }
}