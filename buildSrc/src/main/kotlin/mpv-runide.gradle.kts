import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("org.jetbrains.intellij.platform")
}

// T-BuildScriptSplit：开发沙盒分治——块级逐字搬运自根 build.gradle.kts（锚点 4c3e268），
// 注释随迁；验收=任务注册点查 + 真机走查（runIde 噪音修复/runIdeHotSwap 热替换链路）。

// T-TransformLockGuard（2026-11-26）：沙盒 IDE 的 JVM 工作目录就是平台首页，也就是
// ~/.gradle/caches/<ver>/transforms/<hash>/transformed/ideaIU-*。它崩溃时 HotSpot 默认把
// hs_err_pid<pid>.log 与 replay_pid<pid>.log 写进当前目录，等于往 Gradle 的 transform 输出里
// 塞外来文件——Gradle 校验 transform 输出快照后会判其失效，下次查询 runtimeDirectory 时把整个
// 目录删掉重解压；而此时主 IDE 的 Kotlin daemon、崩溃残留的 JPS javac worker 正占着 lib/*.jar，
// 于是 "Unable to delete directory" 直接把 runIde 打死，而且报错点只是「查询 runtimeDirectory
// 失败」，看不出与崩溃有关。
// 把两类崩溃产物显式指到 build/hs-err/：崩溃不再污染 transform 输出，日志也留在工程内可查。
// 实证链条（12:21 OOM 崩溃 → 缓存被污染 → 13:52 起沙盒锁死失败）见 docs/LESSONS.md L109。
//
// 注意：两个路径必须写成**各任务块内的局部 val**（下面 runIde / runIdeHotSwap 各一份），
// 不能提到脚本顶层——预编译脚本的顶层 val 会变成脚本类字段，provider 闭包随之捕获脚本实例，
// 配置缓存直接拒绝（实测报 "cannot serialize Gradle script object references"）。

tasks {
    // T-M31.9：消除 runIde 启动日志的 RegionUrlMapper SecurityException: setContextClassLoader 噪音。
    // 根因：发行版 vmoptions 的 -Djava.security.manager=CoreBootstrapSecurityManager 使 JDK 21 common
    // pool 在启动早期（SM 卸载前）创建 InnocuousForkJoinWorkerThread（setContextClassLoader 硬抛）。
    // 修复：独立 Java 源编译普通线程工厂 jar（不进入插件产物），追加 runIde 启动 classpath，
    // 经 java.util.concurrent.ForkJoinPool.common.threadFactory 属性生效（JDK 21 common pool 构造
    // 用 getSystemClassLoader 加载该类，无视 SM 状态）。注意：不可改为 -Djava.security.manager=allow
    // ——SM 是 FileSystems 在 initLevel 2 预初始化的触发者（PathClassLoader 构造依赖它），实测会崩。
    val compilePlainFjpf = register<JavaCompile>("compilePlainFjpf") {
        source = fileTree("dev-support/plain-fjpf/src")
        classpath = files()
        destinationDirectory.set(layout.buildDirectory.dir("plain-fjpf/classes"))
        options.release.set(17)
        options.encoding = "UTF-8"
    }
    val plainFjpfJar = register<Jar>("plainFjpfJar") {
        from(compilePlainFjpf.map { it.destinationDirectory })
        destinationDirectory.set(layout.buildDirectory.dir("plain-fjpf"))
        archiveFileName.set("plain-fjpf.jar")
    }
    // 热替换本地服务清理（runIdeHotSwap 的 finalizedBy——IDE 关闭/崩溃/任务失败都执行；
    // 只杀 pid 文件记录的自有进程，用户手动起的服务不接管、不误杀）。也可手动单独执行。
    register("hotswapServerStop") {
        // 配置缓存约束：任务动作内不得引用脚本对象——pid 路径在配置期解析为 File
        val pidFile = layout.buildDirectory.file("hotswap-server.pid").get().asFile
        doLast {
            val pid = pidFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
            if (pid.isNotEmpty()) {
                val isWindows = System.getProperty("os.name").lowercase().contains("windows")
                val cmd = if (isWindows) listOf("taskkill", "/F", "/T", "/PID", pid) else listOf("kill", pid)
                runCatching { ProcessBuilder(cmd).start().waitFor() }
                    .onFailure { logger.warn("hotswap server stop failed: ${it.message}") }
                logger.lifecycle("hotswap local server (pid=$pid) stopped")
            }
            runCatching { pidFile.delete() }
        }
    }
    // T-SandboxCacheHygiene：沙盒退出后收尾清缓存。两件事一起做，都交给
    // dev-support/gradle-transform-doctor.ps1（唯一实现）：
    //  ① 杀「父进程已死」的 JPS 外部 javac worker——沙盒 IDE 退出后它们会残留，一直占着
    //     transforms 里平台首页的 lib/*.jar（2026-11-26 实测：沙盒关掉后仍留 4 个在锁）。
    //  ② 被 JVM 崩溃产物（hs_err/replay）污染、或解压不完整的平台 transform 目录改名挪开，
    //     Gradle 下次自动重解压。
    // 为什么不用 Java 侧判定：实测本机 ProcessHandle.Info.commandLine() 对所有进程返回空
    //（连 Gradle daemon 自己都是 cmdLen=0），拿不到命令行就认不出谁是 javac worker；
    // 进程身份只能取 WMI。
    // 只收尾不报错：清理失败记 WARN，不让它把 runIde 的退出码带坏。
    register("sandboxCacheHygiene") {
        group = "intellij platform"
        description = "Kill JPS javac workers orphaned by a closed runIde sandbox and clear polluted platform transform dirs."
        val doctor = rootProject.file("dev-support/gradle-transform-doctor.ps1")
        val hygieneLog = layout.buildDirectory.file("sandbox-hygiene.log").get().asFile
        doLast {
            if (!doctor.isFile) {
                logger.warn("sandboxCacheHygiene: $doctor not found - skipped")
                return@doLast
            }
            // 干净检出（build/ 尚未生成）时重定向目标目录可能不存在
            hygieneLog.parentFile?.mkdirs()
            val process = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", doctor.absolutePath, "-Fix"
            ).redirectErrorStream(true).redirectOutput(hygieneLog).start()
            val rc = runCatching { process.waitFor() }.getOrElse { -1 }
            if (rc == 0) logger.lifecycle("sandbox cache hygiene done (log: $hygieneLog)")
            else logger.warn("sandbox cache hygiene exited with $rc (log: $hygieneLog)")
        }
    }
    runIde {
        autoReload = true
        dependsOn(plainFjpfJar)
        // 沙盒一退出（正常退出/崩溃/任务失败三条出口）就收尾清缓存，见 T-SandboxCacheHygiene。
        finalizedBy("sandboxCacheHygiene")
        classpath += files(layout.buildDirectory.file("plain-fjpf/plain-fjpf.jar"))
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "-Djava.util.concurrent.ForkJoinPool.common.threadFactory=" +
                    "cn.song.mybatisplus.visualizer.support.PlainForkJoinWorkerThreadFactory"
            )
        })
        // 崩溃日志改道 build/hs-err（T-TransformLockGuard）：局部 val，配置缓存安全；
        // mkdirs 放在执行期求值，配置缓存命中后 build/ 被清也能就地补建。
        val crashLogDir = layout.buildDirectory.dir("hs-err").get().asFile
        val crashLogArgs = listOf(
            "-XX:ErrorFile=" + File(crashLogDir, "hs_err_pid%p.log").absolutePath,
            "-XX:ReplayDataFile=" + File(crashLogDir, "replay_pid%p.log").absolutePath
        )
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            crashLogDir.mkdirs()
            crashLogArgs
        })
        // 录制沙盒的许可补丁（本机 IDEA Ultimate 靠 javaagent 绕过签名校验）。
        // 两点事实（2026-10-25 实测，见 docs/经验总结/20261025-0830-…）：
        //  1) IntelliJ Platform Gradle Plugin 2.x 只读平台自身 bin/idea64.exe.vmoptions
        //     （IntelliJPlatformArgumentProvider 走 product-info.json 的 vmOptionsFilePath），
        //     沙盒 config/idea64.exe.vmoptions 不再被 merge；
        //  2) agent 加载 ≠ 许可生效——许可实体是沙盒 config/idea.key，build/ 被 clean 后
        //     整个沙盒消失、key 也不会自动恢复。缺 key 时 agent 照样加载（jcmd 能列出
        //     53 个 janetfilter 类）但界面仍弹 Start Free Trial。
        // 故这里只负责把 agent 传进去，key 的事在文档里单列一步。
        // 路径由 -Pmpvs.licenseAgent=... 给定，缺席则一个参数都不加（其他机器/CI 零影响）。
        // 注意必须在执行期读——配置期 System.getProperty 会被配置缓存冻结（同下面清单 URL 那条）。
        val licenseAgent = providers.gradleProperty("mpvs.licenseAgent").orNull
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { file(it).isFile }
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            licenseAgent?.let { listOf("-javaagent:$it") } ?: emptyList()
        })
        // T-DynUpdate 职责分离（用户定案）：hotswap 本地服务起停只挂 runIdeHotSwap
        //（Run IDE (Local Manifest) 链路专属）——本任务（Run IDE with Plugin）是纯开发沙盒，
        // 不依赖更新清单服务，曾因无条件起服务 + build/distributions 缺 zip（clean 后）导致
        // 启动即挂（hotswap server exited during startup）。旧 .run 同名配置已删除，无误触入口。
        // -Dmpvs.update.manifest.url 转发保留：显式传参时仍可用（属性缺席为空，无副作用）。
        // 清单 URL 转发必须在执行期读——配置期 System.getProperty 被配置缓存冻结
        //（runIdeHotSwap 同款修复，见该任务注释）
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            System.getProperty("mpvs.update.manifest.url")
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf("-Dmpvs.update.manifest.url=$it") }
                ?: emptyList<String>()
        })
    }
}

intellijPlatformTesting {
    // T-DynUpdate 热替换本地验证沙盒（配套 dev-support/hotswap-local-server.py 与
    // .run/runIdeLocalManifest.run.xml）：与开发 runIde 沙盒隔离，验证自更新免重启全链
    //（本地清单 → 下载 → DynamicInstallSupport 卸载旧版+装载新版）。
    // autoReload 必须关闭——插件自动重载器监听 plugins 目录文件变化，会在热替换的
    //「解包中途」触发并发重载，与 EDT 卸载/装载序列竞态（卸载失败 → 测试假阴性）。
    // 沙盒插件是 jar-copy 安装（非 build/classes 直载），dev 守卫正常放行；
    // 验证后重置：删 .intellijPlatform/hotswap-sandbox 或重跑本任务（prepareSandbox 覆盖插件目录）。
    // 本地服务随任务生命周期自动起停：doFirst 起 hotswap-local-server.py（端口 8919 被占则
    // 复用不接管——pid 文件留空，清理任务即无害空转）；finalizedBy 清理保证 IDE 正常关闭、
    // 崩溃、任务失败三条出口都停服务，且只杀自己起的进程（按 pid 文件）。
    runIde {
        register("runIdeHotSwap") {
            task {
                autoReload.set(false)
                dependsOn(tasks.named("plainFjpfJar"))
                classpath += files(layout.buildDirectory.file("plain-fjpf/plain-fjpf.jar"))
                jvmArgumentProviders.add(CommandLineArgumentProvider {
                    listOf(
                        "-Djava.util.concurrent.ForkJoinPool.common.threadFactory=" +
                            "cn.song.mybatisplus.visualizer.support.PlainForkJoinWorkerThreadFactory"
                    )
                })
                // 崩溃日志改道 build/hs-err（T-TransformLockGuard，与开发沙盒同一口径）
                val crashLogDir = layout.buildDirectory.dir("hs-err").get().asFile
                val crashLogArgs = listOf(
                    "-XX:ErrorFile=" + File(crashLogDir, "hs_err_pid%p.log").absolutePath,
                    "-XX:ReplayDataFile=" + File(crashLogDir, "replay_pid%p.log").absolutePath
                )
                jvmArgumentProviders.add(CommandLineArgumentProvider {
                    crashLogDir.mkdirs()
                    crashLogArgs
                })
                // 清单 URL 转发必须在执行期读（jvmArgumentProviders 每次执行求值）——配置期
                // System.getProperty 会被配置缓存冻结：首次无 -D 的运行存下空配置，后续带
                // -D 的运行不再转发（实证：11:21 会话 URL 已转发系旧路径，CC 风险仍在）
                jvmArgumentProviders.add(CommandLineArgumentProvider {
                    System.getProperty("mpvs.update.manifest.url")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { listOf("-Dmpvs.update.manifest.url=$it") }
                        ?: emptyList<String>()
                })
                // 本地服务随任务生命周期自动起停：doFirst 起 hotswap-local-server.py（端口被占
                // 则复用不接管——pid 文件留空，清理任务即无害空转）；finalizedBy 清理保证 IDE
                // 正常关闭、崩溃、任务失败三条出口都停服务，且只杀自己起的进程（按 pid 文件）。
                // 配置缓存约束：路径在配置期解析为 File，doFirst 不引用脚本对象/布局访问器。
                val pidFile = layout.buildDirectory.file("hotswap-server.pid").get().asFile
                val logFile = layout.buildDirectory.file("hotswap-server.log").get().asFile
                val scriptFile = rootProject.file("dev-support/hotswap-local-server.py")
                doFirst {
                    // 端口须与 .run/runIdeLocalManifest.run.xml 清单 URL 及脚本默认值一致
                    val port = 8919
                    fun listening() = runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress("localhost", port), 500)
                            true
                        }
                    }.getOrDefault(false)
                    if (listening()) {
                        logger.lifecycle("hotswap local server already listening on $port — reusing (not owned, left running at exit)")
                        pidFile.writeText("")
                        return@doFirst
                    }
                    if (!scriptFile.isFile) throw GradleException("hotswap-local-server.py not found: $scriptFile")
                    val proc = ProcessBuilder("python", scriptFile.absolutePath)
                        .redirectOutput(logFile)
                        .redirectErrorStream(true)
                        .start()
                    pidFile.writeText(proc.pid().toString())
                    val deadline = System.currentTimeMillis() + 20_000
                    while (System.currentTimeMillis() < deadline) {
                        if (listening()) {
                            logger.lifecycle("hotswap local server started (pid=${proc.pid()}, log=$logFile)")
                            return@doFirst
                        }
                        if (!proc.isAlive) throw GradleException("hotswap server exited during startup — see $logFile")
                        Thread.sleep(300)
                    }
                    runCatching { proc.destroy() }
                    throw GradleException("hotswap server not listening on $port within 20s — see $logFile")
                }
                finalizedBy("hotswapServerStop")
                // 与开发沙盒同口径收尾清缓存（T-SandboxCacheHygiene）
                finalizedBy("sandboxCacheHygiene")
            }
        }
    }
}

// 本地清单测试集成见 dev-support/run-ide-local-manifest.sh（自动起/停 Python 服务器）
