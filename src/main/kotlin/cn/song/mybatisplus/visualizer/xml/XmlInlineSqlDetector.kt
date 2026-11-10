package cn.song.mybatisplus.visualizer.xml

import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import java.util.Locale

/**
 * 通用 XML 内嵌 SQL 检测（O-72 T-XmlInlineSql）。
 *
 * 背景：RFListPage 等自研框架 XML 把 SQL/HQL 写在自定义标签的 CDATA/文本里，
 * 根元素名不固定（用户实证），mapper/pages/hbm 三套「根元素+标签名」白名单全部
 * 不认领。本检测**锚定内容不锚定标签名**：叶子标签 + 文本首词命中 SQL 关键字
 * 即视为 SQL 载体，对任何框架形态生效。
 *
 * - 认领条件（[isSqlCarrierTag]）：无子标签 + 首段非空文本的前缀词 ∈ [SQL_KEYWORDS]；
 * - 排除既有认领（[isClaimedByKnownStructure]）：mapper 语句 / hbm 命名查询 /
 *   pages 的 `<datasource>`（图标在 maintainPage 上）/ iBatis `<sqlMap>` 语句
 *   （T-Ibatis2Support 券 A：由 [cn.song.mybatisplus.visualizer.ui.gutter.IbatisXmlGutterLineMarkerProvider]
 *   接管，本通道让位）——防止既有文件双图标；
 * - HQL 判别（[queryTypeOf]）：自身或祖先标签的 `type` 属性 ∈ {hql, jpql}
 *   （就近优先），仅预览链（后台线程）调用，不进 gutter 快速路径。
 *
 * **性能红线**：gutter 快速路径只做局部判定——叶子判定 + 首段非空文本截断前缀
 * （[PREFIX_PROBE_LENGTH] 字符），零 resolve、零全文件遍历、零转换。
 */
object XmlInlineSqlDetector {

    /** 首词命中即认领（宽口径，决策 D2）：SQL 关键字集，大小写不敏感。 */
    val SQL_KEYWORDS: Set<String> = setOf("SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "REPLACE")

    /** type 属性取这些值时按 HQL 走 [cn.song.mybatisplus.visualizer.converter.jpa.HqlConverter] 管道。 */
    private val HQL_TYPES: Set<String> = setOf("hql", "jpql")

    /** gutter 快速路径的文本探测上限（只影响首词判定，不影响预览时的完整提取）。 */
    const val PREFIX_PROBE_LENGTH = 64

    /** 判定 [tag] 是否为通用 SQL 载体标签（叶子 + 文本像 SQL + 未被既有结构认领）。 */
    fun isSqlCarrierTag(tag: XmlTag): Boolean {
        if (tag.subTags.isNotEmpty()) return false
        if (isClaimedByKnownStructure(tag)) return false
        val firstWord = firstTextWord(tag) ?: return false
        return firstWord in SQL_KEYWORDS
    }

    /** 该标签是否已被 mapper / hbm / pages / iBatis 四套既有白名单认领（通用检测必须让位，防双图标）。 */
    fun isClaimedByKnownStructure(tag: XmlTag): Boolean {
        val name = tag.name
        val parentName = tag.parentTag?.name
        // mapper 语句（XmlSqlGutterLineMarkerProvider：STATEMENT_TAGS 且父标签 mapper）
        if (name in MAPPER_STATEMENT_TAGS && parentName == "mapper") return true
        // hbm 命名查询（HbmNamedQueryGutterLineMarkerProvider：query/sql-query 且父标签 hibernate-mapping）
        if (name in HBM_QUERY_TAGS && parentName == "hibernate-mapping") return true
        // pages 的 SQL 载体（PagesXmlGutterLineMarkerProvider 图标锚在页面标签上）。
        // T-PagesHql：页面标签名不固定（maintainPage / listPage / …），统一按「<pages> 的直接子级」
        // 判定；`<maintainPage>` 兼容旧形态（历史文件可能不在 <pages> 下）。
        if (name == "datasource" &&
            (parentName == "maintainPage" || tag.parentTag?.parentTag?.name == "pages")
        ) {
            return true
        }
        // iBatis 语句（T-Ibatis2Support 券 A）：<sqlMap> 直接子级的语句标签由
        // IbatisXmlGutterLineMarkerProvider 接管（新的预览入口，见计划册 §券 A）。
        //
        // **性能红线**：本判定跑在 gutter 热路径，只许 O(1) 结构比较（标签名 + 父标签名），
        // 严禁调 IbatisXmlRecognizer 或任何 resolve / 索引查询。判据**直接调用接管方的同一函数**
        // （而不是在本地复写一遍条件），否则会出现「让位了但没人接管」的图标空洞或
        // 「两边都认领」的双图标。
        if (cn.song.mybatisplus.visualizer.ui.gutter.IbatisXmlGutterLineMarkerProvider.isStatementAnchor(tag)) {
            return true
        }
        return false
    }

    /** 首段非空文本的截断前缀首词（大写；无文本 → null）。 */
    fun firstTextWord(tag: XmlTag): String? {
        val prefix = firstNonBlankTextPrefix(tag) ?: return null
        val word = prefix.trim().split(WHITESPACE).firstOrNull().orEmpty()
        return word.uppercase(Locale.ROOT).ifEmpty { null }
    }

    /** 完整提取载体标签内 SQL 文本（直属文本段拼接，CDATA 剥包裹，trim；口径见 [directTextOf]）。 */
    fun extractSqlText(tag: XmlTag): String = directTextOf(tag)

    /** 自身或祖先标签的 `type` 属性值（就近优先，小写；无 → null）。 */
    fun queryTypeOf(tag: XmlTag): String? {
        var cur: XmlTag? = tag
        while (cur != null) {
            val type = cur.getAttributeValue("type")
            if (!type.isNullOrBlank()) return type.trim().lowercase(Locale.ROOT)
            cur = cur.parentTag
        }
        return null
    }

    /** [queryTypeOf] 是否判为 HQL 族（hql/jpql）——预览链据此走 HqlConverter。 */
    fun isHqlType(tag: XmlTag): Boolean = queryTypeOf(tag) in HQL_TYPES

    /** 通用检测总开关（设置页可关；测试环境 service 未注册时按默认开）。 */
    fun detectionEnabled(): Boolean = runCatching {
        cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.xmlInlineSqlEnabled
    }.getOrDefault(true)

    /**
     * gutter 快速路径合并开关（F19：一次设置服务查找取两布尔——平台逐 provider 全量跑、
     * 无共享短路，每次高亮 pass 两次查找是白付的；与 [detectionEnabled]/gutter 开关语义逐字节等价）。
     *
     * F51（T-PerfQuick5）起生产路径不再调用：[cn.song.mybatisplus.visualizer.ui.gutter.XmlInlineSqlGutterLineMarkerProvider]
     * 改为方法头单次取 state 并闸（经本函数自读属第二次查找）。函数保留——语义锚点与
     * 既有测试（XmlInlineSqlDetectorTest）仍在用。
     */
    fun gutterFastPathEnabled(): Boolean = runCatching {
        val s = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state
        s.xmlInlineSqlEnabled && s.showGutterIcon
    }.getOrDefault(true)

    /** 首段非空文本的截断前缀（遍历直属文本段，剥 CDATA 包裹、跳过纯空白段；无 → null）。
     *  F18 收口（性能红线「零全文件遍历」的自洽修复）：无包裹直读 trim+take；有包裹走
     *  「空白/标记跳过 + 截到闭合标记前的内容窗口剥包裹」——O(前缀窗) 而非 O(全文) 单次大正则
     *  （大 CDATA 段自研框架载体几十~几百 KB，此前每高亮 pass 付一次全文正则）。
     *  语义：CDATA 段级包裹不出现在首词内部，窗口剥包裹与全文剥包裹在首词判定上等价；
     *  短 CDATA 语料闭合标记截进窗口的历史形态由 close-cut 精确保持（窗口不含闭合）。 */
    internal fun firstNonBlankTextPrefix(tag: XmlTag): String? {
        for (child in tag.children) {
            val text = (child as? XmlText)?.text ?: continue
            if (!text.contains("<![CDATA[")) {
                val t = text.trim()
                if (t.isNotEmpty()) return t.take(PREFIX_PROBE_LENGTH)
                continue
            }
            var p = 0
            while (p < text.length) {
                while (p < text.length && text[p].isWhitespace()) p++
                if (p < text.length && (text.startsWith("<![CDATA[", p) || text.startsWith("]]>", p))) {
                    p += if (text[p] == '<') 9 else 3
                    continue
                }
                break
            }
            if (p >= text.length) continue
            // 内容窗口：截到首个闭合标记前（窗口含闭合会留 unmatched 伪影），超长取 PREFIX+64
            val closeIdx = text.indexOf("]]>", p)
            val hardEnd = if (closeIdx >= 0) closeIdx else text.length
            val end = minOf(text.length, p + PREFIX_PROBE_LENGTH + 64, hardEnd)
            val piece = unwrapCdata(text.substring(p, end)).trim()
            if (piece.isNotEmpty()) return piece.take(PREFIX_PROBE_LENGTH)
            // 窗口全空白（内容起点后紧跟闭合/再开标记）：从闭合之后继续找下一段内容
            var resume = if (closeIdx >= 0) closeIdx + 3 else end
            while (resume < text.length) {
                var q = resume
                while (q < text.length && text[q].isWhitespace()) q++
                if (q < text.length && (text.startsWith("<![CDATA[", q) || text.startsWith("]]>", q))) {
                    q += if (text[q] == '<') 9 else 3
                    resume = q
                    continue
                }
                if (q >= text.length) return null
                val c2 = text.indexOf("]]>", q)
                val e2 = minOf(text.length, q + PREFIX_PROBE_LENGTH + 64, if (c2 >= 0) c2 else text.length)
                val piece2 = unwrapCdata(text.substring(q, e2)).trim()
                if (piece2.isNotEmpty()) return piece2.take(PREFIX_PROBE_LENGTH)
                if (c2 >= 0) { resume = c2 + 3; continue }
                return null
            }
            return null
        }
        return null
    }

    private val WHITESPACE = Regex("\\s+")

    /** 与 XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS 同源（引用而非复写，防两处漂移）。 */
    private val MAPPER_STATEMENT_TAGS: Set<String> =
        cn.song.mybatisplus.visualizer.ui.gutter.XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS

    private val HBM_QUERY_TAGS: Set<String> =
        cn.song.mybatisplus.visualizer.ui.gutter.HbmNamedQueryGutterLineMarkerProvider.QUERY_TAGS
}
