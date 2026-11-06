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

    // ------------------------------------------------------------------
    // T-SAF-08：SQL 位形设置值级校验（settings-import.sql-fragment-unvalidated）
    // ------------------------------------------------------------------

    /** 组装最小导入 JSON（只含指定键；值按 JSON 字符串原样嵌入）。 */
    private fun importJsonOf(vararg pairs: Pair<String, String>): String = buildString {
        appendLine("{")
        appendLine("  \"version\": $currentVersion,")
        pairs.forEachIndexed { i, (k, v) ->
            append("  \"$k\": \"")
            append(v.replace("\\", "\\\\").replace("\"", "\\\""))
            append("\"")
            if (i < pairs.size - 1) appendLine(",") else appendLine()
        }
        appendLine("}")
    }

    private fun importRejectsField(json: String, expectedField: String) {
        var caught: SettingsIO.ImportException? = null
        try {
            SettingsIO.importFromJson(json)
        } catch (e: SettingsIO.ImportException) {
            caught = e
        }
        assertTrue(
            "载荷字段 $expectedField 应被拒绝导入（抛 ImportException）",
            caught != null,
        )
        assertTrue(
            "拒绝报告应逐字段点名（含 $expectedField）：${caught!!.message}",
            caught.message!!.contains(expectedField),
        )
    }

    /**
     * T-SAF-08 回归：logicDeleteValue 曾原样进入渲染 SQL 的 WHERE 字面量位
     *（SqlValue.Literal.raw 直通），共享设置文件可用 `'0 OR 1=1'` 把注入的
     * `AND deleted = 0` 翻转成恒真条件。
     * **修复前失败断言**：caught != null——修复前值无校验原样合入，导入不抛异常。
     */
    @Test
    fun testImportRejectsLogicDeleteValueOrConnector() {
        importRejectsField(importJsonOf("logicDeleteValue" to "0 OR 1=1"), "logicDeleteValue")
    }

    @Test
    fun testImportRejectsLogicDeleteValueSqlCommentAndSemicolon() {
        importRejectsField(importJsonOf("logicDeleteValue" to "0--x"), "logicDeleteValue")
        importRejectsField(importJsonOf("logicDeleteValue" to "0;DELETE x"), "logicDeleteValue")
        importRejectsField(importJsonOf("logicDeleteValue" to "0 /*x*/"), "logicDeleteValue")
    }

    /** T-SAF-08：tenantColumnName 走标识符形状白名单（曾可携带 `tenant_id=1 OR 1=1 --`）。 */
    @Test
    fun testImportRejectsTenantColumnNamePayload() {
        importRejectsField(importJsonOf("tenantColumnName" to "tenant_id=1 OR 1=1 --"), "tenantColumnName")
        importRejectsField(importJsonOf("tenantColumnName" to "tenant id"), "tenantColumnName")
        importRejectsField(importJsonOf("tenantColumnName" to "1tenant"), "tenantColumnName")
    }

    /** T-SAF-08：tenantColumnDefault 单字面量校验。 */
    @Test
    fun testImportRejectsTenantColumnDefaultPayload() {
        importRejectsField(importJsonOf("tenantColumnDefault" to "1;DROP TABLE x"), "tenantColumnDefault")
        importRejectsField(importJsonOf("tenantColumnDefault" to "1 OR 1=1"), "tenantColumnDefault")
    }

    /** T-SAF-08：tablePrefix/tableSuffix 标识符形状白名单（空串放行 = 无前缀安全语义）。 */
    @Test
    fun testImportRejectsTableNameFragmentPayload() {
        importRejectsField(importJsonOf("tablePrefix" to "p_--x"), "tablePrefix")
        importRejectsField(importJsonOf("tablePrefix" to "p_;DROP"), "tablePrefix")
        importRejectsField(importJsonOf("tableSuffix" to "_log OR 1=1"), "tableSuffix")
    }

    /** T-SAF-08：dataPermissionMockCondition 长度上限 + 注释/分号拒绝（追加为原始 WHERE 片段）。 */
    @Test
    fun testImportRejectsDataPermissionMockConditionPayload() {
        importRejectsField(importJsonOf("dataPermissionMockCondition" to "1=1;DROP TABLE x"), "dataPermissionMockCondition")
        importRejectsField(importJsonOf("dataPermissionMockCondition" to "1=1 --x"), "dataPermissionMockCondition")
        importRejectsField(importJsonOf("dataPermissionMockCondition" to "a".repeat(513)), "dataPermissionMockCondition")
    }

    /** T-SAF-08：dynamicTableNameRules pattern sanity（可编译 + 长度上限）。 */
    @Test
    fun testImportRejectsUnsafeDynamicTableNameRulePatterns() {
        val broken = """
            {
              "version": $currentVersion,
              "dynamicTableNameRules": [
                {"pattern": "user[invalid", "replacement": "order", "enabled": true}
              ]
            }
        """.trimIndent()
        importRejectsField(broken, "dynamicTableNameRules")
        val oversized = """
            {
              "version": $currentVersion,
              "dynamicTableNameRules": [
                {"pattern": "${"a".repeat(300)}", "replacement": "order", "enabled": true}
              ]
            }
        """.trimIndent()
        importRejectsField(oversized, "dynamicTableNameRules")
    }

    /** T-SAF-08：一次导入多个载荷字段时，拒绝报告逐字段全部点名（不静默丢一个不报）。 */
    @Test
    fun testImportRejectionReportsEveryOffendingField() {
        var caught: SettingsIO.ImportException? = null
        try {
            SettingsIO.importFromJson(
                importJsonOf(
                    "logicDeleteValue" to "0 OR 1=1",
                    "tenantColumnName" to "tenant_id=1 OR 1=1 --",
                ),
            )
        } catch (e: SettingsIO.ImportException) {
            caught = e
        }
        assertTrue("多字段载荷应整体拒绝", caught != null)
        val message = caught!!.message ?: ""
        assertTrue("报告应点名 logicDeleteValue：$message", message.contains("logicDeleteValue"))
        assertTrue("报告应点名 tenantColumnName：$message", message.contains("tenantColumnName"))
    }

    /** T-SAF-08：合法值与出厂默认导出→导入往返不受校验影响（防误伤）。 */
    @Test
    fun testImportStillAcceptsLegitSqlPositionValues() {
        val state = SettingsIO.importFromJson(
            importJsonOf(
                "logicDeleteValue" to "0",
                "tenantColumnName" to "tenant_id",
                "tenantColumnDefault" to "1",
                "tablePrefix" to "sys_",
                "tableSuffix" to "_log",
                "dataPermissionMockCondition" to "dept_id = 1",
            ),
        )
        assertEquals("0", state.logicDeleteValue)
        assertEquals("tenant_id", state.tenantColumnName)
        assertEquals("1", state.tenantColumnDefault)
        assertEquals("sys_", state.tablePrefix)
        assertEquals("_log", state.tableSuffix)
        assertEquals("dept_id = 1", state.dataPermissionMockCondition)
        // 出厂默认导出（含空 tablePrefix/tableSuffix）必须原样可导入
        SettingsIO.importFromJson(SettingsIO.exportToJson(PluginSettings.State()))
    }
}
