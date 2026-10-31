// T-BuildScriptSplit：构建逻辑分治的 buildSrc 骨架。convention 插件（mpv-*）见 src/main/kotlin/，
// 块级来源与去向见 docs/开发计划/20260917-0817 计划 §2.1/§3.1。
// buildSrc 是独立构建，不吃根 settings 的 pluginManagement——IPGP/changelog 版本在此显式钉死，
// 必须与根 settings.gradle.kts 声明的版本保持一致（IPGP 2.18.1 / changelog 2.5.0）。
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.intellij.platform:org.jetbrains.intellij.platform.gradle.plugin:2.18.1")
    implementation("org.jetbrains.changelog:org.jetbrains.changelog.gradle.plugin:2.5.0")
}

// T-TestShardParallel：并发 Gradle 进程共享 buildSrc/build 时，任一路重编会让其余路的
// 输出指纹失效并级联重编，最终互踩生成源（compilePluginsBlocks FAILED）。
// 并行运行器用 -PmpvBuildSrcDir 给每个 lane 指独立输出目录；缺省仍是 build，
// 串行 testShards / 日常构建行为不变。
providers.gradleProperty("mpvBuildSrcDir").orNull?.let {
    layout.buildDirectory.set(file(it))
}
