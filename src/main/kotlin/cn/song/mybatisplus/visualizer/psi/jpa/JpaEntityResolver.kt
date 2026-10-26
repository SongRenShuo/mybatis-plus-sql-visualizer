package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
import cn.song.mybatisplus.visualizer.model.AssociationInfo
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRole
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.ColumnNameCalibrator
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import cn.song.mybatisplus.visualizer.util.TableConfigSignature
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import java.util.concurrent.ConcurrentHashMap

/**
 * JPA 实体解析器：从 JPA 注解实体 [PsiClass] 解析出 [TableInfo]。
 *
 * 规则（对齐 JPA 规范 / Hibernate 默认行为）：
 *  - 表名：优先 `@Table(name="xxx")` 注解，否则按命名策略由类名推断（驼峰转下划线）
 *  - 列名：优先 `@Column(name="xxx")` 注解，否则按命名策略由字段名推断
 *  - 主键：`@Id` 标记；`@EmbeddedId` 展平内部字段并全部标记主键（T-J18）
 *  - `@Transient` 字段（非持久化）跳过
 *  - `@ManyToOne` + `@JoinColumn(name="xxx")` 解析为外键列；`@JoinColumns` 复合外键不落单列，
 *    完整清单记入 AssociationInfo.joinColumns（T-J7）；@OneToOne(mappedBy=...) 无外键侧不落列（T-J7）
 *  - `@OneToMany` / `@ManyToMany` / `@ElementCollection` 集合字段跳过（非简单列），
 *    集合关联元数据（中间表/集合表/mappedBy/OrderBy）记入 TableInfo.collectionAssociations（T-J7/T-J18）
 *  - `@Embedded` / `@Embeddable` 嵌入对象字段展平，列名按 @AttributeOverride 覆写（T-J18）
 *  - `@GeneratedValue(strategy)` 记入主键列（T-J14）；@SequenceGenerator/@TableGenerator 记入 TableInfo
 *  - Hibernate `@Where`/`@SQLRestriction`/`@SoftDelete`/`@SQLDelete` 族记入 TableInfo（T-J8）；
 *    jakarta/javax `@Version` 记入 versionColumn（与 MP @Version 同列语义）
 *  - `@Table(schema/catalog)`、`@SecondaryTable(s)`、`@Inheritance`、`@Discriminator*`、`@IdClass` 等
 *    映射深水区注解记入 TableInfo 对应字段（T-J18）
 *  - `@MappedSuperclass` 父类字段继承（父类字段在前，子类字段在后）
 *  - 字段按角色分组稳定排序——`@Id`(PRIMARY) 置顶、业务字段(BUSINESS) 居中、
 *    审计字段(AUDIT) 与系统字段(SYSTEM) 沉底，组内保持声明顺序
 *  - 注解识别：jakarta / javax 双包名全限定名 + 短名兜底（三轨，见 [JpaMetadataReader]）
 *  - 解析结果按「PsiFile + classQualifiedName + configSignature」三维缓存，
 *    文件修改戳（modificationStamp）+ 配置签名双维度失效
 *  - O-58：返回边界接 [cn.song.mybatisplus.visualizer.psi.ColumnNameCalibrator]——数据源可用时用真实
 *    列集合纠正猜测出的列名，不可用/查不了/开关关闭时逐字段原样返回
 */
class JpaEntityResolver {

    /**
     * 解析 JPA 实体类为表信息（带缓存）。
     *
     * 缓存键：PsiFile + classQualifiedName + configSignature（三维）。
     * 失效机制：
     *  - 文件修改（modificationStamp 变化）→ 缓存自然失效
     *  - 配置签名变化（tableNameInference 等设置变更）→ 缓存失效
     *
     * O-58（列名数据源校正）：缓存**继续只存未校正的 PSI 产物**，校正在返回前作为一步廉价后处理
     * （[ColumnNameCalibrator.calibrate]）执行——DDL 变更的失效面因此只有数据源 schema 缓存一处
     * （已登记 MetadataInvalidator），不必给实体缓存键扩 DB 签名。
     *
     * @param entityClass JPA 实体 PsiClass
     * @return 表信息；类名缺失等异常情况返回带推断表名、空列的表信息（保证不抛异常）
     */
    fun resolve(entityClass: PsiClass): TableInfo {
        val className = entityClass.name ?: return TableInfo("", "", emptyList())
        val project = entityClass.project
        val file = entityClass.containingFile
            ?: return ColumnNameCalibrator.calibrate(project, resolveInternal(entityClass))
        val qName = entityClass.qualifiedName ?: className
        val configSig = TableConfigSignature.projectSignature(project)

        // T-Perf-18：缓存键不持 PsiFile 强引用（VirtualFile.url 字符串标识）
        val key = JpaCacheKey(file.virtualFile?.url, System.identityHashCode(file), qName, configSig)
        val stamp = file.modificationStamp
        cache[key]?.let { entry ->
            if (entry.stamp == stamp) return ColumnNameCalibrator.calibrate(project, entry.value)
        }

        val value = resolveInternal(entityClass)
        cache[key] = JpaEntry(stamp, value)
        return ColumnNameCalibrator.calibrate(project, value)
    }

    /**
     * 内部解析（完整 PSI 遍历，缓存未命中时调用）。
     */
    private fun resolveInternal(entityClass: PsiClass): TableInfo {
        val className = entityClass.name ?: return TableInfo("", "", emptyList())
        // 类级元数据：schema/catalog、限制子句、软删除、自定义脚本、生成器、副表、继承与判别、@IdClass
        // （T-J8 / T-J14 / T-J18，读取失败整体降级为空元数据）
        val classMeta = JpaMetadataReader.readClassMeta(entityClass)
        // 表名：优先 @Table 注解（SD8：name → value 双属性兜底——relational @Table("t_user") value
        // 简写最常用），否则按命名策略由类名推断
        val tableName = tableAnnotationValue(entityClass)
            ?: decorateTableName(inferTableName(className, entityClass.project), entityClass.project)

        // 递归父类收集字段（支持 @MappedSuperclass 继承；父类字段在前、子类字段在后）
        val fields = collectFieldsIncludingSuper(entityClass)
        val ignoredFields = ignoredFieldNames(entityClass.project)
        // 类级 @AttributeOverride（嵌入列名覆写，字段级覆写优先于类级，T-J18）
        val classOverrides = JpaMetadataReader.attributeOverrides(entityClass)
        // 按角色分组稳定排序（PRIMARY 置顶 → BUSINESS 居中 → AUDIT/SYSTEM 沉底，组内保持声明顺序）
        val columns = fields.flatMap { field -> resolveColumns(field, ignoredFields, classOverrides) }
            .sortedWith(compareBy { it.role })
            // 去重：同一列名只保留第一个（简单字段优先于 @ManyToOne 关联字段）
            // 示例：departmentId(@Column) 和 department(@ManyToOne+@JoinColumn) 同映射 department_id
            .distinctBy { it.columnName }
        // 表注释（类级注解 > javadoc > 行注释；schema 预留最高优先级，当前返回 null）
        val comment = resolveComment(entityClass)
        // 收集 @ManyToOne / @OneToOne 关联元数据（T-J7：referencedColumnName/复合外键/mappedBy 侧别/@MapsId/@NotFound）
        val associations = fields.mapNotNull { field -> resolveAssociation(field) }
        // 集合关联元数据（T-J7/T-J18：@OneToMany/@ManyToMany/@ElementCollection → 中间表/集合表语义）
        val collectionAssociations = fields.mapNotNull { field ->
            JpaMetadataReader.readCollectionAssociation(field, ::resolveTargetTableName, tableName)
        }
        // jakarta/javax + spring-data/Easy-Query/xbatis @Version → versionColumn（T-J8/EQ9/XB）
        val versionColumn = fields.firstOrNull { JpaMetadataReader.hasAnyVersionAnnotation(it) }
            ?.let { versionField -> columns.firstOrNull { it.fieldName == versionField.name }?.columnName }
        // EQ9/XB：@LogicDelete 标记字段 → logicDeleteColumn（可解析时）
        val logicDeleteColumn = fields.firstNotNullOfOrNull { field ->
            field.name?.let { JpaMetadataReader.logicDeleteColumnOf(field, it) }
        }
        // 复合主键列清单（T-J18：@EmbeddedId / @IdClass / 多 @Id，声明顺序；单主键为空列表）
        val hasEmbeddedId = fields.any { JpaMetadataReader.hasEmbeddedIdAnnotation(it) }
        val idColumns = columns.filter { it.isId }.map { it.columnName }
        val compositeIdColumns =
            if (hasEmbeddedId || classMeta.idClassType != null || idColumns.size > 1) idColumns else emptyList()
        return TableInfo(
            entityClassName = className,
            tableName = tableName,
            columns = columns,
            logicDeleteColumn = logicDeleteColumn,
            versionColumn = versionColumn,
            comment = comment,
            associations = associations,
            schema = classMeta.schema,
            catalog = classMeta.catalog,
            restrictionClause = classMeta.restrictionClause,
            softDelete = classMeta.softDelete,
            customSqlScripts = classMeta.customSqlScripts,
            sequenceGenerators = classMeta.sequenceGenerators,
            tableGenerators = classMeta.tableGenerators,
            collectionAssociations = collectionAssociations,
            secondaryTables = classMeta.secondaryTables,
            primaryKeyJoinColumns = classMeta.primaryKeyJoinColumns,
            inheritanceStrategy = classMeta.inheritanceStrategy,
            discriminatorColumn = classMeta.discriminatorColumn,
            discriminatorValue = classMeta.discriminatorValue,
            compositeIdColumns = compositeIdColumns,
            idClassType = classMeta.idClassType,
        )
    }

    /**
     * 递归收集类及其所有父类的字段（支持 @MappedSuperclass 继承）：
     *  - 父类字段在前、子类字段在后；
     *  - 仅沿 superClass 类继承链——接口字段不纳入；
     *  - seen 集合防循环继承（异常 PSI 场景防御）；
     *  - 父类本身标注 @MappedSuperclass 或 @Entity 时字段均纳入。
     */
    private fun collectFieldsIncludingSuper(
        clazz: PsiClass,
        seen: MutableSet<PsiClass> = mutableSetOf(),
    ): List<PsiField> {
        if (!seen.add(clazz)) return emptyList()
        val superFields = clazz.superClass?.let { collectFieldsIncludingSuper(it, seen) } ?: emptyList()
        return superFields + clazz.fields.toList()
    }

    /**
     * 解析单个字段为列信息（可能展开为多列——嵌入对象展平，T-J18）；
     * 非持久化字段（@Transient / 集合关联注解 / mappedBy 侧 @OneToOne / 复合外键等）返回空列表。
     */
    private fun resolveColumns(
        field: PsiField,
        ignoredFields: Set<String>,
        classOverrides: Map<String, String>,
    ): List<ColumnInfo> {
        val fieldName = field.name

        // @Transient / @ColumnIgnore / @Ignore：非持久化字段，跳过
        // （SD8：spring-data @Transient；EQ8：@ColumnIgnore；XB：@Ignore——M44 统一轨）
        if (JpaMetadataReader.hasTransientLikeAnnotation(field)) {
            return emptyList()
        }

        // 一对多 / 多对多 / 元素集合关联字段跳过（非简单列，对应集合类型属性；
        // 集合语义由 TableInfo.collectionAssociations 承载）
        if (hasAnnotation(field, ONE_TO_MANY_ANNOTATION) || hasAnnotation(field, MANY_TO_MANY_ANNOTATION) ||
            JpaMetadataReader.findFlex(field, JpaMetadataReader.FQN_ELEMENT_COLLECTION) != null
        ) {
            return emptyList()
        }

        // SD10（M44 止血）：@MappedCollection 集合/嵌套实体字段不落父表列（止血期不做反向列展开）
        if (JpaMetadataReader.isMappedCollectionField(field)) {
            return emptyList()
        }
        // SD10：无注解的嵌套实体引用字段 / 元素为实体的集合字段同样跳过——
        // 否则 SELECT/INSERT 出现不存在的列（宁缺列不出错误列）
        if (JpaMetadataReader.isNestedEntityOrEntityCollectionField(field)) {
            return emptyList()
        }

        // M44：Easy-Query @Column(exist = false) / xbatis @TableField(exists = false) 字段跳过
        if (JpaMetadataReader.hasExistFalse(field)) {
            return emptyList()
        }

        // @OneToOne(mappedBy=...) 无外键侧：外键列在对方表，本表无列（T-J7 侧别语义——二次 SELECT 标记）
        if (hasAnnotation(field, ONE_TO_ONE_ANNOTATION) && JpaMetadataReader.mappedByOf(field) != null) {
            return emptyList()
        }

        // 忽略字段名配置（`;` 分隔，按 Java 字段名匹配）
        if (fieldName in ignoredFields) {
            return emptyList()
        }

        // serialVersionUID 默认忽略（对齐 JPA/Hibernate 不映射 static 字段语义）
        if (fieldName.lowercase().replace("_", "") == "serialversionuid") {
            return emptyList()
        }

        val isManyToOne = hasAnnotation(field, MANY_TO_ONE_ANNOTATION)
        val isOneToOne = hasAnnotation(field, ONE_TO_ONE_ANNOTATION)
        val isEmbeddedId = JpaMetadataReader.hasEmbeddedIdAnnotation(field)

        // @Embedded / @Embeddable / @EmbeddedId：嵌入对象字段展平（T-J18）；
        // SD8 形态（裸 @Embedded / 捷径 @Embedded.Nullable / @Embedded.Empty，T-Embedded D2）——
        // SD 判定在前取 prefix 语义，jakarta 轨兜底（裸 SD @Embedded 此前经短名轨命中，行为不回归）
        val sdEmbedded = JpaMetadataReader.isSpringDataEmbeddedField(field)
        if (isEmbeddedId || sdEmbedded || JpaMetadataReader.isEmbeddedField(field)) {
            val sdPrefix = if (sdEmbedded) JpaMetadataReader.springDataEmbeddedPrefixOf(field) else ""
            return flattenEmbeddedColumns(field, isPrimaryKey = isEmbeddedId, classOverrides, sdPrefix)
        }

        // @JoinColumns 复合外键：多列外键无法以单列表达，完整清单记入 AssociationInfo.joinColumns（T-J7）
        if ((isManyToOne || isOneToOne) && JpaMetadataReader.hasCompositeJoinColumns(field)) {
            return emptyList()
        }

        // 列名：优先 @Column(name→value 兜底，SD8)/@JoinColumn（@ManyToOne 外键列）/
        // xbatis @TableField(value)，否则驼峰转下划线
        val isId = isIdentifierField(field)
        val toOneJoinColumnRefs =
            if (isManyToOne || isOneToOne) JpaMetadataReader.readToOneJoinColumns(field, fieldName) else emptyList()
        val columnName = when {
            // @ManyToOne / @OneToOne + @JoinColumn：外键列名（含无注解时的推断名）
            toOneJoinColumnRefs.isNotEmpty() -> toOneJoinColumnRefs.first().name
            // @Column / @TableField：普通列名（SD8：name → value 双属性兜底）
            else -> JpaMetadataReader.columnValueOrSnake(field, fieldName)
        }

        // 字段类型（参数回填引号推断用）；PSI 类型解析异常时兜底 null
        val fieldType = runCatching { field.type.canonicalText }.getOrNull()

        // 字段注释（注解 > javadoc > 行注释；schema 预留最高优先级，当前返回 null）
        val comment = resolveComment(field)

        // 角色分组——主键置顶(PRIMARY)、关联外键(BUSINESS)、普通业务字段(BUSINESS)
        val role = when {
            isId -> ColumnRole.PRIMARY
            else -> ColumnRole.BUSINESS
        }

        return listOf(
            ColumnInfo(
                fieldName = fieldName,
                columnName = columnName,
                isId = isId,
                idTypeAuto = false,
                fieldType = fieldType,
                fill = null,
                comment = comment,
                role = role,
                insertable = toOneJoinColumnRefs.firstOrNull()?.insertable ?: JpaMetadataReader.columnInsertableOf(field),
                updatable = toOneJoinColumnRefs.firstOrNull()?.updatable ?: JpaMetadataReader.columnUpdatableOf(field),
                generatedValueStrategy = if (isId) JpaMetadataReader.generatedValueStrategy(field) else null,
                converted = JpaMetadataReader.hasConvertAnnotation(field),
            ),
        )
    }

    /**
     * 展平嵌入对象字段为列清单（T-J18）：@AttributeOverride 覆写 > 内部字段 @Column > 驼峰转下划线；
     * @EmbeddedId 展平列全部标记主键。嵌入类型不可解析等异常场景降级为单列形态（字段名推断列名），不丢字段。
     *
     * T-Embedded D2：[sdPrefix] 为 SD @Embedded(prefix) 前缀，官方语义拼在嵌入对象每一列之前
     * （同一值对象多次嵌入防撞车）；非 SD 轨为空串，列名逐字节不变。
     *
     * G1 修复（prefix 不写重）：**@AttributeOverride 命中的列名是 JPA 轨最终列名**（Hibernate 运行期
     * 实际使用的列），SD prefix 不再叠加——同型值对象二次嵌入的混合声明里，覆写名通常已按
     * 「prefix + 列名」同口径书写（demo `CustomerEntity` 的 bizAddress：prefix=biz_ 且覆写
     * street→biz_street），叠加会产出 `biz_biz_street` 这类两轨都不存在的列名。无覆写时 prefix
     * 照旧拼在每一列之前（`shipping_street` 形态不回归）。
     */
    private fun flattenEmbeddedColumns(
        field: PsiField,
        isPrimaryKey: Boolean,
        classOverrides: Map<String, String>,
        sdPrefix: String = "",
    ): List<ColumnInfo> {
        val fieldName = field.name
        // 字段级 @AttributeOverride 优先于类级（合并顺序：右侧覆盖左侧同名键）
        val overrides = classOverrides + JpaMetadataReader.attributeOverrides(field)
        // T-EmbeddedColPrefix：嵌入列命名约定按「显式设置 > 所在模块的 Hibernate 命名配置探测 > 无前缀」解析
        val namingResolution = EmbeddedColumnNamingResolver.resolveDetailed(
            runCatching { field.project }.getOrNull(),
            runCatching { field.containingFile?.virtualFile }.getOrNull(),
        )
        val specs = runCatching { JpaMetadataReader.flattenEmbeddedColumns(field, overrides, namingResolution.naming) }
            .getOrDefault(emptyList())
        // T-EmbeddedIdentify W1：嵌入列诊断日志——外部报障「@Embedded 不识别」的排障锚点。
        // 每个 @Embedded 字段一行 INFO（本函数只在嵌入分支被调用，天然免噪音）；展平失败降级单列升 WARN。
        runCatching {
            val owner = runCatching { field.containingClass?.name }.getOrNull() ?: "?"
            if (specs.isEmpty()) {
                val fallbackType = runCatching { field.type.canonicalText }.getOrNull()
                LOG.warn(
                    "[T-EmbeddedIdentify] 嵌入展平降级单列（类型不可解析/全被跳过） entity=$owner field=$fieldName " +
                        "fieldType=$fallbackType naming=${namingResolution.naming}/${namingResolution.source} " +
                        "overrides=${overrides.size}",
                )
            } else {
                val summary = specs.joinToString(", ") { "${it.fieldName}→${it.columnName}" }
                LOG.info(
                    "[T-EmbeddedIdentify] entity=$owner field=$fieldName " +
                        "naming=${namingResolution.naming}/${namingResolution.source} sdPrefix=$sdPrefix " +
                        "columns=[$summary]",
                )
            }
        }
        if (specs.isEmpty()) {
            val fallbackType = runCatching { field.type.canonicalText }.getOrNull()
            return listOf(
                ColumnInfo(
                    fieldName = fieldName,
                    columnName = NamingStrategy.toSnakeCase(fieldName),
                    isId = isPrimaryKey,
                    idTypeAuto = false,
                    fieldType = fallbackType,
                    fill = null,
                    comment = resolveComment(field),
                    role = if (isPrimaryKey) ColumnRole.PRIMARY else ColumnRole.BUSINESS,
                    embedded = true,
                    embeddedParent = fieldName,
                ),
            )
        }
        return specs.map { spec ->
            ColumnInfo(
                fieldName = spec.fieldName,
                columnName = if (sdPrefix.isEmpty() || spec.fromAttributeOverride) {
                    spec.columnName
                } else {
                    sdPrefix + spec.columnName
                },
                isId = isPrimaryKey,
                idTypeAuto = false,
                fieldType = spec.fieldType,
                fill = null,
                comment = resolveComment(spec.field),
                role = if (isPrimaryKey) ColumnRole.PRIMARY else ColumnRole.BUSINESS,
                insertable = spec.insertable,
                updatable = spec.updatable,
                embedded = true,
                embeddedParent = fieldName,
                embeddedPath = spec.embeddedPath,
            )
        }
    }

    // ------------------------------------------------------------------
    // 关联元数据解析（@ManyToOne / @OneToOne）
    // ------------------------------------------------------------------

    /**
     * 解析单个字段的关联信息（T-J7 增强：referencedColumnName / 复合外键 / mappedBy 侧别 /
     * @NotFound / @MapsId）。
     *
     * @ManyToOne + @JoinColumn → 外键列名 + 目标实体类名 → 目标表名
     * @OneToOne + @JoinColumn  → 同上；mappedBy 侧 hasForeignKey=false（无 FK，二次 SELECT 语义）
     * 其他 → null
     */
    private fun resolveAssociation(field: PsiField): AssociationInfo? {
        val isManyToOne = hasAnnotation(field, MANY_TO_ONE_ANNOTATION)
        val isOneToOne = hasAnnotation(field, ONE_TO_ONE_ANNOTATION)
        if (!isManyToOne && !isOneToOne) return null

        val fieldName = field.name
        // 外键列清单：@JoinColumn(name="xxx") / @JoinColumns 复合 / 无注解时默认 {fieldName}_id
        val joinColumnRefs = JpaMetadataReader.readToOneJoinColumns(field, fieldName)
        // 既有 joinColumn 字段语义不变：单列为该列名，复合外键取首列（完整清单见 joinColumns）
        val joinColumn = joinColumnRefs.first().name
        // mappedBy 侧别：本表无外键，查询语义为按主键二次 SELECT（T-J7）
        val mappedBy = JpaMetadataReader.mappedByOf(field)

        // 目标实体类：从字段类型获取
        val targetClass = resolveTargetEntityClass(field) ?: return null
        val targetTableName = resolveTargetTableName(targetClass)

        // 别名：实体类名首字母小写
        val targetAlias = (targetClass.name ?: fieldName).replaceFirstChar { it.lowercaseChar() }

        return AssociationInfo(
            fieldName = fieldName,
            joinColumn = joinColumn,
            targetTable = targetTableName,
            targetAlias = targetAlias,
            referencedColumnName = joinColumnRefs.first().referencedColumnName,
            joinColumns = joinColumnRefs,
            mappedBy = mappedBy,
            hasForeignKey = mappedBy == null,
            notFoundIgnore = JpaMetadataReader.isNotFoundIgnore(field),
            mapsId = JpaMetadataReader.hasMapsId(field),
        )
    }

    /**
     * 解析 @ManyToOne/@OneToOne 字段的目标实体类。
     *
     * 策略：
     * 1. 字段类型直接是 PsiClassType → 取 PsiClass
     * 2. 泛型字段（如 Optional<Address>）→ 取第一个泛型实参
     * 3. 无法解析 → null（降级：不作为关联处理）
     */
    private fun resolveTargetEntityClass(field: PsiField): PsiClass? {
        val type = field.type as? PsiClassType ?: return null
        return type.resolve()
    }

    /**
     * 从目标实体 PsiClass 获取表名。
     *
     * 优先 @Table（SD8：name→value 兜底），否则按命名策略推断。
     */
    private fun resolveTargetTableName(targetClass: PsiClass): String {
        val explicitTable = tableAnnotationValue(targetClass)
        if (explicitTable != null) return explicitTable
        val className = targetClass.name ?: return NamingStrategy.toSnakeCase("unknown")
        return decorateTableName(inferTableName(className, targetClass.project), targetClass.project)
    }

    // ------------------------------------------------------------------
    // 注释读取（四级优先级：SCHEMA > ANNOTATION > JAVADOC > LINE_COMMENT）
    // ------------------------------------------------------------------

    /**
     * 注释来源优先级（schema 预留）：
     * SCHEMA（数据源 schema 列/表注释）最高，当前实现 schema 返回 null——
     * 实际生效顺序 = 注解 > javadoc > 行注释。
     */
    enum class CommentSource { SCHEMA, ANNOTATION, JAVADOC, LINE_COMMENT }

    /**
     * 提取字段注释（PsiField）：注解白名单 > javadoc > 行注释。
     */
    private fun resolveComment(field: PsiField): String? = firstNonBlank(
        listOf(
            null, // SCHEMA 预留
            annotationComment(field),
            javadocComment(field.docComment?.text),
            lineComment(field),
        ),
    )

    /** 提取类级注释：类注解白名单 > 类 javadoc > 类上方行注释。 */
    private fun resolveComment(psiClass: PsiClass): String? = firstNonBlank(
        listOf(
            null, // SCHEMA 预留
            annotationComment(psiClass),
            javadocComment(psiClass.docComment?.text),
            lineComment(psiClass),
        ),
    )

    /** 取最高优先级非空注释（按来源顺序，schema 为空时注解生效）。 */
    private fun firstNonBlank(comments: List<String?>): String? =
        comments.firstOrNull { !it.isNullOrBlank() }?.trim()

    /**
     * 注解注释提取：白名单注解按专属属性，通用属性（description/name/comment/desc）兜底；
     * 排除 JPA 核心映射注解（@Entity/@Table/@Column/@Id 等——name/value 是表名/列名非注释）。
     */
    private fun annotationComment(psi: PsiClass): String? =
        annotationComment(psi.modifierList?.annotations ?: emptyArray())

    private fun annotationComment(field: PsiField): String? =
        annotationComment(field.modifierList?.annotations ?: emptyArray())

    private fun annotationComment(annotations: Array<PsiAnnotation>): String? {
        for (annotation in annotations) {
            val simpleName = annotation.nameReferenceElement?.referenceName ?: continue
            // JPA 核心映射注解不参与注释提取（name/value 是表名/列名）
            if (simpleName in JPA_MAPPING_ANNOTATION_NAMES) {
                continue
            }
            // 白名单注解专属属性
            COMMENT_ANNOTATION_ATTRIBUTES[simpleName]?.forEach { attr ->
                attributeString(annotation, attr)?.takeIf { it.isNotBlank() }?.let { return it }
            }
            // 通用属性兜底
            COMMENT_ATTRIBUTE_NAMES.forEach { attr ->
                attributeString(annotation, attr)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }

    /**
     * JavaDoc 描述提取：去 `/** */` 与行首 `*`，取 `@` 标签前描述段落，空行折叠为空格。
     */
    private fun javadocComment(docText: String?): String? {
        val text = docText ?: return null
        val cleaned = text
            .removePrefix("/**")
            .removeSuffix("*/")
            .lineSequence()
            .map { it.trim().removePrefix("*").trim() }
            .takeWhile { !it.startsWith("@") }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return cleaned.takeIf { it.isNotBlank() }
    }

    /**
     * 行注释提取（兜底）：`// xxx` 行注释。
     *
     * 双路径：① LightJava 将元素上方的行注释包含进元素 textRange——先检查元素自身文本首行；
     * ② 完整 IDE 中注释是独立兄弟节点——回退文档文本向上逐行扫描
     * （跳过空行与 `@` 注解行，第一个非空行若是 `//` 则提取，否则停止）。
     */
    private fun lineComment(element: PsiElement): String? {
        // 路径 ①：元素自身文本首行（LightPlatform 注释绑定进 textRange）
        val ownFirstLine = element.text.substringBefore('\n').trim()
        if (ownFirstLine.startsWith("//")) {
            val comment = ownFirstLine.removePrefix("//").trim()
            if (comment.isNotBlank()) return comment
        }
        // 路径 ②：文档文本向上扫描
        val offset = element.textRange.startOffset
        val before = element.containingFile.text.substring(0, offset)
        val lines = before.split("\n")
        for (i in lines.indices.reversed()) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            if (line.startsWith("@")) continue
            if (line.startsWith("//")) {
                val comment = line.removePrefix("//").trim()
                return comment.takeIf { it.isNotBlank() }
            }
            return null
        }
        return null
    }

    /** 注解 → 注释属性白名单（swagger/easyexcel 等业务注解的注释属性）。 */
    private val COMMENT_ANNOTATION_ATTRIBUTES: Map<String, List<String>> = mapOf(
        "Schema" to listOf("description"),
        "ApiModelProperty" to listOf("value", "description"),
        "ExcelProperty" to listOf("value"),
        "DictField" to listOf("description", "comment", "name"),
    )

    /** 通用注释属性（非白名单注解兜底提取，不取 value——value 常为标识符）。 */
    private val COMMENT_ATTRIBUTE_NAMES = listOf("description", "name", "comment", "desc")

    /** JPA 核心映射注解（name/value 是表名/列名而非注释，不参与提取；含 M39 扩展元数据注解）。 */
    private val JPA_MAPPING_ANNOTATION_NAMES = setOf(
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
        "OrderBy", "OrderColumn",
    )

    // ------------------------------------------------------------------
    // 注解辅助
    // ------------------------------------------------------------------

    private fun hasAnnotation(psi: PsiClass, qualifiedName: String): Boolean =
        findAnnotation(psi, qualifiedName) != null

    private fun hasAnnotation(field: PsiField, qualifiedName: String): Boolean =
        findAnnotation(field, qualifiedName) != null

    /**
     * 按注解查找：优先全限定名；粘贴片段/测试环境注解类不可解析时，
     * 按短名兜底匹配（`@Table` → Table），保证 `@Table(name="t_user")` 可识别。
     */
    private fun findAnnotation(psi: PsiClass, qualifiedName: String): PsiAnnotation? {
        psi.modifierList?.findAnnotation(qualifiedName)?.let { return it }
        return findAnnotationByShortName(psi.modifierList?.annotations, qualifiedName)
    }

    private fun findAnnotation(field: PsiField, qualifiedName: String): PsiAnnotation? {
        field.modifierList?.findAnnotation(qualifiedName)?.let { return it }
        return findAnnotationByShortName(field.modifierList?.annotations, qualifiedName)
    }

    private fun findAnnotationByShortName(
        annotations: Array<PsiAnnotation>?,
        qualifiedName: String,
    ): PsiAnnotation? {
        val simpleName = qualifiedName.substringAfterLast('.')
        return annotations?.firstOrNull { it.nameReferenceElement?.referenceName == simpleName }
    }

    /**
     * 读取注解的 value 属性字符串值（如 @Table("t_user") → t_user）。
     * 无注解或无值返回 null。
     */
    private fun annotationAttributeValue(psi: PsiClass, qualifiedName: String, attribute: String): String? {
        val annotation = findAnnotation(psi, qualifiedName) ?: return null
        return attributeString(annotation, attribute)
    }

    private fun annotationAttributeValue(field: PsiField, qualifiedName: String, attribute: String): String? {
        val annotation = findAnnotation(field, qualifiedName) ?: return null
        return attributeString(annotation, attribute)
    }

    /**
     * 类级表名注解取值（SD8，M44）：@Table `name` → `value` 双属性兜底——
     * jakarta @Table 只有 name 属性（value 兜底天然落空），relational/Easy-Query/xbatis @Table
     * 以 value 简写为最常用形态（`@Table("t_user")`）。查找轨道：jakarta/javax FQN + relational
     * FQN + 短名兜底（findAnnotation/FQN_XBATIS_TABLE 显式轨）。
     */
    private fun tableAnnotationValue(psi: PsiClass): String? =
        annotationAttributeValue(psi, TABLE_ANNOTATION, "name")
            ?: annotationAttributeValue(psi, TABLE_ANNOTATION, "value")
            ?: annotationAttributeValue(psi, JpaMetadataReader.FQN_RELATIONAL_TABLE, "name")
            ?: annotationAttributeValue(psi, JpaMetadataReader.FQN_RELATIONAL_TABLE, "value")
            ?: annotationAttributeValue(psi, JpaMetadataReader.FQN_XBATIS_TABLE, "value")

    /**
     * 字段主键判定（M44 统一轨）：jakarta/javax @Id（既有短名兜底）+ spring-data @Id（SD8）+
     * xbatis @TableId（XB）+ Easy-Query @Column(primaryKey = true)（EQ8）。
     */
    private fun isIdentifierField(field: PsiField): Boolean =
        hasAnnotation(field, ID_ANNOTATION) ||
            hasAnnotation(field, JpaMetadataReader.FQN_SD_ID) ||
            hasAnnotation(field, JpaMetadataReader.FQN_XBATIS_TABLE_ID) ||
            isEasyQueryPrimaryKey(field)

    /** EQ8：@Column(primaryKey = true)（Easy-Query 以 Column 注解属性标记主键）。 */
    private fun isEasyQueryPrimaryKey(field: PsiField): Boolean {
        val column = JpaMetadataReader.findFlex(field, JpaMetadataReader.FQN_EASY_QUERY_COLUMN) ?: return false
        return JpaMetadataReader.attrBoolean(column, "primaryKey") == true
    }

    private fun attributeString(annotation: PsiAnnotation, attribute: String): String? {
        val memberValue = annotation.findAttributeValue(attribute) ?: return null
        val literal = memberValue as? PsiLiteralExpression ?: return null
        val value = literal.value as? String
        // 空白视为未指定（回退命名策略）
        return value?.takeIf { it.isNotBlank() }
    }

    companion object {
        private val LOG = Logger.getInstance(JpaEntityResolver::class.java)
        // JPA 注解全限定名（jakarta.persistence）
        private const val ENTITY_ANNOTATION = "jakarta.persistence.Entity"
        private const val TABLE_ANNOTATION = "jakarta.persistence.Table"
        private const val ID_ANNOTATION = "jakarta.persistence.Id"
        private const val MANY_TO_ONE_ANNOTATION = "jakarta.persistence.ManyToOne"
        private const val ONE_TO_MANY_ANNOTATION = "jakarta.persistence.OneToMany"
        private const val ONE_TO_ONE_ANNOTATION = "jakarta.persistence.OneToOne"
        private const val MANY_TO_MANY_ANNOTATION = "jakarta.persistence.ManyToMany"
        private const val JOIN_COLUMN_ANNOTATION = "jakarta.persistence.JoinColumn"
        private const val JOIN_TABLE_ANNOTATION = "jakarta.persistence.JoinTable"
        private const val MAPPED_SUPERCLASS_ANNOTATION = "jakarta.persistence.MappedSuperclass"

        // ------------------------------------------------------------------
        // JPA 实体解析缓存（三维键 + 双维度失效）
        // ------------------------------------------------------------------

        /**
         * JPA 实体解析缓存键。
         *
         * @param file               PsiClass 所在文件（文件修改戳作为失效依据）
         * @param classQualifiedName 类全限定名（PsiClass 对象不稳定，用全限定名跨 PSI 重建保持同一性）
         * @param configSignature   配置签名（表名推断策略 + 忽略字段 + 表前缀后缀拼接串）
         */
        private data class JpaCacheKey(
            val fileUrl: String?,
            /** T-Perf-18：文件实例同一性辅助键（同 EntityResolver——light/mock 文件 url 可重复）。 */
            val fileIdentity: Int,
            val classQualifiedName: String,
            val configSignature: String,
        )

        /** 缓存条目：文件修改戳 + 解析结果。 */
        private data class JpaEntry(val stamp: Long, val value: TableInfo)

        /** 缓存键 → 条目（项目级，跟随 JpaEntityResolver 生命周期）。 */
        private val cache = ConcurrentHashMap<JpaCacheKey, JpaEntry>()

        /** 清空缓存（配置变更 / 项目关闭时调用）。 */
        @JvmStatic
        fun clearCache() = cache.clear()

        /** 当前缓存条目数（测试 / 诊断用）。 */
        @JvmStatic
        fun cacheSize(): Int = cache.size

        // ------------------------------------------------------------------
        // 表名推断策略 + 统一修饰链
        // ------------------------------------------------------------------

        /**
         * 无 @Table 时的表名推断：
         *  - [PluginSettings.INFERENCE_SNAKE]（默认）：驼峰转下划线（UserInfoEntity → user_info_entity）；
         *  - [PluginSettings.INFERENCE_CAMEL]：纯保留驼峰——仅首字母小写。
         * 设置服务不可用时兜底默认 SNAKE。
         *
         * @param project 实体所属项目（决定读哪一份项目级设置；null 见 [effectiveTableSetting]）
         */
        private fun inferTableName(className: String, project: Project? = null): String {
            val strategy = effectiveTableSetting({ it.effectiveTableNameInference() }, project)
                ?: runCatching { PluginSettings.getInstance().state.tableNameInference }
                    .getOrDefault(PluginSettings.INFERENCE_SNAKE)
            return if (strategy == PluginSettings.INFERENCE_CAMEL) {
                className.replaceFirstChar { it.lowercaseChar() }
            } else {
                NamingStrategy.toSnakeCase(className)
            }
        }

        /**
         * 统一表名修饰链：
         * `最终表名 = 添加前缀 + 移除后缀(移除前缀(表名)) + 添加后缀`；
         *  - 移除前缀/后缀：`;` 分隔，首个匹配（大小写敏感），不匹配则不修饰；
         *  - 添加前缀/后缀：单值（用户反馈定稿，不再 `;` 分隔）。
         *
         * @param project 实体所属项目（决定读哪一份项目级设置；null 见 [effectiveTableSetting]）
         */
        private fun decorateTableName(raw: String, project: Project? = null): String {
            val removedPrefix = removeTablePrefixList().firstOrNull { raw.startsWith(it) }
                ?.let { raw.removePrefix(it) } ?: raw
            val removedSuffix = removeTableSuffixList().firstOrNull { removedPrefix.endsWith(it) }
                ?.let { removedPrefix.removeSuffix(it) } ?: removedPrefix
            return tablePrefix(project) + removedSuffix + tableSuffix(project)
        }

        /**
         * 实体类不可解析的兜底推断统一入口——
         * 按配置推断策略（SNAKE/CAMEL）+ 统一修饰链，与 resolve 的推断路径语义一致。
         *
         * @param project 调用方已知的所属项目；调用方只有类名字符串时留空——
         *   留空沿改动前的「首个打开项目」取值（见 [effectiveTableSetting]），不引入新行为。
         */
        @JvmStatic
        @JvmOverloads
        fun inferDecoratedTableName(className: String, project: Project? = null): String =
            decorateTableName(inferTableName(className, project), project)

        /**
         * `;` 分隔配置解析为列表（trim + 去空；设置服务不可用时兜底空列表）。
         */
        private fun listSetting(get: (PluginSettings.State) -> String): List<String> =
            runCatching { get(PluginSettings.getInstance().state) }
                .getOrDefault("")
                .split(';').map { it.trim() }.filter { it.isNotEmpty() }

        /**
         * T-SettingsTruth P4 / T-Fix-SettingsScope：项目级表配置读取入口——[project] 对应项目
         * 的 [ProjectPluginSettings] 启用时 effectiveXxx 覆盖全局；无项目上下文/服务不可用/
         * 项目值为空时返回 null（调用方回退全局设置，与原全局读取行为一致）。
         *
         * [project] 为 null 时沿用改动前的「首个打开项目」：多项目同开时该取值取决于 IDE
         * 打开顺序（本次修复的歧义点），但**显式传入 project 的路径已不再受它影响**；
         * 只有「仅有类名字符串、拿不到 PsiClass」的兜底调用方才会走到这里。
         *
         * @param get     从项目级设置读取有效值
         * @param project 目标项目；null = 未提供（兜底路径）
         */
        private fun effectiveTableSetting(get: (ProjectPluginSettings) -> String, project: Project? = null): String? {
            // runCatching 包裹整体：纯单测（无 Application/ProjectManager 实例）与无打开项目时
            // 一律降级 null → 调用方回退全局设置，与原全局读取行为一致
            return runCatching {
                val target = project
                    ?: com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
                    ?: return null
                get(ProjectPluginSettings.getInstance(target)).ifBlank { null }
            }.getOrNull()
        }

        private fun tablePrefix(project: Project? = null): String =
            effectiveTableSetting({ it.effectiveTablePrefix() }, project)
                ?: runCatching { PluginSettings.getInstance().state.tablePrefix }.getOrDefault("")

        private fun tableSuffix(project: Project? = null): String =
            effectiveTableSetting({ it.effectiveTableSuffix() }, project)
                ?: runCatching { PluginSettings.getInstance().state.tableSuffix }.getOrDefault("")

        private fun removeTablePrefixList(): List<String> = listSetting { it.removeTablePrefix }

        private fun removeTableSuffixList(): List<String> = listSetting { it.removeTableSuffix }

        /** 忽略字段名集合（`;` 分隔，按 Java 字段名匹配）。 */
        private fun ignoredFieldNames(project: Project? = null): Set<String> =
            (effectiveTableSetting({ it.effectiveIgnoredFields() }, project)
                ?: runCatching { PluginSettings.getInstance().state.ignoredFields }.getOrDefault(""))
                .split(';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
