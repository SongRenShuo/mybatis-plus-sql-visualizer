package cn.song.mybatisplus.visualizer.update

import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * 包归属校验结果（T-LocalUpdateDir 计划 §3.3.1）。
 *
 * - [Ours]：id 是本插件、顶层布局唯一目录，[version]/[sinceBuildBaseline] 取自包内描述符；
 * - [NotOurs]：不是本插件（id 不符 / 描述符缺失或损坏）；
 * - [Tainted]：**id 是本插件但包不可信**——夹带其他顶层目录 / 顶层目录名不是插件目录名
 *   （重启脚本路径把 zip 整包解压到 IDE 插件根目录，夹带目录会被一起装成别的插件；目录名
 *   大小写变体在 Windows 上会互相覆盖）。
 */
internal sealed interface Ownership {
    data class Ours(
        val version: String,
        /** 包内 `<idea-version since-build>` 的主版本段（如 243）；未声明或不可解析为 null。 */
        val sinceBuildBaseline: Int?,
        val topLevelDir: String,
    ) : Ownership

    data class NotOurs(val reason: String) : Ownership
    data class Tainted(val reason: String) : Ownership
}

/**
 * 归属 + 布局校验（T-LocalUpdateDir 计划 §3.3.1 G2）：**这个通道只能装本插件**。
 *
 * 两道判据：
 * - G1 描述符归属：复用 [DynamicInstallSupport.validateArtifact]（zip 可打开、能定位
 *   plugin.xml——直属 `META-INF/plugin.xml` 或 lib 目录下插件 jar 内嵌两形态、`<id>` 非空可读），
 *   `id` 必须逐字等于 [UpdateSource.PLUGIN_ID]；
 * - G2 布局归属（本类新增）：zip 顶层**只能有一个目录**且名 == [UpdateSource.INSTALL_DIR_NAME]
 *   （目录内部不限）；唯一例外是完全平坦的 `META-INF/plugin.xml` 布局。任何第二个顶层目录、
 *   任何插件目录以外的顶层条目（含文件）即 [Tainted]。
 *
 * 真实 buildPlugin 产物布局实证（2026-11-12，`build/distributions/mybatis-plus-sql-visualizer-2026.11.12.zip`）：
 * 顶层仅 `mybatis-plus-sql-visualizer/` 一个目录（LICENSE / THIRD-PARTY-NOTICES.md 在插件目录内，
 * 非 zip 根）——严格判定不误杀真包（计划 P-9）。
 *
 * `since-build` 在同一次描述符读取里顺带取出（D-5 兼容性判据），不二次开包，也不改
 * [DynamicInstallSupport.ArtifactCheck] 现有签名。
 *
 * 只读中央目录与描述符，不解压、不触平台安装；纯 JVM（单测直接覆盖）。调用方放 BGT。
 */
internal object PluginPackageOwnership {

    /** `<idea-version since-build="…">` 属性提取（容忍属性序变化）。 */
    private val SINCE_BUILD_ATTR = Regex("""<idea-version[^>]*\ssince-build\s*=\s*"([^"]+)"""")

    fun verify(zip: Path): Ownership = runCatching { doVerify(zip) }.getOrElse {
        Ownership.NotOurs("安装包不可读: ${it.message ?: it.javaClass.simpleName}")
    }

    private fun doVerify(zip: Path): Ownership {
        // G1 描述符归属（复用既有校验：定位描述符 + id/version 非空）
        val artifact = DynamicInstallSupport.validateArtifact(zip)
        val version = when (artifact) {
            is DynamicInstallSupport.ArtifactCheck.Invalid ->
                return Ownership.NotOurs("安装包缺描述符或损坏: ${artifact.reason}")
            is DynamicInstallSupport.ArtifactCheck.Ok ->
                if (artifact.id != UpdateSource.PLUGIN_ID) {
                    return Ownership.NotOurs("这是别的插件（id=${artifact.id}）")
                } else {
                    artifact.version
                }
        }

        // G2 布局归属：顶层唯一目录 == INSTALL_DIR_NAME。
        // 注意 zip 的 `META-INF/plugin.xml` 直属条目会登记出顶层段 "META-INF"——平坦布局与
        // 标准布局的判定都要先把这一段摘出去（flat = 除 META-INF 外没有任何顶层目录）。
        ZipFile(zip.toFile()).use { zf ->
            val topDirs = LinkedHashMap<String, Int>()
            val topFiles = mutableListOf<String>()
            for (entry in zf.entries()) {
                val name = entry.name.replace('\\', '/')
                if (name.isEmpty()) continue
                val slash = name.indexOf('/')
                if (slash < 0) {
                    if (!entry.isDirectory) topFiles.add(name)
                    continue
                }
                val top = name.substring(0, slash)
                topDirs[top] = (topDirs[top] ?: 0) + 1
            }
            val nonMetaTopDirs = topDirs.keys.filter { it != "META-INF" }

            val ours = when {
                // 平坦布局（直属 META-INF/plugin.xml + 根级直属文件）：G1 已确认描述符在位，放行
                nonMetaTopDirs.isEmpty() ->
                    Ownership.Ours(version, readSinceBuildFromDescriptor(zf, null), "")
                nonMetaTopDirs.size == 1 -> {
                    val top = nonMetaTopDirs.single()
                    // 精确匹配（非忽略大小写）：zip 条目名大小写敏感，大小写变体在 Linux/macOS 上
                    // 会解出第二个插件目录（同 id 重复安装，平台状态损坏），在 Windows 上虽并入
                    // 同名目录但形态已不可信——一律 Tainted（计划 §3.3.1）
                    if (top != UpdateSource.INSTALL_DIR_NAME) {
                        return Ownership.Tainted(
                            "顶层目录名是 \"$top\"，不是本插件目录 \"${UpdateSource.INSTALL_DIR_NAME}\""
                        )
                    }
                    if (topFiles.isNotEmpty()) {
                        return Ownership.Tainted("顶层出现插件目录以外的条目: $topFiles")
                    }
                    if ("META-INF" in topDirs) {
                        return Ownership.Tainted("顶层同时出现插件目录与 META-INF 目录（非标准布局）")
                    }
                    Ownership.Ours(version, readSinceBuildFromDescriptor(zf, top), top)
                }
                else ->
                    return Ownership.Tainted("包内含多个顶层目录（夹带其他插件？）: ${nonMetaTopDirs}")
            }
            return ours
        }
    }

    /**
     * 从 zip 直属 `META-INF/plugin.xml`（或插件目录下同名条目、或 lib 目录下插件 jar 内嵌描述符）
     * 读 `<idea-version since-build>`。与 [DynamicInstallSupport.validateArtifact] 同查找序，
     * 读取复用其有界实现；找不到描述符（理论不可达——G1 已过）返回 null。
     */
    private fun readSinceBuildFromDescriptor(zf: ZipFile, topLevelDir: String?): Int? = runCatching {
        val xml = firstDescriptorXml(zf, topLevelDir) ?: return null
        parseSinceBuildBaseline(xml)
    }.getOrNull()

    private fun firstDescriptorXml(zf: ZipFile, topLevelDir: String?): String? {
        val directNames = buildList {
            if (topLevelDir == null) {
                add("META-INF/plugin.xml")
            } else {
                add("$topLevelDir/META-INF/plugin.xml")
                add("META-INF/plugin.xml")
            }
        }
        for (name in directNames) {
            zf.getEntry(name)?.let { return DynamicInstallSupport.readDescriptorBounded(zf.getInputStream(it)) }
        }
        // 内嵌形态：lib 目录下插件 jar 里的 META-INF/plugin.xml（IPGP buildPlugin 布局，与 validateArtifact 同序）
        for (entry in zf.entries()) {
            if (entry.isDirectory || !entry.name.endsWith(".jar") || !entry.name.replace('\\', '/').contains("/lib/")) continue
            java.util.zip.ZipInputStream(zf.getInputStream(entry).buffered()).use { inner ->
                var next = inner.nextEntry
                var scanned = 0
                while (next != null && scanned < DynamicInstallSupport.MAX_INNER_ENTRIES) {
                    if (next.name.equals("META-INF/plugin.xml", ignoreCase = true)) {
                        return DynamicInstallSupport.readDescriptorBounded(inner)
                    }
                    next = inner.nextEntry
                    scanned++
                }
            }
        }
        return null
    }

    /**
     * `since-build` 属性 → 主版本段整数（`243.*` / `IU-243.x` 等形态取前导数字）；
     * 未声明或不可解析 → null（视为「未声明」，不拦）。纯函数。
     */
    internal fun parseSinceBuildBaseline(descriptorXml: String): Int? =
        SINCE_BUILD_ATTR.find(descriptorXml)?.groupValues?.get(1)
            ?.let { Regex("^\\d+").find(it.trim())?.value?.toIntOrNull() }

    /**
     * D-5 兼容性判定（纯函数）：包要求的主版本段高于当前 IDE baseline ⇒ 不兼容。
     * 任一侧未知（包未声明 / 当前 baseline 读不到）⇒ 不拦（与远端通道 `warnsIncompatible`
     * 的「仅提示」口径对齐——这里拦的理由是共享盘可能出现「给别人 IDE 打的包」，未知侧不构成该风险）。
     */
    internal fun isSinceBuildIncompatible(sinceBuildBaseline: Int?, currentBaseline: Int?): Boolean =
        sinceBuildBaseline != null && currentBaseline != null && sinceBuildBaseline > currentBaseline
}
