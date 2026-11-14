package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.convention.BannedApiScanSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内嵌预览面板「原生执行 affordance」入口点扫描（T-SAF-04，
 * execute.embedded-native-console-exec-bypasses-confirm-chain）。
 *
 * 威胁模型：prepareAfterEditorResolved 曾在插件嵌入式面板内伪造完整原生执行上下文——
 * UiDataProvider 直供 PROJECT/EDITOR/VIRTUAL_FILE/PSI_FILE + DBN SessionClientHolder.CLIENT_KEY，
 * 并经 JdbcConsole.buildHeaderComponent 装 Console.Jdbc.Left（原生 ▶）——使原生 ▶/控制台
 * Ctrl+Enter 在内嵌面板可用，而这些路径不经过插件执行授权链（SqlTypeClassifier DML 确认、
 * ${}-raw/未知参数确认、ExecutionDialectGate.allowDialectGate——它们只存在于插件按钮链）。
 * 提交的文档文本源自捕获的控制台/日志输出（低信任主体），同级控制等价性失败。
 *
 * 修复决策（D1=A，2026-11-06，开发计划 §四）：移除 UiDataProvider 的 CLIENT_KEY 注入与
 * EDITOR 项，禁用原生执行入口，只留插件按钮全门禁链（原生入口是便利功能，失去后插件
 * 按钮仍在）。
 *
 * 扫描口径（剥注释后按出现次数断言——DataSourceSelectorConsole 的诊断探针
 * logDataContextCheck 合法保留同名字符串各一次，故断言「仅诊断残留」而非全零）：
 *  - SessionClientHolder 全文件仅诊断探针 1 处（CLIENT_KEY 注入与其反射查找 2 处被移除）；
 *  - CommonDataKeys.EDITOR 全文件仅诊断探针 1 处（sink.set EDITOR 项被移除）；
 *  - PROJECT/VIRTUAL_FILE/PSI_FILE 供给保留（其他平台能力仍需，非执行 affordance）。
 *
 * **修复前失败断言**：两个计数断言——修复前 SessionClientHolder 3 处（:247 查找、:297 注入、
 * :951 诊断）、CommonDataKeys.EDITOR 2 处（:283 注入、:949 诊断）。
 *
 * 本扫描只钉「入口点不接线」；原生 ▶ 在真机沙盒确实失效（动作 update 拿不到 console）
 * 需 owner runIde 观察（NEEDS-VALIDATION #4 部署检查），headless 无法驱动平台动作链。
 */
class EmbeddedNativeExecSourceScanTest : BannedApiScanSupport() {

    private fun consoleSource(): String =
        mainSources().firstOrNull { (path, _) -> path.replace('\\', '/').endsWith("execute/DataSourceSelectorConsole.kt") }?.second
            ?: error("DataSourceSelectorConsole.kt 不在 src/main 源码树中")

    @Test
    fun `内嵌面板DataProvider不得直供CLIENT_KEY与EDITOR_T-SAF-04`() {
        val code = stripComments(consoleSource())
        val clientKeySites = Regex("SessionClientHolder").findAll(code).count()
        val editorSites = Regex("CommonDataKeys\\.EDITOR").findAll(code).count()
        assertEquals(
            "SessionClientHolder 应仅剩诊断探针 1 处（CLIENT_KEY 注入与其反射查找必须移除，实际 $clientKeySites 处）",
            1, clientKeySites,
        )
        assertEquals(
            "CommonDataKeys.EDITOR 应仅剩诊断探针 1 处（UiDataProvider 的 EDITOR 项必须移除，实际 $editorSites 处）",
            1, editorSites,
        )
    }

    @Test
    fun `非执行affordance的供给保留_T-SAF-04`() {
        val code = stripComments(consoleSource())
        // PROJECT/VIRTUAL_FILE/PSI_FILE 保留（复制/导航等平台能力仍需；未要求移除）
        assertTrue("PROJECT 供给应保留", code.contains("CommonDataKeys.PROJECT"))
        assertTrue("VIRTUAL_FILE 供给应保留", code.contains("CommonDataKeys.VIRTUAL_FILE"))
        assertTrue("PSI_FILE（DataSink.lazy）供给应保留", code.contains("CommonDataKeys.PSI_FILE"))
    }
}
