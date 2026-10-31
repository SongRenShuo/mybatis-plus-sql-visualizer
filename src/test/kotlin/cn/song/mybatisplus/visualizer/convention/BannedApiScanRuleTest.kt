package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 写安全·模态 / catch(Throwable) 致命 / 池化线程读 PSI / 跳转族读权限 四族（内核与其自证用例同文件）
 * （T-M53-II 2026-10-10：成员逐字符搬运；可见性按拆类需要统一放宽为 internal。）
 */
class BannedApiScanRuleTest : BannedApiScanSupport() {

    /**
     * `invokeLater` 调用站点：**括号实参区**与**尾随 lambda 体**一次取全（T-FS-06 缺口一的修法）。
     *
     * 提取跑在 [maskNonCode] 后的文本上（配平必须避开注释/字符串里的假括号），行号因涂白保行
     * 而与原文一致，可直接回喂 [leadingCommentBlock]。
     */
    internal data class InvokeLaterSite(
        /** 调用起始行号（1-based，与 [rawMainSources] 的原文行号对齐）。 */
        val line: Int,
        /** 括号实参区（含括号）；省略实参列表的尾随 lambda 形态为 null。 */
        val args: String?,
        /** 尾随 lambda 体（含花括号）；无尾随 lambda 为 null。 */
        val lambdaBody: String?,
    ) {
        /**
         * 写体扫描面 = 实参区 + 尾随 lambda 体。
         *
         * `invokeLater { … }` 的写体在**花括号**里、
         * `invokeLater({ … }, ModalityState.any())` 的写体在**实参区**里——只看任一侧都会漏
         * （T-FS-06 缺口一即后者曾被默认模态规则漏掉：该规则原先要求 `invokeLater` 后紧跟 `{`，
         * 而此形态紧跟的是 `(`）。
         */
        val body: String = (args ?: "") + (lambdaBody ?: "")
    }

    /**
     * 扫出全部 `invokeLater` 调用站点，覆盖四种写法：
     *  ① `invokeLater({ … }, ModalityState.any())`——写体在实参区；
     *  ② `invokeLater({ … }, ModalityState.defaultModalityState())`——实参区含显式默认模态
     *     （T-FS-06 缺口一：旧默认模态规则要求 `invokeLater` 后紧跟 `{`，此形态整片漏网）；
     *  ③ `invokeLater { … }`——省略实参的尾随 lambda（隐式 `defaultModalityState()`）；
     *  ④ 链式前缀 `ApplicationManager.getApplication().invokeLater(…)`——`invokeLater(` 是子串匹配，
     *     前缀写法不影响；负向后顾 `(?<![A-Za-z0-9_])` 保证不误吃 `invokeLaterWriteSafe` /
     *     `invokeLaterOnWriteThread`（正确形态）。
     *
     * 已知边界（全部是**漏报**方向，不误报；T-FS-06 独立复核逐条实测订正）：`invokeLater` 与 `(` 之间插空白、
     * 1 参 `invokeLater(runnable)`（写体在别处的 Runnable 定义里，本扫描面不可判定）、
     * **`invokeLater({ … })`（括号内 lambda 且完全不写模态实参）**——默认模态规则按「省略实参列表
     * 或实参区含 `defaultModalityState()`」判定，此形态两者都不满足故漏报（仓内 0 处）。收编需把判据
     * 改成「实参区无 any()/nonModal() 模态令牌即视为默认模态」，会连带改变对自定义模态的判定
     * （`stateForComponent` 等），本轮只登记边界不扩判据。
     * ~~`)` 与 `{` 之间夹注释~~ **不是边界**：注释已被 [maskNonCode] 涂白、[trailingLambdaBody] 跳空白
     * 后仍能命中（复核实测 `app.invokeLater(ModalityState.nonModal()) /* c */ { … }` 命中）——旧表述过保守。
     */
    internal fun invokeLaterSites(code: String): List<InvokeLaterSite> {
        val out = ArrayList<InvokeLaterSite>()
        // 形态 ①②④：带实参列表；其后可跟尾随 lambda
        var idx = code.indexOf("invokeLater(")
        while (idx >= 0) {
            val open = idx + "invokeLater".length
            val close = matchingDelimiter(code, open, '(', ')')
            if (close >= 0) {
                out.add(
                    InvokeLaterSite(
                        line = code.take(idx).count { it == '\n' } + 1,
                        args = code.substring(open, close + 1),
                        lambdaBody = trailingLambdaBody(code, close + 1),
                    ),
                )
            }
            idx = code.indexOf("invokeLater(", idx + 1)
        }
        // 形态 ③：省略实参列表的尾随 lambda
        for (m in Regex("""(?<![A-Za-z0-9_])invokeLater\s*\{""").findAll(code)) {
            val open = code.indexOf('{', m.range.first)
            val close = matchingDelimiter(code, open, '{', '}')
            if (close >= 0) {
                out.add(
                    InvokeLaterSite(
                        line = code.take(open).count { it == '\n' } + 1,
                        args = null,
                        lambdaBody = code.substring(open, close + 1),
                    ),
                )
            }
        }
        return out
    }

    /** 闸门令牌：body 内出现即视为写体已过 [cn.song.mybatisplus.visualizer.execute.DocWriteContext] 闸门。 */
    internal val writeGateTokens = listOf("DocWriteContext.runWriteSafe", "DocWriteContext.invokeLaterWriteSafe")

    /**
     * 通用扫描：对满足 [isTarget] 的 `invokeLater` 站点判定「回调里做文档写」，
     * 返回 `List<"路径:行号 → 命中令牌">`。
     *
     * 豁免三选一（两条规则同口径）：
     *  ① 调用行上方连续注释块含 `write-safe:` 理由标注；
     *  ② 写体已过闸门（[writeGateTokens]）；
     *  ③ 无写入令牌（本族规则只拦写文档）。
     *
     * 令牌判定只看代码——body 取自 [maskNonCode] 后的文本，注释与字符串里的 `setText` 字样不算写。
     * 单独成函数（而非内联进 @Test）是为了让规则本身可自证：合成源码样本能直接喂进来，
     * 否则「规则看不见某形态」这种空绿只能靠人工改线上代码才验得出来。
     */
    internal fun invokeLaterWriteOffenders(
        sources: List<Pair<String, String>>,
        isTarget: (InvokeLaterSite) -> Boolean,
    ): List<String> {
        val offenders = ArrayList<String>()
        for ((path, raw) in sources) {
            for (site in invokeLaterSites(maskNonCode(raw))) {
                if (!isTarget(site)) continue
                if (leadingCommentBlock(raw, site.line).contains("write-safe:")) continue
                if (writeGateTokens.any { site.body.contains(it) }) continue
                val hit = writeTokens.filter { site.body.contains(it) }
                if (hit.isNotEmpty()) offenders.add("$path:${site.line} → ${hit.joinToString("/")}")
            }
        }
        return offenders
    }

    /** `ModalityState.any()`（恒 write-unsafe）规则的站点判定。 */
    internal fun anyModalityWriteOffenders(sources: List<Pair<String, String>>): List<String> =
        invokeLaterWriteOffenders(sources) { it.args?.contains("ModalityState.any()") == true }

    /**
     * `update` 包「派发平台卸载/装载禁用 any 模态」规则的命中判定（T-DynUpdateWriteSafe）。
     *
     * 与 [anyModalityWriteOffenders] 的分工：那条按**写令牌**拦「回调体里写文档」，本规则**不看写令牌**
     * ——写发生在被调用的平台卸载内部（`DynamicPlugins.unloadPlugin` → `inModalContext(sentinel
     * "Unloading of <plugin>")` → 平台自身 commit/落盘/dispose），词法上永远看不见（2026-09-22 真机
     * 日志实证：模态串 `[设置对话框, Unloading of SQL Visualizer]`）。故按「包 + 令牌」封口：该包是
     * 仓内唯一向平台动态卸载/装载交棒的地方，**任何形态**的 `ModalityState.any()` 都不该出现
     * （直接实参、赋给变量再传、决策函数返回——三种形态都实测过）。
     *
     * 判定跑在 [maskNonCode] 后的文本上（注释与字符串字面量里的字样不算命中，逐行保行故行号可直接报）；
     * 豁免沿用「该行向上连续注释块含 `write-safe:`」口径（本项 T-01 的只读跳板即此形态：只读当前模态，
     * 无模型改动）。
     */
    internal fun anyModalityInUpdatePackageOffenders(sources: List<Pair<String, String>>): List<String> {
        val offenders = ArrayList<String>()
        for ((path, raw) in sources) {
            if (!path.contains("/update/")) continue
            maskNonCode(raw).lines().forEachIndexed { index, line ->
                if (!line.contains("ModalityState.any()")) return@forEachIndexed
                if (leadingCommentBlock(raw, index + 1).contains("write-safe:")) return@forEachIndexed
                offenders.add("$path:${index + 1}")
            }
        }
        return offenders
    }


    /**
     * 默认模态规则——`defaultModalityState()` 语义的两种等价写法：
     *  ① 省略实参的尾随 lambda `invokeLater { … }`（[InvokeLaterSite.args] == null）；
     *  ② 显式实参 `invokeLater({ … }, ModalityState.defaultModalityState())`（T-FS-06 补口，此前漏网）。
     * `nonModal()`（恒 write-safe）与 `any()`（另由 [anyModalityWriteOffenders] 管）都不在此列。
     */
    internal fun defaultModalityWriteOffenders(sources: List<Pair<String, String>>): List<String> =
        invokeLaterWriteOffenders(sources) {
            it.args == null || it.args.contains("ModalityState.defaultModalityState()")
        }

    /**
     * 文档写入令牌：命中即认为该回调在做文档写（`any()` 规则与尾随 lambda 规则共用同一份名单）。
     *
     * 名单里既有平台 API（`setText(` / `commitDocument(` / `WriteAction.`），也有本仓库自己的
     * 写入收口（`writeVfTextViaDocument(` / `dispatchDocWriteOnEdt(` / `setResultText(` …）——
     * 后者是「一次调用背后就是一次文档写」的语义单位，只看平台 API 会漏掉整条自建写链。
     */
    internal val writeTokens = listOf(
        "setText(", "insertString(", "replaceString(", "runWriteCommandAction", "commitDocument(",
        "saveDocument(", "setBinaryContent", "writeVfTextViaDocument(", "docWriteOnce(",
        "dispatchDocWriteOnEdt(", "writeConsoleOutput(", "refreshConsoleModel(",
        "prepareAfterEditorResolved(", "attachConsole(", "prewarmExecTempFile(",
        "prewarmPreviewTempFile(", "syncExecVfContentIfNeeded(", "renderSql(", "showSql(",
        "recoverConsole(", "setResultText(", "createEditor(", "render(", "WriteAction.",
    )

    @Test
    fun `禁止在any模态回调里做文档写`() {
        // T-WriteSafeCtx（2026-09-17 真机堆栈：Write-unsafe context! current modality=NON_MODAL，
        // 栈底 SqlPreviewPanel.showSql:1711-1712）。平台契约（243 源码实证）：`ModalityState.nonModal()`
        // 恒 write-safe、`any()` 恒 write-unsafe（TransactionGuard.java:73-76 原文）——posted runnable
        // 不算用户活动（IdeEventQueue.kt:1054），`wrapLaterInvocation` 对非 write-safe 模态走 else 分支
        // （TransactionGuardImpl.java:201）不套 runWithWritingAllowed，于是写文档必触发
        // TransactionGuardImpl:141-145 的 LOG.error（生产 SEVERE + 「Plugin to blame」+ FUS 上报，
        // 单测 logger 直接抛）。
        //
        // 修法三选一：① 写入类回调改 ModalityState.nonModal()；② 幂等重算的入口经 DocWriteContext
        // （runWriteSafe / invokeLaterWriteSafe）自愈投递；③ 确属纯 UI/只读，在调用行上方加
        // `// write-safe: <理由>` 标注（守卫按「调用行向上连续的注释块」识别豁免）。
        //
        // T-FS-06 缺口一：站点判定改为「实参区含 any()」，写体扫描面 = 实参区 + **尾随 lambda 体**——
        // `invokeLater({ editor.document.setText(…) }, ModalityState.any())` 的写体在实参区。
        val offenders = anyModalityWriteOffenders(rawMainSources())
        assertTrue(
            "在 ModalityState.any()（恒 write-unsafe）排出的回调里做文档写，命中 ${offenders.size} 处：$offenders。" +
                "改 ModalityState.nonModal()、或经 DocWriteContext 投递、或对纯 UI 站点加 `// write-safe: <理由>` 标注",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `update包派发平台卸载装载禁用any模态`() {
        // T-DynUpdateWriteSafe（2026-09-22 真机：`Write-unsafe context!` current modality=
        // {[设置对话框, APPLICATION_MODAL], [Unloading of SQL Visualizer]}）。平台契约（262 源码实证）：
        // 模态的写安全资格是「进入那一刻 myWritingAllowed 的快照」，表以模态实例为键
        // （LaterInvocator.enterModal:204-207 → TransactionGuardImpl.enteredModality:199-201）；
        // 平台卸载会压入 ad-hoc 模态（DynamicPluginsLegacyImpl:509），any() 排出的是 posted runnable
        // （IdeEventQueue:1106-1122 只把输入/Item/Focus 事件算用户活动）→ 快照恒 false → 卸载窗口内
        // 平台自身的 commit/落盘/dispose 全部记 SEVERE。
        // 本规则不看写令牌（写发生在平台卸载内部，词法上永远看不见），故按「包 + 令牌」封口：
        // update 包任何形态的 any() 都不该出现（直接实参 / 变量中转 / 决策函数返回，三种形态实测都命中）。
        val offenders = anyModalityInUpdatePackageOffenders(rawMainSources())
        assertTrue(
            "update 包出现 ModalityState.any()（命中 ${offenders.size} 处：$offenders）。" +
                "平台动态卸载会在该模态下压入 Unloading of <plugin>，其写安全资格取派发时刻快照，" +
                "any() 下恒 false（卸载窗口内平台自身模型改动全部记 Write-unsafe context!）。" +
                "派发一律走 DynamicInstallSupport.dispatchModalityForUnload()；" +
                "确属只读跳板请在该行上方加 `// write-safe: <理由>` 标注",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `update包any模态守卫自证_合成样本必红`() {
        // T-DynUpdateWriteSafe：新规则的负向自证（对齐 T-TS-04 惯例）——违规必报 / 带标注放行 /
        // 他包不误伤，三态缺一即「规则看不见真实形态」的空绿。
        val violating = listOf(
            "src/main/kotlin/cn/x/update/Bad.kt" to """
                class Bad {
                    fun go() {
                        ApplicationManager.getApplication().invokeAndWait({ unloadPlugin() }, ModalityState.any())
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "update 包内 any() 派发（含 invokeAndWait 形态）必须被命中，实际" +
                " ${anyModalityInUpdatePackageOffenders(violating)}",
            1,
            anyModalityInUpdatePackageOffenders(violating).size,
        )

        val annotated = listOf(
            "src/main/kotlin/cn/x/update/Ok.kt" to """
                class Ok {
                    fun go() {
                        // write-safe: UI-only（只读跳板，无模型写）
                        ApplicationManager.getApplication().invokeAndWait({ readModality() }, ModalityState.any())
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "带 write-safe 标注的只读站点应放行（豁免不得变成一律放行）",
            0,
            anyModalityInUpdatePackageOffenders(annotated).size,
        )

        val otherPackage = listOf(
            "src/main/kotlin/cn/x/ui/Else.kt" to """
                class Else {
                    fun go() {
                        ApplicationManager.getApplication().invokeLater({ refreshUi() }, ModalityState.any())
                    }
                }
            """.trimIndent(),
        )
        assertEquals("规则只封 update 包，他包同形态不误伤", 0, anyModalityInUpdatePackageOffenders(otherPackage).size)
    }

    @Test
    fun `尾随lambda写文档守卫自证_合成样本必红`() {        // T-TS-04：新规则的**负向自证**。旧提取器只认 `invokeLater(`，而线上四处真实站点
        // （SqlInputEditor:142 / SqlPreviewPanel:2486 / SqlToJavaPanel:820,881）全是省略括号、
        // 隐式 defaultModalityState 的尾随 lambda 形态——规则看不见它们等于空绿。
        // 用合成源码钉住三态：违规必报 / 过闸放行 / 带理由标注放行（豁免不得变成「一律放行」）。
        val violating = listOf(
            "src/main/kotlin/x/Bad.kt" to """
                class Bad {
                    fun go() {
                        ApplicationManager.getApplication().invokeLater {
                            editor.document.setText("boom")
                        }
                    }
                }
            """.trimIndent(),
        )
        val found = defaultModalityWriteOffenders(violating)
        assertEquals(
            "尾随 lambda（隐式 defaultModalityState）里写文档必须被新规则命中，实际命中 ${found.size} 处：$found",
            1,
            found.size,
        )

        val gated = listOf(
            "src/main/kotlin/x/Gated.kt" to """
                class Gated {
                    fun go() {
                        app.invokeLater {
                            DocWriteContext.runWriteSafe(project, "reason") { editor.document.setText("ok") }
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "写体已过 DocWriteContext 闸门的站点不得误判（闸门感知豁免）：${defaultModalityWriteOffenders(gated)}",
            0,
            defaultModalityWriteOffenders(gated).size,
        )

        val annotated = listOf(
            "src/main/kotlin/x/Annotated.kt" to """
                class Annotated {
                    fun go() {
                        // write-safe: 用户输入触发的补全前置提交（PSI 同步，不改文档文本）
                        app.invokeLater {
                            PsiDocumentManager.getInstance(project).commitDocument(doc)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "带 `// write-safe: <理由>` 标注的站点应豁免：${defaultModalityWriteOffenders(annotated)}",
            0,
            defaultModalityWriteOffenders(annotated).size,
        )
    }

    @Test
    fun `禁止在默认模态尾随lambda里做文档写`() {
        // T-TS-04：`invokeLater { … }`（省略模态实参）取 `ModalityState.defaultModalityState()`，
        // 平台侧 `defaultModalityImpl()` 依次取 ProgressManager.currentProgressModality → 线程/协程
        // 上下文 modality → NON_MODAL（DataSourceSelector T-FixWriteSafe 已逐帧实证）：**常态**落
        // NON_MODAL 恒 write-safe，但**在模态进度/带 modality 上下文里**取到该模态——若该模态是
        // 「写不安全进入」的，排队的 runnable 就在该模态事件泵内以 write-unsafe 身份执行，改文档
        // 即 `Write-unsafe context!`。故写入类回调只有两条合规出路：写体过 DocWriteContext 闸门，
        // 或调用行上方带理由的 `// write-safe:` 标注（用户输入活动内创建的拍属后者）。
        //
        // T-FS-06 补口：显式 `invokeLater({ … }, ModalityState.defaultModalityState())` 与省略实参的
        // 尾随 lambda 是**同一语义**（同一份 defaultModalityImpl 取值链），此前只认后者。
        val offenders = defaultModalityWriteOffenders(rawMainSources())
        assertTrue(
            "在默认模态（defaultModalityState）排出的回调里做文档写，命中 ${offenders.size} 处：$offenders。" +
                "写体过 DocWriteContext.runWriteSafe / invokeLaterWriteSafe 闸门，或对纯 UI/用户输入触发的站点" +
                "加 `// write-safe: <理由>` 标注",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `TFS06自证_显式模态实参写法必报且配平不越界`() {
        // ---- 缺口一：显式默认模态 + 括号内 lambda 此前两条规则都不覆盖 ----
        // 旧默认模态规则要求 `invokeLater` 后紧跟 `{`、any() 规则只扫含 any() 的实参区，于是
        // `invokeLater({ … }, ModalityState.defaultModalityState())` 整片漏网（当前仓内 0 处，属预防性
        // 守卫——必须用合成样本把「新规则看得见它」钉住，否则将来落地即静默）。
        // 样本一律用**可编译**的真实写法（平台无 `(ModalityState, Runnable)` 重载，见类 KDoc）。
        val anyArgLambda = listOf(
            "src/main/kotlin/x/ChainedAny.kt" to """
                class ChainedAny {
                    fun go(doc: Document) {
                        ApplicationManager.getApplication().invokeLater({ doc.setText("boom") }, ModalityState.any())
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "any() 实参的写文档站点必须被 any() 规则命中（链式前缀 ApplicationManager.getApplication(). " +
                "一并覆盖）：${anyModalityWriteOffenders(anyArgLambda)}",
            1,
            anyModalityWriteOffenders(anyArgLambda).size,
        )
        assertEquals(
            "同一站点归 any() 规则，不得被默认模态规则重复计入：" +
                "${defaultModalityWriteOffenders(anyArgLambda)}",
            0,
            defaultModalityWriteOffenders(anyArgLambda).size,
        )

        val nonModalArg = listOf(
            "src/main/kotlin/x/NonModalArg.kt" to """
                class NonModalArg {
                    fun go(doc: Document) {
                        app.invokeLater({ doc.setText("ok") }, ModalityState.nonModal())
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "nonModal() 实参恒 write-safe，两条规则都不得报：" +
                "${anyModalityWriteOffenders(nonModalArg) + defaultModalityWriteOffenders(nonModalArg)}",
            0,
            anyModalityWriteOffenders(nonModalArg).size + defaultModalityWriteOffenders(nonModalArg).size,
        )

        // 显式 defaultModalityState 实参 = 与省略实参的尾随 lambda 同一语义（T-FS-06 顺带补口）
        val explicitDefault = listOf(
            "src/main/kotlin/x/ExplicitDefault.kt" to """
                class ExplicitDefault {
                    fun go(doc: Document) {
                        app.invokeLater({ doc.setText("boom") }, ModalityState.defaultModalityState())
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "显式 defaultModalityState 实参 + 尾随 lambda 必须被默认模态规则命中：" +
                "${defaultModalityWriteOffenders(explicitDefault)}",
            1,
            defaultModalityWriteOffenders(explicitDefault).size,
        )

        // ---- 缺口二：配平必须先剥注释/涂白字符串，否则过报（body 越界）或漏报（body 被截断）----
        val unbalancedOpenInComment = listOf(
            "src/main/kotlin/x/UnbalancedOpen.kt" to """
                class UnbalancedOpen {
                    fun go(doc: Document) {
                        app.invokeLater {
                            // 注释里的不成对左花括号 {
                            touchUi()
                        }
                        WriteAction.compute<Unit, Throwable> { doc.setText("outside") }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "lambda 体注释里不成对 `{` 不得让 body 越过 lambda 边界（否则 lambda 外 WriteAction 里的" +
                "合法写被过报——实测原状即红）：${defaultModalityWriteOffenders(unbalancedOpenInComment)}",
            0,
            defaultModalityWriteOffenders(unbalancedOpenInComment).size,
        )

        val unbalancedCloseInComment = listOf(
            "src/main/kotlin/x/UnbalancedClose.kt" to """
                class UnbalancedClose {
                    fun go(doc: Document) {
                        app.invokeLater {
                            // 注释里的不成对右花括号 }
                            doc.setText("boom")
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "注释里不成对 `}` 不得提前截断 body 而漏掉其后的真违规：" +
                "${defaultModalityWriteOffenders(unbalancedCloseInComment)}",
            1,
            defaultModalityWriteOffenders(unbalancedCloseInComment).size,
        )

        val braceInString = listOf(
            "src/main/kotlin/x/BraceInString.kt" to """
                class BraceInString {
                    fun go(doc: Document) {
                        app.invokeLater {
                            val s = "} 字符串里的花括号不算花括号"
                            doc.setText(s)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "字符串字面量里的 `}` 不得提前截断 body（同类错配源，一并涂白）：" +
                "${defaultModalityWriteOffenders(braceInString)}",
            1,
            defaultModalityWriteOffenders(braceInString).size,
        )

        // ---- 字符串模板 ${…} 内嵌字符串（T-FS-06 独立复核阻断项）：掩码器 in-string 状态不得翻转 ----
        // 反例原状：遇首个未转义 `"` 即结束外层字符串 → 正文被当代码、模板内代码被涂白 → 后续站点的
        // 实参区被正文里的 `)` 提前配平到 0（实测 execute/DataSourceSelector.kt:1009 实参区被截断到
        // 9414/13999 字符而不再含 ModalityState.any()、:2684 整条站点被丢弃）。
        //
        // 样本形态（`9f8607b` 复核实测校准）：**模板必须跨行、且其后正文里带一个 `)`** 才能区分修复前后。
        // 单行简单形态（如 `"a${b("c")}d"`）**不能**钉住——掩码输出虽然不同，但误判在同一行内自行
        // 重新同步、括号得失对称，两版判定结果一致（`9f8607b` 提交报文曾误称该形态「修复前必红」，
        // 经复核实测更正）。本形态 pre-fix 实测 any 规则漏报（`[]`）、post-fix 报 1 处。
        val templateNestedString = listOf(
            "src/main/kotlin/x/TemplateNested.kt" to """
                class TemplateNested {
                    fun go(doc: Document) {
                        app.invokeLater({
                            LOG.info("a=${'$'}{
                                f("x")
                            }) b)")
                            doc.setText("z")
                        }, ModalityState.any())
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "模板表达式内嵌字符串不得翻转掩码器状态（否则其后带 any() 实参的站点会脱离覆盖）：" +
                "${anyModalityWriteOffenders(templateNestedString)}",
            1,
            anyModalityWriteOffenders(templateNestedString).size,
        )
        assertEquals(
            "该站点归 any() 规则，不得被默认模态规则重复计入：" +
                "${defaultModalityWriteOffenders(templateNestedString)}",
            0,
            defaultModalityWriteOffenders(templateNestedString).size,
        )

        // ---- 工具函数自证：stripComments 不保行 / maskNonCode 保行（行号错位=豁免标注认错行）----
        val multilineBlockComment = "a\n/* x\ny */\nb\nc\n"
        assertEquals(
            "stripComments 把多行 block 注释整段删除（含其中的换行）→ 其后行号前移，不可用于回推行号",
            2,
            stripComments(multilineBlockComment).lines().indexOfFirst { it.trim() == "b" },
        )
        assertEquals(
            "maskNonCode 逐行保行（注释涂白、换行保留）——配平提取的行号才能回喂 leadingCommentBlock",
            3,
            maskNonCode(multilineBlockComment).lines().indexOfFirst { it.trim() == "b" },
        )
        assertEquals(
            "maskNonCode 不增删行（原文 5 行 + 末尾空串 = 6 项）",
            multilineBlockComment.lines().size,
            maskNonCode(multilineBlockComment).lines().size,
        )
    }

    // ------------------------------------------------------------------
    // fatal 透传（T-FatalPassthrough，2026-09-19）：catch (…: Throwable) 必须判定或登记豁免
    // ------------------------------------------------------------------

    /**
     * `catch (…: Throwable)` 块：调用行号（1-based，与原文对齐）+ 块体（取自 [maskNonCode] 后的
     * 文本——注释/字符串里的 `isFatal` 字样不算判定）。
     */
    internal data class ThrowableCatchSite(val line: Int, val body: String)

    /**
     * 扫出全部 `catch (…: Throwable)` 块，含**嵌套**块（内层块体独立成站点：外层判了 fatal
     * 不代表内层判了）。边界（均为漏报方向，不误报）：
     *  - `catch (Throwable t)`（Java 写法，只出现在注入 agent 的 **Java 模板字符串**里，
     *    `SourceInterceptorTemplates.kt:99`）——被 [maskNonCode] 涂白，不是 Kotlin 站点；
     *  - 多 catch 共体（Kotlin 无 multi-catch 语法）不存在；`catch (e: Exception)` 不是本规则对象。
     */
    internal fun throwableCatchSites(code: String): List<ThrowableCatchSite> {
        val out = ArrayList<ThrowableCatchSite>()
        for (m in Regex("""catch\s*\([^)]*:\s*Throwable\s*\)""").findAll(code)) {
            val open = code.indexOf('{', m.range.last)
            if (open < 0) continue
            val close = matchingDelimiter(code, open, '{', '}')
            out.add(
                ThrowableCatchSite(
                    line = code.take(m.range.first).count { it == '\n' } + 1,
                    body = if (close >= 0) code.substring(open, close + 1) else code.substring(open),
                ),
            )
        }
        return out
    }

    /** 豁免记录：[sites] = 提交时该文件的站点数（漂移即红——新增/删除捕获点必须重新登记），[reason] 必填。 */
    internal data class CatchExemption(val sites: Int, val reason: String)

    /** fatal 判定令牌（形态 A）：块体内出现即视为已判。 */
    internal val fatalCheckToken = "FatalErrors.isFatal("

    /**
     * 已落地 fatal 透传的文件（T-FP-02 子集）——**不得**出现在 [throwableCatchExemptions]，
     * 且每处 `catch (…: Throwable)` 都必须带判定。
     */
    internal val landedFatalPassthroughFiles = setOf(
        // T-M53-II（2026-10-10）：原 `execute/DataSourceSelector.kt` 的 13 处已落地站点随组合式重构
        // 分布到同包两个协作文件（`*Console.kt` 2 处 + `*Results.kt` 11 处，合计仍是 13）；
        // 本表按「站点真正所在文件」登记，站点全部带 fatal 判定由下面 `sites.all { … }` 逐个保鲜。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsole.kt",
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt",
        // D202（2026-10-31）：writeConsoleOutput 的 2 处 catch(Throwable)（均带 FatalErrors.isFatal 判定）
        // 随输出写入链搬入本文件——按站点真正所在文件登记，站点全部带判定由下面 sites.all{…} 逐个保鲜。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsoleOutput.kt",
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt",
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/RunConfigurationProcessListener.kt",
        // T-FP-02b（独立复核条件项）：原豁免理由「可选能力探测失败返回 null（预览主链降级）」把**预览
        // 主链**写成能力探测——属借豁免放走真该收口的站点。已收口（判 fatal + 非 fatal 补 WARN 留痕），
        // 故从豁免表移入本表。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewService.kt",
        // T-RecentExecMenu（20261007，本轮修复并行会话新增站点时的顺带收口）：`log/SqlToJavaPanel.kt`
        // 原登记 1 处（后台候选收集失败返回空），并行会话 T-MapResultVo v2 又加了一处同形 PSI 读降级
        // （重算调用类 MPJ 能力/方法集）→ 登记数与实际 2 处不符、守卫转红。按本工程方向**整段收口**
        // 而非改登记数：两处均补 `FatalErrors.isFatal(t) throw t`（非 fatal 仍降级，语义不变），
        // 文件随之从豁免表移入本表——此后任一站点退回无判定即红（自动保鲜，无需维护站点数）。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlToJavaPanel.kt",
    )

    /**
     * 未收口 `catch (…: Throwable)` 的**豁免登记**（T-FatalPassthrough T-FP-03 / 计划 §八 清单）。
     *
     * 为什么是「文件 + 站点数」而不是「文件:行」：行号在**别的高频改动文件**（`log/`、`actions/`、
     * `ui/`）里随任何编辑漂移，逐行白名单会让并行会话的合法改动把守卫打成红的（计划 §六 风险表
     * 「守卫误报」）。站点数同样能钉住回潮——豁免文件里新增一个无判定的 `catch (…: Throwable)`
     * 即红，须补判定或重新登记理由。行号清单见本段末（提交时实测，仅供人工核对）。
     *
     * 豁免口径：捕获面覆盖 fatal 但**失败语义就是降级**（可选能力探测 / 后台候选收集 /
     * 图形渲染回落 / fail-closed 安全闸门）——不是「懒得改」。
     *
     * T-FP-02b 补一条边界（独立复核条件项教训）：**主链降级不算「可选能力探测」**——
     * `ui/SqlPreviewService.kt` 的预览转换失败返回 null 曾被写成「能力探测失败返回 null（预览主链
     * 降级）」放行，实际是用户点击后的唯一主路径（失败只弹「未找到可转换的 Wrapper」提示、零日志），
     * 已收口。登记理由须如实描述失败面属于哪一类，借「探测」名义放走主链会红在复核。
     */
    internal val throwableCatchExemptions: Map<String, CatchExemption> = mapOf(
        // 12 处：平台可选能力探测 + 反射候选遍历（2026.2 ContentManagerListener 接口形态不匹配、
        // filter 接口逐个试、方法/组件查找）——失败即回落轮询兜底 / 返回 null / 视为存活。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogConsoleBridge.kt" to
            CatchExemption(12, "控制台桥的平台可选能力探测与反射候选遍历：失败即降级（轮询兜底/null/视为存活）"),
        // 7 处：后台收集候选（链头/注解/XML tag）失败返回空列表 → EDT 提示「未找到候选」。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.kt" to
            CatchExemption(7, "后台候选收集失败返回空列表，用户侧看到「未找到候选」提示（非长驻链路）"),
        // 4 处：JOIN 图渲染/后台富化失败回落「无图 / 未富化模型」。
        // T-M53-II（2026-10-10）：原 5 处中的 `bindAutoPopup` 补全站点搬进 SqlPreviewPanelEditorLifecycle.kt
        // （下面单列 1 处）；余 4 处（updateDiagramTab / showJoinDiagram / clearNativeDiagram 链）
        // 随「结果区/图表 Tab」整块搬进 SqlPreviewPanelResultView.kt——本表按站点真正所在文件登记。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelResultView.kt" to
            CatchExemption(4, "JOIN 图渲染/富化失败回落无图或未富化模型（已 warn 留痕 + 显式摘转圈）"),
        // 1 处：补全弹窗异常不影响输入（同 `ui/SqlInputEditor.kt` 族）——T-M53-II D2-5 随
        // `bindAutoPopup` 搬出（原属 SqlPreviewPanel.kt 的 5 处之一，站点数与语义均未变）。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelEditorLifecycle.kt" to
            CatchExemption(1, "补全弹窗异常不影响输入（失败下次输入重试）——同 SqlInputEditor 族"),
        // 3 处：原生 Database 插件图表可选能力探测。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/diagram/nativefw/db/NativeDatabaseDiagramSupport.kt" to
            CatchExemption(3, "原生 Database 图表可选能力探测，失败回落自建 A 路径（零劣化）"),
        // 3 处：原生 JOIN 图可选能力探测 + 关编辑器失败不阻断。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/diagram/nativefw/NativeJoinDiagramSupport.kt" to
            CatchExemption(3, "原生 JOIN 图可选能力探测，失败回落内置渲染器（零劣化）"),
        // 2 处：本地帮助 HTML 打开失败回落在线文档；版本号读取失败返回空串。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt" to
            CatchExemption(2, "帮助 HTML 打开失败回落在线文档；版本号读取失败返回空串"),
        // 1 处：OGNL 求值器对用户表达式收敛为 Err。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/xml/Ognl.kt" to
            CatchExemption(1, "OGNL 求值器须把用户表达式的任何失败收敛为 Err（同 McpSelectGuard fail-closed 族）"),
        // 1 处：DasModel 遍历失败 bail。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/highlight/DatabaseSchemaResolver.kt" to
            CatchExemption(1, "平台 DasModel 形态差异探测：遍历失败 bail + warn（解析降级）"),
        // 1 处：渲染失败回「无图」语义。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/diagram/DiagramPanel.kt" to
            CatchExemption(1, "渲染失败回「无图」语义（清画布 + currentModel，防每次缩放重复抛）"),
        // 1 处：补全异常不影响输入。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlInputEditor.kt" to
            CatchExemption(1, "补全弹窗异常不得影响输入（失败下次输入重试）"),
        // 1 处：SQL 解析失败回落 trimEnd + 分号。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/psource/JdbcTemplateSqlPreviewService.kt" to
            CatchExemption(1, "SQL 解析失败回落「trimEnd + 分号」纯文本形态"),
        // 1 处：安全闸门 fail-closed——调用点注释即依据。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSelectGuard.kt" to
            CatchExemption(1, "安全闸门 fail-closed：解析器对恶意构造输入可能抛 Error，必须接住并拒绝"),
        // 1 处：反射探测返回 false。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogManager.kt" to
            CatchExemption(1, "反射能力探测失败返回 false"),
        // 1 处：后台候选收集失败返回空。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/GenerateCrudSqlFromEntityAction.kt" to
            CatchExemption(1, "后台候选收集失败返回空列表（动作提示无候选）"),
        // 1 处：同上。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/GenerateCrudSqlFromDbTableAction.kt" to
            CatchExemption(1, "后台候选收集失败返回空列表（动作提示无候选）"),
        // 1 处：同上。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertSqlToJavaAction.kt" to
            CatchExemption(1, "后台候选收集失败返回空列表（动作提示无候选）"),
    )

    /**
     * fatal 透传违规点：`路径:行号`（无判定且未豁免）。单独成函数以便合成样本自证
     * （规则「看不见某形态」的空绿只能靠合成样本钉住，见 `TFP03自证_…`）。
     */
    /**
     * 未收口点的**行号清单**（T-FatalPassthrough 实施时实测；守卫不校验行号，仅作人工核对与
     * 计划 §八 登记的出处）。共 **42 处 / 17 文件**（T-FP-02b 起 `ui/SqlPreviewService.kt:2100`
     * 已收口移入已落地表；20261007 `log/SqlToJavaPanel.kt` 两站点收口后同此；T-M53-II D2-5 起
     * `ui/SqlPreviewPanel.kt` 的 5 处拆为 4 + `SqlPreviewPanelEditorLifecycle.kt` 1，站点总数不变）：
     *
     * ```
     * log/SqlLogConsoleBridge.kt                                     12 处：151 167 208 410 561 570 595 642 656 664 678 695
     * actions/ConvertToSqlAction.kt                                   7 处：119 339 383 431 485 554 593
     * ui/SqlPreviewPanel.kt                                           4 处：updateDiagramTab/showJoinDiagram/clearNativeDiagram 链
     * ui/SqlPreviewPanelEditorLifecycle.kt                            1 处：bindAutoPopup
     * ui/diagram/nativefw/db/NativeDatabaseDiagramSupport.kt          3 处：102 122 224
     * ui/diagram/nativefw/NativeJoinDiagramSupport.kt                 3 处：52 115 126
     * log/SqlLogPanel.kt                                              2 处：1139 1155
     * xml/Ognl.kt                                                     1 处：163
     * ui/highlight/DatabaseSchemaResolver.kt                          1 处：352
     * ui/diagram/DiagramPanel.kt                                      1 处：197
     * ui/SqlInputEditor.kt                                            1 处：160
     * psource/JdbcTemplateSqlPreviewService.kt                        1 处：105
     * mcp/McpSelectGuard.kt                                           1 处：69
     * log/SqlLogManager.kt                                            1 处：275
     * actions/GenerateCrudSqlFromEntityAction.kt                      1 处：61
     * actions/GenerateCrudSqlFromDbTableAction.kt                     1 处：50
     * actions/ConvertSqlToJavaAction.kt                               1 处：93
     * ```
     *
     * 已落地（**33 处 / 6 文件**）：`execute/DataSourceSelectorConsole.kt` 2 +
     * `execute/DataSourceSelectorResults.kt` 11（原同属 `execute/DataSourceSelector.kt` 的 13 处，
     * T-M53-II 组合式重构把它们分到两个协作文件——合计不变）、`log/agent/AgentManager.kt` 10、
     * `log/RunConfigurationProcessListener.kt` 7、`ui/SqlPreviewService.kt` 1（T-FP-02b 收口）、
     * `log/SqlToJavaPanel.kt` 2（20261007 收口；该文件此前**登记 1 处、实际 2 处**，故上表登记口径
     * 的 42 与已落地 33 之和为 75，比历史「合计 74」多 1——差额即那次漏登）——
     * 合计 75 处（`grep -rn "catch (.*: Throwable)"` 口径）。
     * 另有 `log/SourceInterceptorTemplates.kt:99` 的 `catch (Throwable t)` 位于**注入 agent 的 Java
     * 源码模板字符串**里，不是 Kotlin 站点（[maskNonCode] 涂白，`TFP03自证_…` 已钉住该边界）。
     *
     * **同族抵消面（runCatching 接住已透传的 fatal）**：本守卫只扫 `catch (…: Throwable)` 块，看不见
     * 「块内 `throw` 被同一函数的外层 `runCatching {}.onFailure/getOrElse` 接住」的抵消——T-FP-02b
     * （独立复核阻断 1/2）已把**写集内**的抵消点逐个放行：`execute/DataSourceSelectorResults.kt`
     * `writeConsoleOutput` 外层（包住同文件内两处捕获块）、同文件 `prewarmExecTempFile`
     * 缓存命中刷新、`prewarmExecTempFileInner` 写盘、`log/agent/AgentManager.kt`
     * `resolvePid` 外层，以及预热链唯一调用方 `ui/SqlPreviewService.kt` 的
     * `runCatching { prewarmExecTempFile(...) }`（`:2121`）。写集外仍存在的同类抵消面（如
     * `executeOnConsole` 的外层 `runCatching`，其内层抛点实际在 `executeOnPooledThread`/`invokeLater`
     * lambda 内、运行时不经该 runCatching）见计划 §九。
     */
    internal fun fatalPassthroughOffenders(sources: List<Pair<String, String>>): List<String> {
        val offenders = ArrayList<String>()
        for ((path, raw) in sources) {
            if (throwableCatchExemptions.containsKey(path)) continue
            for (site in throwableCatchSites(maskNonCode(raw))) {
                if (!site.body.contains(fatalCheckToken)) offenders.add("$path:${site.line}")
            }
        }
        return offenders
    }

    @Test
    fun `catch_Throwable块必须判fatal或登记豁免`() {
        // T-FatalPassthrough T-FP-03：JVM 级致命错误（VirtualMachineError / LinkageError /
        // ThreadDeath）被 catch (…: Throwable) 吞成一条 warn 后，崩溃现场只剩一行日志、流程带病
        // 继续（第八轮报告 §7.2 Z-21）。本规则要求每个 `catch (…: Throwable)` 块**要么**判 fatal
        // （形态 A：块内 `FatalErrors.isFatal(`，见 util/FatalErrors.kt），**要么**登记豁免理由。
        // 未收口的 42 处（16 文件）豁免见 [throwableCatchExemptions]；已落地的 33 处（5 文件）见
        // [landedFatalPassthroughFiles]，其中任何一处退回无判定即红。
        val sources = rawMainSources()
        val offenders = fatalPassthroughOffenders(sources)
        assertTrue(
            "catch (…: Throwable) 块未判 fatal 且未登记豁免，命中 ${offenders.size} 处：$offenders。" +
                "块内加 `if (FatalErrors.isFatal(t)) throw t`（反射链先解包 InvocationTargetException），" +
                "确属「失败即降级」语义的在 [throwableCatchExemptions] 登记文件 + 站点数 + 理由",
            offenders.isEmpty(),
        )

        // 已落地文件：不得被豁免掩盖，且确实有站点、有判定（防「加白名单把落地缺失藏掉」）
        for (path in landedFatalPassthroughFiles) {
            val code = sources.firstOrNull { it.first == path }?.second
            assertTrue("已落地文件不在扫描面内（路径写错？）：$path", code != null)
            assertTrue("已落地文件不得出现在豁免表中：$path", !throwableCatchExemptions.containsKey(path))
            val sites = throwableCatchSites(maskNonCode(code!!))
            assertTrue("已落地文件应含 catch (…: Throwable) 站点：$path", sites.isNotEmpty())
            assertTrue(
                "已落地文件的站点应全部带 fatal 判定：$path",
                sites.all { it.body.contains(fatalCheckToken) },
            )
        }

        // 反射解包锚点（原生执行链）：Method.invoke 把目标异常（含 Error）包进
        // InvocationTargetException（普通异常），只判外层会漏判——须对解包后的 cause 判定。
        // T-M53-II：`executeOnConsole`（含该反射捕获块）已随组合式重构搬到 *Console.kt；
        // 读取面须指向站点真正所在处，否则断言退化为扫空文件（`isNotEmpty` 红）。
        val nativeRunChain = maskNonCode(
            sources.first { it.first.endsWith("execute/DataSourceSelectorConsole.kt") }.second,
        )
        val unwrapSites = throwableCatchSites(nativeRunChain).filter { it.body.contains("InvocationTargetException") }
        assertTrue("原生执行链的反射捕获块应存在（含 InvocationTargetException 解包）", unwrapSites.isNotEmpty())
        assertTrue(
            "反射捕获块须对解包后的 cause 判 fatal（`FatalErrors.isFatal(cause)`）——" +
                "只判外层 InvocationTargetException 会漏判目标方法抛出的 fatal",
            unwrapSites.all { it.body.contains("FatalErrors.isFatal(cause)") },
        )

        // 豁免表保鲜：路径必须在扫描面内、站点数与登记一致、理由非空
        for ((path, exemption) in throwableCatchExemptions) {
            val code = sources.firstOrNull { it.first == path }?.second
            assertTrue("豁免表条目指向不存在的源文件（改名/删除后未同步）：$path", code != null)
            val actual = throwableCatchSites(maskNonCode(code!!)).size
            assertEquals(
                "豁免文件的 catch (…: Throwable) 站点数与登记不符（新增/删除捕获点须补判定或重新登记理由）：$path",
                exemption.sites,
                actual,
            )
            assertTrue("豁免理由不得为空：$path", exemption.reason.isNotBlank())
        }
    }

    @Test
    fun `TFP03自证_无判定必红_有判定与豁免放行`() {
        // 负向自证（规则「看不见某形态」= 空绿，只能靠合成样本钉住）：三态 + 边界。
        val violating = listOf(
            "src/main/kotlin/x/NoCheck.kt" to """
                class NoCheck {
                    fun go() {
                        try {
                            risky()
                        } catch (t: Throwable) {
                            LOG.warn("swallowed", t)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "无判定的 catch (…: Throwable) 必须命中：${fatalPassthroughOffenders(violating)}",
            1,
            fatalPassthroughOffenders(violating).size,
        )

        val checked = listOf(
            "src/main/kotlin/x/Checked.kt" to """
                class Checked {
                    fun go() {
                        try {
                            risky()
                        } catch (t: Throwable) {
                            if (FatalErrors.isFatal(t)) throw t
                            LOG.warn("degraded", t)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "已判 fatal 的站点不得误报：${fatalPassthroughOffenders(checked)}",
            0,
            fatalPassthroughOffenders(checked).size,
        )

        // 嵌套块：外层判了不代表内层判了（内层独立成站点）
        val nestedInnerUnchecked = listOf(
            "src/main/kotlin/x/Nested.kt" to """
                class Nested {
                    fun go() {
                        try {
                            risky()
                        } catch (t: Throwable) {
                            if (FatalErrors.isFatal(t)) throw t
                            try {
                                alsoRisky()
                            } catch (e: Throwable) {
                                LOG.warn("inner swallowed", e)
                            }
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "嵌套 catch 的内层块须独立判定（外层判定不得替内层豁免）：${fatalPassthroughOffenders(nestedInnerUnchecked)}",
            1,
            fatalPassthroughOffenders(nestedInnerUnchecked).size,
        )

        // 注释里的判定字样不算判定（令牌只看代码）；注释里的花括号不得截断块体
        val tokenOnlyInComment = listOf(
            "src/main/kotlin/x/CommentOnly.kt" to """
                class CommentOnly {
                    fun go() {
                        try {
                            risky()
                        } catch (t: Throwable) {
                            // FatalErrors.isFatal(t) 只是注释，不算判定 } {
                            LOG.warn("swallowed", t)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "注释里的 isFatal 字样不算判定，且注释里的花括号不得截断块体：${fatalPassthroughOffenders(tokenOnlyInComment)}",
            1,
            fatalPassthroughOffenders(tokenOnlyInComment).size,
        )

        // `catch (…: Exception)` 不是本规则对象（捕获面不含 Error）
        val exceptionOnly = listOf(
            "src/main/kotlin/x/ExceptionOnly.kt" to """
                class ExceptionOnly {
                    fun go() {
                        try {
                            risky()
                        } catch (e: Exception) {
                            LOG.warn("degraded", e)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "catch (…: Exception) 捕获面不含 Error，不属本规则：${fatalPassthroughOffenders(exceptionOnly)}",
            0,
            fatalPassthroughOffenders(exceptionOnly).size,
        )

        // Java 模板字符串里的 `catch (Throwable t)`（注入 agent 的源码模板）被涂白，不是 Kotlin 站点
        val javaTemplateInString = listOf(
            "src/main/kotlin/x/JavaTemplate.kt" to """
                object JavaTemplate {
                    val SOURCE = ""${'"'}
                        try { run(); } catch (Throwable t) { log(t); }
                    ""${'"'}
                }
            """.trimIndent(),
        )
        assertEquals(
            "字符串里的 Java catch 写法不得被当站点：${fatalPassthroughOffenders(javaTemplateInString)}",
            0,
            fatalPassthroughOffenders(javaTemplateInString).size,
        )
    }

    // ------------------------------------------------------------------
    // PSI 跨线程捕获（T-PsiCaptureProtocol T-PCP-04，2026-09-19）
    // ------------------------------------------------------------------

    /** 池化线程站点：`executeOnPooledThread` 的**括号实参区 + 尾随 lambda 体**（提取口径同 [invokeLaterSites]）。 */
    internal data class PooledThreadSite(val line: Int, val args: String?, val lambdaBody: String?) {
        /** 扫描面 = 实参区 + 尾随 lambda 体（写体/读体可能在任一侧，只看一侧会漏）。 */
        val body: String = (args ?: "") + (lambdaBody ?: "")
    }

    /** 扫出全部 `executeOnPooledThread` 调用站点；提取跑在 [maskNonCode] 后（配平须避开注释/字符串里的假括号）。 */
    internal fun pooledThreadSites(code: String): List<PooledThreadSite> {
        val out = ArrayList<PooledThreadSite>()
        var idx = code.indexOf("executeOnPooledThread")
        while (idx >= 0) {
            var i = idx + "executeOnPooledThread".length
            while (i < code.length && code[i].isWhitespace()) i++
            var args: String? = null
            if (i < code.length && code[i] == '(') {
                val close = matchingDelimiter(code, i, '(', ')')
                if (close >= 0) {
                    args = code.substring(i, close + 1)
                    i = close + 1
                }
            }
            out.add(
                PooledThreadSite(
                    line = code.take(idx).count { it == '\n' } + 1,
                    args = args,
                    lambdaBody = trailingLambdaBody(code, i),
                ),
            )
            idx = code.indexOf("executeOnPooledThread", idx + 1)
        }
        return out
    }

    /**
     * 跨线程 PSI 读令牌：池化块内出现其一，即认为该块在**后台读捕获来的 PSI 元素**
     * （元素 → 所属文件 / 文档 / 区间）。
     */
    internal val psiReadTokens = listOf("containingFile", "viewProvider.document", ".textRange", ".textLength")

    /**
     * 两段式校验令牌：池化块内出现其一即视为已走 T-PCP-01 快照校验。
     *
     *  - `PsiSnapshot`——T-PCP-01 工具本体（`PsiSnapshot.capture` / `PsiSnapshot.Validity.Valid` 判定）；
     *  - `validatedRecentAnchor(` / `validatedMethodRows(`——两个 gutter 站点的**校验收口助手**
     *    （`ui/gutter` 包内两个 provider 文件，内部先 `validate()` 再读；新增收口助手须登记进本清单，
     *    或直接在池化块内用 `PsiSnapshot.Validity` 判定）。
     */
    internal val psiValidationTokens = listOf("PsiSnapshot", "validatedRecentAnchor(", "validatedMethodRows(")

    /** 豁免记录：[sites] = 提交时该文件的**命中站点数**（漂移即红），[reason] 必填。 */
    internal data class PsiCaptureExemption(val sites: Int, val reason: String)

    /**
     * 池化线程内读 PSI 的**豁免登记**（T-PCP-04 落地时实测，仅一处）。
     *
     * 口径：本规则只认「元素级捕获 + 后台读」这一形态（T-PCP-01 的适用面）。豁免条目是
     * **文件级捕获 + 块内重取 PSI**——`PsiFile` 捕获后于块内 `findChildrenOfType` 重新取标签再读行号，
     * 失效面由 `ReadAction` + 平台 reparse 语义承担，不在本轮锚点（计划 §一甲类只列两个 gutter provider）。
     * 将来收编进 `PsiSnapshot` 时须删条目并重新登记站点数。
     */
    internal val psiCaptureExemptions: Map<String, PsiCaptureExemption> = mapOf(
        // 4 处：XML 选区候选 / 全文件语句候选（T-M29.6、T-Perf-14）——池化块内从捕获的 PsiFile 重取 XmlTag 再算行号。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.kt" to
            PsiCaptureExemption(4, "文件级捕获 + 块内重取 PSI（XmlTag 候选/行号），非元素级快照；计划未含本文件"),
    )

    /**
     * 池化线程内读 PSI 未走校验的违规点：`路径:行号 → 命中令牌`。
     *
     * 豁免三选一：① 池化块内出现 [psiValidationTokens]（已走两段式校验）；② 调用行上方连续注释块含
     * `psi-capture: <理由>` 标注；③ 文件在 [psiCaptureExemptions] 内。
     *
     * **边界（如实登记，两个方向都有）**：
     *  - **漏报**：PSI 读被挪进池化块**调用的另一个函数**（块内看不见 `containingFile`）即脱离扫描面——
     *    本规则只看池化块本体，与既有的 `invokeLater` 两条规则同口径；`validate()` 自身也可能被
     *    封装进别的助手（此时须把助手登记进 [psiValidationTokens]）。
     *  - **过报**：块内对**块内新解析**的对象读 `textRange`（非捕获元素）也会命中——当前仓内 0 例，
     *    遇到时应把该读挪出块外或加 `psi-capture:` 标注说明其非捕获元素。
     *  - 覆盖的执行原语只有 `executeOnPooledThread`（仓内唯一在用的池化原语，42 处）；
     *    `runAsync`/`supplyAsync`/`ReadAction.nonBlocking` 不在扫描面（仓内 0 处，见计划 §一「非问题」）。
     */
    internal fun psiCaptureOffenders(
        sources: List<Pair<String, String>>,
        skipExemptions: Boolean = true,
    ): List<String> {
        val offenders = ArrayList<String>()
        for ((path, raw) in sources) {
            if (skipExemptions && psiCaptureExemptions.containsKey(path)) continue
            for (site in pooledThreadSites(maskNonCode(raw))) {
                val read = psiReadTokens.filter { site.body.contains(it) }
                if (read.isEmpty()) continue
                if (psiValidationTokens.any { site.body.contains(it) }) continue
                if (leadingCommentBlock(raw, site.line).contains("psi-capture:")) continue
                offenders.add("$path:${site.line} → ${read.joinToString("/")}")
            }
        }
        return offenders
    }

    @Test
    fun `池化线程内读PSI须走两段式快照校验或登记豁免`() {
        // T-PsiCaptureProtocol T-PCP-04：EDT 捕获 PsiElement → `executeOnPooledThread` 内读该元素
        // （containingFile / viewProvider.document / textRange）这条链，捕获与读取之间的写操作会让元素
        // 失效——只包 ReadAction 只解决「读权限」，不解决「元素是否仍有效」。本规则要求这类读取
        // 走 T-PCP-01 的 PsiSnapshot 校验（或 `psi-capture:` 标注 / 豁免登记）。
        // 落地形态：两个 gutter provider 各抽一个校验收口助手（`validatedRecentAnchor` /
        // `validatedMethodRows`，池化块只调它），`actions/ConvertToSqlAction.kt` 4 处登记豁免。
        val sources = rawMainSources()
        val offenders = psiCaptureOffenders(sources)
        assertTrue(
            "池化线程内读捕获的 PSI 却无快照校验，命中 ${offenders.size} 处：$offenders。" +
                "改走 PsiSnapshot.capture（EDT 侧）+ validate()（后台侧），或对确属「文件级捕获 + 块内重取」" +
                "的站点在 [psiCaptureExemptions] 登记文件 + 站点数 + 理由，或加 `// psi-capture: <理由>` 标注",
            offenders.isEmpty(),
        )

        // 豁免表保鲜：路径在扫描面内、命中站点数与登记一致、理由非空
        for ((path, exemption) in psiCaptureExemptions) {
            val source = sources.firstOrNull { it.first == path }
            assertTrue("豁免表条目指向不存在的源文件（改名/删除后未同步）：$path", source != null)
            val actual = psiCaptureOffenders(listOf(source!!), skipExemptions = false).size
            assertEquals(
                "豁免文件的命中站点数与登记不符（新增/删除跨线程 PSI 读须补校验或重新登记理由）：$path",
                exemption.sites,
                actual,
            )
            assertTrue("豁免理由不得为空：$path", exemption.reason.isNotBlank())
        }
    }

    @Test
    fun `TPCP04自证_池化块读PSI无校验必红_三种放行形态`() {
        // 负向自证（规则「看不见某形态」= 空绿，只能靠合成样本钉住）：违规必报 + 校验/标注/豁免三态放行。
        val violating = listOf(
            "src/main/kotlin/x/BgRead.kt" to """
                class BgRead {
                    fun go(elt: PsiElement) {
                        app.executeOnPooledThread {
                            ReadAction.compute<Int, Throwable> { elt.containingFile.textRange.startOffset }
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "池化块内读捕获元素的 PSI 属性必须命中：${psiCaptureOffenders(violating)}",
            1,
            psiCaptureOffenders(violating).size,
        )

        val validated = listOf(
            "src/main/kotlin/x/Validated.kt" to """
                class Validated {
                    fun go(elt: PsiElement, project: Project, snapshot: PsiSnapshot?) {
                        app.executeOnPooledThread {
                            val anchor = ReadAction.compute<Pair<String, Int>?, Throwable> { validatedRecentAnchor(project.basePath, snapshot) }
                            log(anchor.toString() + elt.containingFile.name)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "已走校验收口助手的站点不得误报：${psiCaptureOffenders(validated)}",
            0,
            psiCaptureOffenders(validated).size,
        )

        val inlineSnapshotCheck = listOf(
            "src/main/kotlin/x/InlineCheck.kt" to """
                class InlineCheck {
                    fun go(elt: PsiElement, snapshot: PsiSnapshot?) {
                        app.executeOnPooledThread {
                            val valid = snapshot != null && snapshot.validate() is PsiSnapshot.Validity.Valid
                            if (valid) elt.containingFile.textRange.startOffset else 0
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "块内直接用 PsiSnapshot 判定的站点不得误报：${psiCaptureOffenders(inlineSnapshotCheck)}",
            0,
            psiCaptureOffenders(inlineSnapshotCheck).size,
        )

        val annotated = listOf(
            "src/main/kotlin/x/Annotated.kt" to """
                class Annotated {
                    fun go(elt: PsiElement) {
                        // psi-capture: 该元素在块内重新解析（非 EDT 捕获），失效面由平台 reparse 承担
                        app.executeOnPooledThread {
                            elt.containingFile.textRange.startOffset
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "带 `// psi-capture: <理由>` 标注的站点应豁免：${psiCaptureOffenders(annotated)}",
            0,
            psiCaptureOffenders(annotated).size,
        )

        // 豁免表生效（用真实豁免路径喂合成样本，证明表被真正查过而不是白名单空转）
        val exemptedPath = "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.kt"
        val exempted = listOf(
            exemptedPath to """
                class Exempted {
                    fun go(tag: XmlTag) {
                        app.executeOnPooledThread {
                            tag.containingFile.viewProvider.document?.getLineNumber(tag.textRange.startOffset)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "豁免路径的命中站点须被计数（否则豁免表空转、漂移检测失效）：" +
                "${psiCaptureOffenders(exempted, skipExemptions = false)}",
            1,
            psiCaptureOffenders(exempted, skipExemptions = false).size,
        )
        assertEquals(
            "豁免表内的路径不得报违规：${psiCaptureOffenders(exempted)}",
            0,
            psiCaptureOffenders(exempted).size,
        )

        // 池化块内不读 PSI（纯数据/纯 IO）不得命中
        val noPsiRead = listOf(
            "src/main/kotlin/x/NoPsi.kt" to """
                class NoPsi {
                    fun go(path: String) {
                        app.executeOnPooledThread {
                            val text = java.io.File(path).readText()
                            log(text)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "池化块内无 PSI 读不得命中：${psiCaptureOffenders(noPsiRead)}",
            0,
            psiCaptureOffenders(noPsiRead).size,
        )

        // 工具函数自证：池化站点提取覆盖「尾随 lambda」与「括号实参 + 尾随 lambda」两种写法
        val sites = pooledThreadSites(maskNonCode(violating[0].second))
        assertEquals("executeOnPooledThread 尾随 lambda 形态应被提取", 1, sites.size)
        assertEquals("提取行号须与原文对齐（保行掩码）", 3, sites[0].line)
    }

    // ------------------------------------------------------------------
    // 跳转族读权限约定（T-PsiCaptureProtocol T-PCP-03，2026-09-19）
    // ------------------------------------------------------------------

    /** 跳转族调用令牌：读平台模型（ModuleManager / LocalFileSystem），调用方须持读权限。 */
    internal val jumpFamilyCallTokens = listOf(
        "SourcePathResolver.resolveVirtualFile(",
        "EntryJumpResolver.resolve(",
        "EntryJumpResolver.resolveCandidates(",
    )

    /**
     * 跳转族调用点的**读权限白名单**（EDT 直读；新增调用点须显式包 `ReadAction`，否则守卫红）。
     *
     * 本族读 `ModuleManager`/`LocalFileSystem` 平台模型（**不碰 PSI/Document**），故不适用 T-PCP-01
     * 的元素快照——要的是「调用方持读权限」这条约定：EDT 直读合法，后台调用必须自包 `ReadAction`
     * （`log/CallerMethodRangeResolver.kt` 即后者先例，文件内含 `ReadAction` 故自动通过）。
     */
    internal val jumpFamilyReadAllowedFiles = setOf(
        // 族本体：内部转发，读权限由调用方保证（现网两个调用方均在 EDT）
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/EntryJumpResolver.kt",
        // EDT 右键跳转（平台菜单回调在 EDT）
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt",
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/HistorySqlLogPanel.kt",
        // T-RecentExecMenu O-119：跳转原语共享件——三个调用方（实时页右键、历史页右键、弹框右键菜单）
        // 全是平台菜单/按钮回调（EDT）；本文件只做转接与导航，不自开线程。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogEntryJumpOps.kt",
    )

    /** 跳转族调用点缺读权限的违规文件（无 `ReadAction` 且不在白名单）。 */
    internal fun edtReadContractOffenders(sources: List<Pair<String, String>>): List<String> =
        sources
            .filter { (_, code) -> jumpFamilyCallTokens.any { code.contains(it) } }
            .filter { (_, code) -> !code.contains("ReadAction") }
            .map { (path, _) -> path }
            .filter { it !in jumpFamilyReadAllowedFiles }

    @Test
    fun `跳转族调用点须持读权限_后台调用须包ReadAction`() {
        // T-PCP-03：`EntryJumpResolver`/`SourcePathResolver` 一族的 KDoc 约定（「调用方保证持有读权限」）
        // 原先无工具保障——一旦被后台线程裸调，读 ModuleManager 的违约只在真机偶发。本规则把约定固化为
        // 「文件内出现 ReadAction（自包权限）或在 EDT 调用者白名单内」；配套运行期硬断言见下一段。
        val offenders = edtReadContractOffenders(mainSources())
        assertTrue(
            "跳转族调用点缺读权限（文件内无 ReadAction 且不在 EDT 白名单），命中 ${offenders.size} 处：$offenders。" +
                "后台调用须显式包 ReadAction，EDT 调用点加入 [jumpFamilyReadAllowedFiles] 并注明依据",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `TEDTRC自证_跳转族调用点无ReadAction必红_白名单与自包权限放行`() {
        // 独立复核㉗：`edtReadContractOffenders` 此前**只有主用例直接引用、无合成样本**（被元守卫登记为
        // 「受机器保护的豁免」——缺口因此不可见）。此处关闭该缺口：三态各归其位。
        val violating = listOf(
            "src/main/kotlin/cn/x/BadJump.kt" to """
                class BadJump {
                    fun go() {
                        SourcePathResolver.resolveVirtualFile(project, module)
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "文件内无 ReadAction 的跳转族调用点必须被命中：${edtReadContractOffenders(violating)}",
            listOf("src/main/kotlin/cn/x/BadJump.kt"),
            edtReadContractOffenders(violating),
        )

        val wrapped = listOf(
            "src/main/kotlin/cn/x/WrappedJump.kt" to """
                class WrappedJump {
                    fun go() {
                        ReadAction.compute<Unit, Throwable> {
                            EntryJumpResolver.resolve(project, element)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertTrue(
            "自包 ReadAction 的调用点应放行：${edtReadContractOffenders(wrapped)}",
            edtReadContractOffenders(wrapped).isEmpty(),
        )

        val whitelisted = listOf(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt" to """
                class SqlLogPanel {
                    fun go() {
                        EntryJumpResolver.resolveCandidates(project, element)
                    }
                }
            """.trimIndent(),
        )
        assertTrue(
            "EDT 白名单文件应放行（白名单不得失效，否则主用例会因过报而红）：${edtReadContractOffenders(whitelisted)}",
            edtReadContractOffenders(whitelisted).isEmpty(),
        )
    }
}
