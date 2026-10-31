package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.mcp.McpToolGate
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plugin.xml（含三个 optional 配置文件）结构守卫（T-TestGuard-02）。
 *
 * 收录的每条规则都对应一个「只有真机/Marketplace 才暴露」的历史坑，剥注释后做负向断言，
 * 防止同族问题复发：
 *  - `<listener topic=`：plugin.xml 不支持该注册形态（ProjectManagerListener 等须 messageBus
 *    编程注册——全局平台陷阱清单）；
 *  - `<action` 缺 `class=`：整个插件无法加载（bug-fixes/project-plugin-xml-kotlin-optional.md）；
 *  - description 内嵌外链 `<img`：第三方图床在 Marketplace 会被 302 防盗链、图全挂
 *    （pitfalls/pitfall-gitee-hotlink-302.md）——2026-10-13 用户要求把文档站二维码卡片加到描述底部
 *    后，本规则收窄为「只允许自有文档站域名 sql-visualizer.jvtools.cn」，其余外链 `<img>` 仍禁；
 *  - T-PluginDescReshape（2026-10-08）新增描述结构四条：`<hr/>` 与 HTML 注释被 Marketplace 剥离
 *    （实测 tag 2026.10.7 本地有 hr、线上同版本 hr=0，中英两段粘连）、首 40 字符须英文摘要
 *    （预览卡文案位，官方 best-practices 硬要求）、数字与行为声明对账（11 方言 / 10 工具 /
 *    Alt+Shift+Q / since-build 版本标签）、双语同构且中文在前；
 *  - `projectConfigurable` 与父级同名套娃（bug-fixes/project-settings-no-nested-configurable.md）；
 *    T-SettingsTruth P4 起项目级设置页合法注册（parentId=tools 顶层），守卫收窄为
 *    「不得挂应用级 configurable 之下 + id 不与 applicationConfigurable 重复」；
 *  - `completion.contributor` 缺 `language=`：全语言注册不被调度、静默失效
 *    （bug-fixes/project-native-sql-highlighting.md，M34 补全失效根因之一）；
 *  - `notificationGroup` 带 `displayName=`：NotificationGroupEP 无该属性（243 javap 实证），
 *    分组显示名必须走 bundle + key；
 *  - `<toolWindow` 带 `title=`：不支持该属性（bug-fixes/toolwindow-button-tooltip-mechanism.md）；
 *  - 设置子页面 `applicationConfigurable` 缺 `groupWeight` 或非声明序递减：平台 Weighted.COMPARATOR
 *    按 weight 降序优先、displayName 自然比较兜底，缺 weight 时中文按码点序重排，中英文子页
 *    顺序不一致（2026-09-15 真机发现，243 源码 + 262 字节码双核实）。
 *
 * 纯逻辑测试：从 classpath 读 META-INF 下全部 xml，先剥注释再断言（plugin.xml 注释里有历史
 * 注册段残段，不剥会误报）。
 */
class PluginXmlStructureGuardTest {

    private val xmlNames = listOf(
        "plugin.xml", "kotlin-optional.xml", "database-optional.xml", "diagram-optional.xml",
        "mcp-optional.xml", "acp-transport-optional.xml",
    )

    private fun strippedXmls(): List<Pair<String, String>> = xmlNames.map { name ->
        val stream = javaClass.classLoader.getResourceAsStream("META-INF/$name")
            ?: error("资源缺失: META-INF/$name")
        val raw = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        name to raw.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }

    private fun tagsOf(xml: String, tag: String): List<String> =
        Regex("<$tag\\b[^>]*>").findAll(xml).map { it.value }.toList()

    private fun assertHolds(description: String, condition: Boolean) =
        assertTrue(description, condition)

    // ------------------------------------------------------------------
    // 注册形态守卫
    // ------------------------------------------------------------------

    @Test
    fun `不存在listener_topic注册形态`() {
        for ((name, xml) in strippedXmls()) {
            assertHolds(
                "$name 含 <listener topic=…>（平台不支持，监听须 messageBus 编程注册）",
                !xml.contains("<listener topic="),
            )
        }
    }

    @Test
    fun `action元素必须带class属性`() {
        for ((name, xml) in strippedXmls()) {
            val bad = tagsOf(xml, "action").filter { !it.contains("class=") }
            assertHolds("$name 存在缺 class 属性的 <action>（整插件加载失败）：$bad", bad.isEmpty())
        }
    }

    /**
     * 描述内嵌 `<img>` 的准入＝**只允许自有文档站域名**（`sql-visualizer.jvtools.cn`）。
     *
     * 原规则禁一切外链 `<img>`，依据是 pitfalls/pitfall-gitee-hotlink-302 的实测：第三方图床在
     * Marketplace 会被 302 防盗链、图全挂。2026-10-13 用户要求把文档站的两张二维码卡片
     * （作者微信 / 交流群）加到描述底部、并明确「用链接引用本站图片」，故按域名准入收窄：
     * 本站域名（自有 Cloudflare Pages，非第三方图床）放行，其余外链 `<img>` 一律仍禁。
     * **平台侧残余风险**：描述区外链图在 Marketplace 可能仍被 302 挡住不显示（Gitee 与本地看
     * plugin.xml 不受影响），已与用户确认按此形态落地。
     */
    @Test
    fun `description内嵌img仅限自有文档站域名`() {
        val allowedPrefix = "https://sql-visualizer.jvtools.cn/"
        for ((name, xml) in strippedXmls()) {
            val desc = xml.substringAfter("<description", "").substringBefore("</description>")
            val offenders = Regex("""<img\b[^>]*>""").findAll(desc)
                .map { it.value }
                .filterNot { it.contains("src=\"$allowedPrefix") }
                .toList()
            assertHolds(
                "$name description 内嵌了非自有域名的 <img>（第三方图床在 Marketplace 被 302 防盗链图全挂；" +
                    "只允许 src=\"$allowedPrefix…\"）：$offenders",
                offenders.isEmpty(),
            )
        }
    }

    // ------------------------------------------------------------------
    // 描述结构守卫（T-PluginDescReshape）：平台剥离面 / 预览卡摘要 / 声明对账 / 双语同构
    // ------------------------------------------------------------------

    /**
     * 描述不得含 `<hr/>` 与 HTML 注释——**实测 Marketplace 两者都会剥离**：
     * 本地 tag `2026.10.7` 的 plugin.xml 描述里含 `<hr/>`，而 Marketplace API
     * （`GET /api/plugins/33633`）返回的同版本描述里 `<hr` 出现 0 次、注释同样消失，
     * 结果中英两段直接首尾粘连。分隔改用 `<h3>` 分节标题（线上实测保留）。
     *
     * 断言取自**未剥注释**的原始资源：本类 [strippedXmls] 会先剥注释，用它会把这
     * 条规则变成恒真（空绿），故单列 [rawDescription]。
     */
    @Test
    fun `描述不得含hr分隔线与HTML注释`() {
        val desc = rawDescription()
        assertHolds(
            "描述含 <hr/>（Marketplace 剥离 → 中英两段粘连；分隔请用 <h3> 分节标题）",
            !desc.contains("<hr"),
        )
        assertHolds(
            "描述含 HTML 注释（Marketplace 同样剥离，写了也不会显示）",
            !desc.contains("<!--"),
        )
    }

    /**
     * 描述首 40 字符须是英文短摘要：Marketplace 用前 40 字符做预览卡文案且**不可单独修改**
     * （官方 best-practices-for-listing「Tips and Tricks」原文），故顶部摘要行必须由英文承载。
     */
    @Test
    fun `描述首40字符须为英文摘要`() {
        val plain = rawDescription().replace(Regex("<[^>]*>"), "").trim()
        val head = plain.take(40)
        assertHolds(
            "描述首 40 字符须为英文可打印字符（预览卡文案位），实际：$head",
            head.length == 40 && head.all { it.code in 0x20..0x7E },
        )
    }

    /**
     * 描述里的数字与行为类声明跟代码对账：方言数、MCP 工具数、默认键位、最低支持版本。
     * 四项都是「代码一改、描述静默过期」的高危面（加方言 / 加工具 / 换键位 / 抬 since-build）。
     */
    @Test
    fun `描述声明与代码对账`() {
        val desc = rawDescription()
        val pluginXml = strippedXmls().first { it.first == "plugin.xml" }.second

        val dialects = DialectRegistry.dialects.size
        assertDeclaredNumber(desc, Regex("""(\d+)\s+database dialects"""), dialects, "英文段数据库方言数")
        assertDeclaredNumber(desc, Regex("""(\d+)\s*种数据库方言"""), dialects, "中文段数据库方言数")

        val tools = McpToolGate.TOOL_NAMES.size
        assertDeclaredNumber(desc, Regex("""(\d+)\s+deterministic"""), tools, "英文段 MCP 工具数")
        assertDeclaredNumber(desc, Regex("""(\d+)\s*个确定性"""), tools, "中文段 MCP 工具数")

        // 键位：描述宣传的快捷键须与 ConvertToSql 注册的默认键一致（换键位时描述会红）
        val convertBlock = actionBlock(pluginXml, "cn.song.mybatisplus.visualizer.ConvertToSql")
        val keystroke = Regex("""first-keystroke\s*=\s*"([^"]+)"""").find(convertBlock)?.groupValues?.get(1)
        if (keystroke != null) {
            val label = keystroke.split(" ").joinToString("+") { part -> part.replaceFirstChar { it.uppercase() } }
            assertHolds("描述须使用 ConvertToSql 的默认键位 $label（当前描述未见）", desc.contains(label))
        } else {
            assertHolds("ConvertToSql 已无默认键位，描述不得再宣传 Alt+Shift+Q", !desc.contains("Alt+Shift+Q"))
        }

        // 最低支持版本：since-build 前缀 → 版本号标签，二者必须同源（抬版本而描述不跟着改会红）
        val sinceBuild = Regex("""since-build\s*=\s*"(\d+)"""").find(pluginXml)?.groupValues?.get(1)
        val versionLabel = mapOf(
            "243" to "2024.3", "251" to "2025.1", "252" to "2025.2", "253" to "2025.3",
            "261" to "2026.1", "262" to "2026.2",
        )[sinceBuild]
        assertHolds("plugin.xml 未声明可解析的 since-build（对账表须补该构建号）", versionLabel != null)
        assertHolds("描述须写明最低支持版本 $versionLabel（since-build=$sinceBuild）", desc.contains(versionLabel!!))
    }

    /**
     * 双语同构 + 中文在上：`<h3>中文</h3>` 与 `<h3>English</h3>` 两节都须在，
     * 中文节在前（用户 2026-10-08 定案），且两侧 `<li>` 条数相等（防只改一侧的静默漂移）。
     */
    @Test
    fun `描述双语同构且中文在前`() {
        val desc = rawDescription()
        val zhMark = "<h3>中文</h3>"
        val enMark = "<h3>English</h3>"
        val zhIndex = desc.indexOf(zhMark)
        val enIndex = desc.indexOf(enMark)
        assertHolds("描述须有 $zhMark 分节", zhIndex >= 0)
        assertHolds("描述须有 $enMark 分节", enIndex >= 0)
        assertHolds("中文节须在英文节之上（T-PluginDescReshape 结构定案）", zhIndex in 0 until enIndex)

        val zhItems = desc.substring(zhIndex, enIndex).split("<li>").size - 1
        val enItems = desc.substring(enIndex).split("<li>").size - 1
        assertHolds(
            "中英条目数须相等且非零（防单边改一条）：中文 $zhItems / 英文 $enItems",
            zhItems > 0 && zhItems == enItems,
        )
    }

    @Test
    fun `不存在projectConfigurable注册`() {
        // T-SettingsTruth P4：项目级设置页已合法注册（parentId=tools 顶层，与全局页平级）。
        // 保留守卫但收窄为「防套娃」：projectConfigurable 不得挂在应用级 configurable
        // （id=cn.song.mybatisplus.visualizer.settings）之下——平台限制，项目级节点挂应用级
        // configurable 之下不渲染（bug-fixes/project-settings-no-nested-configurable.md）。
        for ((name, xml) in strippedXmls()) {
            val bad = tagsOf(xml, "projectConfigurable").filter {
                it.contains("""parentId="cn.song.mybatisplus.visualizer.settings"""")
            }
            assertHolds(
                "$name 存在挂在应用级 configurable 之下的 projectConfigurable（防套娃；" +
                    "项目级须挂 tools 顶层与全局页平级）：$bad",
                bad.isEmpty(),
            )
            // 项目级注册与全局页 id 不得重复
            val projIds = tagsOf(xml, "projectConfigurable")
                .mapNotNull { Regex("""id="([^"]+)"""").find(it)?.groupValues?.get(1) }
            val appIds = tagsOf(xml, "applicationConfigurable")
                .mapNotNull { Regex("""id="([^"]+)"""").find(it)?.groupValues?.get(1) }
            val dup = projIds.intersect(appIds.toSet())
            assertHolds("$name projectConfigurable 与 applicationConfigurable id 重复：$dup", dup.isEmpty())
        }
    }

    @Test
    fun `项目级设置页已注册且挂tools顶层`() {
        // T-SettingsTruth P4 接线守卫：ProjectPluginSettingsConfigurable 必须注册为
        // projectConfigurable 且 parentId=tools（防回归为死类）
        val xml = strippedXmls().first { it.first == "plugin.xml" }.second
        val proj = tagsOf(xml, "projectConfigurable").filter {
            it.contains("""instance="cn.song.mybatisplus.visualizer.config.ProjectPluginSettingsConfigurable"""")
        }
        assertHolds("项目级设置页 projectConfigurable 注册数应为 1，实际 ${proj.size}", proj.size == 1)
        assertHolds(
            "项目级设置页必须挂 tools 顶层（不得挂应用级 configurable 之下）",
            proj[0].contains("""parentId="tools""""),
        )
        assertHolds("项目级设置页须声明 bundle+key 本地化", proj[0].contains("bundle=") && proj[0].contains("key="))
    }

    @Test
    fun `inlayProvider必须声明language与implementationClass`() {
        // T-InsertColumnInlay O-44b：inlay EP 的 bean（InlayHintsProviderExtensionBean）把
        // language 与 implementationClass 都标为 @RequiredElement（243 sources 逐行核实）——
        // 缺任一属性即插件加载报错；实现类写错则 EP 的 language 级作用面全乱。
        val xml = strippedXmls().first { it.first == "plugin.xml" }.second
        val tags = tagsOf(xml, "codeInsight.inlayProvider")
        assertHolds("inlayProvider 注册数应为 1，实际 ${tags.size}", tags.size == 1)
        val tag = tags[0]
        assertHolds("inlayProvider 缺 language 属性（@RequiredElement，缺失即加载报错）：$tag", tag.contains("language="))
        assertHolds(
            "inlayProvider 缺 implementationClass 属性（@RequiredElement）：$tag",
            tag.contains("implementationClass="),
        )
        assertHolds(
            "inlayProvider 实现类应为预览面板的 INSERT 目标列 provider：$tag",
            tag.contains("PreviewInsertColumnInlayProvider"),
        )
    }

    @Test
    fun `completion_contributor必须声明language属性`() {
        for ((name, xml) in strippedXmls()) {
            val bad = tagsOf(xml, "completion.contributor").filter { !it.contains("language=") }
            assertHolds(
                "$name 存在缺 language 属性的 completion.contributor（全语言注册不被调度、静默失效）：$bad",
                bad.isEmpty(),
            )
        }
    }

    @Test
    fun `notificationGroup不得带displayName属性`() {
        for ((name, xml) in strippedXmls()) {
            val bad = tagsOf(xml, "notificationGroup").filter { it.contains("displayName=") }
            assertHolds("$name notificationGroup 带 displayName（该属性不存在，显示名走 bundle+key）：$bad", bad.isEmpty())
        }
    }

    @Test
    fun `toolWindow不得带title属性`() {
        for ((name, xml) in strippedXmls()) {
            val bad = tagsOf(xml, "toolWindow").filter { it.contains("title=") }
            assertHolds("$name toolWindow 带 title 属性（平台不支持）：$bad", bad.isEmpty())
        }
    }

    @Test
    fun `设置子页面groupWeight按声明序严格递减`() {
        val xml = strippedXmls().first { it.first == "plugin.xml" }.second
        val children = tagsOf(xml, "applicationConfigurable")
            .filter { it.contains("""parentId="cn.song.mybatisplus.visualizer.settings"""") }
        assertHolds(
            "SQL Visualizer 子设置页注册数应为 9（主五页 + shortcuts/mcp + bannedApi + 外部模板，" +
                "T-ShortcutSettings/T-BannedApiCheck/T-TemplateHook），实际 ${children.size}",
            children.size == 9,
        )
        val weights = children.map { tag ->
            val id = Regex("""id="([^"]+)"""").find(tag)!!.groupValues[1]
            val w = Regex("""groupWeight="(\d+)"""").find(tag)?.groupValues?.get(1)?.toIntOrNull()
            assertHolds(
                "子设置页 $id 缺 groupWeight（平台按 displayName 自然比较兜底排序，中文名码点序" +
                    "与英文不一致导致中英文子页顺序错乱）",
                w != null,
            )
            id to w!!
        }
        for (i in 1 until weights.size) {
            val (prevId, prevW) = weights[i - 1]
            val (id, w) = weights[i]
            assertHolds(
                "子设置页 groupWeight 须按声明序严格递减（weight 降序=树中显示顺序）：$prevId=$prevW 应大于 $id=$w",
                prevW > w,
            )
        }
    }

    // ------------------------------------------------------------------
    // 存在性守卫（防整体误删）
    // ------------------------------------------------------------------

    @Test
    fun `主描述文件保留核心注册锚点`() {
        val xml = strippedXmls().first { it.first == "plugin.xml" }.second
        assertTrue("applicationConfigurable 注册不应丢失", xml.contains("applicationConfigurable"))
        assertTrue("可选 Kotlin 依赖声明不应丢失", xml.contains("supportsKotlinPluginMode"))
        assertTrue("errorHandler EP 不应丢失（Internal Errors 上报按钮）", xml.contains("<errorHandler"))
    }

    // ------------------------------------------------------------------
    // T-McpToolset：MCP 可选依赖结构守卫
    // ------------------------------------------------------------------

    @Test
    fun `mcp可选依赖声明与动态注册入口成对存在`() {
        // T-McpDynamicToolset（20260926）：十 toolset 的 XML 声明退役（implementation 类 implements
        // 平台接口，静态引用在 243/251 被 verifier 判未解析类——Marketplace 审核拒绝主因），改由
        // McpToolsetDynamicRegistrar 运行期注册。守卫反转：XML 不得再出现 toolset 声明（回归即红），
        // 注册入口 postStartupActivity 必须在册。
        val pluginXml = strippedXmls().first { it.first == "plugin.xml" }.second
        val mcpXml = strippedXmls().first { it.first == "mcp-optional.xml" }.second
        assertHolds(
            "plugin.xml 缺 mcpServer 可选依赖声明（MCP 工具注册失效）",
            pluginXml.contains("""<depends optional="true" config-file="mcp-optional.xml">com.intellij.mcpServer</depends>"""),
        )
        assertHolds(
            "mcp-optional.xml 缺动态注册入口 McpGateFilterRegistrar（postStartupActivity；T-McpDynamicToolset）",
            mcpXml.contains("cn.song.mybatisplus.visualizer.mcp.McpGateFilterRegistrar"),
        )
        assertHolds(
            "mcp-optional.xml 出现 mcpServer.mcpToolset XML 声明（T-McpDynamicToolset 已退役该形态，" +
                "静态引用会 reintroduce 243/251 compatibility problems——工具注册改走动态壳）",
            !mcpXml.contains("mcpServer.mcpToolset"),
        )
    }

    @Test
    fun `主描述文件不得直接引用mcpserver类型`() {
        // 可选依赖铁律（M17 database-optional 同款）：主描述文件不得直接引用可选插件类型/注册——
        // mcpServer 缺席（243/未装）时整份主文件仍须可加载，mcp 引用只允许出现在 mcp-optional.xml
        val pluginXml = strippedXmls().first { it.first == "plugin.xml" }.second
        assertHolds(
            "plugin.xml 主文件出现 mcpToolset/mcpServer EP 注册（须收敛进 mcp-optional.xml）",
            !pluginXml.contains("mcpToolset"),
        )
    }

    // ------------------------------------------------------------------
    // T-AcpMcpTransport：AI Assistant（com.intellij.ml.llm）可选依赖结构守卫
    // ------------------------------------------------------------------

    @Test
    fun `acp可选依赖声明与传输provider注册成对存在`() {
        // 可选依赖铁律：声明与注册必须成对——只声明不注册=provider 不上线（绕行失效且无任何提示），
        // 只注册不声明=未装 AI Assistant 的环境解析该文件即失败（缺类/未知 EP）。
        // T-MarketCompatProxy（20260923）：provider 从 XML EP 声明改为 postStartupActivity
        // 探测式程序化注册（动态代理形态，字节码零 ml-llm 类型引用）——成对判据同步为
        // 「optional depends 声明 + registrar 类挂载」。
        val pluginXml = strippedXmls().first { it.first == "plugin.xml" }.second
        val acpXml = strippedXmls().first { it.first == "acp-transport-optional.xml" }.second
        assertHolds(
            "plugin.xml 缺 com.intellij.ml.llm 可选依赖声明（ACP 传输绕行失效）",
            pluginXml.contains(
                """<depends optional="true" config-file="acp-transport-optional.xml">com.intellij.ml.llm</depends>""",
            ),
        )
        assertHolds(
            "acp-transport-optional.xml 缺传输注册入口（postStartupActivity 探测式注册）",
            acpXml.contains("cn.song.mybatisplus.visualizer.acp.AcpTransportRegistrar"),
        )
    }

    @Test
    fun `主描述文件不得直接引用ml-llm类型`() {
        // 与 mcpserver 同款铁律：主文件不得引用可选插件类型（插件 id 的 <depends> 声明不在此列）
        val pluginXml = strippedXmls().first { it.first == "plugin.xml" }.second
        assertHolds(
            "plugin.xml 主文件出现 ml-llm 类包引用（须收敛进 acp-transport-optional.xml）",
            !pluginXml.contains("com.intellij.ml.llm.agents.acp"),
        )
    }

    // ------------------------------------------------------------------
    // T-TemplateKeyDrop：快捷模板三动作默认键位摘除守卫
    // ------------------------------------------------------------------

    @Test
    fun `快捷模板三动作不得带默认键位`() {
        // T-TemplateKeyDrop 定案：三动作不设默认键位。原注册 Alt+Shift+E/C/T 与平台动作冲突
        // （Alt+Shift+C=RecentChanges、Alt+Shift+T=tasks.switch），而备选键 Ctrl+Alt+Shift+同字母
        // 同样被占（Copy Reference / Refactor This）——「冲突自动换绑」无解，且换绑只加不摘、
        // 即便成功也解决不了冲突（实测与键位表见开发计划 §一）。重新加回键位即红。
        val xml = strippedXmls().first { it.first == "plugin.xml" }.second
        val actionIds = listOf(
            "cn.song.mybatisplus.visualizer.ExplainSelectedTable",
            "cn.song.mybatisplus.visualizer.CountSelectedTable",
            "cn.song.mybatisplus.visualizer.SampleSelectedTable",
        )
        for (id in actionIds) {
            assertHolds(
                "$id 重新带上了默认键位（与平台 RecentChanges / tasks.switch 冲突，备选键亦被占）",
                !actionBlockHasKeyboardShortcut(xml, id),
            )
            assertHolds(
                "plugin.xml 缺三动作注册之一（$id）——守卫失去意义，先修注册",
                actionBlockExists(xml, id),
            )
        }
        assertHolds(
            "plugin.xml 仍注册 SqlTemplateShortcutConflictCheck（该类已随默认键位一并删除）",
            !xml.contains("SqlTemplateShortcutConflictCheck"),
        )
    }

    // ------------------------------------------------------------------
    // 守卫自检（独立复核㉗：本类此前以空 decisionFns 登记，判定函数与同性质助手
    // tagsOf/assertHolds 完全不进登记面——与 BannedApiSourceScanTest 逐条登记站点提取器
    // 的口径不一致。现逐条登记，并由下面两个自证用例提供语料）
    // ------------------------------------------------------------------

    /**
     * 扫描自检：资源夹具健全 + 剥注释无残留 + 站点提取器非空——三条断言都落在**真实资源**上，
     * 资源名漂移 / 剥注释失效 / 提取器退化成空面时本用例红，本类其余用例不会因扫描面为空而空绿。
     */
    @Test
    fun `扫描自检_资源夹具健全且剥注释无残留`() {
        val xmls = strippedXmls()
        assertHolds("六份 xml 资源必须全部加载到，实际 ${xmls.size} 份", xmls.size == xmlNames.size)
        xmls.forEach { (name, xml) ->
            assertHolds("$name 剥注释后不得残留注释开标记（否则注释里的历史注册段会被当注册形态断言）", !xml.contains("<!--"))
            assertHolds("$name 剥注释后正文不得为空", xml.isNotBlank())
        }
        val actions = tagsOf(xmls.first { it.first == "plugin.xml" }.second, "action")
        assertTrue("plugin.xml 的 action 标签提取数应非零（空面会让全部标签形态断言空转），实际 ${actions.size}", actions.isNotEmpty())
        assertTrue("提取器须返回标签本身而非整段：${actions.first()}", actions.all { it.startsWith("<action") })
    }

    /**
     * 合成 XML 自证：两个判定函数三态各归其位——带键位必报 / **自闭合动作不得被算成带键位** /
     * 未注册 id 不得被判存在。第二条即本类 KDoc（见 `actionBlockHasKeyboardShortcut` 注释）记载的
     * 2026-09-21 变异实验形态（用 `<action …>(.*?)</action>` 取块会把后一个动作的键位算到前一个头上），
     * 此处把它从「注释里的历史教训」变成合成样本自证。
     */
    @Test
    fun `自证_合成XML带键位必报_自闭合动作不得误判`() {
        val withShortcut = """<action id="A" class="X"><keyboard-shortcut first-keystroke="alt shift E"/></action>"""
        assertTrue("带 keyboard-shortcut 的动作必须被判为带键位", actionBlockHasKeyboardShortcut(withShortcut, "A"))
        assertTrue("已注册动作必须被判存在", actionBlockExists(withShortcut, "A"))

        val selfClosing = """<action id="B" class="Y"/><action id="C" class="Z"><keyboard-shortcut first-keystroke="alt shift T"/></action>"""
        assertTrue(
            "自闭合动作不得被判为带键位（否则后一个动作的键位会串到它头上）",
            !actionBlockHasKeyboardShortcut(selfClosing, "B"),
        )
        assertTrue("自闭合动作本身仍是「已注册」", actionBlockExists(selfClosing, "B"))
        assertTrue("其后动作的键位仍须认出（自闭合短路不得吃掉后续块）", actionBlockHasKeyboardShortcut(selfClosing, "C"))

        assertTrue("未注册的动作 id 不得被判存在", !actionBlockExists(withShortcut, "NOPE"))
        assertTrue("未注册的动作 id 不得被判带键位", !actionBlockHasKeyboardShortcut(withShortcut, "NOPE"))
    }

    /**
     * 描述段原文（含 CDATA 包裹），取自**未剥注释**的原始资源——hr/注释两条规则要看得见它们。
     * 注意 classpath 上这份 plugin.xml 是构建期重写过的版本（注入 change-notes），
     * 但描述 CDATA 与注释均逐字保留（20261008 实测 build/resources/main 副本 desc 含 hr）。
     */
    private fun rawDescription(): String {
        val raw = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("资源缺失: META-INF/plugin.xml")
        return raw.substringAfter("<description", "").substringBefore("</description>")
    }

    /** 描述里的数字声明与代码计数对账：匹配不到 / 数字不等都判红（缺声明即为漂移面）。 */
    private fun assertDeclaredNumber(desc: String, pattern: Regex, expected: Int, label: String) {
        val matched = pattern.find(desc)?.groupValues?.get(1)
        assertHolds("$label：描述未声明（模式 ${pattern.pattern}），代码实际 $expected", matched != null)
        assertHolds("$label：描述声明 $matched，代码实际 $expected", matched == expected.toString())
    }

    /** 取动作块文本（自闭合动作返回空串）：描述对账里读取默认键位用。 */
    private fun actionBlock(xml: String, actionId: String): String {
        val tag = Regex("""<action\b[^>]*\bid\s*=\s*"$actionId"[^>]*?(/)?>""").find(xml) ?: return ""
        if (tag.groupValues[1] == "/") return ""
        return xml.substring(tag.range.last + 1).substringBefore("</action>")
    }

    /**
     * 取 action 块的「是否含子元素」判据（而非裸正则匹配整段）：
     *
     * classpath 上的 plugin.xml 是**构建期重写过的**版本（注入 change-notes + 属性压成一行），
     * 其中无子元素的 action 被写成**自闭合** `<action … />`。若用 `<action …>(.*?)</action>`
     * 取块，自闭合动作的「块」会一路吞到**下一个** `</action>`，于是把后一个动作的键位算到
     * 前一个头上（2026-09-21 变异实验实测：把键位加回 Count，报错点名 Explain）。
     * 故先判自闭合（自闭合=无子元素，必然无键位），非自闭合才取到首个 `</action>`。
     */
    private fun actionBlockHasKeyboardShortcut(xml: String, actionId: String): Boolean {
        val tag = Regex("""<action\b[^>]*\bid\s*=\s*"$actionId"[^>]*?(/)?>""").find(xml) ?: return false
        if (tag.groupValues[1] == "/") return false
        return xml.substring(tag.range.last + 1).substringBefore("</action>").contains("<keyboard-shortcut")
    }

    /** 三动作注册存在性（自闭合与非自闭合都算存在）。 */
    private fun actionBlockExists(xml: String, actionId: String): Boolean =
        Regex("""<action\b[^>]*\bid\s*=\s*"$actionId"[^>]*?>""").containsMatchIn(xml)
}
