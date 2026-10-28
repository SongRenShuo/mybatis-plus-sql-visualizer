package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue

/**
 * 券02（T-PerfSweep27-02）：JpaEntityResolver 缓存容量上限守卫。
 *
 * 配置签名（含 T-EmbeddedHostCascade 的宿主锚点维度）变化后旧键永不命中也永不淘汰——
 * 无上限时长会话缓慢累积。上限语义与 [cn.song.mybatisplus.visualizer.psi.EntityResolver]
 * 同款：写入前超限整体清空再写（HbmXml 负缓存范式；键轻值纯，重解析成本可接受）。
 */
class JpaEntityResolverCacheBoundTest : BasePlatformTestCase() {

    private fun resolveEntity(code: String): PsiClass {
        myFixture.configureByText("Entity.java", code)
        return PsiTreeUtil.findChildOfType(myFixture.file, PsiClass::class.java)!!
    }

    /** 设置表前缀并执行断言，finally 恢复原状态（APP 级服务跨测试方法共享，必须还原防污染）。 */
    private fun withTablePrefix(prefix: String, block: () -> Unit) {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.tablePrefix = prefix
            block()
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun test缓存容量上限_超限整体清空() {
        JpaEntityResolver.clearCache()
        val saved = JpaEntityResolver.maxCacheEntries
        JpaEntityResolver.maxCacheEntries = 3
        try {
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                public class BoundJpaEntity {
                    @jakarta.persistence.Id
                    private Long id;
                    private String name;
                }
                """.trimIndent(),
            )
            // 4 个不同配置签名 = 4 个不同缓存键；修复前第 4 次写入后 cacheSize=4（无上限，先红）
            repeat(4) { i ->
                withTablePrefix("jb$i" + "_") {
                    JpaEntityResolver().resolve(clazz)
                }
            }
            assertTrue(
                "写入第 4 条后缓存应被上限守卫清空重填（cacheSize=${JpaEntityResolver.cacheSize()}），不得超过 maxCacheEntries=3",
                JpaEntityResolver.cacheSize() <= 3,
            )
            // 清空后可重填：最后一次签名（jb3_）解析结果仍正确
            withTablePrefix("jb3_") {
                val again = JpaEntityResolver().resolve(clazz)
                assertTrue("上限清空后应可正常重填并解析: ${again.tableName}", again.tableName.isNotBlank())
            }
        } finally {
            JpaEntityResolver.maxCacheEntries = saved
            JpaEntityResolver.clearCache()
        }
    }
}
