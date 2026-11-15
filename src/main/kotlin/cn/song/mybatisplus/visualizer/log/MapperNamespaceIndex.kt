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
 * ## 失效与正确性
 *
 * `dependsOnFileContent()=true`：文件内容变更即增量重索引，平台管理，无自建失效。**正确性闸门在
 * PSI 侧**：`MapperXmlLocator.findStatementTag` 校验根标签 namespace 与 id 精确匹配——索引期提取的
 * 键即使偶有偏差（头 16KB 窗外、嵌套形态），也只影响「查得到查不到」，不会错跳。
 */
class MapperNamespaceIndex : ScalarIndexExtension<String>() {

    override fun getName(): ID<String, Void> = NAME

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    /** namespace 提取逻辑变化（头扫窗/正则）即 bump，触发平台全量重索引。 */
    override fun getVersion(): Int = 1

    override fun getInputFilter(): DefaultFileTypeSpecificInputFilter =
        DefaultFileTypeSpecificInputFilter(XmlFileType.INSTANCE)

    override fun dependsOnFileContent(): Boolean = true

    override fun getIndexer(): DataIndexer<String, Void, FileContent> = DataIndexer { inputData ->
        val namespace = namespaceFromHeadText(inputData.contentAsText.toString())
        if (namespace.isNullOrEmpty()) emptyMap() else mapOf(namespace to null)
    }

    companion object {

        /** 索引 ID：命名带插件前缀防跨插件撞名（MPJ 的同类索引叫 MybatisCodeHelperIndexer6）。 */
        val NAME: ID<String, Void> = ID.create("cn.song.mybatisplus.mapperNamespace")

        /**
         * namespace → 项目内的 mapper XML 文件列表（索引查询，毫秒级；多模块同名全返回，
         * 调用方取第一个并留痕）。dumb/索引未就绪期可能返回不全——调用方（慢趟 provider）有
         * F1' 失败分支（正缓存/dumb 乐观产出）兜底，不会把「还没查到」固化为「真没有」。
         */
        fun getMapperXmlFiles(project: Project, namespace: String): List<VirtualFile> =
            FileBasedIndex.getInstance().getContainingFiles(NAME, namespace, GlobalSearchScope.projectScope(project))
                .toList()

        /** 头扫字节数：mapper 根标签必在文件头部（XML 声明 + DOCTYPE 之后）。 */
        private const val HEAD_SCAN_BYTES = 16 * 1024

        /** `<mapper` 根标签开锚（`<mappers`/`<mapperX` 不命中）；属性序列任意，namespace 双/单引号都认。 */
        private val MAPPER_TAG_START = Regex("<mapper[\\s>/]")

        private val NS_ATTR = Regex("""namespace\s*=\s*(["'])([^"']+)\1""")

        /** 纯文本判定内核（可纯单测）：`<mapper …>` 开标签内的 namespace 属性值；无/空 → null。
         *  索引期喂 `FileContent.contentAsText`（全文本）——根标签必在前部，首锚即根。 */
        internal fun namespaceFromHeadText(head: String): String? {
            val tagStart = MAPPER_TAG_START.find(head) ?: return null
            val tagEnd = head.indexOf('>', tagStart.range.first)
            if (tagEnd < 0) return null
            return NS_ATTR.find(head.substring(tagStart.range.first, tagEnd))?.groupValues?.get(2)
                ?.takeIf { it.isNotBlank() }
        }
    }
}
