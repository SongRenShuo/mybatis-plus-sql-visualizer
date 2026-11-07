package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * T-ConsoleBind263 §4.3：平台契约探针（[ExecFileBinding.detectMappingStrategies] / [ExecFileBinding.MAPPING_SPECS]）。
 *
 * 为什么要有这一层：2026.3 把 `setMapping(VirtualFile, LocalDataSource)` 收走后，插件侧**没有任何
 * 红灯**——旧链一路 `runCatching` 退化成「只清 detach、没清关联」，症状是用户在真机上看到
 * 「弹窗只有 1 项」。契约探针把「这版平台还有哪条写入口」变成一行可读日志 + 一条断言。
 *
 * **三层，取代旧的「拿不到类就打印一句话然后 return」**：
 *
 *  1. `合成夹具` 用例（恒跑）：用**真实反射**喂两种平台形态——263 形态（S1+S3+S4，无旧重载）与
 *     262 形态（只有旧重载 S2）——钉住探测用的方法名/签名。把 `setMappingWithWriteAction` 拼错
 *     一个字母，这层就红；不需要 Database 插件在测试类路径上。
 *  2. `spec ↔ 夹具` 一致性：策略集合由 [ExecFileBinding.MAPPING_SPECS] 驱动，这层保证「spec 里写的
 *     签名」与「夹具/生产反射实际查的签名」是同一组（避免两边各写一份漂移）。
 *  3. `真实平台 jar` 用例（可选，默认 skipped）：给 `-Dmpvs.contract.ide=<IDE 安装目录|jar|目录>`
 *     时**直接解析 class 文件**，核对 2026.3 上这几条签名到底在不在。
 *     为什么不用 `Class.forName`：263 的 class 是 Java 25 字节码（major 69），测试 JVM 是 17，
 *     加载必抛 `UnsupportedClassVersionError`（本轮实测：119 个 jar 全部解析失败 → 探测为空）。
 *     读字节没有版本约束，也不触发任何类初始化。
 *
 * 本地跑真 jar 用例（未给属性时按 skipped 计，**不是通过**；CI 没有 IDE 安装目录属预期）：
 * ```
 * ./gradlew test --tests "...PlatformMappingContractTest" \
 *   -Dmpvs.contract.ide="C:/Users/USER/AppData/Local/Programs/IntelliJ IDEA"
 * ```
 */
class PlatformMappingContractTest {

    // ------------------------------------------------------------------
    // 一、合成夹具：钉住反射签名（恒跑，不依赖 Database 插件）
    // ------------------------------------------------------------------

    /** 假 LocalDataSource —— 反射按参数类型精确匹配，夹具必须用它自己的类型。 */
    private class FakeLocalDataSource

    /** 263 形态：`setMapping(VirtualFile, String)` + 异步写动作 + 静态只写入口，**没有**旧重载。 */
    private class Impl263 {
        @Suppress("unused")
        fun setMapping(file: VirtualFile, uniqueId: String?) = Unit

        @Suppress("unused")
        fun setMappingWithWriteAction(
            file: VirtualFile,
            dataSource: FakeLocalDataSource?,
            onDone: Runnable?,
        ) = Unit

        @Suppress("unused")
        fun setDataSourcePerFileMapping(project: Project, file: VirtualFile, dataSource: FakeLocalDataSource) = Unit
    }

    /** 262 形态：只有旧重载 `setMapping(VirtualFile, LocalDataSource)`（263 已移除的那条）。 */
    private class Impl262 {
        @Suppress("unused")
        fun setMapping(file: VirtualFile, dataSource: FakeLocalDataSource?) = Unit
    }

    /** 只在接口上声明 S1、实现类不覆写的形态（代理/委托平台可能出现）——探针要能认出来。 */
    private class ApiOnly {
        @Suppress("unused")
        fun setMappingWithWriteAction(
            file: VirtualFile,
            dataSource: FakeLocalDataSource?,
            onDone: Runnable?,
        ) = Unit
    }

    /**
     * 夹具解析器：只替换 Database 侧三个类，其余（VirtualFile/Project/String/Runnable 等平台核心
     * 类型）照常从测试类路径解析——生产里它们由 `cachedClass` 解析，探测必须能拿到它们。
     */
    private fun resolverFor(impl: Class<*>?, api: Class<*>? = null): (String) -> Class<*>? = { fqcn ->
        when (fqcn) {
            PER_FILE_MAPPINGS_CLASS -> impl
            PER_FILE_MAPPINGS_API_CLASS -> api
            LOCAL_DS_CLASS -> FakeLocalDataSource::class.java
            else -> classOrNull(fqcn)
        }
    }

    private fun classOrNull(fqcn: String): Class<*>? =
        runCatching {
            Class.forName(fqcn, false, PlatformMappingContractTest::class.java.classLoader)
        }.getOrNull()

    @Test
    fun `263 形态探测到 S1-S3-S4 且没有旧重载 S2`() {
        val detected = ExecFileBinding.detectMappingStrategies(resolverFor(Impl263::class.java))
        assertEquals(
            listOf(
                ExecFileBinding.MappingStrategy.WRITE_ACTION,
                ExecFileBinding.MappingStrategy.STRING_ID,
                ExecFileBinding.MappingStrategy.STATIC_WRITE_ONLY,
            ),
            detected,
        )
        // CLEAR 语义：只能写的 S4 必须缺席（263 的静态入口内部直接取 ds.uniqueId）
        assertEquals(
            listOf(
                ExecFileBinding.MappingStrategy.WRITE_ACTION,
                ExecFileBinding.MappingStrategy.STRING_ID,
            ),
            ExecFileBinding.selectMappingStrategies(detected, clearing = true),
        )
    }

    @Test
    fun `262 形态探测到旧重载 S2`() {
        val detected = ExecFileBinding.detectMappingStrategies(resolverFor(Impl262::class.java))
        assertEquals(listOf(ExecFileBinding.MappingStrategy.LEGACY_DS), detected)
    }

    @Test
    fun `S1 只声明在接口上时仍被认出`() {
        val detected = ExecFileBinding.detectMappingStrategies(
            resolverFor(impl = null, api = ApiOnly::class.java),
        )
        assertEquals(listOf(ExecFileBinding.MappingStrategy.WRITE_ACTION), detected)
    }

    @Test
    fun `无 Database 类时探测为空而不是假装有入口`() {
        assertTrue(ExecFileBinding.detectMappingStrategies { null }.isEmpty())
        // LocalDataSource 缺席时，依赖 ds 参数的 S1/S2/S4 一律不得出现（S3 只需 VirtualFile+String）
        val noDsButImpl = ExecFileBinding.detectMappingStrategies { fqcn ->
            if (fqcn == PER_FILE_MAPPINGS_CLASS) Impl263::class.java else classOrNull(fqcn)
        }
        assertEquals(listOf(ExecFileBinding.MappingStrategy.STRING_ID), noDsButImpl)
    }

    // ------------------------------------------------------------------
    // 二、spec ↔ 夹具一致性（恒跑）：生产反射查的签名必须就是 spec 里写的
    // ------------------------------------------------------------------

    @Test
    fun `spec 的每条签名在对应夹具上都能被反射命中`() {
        val impl = Impl263::class.java
        val api = ApiOnly::class.java
        val typeMap = mapOf(
            "com.intellij.openapi.vfs.VirtualFile" to VirtualFile::class.java,
            "com.intellij.openapi.project.Project" to Project::class.java,
            "java.lang.Runnable" to Runnable::class.java,
            "java.lang.String" to String::class.java,
            LOCAL_DS_CLASS to FakeLocalDataSource::class.java,
        )
        // 263 夹具上 S1/S3/S4 三条都该命中——任何一条 miss 就说明 spec 与反射口径漂移了
        for (strategy in listOf(
            ExecFileBinding.MappingStrategy.WRITE_ACTION,
            ExecFileBinding.MappingStrategy.STRING_ID,
            ExecFileBinding.MappingStrategy.STATIC_WRITE_ONLY,
        )) {
            val spec = ExecFileBinding.MAPPING_SPECS.single { it.strategy == strategy }
            val owners = spec.owners.map { if (it == PER_FILE_MAPPINGS_API_CLASS) api else impl }
            val params = spec.paramTypes.map { requireNotNull(typeMap[it]) { "spec 里的未知类型 $it" } }.toTypedArray()
            assertTrue(
                "spec(${strategy.id}) 的签名在夹具上找不到——生产反射会静默 miss",
                owners.any { runCatching { it.getMethod(spec.methodName, *params) }.isSuccess },
            )
        }
        // 263 夹具**不该**命中旧重载（正是 263 的形态）
        val legacy = ExecFileBinding.MAPPING_SPECS.single { it.strategy == ExecFileBinding.MappingStrategy.LEGACY_DS }
        val legacyParams = legacy.paramTypes.map { requireNotNull(typeMap[it]) }.toTypedArray()
        assertFalse(
            "263 夹具不该有旧重载 S2——夹具形态写错了，后面的探测断言就没有意义",
            runCatching { impl.getMethod(legacy.methodName, *legacyParams) }.isSuccess,
        )
    }

    // ------------------------------------------------------------------
    // 三、真实平台 jar：直接读 class 文件核对签名（可选，默认 skipped）
    // ------------------------------------------------------------------

    @Test
    fun `真实平台 jar 上 spec 签名与字节码一致`() {
        val root = System.getProperty(CONTRACT_IDE_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue(
            "未指定 -D$CONTRACT_IDE_PROPERTY=<IDE 安装目录|jar|目录>，真 jar 契约校验跳过（skipped，不是通过）",
            root != null && root.exists(),
        )

        val jars = collectPlatformJars(root!!)
        assertTrue("$root 下没找到 intellij.database*.jar", jars.isNotEmpty())

        val classes = readClasses(jars, listOf(PER_FILE_MAPPINGS_CLASS, PER_FILE_MAPPINGS_API_CLASS))
        val implMethods = classes[PER_FILE_MAPPINGS_CLASS]
        assertTrue(
            "$root 的 jar 里找不到 ${PER_FILE_MAPPINGS_CLASS}.class（该 IDE 的 Database 插件布局与预期不同？）",
            implMethods != null,
        )

        val present = ExecFileBinding.MAPPING_SPECS.filter { spec ->
            spec.owners.any { owner ->
                val methods = classes[owner] ?: return@any false
                "${spec.methodName}${descriptor(spec.paramTypes)}" in methods
            }
        }.map { it.strategy }
        println(
            "[T-ConsoleBind263] real-jar contract: jars=${jars.size}, root=$root → $present",
        )

        assertTrue("平台一条写入口都没有——策略链无处可走", present.isNotEmpty())
        // 设计不变式：263 把同步旧重载换成了异步写动作 + String 同步写。若平台只给异步那条而没有
        // 同步兜底，我们的「写后回读 + 下沉到 S3」就会每次都失败——这条必须在契约层拦住。
        if (ExecFileBinding.MappingStrategy.WRITE_ACTION in present) {
            assertTrue(
                "平台有 S1(异步) 却没有 S3(同步兜底)：写后回读必然不符，换源会退化成「看着成功、其实没写」",
                ExecFileBinding.MappingStrategy.STRING_ID in present,
            )
        }
    }

    // ------------------------------------------------------------------
    // 工具：jar 收集 / class 文件方法表解析（不加载类，无 JVM 版本约束）
    // ------------------------------------------------------------------

    /** 收集 `plugins/DatabaseTools/lib` 与 `lib/modules` 下的 database jar；也接受直接给 jar/目录。 */
    private fun collectPlatformJars(root: File): List<File> {
        if (root.isFile) return listOf(root)
        val candidates = listOf(
            File(root, "plugins/DatabaseTools/lib/modules"),
            File(root, "plugins/DatabaseTools/lib"),
            root,
        )
        return candidates
            .filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".jar") } }
            .filter { it.name.startsWith("intellij.database") || it.name.startsWith("database-plugin") }
            .distinct()
    }

    /**
     * 从若干 jar 里按类名取 `方法名 + 描述符` 集合。
     *
     * 只做「常量池 + 方法表」两段解析：够读出签名，且**不加载类**——263 的 major 69 在 JDK17 上
     * 根本加载不了，这也正是本用例不用 `Class.forName` 的原因。
     */
    private fun readClasses(jars: List<File>, classNames: List<String>): Map<String, Set<String>> {
        val entries = classNames.associateWith { it.replace('.', '/') + ".class" }
        val out = HashMap<String, Set<String>>(entries.size)
        for (jar in jars) {
            if (out.size == entries.size) break
            runCatching {
                ZipFile(jar).use { zip ->
                    for ((fqcn, entryName) in entries) {
                        if (fqcn in out) continue
                        val entry = zip.getEntry(entryName) ?: continue
                        zip.getInputStream(entry).use { stream ->
                            out[fqcn] = parseMethodSignatures(stream.readBytes())
                        }
                    }
                }
            }
        }
        return out
    }

    /** 描述符（本处参数都是引用类型）：`(Lcom/x/Y;Ljava/lang/String;)V`。 */
    private fun descriptor(paramTypes: List<String>): String =
        paramTypes.joinToString(separator = "", prefix = "(", postfix = ")V") {
            "L${it.replace('.', '/')};"
        }

    /** 解析 class 文件的常量池与方法表，返回 `方法名描述符` 集合。 */
    private fun parseMethodSignatures(bytes: ByteArray): Set<String> {
        var pos = 0
        fun u1(): Int = bytes[pos++].toInt() and 0xFF
        fun u2(): Int = (u1() shl 8) or u1()
        fun u4(): Int = (u2() shl 16) or u2()
        fun skip(n: Int) {
            pos += n
        }

        require(u4() == 0xCAFEBABE.toInt()) { "不是 class 文件" }
        skip(4) // minor + major：**不校验版本**，263 的 69 正是本地 JVM 读不了它的原因
        val cpCount = u2()
        val utf8 = HashMap<Int, String>(cpCount)
        var index = 1
        while (index < cpCount) {
            when (val tag = u1()) {
                1 -> {
                    val len = u2()
                    utf8[index] = String(bytes, pos, len, Charsets.UTF_8)
                    pos += len
                }
                3, 4 -> skip(4)
                5, 6 -> {
                    skip(8)
                    index++ // long/double 占两个槽位
                }
                7, 8, 16, 19, 20 -> skip(2)
                9, 10, 11, 12, 17, 18 -> skip(4)
                15 -> skip(3)
                else -> error("未知常量池 tag=$tag（class 文件格式变了？）")
            }
            index++
        }
        skip(6) // access_flags + this_class + super_class
        repeat(u2()) { skip(2) } // interfaces
        fun skipAttributes() {
            repeat(u2()) {
                skip(2) // attribute_name_index
                skip(u4()) // attribute_length
            }
        }
        repeat(u2()) { // fields
            skip(6) // access + name + descriptor
            skipAttributes()
        }
        val methods = LinkedHashSet<String>()
        repeat(u2()) {
            skip(2) // access_flags
            val name = utf8[u2()] ?: error("方法名常量缺失")
            val desc = utf8[u2()] ?: error("方法描述符常量缺失")
            skipAttributes()
            methods += "$name$desc"
        }
        return methods
    }

    private companion object {
        const val CONTRACT_IDE_PROPERTY = "mpvs.contract.ide"
        const val PER_FILE_MAPPINGS_CLASS = "com.intellij.database.dataSource.DataSourcePerFileMappingsImpl"
        const val PER_FILE_MAPPINGS_API_CLASS = "com.intellij.database.console.DataSourcePerFileMappings"
        const val LOCAL_DS_CLASS = "com.intellij.database.dataSource.LocalDataSource"
    }
}
