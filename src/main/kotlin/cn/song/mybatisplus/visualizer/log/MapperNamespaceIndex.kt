package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.indexing.ID
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import com.intellij.ide.highlighter.XmlFileType

/**
 * F1'''（T-GutterFirstOpenDelay 精华采纳刀，2026-11-15）：mapper XML 的 namespace 自定义标量索引。
 *
 * ## 为什么（MPJ 反编译调研的 Top1 可借鉴点）
 *
 * 调研报告 `docs/调研报告/20261115-0202-MPJ跳转实现反编译调研-调研报告.md`：MPJ（MyBatisCodeHelper-Pro
 * 3.6.8+2321）把每个 mapper XML 的 namespace 在**索引期**摊平成键（自定义 `fileBasedIndex`，索引 ID
 * `MybatisCodeHelperIndexer6`），运行时一次 `FileBasedIndex.getContainingFiles` 出答案，失效全交平台
 * 增量——没有全扫、没有 TTL、没有「清单未就绪」窗口。
 *
 * 我们此前的三轮修复（F1 正缓存 / F1'' 异步头扫 + serve stale + 落地 restart）都是在「无索引」前提下
 * 打补丁；平台 `product.jar` 的 EP 声明实证 `fileBasedIndex` **dynamic="true"**（非动态卸载屏障）——
 * 本插件的热更新特性不受影响（字节码/平台声明为唯一权威，推翻既有记忆里的屏障清单）。
 * 故本轮直接采纳 MPJ 精华：**用平台索引替换整套清单状态机**。
 *
 * ## 索引键设计
 *
 * 键 = mapper 根标签的 `namespace` 属性值；值 = Void（键→包含文件即答案，`getContainingFiles`）。
 * 提取走 [namespaceFromHeadText]（原始头 16KB 内的 `<mapper …>` 开标签，双/单引号都认）——
 * MyBatis DTD 规定 namespace 是根标签属性、必在文件头部；iBatis 2.x `<sqlMap>` 根不命中锚
 * （与本定位器旧口径一致：iBatis 跳转走 IbatisXmlLocators 独立链，本索引不扩面）。
 *
 * ## v2（T-IndexScanSweep2 P1，2026-11-16）：新增两类**文件级标记键**
 *
 * 补全候选清单服务（`ui/highlight/XmlSymbolNameIndexCache`）需要「项目里有哪些 mapper 文件 / 哪些
 * mybatis-config 文件」这两个**文件集合**——若仍退化成 `FileTypeIndex(XML)` 全量，就回到了
 * 「每次补全对最多 500 个 XML 建 PSI」的老形态（本刀要消灭的正是它）。故本索引顺带摊平：
 *
 * | 键 | 含义 | 消费者 |
 * |---|---|---|
 * | `#mapper`（常量键） | 「本文件根标签是 `<mapper>`」 | 跨文件 `<sql id>` 清单构建 |
 * | `#config`（常量键） | 「本文件是 mybatis-config 形态：根 `<configuration>` 且含 `<properties>`/`<mappers>` 之一」 | `<properties>` key 清单构建 |
 *
 * 前缀用 `#`：namespace 是 Java 包名形态、不含 `#`，故与既有 namespace 键天然不撞（iBatis 侧
 * `all`/`ns:`/`stmt:` 方案在那边允许无前缀字面量，这边不行）。**既有 namespace 键语义零改动**
 * （`MapperXmlLocator`/`XmlCrossNsFragmentResolver`/`MybatisXmlTargets` 三个消费方不变），
 * `getVersion()` 1 → 2 触发一次平台增量重建（计划内）。
 *
 * 根标签判定走 [rootElementName]（跳过 XML 声明/DOCTYPE/注释后首个元素名），比「首个 `<mapper`
 * 锚」更精确——`mybatis-config.xml` 的 `<mappers><mapper resource="…"/></mappers>` 不会被误判为
 * mapper 文件（老锚会）。
 *
 * ## 失效与正确性
 *
 * `dependsOnFileContent()=true`：文件内容变更即增量重索引，平台管理，无自建失效。**正确性闸门在
 * PSI 侧**：`MapperXmlLocator.findStatementTag` 校验根标签 namespace 与 id 精确匹配——索引期提取的
 * 键即使偶有偏差（头 16KB 窗外、嵌套形态），也只影响「查得到查不到」，不会错跳。
 *
 * **同一个平台的坑（实测，2026-11-16）**：`FileBasedIndex.getIndexModificationStamp(NAME, project)`
 * 只随「索引**产出内容**变化」递增——实测「新增一个 XML 文件」= +1，而「改已存在 mapper 的
 * `<sql id>`（namespace/根标签没变 ⇒ 索引键不变）」**恒不变**。故消费方（清单服务）不能用它单独
 * 当失效代戳，只能与「来源文件的 `VirtualFile.modificationStamp`」合用（见 [getIndexStamp]）。
 */
class MapperNamespaceIndex : ScalarIndexExtension<String>() {

    override fun getName(): ID<String, Void> = NAME

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    /** 键面/提取逻辑变化即 bump（v2 = 新增 `#mapper`/`#config` 两类标记键）。 */
    override fun getVersion(): Int = 2

    override fun getInputFilter(): DefaultFileTypeSpecificInputFilter =
        DefaultFileTypeSpecificInputFilter(XmlFileType.INSTANCE)

    override fun dependsOnFileContent(): Boolean = true

    override fun getIndexer(): DataIndexer<String, Void, FileContent> = DataIndexer { inputData ->
        indexMap(keysForText(inputData.contentAsText.toString()))
    }

    companion object {

        /** 索引 ID：命名带插件前缀防跨插件撞名（MPJ 的同类索引叫 MybatisCodeHelperIndexer6）。 */
        val NAME: ID<String, Void> = ID.create("cn.song.mybatisplus.mapperNamespace")

        /** 文件级标记键：本文件根标签是 `<mapper>`。 */
        const val KEY_MAPPER: String = "#mapper"

        /** 文件级标记键：本文件是 mybatis-config 形态。 */
        const val KEY_CONFIG: String = "#config"

        private const val ROOT_MAPPER = "mapper"
        private const val ROOT_CONFIGURATION = "configuration"

        /**
         * namespace → 项目内的 mapper XML 文件列表（索引查询，毫秒级；多模块同名全返回，
         * 调用方取第一个并留痕）。dumb/索引未就绪期可能返回不全——调用方（慢趟 provider）有
         * F1' 失败分支（正缓存/dumb 乐观产出）兜底，不会把「还没查到」固化为「真没有」。
         */
        fun getMapperXmlFiles(project: Project, namespace: String): List<VirtualFile> =
            FileBasedIndex.getInstance().getContainingFiles(NAME, namespace, GlobalSearchScope.projectScope(project))
                .toList()

        /** 项目内全部 mapper XML 文件（根标签 `<mapper>`；索引查询，不建 PSI）。 */
        fun getMapperFiles(project: Project): List<VirtualFile> = query(project, KEY_MAPPER)

        /** 项目内全部 mybatis-config 文件（根 `<configuration>` 且含 `<properties>`/`<mappers>`）。 */
        fun getConfigFiles(project: Project): List<VirtualFile> = query(project, KEY_CONFIG)

        /**
         * 本索引的**索引内容代戳**（`FileBasedIndex.getIndexModificationStamp`，IU-243/262 双平台
         * `javap` 实证在册）。注意实测语义（见类头）：它只随索引**产出**变化，**不**随「不改变索引键的
         * 内容编辑」变化——消费方须与来源文件的 `VirtualFile.modificationStamp` 合用。
         */
        fun getIndexStamp(project: Project): Long =
            FileBasedIndex.getInstance().getIndexModificationStamp(NAME, project)

        private fun query(project: Project, key: String): List<VirtualFile> =
            FileBasedIndex.getInstance().getContainingFiles(NAME, key, GlobalSearchScope.projectScope(project))
                .toList()

        /** 头扫字节数：mapper 根标签必在文件头部（XML 声明 + DOCTYPE 之后）。 */
        private const val HEAD_SCAN_BYTES = 16 * 1024

        /** `<mapper` 根标签开锚（`<mappers`/`<mapperX` 不命中）；属性序列任意，namespace 双/单引号都认。 */
        private val MAPPER_TAG_START = Regex("<mapper[\\s>/]")

        private val NS_ATTR = Regex("""namespace\s*=\s*(["'])([^"']+)\1""")

        /** mybatis-config 形态判定锚（`<properties>`/`<mappers>` 开标签）。 */
        private val PROPERTIES_TAG_START = Regex("<properties[\\s>/]")
        private val MAPPERS_TAG_START = Regex("<mappers[\\s>/]")

        /** 根元素名开锚（`<name`，名字允许 `_`/`-`/`.` 的 XML Name 子集）。 */
        private val ELEMENT_NAME = Regex("^<([A-Za-z_][\\w.-]*)")

        /**
         * 纯文本判定内核（可纯单测）：本文件产出的全部索引键。
         *
         *  - namespace 键：走 [namespaceFromHeadText]（与 v1 一字不差的旧口径）；
         *  - `#mapper`：根元素名 == `mapper`；
         *  - `#config`：根元素名 == `configuration` 且全文含 `<properties>` 或 `<mappers>` 开标签。
         */
        internal fun keysForText(text: String): Set<String> {
            val keys = LinkedHashSet<String>(4)
            namespaceFromHeadText(text)?.takeIf { it.isNotBlank() }?.let { keys.add(it) }
            when (rootElementName(text)) {
                ROOT_MAPPER -> keys.add(KEY_MAPPER)
                ROOT_CONFIGURATION ->
                    if (PROPERTIES_TAG_START.containsMatchIn(text) || MAPPERS_TAG_START.containsMatchIn(text)) {
                        keys.add(KEY_CONFIG)
                    }
            }
            return keys
        }

        /**
         * 根元素名（小写）：跳过 XML 声明 `<?…?>`、注释 `<!--…-->`、DOCTYPE/其它 `<!…>` 之后的
         * **首个** 元素开标签名。无元素/畸形 → null。
         *
         * 只用于 `#mapper`/`#config` 的**文件级**判定；namespace 键仍走 [namespaceFromHeadText]
         * （不改既有语义）。HEAD_SCAN_BYTES 之外不再扫（根标签必在头部；超窗 → 判定失败，只少收不多收）。
         */
        internal fun rootElementName(text: String): String? {
            val end = if (text.length > HEAD_SCAN_BYTES) HEAD_SCAN_BYTES else text.length
            var i = 0
            while (i < end) {
                val lt = text.indexOf('<', i)
                if (lt < 0 || lt >= end) return null
                if (text.startsWith("<!--", lt)) {
                    val e = text.indexOf("-->", lt)
                    if (e < 0) return null
                    i = e + 3
                } else if (lt + 1 < end && text[lt + 1] == '?') {
                    val e = text.indexOf("?>", lt)
                    if (e < 0) return null
                    i = e + 2
                } else if (lt + 1 < end && text[lt + 1] == '!') {
                    // DOCTYPE（含内部子集 `[ … ]`）统一按首个 `>` 收尾：MyBatis DTD 的 PUBLIC/SYSTEM
                    // 串内不含 `>`，够用；异常形态只会让判定失败（少收），不会错收。
                    val e = text.indexOf('>', lt)
                    if (e < 0) return null
                    i = e + 1
                } else {
                    val m = ELEMENT_NAME.find(text.substring(lt, minOf(end, lt + 80))) ?: return null
                    return m.groupValues[1].lowercase()
                }
            }
            return null
        }

        /** 纯文本判定内核（可纯单测）：`<mapper …>` 开标签内的 namespace 属性值；无/空 → null。
         *  索引期喂 `FileContent.contentAsText`（全文本）——根标签必在前部，首锚即根。 */
        internal fun namespaceFromHeadText(head: String): String? {
            val tagStart = MAPPER_TAG_START.find(head) ?: return null
            val tagEnd = head.indexOf('>', tagStart.range.first)
            if (tagEnd < 0) return null
            return NS_ATTR.find(head.substring(tagStart.range.first, tagEnd))?.groupValues?.get(2)
                ?.takeIf { it.isNotBlank() }
        }

        /**
         * 键集 → 索引 value map。平台把 value 类型定为 `Void`（键即全部信息），Kotlin 侧无法直接
         * 造 `Void` 实例，只能借 `associateWith { null }` 的 `Nothing?` 值投过去（`HashMap` 允许
         * null 值，照 `xml/ibatis/SqlMapNamespaceIndex.indexMap` 同款写法）。
         */
        @Suppress("UNCHECKED_CAST")
        private fun indexMap(keys: Set<String>): Map<String, Void> =
            if (keys.isEmpty()) emptyMap() else keys.associateWith { null } as Map<String, Void>
    }
}
