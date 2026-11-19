package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「后台线程等 EDT 必须有界」形态守卫（T-UserFreezeTriage，2026-11-19 用户线程转储）。
 *
 * 缺陷实证（用户报障 dump，IDEA 2025.3.3）：EDT 上的写动作要等**所有非 EDT 线程的读锁**释放；
 * IDE 自带 `Duplicated code fragment` 检查在检查器读动作里为 quickfix 做全工程词条搜索，持读锁数秒
 * 到数分钟。此时任何无超时的 `invokeAndWait` 都会把调用方线程一起挂死——dump 里 Cool Request 的
 * 发送任务就停在 `SwingUtilities.invokeAndWait`，请求根本没发出去。
 *
 * 为什么是形态守卫：headless 测试造不出「第三方持长读锁」的真实时序；能机器化判定的只有调用形态。
 * 判据＝`src/main` 内除下列白名单外不得出现 `invokeAndWait`：
 *  - `util/EdtDispatch.java`：有界派发器本体（内部就是 `invokeLater` + 有界 `await`）；
 *  - `update/DynamicInstallSupport.java`：一次性安装流程的 EDT 串行卸载/装载（用户点安装时的单次
 *    阻塞，语义上必须等完成；同文件里的模态跳板已改走 EdtDispatch）。
 *
 * k2j 适配：扫描根 = src/main/java + .java（BannedApiScanSupport 已统一口径），白名单路径同步改 .java。
 */
class EdtBoundedWaitGuardTest : BannedApiScanSupport() {

    private val allowedFiles = setOf(
        "src/main/java/cn/song/mybatisplus/visualizer/util/EdtDispatch.java",
        "src/main/java/cn/song/mybatisplus/visualizer/update/DynamicInstallSupport.java",
    )

    /** 判定内核：命中裸 `invokeAndWait` 的文件清单（白名单除外）。写法含 `invokeAndWait(…)` 与尾随 lambda 两种。 */
    internal fun unboundedWaitOffenders(
        sources: List<Pair<String, String>>,
        allowed: Set<String> = allowedFiles,
    ): List<String> = sources
        .filter { (path, code) -> path !in allowed && code.contains("invokeAndWait") }
        .map { (path, _) -> path }

    @Test
    fun `后台等EDT必须有界_禁裸invokeAndWait`() {
        val offenders = unboundedWaitOffenders(mainSources())
        assertTrue(
            "src/main 出现裸 invokeAndWait（无超时等 EDT：第三方长读锁会把调用线程一起挂死，" +
                "用户冻结 dump 的同族形态）。命中 ${offenders.size} 处：$offenders。" +
                "改走 util.EdtDispatch.invokeLaterAndWaitBounded(reason=…)",
            offenders.isEmpty(),
        )
    }

    /**
     * 防空绿：有界派发器必须真的被接线。
     *
     * 若 P0 的两处调用点被改回同步直调、或派发器被整体删掉，「禁裸 invokeAndWait」会因为
     * 一条命中都没有而恒绿——这条把「有人真的在用」钉住。
     */
    @Test
    fun `有界派发器必须真被接线`() {
        val users = mainSources()
            .filter { (path, code) ->
                path != allowedFiles.first() && code.contains("EdtDispatch.invokeLaterAndWaitBounded(")
            }
            .map { (path, _) -> path }
        assertTrue(
            "src/main 除 EdtDispatch 本体外没有任何调用点使用有界派发（接线被撤或被改成同步直调？）：$users",
            users.isNotEmpty(),
        )
        val helper = mainSources().firstOrNull { (path, _) -> path == allowedFiles.first() }
        assertTrue("有界派发器本体不在源码树里：${allowedFiles.first()}", helper != null)
        val body = helper!!.second
        assertTrue("派发器本体必须走 invokeLater 投递", body.contains("invokeLater("))
        assertTrue("派发器本体必须有界等待（await(…, TimeUnit.MILLISECONDS)）", body.contains("await("))
    }

    /** 自证：裸调用必被点名、白名单放行、自等/超时两条分支都在本体里。 */
    @Test
    fun `自证_裸invokeAndWait必被点名`() {
        val banned = listOf(
            "src/main/java/cn/song/mybatisplus/visualizer/ui/Fake.java" to
                "internal fun f(app: Application) { app.invokeAndWait {} }",
        )
        assertEquals(
            "裸 invokeAndWait 必须被点名：${unboundedWaitOffenders(banned)}",
            1,
            unboundedWaitOffenders(banned).size,
        )
        val allowed = listOf(
            "src/main/java/cn/song/mybatisplus/visualizer/util/EdtDispatch.java" to
                "internal fun f(app: Application) { app.invokeAndWait {} }",
        )
        assertEquals(
            "白名单文件不应误报：${unboundedWaitOffenders(allowed)}",
            0,
            unboundedWaitOffenders(allowed).size,
        )
        assertEquals("空源码树不应报错", 0, unboundedWaitOffenders(emptyList()).size)
    }
}
