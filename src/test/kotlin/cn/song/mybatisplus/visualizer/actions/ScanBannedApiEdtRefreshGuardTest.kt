package cn.song.mybatisplus.visualizer.actions

import com.intellij.openapi.vfs.VirtualFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UI-001 守卫：`ScanBannedApiUsagesAction` 的 VFS refresh 必须留在**后台线程段**，不得回到 EDT。
 *
 * ## 背景（262 沙盒 idea.log 实证）
 *
 * `refreshAndFindFileByIoFile` 在 262 平台是 Slow Operations（栈顶
 * `FSRecordsImpl.update`）；UI-001 版本把它写在 `present()` 里，而 `present` 由
 * `Task.Backgroundable.run` 经 `invokeLater` 调回 EDT（write-intent read action 上下文），
 * 扫描完成后打开报告即抛 SEVERE，用户看到右下角红色「IDE error occurred」横幅。
 *
 * ## 为什么只能源码级把关
 *
 * 线程落点是**调用时序**属性：present 只在 `invokeLater` 回调里被调，运行期单测起不了
 * 真平台 EDT＋VFS 那套环境；等价判据是**文本位置**——refresh 调用必须位于后台任务体内
 * （`invokeLater { present(...) }` 之前），present 方法体内不得出现任何 VFS refresh / 磁盘 IO
 * （present 只收解析好的 `VirtualFile?`）。若有人把 refresh 挪回 present（即还原 UI-001），
 * 本守卫立即变红——这就是本缺陷的**测试级反向验证**。
 *
 * 另加反射断言钉死 present 形参类型：收 `VirtualFile` 不收 `java.io.File`（File 到手必经
 * refresh 才能变 VirtualFile，参数类型改回 File 即意味着 refresh 回流）。
 */
class ScanBannedApiEdtRefreshGuardTest {

    private fun sourceText(): String {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val candidate = File(dir, "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ScanBannedApiUsagesAction.kt")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 ScanBannedApiUsagesAction.kt（user.dir=${System.getProperty("user.dir")}）")
    }

    @Test
    fun `VFS_refresh_只出现在后台段_present_体内零_IO`() {
        val text = sourceText()
        val presentMarker = "private fun present("
        val presentIdx = text.indexOf(presentMarker)
        assertTrue("源码里应有 present 方法", presentIdx > 0)

        // 只数**调用形态**：注释/KDoc 里会提到方法名（类注释、后台段注释），裸方法名不计数
        val callPattern = "LocalFileSystem.getInstance().refreshAndFindFileByIoFile"
        val refreshOccurrences = Regex(Regex.escape(callPattern)).findAll(text).map { it.range.first }.toList()
        assertEquals(
            "VFS refresh 调用应恰好出现 1 次（UI-001 修复后只在后台线程段）",
            listOf(1),
            listOf(refreshOccurrences.size),
        )
        assertTrue(
            "refreshAndFindFileByIoFile 调用必须位于 present 方法之前（后台任务体内），不得回流 EDT 段",
            refreshOccurrences.first() < presentIdx,
        )

        // present 方法体只截到下一个成员声明（notify）为止——substring 到文件尾会把
        // writeTempReport/notify 的定义误算进 present 体内（红跑后绿跑首翻车实证）
        val presentBodyEnd = text.indexOf("private fun notify(", presentIdx).let { if (it < 0) text.length else it }
        val presentBody = text.substring(presentIdx, presentBodyEnd)
        listOf(
            "refreshAndFindFileByIoFile",
            "LocalFileSystem",
            "writeTempReport",
            "Files.create", // 裸 "Files." 会误报 scannedFiles.toString()（绿跑首翻车实证）
            "java.nio.file",
            "findFileByIoFile",
        ).forEach { forbidden ->
            assertFalse(
                "present 方法体内不得出现「$forbidden」（EDT 段只允许 UI 操作）",
                presentBody.contains(forbidden),
            )
        }
    }

    @Test
    fun `present_形参收_VirtualFile_不收_File`() {
        val method = ScanBannedApiUsagesAction::class.java.declaredMethods.firstOrNull { it.name == "present" }
        assertNotNull("应能反射到 present 方法", method)
        val paramTypes = method!!.parameterTypes.toList()
        assertTrue(
            "present 形参应含 VirtualFile，实际=$paramTypes",
            paramTypes.any { VirtualFile::class.java == it },
        )
        assertFalse(
            "present 不得收 java.io.File（收到 File 必然伴随 EDT 上的 VFS refresh，UI-001 形态），实际=$paramTypes",
            paramTypes.any { File::class.java == it },
        )
    }
}
