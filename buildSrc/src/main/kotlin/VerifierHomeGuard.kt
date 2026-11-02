package mpv.verification

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * T-ReleasePerf8（20261024）：共享 verifier home 的并发锁探测。
 *
 * 独立 .kt 文件而非 gradle.kts 顶层函数的原因与 [MarketVerdictChecker] 相同：
 * doFirst lambda 引用脚本顶层函数触发配置缓存「cannot serialize Gradle script object references」。
 *
 * 背景：verifier home 缺省从 build/ 迁到 GRADLE_USER_HOME/mpv-verifier-home/{gate,marketplace}
 * （机器级持久，发行 detached 工作树不再付 3.33GB 冷下载税，探针实证 warm=0 B、跨树 10 天
 * build 号 130/131 复用）。共享 home 重开 T-VerifierHomeIso 消灭过的冲突类（两 verifier 并发写
 * 同一 extracted-plugins），本探测是纵深防御：doFirst 扫描整机 java 进程命令行，发现指向同一
 * home 的外部 PluginVerifierMain 即硬失败。探测自身失败降级为 WARN 放行——主防线是全量门禁
 * 互斥定例；但探到真冲突必须硬失败，不做静默共写。
 *
 * 实现注记：PowerShell 5.1 下引号/反斜杠转义面大（Git Bash CJK 与转义坑在案），用 base64
 * -EncodedCommand 下发（UTF-16LE），零转义歧义；且必须**单行**——多行 script block 里跨行 `-and`
 * 在 5.1 实测静默不匹配（Where-Object 恒空、无报错），单行同进程同条件立刻命中（反向验证实录）。
 */
fun assertNoForeignVerifier(home: File, logger: Logger) {
    home.mkdirs()
    val ps = (
        "\$found = Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | " +
            "Where-Object { \$null -ne \$_.CommandLine -and " +
            "\$_.CommandLine.Contains('PluginVerifierMain') -and " +
            "\$_.CommandLine.Contains('$home') } | " +
            "ForEach-Object { 'FOREIGN ' + \$_.ProcessId }; " +
            "if (-not \$found) { 'CLEAR' } else { \$found }"
        )
    val encoded = Base64.getEncoder().encodeToString(ps.toByteArray(Charsets.UTF_16LE))
    val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
        .redirectErrorStream(true).start()
    val finished = proc.waitFor(45, TimeUnit.SECONDS)
    // CLIXML 清洗：stdout 重定向时 PowerShell 5.1 把进度序列化成 #< CLIXML + <Obj ...> XML 噪声，
    // 混进错误信息不可读；判定与展示都基于清洗后的文本。
    val out = if (finished) proc.inputStream.readBytes().decodeToString()
        .lineSequence().filter { it != "#< CLIXML" && !it.startsWith("<Obj ") && it.isNotBlank() }
        .joinToString("\n") else ""
    if (!finished) {
        logger.warn(
            "verifier home 并发锁探测超时（45s）——按纵深防御降级放行，主防线=全量门禁互斥定例；" +
                "若另一门禁确实在跑请人工错峰",
        )
        proc.destroyForcibly()
        return
    }
    if (out.contains("FOREIGN")) {
        throw GradleException(
            "共享 verifier home 正被另一 verifier 进程使用（$home）——两 verifier 并发写同一缓存会互踩\n" +
                "（T-VerifierHomeIso 冲突类）。探到的进程：\n$out" +
                "处置：等对方门禁收口再跑，或 -PverifierHomeRoot=build 回落每工作树隔离 home。",
        )
    }
    logger.lifecycle("verifier home 锁探测 CLEAR（$home）")
}

// T-ReleasePerf11（20261103）券01：market 三目标并行化支撑件。
//
// 独立 .kt 顶层函数（配置缓存可序列化）与上方 assertNoForeignVerifier 同款理由。

/**
 * 在 gradle 缓存 transforms 下解析一个 IDE 发行目录（ideaIU-2024.3.7.1-win 等）。
 * 原逻辑内嵌在 verifyMarketplace 的 argumentProviders lambda 里，三目标并行拆分后
 * 三个子任务都要用它，提升为共享顶层函数（逐字搬运，语义不变）。
 */
fun resolveIdeDir(gradleUserHome: File, name: String): File =
    gradleUserHome.resolve("caches").listFiles()?.asSequence()
        ?.filter { it.resolve("transforms").isDirectory }
        ?.flatMap { ver -> ver.resolve("transforms").listFiles()?.asSequence() ?: emptySequence() }
        ?.map { hash -> hash.resolve("transformed").resolve(name) }
        ?.firstOrNull { it.isDirectory }
        ?: error("IDE 发行目录 $name 未在 gradle 缓存 transforms 找到（先跑一次 verifyPlugin/构建一次对应平台）")

/**
 * 给并行化的 marketplace 子 home 做一次性种子：把主 marketplace home 的既有缓存拷过去，
 * 使三目标并行拆分不付 3×3.33GB 冷下载税（计划 P-3 的对冲）。
 *
 * 语义：目标子 home 存在 stamp 或 extracted-plugins 非空即视为已初始化，原样返回（幂等）；
 * 主 home 未初始化（目录不存在或为空）时跳过种子（新环境走 verifier 自下载，行为与拆分前一致）。
 * 拷贝失败降级为 WARN 放行——种子是提速手段不是正确性依赖，verifier 拿到空 home 会自己下载。
 *
 * 只拷缓存形态目录（extracted-plugins / dependencies / download），不拷锁/日志/stamp：
 *   - .mpv-refresh-stamp 刻意不拷——子 home 各自的 stamp 由 refresh 任务独立维护，拷主 stamp
 *     会让三子 home 的陈旧判定失真；
 *   - download 下的临时文件（.part 等）verifier 自身容忍，不再精细过滤。
 */
fun seedVerifierHome(master: File, sub: File, logger: org.gradle.api.logging.Logger) {
    val seededMarker = File(sub, "extracted-plugins")
    if (seededMarker.isDirectory && seededMarker.listFiles()?.isNotEmpty() == true) return
    if (!master.isDirectory || master.listFiles()?.isNullOrEmpty() != false) return
    sub.mkdirs()
    runCatching {
        listOf("extracted-plugins", "dependencies", "download").forEach { dirName ->
            val src = File(master, dirName)
            if (src.isDirectory) copyDirRecursively(src, File(sub, dirName))
        }
        logger.lifecycle("verifier home seed: $sub <- $master（一次性，避免三目标并行拆分重付冷下载税）")
    }.onFailure { e ->
        logger.warn("verifier home seed 失败（${e.javaClass.simpleName}: ${e.message}）——子 home 将自行下载（冷税一次性）")
    }
}

/**
 * 递归目录拷贝（Kotlin stdlib 实现）——不用 org.gradle.util.internal.GFileUtils：
 * internal 包无 API 稳定性承诺（虽在 9.7 在位，javap 实证），种子是锦上添花件不值得背这个债。
 * 绝对路径去重防软链/相对路径自嵌套（按 canonicalPath 比对，异常时退 equals）。
 */
private fun copyDirRecursively(src: File, dst: File) {
    val s = runCatching { src.canonicalPath }.getOrDefault(src.absolutePath)
    val d = runCatching { dst.canonicalPath }.getOrDefault(dst.absolutePath)
    require(s != d && !d.startsWith(s + File.separator)) { "src/dst 重叠：$src -> $dst" }
    if (src.isDirectory) {
        dst.mkdirs()
        src.listFiles()?.forEach { copyDirRecursively(it, File(dst, it.name)) }
    } else {
        dst.parentFile.mkdirs()
        src.copyTo(dst, overwrite = true)
    }
}
