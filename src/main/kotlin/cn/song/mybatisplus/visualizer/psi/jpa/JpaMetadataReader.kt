package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.model.CollectionAssociationInfo
import cn.song.mybatisplus.visualizer.model.CollectionKind
import cn.song.mybatisplus.visualizer.model.CustomSqlScripts
import cn.song.mybatisplus.visualizer.model.GeneratorInfo
import cn.song.mybatisplus.visualizer.model.JoinColumnRef
import cn.song.mybatisplus.visualizer.model.JoinTableInfo
import cn.song.mybatisplus.visualizer.model.SecondaryTableInfo
import cn.song.mybatisplus.visualizer.model.SequenceGeneratorInfo
import cn.song.mybatisplus.visualizer.model.SoftDeleteInfo
import cn.song.mybatisplus.visualizer.model.TableGeneratorInfo
import cn.song.mybatisplus.visualizer.psi.EntityFieldCollector
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiModifierListOwner

/**
 * JPA 元数据纯函数读取器（M39：T-J7 关联增强 / T-J8 软删除与动态 SQL / T-J14 主键生成值 / T-J18 映射深水区）。
 *
 * 职责边界：只读 PSI、产出纯数据；不做缓存（随 TableInfo 三维缓存一并缓存）、
 * 不抛异常（所有 PSI 访问 runCatching 包裹，解析不出即优雅降级为空/null）。
 *
 * 注解识别三轨（T-J14 双包名要求）：
 *  1. jakarta.persistence 全限定名精确匹配；
 *  2. javax.persistence 全限定名精确匹配（Spring Boot 2 / Hibernate 5 老工程）；
 *  3. 短名兜底（注解类不可解析 / 混用 import 场景）。
 * Hibernate 专有注解（org.hibernate.annotations）为 FQN + 短名双轨。
 *
 * 属性读取容错：注解类不可解析时 `findAttributeValue` 走 PSI 本地参数列表回退，
 * 字面量属性照常可读；枚举属性取引用文本末段（`GenerationType.IDENTITY` → IDENTITY）。
 */
internal object JpaMetadataReader {

    // ------------------------------------------------------------------
    // 注解全限定名常量（jakarta 为主，javax 双轨由 findFlex 内部处理；Hibernate 专有单列）
    // ------------------------------------------------------------------

    const val FQN_TABLE = "jakarta.persistence.Table"
    const val FQN_COLUMN = "jakarta.persistence.Column"
    const val FQN_TRANSIENT = "jakarta.persistence.Transient"
    const val FQN_ONE_TO_ONE = "jakarta.persistence.OneToOne"
    const val FQN_ONE_TO_MANY = "jakarta.persistence.OneToMany"
    const val FQN_MANY_TO_ONE = "jakarta.persistence.ManyToOne"
    const val FQN_MANY_TO_MANY = "jakarta.persistence.ManyToMany"
    const val FQN_JOIN_COLUMNS = "jakarta.persistence.JoinColumns"
    const val FQN_JOIN_TABLE = "jakarta.persistence.JoinTable"
    const val FQN_GENERATED_VALUE = "jakarta.persistence.GeneratedValue"
    const val FQN_SEQUENCE_GENERATORS = "jakarta.persistence.SequenceGenerators"
    const val FQN_TABLE_GENERATORS = "jakarta.persistence.TableGenerators"
    const val FQN_VERSION = "jakarta.persistence.Version"
    const val FQN_ELEMENT_COLLECTION = "jakarta.persistence.ElementCollection"
    const val FQN_COLLECTION_TABLE = "jakarta.persistence.CollectionTable"
    const val FQN_EMBEDDED = "jakarta.persistence.Embedded"
    const val FQN_EMBEDDED_ID = "jakarta.persistence.EmbeddedId"
    const val FQN_EMBEDDABLE = "jakarta.persistence.Embeddable"
    const val FQN_ID_CLASS = "jakarta.persistence.IdClass"
    const val FQN_INHERITANCE = "jakarta.persistence.Inheritance"
    const val FQN_DISCRIMINATOR_COLUMN = "jakarta.persistence.DiscriminatorColumn"
    const val FQN_DISCRIMINATOR_VALUE = "jakarta.persistence.DiscriminatorValue"
    const val FQN_SECONDARY_TABLES = "jakarta.persistence.SecondaryTables"
    const val FQN_PRIMARY_KEY_JOIN_COLUMNS = "jakarta.persistence.PrimaryKeyJoinColumns"
    const val FQN_ORDER_BY = "jakarta.persistence.OrderBy"
    const val FQN_ORDER_COLUMN = "jakarta.persistence.OrderColumn"
    const val FQN_MAPS_ID = "jakarta.persistence.MapsId"
    const val FQN_CONVERT = "jakarta.persistence.Convert"

    const val FQN_HIBERNATE_WHERE = "org.hibernate.annotations.Where"
    const val FQN_HIBERNATE_SQL_RESTRICTION = "org.hibernate.annotations.SQLRestriction"
    const val FQN_HIBERNATE_SOFT_DELETE = "org.hibernate.annotations.SoftDelete"
    const val FQN_HIBERNATE_SQL_DELETE = "org.hibernate.annotations.SQLDelete"
    const val FQN_HIBERNATE_SQL_DELETE_ALL = "org.hibernate.annotations.SQLDeleteAll"
    const val FQN_HIBERNATE_SQL_INSERT = "org.hibernate.annotations.SQLInsert"
    const val FQN_HIBERNATE_SQL_UPDATE = "org.hibernate.annotations.SQLUpdate"
    const val FQN_HIBERNATE_NOT_FOUND = "org.hibernate.annotations.NotFound"

    // ------------------------------------------------------------------
    // M44 其他 ORM 第一批注解轨（SD8 / EQ8 / EQ9 / XB 实体解析；javap 实证属性面）
    // ------------------------------------------------------------------

    /** SD8：Spring Data relational @Table（value/name/schema——value 简写最常用）。 */
    const val FQN_RELATIONAL_TABLE = "org.springframework.data.relational.core.mapping.Table"

    /** SD8：Spring Data relational @Column（javap 证实**只有 value 属性**，无 name）。 */
    const val FQN_RELATIONAL_COLUMN = "org.springframework.data.relational.core.mapping.Column"

    /** SD8：Spring Data relational @Embedded。 */
    const val FQN_RELATIONAL_EMBEDDED = "org.springframework.data.relational.core.mapping.Embedded"

    /** SD10：Spring Data relational @MappedCollection（集合/嵌套实体反向映射，止血期不展开）。 */
    const val FQN_RELATIONAL_MAPPED_COLLECTION = "org.springframework.data.relational.core.mapping.MappedCollection"

    /** SD8：spring-data-commons @Id（org.springframework.data.annotation.Id，非 jakarta 包）。 */
    const val FQN_SD_ID = "org.springframework.data.annotation.Id"

    /** SD8：spring-data-commons @Transient。 */
    const val FQN_SD_TRANSIENT = "org.springframework.data.annotation.Transient"

    /** SD8：spring-data-commons @Version。 */
    const val FQN_SD_VERSION = "org.springframework.data.annotation.Version"

    /** EQ8：Easy-Query @Table（value/schema/oldName；javap 实证）。 */
    const val FQN_EASY_QUERY_TABLE = "com.easy.query.core.annotation.Table"

    /** EQ8：Easy-Query @Column（value/primaryKey/exist 等；javap 实证）。 */
    const val FQN_EASY_QUERY_COLUMN = "com.easy.query.core.annotation.Column"

    /** EQ8：Easy-Query @ColumnIgnore（字段级跳过）。 */
    const val FQN_EASY_QUERY_COLUMN_IGNORE = "com.easy.query.core.annotation.ColumnIgnore"

    /** EQ9：Easy-Query @Version（乐观锁）。 */
    const val FQN_EASY_QUERY_VERSION = "com.easy.query.core.annotation.Version"

    /** EQ9：Easy-Query @LogicDelete（逻辑删除，strategy/strategyName）。 */
    const val FQN_EASY_QUERY_LOGIC_DELETE = "com.easy.query.core.annotation.LogicDelete"

    /** XB：xbatis @Table（value/schema；javap cn.xbatis.db.annotations.Table 实证）。 */
    const val FQN_XBATIS_TABLE = "cn.xbatis.db.annotations.Table"

    /** XB：xbatis @TableId（主键）。 */
    const val FQN_XBATIS_TABLE_ID = "cn.xbatis.db.annotations.TableId"

    /** XB：xbatis @TableField（value 列名 / exists 存在性；javap 实证）。 */
    const val FQN_XBATIS_TABLE_FIELD = "cn.xbatis.db.annotations.TableField"

    /** XB：xbatis @Ignore（字段级跳过）。 */
    const val FQN_XBATIS_IGNORE = "cn.xbatis.db.annotations.Ignore"

    /** XB：xbatis @Version（乐观锁）。 */
    const val FQN_XBATIS_VERSION = "cn.xbatis.db.annotations.Version"

    /** XB：xbatis @LogicDelete（逻辑删除，beforeValue/afterValue）。 */
    const val FQN_XBATIS_LOGIC_DELETE = "cn.xbatis.db.annotations.LogicDelete"

    // ------------------------------------------------------------------
    // 注解查找与属性读取（纯函数）
    // ------------------------------------------------------------------

    /**
     * 三轨注解查找：jakarta FQN → javax FQN（jakarta.persistence 系）→ 短名兜底。
     */
    fun findFlex(owner: PsiModifierListOwner, annotationFqn: String): PsiAnnotation? {
        val list = owner.modifierList ?: return null
        list.findAnnotation(annotationFqn)?.let { return it }
        if (annotationFqn.startsWith("jakarta.persistence.")) {
            list.findAnnotation("javax.persistence." + annotationFqn.substringAfterLast('.'))?.let { return it }
        }
        val simple = annotationFqn.substringAfterLast('.')
        return list.annotations.firstOrNull { it.nameReferenceElement?.referenceName == simple }
    }

    /** 首个非空白字符串属性（按给定顺序尝试多个属性名，如 @Where 的 clause → value 兜底）。 */
    fun attrString(annotation: PsiAnnotation, vararg attributes: String): String? {
        for (attribute in attributes) {
            val value = annotation.findAttributeValue(attribute) ?: continue
            val text = (value as? PsiLiteralExpression)?.value as? String
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    /** 布尔属性；未显式指定时依赖解析到的注解默认值，解析不出返回 null（由调用方兜底）。 */
    fun attrBoolean(annotation: PsiAnnotation, attribute: String): Boolean? =
        (annotation.findAttributeValue(attribute) as? PsiLiteralExpression)?.value as? Boolean

    /** 整型属性文本（allocationSize 等；字符串数字形式容错）。 */
    fun attrIntText(annotation: PsiAnnotation, attribute: String): String? {
        val value = (annotation.findAttributeValue(attribute) as? PsiLiteralExpression)?.value ?: return null
        return when (value) {
            is Int -> value.toString()
            is Long -> value.toString()
            is String -> value.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /** 枚举属性文本末段（`GenerationType.IDENTITY` / `jakarta…IDENTITY` / `IDENTITY` → IDENTITY，统一大写）。 */
    fun attrEnumText(annotation: PsiAnnotation, attribute: String): String? {
        val text = annotation.findAttributeValue(attribute)?.text ?: return null
        val last = text.substringAfterLast('.').uppercase()
        return last.takeIf { it.isNotBlank() }
    }

    /** 注解型属性（@AttributeOverride 的 column 等）。 */
    fun attrAnnotation(annotation: PsiAnnotation, attribute: String): PsiAnnotation? =
        annotation.findAttributeValue(attribute) as? PsiAnnotation

    /** 注解数组属性（@JoinColumns({…}) 的 value 等；花括号缺省的单元素形式同样兼容）。 */
    fun attrAnnotationArray(annotation: PsiAnnotation, attribute: String): List<PsiAnnotation> =
        when (val value: PsiAnnotationMemberValue? = annotation.findAttributeValue(attribute)) {
            is PsiArrayInitializerMemberValue -> value.initializers.filterIsInstance<PsiAnnotation>()
            is PsiAnnotation -> listOf(value)
            else -> emptyList()
        }

    /** 按 short 名收集 owner 上直接标注的注解（repeatable 注解无容器重复标注形态）。 */
    private fun directAnnotations(owner: PsiModifierListOwner, simpleName: String): List<PsiAnnotation> =
        owner.modifierList?.annotations
            ?.filter { it.nameReferenceElement?.referenceName == simpleName }
            ?: emptyList()

    // ------------------------------------------------------------------
    // 类级元数据（T-J8 / T-J14 / T-J18）
    // ------------------------------------------------------------------

    /** 类级元数据聚合体（全部字段带默认值，解析失败整体降级为空元数据）。 */
    data class ClassMeta(
        val schema: String? = null,
        val catalog: String? = null,
        val restrictionClause: String? = null,
        val softDelete: SoftDeleteInfo? = null,
        val customSqlScripts: CustomSqlScripts? = null,
        val sequenceGenerators: List<SequenceGeneratorInfo> = emptyList(),
        val tableGenerators: List<TableGeneratorInfo> = emptyList(),
        val secondaryTables: List<SecondaryTableInfo> = emptyList(),
        val primaryKeyJoinColumns: List<JoinColumnRef> = emptyList(),
        val inheritanceStrategy: String? = null,
        val discriminatorColumn: String? = null,
        val discriminatorValue: String? = null,
        val idClassType: String? = null,
    )

    /** 读取类级元数据；任何 PSI 异常整体降级为空元数据，绝不抛出。 */
    fun readClassMeta(clazz: PsiClass): ClassMeta =
        runCatching { readClassMetaInternal(clazz) }.getOrDefault(ClassMeta())

    private fun readClassMetaInternal(clazz: PsiClass): ClassMeta {
        // @Table(schema/catalog)（T-J18）
        val tableAnnotation = findFlex(clazz, FQN_TABLE)
        val schema = tableAnnotation?.let { attrString(it, "schema") }
        val catalog = tableAnnotation?.let { attrString(it, "catalog") }

        // 静态限制子句（T-J8）：@SQLRestriction（6.3+）优先——它取代了 @Where；@Where 读 clause（Hibernate 5/6）value 兜底
        val restrictionClause = findFlex(clazz, FQN_HIBERNATE_SQL_RESTRICTION)?.let { attrString(it, "value") }
            ?: findFlex(clazz, FQN_HIBERNATE_WHERE)?.let { attrString(it, "clause", "value") }

        // 软删除（T-J8 @SoftDelete）：默认列 deleted / 默认策略 DELETED（Hibernate 6.4 规范默认）
        val softDelete = findFlex(clazz, FQN_HIBERNATE_SOFT_DELETE)?.let { sd ->
            SoftDeleteInfo(
                columnName = attrString(sd, "columnName") ?: "deleted",
                strategy = attrEnumText(sd, "strategy") ?: "DELETED",
            )
        }

        // 自定义 SQL 脚本（T-J8）：@SQLDelete 族属性名为 sql（value 兜底容错）
        val customSqlScripts = CustomSqlScripts(
            sqlInsert = findFlex(clazz, FQN_HIBERNATE_SQL_INSERT)?.let { attrString(it, "sql", "value") },
            sqlDelete = findFlex(clazz, FQN_HIBERNATE_SQL_DELETE)?.let { attrString(it, "sql", "value") },
            sqlDeleteAll = findFlex(clazz, FQN_HIBERNATE_SQL_DELETE_ALL)?.let { attrString(it, "sql", "value") },
            sqlUpdate = findFlex(clazz, FQN_HIBERNATE_SQL_UPDATE)?.let { attrString(it, "sql", "value") },
        ).takeIf {
            it.sqlInsert != null || it.sqlDelete != null || it.sqlDeleteAll != null || it.sqlUpdate != null
        }

        // 生成器（T-J14）：类级 repeatable + 容器注解 + 字段级三处扫描，按 name 归并（先见者优先）
        val sequenceGenerators = collectGenerators(clazz, "SequenceGenerator", FQN_SEQUENCE_GENERATORS) { anno ->
            val name = attrString(anno, "name") ?: return@collectGenerators null
            SequenceGeneratorInfo(
                name = name,
                sequenceName = attrString(anno, "sequenceName"),
                allocationSize = attrIntText(anno, "allocationSize"),
                initialValue = attrIntText(anno, "initialValue"),
                schema = attrString(anno, "schema"),
            )
        }
        val tableGenerators = collectGenerators(clazz, "TableGenerator", FQN_TABLE_GENERATORS) { anno ->
            val name = attrString(anno, "name") ?: return@collectGenerators null
            TableGeneratorInfo(
                name = name,
                table = attrString(anno, "table"),
                pkColumnName = attrString(anno, "pkColumnName"),
                valueColumnName = attrString(anno, "valueColumnName"),
                pkColumnValue = attrString(anno, "pkColumnValue"),
                allocationSize = attrIntText(anno, "allocationSize"),
            )
        }

        // 副表（T-J18 @SecondaryTable(s)）
        val secondaryTables = (
            directAnnotations(clazz, "SecondaryTable") +
                (findFlex(clazz, FQN_SECONDARY_TABLES)?.let { attrAnnotationArray(it, "value") } ?: emptyList())
            ).mapNotNull { st ->
            val name = attrString(st, "name") ?: return@mapNotNull null
            SecondaryTableInfo(
                tableName = name,
                schema = attrString(st, "schema"),
                catalog = attrString(st, "catalog"),
                pkJoinColumns = attrAnnotationArray(st, "pkJoinColumns").map { toPkJoinColumnRef(it) },
            )
        }

        // 类级 @PrimaryKeyJoinColumn(s)（T-J18，JOINED 继承场景）
        val primaryKeyJoinColumns = (
            directAnnotations(clazz, "PrimaryKeyJoinColumn") +
                (findFlex(clazz, FQN_PRIMARY_KEY_JOIN_COLUMNS)?.let { attrAnnotationArray(it, "value") } ?: emptyList())
            ).map { toPkJoinColumnRef(it) }

        // 继承与判别（T-J18）：strategy 缺省即规范默认 SINGLE_TABLE；判别列缺省名 DTYPE
        val inheritanceStrategy = findFlex(clazz, FQN_INHERITANCE)?.let { attrEnumText(it, "strategy") ?: "SINGLE_TABLE" }
        val discriminatorColumn = findFlex(clazz, FQN_DISCRIMINATOR_COLUMN)?.let { attrString(it, "name") ?: "DTYPE" }
        val discriminatorValue = findFlex(clazz, FQN_DISCRIMINATOR_VALUE)?.let { attrString(it, "value") }

        // @IdClass 复合主键类（T-J18）：`UserPk.class` / `com.x.UserPk.class` → UserPk
        val idClassType = findFlex(clazz, FQN_ID_CLASS)?.let { anno ->
            anno.findAttributeValue("value")?.text
                ?.removeSuffix(".class")
                ?.substringAfterLast('.')
                ?.takeIf { it.isNotBlank() }
        }

        return ClassMeta(
            schema = schema,
            catalog = catalog,
            restrictionClause = restrictionClause,
            softDelete = softDelete,
            customSqlScripts = customSqlScripts,
            sequenceGenerators = sequenceGenerators,
            tableGenerators = tableGenerators,
            secondaryTables = secondaryTables,
            primaryKeyJoinColumns = primaryKeyJoinColumns,
            inheritanceStrategy = inheritanceStrategy,
            discriminatorColumn = discriminatorColumn,
            discriminatorValue = discriminatorValue,
            idClassType = idClassType,
        )
    }

    /**
     * 生成器统一收集：类级直接标注 + 容器注解 + 字段级标注，
     * 按 [GeneratorInfo.name] 归并去重（同名生成器先见者优先——收集顺序即类级直接标注在容器之前）。
     */
    private fun <T : GeneratorInfo> collectGenerators(
        clazz: PsiClass,
        simpleName: String,
        containerFqn: String,
        toInfo: (PsiAnnotation) -> T?,
    ): List<T> {
        val annotations = buildList {
            addAll(directAnnotations(clazz, simpleName))
            findFlex(clazz, containerFqn)?.let { addAll(attrAnnotationArray(it, "value")) }
            clazz.allFields.forEach { field -> addAll(directAnnotations(field, simpleName)) }
        }
        return annotations.mapNotNull(toInfo)
            .distinctBy { it.name }
    }

    private fun toPkJoinColumnRef(annotation: PsiAnnotation): JoinColumnRef = JoinColumnRef(
        name = attrString(annotation, "name") ?: "",
        referencedColumnName = attrString(annotation, "referencedColumnName"),
    )

    // ------------------------------------------------------------------
    // 字段级：主键生成值 / 乐观锁 / 嵌入 / 转换（T-J14 / T-J8 / T-J18）
    // ------------------------------------------------------------------

    /** @GeneratedValue 策略文本；注解存在而未指定 strategy 时为规范默认 AUTO。 */
    fun generatedValueStrategy(field: PsiField): String? {
        val generated = findFlex(field, FQN_GENERATED_VALUE) ?: return null
        return attrEnumText(generated, "strategy") ?: "AUTO"
    }

    /** jakarta/javax @Version（T-J8，与 MP @Version 同列语义）。 */
    fun hasVersionAnnotation(field: PsiField): Boolean = findFlex(field, FQN_VERSION) != null

    /**
     * M44 全框架 @Version（乐观锁）识别：jakarta/javax + spring-data-commons（SD8）+
     * Easy-Query（EQ9）+ xbatis（XB）。任一命中即视为版本列。
     */
    fun hasAnyVersionAnnotation(field: PsiField): Boolean =
        hasVersionAnnotation(field) ||
            findFlex(field, FQN_SD_VERSION) != null ||
            findFlex(field, FQN_EASY_QUERY_VERSION) != null ||
            findFlex(field, FQN_XBATIS_VERSION) != null

    /**
     * EQ9/XB：@LogicDelete（Easy-Query / xbatis）标记字段的列名。
     * 列名 = 字段 @Column/@TableField value（EQ 轨已由 value 兜底覆盖）或驼峰转下划线；无标记返回 null。
     */
    fun logicDeleteColumnOf(field: PsiField, fieldName: String): String? {
        val marked = findFlex(field, FQN_EASY_QUERY_LOGIC_DELETE) != null ||
            findFlex(field, FQN_XBATIS_LOGIC_DELETE) != null
        if (!marked) return null
        return columnValueOrSnake(field, fieldName)
    }

    /**
     * 字段显式列名（M44 统一链）：jakarta/javax/relational/EQ @Column（name → value 兜底）→
     * xbatis @TableField(value) → **同名 getter 上的 @Column（O-58/T-07）** → 驼峰转下划线。
     */
    fun columnValueOrSnake(field: PsiField, fieldName: String): String {
        findFlex(field, FQN_COLUMN)?.let { column ->
            attrString(column, "name", "value")?.let { return it }
        }
        findFlex(field, FQN_XBATIS_TABLE_FIELD)?.let { tf ->
            attrString(tf, "value")?.let { return it }
        }
        // O-58/T-07：JPA 的 @Access(AccessType.PROPERTY) 与「注解写在 getter 上」的工程里，
        // 字段侧一个 @Column 都没有，此前静默回落 snake 盲猜（用户报障「字段命名不标准」的形态之一，
        // 但与命名策略猜错在现象上不可区分）。回退读同名 getter 的 @Column 即可救回真值。
        getterColumnNameOf(field)?.let { return it }
        return NamingStrategy.toSnakeCase(fieldName)
    }

    /**
     * O-58/T-07：同名 getter（`getXxx` / `isXxx`）上的 @Column 列名。
     *
     * **范围收窄**（有意为之）：只做 @Column 的 name/value 回退，不实现完整 JPA 属性访问语义——
     * 字段与 getter 注解冲突时以 @Access 为准这类判定、以及 @JoinColumn/@TableField 的 getter 回退
     * 都不在本函数内（多读一条即多一份半套语义的风险，且数据源校正已能覆盖大部分错配）。
     *
     * **不受「列名数据源校正」开关约束**：本函数读的是源码里本来就写着的 `@Column`，不查数据源，
     * 属**离线纠错**——无数据源时它照样生效（实体列名因此可能比改造前更准）。受开关与数据源可用性
     * 约束的是 `psi.ColumnNameCalibrator` 那层，两者在交付说明里必须分开表述。
     *
     * **MP 侧没有对应回退是有意的**（与 JPA 侧不对称，实读确认）：MyBatis-Plus 按**字段**建映射，
     * 且其注解契约根本不允许写 getter——`mybatis-plus-annotation-3.5.17` 的 `TableField` 字节码实证
     * `@Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE})`（`TableName` 为 `{TYPE,
     * ANNOTATION_TYPE}`），`METHOD` 不在合法目标里。所以「@TableField 写在 getter 上」在 MP 工程里
     * 既编译不过、MP 运行时也不会读——给 MP 侧加 getter 回退等于实现一个 MP 自己都没有的语义。
     *
     * 匹配口径：方法名去 `get`/`is` 前缀后与字段名 **忽略大小写**相等（`orderID` 的 getter
     * `getOrderID` / `getOrderId` 都算命中）；只有无参 getter 才可能是属性访问点。
     */
    private fun getterColumnNameOf(field: PsiField): String? = runCatching {
        val owner = field.containingClass ?: return@runCatching null
        val fieldName = field.name ?: return@runCatching null
        for (method in owner.methods) {
            if (method.parameterList.parametersCount != 0) continue
            val methodName = method.name
            val property = when {
                methodName.length > 3 && methodName.startsWith("get") -> methodName.substring(3)
                methodName.length > 2 && methodName.startsWith("is") -> methodName.substring(2)
                else -> continue
            }
            if (!property.equals(fieldName, ignoreCase = true)) continue
            val column = findFlex(method, FQN_COLUMN) ?: continue
            attrString(column, "name", "value")?.let { return@runCatching it }
        }
        null
    }.getOrNull()

    /**
     * M44 字段级跳过注解全集：jakarta/javax @Transient（既有）+ spring-data @Transient（SD8）+
     * Easy-Query @ColumnIgnore（EQ8）+ xbatis @Ignore（XB）。
     */
    fun hasTransientLikeAnnotation(field: PsiField): Boolean =
        findFlex(field, FQN_TRANSIENT) != null ||
            findFlex(field, FQN_SD_TRANSIENT) != null ||
            findFlex(field, FQN_EASY_QUERY_COLUMN_IGNORE) != null ||
            findFlex(field, FQN_XBATIS_IGNORE) != null

    /**
     * M44 存在性为 false 的字段（不出现在任何 SQL 列清单）：
     * Easy-Query @Column(exist = false) / xbatis @TableField(exists = false)。
     * 注解类不可解析时 findAttributeValue 走本地参数列表回退，显式 false 照常可读；
     * 未指定时（解析到默认 true / 读不到）不跳过。
     */
    fun hasExistFalse(field: PsiField): Boolean {
        for (fqn in listOf(FQN_EASY_QUERY_COLUMN, FQN_XBATIS_TABLE_FIELD)) {
            val annotation = findFlex(field, fqn) ?: continue
            if (attrBoolean(annotation, "exist") == false || attrBoolean(annotation, "exists") == false) return true
        }
        return false
    }

    /** SD10：字段是否标注 @MappedCollection（Spring Data relational 集合/嵌套实体反向映射）。 */
    fun isMappedCollectionField(field: PsiField): Boolean =
        findFlex(field, FQN_RELATIONAL_MAPPED_COLLECTION) != null

    /**
     * SD10：字段是否为**嵌套实体引用 / 实体集合字段**（无 JPA 关联注解的实体类型字段、
     * 或元素为实体的集合字段）——不落父表列（止血期不做反向列展开，否则 SELECT/INSERT
     * 出现不存在的列）。
     *
     * 实体判定（[isLikelyMappedEntityClass]）：@Entity / @Table（jakarta/javax/relational/短名）
     * 或含 @Id 字段；@Embeddable 值对象不算（仍走 @Embedded 展平）。已有 JPA 关联注解
     * （@ManyToOne/@OneToOne/@JoinColumn 等）的字段不在此列（走既有外键列语义）。
     */
    fun isNestedEntityOrEntityCollectionField(field: PsiField): Boolean = runCatching {
        if (findFlex(field, FQN_MANY_TO_ONE) != null || findFlex(field, FQN_ONE_TO_ONE) != null ||
            findFlex(field, FQN_ONE_TO_MANY) != null || findFlex(field, FQN_MANY_TO_MANY) != null ||
            findFlex(field, FQN_ELEMENT_COLLECTION) != null ||
            hasJoinColumnsAnnotation(field) || isEmbeddedField(field)
        ) {
            return@runCatching false
        }
        val type = field.type as? PsiClassType ?: return@runCatching false
        val typeText = runCatching { field.type.canonicalText }.getOrDefault("")
        val isCollection = COLLECTION_TYPE_PREFIXES.any { typeText.startsWith(it) }
        val parameters = type.parameters
        val elementType = when {
            isCollection && typeText.startsWith("java.util.Map") -> parameters.lastOrNull() as? PsiClassType
            isCollection -> parameters.firstOrNull() as? PsiClassType
            else -> type
        }
        val elementClass = elementType?.resolve() ?: return@runCatching false
        isLikelyMappedEntityClass(elementClass)
    }.getOrDefault(false)

    /** 集合类型前缀（canonicalText 起始匹配；Map 单列取末位泛型参数）。 */
    private val COLLECTION_TYPE_PREFIXES = listOf(
        "java.util.List<", "java.util.Set<", "java.util.Map<", "java.util.Collection<",
        "java.util.SortedSet<", "java.util.LinkedHashSet<", "java.util.ArrayList<",
    )

    /**
     * @Entity(name=...) 实体别名（T-HqlInhCols）：jakarta/javax 双包 + 短名三轨（findFlex）。
     * HQL/JPQL `FROM <实体名>` 可用该别名而非类名；未指定或读不到返回 null。
     */
    fun entityNameOf(clazz: PsiClass): String? = runCatching {
        findFlex(clazz, "jakarta.persistence.Entity")?.let { attrString(it, "name") }
    }.getOrDefault(null)

    /**
     * 类是否疑似 ORM 映射实体（SD10 嵌套实体跳过判定的保守守卫）：
     * @Entity / @Table（jakarta/javax/relational 全限定名或短名）注解，或类内任一字段标注 @Id
     * （jakarta/javax/spring-data）。@Embeddable/@MappedSuperclass 值对象不算。
     */
    fun isLikelyMappedEntityClass(clazz: PsiClass): Boolean = runCatching {
        for (annotation in clazz.modifierList?.annotations ?: emptyArray()) {
            val qName = annotation.qualifiedName ?: continue
            if (qName in MAPPED_ENTITY_CLASS_ANNOTATIONS) return@runCatching true
            val simple = qName.substringAfterLast('.')
            if (simple == "Entity" || simple == "Table") return@runCatching true
        }
        clazz.allFields.any { field ->
            findFlex(field, "jakarta.persistence.Id") != null || findFlex(field, FQN_SD_ID) != null
        }
    }.getOrDefault(false)

    private val MAPPED_ENTITY_CLASS_ANNOTATIONS = setOf(
        "jakarta.persistence.Entity", "javax.persistence.Entity",
        "jakarta.persistence.Table", "javax.persistence.Table",
        FQN_RELATIONAL_TABLE, FQN_EASY_QUERY_TABLE, FQN_XBATIS_TABLE,
    )

    /** @Convert 标记（T-J18，仅标记不改 SQL 形态）。 */
    fun hasConvertAnnotation(field: PsiField): Boolean = findFlex(field, FQN_CONVERT) != null

    /** @MapsId 标记（T-J7，仅标记）。 */
    fun hasMapsId(field: PsiField): Boolean = findFlex(field, FQN_MAPS_ID) != null

    /** Hibernate @NotFound(action = IGNORE) 标记（T-J7）；默认 action 为 EXCEPTION → false。 */
    fun isNotFoundIgnore(field: PsiField): Boolean {
        val notFound = findFlex(field, FQN_HIBERNATE_NOT_FOUND) ?: return false
        return attrEnumText(notFound, "action") == "IGNORE"
    }

    /** mappedBy 属性值（OneToOne/OneToMany/ManyToMany；null = 未声明反向侧）。 */
    fun mappedByOf(field: PsiField): String? {
        for (fqn in listOf(FQN_ONE_TO_ONE, FQN_ONE_TO_MANY, FQN_MANY_TO_MANY)) {
            val annotation = findFlex(field, fqn) ?: continue
            attrString(annotation, "mappedBy")?.let { return it }
        }
        return null
    }

    /**
     * 字段是否为嵌入对象字段（T-J18）：@Embedded / @EmbeddedId 标注，
     * 或字段类型类标注 @Embeddable（JPA 默认嵌入语义）。
     */
    fun isEmbeddedField(field: PsiField): Boolean = runCatching {
        if (findFlex(field, FQN_EMBEDDED) != null || findFlex(field, FQN_EMBEDDED_ID) != null) {
            return@runCatching true
        }
        val typeClass = (field.type as? PsiClassType)?.resolve() ?: return@runCatching false
        findFlex(typeClass, FQN_EMBEDDABLE) != null
    }.getOrDefault(false)

    /** @EmbeddedId 标注（T-J18 复合主键）。 */
    fun hasEmbeddedIdAnnotation(field: PsiField): Boolean = findFlex(field, FQN_EMBEDDED_ID) != null

    // ------------------------------------------------------------------
    // Spring Data relational @Embedded（T-Embedded D2）
    // ------------------------------------------------------------------

    /**
     * 字段是否标注 Spring Data JDBC 嵌入注解（T-Embedded D2）：裸 @Embedded、捷径
     * @Embedded.Nullable / @Embedded.Empty。捷径是组合注解——PSI 短名是末段（Nullable/Empty），
     * [findFlex] 按短名 "Embedded" 命不中，须按注解类型全名/文本匹配。
     *
     * 判定必须确证注解**属于 SD**：[findFlex] 的短名兜底轨会把 jakarta @Embedded 一起命中，
     * 不能直接当 SD 证据。注解类型可解析时按全名判定；不可解析（SD jar 不在 classpath）时按文本
     * 兜底——此时项目里不可能真用 SD，裸 @Embedded 返回 false 交给 jakarta 轨（无害），
     * 捷径 `@Embedded.` 点号形态 jakarta 不存在、按文本判定不误伤。
     */
    fun isSpringDataEmbeddedField(field: PsiField): Boolean = runCatching {
        for (simpleName in listOf("Embedded", "Nullable", "Empty")) {
            if (directAnnotations(field, simpleName).any { isSpringDataEmbeddedAnnotation(it) }) {
                return@runCatching true
            }
        }
        false
    }.getOrDefault(false)

    /**
     * SD @Embedded(prefix = "...") 的列名前缀（T-Embedded D2）：官方语义是拼在嵌入对象**每一列**
     * 之前（同一值对象多次嵌入的防撞车手段）；无注解/未指定 prefix/捷径注解（无 prefix 属性）
     * 返回空串。
     */
    fun springDataEmbeddedPrefixOf(field: PsiField): String = runCatching {
        directAnnotations(field, "Embedded")
            .firstOrNull { isSpringDataEmbeddedAnnotation(it) }
            ?.let { attrString(it, "prefix") }
    }.getOrNull().orEmpty()

    private fun isSpringDataEmbeddedAnnotation(annotation: PsiAnnotation): Boolean = runCatching {
        // 轨 1：注解类型可解析（真实项目有 SD jar）→ 按全名判定
        val qualified = annotation.qualifiedName
        if (qualified != null &&
            (qualified == FQN_RELATIONAL_EMBEDDED || qualified.startsWith("$FQN_RELATIONAL_EMBEDDED."))
        ) {
            return@runCatching true
        }
        // 轨 2：不可解析（jar 缺失/测试夹具）→ 按注解文本兜底。全限定写法（含捷径 `.Nullable`/
        // `.Empty` 与带属性 `(...)` 两种后缀形态）逐字匹配；短名捷径 `@Embedded.` 点号形态 jakarta
        // 不存在、不误伤；裸 @Embedded 无从区分且此时 SD 必不在 classpath，不认（交给 jakarta 轨）
        val text = annotation.text ?: return@runCatching false
        text.startsWith("@$FQN_RELATIONAL_EMBEDDED.") ||
            text.startsWith("@$FQN_RELATIONAL_EMBEDDED(") ||
            text.startsWith("@Embedded.")
    }.getOrDefault(false)

    /** @Column(insertable)，未指定时规范默认 true。 */
    fun columnInsertableOf(field: PsiField): Boolean =
        findFlex(field, FQN_COLUMN)?.let { attrBoolean(it, "insertable") } ?: true

    /** @Column(updatable)，未指定时规范默认 true。 */
    fun columnUpdatableOf(field: PsiField): Boolean =
        findFlex(field, FQN_COLUMN)?.let { attrBoolean(it, "updatable") } ?: true

    /**
     * @AttributeOverride(s) 覆写清单（T-J18）：嵌入字段名 → 覆写列名。
     * 兼容 repeatable 直接重复标注与 @AttributeOverrides 容器两种形态。
     */
    fun attributeOverrides(owner: PsiModifierListOwner): Map<String, String> = runCatching {
        val result = linkedMapOf<String, String>()
        val annotations = owner.modifierList?.annotations ?: return@runCatching emptyMap<String, String>()
        for (annotation in annotations) {
            when (annotation.nameReferenceElement?.referenceName) {
                "AttributeOverride" -> putAttributeOverride(annotation, result)
                "AttributeOverrides" -> attrAnnotationArray(annotation, "value").forEach { putAttributeOverride(it, result) }
            }
        }
        result
    }.getOrDefault(emptyMap())

    private fun putAttributeOverride(annotation: PsiAnnotation, into: MutableMap<String, String>) {
        val attributeName = attrString(annotation, "name") ?: return
        val column = attrAnnotation(annotation, "column") ?: return
        attrString(column, "name")?.let { into[attributeName] = it }
    }

    /**
     * 嵌入对象展平产物（T-J18）。
     *
     * @param field      嵌入对象内部字段（注释提取用）
     * @param fieldName  内部字段名（ColumnInfo.fieldName；非完整路径——嵌套路径见 [embeddedPath]）
     * @param columnName 展平列名（@AttributeOverride > 内部字段 @Column > 驼峰转下划线）
     * @param fieldType  内部字段类型文本（参数回填引号推断用）
     * @param insertable 内部字段 @Column(insertable)
     * @param updatable  内部字段 @Column(updatable)
     * @param embeddedPath 完整嵌入段驼峰路径（实体根起算，各级嵌入字段名首字母大写，如
     *   `address.zipcode.zip` → `addressZipcodeZip`）——两级以上嵌入链的派生查询路径
     *   （`findByAddressZipcodeZip`）据此命中本列（G2 修复点）
     * @param fromAttributeOverride 列名是否来自 @AttributeOverride——JPA 轨覆写名即最终列名，
     *   SD @Embedded(prefix) 不再叠加（同口径声明防撞车场景，G1 修复点）
     */
    data class EmbeddedColumnSpec(
        val field: PsiField,
        val fieldName: String,
        val columnName: String,
        val fieldType: String?,
        val insertable: Boolean,
        val updatable: Boolean,
        val embeddedPath: String,
        val fromAttributeOverride: Boolean,
    )

    /**
     * 展平嵌入对象字段为列清单（T-J18）：逐字段解析 @AttributeOverride 覆写 →
     * 内部字段 @Column → 兜底命名；嵌套 @Embedded 递归展平。
     * 嵌入类型不可解析时返回空列表（调用方降级为单列形态）。
     *
     * T-Embedded D1（点路径覆写）：[overrides] 的键支持三种形态，查找按点路径优先、裸名兜底——
     *  - 实体根相对点路径（类级 @AttributeOverride，如 `address.zipcode.zip`——覆写嵌套嵌入必须用
     *    点路径，jakarta persistence javadoc「a dot (".") notation form must be used」）；
     *  - 嵌入对象相对点路径（字段级 @AttributeOverride，规范 Example 2 的 `zipcode.zip`）；
     *  - 裸属性名（既有形态，T-J18 用例不回归）。
     *
     * T-EmbeddedColPrefix：兜底命名由 [naming] 决定——[EmbeddedColumnNaming.NONE] 用内层字段名本身
     * （JPA 默认），[EmbeddedColumnNaming.COMPONENT_PATH] 把嵌入段路径并进列名（Hibernate
     * component-path）。两档**只影响既无 @AttributeOverride 也无内层 @Column(name) 的兜底档**。
     *
     * @param rootKeyPrefix 实体根起算的键前缀（顶层嵌入字段名 + `.`，如 `address.`）——类级覆写键
     *   相对实体根，而展平内部按嵌入对象相对路径查找，靠它把两个键空间对上
     * @param naming 嵌入列命名约定（默认无前缀，保持既有行为）
     */
    fun flattenEmbeddedColumns(
        field: PsiField,
        overrides: Map<String, String>,
        naming: EmbeddedColumnNaming = EmbeddedColumnNaming.NONE,
    ): List<EmbeddedColumnSpec> {
        val clazz = runCatching { (field.type as? PsiClassType)?.resolve() }.getOrNull() ?: return emptyList()
        val rootFieldName = runCatching { field.name }.getOrNull() ?: ""
        val rootKeyPrefix = if (rootFieldName.isEmpty()) "" else "$rootFieldName."
        // camelPathPrefix：实体根起的驼峰路径累积（顶层嵌入字段名起算），逐段拼给末端列
        // snakePathPrefix：同一条路径的「下划线连接」形态，仅 COMPONENT_PATH 档用于拼列名前缀
        return flattenEmbeddableClass(
            clazz, overrides, mutableSetOf(), rootKeyPrefix,
            pathPrefix = "", camelPathPrefix = rootFieldName,
            snakePathPrefix = if (rootFieldName.isEmpty()) "" else "${rootFieldName}_",
            naming = naming,
        )
    }

    private fun flattenEmbeddableClass(
        clazz: PsiClass,
        overrides: Map<String, String>,
        seen: MutableSet<PsiClass>,
        rootKeyPrefix: String,
        pathPrefix: String,
        camelPathPrefix: String,
        snakePathPrefix: String,
        naming: EmbeddedColumnNaming,
    ): List<EmbeddedColumnSpec> {
        if (!seen.add(clazz)) return emptyList()
        val result = mutableListOf<EmbeddedColumnSpec>()
        // D254：嵌入对象同样走实体列语义收集（同名合并取子类；static/transient 已由收集器排除）
        for (field in EntityFieldCollector.collectColumns(clazz)) {
            val fieldName = runCatching { field.name }.getOrNull() ?: continue
            // 第二道防线：serialVersionUID 归一化名字（规范形态为 static final，已被收集器过滤；
            // 保留判定以挡住非规范写法）
            if (fieldName.lowercase().replace("_", "") == "serialversionuid") continue
            if (findFlex(field, FQN_TRANSIENT) != null) continue
            // 嵌套 @Embedded / @Embeddable：递归展平（D1）。覆写键空间双层并存——类级键（实体根相对）
            // 随 overrides 逐层传递、pathPrefix 累积；该字段自身 @AttributeOverride(s) 的相对键合并进
            // overrides（右侧覆盖左侧，字段级相对语义与改前「只传该字段覆写」一致）
            if (isEmbeddedField(field)) {
                val nestedClass = runCatching { (field.type as? PsiClassType)?.resolve() }.getOrNull() ?: continue
                result += flattenEmbeddableClass(
                    nestedClass,
                    overrides + attributeOverrides(field),
                    seen,
                    rootKeyPrefix,
                    pathPrefix = "$pathPrefix$fieldName.",
                    camelPathPrefix = camelPathPrefix + fieldName.replaceFirstChar { it.uppercaseChar() },
                    snakePathPrefix = "$snakePathPrefix${fieldName}_",
                    naming = naming,
                )
                continue
            }
            val columnAnnotation = findFlex(field, FQN_COLUMN)
            // 覆写查找三级兜底（实体根点路径 → 嵌入对象相对点路径 → 裸名）；命中即 JPA 轨最终列名
            val override = overrides["$rootKeyPrefix$pathPrefix$fieldName"]
                ?: overrides["$pathPrefix$fieldName"]
                ?: overrides[fieldName]
            result += EmbeddedColumnSpec(
                field = field,
                fieldName = fieldName,
                columnName = override
                    ?: columnAnnotation?.let { attrString(it, "name") }
                    ?: embeddedFallbackColumnName(snakePathPrefix, fieldName, naming),
                fieldType = runCatching { field.type.canonicalText }.getOrNull(),
                insertable = columnAnnotation?.let { attrBoolean(it, "insertable") } ?: true,
                updatable = columnAnnotation?.let { attrBoolean(it, "updatable") } ?: true,
                embeddedPath = camelPathPrefix + fieldName.replaceFirstChar { it.uppercaseChar() },
                fromAttributeOverride = override != null,
            )
        }
        return result
    }

    /**
     * 嵌入列的**兜底**列名（既无 @AttributeOverride、也无内层 @Column(name) 时，T-EmbeddedColPrefix）。
     *
     *  - [EmbeddedColumnNaming.NONE]（JPA 默认）：内层字段名本身（`baseQty` → `base_qty`）；
     *  - [EmbeddedColumnNaming.COMPONENT_PATH]：把嵌入段路径并进列名
     *    （`qty` + `baseQty` → `qty_base_qty`；多级嵌入逐段累积 `a` + `b` + `c` → `a_b_c`）。
     *
     * COMPONENT_PATH 的形态与 Hibernate 一致：hibernate-core 5.6.4
     * `ImplicitNamingStrategyComponentPathImpl.transformAttributePath` 用**原始属性名**按 `_`
     * 递归连接父路径，下划线化由 PhysicalNamingStrategy 完成；此处对连接后的整串做一次 snake 化，
     * 结果等价（`toSnakeCase("qty_baseQty")` = `qty_base_qty`）。
     *
     * @param snakePathPrefix 自实体根起的嵌入段路径（原始字段名以 `_` 连接，含尾随 `_`；顶层为空串时无前缀）
     */
    private fun embeddedFallbackColumnName(
        snakePathPrefix: String,
        fieldName: String,
        naming: EmbeddedColumnNaming,
    ): String = when (naming) {
        EmbeddedColumnNaming.NONE -> NamingStrategy.toSnakeCase(fieldName)
        EmbeddedColumnNaming.COMPONENT_PATH -> NamingStrategy.toSnakeCase(snakePathPrefix + fieldName)
    }

    // ------------------------------------------------------------------
    // 关联外键列（T-J7）
    // ------------------------------------------------------------------

    /**
     * @ToOne 字段的外键列清单（T-J7）：
     *  - @JoinColumns 容器 → 复合外键多列；
     *  - repeatable @JoinColumn 直接重复标注 ≥ 2 个 → 复合外键；
     *  - 单个 @JoinColumn（或无）→ 单列（无注解时推断 `字段名_id`，与既有列名语义一致）。
     */
    fun readToOneJoinColumns(field: PsiField, fieldName: String): List<JoinColumnRef> = runCatching {
        val container = findFlex(field, FQN_JOIN_COLUMNS)
        if (container != null) {
            val columns = attrAnnotationArray(container, "value")
            if (columns.isNotEmpty()) return@runCatching columns.map { toJoinColumnRef(it, fieldName) }
        }
        val direct = directAnnotations(field, "JoinColumn")
        if (direct.size > 1) return@runCatching direct.map { toJoinColumnRef(it, fieldName) }
        listOf(toJoinColumnRef(direct.firstOrNull(), fieldName))
    }.getOrDefault(listOf(JoinColumnRef(name = NamingStrategy.toSnakeCase(fieldName) + "_id")))

    /** 是否存在复合外键声明（@JoinColumns 容器或多个 @JoinColumn）——多列外键不落单列。 */
    fun hasCompositeJoinColumns(field: PsiField): Boolean {
        if (findFlex(field, FQN_JOIN_COLUMNS) != null) return true
        return directAnnotations(field, "JoinColumn").size > 1
    }

    private fun toJoinColumnRef(annotation: PsiAnnotation?, fieldName: String): JoinColumnRef {
        if (annotation == null) return JoinColumnRef(name = NamingStrategy.toSnakeCase(fieldName) + "_id")
        return JoinColumnRef(
            name = attrString(annotation, "name") ?: (NamingStrategy.toSnakeCase(fieldName) + "_id"),
            referencedColumnName = attrString(annotation, "referencedColumnName"),
            insertable = attrBoolean(annotation, "insertable") ?: true,
            updatable = attrBoolean(annotation, "updatable") ?: true,
        )
    }

    // ------------------------------------------------------------------
    // 集合关联（T-J7 / T-J18：@OneToMany / @ManyToMany / @ElementCollection）
    // ------------------------------------------------------------------

    /**
     * 读取集合关联元数据；非集合关联字段返回 null。
     *
     * @param targetTableNameOf 元素实体 → 表名回调（复用 JpaEntityResolver 的 @Table/命名策略推断）
     * @param entityTableName   主表名（@CollectionTable 缺省名 = 主表名_字段名 推断用）
     */
    fun readCollectionAssociation(
        field: PsiField,
        targetTableNameOf: (PsiClass) -> String,
        entityTableName: String,
    ): CollectionAssociationInfo? = runCatching {
        val fieldName = field.name ?: return@runCatching null
        val kind = when {
            findFlex(field, FQN_ELEMENT_COLLECTION) != null -> CollectionKind.ELEMENT_COLLECTION
            findFlex(field, FQN_ONE_TO_MANY) != null -> CollectionKind.ONE_TO_MANY
            findFlex(field, FQN_MANY_TO_MANY) != null -> CollectionKind.MANY_TO_MANY
            else -> return@runCatching null
        }
        val mappedBy = mappedByOf(field)
        val orderBy = findFlex(field, FQN_ORDER_BY)?.let { attrString(it, "value") }
        val orderColumn = findFlex(field, FQN_ORDER_COLUMN)?.let { attrString(it, "name") }

        // @ElementCollection：集合表（@CollectionTable）为目标表
        if (kind == CollectionKind.ELEMENT_COLLECTION) {
            val collectionTable = findFlex(field, FQN_COLLECTION_TABLE)
            val tableName = collectionTable?.let { attrString(it, "name") }
                ?: (entityTableName + "_" + NamingStrategy.toSnakeCase(fieldName))
            val joinColumns = collectionTable
                ?.let { attrAnnotationArray(it, "joinColumns") }
                ?.map { toJoinColumnRef(it, fieldName) }
                ?: emptyList()
            val joinTable = collectionTable?.let {
                JoinTableInfo(
                    tableName = tableName,
                    schema = attrString(it, "schema"),
                    catalog = attrString(it, "catalog"),
                    joinColumns = joinColumns,
                )
            }
            return@runCatching CollectionAssociationInfo(
                fieldName = fieldName,
                kind = kind,
                targetTable = tableName,
                mappedBy = mappedBy,
                joinTable = joinTable,
                joinColumns = joinColumns,
                orderBy = orderBy,
                orderColumn = orderColumn,
            )
        }

        // @OneToMany / @ManyToMany：元素实体表；元素类型不可解析时按泛型文本推断（优雅降级）
        val targetTable = elementEntityTableName(field, targetTableNameOf) ?: return@runCatching null
        // @JoinTable 中间表（ManyToMany 常规、OneToMany 少见合法形态）
        val joinTable = findFlex(field, FQN_JOIN_TABLE)?.let { jt ->
            JoinTableInfo(
                tableName = attrString(jt, "name"),
                schema = attrString(jt, "schema"),
                catalog = attrString(jt, "catalog"),
                joinColumns = attrAnnotationArray(jt, "joinColumns").map { toJoinColumnRef(it, fieldName) },
                inverseJoinColumns = attrAnnotationArray(jt, "inverseJoinColumns").map { toJoinColumnRef(it, fieldName) },
            )
        }
        // 单向 @OneToMany + @JoinColumn：外键列在目标表（独立 UPDATE 子表语义）
        val joinColumns = if (kind == CollectionKind.ONE_TO_MANY && mappedBy == null && hasJoinColumnsAnnotation(field)) {
            readToOneJoinColumns(field, fieldName)
        } else {
            emptyList()
        }
        CollectionAssociationInfo(
            fieldName = fieldName,
            kind = kind,
            targetTable = targetTable,
            mappedBy = mappedBy,
            joinTable = joinTable,
            joinColumns = joinColumns,
            orderBy = orderBy,
            orderColumn = orderColumn,
        )
    }.getOrNull()

    /** 字段是否存在任何 @JoinColumn 声明（容器或直接标注）。 */
    private fun hasJoinColumnsAnnotation(field: PsiField): Boolean =
        findFlex(field, FQN_JOIN_COLUMNS) != null || directAnnotations(field, "JoinColumn").isNotEmpty()

    /** 集合字段元素实体表名：元素类可解析走回调，否则按泛型文本推断蛇形表名。 */
    private fun elementEntityTableName(
        field: PsiField,
        targetTableNameOf: (PsiClass) -> String,
    ): String? {
        val type = runCatching { field.type as? PsiClassType }.getOrNull() ?: return null
        val elementType = type?.parameters?.firstOrNull() as? PsiClassType
        val elementClass = elementType?.resolve()
        if (elementClass != null) return targetTableNameOf(elementClass)
        val text = runCatching { field.type.canonicalText }.getOrNull() ?: return null
        val elementText = text.substringAfter('<', "").substringBefore('>', "").substringAfterLast('.')
        return elementText.takeIf { it.isNotBlank() }?.let { NamingStrategy.toSnakeCase(it) }
    }
}
