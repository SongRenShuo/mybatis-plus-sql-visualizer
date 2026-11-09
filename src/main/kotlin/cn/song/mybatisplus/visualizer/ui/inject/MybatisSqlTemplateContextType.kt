package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * T-XmlGap W6：live template 的**自定义上下文**——只在语句宿主的语句标签内可用
 * （MyBatis `mapper` / iBatis `sqlMap`，T-Ibatis2Support 券 D 放宽根守卫）。
 *
 * 与竞品的取舍：竞品有 6 个模板集，其中 `table`/`sql`/`ignore` 三个是它的**引导注释体系**（用户手写
 * `<!--@Table-->`/`<!--@sql-->` 喂平台注入）；我方不做校验器、不需要这套注释依赖，**不照搬**
 * （内部调研报告 §2.5 已留痕）。本类只服务两个与注释体系无关的纯骨架模板：`cd`（CDATA 包裹）、
 * `co`（foreach 骨架）。
 *
 * 模板文件在 `resources/templates/mybatis-sql.xml`（平台自动扫描插件 `templates/` 目录），
 * 其中 `<context><option name="MYBATIS_SQL" .../></context>` 的 name 必须等于本类的 [ID]。
 */
class MybatisSqlTemplateContextType : TemplateContextType(ID, "MyBatis SQL") {

    override fun isInContext(file: PsiFile, offset: Int): Boolean {
        val xmlFile = file as? XmlFile ?: return false
        // T-Ibatis2Support 券 D：语句宿主根放宽到 iBatis `sqlMap`（同一个廉价判据）。
        // 语义留存（如实登记）：`cd`（CDATA 包裹）是 ORM 中立形态；`co`（foreach 骨架）是 **MyBatis**
        // 形态，iBatis 对应物是 `<iterate>`——本券**不**新增 iBatis 模板（新增模板须动
        // `resources/templates/**` 与模板名单守卫，且属「做错比不做差」面），故 iBatis 文件里
        // `co` 仍是 MyBatis foreach 骨架（用户主动敲缩写才会展开，非自动行为）。
        if (!XmlSynHiGate.isStatementHostRootName(xmlFile.rootTag?.name)) return false
        val safeOffset = offset.coerceIn(0, maxOf(0, file.textLength - 1))
        val element = file.findElementAt(safeOffset) ?: return false
        var tag: XmlTag? = PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false)
        var depth = 0
        while (tag != null && depth++ < MAX_ANCESTOR_DEPTH) {
            if (tag.name in STATEMENT_TAGS) return true
            tag = tag.parentTag
        }
        return false
    }

    companion object {
        /** 上下文 id（模板 XML 的 `<option name>` 必须与之一致）。 */
        const val ID = "MYBATIS_SQL"
        private val STATEMENT_TAGS = setOf("select", "insert", "update", "delete", "sql")
        private const val MAX_ANCESTOR_DEPTH = 24
    }
}
