package cn.song.mybatisplus.visualizer.xml.fidelity

import cn.song.mybatisplus.visualizer.log.LogEntryPreviewRecognizerKt.countPlaceholders
import cn.song.mybatisplus.visualizer.xml.EvalResult
import cn.song.mybatisplus.visualizer.xml.MockObject
import cn.song.mybatisplus.visualizer.xml.OgnlEvaluator
import cn.song.mybatisplus.visualizer.xml.RenderedXmlSql
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlSqlRenderer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真 MyBatis 引擎 vs 自研渲染器 —— 标签语义对照集（T-TailSweep2 T-FS-02 协议 / T-FS-03 场景集）。
 *
 * 来源：T-FS-01 临时探针 `FidelityProbeTest` 的 31 条 P0~P30 实测读数（探针按自身 KDoc「不入库」
 * 处置——已删除，其读数转为本文件的断言与 `docs/调研报告/20260919-T-FidelityEval-自研与真引擎已知差异清单.md`）。
 * T-FCA（N-32）在其后追加 P31~P33 三条集合快捷别名场景（见「场景表」尾部注记）。
 *
 * ## 协议（T-FS-02）
 * 同一批**真实值**分别喂两侧：真引擎吃 [Scenario.realParams]（Map / POJO / List / Integer 等真实对象），
 * 自研吃 [Scenario.bindings]（`_parameter` + 顶层变量 + `_databaseId`，对齐 `XmlPreviewModel.mockBindings`
 * 的键形态）。两侧产物直接对照，**绕开面板 mock 层**。
 *
 * **驱动直连 `MappedStatement.getBoundSql`（绕开 `MapperMethod`）**：故 `ParamNameResolver` 的
 * 「单集合参数包装成 ParamMap」这一步在真引擎侧不会自动发生——凡依赖别名键（`collection` / `list` /
 * `array`）的场景，真引擎侧必须显式经引擎自身代码补上（[wrapToMapIfCollection]），
 * 键集由 [别名键集钉] 用例实测钉住（T-FCA-04）。
 *
 * ## 断言面（只覆盖标签语义）
 * ① SQL 文本（空白归一后逐字节相等）；② `#{}` 参数属性名序列（顺序即 `?` 出现顺序，foreach 内为
 * `__frch_<名>_<n>` 形态）；③（辅助）自研侧 `errors` 是否为空——一致面要求为空，差异面按登记形态断言。
 * **不覆盖**：mock 值语义（引号 / 类型 / 占位形态）、面板回填链、`${ew.customSqlSegment}`、性能面、
 * **我方集合快捷别名的注入**（P31~P33 的判别力边界与承重判据位置见场景表处注记）。
 *
 * ## 空白归一（唯一的非逐字节环节）
 * 真引擎保留 mapper 原文空白（换行/缩进原样进 SQL），自研渲染器按 `DynamicContext.appendSql` 的
 * 「段间单空格」规则拼接（对齐 3.5.13+ 的 `StringJoiner(" ")`）。故断言前对两侧做 `\s+` → 单空格
 * 归一（[compact]）——归一只动空白，不动 token 与 `?` 位点。
 *
 * ## 已知差异（6 条：P0 / P14 / P15 / P16 / P20 / P30）
 * 按 T-FS-02 纪律**登记为已知差异而非失败**：mock 值语义与降级形态差异若一律断言不等，对照集会被
 * 淹没而失去判别力。这 6 条按**登记形态**断言（真引擎抛什么类 / 自研渲染出什么），形态一变即红，
 * 强制同步更新差异清单文档。逐条成因见
 * `docs/调研报告/20260919-T-FidelityEval-自研与真引擎已知差异清单.md`。
 *
 * ## 驱动不可用时的口径：硬失败（不 skip）
 * `RealEngineDriver.create()` 在 jar 缺失时抛 [RealEngineUnavailableException] → 本类构造期即红。
 * 依据：① [RealEngineDriver] 自身 KDoc「调用方断言红，禁止静默跳过：跳过=对照集形同不存在」；
 * ② 真引擎 jar 经 `testImplementation(libs.mp.core)`（mybatis-plus-core:3.5.17）传递带入
 * `org.mybatis:mybatis:3.5.19`，已在测试依赖图内（仓内软跳先例 `DialectDockerVerificationTest`
 * 的 Docker 探测适用前提是「被测能力不在本机依赖图内」，此处不成立）；③ 对照集是回归资产，
 * 静默降级即退化为装饰。
 *
 * ## 引擎版本钉住
 * 真引擎版本由传递依赖决定（非本仓显式声明）→ 升级 mybatis-plus 会静默换掉被对照的引擎版本，
 * 故本类另有版本钉住用例（jar 文件名 + 隔离 loader 包 `implementationVersion` 两路核验 3.5.19）。
 */
class RealEngineFidelityTest {

    /** 构造期即建驱动：不可用 → 硬失败（口径见类 KDoc）。 */
    private val driver = RealEngineDriver.create()

    @Test
    fun `一致面 28 条：SQL 文本与参数队列逐字节相等`() {
        val mismatches = mutableListOf<String>()
        for (scenario in equivalentScenarios()) {
            val real = runCatching { driver.render(scenario.mapper, NAMESPACE, STATEMENT_ID, scenario.realParams) }
            if (real.isFailure) {
                mismatches += "${scenario.id}: 真引擎意外抛错（期望两侧一致）——${real.exceptionOrNull()!!.message}"
                continue
            }
            val realBound = real.getOrThrow()
            val mine = mine(scenario)
            val realSql = compact(realBound.sql)
            val mineSql = compact(mine.sql)
            if (realSql != mineSql) {
                mismatches += "${scenario.id}: SQL 不一致\n    真引擎=[$realSql]\n    自研  =[$mineSql]"
            }
            val mineProps = mine.mappings.map { it.property }
            if (realBound.parameterProperties != mineProps) {
                mismatches += "${scenario.id}: 参数队列不一致\n    真引擎=${realBound.parameterProperties}\n    自研  =$mineProps"
            }
            if (mine.errors.isNotEmpty()) {
                mismatches += "${scenario.id}: 自研渲染意外产生 errors=${mine.errors}"
            }
        }
        assertTrue(
            "标签语义一致面出现 ${mismatches.size} 条偏差：\n" + mismatches.joinToString("\n"),
            mismatches.isEmpty(),
        )
    }

    @Test
    fun `已知差异 6 条：按登记形态断言（形态变化即红，强制同步差异清单）`() {
        val known = knownDifferenceScenarios()

        // ---- P0 无 DOCTYPE：真引擎 BuilderException（强制 DOCTYPE）vs 自研正常渲染（更宽容）----
        val p0 = known.getValue("P0")
        val p0Real = runCatching { driver.render(p0.mapper, NAMESPACE, STATEMENT_ID, p0.realParams) }
        assertEquals(
            "P0 真引擎应抛 BuilderException（DOCTYPE 强制校验）",
            "org.apache.ibatis.builder.BuilderException",
            (p0Real.exceptionOrNull() as? RealEngineException)?.causeClassName,
        )
        val p0Mine = mine(p0)
        assertEquals("P0 自研 SQL", "SELECT * FROM t WHERE a = ?", compact(p0Mine.sql))
        assertEquals("P0 自研参数队列", listOf("a"), p0Mine.mappings.map { it.property })
        assertEquals("P0 自研无错误提示（宽容侧静默）", emptyList<String>(), p0Mine.errors)

        // ---- P14 `||flag||`：真引擎 ExpressionSyntaxException vs 自研剥壳渲染（我方超集能力）----
        val p14 = known.getValue("P14")
        val p14Real = runCatching { driver.render(p14.mapper, NAMESPACE, STATEMENT_ID, p14.realParams) }
        val p14Cause = (p14Real.exceptionOrNull() as? RealEngineException)?.causeClassName
        assertEquals("P14 真引擎应抛 BuilderException", "org.apache.ibatis.builder.BuilderException", p14Cause)
        assertTrue(
            "P14 真引擎应因 Malformed OGNL 失败：${p14Real.exceptionOrNull()?.message}",
            p14Real.exceptionOrNull()?.message.orEmpty().contains("ExpressionSyntaxException"),
        )
        val p14Mine = mine(p14)
        assertEquals("P14 自研剥壳后渲染（超集能力）", "SELECT * FROM t AND x = 1", compact(p14Mine.sql))
        assertEquals("P14 自研无错误提示（剥壳是显式兼容语义）", emptyList<String>(), p14Mine.errors)

        // ---- P15 顶层 if 非法表达式：真引擎硬抛 vs 自研容错展开 + 记 errors ----
        val p15 = known.getValue("P15")
        val p15Real = runCatching { driver.render(p15.mapper, NAMESPACE, STATEMENT_ID, p15.realParams) }
        assertEquals(
            "P15 真引擎应抛 BuilderException",
            "org.apache.ibatis.builder.BuilderException",
            (p15Real.exceptionOrNull() as? RealEngineException)?.causeClassName,
        )
        val p15Mine = mine(p15)
        assertEquals("P15 自研容错展开", "SELECT * FROM t AND x = 1", compact(p15Mine.sql))
        assertEquals("P15 自研错误提示条数", 1, p15Mine.errors.size)
        assertTrue("P15 自研错误提示应说明按 true 展开：${p15Mine.errors}", p15Mine.errors[0].contains("条件表达式无法求值"))

        // ---- P16 foreach 体内 if 非法表达式：真引擎硬抛 vs 自研展开 + **记一行可见提示** ----
        // 合并注记（T-TailSweep2 并轨时同步改）：本断言原先钉的是「errors 为空」——那是 T-FS-07 落地**前**
        // 的在册静默（PrefixedContext/FilteredContext 未转发 DynamicContext.errors）。T-FS-07（`e001b6f`）
        // 已让三个子上下文转发 errors、并在写入侧判重，故此处改为钉「恰一行且含表达式原文」：既排除
        // 「仍未转发」（=0 条），也排除「转发未去重」（=迭代次数条）。与真引擎的差别从此只剩
        // 「硬失败 vs 降级展开 + 可见提示」，差异清单 D4 已同步改述。
        val p16 = known.getValue("P16")
        val p16Real = runCatching { driver.render(p16.mapper, NAMESPACE, STATEMENT_ID, p16.realParams) }
        assertEquals(
            "P16 真引擎应抛 BuilderException",
            "org.apache.ibatis.builder.BuilderException",
            (p16Real.exceptionOrNull() as? RealEngineException)?.causeClassName,
        )
        val p16Mine = mine(p16)
        assertEquals("P16 自研展开产物", "SELECT * FROM t WHERE id IN ( ? , ? )", compact(p16Mine.sql))
        assertEquals("P16 自研参数队列", listOf("__frch_id_0", "__frch_id_1"), p16Mine.mappings.map { it.property })
        assertEquals("P16 自研 errors 应恰一行（逐迭代去重后）", 1, p16Mine.errors.size)
        assertTrue(
            "P16 自研错误提示应含 test 表达式原文：${p16Mine.errors}",
            p16Mine.errors[0].contains("id === 1"),
        )

        // ---- P20 DTO 属性在 test 中：真引擎判真展开 vs 自研判假不展开（求值器无 POJO 属性访问）----
        val p20 = known.getValue("P20")
        val p20Real = driver.render(p20.mapper, NAMESPACE, STATEMENT_ID, p20.realParams)
        assertEquals("P20 真引擎 SQL", "SELECT * FROM t AND name = ?", compact(p20Real.sql))
        assertEquals("P20 真引擎参数队列", listOf("u.name"), p20Real.parameterProperties)
        val p20Mine = mine(p20)
        assertEquals("P20 自研整段不展开", "SELECT * FROM t", compact(p20Mine.sql))
        assertEquals("P20 自研无参数", emptyList<String>(), p20Mine.mappings.map { it.property })
        assertTrue(
            "P20 无法静态读取 DTO 属性时必须显式警示：${p20Mine.errors}",
            p20Mine.errors.any { it.contains("u.name") && it.contains("无法静态读取对象属性") },
        )

        // ---- P30 `${}` 求值为空：SQL 两侧相同，自研多一条提示 errors ----
        val p30 = known.getValue("P30")
        val p30Real = driver.render(p30.mapper, NAMESPACE, STATEMENT_ID, p30.realParams)
        val p30Mine = mine(p30)
        assertEquals("P30 两侧 SQL 相同（真引擎）", "SELECT * FROM WHERE a = 1", compact(p30Real.sql))
        assertEquals("P30 两侧 SQL 相同（自研）", compact(p30Real.sql), compact(p30Mine.sql))
        assertEquals("P30 两侧参数队列相同", p30Real.parameterProperties, p30Mine.mappings.map { it.property })
        assertEquals("P30 自研多一条提示", 1, p30Mine.errors.size)
        assertTrue("P30 提示应说明求值为空：${p30Mine.errors}", p30Mine.errors[0].contains("求值为空"))
    }

    /**
     * P20 归因钉：自研求值器的属性访问只支持 `MockObject` / `Map` / `Collection` / `Array` / `CharSequence`
     * （`xml/Ognl.kt` 的 `property()`，其余载体 `else -> null`），**真实 DTO/POJO 实例的属性一律取不到**——
     * 这是 P20 两侧分叉的根因（不是探针构造问题，也不是真引擎行为差异）。
     *
     * 本钉不修缺陷，只把根因固化成可复跑证据：载体一换（DTO → MockObject）结果就翻转。
     */
    @Test
    fun `P20 归因：求值器属性访问的载体面不含 POJO`() {
        assertEquals(
            "DTO 载体：u.name 取不到 → null → 判假",
            EvalResult.Ok(false),
            OgnlEvaluator.evaluate("u.name != null", mapOf("u" to ProbeDto("a"))),
        )
        assertEquals(
            "MockObject 载体：u.name 取到 → 判真",
            EvalResult.Ok(true),
            OgnlEvaluator.evaluate("u.name != null", mapOf("u" to MockObject(mapOf("name" to "a")))),
        )
    }

    /**
     * 引擎版本钉住（T-TailSweep2 G1 补充）：对照集的价值取决于「对比的是哪个引擎版本」——
     * 真引擎 jar **不由本仓显式声明**，而是经 `testImplementation(libs.mp.core)`
     * （`com.baomidou:mybatis-plus-core:3.5.17`）传递带入 `org.mybatis:mybatis:3.5.19`，
     * 故版本漂移（mp-core 升级 → 传递 mybatis 变版）会**静默**改变对照结论。本断言把它变响亮。
     *
     * 期望值 [EXPECTED_ENGINE_VERSION] 与自研管线声称复刻的目标版本同源（`xml/XmlSqlRenderer.kt:52`
     * 的「复刻 MyBatis 3.5.19 `DynamicSqlSource.getBoundSql`」KDoc 字面量——生产侧无常量可引，
     * 此处显式钉住并在失败消息里交叉引用该处）。两路核验：jar 文件名 + 隔离 loader 载入类的
     * 包 `implementationVersion`（来自 jar 清单）。
     */
    @Test
    fun `引擎版本钉住：实际加载的真引擎 jar 版本与声称复刻的目标一致`() {
        val jarName = File(driver.mybatisJarPath).name
        val fromJarName = Regex("""mybatis-(\d+\.\d+\.\d+)\.jar""").find(jarName)?.groupValues?.get(1)
        assertEquals(
            "真引擎 jar 版本与自研管线声称复刻的目标版本不一致，对照结论需重新评估" +
                "（期望 $EXPECTED_ENGINE_VERSION，声称处 xml/XmlSqlRenderer.kt:52）：$jarName",
            EXPECTED_ENGINE_VERSION,
            fromJarName,
        )
        // 包版本来自 jar 清单 Implementation-Version（隔离 loader 从 jar 载入的类才有）；缺失时只按文件名核验并留痕
        val fromManifest = driver.loadIsolated(ISOLATED_PROBE_CLASS).`package`?.implementationVersion
        if (fromManifest == null) {
            println("[fidelity] jar 清单无 Implementation-Version，本次仅按文件名核验引擎版本：$jarName")
        } else {
            assertEquals(
                "隔离 loader 载入的引擎包版本与期望不一致（jar 名=$jarName）：对照结论需重新评估",
                EXPECTED_ENGINE_VERSION,
                fromManifest,
            )
        }
        println("[fidelity] 引擎 jar = ${driver.mybatisJarPath}（版本 $EXPECTED_ENGINE_VERSION，jar 名与包版本两路核验）")
    }

    /**
     * T-FCA-04 别名键集钉（N-32）：真引擎 `ParamNameResolver.wrapToMapIfCollection` 的**确切键集**。
     *
     * 为什么需要它：本驱动的真引擎侧直连 `MappedStatement.getBoundSql`（绕过 `MapperMethod`），
     * `ParamNameResolver` 的集合包装**不会自动发生**——裸 `List` 参数下真引擎取 `list` 键会直接抛错
     * （见下方反向读数），故凡依赖别名键的场景必须由测试侧调**引擎自身**的包装方法补齐。
     * 键集口径不照抄竞品转述，以此处实测为准（与 `mybatis-3.5.19-sources.jar` 的
     * `ParamNameResolver.wrapToMapIfCollection` 源码逐支一致）：
     *  - `List`（含 List 族）→ `collection` + `list`；
     *  - `Collection` 非 `List`（Set/Queue 族）→ 仅 `collection`（**无 `list`**）；
     *  - `T[]` → 仅 `array`（**无 `collection`**）；
     *  - 其余（Map / POJO / 标量）→ 原对象不包装（无别名键）；
     *  - 实参名（`useActualParamName=true` 时）与别名键**并存**。
     *
     * 自研侧对应实现：`ui/XmlParamTypeResolver.collectionAliasTypes`（门槛与键集须与此处一致）——
     * **本用例只钉真引擎侧键集**；我方注入的承重判据在 `heavy/HeavyXmlParamTypeResolverTest`
     * （见场景表 P31~P33 处注记）。
     */
    @Test
    fun `T-FCA 别名键集钉：真引擎 wrapToMapIfCollection 的逐形态键集`() {
        assertEquals(
            "List → collection + list + 实参名",
            setOf("collection", "list", "ids"),
            wrapKeys(listOf(1, 2), "ids"),
        )
        assertEquals(
            "Set（Collection 非 List）→ 仅 collection，无 list",
            setOf("collection", "ids"),
            wrapKeys(linkedSetOf(1, 2), "ids"),
        )
        assertEquals(
            "T[] → 仅 array，无 collection",
            setOf("array", "ids"),
            wrapKeys(arrayOf(1, 2), "ids"),
        )
        // 非集合参数：`wrapToMapIfCollection` 原样返回入参（引用同一，不新建 ParamMap）——
        // 故不能用「返回值是否为 Map」判定（Map 入参本身就返回 Map），须按引用判定
        val mapParam = linkedMapOf("a" to 1)
        assertSame("Map 参数原样返回（不包装）", mapParam, wrapToMapIfCollection(mapParam, "ids"))
        assertSame("标量参数原样返回（不包装）", "x", wrapToMapIfCollection("x", "ids"))
        assertEquals(
            "useActualParamName=false（实参名缺席）→ 仅 collection + list",
            setOf("collection", "list"),
            wrapKeys(listOf(1, 2), null),
        )

        // 反向读数（T-FCA-04「先红」侧证据，实测）：裸 List 参数（未经 ParamNameResolver 包装）下
        // collection="list" 在真引擎里取不到 —— 别名键**只**由 ParamNameResolver 包装产生。
        // 实测异常类 = java.lang.UnsupportedOperationException：集合参数在 DynamicContext 里被
        // MetaObject 包成 CollectionWrapper（其 get 一律抛 UnsupportedOperationException，不支持属性访问），
        // 非 Map 参数无 _parameter fallback（fallbackParameterObject=existsTypeHandler=ArrayList→false）。
        val bare = runCatching { driver.render(listForeachMapper(), NAMESPACE, STATEMENT_ID, listOf(1, 2)) }
        assertEquals(
            "裸 List 参数下真引擎应抛 UnsupportedOperationException（CollectionWrapper 不支持属性访问）",
            "java.lang.UnsupportedOperationException",
            (bare.exceptionOrNull() as? RealEngineException)?.causeClassName,
        )
        // 包装后同一 mapper 正常渲染 —— 同一输入、同一 mapper，差别只在包装这一步
        val wrapped = driver.render(listForeachMapper(), NAMESPACE, STATEMENT_ID, wrapToMapIfCollection(listOf(1, 2), "ids"))
        assertEquals("包装后真引擎 SQL", "SELECT * FROM t WHERE id IN ( ? , ? )", compact(wrapped.sql))
        assertEquals("包装后真引擎参数队列", listOf("__frch_id_0", "__frch_id_1"), wrapped.parameterProperties)
    }

    /**
     * T-REC-02 绑定基数三方对账（O-40 / Z-25）：一致面每条场景断言三个量全等——
     * **真引擎 `parameterProperties.size` == 我方 `mappings.size` == 我方串感知 `?` 位点数**。
     *
     * 三条量的推导链互相独立：真引擎侧经隔离 loader 走 `BoundSql.getParameterMappings()`（与我方渲染器
     * 无共享代码），我方两量一条来自渲染器累积的映射清单、一条来自渲染出的**文本**（`countPlaceholders`，
     * 复用产品侧 `log/LogEntryPreviewRecognizer.kt:100` 的串感知口径，测试与主代码同模块可达，
     * **未放宽任何产品代码可见性**）。故「`#{}` 只进 mappings 不进文本」或「`?` 进文本不进 mappings」
     * 任一方向的漂移都会被抓到——这正是 O-34 原有断言面（SQL 文本相等 + 属性名序列相等）**没有独立钉住**的维度。
     *
     * ## 前提 1 实测结论（本任务第一步，先证伪再动手）
     * 「真引擎参数条数恒等于 SQL 内 `?` 个数」**不成立**：两种**合法**形态下真引擎自身就不等
     * （实测 mybatis 3.5.19，读数见下条边界用例）——`${}` 直替出的值自身含 `?`（`props=0` / `?`=1）、
     * `#{}` 落在字符串字面量或 `--` 注释内（`props=2` / `?`=1）。故本对账**不是普适恒等式的断言**，
     * 而是「一致面 28 条场景内三方相等」的场景域断言；两种非恒等形态由紧随其后的边界用例按登记形态钉住。
     * 28 条一致面**全部**落在恒等域内（本用例实测，非推断）。
     *
     * ## T-REC-03：6 条已知差异为何不参与本对账
     * P0 / P14 / P15 / P16 / P20 / P30 是保真差异场景，其中 P20 两侧基数**本就不同**（真引擎 1 条 /
     * 自研 0 条，见「已知差异 6 条」用例），纳入「三方相等」等于要求它不成立。故它们按登记形态断言、
     * 排除在基数对账之外；下方**场景集不相交断言**兜底——已知差异场景被误搬进一致面即红，
     * 后人不会误以为漏做。口径同步见
     * `docs/调研报告/20260919-T-FidelityEval-自研与真引擎已知差异清单.md` §9「基数对账」。
     *
     * 失败报文含**场景 id**（照既有场景 id 陈述式命名，如 `P1 if Integer 0 / Map param`），
     * 且一次报出全部偏差，便于定位是哪条场景漂移。
     *
     * **T-SyncK2J 互操作注记**：k2j 侧 `countPlaceholders` 是 Java 静态方法且返回 `long`
     * （master 侧 Kotlin `internal fun` 返回 `Int`），Kotlin 的 `Long != Int` 是硬编译错误，
     * 故读取点显式 `.toInt()`（值域内无损），断言与报文与 master 逐字一致。
     */
    @Test
    fun `T-REC 基数对账：28 条一致面三方基数相等（真引擎条数 == 我方映射条数 == 串感知问号位点数）`() {
        val scenarios = equivalentScenarios()
        val known = knownDifferenceScenarios()

        // T-REC-03 兜底：已知差异场景不得进入基数对账域（集合不相交，误搬即红）
        val overlap = scenarios.map { it.id.substringBefore(' ') }.filter { it in known.keys }
        assertTrue(
            "已知差异场景不得进入基数对账（排除理由见本用例 KDoc）：$overlap",
            overlap.isEmpty(),
        )
        assertEquals("一致面场景数（T-FCA 追加 P31~P33 后）", 28, scenarios.size)
        assertEquals("已知差异场景数", 6, known.size)

        val mismatches = mutableListOf<String>()
        val readings = mutableListOf<String>()
        for (scenario in scenarios) {
            val real = runCatching { driver.render(scenario.mapper, NAMESPACE, STATEMENT_ID, scenario.realParams) }
            if (real.isFailure) {
                mismatches += "${scenario.id}: 真引擎意外抛错（一致面场景应两侧一致）——${real.exceptionOrNull()!!.message}"
                continue
            }
            val realCount = real.getOrThrow().parameterProperties.size
            val mine = mine(scenario)
            val mineCount = mine.mappings.size
            val placeholders = countPlaceholders(mine.sql).toInt()
            readings += "${scenario.id}: real=$realCount mine=$mineCount q=$placeholders"
            if (realCount != mineCount || mineCount != placeholders) {
                mismatches += "${scenario.id}: 基数不等（真引擎=$realCount / 我方映射=$mineCount / 位点数=$placeholders）" +
                    "\n    自研 sql=[${compact(mine.sql)}]" +
                    "\n    自研 props=${mine.mappings.map { it.property }}"
            }
        }
        assertTrue(
            "基数对账出现 ${mismatches.size} 条偏差（真引擎条数 / 我方映射条数 / 串感知位点数三者不全等）：\n" +
                mismatches.joinToString("\n"),
            mismatches.isEmpty(),
        )
        println("[cardinality] 三方基数对账 ${scenarios.size} 条全部相等：\n" + readings.joinToString("\n"))
    }

    /**
     * T-REC 前提 1 边界（真引擎侧）：`getParameterMappings()` 条数与串感知 `?` 位点数**不恒等**的两种合法形态。
     *
     * 实测（mybatis 3.5.19，隔离 loader 直连 `getBoundSql`）：
     * - `${}` 直替出的值自身含 `?` → `props=0` / `?`=1（`?` 由文本注入，非 `#{}` 产生）；
     * - `#{}` 落在字符串字面量或 `--` 注释内 → `props=2` / `?`=1（引擎的 `#{}` 识别**非串感知**，
     *   产出的 `?` 落进字面量/注释里，串感知计数不认它）。
     *
     * 两形态下我方与真引擎**完全一致**（文本与参数条数都相同）——这是「串感知计数」与「引擎非串感知
     * `#{}` 识别」两个口径的固有分歧，**不是保真差异，也不是待修缺陷**。此处按登记形态钉住：任一侧口径
     * 变化即红，强制同步差异清单 §9；我方侧的同一读数由 `RealEngineCardinalityTest` 的边界钉用例守护
     * （该类零真引擎依赖，无 jar 环境照常生效）。
     */
    @Test
    fun `T-REC 前提 1 边界：真引擎参数条数与串感知问号位点数不恒等的两种合法形态`() {
        // 形态 1：`${}` 直替出的值自身含 `?`
        val dollarMapper = doc(
            """<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM ${'$'}{cond}</select></mapper>""",
        )
        val dollarReal = driver.render(dollarMapper, NAMESPACE, STATEMENT_ID, mapOf("cond" to "t WHERE a = ?"))
        assertEquals("B1 真引擎文本（直替原文照进产物）", "SELECT * FROM t WHERE a = ?", compact(dollarReal.sql))
        assertEquals("B1 真引擎参数条数（直替注入的 `?` 不产生映射）", 0, dollarReal.parameterProperties.size)
        assertEquals("B1 串感知位点数认这个 `?`", 1, countPlaceholders(dollarReal.sql).toInt())

        // 形态 2：`#{}` 落在字符串字面量内 / `--` 注释内
        val literalMapper = doc(
            """<mapper namespace="n"><select id="s1" resultType="map">SELECT 'x#{a}' FROM t WHERE b = #{b}</select></mapper>""",
        )
        val literalReal = driver.render(literalMapper, NAMESPACE, STATEMENT_ID, mapOf("a" to 1, "b" to 2))
        assertEquals("B2 真引擎文本（`?` 落进字面量）", "SELECT 'x?' FROM t WHERE b = ?", compact(literalReal.sql))
        assertEquals("B2 真引擎参数条数", 2, literalReal.parameterProperties.size)
        assertEquals("B2 串感知位点数只认字面量外的 `?`", 1, countPlaceholders(literalReal.sql).toInt())

        val commentMapper = doc(
            "<mapper namespace=\"n\"><select id=\"s1\" resultType=\"map\">SELECT a FROM t -- #{c}\nWHERE b = #{b}</select></mapper>",
        )
        val commentReal = driver.render(commentMapper, NAMESPACE, STATEMENT_ID, mapOf("b" to 2, "c" to 3))
        assertEquals("B3 真引擎参数条数", 2, commentReal.parameterProperties.size)
        assertEquals("B3 串感知位点数只认注释外的 `?`", 1, countPlaceholders(commentReal.sql).toInt())

        // 两形态下我方与真引擎一致（非保真差异）——文本与参数条数两路对照
        val boundaryCases = listOf(
            BoundaryCase("B1", dollarMapper, mapOf<String, Any?>("cond" to "t WHERE a = ?")),
            BoundaryCase("B2", literalMapper, mapOf<String, Any?>("a" to 1, "b" to 2)),
            BoundaryCase("B3", commentMapper, mapOf<String, Any?>("b" to 2, "c" to 3)),
        )
        for (case in boundaryCases) {
            val real = driver.render(case.mapper, NAMESPACE, STATEMENT_ID, case.params)
            val mineSql = XmlSqlRenderer().render(
                XmlMapperParser().parse(case.mapper).statements.first().node,
                case.params + mapOf("_parameter" to case.params, "_databaseId" to null),
            )
            assertEquals("${case.id} 边界形态两侧文本应一致", compact(real.sql), compact(mineSql.sql))
            assertEquals(
                "${case.id} 边界形态两侧参数条数应一致（非保真差异）",
                real.parameterProperties.size,
                mineSql.mappings.size,
            )
        }
    }

    /** 前提 1 边界用例的输入（id + mapper + 真引擎实参）。 */
    private class BoundaryCase(val id: String, val mapper: String, val params: Map<String, Any?>)

    // ------------------------------------------------------------------
    // 场景表（34 条 = 28 条一致面 + 6 条已知差异，读数基线见差异清单文档）
    // ------------------------------------------------------------------

    /** 逐字节一致面（28 条）：P1~P13、P17~P19、P21~P29、P31~P33（T-FCA 别名三条）。 */
    private fun equivalentScenarios(): List<Scenario> = listOf(
        Scenario(
            "P1 if Integer 0 / Map param",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="count != 0"> AND c = #{count}</if></select></mapper>"""),
            mapOf("count" to 0), bindings("count" to 0),
        ),
        Scenario(
            "P2 if Integer 1",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="count != 0"> AND c = #{count}</if></select></mapper>"""),
            mapOf("count" to 1), bindings("count" to 1),
        ),
        Scenario(
            "P3 choose/when/otherwise",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<choose><when test="type == 1"> ORDER BY a</when><when test="type == 2"> ORDER BY b</when><otherwise> ORDER BY id</otherwise></choose></select></mapper>"""),
            mapOf("type" to 2), bindings("type" to 2),
        ),
        Scenario(
            "P4 where + set trim",
            doc("""<mapper namespace="n"><update id="s1">UPDATE t<set><if test="a != null">a = #{a},</if><if test="b != null">b = #{b},</if></set><where><if test="a != null">AND a = #{a}</if></where></update></mapper>"""),
            mapOf("a" to 1, "b" to null), bindings("a" to 1, "b" to null),
        ),
        Scenario(
            "P5 trim custom",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<trim prefix="WHERE" prefixOverrides="AND |OR " suffix="ORDER BY id"> AND x = #{x}</trim></select></mapper>"""),
            mapOf("x" to 1), bindings("x" to 1),
        ),
        Scenario(
            "P6 foreach List<Integer>",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE id IN<foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></select></mapper>"""),
            mapOf("ids" to listOf(1, 2, 3)), bindings("ids" to listOf(1, 2, 3)),
        ),
        Scenario(
            "P7 foreach DTO item props",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE<foreach collection="list" item="u" separator="OR"> u.name = #{u.name}</foreach></select></mapper>"""),
            mapOf("list" to listOf(ProbeDto("a"), ProbeDto("b"))),
            bindings("list" to listOf(ProbeDto("a"), ProbeDto("b"))),
        ),
        Scenario(
            "P8 foreach Map entry index=key",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE<foreach collection="m" index="k" item="v" separator="AND"> #{k} = #{v}</foreach></select></mapper>"""),
            mapOf("m" to linkedMapOf("a" to 1, "b" to 2)),
            bindings("m" to linkedMapOf("a" to 1, "b" to 2)),
        ),
        Scenario(
            "P9 nested foreach",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE<foreach collection="groups" item="g" open="(" separator=" OR " close=")">gid IN<foreach collection="g.ids" item="i" open="(" separator="," close=")">#{i}</foreach></foreach></select></mapper>"""),
            mapOf("groups" to listOf(mapOf("ids" to listOf(1, 2)), mapOf("ids" to listOf(3)))),
            bindings("groups" to listOf(mapOf("ids" to listOf(1, 2)), mapOf("ids" to listOf(3)))),
        ),
        Scenario(
            "P10 bind + like",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<bind name="like" value="'%' + name + '%'"/> WHERE name LIKE #{like}</select></mapper>"""),
            mapOf("name" to "x"), bindings("name" to "x"),
        ),
        Scenario(
            "P11 dollar direct",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM ${'$'}{table} WHERE c = #{c}</select></mapper>"""),
            mapOf("table" to "orders", "c" to 1), bindings("table" to "orders", "c" to 1),
        ),
        Scenario(
            "P12 static field @java.lang.Integer@MAX_VALUE",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE a = ${'$'}{@java.lang.Integer@MAX_VALUE}</select></mapper>"""),
            emptyMap<String, Any?>(), bindings(), mapOf("java.lang.Integer@MAX_VALUE" to Int.MAX_VALUE),
        ),
        Scenario(
            "P13 static field local class",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE a = ${'$'}{@cn.song.mybatisplus.visualizer.xml.fidelity.ProbeConsts@FLAG}</select></mapper>"""),
            emptyMap<String, Any?>(), bindings(), mapOf("cn.song.mybatisplus.visualizer.xml.fidelity.ProbeConsts@FLAG" to ProbeConsts.FLAG),
        ),
        Scenario(
            "P17 empty string truthiness",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="emptyStr"> AND x = 1</if></select></mapper>"""),
            mapOf("emptyStr" to ""), bindings("emptyStr" to ""),
        ),
        Scenario(
            "P18 or value semantics (a='' or b='x')",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="a or b"> AND x = 1</if></select></mapper>"""),
            mapOf("a" to "", "b" to "x"), bindings("a" to "", "b" to "x"),
        ),
        Scenario(
            "P19 and value semantics (a='' and b=null)",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="a and b"> AND x = 1</if></select></mapper>"""),
            mapOf("a" to "", "b" to null), bindings("a" to "", "b" to null),
        ),
        Scenario(
            "P21 pojo as _parameter",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="name != null"> AND name = #{name}</if></select></mapper>"""),
            ProbeDto("a"),
            mapOf("_parameter" to ProbeDto("a"), "_databaseId" to null, "name" to "a"),
        ),
        Scenario(
            "P22 empty collection foreach",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="ids != null"> WHERE id IN<foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></if></select></mapper>"""),
            mapOf("ids" to emptyList<Int>()), bindings("ids" to emptyList<Int>()),
        ),
        Scenario(
            "P23 escaped hash",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE c = \#{literal}</select></mapper>"""),
            emptyMap<String, Any?>(), bindings(),
        ),
        Scenario(
            "P24 include with property",
            doc("""<mapper namespace="n"><sql id="w"><if test="status != null">AND status = #{status}</if></sql><select id="s1" resultType="map">SELECT * FROM t<where><include refid="w"><property name="status" value="1"/></include></where></select></mapper>"""),
            mapOf("status" to "1"), bindings("status" to "1"),
        ),
        Scenario(
            "P25 foreach index on list",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE<foreach collection="ids" item="id" index="i" separator="OR"> (i = #{i} AND id = #{id})</foreach></select></mapper>"""),
            mapOf("ids" to listOf(7, 8)), bindings("ids" to listOf(7, 8)),
        ),
        Scenario(
            "P26 jdbcType attribute in token",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE a = #{a,jdbcType=INTEGER} AND b = #{b, javaType=java.lang.String}</select></mapper>"""),
            mapOf("a" to 1, "b" to "x"), bindings("a" to 1, "b" to "x"),
        ),
        Scenario(
            "P27 if null-safe == null",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="a == null"> WHERE a IS NULL</if></select></mapper>"""),
            mapOf("a" to null), bindings("a" to null),
        ),
        Scenario(
            "P28 if set/list size",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="ids != null and ids.size > 0"> AND id IN<foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></if></select></mapper>"""),
            mapOf("ids" to listOf(1)), bindings("ids" to listOf(1)),
        ),
        Scenario(
            "P29 nested property chain map",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="q.name != null"> AND n = #{q.name}</if></select></mapper>"""),
            mapOf("q" to linkedMapOf("name" to "z")), bindings("q" to linkedMapOf("name" to "z")),
        ),
        // ---- T-FCA（N-32）新增：foreach 集合快捷别名（ParamNameResolver.wrapToMapIfCollection）----
        // 真引擎侧吃**引擎自身**包装出的 ParamMap（[wrapToMapIfCollection]），自研侧 bindings 对齐同一键集；
        // 键集口径见 [别名键集钉] 用例（先跑该用例拿读数，再据实断言，不照抄竞品转述）。
        //
        // **判别力边界（T-FCA-04 复核补记，防「空绿」误读）**：P31~P33 自研侧 bindings 是**手工喂的**
        // 别名键（`bindings("collection" to …, "list" to …)`），钉的是「真引擎键集 + 自研 foreach 语义」，
        // **不覆盖我方 `XmlParamTypeResolver.collectionAliasTypes` 的别名注入**——摘掉注入后本三条仍绿
        // （实测）。结构原因：本类自研侧直连 `XmlSqlRenderer`（按类 KDoc「绕开面板 mock 层」），
        // 而面板侧 `XmlPreviewModel.mockBindings` 对 foreach 是**直接按 `collectionExpr` 建键**
        // （实测：paramTypes 为空时 `collection="list"` 仍渲染出 `IN ( ? , ? )`），故「渲染成功/逐字节相等」
        // 不构成注入的判据；注入的唯一可观测面是类型视图（`kindFor`/`quoteFor`）。
        // 我方注入的承重判据在 `heavy/HeavyXmlParamTypeResolverTest`：
        //  - `testCollectionAliasKeysByParamShape` / `testCollectionAliasAbsentWhenGateFails`（键集与门槛）；
        //  - `testCollectionAliasFeedsPreviewBindings`（注入进 paramTypes → 面板类型视图 kindFor/quoteFor）。
        Scenario(
            "P31 单 List 参数别名 collection=list",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE id IN<foreach collection="list" item="id" open="(" separator="," close=")">#{id}</foreach></select></mapper>"""),
            wrapToMapIfCollection(listOf(1, 2, 3), "ids"),
            bindings("collection" to listOf(1, 2, 3), "list" to listOf(1, 2, 3), "ids" to listOf(1, 2, 3)),
        ),
        Scenario(
            "P32 单 List 参数别名 collection=collection",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE id IN<foreach collection="collection" item="id" open="(" separator="," close=")">#{id}</foreach></select></mapper>"""),
            wrapToMapIfCollection(listOf(1, 2, 3), "ids"),
            bindings("collection" to listOf(1, 2, 3), "list" to listOf(1, 2, 3), "ids" to listOf(1, 2, 3)),
        ),
        Scenario(
            "P33 单 T[] 参数别名 collection=array",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE id IN<foreach collection="array" item="id" open="(" separator="," close=")">#{id}</foreach></select></mapper>"""),
            wrapToMapIfCollection(arrayOf(1, 2, 3), "ids"),
            bindings("array" to arrayOf(1, 2, 3), "ids" to arrayOf(1, 2, 3)),
        ),
        // T-FCA 新增场景到此为止，后续 T-REC 场景请加在其后
    )

    /** 已知差异面（6 条）：P0 / P14 / P15 / P16 / P20 / P30。 */
    private fun knownDifferenceScenarios(): Map<String, Scenario> = mapOf(
        "P0" to Scenario(
            "P0 no DOCTYPE",
            """<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE a = #{a}</select></mapper>""",
            mapOf("a" to 1), bindings("a" to 1),
        ),
        "P14" to Scenario(
            "P14 or-bars wrap",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="||flag||"> AND x = 1</if></select></mapper>"""),
            mapOf("flag" to true), bindings("flag" to true),
        ),
        "P15" to Scenario(
            "P15 invalid expr in if (top level)",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="a === b"> AND x = 1</if></select></mapper>"""),
            mapOf("a" to 1), bindings("a" to 1),
        ),
        "P16" to Scenario(
            "P16 invalid expr in foreach body if",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE id IN<foreach collection="ids" item="id" open="(" separator="," close=")"><if test="id === 1">#{id}</if></foreach></select></mapper>"""),
            mapOf("ids" to listOf(1, 2)), bindings("ids" to listOf(1, 2)),
        ),
        "P20" to Scenario(
            "P20 DTO property in test",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="u.name != null"> AND name = #{u.name}</if></select></mapper>"""),
            mapOf("u" to ProbeDto("a")), bindings("u" to ProbeDto("a")),
        ),
        "P30" to Scenario(
            "P30 dollar null value",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM ${'$'}{tbl} WHERE a = 1</select></mapper>"""),
            mapOf("tbl" to null), bindings("tbl" to null),
        ),
    )

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun mine(scenario: Scenario): RenderedXmlSql {
        val statement = XmlMapperParser().parse(scenario.mapper).statements.first()
        return XmlSqlRenderer().render(
            statement.node,
            scenario.bindings,
            emptyMap(), emptySet(), scenario.staticFieldValues,
        )
    }

    /**
     * T-FCA-04：调**真引擎自身**的 `ParamNameResolver.wrapToMapIfCollection`（隔离 loader 反射）。
     *
     * 类位置按 3.5.19 实测定为 `org.apache.ibatis.reflection.ParamNameResolver`（**不是**旧版的
     * `org.apache.ibatis.session.ParamNameResolver`，`javap` 已核）；方法是 `public static`，
     * 与 `getNamedParams` 的单参数支同款调用形态（实参名由调用方给，对应 `useActualParamName`）。
     * 返回的 `ParamMap`（`MapperMethod$ParamMap`，`extends HashMap`）可直接喂 `getBoundSql`——
     * 真引擎内部按 `java.util.Map` 判定，跨 loader 无碍。
     */
    private fun wrapToMapIfCollection(params: Any?, actualParamName: String?): Any? {
        val resolver = driver.loadIsolated("org.apache.ibatis.reflection.ParamNameResolver")
        val method = resolver.getMethod("wrapToMapIfCollection", Any::class.java, String::class.java)
        return method.invoke(null, params, actualParamName)
    }

    /** 包装结果若是 Map → 其键集；否则 null（原对象未被包装）。 */
    private fun wrapKeys(params: Any?, actualParamName: String?): Set<String>? =
        (wrapToMapIfCollection(params, actualParamName) as? Map<*, *>)?.keys?.map { it.toString() }?.toSet()

    /** T-FCA 键集钉与反向读数共用的 mapper：`collection="list"` 的 foreach。 */
    private fun listForeachMapper(): String = doc(
        """<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE id IN<foreach collection="list" item="id" open="(" separator="," close=")">#{id}</foreach></select></mapper>""",
    )

    private fun compact(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()

    /**
     * 自研侧 bindings 形态（对齐 `XmlPreviewModel.mockBindings` 的键形态，非面板 mock 值策略）：
     * 顶层变量 + `_parameter`（同值 Map）+ `_databaseId`。
     */
    private fun bindings(vararg pairs: Pair<String, Any?>): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        pairs.forEach { map[it.first] = it.second }
        map["_parameter"] = LinkedHashMap(map)
        map["_databaseId"] = null
        return map
    }

    /** 官方 DOCTYPE 头（真引擎 3.5.19 强制校验：无 DOCTYPE 直接 `BuilderException`，见 P0）。 */
    private fun doc(body: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n" +
            body

    private companion object {
        const val NAMESPACE = "n"
        const val STATEMENT_ID = "s1"

        /**
         * 期望的真引擎版本——与 `xml/XmlSqlRenderer.kt:52` 声称复刻的 MyBatis 版本同源（3.5.19）。
         * 该处是 KDoc 字面量、生产侧无常量可引，故在此显式钉住（漂移即红，见版本钉住用例）。
         */
        const val EXPECTED_ENGINE_VERSION = "3.5.19"

        /** 隔离 loader 包版本探测类（真引擎核心类；其 `Package` 版本来自 jar 清单）。 */
        const val ISOLATED_PROBE_CLASS = "org.apache.ibatis.session.Configuration"
    }
}

/** 对照场景（两侧同源输入：真引擎吃 [realParams]，自研吃 [bindings] + [staticFieldValues]）。 */
internal class Scenario(
    val id: String,
    val mapper: String,
    val realParams: Any?,
    val bindings: Map<String, Any?>,
    val staticFieldValues: Map<String, Any?> = emptyMap(),
)

/** 对照用 DTO（真实实例——真引擎经 OGNL 反射取属性，自研求值器见 P20 归因钉）。 */
internal class ProbeDto(val name: String)

/** 对照用常量类（P13 `@class@field` 静态字段引用的宿主类，真引擎从宿主 classpath 解析）。 */
internal object ProbeConsts {
    const val FLAG = "PROBE_FLAG"
}
