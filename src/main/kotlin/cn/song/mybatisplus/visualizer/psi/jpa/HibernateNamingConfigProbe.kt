package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.TextOccurenceProcessor
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiModificationTracker
import java.util.concurrent.ConcurrentHashMap

/**
 * 工程 Hibernate 命名约定探测（T-EmbeddedColPrefix 方案 A / T-JarEmbedded W1）。
 *
 * 实体本身不携带「嵌入列名怎么拼」这一信息——它由 Hibernate 的隐式命名策略决定
 * （见 [EmbeddedColumnNaming]）。本对象从工程里找这个信号。
 *
 * **扫描面分两档**（精度与覆盖的取舍，20261016 补第二档）：
 *  - **模块已知**（实体文件在工程源码里，能自路径上溯找到最近的 `pom.xml` / `build.gradle*`）：
 *    只看该模块 `src/main/resources`（含 `META-INF/`）下的命名配置文件。命名策略是**每个持久化
 *    单元**的事，按模块取范围可防兄弟模块配置外溢（demo 仓同时放「无前缀」与「组件路径」两套语料
 *    时必然踩到，20261013 修正）。
 *  - **模块不可知**（实体来自**依赖 JAR**——条目路径不是本地文件，上溯找不到模块标记；或工程布局
 *    认不出）：退到**工程级信号**，两类都看：
 *      1. 配置文件：`application*.{yml,yaml,properties}` + JPA/Hibernate 标准文件
 *        （`persistence.xml` / `orm.xml` / `hibernate.cfg.xml`，常直接写
 *        `<property name="hibernate.implicit_naming_strategy" value="component-path"/>`）；
 *      2. **源码里的代码式配置**：用词索引在工程源码中找命名策略类名
 *        （`props.put("hibernate.implicit_naming_strategy", ImplicitNamingStrategyComponentPathImpl.class)`
 *        这类写法在 `application*.yml` 里根本看不见——这是 A 此前最大的盲区，也是「实体在 JAR 里」
 *        场景下用户报障的直接嫌疑）。
 *
 * 其它设计取舍：
 *  - **只认强信号**：匹配命名策略类名/关键字，不做「看着像」的模糊判定。判不出返回 false，
 *    由设置项兜底（[EmbeddedColumnNamingResolver]）——宁可漏判（用户可显式开）也不误判；
 *  - **纯文本判定与取文件分离**：[indicatesComponentPath] 与 [nearestModuleRootPath] 都不碰
 *    平台 API，可脱离工程单测；
 *  - **按 PSI 版本 + 扫描范围缓存**：实体解析会被反复触发，每次读配置/搜索引不可接受。
 */
object HibernateNamingConfigProbe {

    /**
     * 组件路径命名策略的识别标记（全名短名 + Hibernate 配置关键字写法）。
     *
     * `ImplicitNamingStrategyComponentPathImpl` 是 Hibernate 5/6 的实现类；
     * `DefaultComponentSafeNamingStrategy` 是它移植前的 Hibernate 4 源类（老工程仍可能配这个名字）。
     */
    internal val COMPONENT_PATH_MARKERS = listOf(
        "ImplicitNamingStrategyComponentPathImpl",
        "DefaultComponentSafeNamingStrategy",
        "component-path",
        "component_path",
    )

    /** 源码式配置的识别词（走词索引，按类名精确匹配）。 */
    private val COMPONENT_PATH_CLASS_NAMES = listOf(
        "ImplicitNamingStrategyComponentPathImpl",
        "DefaultComponentSafeNamingStrategy",
    )

    /** Spring Boot 配置文件主名前缀。 */
    private const val CONFIG_FILE_PREFIX = "application"

    /** Spring Boot 配置文件扩展名。 */
    private val CONFIG_EXTENSIONS = setOf("yml", "yaml", "properties")

    /** JPA/Hibernate 标准配置文件（模块内扫描用；工程级另走设置键词索引，见 [configFileMentionsNamingStrategyKey]）。 */
    internal val JPA_CONFIG_FILE_NAMES = listOf("persistence.xml", "orm.xml", "hibernate.cfg.xml")

    /** 命名策略的设置键（词索引识别词；点分写法里的单个标识符段）。 */
    private const val NAMING_STRATEGY_KEY = "implicit_naming_strategy"

    /** 隐式命名策略的参与类型（工程里出现即说明命名策略被工程自己改写）。 */
    private val NAMING_STRATEGY_TYPES = listOf(
        "ImplicitNamingStrategy",
        "ImplicitNamingStrategyJpaCompliantImpl",
        "ImplicitNamingStrategyLegacyJpaImpl",
        "ImplicitNamingStrategyLegacyHbmImpl",
    )

    /** 属性路径类型（Hibernate 的 `AttributePath`）。 */
    private const val ATTRIBUTE_PATH_TYPE = "AttributePath"

    /**
     * 「把属性路径的父段并进列名」的语义特征。
     *
     * `AttributePath` 上只有 `getParent()` / `getFullPath()` / `getDepth()` 能拿到**父段/整条路径**；
     * 内置的组件路径策略正是 `getProperty()` + `getParent()` 递归拼接。只看末端属性名
     * （`getProperty()`）的策略不会碰这三个方法。
     */
    private val ATTRIBUTE_PATH_WALK_MARKERS = listOf("getParent", "getFullPath", "getDepth")

    /** 模块根标记文件（任一命中即视为模块根）。 */
    internal val MODULE_MARKER_FILES = listOf("pom.xml", "build.gradle", "build.gradle.kts")

    /** 模块内配置所在相对路径。 */
    private const val RESOURCES_RELATIVE_PATH = "src/main/resources"

    /** JPA 标准文件在资源目录下的子目录。 */
    private const val META_INF_DIR = "META-INF"

    /** 未识别出模块布局时的缓存键（工程级扫描）。 */
    private const val PROJECT_WIDE_KEY = "<project-wide>"

    private data class Cached(val stamp: Long, val value: Boolean)

    private val cache = ConcurrentHashMap<String, Cached>()

    /**
     * 券03（T-PerfSweep28-03，R28-4）缓存容量上限：键含项目基路径与模块资源目录，多项目长会话
     * 缓慢累积且无项目关闭摘除。超限整体清空（HbmXml 负缓存/EntityResolver 同范式；值为纯布尔
     * 判定结果，重算成本 = 一次资源目录扫描，可接受）。internal 可写仅测试缝。
     */
    @JvmStatic
    internal var maxCacheEntries: Int = 512

    /** 当前缓存条目数（测试 / 诊断用）。 */
    @JvmStatic
    internal fun cacheSize(): Int = cache.size

    /** 券03 测试缝：按给定键走同一写入守卫路径（灌键验证上限收敛；生产链路无调用点）。 */
    @JvmStatic
    internal fun putForTest(key: String, stamp: Long, value: Boolean) {
        store(key, Cached(stamp, value))
    }

    /** 券03 测试缝：清空缓存（用例隔离；生产链路无调用点）。 */
    @JvmStatic
    internal fun clearCacheForTest() = cache.clear()

    /** 写入守卫单一事实：超限整体清空再写（[maxCacheEntries] 语义）。 */
    private fun store(key: String, entry: Cached) {
        if (cache.size >= maxCacheEntries) cache.clear()
        cache[key] = entry
    }

    /**
     * 工程（或更准确地说：[anchor] 所在模块）是否使用「组件路径」嵌入列命名。
     *
     * @param anchor 正在解析的实体文件；null、或实体来自依赖 JAR 时按工程级信号判定
     */
    fun projectUsesComponentPath(project: Project, anchor: VirtualFile? = null): Boolean {
        val resourcesDir = moduleResourcesDir(anchor)
        return if (resourcesDir != null) {
            cached(project, resourcesDir.path) { ReadAction.compute<Boolean, RuntimeException> { scanResourcesDir(resourcesDir) } }
        } else {
            cached(project, PROJECT_WIDE_KEY) {
                ReadAction.compute<Boolean, RuntimeException> {
                    scanProjectConfigs(project) ||
                        projectSourcesReferenceComponentPathStrategy(project) ||
                        projectHasCustomComponentPathStrategy(project)
                }
            }
        }
    }

    private fun cached(project: Project, scopeKey: String, compute: () -> Boolean): Boolean {
        val key = "${project.basePath ?: project.name}|$scopeKey"
        val stamp = PsiModificationTracker.getInstance(project).modificationCount
        cache[key]?.let { if (it.stamp == stamp) return it.value }
        val value = compute()
        // 券03（T-PerfSweep28-03）：写入走统一守卫（超限整体清空再写，语义见 maxCacheEntries）
        store(key, Cached(stamp, value))
        return value
    }

    /** 实体文件所在模块的 `src/main/resources`；认不出模块布局（含实体来自 JAR）时返回 null。 */
    private fun moduleResourcesDir(anchor: VirtualFile?): VirtualFile? {
        val anchorFile = anchor ?: return null
        val fs = LocalFileSystem.getInstance()
        val moduleRootPath = nearestModuleRootPath(anchorFile.path) { path ->
            val dir = fs.findFileByPath(path) ?: return@nearestModuleRootPath false
            MODULE_MARKER_FILES.any { dir.findChild(it) != null }
        } ?: return null
        val moduleRoot = fs.findFileByPath(moduleRootPath) ?: return null
        val resources = VfsUtilCore.findRelativeFile(RESOURCES_RELATIVE_PATH, moduleRoot)
        return resources?.takeIf { it.isDirectory }
    }

    /** 模块内扫描：资源目录本体 + `META-INF/`（JPA 标准文件在那儿）。 */
    private fun scanResourcesDir(resourcesDir: VirtualFile): Boolean = runCatching {
        val dirs = listOfNotNull(resourcesDir, resourcesDir.findChild(META_INF_DIR))
        dirs.asSequence()
            .flatMap { it.children.asSequence() }
            .filter { !it.isDirectory && isNamingConfigFile(it.name) }
            .any { indicatesComponentPath(readText(it)) }
    }.getOrDefault(false)

    /** 工程级扫描（模块不可知时）：配置文件全扫——两条互补的路。 */
    private fun scanProjectConfigs(project: Project): Boolean = runCatching {
        applicationConfigMentions(project) || configFileMentionsNamingStrategyKey(project)
    }.getOrDefault(false)

    /** application 族配置按扩展名索引找（能覆盖 Boot 的连字符键 `naming.implicit-strategy`）。 */
    private fun applicationConfigMentions(project: Project): Boolean = runCatching {
        val scope = GlobalSearchScope.projectScope(project)
        CONFIG_EXTENSIONS.asSequence()
            .flatMap { ext -> FilenameIndex.getAllFilesByExt(project, ext, scope).asSequence() }
            .filter { isApplicationConfig(it.name) }
            .any { indicatesComponentPath(readText(it)) }
    }.getOrDefault(false)

    /**
     * 按**设置键**词索引找任意文件里的命名策略声明。
     *
     * `implicit_naming_strategy` 是单个标识符词，词索引能命中
     * `hibernate.implicit_naming_strategy` / `spring.jpa.properties.hibernate.implicit_naming_strategy`
     * 这类点分写法，且**不限文件类型**——`persistence.xml` / `orm.xml` / 自定义 properties 一并覆盖
     * （按文件名查表只能覆盖已知文件名，且见下方测试口径说明）。命中后读该文件文本再走同一判据。
     */
    private fun configFileMentionsNamingStrategyKey(project: Project): Boolean {
        val helper = PsiSearchHelper.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val context = (
            UsageSearchContext.IN_CODE.toInt() or UsageSearchContext.IN_STRINGS.toInt() or
                UsageSearchContext.IN_COMMENTS.toInt() or UsageSearchContext.IN_PLAIN_TEXT.toInt()
            ).toShort()
        var found = false
        helper.processElementsWithWord(
            TextOccurenceProcessor { element: PsiElement, _: Int ->
                val file = element.containingFile
                if (file != null && indicatesComponentPath(file.text)) {
                    found = true
                    false // 命中即停
                } else {
                    true
                }
            },
            scope,
            NAMING_STRATEGY_KEY,
            context,
            true,
        )
        return found
    }

    /**
     * 工程级扫描（模块不可知时）：**源码里的代码式配置**——用词索引找命名策略类名。
     *
     * 为什么不能靠读文件：`ImplicitNamingStrategyComponentPathImpl` 出现在 Java 源码里
     * （`props.put(...)` / `setImplicitNamingStrategy(...)`），而 `application*.yml` 扫描看不见它。
     * 词索引是 O(命中) 的，比遍历模块内全部 `.java` 便宜得多。
     */
    private fun projectSourcesReferenceComponentPathStrategy(project: Project): Boolean = runCatching {
        val helper = PsiSearchHelper.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val context = (UsageSearchContext.IN_CODE.toInt() or UsageSearchContext.IN_STRINGS.toInt()).toShort()
        COMPONENT_PATH_CLASS_NAMES.any { className ->
            var found = false
            helper.processElementsWithWord(
                TextOccurenceProcessor { element: PsiElement, _: Int ->
                    if (element.containingFile is PsiJavaFile) {
                        found = true
                        false // 命中即停
                    } else {
                        true
                    }
                },
                scope,
                className,
                context,
                true,
            )
            found
        }
    }.getOrDefault(false)

    /**
     * 工程里是否存在**自定义**的组件路径命名策略实现（T-JarEmbedded W1b）。
     *
     * 为什么不能「见到自定义策略就算」：多数自定义 `ImplicitNamingStrategy` 只改**表名**（加前缀、
     * 去后缀等），对嵌入列命名毫无影响——那种工程若被当成组件路径档，反而会把列名改错。故这里看
     * **语义特征**：文件既参与隐式命名（`implements`/`extends` 命名策略类型），又用到 `AttributePath`
     * 的父段/全路径（`getParent` / `getFullPath` / `getDepth`）——那正是「把父路径并进列名」的写法。
     *
     * 已知边界（如实登记，勿当已解决）：
     *  - 自定义策略类若在**依赖 JAR** 里（工程无源码），本探测看不到 → 仍需设置项或数据源校正；
     *  - 自定义策略若用**别的连接符/形态**（不是 `_` 连接 + snake 化），插件按内置 component-path
     *    口径渲染仍可能与真库不同（本轮不猜连接符）；
     *  - 纯文本特征匹配，不解析控制流——`AttributePath` 与 `getParent` 出现在同一文件即视为命中。
     */
    private fun projectHasCustomComponentPathStrategy(project: Project): Boolean = runCatching {
        val helper = PsiSearchHelper.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val context = (UsageSearchContext.IN_CODE.toInt() or UsageSearchContext.IN_STRINGS.toInt()).toShort()
        NAMING_STRATEGY_TYPES.any { type ->
            var found = false
            helper.processElementsWithWord(
                TextOccurenceProcessor { element: PsiElement, _: Int ->
                    val file = element.containingFile
                    if (file is PsiJavaFile && looksLikeComponentPathStrategy(file.text)) {
                        found = true
                        false
                    } else {
                        true
                    }
                },
                scope,
                type,
                context,
                true,
            )
            found
        }
    }.getOrDefault(false)

    /** 文件文本是否呈现「把属性路径的父段/全路径并进列名」的形态。 */
    private fun looksLikeComponentPathStrategy(text: String): Boolean =
        text.contains(ATTRIBUTE_PATH_TYPE) && ATTRIBUTE_PATH_WALK_MARKERS.any { text.contains(it) }

    private fun isNamingConfigFile(name: String): Boolean =
        isApplicationConfig(name) || name in JPA_CONFIG_FILE_NAMES

    private fun isApplicationConfig(name: String): Boolean =
        name.startsWith(CONFIG_FILE_PREFIX) && CONFIG_EXTENSIONS.any { name.endsWith(".$it") }

    private fun readText(file: VirtualFile): String = runCatching { VfsUtilCore.loadText(file) }.getOrDefault("")

    /**
     * 纯文本判定（可单测）：配置文本里出现组件路径命名策略的任一写法即命中。
     *
     * 先剔除整行注释（`#` / `!` 起首，覆盖 properties 与 yml 两种注释形态）——被注释掉的
     * 配置不是生效配置，把它当命中会把工程判定反。
     */
    internal fun indicatesComponentPath(text: String): Boolean {
        val active = text.lineSequence()
            .filterNot {
                val trimmed = it.trimStart()
                trimmed.startsWith("#") || trimmed.startsWith("!")
            }
            .joinToString("\n")
        return COMPONENT_PATH_MARKERS.any { active.contains(it, ignoreCase = true) }
    }

    /**
     * 纯路径判定（可单测）：自 [anchorPath] 向上找最近一个「含模块标记文件」的目录。
     *
     * @param anchorPath 实体文件的绝对路径（`/` 分隔）
     * @param isModuleDir 判定某目录是否为模块根（真实实现查 `pom.xml`/`build.gradle*` 是否存在）
     * @return 模块根路径；一路到根都没命中返回 null（JAR 条目路径必落此档 → 工程级信号）
     */
    internal fun nearestModuleRootPath(anchorPath: String, isModuleDir: (String) -> Boolean): String? {
        var dir = anchorPath.substringBeforeLast('/', "")
        while (dir.isNotEmpty()) {
            if (isModuleDir(dir)) return dir
            val parent = dir.substringBeforeLast('/', "")
            if (parent == dir) return null
            dir = parent
        }
        return null
    }
}
