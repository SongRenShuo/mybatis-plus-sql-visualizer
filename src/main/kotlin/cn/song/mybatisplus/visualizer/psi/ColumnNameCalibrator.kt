package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
import cn.song.mybatisplus.visualizer.model.AssociationInfo
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.JoinColumnRef
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

/**
 * 列名校正器（O-58）：用数据源的真实列集合纠正「实体字段 → 列名」的猜测值。
 *
 * 为什么需要它：实体侧的列名只有「显式注解」与「命名策略猜测」两条来源，而猜测在四类场景必然出错——
 * 注解写在 getter / `@Access(AccessType.PROPERTY)` 上时读不到、连续大写缩写（`orderID` →
 * `order_i_d`）、语义改名（`qty` ↔ `quantity`）、工程自定义命名策略；库里的真实列名是唯一真值。
 * 校正只做一件事：**当归一化后能唯一确定是哪一列时才改写列名**；命中 0 列或 ≥2 列一律原样保留
 * （宁缺不错——`field1` ↔ `col_a` 这类语义无关的改名任何猜测都无解，校正层也不猜）。
 *
 * 与既有「合并」的分工：JOIN 图富化与补全候选都是以实体侧猜测列名为**精确键**去查 DB 列、查不到就
 * 静默回落；本类是唯一做「用真实列集合反证猜测值」的地方。应用点是两个映射产出点的返回边界
 * （[EntityResolver.resolve] / [JpaEntityResolver.resolve]），下游全链路（HQL/JPQL、MP Wrapper、
 * EasyQuery、MDS、Querydsl、Criteria、ER 图）一次性受益。
 *
 * 性能口径（硬性）：生产入口 [calibrate] 只读 [DatabaseSchemaResolver.columnNamesCached] 缓存（零遍历），
 * 未命中只投递一次后台预热并**原样返回未校正结果**——不阻塞、不等待、不轮询。会触发数据源遍历的
 * 同步取数入口在本文件中零引用（由 `ColumnNameCalibratorTest` 的源码扫描守卫钉死），故补全/预览
 * 热路径不新增任何 schema 遍历。
 *
 * 为什么取「全列名」而不是列元数据：`columnsCached` 给的是**带元数据的子集**
 * （[DatabaseSchemaResolver.TableMeta.columns] 只收录「有注释 ∨ 有类型串 ∨ notNull 为 true」的列），
 * 而 [resolveName] 的第 2/3 步是在候选集上判**唯一**——候选集缺列会让歧义凭空消失，
 * 于是「唯一命中」可能是错的那一列（同形两列 `user_name` / `username` 里少了一列时，
 * 剩下那列会被当成唯一解）。这不是少校正一次，是选错列，故必须用未过滤的全列名集合。
 *
 * 降级等价（**本类**的核心断言）：数据源不可用（社区版 / 未配置 / 未连接）、表看不见、查不了、
 * 开关关闭 → 返回**入参实例本身**，与「没有这个功能」逐字段一致。
 *
 * ⚠️ 口径切分（交付说明必须显式区分，别笼统写成「无数据源时行为与改造前完全一致」）：
 *  - **本类（数据源校正层）**受开关与数据源可用性双重约束，降级态逐字段等价（上一段）；
 *  - **getter 侧 JPA 注解读取**（O-58/T-07，落在 [cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader]
 *    的 `columnValueOrSnake`）是**独立的离线纠错**：它读的是源码里本来就写着的 `@Column`，不查数据源、
 *    也不受本开关约束，故**无数据源时实体列名也可能与改造前不同**（变得更准）。
 *    用户「没有可用数据源要自动降级」的要求针对的是**数据源校正层**（本类），不是这条离线读取。
 *
 * 依赖口径（照实记录，勿读成「全仓首次跨层」）：psi 包**本来就**依赖 config 包——[EntityResolver] 与
 * [JpaEntityResolver] 都 import [cn.song.mybatisplus.visualizer.config.PluginSettings] /
 * [cn.song.mybatisplus.visualizer.config.ProjectPluginSettings]，而 config 包里含 Swing 面板
 * （PluginSettingsPanel 等），所以「psi 包零 UI 依赖」的说法并不成立。本类新增的是 psi →
 * `ui.highlight` 这一条**具体**依赖：[DatabaseSchemaResolver] 是纯反射的 schema 取数层（不碰 Swing，
 * 只是包名落在 ui 下），且取数实现落在此处是写集所限（新文件只能放 psi 包）。
 * 后续若要收口层次：把取数反转为「psi 侧声明 provider 接口 + UI 侧注入实现」，本类只依赖接口，
 * 即可彻底去掉 psi → ui 的引用（本轮不实施）。
 */
object ColumnNameCalibrator {

    /**
     * 纯函数核心：把 [table] 的列名按真实列集合 [realColumns] 校正。
     *
     * 取数缝的语义：`null` = 查不了（数据源不可用 / 未缓存 / 查不到），`emptySet` 同理——
     * 两者都不改写任何列（无真值可仲裁）。
     *
     * 改写的字段（全部只改**列名**，`fieldName` / `isId` / `fieldType` / `comment` / `role` /
     * `embedded` / `insertable` / `updatable` 等一概不动，消费方按 `fieldName` 索引）：
     *  - [ColumnInfo.columnName]（`columns` 顺序不变）；
     *  - 表级派生列名字段 `logicDeleteColumn` / `versionColumn` / `compositeIdColumns`——它们持有的是
     *    **同一个列名**，不同步改写会让同一个 TableInfo 里同一列出现两个名字（`@TableLogic` 注入的
     *    `AND deleted = 0` 会与列清单不一致）；
     *  - 关联的**本表侧**外键列 `associations[].joinColumn` 与 `associations[].joinColumns[].name`
     *    （无 `@JoinColumn` 时它们是 `{fieldName}_id` 的**猜测值**，而 JOIN ON 直接消费——只校列清单
     *    不改它，JOIN 预览照样报 unknown column）；
     *  - 类级主键关联列 `primaryKeyJoinColumns[].name`（JPA 规范：类级 `@PrimaryKeyJoinColumn.name`
     *    是**当前实体**的主键列）。
     *
     * **不碰**（判定依据见各自 KDoc）：`associations[].joinColumns[].referencedColumnName`（目标表列）、
     * `secondaryTables[].pkJoinColumns[].name`（副表列）、`discriminatorColumn`（继承层次根表的列）、
     * `collectionAssociations` 族与 `joinTable` 族（中间表/集合表的列）。
     *
     * @param table       实体解析产物（未校正）
     * @param realColumns 数据源给出的真实列名集合；null = 查不了
     * @return 校正后的表信息；无任何改动时返回 [table] 本身
     */
    fun calibrate(table: TableInfo, realColumns: Set<String>?): TableInfo {
        if (realColumns.isNullOrEmpty() || table.columns.isEmpty()) return table
        // 归一化索引按需构建：多数列在第 1 步精确命中，此时零额外开销（T-03 的「几次 map 查找」口径）
        val index = lazy(LazyThreadSafetyMode.NONE) { indexByNormalized(realColumns) }
        val calibrated = ArrayList<ColumnInfo>(table.columns.size)
        // 旧列名 → 新列名；[ambiguousOldNames] 里的旧名**永久**不参与派生字段回写
        val renames = HashMap<String, String>()
        val ambiguousOldNames = HashSet<String>()
        var changed = false
        for (column in table.columns) {
            // 嵌入列多一道兜底（T-EmbeddedColPrefix）：工程用「组件路径」命名策略且实体无显式注解时，
            // 猜测值差的是整段前缀（`base_qty` vs `qty_base_qty`），归一化比不出，改用嵌入段路径作候选
            val real = resolveName(column.columnName, column.fieldName, realColumns) { index.value }
                ?: resolveEmbeddedPathName(column, realColumns) { index.value }
            if (real == null) {
                calibrated += column
                continue
            }
            changed = true
            calibrated += column.copy(columnName = real)
            registerRename(column.columnName, real, renames, ambiguousOldNames)
        }
        val associations = calibrateAssociations(table.associations, realColumns) { index.value }
        val pkJoinColumns = calibrateJoinColumnRefs(table.primaryKeyJoinColumns, "", realColumns) { index.value }
        if (!changed && associations === table.associations && pkJoinColumns === table.primaryKeyJoinColumns) {
            return table
        }
        return table.copy(
            columns = calibrated,
            associations = associations,
            primaryKeyJoinColumns = pkJoinColumns,
            logicDeleteColumn = table.logicDeleteColumn?.let { remapName(it, renames, ambiguousOldNames) },
            versionColumn = table.versionColumn?.let { remapName(it, renames, ambiguousOldNames) },
            compositeIdColumns = table.compositeIdColumns.map { remapName(it, renames, ambiguousOldNames) },
        )
    }

    /**
     * 登记「旧列名 → 新列名」供派生字段回写。同一旧名若映射到**两个不同**新名即为歧义，该旧名进
     * [ambiguousOldNames] **永久**退出回写——只 remove 不拉黑的话，后续同一旧名的 put 因
     * `previous == null` 会把它重新登记（复核反例：columns = [(alpha,"x"), (beta,"x"), (alpha,"x")]
     * 最终 renames = {x: alpha}，`logicDeleteColumn = "x"` 被改成歧义列名 alpha）。
     */
    private fun registerRename(
        oldName: String,
        newName: String,
        renames: MutableMap<String, String>,
        ambiguousOldNames: MutableSet<String>,
    ) {
        if (oldName in ambiguousOldNames) return
        val previous = renames.put(oldName, newName)
        if (previous != null && previous != newName) {
            renames.remove(oldName)
            ambiguousOldNames.add(oldName)
        }
    }

    /** 派生字段列名回写：歧义旧名原样保留，其余按登记映射改写。 */
    private fun remapName(name: String, renames: Map<String, String>, ambiguousOldNames: Set<String>): String =
        if (name in ambiguousOldNames) name else renames[name] ?: name

    /**
     * 校正关联的**本表侧**外键列（[AssociationInfo.joinColumn] 与 [AssociationInfo.joinColumns] 的
     * `name`）。`referencedColumnName` 指向**目标表**的列，本表真值集合对它没有仲裁权，故不碰。
     * 两者用同一算法、同一 `fieldName`，故 `joinColumn == joinColumns[0].name` 的既有不变量保持。
     */
    private fun calibrateAssociations(
        associations: List<AssociationInfo>,
        realColumns: Set<String>,
        index: () -> Map<String, List<String>>,
    ): List<AssociationInfo> {
        if (associations.isEmpty()) return associations
        var changed = false
        val out = associations.map { association ->
            val joinColumn = resolveName(association.joinColumn, association.fieldName, realColumns, index)
            val refs = calibrateJoinColumnRefs(association.joinColumns, association.fieldName, realColumns, index)
            if (joinColumn == null && refs === association.joinColumns) {
                association
            } else {
                changed = true
                association.copy(joinColumn = joinColumn ?: association.joinColumn, joinColumns = refs)
            }
        }
        return if (changed) out else associations
    }

    /**
     * 校正一组 [JoinColumnRef] 的 `name`（本表侧列名）。[fieldName] 供算法第 3 步使用；类级
     * `@PrimaryKeyJoinColumn` 没有对应的 Java 字段，传空串使第 3 步自然失效（只靠精确/归一化命中）。
     */
    private fun calibrateJoinColumnRefs(
        refs: List<JoinColumnRef>,
        fieldName: String,
        realColumns: Set<String>,
        index: () -> Map<String, List<String>>,
    ): List<JoinColumnRef> {
        if (refs.isEmpty()) return refs
        var changed = false
        val out = refs.map { ref ->
            val real = resolveName(ref.name, fieldName, realColumns, index)
            if (real == null) {
                ref
            } else {
                changed = true
                ref.copy(name = real)
            }
        }
        return if (changed) out else refs
    }

    /**
     * 生产入口：数据源可用（连得上、看得见这张表）时校正，否则原样返回。
     *
     * 开关关闭 / 表名为空 / 无列 / 未命中缓存且本次不等待 → 全部走「原样返回」这一条路，
     * 即降级态与未接入本功能逐字段一致。
     *
     * @param project 实体所属项目（数据源按项目配置）；null 时无法取数，直接降级
     * @param table   实体解析产物（未校正）
     */
    fun calibrate(project: Project?, table: TableInfo): TableInfo {
        if (table.columns.isEmpty() || table.tableName.isBlank()) return table
        if (!isEnabled(project)) return table
        val realColumns = realColumnsFromCache(project, table.tableName) ?: return table
        return calibrate(table, realColumns)
    }

    // ------------------------------------------------------------------
    // 匹配算法（T-02）：只在能唯一确定时改写
    // ------------------------------------------------------------------

    /**
     * 单名仲裁，按优先级判定。**返回 null = 不改写**（含「精确命中，本就无需改写」——调用方据此
     * 直接复用原对象，保证「无改动时返回入参实例本身」这一不变量）：
     *  1. `columnName` 在真实列集合中**精确命中** → 保留（库里的实际大小写形态即当前值）；
     *  2. 真实列集合中存在**唯一**一列，其归一化形式与 `columnName` 相同 → 采用该真实列名
     *     （大小写/下划线形态按库回写——Oracle/PG 折叠族由此得到库里的实际形态）；
     *  3. **仅当第 2 步 0 候选时**才以 `fieldName` 归一化形式匹配，且同样要求唯一 → 采用该真实列名
     *     （H1/H2 的主命中路径：`orderID` → `order_i_d`，归一化后与 `order_id` 同为 `orderid`）；
     *  4. 其余情形（第 2 步歧义 / 第 3 步 0 候选或歧义）→ 原样保留。
     *
     * **第 2 步歧义时立即放弃，不再走第 3 步**：第 3 步是换一把尺子（`fieldName` 键）重新判唯一，
     * 在另一个键上「唯一」不等于这一列唯一——复核反例：real = {home_city, homecity, city}、
     * fieldName = `city`、columnName = `home-city`，步 2 在 `homecity` 键上两候选歧义，步 3 却在
     * `city` 键上唯一命中 → 改写成 `city`（错列）。这与「宁缺不错」直接冲突，故收窄为「歧义即停」。
     */
    private fun resolveName(
        columnName: String,
        fieldName: String,
        realColumns: Set<String>,
        index: () -> Map<String, List<String>>,
    ): String? {
        if (columnName.isBlank()) return null
        // 步 1：精确命中（大小写敏感）——库里就是这个字符串，本就无需改写
        if (columnName in realColumns) return null
        val byNormalized = index()
        val byCurrent = candidatesOf(byNormalized, normalize(columnName))
        if (byCurrent.size == 1) return byCurrent[0]
        // 步 2 歧义（≥2）：立即放弃（理由见 KDoc，别在这里「再试一次」）
        if (byCurrent.size > 1) return null
        // 步 2 无候选 → 步 3：以 fieldName 归一化形式唯一命中
        val byField = candidatesOf(byNormalized, normalize(fieldName))
        return if (byField.size == 1) byField[0] else null
    }

    /** 归一化键的候选列（空键——全下划线等退化形态——不参与匹配，返回空表）。 */
    private fun candidatesOf(byNormalized: Map<String, List<String>>, key: String): List<String> =
        if (key.isEmpty()) emptyList() else byNormalized[key] ?: emptyList()

    /**
     * 嵌入列的最后一档候选（T-EmbeddedColPrefix）：用 [ColumnInfo.embeddedPath]（实体根起的嵌入段
     * 驼峰路径，`qty.baseQty` → `qtyBaseQty`）的 snake 形态作候选列名，**唯一命中才改写**。
     *
     * 存在理由：工程用 Hibernate 组件路径命名策略（`ImplicitNamingStrategyComponentPathImpl`）时，
     * 真实列名比「内层字段名」多一整段前缀（`base_qty` vs `qty_base_qty`）——[normalize] 只去下划线，
     * 两者归一化后仍不同（`baseqty` vs `qtybaseqty`），既有三步算法必然 0 候选。而本类 KDoc 列的
     * 适用场景里正有「工程自定义命名策略」一条，嵌入列此前是那条的盲区。
     *
     * 安全口径照既有「宁缺不错」：
     *  - 只对 `embedded == true` 的列生效；
     *  - 当前列名**已精确存在于真实列集合**时该列在第 1 步就已按「无需改写」返回，走不到这里；
     *  - 候选名要**唯一**命中（精确命中，或归一化键唯一），0 候选 / ≥2 候选一律不动；
     *  - 命中值与当前列名相同也返回 null（不制造无意义改动，保住「无改动返回入参实例」不变量）。
     */
    private fun resolveEmbeddedPathName(
        column: ColumnInfo,
        realColumns: Set<String>,
        index: () -> Map<String, List<String>>,
    ): String? {
        if (!column.embedded) return null
        // 当前列名已是库中真名（[resolveName] 第 1 步的「无需改写」）→ 路径候选不得把它抢走
        if (column.columnName in realColumns) return null
        val candidate = column.embeddedPath?.takeIf { it.isNotBlank() }?.let { NamingStrategy.toSnakeCase(it) }
            ?: return null
        if (candidate.isBlank() || candidate == column.columnName) return null
        // 精确命中：库里就是这个字符串
        if (candidate in realColumns) return candidate
        return candidatesOf(index(), normalize(candidate)).singleOrNull()?.takeIf { it != column.columnName }
    }

    /**
     * 归一化：小写 + 去 `_`/`-`（`order_i_d` / `orderID` / `order_id` / `ORDER-ID` → `orderid`）。
     *
     * 这是「实体侧猜测」与「库侧真值」之间唯一可靠的可比形态——命名策略、大小写折叠、下划线风格
     * 三个维度上的差异都被它抹平，剩下的差异才是语义改名（那类不猜）。
     */
    internal fun normalize(name: String): String {
        val sb = StringBuilder(name.length)
        for (ch in name) {
            if (ch == '_' || ch == '-') continue
            sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    /** 归一化列名 → 真实列名列表（size > 1 = 歧义，调用方据此放弃改写）。 */
    private fun indexByNormalized(realColumns: Set<String>): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>(realColumns.size * 2)
        for (name in realColumns) {
            val key = normalize(name)
            if (key.isEmpty()) continue
            out.getOrPut(key) { ArrayList(1) }.add(name)
        }
        return out
    }

    // ------------------------------------------------------------------
    // 生产取数（T-03）：只读缓存 + 未命中投递预热，热路径零遍历
    // ------------------------------------------------------------------

    /**
     * 生产取数：只读数据源 schema 缓存（零遍历）。未命中（数据源未连接 / 内省未完成 / 表不在库中）
     * 返回 null 并投递一次后台预热——**本次调用不等待**，由下一次调用（预热完成后的重绘/补全）取到真值。
     *
     * 本函数是本文件里唯一的生产取数实现，只允许调「全列名缓存读」与「后台预热」两个成员
     * （`ColumnNameCalibratorTest` 用源码扫描守卫钉死，防后续改动把会遍历的同步入口、或只含元数据
     * 子集的 `columnsCached` 引进来——后者会让唯一性判定失真）。
     */
    private fun realColumnsFromCache(project: Project?, tableName: String): Set<String>? {
        val source = columnSourceForTest
        val real = when {
            source != null -> source(project, tableName)
            project == null -> return null
            else -> runCatching { DatabaseSchemaResolver.columnNamesCached(project, listOf(tableName)) }
                .getOrNull()?.get(tableName)
        }
        // 未命中（数据源未连接 / 内省未完成 / 表不在库中）：只投递一次后台预热，本次调用原样返回
        if (real.isNullOrEmpty()) requestPrefetch(project, tableName)
        return real?.takeIf { it.isNotEmpty() }
    }

    /**
     * 投递一次后台预热。两道闸，任一未过即**本次不投递**（不阻塞、不等待）：
     *  1. 表名冷却窗口（[markPrefetchDispatched]，防同一张表在补全热路径上形成投递风暴）；
     *  2. 全局在途上限（[MAX_IN_FLIGHT_PREFETCH]）——只有冷却是不够的：20+ 个**不同**表名突发时
     *     去重闸一个都不拦，会投出等量后台任务，每个都独立触发一次全模型遍历（复核指出）。
     *
     * 超过在途上限时该表已占用冷却窗口，冷却过后自然重试（不会永久漏预热）。
     *
     * 线程与异常口径照 [DatabaseSchemaResolver.prefetchTables] 的既有约定：BGT 执行，内部自带
     * ReadAction；任何反射链异常都在下游被吞掉（`runCatching`），不会冒泡到调用方。
     */
    private fun requestPrefetch(project: Project?, tableName: String) {
        if (!markPrefetchDispatched(tableName)) return
        if (inFlightCount() >= MAX_IN_FLIGHT_PREFETCH) return
        prefetchSinkForTest?.let { sink ->
            sink(project, tableName)
            return
        }
        if (project == null) return
        // §18（2026-11-13 14:47 真机）：索引期不投递 schema 预热——那会儿数据源模型同样没加载，
        // 830 张表逐张投递只会刷出 830 条「模型冷跳过」，纯噪音；索引就绪后的下一次重绘会自然补。
        if (runCatching { com.intellij.openapi.project.DumbService.isDumb(project) }.getOrDefault(false)) return
        inFlightPrefetch.incrementAndGet()
        val future = runCatching {
            ApplicationManager.getApplication()?.executeOnPooledThread {
                try {
                    runCatching { DatabaseSchemaResolver.prefetchTables(project, listOf(tableName)) }
                } finally {
                    inFlightPrefetch.decrementAndGet()
                }
            }
        }.getOrNull()
        // 没投出去（无 Application / 排程抛错）→ 立刻归还计数，避免在途数泄漏后把后续预热全挡掉
        if (future == null) inFlightPrefetch.decrementAndGet()
    }

    /** 当前在途预热数（测试缝优先）。 */
    private fun inFlightCount(): Int = inFlightForTest ?: inFlightPrefetch.get()

    /**
     * 预热去重登记：冷却窗口内的同一表名只投递一次。
     *
     * 用「时间戳 + 冷却窗口」而不是「在途集合」：投递后立即移除会让同一批调用重复投递，投递后
     * 长期保留又会在预热失败（数据源始终连不上）时把该表永久钉死。窗口过后自动允许重投，
     * 两种失效形态都不存在。
     *
     * 去重键**只含表名、不带 project**（有意）：预热是幂等的取数动作，多项目同开时同名表最多互相
     * 抑制一个 5s 窗口（下一个窗口照常投递），不会漏预热；而带上 project 会让键空间随打开项目数
     * 线性膨胀，把 20 条的容量上界摊薄成「每项目几条」。
     *
     * 容量上界 [MAX_PREFETCH_TABLES]：溢出时**淘汰最旧一条**。原实现是整体 `clear()`——那会把所有
     * 表名的冷却时间戳一起抹掉，等于重置全部冷却，恰好把自己刚做的限流抵消掉（复核指出）。
     * 淘汰按**插入顺序**（[prefetchDispatchedAt] 是插入序 LinkedHashMap）而非时间戳最小值：同一毫秒内
     * 连续登记时时间戳可能相等，按值淘汰会退化成「随机淘汰」，按插入序则确定。
     */
    private fun markPrefetchDispatched(tableName: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(prefetchDispatchedAt) {
            val last = prefetchDispatchedAt[tableName]
            if (last != null && now - last < prefetchCooldownMs) return false
            if (prefetchDispatchedAt.size >= MAX_PREFETCH_TABLES) {
                prefetchDispatchedAt.keys.firstOrNull()?.let { prefetchDispatchedAt.remove(it) }
            }
            prefetchDispatchedAt[tableName] = now
            return true
        }
    }

    /** 开关读取：项目级启用时项目值优先，否则全局值；设置服务不可用（纯单测环境）兜底默认开。 */
    private fun isEnabled(project: Project?): Boolean {
        enabledForTest?.let { return it }
        val projectValue = runCatching {
            val target = project ?: ProjectManager.getInstance().openProjects.firstOrNull()
            target?.let { ProjectPluginSettings.getInstance(it).effectiveColumnCalibration() }
        }.getOrNull()
        return projectValue
            ?: runCatching { PluginSettings.getInstance().state.columnCalibrationEnabled }.getOrDefault(true)
    }

    // ------------------------------------------------------------------
    // 测试缝（生产恒为 null / 默认值）
    // ------------------------------------------------------------------

    /** 测试缝：真实列集合取数替身（null = 走数据源 schema 缓存读链）。 */
    internal var columnSourceForTest: ((Project?, String) -> Set<String>?)? = null

    /** 测试缝：预热投递替身（null = 投递 executeOnPooledThread）。 */
    internal var prefetchSinkForTest: ((Project?, String) -> Unit)? = null

    /** 测试缝：开关替身（null = 读设置服务）。 */
    internal var enabledForTest: Boolean? = null

    /** 测试缝：全局在途预热数替身（null = 读真实计数器）。 */
    internal var inFlightForTest: Int? = null

    /** 测试缝：预热去重冷却窗口（毫秒）。 */
    @Volatile
    internal var prefetchCooldownMs: Long = PREFETCH_COOLDOWN_MS

    /** 测试缝：清空预热去重登记与在途计数（用例间隔离）。 */
    internal fun resetPrefetchStateForTest() {
        synchronized(prefetchDispatchedAt) { prefetchDispatchedAt.clear() }
        inFlightPrefetch.set(0)
        inFlightForTest = null
    }

    /** 预热去重冷却窗口（毫秒）：窗口内同一表名只投递一次预热。 */
    internal const val PREFETCH_COOLDOWN_MS = 5_000L

    /** 预热去重登记容量上界（溢出淘汰最旧一条）。 */
    internal const val MAX_PREFETCH_TABLES = 20

    /** 全局在途预热上限：同时最多这么多预热任务在跑，超出即本次不投递（冷却后自然重试）。 */
    internal const val MAX_IN_FLIGHT_PREFETCH = 4

    /** 表名 → 最近一次预热投递时刻（毫秒）；**插入序** LinkedHashMap（溢出淘汰最旧用）。 */
    private val prefetchDispatchedAt =
        java.util.Collections.synchronizedMap(LinkedHashMap<String, Long>())

    /** 全局在途预热任务计数（投递 +1、任务结束 -1）。 */
    private val inFlightPrefetch = java.util.concurrent.atomic.AtomicInteger()
}
