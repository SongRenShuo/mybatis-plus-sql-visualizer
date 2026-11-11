package cn.song.mybatisplus.visualizer.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SettingsIO 导入/导出序列化测试（O-5 Phase A）：
 *  - 导出 JSON 完整（全部可覆盖字段）
 *  - 导入恢复（JSON → State 往返一致）
 *  - 版本不兼容拒绝（版本号高于当前时抛异常）
 *  - 字段缺失默认值（旧版配置文件缺少新字段时不报错，使用默认值）
 *  - JSON 格式错误拒绝
 */
class SettingsIOTest {

    private val currentVersion = SettingsIO.CURRENT_VERSION

    @Test
    fun testExportContainsAllFields() {
        val state = PluginSettings.State(
            dialect = "PostgreSQL",
            replaceParams = false,
            formatSql = false,
            verticalPairAnnotation = false,
            autoFoldEnabled = true,
            autoFoldFieldThreshold = 5,
            injectLogicDelete = false,
            logicDeleteValue = "1",
            tablePrefix = "sys_",
            tableSuffix = "_log",
            ignoredFields = "remark;transientField",
            tableNameInference = PluginSettings.INFERENCE_CAMEL,
            columnCalibrationEnabled = false, // O-58：与出厂默认（true）相反，缺导出会露馅
        )
        val json = SettingsIO.exportToJson(state)
        assertTrue("JSON 应含 version", json.contains("\"version\": $currentVersion"))
        assertTrue("JSON 应含 dialect", json.contains("\"dialect\": \"PostgreSQL\""))
        assertTrue("JSON 应含 replaceParams", json.contains("\"replaceParams\": false"))
        assertTrue("JSON 应含 formatSql", json.contains("\"formatSql\": false"))
        assertTrue("JSON 应含 verticalPairAnnotation", json.contains("\"verticalPairAnnotation\": false"))
        assertTrue("JSON 应含 autoFoldEnabled", json.contains("\"autoFoldEnabled\": true"))
        assertTrue("JSON 应含 autoFoldFieldThreshold", json.contains("\"autoFoldFieldThreshold\": 5"))
        assertTrue("JSON 应含 injectLogicDelete", json.contains("\"injectLogicDelete\": false"))
        assertTrue("JSON 应含 logicDeleteValue", json.contains("\"logicDeleteValue\": \"1\""))
        assertTrue("JSON 应含 tablePrefix", json.contains("\"tablePrefix\": \"sys_\""))
        assertTrue("JSON 应含 tableSuffix", json.contains("\"tableSuffix\": \"_log\""))
        assertTrue("JSON 应含 ignoredFields", json.contains("\"ignoredFields\": \"remark;transientField\""))
        assertTrue("JSON 应含 tableNameInference", json.contains("\"tableNameInference\": \"CAMEL\""))
        // O-58：列名数据源校正开关必须在导出面内（同类可覆盖项都在；漏了会让「导出→导入」丢配置）
        assertTrue("JSON 应含 columnCalibrationEnabled", json.contains("\"columnCalibrationEnabled\": false"))
    }

    @Test
    fun testImportRestoresState() {
        val json = """
            {
              "version": $currentVersion,
              "dialect": "PostgreSQL",
              "replaceParams": false,
              "formatSql": false,
              "verticalPairAnnotation": false,
              "autoFoldEnabled": true,
              "autoFoldFieldThreshold": 5,
              "injectLogicDelete": false,
              "logicDeleteValue": "1",
              "tablePrefix": "sys_",
              "tableSuffix": "_log",
              "ignoredFields": "remark",
              "tableNameInference": "CAMEL"
            }
        """.trimIndent()
        val state = SettingsIO.importFromJson(json)
        assertEquals("dialect 应恢复", "PostgreSQL", state.dialect)
        assertEquals("replaceParams 应恢复", false, state.replaceParams)
        assertEquals("formatSql 应恢复", false, state.formatSql)
        assertEquals("verticalPairAnnotation 应恢复", false, state.verticalPairAnnotation)
        assertEquals("autoFoldEnabled 应恢复", true, state.autoFoldEnabled)
        assertEquals("autoFoldFieldThreshold 应恢复", 5, state.autoFoldFieldThreshold)
        assertEquals("injectLogicDelete 应恢复", false, state.injectLogicDelete)
        assertEquals("logicDeleteValue 应恢复", "1", state.logicDeleteValue)
        assertEquals("tablePrefix 应恢复", "sys_", state.tablePrefix)
        assertEquals("tableSuffix 应恢复", "_log", state.tableSuffix)
        assertEquals("ignoredFields 应恢复", "remark", state.ignoredFields)
        assertEquals("tableNameInference 应恢复", PluginSettings.INFERENCE_CAMEL, state.tableNameInference)
    }

    @Test
    fun testVersionMismatchRejected() {
        val json = """
            {
              "version": 9999,
              "dialect": "MySQL"
            }
        """.trimIndent()
        var caught: SettingsIO.ImportException? = null
        try {
            SettingsIO.importFromJson(json)
        } catch (e: SettingsIO.ImportException) {
            caught = e
        }
        assertTrue("版本不兼容应抛 ImportException", caught != null)
        assertTrue("异常消息应含版本信息", caught?.message?.contains("9999") == true)
    }

    @Test
    fun testUnknownFieldsIgnored() {
        val json = """
            {
              "version": $currentVersion,
              "dialect": "MySQL",
              "unknownField": "should be ignored",
              "anotherUnknown": 12345
            }
        """.trimIndent()
        val state = SettingsIO.importFromJson(json)
        assertEquals("未知字段应被忽略，dialect 正常恢复", "MySQL", state.dialect)
    }

    @Test
    fun testMissingFieldsUseDefaults() {
        // 旧版配置文件仅含部分字段
        val json = """
            {
              "version": $currentVersion,
              "dialect": "Oracle"
            }
        """.trimIndent()
        val state = SettingsIO.importFromJson(json)
        assertEquals("dialect 应恢复", "Oracle", state.dialect)
        // 未提供的字段使用默认值
        assertEquals("replaceParams 默认值", true, state.replaceParams)
        assertEquals("formatSql 默认值", true, state.formatSql)
        assertEquals("verticalPairAnnotation 默认值", true, state.verticalPairAnnotation)
        assertEquals("injectLogicDelete 默认值", true, state.injectLogicDelete)
        assertEquals("logicDeleteValue 默认值", "0", state.logicDeleteValue)
        assertEquals("tablePrefix 默认值", "", state.tablePrefix)
        assertEquals("tableNameInference 默认值", PluginSettings.INFERENCE_SNAKE, state.tableNameInference)
    }

    @Test
    fun testMalformedJsonRejected() {
        val json = "{ this is not valid json }"
        var caught: SettingsIO.ImportException? = null
        try {
            SettingsIO.importFromJson(json)
        } catch (e: SettingsIO.ImportException) {
            caught = e
        }
        assertTrue("格式错误应抛 ImportException", caught != null)
    }

    @Test
    fun testRoundTrip() {
        // 导出 → 导入往返一致
        val original = PluginSettings.State(
            dialect = "MySQL",
            replaceParams = false,
            formatSql = false,
            injectLogicDelete = false,
            logicDeleteValue = "1",
            tablePrefix = "t_",
            tableSuffix = "_bak",
            ignoredFields = "temp",
            tableNameInference = PluginSettings.INFERENCE_CAMEL,
            columnCalibrationEnabled = false, // O-58：非默认值，往返丢字段必红
        )
        val json = SettingsIO.exportToJson(original)
        val restored = SettingsIO.importFromJson(json)
        assertEquals("往返后 dialect 一致", original.dialect, restored.dialect)
        assertEquals("往返后 replaceParams 一致", original.replaceParams, restored.replaceParams)
        assertEquals("往返后 formatSql 一致", original.formatSql, restored.formatSql)
        assertEquals("往返后 injectLogicDelete 一致", original.injectLogicDelete, restored.injectLogicDelete)
        assertEquals("往返后 logicDeleteValue 一致", original.logicDeleteValue, restored.logicDeleteValue)
        assertEquals("往返后 tablePrefix 一致", original.tablePrefix, restored.tablePrefix)
        assertEquals("往返后 tableSuffix 一致", original.tableSuffix, restored.tableSuffix)
        assertEquals("往返后 ignoredFields 一致", original.ignoredFields, restored.ignoredFields)
        assertEquals("往返后 tableNameInference 一致", original.tableNameInference, restored.tableNameInference)
        assertEquals(
            "往返后 columnCalibrationEnabled 一致（O-58 新开关不得在导出/导入链路里丢失）",
            original.columnCalibrationEnabled,
            restored.columnCalibrationEnabled,
        )
    }

    @Test
    fun testExportDefaultState() {
        // 默认状态导出后应能正确导入
        val defaultState = PluginSettings.State()
        val json = SettingsIO.exportToJson(defaultState)
        val restored = SettingsIO.importFromJson(json)
        assertEquals("默认 dialect", PluginSettings.DEFAULT_DIALECT, restored.dialect)
        assertEquals("默认 replaceParams", true, restored.replaceParams)
        assertEquals("默认 formatSql", true, restored.formatSql)
        assertEquals("默认 injectLogicDelete", true, restored.injectLogicDelete)
    }

    // ------------------------------------------------------------------
    // T-Fix-SettingsScope：导入按「JSON 出现的键才覆盖」合并
    // ------------------------------------------------------------------

    @Test
    fun testImportMergesIntoBaseKeepingKeysAbsentFromJson() {
        // 导出面是 State 的子集（23 键 vs 81 个 var 字段）。以现值为底合并时，文件未承载的
        // 字段必须原样保留——否则参数历史/增强捕获授权记忆/反馈身份/日志自动保存目录等
        // 用户数据会在一次「导入设置」后静默归零。
        val base = PluginSettings.State(
            dialect = "Oracle",
            tablePrefix = "keep_",
            paramHistoryJson = """[{"sql":"select 1"}]""",
            enhancedCaptureConfirmed = true,
            reportSubmitterName = "tester",
            logAutoSavePath = "D:/logs",
            historyMaxSize = 42,
            showGutterIcon = false,
            autoCheckUpdate = true,
            ignoredUpdateVersions = "1.2.3",
        )
        val json = """
            {
              "version": $currentVersion,
              "dialect": "PostgreSQL",
              "tablePrefix": "imported_"
            }
        """.trimIndent()

        val merged = SettingsIO.importFromJson(json, base)

        // JSON 出现的键 → 被文件值覆盖
        assertEquals("dialect 应被文件值覆盖", "PostgreSQL", merged.dialect)
        assertEquals("tablePrefix 应被文件值覆盖", "imported_", merged.tablePrefix)
        // JSON 未出现的键 → 保留 base 现值
        assertEquals("参数历史应保留", """[{"sql":"select 1"}]""", merged.paramHistoryJson)
        assertEquals("增强捕获授权记忆应保留", true, merged.enhancedCaptureConfirmed)
        assertEquals("反馈身份应保留", "tester", merged.reportSubmitterName)
        assertEquals("日志自动保存目录应保留", "D:/logs", merged.logAutoSavePath)
        assertEquals("历史上限应保留", 42, merged.historyMaxSize)
        assertEquals("Gutter 图标开关应保留", false, merged.showGutterIcon)
        assertEquals("更新检查开关应保留（SettingsIO 自称不含全局项）", true, merged.autoCheckUpdate)
        assertEquals("忽略版本列表应保留", "1.2.3", merged.ignoredUpdateVersions)
        // 合并不得就地改写 base（调用方传入的是活状态副本）
        assertEquals("base 不应被就地改写", "Oracle", base.dialect)
        assertEquals("base.tablePrefix 不应被就地改写", "keep_", base.tablePrefix)
    }

    @Test
    fun testImportWithoutBaseStillYieldsFactoryDefaults() {
        // 不带 base 的重载保持旧语义：未出现的键回落出厂默认（旧版配置文件兼容路径）
        val json = """
            {
              "version": $currentVersion,
              "dialect": "Oracle"
            }
        """.trimIndent()
        val state = SettingsIO.importFromJson(json)
        assertEquals("dialect 应恢复", "Oracle", state.dialect)
        assertEquals("未出现的键回落出厂默认", "", state.paramHistoryJson)
        assertEquals("未出现的键回落出厂默认", PluginSettings.DEFAULT_HISTORY_MAX_SIZE, state.historyMaxSize)
    }

    // ------------------------------------------------------------------
    // T-TS-FixC：dialectAuto 键（T-DialectAutoInfer 新增）白名单无回归
    // ------------------------------------------------------------------

    /**
     * `dialectAuto` 的导出/导入往返。本用例的存在意义是**白名单钉子**：该键此前在
     * SettingsIOTest 零出现，一旦 [SettingsIO.exportToJson] 的导出面或 [SettingsIO.importFromJson]
     * 的读取面漏掉它，「方言智能推断」开关就会在团队分发配置后静默丢失（导入端表现为回落 base 现值）。
     */
    @Test
    fun testDialectAutoExportImportRoundTrip() {
        // 导出恒写该键（true / false 两态都要在文件里可读）
        val exportedFalse = SettingsIO.exportToJson(PluginSettings.State(dialectAuto = false))
        assertTrue("导出应含 dialectAuto 键", exportedFalse.contains("\"dialectAuto\": false"))
        assertEquals("导入应恢复 false", false, SettingsIO.importFromJson(exportedFalse).dialectAuto)

        val exportedTrue = SettingsIO.exportToJson(PluginSettings.State(dialectAuto = true))
        assertTrue("导出应含 dialectAuto 键", exportedTrue.contains("\"dialectAuto\": true"))
        assertEquals("导入应恢复 true", true, SettingsIO.importFromJson(exportedTrue).dialectAuto)

        // 键缺失 → 保留 base 现值（不得被静默重置为出厂默认 true）
        val withoutKey = """
            {
              "version": $currentVersion,
              "dialect": "MySQL"
            }
        """.trimIndent()
        assertEquals(
            "JSON 未含 dialectAuto 时应保留 base 现值 false",
            false,
            SettingsIO.importFromJson(withoutKey, PluginSettings.State(dialectAuto = false)).dialectAuto,
        )
    }
    @Test
    fun `病态数字抛ImportException而非NumberFormatException_券Y14`() {
        try {
            SettingsIO.importFromJson("""{"version":1,"x":-}""")
            org.junit.Assert.fail("应抛 ImportException")
        } catch (e: SettingsIO.ImportException) {
            org.junit.Assert.assertTrue(e.message!!.contains("非法数字字面量"))
        }
    }

    @Test
    fun `version小于1拒绝导入_券Y14`() {
        try {
            SettingsIO.importFromJson("""{"version":0}""")
            org.junit.Assert.fail("应抛 ImportException")
        } catch (e: SettingsIO.ImportException) {
            org.junit.Assert.assertTrue(e.message!!.contains("版本不合法"))
        }
    }

}
