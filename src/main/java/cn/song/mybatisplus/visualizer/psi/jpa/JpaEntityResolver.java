package cn.song.mybatisplus.visualizer.psi.jpa;
import com.intellij.psi.PsiAnnotationMemberValue;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings;
import cn.song.mybatisplus.visualizer.model.AssociationInfo;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRole;
import cn.song.mybatisplus.visualizer.model.CollectionAssociationInfo;
import cn.song.mybatisplus.visualizer.model.JoinColumnRef;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.ColumnNameCalibrator;
import cn.song.mybatisplus.visualizer.util.NamingStrategy;
import cn.song.mybatisplus.visualizer.util.TableConfigSignature;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * JPA 实体解析器：从 JPA 注解实体 [PsiClass] 解析出 [TableInfo]。
 *
 * 规则（对齐 JPA 规范 / Hibernate 默认行为）：
 *  - 表名：优先 @Table(name="xxx") 注解，否则按命名策略由类名推断（驼峰转下划线）
 *  - 列名：优先 @Column(name="xxx") 注解，否则按命名策略由字段名推断
 *  - 主键：@Id 标记
 *  - @Transient 字段（非持久化）跳过
 *  - @ManyToOne + @JoinColumn(name="xxx") 解析为外键列
 *  - @OneToMany / @OneToOne / @ManyToMany 关联字段跳过（非简单列）
 *  - @MappedSuperclass 父类字段继承（父类字段在前，子类字段在后）
 *  - 字段按角色分组稳定排序——@Id(PRIMARY) 置顶、业务字段(BUSINESS) 居中、
 *    审计字段(AUDIT) 与系统字段(SYSTEM) 沉底，组内保持声明顺序
 *  - 解析结果按「PsiFile + classQualifiedName + configSignature」三维缓存，
 *    文件修改戳（modificationStamp）+ 配置签名双维度失效
 *  - O-58：返回边界接 {@link cn.song.mybatisplus.visualizer.psi.ColumnNameCalibrator}——数据源可用时用真实
 *    列集合纠正猜测出的列名，不可用/查不了/开关关闭时逐字段原样返回
 */
public class JpaEntityResolver {

    /**
     * 解析 JPA 实体类为表信息（带缓存）。
     *
     * <p>缓存键：PsiFile + classQualifiedName + configSignature（三维）。
     * 失效机制：文件修改（modificationStamp 变化）→ 缓存自然失效；
     * 配置签名变化（tableNameInference 等设置变更）→ 缓存失效。</p>
     *
     * <p>O-58（列名数据源校正）：缓存<b>继续只存未校正的 PSI 产物</b>，校正在返回前作为一步廉价后处理
     * （{@link cn.song.mybatisplus.visualizer.psi.ColumnNameCalibrator#calibrate(Project, TableInfo)}）执行——
     * DDL 变更的失效面因此只有数据源 schema 缓存一处（已登记 MetadataInvalidator），不必给实体缓存键扩 DB 签名。</p>
     *
     * @param entityClass JPA 实体 PsiClass
     * @return 表信息；类名缺失等异常情况返回带推断表名、空列的表信息（保证不抛异常）
     */
    @NotNull
    public TableInfo resolve(@NotNull PsiClass entityClass) {
        return resolve(entityClass, null);
    }

    /**
     * Kotlin 默认参数等价重载宿主：{@code resolve(entityClass, hostAnchor = null)}。
     *
     * @param hostAnchor 查询宿主元素（T-EmbeddedHostCascade，可空）——实体在依赖 JAR/lib 模块时
     *                   嵌入列命名策略配置在宿主 App 模块，实体自身模块探测 miss 后按宿主模块级联判定；
     *                   参与缓存键（不同宿主可判出不同命名档，不得共用缓存条目）
     */
    @NotNull
    public TableInfo resolve(@NotNull PsiClass entityClass, @Nullable PsiElement hostAnchor) {
        String className = entityClass.getName();
        if (className == null) return new TableInfo("", "", List.of(), null, null, null, List.of());
        Project project = entityClass.getProject();
        PsiFile file = entityClass.getContainingFile();
        if (file == null) {
            return ColumnNameCalibrator.calibrate(project,
                    resolveInternal(entityClass, hostAnchorFile(hostAnchor)));
        }
        String qName = entityClass.getQualifiedName() != null ? entityClass.getQualifiedName() : className;
        String configSig = TableConfigSignature.projectSignature(project);
        com.intellij.openapi.vfs.VirtualFile hostAnchorVirtualFile = hostAnchorFile(hostAnchor);
        String hostAnchorUrl = hostAnchorVirtualFile != null ? hostAnchorVirtualFile.getPath() : null;

        // T-Perf-18：缓存键不持 PsiFile 强引用（VirtualFile.url 字符串标识）
        JpaCacheKey key = new JpaCacheKey(
                file.getVirtualFile() != null ? file.getVirtualFile().getUrl() : null,
                System.identityHashCode(file), qName, configSig, hostAnchorUrl);
        long stamp = file.getModificationStamp();
        JpaEntry entry = cache.get(key);
        if (entry != null) {
            if (entry.stamp() == stamp) {
                return ColumnNameCalibrator.calibrate(project, entry.value());
            }
        }

        TableInfo value = resolveInternal(entityClass, hostAnchorVirtualFile);
        // 券02（T-PerfSweep27-02）：写入前容量守卫——超限整体清空再写（上限语义见 maxCacheEntries）。
        if (cache.size() >= maxCacheEntries) cache.clear();
        cache.put(key, new JpaEntry(stamp, value));
        return ColumnNameCalibrator.calibrate(project, value);
    }

    /** 宿主锚点 → 其文件 VirtualFile（探测级联与缓存键用；解析异常一律 null 不改语义）。 */
    @Nullable
    private static com.intellij.openapi.vfs.VirtualFile hostAnchorFile(@Nullable PsiElement hostAnchor) {
        try {
            return hostAnchor != null && hostAnchor.getContainingFile() != null
                    ? hostAnchor.getContainingFile().getVirtualFile() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 内部解析（完整 PSI 遍历，缓存未命中时调用）；Kotlin 默认参数等价重载（hostAnchor = null）。
     */
    @NotNull
    private TableInfo resolveInternal(@NotNull PsiClass entityClass) {
        return resolveInternal(entityClass, null);
    }

    /**
     * 内部解析（完整 PSI 遍历，缓存未命中时调用）。
     *
     * @param hostAnchor 查询宿主文件（T-EmbeddedHostCascade）——穿透到嵌入展平的命名档级联判定
     */
    @NotNull
    private TableInfo resolveInternal(@NotNull PsiClass entityClass,
            @Nullable com.intellij.openapi.vfs.VirtualFile hostAnchor) {
        String className = entityClass.getName();
        if (className == null) return new TableInfo("", "", List.of(), null, null, null, List.of());
        // 类级元数据：schema/catalog、限制子句、软删除、自定义脚本、生成器、副表、继承与判别、@IdClass
        // （T-J8 / T-J14 / T-J18，读取失败整体降级为空元数据）
        JpaMetadataReader.ClassMeta classMeta = JpaMetadataReader.readClassMeta(entityClass);
        // 表名：优先 @Table 注解（SD8：name → value 双属性兜底——relational @Table("t_user") value
        // 简写最常用），否则按命名策略由类名推断
        String tableName = tableAnnotationValue(entityClass);
        if (tableName == null) tableName = decorateTableName(inferTableName(className, entityClass.getProject()), entityClass.getProject());

        // 递归父类收集字段（支持 @MappedSuperclass 继承；父类字段在前、子类字段在后）
        List<PsiField> fields = collectFieldsIncludingSuper(entityClass);
        Set<String> ignoredFields = ignoredFieldNames(entityClass.getProject());
        // 类级 @AttributeOverride（嵌入列名覆写，字段级覆写优先于类级，T-J18）
        java.util.Map<String, String> classOverrides = JpaMetadataReader.attributeOverrides(entityClass);
        // 按角色分组稳定排序（PRIMARY 置顶 → BUSINESS 居中 → AUDIT/SYSTEM 沉底，组内保持声明顺序）
        List<ColumnInfo> columns = new ArrayList<>();
        for (PsiField field : fields) {
            columns.addAll(resolveColumns(field, ignoredFields, classOverrides, hostAnchor));
        }
        columns.sort(Comparator.comparing(ColumnInfo::getRole));
        // 去重：同一列名只保留第一个（简单字段优先于 @ManyToOne 关联字段）
        // 示例：departmentId(@Column) 和 department(@ManyToOne+@JoinColumn) 同映射 department_id
        LinkedHashMap<String, ColumnInfo> byCol = new LinkedHashMap<>();
        for (ColumnInfo c : columns) {
            byCol.putIfAbsent(c.getColumnName(), c);
        }
        columns = new ArrayList<>(byCol.values());
        // 表注释（类级注解 > javadoc > 行注释）
        String comment = resolveComment(entityClass);
        // 收集 @ManyToOne / @OneToOne 关联元数据（T-J7：referencedColumnName/复合外键/mappedBy 侧别/@MapsId/@NotFound）
        List<AssociationInfo> associations = new ArrayList<>();
        for (PsiField field : fields) {
            AssociationInfo a = resolveAssociation(field);
            if (a != null) associations.add(a);
        }
        // 集合关联元数据（T-J7/T-J18：@OneToMany/@ManyToMany/@ElementCollection → 中间表/集合表语义）
        List<CollectionAssociationInfo> collectionAssociations = new ArrayList<>();
        for (PsiField field : fields) {
            CollectionAssociationInfo c = JpaMetadataReader.readCollectionAssociation(field,
                    this::resolveTargetTableName, tableName);
            if (c != null) collectionAssociations.add(c);
        }
        // jakarta/javax + spring-data/Easy-Query/xbatis @Version → versionColumn（T-J8/EQ9/XB）
        String versionColumn = null;
        for (PsiField versionField : fields) {
            if (JpaMetadataReader.hasAnyVersionAnnotation(versionField)) {
                for (ColumnInfo c : columns) {
                    if (versionField.getName() != null && c.getFieldName().equals(versionField.getName())) {
                        versionColumn = c.getColumnName();
                        break;
                    }
                }
                if (versionColumn != null) break;
            }
        }
        // EQ9/XB：@LogicDelete 标记字段 → logicDeleteColumn（可解析时）
        String logicDeleteColumn = null;
        for (PsiField field : fields) {
            String name = field.getName();
            if (name == null) continue;
            String candidate = JpaMetadataReader.logicDeleteColumnOf(field, name);
            if (candidate != null) {
                logicDeleteColumn = candidate;
                break;
            }
        }
        // 复合主键列清单（T-J18：@EmbeddedId / @IdClass / 多 @Id，声明顺序；单主键为空列表）
        boolean hasEmbeddedId = false;
        for (PsiField field : fields) {
            if (JpaMetadataReader.hasEmbeddedIdAnnotation(field)) {
                hasEmbeddedId = true;
                break;
            }
        }
        List<String> idColumns = new ArrayList<>();
        for (ColumnInfo c : columns) {
            if (c.isId()) idColumns.add(c.getColumnName());
        }
        List<String> compositeIdColumns = hasEmbeddedId || classMeta.getIdClassType() != null || idColumns.size() > 1
                ? idColumns : List.of();
        return new TableInfo(className, tableName, columns, logicDeleteColumn, versionColumn, comment,
                associations, classMeta.getSchema(), classMeta.getCatalog(),
                classMeta.getRestrictionClause(), classMeta.getSoftDelete(), classMeta.getCustomSqlScripts(),
                classMeta.getSequenceGenerators(), classMeta.getTableGenerators(), collectionAssociations,
                classMeta.getSecondaryTables(), classMeta.getPrimaryKeyJoinColumns(),
                classMeta.getInheritanceStrategy(), classMeta.getDiscriminatorColumn(),
                classMeta.getDiscriminatorValue(), compositeIdColumns, classMeta.getIdClassType());
    }

    /**
     * 递归收集类及其所有父类的字段（支持 @MappedSuperclass 继承）：
     *  - 父类字段在前、子类字段在后；
     *  - 仅沿 superClass 类继承链——接口字段不纳入；
     *  - seen 集合防循环继承（异常 PSI 场景防御）；
     *  - 父类本身标注 @MappedSuperclass 或 @Entity 时字段均纳入。
     */
    @NotNull
    private List<PsiField> collectFieldsIncludingSuper(@NotNull PsiClass clazz) {
        return collectFieldsIncludingSuper(clazz, new LinkedHashSet<>());
    }

    private List<PsiField> collectFieldsIncludingSuper(
            @NotNull PsiClass clazz,
            @NotNull Set<PsiClass> seen) {
        if (!seen.add(clazz)) return List.of();
        List<PsiField> superFields = clazz.getSuperClass() != null
                ? collectFieldsIncludingSuper(clazz.getSuperClass(), seen)
                : List.of();
        List<PsiField> selfFields = new ArrayList<>(Arrays.asList(clazz.getFields()));
        List<PsiField> result = new ArrayList<>(superFields);
        result.addAll(selfFields);
        return result;
    }

    /**
     * 解析单个字段为列信息（可能展开为多列——嵌入对象展平，T-J18）；
     * 非持久化字段（@Transient / 集合关联注解 / mappedBy 侧 @OneToOne / 复合外键等）返回空列表。
     */
    @NotNull
    private List<ColumnInfo> resolveColumns(
            @NotNull PsiField field,
            @NotNull Set<String> ignoredFields,
            @NotNull java.util.Map<String, String> classOverrides,
            @Nullable com.intellij.openapi.vfs.VirtualFile hostAnchor) {
        String fieldName = field.getName();

        // @Transient / @ColumnIgnore / @Ignore：非持久化字段，跳过
        // （SD8：spring-data @Transient；EQ8：@ColumnIgnore；XB：@Ignore——M44 统一轨）
        if (JpaMetadataReader.hasTransientLikeAnnotation(field)) return List.of();

        // 一对多 / 多对多 / 元素集合关联字段跳过（非简单列，对应集合类型属性；
        // 集合语义由 TableInfo.collectionAssociations 承载）
        if (hasAnnotation(field, ONE_TO_MANY_ANNOTATION) || hasAnnotation(field, MANY_TO_MANY_ANNOTATION)
                || JpaMetadataReader.findFlex(field, JpaMetadataReader.FQN_ELEMENT_COLLECTION) != null) {
            return List.of();
        }

        // SD10（M44 止血）：@MappedCollection 集合/嵌套实体字段不落父表列（止血期不做反向列展开）
        if (JpaMetadataReader.isMappedCollectionField(field)) return List.of();
        // SD10：无注解的嵌套实体引用字段 / 元素为实体的集合字段同样跳过——
        // 否则 SELECT/INSERT 出现不存在的列（宁缺列不出错误列）
        if (JpaMetadataReader.isNestedEntityOrEntityCollectionField(field)) return List.of();

        // M44：Easy-Query @Column(exist = false) / xbatis @TableField(exists = false) 字段跳过
        if (JpaMetadataReader.hasExistFalse(field)) return List.of();

        // @OneToOne(mappedBy=...) 无外键侧：外键列在对方表，本表无列（T-J7 侧别语义——二次 SELECT 标记）
        if (hasAnnotation(field, ONE_TO_ONE_ANNOTATION) && JpaMetadataReader.mappedByOf(field) != null) {
            return List.of();
        }

        // 忽略字段名配置（`;` 分隔，按 Java 字段名匹配）
        if (fieldName != null && ignoredFields.contains(fieldName)) return List.of();

        // serialVersionUID 默认忽略
        if (fieldName != null && fieldName.toLowerCase().replace("_", "").equals("serialversionuid")) {
            return List.of();
        }

        boolean isManyToOne = hasAnnotation(field, MANY_TO_ONE_ANNOTATION);
        boolean isOneToOne = hasAnnotation(field, ONE_TO_ONE_ANNOTATION);
        boolean isEmbeddedId = JpaMetadataReader.hasEmbeddedIdAnnotation(field);

        // @Embedded / @Embeddable / @EmbeddedId：嵌入对象字段展平（T-J18）；
        // SD8 形态（裸 @Embedded / 捷径 @Embedded.Nullable / @Embedded.Empty，T-Embedded D2）——
        // SD 判定在前取 prefix 语义，jakarta 轨兜底（裸 SD @Embedded 此前经短名轨命中，行为不回归）
        boolean sdEmbedded = JpaMetadataReader.isSpringDataEmbeddedField(field);
        if (isEmbeddedId || sdEmbedded || JpaMetadataReader.isEmbeddedField(field)) {
            String sdPrefix = sdEmbedded ? JpaMetadataReader.springDataEmbeddedPrefixOf(field) : "";
            return flattenEmbeddedColumns(field, isEmbeddedId, classOverrides, sdPrefix, hostAnchor);
        }

        // @JoinColumns 复合外键：多列外键无法以单列表达，完整清单记入 AssociationInfo.joinColumns（T-J7）
        if ((isManyToOne || isOneToOne) && JpaMetadataReader.hasCompositeJoinColumns(field)) {
            return List.of();
        }

        // 列名：优先 @Column(name→value 兜底，SD8)/@JoinColumn（@ManyToOne 外键列）/
        // xbatis @TableField(value)，否则驼峰转下划线
        boolean isId = isIdentifierField(field);
        List<JoinColumnRef> toOneJoinColumnRefs = isManyToOne || isOneToOne
                ? JpaMetadataReader.readToOneJoinColumns(field, fieldName) : List.<JoinColumnRef>of();
        String columnName;
        if (!toOneJoinColumnRefs.isEmpty()) {
            // @ManyToOne / @OneToOne + @JoinColumn：外键列名（含无注解时的推断名）
            columnName = toOneJoinColumnRefs.get(0).getName();
        } else {
            // @Column / @TableField：普通列名（SD8：name → value 双属性兜底）
            columnName = JpaMetadataReader.columnValueOrSnake(field, fieldName);
        }

        // 字段类型（参数回填引号推断用）；PSI 类型解析异常时兜底 null
        String fieldType;
        try {
            fieldType = field.getType().getCanonicalText();
        } catch (Exception e) {
            fieldType = null;
        }

        // 字段注释
        String comment = resolveComment(field);

        // 角色分组——主键置顶(PRIMARY)、关联外键(BUSINESS)、普通业务字段(BUSINESS)
        ColumnRole role = isId ? ColumnRole.PRIMARY : ColumnRole.BUSINESS;

        JoinColumnRef firstRef = toOneJoinColumnRefs.isEmpty() ? null : toOneJoinColumnRefs.get(0);
        return List.of(new ColumnInfo(fieldName, columnName, isId, false, fieldType, null, comment, role,
                firstRef != null ? firstRef.isInsertable() : JpaMetadataReader.columnInsertableOf(field),
                firstRef != null ? firstRef.isUpdatable() : JpaMetadataReader.columnUpdatableOf(field),
                isId ? JpaMetadataReader.generatedValueStrategy(field) : null,
                false,
                JpaMetadataReader.hasConvertAnnotation(field)));
    }

    /**
     * 展平嵌入对象字段为列清单（T-J18）：@AttributeOverride 覆写 > 内部字段 @Column > 兜底命名；
     * @EmbeddedId 展平列全部标记主键。嵌入类型不可解析等异常场景降级为单列形态（字段名推断列名），不丢字段。
     *
     * <p>T-Embedded D2：{@code sdPrefix} 为 SD @Embedded(prefix) 前缀，官方语义拼在嵌入对象每一列之前
     * （同一值对象多次嵌入防撞车）；非 SD 轨为空串，列名逐字节不变。</p>
     *
     * <p>G1 修复（prefix 不写重）：<b>@AttributeOverride 命中的列名是 JPA 轨最终列名</b>（Hibernate 运行期
     * 实际使用的列），SD prefix 不再叠加——同型值对象二次嵌入的混合声明里，覆写名通常已按
     * 「prefix + 列名」同口径书写（demo {@code CustomerEntity} 的 bizAddress：prefix=biz_ 且覆写
     * street→biz_street），叠加会产出 {@code biz_biz_street} 这类两轨都不存在的列名。无覆写时 prefix
     * 照旧拼在每一列之前（{@code shipping_street} 形态不回归）。</p>
     */
    @NotNull
    private List<ColumnInfo> flattenEmbeddedColumns(
            @NotNull PsiField field,
            boolean isPrimaryKey,
            @NotNull java.util.Map<String, String> classOverrides,
            @NotNull String sdPrefix,
            @Nullable com.intellij.openapi.vfs.VirtualFile hostAnchor) {
        String fieldName = field.getName();
        // 字段级 @AttributeOverride 优先于类级（合并顺序：右侧覆盖左侧同名键）
        java.util.Map<String, String> overrides = new LinkedHashMap<>(classOverrides);
        overrides.putAll(JpaMetadataReader.attributeOverrides(field));
        // T-EmbeddedColPrefix：嵌入列命名约定按「显式设置 > 所在模块的 Hibernate 命名配置探测 > 无前缀」解析；
        // T-EmbeddedHostCascade：实体模块判不出时按查询宿主模块级联（实体在依赖 JAR/lib、策略配在宿主的形态）
        com.intellij.openapi.project.Project project;
        try {
            project = field.getProject();
        } catch (Throwable ignored) {
            project = null;
        }
        com.intellij.openapi.vfs.VirtualFile anchor;
        try {
            anchor = field.getContainingFile() != null ? field.getContainingFile().getVirtualFile() : null;
        } catch (Throwable ignored) {
            anchor = null;
        }
        EmbeddedColumnNamingResolver.NamingResolution namingResolution =
                EmbeddedColumnNamingResolver.resolveDetailed(project, anchor, hostAnchor);
        List<JpaMetadataReader.EmbeddedColumnSpec> specs;
        try {
            specs = JpaMetadataReader.flattenEmbeddedColumns(field, overrides, namingResolution.naming());
        } catch (Throwable ignored) {
            specs = List.of();
        }
        // T-EmbeddedIdentify W1：嵌入列诊断日志——外部报障「@Embedded 不识别」的排障锚点。
        // 每个 @Embedded 字段一行 INFO（本函数只在嵌入分支被调用，天然免噪音）；展平失败降级单列升 WARN。
        try {
            String owner;
            try {
                owner = field.getContainingClass() != null ? field.getContainingClass().getName() : null;
            } catch (Throwable ignored) {
                owner = null;
            }
            if (owner == null) owner = "?";
            if (specs.isEmpty()) {
                String fallbackType;
                try {
                    fallbackType = field.getType().getCanonicalText();
                } catch (Throwable ignored) {
                    fallbackType = null;
                }
                LOG.warn("[T-EmbeddedIdentify] 嵌入展平降级单列（类型不可解析/全被跳过） entity=" + owner
                        + " field=" + fieldName + " fieldType=" + fallbackType
                        + " naming=" + namingResolution.naming() + "/" + namingResolution.source()
                        + " overrides=" + overrides.size());
            } else {
                StringBuilder summary = new StringBuilder();
                for (JpaMetadataReader.EmbeddedColumnSpec spec : specs) {
                    if (summary.length() > 0) summary.append(", ");
                    summary.append(spec.getFieldName()).append("→").append(spec.getColumnName());
                }
                LOG.info("[T-EmbeddedIdentify] entity=" + owner + " field=" + fieldName
                        + " naming=" + namingResolution.naming() + "/" + namingResolution.source()
                        + " sdPrefix=" + sdPrefix + " columns=[" + summary + "]");
            }
        } catch (Throwable ignored) {
        }
        if (specs.isEmpty()) {
            String fallbackType;
            try {
                fallbackType = field.getType().getCanonicalText();
            } catch (Throwable ignored) {
                fallbackType = null;
            }
            return List.of(new ColumnInfo(fieldName, NamingStrategy.INSTANCE.toSnakeCase(fieldName),
                    isPrimaryKey, false, fallbackType, null, resolveComment(field),
                    isPrimaryKey ? ColumnRole.PRIMARY : ColumnRole.BUSINESS,
                    true, true, null, true, false, fieldName));
        }
        List<ColumnInfo> result = new ArrayList<>();
        for (JpaMetadataReader.EmbeddedColumnSpec spec : specs) {
            String columnName = sdPrefix.isEmpty() || spec.isFromAttributeOverride()
                    ? spec.getColumnName()
                    : sdPrefix + spec.getColumnName();
            result.add(new ColumnInfo(spec.getFieldName(), columnName, isPrimaryKey, false,
                    spec.getFieldType(), null, resolveComment(spec.getField()),
                    isPrimaryKey ? ColumnRole.PRIMARY : ColumnRole.BUSINESS,
                    spec.isInsertable(), spec.isUpdatable(), null, true, false, fieldName,
                    spec.getEmbeddedPath()));
        }
        return result;
    }

    /**
     * 解析单个字段的关联信息（@ManyToOne / @OneToOne）。
     */
    @Nullable
    private AssociationInfo resolveAssociation(@NotNull PsiField field) {
        boolean isManyToOne = hasAnnotation(field, MANY_TO_ONE_ANNOTATION);
        boolean isOneToOne = hasAnnotation(field, ONE_TO_ONE_ANNOTATION);
        if (!isManyToOne && !isOneToOne) return null;

        String fieldName = field.getName();
        // 外键列清单：@JoinColumn(name="xxx") / @JoinColumns 复合 / 无注解时默认 {fieldName}_id
        List<JoinColumnRef> joinColumnRefs = JpaMetadataReader.readToOneJoinColumns(field, fieldName);
        // 既有 joinColumn 字段语义不变：单列为该列名，复合外键取首列（完整清单见 joinColumns）
        String joinColumn = joinColumnRefs.get(0).getName();
        // mappedBy 侧别：本表无外键，查询语义为按主键二次 SELECT（T-J7）
        String mappedBy = JpaMetadataReader.mappedByOf(field);

        // 目标实体类：从字段类型获取
        PsiClass targetClass = resolveTargetEntityClass(field);
        if (targetClass == null) return null;
        String targetTableName = resolveTargetTableName(targetClass);

        // 别名：实体类名首字母小写
        String targetName = targetClass.getName();
        String base = targetName != null ? targetName : fieldName;
        String targetAlias = base.substring(0, 1).toLowerCase() + base.substring(1);

        return new AssociationInfo(fieldName, joinColumn, targetTableName, targetAlias,
                joinColumnRefs.get(0).getReferencedColumnName(), joinColumnRefs, mappedBy,
                mappedBy == null, null,
                JpaMetadataReader.isNotFoundIgnore(field), JpaMetadataReader.hasMapsId(field));
    }

    /**
     * 解析 @ManyToOne/@OneToOne 字段的目标实体类。
     */
    @Nullable
    private PsiClass resolveTargetEntityClass(@NotNull PsiField field) {
        if (!(field.getType() instanceof PsiClassType type)) return null;
        return type.resolve();
    }

    /**
     * 从目标实体 PsiClass 获取表名。
     *
     * 优先 @Table(name="xxx")，否则按命名策略推断。
     */
    @NotNull
    private String resolveTargetTableName(@NotNull PsiClass targetClass) {
        String explicitTable = tableAnnotationValue(targetClass);
        if (explicitTable != null) return explicitTable;
        String className = targetClass.getName();
        if (className == null) return NamingStrategy.INSTANCE.toSnakeCase("unknown");
        return decorateTableName(inferTableName(className, targetClass.getProject()), targetClass.getProject());
    }

    // ------------------------------------------------------------------
    // 注释读取（四级优先级：SCHEMA > ANNOTATION > JAVADOC > LINE_COMMENT）
    // ------------------------------------------------------------------

    /**
     * 注释来源优先级（schema 预留）：
     * SCHEMA（数据源 schema 列/表注释）最高，当前实现 schema 返回 null——
     * 实际生效顺序 = 注解 > javadoc > 行注释。
     */
    public enum CommentSource {
        SCHEMA, ANNOTATION, JAVADOC, LINE_COMMENT
    }

    /**
     * 提取字段注释（PsiField）：注解白名单 > javadoc > 行注释。
     */
    @Nullable
    private String resolveComment(@NotNull PsiField field) {
        String docText = field.getDocComment() != null ? field.getDocComment().getText() : null;
        return firstNonBlank(Arrays.asList(
                null,
                annotationComment(field),
                javadocComment(docText),
                lineComment(field)));
    }

    /** 提取类级注释：类注解白名单 > 类 javadoc > 类上方行注释。 */
    @Nullable
    private String resolveComment(@NotNull PsiClass psiClass) {
        String docText = psiClass.getDocComment() != null ? psiClass.getDocComment().getText() : null;
        return firstNonBlank(Arrays.asList(
                null,
                annotationComment(psiClass),
                javadocComment(docText),
                lineComment(psiClass)));
    }

    /** 取最高优先级非空注释（按来源顺序，schema 为空时注解生效）。 */
    @Nullable
    private String firstNonBlank(@NotNull List<String> comments) {
        for (String c : comments) {
            if (c != null && !c.isBlank()) return c.trim();
        }
        return null;
    }

    /**
     * 注解注释提取：白名单注解按专属属性，通用属性（description/name/comment/desc）兜底；
     * 排除 JPA 核心映射注解（@Entity/@Table/@Column/@Id 等——name/value 是表名/列名非注释）。
     */
    @Nullable
    private String annotationComment(@NotNull PsiClass psi) {
        return annotationComment(psi.getModifierList() != null ? psi.getModifierList().getAnnotations() : new PsiAnnotation[0]);
    }

    private String annotationComment(@NotNull PsiField field) {
        return annotationComment(field.getModifierList() != null ? field.getModifierList().getAnnotations() : new PsiAnnotation[0]);
    }

    private String annotationComment(@NotNull PsiAnnotation[] annotations) {
        for (PsiAnnotation annotation : annotations) {
            String simpleName = annotation.getNameReferenceElement() != null
                    ? annotation.getNameReferenceElement().getReferenceName() : null;
            if (simpleName == null) continue;
            // JPA 核心映射注解不参与注释提取（name/value 是表名/列名）
            if (JPA_MAPPING_ANNOTATION_NAMES.contains(simpleName)) continue;
            // 白名单注解专属属性
            List<String> attrs = COMMENT_ANNOTATION_ATTRIBUTES.get(simpleName);
            if (attrs != null) {
                for (String attr : attrs) {
                    String v = attributeString(annotation, attr);
                    if (v != null && !v.isBlank()) return v;
                }
            }
            // 通用属性兜底
            for (String attr : COMMENT_ATTRIBUTE_NAMES) {
                String v = attributeString(annotation, attr);
                if (v != null && !v.isBlank()) return v;
            }
        }
        return null;
    }

    /**
     * JavaDoc 描述提取：去 /** *​/ 与行首 *，取 @ 标签前描述段落，空行折叠为空格。
     */
    @Nullable
    private String javadocComment(@Nullable String docText) {
        if (docText == null) return null;
        String text = docText;
        if (text.startsWith("/**")) text = text.substring(3);
        if (text.endsWith("*/")) text = text.substring(0, text.length() - 2);
        String result = Arrays.stream(text.split("\n"))
                .map(line -> {
                    String t = line.trim();
                    if (t.startsWith("*")) t = t.substring(1).trim();
                    return t;
                })
                .takeWhile(t -> !t.startsWith("@"))
                .filter(t -> !t.isEmpty())
                .collect(Collectors.joining(" "));
        return result.isEmpty() ? null : result;
    }

    /**
     * 行注释提取（兜底）：// xxx 行注释。
     */
    @Nullable
    private String lineComment(@NotNull PsiElement element) {
        // 路径 ①：元素自身文本首行（LightPlatform 注释绑定进 textRange）
        String ownFirstLine = element.getText().split("\n", 2)[0].trim();
        if (ownFirstLine.startsWith("//")) {
            String comment = ownFirstLine.substring(2).trim();
            if (!comment.isBlank()) return comment;
        }
        // 路径 ②：文档文本向上扫描
        int offset = element.getTextRange().getStartOffset();
        String before = element.getContainingFile().getText().substring(0, offset);
        String[] lines = before.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("@")) continue;
            if (line.startsWith("//")) {
                String comment = line.substring(2).trim();
                return comment.isBlank() ? null : comment;
            }
            return null;
        }
        return null;
    }

    /** 注解 → 注释属性白名单（swagger/easyexcel 等业务注解的注释属性）。 */
    private static final Map<String, List<String>> COMMENT_ANNOTATION_ATTRIBUTES = Map.of(
            "Schema", List.of("description"),
            "ApiModelProperty", List.of("value", "description"),
            "ExcelProperty", List.of("value"),
            "DictField", List.of("description", "comment", "name")
    );

    /** 通用注释属性（非白名单注解兜底提取，不取 value——value 常为标识符）。 */
    private static final List<String> COMMENT_ATTRIBUTE_NAMES = List.of("description", "name", "comment", "desc");

    /** JPA 核心映射注解（name/value 是表名/列名而非注释，不参与提取；含 M39 扩展元数据注解）。 */
    private static final Set<String> JPA_MAPPING_ANNOTATION_NAMES = Set.of(
            "Entity", "Table", "Column", "Id", "Transient",
            "ManyToOne", "OneToMany", "OneToOne", "ManyToMany",
            "JoinColumn", "JoinColumns", "JoinTable", "MappedSuperclass",
            // M39 元数据扩展（T-J7/T-J8/T-J14/T-J18）：name/value 是生成器名、SQL 脚本、覆写目标等，非注释
            "GeneratedValue", "SequenceGenerator", "SequenceGenerators",
            "TableGenerator", "TableGenerators", "Version",
            "Where", "SQLRestriction", "SoftDelete",
            "SQLDelete", "SQLDeleteAll", "SQLInsert", "SQLUpdate", "NotFound",
            "ElementCollection", "CollectionTable", "Embedded", "Embeddable", "EmbeddedId", "IdClass",
            "AttributeOverride", "AttributeOverrides", "MapsId", "Convert",
            "Inheritance", "DiscriminatorColumn", "DiscriminatorValue",
            "SecondaryTable", "SecondaryTables",
            "PrimaryKeyJoinColumn", "PrimaryKeyJoinColumns",
            "OrderBy", "OrderColumn"
    );

    // ------------------------------------------------------------------
    // 注解辅助
    // ------------------------------------------------------------------

    private boolean hasAnnotation(@NotNull PsiClass psi, @NotNull String qualifiedName) {
        return findAnnotation(psi, qualifiedName) != null;
    }

    private boolean hasAnnotation(@NotNull PsiField field, @NotNull String qualifiedName) {
        return findAnnotation(field, qualifiedName) != null;
    }

    /**
     * 按注解查找：优先全限定名；粘贴片段/测试环境注解类不可解析时，
     * 按短名兜底匹配（@Table → Table）。
     */
    @Nullable
    private PsiAnnotation findAnnotation(@NotNull PsiClass psi, @NotNull String qualifiedName) {
        if (psi.getModifierList() != null) {
            PsiAnnotation a = psi.getModifierList().findAnnotation(qualifiedName);
            if (a != null) return a;
        }
        return findAnnotationByShortName(
                psi.getModifierList() != null ? psi.getModifierList().getAnnotations() : null, qualifiedName);
    }

    private PsiAnnotation findAnnotation(@NotNull PsiField field, @NotNull String qualifiedName) {
        if (field.getModifierList() != null) {
            PsiAnnotation a = field.getModifierList().findAnnotation(qualifiedName);
            if (a != null) return a;
        }
        return findAnnotationByShortName(
                field.getModifierList() != null ? field.getModifierList().getAnnotations() : null, qualifiedName);
    }

    @Nullable
    private PsiAnnotation findAnnotationByShortName(
            @Nullable PsiAnnotation[] annotations,
            @NotNull String qualifiedName) {
        if (annotations == null) return null;
        String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        for (PsiAnnotation a : annotations) {
            if (a.getNameReferenceElement() != null
                    && simpleName.equals(a.getNameReferenceElement().getReferenceName())) {
                return a;
            }
        }
        return null;
    }

    /**
     * 读取注解的 value 属性字符串值（如 @Table("t_user") → t_user）。
     * 无注解或无值返回 null。
     */
    @Nullable
    private String annotationAttributeValue(@NotNull PsiClass psi, @NotNull String qualifiedName, @NotNull String attribute) {
        PsiAnnotation annotation = findAnnotation(psi, qualifiedName);
        if (annotation == null) return null;
        return attributeString(annotation, attribute);
    }

    @Nullable
    private String annotationAttributeValue(@NotNull PsiField field, @NotNull String qualifiedName, @NotNull String attribute) {
        PsiAnnotation annotation = findAnnotation(field, qualifiedName);
        if (annotation == null) return null;
        return attributeString(annotation, attribute);
    }

    /**
     * 类级表名注解取值（SD8，M44）：@Table `name` → `value` 双属性兜底——
     * jakarta @Table 只有 name 属性（value 兜底天然落空），relational/Easy-Query/xbatis @Table
     * 以 value 简写为最常用形态（`@Table("t_user")`）。查找轨道：jakarta/javax FQN + relational
     * FQN + 短名兜底（findAnnotation/FQN_XBATIS_TABLE 显式轨）。
     */
    private String tableAnnotationValue(@NotNull PsiClass psi) {
        String value = annotationAttributeValue(psi, TABLE_ANNOTATION, "name");
        if (value != null) return value;
        value = annotationAttributeValue(psi, TABLE_ANNOTATION, "value");
        if (value != null) return value;
        value = annotationAttributeValue(psi, JpaMetadataReader.FQN_RELATIONAL_TABLE, "name");
        if (value != null) return value;
        value = annotationAttributeValue(psi, JpaMetadataReader.FQN_RELATIONAL_TABLE, "value");
        if (value != null) return value;
        return annotationAttributeValue(psi, JpaMetadataReader.FQN_XBATIS_TABLE, "value");
    }

    /**
     * 字段主键判定（M44 统一轨）：jakarta/javax @Id（既有短名兜底）+ spring-data @Id（SD8）+
     * xbatis @TableId（XB）+ Easy-Query @Column(primaryKey = true)（EQ8）。
     */
    private boolean isIdentifierField(@NotNull PsiField field) {
        return hasAnnotation(field, ID_ANNOTATION)
                || hasAnnotation(field, JpaMetadataReader.FQN_SD_ID)
                || hasAnnotation(field, JpaMetadataReader.FQN_XBATIS_TABLE_ID)
                || isEasyQueryPrimaryKey(field);
    }

    /** EQ8：@Column(primaryKey = true)（Easy-Query 以 Column 注解属性标记主键）。 */
    private boolean isEasyQueryPrimaryKey(@NotNull PsiField field) {
        PsiAnnotation column = JpaMetadataReader.findFlex(field, JpaMetadataReader.FQN_EASY_QUERY_COLUMN);
        if (column == null) return false;
        return Boolean.TRUE.equals(JpaMetadataReader.attrBoolean(column, "primaryKey"));
    }

    @Nullable
    private String attributeString(@NotNull PsiAnnotation annotation, @NotNull String attribute) {
        PsiAnnotationMemberValue memberValue = annotation.findAttributeValue(attribute);
        if (memberValue == null) return null;
        if (!(memberValue instanceof PsiLiteralExpression literal)) return null;
        Object value = literal.getValue();
        if (!(value instanceof String s)) return null;
        // 空白视为未指定（回退命名策略）
        return s.isBlank() ? null : s;
    }

    // ------------------------------------------------------------------
    // 伴生对象（静态）成员
    // ------------------------------------------------------------------

    private static final Logger LOG = Logger.getInstance(JpaEntityResolver.class);

    /** JPA 注解全限定名（jakarta.persistence）。 */
    private static final String ENTITY_ANNOTATION = "jakarta.persistence.Entity";
    private static final String TABLE_ANNOTATION = "jakarta.persistence.Table";
    private static final String ID_ANNOTATION = "jakarta.persistence.Id";
    private static final String MANY_TO_ONE_ANNOTATION = "jakarta.persistence.ManyToOne";
    private static final String ONE_TO_MANY_ANNOTATION = "jakarta.persistence.OneToMany";
    private static final String ONE_TO_ONE_ANNOTATION = "jakarta.persistence.OneToOne";
    private static final String MANY_TO_MANY_ANNOTATION = "jakarta.persistence.ManyToMany";
    private static final String JOIN_COLUMN_ANNOTATION = "jakarta.persistence.JoinColumn";
    private static final String JOIN_TABLE_ANNOTATION = "jakarta.persistence.JoinTable";
    private static final String MAPPED_SUPERCLASS_ANNOTATION = "jakarta.persistence.MappedSuperclass";

    /** JPA 实体解析缓存键（PsiFile + classQualifiedName + configSignature + 宿主锚点路径）。 */
    private static final class JpaCacheKey {
    private final String fileUrl;
    /** T-Perf-18：文件实例同一性辅助键（同 EntityResolver——light/mock 文件 url 可重复）。 */
    private final int fileIdentity;
    private final String classQualifiedName;
    private final String configSignature;
    /** T-EmbeddedHostCascade：查询宿主模块路径（命名档随宿主判出，不同宿主不得共用条目；null=未传宿主）。 */
    private final String hostAnchorPath;

    public JpaCacheKey(String fileUrl, int fileIdentity, String classQualifiedName, String configSignature,
            String hostAnchorPath) {
        this.fileUrl = fileUrl;
        this.fileIdentity = fileIdentity;
        this.classQualifiedName = classQualifiedName;
        this.configSignature = configSignature;
        this.hostAnchorPath = hostAnchorPath;
    }

    public String getFileUrl() { return fileUrl; }
    public String fileUrl() { return fileUrl; }
    public int getFileIdentity() { return fileIdentity; }
    public int fileIdentity() { return fileIdentity; }
    public String getClassQualifiedName() { return classQualifiedName; }
    public String classQualifiedName() { return classQualifiedName; }
    public String getConfigSignature() { return configSignature; }
    public String configSignature() { return configSignature; }
    public String getHostAnchorPath() { return hostAnchorPath; }
    public String hostAnchorPath() { return hostAnchorPath; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaCacheKey)) return false;
        JpaCacheKey that = (JpaCacheKey) o;
        return this.fileIdentity == that.fileIdentity && java.util.Objects.equals(this.fileUrl, that.fileUrl) && java.util.Objects.equals(this.classQualifiedName, that.classQualifiedName) && java.util.Objects.equals(this.configSignature, that.configSignature) && java.util.Objects.equals(this.hostAnchorPath, that.hostAnchorPath);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fileUrl, fileIdentity, classQualifiedName, configSignature, hostAnchorPath);
    }

    @Override
    public String toString() {
        return "JpaCacheKey[" + "fileUrl=" + fileUrl + ", " + "fileIdentity=" + fileIdentity + ", " + "classQualifiedName=" + classQualifiedName + ", " + "configSignature=" + configSignature + ", " + "hostAnchorPath=" + hostAnchorPath + "]";
    }
}

    /** 缓存条目：文件修改戳 + 解析结果。 */
    private static final class JpaEntry {
    private final long stamp;
    private final TableInfo value;

    public JpaEntry(long stamp, TableInfo value) {
        this.stamp = stamp;
        this.value = value;
    }

    public long getStamp() { return stamp; }
    public long stamp() { return stamp; }
    public TableInfo getValue() { return value; }
    public TableInfo value() { return value; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaEntry)) return false;
        JpaEntry that = (JpaEntry) o;
        return java.util.Objects.equals(this.stamp, that.stamp) && java.util.Objects.equals(this.value, that.value);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(stamp, value);
    }

    @Override
    public String toString() {
        return "JpaEntry[" + "stamp=" + stamp + ", " + "value=" + value + "]";
    }
}

    /** 缓存键 → 条目（项目级，跟随 JpaEntityResolver 生命周期）。 */
    private static final ConcurrentHashMap<JpaCacheKey, JpaEntry> cache = new ConcurrentHashMap<>();

    /**
     * 券02（T-PerfSweep27-02）缓存容量上限：与 {@link cn.song.mybatisplus.visualizer.psi.EntityResolver}
     * 同款——配置签名/宿主锚点变化后旧键永不命中也永不淘汰，超限整体清空（HbmXml 负缓存范式）。
     * internal 可写仅测试缝（生产不改）——包私有（Kotlin internal 等价）。
     */
    static int maxCacheEntries = 4096;

    /** 清空缓存（配置变更 / 项目关闭时调用）。 */
    public static void clearCache() {
        cache.clear();
    }

    /** 当前缓存条目数（测试 / 诊断用）。 */
    public static int cacheSize() {
        return cache.size();
    }

    /**
     * 无 @Table 时的表名推断（默认 SNAKE 驼峰转下划线；CAMEL 纯保留驼峰仅首字母小写）。
     *
     * @param project 实体所属项目（决定读哪一份项目级设置；null 见 {@link #effectiveTableSetting}）
     */
    private static String inferTableName(@NotNull String className, Project project) {
        String strategy = effectiveTableSetting(ProjectPluginSettings::effectiveTableNameInference, project);
        if (strategy == null) {
            try {
                strategy = PluginSettings.getInstance().getState().getTableNameInference();
            } catch (Exception e) {
                strategy = PluginSettings.INFERENCE_SNAKE;
            }
        }
        if (PluginSettings.INFERENCE_CAMEL.equals(strategy)) {
            return className.substring(0, 1).toLowerCase() + className.substring(1);
        }
        return NamingStrategy.INSTANCE.toSnakeCase(className);
    }

    /** Kotlin 默认参数等价重载：{@code inferTableName(className)}（project = null）。 */
    private static String inferTableName(@NotNull String className) {
        return inferTableName(className, null);
    }

    /**
     * 统一表名修饰链：
     * 最终表名 = 添加前缀 + 移除后缀(移除前缀(表名)) + 添加后缀。
     *
     * @param project 实体所属项目（决定读哪一份项目级设置；null 见 {@link #effectiveTableSetting}）
     */
    private static String decorateTableName(@NotNull String raw, Project project) {
        String removedPrefix = raw;
        for (String p : removeTablePrefixList()) {
            if (raw.startsWith(p)) {
                removedPrefix = raw.substring(p.length());
                break;
            }
        }
        String removedSuffix = removedPrefix;
        for (String s : removeTableSuffixList()) {
            if (removedPrefix.endsWith(s)) {
                removedSuffix = removedPrefix.substring(0, removedPrefix.length() - s.length());
                break;
            }
        }
        return tablePrefix(project) + removedSuffix + tableSuffix(project);
    }

    /** Kotlin 默认参数等价重载：{@code decorateTableName(raw)}（project = null）。 */
    private static String decorateTableName(@NotNull String raw) {
        return decorateTableName(raw, null);
    }

    /**
     * 实体类不可解析的兜底推断统一入口——按配置推断策略（SNAKE/CAMEL）+ 统一修饰链，
     * 与 {@link #resolve} 的推断路径语义一致。
     *
     * @param project 调用方已知的所属项目；调用方只有类名字符串时留空——
     *                留空沿改动前的「首个打开项目」取值（见 {@link #effectiveTableSetting}），不引入新行为。
     */
    public static String inferDecoratedTableName(@NotNull String className, Project project) {
        return decorateTableName(inferTableName(className, project), project);
    }

    /** Kotlin 默认参数等价重载：{@code inferDecoratedTableName(className)}（project = null）。 */
    public static String inferDecoratedTableName(@NotNull String className) {
        return inferDecoratedTableName(className, null);
    }

    /** `;` 分隔配置解析为列表（trim + 去空；设置服务不可用时兜底空列表）。 */
    private static List<String> listSetting(@NotNull Function<PluginSettings.State, String> get) {
        String raw;
        try {
            raw = get.apply(PluginSettings.getInstance().getState());
        } catch (Exception e) {
            raw = "";
        }
        return Arrays.stream(raw.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * T-SettingsTruth P4 / T-Fix-SettingsScope：项目级表配置读取入口——{@code project} 对应项目
     * 的 {@link ProjectPluginSettings} 启用时 effectiveXxx 覆盖全局；无项目上下文/服务不可用/
     * 项目值为空时返回 null（调用方回退全局设置，与原全局读取行为一致）。
     *
     * <p>{@code project} 为 null 时沿用改动前的「首个打开项目」：多项目同开时该取值取决于 IDE
     * 打开顺序（本次修复的歧义点），但<b>显式传入 project 的路径已不再受它影响</b>；
     * 只有「仅有类名字符串、拿不到 PsiClass」的兜底调用方才会走到这里。</p>
     *
     * @param get     从项目级设置读取有效值
     * @param project 目标项目；null = 未提供（兜底路径）
     */
    private static String effectiveTableSetting(@NotNull Function<ProjectPluginSettings, String> get, Project project) {
        // runCatching 包裹整体：纯单测（无 Application/ProjectManager 实例）与无打开项目时
        // 一律降级 null → 调用方回退全局设置，与原全局读取行为一致
        try {
            Project target = project;
            if (target == null) {
                Project[] openProjects = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
                target = openProjects.length > 0 ? openProjects[0] : null;
            }
            if (target == null) return null;
            String value = get.apply(ProjectPluginSettings.getInstance(target));
            return value != null && !value.isBlank() ? value : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String tablePrefix(Project project) {
        String effective = effectiveTableSetting(ProjectPluginSettings::effectiveTablePrefix, project);
        if (effective != null) return effective;
        try {
            return PluginSettings.getInstance().getState().getTablePrefix();
        } catch (Exception e) {
            return "";
        }
    }

    /** Kotlin 默认参数等价重载：{@code tablePrefix()}（project = null）。 */
    private static String tablePrefix() {
        return tablePrefix(null);
    }

    private static String tableSuffix(Project project) {
        String effective = effectiveTableSetting(ProjectPluginSettings::effectiveTableSuffix, project);
        if (effective != null) return effective;
        try {
            return PluginSettings.getInstance().getState().getTableSuffix();
        } catch (Exception e) {
            return "";
        }
    }

    /** Kotlin 默认参数等价重载：{@code tableSuffix()}（project = null）。 */
    private static String tableSuffix() {
        return tableSuffix(null);
    }

    private static List<String> removeTablePrefixList() {
        return listSetting(s -> s.getRemoveTablePrefix());
    }

    private static List<String> removeTableSuffixList() {
        return listSetting(s -> s.getRemoveTableSuffix());
    }

    /** 忽略字段名集合（`;` 分隔，按 Java 字段名匹配）。 */
    private static Set<String> ignoredFieldNames(Project project) {
        String effective = effectiveTableSetting(ProjectPluginSettings::effectiveIgnoredFields, project);
        if (effective != null) {
            return new LinkedHashSet<>(Arrays.stream(effective.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        }
        return new LinkedHashSet<>(listSetting(s -> s.getIgnoredFields()));
    }

    /** Kotlin 默认参数等价重载：{@code ignoredFieldNames()}（project = null）。 */
    private static Set<String> ignoredFieldNames() {
        return ignoredFieldNames(null);
    }
}
