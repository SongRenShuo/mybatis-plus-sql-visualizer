package cn.song.mybatisplus.visualizer.ui.highlight

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-StartupReadLockFreeze §二十三：补全的子句判定必须读**编辑器文档文本**，不能读 PSI 文本。
 *
 * 用户现象（2026-11-13）：「刚开始有补全，删掉最后几个字符再接着补就没提示了」。
 * 补全期间文档刚被改、PSI 还没 commit（嵌入式/非项目 PSI 的 commit 是惰性的），
 * 旧实现读 `position.containingFile.text` 得到的是**改动前**的 SQL：
 * 子句被判成 `TABLE` ⇒ 列名分支整段跳过，表现为「表名还能补，列名没了」。
 *
 * 这里用源码守卫钉住调用面（行为本身在编辑器里，单测无法稳定复现 commit 时机）。
 */
class SqlCompletionClauseSourceTest {

    @Test
    fun `子句判定必须读编辑器文档文本`() {
        val src = sourceOf(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/highlight/SqlCompletionContributor.kt",
        )
        assertTrue(
            "detectClause 必须喂 parameters.editor.document.text（与补全器同一坐标系）",
            "parameters.editor.document.text" in src,
        )
        assertFalse(
            "不得再把 position.containingFile 的 PSI 文本喂给 detectClause：补全期 PSI 可能尚未 commit",
            "containingFile?.text" in src,
        )
    }

    private fun sourceOf(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, relativePath)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        error("未找到 $relativePath（user.dir=${System.getProperty("user.dir")}）")
    }
}
