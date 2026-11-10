package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * changeNotes 守卫，两条独立规则：
 *
 * ## 一、评分引导句只允许在最新 `<h2>` 块出现一次
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
 *
 * ## 二、注解名不得写成裸 `@`
 *
 * 背景（用户 2026-11-10 报障「GitHub 发行版的 Contributors 每次多两个我不认识的」）：正文是从本文件
 * 逐条拼出来喂给 `gh release create --notes-file` 的，里面 `@Query` / `@Embedded` / `@Host` 这类
 * Java 注解名会被 GitHub 当成**用户提及**——发行版页面的 Contributors 于是列出 `query`（Roger Que）、
 * `Embedded`、`host` 三个无关账号；不存在同名账号的（`@NamedQuery` / `@TableName`）才侥幸没出现。
 * 三版实测：2026.10.26 只显示 Embedded（正文只有 `@Embedded`）、2026.11.7 只显示 host（正文是
 * `库名@host:port`）、2026.11.8 起显示 query and Embedded（正文两条都有）。
 *
 * 约定：注解名必须写成 `<code>@Query</code>`。**两种看似可行的写法都实测证伪**（2026-11-10，
 * `POST /markdown` 探针，四种形态同一份样本对比）：
 *  - `&#64;Query` 无效——GitHub 先解实体再识别提及，输出里照样是 `<a class="user-mention">`；
 *  - 反引号 `` `@Query` `` 无效——正文是裸 HTML 块，块内不跑 markdown 行内解析，反引号原样留在页面上；
 *  - 零宽空格 `@&#8203;Query` 有效但往正文里塞了不可见字符，不采用；
 *  - `<code>@Query</code>` 有效且干净（实测输出无 user-mention）。
 *
 * 灵敏度自证：把任一处 `<code>@X</code>` 拆成裸 `@X` → 该用例红并点出行号。
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

    @Test
    fun test注解名不得写成裸加号() {
        val text = notes()
        // <code>@X</code> 内的 @ 不会被 GitHub 识别成提及，剥掉这层再查裸 @
        val stripped = Regex("<code>[\\s\\S]*?</code>").replace(text, "<code/>")
        val lines = Regex("@(?=[A-Za-z])").findAll(stripped)
            .map { m -> stripped.substring(0, m.range.first).count { it == '\n' } + 1 }
            .toList()
        assertTrue(
            "changeNotes 里还有 ${lines.size} 处裸 @ 开头的字（行 ${lines.joinToString()}）——" +
                "GitHub 会把它们解析成 @用户 提及，发行版页面的 Contributors 会冒出无关账号；" +
                "注解名请写成 <code>@Query</code>（&#64; 与反引号两种写法实测均无效）",
            lines.isEmpty(),
        )
    }
}
