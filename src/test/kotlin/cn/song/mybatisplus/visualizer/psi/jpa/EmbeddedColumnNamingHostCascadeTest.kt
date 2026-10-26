package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import java.io.File
import java.nio.file.Files

/**
 * T-EmbeddedHostCascade：嵌入列命名探测的**宿主模块级联**（实体模块 miss → 查询宿主模块）。
 *
 * 形态动机（20261026 外部报障「JAR 里的 @Embedded 不识别」排查）：实体打包进依赖 JAR/lib 模块时，
 * `component-path` 命名策略配置在**宿主 App 模块**的 `application.yml`，实体自身模块往往只有
 * 普通配置没有命名策略——`6919d8ef` 的「按实体所在模块收范围」在该形态下必 miss。级联次序＝
 * 实体模块 → 宿主模块，**两档都判不出不回退工程级扫描**（多模块聚合根打开时兄弟模块配置外溢
 * 正是 `6919d8ef` 要防的形态，test同模块不外溢 钉住）。
 *
 * 夹具用真实本地临时目录（探测按 LocalFileSystem 路径上溯找 `pom.xml` 模块标记、扫
 * `src/main/resources`）。各模块都放普通 `application.yml`（无命名配置），保证判定
 * 确定性走**模块扫描**路径、不落工程级扫描（那条路径依赖异步索引，会飘）。
 */
class EmbeddedColumnNamingHostCascadeTest : BasePlatformTestCase() {

    private var root: File? = null

    override fun tearDown() {
        try {
            root?.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    /** baseDir 下按相对路径落文件；`yml:<内容>` 写 yml，其余写 pom 标记。 */
    /** baseDir 下按相对路径落文件；内容 `pom`＝模块标记，`cp`＝component-path 配置，其余＝普通 yml。 */
    private fun scaffold(vararg files: Pair<String, String>): File {
        val base = root ?: Files.createTempDirectory("embed-cascade-").toFile()
            .also { root = it }
        files.forEach { (rel, kind) ->
            val f = File(base, rel)
            f.parentFile.mkdirs()
            f.writeText(
                when (kind) {
                    "pom" -> "<project/>"
                    "cp" -> "spring:\n  jpa:\n    hibernate:\n      naming:\n        implicit_naming_strategy: component-path\n"
                    else -> "server:\n  port: 0\n"
                },
            )
        }
        return base
    }

    private fun vf(base: File, rel: String) =
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(base, rel))!!

    private fun resolve(anchorPath: String, hostPath: String?, base: File): EmbeddedColumnNamingResolver.NamingResolution =
        EmbeddedColumnNamingResolver.resolveDetailed(
            project,
            vf(base, anchorPath),
            hostPath?.let { vf(base, it) },
        )

    fun test宿主模块级联命中() {
        val base = scaffold(
            "lib-module/pom.xml" to "pom",
            "lib-module/src/main/resources/application.yml" to "plain", // 普通 yml：模块扫描可进、判不出
            "lib-module/src/main/java/Entity.java" to "plain",
            "host-module/pom.xml" to "pom",
            "host-module/src/main/resources/application.yml" to "cp",
            "host-module/src/main/java/Dao.java" to "plain",
        )
        val r = resolve("lib-module/src/main/java/Entity.java", "host-module/src/main/java/Dao.java", base)
        assertEquals("实体模块判不出、宿主模块有 component-path → 级联命中", EmbeddedColumnNaming.COMPONENT_PATH, r.naming)
        assertEquals("来源应标宿主级联", "probe-hit-host", r.source)
    }

    fun test实体模块自身配置优先于宿主() {
        val base = scaffold(
            "lib-module/pom.xml" to "pom",
            "lib-module/src/main/resources/application.yml" to "cp", // 有命名配置
            "lib-module/src/main/java/Entity.java" to "plain",
            "host-module/pom.xml" to "pom",
            "host-module/src/main/java/Dao.java" to "plain",
        )
        val r = resolve("lib-module/src/main/java/Entity.java", "host-module/src/main/java/Dao.java", base)
        assertEquals("实体模块自身有配置 → 直接命中不走级联", EmbeddedColumnNaming.COMPONENT_PATH, r.naming)
        assertEquals("probe-hit", "probe-hit", r.source)
    }

    fun test宿主也无配置回落无前缀() {
        val base = scaffold(
            "lib-module/pom.xml" to "pom",
            "lib-module/src/main/resources/application.yml" to "plain",
            "lib-module/src/main/java/Entity.java" to "plain",
            "host-module/pom.xml" to "pom",
            "host-module/src/main/resources/application.yml" to "plain",
            "host-module/src/main/java/Dao.java" to "plain",
        )
        val r = resolve("lib-module/src/main/java/Entity.java", "host-module/src/main/java/Dao.java", base)
        assertEquals("两档都判不出 → 无前缀（不回退工程级）", EmbeddedColumnNaming.NONE, r.naming)
        assertEquals("probe-miss", "probe-miss", r.source)
    }

    fun test同模块不外溢() {
        // 6919d8ef 防外溢契约的反向钉：anchor 与 host 同模块（无配置），兄弟模块有 component-path
        // ——不得借级联或工程级扫描把兄弟配置判进来
        val base = scaffold(
            "app-module/pom.xml" to "pom",
            "app-module/src/main/resources/application.yml" to "plain",
            "app-module/src/main/java/Repo.java" to "plain",
            "sibling-module/pom.xml" to "pom",
            "sibling-module/src/main/resources/application.yml" to "cp",
        )
        val r = resolve("app-module/src/main/java/Repo.java", "app-module/src/main/java/Repo.java", base)
        assertEquals("同模块判不出 → 无前缀（兄弟模块配置不得外溢）", EmbeddedColumnNaming.NONE, r.naming)
        assertEquals("probe-miss", "probe-miss", r.source)
    }
}
