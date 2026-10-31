package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫自证覆盖率元守卫（T-GuardSelfTestCoverage T-GSC-01，O-50 / 第十轮报告 §7.2 Z-30）。
 *
 * ## 要解决的问题
 *
 * 我方守卫**已有**自证能力（合成源码样本喂进判定函数、`BannedApiSourceScanTest.kt:610` 的设计意图原文），
 * 缺的是**约束力**：新加一条守卫规则而不写自证语料，没有任何测试会红——仓内已三次实证的失败形态
 * （守卫绿灯下失效、空扫描面空绿）正是从这条缝里长出来的。本元守卫把「判定函数必须配自证语料」
 * 从人工仪式变成结构性断言。
 *
 * ## 判据
 *
 * 1. **覆盖率**：`GUARD_CLASSES` 登记的每个判定函数，必须在本类源码的**自证语料**函数体内被引用
 *    （词边界匹配，且**先剥注释**）；否则红并点名，或在登记项登记豁免——豁免必须带机器可校验的
 *    `via` 指称物（见判据 6），**散文理由单独不再生效**。
 * 2. **登记表不得腐烂**：登记的函数名必须真实存在于该类源码（函数改名/删除后登记表会静默空转）。
 * 3. **豁免不得腐烂**：`exempt` 说明非空、`via` 指称物齐备，且该函数**仍未**获得自证引用（一旦补上
 *    自证语料即红，提示删除豁免条目——同 `EnumReachabilityGuardTest` 的 `WHITELIST` 反腐烂口径）。
 *    这条同时是「缺口不得固化成受保护的豁免」的执行机制：把缺口补上自证语料，豁免条目自己就会红。
 * 4. **扫描面不得整体脱离**：① 磁盘上文件名含 `Guard` 或以 `SourceScanTest` 结尾的测试类必须登记；
 *    ② 任何测试文件里出现类级（4 空格缩进）判定形状函数（`*Offenders` / `*Sites` / `*Violations` /
 *    `*Hits` / `*Functions`）而其文件未登记 → 红；③ 已登记类里出现未逐条登记的判定形状函数 → 红。
 * 5. **元守卫自身灵敏度自证**：合成源码喂进同一判定实现（见末个用例）。
 *
 * ## 独立复核㉗ 的两条补强（2026-09-22）
 *
 * 复核㉗ 指出两条结构性问题，本轮按「机器可校验」口径补上：
 *
 * 6. **豁免必须机器可校验**：`exempt` 不再是豁免依据、只是给人看的说明——每条豁免必须写 `via`
 *    （本函数被哪些**同类函数**引用），逐条校验：① 被点名的函数真实存在；② 其函数体（**剥注释后**）
 *    真的引用本函数——即**转发调用边**。复核点名的「5 条豁免的理由是散文（转发调用边无机器校验，
 *    重构后豁免仍静默有效）」正是缺这一条；③ 站点/违规内核类（SITES/VIOLATIONS）豁免的 `via` **链**
 *    必须走得到**已登记未豁免**的函数（该函数自身已被自证语料直接引用），防「豁免背书豁免」的空心链。
 *    HELPER（断言助手/夹具）只校验调用点存在——它本来就不主张自证覆盖。
 * 7. **登记粒度跨类一致**：已登记守卫类里的**类级 Boolean 返回**函数（形态判定/谓词）必须逐条登记。
 *    此前 `PluginXmlStructureGuardTest` 以空 `decisionFns` 登记，而它同样有判定函数
 *    （`actionBlockHasKeyboardShortcut` / `actionBlockExists`）与同性质助手（`tagsOf` 站点提取器、
 *    `assertHolds` 断言助手）——`BannedApiSourceScanTest` 里同性质的 `invokeLaterSites`/`assertNoHits`
 *    是逐条登记的，两个类口径不一致。
 *
 * 复核㉗ 的另一半「3 处真实覆盖缺口固化成受机器保护的豁免」（`edtReadContractOffenders` /
 * `checkRequiredEnv` / `checkHealthcheck`）**不以豁免收口，而是补合成样本把缺口关掉**（三条用例：
 * `TEDTRC自证_…` / `漂移自证_缺必填环境变量被抓` / `漂移自证_缺healthcheck被抓`），豁免条目随之删除——
 * 按判据 3，缺口一旦补上自证语料，豁免条目会自己变红要求删除。
 *
 * ## 自证语料的识别判据
 *
 * 函数是**测试函数**（紧邻上方有 `@Test`，或函数名以 `test` 开头），且满足其一：
 *  - 函数名含 `自证` / `自检` / `selftest`（大小写不敏感）；或
 *  - 声明行上方**紧邻的注解/注释块**含上述关键词（允许中间夹空行与 `//` 分隔线，遇首个代码行即停）。
 *
 * ## 漏报面（已知，勿当「已覆盖」）
 *
 * 1. **命名兜底面认不全判定函数**：本仓既有判定函数命名发散——`DialogWrapperInitOrderGuardTest:178`
 *    与 `EnumReachabilityGuardTest:206` 都叫 `scan`、`DmlAliasGuardTest:42` 叫 `dmlTargetsHaveNoAlias`、
 *    `DockerComposeLabConsistencyTest:147/:159` 是 `internal fun checkXxx`。故**登记表是主识别面**，
 *    命名形状只在第 4 条里当「新函数兜底提示」用。
 * 2. **文件名闭包只认 `Guard` / `SourceScanTest`**：非此命名且不含判定形状函数的守卫类不会被发现
 *    （实测两例已手工登记：`convention/BundlePlaceholderArityTest.kt`、`sql/verify/DockerComposeLabConsistencyTest.kt`）。
 * 3. **只认类级成员（4 空格缩进）**：嵌套类/局部函数里的同名后缀方法不在扫描面
 *    （实测 `ui/SqlPreviewPanelPrewarmWriteSafeTest.kt:86` 的 `writeUnsafeHits` 在嵌套 `RecordingProcessor` 内）。
 * 4. **无判定内核的守卫无约束力**：本仓守卫类里只有 5 个暴露可注入合成样本的判定内核，其余
 *    断言直接内联在 `@Test` 里（登记项 `decisionFns` 为空并写明依据）——这类守卫的规则漂移仍只能靠人工。
 * 5. **自证语料只在同文件内匹配**：跨文件引用的自证不被识别。
 * 6. **函数体界定按大括号收尾**：表达式体函数（`fun x() = …`）会延伸到下一个同类缩进 `}`，只可能放宽
 *    不会收紧；本仓自证语料全为块体。
 * 7. **`via` 只钉「调用边存在 + 链不自背书」**：不主张转发链一路传递到自证语料的**整条**路径——那会
 *    要求把全部夹具/助手也登记成 `via` 链节点（本仓 20 余个守卫类的夹具近百个），登记表会退化成一
 *    份源码目录。传递性由「`via` 至少一项是已登记未豁免项」+「未豁免项必须被自证语料直接引用」两级
 *    合起来保证：链的上游要么被语料直接打到，要么本身还要再举证。
 * 8. **Boolean 粒度规则只认同行的返回类型**：多行参数表（返回类型落到下一行）的类级函数不被本规则
 *    兜住——这是兜底面，登记表仍是主识别面。
 *
 * ## 过报面（已知，已按 kind 分列登记，勿误判为缺陷）
 *
 * 1. **站点提取器与断言助手会命中命名形状**：`invokeLaterSites`（返回 `Site` 集合，不是违规点）、
 *    `assertNoHits`（断言助手）都被 `*Sites` / `*Hits` 兜底形状抓进来——已用 `FnKind.SITES` /
 *    `FnKind.HELPER` 与 `FnKind.VIOLATIONS` 分列登记，语义不混。
 * 2. **判定函数自身 KDoc 里的「自证」字样不算语料**：`BannedApiSourceScanTest.kt:610` 的 KDoc 明写
 *    「让规则本身可自证」，若按「附近有自证字样」宽泛匹配，会把判定函数自己当语料 → 故自证识别
 *    只认**测试函数**（有 `@Test` 或 `test` 前缀），非测试函数一律不看。
 * 3. **任何测试文件里的 `*Offenders` 类函数都会要求登记**：这是刻意的三振出局式提示，不是精度缺陷；
 *    确非判定内核时登记为 `HELPER` 并写依据即可。
 *
 * 本守卫是**测试基建**，不改产品代码、不改既有守卫逻辑；零平台依赖（源码无平台包引用，
 * 故进 `testFast` 快桶——该桶按「源码文本是否出现平台包名」划桶，故本 KDoc 也不得写出那个包名）。
 */
class GuardSelfTestCoverageMetaGuardTest {

    // ------------------------------------------------------------------
    // 仓库定位与源码读取（与同包既有守卫同形态）
    // ------------------------------------------------------------------

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    private fun testRoot(): File = repoRoot().resolve("src/test/kotlin")

    /** 登记表路径是 `cn/song/mybatisplus/visualizer/` 之后的包相对路径（如 `convention/XxxTest.kt`）。 */
    private fun sourceOf(entryPath: String): String = File(testRoot(), TEST_PKG + entryPath).readText()

    /** 全量测试源码（包相对路径 → 文本）；登记表与命名兜底面共用。 */
    private fun allTestSources(): List<Pair<String, String>> =
        testRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file ->
                val full = file.relativeTo(testRoot()).path.replace('\\', '/')
                val rel = if (full.startsWith(TEST_PKG)) full.removePrefix(TEST_PKG) else full
                rel to file.readText()
            }
            .toList()

    // ------------------------------------------------------------------
    // 判定实现（可注入源码：主用例与灵敏度自证共用同一实现）
    // ------------------------------------------------------------------

    /** 类级成员函数声明（4 空格缩进；`internal`/`override` 等修饰符与反引号函数名都收）。 */
    private fun fnDecls(source: String): List<FnDecl> {
        val lines = source.split('\n')
        val out = mutableListOf<FnDecl>()
        lines.forEachIndexed { idx, line ->
            val m = FUN_DECL.find(line) ?: return@forEachIndexed
            val name = m.groupValues[1].trim('`')
            out += FnDecl(name, idx + 1, isSelfTest(lines, idx, name), bodyOf(lines, idx), returnTypeOf(line))
        }
        return out
    }

    /** 自证语料：函数名 + 函数体文本（[fnDecls] 里 `selfTest = true` 的那些）。 */
    private fun selfTestFns(source: String): List<Pair<String, String>> =
        fnDecls(source).filter { it.selfTest }.map { it.name to it.body }

    /** 命名兜底面命中的类级函数：名 + 行号。 */
    private fun decisionShapedDecls(source: String): List<Pair<String, Int>> =
        fnDecls(source).filter { DECISION_SHAPE.containsMatchIn(it.name) }.map { it.name to it.line }

    /** 纯判定：源码里「无自证语料引用且未登记豁免」的判定函数名。 */
    private fun uncoveredNames(source: String, declared: List<DecisionFn>): List<String> {
        val selfTests = selfTestFns(source)
        return declared
            .filter { it.exempt == null }
            .filter { fn -> selfTests.none { (_, body) -> references(body, fn.name) } }
            .map { it.name }
    }

    /** 自证语料里对该函数名的引用（词边界，避免 `foo` 命中 `fooBar`）。 */
    private fun references(body: String, name: String): Boolean =
        Regex("\\b" + Regex.escape(name) + "\\b").containsMatchIn(body)

    /**
     * 是否自证语料：先必须是**测试函数**（紧邻上方有 `@Test`，或函数名以 `test` 开头），再看关键词。
     *
     * 只认测试函数是刻意的——判定函数自身 KDoc 常写「可自证」（`BannedApiSourceScanTest.kt:610`），
     * 不设这道门会把判定函数自己当语料，覆盖率检查整体空转。
     */
    private fun isSelfTest(lines: List<String>, declIdx: Int, name: String): Boolean {
        val above = annotationRun(lines, declIdx)
        val isTest = name.startsWith("test") || above.any { it.startsWith("@Test") }
        if (!isTest) return false
        if (SELF_TEST_MARK.containsMatchIn(name)) return true
        return above.any { SELF_TEST_MARK.containsMatchIn(it) }
    }

    /** 声明行上方紧邻的注解/注释块（遇首个代码行即停；空行与 `//` 分隔线允许夹在中间）。 */
    private fun annotationRun(lines: List<String>, declIdx: Int): List<String> {
        val out = mutableListOf<String>()
        var i = declIdx - 1
        while (i >= 0) {
            val s = lines[i].trim()
            if (s.startsWith("@") || s.startsWith("//") || s.startsWith("*") || s.startsWith("/*") || s.isEmpty()) {
                out += s
                i--
                continue
            }
            break
        }
        return out
    }

    /** 函数体：声明行到首个「缩进不深于声明行且以 `}` 开头」的行（含）；找不到则到文件尾。**注释已剥离**。 */
    private fun bodyOf(lines: List<String>, declIdx: Int): String {
        val declIndent = indentOf(lines[declIdx])
        var i = declIdx + 1
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("}") && indentOf(line) <= declIndent) {
                return stripComments(lines.subList(declIdx, i + 1).joinToString("\n"))
            }
            i++
        }
        return stripComments(lines.subList(declIdx, lines.size).joinToString("\n"))
    }

    /**
     * 声明行上的返回类型（取参数表**闭合括号之后**的 `: Type`；多行参数表返回 null，见类头漏报面第 8 条）。
     *
     * 用于判据 7 的「类级 Boolean 判定函数」兜底面——Kotlin 的形态谓词（`isXxx`/`xxxAt`/`xxxExists`）
     * 不命中任何命名形状，只能从返回类型上认。
     */
    private fun returnTypeOf(declLine: String): String? =
        RETURN_TYPE.find(declLine)?.groupValues?.get(1)?.trim()

    /**
     * 剥离行注释与块注释：**一切「引用」判据都跑在剥离后的文本上**。
     *
     * 复核㉗ 的教训——豁免/覆盖若在**原文**上匹配函数名，函数体里一句 KDoc/`//` 交叉引用（`[fooOffenders]`）
     * 就能把「缺自证」洗成「有自证」、把「转发边不存在」洗成「存在」。机器校验的指称物必须是**代码里的
     * 调用边**，不是散文里的名字。本仓现存登记项在剥离前后读数一致（2026-09-22 实测全表对照），
     * 故本改动不改变既有判定结果，只堵住新形态。
     */
    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\n]*"), "")

    private fun indentOf(line: String): Int = line.length - line.trimStart().length

    /** 文件名闭包：含 `Guard` 或以 `SourceScanTest` 结尾（指南守卫族全清单的机器可读口径）。 */
    // ===== 跨文件解析面（T-M53-II 登记模型扩展）=====
    // via 调用边**先本文件、再注册面全集**解析（判定内核与其调用用例允许分处两文件）；同名函数以本文件为准，
    // 避免各守卫共有的 scan/stripComments 张冠李戴；边仍须真实存在（改名/删除照旧报红）。
    // **语料刻意不跨文件解析**：实测同名函数会把 A 文件的用法算到 B 文件头上（误报），故「自证语料与判定内核
    // 同文件」是硬约束——这正是 BannedApiSourceScanTest 必须按规则族拆分的依据。
    private val allRegisteredDecls: List<FnDecl> by lazy {
        GUARD_CLASSES.flatMap { fnDecls(sourceOf(it.path)) }
    }

    private val allRegisteredByName: Map<String, FnDecl> by lazy {
        allRegisteredDecls.associateBy { it.name }
    }

    private fun isGuardFile(fileName: String): Boolean {
        val base = fileName.removeSuffix(".kt")
        return "Guard" in base || base.endsWith("SourceScanTest")
    }

    // ------------------------------------------------------------------
    // 覆盖率检查
    // ------------------------------------------------------------------

    @Test
    fun `每个守卫判定函数必须有自证语料或登记豁免`() {
        val missing = mutableListOf<String>()
        GUARD_CLASSES.forEach { entry ->
            val source = sourceOf(entry.path)
            uncoveredNames(source, entry.decisionFns).forEach { name ->
                val kind = entry.decisionFns.first { it.name == name }.kind
                missing += "${entry.path} :: $name（$kind）"
            }
        }
        assertTrue(
            "守卫判定函数缺自证语料（合成样本喂不进去 = 「规则看不见某形态」的空绿只能靠人工改线上代码才发现）：\n" +
                missing.joinToString("\n") { "  - $it" } +
                "\n修法二选一：① 补一个自证用例（测试函数名含 `自证`/`自检`/`selftest`，或紧邻注释标注）；" +
                "② 在 GUARD_CLASSES 对应项登记 exempt 理由（理由不得为空）。" +
                "约定原文见 docs/架构设计/03-开发与测试指南.md 守卫章节。",
            missing.isEmpty(),
        )
    }

    @Test
    fun `登记表不得腐烂_登记的函数必须真实存在于该类源码`() {
        val stale = GUARD_CLASSES.flatMap { entry ->
            val names = fnDecls(sourceOf(entry.path)).map { it.name }.toSet()
            entry.decisionFns.filter { it.name !in names }.map { "${entry.path} :: ${it.name}" }
        }
        assertTrue(
            "登记表条目在源码里已不存在（函数改名/删除后本元守卫对该项静默空转）：\n" +
                stale.joinToString("\n") { "  - $it" } + "\n修法：同步改名或删除该条目",
            stale.isEmpty(),
        )
    }

    @Test
    fun `豁免条目不得腐烂_理由非空且仍无自证语料`() {
        val problems = mutableListOf<String>()
        GUARD_CLASSES.forEach { entry ->
            if (entry.note.isBlank()) problems += "${entry.path} 登记说明为空（须写清该类为何无判定内核或为何登记）"
            val selfTests = selfTestFns(sourceOf(entry.path))
            entry.decisionFns.filter { it.exempt != null }.forEach { fn ->
                if (fn.exempt!!.isBlank()) problems += "${entry.path} :: ${fn.name} 豁免理由为空"
                val refs = selfTests.filter { (_, body) -> references(body, fn.name) }.map { it.first }
                if (refs.isNotEmpty()) {
                    problems += "${entry.path} :: ${fn.name} 已获自证语料（${refs.joinToString()}）——请删除该豁免条目"
                }
            }
        }
        assertTrue(
            "豁免表/登记表腐烂（豁免条目必须随语料补齐而删除，同 EnumReachabilityGuardTest 的 WHITELIST 口径）：\n" +
                problems.joinToString("\n") { "  - $it" },
            problems.isEmpty(),
        )
    }

    /**
     * 独立复核㉗ 的补强：**豁免必须带机器可校验的指称物**（判据 6）。
     *
     * `exempt` 只是给人看的说明，不是豁免依据——真正的依据是 `via`（本函数被哪些同类函数引用）。
     * 三条校验各自对应一种「重构后豁免仍静默有效」的形态：函数改名（via 指向不存在）、转发边被删
     * （函数体里不再引用本函数）、链自我背书（via 全指向豁免项 → 自证语料其实到不了本函数）。
     */
    @Test
    fun `豁免必须机器可校验_转发调用边与调用点真实存在`() {
        val problems = mutableListOf<String>()
        GUARD_CLASSES.forEach { entry ->
            val ownByName = fnDecls(sourceOf(entry.path)).associateBy { it.name }
            // T-M53-II：先本文件、再注册面全集（口径见 allRegisteredByName）。
            val byName = allRegisteredByName
            entry.decisionFns.filter { it.exempt != null }.forEach { fn ->
                if (fn.via.isEmpty()) {
                    problems += "${entry.path} :: ${fn.name} 豁免只有散文理由（无 via 调用边）——" +
                        "重构后该豁免仍会静默有效，机器看不见"
                    return@forEach
                }
                fn.via.forEach { caller ->
                    val target = ownByName[caller] ?: allRegisteredByName[caller]
                    if (target == null) {
                        problems += "${entry.path} :: ${fn.name} 的 via 指向本类不存在的函数 '$caller'（改名/删除后豁免静默空转）"
                    } else if (!references(target.body, fn.name)) {
                        problems += "${entry.path} :: ${fn.name} 的 via '$caller' 函数体里并不引用它——" +
                            "转发调用边不存在（机器校验的正是这条边）"
                    }
                }
                if (fn.kind != FnKind.HELPER && !reachesDirectCoverage(entry, fn, HashSet())) {
                    problems += "${entry.path} :: ${fn.name}（${fn.kind}）的 via 链走不到「已登记未豁免」的函数，" +
                        "无一项自身被自证语料直接引用——豁免链自我背书，自证语料并未经转发到达本函数"
                }
            }
        }
        assertTrue(
            "豁免缺少机器可校验的指称物（`exempt` 只是说明，豁免依据必须是 via 调用边）：\n" +
                problems.joinToString("\n") { "  - $it" } +
                "\n修法：① 该函数确被某个已自证的内核转发调用 → 在 via 写明内核函数名（HELPER 类可写调用它的用例名）；" +
                "② 确实没有指称物 → 补一个合成样本自证用例，把缺口关掉（缺口不得固化成受保护的豁免）。",
            problems.isEmpty(),
        )
    }

    /**
     * 豁免链是否接到「已登记**未**豁免」的登记项——那类函数自身被自证语料直接引用（判据 1 已保证）。
     *
     * 递归而非只看一层：共享内核常有两级转发（`invokeLaterSites` ← `invokeLaterWriteOffenders` ←
     * `anyModalityWriteOffenders`/`defaultModalityWriteOffenders`），只看一层会把合法的两级链误判成
     * 「自我背书」。环由 [visited] 兜住（via 互为引用时不死循环）；via 指向非登记项（用例名/夹具）时
     * 不构成终点——终点必须是登记表里的判定面函数，否则「链接到自证语料」这句话就没有机器含义。
     */
    private fun reachesDirectCoverage(
        entry: GuardEntry,
        fn: DecisionFn,
        visited: MutableSet<String>,
    ): Boolean = fn.via.any { caller ->
        val target = entry.decisionFns.firstOrNull { it.name == caller } ?: return@any false
        if (target.exempt == null) return@any true
        visited.add(caller) && reachesDirectCoverage(entry, target, visited)
    }

    /**
     * 独立复核㉗ 的补强：**登记粒度跨类一致**（判据 7）。
     *
     * 类级 Boolean 返回函数是「形态判定/谓词」，与 `*Offenders` 同属判定面，只是不命中命名形状
     * （`actionBlockHasKeyboardShortcut`/`isProduction`/`writesAt` 一个都不沾）。已登记类里出现未登记的
     * 这类函数 → 红：这正是复核点名的「`PluginXmlStructureGuardTest` 以空 decisionFns 登记、同性质助手
     * 完全不登记」的复发形态。
     */
    @Test
    fun `已登记守卫类里的Boolean判定函数必须逐条登记`() {
        val undeclared = GUARD_CLASSES.flatMap { entry ->
            val declared = entry.decisionFns.map { it.name }.toSet()
            fnDecls(sourceOf(entry.path))
                .filter { it.returnType?.startsWith("Boolean") == true && it.name !in declared }
                .map { "${entry.path}:${it.line} ${it.name}(): ${it.returnType}" }
        }
        assertTrue(
            "已登记守卫类里存在未逐条登记的 Boolean 判定函数（形态谓词脱离覆盖率约束——" +
                "BannedApiSourceScanTest 逐条登记站点提取器，其余类不得以空 decisionFns 整体豁免）：\n" +
                undeclared.joinToString("\n") { "  - $it" } +
                "\n修法：登记进该类 decisionFns（确是判定面给 VIOLATIONS/SITES 并配自证语料；" +
                "只是夹具/助手给 HELPER 并写明 via 调用点）",
            undeclared.isEmpty(),
        )
    }

    @Test
    fun `磁盘守卫类必须全部登记_防新守卫类整体脱离扫描面`() {
        val registered = GUARD_CLASSES.map { it.path }.toSet()
        val unregistered = allTestSources()
            .filter { (path, _) -> isGuardFile(path.substringAfterLast('/')) && path !in registered }
            .map { it.first }
            .sorted()
        assertTrue(
            "磁盘上存在未登记的守卫类（整体脱离自证覆盖率扫描面）：$unregistered。" +
                "修法：在 GUARD_CLASSES 登记（无「返回违规点/站点集合」的判定内核时 decisionFns 留空并写明依据）",
            unregistered.isEmpty(),
        )
    }

    @Test
    fun `命名兜底面命中的判定形状函数必须全部登记`() {
        val registered = GUARD_CLASSES.map { it.path }.toSet()
        val unregisteredFiles = allTestSources()
            .filter { (path, source) -> path !in registered && decisionShapedDecls(source).isNotEmpty() }
            .map { (path, source) ->
                "$path -> " + decisionShapedDecls(source).joinToString { "${it.first}:${it.second}" }
            }
        assertTrue(
            "存在未登记却声明了判定形状函数（*Offenders/*Sites/*Violations/*Hits/*Functions）的测试文件：\n" +
                unregisteredFiles.joinToString("\n") { "  - $it" } +
                "\n修法：确是守卫判定内核则登记进 GUARD_CLASSES 并配自证语料；否则登记为 HELPER 类豁免并写明依据",
            unregisteredFiles.isEmpty(),
        )

        val undeclared = GUARD_CLASSES.flatMap { entry ->
            val declared = entry.decisionFns.map { it.name }.toSet()
            decisionShapedDecls(sourceOf(entry.path))
                .filter { (name, _) -> name !in declared }
                .map { (name, line) -> "${entry.path}:$line $name" }
        }
        assertTrue(
            "已登记守卫类里出现未逐条登记的判定形状函数（新判定函数脱离覆盖率约束）：\n" +
                undeclared.joinToString("\n") { "  - $it" },
            undeclared.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 元守卫自身灵敏度自证（合成源码喂进同一判定实现）
    // ------------------------------------------------------------------

    /**
     * 元守卫自身的红绿语料：三种形态喂给 [uncoveredNames] / [decisionShapedDecls] / [selfTestFns]，
     * 断言各自落位——否则本元守卫自己也可能是「扫描面失效仍全绿」的那一个。
     */
    @Test
    fun `元守卫灵敏度自证_未自证的判定函数必红_自证与KDoc边界各归其位`() {
        // ① 未自证：判定函数存在，唯一的 @Test 不是自证语料 → 必须被点名
        val uncovered = """
            class SyntheticUncoveredTest {
                private fun syntheticOffenders(code: String): List<String> = emptyList()

                @Test
                fun `主用例_全仓零违规`() {
                    assertTrue(syntheticOffenders("x").isEmpty())
                }
            }
        """.trimIndent()
        assertTrue(
            "判定形状函数必须被识别：${decisionShapedDecls(uncovered)}",
            decisionShapedDecls(uncovered).any { it.first == "syntheticOffenders" },
        )
        assertTrue(
            "非自证的 @Test 不得算语料（否则覆盖率检查整体空转）：${selfTestFns(uncovered).map { it.first }}",
            selfTestFns(uncovered).isEmpty(),
        )
        assertTrue(
            "缺自证的判定函数必须被点名：${uncoveredNames(uncovered, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS)))}",
            uncoveredNames(uncovered, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS))) ==
                listOf("syntheticOffenders"),
        )

        // ② 已自证：自证用例函数体内引用判定函数 → 放行
        val covered = """
            class SyntheticCoveredTest {
                private fun syntheticOffenders(code: String): List<String> = emptyList()

                /** 合成样本自证：注入违规形态必报。 */
                @Test
                fun `合成样本自证_注入必报`() {
                    assertTrue(syntheticOffenders("bad").isNotEmpty())
                }
            }
        """.trimIndent()
        assertTrue(
            "自证用例（名字含关键词）必须被识别：${selfTestFns(covered).map { it.first }}",
            selfTestFns(covered).any { it.first.contains("自证") },
        )
        assertTrue(
            "被自证用例引用的判定函数不得报缺自证：${uncoveredNames(covered, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS)))}",
            uncoveredNames(covered, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS))).isEmpty(),
        )

        // ③ KDoc 边界：判定函数自身 KDoc 含「自证」字样 + 主用例引用它 → 仍必须报缺自证
        //    （BannedApiSourceScanTest.kt:610 的真实形态：设计说明写「让规则本身可自证」）
        val kdocOnly = """
            class SyntheticKdocOnlyTest {
                /**
                 * 单独成函数（而非内联进 @Test）是为了让规则本身可自证：合成源码样本能直接喂进来。
                 */
                private fun syntheticOffenders(code: String): List<String> = emptyList()

                @Test
                fun `主用例_全仓零违规`() {
                    assertTrue(syntheticOffenders("x").isEmpty())
                }
            }
        """.trimIndent()
        assertTrue(
            "判定函数自身 KDoc 里的「自证」字样不得被当语料（否则 :610 形态会洗绿整个检查）：" +
                "${selfTestFns(kdocOnly).map { it.first }}",
            selfTestFns(kdocOnly).isEmpty(),
        )
        assertTrue(
            "KDoc 含「自证」的判定函数仍必须报缺自证：${uncoveredNames(kdocOnly, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS)))}",
            uncoveredNames(kdocOnly, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS))) ==
                listOf("syntheticOffenders"),
        )

        // ④ 注释标注形态（DmlAliasGuardTest.kt:121 的真实形态：`//` 注释与 @Test 之间夹 `// ----` 与空行）
        val commentMarked = """
            class SyntheticCommentMarkedTest {
                private fun syntheticOffenders(code: String): List<String> = emptyList()

                // ---- 守卫有效性自证：注入违规形态必红（防止守卫自身空转）----
                // ----
                @Test
                fun injectedFormsAreDetected() {
                    assertTrue(syntheticOffenders("bad").isNotEmpty())
                }
            }
        """.trimIndent()
        assertTrue(
            "紧邻 `//` 注释标注的自证用例必须被识别（允许夹 `//` 分隔线与空行）：${selfTestFns(commentMarked).map { it.first }}",
            selfTestFns(commentMarked).any { it.first == "injectedFormsAreDetected" },
        )
        assertTrue(
            "注释标注的自证用例应覆盖其引用的判定函数：${uncoveredNames(commentMarked, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS)))}",
            uncoveredNames(commentMarked, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS))).isEmpty(),
        )

        // ⑤ 中文函数名（无反引号）形态：`test_扫描器灵敏度与豁免自证()` 是仓内真实形态
        //    （DialogWrapperInitOrderGuardTest:363 / EnumReachabilityGuardTest:267）。Java 正则的 `\w`
        //    只认 ASCII，函数名匹配写成 `[A-Za-z_]\w*` 会在中文首字符处断掉、整条声明静默漏识别，
        //    把这两个类的 `scan` 误报成「缺自证」——2026-09-22 首次实跑即栽在此。
        val cjkNamed = """
            class SyntheticCjkNamedTest {
                private fun syntheticOffenders(code: String): List<String> = emptyList()

                @Test
                fun test_扫描器灵敏度与豁免自证() {
                    assertTrue(syntheticOffenders("bad").isNotEmpty())
                }
            }
        """.trimIndent()
        assertTrue(
            "中文函数名的自证用例必须被识别（Java 正则 `\\w` 只认 ASCII，写成 `[A-Za-z_]\\w*` 会整条漏）：" +
                "${selfTestFns(cjkNamed).map { it.first }}",
            selfTestFns(cjkNamed).any { it.first.startsWith("test_扫描器") },
        )
        assertTrue(
            "中文函数名自证用例应覆盖其引用的判定函数：${uncoveredNames(cjkNamed, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS)))}",
            uncoveredNames(cjkNamed, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS))).isEmpty(),
        )
    }

    private data class FnDecl(
        val name: String,
        val line: Int,
        val selfTest: Boolean,
        val body: String,
        /** 声明行上的返回类型（多行参数表为 null，见类头漏报面第 8 条）。 */
        val returnType: String?,
    )

    private enum class FnKind {
        /** 返回违规点集合的判定内核（`List<String>` 形态），本元守卫的主要约束对象。 */
        VIOLATIONS,

        /** 返回站点集合的提取器（`List<XxxSite>` 形态），违规点内核的输入。 */
        SITES,

        /** 命名形状偶然命中的断言助手/夹具，不是判定内核。 */
        HELPER,
    }

    /**
     * 一个判定函数登记项。
     *
     * [exempt] 非空即豁免——但**它只是给人看的说明，不是豁免依据**：机器只认 [via]（见判据 6），
     * 且该函数须保持「无自证语料」（补上自证后本元守卫会红并要求删除豁免）。
     *
     * [via] 是豁免的**机器可校验指称物**：本函数被哪些同类函数引用。校验①该函数存在、②其函数体
     * （剥注释后）真的引用本函数、③ SITES/VIOLATIONS 类至少一项指向已登记未豁免的函数（防自我背书）。
     */
    private data class DecisionFn(
        val name: String,
        val kind: FnKind,
        val exempt: String? = null,
        val via: List<String> = emptyList(),
    )

    /**
     * 一个守卫类登记项。
     *
     * [decisionFns] 为空表示该类没有「返回违规点/站点集合」的判定内核（断言直接内联在 `@Test` 里），
     * 此时 [note] 必须写清依据。注意：类级 **Boolean 返回**函数也算判定面（判据 7），有它就不得留空。
     */
    private data class GuardEntry(val path: String, val decisionFns: List<DecisionFn>, val note: String)

    private companion object {
        const val TEST_PKG = "cn/song/mybatisplus/visualizer/"

        /**
         * 类级成员函数声明（4 空格缩进；反引号函数名与常见修饰符都收）。
         *
         * 标识符用 `[\p{L}_][\p{L}\p{N}_]*` 而非 `[A-Za-z_]\w*`：Java 正则的 `\w` **只认 ASCII**，
         * 而本仓自证用例大量用「无反引号的 `test_扫描器灵敏度与豁免自证()`」这种**中文函数名**形态——
         * 用 `\w` 会在中文首字符处断掉、`\s*\(` 随即失配，整条声明**静默不被识别**，进而把
         * `DialogWrapperInitOrderGuardTest.scan` / `EnumReachabilityGuardTest.scan` 误报成「缺自证」
         * （2026-09-22 首次实跑实证：本元守卫自身先栽在这条上）。
         */
        val FUN_DECL = Regex(
            "^ {4}(?:(?:private|internal|public|protected|override|suspend|inline|operator|open|tailrec)\\s+)*" +
                "fun\\s+(`[^`]+`|[\\p{L}_][\\p{L}\\p{N}_]*)\\s*\\(",
        )

        /**
         * 声明行上的返回类型（参数表闭合括号之后的 `: Type`）。
         *
         * 取**首个**能匹配上的 `): Type`——参数里带 lambda 类型（`(Int) -> Unit`）时首括号后不是冒号，
         * 正则自然跳到参数表真正的闭合括号；多行参数表则整条失配（返回 null，见类头漏报面第 8 条）。
         */
        val RETURN_TYPE = Regex("""\)\s*:\s*([^={]+?)\s*(?:=|\{|$)""")

        /**
         * 判定函数的命名兜底形状（计划约定的 `*Offenders`/`*Sites` 加同性质后缀）。
         *
         * 尾锚用 `\z` 而非 `$`：Kotlin 普通字符串里 `$` 是模板起始符（同 `EnumReachabilityGuardTest`
         * 的 `tailMatches` 教训），写 `"...Functions)$"` 有被词法层吃掉的风险。
         */
        val DECISION_SHAPE = Regex("(Offenders|Sites|Violations|Hits|Functions)\\z")

        /** 自证语料关键词（大小写不敏感）。 */
        val SELF_TEST_MARK = Regex("自证|自检|selftest", RegexOption.IGNORE_CASE)

        /**
         * 守卫类登记表（**主识别面**：命名约定认不全本仓的判定函数，见类头「漏报面」第 1 条）。
         *
         * 基线扫描（2026-09-22，master 树）实测：文件名闭包内的守卫类中，仅 5 个暴露可注入合成
         * 样本的判定内核（`BannedApiSourceScanTest` / `DialogWrapperInitOrderGuardTest` /
         * `EnumReachabilityGuardTest` / `DmlAliasGuardTest` / `PluginXmlStructureGuardTest`），另两例手工扩展登记
         * （`BundlePlaceholderArityTest` / `DockerComposeLabConsistencyTest`）。
         *
         * 独立复核㉗ 后：本表**不再接受散文豁免**——`exempt` 只是说明，豁免依据必须是 `via` 调用边
         * （判据 6）；已登记类里的类级 Boolean 判定函数也必须逐条登记（判据 7）。
         */
        val GUARD_CLASSES = listOf(
            GuardEntry(
                "convention/HardcodedUiTextGuardTest.kt",
                listOf(DecisionFn("scanHardcoded", FnKind.VIOLATIONS)),
                "UI 控件文案硬编码中文守卫（T-TestGateGems W1-a）：判定内核 `scanHardcoded` 逐文件剥注释" +
                    "后按 `uiTextPatterns` 扫 CJK 字面量，返回 `文件:行 片段` 违规点集合。自证语料=同文件" +
                    "「判定内核能看见硬编码中文形态」+「英文文案与注释中文不得命中」两例，覆盖正反两面。",
            ),
            GuardEntry(
                "convention/IconResourceGuardTest.kt",
                listOf(DecisionFn("declaredIconPaths", FnKind.SITES)),
                "图标资源存在性守卫（T-TestGateGems W1-b）：判定内核 `declaredIconPaths` 从 plugin.xml " +
                    "属性与 .kt 字面量提取 `/icons/*.svg|png` 站点集合（`文件:行` to 路径）。合并 master 时" +
                    "登记，并顺手补了自证引用——原自证用例只对合成路径做 isFile、未调用提取器，元守卫" +
                    "因此报缺口；现「判定内核能看见缺失的图标资源」已直接断言提取器能提非空站点集合。",
            ),
            GuardEntry(
                "convention/SqlDialectParserDefinitionPairingGuardTest.kt",
                emptyList(),
                "T-PreviewPsiDialect 根因守卫：database-optional.xml 里每个 <sql.dialect> 必须有同名 " +
                    "<lang.parserDefinition>（缺了平台会回落到基语言 SQL 的 parser definition，其 file node " +
                    "type 绑基类 SqlLanguage，SqlParserDefinitionBase.createFile 强转 SqlLanguageDialect " +
                    "抛 CCE → 预览 PSI 建不出来、预热降级）。判定内核为纯文本扫描" +
                    "（dialectClasses / parserDefinitionLanguages 两个提取器 + 数量与配对断言），" +
                    "无返回违规点集合的 Boolean 判定函数，故 decisionFns 留空；" +
                    "灵敏度=摘掉一条 lang.parserDefinition 注册必红（实施轮实测）。",
            ),
            GuardEntry(
                "log/EmptyTextLinkApiGuardTest.kt",
                emptyList(),
                "空态链接 API 守卫（T-SettingsFocusFromLink 二修）：判定内核内联于用例——行级扫描 " +
                    "src/main/kotlin 全量 .kt，剥行首 `//`/`*`/`/*` 注释行后断言无 " +
                    "appendSecondaryText 实际调用（平台该 API 固定写 row=1 会叠印空态链接）。" +
                    "无独立「返回违规点集合」的具名判定函数，故 decisionFns 留空；" +
                    "灵敏度=探针（临时插入真调用）必红已实测于实施轮。",
            ),
            GuardEntry(
                "ui/inject/SqlMapperKeywordCoverageGuardTest.kt",
                emptyList(),
                "mapper 注入语言关键字集一致性守卫（T-XmlGap W3）：判定内核内联于用例——断言 " +
                    "SqlLexer.KEYWORDS（补全链）⊆ SqlMapperLanguage.KEYWORDS（高亮链）、方言保留字在集内，" +
                    "并用内部调研报告 §0.9 的**真实差集**（25 词 + 9 个函数名）作判别位（非合成样本）。" +
                    "无独立「返回违规点集合」的具名判定函数（差集在用例内联计算），故 decisionFns 留空；" +
                    "灵敏度=把 KEYWORDS 改回旧 41 词平集必红，已实测于实施轮（4 例红，含逐词差集原文）。",
            ),
            GuardEntry(
                "psi/FullScanCancelGuardTest.kt",
                emptyList(),
                "全量短名扫描的「有界 + 可取消」守卫（T-JarEmbedded，补 20261008 T-GutterDupFreeze 冻结形态的缺口）：" +
                    "判定内核内联于两个用例——扫 src/main/kotlin 全量 .kt（先剥注释），断言" +
                    "「每个 getAllClassNames() 所在成员函数体含 checkCanceled( 或委派 EntityAliasSweep」" +
                    "与「库扫描 runLibrarySweepNow 不得出现 inferDecoratedTableName」。" +
                    "offenders 列表在用例内联累积，无独立「返回违规点集合」的具名判定函数，故 decisionFns 留空；" +
                    "灵敏度=按用例内合成样本自证（含合法/违规两向样本），实施轮已实测。",
            ),
            GuardEntry(
                "convention/EnhancedCaptureDefaultOnGuardTest.kt",
                emptyList(),
                "增强捕获默认开启/确认流删除守卫（T-EnhancedCaptureDefaultOn）：判定内核内联于三个用例——" +
                    "扫 src/main 全量 .kt/.java（先剥注释）断言确认流符号零命中；扫中英 bundle 断言确认/" +
                    "重新授权/未授权键零命中且 enableDone/tip 键在场；断言默认值与 loadState 归一在场。" +
                    "无独立「返回违规点集合」的具名判定函数，故 decisionFns 留空；" +
                    "灵敏度=反向三组各恰 1 红（恢复 confirmed 闸门 / 恢复 requestReauthorization 符号 / " +
                    "摘 loadState 归一）已实测。",
            ),
            // ---- T-PerfSweep27-03：机械优化结构静检守卫（2026-10-27） ----
            GuardEntry(
                "convention/PerfSweep27StaticGuardTest.kt",
                emptyList(),
                "券03 机械优化源码结构钉（T-PerfSweep27-03）：判定内核内联于两个用例——" +
                    "读 SqlCompletionContributor.kt 源码断言 isDebugEnabled 块门控存在且 " +
                    "editor 等急切读取落入门控内；读 HqlGutterLineMarkerProvider.kt 断言 " +
                    "showGutterIcon 开关判定行先于 hasStringFirstArgument 调用行（关闸不付 resolve）。" +
                    "无独立「返回违规点集合」的具名判定函数，故 decisionFns 留空；" +
                    "灵敏度=反向探针（摘门控改 run{ / 开关判定后置）两断言各恰 1 红，实施轮已实测。",
            ),
            GuardEntry(
                "convention/ExplainAskPasswordChannelGuardTest.kt",
                emptyList(),
                "结构化执行计划三通道 askPassword 表态守卫（T-ExplainPwdPrompt）：判定内核内联于三个用例——" +
                    "扫 ExplainDialog/预估行数与 EXPLAIN 旁路/MCP 工具三处源码，断言 askPassword 按" +
                    "通道强制表态（UI 两通道 true、MCP 通道 false），漏表态即红。无独立「返回违规点集合」" +
                    "的具名判定函数，故 decisionFns 留空。master 侧 4bccd331 加守卫时漏登（该侧元守卫" +
                    "潜伏红灯），合并轮补登。",
            ),
            // ---- T-DialectInferFix 券 06：方言兜底反向守卫（2026-10-29） ----
            GuardEntry(
                "convention/DialectInferFallbackGuardTest.kt",
                listOf(
                    DecisionFn(
                        "insideString",
                        FnKind.HELPER,
                        "注释剥离助手（返回 Boolean，不是判定内核）：判断行内 `//` 是否落在字符串字面量里，" +
                            "供 stripComments 决定是否截断；被 stripComments 的 map 分支调用。",
                        via = listOf("stripComments"),
                    ),
                ),
                "方言兜底反向守卫（T-DialectInferFix 券 06）：判定内核内联于四个用例——" +
                    "读 SqlPreviewPanelEditorLifecycle 断言不再出现 MySQL 硬兜底符号" +
                    "（MYSQL_LANGUAGE_ID / setMySqlOnEdt）且走继承入口 inheritedLanguageId；" +
                    "读 PreviewSourceContext 断言无「声明了没人读」的精确 URL/数据源字段；" +
                    "读日志识别通道断言 capturedDialect 在场；另有用例以合成样本自证危险形态必红。" +
                    "无独立「返回违规点集合」的具名判定函数，故 decisionFns 留空。",
            ),
            // ---- changeNotes 评分引导句守卫（2026-10-30，用户二次指出） ----
            GuardEntry(
                "convention/ChangeNotesRatingLineGuardTest.kt",
                emptyList(),
                "changeNotes 评分引导句只留最新块守卫：判定内核内联于两个用例——数 gradle/plugin-change-notes.html " +
                    "里「好用的话，欢迎到 Marketplace 页评分留言」的出现次数须恰为 1；并断言该行落在第一个 <h2> 块内。" +
                    "无独立「返回违规点集合」的具名判定函数，故 decisionFns 留空；" +
                    "灵敏度＝把该行复制到第二块必红、只留历史块必红，两向已实测。",
            ),
            // ---- T-TemplateHook 接线守卫（FR-0045 第 3 项 MVP，2026-10-31） ----
            GuardEntry(
                "convention/ExternalTemplateWiringSourceScanTest.kt",
                emptyList(),
                "外部模板渲染接线守卫（T-TemplateHook）：判定内核内联于三个用例——读 McpSourceLocator.kt 源码断言" +
                    "首个 externalStep( 调用点早于 STATEMENT_TAGS 爬升、且前 12 行内有 isActive() 空判门；" +
                    "读 ExternalTemplateSettings.kt 断言 enabled 出厂默认 false。" +
                    "无独立「返回违规点集合」的具名判定函数，故 decisionFns 留空；" +
                    "灵敏度=注释掉 XML 分支的 externalStep 调用 → 断言一必红（计划 §8.3 已留两次读数），" +
                    "另有 `自证_挂点后置与无门形态必被判红` 用合成源码钉住两种故障形态。",
            ),
            // ---- 判定内核类（有可注入合成样本的判定函数） ----
            GuardEntry(
                "convention/BannedApiScanSupport.kt",
                listOf(
                    DecisionFn(
                        "assertNoHits",
                        FnKind.HELPER,
                        "断言助手（返回 Unit，不返回违规点集合），命名偶然命中 *Hits 兜底形状；" +
                            "其行为由下列主用例的断言直接覆盖（via 即这三个调用点）",
                        via = listOf(
                            "禁用ActionToolbar旧式刷新API与反射强改按钮hack",
                            "禁用IPGP发行版裁剪平台类",
                            "剪贴板写入只允许收口与白名单直用",
                        ),
                    ),
                ),
                "T-M53-II 拆出的扫描支撑基类：判定面只剩断言助手 assertNoHits（豁免；via 指向三个调用用例，用例在同批拆出的测试类中，经注册面全集解析）。",
            ),
            GuardEntry(
                "convention/BannedApiSourceScanTest.kt",
                listOf(
                ),
                "T-M53-II 拆出的「禁用 API 与结构锚点」测试类：断言内联在用例里，无独立判定内核，故 decisionFns 留空。",
            ),
            GuardEntry(
                "convention/BannedApiScanRuleTest.kt",
                listOf(
                    DecisionFn(
                        "invokeLaterSites",
                        FnKind.SITES,
                        "站点提取器（invokeLater 调用点），被共享内核 invokeLaterWriteOffenders 调用；自证经该转发边到达",
                        via = listOf("invokeLaterWriteOffenders"),
                    ),
                    DecisionFn(
                        "invokeLaterWriteOffenders",
                        FnKind.VIOLATIONS,
                        "共享内核，被两个已自证的模态规则转发调用；自证经这两条边到达（无独立合成样本用例）",
                        via = listOf("anyModalityWriteOffenders", "defaultModalityWriteOffenders"),
                    ),
                    DecisionFn("anyModalityWriteOffenders", FnKind.VIOLATIONS),
                    DecisionFn("defaultModalityWriteOffenders", FnKind.VIOLATIONS),
                    DecisionFn(
                        "throwableCatchSites",
                        FnKind.SITES,
                        "站点提取器（catch (…: Throwable) 站点），被 fatalPassthroughOffenders 调用；自证经该转发边到达",
                        via = listOf("fatalPassthroughOffenders"),
                    ),
                    DecisionFn("fatalPassthroughOffenders", FnKind.VIOLATIONS),
                    DecisionFn("pooledThreadSites", FnKind.SITES),
                    DecisionFn("psiCaptureOffenders", FnKind.VIOLATIONS),
                    DecisionFn("edtReadContractOffenders", FnKind.VIOLATIONS),
                    DecisionFn("anyModalityInUpdatePackageOffenders", FnKind.VIOLATIONS),
                ),
                "T-M53-II 拆出的四族规则类（写安全·模态 / catch(Throwable) 致命 / 池化线程读 PSI / 跳转族读权限）：判定内核与其自证用例同文件。",
            ),
            GuardEntry(
                "convention/BannedApiScanServiceStateTest.kt",
                listOf(
                    DecisionFn("initEntrySites", FnKind.SITES),
                    DecisionFn(
                        "serviceWriteHits",
                        FnKind.SITES,
                        "站点提取器（项目服务写站点），被 projectServiceStateOffenders 调用；自证经该转发边到达",
                        via = listOf("projectServiceStateOffenders"),
                    ),
                    DecisionFn("projectServiceStateOffenders", FnKind.VIOLATIONS),
                    DecisionFn(
                        "entrySites",
                        FnKind.SITES,
                        "站点提取器（初始化/收尾入口站点），被 initEntrySites/teardownEntrySites 转发调用；" +
                            "自证经这两条边到达（无直接合成样本用例）",
                        via = listOf("initEntrySites", "teardownEntrySites"),
                    ),
                    DecisionFn("teardownEntrySites", FnKind.SITES),
                    DecisionFn("teardownServiceLookupOffenders", FnKind.VIOLATIONS),
                ),
                "T-M53-II 拆出的项目服务状态规则族：判定内核与其自证用例同文件。",
            ),

            GuardEntry(
                "convention/DialogWrapperInitOrderGuardTest.kt",
                listOf(
                    DecisionFn("scan", FnKind.VIOLATIONS),
                    // 独立复核㉗ 判据 7 补登记：Boolean 形态谓词此前完全不在登记面内
                    DecisionFn(
                        "writesAt",
                        FnKind.HELPER,
                        "纯赋值形态谓词（`name = expr`，排除 `==`/`+=`），是判定内核 scan 的输入谓词；自证经该转发边到达",
                        via = listOf("scan"),
                    ),
                ),
                "判定函数名不命中命名形状（叫 scan，:178）——正是「登记表为主识别面」的实证；自证 :363",
            ),
            GuardEntry(
                "convention/ManualButtonPaintGuardTest.kt",
                listOf(
                    DecisionFn("scan", FnKind.VIOLATIONS),
                    DecisionFn(
                        "isRendererFile",
                        FnKind.HELPER,
                        "渲染器文件判定（Boolean 形态谓词），是判定内核 scan 的豁免输入；自证经该转发边到达",
                        via = listOf("scan"),
                    ),
                ),
                "按钮手工铺底色形态守卫（UI-043）：判定内核 scan 返回命中集合，自证用例 test_扫描器灵敏度与豁免自证 " +
                    "喂合成源码（isOpaque/自选色必红、命名色与渲染器必走豁免）。",
            ),
            GuardEntry(
                "log/SqlLogToolbarUpdateThreadGuardTest.kt",
                emptyList(),
                "SQL 日志工具栏 BGT 声明守卫（T-SqlLogToolbarEdt W4）：判定内核内联于四个用例——" +
                    "扫 SqlLogPanel 源码断言组与组内每个覆写 update 的动作都声明 BGT、BGT 常量类型为 BGT、" +
                    "排序文案查表覆盖全部模式。offenders 在用例内累积，无独立「返回违规点集合」的具名判定函数，" +
                    "故 decisionFns 留空；灵敏度=删一个动作的 BGT 声明恰 1 红已实测（并修掉首版正则剥 Action " +
                    "后缀致 filter 全程跳过的假绿）。",
            ),
            GuardEntry(
                "ui/ExplainStructuredDefaultOnGuardTest.kt",
                emptyList(),
                "结构化执行计划默认开守卫（R45 转正；沿革 T-ExplainConsoleFirst W6）：判定内核内联于三个用例——" +
                    "断言 explainStructuredRender 默认值为 true、「启用结构化并重试」推荐仍只提示一次、" +
                    "设置页仍保留显式开关。断言直接内联，无独立「返回违规点集合」的具名判定函数，故 decisionFns 留空；" +
                    "灵敏度=把默认值改回 false 或去掉一次性标记必红。",
            ),
            GuardEntry(
                "config/SettingsPanelSearchableGuardTest.kt",
                listOf(
                    DecisionFn(
                        "isDescendantOf",
                        FnKind.HELPER,
                        "组件归属谓词（Boolean 形态），服务于断言一的动态豁免组件过滤；" +
                            "本类用例名与上方注释均无「自证」关键词、不构成自证语料，故按豁免+via 登记" +
                            "（HELPER 类可写调用它的用例名）",
                        via = listOf("test面板输入型文本均能在搜索索引命中或列白名单"),
                    ),
                ),
                "设置页内搜索面板-索引配对守卫（T-SettingsSearchB）：判定内核内联于四个用例（真实面板文本经 " +
                    "双语 bundle 反解回键逐条配对索引、四关键词真实搜索链路命中），无独立判定函数；" +
                    "decisionFns 仅登记 Boolean 形态谓词（判据 7）。",
            ),
            GuardEntry(
                "convention/SettingsScenarioGroupCoverageGuardTest.kt",
                listOf(
                    DecisionFn("booleanFieldsOf", FnKind.SITES),
                    DecisionFn("ungroupedBooleanFields", FnKind.VIOLATIONS),
                    DecisionFn("staleGroupKeys", FnKind.VIOLATIONS),
                ),
                "设置项场景归属守卫（T-SettingsConverge 守卫）：扫 PluginSettings.kt 的 `var xxx: Boolean` 字段名，" +
                    "与 SettingsScenarioGroups 的场景清单对账——未归组的 Boolean 即红并点名，清单里的陈旧键同样红。" +
                    "三个判定内核均可注入合成源码；自证语料=「未归组的 Boolean 必被点名且陈旧键可检出」" +
                    "「非 Boolean 字段不得误判」两例，覆盖正反两面。",
            ),
            GuardEntry(
                "convention/EnumReachabilityGuardTest.kt",
                listOf(
                    DecisionFn("scan", FnKind.VIOLATIONS),
                    DecisionFn(
                        "tailMatches",
                        FnKind.HELPER,
                        "尾锚匹配谓词（`$` 会被词法层吃掉，故单独成函数），判定内核 scan 的输入；自证经该转发边到达",
                        via = listOf("scan"),
                    ),
                    DecisionFn(
                        "isProduction",
                        FnKind.HELPER,
                        "引用点是否产出点谓词，判定内核 scan 的输入；自证经该转发边到达",
                        via = listOf("scan"),
                    ),
                ),
                "判定函数叫 scan（:206）；自证 :267；WHITELIST 反腐烂口径被本元守卫的豁免检查沿用",
            ),
            GuardEntry(
                "generator/DmlAliasGuardTest.kt",
                listOf(DecisionFn("dmlTargetsHaveNoAlias", FnKind.VIOLATIONS)),
                "判定函数名不命中命名形状（:42）；自证用例靠 `//` 行注释标注（:121）+ 函数名不含关键词（:125）",
            ),
            GuardEntry(
                "sql/verify/DialectDocTableGuardTest.kt",
                listOf(
                    DecisionFn("docTableViolations", FnKind.VIOLATIONS),
                    DecisionFn("classifyPagination", FnKind.SITES),
                    DecisionFn("classifyUpsert", FnKind.SITES),
                ),
                "文档站方言表 ↔ Dialect 实现的族一致性守卫（T-DialectDocDrift，2026-10-26 三处已上线错误倒查所得）；" +
                    "两个 classify 是族归类器（文档与实现共用同一实现，族判定的唯一事实源），docTableViolations 是比对内核；" +
                    "自证语料：`漂移自证_分页族归错被抓` / `漂移自证_ROWNUM写法被抓` / `漂移自证_漏方言被抓` / " +
                    "`漂移自证_upsert族归错被抓`（后者同时覆盖两个 classify 的归类灵敏度）。" +
                    "文档站不在场时四个文档表用例显式绿跳（外部贡献者只 clone 插件仓），但表格在场却解析不出内容必红。",
            ),
            GuardEntry(
                "sql/verify/DockerComposeLabConsistencyTest.kt",
                listOf(
                    DecisionFn("checkImagePinning", FnKind.VIOLATIONS),
                    DecisionFn("checkPorts", FnKind.VIOLATIONS),
                    // 独立复核㉗：两条「真缺口」豁免（无合成样本）已由 `漂移自证_缺必填环境变量被抓` /
                    // `漂移自证_缺healthcheck被抓` 关闭，豁免条目删除。
                    DecisionFn("checkRequiredEnv", FnKind.VIOLATIONS),
                    DecisionFn("checkHealthcheck", FnKind.VIOLATIONS),
                ),
                "手工扩展登记（文件名不含 Guard / SourceScanTest，文件名闭包发现不到）；判定函数是 internal fun checkXxx；" +
                    "四条检查各配一个漂移自证用例（:93/:105/:123 + 缺必填项/缺 healthcheck 两条）",
            ),
            GuardEntry(
                "convention/BundlePlaceholderArityTest.kt",
                listOf(DecisionFn("warningSites", FnKind.SITES)),
                "手工扩展登记（文件名不含 Guard，靠命名兜底面发现）；扫描自检 :129 覆盖 warningSites",
            ),
            GuardEntry(
                "ui/PreviewRenderSlowOpsGuardTest.kt",
                listOf(DecisionFn("slowOpDirectCallHits", FnKind.VIOLATIONS)),
                "T-PreviewEdtGuard S1：预览重渲染链宿主文件（SqlPreviewPanel/SqlAutoFolding）禁直调慢操作 " +
                    "API 族（getSyntaxHighlighter/findFileByUrl/findFileByPath——2026-09-24 沙盒实栈：" +
                    "SQL 方言映射 ensureStateLoaded→VFS findChild→FSRecords 慢操作门控）；" +
                    "自证 `test自证_慢操作API族判定内核可判别`（三令牌逐一必中+干净源零误报）",
            ),
            // ---- 无判定内核的守卫类（断言直接内联在 @Test；登记以关闭文件名闭包） ----
            GuardEntry(
                "acp/AcpTransportScopeSourceScanTest.kt",
                emptyList(),
                "断言直接内联在 @Test；repoRoot/stripComments/stripStrings/mainSources/stripXmlComments 为夹具",
            ),
            GuardEntry(
                "config/PluginXmlStructureGuardTest.kt",
                listOf(
                    // 独立复核㉗：这三个此前完全不进登记面（该类以空 decisionFns 登记）。它们被下面的
                    // 扫描自检用例直接调用，故**不是豁免**——直接进自证语料覆盖（判据 1 即可满足）。
                    DecisionFn("strippedXmls", FnKind.HELPER),
                    DecisionFn("tagsOf", FnKind.SITES),
                    DecisionFn("assertHolds", FnKind.HELPER),
                    DecisionFn("actionBlockHasKeyboardShortcut", FnKind.VIOLATIONS),
                    DecisionFn("actionBlockExists", FnKind.VIOLATIONS),
                ),
                "独立复核㉗ 前以空 decisionFns 登记（判定函数与同性质助手整体不进登记面，与 BannedApiSourceScanTest " +
                    "逐条登记站点提取器的口径不一致）；现逐条登记，语料为 `自证_合成XML带键位必报_自闭合动作不得误判` " +
                    "+ `扫描自检_资源夹具健全且剥注释无残留`",
            ),
            GuardEntry(
                "config/SettingsRealityGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；mainSources/nonConfigSources 为夹具",
            ),
            GuardEntry(
                "execute/ExecutionTimeoutGuardTest.kt",
                emptyList(),
                "表驱动纯逻辑断言直接内联在 @Test；无复用判定函数",
            ),
            GuardEntry(
                "execute/SessionIdentityGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；memory(:63) 为夹具",
            ),
            GuardEntry(
                "i18n/BundleParityGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；loadBundle/repoRoot/mainSources/metaInfXml 为夹具",
            ),
            GuardEntry(
                "i18n/DegradedBannerWordingGuardTest.kt",
                emptyList(),
                "扫描自检 :52 直接内联 bannerEntries(:40) 断言，无独立判定函数",
            ),
            GuardEntry(
                "log/ConsoleReplayGuardTest.kt",
                emptyList(),
                "判定内核 decide(:14) 返回重放/流式决策（非违规点集合），不在本元守卫面内；8 个用例为合成状态注入式",
            ),
            GuardEntry(
                "log/LogRouterHotPathRegexGuardTest.kt",
                emptyList(),
                "单用例断言直接内联",
            ),
            GuardEntry(
                "log/ReplayGuardLine0ReadTest.kt",
                emptyList(),
                "断言直接内联在 @Test；attach(:65) 为夹具",
            ),
            GuardEntry(
                "log/SqlLogAutoSaverPruneGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；tempDir/makeFile/namesOf/entryOf 为夹具",
            ),
            GuardEntry(
                "log/SqlLogListFreezeGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；effectiveRenderer/allLists/withSingleLineMode 为夹具",
            ),
            GuardEntry(
                "log/SqlLogPanelSearchWiringGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test（T-SearchToggleNativeLook 搜索框 DocumentListener 接线守卫）；repoRoot 为夹具",
            ),
            GuardEntry(
                "log/agent/AgentUrlDecoupleGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；source(:38) 为夹具",
            ),
            GuardEntry(
                "log/agent/StatementWeaveWiringGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test（T-StatementWeave 接线源码守卫，跨仓直读 agent 子项目源码）；" +
                    "agentSrc/stripComments 为夹具",
            ),
            GuardEntry(
                "mcp/McpScopeSourceScanTest.kt",
                emptyList(),
                "断言直接内联在 @Test；repoRoot/stripComments/stripStrings/stripXmlComments 为夹具",
            ),
            GuardEntry(
                "mcp/McpGateFilterRegistrationGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test（O-68 注册结构三条：单 depends、过滤 EP 禁直宣、探测与桥在册；T-MarketCompatProxy 代理形态后判据不变）",
            ),
            GuardEntry(
                "mcp/McpSelectGuardTest.kt",
                emptyList(),
                "断言经 assertDenied(:17)/assertAllowed(:24) 助手内联在 @Test",
            ),
            GuardEntry(
                "mcp/McpExplainTargetGuardTest.kt",
                emptyList(),
                "T-Fr45DmGuard S1：断言直接内联在 @Test（非 SQL 数据源判定的命中/放行形态 + " +
                    "SqlVisualizerExplainTool 接线顺序源码扫描——取 url 之后、McpDialectDetector.resolve 之前）；" +
                    "explainToolSource/stripComments 为夹具",
            ),
            GuardEntry(
                "update/UpdateZipGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；withTempZip/manualZip/concatManualZips 为夹具",
            ),
            // ---- 2026-09-22 合并 master 后补登记（master 侧 O-48/O-49 批次新增的守卫类） ----
            GuardEntry(
                "convention/SqlLogManagerLookupGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；repoRoot/stripComments/mainSources 为夹具（T-AgentResiduals R2 配套守卫）",
            ),
            GuardEntry(
                "execute/ErrorPositionWiringGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；repoRoot/readArguments/lineOf 为夹具（T-ELU-03 接线守卫）",
            ),
            GuardEntry(
                "database/CatalogCrudDialectWiringGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；repoRoot/isInsideComment/readArguments/lineOf 为夹具" +
                    "（2026-09-23 真机走查配套：目录来源 CRUD 必须按表所属项目解析方言）",
            ),
            // ---- 2026-09-23 合并 dev 后补登记（master 侧 O-68 批次新增的守卫类，基线红收口） ----
            GuardEntry(
                "mcp/McpGateFilterRegistrationGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test（O-68 注册结构守卫：mcpServer optional depends 恰一条 / " +
                    "mcp-optional.xml 禁直宣 mcpToolFilterProvider / 探测式 registrar 与桥类在册防改名漂移）",
            ),
            // ---- O-64/T-RollbackIdentityParse 新增守卫（2026-09-23） ----
            GuardEntry(
                "sql/IdentityWholeScriptWiringGuardTest.kt",
                listOf(
                    DecisionFn(
                        "argSpan",
                        FnKind.HELPER,
                        "括号配对实参提取器，被 callsWithin 调用（via 即该调用边）",
                        via = listOf("callsWithin"),
                    ),
                    DecisionFn(
                        "argSpanBody",
                        FnKind.HELPER,
                        "花括号配对函数体提取器，被 callsWithin 调用（via 即该调用边）",
                        via = listOf("callsWithin"),
                    ),
                    // 裸条目：callsWithin 由本类自证语料 `自证_合成源码缺旗标被守卫抓到` 直接引用（勿加描述=豁免）
                    DecisionFn("callsWithin", FnKind.SITES),
                ),
                "O-64 wholeScriptExec 接线守卫：生产链每个转发点必传旗标（P19 批次默认执行缺口的防复发面）；" +
                    "callsWithin 的自证语料为合成源码直接喂入",
            ),
            // ---- T-PerfSweep16 新增守卫补登记（2026-09-29 合并 dev 后收口门禁基线红，防整体脱离扫描面） ----
            GuardEntry(
                "convention/SuppressKeysPairingGuardTest.kt",
                listOf(
                    // 裸条目：判定内核由本类 4 个 `守卫自证_` 合成样本用例直接引用（勿加描述=豁免）
                    DecisionFn("unpairedSuppressKeyLines", FnKind.VIOLATIONS),
                    DecisionFn(
                        "isAssignmentTo",
                        FnKind.HELPER,
                        "赋值判别谓词（`token =` 形态，排除 `==` 比较与前缀粘连），是判定内核 unpairedSuppressKeyLines " +
                            "与扫描面保鲜计数 countSuppressKeyAssignments 的共同输入；自证语料经该转发边到达",
                        via = listOf("unpairedSuppressKeyLines"),
                    ),
                ),
                "T-PerfSweep16 F44 suppressKeys 配对写源码守卫：扫描面保鲜计数与 stripComments/repoRoot/" +
                    "readSqlLogManagerSource 为夹具",
            ),
            GuardEntry(
                "ui/gutter/GutterProviderSingleLookupGuardTest.kt",
                listOf(
                    // 裸条目：判定内核由 `守卫自证_合成样本双读必报单读放行` 直接引用
                    DecisionFn("fastPathLookupCount", FnKind.VIOLATIONS),
                ),
                "T-PerfSweep16 F36 gutter provider 单次查找守卫：providerFiles/extractBracedBody/countToken/" +
                    "stripComments/repoRoot 为夹具",
            ),
            // ---- R33 EXPLAIN 结构化渲染接线守卫（2026-10-01 批次 9 门禁第一轮修复补登记） ----
            GuardEntry(
                "ui/ExplainPanelWiringGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test（R33 接线四断言：ExplainPanelChannel.decide 可达/submitExplainDirect 直送链单函数收敛/" +
                    "ExplainPlanDialog/showExplainDialog 非模态展示不替换编辑器/ExplainIndexAdvisor.advise 面板侧建议接入）",
            ),
            GuardEntry(
                "ui/DialectStatusRefreshWiringGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test（T-DialectStatusLiveRefresh 两断言：embedConsole 钩子与绑定行刷新同拍调用 " +
                    "refreshDialectStatusRow / rerenderForExecutionTarget 在 renderSql 前升级 currentDialectDecision）",
            ),
            // ---- O-93 全局手动方言残值泄漏守卫（2026-10-02 T-BatchTestLeak 补登记——漏登记由 R33 修复轮元守卫红抓出） ----
            GuardEntry(
                "sql/DialectStaleManualLeakGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test（O-93 三断言：effective(project=null) auto 态忽略手动残值回退 MySQL/" +
                    "手动模式语义不变对照/saveOrUpdate 批量预览 upsert+反引号不受残值污染）；" +
                    "私有 leakStaleManualDialect 为污染动作夹具非判定函数",
            ),
            // ---- T-PerfSweep24 F84 新增守卫补登记（2026-10-08 收口门禁红抓出：整体脱离自证覆盖率扫描面） ----
            GuardEntry(
                "convention/InFunctionRegexSourceScanTest.kt",
                emptyList(),
                "断言直接内联在 @Test（受守卫文件零函数体现场 Regex 构造 + 判定器自证两用例）；" +
                    "repoRoot/stripComments/guardedFiles 为夹具（F59/F60/F84 提常量的源码形态守卫）",
            ),
            // ---- T-PrewarmWilDeadlock：同步 VFS refresh × WIRA 死锁守卫（2026-10-15） ----
            GuardEntry(
                "convention/SyncRefreshWriteIntentGuardTest.kt",
                listOf(
                    DecisionFn("syncRefreshInsideWiraOffenders", FnKind.VIOLATIONS),
                ),
                "同步 VFS refresh × WriteIntentReadAction 死锁守卫：BGT 持 WIL 等刷新会话（会话推进需 " +
                    "WIL/EDT）自锁，2026.2 真机 threadDump 全 IDE 冻结实证；白名单仅 EDT 冷路径 " +
                    "createTempPsiFile。判定内核配同文件合成样本自证（BGT 站点必抓/白名单不报/裸调不报）。",
            ),
            // ---- T-UiFixGate9：exec 预暖链/文档写项目 dispose 生命周期守卫（2026-10-18 门禁补登记） ----
            GuardEntry(
                "execute/ExecDocPrewarmDisposeGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test（四用例：prewarmChainAbandonedOnDisposedProject/" +
                    "prewarmRawCreateRefusedOnDisposedProject/docWriteOnceRefusedOnDisposedProject/" +
                    "writeVfTextViaDocumentNoopOnDisposedProject——项目 dispose 后预热链与文档写全链拒绝写）；" +
                    "disposedProjectProxy/refreshVf 为夹具非判定函数",
            ),
            // ---- T-LicenseCompliance：分发包许可合规守卫（20261021） ----
            GuardEntry(
                "convention/DistributionComplianceGuardTest.kt",
                emptyList(),
                "断言直接内联在三个 @Test（根目录 LICENSE/NOTICES 在场、NOTICES 覆盖 JetBrains 图标/" +
                    "JSqlParser/ASM 关键词、build.gradle.kts 的 jsqlparser 排除 jmh-core——防打包泄漏回潮）；" +
                    "零平台引用，进 testFast 快桶；反向=删 NOTICES/改名关键词/摘 exclude 必红",
            ),
            GuardEntry(
                "convention/SqlDialectEdtSourceScanTest.kt",
                listOf(DecisionFn("syntaxHighlighterFactorySites", FnKind.VIOLATIONS)),
                "平台 SQL 高亮器直取守卫（T-SqlDialectPrewarm）：该调用会触发 SqlDialectMappings 的" +
                    "一次性延迟加载（逐条 VirtualFileManager.findFileByUrl → FSRecordsImpl.update），" +
                    "落 EDT 即平台慢操作断言并把本插件归为 blame。允许清单只有预热助手一处，且断言取" +
                    "**等值**而非子集——助手不再直取时清单变空即红，防失效豁免（同 WHITELIST 反腐烂口径）。" +
                    "自证语料：`自证_裸调用与跨行全限定调用均被抓`（含真实历史跨行形态）/ " +
                    "`自证_JavaSyntaxHighlighterFactory与自研EP类不得误伤`（判错面的负例）/ " +
                    "`自证_注释里的调用不算`。",
            ),
            // ---- 本元守卫自身 ----
            GuardEntry(
                "convention/GuardSelfTestCoverageMetaGuardTest.kt",
                listOf(
                    // 三个 Boolean 谓词与剥注释夹具由判据 7 兜底进来；四个集合返回的判定内核/提取器
                    // 按「登记表为主识别面」逐条登记（原名 note 已声明它们是判定内核，故一并入表）。
                    DecisionFn("uncoveredNames", FnKind.VIOLATIONS),
                    DecisionFn("decisionShapedDecls", FnKind.SITES),
                    DecisionFn("selfTestFns", FnKind.SITES),
                    DecisionFn("fnDecls", FnKind.SITES),
                    DecisionFn("references", FnKind.HELPER),
                    DecisionFn(
                        "isSelfTest",
                        FnKind.HELPER,
                        "自证语料识别谓词，是 fnDecls 的零件（无独立合成样本用例）；via 即该调用边",
                        via = listOf("fnDecls"),
                    ),
                    DecisionFn(
                        "isGuardFile",
                        FnKind.HELPER,
                        "文件名闭包谓词，被扫描面用例直接调用（via 即该调用点）",
                        via = listOf("磁盘守卫类必须全部登记_防新守卫类整体脱离扫描面"),
                    ),
                    DecisionFn(
                        "stripComments",
                        FnKind.HELPER,
                        "剥注释夹具（判据一律跑在剥离后的文本上），被 bodyOf 调用（via 即该调用边）",
                        via = listOf("bodyOf"),
                    ),
                ),
                "本元守卫自身：判定内核为纯函数（fnDecls/selfTestFns/decisionShapedDecls/uncoveredNames），" +
                    "灵敏度由 `元守卫灵敏度自证_未自证的判定函数必红_自证与KDoc边界各归其位` 用例钉住",
            ),
        )
    }
}
