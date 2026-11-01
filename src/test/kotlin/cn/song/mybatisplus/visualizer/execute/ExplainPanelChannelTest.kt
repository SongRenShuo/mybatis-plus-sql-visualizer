package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R33 EXPLAIN 结构化渲染通道判定单测（面板侧自建执行链路线，核实报告 §三 V41）。
 *
 * 覆盖三组判定：
 *  1. 结构化通道准入（开关开 ∧ 格式 ∈ {TABLE, JSON} ∧ database 插件在场 ∧ 已绑数据源）；
 *  2. 设计内直送（开关关 / 其余方言 TEXT/XML/未知——维持直送不硬解析，红线：勿硬解析）；
 *  3. 环境性回退（无数据源 / 无 database 插件——fail-closed 落回直送通道并给轻提示）。
 *
 * 面板接线（explainSql 真的走本判定 + 回退复用直送通道）由 `ExplainPanelWiringGuardTest`
 * 的源码扫描断言钉住（照 McpErrorPositionTextTest 先例）——判定与接线分两个用例文件，
 * 回归信号不互相掩盖。
 */
class ExplainPanelChannelTest {

    // ------------------------------------------------------------------
    // 第一组：结构化通道准入（四条件同时成立才走自建链）
    // ------------------------------------------------------------------

    @Test
    fun `准入_开关开_TABLE格式_插件在场_有数据源`() {
        assertEquals(
            ExplainPanelChannel.Decision.Structured,
            ExplainPanelChannel.decide(
                enabled = true,
                format = ExplainOutputFormat.TABLE,
                databaseAvailable = true,
                hasBoundDataSource = true,
            ),
        )
    }

    @Test
    fun `准入_开关开_JSON格式_插件在场_有数据源`() {
        assertEquals(
            ExplainPanelChannel.Decision.Structured,
            ExplainPanelChannel.decide(
                enabled = true,
                format = ExplainOutputFormat.JSON,
                databaseAvailable = true,
                hasBoundDataSource = true,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 第二组：设计内直送（静默，不给轻提示——不是回退，是既有主路径）
    // ------------------------------------------------------------------

    @Test
    fun `直送_开关关`() {
        assertEquals(
            ExplainPanelChannel.Decision.DirectSilent,
            ExplainPanelChannel.decide(
                enabled = false,
                format = ExplainOutputFormat.TABLE,
                databaseAvailable = true,
                hasBoundDataSource = true,
            ),
        )
    }

    @Test
    fun `直送_TEXT格式不硬解析`() {
        // DM/H2/SQLite/Oracle（含 R23 二段式）一律维持直送通道——红线：勿硬解析
        assertEquals(
            ExplainPanelChannel.Decision.DirectSilent,
            ExplainPanelChannel.decide(true, ExplainOutputFormat.TEXT, true, true),
        )
    }

    @Test
    fun `直送_XML格式不硬解析`() {
        // SQL Server Showplan XML 是附加结果集（R23 channelNote 口径），维持直送
        assertEquals(
            ExplainPanelChannel.Decision.DirectSilent,
            ExplainPanelChannel.decide(true, ExplainOutputFormat.XML, true, true),
        )
    }

    @Test
    fun `直送_格式未知`() {
        assertEquals(
            ExplainPanelChannel.Decision.DirectSilent,
            ExplainPanelChannel.decide(true, null, true, true),
        )
    }

    // ------------------------------------------------------------------
    // 第三组：环境性回退（结构化预期成立但环境不可用——fail-closed + 轻提示）
    // ------------------------------------------------------------------

    @Test
    fun `回退_无已绑数据源`() {
        assertEquals(
            ExplainPanelChannel.Decision.DirectFallback(ExplainPanelChannel.FallbackReason.NO_DATA_SOURCE),
            ExplainPanelChannel.decide(true, ExplainOutputFormat.TABLE, true, false),
        )
    }

    @Test
    fun `回退_无database插件`() {
        assertEquals(
            ExplainPanelChannel.Decision.DirectFallback(ExplainPanelChannel.FallbackReason.NO_DATABASE_PLUGIN),
            ExplainPanelChannel.decide(true, ExplainOutputFormat.JSON, false, true),
        )
    }

    @Test
    fun `回退理由序_无插件优先于无数据源`() {
        // 两个都缺时报告更基础的缺插件（Community 下数据源根本绑不上，插件缺失才是根因；
        // 提示「先去绑数据源」会误导用户做不可能的操作）
        assertEquals(
            ExplainPanelChannel.FallbackReason.NO_DATABASE_PLUGIN,
            (ExplainPanelChannel.decide(true, ExplainOutputFormat.TABLE, false, false)
                as ExplainPanelChannel.Decision.DirectFallback).reason,
        )
    }

    // ------------------------------------------------------------------
    // 第四组：未回填参数（R33 真机走查修复——残留 `?` 不发结构化探测，响亮失败优于语法错）
    // ------------------------------------------------------------------

    @Test
    fun `回退_存在未回填参数`() {
        // 参数已填满（hasUnfilledParams=false）是结构化准入的第五条件——残留 `?` 下发探测
        // = 字面 `?` 送 JDBC 语法错（真机 SQLException: You have an error in your SQL syntax）
        assertEquals(
            ExplainPanelChannel.Decision.DirectFallback(ExplainPanelChannel.FallbackReason.UNFILLED_PARAMS),
            ExplainPanelChannel.decide(
                enabled = true,
                format = ExplainOutputFormat.TABLE,
                databaseAvailable = true,
                hasBoundDataSource = true,
                hasUnfilledParams = true,
            ),
        )
    }

    @Test
    fun `参数已回填则结构化准入不受影响`() {
        assertEquals(
            ExplainPanelChannel.Decision.Structured,
            ExplainPanelChannel.decide(
                enabled = true,
                format = ExplainOutputFormat.TABLE,
                databaseAvailable = true,
                hasBoundDataSource = true,
                hasUnfilledParams = false,
            ),
        )
    }

    @Test
    fun `回退理由序_环境缺项优先于未回填参数`() {
        // 环境缺项（插件/数据源）是更基础的根因——先修环境再谈参数，提示措辞按此序
        assertEquals(
            ExplainPanelChannel.FallbackReason.NO_DATA_SOURCE,
            (ExplainPanelChannel.decide(
                enabled = true,
                format = ExplainOutputFormat.JSON,
                databaseAvailable = true,
                hasBoundDataSource = false,
                hasUnfilledParams = true,
            ) as ExplainPanelChannel.Decision.DirectFallback).reason,
        )
    }

    // ------------------------------------------------------------------
    // 第五组（T-ObPanelAdvice）：OB TEXT 档放行——structuredTextPlan 在册方言的唯一例外
    // ------------------------------------------------------------------

    @Test
    fun `准入_OB_TEXT档在册放行`() {
        assertEquals(
            ExplainPanelChannel.Decision.Structured,
            ExplainPanelChannel.decide(
                enabled = true,
                format = ExplainOutputFormat.TEXT,
                databaseAvailable = true,
                hasBoundDataSource = true,
                dialectName = "OceanBase",
            ),
        )
    }

    @Test
    fun `直送_TEXT档其余方言不放行`() {
        // Oracle（二段式）/H2/SQLite 的 TEXT 无解析面（Entry.structuredTextPlan 默认 false）
        for (dialect in listOf("Oracle", "H2", "SQLite", "DM（达梦）")) {
            assertEquals(
                "TEXT 档方言 $dialect 必须维持直送",
                ExplainPanelChannel.Decision.DirectSilent,
                ExplainPanelChannel.decide(true, ExplainOutputFormat.TEXT, true, true, dialectName = dialect),
            )
        }
    }

    @Test
    fun `直送_TEXT档未传方言名不放行`() {
        // 既有调用形态（不传 dialectName）零改编译且语义不变：TEXT 一律直送
        assertEquals(
            ExplainPanelChannel.Decision.DirectSilent,
            ExplainPanelChannel.decide(true, ExplainOutputFormat.TEXT, true, true),
        )
    }

    @Test
    fun `OB_TEXT档环境闸照走`() {
        // 放行 ≠ 绕过环境闸：无插件/无数据源/参数未回填照样回退
        assertEquals(
            ExplainPanelChannel.FallbackReason.NO_DATABASE_PLUGIN,
            (ExplainPanelChannel.decide(true, ExplainOutputFormat.TEXT, false, true, dialectName = "OceanBase")
                as ExplainPanelChannel.Decision.DirectFallback).reason,
        )
        assertEquals(
            ExplainPanelChannel.FallbackReason.NO_DATA_SOURCE,
            (ExplainPanelChannel.decide(true, ExplainOutputFormat.TEXT, true, false, dialectName = "OceanBase")
                as ExplainPanelChannel.Decision.DirectFallback).reason,
        )
    }

    @Test
    fun `直送_OB_TEXT档开关关`() {
        // 例外只放宽格式档位，开关仍是总闸（与 MySQL/PG 同款语义）
        assertEquals(
            ExplainPanelChannel.Decision.DirectSilent,
            ExplainPanelChannel.decide(
                enabled = false,
                format = ExplainOutputFormat.TEXT,
                databaseAvailable = true,
                hasBoundDataSource = true,
                dialectName = "OceanBase",
            ),
        )
    }
}
