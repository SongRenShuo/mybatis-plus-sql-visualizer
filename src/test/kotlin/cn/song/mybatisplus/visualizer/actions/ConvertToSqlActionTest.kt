package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisCallTargets
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisStatementPreview
import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets
import cn.song.mybatisplus.visualizer.ui.nav.MapperNavigationTargets
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.testFramework.MapDataContext
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 转换 Action（正向 Wrapper→SQL）update() 可见性测试（F-44 续 · 补用例大波次）：
 * 此前 0 测试。update 读取 PSI_FILE 决定启用范围（Java 文件 / XML mapper 可用，其它文件不可用）。
 * 用 MapDataContext 构造 AnActionEvent，确定性校验可见性逻辑，不触发 actionPerformed。
 */
class ConvertToSqlActionTest : BasePlatformTestCase() {

    private fun eventFor(file: com.intellij.psi.PsiFile?, editor: com.intellij.openapi.editor.Editor?): AnActionEvent {
        val dc = MapDataContext()
        dc.put(CommonDataKeys.PROJECT.name, project)
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

    fun testUpdate_Kotlin文件主动入口可达且命中Wrapper时给专用降级说明() {
        myFixture.configureByText(
            "Demo.kt",
            """
            fun query() {
                service.ktQuery().eq(User::name, "x").list()
            }
            """.trimIndent(),
        )
        val offset = myFixture.file.text.indexOf("ktQuery")
        myFixture.editor.caretModel.moveToOffset(offset)
        val event = eventFor(myFixture.file, myFixture.editor)
        ConvertToSqlAction().update(event)
        assertTrue("Kotlin 主动快捷入口应保持可达", event.presentation.isEnabledAndVisible)
        assertTrue(ConvertToSqlAction.kotlinWrapperUnavailableAt(myFixture.file, offset))

        val captured = java.util.concurrent.atomic.AtomicReference<com.intellij.notification.Notification?>()
        ConvertToSqlAction.kotlinWrapperUnavailableNoticeForTest = { captured.set(it) }
        try {
            ConvertToSqlAction().actionPerformed(event)
            PlatformTestUtil.waitWithEventsDispatching(
                "Kotlin Wrapper 主动入口应给降级说明",
                { captured.get() != null },
                5_000,
            )
            val notification = captured.get()!!
            assertEquals(MpSqlVisualizerBundle.message("action.kotlinWrapperUnsupported.title"), notification.title)
            assertEquals(MpSqlVisualizerBundle.message("action.kotlinWrapperUnsupported.message"), notification.content)
        } finally {
            ConvertToSqlAction.kotlinWrapperUnavailableNoticeForTest = null
        }
    }

    fun testUpdate_Kotlin普通行不误判Wrapper降级() {
        myFixture.configureByText("Demo.kt", "fun query() = println(\"ok\")")
        val offset = myFixture.file.text.indexOf("println")
        assertFalse(ConvertToSqlAction.kotlinWrapperUnavailableAt(myFixture.file, offset))
    }

    fun testUpdate_非Java非Xml文件不可见() {
        myFixture.configureByText("a.txt", "x")
        val e = eventFor(myFixture.file, myFixture.editor)
        ConvertToSqlAction().update(e)
        assertFalse("普通文本文件不应启用", e.presentation.isEnabledAndVisible)
    }

    /**
     * T-Ibatis2Support 券 A 收尾：XML 候选判据三路来源。
     *
     * 背景＝通用兜底通道对 iBatis `<sqlMap>` 语句**让位**（防与专用 gutter provider 双图标）后，
     * 若快捷键候选仍只认「MyBatis 语句 + 兜底载体」，Alt+Shift+Q 在 sqlMap 文件里会**空候选**——
     * gutter 图标正常、快捷键什么都列不出来。本用例把三路来源焊死在单一判据上。
     */
    fun testXmlStatementCandidate_三路来源与边界() {
        myFixture.configureByText(
            "ibatis.xml",
            """
            <sqlMap namespace="user">
              <resultMap id="rm" class="A"/>
              <sql id="cols">ID, NAME</sql>
              <select id="leaf">SELECT 1 FROM T</select>
              <select id="dyn">
                WHERE
                <dynamic prepend="AND">
                  <isNotNull property="id">ID = #id#</isNotNull>
                </dynamic>
              </select>
              <statement id="raw">SELECT * FROM T WHERE ID = ?</statement>
            </sqlMap>
            """.trimIndent(),
        )
        val tags = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
            myFixture.file,
            com.intellij.psi.xml.XmlTag::class.java,
        )
        fun tag(name: String, id: String? = null) = tags.first {
            it.name == name && (id == null || it.getAttributeValue("id") == id)
        }

        // iBatis 语句：叶子与含动态子标签两种形态都必须进候选（后者正是用户报障的零图标形态）
        assertTrue("iBatis 叶子 select 应进候选", ConvertToSqlAction.isXmlStatementCandidate(tag("select", "leaf")))
        assertTrue("含 <dynamic> 的 select 也应进候选", ConvertToSqlAction.isXmlStatementCandidate(tag("select", "dyn")))
        assertTrue("iBatis <statement> 应进候选", ConvertToSqlAction.isXmlStatementCandidate(tag("statement", "raw")))
        // 非语句：resultMap / <sql> 片段 / <dynamic> 自身都不该进候选
        assertFalse("resultMap 不是语句", ConvertToSqlAction.isXmlStatementCandidate(tag("resultMap")))
        assertFalse("<sql> 片段不是语句", ConvertToSqlAction.isXmlStatementCandidate(tag("sql")))
        assertFalse("<dynamic> 不是语句", ConvertToSqlAction.isXmlStatementCandidate(tag("dynamic")))
    }

    /** MyBatis 路径零回归：`<mapper>` 下语句仍进候选（判据重构不得改变既有来源）。 */
    fun testXmlStatementCandidate_MyBatis路径不变() {
        myFixture.configureByText(
            "UserMapper.xml",
            """
            <mapper namespace="demo.UserMapper">
              <select id="byId">SELECT * FROM t_user WHERE id = #{id}</select>
              <sql id="cols">id, name</sql>
            </mapper>
            """.trimIndent(),
        )
        val tags = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
            myFixture.file,
            com.intellij.psi.xml.XmlTag::class.java,
        )
        val select = tags.first { it.name == "select" }
        assertTrue("<mapper> 下语句应进候选", ConvertToSqlAction.isXmlStatementCandidate(select))
    }

    /**
     * T-IbatisCallPreview：iBatis DAO 调用行的形状门必须在 JDBC 通道之前认领。
     *
     * 现状（修复前）：`JdbcTemplateSqlLocator` **没有** iBatis 让位门 → 它认领该调用后
     * `JdbcTemplateSqlPreviewService.preview` 用 `isLikelySql("User.selectById")` 拒绝 → 动作
     * 已 `return true` → 用户拿到「未找到可转换的 Wrapper」（错源提示）。
     * 本用例把「JDBC 会认领但渲染不出东西」与「iBatis 门能渲染出 SQL」两侧同时钉住。
     */
    fun testiBatis调用行_预览门先于JDBC通道() {
        myFixture.addFileToProject(
            "sqlmap/User.xml",
            """<sqlMap namespace="User"><select id="selectById">SELECT ID FROM T_USER WHERE ID = #id#</select></sqlMap>""",
        )
        val dao = myFixture.addFileToProject(
            "com/demo/UserDaoImpl.java",
            """
            package com.demo;
            public class UserDaoImpl {
                private Object client;
                public Object selectById(int id) { return client.queryForObject("User.selectById", id); }
            }
            """.trimIndent(),
        )
        IbatisXmlStatementTargets.resetPerfLogForTest()
        val literal = PsiTreeUtil.findChildrenOfType(dao, PsiLiteralExpression::class.java)
            .first { it.value == "User.selectById" }

        // 对照：JDBC 通道会认领这个调用（所以「谁先认领」决定用户看到什么）
        val jdbcCall = JdbcTemplateSqlLocator.locate(literal)
        assertNotNull("夹具前提：JdbcTemplateSqlLocator 不拦 iBatis 调用", jdbcCall)
        assertNull(
            "JDBC 通道对 statementId 渲染不出 SQL（错源提示的来源）",
            JdbcTemplateSqlPreviewService.preview(jdbcCall!!, literal),
        )

        // 新门：形状门（与 gutter 让位同一函数）认领并渲染出 iBatis SQL
        val target = IbatisCallTargets.resolve(literal)
        assertNotNull("iBatis 形状门应认领该调用", target)
        assertEquals("User.selectById", target!!.statementId)
        val preview = IbatisStatementPreview.previewResolved(project, target.statementId, target.ownerNamespace)
        assertTrue("iBatis 预览应渲染出 SQL：${preview.sql}", preview.sql.contains("FROM T_USER"))
        assertEquals(listOf("id"), preview.unresolvedParams)
    }

    /**
     * T-IbatisAtShapeGate：`组名@命名空间.语句id` 形态在**快捷键回落链**上必须被 iBatis 认领。
     *
     * 真机报障：`baseDao.queryForObject("cQAccountTypeByAccountType@custmg.qAccountTypeByAccountType", inMap)`
     * ——gutter 图标点击正常，Alt+Shift+Q 弹「未在光标处找到可预览的 SQL 源」。根因是形状门正则
     * 不含 `@`：iBatis 两档回落都放弃，`JdbcTemplateSqlLocator`（action 侧无让位门）认领后
     * `isLikelySql` 拒绝 → 动作已 return true → 错源提示。
     *
     * 三段断言对齐缺陷链：① JDBC 会认领且渲染不出（错源来源，夹具前提）；② 形状门认领并渲染出 SQL；
     * ③ 参数解析到 `#accountType#`。
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
            target!!.statementId,
        )
        val preview = IbatisStatementPreview.previewResolved(project, target.statementId, target.ownerNamespace)
        assertTrue("iBatis 预览应渲染出 SQL：${preview.sql}", preview.sql.contains("FROM T_ACCOUNT_TYPE"))
        assertEquals(listOf("accountType"), preview.unresolvedParams)
    }

    /**
     * T-IbatisAtShapeGate S4：**错源兜底**——JDBC 通道认领且渲染失败后，跳过形状正则再试一次 iBatis。
     *
     * 场景：语句名含形状门不认的字符（本例 `select-by-id` 的 `-`）。常规认领门（[IbatisCallTargets.resolve]）
     * 放弃 → 快捷键回落到 JDBC → 认领后 `isLikelySql` 拒绝 → 修复前只弹「未在光标处找到可预览的 SQL 源」。
     * 兜底口径（[IbatisCallTargets.resolveIgnoringShape]）只判入口方法名 + 首参字面量，存在性交给解析链：
     * 命中才接管、未命中保持原提示，故非 iBatis 工程不受影响。
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
        val preview = IbatisStatementPreview.previewResolvedOrNull(project, target!!.statementId, target.ownerNamespace)
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
            IbatisStatementPreview.previewResolvedOrNull(project, target!!.statementId, target.ownerNamespace),
        )
    }

    /**
     * 结构守卫（S4）：错源兜底必须真的接在 `tryJdbcTemplateFallback` 的失败分支上——
     * 行为用例只能证明兜底函数本身，接线顺序/存在性只能在源码上钉。
     */
    fun test错源兜底接线在JdbcTemplate失败分支() {
        val source = java.io.File(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.kt",
        ).readText()
        val fnStart = source.indexOf("private fun tryJdbcTemplateFallback(")
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

    /**
     * 结构守卫（T-IbatisCallPreview）：iBatis 两档回落必须排在 `tryJdbcTemplateFallback` **之前**。
     *
     * 这是本轮缺陷的根因——`JdbcTemplateSqlLocator` 没有 iBatis 让位门，先认领会把 statementId
     * 当 SQL 渲染并得到 null，而动作已 `return true`，用户只看到「未找到」。行为用例只能证明
     * 形状门本身，认领**顺序**只能在源码上钉：`!` 前缀只出现在回落链里，函数定义行不含它。
     */
    fun testiBatis两档回落排在JdbcTemplate之前() {
        val source = java.io.File(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.kt",
        ).readText()
        val ibatisCall = source.indexOf("!tryIbatisCallFallback(")
        val ibatisMethod = source.indexOf("!tryIbatisMethodFallback(")
        val jdbc = source.indexOf("!tryJdbcTemplateFallback(")
        assertTrue(
            "三档回落都必须出现在链里（call=$ibatisCall method=$ibatisMethod jdbc=$jdbc）",
            ibatisCall > 0 && ibatisMethod > 0 && jdbc > 0,
        )
        assertTrue("调用行 iBatis 回落必须先于 JdbcTemplate", ibatisCall < jdbc)
        assertTrue("方法级 iBatis 回落同样先于 JdbcTemplate", ibatisMethod < jdbc)
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
        val preview = XmlPreviewService.preview(xmlFile.text, tags[0].getAttributeValue("id"), project)
        assertTrue("快捷键回落应渲染出该语句 SQL：${preview.sql}", preview.sql.contains("FROM T_USER"))
    }

    /** 结构守卫：Mapper XML 回落必须排在 iBatis 两档之前（无 body 的接口方法先按 XML 绑定认领）。 */
    fun testMapperXml回落排在iBatis之前() {
        val source = java.io.File(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.kt",
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
            "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.kt",
        ).readText()
        val occurrences = Regex("MapperNavigationTargets\\.statementTagsFor\\(").findAll(source).count()
        assertEquals("索引解析只能在 mapperXmlTagsFor 辅助函数里出现一次（BGT + ReadAction）", 1, occurrences)
        val helperAt = source.indexOf("internal fun mapperXmlTagsFor(")
        val parseAt = source.indexOf("MapperNavigationTargets.statementTagsFor(")
        assertTrue("辅助函数必须存在（helper=$helperAt）", helperAt > 0)
        assertTrue("解析只能落在辅助函数体内（helper=$helperAt parse=$parseAt）", parseAt > helperAt)
        assertTrue(
            "回调链必须消费预解析结果（形参 mapperXmlTags.get()）——传 element 又会回到 EDT 查索引",
            source.contains("tryMapperXmlMethodFallback(project, mapperXmlTags.get(), sourceLocation)"),
        )
    }

    // ---------- T-PerfSweep34 券01：Repository 声明行兜底的 EDT 链（2026-11-26） ----------

    /**
     * 结构守卫（与 [testMapperXml兜底锚点解析不在EDT回调链] 同族，源文本断言）。
     *
     * L110 只修了兜底链第一条（Mapper XML），第 8 条 `tryJpaFallback` 的第 5 步
     * （Repository 声明行）仍在 EDT 回调里同步跑 `previewRepositoryDeclaration`——该链会查
     * `FilenameIndex` 并 `readText` 读配置文件（见 `HibernateNamingConfigProbe.projectUsesComponentPath`），
     * 与 L110 是同一回调、同一类操作。
     *
     * 钉四件事：①`previewRepositoryDeclaration`（会记历史的整链入口）不得出现在本文件；
     * ②纯计算入口 `computeRepositoryDeclaration` 只许出现在 `probeRepositoryDeclaration` 辅助函数里；
     * ③**compute 块必须真的接线**（`repositoryDeclaration.set(probeRepositoryDeclaration(...))` 至少一次，
     * 且落在 compute lambda 内）——只留 helper 但不调用，等于把兜底悄悄关掉，这一条正是防止
     * 「反向验证空绿」的形态（T-PerfSweep34 实施期实测：只删调用点时前三条仍全绿）；
     * ④回调链必须消费预解析产物 `repositoryDeclaration.get()`。
     */
    fun testRepository声明兜底解析不在EDT回调链() {
        val source = java.io.File(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.kt",
        ).readText()
        assertEquals(
            "回调链不得直调 previewRepositoryDeclaration（那会在 EDT 上查索引 + 读配置）",
            0,
            Regex("previewRepositoryDeclaration\\(").findAll(source).count(),
        )
        assertEquals(
            "纯计算只能在 probeRepositoryDeclaration 辅助函数里出现一次（BGT + ReadAction）",
            1,
            Regex("computeRepositoryDeclaration\\(").findAll(source).count(),
        )
        val helperAt = source.indexOf("private fun probeRepositoryDeclaration(")
        val computeAt = source.indexOf("computeRepositoryDeclaration(")
        assertTrue("辅助函数必须存在（helper=$helperAt）", helperAt > 0)
        assertTrue("计算只能落在辅助函数体内（helper=$helperAt compute=$computeAt）", computeAt > helperAt)
        // ③ compute 块必须真的调用 helper（helper 存在但没人调 = 兜底被静默关掉）
        val probeCalls = Regex("repositoryDeclaration\\.set\\(probeRepositoryDeclaration\\(")
            .findAll(source).count()
        assertTrue(
            "compute 块必须接线 probeRepositoryDeclaration（实际 $probeCalls 处）——" +
                "只留 helper 不调用等于把 Repository 声明行兜底静默关掉",
            probeCalls >= 1,
        )
        assertTrue(
            "回调链必须消费预解析结果（形参 repositoryDeclaration.get()）",
            source.contains("tryJpaFallback(service, project, element, repositoryDeclaration.get(), sourceLocation)"),
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
            "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.kt",
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
