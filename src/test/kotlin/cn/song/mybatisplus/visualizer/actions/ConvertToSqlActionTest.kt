package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisCallTargets
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisStatementPreview
import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets
import cn.song.mybatisplus.visualizer.ui.nav.MapperNavigationTargets
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.MapDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * 转换 Action（正向 Wrapper→SQL）update() 可见性测试（F-44 续 · 补用例大波次）：
 * 此前 0 测试。update 读取 PSI_FILE 决定启用范围（Java 文件 / XML mapper 可用，其它文件不可用）。
 * 用 MapDataContext 构造 AnActionEvent，确定性校验可见性逻辑，不触发 actionPerformed。
 */
class ConvertToSqlActionTest : BasePlatformTestCase() {

    private fun eventFor(file: com.intellij.psi.PsiFile?, editor: com.intellij.openapi.editor.Editor?): AnActionEvent {
        val dc = MapDataContext()
        if (file != null) dc.put(CommonDataKeys.PSI_FILE.name, file)
        if (editor != null) dc.put(CommonDataKeys.EDITOR.name, editor)
        return AnActionEvent(null, dc, ActionPlaces.UNKNOWN, Presentation(), ActionManager.getInstance(), 0)
    }

    fun testUpdate_Java文件可见可用() {
        myFixture.configureByText("A.java", "class A {}")
        val e = eventFor(myFixture.file, myFixture.editor)
        ConvertToSqlAction().update(e)
        assertTrue("Java 文件应启用", e.presentation.isEnabledAndVisible)
    }

    fun testUpdate_Xml文件可见可用() {
        myFixture.configureByText("a.xml", "<root/>")
        val e = eventFor(myFixture.file, myFixture.editor)
        ConvertToSqlAction().update(e)
        assertTrue("XML mapper 文件应启用", e.presentation.isEnabledAndVisible)
    }

    fun testUpdate_非Java非Xml文件不可见() {
        myFixture.configureByText("a.txt", "x")
        val e = eventFor(myFixture.file, myFixture.editor)
        ConvertToSqlAction().update(e)
        assertFalse("普通文本文件不应启用", e.presentation.isEnabledAndVisible)
    }

    // ------------------------------------------------------------------
    // T-IbatisAtShapeGate（本轮同步引入）：@ 形态语句名认领与错源兜底
    // ------------------------------------------------------------------

    /**
     * T-IbatisAtShapeGate：`组名@命名空间.语句id` 形态在**快捷键回落链**上必须被 iBatis 认领。
     *
     * 真机报障：`baseDao.queryForObject("cQAccountTypeByAccountType@custmg.qAccountTypeByAccountType", inMap)`
     * ——gutter 图标点击正常，Alt+Shift+Q 弹「未在光标处找到可预览的 SQL 源」。根因是形状门正则
     * 不含 `@`：iBatis 两档回落都放弃，`JdbcTemplateSqlLocator`（action 侧无让位门）认领后
     * `isLikelySql` 拒绝 → 动作已 return true → 错源提示。
     */
    fun test带At符号的自研语句名_iBatis形状门认领且渲染出SQL() {
        myFixture.addFileToProject(
            "sqlmap/Account.xml",
            """<sqlMap namespace="cQAccountTypeByAccountType@custmg"><select id="qAccountTypeByAccountType">SELECT * FROM T_ACCOUNT_TYPE WHERE ACCOUNT_TYPE = #accountType#</select></sqlMap>""",
        )
        val dao = myFixture.addFileToProject(
            "com/demo/AccountDaoImpl.java",
            """
            package com.demo;
            public class AccountDaoImpl {
                private Object baseDao;
                public Object query(String accountType) {
                    return baseDao.queryForObject("cQAccountTypeByAccountType@custmg.qAccountTypeByAccountType", accountType);
                }
            }
            """.trimIndent(),
        )
        IbatisXmlStatementTargets.resetPerfLogForTest()
        val literal = PsiTreeUtil.findChildrenOfType(dao, PsiLiteralExpression::class.java)
            .first { (it.value as? String)?.contains("@custmg.") == true }

        // ① 对照：JDBC 通道会认领这个调用（谁先认领决定用户看到什么）
        val jdbcCall = JdbcTemplateSqlLocator.locate(literal)
        assertNotNull("夹具前提：JdbcTemplateSqlLocator 不拦 @ 形态 iBatis 调用", jdbcCall)
        assertNull(
            "JDBC 通道对 @ 形态 statementId 渲染不出 SQL（错源提示的来源）",
            JdbcTemplateSqlPreviewService.preview(jdbcCall!!, literal),
        )

        // ② 修复后：形状门（与 gutter 让位同一函数）认领并渲染出 iBatis SQL
        val target = IbatisCallTargets.resolve(literal)
        assertNotNull("iBatis 形状门应认领 @ 形态调用（修复前为 null → 错源提示）", target)
        assertEquals(
            "cQAccountTypeByAccountType@custmg.qAccountTypeByAccountType",
            target!!.statementId(),
        )
        val preview = IbatisStatementPreview.previewResolved(project, target.statementId(), target.ownerNamespace())
        assertTrue("iBatis 预览应渲染出 SQL：${preview.sql}", preview.sql.contains("FROM T_ACCOUNT_TYPE"))
        assertEquals(listOf("accountType"), preview.unresolvedParams)
    }

    /**
     * T-IbatisAtShapeGate S4：**错源兜底**——JDBC 通道认领且渲染失败后，跳过形状正则再试一次 iBatis。
     */
    fun test错源兜底_形状门不认的语句名经解析命中后接管() {
        myFixture.addFileToProject(
            "sqlmap/Dash.xml",
            """<sqlMap namespace="Dash"><select id="select-by-id">SELECT ID FROM T_DASH WHERE ID = #id#</select></sqlMap>""",
        )
        val dao = myFixture.addFileToProject(
            "com/demo/DashDaoImpl.java",
            """
            package com.demo;
            public class DashDaoImpl {
                private Object client;
                public Object select(int id) { return client.queryForObject("Dash.select-by-id", id); }
            }
            """.trimIndent(),
        )
        IbatisXmlStatementTargets.resetPerfLogForTest()
        val literal = PsiTreeUtil.findChildrenOfType(dao, PsiLiteralExpression::class.java)
            .first { it.value == "Dash.select-by-id" }

        // 前提：常规形状门拒绝（这就是缺陷链的起点）
        assertNull("形状门不认 `-`，常规认领放弃", IbatisCallTargets.resolve(literal))
        // 前提：JDBC 会认领但渲染不出（错源提示的来源）
        val jdbcCall = JdbcTemplateSqlLocator.locate(literal)
        assertNotNull("JDBC 定位器认领该调用", jdbcCall)
        assertNull("JDBC 渲染不出 SQL", JdbcTemplateSqlPreviewService.preview(jdbcCall!!, literal))

        // 兜底口径：跳过形状正则 → 解析命中 → 渲染出 SQL（命中才接管）
        val target = IbatisCallTargets.resolveIgnoringShape(literal)
        assertNotNull("兜底口径应定位到该调用", target)
        val preview = IbatisStatementPreview.previewResolvedOrNull(project, target!!.statementId(), target.ownerNamespace())
        assertNotNull("语句存在 → 兜底预览接管（不是 null，故不会落「未找到」）", preview)
        assertTrue("兜底渲染出 iBatis SQL：${preview!!.sql}", preview.sql.contains("FROM T_DASH"))
    }

    /**
     * 兜底不得给非 iBatis 工程制造假阳性：语句不存在时 `previewResolvedOrNull` 返回 null，
     * 调用方保持原「未找到」提示（而不是 iBatis「语句未找到」的误导文案）。
     */
    fun test错源兜底_语句不存在时返回null保持原提示() {
        myFixture.addFileToProject(
            "sqlmap/Dash.xml",
            """<sqlMap namespace="Dash"><select id="select-by-id">SELECT ID FROM T_DASH WHERE ID = #id#</select></sqlMap>""",
        )
        val dao = myFixture.addFileToProject(
            "com/demo/OtherDaoImpl.java",
            """
            package com.demo;
            public class OtherDaoImpl {
                private Object cache;
                public void refresh() { cache.update("refreshCache"); }
            }
            """.trimIndent(),
        )
        IbatisXmlStatementTargets.resetPerfLogForTest()
        val literal = PsiTreeUtil.findChildrenOfType(dao, PsiLiteralExpression::class.java)
            .first { it.value == "refreshCache" }

        val target = IbatisCallTargets.resolveIgnoringShape(literal)
        assertNotNull("入口方法名 + 字面量即进入兜底口径", target)
        assertNull(
            "解析未命中 → null（调用方保持原「未找到」提示，不改写成 iBatis 语句未找到）",
            IbatisStatementPreview.previewResolvedOrNull(project, target!!.statementId(), target.ownerNamespace()),
        )
    }

    /**
     * 结构守卫（S4）：错源兜底必须真的接在 `tryJdbcTemplateFallback` 的失败分支上——
     * 行为用例只能证明兜底函数本身，接线顺序/存在性只能在源码上钉。
     */
    fun test错源兜底接线在JdbcTemplate失败分支() {
        val source = java.io.File(
            "src/main/java/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.java",
        ).readText()
        val fnStart = source.indexOf("private boolean tryJdbcTemplateFallback(")
        assertTrue("tryJdbcTemplateFallback 必须存在", fnStart > 0)
        val fnBody = source.substring(fnStart, source.indexOf("\n    }", fnStart))
        assertTrue(
            "JDBC 预览失败分支必须调 resolveIgnoringShape 兜底",
            fnBody.contains("resolveIgnoringShape"),
        )
        assertTrue(
            "兜底必须用 previewResolvedOrNull（未命中保持原提示，不能用 previewResolved）",
            fnBody.contains("previewResolvedOrNull"),
        )
    }

    // ---------- T-MapperMethodSqlPreview：Mapper 接口方法行预览快捷键（2026-11-26） ----------

    /**
     * 用户报障（2026-11-26）：「mapper.java 方法处的预览快捷键没支持」——`Alt+Shift+Q` 停在
     * 接口方法名上原本落到「未找到可转换的 Wrapper」。本用例钉住新回落的数据面：
     * 接口方法 → 同名 XML 语句 → 渲染出 SQL（`tryMapperXmlMethodFallback` 内部即这两步）。
     */
    fun testMapper接口方法_快捷键回落命中XML语句并渲染SQL() {
        myFixture.addFileToProject(
            "mapper/UserMapper.xml",
            """<mapper namespace="com.demo.UserMapper"><select id="selectById">SELECT ID, NAME FROM T_USER WHERE ID = #{id}</select></mapper>""",
        )
        val iface = myFixture.addFileToProject(
            "com/demo/UserMapper.java",
            """
            package com.demo;
            public interface UserMapper {
                Object selectById(Long id);
            }
            """.trimIndent(),
        )
        val methodName = PsiTreeUtil.findChildrenOfType(iface, com.intellij.psi.PsiIdentifier::class.java)
            .first { it.text == "selectById" }
        val method = MapperNavigationTargets.nearestMapperMethod(methodName)
        assertNotNull("光标停在方法名上应能定位到 Mapper 接口方法", method)
        val tags = MapperNavigationTargets.statementTagsFor(method!!)
        assertEquals("应命中同名 XML 语句", 1, tags.size)
        val xmlFile = tags[0].containingFile as com.intellij.psi.xml.XmlFile
        // k2j 形态：master 的 `object XmlPreviewService` 已落为 `final class` + `INSTANCE` 单例，
        // preview 是实例方法（不能像 Kotlin object 那样直接静态调用）。
        val preview = XmlPreviewService.INSTANCE.preview(xmlFile.text, tags[0].getAttributeValue("id"), project)
        assertTrue("快捷键回落应渲染出该语句 SQL：${preview.sql}", preview.sql.contains("FROM T_USER"))
    }

    /** 结构守卫：Mapper XML 回落必须排在 iBatis 两档之前（无 body 的接口方法先按 XML 绑定认领）。 */
    fun testMapperXml回落排在iBatis之前() {
        val source = java.io.File(
            "src/main/java/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.java",
        ).readText()
        val mapper = source.indexOf("!tryMapperXmlMethodFallback(")
        val ibatisCall = source.indexOf("!tryIbatisCallFallback(")
        assertTrue(
            "Mapper XML 回落必须出现在链里且先于 iBatis（mapper=$mapper iBatis=$ibatisCall）",
            mapper > 0 && ibatisCall > 0 && mapper < ibatisCall,
        )
    }

    // ---------- T-PreviewFallbackEdtSlowOps：兜底锚点在 BGT 解析（2026-11-26 真机 SEVERE） ----------

    /**
     * 数据面：接口方法名 → [ConvertToSqlAction.mapperXmlTagsFor] 命中间名 XML 语句。
     *
     * 真机 SEVERE 的成因是这步原先在 EDT 回调里做（`FileBasedIndex` 是慢操作门控 API）；
     * 本用例钉住"换到 compute 块后解析结果仍然可用"这一前提。
     */
    fun testMapperXml兜底锚点解析_命中同名XML语句() {
        myFixture.addFileToProject(
            "mapper/UserMapper.xml",
            """<mapper namespace="com.demo.UserMapper"><select id="selectById">SELECT ID FROM T_USER WHERE ID = #{id}</select></mapper>""",
        )
        val iface = myFixture.addFileToProject(
            "com/demo/UserMapper.java",
            """
            package com.demo;
            public interface UserMapper {
                Object selectById(Long id);
            }
            """.trimIndent(),
        )
        val methodName = PsiTreeUtil.findChildrenOfType(iface, com.intellij.psi.PsiIdentifier::class.java)
            .first { it.text == "selectById" }
        val tags = ConvertToSqlAction().mapperXmlTagsFor(methodName)
        assertEquals("兜底锚点应命中间名 XML 语句", 1, tags.size)
        assertEquals("select", tags[0].name)
        assertEquals("selectById", tags[0].getAttributeValue("id"))
    }

    /**
     * 结构守卫（与 [testMapperXml回落排在iBatis之前] 同族，源文本断言）：索引解析只许出现在
     * [ConvertToSqlAction.mapperXmlTagsFor] 辅助函数体内，EDT 回调链只消费预解析结果——
     * 把 `statementTagsFor` 调回回调里就等于让 `FileBasedIndex` 在 EDT 上跑
     * （2026-11-26 真机 SEVERE「Slow operations are prohibited on EDT」，平台点名本动作）。
     */
    fun testMapperXml兜底锚点解析不在EDT回调链() {
        val source = java.io.File(
            "src/main/java/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.java",
        ).readText()
        val occurrences = Regex("MapperNavigationTargets\\.statementTagsFor\\(").findAll(source).count()
        assertEquals("索引解析只能在 mapperXmlTagsFor 辅助函数里出现一次（BGT + ReadAction）", 1, occurrences)
        val helperAt = source.indexOf("public List<XmlTag> mapperXmlTagsFor(")
        val parseAt = source.indexOf("MapperNavigationTargets.statementTagsFor(")
        assertTrue("辅助函数必须存在（helper=$helperAt）", helperAt > 0)
        assertTrue("解析只能落在辅助函数体内（helper=$helperAt parse=$parseAt）", parseAt > helperAt)
        assertTrue(
            "回调链必须消费预解析结果（形参 mapperXmlTags.get()）——传 element 又会回到 EDT 查索引",
            source.contains("tryMapperXmlMethodFallback(project, mapperXmlTags.get(), sourceLocation)"),
        )
    }

    // ---------- T-MapperPreviewDedupAndAnnotationShortcut：注解方法按方法定位（2026-11-26） ----------

    /**
     * 用户报障：「注解 sql 用快捷键预览，弹了列表，没预览选中的 sql」。
     * 光标停在方法声明行（注解在上一行）时，快捷键必须定位到**该方法自己的** SQL 注解。
     */
    fun test注解方法声明行_定位到该方法注解() {
        val file = myFixture.configureByText(
            "UserMapper.java",
            """
            package com.demo;
            import org.apache.ibatis.annotations.Update;
            public interface UserMapper {
                @Update("CREATE TABLE IF NOT EXISTS t_demo (id INT)")
                int createDemoTable();
            }
            """.trimIndent(),
        )
        val methodName = PsiTreeUtil.findChildrenOfType(file, com.intellij.psi.PsiIdentifier::class.java)
            .first { it.text == "createDemoTable" }
        val annotation = ConvertToSqlAction().sqlAnnotationForMethod(methodName)
        assertNotNull("光标在方法名上应定位到该方法的 @Update", annotation)
        assertTrue(
            "应定位到 Update 注解：${annotation!!.text}",
            annotation.text.contains("Update"),
        )
    }

    fun test方法JavaDoc区_同样定位到方法注解() {
        val file = myFixture.configureByText(
            "UserMapper.java",
            """
            package com.demo;
            import org.apache.ibatis.annotations.Select;
            public interface UserMapper {
                /** 说明 */
                @Select("SELECT 1 FROM dual")
                int ping();
            }
            """.trimIndent(),
        )
        val doc = PsiTreeUtil.findChildrenOfType(file, com.intellij.psi.PsiComment::class.java)
            .first { it.text.startsWith("/**") }
        val token = PsiTreeUtil.getDeepestFirst(doc)
        val annotation = ConvertToSqlAction().sqlAnnotationForMethod(token)
        assertNotNull("光标在方法 JavaDoc 里也应定位到方法注解", annotation)
        assertTrue("应定位到 Select 注解：${annotation!!.text}", annotation.text.contains("Select"))
    }

    fun test类体空白处_不定位方法注解() {
        val file = myFixture.configureByText(
            "UserMapper.java",
            """
            package com.demo;
            import org.apache.ibatis.annotations.Select;
            public interface UserMapper {
                @Select("SELECT 1 FROM dual")
                int ping();

            }
            """.trimIndent(),
        )
        val cls = PsiTreeUtil.findChildOfType(file, com.intellij.psi.PsiClass::class.java)!!
        val rbrace = PsiTreeUtil.findChildrenOfType(cls, com.intellij.psi.PsiJavaToken::class.java)
            .last { it.text == "}" }
        assertNull(
            "光标在类体空白处不应定位到方法注解（保留文件级列表兜底）",
            ConvertToSqlAction().sqlAnnotationForMethod(rbrace),
        )
    }

    /** 链序守卫：XML 绑定 → 注解绑定 → iBatis 等家族（注解档在 XML 之后、iBatis 之前）。 */
    fun testMapper注解档排在XML与iBatis之间() {
        val source = java.io.File(
            "src/main/java/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.java",
        ).readText()
        val xml = source.indexOf("!tryMapperXmlMethodFallback(")
        val anno = source.indexOf("!tryMapperAnnotationMethodFallback(")
        val ibatis = source.indexOf("!tryIbatisCallFallback(")
        assertTrue(
            "三档都必须出现在链里（xml=$xml anno=$anno iBatis=$ibatis）",
            xml > 0 && anno > 0 && ibatis > 0,
        )
        assertTrue("XML 档必须先于注解档", xml < anno)
        assertTrue("注解档必须先于 iBatis", anno < ibatis)
    }
}
