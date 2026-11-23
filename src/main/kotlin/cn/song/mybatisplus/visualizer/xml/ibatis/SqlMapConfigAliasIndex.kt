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
 * T-PerfSweep31 券01（2026-11-17）：iBatis `<sqlMapConfig>` 的 `<typeAlias>` 自定义标量索引。
 *
 * ## 为什么（对位 MyBatis 侧 MapperNamespaceIndex / iBatis 侧 SqlMapNamespaceIndex）
 *
 * 旧链 `ui/IbatisTypeAliasResolver`（v1）的做法是：首次调用对**语句所在模块的内容根**做
 * `VfsUtilCore.iterateChildrenRecursively` 全走查，逐个 XML `PsiManager.findFile` 建 PSI 后筛
 * 根标签 `sqlMapConfig`、提取 `<typeAlias>`——跑在预览点击链的 `ReadAction.compute`（池线程持
 * 读锁）内：大模块首次点击付出「整模块 XML 全部进 PSI」的代价，且持读锁期间平台写动作排队
 * （性能分析第八期 §4.3，P2 主菜）。另有会话级缓存——config 改动要重启会话才生效。
 *
 * 本索引把「哪些文件是 sqlMapConfig / 各自声明了哪些别名→FQN」在**索引期**摊平：运行时一次
 * `getContainingFiles` 出候选文件，失效全交平台增量（`dependsOnFileContent=true`）——没有全走查、
 * 没有会话级陈旧缓存（config 保存后索引自动更新，顺带修掉 v1 已知限制）。命中不到时调用方
 * （`IbatisTypeAliasResolver`）回落短名索引，行为不变。
 *
 * ## 索引键设计
 *
 * | 键 | 含义 | 消费方 |
 * |---|---|---|
 * | `cfg` | 「本文件根标签是 `<sqlMapConfig>`」的常量标记 | 全量清单（诊断/测试） |
 * | `ta:<alias>` | 该 config 声明的别名 | `IbatisTypeAliasResolver.fqnOf` 主查询 |
 *
 * 前缀 `ta:` 与常量键 `cfg` 天然不撞（别名可含任意字符，但 `ta:` 前缀限定了键空间）。
 *
 * ## 键的提取（索引期，纯文本，不建 PSI）
 *
 * 根锚判定照 `SqlMapNamespaceIndex.rootTagStart` 同款互斥口径——sqlMapConfig 里常有
 * `<sqlMap resource=…/>` 子元素，必须先认出 config；`<sqlMap ` 出现在 `<sqlMapConfig` 之前
 * 即映射文件，不产键。`<typeAlias>` 走全文扫描（配置文件通常 KB 级）。
 *
 * 提取内核全部是纯函数（[aliasEntriesFromText] / [keysForText]），可纯单测。已知边界同族：
 * 根锚/别名写在注释或 CDATA 里会多收，由 PSI 侧 `rootTag` + `alias` 精确匹配闸门否掉（只少收
 * 不误收的正确性口径，见下）。
 *
 * ## 正确性闸门在 PSI 侧
 *
 * 索引只回答「去哪几个文件里找」。`IbatisTypeAliasResolver` 拿到候选文件后仍走
 * `rootTag.name == "sqlMapConfig"` + `<typeAlias alias>` 精确匹配——索引键偶有偏差只影响
 * 查得到查不到，不会给出错的 FQN。
 *
 * ## 平台注记
 *
 * `fileBasedIndex` EP `dynamic="true"`（product.jar 实证，两前置索引同款）非动态卸载屏障，
 * 热更新特性不受影响。键域有界（别名数 × 项目内 config 文件数）。
 */
class SqlMapConfigAliasIndex : ScalarIndexExtension<String>() {

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

        /** 索引 ID：带插件前缀防跨插件撞名（对位既有两索引）。 */
        val NAME: ID<String, Void> = ID.create("cn.song.mybatisplus.sqlMapConfigAlias")

        /** 「本文件是 sqlMapConfig」的常量键。 */
        const val CONFIG_KEY: String = "cfg"

        private const val ALIAS_PREFIX = "ta:"

        /** config 根锚：`<sqlMapConfig` 后必须跟空白 / `>` / `/`。 */
        private val SQL_MAP_CONFIG_TAG_START = Regex("<sqlMapConfig[\\s>/]")

        /** 映射文件根锚：`<sqlMap ` 出现在 config 锚之前 ⇒ 本文件是 sqlMap，不是 config。 */
        private val SQL_MAP_TAG_START = Regex("<sqlMap[\\s>/]")

        private val TYPE_ALIAS_TAG_START = Regex("<typeAlias[\\s>/]")

        private val ALIAS_ATTR = Regex("""\balias\s*=\s*(["'])([^"']+)\1""")
        private val TYPE_ATTR = Regex("""\btype\s*=\s*(["'])([^"']+)\1""")

        /** 别名 → 声明它的 sqlMapConfig 文件（索引查询；dumb 期可能抛 IndexNotReadyException，调用方兜底）。 */
        fun getConfigFilesByAlias(project: Project, alias: String): List<VirtualFile> {
            val a = alias.trim()
            if (a.isEmpty()) return emptyList()
            return query(project, ALIAS_PREFIX + a)
        }

        /** 项目内全部 sqlMapConfig 文件（全量清单/诊断用）。 */
        fun getAllConfigFiles(project: Project): List<VirtualFile> = query(project, CONFIG_KEY)

        private fun query(project: Project, key: String): List<VirtualFile> =
            FileBasedIndex.getInstance()
                .getContainingFiles(NAME, key, GlobalSearchScope.projectScope(project))
                .toList()

        /**
         * 纯文本判定内核（可纯单测）：本文件产出的全部索引键；根不是 `<sqlMapConfig>` 时返回空集。
         */
        internal fun keysForText(text: String): Set<String> {
            val entries = aliasEntriesFromText(text)
            if (entries.isEmpty() && !isSqlMapConfigFile(text)) return emptySet()
            val keys = LinkedHashSet<String>(entries.size + 1)
            keys.add(CONFIG_KEY)
            entries.keys.forEach { keys.add(ALIAS_PREFIX + it) }
            return keys
        }

        /** 根标签是否 `<sqlMapConfig>`（纯文本判定；`<sqlMap ` 在前 ⇒ 映射文件，非 config）。 */
        internal fun isSqlMapConfigFile(text: String): Boolean {
            val configStart = SQL_MAP_CONFIG_TAG_START.find(text)?.range?.first ?: return false
            val sqlMapStart = SQL_MAP_TAG_START.find(text)?.range?.first
            if (sqlMapStart != null && sqlMapStart < configStart) return false
            return true
        }

        /**
         * 全文里的 `<typeAlias>` 别名 → type 表（去重、保序；同别名重复声明取首个）。
         *
         * 只看开标签内的属性（`>` 前），因此不会把 SQL 体里的 `alias` 字样误收；跨行属性、
         * 属性序任意、单双引号都认。alias/type 缺一即弃（照 `IbatisTypeAliasResolver` 旧口径）。
         */
        internal fun aliasEntriesFromText(text: String): Map<String, String> {
            if (!isSqlMapConfigFile(text)) return emptyMap()
            if (!text.contains("alias")) return emptyMap()
            val entries = LinkedHashMap<String, String>()
            for (match in TYPE_ALIAS_TAG_START.findAll(text)) {
                val tagEnd = text.indexOf('>', match.range.first)
                if (tagEnd < 0) continue
                val tag = text.substring(match.range.first, tagEnd)
                val alias = ALIAS_ATTR.find(tag)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val type = TYPE_ATTR.find(tag)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                entries.putIfAbsent(alias, type)
            }
            return entries
        }

        /**
         * 键集 → 索引 value map。平台把 value 类型定为 `Void`（键即全部信息），Kotlin 侧无法直接
         * 造 `Void` 实例，只能借 `associateWith { null }` 的 `Nothing?` 值投过去（两前置索引同款写法）。
         */
        @Suppress("UNCHECKED_CAST")
        private fun indexMap(keys: Set<String>): Map<String, Void> =
            if (keys.isEmpty()) emptyMap() else keys.associateWith { null } as Map<String, Void>
    }
}
