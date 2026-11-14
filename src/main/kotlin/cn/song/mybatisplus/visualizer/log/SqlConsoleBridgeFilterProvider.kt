package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.execution.filters.Filter
import com.intellij.openapi.project.Project

/**
 * M56 T-M56-09：consoleFilterProvider EP 双轨——EP 为主捕获通道（平台在控制台创建时自动
 * 装配本 provider 返回的 Filter，无轮询发现面），反射轮询降为兜底。
 *
 * 「EP 实证产出」生效判定（T-CaptureTruth T-CT-04 收窄）：EP Filter 真正**产出过一条 SQL 条目**
 * 时才置 [SqlCaptureGate.epActive]（会话内粘滞）——桥据此短路自身的重复解析；桥的**挂载面不再
 * 跳过**（T-CT-04 起），即 EP 未在链上或对该控制台产不出条目时，桥照常解析保底。
 * 原口径「EP 被调用过即置位 + 桥挂载面跳过」的双重假设从未真机验证（下方真机门禁项），
 * 一旦不成立就是静默零捕获——故改为「先交条目再获信任」。
 * 真机门禁：runIde 观测 EP 与桥的单路化（短路计数递增且条目不重）——留人工。
 *
 * 每个 Filter 实例持有独立 [BridgeLineContext]（平台每控制台创建一次 provider 调用，
 * 实例即 per-console 状态）；重放守卫降级为直通（EP Filter 不持有 console 引用，取不到
 * 编辑器文档）——rehighlight 重放由去重口径兜底（同 SQL 同指纹）。
 *
 * 捕捉态机：走与桥同款 capturing 守卫（[SqlLogManager.capturing]），非捕捉态零处理。
 */
class SqlConsoleBridgeFilterProvider : com.intellij.execution.filters.ConsoleFilterProvider {

    override fun getDefaultFilters(project: Project): Array<Filter> {
        // T-ST-02b2：读路径经 sqlLogManagerIfCreated（永不发起创建 → 不会毒化服务容器）；
        // 服务未创建＝本插件从未启用捕获＝无过滤需求，等价于「非捕捉态零处理」
        val manager = sqlLogManagerIfCreated(project) ?: return emptyArray()
        return arrayOf(EpLineFilter(project, manager))
    }

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(SqlConsoleBridgeFilterProvider::class.java)

        /**
         * T-HiddenBugPerfScan 券 C：EP ctx 注册表（弱引用）。T-LogPerf-04 之后配置刷新只剩
         * `SqlLogManager` 设置监听里的 `bridge.refreshConsoleContexts()`——它只遍历**桥侧**
         * consoleContexts，EP 侧 ctx 构造后无任何刷新点（快照在控制台创建时刻固化）→ 捕捉中
         * 改 SQL/参数前缀等设置对 EP 主通道永不生效（T-CaptureTruth 起 EP 为主通道、桥被短路，
         * 桥侧刷新救不了它）。EP ctx 经 [registerEpContext] 登记，设置变更与桥同拍刷新。
         * 弱引用：EP filter 由平台 console 持有，console 关闭后 ctx 不可达即自动出局，
         * 不钉旧控制台（照 UrlResolver/EventBuilder 的「禁静态强引用宿主对象」口径）。
         */
        private val epContexts =
            java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<BridgeLineContext>>()

        /** EP ctx 登记（同实例幂等；顺手清理已不可达的弱引用）。 */
        internal fun registerEpContext(ctx: BridgeLineContext) {
            if (epContexts.none { it.get() === ctx }) epContexts += java.lang.ref.WeakReference(ctx)
            epContexts.removeAll { it.get() == null }
        }

        /** 设置变更：把 [cfg] 同步进全部存活 EP ctx（与桥侧 refreshConsoleContexts 同字段面）。 */
        internal fun refreshEpContexts(cfg: ParserConfig) {
            epContexts.removeAll { it.get() == null }
            for (ref in epContexts) {
                val ctx = ref.get() ?: continue
                synchronized(ctx) { ctx.applyConfig(cfg) }
            }
        }

        /** [SqlLogManager] 设置监听入口：读当前配置并刷新 EP ctx（测试直接用 [refreshEpContexts]）。 */
        internal fun refreshEpContextsFromSettings() {
            refreshEpContexts(parserConfig())
        }

        private fun parserConfig(): ParserConfig {
            val state = PluginSettings.getInstance().state
            return ParserConfig(
                sqlPrefix = state.logSqlPrefix,
                paramPrefix = state.logParamPrefix,
                mixedPrefix = state.logMixedPrefix,
                maxParseLength = state.logMaxParseLength,
                hibernateSqlPrefix = state.logHibernateSqlPrefix,
                hibernateParamPrefix = state.logHibernateParamPrefix,
                hibernateEnabled = state.logHibernateEnabled,
                jdbcEnabled = state.logJdbcEnabled,
                shardingEnabled = state.logShardingSphereEnabled,
            )
        }
    }

    /** EP 行过滤器：实例即 per-console 解析状态。 */
    private class EpLineFilter(
        private val project: Project,
        private val manager: SqlLogManager,
    ) : Filter {

        private val ctx = BridgeLineContext(
            myBatisParser = MyBatisLogParser(
                sqlPrefix = parserConfig().sqlPrefix,
                paramPrefix = parserConfig().paramPrefix,
                mixedPrefix = parserConfig().mixedPrefix,
                maxParseLength = parserConfig().maxParseLength,
            ),
            hibernateParser = HibernateLogParser(
                sqlPrefix = parserConfig().hibernateSqlPrefix,
                paramPrefix = parserConfig().hibernateParamPrefix,
                mixedPrefix = parserConfig().mixedPrefix,
                maxParseLength = parserConfig().maxParseLength,
            ),
            jdbcParser = JdbcTemplateLogParser(
                enabled = parserConfig().jdbcEnabled,
                maxParseLength = parserConfig().maxParseLength,
            ),
            shardingParser = ShardingSphereLogParser(
                maxParseLength = parserConfig().maxParseLength,
            ),
            configProvider = { parserConfig() },
            hibernateEnabled = parserConfig().hibernateEnabled,
            shardingEnabled = parserConfig().shardingEnabled,
        )

        init {
            // T-HiddenBugPerfScan 券 C：ctx 进注册表——设置变更与桥同拍刷新（快照不得固化）
            registerEpContext(ctx)
        }

        override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
            // 捕捉态守卫（与桥同款状态机）：非捕捉态零处理
            if (!manager.capturing) return null
            // T-PS4-02：timePrefix 不再逐行读 PluginSettings——经 manager 桥侧缓存取（对齐桥侧
            // timePrefixCache 形态，刷新点一致：挂载/设置变更；EP 首行早于桥挂载时惰性补种）。
            // 值语义与原逐行读取一致（blank 归 null）。
            val timePrefix = manager.consoleBridge.currentTimePrefix()
            // T-HiddenBugPerfScan 券 C②：以 ctx 为锁与桥侧同款（T-ConsolePendingFlush：解析器
            // 桶状态/pendingSource/pendingError 是跨行共享态）——平台异步 filter（AsyncFilterRunner）
            // 可并发跑多个 highlight job，不锁则大输出下解析状态串台（T-LogParseConc 同族）。
            val produced = synchronized(ctx) {
                processConsoleLine(line, ctx, timePrefix, {}, onErrorText = ::bindConsoleError)
            }
            // T-CaptureTruth T-CT-04：置位条件由「处理过任意一行」收窄为「实证产出过条目」——
            // EP 被平台调用 ≠ EP 真在链上产出（覆盖不全时原口径会封死桥的回落通道 → 静默零捕获）。
            // 置位前桥侧照常解析；置位后桥只短路重复解析，条目仍由 EP 单路产出。
            if (produced.isNotEmpty() && !SqlCaptureGate.epActive) {
                SqlCaptureGate.epActive = true
            }
            // O-147 W0：EP 通道打标（桥轮询路径同款在 SqlLogConsoleBridge.emitEntries）
            produced.forEach { manager.entries.addEntry(it.copy(captureProvenance = CaptureProvenance.CONSOLE_EP)) }
            return null // 不做高亮/链接——纯捕获通道
        }

        /**
         * T-M55-02：错误反向绑定与桥同款（丢弃留 debug 痕）。EP 通道无重放守卫，
         * rehighlight 重放场景靠同文本幂等绑定兜底（文本相同不再替换实例）。
         */
        private fun bindConsoleError(errorText: String) {
            when (val result = manager.entries.bindErrorToRecent(errorText)) {
                SqlLogEntryBuffer.ErrorBindResult.BOUND -> {}
                else -> LOG.debug("EpLineFilter: 错误块绑定丢弃（$result）：${errorText.lineSequence().firstOrNull()?.take(120)}")
            }
        }
    }

}
