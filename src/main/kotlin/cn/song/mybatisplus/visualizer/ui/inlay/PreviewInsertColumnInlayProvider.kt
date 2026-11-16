package cn.song.mybatisplus.visualizer.ui.inlay

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.XmlStatementKind
import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.InlayPresentationFactory
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 预览语句类型载体 + 作用域锚点（T-InsertColumnInlay O-44b）。
 *
 * **为什么需要作用域判定**：inlay 的 EP（`com.intellij.codeInsight.inlayProvider`）是 **language 级**的——
 * 一旦注册 `language="SQL"`，IDE 内**所有** SQL 编辑器（含用户自己的 `.sql` 文件）都会向本 provider
 * 索取提示。本任务唯一的真实风险即「作用域泄漏到用户文件」，故 provider 首件事就是判定
 * 「这个编辑器是不是本面板的预览编辑器」。
 *
 * **双锚点（两条都成立才算命中）**：
 *  1. **文件名** = 本插件预览文件名形态（一级按形态匹配 [isPrimaryPreviewFileName] /
 *     二级 [MEMORY_PREVIEW_FILE_NAME] 精确匹配）；
 *  2. **userData** = [KEY] 语句类型载体（Editor 优先、PSI 文件兜底，同 M16.2 补全上下文双载体口径）。
 *
 * 单靠 userData 不够：它只证明「有人往这个编辑器写过本插件的键」，锚点二把「本插件预览文件」
 * 这个事实也钉上，两者同时成立才放行——用户自己的 `.sql` 文件即使被误挂了键（或文件恰好同名但
 * 非本面板产物）也不满足全部条件。反之单靠文件名也不够（同名用户文件会误命中），故取合取。
 *
 * **文件名取值口径（T-FixN2 合并后订正）**：预览文件有**三个不同形态**——
 *  1. **一级**（项目内临时 VFS 文件，inlay 真正会渲染的那一级）：`mpsql-preview-<pid>-<seq>.sql`，
 *     名字**每次预热唯一**（T-FixN2 消除平台 File Cache Conflict 模态，见 `SqlPreviewPanel` 的
 *     [previewTempFileName] 调用处），故锚点按**形态**匹配而不是固定名；
 *  2. **二级**（内存 PSI，降级链第二级）：`mpsql-preview-memory.sql`，固定名（无 VFS 文件，
 *     不受唯一命名影响），精确匹配——它**不**满足一级形态（`memory` 不是数字序号），
 *     不会被误判成一级；
 *  3. 测试缝文件名 `preview.sql`（`SqlPreviewPanel.createPreviewFileForTest`）**有意不列入**：
 *     它是最像用户文件的普通名，列入即把「用户自己的 `.sql` 文件」判据打穿。
 *
 * **为什么不用宽松前缀判据**（如 `startsWith("mpsql-preview")`）：那会把无关名字一起放进来——
 * `mpsql-preview-memory.sql` 会被当成一级名，用户自造的同前缀文件也满足文件名锚点，判据形同虚设。
 * 形态判据必须紧致：前缀 + 数字 owner + 数字序号 + 扩展名，四段全中才算一级。原生控制台临时文件
 * `mpsql-exec.sql`（`DataSourceSelector.projectExecFile`）**不**满足该形态（前缀不同）——它在锚点处
 * 被拦下是**已知边界**（该路径 inlay 不渲染），不得因放宽判据而变成「错误地渲染」。
 */
object PreviewInlayScope {

    /**
     * 一级预览文件名**前缀**（项目内临时 VFS 文件，inlay 真正会渲染的那一级）。
     *
     * 名字**不是固定值**：T-FixN2 起每次预热换唯一名（消除平台 File Cache Conflict 模态），
     * 故此处给出的是**形态常量**——名字由 [previewTempFileName] 生成、由 [isPrimaryPreviewFileName]
     * 判定，生成与判定**共用同一份常量**（`SqlPreviewPanel.nextPreviewTempFileName` 也引用它）。
     * 这是「面板建文件处」与「锚点判据」之间唯一的事实源：两侧各自硬编码字面量会在改名时静默
     * 打穿锚点——锚点不报错，只是 inlay 永不出现。
     */
    const val PREVIEW_FILE_NAME_PREFIX: String = "mpsql-preview-"

    /** 一级预览文件名**扩展名**（同 [PREVIEW_FILE_NAME_PREFIX] 的形态常量口径）。 */
    const val PREVIEW_FILE_NAME_EXT: String = ".sql"

    /** 二级预览文件（内存 PSI，降级链第二级）名——固定名，无 VFS 文件，不受唯一命名影响。 */
    const val MEMORY_PREVIEW_FILE_NAME: String = "mpsql-preview-memory.sql"

    /**
     * 一级预览文件名形态样例（`mpsql-preview-<owner>-<seq>.sql` 的一个合法实例）。
     *
     * 供文档与测试构造一个「形态合法」的一级名用（测试用 `configureByText` 造 fixture 文件时，
     * 生产名由 `SqlPreviewPanel.nextPreviewTempFileName` 生成，两者形态一致）。**不是**生产使用的
     * 固定名——生产名每轮预热都不同。
     */
    val PREVIEW_FILE_NAME_SAMPLE: String = previewTempFileName("12480", 1)

    /**
     * 一级预览文件名紧致形态判据：`<前缀><数字 owner>-<数字序号><扩展名>`（owner = pid）。
     *
     * 四段全中才算一级：[PREVIEW_FILE_NAME_PREFIX] + `\d+` + `-` + `\d+` + [PREVIEW_FILE_NAME_EXT]。
     * 前缀与扩展名由共享常量 [java.util.regex.Pattern.quote] 转义后拼入，杜绝判据与生成漂移；
     * owner 段**不**与当前 pid 比对——跨会话残留（同 pid 复用 / 已退出会话的残留文件）经
     * `SqlPreviewPanel.projectPreviewFile` 的兜底分支仍会被本面板使用，按 pid 比对会让那一支
     * 静默失去 inlay。
     */
    private val PRIMARY_PREVIEW_FILE_NAME_PATTERN: Regex = Regex(
        "^" + java.util.regex.Pattern.quote(PREVIEW_FILE_NAME_PREFIX) +
            "\\d+-\\d+" + java.util.regex.Pattern.quote(PREVIEW_FILE_NAME_EXT) + "$",
    )

    /**
     * 按共享形态常量拼出一个预览临时文件名（形态的**唯一生成入口**）。
     *
     * @param owner 会话段（本 IDE 进程 pid，取不到时调用方给 `0`）
     * @param seq   本 JVM 内预览序号（同一 owner 下唯一）
     */
    fun previewTempFileName(owner: String, seq: Int): String =
        PREVIEW_FILE_NAME_PREFIX + owner + "-" + seq + PREVIEW_FILE_NAME_EXT

    /** 是否为本插件**一级**预览文件名（项目内临时 VFS 文件，见 [PRIMARY_PREVIEW_FILE_NAME_PATTERN]）。 */
    fun isPrimaryPreviewFileName(name: String): Boolean = PRIMARY_PREVIEW_FILE_NAME_PATTERN.matches(name)

    /**
     * 是否为本插件预览文件名（一级形态匹配 **或** 二级内存名精确匹配）。
     *
     * 注意是**紧致**判据：不是 `startsWith(前缀)`——内存级 `mpsql-preview-memory.sql`、原生控制台
     * 临时文件 `mpsql-exec.sql`、测试缝名 `preview.sql` 都不满足一级形态。
     */
    fun isPreviewFileName(name: String): Boolean =
        name == MEMORY_PREVIEW_FILE_NAME || isPrimaryPreviewFileName(name)

    /** 预览语句类型载体（值 = 当前预览的语句类型；null = 非本面板预览 / 无语句类型语义）。 */
    val KEY: Key<XmlStatementKind> = Key.create("MP_PREVIEW_STATEMENT_KIND")

    /** 写载体（Editor 与 PSI 文件双写，面板收口处调用）；[kind] 为 null 即清除。 */
    fun attach(target: UserDataHolder, kind: XmlStatementKind?) {
        target.putUserData(KEY, kind)
    }

    /**
     * 双锚点判定 + 取语句类型：命中返回语句类型，未命中返回 null（provider 据此不产任何提示）。
     *
     * @param file   平台传入的 PSI 文件（裸 Document 降级级无 PSI ⇒ provider 不被调用，天然不渲染）
     * @param editor 平台传入的编辑器（userData 主载体：用户输入后 PSI 可能被 commit 重建而丢 userData，
     *               Editor 对象编辑期间稳定）
     */
    fun previewStatementKind(file: PsiFile?, editor: Editor?): XmlStatementKind? {
        if (file == null || !isPreviewFileName(file.name)) return null
        return editor?.getUserData(KEY) ?: file.getUserData(KEY)
    }
}

/**
 * INSERT 目标列 inlay provider（T-InsertColumnInlay O-44b）。
 *
 * 在**本面板预览编辑器**的 INSERT 语句上，为每个 VALUES 表达式 / SELECT 投影挂目标列名提示，
 * 让多列动态 INSERT 的「列-值错位」一眼可见。规划规则见 [InsertColumnInlayPlanner]。
 *
 * 四道闸门（任一不满足即返回 null，平台侧「无 collector」= 零 inlay）：
 *  0. **原生让位**（T-PreviewInlayNativeYield）：[NativeColumnInlayYieldGate] 判定 IDEA 自带的 SQL
 *     列名 inlay（providerId `sql.column.names.inlay.hints`，选项 `sql.show.column.names.in.insert.values`）
 *     在位时**整体让位**——那一侧默认开启、做的正是同一件事，两边都画就是同一 offset 上两枚同名胶囊。
 *     判据只取「在位」，不取「本次这条语句对方是否真画得出」（收集期两侧互相不可见，平台机制见该门 KDoc）；
 *  1. **作用域**：[PreviewInlayScope.previewStatementKind] 双锚点（文件名 + userData）；
 *  2. **语句类型**：`xmlModel.kind == INSERT`（面板经 [PreviewInlayScope.attach] 注入；
 *     非 INSERT 与「无 xmlModel 的非 XML 预览链」都取不到 INSERT 值 → 不提示）；
 *  3. **静态可判**：[InsertColumnInlayPlanner] 内部的列/值对应判定（不可判即不提示）。
 *
 * **零设置项**：EP 未注册 `settingsKeyId`，平台按 `InlayHintsSettings.computeIsEnabledByDefault`
 * 的「无匹配 bean → true」口径默认开启（243 源码实证），用户可用平台的 inlay 全局开关或
 * 语言级开关关闭；本 provider 不新增设置页面（`createConfigurable` 为接口强制实现的空壳，
 * 无 `settingsKeyId` 即无设置 UI 入口）。
 *
 * **纯显示层**：只读编辑器文档文本 + 向 InlayModel 投提示，不改 SQL 文本、不写文档。
 * 提示内容只依赖文档文本（不依赖 PSI 树/索引），故实现 [DumbAware]（索引期间照常可用）。
 */
class PreviewInsertColumnInlayProvider : InlayHintsProvider<NoSettings>, DumbAware {

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink,
    ): InlayHintsCollector? {
        // 第 0 道（T-PreviewInlayNativeYield）：IDEA 自带的 SQL 列名 inlay 在位即整体让位。
        // 放在最前——它是「这条功能还要不要」的开关，作用域/类型/可判三道只决定「画哪些」。
        if (NativeColumnInlayYieldGate.shouldYield()) return null
        if (PreviewInlayScope.previewStatementKind(file, editor) != XmlStatementKind.INSERT) return null
        return InsertColumnInlayCollector(PresentationFactory(editor))
    }

    override fun createSettings(): NoSettings = NoSettings()

    override val name: String
        get() = MpSqlVisualizerBundle.message("inlay.insertColumn.name")

    override val key: SettingsKey<NoSettings> = SettingsKey("sqlVisualizer.insertColumn")

    override val previewText: String?
        get() = null

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable = NoOpConfigurable
}

/**
 * INSERT 目标列提示收集器。
 *
 * 平台两处调用形态不同，故用「一次性」闸门：
 *  - 真机 pass（`InlayHintsPass.doCollectInformation`）按 Divider 分段元素逐个调用，返回 false
 *    即把本 collector 移出后续调度；
 *  - 平台测试链（`CollectorWithSettings.collectTraversing`，`SyntaxTraverser.psiTraverser(file)`）
 *    **忽略返回值**、对每个 PSI 元素都调用一次——无闸门会按元素数量重复投提示。
 *
 * 故首次调用即投完全部提示并置位（提示位点取自编辑器当前文档全文，与传入元素无关）。
 *
 * **闸门必须是原子的**（T-PerfSweep6 T-PS6-01，本轮唯一带正确性的发现）：平台真机 pass 不是单线程
 * 遍历——`InlayHintsPass.doCollectInformation` 把分片元素交给
 * `JobLauncher.invokeConcurrentlyUnderProgress(…, Processor { element -> … })` **并发**跑，
 * 闭包内共享同一份 `enabledCollectors`（243 装机版源码实证：`InlayHintsPass.kt` 的
 * `val skippedCollectors = ConcurrentCollectionFactory.createConcurrentSet<Int>()` +
 * 对 `enabledCollectors.indices.minus(skippedCollectors)` 的并发回调）。`skippedCollectors`
 * 只在 `collectHints` **返回之后**才置位，故「判定 + 置位」两步之间是一个真实的竞态窗口：
 * 普通 `var` 下两个分片线程可同时通过判定、各投一遍提示 → 同一 offset 上出现两份 presentation。
 * 改 `AtomicBoolean.compareAndSet(false, true)` 后只有一个线程能进入收集体。
 *
 * **CAS 失败路径直接 `return false`，不做「结果就绪后共享」**：平台只把返回值当「后续还要不要回调
 * 本 collector」用（`CollectorWithSettings.collectHints` 仅转发 `collector.collect` 的返回值，
 * `InlayHintsPass` 见 false 即记入 `skippedCollectors`）——胜者必定完成收集并投递，失败者返回 false
 * 只是让平台更快把它移出调度。若失败路径返回 true，平台会对**每个**分片元素继续回调它，是更差的形态。
 */
private class InsertColumnInlayCollector(
    private val factory: InlayPresentationFactory,
) : InlayHintsCollector {

    private val collected = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
        if (!collected.compareAndSet(false, true)) return false
        val document = editor.document
        // 越界防御用 textLength（O(1)），不读全文——缓存命中时连 text 都不取（见 [InsertColumnInlayPlanCache]）
        val textLength = document.textLength
        for (hint in InsertColumnInlayPlanCache.planFor(document)) {
            // 防御：位点由本次同一份文本算出，理论上必在范围内；越界时平台 addInlineElement 会硬断言
            //（显示层功能不得因断言升级成异常），故越界条目直接跳过。
            if (hint.offset < 0 || hint.offset > textLength) continue
            // relatesToPrecedingText=false：提示归属其后的值表达式（同参数名提示口径）；
            // placeAtTheEndOfLine=false：不占行尾锚点（行内提示，允许光标落在其后继续编辑）
            sink.addInlineElement(hint.offset, false, factory.text(hint.columnName), false)
        }
        return false
    }
}

/**
 * 规划结果单槽缓存（T-PerfSweep6 T-PS6-01）。
 *
 * **为什么必须落在进程级而不是 collector 内**：平台**每个 highlighting pass 都新建 collector**——
 * `InlayHintsPassFactoryInternal.createHighlightingPass` 里 `val hintSink = InlayHintsSinkImpl(editor)`
 * 与 `getProviders(file, editor).mapNotNull { it.getCollectorWrapperFor(file, editor, language, hintSink) }`
 * 都是逐 pass 执行（243 装机版源码实证），collector 内缓存跨不过 pass；而一次性闸门已保证
 * 每 collector 只规划一次，故「collector 内缓存」是零收益的死代码。缓存落在进程级单例上，
 * 才真正省掉「同一文档连续多轮 pass 之间文本未变」的重规划。
 *
 * **键 =（文档身份, `modificationStamp`）**：stamp 是**全局** 24 位计数器
 * （`LocalTimeCounter.MOD_COUNTER_MASK = 0x00ff_ffff`，`DocumentImpl` 取全局 AtomicInteger），
 * 跨文档理论上可碰撞——只按 stamp 建键会把别的文档的提示搬到本文档上，故文档身份必须一起比。
 *
 * **弱键**：缓存不得钉住文档/编辑器（照 [DatabaseSchemaResolver] 的 `synchronizedMap(WeakHashMap)`
 * 弱键纪律）；单槽形态下旧文档随下一次 miss 被替换，`WeakReference` 只是防「最后一次命中的文档
 * 被本对象长期钉住」。
 *
 * **线程安全**：平台分片并发下 `planFor` 可被多线程同时调用；命中判定与写入都收在锁内，
 * 未命中时的重规划在锁外做（重复算一遍只是白付一次，不会产出错结果）。
 */
internal object InsertColumnInlayPlanCache {

    private val lock = Any()
    private var lastDocument: java.lang.ref.WeakReference<Document>? = null
    private var lastStamp: Long = Long.MIN_VALUE
    private var lastHints: List<InsertColumnInlayPlanner.Hint> = emptyList()

    /**
     * 取文档当前文本的规划结果：`(文档身份, stamp)` 与上次一致直接复用，否则重规划并刷新单槽。
     *
     * 命中时**不读 `document.text`**——这是本缓存除「省重规划」外的第二项收益（未变文档上连文本
     * 引用都不取）。
     */
    fun planFor(document: Document): List<InsertColumnInlayPlanner.Hint> {
        val stamp = document.modificationStamp
        synchronized(lock) {
            if (lastStamp == stamp && lastDocument?.get() === document) return lastHints
        }
        val hints = InsertColumnInlayPlanner.plan(document.text)
        synchronized(lock) {
            lastDocument = java.lang.ref.WeakReference(document)
            lastStamp = stamp
            lastHints = hints
        }
        return hints
    }

    /**
     * T-PerfSweep6 T-PS6-01 测试缝：清空单槽。
     *
     * 本对象是**进程级静态状态**，同 JVM 内多个测试类共享（默认 `maxParallelForks=1`、
     * 非 testDatabase 无 forkEvery）——用例必须在开始处清槽，否则前一个用例留下的
     * `(文档, stamp)` 会让后一个用例的首次规划直接命中缓存（读数随用例顺序漂移）。
     * 形态对照 `MetadataInvalidator.clearParseCacheForTest`。
     */
    internal fun clearForTest() {
        synchronized(lock) {
            lastDocument = null
            lastStamp = Long.MIN_VALUE
            lastHints = emptyList()
        }
    }
}

/**
 * 空壳配置页（接口强制实现）。
 *
 * EP 未注册 `settingsKeyId` ⇒ 平台设置页不会为本 provider 建入口（`createConfigurable` 无调用方），
 * 本对象只是接口契约的落点；不做任何 UI（返回空面板，无控件即无状态）。
 */
private object NoOpConfigurable : ImmediateConfigurable {
    override fun createComponent(listener: ChangeListener): JComponent = JPanel()
}
