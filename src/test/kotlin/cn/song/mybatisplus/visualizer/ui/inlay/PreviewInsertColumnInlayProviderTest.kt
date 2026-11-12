package cn.song.mybatisplus.visualizer.ui.inlay

import cn.song.mybatisplus.visualizer.xml.XmlStatementKind
import com.intellij.codeInsight.hints.CollectorWithSettings
import com.intellij.codeInsight.hints.InlayDumpUtil
import com.intellij.codeInsight.hints.InlayHintsSinkImpl
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * PreviewInsertColumnInlayProvider 作用域/闸门测试（T-InsertColumnInlay O-44b）。
 *
 * **本任务的硬判据**（计划 §四 验收 1）：用户自己的 `.sql` 文件**不得**出现本插件的 inlay。
 * inlay 的 EP 是 language 级的（`com.intellij.codeInsight.inlayProvider` + `language="SQL"`），
 * 注册即对 IDE 内所有 SQL 编辑器生效，故作用域判定是 provider 的第一道闸门，也是本文件的主体。
 *
 * 判据形态取「**有无 collector**」：provider 返回 null ⇒ 平台侧零 inlay 条目（不是「产出了再过滤」）。
 * 正向判据取**真实渲染链**：`CollectorWithSettings.collectTraversingAndApply` 把提示投进真实
 * `InlayModel`，再由 `InlayDumpUtil` 读回文本（平台自家 `InlayHintsProviderTestCase` 同款链路）——
 * 即「提示确实落到编辑器上」，而非只断言内部数据。
 *
 * 覆盖矩阵（对应计划 §四 验收 1~4 与 §三 T-ICI-04 用例 ①②③④）：
 *  1. 主路径预览文件（一级名，形态 `mpsql-preview-<pid>-<seq>.sql`，见
 *     [PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE]）+ userData + INSERT → 出提示（端到端渲染）；
 *  2. 二级内存 PSI 文件名（`mpsql-preview-memory.sql`）→ 同样出提示（锚点覆盖降级链第二级）；
 *  3. **用户文件**（普通名 / 测试缝名 `preview.sql`）→ 零提示（含「人为挂上 userData」的最强形态：
 *     文件名锚点单独即可拦下）；反向对照 = 同一条 SQL 换个文件名就从有提示变无提示；
 *  4. 预览文件名但**缺 userData** → 零提示（双锚点缺一不可）；
 *  5. `kind != INSERT`（SELECT/UPDATE/DELETE）与无语句类型载体（非 XML 预览链）→ 零提示；
 *  6. **形态判据紧致性**（T-FixN2 合并新增）：一级形态判定不接受内存名 / 旧固定名 / 原生控制台
 *     临时名 / 非数字段，且 `mpsql-exec.sql` 即便挂上载体也零提示（已知边界不得变成错误渲染）。
 */
class PreviewInsertColumnInlayProviderTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // T-PreviewInlayNativeYield：测试平台（IU 2024.3.7.1）上 DatabaseTools 的 SQL 列名 inlay 在位，
        // 让位门会整体短路 provider，本类全部正向用例会变成「零提示」假红。故先钉死「原生不在位」；
        // 「在位 ⇒ 零提示」由本类 test原生列名inlay在位时整体让位_零提示 与计划 §七 真机 A1 覆盖。
        NativeColumnInlayYieldGate.resetForTest()
        NativeColumnInlayYieldGate.probe = { false }
    }

    override fun tearDown() {
        try {
            NativeColumnInlayYieldGate.resetForTest()
        } finally {
            super.tearDown()
        }
    }

    private val provider = PreviewInsertColumnInlayProvider()

    private val insertSql = "INSERT INTO user (id, name) VALUES (1, 'admin')"

    /**
     * 走真实渲染链取提示文本；无 collector（作用域/类型闸门拦下）返回 null。
     *
     * @param attachKind 是否把语句类型写到 Editor 与 PSI 文件双载体（模拟面板
     *   `SqlPreviewPanel.attachPreviewStatementKind` 的写入）；false = 模拟「非本面板编辑器」
     */
    private fun renderHints(fileName: String, sql: String, kind: XmlStatementKind?, attachKind: Boolean = true): String? {
        myFixture.configureByText(fileName, sql)
        val file = myFixture.file
        val editor = myFixture.editor
        if (attachKind) {
            PreviewInlayScope.attach(editor, kind)
            PreviewInlayScope.attach(file, kind)
        }
        val sink = InlayHintsSinkImpl(editor)
        val collector = provider.getCollectorFor(file, editor, NoSettings(), sink) ?: return null
        CollectorWithSettings(collector, provider.key, file.language, sink).collectTraversingAndApply(editor, file, true)
        return InlayDumpUtil.dumpHintsInternal(
            sql,
            file = file,
            editor = editor,
            document = myFixture.getDocument(file),
        )
    }

    // ------------------------------------------------------------------
    // 正向：预览文件出提示（验收 2）
    // ------------------------------------------------------------------

    fun test主路径预览文件INSERT出目标列提示() {
        val dumped = renderHints(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, insertSql, XmlStatementKind.INSERT)
        assertEquals(
            "预览文件上的 INSERT 应逐值表达式挂目标列名（inlay 文本 = 列名）",
            "INSERT INTO user (id, name) VALUES (/*<# id #>*/1, /*<# name #>*/'admin')",
            dumped,
        )
    }

    fun test二级内存PSI文件名同样出提示() {
        val dumped = renderHints(PreviewInlayScope.MEMORY_PREVIEW_FILE_NAME, insertSql, XmlStatementKind.INSERT)
        assertEquals(
            "降级链第二级（内存 PSI，mpsql-preview-memory.sql）锚点同样命中——" +
                "保留名漏掉它即该级 inlay 静默不出现",
            "INSERT INTO user (id, name) VALUES (/*<# id #>*/1, /*<# name #>*/'admin')",
            dumped,
        )
    }

    fun test投影自带同名列别名时不重复提示() {
        val dumped = renderHints(
            PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE,
            "INSERT INTO user (id, name) SELECT x AS id, y FROM s",
            XmlStatementKind.INSERT,
        )
        assertEquals(
            "验收 3：`x AS id` 已自带同名列别名 id → 该投影不挂提示；`y` 照常挂 name",
            "INSERT INTO user (id, name) SELECT x AS id, /*<# name #>*/y FROM s",
            dumped,
        )
    }

    // ------------------------------------------------------------------
    // 硬判据：用户自己的 .sql 文件零提示（验收 1）
    // ------------------------------------------------------------------

    fun test用户自己的SQL文件不产提示() {
        assertNull(
            "用户自己的 .sql 文件（文件名非本插件保留名）不得出现本插件 inlay——" +
                "EP 是 language 级，这是本任务唯一的真实风险",
            renderHints("UserMapper.sql", insertSql, XmlStatementKind.INSERT),
        )
    }

    fun test用户文件即使被挂上语句类型载体也不产提示() {
        // 最强形态：userData 锚点被人为满足，仅靠文件名锚点拦下——两个锚点是合取关系
        assertNull(
            "文件名锚点须独立成立：用户文件即便带本插件 userData 也不得产提示",
            renderHints("UserMapper.sql", insertSql, XmlStatementKind.INSERT, attachKind = true),
        )
    }

    fun test测试缝文件名preview_sql不产提示() {
        // 测试缝文件名（SqlPreviewPanel.createPreviewFileForTest）**有意不列入保留名**：
        // 它是最像用户文件的普通名，列入即把「用户自己的 .sql 文件」判据打穿
        assertNull(
            "`preview.sql` 是最像用户文件的普通名，不得视为预览文件",
            renderHints("preview.sql", insertSql, XmlStatementKind.INSERT),
        )
    }

    fun test反向对照_同一条SQL仅换文件名即从有提示变无提示() {
        val preview = renderHints(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, insertSql, XmlStatementKind.INSERT)
        val userFile = renderHints("UserMapper.sql", insertSql, XmlStatementKind.INSERT)
        assertNotNull("对照组：预览文件名下应有提示", preview)
        assertNull("对照组：用户文件名下应零提示（差别只来自文件名锚点，与 SQL 无关）", userFile)
        assertEquals(
            "两个文件名跑的是同一条 SQL",
            insertSql,
            "INSERT INTO user (id, name) VALUES (1, 'admin')",
        )
    }

    fun test预览文件名但缺userData时不产提示() {
        assertNull(
            "双锚点缺一不可：文件名命中但无语句类型载体（userData）→ 不产提示",
            renderHints(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, insertSql, XmlStatementKind.INSERT, attachKind = false),
        )
    }

    fun test预览文件名但载体值为null时不产提示() {
        assertNull(
            "非 XML 预览链写 null（xmlModel 为 null）→ 无语句类型语义 → 不产提示",
            renderHints(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, insertSql, null),
        )
    }

    // ------------------------------------------------------------------
    // 语句类型闸门（验收 2）
    // ------------------------------------------------------------------

    fun test语句类型非INSERT时不产提示() {
        for (kind in listOf(XmlStatementKind.SELECT, XmlStatementKind.UPDATE, XmlStatementKind.DELETE)) {
            assertNull(
                "kind=$kind 不得产 INSERT 目标列提示（即便文本形态是 INSERT）",
                renderHints(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, insertSql, kind),
            )
        }
    }

    fun test语句类型为INSERT但文本非INSERT时零提示() {
        val dumped = renderHints(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, "SELECT id, name FROM user", XmlStatementKind.INSERT)
        assertEquals(
            "kind=INSERT 而文本无 INSERT 语句 → 提示条数为零（规划器零命中）",
            "SELECT id, name FROM user",
            dumped,
        )
    }

    // ------------------------------------------------------------------
    // T-PreviewInlayNativeYield：原生让位门的接线（在位 ⇒ 本条功能整体不出）
    // ------------------------------------------------------------------

    fun test原生列名inlay在位时整体让位_零提示() {
        NativeColumnInlayYieldGate.resetForTest()
        NativeColumnInlayYieldGate.probe = { true }
        val dumped = renderHints(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, insertSql, XmlStatementKind.INSERT)
        assertNull(
            "原生列名 inlay 在位时本插件必须零提示——否则同一 offset 上会出现两枚同名胶囊（本次缺陷的现象）",
            dumped,
        )
    }

    fun test让位门不在位时正向契约不变() {
        NativeColumnInlayYieldGate.resetForTest()
        NativeColumnInlayYieldGate.probe = { false }
        val dumped = renderHints(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, insertSql, XmlStatementKind.INSERT)
        assertEquals(
            "让位门不可用（Community / 未装 DatabaseTools / 探测降级）时，本插件提示必须照旧——不因让位把功能丢掉",
            "INSERT INTO user (id, name) VALUES (/*<# id #>*/1, /*<# name #>*/'admin')",
            dumped,
        )
    }

    // ------------------------------------------------------------------
    // 纯显示层（验收 5）：inlay 不改文档文本
    // ------------------------------------------------------------------

    fun test产提示不改动文档文本() {
        renderHints(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, insertSql, XmlStatementKind.INSERT)
        assertEquals("inlay 是显示层：投提示后文档文本逐字节不变", insertSql, myFixture.editor.document.text)
    }

    fun test双锚点判定函数直接读数() {        myFixture.configureByText(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, insertSql)
        val file = myFixture.file
        val editor = myFixture.editor
        assertNull("无载体 → null", PreviewInlayScope.previewStatementKind(file, editor))
        PreviewInlayScope.attach(editor, XmlStatementKind.INSERT)
        assertEquals("Editor 载体命中", XmlStatementKind.INSERT, PreviewInlayScope.previewStatementKind(file, editor))
        // Editor 载体清除后回落 PSI 文件载体（Editor 优先、文件兜底）
        PreviewInlayScope.attach(editor, null)
        assertNull("Editor 载体清除且文件无载体 → null", PreviewInlayScope.previewStatementKind(file, editor))
        PreviewInlayScope.attach(file, XmlStatementKind.UPDATE)
        assertEquals("文件载体兜底命中", XmlStatementKind.UPDATE, PreviewInlayScope.previewStatementKind(file, editor))
        assertNull("文件名为用户文件时判定直接为 null（不读载体）", PreviewInlayScope.previewStatementKind(null, editor))
    }

    // ------------------------------------------------------------------
    // 形态判据紧致性（T-FixN2 合并：唯一命名后锚点改按形态匹配，判据不得放宽）
    // ------------------------------------------------------------------

    fun test一级形态判据_紧致匹配() {
        assertTrue(
            "一级形态样例应命中：${PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE}",
            PreviewInlayScope.isPrimaryPreviewFileName(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE),
        )
        // 一级形态 = 前缀 + 数字 owner + '-' + 数字序号 + 扩展名，四段缺一不可
        for (bad in listOf(
            "mpsql-preview.sql",                        // 旧固定名（T-FixN2 前的形态），已不再产生
            "mpsql-preview-12480.sql",                  // 缺序号段
            "mpsql-preview-12480-1.sql.bak",            // 扩展名不是结尾
            "mpsql-preview-abc-1.sql",                  // owner 段非数字
            "mpsql-preview-12480-1.txt",                // 扩展名不符
            "xmpsql-preview-12480-1.sql",               // 前缀前有额外字符（非前缀匹配）
            "mpsql-exec.sql",                           // 原生控制台临时文件
            "mpsql-exec-12480.sql",                     // 原生控制台系统临时文件形态
            "preview.sql",                              // 测试缝名（最像用户文件）
            "UserMapper.sql",                           // 用户文件
        )) {
            assertFalse("不得被判为一级预览名：$bad", PreviewInlayScope.isPrimaryPreviewFileName(bad))
            assertFalse("不得被判为本插件预览名：$bad", PreviewInlayScope.isPreviewFileName(bad))
        }
        // 二级内存名：不是一级形态（`memory` 不是数字序号 → 不会被误判成一级），但它是预览文件，
        // 「本插件预览名」判据必须仍命中（否则降级链第二级的 inlay 静默消失）。
        assertFalse(
            "二级内存名不得被判为一级形态",
            PreviewInlayScope.isPrimaryPreviewFileName(PreviewInlayScope.MEMORY_PREVIEW_FILE_NAME),
        )
        assertTrue(
            "二级内存名应命中「本插件预览名」",
            PreviewInlayScope.isPreviewFileName(PreviewInlayScope.MEMORY_PREVIEW_FILE_NAME),
        )
    }

    fun test原生控制台临时文件名不产提示() {
        // mpsql-exec.sql（项目内执行文件）与 mpsql-exec-*.sql（系统临时执行文件）都是原生控制台路径，
        // 其编辑器也会被挂上补全上下文载体；本插件 inlay 在该路径**有意不渲染**（已知边界），
        // 放宽判据后不得变成「错误地渲染」——那比「不渲染」更糟。
        for (name in listOf("mpsql-exec.sql", "mpsql-exec-12480.sql")) {
            assertNull(
                "$name 是原生控制台临时文件，不得出现本插件 inlay",
                renderHints(name, insertSql, XmlStatementKind.INSERT, attachKind = true),
            )
        }
    }
}
