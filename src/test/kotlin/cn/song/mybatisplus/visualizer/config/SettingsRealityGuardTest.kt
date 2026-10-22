package cn.song.mybatisplus.visualizer.config

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-SettingsTruth P5 设置真实性守卫（源码扫描，纯逻辑零平台依赖，进 testFast 快测桶）。
 *
 * 四断言对应四类「假配置」形态（docs/开发计划/20260918-0809-T-SettingsTruth §P5）：
 *  - 断言一「写而不读」：[PluginSettings.State] 每个 var 字段在 src/main 非 config 包存在
 *    **词边界**消费（覆盖 Java getXxx 访问器；SettingsIO 属 config 包天然排除其纯导入导出
 *    读写）。P1 删除的 autoInjectSqlSourceInterceptor 假开关由此防回潮。
 *  - 断言二「摆而不绑」：[PluginSettingsPanel] 每个可编辑控件字段必须在 apply() 与 reset()
 *    函数体中各出现（isModified 系列不强制——历史上有意豁免的展示件多）；按钮/纯展示 label
 *    进白名单豁免。防「面板摆了控件却不进读写链」的摆设件回潮。
 *  - 断言三「声明而不消费」：[ProjectPluginSettings] 每个 `fun effectiveXxx` 与项目级总开关
 *    `isEnabled` 在 src/main 非 config 包存在**带接收者限定的调用形态**（`ps.effectiveXxx(` /
 *    `projectSettings?.effectiveXxx(` / `it.effectiveXxx(`；裸调用与函数定义处不算）。P4 接线前
 *    7 个 effective* 零调用的半接线状态由此防回潮。
 *  - 断言四「幽灵键」：bundle 中 `settings.*` / `configurable.*` / `log.list.displayMode*`
 *    键必须被 src/main 引用或列白名单。防 P3「搜索索引收录不存在设置」同类幽灵文案回潮。
 *
 * 与既有守卫的分工：
 *  - [SettingsTruthTest]：P1/P2 的反射与文本断言（autoInject 字段不存在、tooltip 双语、
 *    statusSummary 三态、Topic 接线）——本文件不重复；
 *  - BundleParityGuardTest（i18n 包）：中英 key 集合全等 + 占位符形态 + 引用键必存在——
 *    本文件断言四是反方向（bundle 键必须被引用），互补不重叠。
 *
 * T-Fix-SettingsScope 收紧（2026-09-18）：断言一/二/三原口径为「整文件文本 / 切片子串命中，
 * 注释与更长标识符都算消费」，能过但堵不住三类真实缺口——
 *  - 同名遮蔽：[SqlPreviewService] 的私有 helper 与 [ProjectPluginSettings] 的 effective* 有 7 个
 *    同名（`private fun effectiveTablePrefix(): String`）；删掉真实调用 `ps.effectiveTablePrefix()`
 *    后 helper 定义仍在 → 断言三照旧绿；
 *  - 子串后门：`tablePrefix` ⊂ `removeTablePrefix`，字段真死也能被另一个标识符兜住；
 *  - 扫描面遗漏：断言三只扫 `fun effective\w+\(`，项目级总开关 `isEnabled()`（唯一读点）不在面内。
 * 现口径 = 先剥离注释（KDoc/注释提及不算消费）再按词边界（断言一）或调用形态（断言三）判定，
 * 断言三另从判定面剔除同名函数定义处。收紧后实测仍全绿（缺口 ① 之外的存量接线均真实）。
 *
 * T-TS-06 二次收紧（2026-09-18）：上一版断言三仍对 4 个符号（`effectiveReplaceParams` /
 * `effectiveInjectLogicDelete` / `effectiveLogicDeleteValue` / `isEnabled`）可构造假绿——
 * 判定只要求「标识符后随 `(`」，于是**裸调用同名私有 helper** 也算消费：删掉 helper 体内的
 * `projectSettings?.effectiveXxx()`（对项目级设置的真实消费）后，helper 的调用点
 * `effectiveReplaceParams()` 仍在 → 照旧绿（`isEnabled` 同理：`McpToolGate.isEnabled(toolName)`
 * 与 `ps.isEnabled()` 同形，且判定面未限定文件）。现口径 = 调用须**带接收者限定符**（`.`/`?.`）
 * 且落在**剥注释后直接引用 [ProjectPluginSettings] 的文件**内（实例来源必在该文件出现；
 * 不逐个推断接收者类型——lambda 参数 `it.effectiveTablePrefix()` 无法静态定型，故取双条件近似）。
 * 收紧后实测又查获一处真死面：`ProjectPluginSettings.effectiveDialectAuto()` 零真实调用
 * （SqlPreviewService 的同名私有 helper 直读 `state.dialectAuto` 绕开了它）→ 已改走项目级方法。
 *
 * k2j 移植注（生产为 Java）：扫描根 `src/main/java`（.java）；`var xxx` → `private <Type> xxx;`
 * 字段声明；`fun apply(): Boolean` / `fun reset()` → `public boolean apply()` / `public void reset()`；
 * `fun effectiveXxx(` → `public boolean effectiveXxx(`（调用形态 `ps.effectiveXxx(` 两侧一致；
 * Java 无扩展函数定义形态，定义剔除逻辑退化为防御）。四条断言语义不变。
 */
class SettingsRealityGuardTest {

    // ------------------------------------------------------------------
    // 资源装载（仿 BannedApiSourceScanTest；repoRoot/stripComments/快照统一放类尾 companion）
    // ------------------------------------------------------------------

    private fun mainFile(rel: String): File = repoRoot().resolve(rel)

    /** 同 [stripComments]，但注释内容替换为等量空白、换行原样保留（行数不变）。 */
    private fun stripCommentsKeepLines(source: String): String =
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
            .replace(source) { m -> m.value.map { c -> if (c == '\n' || c == '\r') c else ' ' }.joinToString("") }
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    /** src/main 全部 .kt/.java（含路径与文本，路径统一 / 分隔）。T-TestPerf6 起读快照。 */
    private fun mainSources(): List<Pair<String, String>> = mainSourcesSnapshot

    /** 非 config 包的 src/main 源码（断言一/三的消费判定面）。T-TestPerf6 起读快照（filter+剥注释只算一次）。 */
    private fun nonConfigSources(): List<Pair<String, String>> = nonConfigStrippedSnapshot

    // ------------------------------------------------------------------
    // 断言一（写而不读）：State 每个 var 字段在非 config 包有消费
    // ------------------------------------------------------------------

    @Test
    fun `State每个var字段在非config包存在消费`() {
        val settingsSrc = mainFile("src/main/java/cn/song/mybatisplus/visualizer/config/PluginSettings.java").readText()
        val stateStart = settingsSrc.indexOf("class State {")
        assertTrue("PluginSettings.java 应声明 State", stateStart >= 0)
        // State 类体结束于「\n    }」（后续静态工厂/导入导出方法均在 State 之外）
        val stateBody = settingsSrc.substring(stateStart, settingsSrc.indexOf("\n    }", stateStart))
        // Java 字段形态（Kotlin `var name: T = init` 的等价物）：`private boolean dialectAuto = true;`
        // / `private String dialect = DEFAULT_DIALECT;` / 无初值 `private JButton modeButton;`
        val fields = Regex("""\n\s+(?:private|public|protected)\s+(?:static\s+)?(?:final\s+)?[\w.<>\[\],?\s]*?\s(\w+)\s*[;=]""")
            .findAll(stateBody).map { it.groupValues[1] }.toList()
        assertTrue("State 应解析出字段（正则失效？）", fields.size >= 60)

        // 注释剥离后再判消费：KDoc/注释里提一句字段名不算消费（T-Fix-SettingsScope 收紧）
        // T-TestPerf6：nonConfigSources() 已是快照剥注释版，此处不再重复 strip
        val code = nonConfigSources()
        // 间接消费别名：字段本身不出现在非 config 包，但经 config 包同名/派生访问器转出后真实驱动行为
        // （logListSqlDisplayMode → sqlListDisplayMode()，消费点 SqlLogPanel.java / HistorySqlLogPanel.java）。
        // 显式登记而非泛化匹配——新增别名须留注释可审计。
        val alias = mapOf("logListSqlDisplayMode" to "sqlListDisplayMode")
        // T-EnhancedCaptureDefaultOn（2026-10-19）：两个字段只用于旧配置兼容归一——loadState 内
        // 写为固定值、运行期零读取，因此没有也不应有非 config 消费点。保留字段是为了旧 XML/JSON
        // 与旧版本回退，不是假配置；下线条件 = 确认不再需要兼容旧配置。显式登记而非泛化匹配。
        val compatOnly = setOf("enhancedCaptureConfirmed", "enhancedCaptureRejectedAtMs")
        val dead = fields.filter { it !in compatOnly }.filter { f ->
            val probe = alias[f] ?: f
            // 词边界判定：`tablePrefix` 不再被 `removeTablePrefix` 这类更长标识符兜住（子串口径后门）
            val identifier = Regex("""(?<![\w$])${Regex.escape(probe)}(?![\w$])""")
            // Java 访问器形态：Kotlin `var xxx` 的 Java 消费方只能走 getXxx()（本移植树布尔字段亦为
            // getXxx 而非 isXxx，与 Kotlin 生成的访问器命名一致；logStopOnToolWindowClose 的消费点
            // 在 ToolWindow 监听链）
            val javaAccessor = Regex(
                """(?<![\w$])get${Regex.escape(probe.replaceFirstChar { it.uppercaseChar() })}(?![\w$])""",
            )
            code.none { (_, t) -> identifier.containsMatchIn(t) || javaAccessor.containsMatchIn(t) }
        }
        assertTrue(
            "PluginSettings.State 存在「写而不读」的死字段（非 config 包剥注释后零词边界消费）：$dead。" +
                "T-SettingsTruth 定案：设置项必须真实驱动行为——删字段（如 P1 的 autoInjectSqlSourceInterceptor 纯动作开关）" +
                "或接线消费点，不得留假配置",
            dead.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 断言二（摆而不绑）：面板控件在 apply/reset 中各出现
    // ------------------------------------------------------------------

    companion object {
        /** 按钮/纯展示件白名单（无状态读写语义）：状态行/信息行 label、动作按钮、快捷键编辑器、更新分区（自带状态机）、纯动作注入开关。 */
        private val PANEL_WHITELIST = setOf(
            "clearCacheButton", "crudColorResetButton", "logAutoSaveBrowseButton",
            "enhancedStatusLabel", "statusLabel", "cacheInfoLabel", "logAutoSavePatternPreview",
            "updateSection", "shortcutEditor", "reverseShortcutEditor",
            // T-SettingsTruth P1：autoInjectInterceptorCheck 为纯动作开关（勾选即注入、无论成败终态复位），不进 apply/reset
            "autoInjectInterceptorCheck",
            // T-M19-StorageClear：三按钮点击即执行本地动作，状态行只显示结果；
            // PluginSettingsPanelUiTest 另验确认/清理，不属于 apply/reset 持久化字段。
            "openCaptureDirectoryButton", "clearPreviewHistoryButton", "clearParamHistoryButton",
            "storageActionStatusLabel",
            // R90：只读隐私披露标签，不是可编辑设置；保留开关自身仍须 apply/reset。
            "explainPlanRetentionDisclosure",
            // T-PrefixValidate（master 7b51d57，20260926）：前缀校验结果行与一键修复按钮——
            // label 纯展示（可见性随校验结果翻转）、button 纯动作（点击回写两栏后自隐），
            // 均无自身状态可存可回填，按本白名单既有口径豁免（20260927 T-TestPerf7 合流树首轮全量红补登记）
            "prefixValidationLabel", "prefixFixButton",
            // ShortcutEditor 内部控件（随 shortcutEditor 整体进出 apply/reset）
            "textField", "editButton",
        )

        private fun repoRoot(): File {
            var dir = File(System.getProperty("user.dir")).absoluteFile
            repeat(6) {
                // k2j 生产树为 src/main/java（.java），仓库根探测与 master 的 src/main/kotlin 不同
                if (File(dir, "src/main/java").isDirectory) return dir
                dir = dir.parentFile ?: return@repeat
            }
            error("未定位到仓库根（src/main/java 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
        }

        /**
         * 剥离 block 注释与行注释（自 BannedApiSourceScanTest 搬入复用）。
         *
         * 字符串字面量中的 `//` 会被连带截断——只可能产生漏报不产生误报，可接受。
         * 断言二依赖行结构（apply/reset 切片按 4 空格缩进定位函数边界），不能用本函数
         * （block 注释整段删除会合并行、错切边界），改用 [stripCommentsKeepLines]。
         */
        private fun stripComments(source: String): String =
            source
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .lineSequence()
                .joinToString("\n") { it.substringBefore("//") }

        // T-TestPerf6：src/main 源码快照——同一次测试 JVM 内文件内容恒定，四个断言共享一次
        // 遍历+读取（原口径每个 @Test 独立 walkTopDown+readText 全仓，单轮共付 3 遍全仓读）；
        // 「非 config 包 + 剥注释」版本同样只算一次。断言逻辑与判红条件零改动。
        private val mainSourcesSnapshot: List<Pair<String, String>> by lazy {
            repoRoot().resolve("src/main").walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .map { it.relativeTo(repoRoot()).path.replace('\\', '/') to it.readText() }
                .toList()
        }

        private val nonConfigStrippedSnapshot: List<Pair<String, String>> by lazy {
            mainSourcesSnapshot.filter { (path, _) -> !path.contains("/config/") }
                .map { (p, t) -> p to stripComments(t) }
        }
    }

    @Test
    fun `面板每个可编辑控件在apply与reset函数体中各出现`() {
        // T-M53-II D2：面板的分区构建（含控件装配）已逐字符搬出到 PluginSettingsPanelSections.kt，
        // 「摆而不绑」断言须在两个文件合起来的面板源码上跑，判据不变。
        // k2j 移植注：生产为 Java（PluginSettingsPanel.java + PluginSettingsPanelSections.java），
        // master 的两文件并读语义照搬；Sections.java 仅承载 buildSections 静态方法（控件字段
        // 仍留在 Panel.java），断言实测仍以 Panel.java 为主体、Sections.java 并读为防御。
        val panelSrc = mainFile("src/main/java/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.java").readText() +
            mainFile("src/main/java/cn/song/mybatisplus/visualizer/config/PluginSettingsPanelSections.java").readText()
        // 行尾归一：工作树检出受 core.autocrlf 影响为 CRLF，而 Java 正则 `.` 不匹配 `\r`、
        // 全匹配（matches）与 `l == "    }"` 会双双失配（20260918 合并到 master 后实证）。
        val lines = panelSrc.split('\n').map { it.trimEnd('\r') }

        // 控件字段（Java 形态）：`public final JBCheckBox replaceParamsCheck = new JBCheckBox(…);`
        // / `public final JComboBox<CrudColorStyleOption> crudColorStyleCombo = new ComboBox<>();`
        // 泛型在声明类型上（Kotlin 在初始化表达式上）——取声明类型的裸类名与 master 的控件类型集对齐。
        // T-M53-II D2（2026-10-11）：分区构建搬出到 PluginSettingsPanelSections.java 后，控件字段
        // 可见性由 private 放宽为 public（Kotlin internal → Java public），正则同时接受两种修饰符。
        val widgetTypes = setOf("JBCheckBox", "JBTextField", "JBTextArea", "JComboBox", "ComboBox", "ColorPanel", "JBLabel", "JButton")
        val fields = lines.mapNotNull { l ->
            // T-M53-II D2：控件字段的可见性在按族拆分时由 private 放宽（Kotlin internal →
            // Java public，装配代码搬为 PluginSettingsPanelSections 静态方法），判据不变，
            // 故正则同时接受两种修饰符（Java 字段形态：类型在声明位、泛型随声明类型）。
            val m = Regex("""\s*(?:private|public)\s+(?:final\s+)?(?:static\s+)?([\w.]+)(?:<[^>]*>)?\s+(\w+)\s*=""")
                .find(l) ?: return@mapNotNull null
            val base = m.groupValues[1].substringAfterLast('.')
            if (base in widgetTypes) m.groupValues[2] else null
        }
        assertTrue("面板应解析出控件字段（正则失效？）", fields.size >= 60)

        // 函数体切片：`public boolean apply() {` / `public void reset() {` 到下一个 4 空格缩进的
        // 顶层成员声明（Java 成员声明以修饰符/注解/KDoc 起行）或收尾括号
        // （边界行判定用 startsWith 前缀而非 containsMatchIn——后者会把 8 空格缩进的方法体内
        // `//` 注释行误判为 `/` 边界，把 apply 切成 3 行假切片，20260918 首跑实证）
        fun body(name: String): String {
            val start = lines.indexOfFirst { it.matches(Regex(""" {4}(?:public|private|protected) [\w<>\[\], ?]+ $name\(.*""")) }
            assertTrue("PluginSettingsPanel 应声明 $name(", start >= 0)
            val end = lines.drop(start + 1).indexOfFirst { l ->
                l == "    }" || (
                    l.startsWith("    ") && !l.startsWith("     ") && l.isNotBlank() && l.trimStart().let { t ->
                        t.startsWith("public ") || t.startsWith("private ") || t.startsWith("protected ") ||
                            t.startsWith("static ") || t.startsWith("class ") || t.startsWith("interface ") ||
                            t.startsWith("enum ") || t.startsWith("record ") || t.startsWith("@") || t.startsWith("/**")
                    }
                    )
            }
            assertTrue("$name( 的结束边界未找到", end >= 0)
            return lines.subList(start, start + 1 + end).joinToString("\n")
        }

        // 切片后剥注释：原口径「注释行也算绑定/回填」——KDoc 或行尾注释里提一句控件名即过守卫
        // （T-Fix-SettingsScope 收紧）。边界定位仍用未剥注释的 lines（见 stripCommentsKeepLines 注释）
        val applyBody = stripCommentsKeepLines(body("apply"))
        val resetBody = stripCommentsKeepLines(body("reset"))
        // 间接绑定别名：combo 不直现于 apply()，经 selectedXxx() helper 读值（helper 内引用控件，
        // helper 被 apply 调用）——登记而非泛化匹配，新增别名须留注释可审计。
        //  - crudColorStyleCombo → selectedCrudColorStyle()（apply / reset 直现）
        //  - logDisplayModeCombo → selectedListDisplayMode()（apply / reset 直现）
        val alias = mapOf(
            "crudColorStyleCombo" to listOf("selectedCrudColorStyle()", "crudColorStyleCombo"),
            "logDisplayModeCombo" to listOf("selectedListDisplayMode()", "logDisplayModeCombo"),
        )
        // T-TS-06：词边界判定（原为子串 `f in body`）——`logAutoSavePathField` 这类字段名是
        // `xxxLogAutoSavePathField` 的子串，控件真没进 apply/reset 也能被子串兜住（同断言一的后门）。
        fun boundIn(bodyText: String, f: String): Boolean {
            val probes = alias[f] ?: listOf(f)
            return probes.any { Regex("""(?<![\w$])${Regex.escape(it)}(?![\w$])""").containsMatchIn(bodyText) }
        }
        val editable = fields.filter { it !in PANEL_WHITELIST }
        val missingInApply = editable.filterNot { f -> boundIn(applyBody, f) }
        val missingInReset = editable.filterNot { f -> boundIn(resetBody, f) }
        assertTrue(
            "面板可编辑控件未在 apply() 函数体绑定（改动保存即静默丢弃）：$missingInApply",
            missingInApply.isEmpty(),
        )
        assertTrue(
            "面板可编辑控件未在 reset() 函数体回填（设置页打开不回显真实配置）：$missingInReset",
            missingInReset.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 断言三（声明而不消费）：effectiveXxx 与总开关 isEnabled 有 config 外调用
    // ------------------------------------------------------------------

    @Test
    fun `ProjectPluginSettings每个effective方法与总开关在非config包存在调用`() {
        val projSrc = mainFile("src/main/java/cn/song/mybatisplus/visualizer/config/ProjectPluginSettings.java").readText()
        // Java 声明形态（Kotlin `fun effectiveXxx(` 的等价物）：`public boolean effectiveDialectAuto() {`
        // ——要求行首修饰符，避免把 `return effectiveXxx(` 这类裸调用误当声明。
        val effective = Regex("""(?m)^\s*(?:(?:public|protected|private|static|final|synchronized)\s+)+[\w.<>\[\],?]+\s+(effective\w+)\s*\(""")
            .findAll(projSrc).map { it.groupValues[1] }.toList().distinct()
        assertTrue("ProjectPluginSettings 应声明 effective* 方法", effective.isNotEmpty())
        // 项目级总开关：`isEnabled()` 是全部 effective* 的唯一闸门，此前不在扫描面内
        // （关掉总开关即全项目级覆盖失效，漏接它与漏接任一 effective* 同责）
        val methods = effective + "isEnabled"

        // T-TS-06：判定面 = 非 config 包**且**剥注释后直接引用 ProjectPluginSettings 的文件。
        // 为什么加文件级条件：消费方唯一合法形态是「ProjectPluginSettings 实例.方法」，而实例来源
        // （`ProjectPluginSettings.getInstance(project)` / 类型标注）必然出现在该文件；同名函数
        // （McpToolsetSupport 的 `McpToolGate.isEnabled(toolName)`）与同名私有 helper
        // （SqlPreviewService 的 `effectiveReplaceParams()`）所在文件不引用本类，天然出局——
        // 这正是上一版把 `isEnabled` 判绿的路径。
        // 有意保留的局限：不逐个推断接收者类型（lambda 参数 `it.effectiveTablePrefix()` 无法静态
        // 定型，推断会制造漏报），故取「文件引用本类 + 调用带接收者」双条件近似；残留漏报面仅剩
        // 「同一文件内他类的同名调用」。
        val psReference = Regex("""(?<![\w$])ProjectPluginSettings(?![\w$])""")
        val code = nonConfigSources()
            .filter { (_, t) -> psReference.containsMatchIn(t) }
        val dead = methods.filter { m ->
            // 调用形态（T-TS-06 收紧）：标识符前须有接收者限定符（`.` / `?.`）——裸调用
            // `effectiveReplaceParams()` 消费的是**同名私有 helper**，不构成项目级设置方法的消费；
            // 原口径据此对 4 个符号可构造假绿：删掉 helper 体内的
            // `projectSettings?.effectiveXxx()` 后裸调用仍在 → 照旧绿。
            val call = Regex("""\.\s*${Regex.escape(m)}\s*\(""")
            // 定义形态剔除：Kotlin 扩展函数 `fun ProjectPluginSettings.effectiveXxx(` 自带接收者点号，
            // 会被上式误判为调用，故剔除同名函数定义跨度。Java 无扩展函数定义形态（`public boolean
            // effectiveXxx(` 不含点号），此剔除在 Java 侧退化为防御——保留判据结构不动。
            val definition = Regex(
                """(?m)^\s*(?:(?:public|protected|private|static|final|synchronized)\s+)+[\w.<>\[\],?]+\s+${Regex.escape(m)}\s*\(""",
            )
            code.none { (_, t) ->
                val defSpans = definition.findAll(t).map { it.range }.toList()
                call.findAll(t).any { c -> defSpans.none { d -> c.range.first in d } }
            }
        }
        assertTrue(
            "ProjectPluginSettings 存在零限定调用的 effective*/isEnabled（声明而不消费的半接线死面）：$dead。" +
                "T-SettingsTruth P4 定案：项目级覆盖项必须真实进入读取链（SqlPreviewService/EntityResolver），" +
                "否则删除方法走瘦身方案，不得留假接线；T-TS-06 口径：消费须写成带接收者的调用" +
                "（`ps.effectiveXxx(` / `projectSettings?.effectiveXxx(` / `it.effectiveXxx(`）" +
                "且所在文件直接引用 ProjectPluginSettings",
            dead.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 断言四（幽灵键）：bundle settings.*/configurable.* 键必须被 src/main 引用
    // ------------------------------------------------------------------

    @Test
    fun `bundle设置类键均被srcMain引用或列白名单`() {
        val bundle = mainFile("src/main/resources/messages/MpSqlVisualizerBundle.properties").readLines()
        val keys = bundle.mapNotNull { l ->
            val k = l.substringBefore('=').trim()
            if (k.startsWith("settings.") || k.startsWith("configurable.") || k.startsWith("log.list.displayMode")) k else null
        }
        assertTrue("bundle 应解析出设置类键（前缀正则失效？）", keys.size >= 200)

        // 动态拼 key 白名单（人工维护，证据行号随 P5 核实）：
        //  - settings.update.checkFailed / installFailed：UpdateSection.kt:246
        //    「"settings.update.${if (pendingManifest == null) "checkFailed" else "installFailed"}"」动态拼，
        //    StartupUpdateCheck.kt:159 另有 installFailed 直引；两键保留白名单。
        //  - settings.update.envNotSupported / settings.search.placeholder：P5 守卫首跑查获的存量幽灵键，
        //    已随 T-SettingsTruth 收尾删除双语两键（对齐 P3 filterBar 先例），不再豁免。
        //  - settings.mcp.tool.desc.<tail> 十一键（T-McpToolset2 十键 + R14 T-HistorySearch 增
        //    historysearch 键）：McpConfigurable.kt:117
        //    「"settings.mcp.tool.desc." + toolName.removePrefix("sqlv_").replace("_", "")」动态拼，
        //    尾段由工具名派生（sqlv_render_sql → rendersql），静态扫描不可判定；十一键保留白名单。
        val whitelist = setOf(
            "settings.update.checkFailed",
            "settings.update.installFailed",
            "settings.mcp.tool.desc.rendersql",
            "settings.mcp.tool.desc.schemacontext",
            "settings.mcp.tool.desc.explain",
            "settings.mcp.tool.desc.renderlogs",
            "settings.mcp.tool.desc.checksql",
            "settings.mcp.tool.desc.rollbackplan",
            "settings.mcp.tool.desc.perfadvice",
            "settings.mcp.tool.desc.crudsql",
            "settings.mcp.tool.desc.reversesql",
            "settings.mcp.tool.desc.mappercheck",
            "settings.mcp.tool.desc.historysearch",
            // T-SyncK2JMaster496：下列键对应「尚未移植的 UI 面」，落位后须从本白名单移除
            // （UPDATE 失败分型文案 / MCP 脱敏开关与能力横幅 / T-M19-StorageClear 三按钮 / R90 保留开关文案）：
            "settings.update.fail.network", "settings.update.fail.channel404",
            "settings.update.fallback.marketplace", "settings.update.fallback.gitee",
            "settings.mcp.maskLiterals", "settings.mcp.maskLiterals.desc",
            "settings.mcp.banner.declared", "settings.mcp.banner.platformPresent",
            "settings.mcp.banner.platformUnavailable",
            "settings.storage.captureFiles", "settings.storage.captureFiles.open",
            "settings.storage.captureFiles.open.tip", "settings.storage.captureFiles.opened",
            "settings.storage.captureFiles.open.failed",
            "settings.storage.previewHistory", "settings.storage.previewHistory.clear",
            "settings.storage.previewHistory.clear.tip", "settings.storage.previewHistory.clear.confirm",
            "settings.storage.previewHistory.cleared",
            "settings.storage.paramHistory", "settings.storage.paramHistory.clear",
            "settings.storage.paramHistory.clear.tip", "settings.storage.paramHistory.clear.confirm",
            "settings.storage.paramHistory.cleared",
            "settings.storage.clear.title", "settings.storage.clear.failed", "settings.storage.clearBoundary",
            "settings.explain.retention", "settings.explain.retention.disclosure",
        )
        val texts = mainSources()
        val xmlText = mainFile("src/main/resources/META-INF/plugin.xml").readText()
        val ghosts = keys.filter { k -> k !in whitelist && texts.none { (_, t) -> k in t } && k !in xmlText }
        assertTrue(
            "bundle 存在幽灵键（src/main 零引用的设置文案——搜索索引/页面摆着不存在或无人消费的配置）：$ghosts。" +
                "确属动态拼 key 则加白本守卫并注明证据行号；确属死文案则删双语两键（对齐 P3 filterBar 先例）",
            ghosts.isEmpty(),
        )
    }
}
