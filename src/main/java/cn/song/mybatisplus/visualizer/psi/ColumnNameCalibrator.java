package cn.song.mybatisplus.visualizer.psi;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings;
import cn.song.mybatisplus.visualizer.model.AssociationInfo;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.JoinColumnRef;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver;
import cn.song.mybatisplus.visualizer.util.NamingStrategy;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 列名校正器（O-58）：用数据源的真实列集合纠正「实体字段 → 列名」的猜测值。
 *
 * 为什么需要它：实体侧的列名只有「显式注解」与「命名策略猜测」两条来源，而猜测在四类场景必然出错——
 * 注解写在 getter / {@code @Access(AccessType.PROPERTY)} 上时读不到、连续大写缩写（{@code orderID} →
 * {@code order_i_d}）、语义改名（{@code qty} ↔ {@code quantity}）、工程自定义命名策略；库里的真实列名是唯一真值。
 * 校正只做一件事：<b>当归一化后能唯一确定是哪一列时才改写列名</b>；命中 0 列或 ≥2 列一律原样保留
 * （宁缺不错——{@code field1} ↔ {@code col_a} 这类语义无关的改名任何猜测都无解，校正层也不猜）。
 *
 * 与既有「合并」的分工：JOIN 图富化与补全候选都是以实体侧猜测列名为<b>精确键</b>去查 DB 列、查不到就
 * 静默回落；本类是唯一做「用真实列集合反证猜测值」的地方。应用点是两个映射产出点的返回边界
 * （{@link EntityResolver#resolve} / {@link cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver#resolve}），
 * 下游全链路（HQL/JPQL、MP Wrapper、EasyQuery、MDS、Querydsl、Criteria、ER 图）一次性受益。
 *
 * 性能口径（硬性）：生产入口 {@link #calibrate(Project, TableInfo)} 只读
 * {@link DatabaseSchemaResolver#columnNamesCached} 缓存（零遍历），
 * 未命中只投递一次后台预热并<b>原样返回未校正结果</b>——不阻塞、不等待、不轮询。会触发数据源遍历的
 * 同步取数入口在本文件中零引用（由 {@code ColumnNameCalibratorTest} 的源码扫描守卫钉死），故补全/预览
 * 热路径不新增任何 schema 遍历。
 *
 * 为什么取「全列名」而不是列元数据：{@code columnsCached} 给的是<b>带元数据的子集</b>
 * （DatabaseSchemaResolver.TableMeta.columns 只收录「有注释 ∨ 有类型串 ∨ notNull 为 true」的列），
 * 而 {@code resolveName} 的第 2/3 步是在候选集上判<b>唯一</b>——候选集缺列会让歧义凭空消失，
 * 于是「唯一命中」可能是错的那一列（同形两列 {@code user_name} / {@code username} 里少了一列时，
 * 剩下那列会被当成唯一解）。这不是少校正一次，是选错列，故必须用未过滤的全列名集合。
 *
 * 降级等价（<b>本类</b>的核心断言）：数据源不可用（社区版 / 未配置 / 未连接）、表看不见、查不了、
 * 开关关闭 → 返回<b>入参实例本身</b>，与「没有这个功能」逐字段一致。
 *
 * ⚠️ 口径切分（交付说明必须显式区分，别笼统写成「无数据源时行为与改造前完全一致」）：
 *  - <b>本类（数据源校正层）</b>受开关与数据源可用性双重约束，降级态逐字段等价（上一段）；
 *  - <b>getter 侧 JPA 注解读取</b>（O-58/T-07，落在
 *    {@link cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader} 的 {@code columnValueOrSnake}）
 *    是<b>独立的离线纠错</b>：它读的是源码里本来就写着的 {@code @Column}，不查数据源、
 *    也不受本开关约束，故<b>无数据源时实体列名也可能与改造前不同</b>（变得更准）。
 *    用户「没有可用数据源要自动降级」的要求针对的是<b>数据源校正层</b>（本类），不是这条离线读取。
 *
 * 依赖口径（照实记录，勿读成「全仓首次跨层」）：psi 包<b>本来就</b>依赖 config 包——
 * {@link EntityResolver} 与 {@link cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver} 都 import
 * {@link cn.song.mybatisplus.visualizer.config.PluginSettings} /
 * {@link cn.song.mybatisplus.visualizer.config.ProjectPluginSettings}，而 config 包里含 Swing 面板
 * （PluginSettingsPanel 等），所以「psi 包零 UI 依赖」的说法并不成立。本类新增的是 psi →
 * {@code ui.highlight} 这一条<b>具体</b>依赖：{@link DatabaseSchemaResolver} 是纯反射的 schema 取数层（不碰 Swing，
 * 只是包名落在 ui 下），且取数实现落在此处是写集所限（新文件只能放 psi 包）。
 * 后续若要收口层次：把取数反转为「psi 侧声明 provider 接口 + UI 侧注入实现」，本类只依赖接口，
 * 即可彻底去掉 psi → ui 的引用（本轮不实施）。
 *
 * 无状态单例（测试缝字段除外），可独立单测。
 */
public final class ColumnNameCalibrator {

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final ColumnNameCalibrator INSTANCE = new ColumnNameCalibrator();

    private ColumnNameCalibrator() {
    }

    /**
     * 纯函数核心：把 {@code table} 的列名按真实列集合 {@code realColumns} 校正。
     *
     * 取数缝的语义：{@code null} = 查不了（数据源不可用 / 未缓存 / 查不到），{@code emptySet} 同理——
     * 两者都不改写任何列（无真值可仲裁）。
     *
     * 改写的字段（全部只改<b>列名</b>，{@code fieldName} / {@code isId} / {@code fieldType} / {@code comment} /
     * {@code role} / {@code embedded} / {@code insertable} / {@code updatable} 等一概不动，消费方按 {@code fieldName} 索引）：
     *  - {@link ColumnInfo#getColumnName()}（{@code columns} 顺序不变）；
     *  - 表级派生列名字段 {@code logicDeleteColumn} / {@code versionColumn} / {@code compositeIdColumns}——它们持有的是
     *    <b>同一个列名</b>，不同步改写会让同一个 TableInfo 里同一列出现两个名字（{@code @TableLogic} 注入的
     *    {@code AND deleted = 0} 会与列清单不一致）；
     *  - 关联的<b>本表侧</b>外键列 {@code associations[].joinColumn} 与 {@code associations[].joinColumns[].name}
     *    （无 {@code @JoinColumn} 时它们是 {@code {fieldName}_id} 的<b>猜测值</b>，而 JOIN ON 直接消费——只校列清单
     *    不改它，JOIN 预览照样报 unknown column）；
     *  - 类级主键关联列 {@code primaryKeyJoinColumns[].name}（JPA 规范：类级 {@code @PrimaryKeyJoinColumn.name}
     *    是<b>当前实体</b>的主键列）。
     *
     * <b>不碰</b>（判定依据见各自 KDoc）：{@code associations[].joinColumns[].referencedColumnName}（目标表列）、
     * {@code secondaryTables[].pkJoinColumns[].name}（副表列）、{@code discriminatorColumn}（继承层次根表的列）、
     * {@code collectionAssociations} 族与 {@code joinTable} 族（中间表/集合表的列）。
     *
     * @param table       实体解析产物（未校正）
     * @param realColumns 数据源给出的真实列名集合；null = 查不了
     * @return 校正后的表信息；无任何改动时返回 {@code table} 本身
     */
    public static TableInfo calibrate(@NotNull TableInfo table, @Nullable Set<String> realColumns) {
        if (realColumns == null || realColumns.isEmpty() || table.getColumns().isEmpty()) return table;
        // 归一化索引按需构建：多数列在第 1 步精确命中，此时零额外开销（T-03 的「几次 map 查找」口径）
        // （lazy(LazyThreadSafetyMode.NONE) 的等价物：首次访问时计算一次，不加锁）
        final AtomicReference<Map<String, List<String>>> indexRef = new AtomicReference<>();
        Function0<Map<String, List<String>>> index = () -> {
            Map<String, List<String>> computed = indexRef.get();
            if (computed == null) {
                computed = indexByNormalized(realColumns);
                indexRef.set(computed);
            }
            return computed;
        };
        List<ColumnInfo> calibrated = new ArrayList<>(table.getColumns().size());
        // 旧列名 → 新列名；ambiguousOldNames 里的旧名**永久**不参与派生字段回写
        Map<String, String> renames = new HashMap<>();
        Set<String> ambiguousOldNames = new HashSet<>();
        boolean changed = false;
        for (ColumnInfo column : table.getColumns()) {
            // 嵌入列多一道兜底（T-EmbeddedColPrefix）：工程用「组件路径」命名策略且实体无显式注解时，
            // 猜测值差的是整段前缀（`base_qty` vs `qty_base_qty`），归一化比不出，改用嵌入段路径作候选
            String real = resolveName(column.getColumnName(), column.getFieldName(), realColumns, index);
            if (real == null) real = resolveEmbeddedPathName(column, realColumns, index);
            if (real == null) {
                calibrated.add(column);
                continue;
            }
            changed = true;
            // Kotlin `column.copy(columnName = real)`：其余属性逐字段保留（含 insertable/updatable/
            // generatedValueStrategy/embedded/converted 及嵌入路径元数据）
            calibrated.add(column.copy(column.getFieldName(), real, column.isId(), column.isIdTypeAuto(),
                    column.getFieldType(), column.getFill(), column.getComment(), column.getRole()));
            registerRename(column.getColumnName(), real, renames, ambiguousOldNames);
        }
        List<AssociationInfo> associations = calibrateAssociations(table.getAssociations(), realColumns, index);
        List<JoinColumnRef> pkJoinColumns =
                calibrateJoinColumnRefs(table.getPrimaryKeyJoinColumns(), "", realColumns, index);
        if (!changed && associations == table.getAssociations() && pkJoinColumns == table.getPrimaryKeyJoinColumns()) {
            return table;
        }
        // Kotlin data class copy() 手工重建：派生字段同步回写，其余全部透传
        return new TableInfo(
                table.getEntityClassName(),
                table.getTableName(),
                calibrated,
                table.getLogicDeleteColumn() != null
                        ? remapName(table.getLogicDeleteColumn(), renames, ambiguousOldNames) : null,
                table.getVersionColumn() != null
                        ? remapName(table.getVersionColumn(), renames, ambiguousOldNames) : null,
                table.getComment(),
                associations,
                table.getSchema(),
                table.getCatalog(),
                table.getRestrictionClause(),
                table.getSoftDelete(),
                table.getCustomSqlScripts(),
                table.getSequenceGenerators(),
                table.getTableGenerators(),
                table.getCollectionAssociations(),
                table.getSecondaryTables(),
                pkJoinColumns,
                table.getInheritanceStrategy(),
                table.getDiscriminatorColumn(),
                table.getDiscriminatorValue(),
                remapNames(table.getCompositeIdColumns(), renames, ambiguousOldNames),
                table.getIdClassType());
    }

    /** {@code compositeIdColumns.map { remapName(it, ...) }} 的列表形态。 */
    @NotNull
    private static List<String> remapNames(@NotNull List<String> names, @NotNull Map<String, String> renames,
                                           @NotNull Set<String> ambiguousOldNames) {
        List<String> out = new ArrayList<>(names.size());
        for (String name : names) {
            out.add(remapName(name, renames, ambiguousOldNames));
        }
        return out;
    }

    /**
     * 登记「旧列名 → 新列名」供派生字段回写。同一旧名若映射到<b>两个不同</b>新名即为歧义，该旧名进
     * {@code ambiguousOldNames} <b>永久</b>退出回写——只 remove 不拉黑的话，后续同一旧名的 put 因
     * {@code previous == null} 会把它重新登记（复核反例：columns = [(alpha,"x"), (beta,"x"), (alpha,"x")]
     * 最终 renames = {x: alpha}，{@code logicDeleteColumn = "x"} 被改成歧义列名 alpha）。
     */
    private static void registerRename(@NotNull String oldName, @NotNull String newName,
                                       @NotNull Map<String, String> renames,
                                       @NotNull Set<String> ambiguousOldNames) {
        if (ambiguousOldNames.contains(oldName)) return;
        String previous = renames.put(oldName, newName);
        if (previous != null && !previous.equals(newName)) {
            renames.remove(oldName);
            ambiguousOldNames.add(oldName);
        }
    }

    /** 派生字段列名回写：歧义旧名原样保留，其余按登记映射改写。 */
    private static String remapName(@NotNull String name, @NotNull Map<String, String> renames,
                                    @NotNull Set<String> ambiguousOldNames) {
        if (ambiguousOldNames.contains(name)) return name;
        String mapped = renames.get(name);
        return mapped != null ? mapped : name;
    }

    /**
     * 校正关联的<b>本表侧</b>外键列（{@link AssociationInfo#getJoinColumn()} 与
     * {@link AssociationInfo#getJoinColumns()} 的 {@code name}）。{@code referencedColumnName} 指向<b>目标表</b>的列，
     * 本表真值集合对它没有仲裁权，故不碰。两者用同一算法、同一 {@code fieldName}，
     * 故 {@code joinColumn == joinColumns[0].name} 的既有不变量保持。
     */
    @NotNull
    private static List<AssociationInfo> calibrateAssociations(@NotNull List<AssociationInfo> associations,
                                                               @NotNull Set<String> realColumns,
                                                               @NotNull Function0<Map<String, List<String>>> index) {
        if (associations.isEmpty()) return associations;
        boolean changed = false;
        List<AssociationInfo> out = new ArrayList<>(associations.size());
        for (AssociationInfo association : associations) {
            String joinColumn = resolveName(association.getJoinColumn(), association.getFieldName(), realColumns, index);
            List<JoinColumnRef> refs =
                    calibrateJoinColumnRefs(association.getJoinColumns(), association.getFieldName(), realColumns, index);
            if (joinColumn == null && refs == association.getJoinColumns()) {
                out.add(association);
            } else {
                changed = true;
                out.add(association.copy(association.getFieldName(),
                        joinColumn != null ? joinColumn : association.getJoinColumn(),
                        association.getTargetTable(), association.getTargetAlias(),
                        association.getReferencedColumnName(), refs, association.getMappedBy(),
                        association.isHasForeignKey(), association.getJoinTable(),
                        association.isNotFoundIgnore(), association.isMapsId()));
            }
        }
        return changed ? out : associations;
    }

    /**
     * 校正一组 {@link JoinColumnRef} 的 {@code name}（本表侧列名）。{@code fieldName} 供算法第 3 步使用；类级
     * {@code @PrimaryKeyJoinColumn} 没有对应的 Java 字段，传空串使第 3 步自然失效（只靠精确/归一化命中）。
     */
    @NotNull
    private static List<JoinColumnRef> calibrateJoinColumnRefs(@NotNull List<JoinColumnRef> refs,
                                                               @NotNull String fieldName,
                                                               @NotNull Set<String> realColumns,
                                                               @NotNull Function0<Map<String, List<String>>> index) {
        if (refs.isEmpty()) return refs;
        boolean changed = false;
        List<JoinColumnRef> out = new ArrayList<>(refs.size());
        for (JoinColumnRef ref : refs) {
            String real = resolveName(ref.getName(), fieldName, realColumns, index);
            if (real == null) {
                out.add(ref);
            } else {
                changed = true;
                // Kotlin `ref.copy(name = real)`：其余属性逐字段保留
                out.add(ref.copy(real, ref.getReferencedColumnName(), ref.isInsertable(), ref.isUpdatable()));
            }
        }
        return changed ? out : refs;
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
    public static TableInfo calibrate(@Nullable Project project, @NotNull TableInfo table) {
        if (table.getColumns().isEmpty() || table.getTableName().isBlank()) return table;
        if (!isEnabled(project)) return table;
        Set<String> realColumns = realColumnsFromCache(project, table.getTableName());
        if (realColumns == null) return table;
        return calibrate(table, realColumns);
    }

    // ------------------------------------------------------------------
    // 匹配算法（T-02）：只在能唯一确定时改写
    // ------------------------------------------------------------------

    /**
     * 单名仲裁，按优先级判定。<b>返回 null = 不改写</b>（含「精确命中，本就无需改写」——调用方据此
     * 直接复用原对象，保证「无改动时返回入参实例本身」这一不变量）：
     *  1. {@code columnName} 在真实列集合中<b>精确命中</b> → 保留（库里的实际大小写形态即当前值）；
     *  2. 真实列集合中存在<b>唯一</b>一列，其归一化形式与 {@code columnName} 相同 → 采用该真实列名
     *     （大小写/下划线形态按库回写——Oracle/PG 折叠族由此得到库里的实际形态）；
     *  3. <b>仅当第 2 步 0 候选时</b>才以 {@code fieldName} 归一化形式匹配，且同样要求唯一 → 采用该真实列名
     *     （H1/H2 的主命中路径：{@code orderID} → {@code order_i_d}，归一化后与 {@code order_id} 同为 {@code orderid}）；
     *  4. 其余情形（第 2 步歧义 / 第 3 步 0 候选或歧义）→ 原样保留。
     *
     * <b>第 2 步歧义时立即放弃，不再走第 3 步</b>：第 3 步是换一把尺子（{@code fieldName} 键）重新判唯一，
     * 在另一个键上「唯一」不等于这一列唯一——复核反例：real = {home_city, homecity, city}、
     * fieldName = {@code city}、columnName = {@code home-city}，步 2 在 {@code homecity} 键上两候选歧义，步 3 却在
     * {@code city} 键上唯一命中 → 改写成 {@code city}（错列）。这与「宁缺不错」直接冲突，故收窄为「歧义即停」。
     */
    @Nullable
    private static String resolveName(@NotNull String columnName, @NotNull String fieldName,
                                      @NotNull Set<String> realColumns,
                                      @NotNull Function0<Map<String, List<String>>> index) {
        if (columnName.isBlank()) return null;
        // 步 1：精确命中（大小写敏感）——库里就是这个字符串，本就无需改写
        if (realColumns.contains(columnName)) return null;
        Map<String, List<String>> byNormalized = index.invoke();
        List<String> byCurrent = candidatesOf(byNormalized, normalize(columnName));
        if (byCurrent.size() == 1) return byCurrent.get(0);
        // 步 2 歧义（≥2）：立即放弃（理由见 KDoc，别在这里「再试一次」）
        if (byCurrent.size() > 1) return null;
        // 步 2 无候选 → 步 3：以 fieldName 归一化形式唯一命中
        List<String> byField = candidatesOf(byNormalized, normalize(fieldName));
        return byField.size() == 1 ? byField.get(0) : null;
    }

    /** 归一化键的候选列（空键——全下划线等退化形态——不参与匹配，返回空表）。 */
    @NotNull
    private static List<String> candidatesOf(@NotNull Map<String, List<String>> byNormalized,
                                             @NotNull String key) {
        if (key.isEmpty()) return List.of();
        List<String> found = byNormalized.get(key);
        return found != null ? found : List.of();
    }

    /**
     * 嵌入列的最后一档候选（T-EmbeddedColPrefix）：用 {@code ColumnInfo.embeddedPath}（实体根起的嵌入段
     * 驼峰路径，{@code qty.baseQty} → {@code qtyBaseQty}）的 snake 形态作候选列名，<b>唯一命中才改写</b>。
     *
     * <p>存在理由：工程用 Hibernate 组件路径命名策略（{@code ImplicitNamingStrategyComponentPathImpl}）时，
     * 真实列名比「内层字段名」多一整段前缀（{@code base_qty} vs {@code qty_base_qty}）——{@link #normalize}
     * 只去下划线，两者归一化后仍不同（{@code baseqty} vs {@code qtybaseqty}），既有三步算法必然 0 候选。
     * 而本类 KDoc 列的适用场景里正有「工程自定义命名策略」一条，嵌入列此前是那条的盲区。</p>
     *
     * <p>安全口径照既有「宁缺不错」：
     * <ul>
     *   <li>只对 {@code embedded == true} 的列生效；</li>
     *   <li>当前列名<b>已精确存在于真实列集合</b>时该列在第 1 步就已按「无需改写」返回，走不到这里；</li>
     *   <li>候选名要<b>唯一</b>命中（精确命中，或归一化键唯一），0 候选 / ≥2 候选一律不动；</li>
     *   <li>命中值与当前列名相同也返回 null（不制造无意义改动，保住「无改动返回入参实例」不变量）。</li>
     * </ul></p>
     */
    @Nullable
    private static String resolveEmbeddedPathName(@NotNull ColumnInfo column,
                                                   @NotNull Set<String> realColumns,
                                                   @NotNull Function0<Map<String, List<String>>> index) {
        if (!column.isEmbedded()) return null;
        // 当前列名已是库中真名（resolveName 第 1 步的「无需改写」）→ 路径候选不得把它抢走
        if (realColumns.contains(column.getColumnName())) return null;
        String path = column.getEmbeddedPath();
        if (path == null || path.isBlank()) return null;
        String candidate = NamingStrategy.INSTANCE.toSnakeCase(path);
        if (candidate.isBlank() || candidate.equals(column.getColumnName())) return null;
        // 精确命中：库里就是这个字符串
        if (realColumns.contains(candidate)) return candidate;
        List<String> byNormalized = candidatesOf(index.invoke(), normalize(candidate));
        if (byNormalized.size() == 1) {
            String hit = byNormalized.get(0);
            return hit.equals(column.getColumnName()) ? null : hit;
        }
        return null;
    }

    /**
     * 归一化：小写 + 去 {@code _}/{@code -}（{@code order_i_d} / {@code orderID} / {@code order_id} / {@code ORDER-ID} → {@code orderid}）。
     *
     * 这是「实体侧猜测」与「库侧真值」之间唯一可靠的可比形态——命名策略、大小写折叠、下划线风格
     * 三个维度上的差异都被它抹平，剩下的差异才是语义改名（那类不猜）。
     */
    public static String normalize(@NotNull String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch == '_' || ch == '-') continue;
            sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }

    /** 归一化列名 → 真实列名列表（size > 1 = 歧义，调用方据此放弃改写）。 */
    @NotNull
    private static Map<String, List<String>> indexByNormalized(@NotNull Set<String> realColumns) {
        Map<String, List<String>> out = new HashMap<>(realColumns.size() * 2);
        for (String name : realColumns) {
            String key = normalize(name);
            if (key.isEmpty()) continue;
            out.computeIfAbsent(key, k -> new ArrayList<>(1)).add(name);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 生产取数（T-03）：只读缓存 + 未命中投递预热，热路径零遍历
    // ------------------------------------------------------------------

    /**
     * 生产取数：只读数据源 schema 缓存（零遍历）。未命中（数据源未连接 / 内省未完成 / 表不在库中）
     * 返回 null 并投递一次后台预热——<b>本次调用不等待</b>，由下一次调用（预热完成后的重绘/补全）取到真值。
     *
     * 本函数是本文件里唯一的生产取数实现，只允许调「全列名缓存读」与「后台预热」两个成员
     * （{@code ColumnNameCalibratorTest} 用源码扫描守卫钉死，防后续改动把会遍历的同步入口、或只含元数据
     * 子集的 {@code columnsCached} 引进来——后者会让唯一性判定失真）。
     */
    @Nullable
    private static Set<String> realColumnsFromCache(@Nullable Project project, @NotNull String tableName) {
        Function2<Project, String, Set<String>> source = columnSourceForTest;
        Set<String> real;
        if (source != null) {
            real = source.invoke(project, tableName);
        } else if (project == null) {
            return null;
        } else {
            try {
                real = DatabaseSchemaResolver.columnNamesCached(project, List.of(tableName)).get(tableName);
            } catch (Throwable ignored) {
                real = null;
            }
        }
        // 未命中（数据源未连接 / 内省未完成 / 表不在库中）：只投递一次后台预热，本次调用原样返回
        if (real == null || real.isEmpty()) requestPrefetch(project, tableName);
        return real != null && !real.isEmpty() ? real : null;
    }

    /**
     * 投递一次后台预热。两道闸，任一未过即<b>本次不投递</b>（不阻塞、不等待）：
     *  1. 表名冷却窗口（{@link #markPrefetchDispatched}，防同一张表在补全热路径上形成投递风暴）；
     *  2. 全局在途上限（{@link #MAX_IN_FLIGHT_PREFETCH}）——只有冷却是不够的：20+ 个<b>不同</b>表名突发时
     *     去重闸一个都不拦，会投出等量后台任务，每个都独立触发一次全模型遍历（复核指出）。
     *
     * 超过在途上限时该表已占用冷却窗口，冷却过后自然重试（不会永久漏预热）。
     *
     * 线程与异常口径照 {@link DatabaseSchemaResolver#prefetchTables} 的既有约定：BGT 执行，内部自带
     * ReadAction；任何反射链异常都在下游被吞掉（runCatching），不会冒泡到调用方。
     */
    private static void requestPrefetch(@Nullable Project project, @NotNull String tableName) {
        // §18（2026-11-13 14:47 真机）：索引期不投递 schema 预热——那会儿数据源模型同样没加载，
        // 830 张表逐张投递只会刷出 830 条「模型冷跳过」，纯噪音；索引就绪后的下一次重绘会自然补。
        if (project != null) {
            try {
                if (com.intellij.openapi.project.DumbService.isDumb(project)) return;
            } catch (Throwable ignored) {
                // 平台未就绪按可投递处理（保守方向不改变既有语义）
            }
        }
        if (!markPrefetchDispatched(tableName)) return;
        if (inFlightCount() >= MAX_IN_FLIGHT_PREFETCH) return;
        Function2<Project, String, Unit> sink = prefetchSinkForTest;
        if (sink != null) {
            sink.invoke(project, tableName);
            return;
        }        if (project == null) return;
        inFlightPrefetch.incrementAndGet();
        Future<?> future = null;
        try {
            com.intellij.openapi.application.Application application = ApplicationManager.getApplication();
            if (application != null) {
                future = application.executeOnPooledThread(() -> {
                    try {
                        try {
                            DatabaseSchemaResolver.prefetchTables(project, List.of(tableName));
                        } catch (Throwable ignored) {
                            // runCatching：任何反射链异常都在下游被吞掉，不冒泡
                        }
                    } finally {
                        inFlightPrefetch.decrementAndGet();
                    }
                });
            }
        } catch (Throwable ignored) {
            future = null;
        }
        // 没投出去（无 Application / 排程抛错）→ 立刻归还计数，避免在途数泄漏后把后续预热全挡掉
        if (future == null) inFlightPrefetch.decrementAndGet();
    }

    /** 当前在途预热数（测试缝优先）。 */
    private static int inFlightCount() {
        Integer inFlight = inFlightForTest;
        return inFlight != null ? inFlight : inFlightPrefetch.get();
    }

    /**
     * 预热去重登记：冷却窗口内的同一表名只投递一次。
     *
     * 用「时间戳 + 冷却窗口」而不是「在途集合」：投递后立即移除会让同一批调用重复投递，投递后
     * 长期保留又会在预热失败（数据源始终连不上）时把该表永久钉死。窗口过后自动允许重投，
     * 两种失效形态都不存在。
     *
     * 去重键<b>只含表名、不带 project</b>（有意）：预热是幂等的取数动作，多项目同开时同名表最多互相
     * 抑制一个 5s 窗口（下一个窗口照常投递），不会漏预热；而带上 project 会让键空间随打开项目数
     * 线性膨胀，把 20 条的容量上界摊薄成「每项目几条」。
     *
     * 容量上界 {@link #MAX_PREFETCH_TABLES}：溢出时<b>淘汰最旧一条</b>。原实现是整体 {@code clear()}——那会把所有
     * 表名的冷却时间戳一起抹掉，等于重置全部冷却，恰好把自己刚做的限流抵消掉（复核指出）。
     * 淘汰按<b>插入顺序</b>（{@code prefetchDispatchedAt} 是插入序 LinkedHashMap）而非时间戳最小值：同一毫秒内
     * 连续登记时时间戳可能相等，按值淘汰会退化成「随机淘汰」，按插入序则确定。
     */
    private static boolean markPrefetchDispatched(@NotNull String tableName) {
        long now = System.currentTimeMillis();
        synchronized (prefetchDispatchedAt) {
            Long last = prefetchDispatchedAt.get(tableName);
            if (last != null && now - last < prefetchCooldownMs) return false;
            if (prefetchDispatchedAt.size() >= MAX_PREFETCH_TABLES) {
                Iterator<String> oldest = prefetchDispatchedAt.keySet().iterator();
                if (oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
            prefetchDispatchedAt.put(tableName, now);
            return true;
        }
    }

    /** 开关读取：项目级启用时项目值优先，否则全局值；设置服务不可用（纯单测环境）兜底默认开。 */
    private static boolean isEnabled(@Nullable Project project) {
        Boolean enabled = enabledForTest;
        if (enabled != null) return enabled;
        Boolean projectValue = null;
        try {
            Project target = project;
            if (target == null) {
                Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
                target = openProjects.length > 0 ? openProjects[0] : null;
            }
            if (target != null) {
                projectValue = ProjectPluginSettings.getInstance(target).effectiveColumnCalibration();
            }
        } catch (Throwable ignored) {
            // runCatching：纯单测（无 Application/ProjectManager）降级 null → 回退全局设置
        }
        if (projectValue != null) return projectValue;
        try {
            return PluginSettings.getInstance().getState().getColumnCalibrationEnabled();
        } catch (Throwable e) {
            return true;
        }
    }

    // ------------------------------------------------------------------
    // 测试缝（生产恒为 null / 默认值）
    // ------------------------------------------------------------------

    /** 测试缝：真实列集合取数替身（null = 走数据源 schema 缓存读链）。 */
    public static Function2<Project, String, Set<String>> columnSourceForTest = null;

    /** 测试缝：预热投递替身（null = 投递 executeOnPooledThread）。 */
    public static Function2<Project, String, Unit> prefetchSinkForTest = null;

    /** 测试缝：开关替身（null = 读设置服务）。 */
    public static Boolean enabledForTest = null;

    /** 测试缝：全局在途预热数替身（null = 读真实计数器）。 */
    public static Integer inFlightForTest = null;

    /** 测试缝：预热去重冷却窗口（毫秒）。限定名引用（简名前向引用非法，JLS 8.3.3）。 */
    public static volatile long prefetchCooldownMs = ColumnNameCalibrator.PREFETCH_COOLDOWN_MS;

    /** 测试缝：清空预热去重登记与在途计数（用例间隔离）。 */
    public static void resetPrefetchStateForTest() {
        synchronized (prefetchDispatchedAt) {
            prefetchDispatchedAt.clear();
        }
        inFlightPrefetch.set(0);
        inFlightForTest = null;
    }

    /** 预热去重冷却窗口（毫秒）：窗口内同一表名只投递一次预热。 */
    public static final long PREFETCH_COOLDOWN_MS = 5_000L;

    /** 预热去重登记容量上界（溢出淘汰最旧一条）。 */
    public static final int MAX_PREFETCH_TABLES = 20;

    /** 全局在途预热上限：同时最多这么多预热任务在跑，超出即本次不投递（冷却后自然重试）。 */
    public static final int MAX_IN_FLIGHT_PREFETCH = 4;

    /** 表名 → 最近一次预热投递时刻（毫秒）；<b>插入序</b> LinkedHashMap（溢出淘汰最旧用）。 */
    private static final Map<String, Long> prefetchDispatchedAt =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    /** 全局在途预热任务计数（投递 +1、任务结束 -1）。 */
    private static final AtomicInteger inFlightPrefetch = new AtomicInteger();
}
