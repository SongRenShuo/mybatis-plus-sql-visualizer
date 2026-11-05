package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.log.ReplayScriptExport
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.OracleDialect
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * R36 回放导出弹框（[ReplayExportDialog]）headless 构造测试（T-TestGapSpeed T1 补盲区）。
 * 不调用 show()（依赖真实屏幕），只构造与断言；导出文本映射经
 * [ReplayScriptExport]（弹框只承载选择态，组装全在导出侧）。
 * 注：okAction/cancelAction/content 均为平台 protected，仓内惯例是经弹框自带的
 * public `wrapCheck`/`doDispose` 访问（同 RollbackSqlDialogTest 口径）。
 */
class ReplayExportDialogTest : BasePlatformTestCase() {

    private fun newDialog(
        transactionBegin: String? = MySqlDialect.transactionBegin,
        dialectName: String = MySqlDialect.name,
    ) = ReplayExportDialog(project, dialectName, transactionBegin)

    // ======================== DialogWrapper 结构 ========================

    fun testDialogWrapperStructureTitleAndCheckboxWired() {
        val dialog = newDialog()
        try {
            assertEquals(
                "弹框标题应取 bundle",
                MpSqlVisualizerBundle.message("replay.export.title"),
                dialog.title,
            )
            assertNotNull("勾选框应已装入中央面板树（init 即 createCenterPanel）", dialog.wrapCheck.parent)
            assertEquals("中央面板应为勾选框+提示两条组件", 2, dialog.wrapCheck.parent.componentCount)
            assertTrue(
                "面板第二组件应为禁用态提示（JLabel）",
                dialog.wrapCheck.parent.components[1] is javax.swing.JLabel,
            )
        } finally {
            dialog.doDispose()
        }
    }

    fun testSupportedDialectCheckboxEnabledAndSelectedByDefault() {
        val dialog = newDialog()
        try {
            assertTrue("MySQL 有显式事务语句，选项应可用", dialog.wrapCheck.isEnabled)
            assertTrue("可用方言默认勾选", dialog.wrapCheck.isSelected)
            assertTrue("生效包裹意愿应为 true", dialog.wrapTransaction)
        } finally {
            dialog.doDispose()
        }
    }

    fun testUserUncheckTurnsOffWrap() {
        val dialog = newDialog()
        try {
            dialog.wrapCheck.isSelected = false
            assertFalse("用户取消勾选后不应包裹", dialog.wrapTransaction)
        } finally {
            dialog.doDispose()
        }
    }

    // ======================== 方言无显式事务语句：禁用态（诚实降级） ========================

    fun testUnsupportedDialectCheckboxDisabledWithHint() {
        val dialog = newDialog(transactionBegin = OracleDialect.transactionBegin, dialectName = OracleDialect.name)
        try {
            assertFalse("Oracle 无显式事务语句，选项应禁用", dialog.wrapCheck.isEnabled)
            assertFalse("禁用态选择意愿恒 false", dialog.wrapTransaction)
        } finally {
            dialog.doDispose()
        }
    }

    fun testUnsupportedDialectHintNamesDialectNotBeginStatement() {
        val dialog = newDialog(transactionBegin = OracleDialect.transactionBegin, dialectName = OracleDialect.name)
        try {
            val hint = dialog.wrapCheck.parent.components
                .filterIsInstance<javax.swing.JLabel>()
                .firstOrNull { it.text.contains(OracleDialect.name) }
            assertTrue("禁用提示应包含方言名（Oracle）", hint != null)
            assertTrue(
                "禁用提示应是降级文案：${hint?.text}",
                hint!!.text == MpSqlVisualizerBundle.message(
                    "replay.export.wrap.unsupported", OracleDialect.name,
                ),
            )
        } finally {
            dialog.doDispose()
        }
    }

    fun testUnsupportedDialectForcedSelectedStillFalse() {
        // 双保险：绕过禁用态强置勾选，生效意愿也不得为 true（不产出必然语法错误的伪包裹）。
        val dialog = newDialog(transactionBegin = null, dialectName = "DM（达梦）")
        try {
            assertFalse(dialog.wrapCheck.isEnabled)
            dialog.wrapCheck.isSelected = true
            assertFalse("禁用态强置勾选不得生效", dialog.wrapTransaction)
        } finally {
            dialog.doDispose()
        }
    }

    fun testSupportedDialectHintMentionsBeginStatement() {
        val dialog = newDialog()
        try {
            val hint = dialog.wrapCheck.parent.components
                .filterIsInstance<javax.swing.JLabel>()
                .firstOrNull()
            assertTrue("可用方言提示应含事务开始语句：${hint?.text}", hint?.text?.contains("START TRANSACTION") == true)
        } finally {
            dialog.doDispose()
        }
    }

    // ======================== 导出内容映射：弹框选择态 → 导出文本 ========================

    fun testCheckedDialogStateMapsToWrappedScript() {
        val dialog = newDialog()
        try {
            assertTrue(dialog.wrapTransaction)
            val plan = ReplayScriptExport.plan(
                "SELECT id FROM user WHERE id = 1;\nUPDATE user SET name = 'tom' WHERE id = 1;",
                MySqlDialect,
                dialog.wrapTransaction,
            ) ?: error("有语句应产出计划")
            val out = ReplayScriptExport.render(plan)
            val lines = out.trim().lines()
            assertEquals("包裹开时首条语句前应为方言事务开始语句", "START TRANSACTION;", lines[4])
            assertEquals("包裹开时应以 COMMIT 收尾", ReplayScriptExport.COMMIT_STATEMENT, lines.last())
            assertTrue("注释头应标注包裹形态", out.contains("-- transaction: START TRANSACTION ... COMMIT"))
            assertEquals("注释头 statements 应等于语句条数", "-- statements: 2", lines[2])
        } finally {
            dialog.doDispose()
        }
    }

    fun testUncheckedDialogStateMapsToPlainScript() {
        val dialog = newDialog()
        try {
            dialog.wrapCheck.isSelected = false
            val plan = ReplayScriptExport.plan("SELECT 1;", MySqlDialect, dialog.wrapTransaction)!!
            val out = ReplayScriptExport.render(plan)
            assertTrue(out.contains("-- transaction: none\n"))
            assertFalse("未勾选不得出现事务语句", out.contains("START TRANSACTION") || out.contains("COMMIT"))
        } finally {
            dialog.doDispose()
        }
    }

    // ======================== 非法输入：空语句/缺字段不抛异常 ========================

    fun testEmptySqlYieldsNoExportPlan() {
        val dialog = newDialog()
        try {
            // 空预览（无可执行语句）→ plan 返回 null，调用方不发导出，不抛异常。
            assertNull(
                "空白输入应返回 null",
                ReplayScriptExport.plan("   \n  ", MySqlDialect, dialog.wrapTransaction),
            )
        } finally {
            dialog.doDispose()
        }
    }

    fun testNullTransactionBeginConstructionDoesNotThrow() {
        // 缺字段（transactionBegin = null）直接构造也应安全：勾选框禁用 + 降级文案，不抛异常。
        val dialog = newDialog(transactionBegin = null, dialectName = "DM（达梦）")
        try {
            assertEquals(
                "降级文案兜底应指向方言名",
                MpSqlVisualizerBundle.message("replay.export.wrap.unsupported", "DM（达梦）"),
                dialog.wrapCheck.parent.components
                    .filterIsInstance<javax.swing.JLabel>()
                    .first { it.text.contains("DM") }.text,
            )
        } finally {
            dialog.doDispose()
        }
    }
}
