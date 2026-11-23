package cn.song.mybatisplus.visualizer.ui.inject

import cn.song.mybatisplus.visualizer.psi.EntityFieldCollector
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext

/**
 * T-XmlSynHi D5 后半 + T-XmlGap W5-10：resultMap 属性值补全。
 *
 * 触发位：`<result|id … column="…"|property="…">` 属性值（resultMap 上下文）。
 *  - `column` 候选 = **引用了该 resultMap 的 `<select>` 语句**的投影列
 *    （T-XmlGap W5-10 修正：原为 mapper 全文 SELECT 投影列，会混入无关列），
 *    按已输入前缀过滤/排序（[ResultMapColumns.suggest]）。
 *  - `property` 候选 = 该 resultMap `<resultMap type="X">` 的类字段（新增，W5-10「property 建议」）。
 * 非结果映射上下文零候选（反向验证）。
 *
 * T-PerfSweep16 F40：补全链接让位门（[XmlSynHiGate.shouldYield]）+ 全文重扫 memo
 * （键=文件 url + modificationStamp [+ resultMapId]；F89 起 [ResultMapColumnCompletionMemo]
 * 双槽化）。
 *
 * F93（T-PerfSweep25，顺手件）：补 [DumbAware] 对齐三兄弟（XmlSqlCompletionContributor /
 * MybatisConfigCompletionContributor / WrapperCompletionContributor 均为
 * `CompletionContributor(), DumbAware`——T-XmlCmpl2 教训：缺该标记接口 dumb 期被静默跳过）。
 * dumb 期列名候选走文件文本提取不受索引影响；类字段候选（[resultMapTypeFields] 的
 * findClass/ShortNamesCache）索引未就绪时可能不全，runCatching 兜底空表，无异常面。
 */
class ResultMapColumnCompletionContributor : CompletionContributor(), DumbAware {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(PsiElement::class.java).withParent(XmlAttributeValue::class.java),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val position = parameters.position
                    val attrValue = position.parent as? XmlAttributeValue ?: return
                    val attr = attrValue.parent as? XmlAttribute ?: return
                    if (attr.name != "column" && attr.name != "property") return
                    val tag = attr.parent as? XmlTag ?: return
                    if (tag.name != "result" && tag.name != "id") return
                    val resultMapTag = enclosingResultMap(tag) ?: return
                    // T-PerfSweep16 F40：让位门（设置关/平台注入器/三方插件在场 → 零候选）
                    if (XmlSynHiGate.shouldYield(position.project)) return
                    val file = resultMapTag.containingFile as? XmlFile ?: return
                    val typed = currentTyped(attrValue, parameters)
                    val candidates = if (attr.name == "column") {
                        val resultMapId = resultMapTag.getAttributeValue("id").orEmpty()
                        ResultMapColumnCompletionMemo.columnsForResultMap(file, resultMapId)
                    } else {
                        resultMapTypeFields(resultMapTag)
                    }
                    ResultMapColumns.suggest(candidates, typed).forEach { candidate ->
                        result.addElement(LookupElementBuilder.create(candidate))
                    }
                }
            },
        )
    }

    /** 已输入前缀（属性值内光标前文本；引号由 XmlAttributeValue 边界排除在外）。 */
    private fun currentTyped(attrValue: XmlAttributeValue, parameters: CompletionParameters): String {
        val offset = parameters.offset
        val start = attrValue.textRange.startOffset + 1 // 跳过开引号
        return if (offset in start..attrValue.textRange.endOffset) {
            attrValue.text.substring(0, (offset - start).coerceAtMost(attrValue.text.length))
        } else attrValue.text
    }

    /** 祖先链存在 resultMap 标签（result/id 行属于结果映射上下文）。 */
    private fun enclosingResultMap(tag: XmlTag): XmlTag? {
        var cur: XmlTag? = tag.parentTag
        while (cur != null) {
            if (cur.name == "resultMap") return cur
            cur = cur.parentTag
        }
        return null
    }

    /** `<resultMap type="X">` 的类字段（不可解析 → 空）。 */
    private fun resultMapTypeFields(resultMapTag: XmlTag): List<String> = runCatching {
        val typeName = resultMapTag.getAttributeValue("type")?.trim()?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val project = resultMapTag.project
        val scope = GlobalSearchScope.allScope(project)
        val clazz: PsiClass? = if (typeName.contains('.')) {
            JavaPsiFacade.getInstance(project).findClass(typeName, scope)
        } else {
            PsiShortNamesCache.getInstance(project).getClassesByName(typeName, scope).firstOrNull()
        }
        // D254：不用 allFields（平台不按名去重、不过静态）——resultMap property 候选按实体列语义收
        clazz?.let { EntityFieldCollector.collectColumns(it) }
            ?.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }?.distinct().orEmpty()
    }.getOrElse { emptyList() }
}

/**
 * T-PerfSweep16 F40 + T-XmlGap W5-10：`extractColumns` 全文重扫 memo（进程内一份，补全触发高频）。
 *
 * 键 = 文件 url + modificationStamp + 文本长度（指纹组合）：stamp 变化即失效；**长度是
 * 实测补上的第二键**——测试夹具与部分内存态文件的 LightVirtualFile 在文档变更后
 * modificationStamp 不动（ResultMapColumnCompletionMemoTest 真红实证），单靠 stamp 会
 * 返回陈旧候选。编辑后自然回源重扫。
 *
 * T-XmlGap W5-10 增加第二条轨：[columnsForResultMap]（键额外含 resultMapId，按文件分槽），
 * 与既有 [columnsFor]（整文件全量，签名保持不变——既有测试与注解链复用）互不干扰。
 *
 * F89 双槽化（T-PerfSweep25）：原进程级单槽在两份 mapper 交替补全时互相驱逐，每次交替即一次
 * 全文重扫 miss——现两轨各两槽（键命中复用、全新键按翻转位驱逐其一），两文件交替各占一槽零重扫；
 * 单文件常态恒落首槽（与单槽行为一致），三文件起恢复有界乒乓。线程模型同前：字段各自
 * @Volatile，竞态最坏=陈旧未命中回源重扫，无结构性破坏。
 *
 * `extractor` / `resultMapExtractor` 为 internal 缝——测试注入计数 lambda 断言「二次补全零重扫」。
 */
internal object ResultMapColumnCompletionMemo {

    /** 提取函数缝（默认=纯函数本体；测试可注入计数 lambda）。 */
    internal var extractor: (String) -> List<String> = { text -> ResultMapColumns.extractColumns(text) }

    /** W5-10 提取函数缝（文本 + resultMapId → 候选）。 */
    internal var resultMapExtractor: (String, String) -> List<String> =
        { text, resultMapId -> ResultMapColumns.extractColumnsForResultMap(text, resultMapId) }

    /** F89 双槽：槽 = url 键 + 戳 + 长度 + 值（rm 轨值为 per-resultMapId map）。 */
    private class Slot(
        @Volatile var url: String? = null,
        @Volatile var stamp: Long = Long.MIN_VALUE,
        @Volatile var len: Int = -1,
        @Volatile var value: List<String> = emptyList(),
    )

    private class RmSlot(
        @Volatile var url: String? = null,
        @Volatile var stamp: Long = Long.MIN_VALUE,
        @Volatile var len: Int = -1,
        @Volatile var value: Map<String, List<String>> = emptyMap(),
    )

    private val memoSlots = arrayOf(Slot(), Slot())
    private val rmMemoSlots = arrayOf(RmSlot(), RmSlot())
    @Volatile private var evictFlip = false
    @Volatile private var rmEvictFlip = false

    /** F89 双槽选位：本键已有槽则复用；全新键按翻转位驱逐其一（两文件交替各占一槽零驱逐）。 */
    private fun pickSlot(mine: (Slot) -> Boolean): Slot =
        memoSlots.firstOrNull(mine) ?: run { evictFlip = !evictFlip; memoSlots[if (evictFlip) 1 else 0] }

    private fun pickRmSlot(mine: (RmSlot) -> Boolean): RmSlot =
        rmMemoSlots.firstOrNull(mine) ?: run { rmEvictFlip = !rmEvictFlip; rmMemoSlots[if (rmEvictFlip) 1 else 0] }

    /** 命中 memo 直接返回缓存（零重扫）；键不匹配才全文提取并回填。 */
    internal fun columnsFor(file: XmlFile): List<String> {
        val vf = file.virtualFile
        val url = vf?.url
        val stamp = vf?.modificationStamp ?: file.modificationStamp
        val len = file.textLength
        val slot = pickSlot { it.url == url }
        if (url == slot.url && stamp == slot.stamp && len == slot.len) return slot.value
        val value = extractor(file.text)
        slot.url = url
        slot.stamp = stamp
        slot.len = len
        slot.value = value
        return value
    }

    /**
     * W5-10：指定 resultMap 的候选（引用该 resultMap 的 `<select>` 投影列）。
     * 同一文件内多 resultMap 复用一份解析结果（按 resultMapId 分槽）。
     */
    internal fun columnsForResultMap(file: XmlFile, resultMapId: String): List<String> {
        val vf = file.virtualFile
        val url = vf?.url
        val stamp = vf?.modificationStamp ?: file.modificationStamp
        val len = file.textLength
        val slot = pickRmSlot { it.url == url }
        if (url != slot.url || stamp != slot.stamp || len != slot.len) {
            slot.url = url
            slot.stamp = stamp
            slot.len = len
            slot.value = emptyMap()
        }
        slot.value[resultMapId]?.let { return it }
        // 一次全文扫描解析该 resultMap 的候选（同文件多 resultMap：每 id 一次，file.text O(n)）
        val value = resultMapExtractor(file.text, resultMapId)
        slot.value = slot.value + (resultMapId to value)
        return value
    }

    /** 测试缝：清空 memo，保证断言从冷态起算。 */
    internal fun resetForTest() {
        memoSlots.forEach { slot ->
            slot.url = null
            slot.stamp = Long.MIN_VALUE
            slot.len = -1
            slot.value = emptyList()
        }
        rmMemoSlots.forEach { slot ->
            slot.url = null
            slot.stamp = Long.MIN_VALUE
            slot.len = -1
            slot.value = emptyMap()
        }
        evictFlip = false
        rmEvictFlip = false
    }
}
