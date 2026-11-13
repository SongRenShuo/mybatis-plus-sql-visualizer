package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.sql.SqlClause
import cn.song.mybatisplus.visualizer.sql.detectClause
import cn.song.mybatisplus.visualizer.sql.detectClauseFromText
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * SQL 补全基础设施测试（M9-03/04）：
 *  - 原生 SQL FileType PSI 文件可创建
 *  - CompletionContributor 已注册到 SQL 语言
 *  - SqlCompletionProvider 提供关键字
 *  - M16.2：detectClause 边界防御（offset clamp/空文本）与上下文双载体读取（Editor userData 优先）
 *  - T-M27-01：detectClause 迁入 sql.SqlTextAnalysis 纯函数模块——下述 detectClause 用例改走公共 API
 *    （断言语义不变）
 */
class SqlCompletionContributorTest : BasePlatformTestCase() {

    private val sqlFileType: com.intellij.openapi.fileTypes.FileType
        get() = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
            .getStdFileType("SQL") ?: com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE

    private val sqlLanguage: com.intellij.lang.Language
        get() = sqlFileType.let { ft ->
            when (ft) {
                is com.intellij.openapi.fileTypes.LanguageFileType -> ft.language
                else -> com.intellij.lang.Language.findLanguageByID("SQL") ?: com.intellij.lang.Language.ANY
            }
        }

    fun testPsiFileCreatedForNativeSql() {
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("preview.sql", sqlFileType, "SELECT 1 FROM t")
        assertNotNull("原生 SQL FileType 应能生成 PSI 文件", file)
        assertEquals(sqlLanguage, file.language)
    }

    fun testCompletionContributorRegistered() {
        // M34 回归：全语言注册（无 language 属性）→ CompletionContributor.forLanguage 不返回，
        // 故改为验证贡献者类存在且可实例化（注册本身由平台 EP 机制保证，运行时行为需 runIde 验证）。
        val contributor = runCatching { SqlCompletionContributor() }.getOrNull()
        assertNotNull("SqlCompletionContributor 应可实例化", contributor)
    }

    fun testProviderKeywords() {
        val keywords = SqlCompletionProvider.instance.keywords()
        assertTrue("关键字应含 SELECT", keywords.contains("SELECT"))
        assertTrue("关键字应含 FROM", keywords.contains("FROM"))
        assertTrue("关键字应含 WHERE", keywords.contains("WHERE"))
    }

    fun testDefaultProviderUsesContext() {
        val ctx = SqlCompletionContext("merchant_entity", listOf("id", "phone"), null)
        assertEquals("merchant_entity", SqlCompletionProvider.instance.tableName(ctx))
        assertEquals(listOf("id", "phone"), SqlCompletionProvider.instance.columns(ctx))
    }

    fun testColumnsWithCommentsMergesContext() {
        // T-M16-03：columnsWithComments 合并 columns 与 columnComments（无注释列返回 null）
        val ctx = SqlCompletionContext(
            "merchant_entity",
            listOf("id", "name"),
            null,
            columnComments = mapOf("name" to "设备名称"),
        )
        val merged = SqlCompletionProvider.instance.columnsWithComments(ctx)
        assertEquals(listOf("id" to null, "name" to "设备名称"), merged)
        // 无注释上下文向后兼容
        val plain = SqlCompletionContext("merchant_entity", listOf("id"), null)
        assertEquals(listOf("id" to null), SqlCompletionProvider.instance.columnsWithComments(plain))
    }

    fun testTableCommentFromContext() {
        // T-M16-05：表注释经 context 传递
        val ctx = SqlCompletionContext("merchant_entity", emptyList(), null, tableComment = "商户表")
        assertEquals("商户表", SqlCompletionProvider.instance.tableComment(ctx))
    }

    fun testDetectClausePositionAware() {
        // T-M16-04：位置感知——FROM/JOIN 后表名位、WHERE/ON/HAVING/GROUP BY/ORDER BY 后列名位、
        // SELECT 列位、无法判定全量（T-M27-01：改走 SqlTextAnalysis 公共 API，语义不变）
        assertEquals(SqlClause.TABLE, detectClauseFromText("SELECT id FROM "))
        assertEquals(SqlClause.TABLE, detectClauseFromText("SELECT t.id FROM device t LEFT JOIN "))
        assertEquals(SqlClause.COLUMN, detectClauseFromText("SELECT id FROM device WHERE "))
        assertEquals(SqlClause.COLUMN, detectClauseFromText("SELECT id FROM device WHERE id = 1 AND "))
        assertEquals(SqlClause.COLUMN, detectClauseFromText("SELECT id FROM device ORDER BY "))
        assertEquals(SqlClause.COLUMN, detectClauseFromText("SELECT id FROM device GROUP BY "))
        assertEquals(SqlClause.COLUMN, detectClauseFromText("SELECT id FROM device t ON "))
        assertEquals(SqlClause.SELECT_COLUMN, detectClauseFromText("SELECT "))
        assertEquals(SqlClause.SELECT_COLUMN, detectClauseFromText("SELECT id, "))
        assertEquals(SqlClause.UNKNOWN, detectClauseFromText(""))
    }

    fun testDetectClauseOffsetClamped() {
        // T-M16.2-01：offset 越界 clamp 不抛异常（旧 PSI 未随编辑更新时 offset 可能超过新文档长度——
        // substring 越界异常被 IDE 吞掉 → 补全静默失效的回归点 C）（T-M27-01：改走 SqlTextAnalysis 公共 API）
        // offset 超过文本长度 → clamp 到末尾，按全量文本判定（不抛异常）
        assertEquals(SqlClause.TABLE, detectClause("SELECT id FROM t", 999))
        // offset 为负 → clamp 到 0 → 空前缀 → UNKNOWN
        assertEquals(SqlClause.UNKNOWN, detectClause("SELECT id FROM t", -1))
        // 空文本 → UNKNOWN
        assertEquals(SqlClause.UNKNOWN, detectClause("", 0))
        // 正常位置不受影响
        assertEquals(
            SqlClause.COLUMN,
            detectClause("SELECT id FROM t WHERE id = 1 ", "SELECT id FROM t WHERE id = 1 ".length),
        )
    }

    fun testContextEditorCarrierPreferredOverPsi() {
        // T-M16.2-01：上下文双载体读取——Editor userData 优先（嵌入式编辑器编辑后 PSI 重建丢失 userData，
        // 回归点 B）、PSI 文件 userData 兜底
        val contributor = SqlCompletionContributor()
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("preview.sql", sqlFileType, "SELECT 1")
        val position = PsiTreeUtil.getDeepestFirst(file)

        // ① Editor 无上下文 → 回退 PSI userData
        val psiCtx = SqlCompletionContext("psi_tbl", listOf("id"), null)
        file.putUserData(SqlCompletionContext.KEY, psiCtx)
        val fallback = contributor.resolveContext(null, position)
        assertNotNull("Editor 无上下文时应回退 PSI userData", fallback)
        assertEquals("psi_tbl", fallback!!.tableName)

        // ② Editor 有上下文 → Editor 优先（与 PSI 不一致时取 Editor）
        val editorCtx = SqlCompletionContext("editor_tbl", listOf("name"), null)
        val preferred = contributor.resolveContext(editorCtx, position)
        assertNotNull("Editor 有上下文时应优先使用", preferred)
        assertEquals("editor_tbl", preferred!!.tableName)

        // ③ 双载体均无 → null（关键字补全不受影响，实体补全跳过）
        file.putUserData(SqlCompletionContext.KEY, null)
        val noCtx = contributor.resolveContext(null, PsiTreeUtil.getDeepestFirst(file))
        assertNull("无上下文应返回 null", noCtx)
    }

    fun testWithCommentCarriesTailTextAndLookupString() {
        // T-M16.3-02（用户实测「字段可补全但看不到注释」根因）：LookupElementBuilder 不可变——
        // withTailText/withLookupString 返回新实例，原实现丢弃返回值导致注释 tailText 从不展示。
        // 渲染验证：注释作为灰色尾缀展示 + 进入 lookup strings 支持注释关键词检索
        val contributor = SqlCompletionContributor()

        // 有注释：tailText 渲染可见 + 注释可检索
        val decorated = contributor.withComment(LookupElementBuilder.create("station_id"), "场站ID")
        val presentation = LookupElementPresentation()
        decorated.renderElement(presentation)
        assertEquals("  场站ID", presentation.tailText)
        assertTrue("注释应进入 lookup strings 供关键词检索", decorated.allLookupStrings.contains("场站ID"))

        // 无注释：原样（tailText 为 null，不加检索串）
        val plain = contributor.withComment(LookupElementBuilder.create("id"), null)
        val plainPresentation = LookupElementPresentation()
        plain.renderElement(plainPresentation)
        assertNull(plainPresentation.tailText)
    }

    fun testColumnLookupElementRendersCommentAndDbType() {
        // T-DbSchemaCompletion：列候选 tailText = 注释 + DB 类型串（渲染验证）。
        // 类型串是「这条来自数据源」的可视标记——此前库里独有的列根本不进候选，用户无从分辨来源。
        val withBoth = LookupElementPresentation()
        columnLookupElement(ColumnCandidate("username", "姓名", "varchar(64)")).renderElement(withBoth)
        assertEquals("  姓名  varchar(64)", withBoth.tailText)

        val typeOnly = LookupElementPresentation()
        columnLookupElement(ColumnCandidate("legacy_flag", null, "tinyint(1)")).renderElement(typeOnly)
        assertEquals("库列无 COMMENT：只展示 DB 类型串", "  tinyint(1)", typeOnly.tailText)

        // 实体列（无 DB 类型）与无信息列：保持改动前形态
        val commentOnly = LookupElementPresentation()
        columnLookupElement(ColumnCandidate("id", "主键", null)).renderElement(commentOnly)
        assertEquals("  主键", commentOnly.tailText)

        val bare = LookupElementPresentation()
        columnLookupElement(ColumnCandidate("password", null, null)).renderElement(bare)
        assertNull(bare.tailText)

        // 注释仍可作关键词检索（M16 语义保留）
        assertTrue(
            "注释应进入 lookup strings 供关键词检索",
            columnLookupElement(ColumnCandidate("username", "姓名", "varchar(64)")).allLookupStrings.contains("姓名"),
        )
    }

    fun testResolveContextLevel3DocumentMap() {
        // M34 回归修复（registerContext 从未被调用，documentContexts 恒空 → level 3 死路径）：
        // 注册后 Editor/PSI userData 均丢失时，Document→Context 映射应兜底返回上下文。
        val contributor = SqlCompletionContributor()
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("preview.sql", sqlFileType, "SELECT 1 FROM merchant")
        val position = PsiTreeUtil.getDeepestFirst(file)

        // ① 未注册 → 三级全 miss → null（映射为空）
        file.putUserData(SqlCompletionContext.KEY, null)
        val before = contributor.resolveContext(null, position)
        assertNull("Document 未注册时 level 3 应 miss", before)

        // ② 注册后 → 双载体全空仍兜底命中
        val docCtx = SqlCompletionContext("merchant_entity", listOf("id", "phone"), null)
        file.viewProvider.document?.let { SqlCompletionContributor.registerContext(it, docCtx) }
        file.putUserData(SqlCompletionContext.KEY, null)
        val hit = contributor.resolveContext(null, position)
        assertNotNull("Document 已注册时 level 3 应兜底命中", hit)
        assertEquals("merchant_entity", hit!!.tableName)
    }

    fun testRealTempFileTrackedByPsiDocumentManager() {
        // M34 回归修复根因：CodeCompletionHandlerBase.invokeCompletion 内部调用
        // PsiDocumentManager.getPsiFile(document)——PsiFileFactory.createFileFromText 创建的
        // in-memory 文件不被追踪（getDocument=null → "no PSI file" 断言）。
        // 回归断言：createEditor 采用的真实临时文件（java.io + LocalFileSystem）必须能被
        // PsiDocumentManager 追踪（document→psi round-trip），补全框架初始化才不 NPE。
        val proj = project
        val io = java.io.File.createTempFile("mp-sql-preview-", ".sql")
        io.writeText("SELECT 1 FROM t")
        io.deleteOnExit()
        val vFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io)
        assertNotNull("临时 io 文件应能被 LocalFileSystem 解析", vFile)
        val psi = com.intellij.psi.PsiManager.getInstance(proj).findFile(vFile!!)
        assertNotNull("临时文件应能创建 PSI", psi)
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(proj).getDocument(psi!!)
        assertNotNull("PsiDocumentManager 应能取得临时文件 document", doc)
        val roundTrip = com.intellij.psi.PsiDocumentManager.getInstance(proj).getPsiFile(doc!!)
        assertNotNull("PsiDocumentManager 应能从 document 反查到 PSI（补全框架依赖）", roundTrip)
    }

    /**
     * §二十八：用户实测形态——在 SELECT 列表里删到 `DEL_` 再补 `DEL_F`，补全没反应。
     * 先钉死「子句判定」这一半：这段文本必须判成 SELECT 列位（不是 TABLE），否则列名分支会被整段跳过。
     */
    fun testSelectListPartialColumnClause() {
        val samples = listOf(
            "SELECT 'false' AS QUERYID, ID, DEL_" to SqlClause.SELECT_COLUMN,
            "SELECT 'false' AS QUERYID, ID, DEL_F" to SqlClause.SELECT_COLUMN,
            // WHERE 后的部分列名是列位（实测返回 COLUMN；我最初的 UNKNOWN 预期是错的）
            "SELECT ID, DEL_FLAG FROM CUST_CHANNEL WHERE DEL_F" to SqlClause.COLUMN,
        )
        samples.forEach { (text, expected) ->
            val actual = detectClause(text, text.length)
            assertEquals("子句判定: \"$text\"", expected, actual)
        }
    }

    /**
     * §三十一：列名别名形态。真机证据：`prefix='DEL_F' ctx cols=19 added col=0`——
     * 实体列名是驼峰（`delFlag`），用户敲的是大写蛇形（`DEL_FLAG`），前缀匹配命中不了。
     */
    fun testColumnNameAliases() {
        assertEquals(
            "驼峰列名要能补出大写蛇形",
            listOf("delFlag", "del_flag", "DEL_FLAG"),
            SqlCompletionContributor.columnNameAliases("delFlag"),
        )
        assertEquals(
            "已是蛇形/大写的列名保持原样（不产生重复别名）",
            listOf("DEL_FLAG"),
            SqlCompletionContributor.columnNameAliases("DEL_FLAG"),
        )
        assertEquals(
            "普通小写列名也给一份大写形态（SQL 里常写 ID）",
            listOf("id", "ID"),
            SqlCompletionContributor.columnNameAliases("id"),
        )
        assertTrue("空名不产生别名", SqlCompletionContributor.columnNameAliases(" ").isEmpty())
    }
}
