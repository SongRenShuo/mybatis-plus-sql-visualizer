package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.vfs.VirtualFile
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * exec 临时文件 vf+psi 单槽缓存的项目关闭钩子测试（T-PerfSweep2-04）。
 *
 * 背景：cachedTempPsiFile 是进程级单槽（T-PreviewExecDs 把跨项目泄漏收敛为单槽残留），
 * 项目关闭后槽里仍留该项目的 vf+psi 强引用至下次消费被顶掉。T-PerfSweep2-04 给写点统一
 * 挂一次性项目关闭钩子：disposable 树触发即清槽（仍属该项目才清，别项目缓存不误伤），
 * isCacheUsable 惰性校验保留为双保险。测试经 [DataSourceSelector.markCachedTempPsiFile]
 * 的 parentDisposable 参数注入自有 Disposable，手动 dispose 触发钩子。
 *
 * 全代理桩 + 真实 vf：BasePlatformTestCase 的 project.basePath 为 null（isCacheUsable 恒
 * false）且 VirtualFile 是抽象类不能代理——project/psi 用接口代理，vf 取 fixture 真实文件，
 * 代理项目 basePath 从 vf 真实路径反推（isCacheUsable 前缀校验可通过）。
 */
class CachedTempPsiFileTest : BasePlatformTestCase() {

    /** 真实 vf + 以其路径反推 basePath 的代理项目（isCacheUsable 前缀校验可通过）。 */
    private fun mainProjectAndVf(): Pair<Project, VirtualFile> {
        val vf = myFixture.addFileToProject(".idea/sql-visualizer/exec/mpsql-exec.sql", "select 1").virtualFile
        val root = vf.path.substringBefore("/.idea/")
        return proxyProject("main", root) to vf
    }

    private fun proxyProject(name: String, basePath: String?): Project = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getName" -> name
            "getBasePath" -> basePath
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "ProjectProxy($name)"
            else -> null
        }
    } as Project

    private fun proxyPsi(owner: Project): PsiFile = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(PsiFile::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getProject" -> owner
            // T-ExecPsiInvalid：缓存可用性现在同时看 PSI 有效性，桩必须能应答 isValid（否则拆箱 NPE）
            "isValid" -> true
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "PsiFileProxy(${owner.name})"
            else -> null
        }
    } as PsiFile

    /** 死 PSI 桩（isValid=false）：钉「缓存槽里躺着失效 PSI 时必须弃缓存重扫」。 */
    private fun deadPsi(owner: Project): PsiFile = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(PsiFile::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getProject" -> owner
            "isValid" -> false
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "DeadPsiProxy(${owner.name})"
            else -> null
        }
    } as PsiFile

    /**
     * T-ExecPsiInvalid（2026-11-01 门禁实证）：槽里是**失效 PSI** 时不得命中。
     *
     * 缺陷形态：`vf.isValid` 只反映文件还在不在；内容一变（doc 落盘 / 防抖自动保存 / 其他写方
     * 覆盖 mpsql-exec.sql）平台就丢掉旧 PSI，而 vf 依旧有效——此时命中缓存会把死 PSI 递进
     * Runner 枚举，读 `psi.text` 硬抛 `PsiInvalidElementAccessException`。
     */
    @Test
    fun testInvalidPsiRejectedAsCacheMiss() {
        val (main, vf) = mainProjectAndVf()
        val gate = Disposer.newDisposable()
        DataSourceSelector.markCachedTempPsiFile(main, vf, deadPsi(main), gate)
        assertNull("失效 PSI 必须判为未命中（否则续接链拿到死 PSI 必崩）", DataSourceSelector.consumeCachedTempPsiFile(main))
        assertNull("弃缓存后槽应清空，避免下次再撞", DataSourceSelector.consumeCachedTempPsiFile(main))
    }

    override fun tearDown() {
        DataSourceSelector.clearCachedTempPsiFileForTest()
        super.tearDown()
    }

    /** disposable 触发即清槽——项目关闭后不再残留 vf+psi 强引用。 */
    @Test
    fun testDisposableFiredClearsSlot() {
        val (main, vf) = mainProjectAndVf()
        val gate = Disposer.newDisposable()
        DataSourceSelector.markCachedTempPsiFile(main, vf, proxyPsi(main), gate)
        assertNotNull("写入后应可消费", DataSourceSelector.consumeCachedTempPsiFile(main))

        Disposer.dispose(gate)
        assertNull("钩子触发后应清槽", DataSourceSelector.consumeCachedTempPsiFile(main))
    }

    /** 钩子是一次性的：触发后同项目再次写入应重新挂上（hookedProjects 成员随触发移除）。 */
    @Test
    fun testRehookAfterFire() {
        val (main, vf) = mainProjectAndVf()
        val gate1 = Disposer.newDisposable()
        DataSourceSelector.markCachedTempPsiFile(main, vf, proxyPsi(main), gate1)
        Disposer.dispose(gate1)
        assertNull(DataSourceSelector.consumeCachedTempPsiFile(main))

        val gate2 = Disposer.newDisposable()
        DataSourceSelector.markCachedTempPsiFile(main, vf, proxyPsi(main), gate2)
        Disposer.dispose(gate2)
        assertNull("二次写入的钩子也应生效", DataSourceSelector.consumeCachedTempPsiFile(main))
    }

    /** 别项目的缓存不因本项目钩子误清——槽归属比对按 psi.project 身份。 */
    @Test
    fun testOtherProjectCacheNotCleared() {
        val (main, vf) = mainProjectAndVf()
        val gate = Disposer.newDisposable()
        DataSourceSelector.markCachedTempPsiFile(main, vf, proxyPsi(proxyProject("other", "/other")), gate)
        Disposer.dispose(gate)
        assertNotNull("槽里是别项目的 psi，本项目钩子不应清", DataSourceSelector.consumeCachedTempPsiFile(main))
    }
}
