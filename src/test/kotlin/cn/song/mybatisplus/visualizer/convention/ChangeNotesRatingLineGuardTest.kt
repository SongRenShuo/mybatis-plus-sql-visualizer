package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * changeNotes 评分引导句守卫：**只允许在最新 `<h2>` 块出现一次**。
 *
 * 背景（用户 2026-10-30 第二次指出，同一问题在 2026-10-29 提过一次）：`gradle/plugin-change-notes.html`
 * 每发一版就置顶一块，而「好用的话，欢迎到 Marketplace 页评分留言」这行如果跟着模板一起复制，
 * 就会在历史块里越积越多——用户在 IDE 的「What's new」里逐块翻，会看到同一句反复出现。
 * 约定是：**只有最新块带一行**，旧块一律不带。
 *
 * 判定面（纯文本、零平台依赖，headless 直跑）：
 *  1. 引导句在全文中出现次数恰为 1；
 *  2. 该行落在**第一个** `<h2>` 块内（即最新版本块），不是历史块。
 *
 * 灵敏度自证：把引导句复制到第二块 → 第 1 条红；只留历史块、删掉最新块那行 → 第 2 条红。
 * 两条都在实施轮实测过（见 2026-10-30 提交记录）。
 */
class ChangeNotesRatingLineGuardTest {

    private val ratingLine = "好用的话，欢迎到 Marketplace 页评分留言"

    private fun notes(): String = File("gradle/plugin-change-notes.html").readText()

    @Test
    fun test评分引导句全文只出现一次() {
        val count = notes().windowed(ratingLine.length).count { it == ratingLine }
        assertEquals(
            "changeNotes 的评分引导句只允许出现一次（当前 $count 次）——历史块不要跟着复制这一行",
            1,
            count,
        )
    }

    @Test
    fun test评分引导句落在最新块内() {
        val text = notes()
        val firstBlockEnd = text.indexOf("</ul>")
        val firstBlock = text.substring(0, if (firstBlockEnd >= 0) firstBlockEnd else text.length)
        assertTrue(
            "评分引导句必须落在最新（第一个 <h2>）块内，否则用户在「What's new」里翻到的历史版本也会看到这句",
            firstBlock.contains(ratingLine),
        )
    }
}
