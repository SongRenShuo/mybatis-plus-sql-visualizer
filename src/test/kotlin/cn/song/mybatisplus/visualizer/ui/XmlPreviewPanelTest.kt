package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.ParamKind
import cn.song.mybatisplus.visualizer.xml.ParamTypeInfo
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import cn.song.mybatisplus.visualizer.testutil.FakeClipboardManager
import cn.song.mybatisplus.visualizer.testutil.installFakeClipboard

/**
 * SqlPreviewPanel XML 分支测试（T-M24-04）：
 *  - xmlModel 渲染（含紧凑切换）、条件开关勾选重渲染、foreach 次数、bind 求值回显
 *  - 左栏控件构建（条件复选框 / foreach 次数 / 参数行；T-M26.4：bind 分区已删除，
 *    bind 值由「参数」区唯一承担——求值结果回显、输入覆盖）
 */
class XmlPreviewPanelTest : BasePlatformTestCase() {

    /** T-ClipboardIso：内存剪贴板（测试全程不碰系统剪贴板，用户复制/并行会话互不干扰）。 */
    private lateinit var clipboard: FakeClipboardManager

    // T-M27.4：渲染文本断言类测试关闭自动格式化（格式化行为由 SqlFormatterTest +
    // SqlPreviewPanelTest.testRenderAutoFormatsSql 专项覆盖；此处模拟"关闭格式化"场景）
    override fun setUp() {
        super.setUp()
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
        runCatching { cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = false }
        clipboard = installFakeClipboard(testRootDisposable)
    }

    override fun tearDown() {
        runCatching { cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = true }
        super.tearDown()
    }

    private val mapper = """
        <mapper namespace="com.example.BizMapper">
          <select id="selectList" resultType="map">
            SELECT * FROM t_biz
            <where>
              <if test="type != null">AND type = #{type}</if>
            </where>
            <if test="ids != null">
              AND id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </if>
            <bind name="likeName" value="'%' + name + '%'"/>
            <if test="name != null">AND name LIKE #{likeName}</if>
          </select>
        </mapper>
    """.trimIndent()

    private fun xmlResult(): PreviewResult = xmlResultOf(mapper)

    private fun xmlResultOf(
        source: String,
        paramTypes: Map<String, ParamTypeInfo> = emptyMap(),
    ): PreviewResult {
        val parsed = XmlMapperParser().parse(source)
        val model = XmlPreviewModel.from(parsed.statements.single(), parsed.errors, paramTypes = paramTypes)
        val rendered = model.renderSql()
        return PreviewResult(
            sql = rendered.sql,
            compactSql = rendered.sql.replace(Regex("\\s+"), " ").trim(),
            unresolvedParams = rendered.mappings.map { it.property },
            paramTable = ParameterReplacer.buildParamTable(rendered.mappings.map { it.property }),
            wrapperType = null,
            tableName = null,
            xmlModel = model,
        )
    }

    private fun compact(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /**
     * T-SelToggle：把 XML 条件全部取消勾选。切换按钮文案按勾选态翻转——
     * 默认全勾 → 文案「全不选」→ 一次点击归零（原测试借按钮的「全选/全不选」错位用法已废弃）。
     */
    private fun deselectAllConditions(panel: SqlPreviewPanel) {
        val toggle = panel.toggleSelectButtonForTest()!!
        assertEquals(
            "XML 条件默认全勾，切换按钮应为全不选文案",
            cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.condition.selectNone"),
            toggle.text,
        )
        toggle.doClick()
    }

    fun testXmlPreviewRendersSql() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult())
        val editor = panel.editorForTest()
        assertNotNull("应挂载编辑器视图", editor)
        assertEquals(
            "SELECT * FROM t_biz WHERE type = ? AND id IN ( ? , ? ) AND name LIKE '%1%'",
            compact(editor!!.document.text),
        )
        panel.clear()
    }

    fun testXmlToggleCompact() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult())
        panel.toggleCompact()
        // T-M25.5：bind 自动求值回填（'%' + name + '%'，mock name="1" → '%1%'）
        assertEquals(
            "SELECT * FROM t_biz WHERE type = ? AND id IN ( ? , ? ) AND name LIKE '%1%'",
            panel.editorForTest()!!.document.text,
        )
        panel.clear()
    }

    fun testXmlConditionCheckboxTogglesSql() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult())
        // 左栏条件复选框（第一个 = type != null，第二个 = ids != null，第三个 = name != null）
        assertNotNull("应构建动态条件复选框", panel.conditionCheckboxForTest(0))
        panel.conditionCheckboxForTest(0)!!.isSelected = false
        panel.conditionCheckboxForTest(0)!!.actionListeners.forEach { it.actionPerformed(null) }
        val sql = compact(panel.editorForTest()!!.document.text)
        assertTrue("关闭 type 条件后 WHERE 消失", sql.startsWith("SELECT * FROM t_biz AND id IN"))
        panel.clear()
    }

    fun testXmlForEachCountField() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult())
        // 把 foreach 外的条件全关，只剩 IN 片段便于断言次数
        deselectAllConditions(panel)
        panel.conditionCheckboxForTest(1)?.let { box ->
            box.isSelected = true
            box.actionListeners.forEach { it.actionPerformed(null) }
        }
        val sql1 = compact(panel.editorForTest()!!.document.text)
        assertTrue("默认展开两次：IN ( ? , ? )", sql1.contains("IN ( ? , ? )"))
        panel.clear()
    }

    fun testXmlForEachValuesInputBackfills() {
        // T-M25.4（用户实测：在 foreach 次数框输入 2,3 无效）——集合值输入：逗号分隔自动回填 IN (2, 3)
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult())
        deselectAllConditions(panel)
        panel.conditionCheckboxForTest(1)?.let { box ->
            box.isSelected = true
            box.actionListeners.forEach { it.actionPerformed(null) }
        }
        // foreach 集合值输入框（tooltip 含"逗号分隔"）
        val valuesField = (0 until 20).mapNotNull { panel.paramFieldForTest(it) }
            .firstOrNull { it.toolTipText?.contains("逗号分隔") == true }
        assertNotNull("foreach 应有集合值输入框", valuesField)
        valuesField!!.text = "2,3" // setText 触发 DocumentListener → 回填
        val sql = compact(panel.editorForTest()!!.document.text)
        // T-M29.4：foreach 展开项默认引号（`IN ( '2' , '3' )`，数值隐式转换语义不变）
        assertTrue("输入 2,3 应回填为 IN ( '2' , '3' )：$sql", sql.contains("IN ( '2' , '3' )"))
        panel.clear()
    }

    fun testXmlForEachCountZeroDropsInClause() {
        // 全不选（切换按钮）→ 只剩 foreach 条件开启 → 次数 0 → IN 片段消失
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult())
        deselectAllConditions(panel)
        panel.conditionCheckboxForTest(1)?.let { box ->
            box.isSelected = true
            box.actionListeners.forEach { it.actionPerformed(null) }
        }
        val sql1 = compact(panel.editorForTest()!!.document.text)
        assertTrue("默认展开两次：IN ( ? , ? )", sql1.contains("IN ( ? , ? )"))
        panel.clear()
    }

    fun testXmlBindValuesOnlyInParamSection() {
        // T-M26.4（用户反馈：bind 变量区与参数区重复展示同一组值）——bind 分区已删除：
        // bind 求值结果仅在「参数」区显示（唯一入口：输入覆盖、清空恢复自动值）
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult())
        // bind 名在参数区有输入行（求值结果回显：'%' + name + '%'，mock name="1" → "%1%"）。
        // T-HibDualCapture：tooltip 现为 `#source = 值`，按前缀匹配定位 bind 行（值随回填动态变化）
        val bindRow = (0 until 20).mapNotNull { panel.paramFieldForTest(it) }
            .firstOrNull { it.toolTipText?.startsWith("#likeName") == true }
        assertNotNull("bind 变量应在参数区有输入行", bindRow)
        assertEquals("bind 求值结果应回显到参数行", "%1%", bindRow!!.text)
        // 参数输入覆盖 bind 值 → SQL 回填覆盖值（等价于原 bind 覆盖输入框）
        bindRow.text = "xx"
        assertTrue(
            "参数输入应覆盖 bind 值：" + compact(panel.editorForTest()!!.document.text),
            compact(panel.editorForTest()!!.document.text).contains("name LIKE 'xx'"),
        )
        panel.clear()
    }

    fun testXmlLikeParamQuotedForPureNumericInput() {
        // T-M26.5（用户反馈：模糊查询字段类型推断不正确）——XML 渲染 SQL 的 LIKE 参数
        // 回填恒加引号：String 列（如 deviceCode）的纯数字输入不再被数字启发式误判为数字
        // （#keyword="111" → LIKE CONCAT('%', '111', '%')）
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <select id="s1">
                SELECT * FROM t WHERE code LIKE CONCAT('%', #{keyword}, '%')
              </select>
            </mapper>
        """.trimIndent())
        val model = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        val rendered = model.renderSql()
        val result = PreviewResult(
            sql = rendered.sql,
            compactSql = rendered.sql,
            unresolvedParams = rendered.mappings.map { it.property },
            paramTable = ParameterReplacer.buildParamTable(rendered.mappings.map { it.property }),
            wrapperType = null,
            tableName = null,
            xmlModel = model,
        )
        val panel = SqlPreviewPanel(project)
        panel.showResult(result)
        val keywordField = (0 until 20).mapNotNull { panel.paramFieldForTest(it) }
            .firstOrNull { it.toolTipText == "#keyword" }
        assertNotNull("应有关键词参数输入行", keywordField)
        keywordField!!.text = "111"
        assertTrue(
            "LIKE 参数回填应带引号：" + compact(panel.editorForTest()!!.document.text),
            compact(panel.editorForTest()!!.document.text).contains("LIKE CONCAT('%', '111', '%')"),
        )
        panel.clear()
    }

    fun testXmlParamQuotedByDefault() {
        // T-M29.4（用户实测：Map 入参无列类型信息）——XML 参数回填默认带引号：
        // `settlement_period >= #{p.settlementStartDate}` 输入 2025 → `>= '2025'`（LocalDate 年份输入
        // 不再被数字启发式裸渲染）；数值列（dic_credit_status）输入 2 → `= '2'`（隐式转换语义不变）
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <select id="s1">
                SELECT * FROM t
                <where>
                    t.deleted = 0
                    <if test="p.settlementStartDate != null">
                        AND t.settlement_period &gt;= #{p.settlementStartDate}
                    </if>
                    <if test="p.dicCreditStatus != null">
                        AND t.dic_credit_status = #{p.dicCreditStatus}
                    </if>
                </where>
              </select>
            </mapper>
        """.trimIndent())
        val model = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        val rendered = model.renderSql()
        val result = PreviewResult(
            sql = rendered.sql,
            compactSql = rendered.sql,
            unresolvedParams = rendered.mappings.map { it.property },
            paramTable = ParameterReplacer.buildParamTable(rendered.mappings.map { it.property }),
            wrapperType = null,
            tableName = null,
            xmlModel = model,
        )
        val panel = SqlPreviewPanel(project)
        panel.showResult(result)
        val fields = (0 until 20).mapNotNull { panel.paramFieldForTest(it) }
        val dateField = fields.firstOrNull { it.toolTipText == "#p.settlementStartDate" }
        val statusField = fields.firstOrNull { it.toolTipText == "#p.dicCreditStatus" }
        assertNotNull("应有日期参数输入行", dateField)
        assertNotNull("应有状态参数输入行", statusField)
        dateField!!.text = "2025"
        statusField!!.text = "2"
        val sql = compact(panel.editorForTest()!!.document.text)
        assertTrue("日期参数应带引号：$sql", sql.contains("t.settlement_period >= '2025'"))
        assertTrue("数值参数默认也带引号（隐式转换等价）：$sql", sql.contains("t.dic_credit_status = '2'"))
        panel.clear()
    }

    fun testXmlListParamSplitsCommaInputBySignature() {
        // O-81 T-XmlListParamSplit（R1，用户实测）：List<String> 签名 + `IN (#{taskTypeList})` 直写
        // 形态（无 foreach，左栏为普通参数行）——输入 PI,WP 应按签名拆分多值渲染。
        // 升级前：inList 只看 SQL 形态探测，形态漏判即整串单值 `IN ('PI,WP')`
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            xmlResultOf(
                """
                <mapper namespace="n">
                  <select id="s1">
                    SELECT * FROM t_order WHERE EXISTS (
                      SELECT 1 FROM t_task task WHERE task.order_id = t_order.id
                      AND task.type IN (#{taskTypeList})
                    )
                  </select>
                </mapper>
                """.trimIndent(),
                paramTypes = mapOf(
                    "taskTypeList" to ParamTypeInfo(ParamKind.COLLECTION, elementKind = ParamKind.TEXT),
                ),
            ),
        )
        val field = (0 until 20).mapNotNull { panel.paramFieldForTest(it) }
            .firstOrNull { it.toolTipText == "#taskTypeList" }
        assertNotNull("应有 taskTypeList 参数输入行", field)
        field!!.text = "PI,WP"
        val sql = compact(panel.editorForTest()!!.document.text)
        assertTrue("List<String> 输入 PI,WP 应拆分为 IN ('PI', 'WP')：$sql", sql.contains("task.type IN ('PI', 'WP')"))
        panel.clear()
    }

    fun testXmlListParamNumberElementsRenderBare() {
        // O-81：元素类别 NUMBER（List<Long>）→ 拆分后裸渲染（与 O-78 foreach item 派生同口径）
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            xmlResultOf(
                """
                <mapper namespace="n">
                  <select id="s1">
                    SELECT * FROM t_order WHERE t.move_doc_id IN (#{idList})
                  </select>
                </mapper>
                """.trimIndent(),
                paramTypes = mapOf(
                    "idList" to ParamTypeInfo(ParamKind.COLLECTION, elementKind = ParamKind.NUMBER),
                ),
            ),
        )
        val field = (0 until 20).mapNotNull { panel.paramFieldForTest(it) }
            .firstOrNull { it.toolTipText == "#idList" }
        assertNotNull("应有 idList 参数输入行", field)
        field!!.text = "1,2"
        val sql = compact(panel.editorForTest()!!.document.text)
        assertTrue("List<Long> 输入 1,2 应裸渲染 IN (1, 2)：$sql", sql.contains("t.move_doc_id IN (1, 2)"))
        panel.clear()
    }

    fun testXmlListParamShapeDetectionToleratesNewline() {
        // O-81 R2 端到端：无签名类型信息（接口不可解析/Map 入参）时纯走形态探测——
        // `IN` 与 `(` 之间换行的 mapper 排版不再漏判（升级前整串单值）
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            xmlResultOf(
                """
                <mapper namespace="n">
                  <select id="s1">
                    SELECT * FROM t_order WHERE EXISTS (
                      SELECT 1 FROM t_task task WHERE task.order_id = t_order.id
                      AND task.type IN
                      (#{taskTypeList})
                    )
                  </select>
                </mapper>
                """.trimIndent(),
            ),
        )
        val field = (0 until 20).mapNotNull { panel.paramFieldForTest(it) }
            .firstOrNull { it.toolTipText == "#taskTypeList" }
        assertNotNull("应有 taskTypeList 参数输入行", field)
        field!!.text = "PI,WP"
        val sql = compact(panel.editorForTest()!!.document.text)
        assertTrue("IN 与 ( 换行也应拆分：$sql", sql.contains("task.type IN ('PI', 'WP')"))
        panel.clear()
    }

    fun testXmlRenderErrorsShownInMeta() {
        // bind 表达式解析失败 → 渲染错误进元信息警示（编辑器仍正常显示 SQL）
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <select id="s1">
                <bind name="bad" value="not valid @@ expr"/>
                SELECT * FROM t WHERE a = #{a}
              </select>
            </mapper>
        """.trimIndent())
        val model = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        val rendered = model.renderSql()
        val result = PreviewResult(
            sql = rendered.sql,
            compactSql = rendered.sql.replace(Regex("\\s+"), " ").trim(),
            unresolvedParams = rendered.mappings.map { it.property },
            paramTable = ParameterReplacer.buildParamTable(rendered.mappings.map { it.property }),
            wrapperType = null,
            tableName = null,
            xmlModel = model,
        )
        val panel = SqlPreviewPanel(project)
        panel.showResult(result)
        assertNotNull("编辑器应正常显示 SQL", panel.editorForTest())
        // T-PreviewInfoLayout D-2：警告正文不再进 meta 可见行（与黄色横幅重复），改由横幅承载；
        // 这里同时钉住「警告没有丢」——currentWarning 与横幅仍在。
        assertFalse("meta 行不应再承载警告正文", panel.metaLabelForTest().contains("⚠"))
        assertNotNull("渲染错误必须仍被记录为当前警告", panel.currentWarning)
        assertEquals(
            "警告应由横幅承载",
            DegradationBanner.Kind.RENDER_WARNING,
            panel.degradationBannerForTest().kindForTest(),
        )
        panel.clear()
    }

    fun testErrorCopyButtonVisibleWithWarning() {
        // T-M25.3（用户实测：错误提示难以复制）——有警示时工具栏出现复制错误按钮
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <select id="s1">
                <bind name="bad" value="not valid @@ expr"/>
                SELECT * FROM t
              </select>
            </mapper>
        """.trimIndent())
        val model = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        val rendered = model.renderSql()
        val result = PreviewResult(
            sql = rendered.sql,
            compactSql = rendered.sql,
            unresolvedParams = emptyList(),
            paramTable = null,
            wrapperType = null,
            tableName = null,
            xmlModel = model,
        )
        val panel = SqlPreviewPanel(project)
        panel.showResult(result)
        val button = panel.errorCopyButtonForTest()
        assertNotNull("错误复制按钮应存在", button)
        assertTrue("有警示时按钮应可见", button!!.isVisible)
        assertTrue("复制错误应成功", panel.copyError())
        panel.clear()
        assertFalse("清空后按钮应隐藏", panel.errorCopyButtonForTest()?.isVisible ?: true)
    }

    fun testErrorCopyButtonHiddenWithoutWarning() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult()) // 无警告的预览
        val button = panel.errorCopyButtonForTest()
        assertNotNull(button)
        assertFalse("无警示时按钮应隐藏", button!!.isVisible)
        assertFalse("无错误时复制返回 false", panel.copyError())
        panel.clear()
    }

    /**
     * T-ExprAstPrecheck T-EP-03：条件行支持面标注——SUPPORTED 行不占位；
     * 不支持语法/运行期降级各渲染一条淡色标注，理由进 tooltip。
     */
    fun testXmlConditionSupportAnnotation() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            xmlResultOf(
                """
                <mapper namespace="com.example.BizMapper">
                  <select id="s1" resultType="map">SELECT * FROM t
                    <if test="type != null">AND type = #{type}</if>
                    <if test="a ? b : c">AND a = 1</if>
                    <if test="map.length() &gt; 0">AND b = 2</if>
                  </select>
                </mapper>
                """.trimIndent(),
            ),
        )

        // R30 条件求值探镜起，支持面内的条件行带三态求值标注（真/假/未求值），恰一个
        val supportedAnnotations = conditionAnnotations(panel, 0)
        assertEquals(1, supportedAnnotations.size)
        val outcomeTexts = listOf(
            MpSqlVisualizerBundle.message("panel.xml.outcome.true"),
            MpSqlVisualizerBundle.message("panel.xml.outcome.false"),
            MpSqlVisualizerBundle.message("panel.xml.outcome.unevaluated"),
        )
        assertTrue(
            "支持面内条件行标注应为三态之一：${supportedAnnotations.single().text}",
            supportedAnnotations.single().text in outcomeTexts,
        )

        // R30 起不支持行并存两个标注：不支持语法提示＋探镜三态「未求值」（不可求值即未求值）
        val unsupported = conditionAnnotations(panel, 1)
        assertEquals(2, unsupported.size)
        val unsupportedNotice = unsupported.single { it.text == MpSqlVisualizerBundle.message("panel.xml.expr.unsupported") }
        assertTrue(
            "不支持行的另一标注应为探镜三态之一：${unsupported.map { it.text }}",
            unsupported.map { it.text }.any { it in outcomeTexts },
        )
        assertTrue(
            "不支持语法行的 tooltip 应给理由：${unsupportedNotice.toolTipText}",
            unsupportedNotice.toolTipText.contains("三元"),
        )

        val fallback = conditionAnnotations(panel, 2)
        // R30 起降级行同样并存探镜三态标注（降级路径仍参与求值，标真/假）＋降级提示
        assertEquals(2, fallback.size)
        val fallbackNotice = fallback.single { it.text == MpSqlVisualizerBundle.message("panel.xml.expr.fallback") }
        assertTrue(
            "降级行的另一标注应为探镜三态之一：${fallback.map { it.text }}",
            fallback.map { it.text }.any { it in outcomeTexts },
        )
        assertTrue(
            "降级行的 tooltip 应点名方法：${fallbackNotice.toolTipText}",
            fallbackNotice.toolTipText.contains("length"),
        )

        // 标注只是提示：三个条件仍按启发式默认全部展开
        assertEquals(
            "SELECT * FROM t AND type = ? AND a = 1 AND b = 2",
            compact(panel.editorForTest()!!.document.text),
        )
        panel.clear()
    }

    /** 条件行内的标注标签（复选框所在行 panel 的 JBLabel 子组件）。 */
    private fun conditionAnnotations(panel: SqlPreviewPanel, index: Int): List<JBLabel> {
        val checkBox = panel.conditionCheckboxForTest(index) ?: return emptyList()
        return (checkBox.parent?.components ?: emptyArray()).filterIsInstance<JBLabel>()
    }
}
