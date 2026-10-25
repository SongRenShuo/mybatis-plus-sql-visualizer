package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-SqlTemplateUx P2 补：`refreshResultsComponent` 的「错误晚检 vs 组件换入」解耦守卫。
 *
 * 背景（真机 2026-10-13 录制实录）：SQL 快捷模板三动作（EXPLAIN / COUNT / 抽样）执行一条
 * 必然失败的 SQL（`SELECT COUNT(*) FROM 对齐插件测试`）时，DBN 控制台 Output 页有报错、但
 * **全程没有任何通知气泡/弹窗**——执行失败对用户静默。
 *
 * 调用链判因：`SqlExecutor.execute(onConsole = null)` → 无嵌入宿主（`pendingOnConsole == null`）
 * → `startResultTabRetry` 的每拍早检在错误写入 history 之前未命中（连接建立慢时错误晚到约 2s），
 * 结果页签一出现即走 `result != null` 分支停表、把晚到错误交给 `refreshResultsComponent` 兜底；
 * 而该函数此前首行 `pendingOnConsole ?: return` **整体早退** → 晚检从未启动 → onError/onErrorRich
 * 都没被调用 → 无提示。这正是「实现有、门控漏」的接线缺口：纯逻辑单测照不出来（单测直接调函数），
 * 故用源码扫描守卫钉住接线形态。
 *
 * 两层守卫：
 *  1. 纯判据 DataSourceSelectorResultsKt.refreshSwapEnabled：组件换入仍需宿主 + 持久容器（A49 口径不回退）；
 *  2. 接线形态：`refreshResultsComponent` 不得因 `pendingOnConsole` 非空判定整体早退，
 *     且组件换入段必须以 refreshSwapEnabled 收窄。
 *
 * k2j 互操作：判据函数落在 Java 门面 DataSourceSelectorResultsKt（静态），具名实参改位置实参；
 * 源码扫描对象改为 src/main/java 下的 DataSourceSelectorResultsKt.java（判据串按 Java 等价形态适配）。
 *
 * 反向验证：把 `refreshResultsComponent` 首行改回 `pendingOnConsole ?: return`（并去掉 swapEnabled
 * 判据）→ [refreshResultsComponent_不因无宿主整体早退] 必红。
 */
class RefreshLateErrorWatchTest {

    // ---- 1) 纯判据：组件换入仍只在有宿主 + 持久容器时做（A49 口径不回退） ----

    @Test
    fun `refreshSwapEnabled_无宿主_不换入`() {
        assertFalse(
            "无嵌入宿主（SQL 快捷模板 / 日志转 SQL 执行链）时不得换入——摘 DBN 结果组件无收益",
            DataSourceSelectorResultsKt.refreshSwapEnabled(false, true),
        )
    }

    @Test
    fun `refreshSwapEnabled_有宿主有容器_换入`() {
        assertTrue(
            "有嵌入宿主且持久容器在场时必须换入（预览面板路径不回退）",
            DataSourceSelectorResultsKt.refreshSwapEnabled(true, true),
        )
    }

    @Test
    fun `refreshSwapEnabled_有宿主无容器_不换入`() {
        assertFalse(
            "持久容器缺席时无从换入",
            DataSourceSelectorResultsKt.refreshSwapEnabled(true, false),
        )
    }

    // ---- 2) 接线守卫：无宿主不得整体早退，错误晚检段必须与换入段解耦 ----

    @Test
    fun `refreshResultsComponent_不因无宿主整体早退`() {
        val text = sourceFile("DataSourceSelectorResultsKt.java")

        // 空绿防护：确认扫到的确实是本仓那份含 refreshResultsComponent 的源文件。
        assertTrue(
            "守卫未扫到 refreshResultsComponent —— 扫描规则已失效（空绿）",
            text.contains("refreshResultsComponent("),
        )
        // 缺陷形态：首行 `pendingOnConsole ?: return` 会让无宿主路径整体早退、晚检从不启动。
        assertFalse(
            "refreshResultsComponent 不得因 pendingOnConsole 非空判定整体早退——" +
                "无嵌入宿主路径（SQL 快捷模板三动作 / 日志转 SQL 的「执行」）正是靠它的错误晚检" +
                "拿到失败反馈（真机 2026-10-13 录制：执行失败全程无提示）",
            // k2j 适配：Kotlin `pendingOnConsole ?: return` 的 Java 等价形态是 `if (pendingOnConsole == null) return`
            text.contains("if (pendingOnConsole == null) return"),
        )
        // 解耦接线：换入段必须以 refreshSwapEnabled 收窄（错误晚检段不受其约束）。
        assertTrue(
            "refreshResultsComponent 的组件换入段必须以 refreshSwapEnabled(...) 收窄" +
                "（无宿主不摘 DBN 组件，A49 口径）",
            text.contains("swapEnabled = refreshSwapEnabled("),
        )
        // 晚检通道在场：无宿主的失败反馈靠它把错误交给 deliverError → onError/onErrorRich。
        // 断言只钉「调用点仍在、首实参仍是被刷新控制台」——`extractConsoleError` 在
        // T-ExplainConsoleFirst W7 增了 sqlText/project 形参用于跨源列证据升格，本函数无
        // project 上下文故只传三参；钉死整行实参会把签名演进误判成功能缺失（本轮实测）。
        assertTrue(
            "refreshResultsComponent 必须保留 extractConsoleError 晚检（失败反馈来源）",
            Regex("""extractConsoleError\(\s*shared != null \? shared : preparedConsole\s*,\s*LOG""").containsMatchIn(text),
        )
    }

    private fun sourceFile(name: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/java").isDirectory) break
            dir = dir.parentFile
        }
        val root = dir ?: error("未定位到仓库根：${System.getProperty("user.dir")}")
        // k2j 适配：生产树是 Java，扫描根 src/main/kotlin→src/main/java（SettingsTruthTest 同款惯例）
        return File(root, "src/main/java/cn/song/mybatisplus/visualizer/execute/$name").readText()
    }
}
