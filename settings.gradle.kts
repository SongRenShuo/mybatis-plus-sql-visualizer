import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "mybatis-plus-sql-visualizer"

// 子项目统一收拢于 sub-projects/（目录约定：后续每个独立子项目一个同名目录）。
// 采用组合构建（include + projectDir 重定向）而非 Cool Request 式完全独立构建——
// 保住「buildPlugin 自动构建并内嵌 agent jar」的任务依赖链与增量检查。
//
// M56 T-M56-01：agent 字节码增强子项目（Java 8 字节码 fat jar，ASM shade relocate 自包含）。
// 产物 mps-sql-agent.jar 经根项目 embedAgentJar 任务拷入主插件资源 resources/agent/，
// 运行时解压落盘后经 attach loadAgent 挂载——不进插件 classloader，避免污染插件依赖面。
include(":sql-visualizer-agent")
project(":sql-visualizer-agent").projectDir = file("sub-projects/sql-visualizer-agent")

// T-TestPerf2（2026-09-15）：build cache 关闭改落 settings 层。gradle.properties 的
// org.gradle.caching=false 会被用户级 ~/.gradle/gradle.properties 的 caching=true 按 Gradle
// 属性优先级（GRADLE_USER_HOME > 项目根）覆盖而空转——实证：冷启动分片构建出现
// 「5 from cache」，且 daemon 实际按用户级 jvmargs（MaxMetaspaceSize=256m）运行；
// 即 T-TestPerf-01 的「关闭」自落地起在本机从未真正生效。此块优先级高于属性文件，
// 才把历史三次成片 NoSuchMethodError 假失败的根源（复用陈旧 compileTestKotlin 产物）真正关死。
buildCache {
    local {
        isEnabled = false
    }
}

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.20"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // ⚠️ 升级本行 IPGP 版本时，必须同步 dev-support/verifier-cli-version.txt（当前 1.411）：
    // CI 的 verify / marketplace 六条腿按该文件里的版本从 Maven Central 取 verifier-cli，
    // 不同步就会**静默用旧版 verifier 跑判定**（不报错、门禁照绿）。步骤见
    // docs/发行说明/T-ReleaseChecklist-发行检查清单.md 的 RC-P2c；改完跑
    // VerifierCliVersionGuardTest 确认（本机缓存有 jar 时生效，CI 上会 skip）。
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

dependencyResolutionManagement {
    // Configure all projects' repositories
    repositories {
        // T-SAF-14（gradle-deps-aliyun-mirror-first-no-verification，D4=A 2026-11-06 定案）：
        // Maven Central 置前（可信根），阿里云镜像降为后备——Gradle 按首个含该模块的仓库解析，
        // 镜像置前时「与 Central 同源同版本」只是未验证假设（CI 对 wrapper 镜像的重写已证明
        // 作者并不将其视为等信任源）。镜像保留：代理失效/直连受阻时的可用性后备；
        // 配合 gradle/verification-metadata.xml 对字节做严格校验（替换字节失败关闭）。
        mavenCentral()

        maven("https://maven.aliyun.com/repository/public")

        // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
        intellijPlatform {
            defaultRepositories()
        }
    }
}
