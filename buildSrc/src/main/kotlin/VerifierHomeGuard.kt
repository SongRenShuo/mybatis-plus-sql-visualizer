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
