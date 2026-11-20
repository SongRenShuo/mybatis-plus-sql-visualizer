package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CI 关键测试腿接线守卫（T-SyncK2JMaster180，2026-11-20）。
 *
 * ## 为什么需要它
 *
 * 2026-11-20 实测（k2j 端口分支同步轮，同构缺口 master 一致）：`agent:test` 在 push-test 与 build-plugin gate 两条流水线里**都不跑**（两条都只跑单个
 * `:testShardN`，只有 `testShards` 聚合才 `dependsOn(":sql-visualizer-agent:test")`），唯一读数在
 * `nightly-full.yml`——而那条腿既可能因「当日无代码提交」整条 skip，也不阻断发版。后果是 agent jar
 * 被 `agentFatJar` 打进插件 zip，发版门禁却对它零行为校验（本轮 1 红 3 skip 全靠人工本地补跑才发现）。
 *
 * 缺口本身已修（gate 的打包命令并入 `:sql-visualizer-agent:test`），但**「腿存在」不是一次性的**：
 * 后来者重构 workflow、精简步骤、或误解「tests 已由分片覆盖」都会把它删掉，删了没有人红。本守卫把
 * 「CI 必须跑哪些腿」变成可机器校验的契约。
 *
 * ## 判据
 *
 * 三份 workflow 的**命令面**（剥掉 `#` 注释后）必须满足：
 * 1. `build-plugin.yml` 含 `:buildPlugin`，且**必须存在独立的 `agenttest:` job 块**承载
 *    `:sql-visualizer-agent:test`（发版门禁口径）；
 * 2. `build-plugin.yml` 的**打包命令行**（含 `:buildPlugin` 的那行）**不得**同时含
 *    `:sql-visualizer-agent:test`——串进 build 会把 3.3 分钟的 build 拉长到 5 分钟，而下游
 *    verify / marketplace / test 全在等 build，关键路径白付这份时间（用户定案：宁可多一个
 *    runner 也要压时间）；
 * 3. `nightly-full.yml` 含 `:sql-visualizer-agent:test` 与 `:testExternal`（夜间兜底口径）；
 * 4. `push-test.yml` 含 `:testShard`（推送快反馈口径）；
 * 5. 凡出现 `:sql-visualizer-agent:test` 的命令行都必须同时出现 `-PagentTestExclude`——该参数剔除需本机
 *    真库的 `AttachPoolWarmGateTest`，漏了会让 CI 因「无本机 MySQL 3306」假红（夜间与 gate 须同口径）。
 *
 * 自证：缺任一条的合成样本必须被抓出；三份齐备的样本必须放行。
 */
class CiWorkflowCoverageGuardTest : BannedApiScanSupport() {

    private fun workflow(name: String): String = File(repoRoot(), ".github/workflows/$name").readText()

    /** 剥行注释（YAML 只有行注释）——注释里的示例命令行不得参与判定。 */
    private fun stripYamlComments(source: String): String =
        source.lines().joinToString("\n") { line ->
            val idx = line.indexOf('#')
            if (idx >= 0) line.substring(0, idx) else line
        }

    /**
     * 判定内核：返回违规说明（空 = 合规）。入参是「文件名 → 原文」，可注入合成样本自证。
     *
     * 只看**命令面**：把剥注释后的全文视作一个命令集合——腿名出现即可，不解析 YAML 结构
     * （对「哪一步跑的」不做区分，因为步骤拆分/合并不应让守卫误红）；唯一的例外是打包命令行，
     * 它必须与 agent 腿解耦（见判据 2）。
     */
    internal fun violationsIn(files: Map<String, String>): List<String> {
        val problems = mutableListOf<String>()
        val stripped = files.mapValues { (_, text) -> stripYamlComments(text) }

        val buildPlugin = stripped["build-plugin.yml"]
        if (buildPlugin == null) {
            problems.add("缺 build-plugin.yml（发版门禁 workflow 被删或改名？）")
        } else {
            if (!buildPlugin.contains(":buildPlugin")) problems.add("build-plugin.yml 不再打包插件（缺 :buildPlugin）")
            if (!Regex("(?m)^\\s*agenttest:\\s*$").containsMatchIn(buildPlugin)) {
                problems.add("build-plugin.yml 缺独立 agenttest job——agent 测试没有并行腿载体")
            }
            if (!buildPlugin.contains(":sql-visualizer-agent:test")) {
                problems.add("build-plugin.yml 的 gate 不跑 :sql-visualizer-agent:test——发版门禁对 agent jar 零行为校验")
            }
            // 打包命令行必须与 agent 腿解耦：串行会拖长关键路径（下游全等 build）。
            buildPlugin.lines()
                .filter { it.contains(":buildPlugin") && !it.trimStart().startsWith("- name:") }
                .forEach { line ->
                    if (line.contains(":sql-visualizer-agent:test")) {
                        problems.add(
                            "build-plugin.yml 的打包命令行串了 :sql-visualizer-agent:test —— " +
                                "会拖长 build（下游 verify/marketplace/test 全等它），应留给独立 agenttest job",
                        )
                    }
                }
        }

        val nightly = stripped["nightly-full.yml"]
        if (nightly == null) {
            problems.add("缺 nightly-full.yml（夜间兜底 workflow 被删或改名？）")
        } else {
            if (!nightly.contains(":sql-visualizer-agent:test")) problems.add("nightly-full.yml 不跑 :sql-visualizer-agent:test")
            if (!nightly.contains(":testExternal")) problems.add("nightly-full.yml 不跑 :testExternal")
        }

        val pushTest = stripped["push-test.yml"]
        if (pushTest == null) {
            problems.add("缺 push-test.yml（推送测试 workflow 被删或改名？）")
        } else if (!pushTest.contains(":testShard")) {
            problems.add("push-test.yml 不再跑分片测试（缺 :testShard）")
        }

        // 口径一致：凡跑 agent 测试的命令都必须带真库剔除参数。
        stripped.forEach { (name, text) ->
            if (text.contains(":sql-visualizer-agent:test") && !text.contains("-PagentTestExclude")) {
                problems.add("$name 跑 :sql-visualizer-agent:test 但缺 -PagentTestExclude（需本机真库的用例会让 CI 假红）")
            }
        }
        return problems
    }

    @Test
    fun `CI 关键测试腿必须接线且口径一致`() {
        val files = listOf("build-plugin.yml", "nightly-full.yml", "push-test.yml")
            .associateWith { workflow(it) }
        val problems = violationsIn(files)
        assertTrue(
            "CI 测试腿契约违例（腿被删/被精简/口径漂移）：\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    @Test
    fun `自证_缺腿与串行回退的样本必须被抓出`() {
        val incomplete = mapOf(
            "build-plugin.yml" to """
                :buildPlugin -PgifRecord=false --console=plain
            """.trimIndent(),
            "nightly-full.yml" to ":testExternal --console=plain",
            "push-test.yml" to ":testShard1 --console=plain",
        )
        val problems = violationsIn(incomplete)
        assertTrue("缺独立 agenttest job 必须被抓出：$problems", problems.any { it.contains("缺独立 agenttest job") })
        assertTrue("缺 agent 腿必须被抓出：$problems", problems.any { it.contains("build-plugin.yml 的 gate 不跑") })
        assertTrue("夜间缺 agent 腿必须被抓出：$problems", problems.any { it.contains("nightly-full.yml 不跑 :sql-visualizer-agent:test") })

        // 夜间腿把 :testExternal 删掉也要抓（外部签名护栏属夜间独有面）。
        val missingExternal = mapOf(
            "build-plugin.yml" to """
                agenttest:
                  run: :sql-visualizer-agent:test -PagentTestExclude=cn.x.AttachPoolWarmGateTest
                run: ":buildPlugin -PgifRecord=false"
            """.trimIndent(),
            "nightly-full.yml" to ":sql-visualizer-agent:test -PagentTestExclude=cn.x.AttachPoolWarmGateTest",
            "push-test.yml" to ":testShard1",
        )
        assertTrue(
            "夜间缺 :testExternal 必须被抓出：${violationsIn(missingExternal)}",
            violationsIn(missingExternal).any { it.contains("nightly-full.yml 不跑 :testExternal") },
        )

        // 串行回退（把 agent 腿塞回打包命令）→ 也要抓，否则「压时间」的约束会被静默撤销。
        val serialized = mapOf(
            "build-plugin.yml" to """
                agenttest:
                  run: :sql-visualizer-agent:test -PagentTestExclude=cn.x.AttachPoolWarmGateTest
                run: ":buildPlugin :sql-visualizer-agent:test -PagentTestExclude=cn.x.AttachPoolWarmGateTest --console=plain"
            """.trimIndent(),
            "nightly-full.yml" to ":testExternal :sql-visualizer-agent:test -PagentTestExclude=cn.x.AttachPoolWarmGateTest",
            "push-test.yml" to ":testShard1",
        )
        assertTrue(
            "打包命令串行 agent 腿必须被抓出：${violationsIn(serialized)}",
            violationsIn(serialized).any { it.contains("串了 :sql-visualizer-agent:test") },
        )

        // 跑了 agent 但漏剔除参数 → 也要抓（否则 CI 假红）
        val missingExclude = mapOf(
            "build-plugin.yml" to """
                agenttest:
                  run: :sql-visualizer-agent:test
            """.trimIndent(),
            "nightly-full.yml" to ":testExternal :sql-visualizer-agent:test",
            "push-test.yml" to ":testShard1",
        )
        assertTrue(
            "缺 -PagentTestExclude 必须被抓出：${violationsIn(missingExclude)}",
            violationsIn(missingExclude).any { it.contains("-PagentTestExclude") },
        )
    }

    @Test
    fun `自证_完整样本必须放行`() {
        val complete = mapOf(
            "build-plugin.yml" to """
                build:
                  run: ":buildPlugin -PgifRecord=false --console=plain"
                agenttest:
                  run: ":sql-visualizer-agent:test -PagentTestExclude=cn.x.AttachPoolWarmGateTest"
            """.trimIndent(),
            "nightly-full.yml" to ":testExternal :sql-visualizer-agent:test -PagentTestExclude=cn.x.AttachPoolWarmGateTest",
            "push-test.yml" to ":testShard1 :testShard2 -PgifRecord=false",
        )
        val problems = violationsIn(complete)
        assertTrue("完整样本不得误报：$problems", problems.isEmpty())
    }
}