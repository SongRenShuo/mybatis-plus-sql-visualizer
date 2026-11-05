package cn.song.mybatisplus.visualizer.log.agent

import java.io.File

/**
 * agent 宿主侧残留清理（F-07）：插件卸载/禁用/项目关闭后，清掉本插件落在宿主文件系统的痕迹。
 *
 * 残留清单（先读 AgentManager 落盘实现盘出的「插件走了痕迹还在」的具体物）：
 *  1. **attach 通道 jar**：`File.createTempFile("mps-sql-agent", ".jar")` 随机临时文件——
 *     已登记 deleteOnExit（IDE 正常退出自清），但 IDE 崩溃/强杀后残留；项目关闭时显式删除
 *     收窄残留窗口（[cleanupAttachTempJar]，[AgentManager.dispose] 调用）。
 *  2. **premain 通道 jar**：`<插件临时目录>/mpsv-agent/mps-sql-agent-<CRC32>.jar`——刻意**不**
 *     登记 deleteOnExit（目标 JVM 启动时读文件，早删会让「Error occurred during initialization
 *     of VM」）。插件卸载/禁用后不再有新注入，该文件从「必要」变「纯残留」，且 OS 临时目录
 *     回收周期不可控 → 卸载/末项目关闭时删除（[cleanupPremainResidue]，仅最后一个持有者动手）。
 *  3. **旧构建的 premain jar**：同一目录内 CRC 名不同（旧版本构建产物）——新 jar 落盘时顺带
 *     清掉（[pruneStalePremainJars]，attach 链零影响：只删不是当前要用的那份）。
 *
 * **不可清理面（文档化，设置页帮助文案同口径）**：已 attach/premain 织入**运行中目标应用 JVM**
 * 的 agent 类无法卸载（JVM 不支持 agent 卸载），只能随目标应用重启消失；插件卸载前的收口是
 * 下发 CONFIG 停采指令（AgentManager.dispose 的 best-effort sendConfig disabled=true），
 * 让应用侧尽早停止上报。
 *
 * 本类**零平台依赖**（可注入目录定向测试：文件被删/保留口径逐条断言）。
 */
object AgentResidueCleaner {

    /** premain jar 目录名（[AgentManager.extractPremainAgentJar] 落盘目录，插件临时目录下）。 */
    const val PREMAIN_DIR_NAME = "mpsv-agent"

    /** premain jar 文件名前缀（`mps-sql-agent-<CRC32>.jar`）；attach 随机临时文件同名族前缀。 */
    const val PREMAIN_JAR_PREFIX = "mps-sql-agent-"

    /**
     * premain jar 目录根：插件临时目录优先（平台 API 在 headless 测试环境可能未初始化，故带回退）。
     * 与 AgentManager 既有实现同源（落盘与清理必须同一处，清理才不落空）。
     */
    fun premainAgentDir(): File = runCatching {
        File(com.intellij.openapi.application.PathManager.getPluginTempPath())
    }.getOrElse { File(System.getProperty("java.io.tmpdir") ?: ".") }

    /**
     * 清空 premain 残留目录：删除目录内**本插件名族**的 jar 与 `.tmp` 半成品，目录空了连目录一起删。
     *
     * 保留口径（宁漏删不误删）：
     *  - 只删文件名以 [PREMAIN_JAR_PREFIX] 开头且以 `.jar` / `.jar.tmp` / `.tmp` 结尾的**普通文件**；
     *  - 其余名字（用户自己放进来的文件、其他插件的临时产物）一律不动；
     *  - 子目录不递归、不删除；
     *  - 删除失败（被占用等）静默跳过——残留留给 OS 临时目录回收，不抛不告警刷屏。
     *
     * @return 实际删除的文件数（目录删除不计）
     */
    fun cleanupPremainResidue(dir: File): Int {
        if (!dir.isDirectory) return 0
        var deleted = 0
        val entries = dir.listFiles() ?: return 0
        for (f in entries) {
            if (!f.isFile) continue // 子目录不递归不删
            if (!isOurs(f.name)) continue
            if (f.delete()) deleted++
        }
        // 目录空了连目录一起删（失败=还有别的东西，留给 OS 回收）
        if (dir.listFiles()?.isEmpty() == true) runCatching { dir.delete() }
        return deleted
    }

    /**
     * premain jar 落盘后的**陈旧产物修剪**：删除同目录内 CRC 名不同（旧构建）的本族 jar。
     * 当前要用的那份（[keepName]）与外来文件一律不动——不影响正常 attach/premain。
     *
     * @return 删除的陈旧 jar 数
     */
    fun pruneStalePremainJars(dir: File, keepName: String): Int {
        if (!dir.isDirectory) return 0
        var deleted = 0
        val entries = dir.listFiles() ?: return 0
        for (f in entries) {
            if (!f.isFile) continue
            if (!isOurs(f.name)) continue
            if (f.name == keepName) continue
            if (f.delete()) deleted++
        }
        return deleted
    }

    /**
     * 删除 attach 通道的随机临时 jar（本项目会话私有，无共享者）。文件不存在/为 null/删除失败
     * 一律 false（调用方只做日志，不据此走业务分支）。
     */
    fun cleanupAttachTempJar(jar: File?): Boolean {
        if (jar == null || !jar.isFile) return false
        return jar.delete()
    }

    /** 是否本插件名族（前缀 + jar/tmp 后缀；大小写敏感——本插件生成名大小写固定，更严只漏删不误删）。 */
    internal fun isOurs(name: String): Boolean {
        if (!name.startsWith(PREMAIN_JAR_PREFIX)) return false
        return name.endsWith(".jar") || name.endsWith(".jar.tmp") || name.endsWith(".tmp")
    }
}
