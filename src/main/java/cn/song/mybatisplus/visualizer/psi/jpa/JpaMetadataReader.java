package cn.song.mybatisplus.visualizer.psi.jpa;

import cn.song.mybatisplus.visualizer.model.CollectionAssociationInfo;
import cn.song.mybatisplus.visualizer.model.CollectionKind;
import cn.song.mybatisplus.visualizer.model.CustomSqlScripts;
import cn.song.mybatisplus.visualizer.model.GeneratorInfo;
import cn.song.mybatisplus.visualizer.model.JoinColumnRef;
import cn.song.mybatisplus.visualizer.model.JoinTableInfo;
import cn.song.mybatisplus.visualizer.model.SecondaryTableInfo;
import cn.song.mybatisplus.visualizer.model.SequenceGeneratorInfo;
import cn.song.mybatisplus.visualizer.model.SoftDeleteInfo;
import cn.song.mybatisplus.visualizer.model.TableGeneratorInfo;
import cn.song.mybatisplus.visualizer.psi.EntityFieldCollector;
import cn.song.mybatisplus.visualizer.util.NamingStrategy;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * JPA 元数据纯函数读取器（M39：T-J7 关联增强 / T-J8 软删除与动态 SQL / T-J14 主键生成值 / T-J18 映射深水区）。
 *
 * <p>职责边界：只读 PSI、产出纯数据；不做缓存（随 TableInfo 三维缓存一并缓存）、
 * 不抛异常（所有 PSI 访问 try/catch 包裹，解析不出即优雅降级为空/null）。
 *
 * <p>注解识别三轨（T-J14 双包名要求）：
 * <ol>
 *   <li>jakarta.persistence 全限定名精确匹配；</li>
 *   <li>javax.persistence 全限定名精确匹配（Spring Boot 2 / Hibernate 5 老工程）；</li>
 *   <li>短名兜底（注解类不可解析 / 混用 import 场景）。</li>
 * </ol>
 * Hibernate 专有注解（org.hibernate.annotations）为 FQN + 短名双轨。
 *
 * <p>属性读取容错：注解类不可解析时 {@code findAttributeValue} 走 PSI 本地参数列表回退，
 * 字面量属性照常可读；枚举属性取引用文本末段（{@code GenerationType.IDENTITY} → IDENTITY）。
 */
public final class JpaMetadataReader {

    public static final JpaMetadataReader INSTANCE = new JpaMetadataReader();

    private JpaMetadataReader() {
    }

    // ------------------------------------------------------------------
    // 注解全限定名常量（jakarta 为主，javax 双轨由 findFlex 内部处理；Hibernate 专有单列）
    // ------------------------------------------------------------------

    public static final String FQN_TABLE = "jakarta.persistence.Table";
    public static final String FQN_COLUMN = "jakarta.persistence.Column";
    public static final String FQN_TRANSIENT = "jakarta.persistence.Transient";
    public static final String FQN_ONE_TO_ONE = "jakarta.persistence.OneToOne";
    public static final String FQN_ONE_TO_MANY = "jakarta.persistence.OneToMany";
    public static final String FQN_MANY_TO_ONE = "jakarta.persistence.ManyToOne";
    public static final String FQN_MANY_TO_MANY = "jakarta.persistence.ManyToMany";
    public static final String FQN_JOIN_COLUMNS = "jakarta.persistence.JoinColumns";
    public static final String FQN_JOIN_TABLE = "jakarta.persistence.JoinTable";
    public static final String FQN_GENERATED_VALUE = "jakarta.persistence.GeneratedValue";
    public static final String FQN_SEQUENCE_GENERATORS = "jakarta.persistence.SequenceGenerators";
    public static final String FQN_TABLE_GENERATORS = "jakarta.persistence.TableGenerators";
    public static final String FQN_VERSION = "jakarta.persistence.Version";
    public static final String FQN_ELEMENT_COLLECTION = "jakarta.persistence.ElementCollection";
    public static final String FQN_COLLECTION_TABLE = "jakarta.persistence.CollectionTable";
    public static final String FQN_EMBEDDED = "jakarta.persistence.Embedded";
    public static final String FQN_EMBEDDED_ID = "jakarta.persistence.EmbeddedId";
    public static final String FQN_EMBEDDABLE = "jakarta.persistence.Embeddable";
    public static final String FQN_ID_CLASS = "jakarta.persistence.IdClass";
    public static final String FQN_INHERITANCE = "jakarta.persistence.Inheritance";
    public static final String FQN_DISCRIMINATOR_COLUMN = "jakarta.persistence.DiscriminatorColumn";
    public static final String FQN_DISCRIMINATOR_VALUE = "jakarta.persistence.DiscriminatorValue";
    public static final String FQN_SECONDARY_TABLES = "jakarta.persistence.SecondaryTables";
    public static final String FQN_PRIMARY_KEY_JOIN_COLUMNS = "jakarta.persistence.PrimaryKeyJoinColumns";
    public static final String FQN_ORDER_BY = "jakarta.persistence.OrderBy";
    public static final String FQN_ORDER_COLUMN = "jakarta.persistence.OrderColumn";
    public static final String FQN_MAPS_ID = "jakarta.persistence.MapsId";
    public static final String FQN_CONVERT = "jakarta.persistence.Convert";

    public static final String FQN_HIBERNATE_WHERE = "org.hibernate.annotations.Where";
    public static final String FQN_HIBERNATE_SQL_RESTRICTION = "org.hibernate.annotations.SQLRestriction";
    public static final String FQN_HIBERNATE_SOFT_DELETE = "org.hibernate.annotations.SoftDelete";
    public static final String FQN_HIBERNATE_SQL_DELETE = "org.hibernate.annotations.SQLDelete";
    public static final String FQN_HIBERNATE_SQL_DELETE_ALL = "org.hibernate.annotations.SQLDeleteAll";
    public static final String FQN_HIBERNATE_SQL_INSERT = "org.hibernate.annotations.SQLInsert";
    public static final String FQN_HIBERNATE_SQL_UPDATE = "org.hibernate.annotations.SQLUpdate";
    public static final String FQN_HIBERNATE_NOT_FOUND = "org.hibernate.annotations.NotFound";

    // ------------------------------------------------------------------
    // M44 其他 ORM 第一批注解轨（SD8 / EQ8 / EQ9 / XB 实体解析；javap 实证属性面）
    // ------------------------------------------------------------------

    /** SD8：Spring Data relational @Table（value/name/schema——value 简写最常用）。 */
    public static final String FQN_RELATIONAL_TABLE = "org.springframework.data.relational.core.mapping.Table";

    /** SD8：Spring Data relational @Column（javap 证实**只有 value 属性**，无 name）。 */
    public static final String FQN_RELATIONAL_COLUMN = "org.springframework.data.relational.core.mapping.Column";

    /** SD8：Spring Data relational @Embedded。 */
    public static final String FQN_RELATIONAL_EMBEDDED = "org.springframework.data.relational.core.mapping.Embedded";

    /** SD10：Spring Data relational @MappedCollection（集合/嵌套实体反向映射，止血期不展开）。 */
    public static final String FQN_RELATIONAL_MAPPED_COLLECTION =
            "org.springframework.data.relational.core.mapping.MappedCollection";

    /** SD8：spring-data-commons @Id（org.springframework.data.annotation.Id，非 jakarta 包）。 */
    public static final String FQN_SD_ID = "org.springframework.data.annotation.Id";

    /** SD8：spring-data-commons @Transient。 */
    public static final String FQN_SD_TRANSIENT = "org.springframework.data.annotation.Transient";

    /** SD8：spring-data-commons @Version。 */
    public static final String FQN_SD_VERSION = "org.springframework.data.annotation.Version";

    /** EQ8：Easy-Query @Table（value/schema/oldName；javap 实证）。 */
    public static final String FQN_EASY_QUERY_TABLE = "com.easy.query.core.annotation.Table";

    /** EQ8：Easy-Query @Column（value/primaryKey/exist 等；javap 实证）。 */
    public static final String FQN_EASY_QUERY_COLUMN = "com.easy.query.core.annotation.Column";

    /** EQ8：Easy-Query @ColumnIgnore（字段级跳过）。 */
    public static final String FQN_EASY_QUERY_COLUMN_IGNORE = "com.easy.query.core.annotation.ColumnIgnore";

    /** EQ9：Easy-Query @Version（乐观锁）。 */
    public static final String FQN_EASY_QUERY_VERSION = "com.easy.query.core.annotation.Version";

    /** EQ9：Easy-Query @LogicDelete（逻辑删除，strategy/strategyName）。 */
    public static final String FQN_EASY_QUERY_LOGIC_DELETE = "com.easy.query.core.annotation.LogicDelete";

    /** XB：xbatis @Table（value/schema；javap cn.xbatis.db.annotations.Table 实证）。 */
    public static final String FQN_XBATIS_TABLE = "cn.xbatis.db.annotations.Table";

    /** XB：xbatis @TableId（主键）。 */
    public static final String FQN_XBATIS_TABLE_ID = "cn.xbatis.db.annotations.TableId";

    /** XB：xbatis @TableField（value 列名 / exists 存在性；javap 实证）。 */
    public static final String FQN_XBATIS_TABLE_FIELD = "cn.xbatis.db.annotations.TableField";

    /** XB：xbatis @Ignore（字段级跳过）。 */
    public static final String FQN_XBATIS_IGNORE = "cn.xbatis.db.annotations.Ignore";

    /** XB：xbatis @Version（乐观锁）。 */
    public static final String FQN_XBATIS_VERSION = "cn.xbatis.db.annotations.Version";

    /** XB：xbatis @LogicDelete（逻辑删除，beforeValue/afterValue）。 */
    public static final String FQN_XBATIS_LOGIC_DELETE = "cn.xbatis.db.annotations.LogicDelete";

    // ------------------------------------------------------------------
    // 注解查找与属性读取（纯函数）
    // ------------------------------------------------------------------

    /** 三轨注解查找：jakarta FQN → javax FQN（jakarta.persistence 系）→ 短名兜底。 */
    public static PsiAnnotation findFlex(PsiModifierListOwner owner, String annotationFqn) {
        try {
            if (owner == null || owner.getModifierList() == null) return null;
            PsiAnnotation hit = owner.getModifierList().findAnnotation(annotationFqn);
            if (hit != null) return hit;
            if (annotationFqn.startsWith("jakarta.persistence.")) {
                int dot = annotationFqn.lastIndexOf('.');
                String simple = dot >= 0 ? annotationFqn.substring(dot + 1) : annotationFqn;
                hit = owner.getModifierList().findAnnotation("javax.persistence." + simple);
                if (hit != null) return hit;
            }
            int dot = annotationFqn.lastIndexOf('.');
            String simple = dot >= 0 ? annotationFqn.substring(dot + 1) : annotationFqn;
            for (PsiAnnotation annotation : owner.getModifierList().getAnnotations()) {
                String shortName = annotation.getNameReferenceElement() != null
                        ? annotation.getNameReferenceElement().getReferenceName() : null;
                if (simple.equals(shortName)) return annotation;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 首个非空白字符串属性（按给定顺序尝试多个属性名，如 @Where 的 clause → value 兜底）。 */
    public static String attrString(PsiAnnotation annotation, String... attributes) {
        for (String attribute : attributes) {
            PsiAnnotationMemberValue value = annotation.findAttributeValue(attribute);
            if (value == null) continue;
            String text = value instanceof PsiLiteralExpression literal && literal.getValue() instanceof String s
                    ? s : null;
            if (text != null && !text.isBlank()) return text;
        }
        return null;
    }

    /** 布尔属性；未显式指定时依赖解析到的注解默认值，解析不出返回 null（由调用方兜底）。 */
    public static Boolean attrBoolean(PsiAnnotation annotation, String attribute) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attribute);
        return value instanceof PsiLiteralExpression literal && literal.getValue() instanceof Boolean b ? b : null;
    }

    /** 整型属性文本（allocationSize 等；字符串数字形式容错）。 */
    public static String attrIntText(PsiAnnotation annotation, String attribute) {
        PsiAnnotationMemberValue memberValue = annotation.findAttributeValue(attribute);
        if (!(memberValue instanceof PsiLiteralExpression literal)) return null;
        Object value = literal.getValue();
        if (value == null) return null;
        if (value instanceof Integer || value instanceof Long) return value.toString();
        if (value instanceof String s && !s.isBlank()) return s;
        return null;
    }

    /** 枚举属性文本末段（`GenerationType.IDENTITY` / `jakarta…IDENTITY` / `IDENTITY` → IDENTITY，统一大写）。 */
    public static String attrEnumText(PsiAnnotation annotation, String attribute) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attribute);
        if (value == null || value.getText() == null) return null;
        String text = value.getText();
        int dot = text.lastIndexOf('.');
        String last = (dot >= 0 ? text.substring(dot + 1) : text).toUpperCase();
        return !last.isBlank() ? last : null;
    }

    /** 注解型属性（@AttributeOverride 的 column 等）。 */
    public static PsiAnnotation attrAnnotation(PsiAnnotation annotation, String attribute) {
        return annotation.findAttributeValue(attribute) instanceof PsiAnnotation a ? a : null;
    }

    /** 注解数组属性（@JoinColumns({…}) 的 value 等；花括号缺省的单元素形式同样兼容）。 */
    public static List<PsiAnnotation> attrAnnotationArray(PsiAnnotation annotation, String attribute) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attribute);
        if (value instanceof PsiArrayInitializerMemberValue arrayValue) {
            List<PsiAnnotation> result = new ArrayList<>();
            for (PsiAnnotationMemberValue element : arrayValue.getInitializers()) {
                if (element instanceof PsiAnnotation a) result.add(a);
            }
            return result;
        }
        if (value instanceof PsiAnnotation a) return List.of(a);
        return List.of();
    }

    /** 按 short 名收集 owner 上直接标注的注解（repeatable 注解无容器重复标注形态）。 */
    private static List<PsiAnnotation> directAnnotations(PsiModifierListOwner owner, String simpleName) {
        if (owner == null || owner.getModifierList() == null) return List.of();
        List<PsiAnnotation> result = new ArrayList<>();
        for (PsiAnnotation annotation : owner.getModifierList().getAnnotations()) {
            String shortName = annotation.getNameReferenceElement() != null
                    ? annotation.getNameReferenceElement().getReferenceName() : null;
            if (simpleName.equals(shortName)) result.add(annotation);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 类级元数据（T-J8 / T-J14 / T-J18）
    // ------------------------------------------------------------------

    /** 类级元数据聚合体（全部字段带默认值，解析失败整体降级为空元数据）。 */
    public static final class ClassMeta {
        private final String schema;
        private final String catalog;
        private final String restrictionClause;
        private final SoftDeleteInfo softDelete;
        private final CustomSqlScripts customSqlScripts;
        private final List<SequenceGeneratorInfo> sequenceGenerators;
        private final List<TableGeneratorInfo> tableGenerators;
        private final List<SecondaryTableInfo> secondaryTables;
        private final List<JoinColumnRef> primaryKeyJoinColumns;
        private final String inheritanceStrategy;
        private final String discriminatorColumn;
        private final String discriminatorValue;
        private final String idClassType;

        ClassMeta() {
            this(null, null, null, null, null, List.of(), List.of(), List.of(), List.of(),
                    null, null, null, null);
        }

        ClassMeta(String schema, String catalog, String restrictionClause, SoftDeleteInfo softDelete,
                  CustomSqlScripts customSqlScripts, List<SequenceGeneratorInfo> sequenceGenerators,
                  List<TableGeneratorInfo> tableGenerators, List<SecondaryTableInfo> secondaryTables,
                  List<JoinColumnRef> primaryKeyJoinColumns, String inheritanceStrategy,
                  String discriminatorColumn, String discriminatorValue, String idClassType) {
            this.schema = schema;
            this.catalog = catalog;
            this.restrictionClause = restrictionClause;
            this.softDelete = softDelete;
            this.customSqlScripts = customSqlScripts;
            this.sequenceGenerators = sequenceGenerators;
            this.tableGenerators = tableGenerators;
            this.secondaryTables = secondaryTables;
            this.primaryKeyJoinColumns = primaryKeyJoinColumns;
            this.inheritanceStrategy = inheritanceStrategy;
            this.discriminatorColumn = discriminatorColumn;
            this.discriminatorValue = discriminatorValue;
            this.idClassType = idClassType;
        }

        public String getSchema() {
            return schema;
        }

        public String getCatalog() {
            return catalog;
        }

        public String getRestrictionClause() {
            return restrictionClause;
        }

        public SoftDeleteInfo getSoftDelete() {
            return softDelete;
        }

        public CustomSqlScripts getCustomSqlScripts() {
            return customSqlScripts;
        }

        public List<SequenceGeneratorInfo> getSequenceGenerators() {
            return sequenceGenerators;
        }

        public List<TableGeneratorInfo> getTableGenerators() {
            return tableGenerators;
        }

        public List<SecondaryTableInfo> getSecondaryTables() {
            return secondaryTables;
        }

        public List<JoinColumnRef> getPrimaryKeyJoinColumns() {
            return primaryKeyJoinColumns;
        }

        public String getInheritanceStrategy() {
            return inheritanceStrategy;
        }

        public String getDiscriminatorColumn() {
            return discriminatorColumn;
        }

        public String getDiscriminatorValue() {
            return discriminatorValue;
        }

        public String getIdClassType() {
            return idClassType;
        }
    }

    /** 读取类级元数据；任何 PSI 异常整体降级为空元数据，绝不抛出。 */
    public static ClassMeta readClassMeta(PsiClass clazz) {
        try {
            return readClassMetaInternal(clazz);
        } catch (Throwable ignored) {
            return new ClassMeta();
        }
    }

    private static ClassMeta readClassMetaInternal(PsiClass clazz) {
        // @Table(schema/catalog)（T-J18）
        PsiAnnotation tableAnnotation = findFlex(clazz, FQN_TABLE);
        String schema = tableAnnotation != null ? attrString(tableAnnotation, "schema") : null;
        String catalog = tableAnnotation != null ? attrString(tableAnnotation, "catalog") : null;

        // 静态限制子句（T-J8）：@SQLRestriction（6.3+）优先——它取代了 @Where；
        // @Where 读 clause（Hibernate 5/6）value 兜底
        PsiAnnotation sqlRestriction = findFlex(clazz, FQN_HIBERNATE_SQL_RESTRICTION);
        String restrictionClause = sqlRestriction != null ? attrString(sqlRestriction, "value") : null;
        if (restrictionClause == null) {
            PsiAnnotation where = findFlex(clazz, FQN_HIBERNATE_WHERE);
            restrictionClause = where != null ? attrString(where, "clause", "value") : null;
        }

        // 软删除（T-J8 @SoftDelete）：默认列 deleted / 默认策略 DELETED（Hibernate 6.4 规范默认）
        SoftDeleteInfo softDelete = null;
        PsiAnnotation sd = findFlex(clazz, FQN_HIBERNATE_SOFT_DELETE);
        if (sd != null) {
            softDelete = new SoftDeleteInfo(
                    attrString(sd, "columnName") != null ? attrString(sd, "columnName") : "deleted",
                    attrEnumText(sd, "strategy") != null ? attrEnumText(sd, "strategy") : "DELETED");
        }

        // 自定义 SQL 脚本（T-J8）：@SQLDelete 族属性名为 sql（value 兜底容错）
        PsiAnnotation sqlInsert = findFlex(clazz, FQN_HIBERNATE_SQL_INSERT);
        PsiAnnotation sqlDelete = findFlex(clazz, FQN_HIBERNATE_SQL_DELETE);
        PsiAnnotation sqlDeleteAll = findFlex(clazz, FQN_HIBERNATE_SQL_DELETE_ALL);
        PsiAnnotation sqlUpdate = findFlex(clazz, FQN_HIBERNATE_SQL_UPDATE);
        String insertText = sqlInsert != null ? attrString(sqlInsert, "sql", "value") : null;
        String deleteText = sqlDelete != null ? attrString(sqlDelete, "sql", "value") : null;
        String deleteAllText = sqlDeleteAll != null ? attrString(sqlDeleteAll, "sql", "value") : null;
        String updateText = sqlUpdate != null ? attrString(sqlUpdate, "sql", "value") : null;
        CustomSqlScripts customSqlScripts = insertText != null || deleteText != null
                || deleteAllText != null || updateText != null
                ? new CustomSqlScripts(insertText, deleteText, deleteAllText, updateText) : null;

        // 生成器（T-J14）：类级 repeatable + 容器注解 + 字段级三处扫描，按 name 归并（先见者优先）
        List<SequenceGeneratorInfo> sequenceGenerators = collectGenerators(clazz, "SequenceGenerator",
                FQN_SEQUENCE_GENERATORS, anno -> {
                    String name = attrString(anno, "name");
                    if (name == null) return null;
                    return new SequenceGeneratorInfo(name,
                            attrString(anno, "sequenceName"),
                            attrIntText(anno, "allocationSize"),
                            attrIntText(anno, "initialValue"),
                            attrString(anno, "schema"));
                });
        List<TableGeneratorInfo> tableGenerators = collectGenerators(clazz, "TableGenerator",
                FQN_TABLE_GENERATORS, anno -> {
                    String name = attrString(anno, "name");
                    if (name == null) return null;
                    return new TableGeneratorInfo(name,
                            attrString(anno, "table"),
                            attrString(anno, "pkColumnName"),
                            attrString(anno, "valueColumnName"),
                            attrString(anno, "pkColumnValue"),
                            attrIntText(anno, "allocationSize"));
                });

        // 副表（T-J18 @SecondaryTable(s)）
        List<PsiAnnotation> secondaryAnnotations = new ArrayList<>(directAnnotations(clazz, "SecondaryTable"));
        PsiAnnotation secondaryContainer = findFlex(clazz, FQN_SECONDARY_TABLES);
        if (secondaryContainer != null) secondaryAnnotations.addAll(attrAnnotationArray(secondaryContainer, "value"));
        List<SecondaryTableInfo> secondaryTables = new ArrayList<>();
        for (PsiAnnotation st : secondaryAnnotations) {
            String name = attrString(st, "name");
            if (name == null) continue;
            List<JoinColumnRef> pkJoinColumns = new ArrayList<>();
            for (PsiAnnotation pk : attrAnnotationArray(st, "pkJoinColumns")) {
                pkJoinColumns.add(toPkJoinColumnRef(pk));
            }
            secondaryTables.add(new SecondaryTableInfo(name, attrString(st, "schema"),
                    attrString(st, "catalog"), pkJoinColumns));
        }

        // 类级 @PrimaryKeyJoinColumn(s)（T-J18，JOINED 继承场景）
        List<PsiAnnotation> pkJoinAnnotations = new ArrayList<>(directAnnotations(clazz, "PrimaryKeyJoinColumn"));
        PsiAnnotation pkJoinContainer = findFlex(clazz, FQN_PRIMARY_KEY_JOIN_COLUMNS);
        if (pkJoinContainer != null) pkJoinAnnotations.addAll(attrAnnotationArray(pkJoinContainer, "value"));
        List<JoinColumnRef> primaryKeyJoinColumns = new ArrayList<>();
        for (PsiAnnotation pk : pkJoinAnnotations) {
            primaryKeyJoinColumns.add(toPkJoinColumnRef(pk));
        }

        // 继承与判别（T-J18）：strategy 缺省即规范默认 SINGLE_TABLE；判别列缺省名 DTYPE
        PsiAnnotation inheritance = findFlex(clazz, FQN_INHERITANCE);
        String inheritanceStrategy = inheritance != null
                ? (attrEnumText(inheritance, "strategy") != null ? attrEnumText(inheritance, "strategy") : "SINGLE_TABLE")
                : null;
        PsiAnnotation discriminatorCol = findFlex(clazz, FQN_DISCRIMINATOR_COLUMN);
        String discriminatorColumn = discriminatorCol != null
                ? (attrString(discriminatorCol, "name") != null ? attrString(discriminatorCol, "name") : "DTYPE")
                : null;
        PsiAnnotation discriminatorVal = findFlex(clazz, FQN_DISCRIMINATOR_VALUE);
        String discriminatorValue = discriminatorVal != null ? attrString(discriminatorVal, "value") : null;

        // @IdClass 复合主键类（T-J18）：`UserPk.class` / `com.x.UserPk.class` → UserPk
        String idClassType = null;
        PsiAnnotation idClass = findFlex(clazz, FQN_ID_CLASS);
        if (idClass != null && idClass.findAttributeValue("value") != null
                && idClass.findAttributeValue("value").getText() != null) {
            String text = idClass.findAttributeValue("value").getText();
            if (text.endsWith(".class")) text = text.substring(0, text.length() - ".class".length());
            int dot = text.lastIndexOf('.');
            String simple = dot >= 0 ? text.substring(dot + 1) : text;
            if (!simple.isBlank()) idClassType = simple;
        }

        return new ClassMeta(schema, catalog, restrictionClause, softDelete, customSqlScripts,
                sequenceGenerators, tableGenerators, secondaryTables, primaryKeyJoinColumns,
                inheritanceStrategy, discriminatorColumn, discriminatorValue, idClassType);
    }

    /**
     * 生成器统一收集：类级直接标注 + 容器注解 + 字段级标注，
     * 按 GeneratorInfo.name 归并去重（同名生成器先见者优先——收集顺序即类级直接标注在容器之前）。
     */
    private static <T extends GeneratorInfo> List<T> collectGenerators(
            PsiClass clazz, String simpleName, String containerFqn, Function<PsiAnnotation, T> toInfo) {
        List<PsiAnnotation> annotations = new ArrayList<>(directAnnotations(clazz, simpleName));
        PsiAnnotation container = findFlex(clazz, containerFqn);
        if (container != null) annotations.addAll(attrAnnotationArray(container, "value"));
        for (PsiField field : clazz.getAllFields()) {
            annotations.addAll(directAnnotations(field, simpleName));
        }
        List<T> result = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();
        for (PsiAnnotation anno : annotations) {
            T info = toInfo.apply(anno);
            if (info != null && seenNames.add(info.getName())) {
                result.add(info);
            }
        }
        return result;
    }

    private static JoinColumnRef toPkJoinColumnRef(PsiAnnotation annotation) {
        return new JoinColumnRef(
                attrString(annotation, "name") != null ? attrString(annotation, "name") : "",
                attrString(annotation, "referencedColumnName"));
    }

    // ------------------------------------------------------------------
    // 字段级：主键生成值 / 乐观锁 / 嵌入 / 转换（T-J14 / T-J8 / T-J18）
    // ------------------------------------------------------------------

    /** @GeneratedValue 策略文本；注解存在而未指定 strategy 时为规范默认 AUTO。 */
    public static String generatedValueStrategy(PsiField field) {
        PsiAnnotation generated = findFlex(field, FQN_GENERATED_VALUE);
        if (generated == null) return null;
        String strategy = attrEnumText(generated, "strategy");
        return strategy != null ? strategy : "AUTO";
    }

    /** jakarta/javax @Version（T-J8，与 MP @Version 同列语义）。 */
    public static boolean hasVersionAnnotation(PsiField field) {
        return findFlex(field, FQN_VERSION) != null;
    }

    /**
     * M44 全框架 @Version（乐观锁）识别：jakarta/javax + spring-data-commons（SD8）+
     * Easy-Query（EQ9）+ xbatis（XB）。任一命中即视为版本列。
     */
    public static boolean hasAnyVersionAnnotation(PsiField field) {
        return hasVersionAnnotation(field)
                || findFlex(field, FQN_SD_VERSION) != null
                || findFlex(field, FQN_EASY_QUERY_VERSION) != null
                || findFlex(field, FQN_XBATIS_VERSION) != null;
    }

    /**
     * EQ9/XB：@LogicDelete（Easy-Query / xbatis）标记字段的列名。
     * 列名 = 字段 @Column/@TableField value（EQ 轨已由 value 兜底覆盖）或驼峰转下划线；无标记返回 null。
     */
    public static String logicDeleteColumnOf(PsiField field, String fieldName) {
        boolean marked = findFlex(field, FQN_EASY_QUERY_LOGIC_DELETE) != null
                || findFlex(field, FQN_XBATIS_LOGIC_DELETE) != null;
        if (!marked) return null;
        return columnValueOrSnake(field, fieldName);
    }

    /**
     * 字段显式列名（M44 统一链）：jakarta/javax/relational/EQ @Column（name → value 兜底）→
     * xbatis @TableField(value) → <b>同名 getter 上的 @Column（O-58/T-07）</b> → 驼峰转下划线。
     */
    public static String columnValueOrSnake(PsiField field, String fieldName) {
        PsiAnnotation column = findFlex(field, FQN_COLUMN);
        if (column != null) {
            String name = attrString(column, "name", "value");
            if (name != null) return name;
        }
        PsiAnnotation tf = findFlex(field, FQN_XBATIS_TABLE_FIELD);
        if (tf != null) {
            String name = attrString(tf, "value");
            if (name != null) return name;
        }
        // O-58/T-07：JPA 的 @Access(AccessType.PROPERTY) 与「注解写在 getter 上」的工程里，
        // 字段侧一个 @Column 都没有，此前静默回落 snake 盲猜（用户报障「字段命名不标准」的形态之一，
        // 但与命名策略猜错在现象上不可区分）。回退读同名 getter 的 @Column 即可救回真值。
        String getterColumn = getterColumnNameOf(field);
        if (getterColumn != null) return getterColumn;
        return NamingStrategy.INSTANCE.toSnakeCase(fieldName);
    }

    /**
     * O-58/T-07：同名 getter（{@code getXxx} / {@code isXxx}）上的 @Column 列名。
     *
     * <p><b>范围收窄</b>（有意为之）：只做 @Column 的 name/value 回退，不实现完整 JPA 属性访问语义——
     * 字段与 getter 注解冲突时以 @Access 为准这类判定、以及 @JoinColumn/@TableField 的 getter 回退
     * 都不在本函数内（多读一条即多一份半套语义的风险，且数据源校正已能覆盖大部分错配）。</p>
     *
     * <p><b>不受「列名数据源校正」开关约束</b>：本函数读的是源码里本来就写着的 {@code @Column}，不查数据源，
     * 属<b>离线纠错</b>——无数据源时它照样生效（实体列名因此可能比改造前更准）。受开关与数据源可用性
     * 约束的是 {@code psi.ColumnNameCalibrator} 那层，两者在交付说明里必须分开表述。</p>
     *
     * <p><b>MP 侧没有对应回退是有意的</b>（与 JPA 侧不对称，实读确认）：MyBatis-Plus 按<b>字段</b>建映射，
     * 且其注解契约根本不允许写 getter——{@code mybatis-plus-annotation-3.5.17} 的 {@code TableField} 字节码实证
     * {@code @Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE})}（{@code TableName} 为 {@code {TYPE,
     * ANNOTATION_TYPE}}），{@code METHOD} 不在合法目标里。所以「@TableField 写在 getter 上」在 MP 工程里
     * 既编译不过、MP 运行时也不会读——给 MP 侧加 getter 回退等于实现一个 MP 自己都没有的语义。</p>
     *
     * 匹配口径：方法名去 {@code get}/{@code is} 前缀后与字段名<b>忽略大小写</b>相等（{@code orderID} 的 getter
     * {@code getOrderID} / {@code getOrderId} 都算命中）；只有无参 getter 才可能是属性访问点。
     */
    private static String getterColumnNameOf(PsiField field) {
        try {
            PsiClass owner = field.getContainingClass();
            if (owner == null) return null;
            String fieldName = field.getName();
            if (fieldName == null) return null;
            for (com.intellij.psi.PsiMethod method : owner.getMethods()) {
                if (method.getParameterList().getParameters().length != 0) continue;
                String methodName = method.getName();
                String property;
                if (methodName.length() > 3 && methodName.startsWith("get")) {
                    property = methodName.substring(3);
                } else if (methodName.length() > 2 && methodName.startsWith("is")) {
                    property = methodName.substring(2);
                } else {
                    continue;
                }
                if (!property.equalsIgnoreCase(fieldName)) continue;
                PsiAnnotation columnAnnotation = findFlex(method, FQN_COLUMN);
                if (columnAnnotation == null) continue;
                String name = attrString(columnAnnotation, "name", "value");
                if (name != null) return name;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * M44 字段级跳过注解全集：jakarta/javax @Transient（既有）+ spring-data @Transient（SD8）+
     * Easy-Query @ColumnIgnore（EQ8）+ xbatis @Ignore（XB）。
     */
    public static boolean hasTransientLikeAnnotation(PsiField field) {
        return findFlex(field, FQN_TRANSIENT) != null
                || findFlex(field, FQN_SD_TRANSIENT) != null
                || findFlex(field, FQN_EASY_QUERY_COLUMN_IGNORE) != null
                || findFlex(field, FQN_XBATIS_IGNORE) != null;
    }

    /**
     * M44 存在性为 false 的字段（不出现在任何 SQL 列清单）：
     * Easy-Query @Column(exist = false) / xbatis @TableField(exists = false)。
     * 注解类不可解析时 findAttributeValue 走本地参数列表回退，显式 false 照常可读；
     * 未指定时（解析到默认 true / 读不到）不跳过。
     */
    public static boolean hasExistFalse(PsiField field) {
        for (String fqn : Arrays.asList(FQN_EASY_QUERY_COLUMN, FQN_XBATIS_TABLE_FIELD)) {
            PsiAnnotation annotation = findFlex(field, fqn);
            if (annotation == null) continue;
            if (Boolean.FALSE.equals(attrBoolean(annotation, "exist"))
                    || Boolean.FALSE.equals(attrBoolean(annotation, "exists"))) {
                return true;
            }
        }
        return false;
    }

    /** SD10：字段是否标注 @MappedCollection（Spring Data relational 集合/嵌套实体反向映射）。 */
    public static boolean isMappedCollectionField(PsiField field) {
        return findFlex(field, FQN_RELATIONAL_MAPPED_COLLECTION) != null;
    }

    /**
     * SD10：字段是否为**嵌套实体引用 / 实体集合字段**（无 JPA 关联注解的实体类型字段、
     * 或元素为实体的集合字段）——不落父表列（止血期不做反向列展开，否则 SELECT/INSERT
     * 出现不存在的列）。
     *
     * <p>实体判定（isLikelyMappedEntityClass）：@Entity / @Table（jakarta/javax/relational/短名）
     * 或含 @Id 字段；@Embeddable 值对象不算（仍走 @Embedded 展平）。已有 JPA 关联注解
     * （@ManyToOne/@OneToOne/@JoinColumn 等）的字段不在此列（走既有外键列语义）。
     */
    public static boolean isNestedEntityOrEntityCollectionField(PsiField field) {
        try {
            if (findFlex(field, FQN_MANY_TO_ONE) != null || findFlex(field, FQN_ONE_TO_ONE) != null
                    || findFlex(field, FQN_ONE_TO_MANY) != null || findFlex(field, FQN_MANY_TO_MANY) != null
                    || findFlex(field, FQN_ELEMENT_COLLECTION) != null
                    || hasJoinColumnsAnnotation(field) || isEmbeddedField(field)) {
                return false;
            }
            if (!(field.getType() instanceof PsiClassType type)) return false;
            String typeText;
            try {
                typeText = field.getType().getCanonicalText();
            } catch (Throwable t) {
                typeText = "";
            }
            boolean isCollection = false;
            for (String prefix : COLLECTION_TYPE_PREFIXES) {
                if (typeText.startsWith(prefix)) {
                    isCollection = true;
                    break;
                }
            }
            PsiClassType elementType;
            if (isCollection && typeText.startsWith("java.util.Map")) {
                PsiType[] parameters = type.getParameters();
                elementType = parameters.length > 0 && parameters[parameters.length - 1] instanceof PsiClassType ct
                        ? ct : null;
            } else if (isCollection) {
                PsiType[] parameters = type.getParameters();
                elementType = parameters.length > 0 && parameters[0] instanceof PsiClassType ct ? ct : null;
            } else {
                elementType = type;
            }
            if (elementType == null) return false;
            PsiClass elementClass = elementType.resolve();
            if (elementClass == null) return false;
            return isLikelyMappedEntityClass(elementClass);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 集合类型前缀（canonicalText 起始匹配；Map 单列取末位泛型参数）。 */
    private static final List<String> COLLECTION_TYPE_PREFIXES = List.of(
            "java.util.List<", "java.util.Set<", "java.util.Map<", "java.util.Collection<",
            "java.util.SortedSet<", "java.util.LinkedHashSet<", "java.util.ArrayList<");

    /**
     * {@code @Entity(name=...)} 实体别名（T-HqlInhCols）：jakarta/javax 双包 + 短名三轨（findFlex）。
     * HQL/JPQL {@code FROM <实体名>} 可用该别名而非类名；未指定或读不到返回 null。
     */
    public static String entityNameOf(PsiClass clazz) {
        try {
            PsiAnnotation entity = findFlex(clazz, "jakarta.persistence.Entity");
            return entity != null ? attrString(entity, "name") : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 类是否疑似 ORM 映射实体（SD10 嵌套实体跳过判定的保守守卫）：
     * @Entity / @Table（jakarta/javax/relational 全限定名或短名）注解，或类内任一字段标注 @Id
     * （jakarta/javax/spring-data）。@Embeddable/@MappedSuperclass 值对象不算。
     */
    public static boolean isLikelyMappedEntityClass(PsiClass clazz) {
        try {
            if (clazz.getModifierList() != null) {
                for (PsiAnnotation annotation : clazz.getModifierList().getAnnotations()) {
                    String qName = annotation.getQualifiedName();
                    if (qName == null) continue;
                    if (MAPPED_ENTITY_CLASS_ANNOTATIONS.contains(qName)) return true;
                    int dot = qName.lastIndexOf('.');
                    String simple = dot >= 0 ? qName.substring(dot + 1) : qName;
                    if (simple.equals("Entity") || simple.equals("Table")) return true;
                }
            }
            for (PsiField field : clazz.getAllFields()) {
                if (findFlex(field, "jakarta.persistence.Id") != null || findFlex(field, FQN_SD_ID) != null) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final Set<String> MAPPED_ENTITY_CLASS_ANNOTATIONS = Set.of(
            "jakarta.persistence.Entity", "javax.persistence.Entity",
            "jakarta.persistence.Table", "javax.persistence.Table",
            FQN_RELATIONAL_TABLE, FQN_EASY_QUERY_TABLE, FQN_XBATIS_TABLE);

    /** @Convert 标记（T-J18，仅标记不改 SQL 形态）。 */
    public static boolean hasConvertAnnotation(PsiField field) {
        return findFlex(field, FQN_CONVERT) != null;
    }

    /** @MapsId 标记（T-J7，仅标记）。 */
    public static boolean hasMapsId(PsiField field) {
        return findFlex(field, FQN_MAPS_ID) != null;
    }

    /** Hibernate @NotFound(action = IGNORE) 标记（T-J7）；默认 action 为 EXCEPTION → false。 */
    public static boolean isNotFoundIgnore(PsiField field) {
        PsiAnnotation notFound = findFlex(field, FQN_HIBERNATE_NOT_FOUND);
        if (notFound == null) return false;
        return "IGNORE".equals(attrEnumText(notFound, "action"));
    }

    /** mappedBy 属性值（OneToOne/OneToMany/ManyToMany；null = 未声明反向侧）。 */
    public static String mappedByOf(PsiField field) {
        for (String fqn : Arrays.asList(FQN_ONE_TO_ONE, FQN_ONE_TO_MANY, FQN_MANY_TO_MANY)) {
            PsiAnnotation annotation = findFlex(field, fqn);
            if (annotation == null) continue;
            String mappedBy = attrString(annotation, "mappedBy");
            if (mappedBy != null) return mappedBy;
        }
        return null;
    }

    /**
     * 字段是否为嵌入对象字段（T-J18）：@Embedded / @EmbeddedId 标注，
     * 或字段类型类标注 @Embeddable（JPA 默认嵌入语义）。
     */
    public static boolean isEmbeddedField(PsiField field) {
        try {
            if (findFlex(field, FQN_EMBEDDED) != null || findFlex(field, FQN_EMBEDDED_ID) != null) {
                return true;
            }
            PsiClass typeClass = field.getType() instanceof PsiClassType ct ? ct.resolve() : null;
            if (typeClass == null) return false;
            return findFlex(typeClass, FQN_EMBEDDABLE) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** @EmbeddedId 标注（T-J18 复合主键）。 */
    public static boolean hasEmbeddedIdAnnotation(PsiField field) {
        return findFlex(field, FQN_EMBEDDED_ID) != null;
    }

    // ------------------------------------------------------------------
    // Spring Data relational @Embedded（T-Embedded D2）
    // ------------------------------------------------------------------

    /**
     * 字段是否标注 Spring Data JDBC 嵌入注解（T-Embedded D2）：裸 @Embedded、捷径
     * {@code @Embedded.Nullable} / {@code @Embedded.Empty}。捷径是组合注解——PSI 短名是末段
     * （Nullable/Empty），{@link #findFlex} 按短名 "Embedded" 命不中，须按注解类型全名/文本匹配。
     *
     * <p>判定必须确证注解<b>属于 SD</b>：{@link #findFlex} 的短名兜底轨会把 jakarta @Embedded 一起命中，
     * 不能直接当 SD 证据。注解类型可解析时按全名判定；不可解析（SD jar 不在 classpath）时按文本
     * 兜底——此时项目里不可能真用 SD，裸 @Embedded 返回 false 交给 jakarta 轨（无害），
     * 捷径 {@code @Embedded.} 点号形态 jakarta 不存在、按文本判定不误伤。
     */
    public static boolean isSpringDataEmbeddedField(PsiField field) {
        try {
            for (String simpleName : List.of("Embedded", "Nullable", "Empty")) {
                for (PsiAnnotation annotation : directAnnotations(field, simpleName)) {
                    if (isSpringDataEmbeddedAnnotation(annotation)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * SD {@code @Embedded(prefix = "...")} 的列名前缀（T-Embedded D2）：官方语义是拼在嵌入对象
     * <b>每一列</b>之前（同一值对象多次嵌入的防撞车手段）；无注解/未指定 prefix/捷径注解
     * （无 prefix 属性）返回空串。
     */
    public static String springDataEmbeddedPrefixOf(PsiField field) {
        try {
            for (PsiAnnotation annotation : directAnnotations(field, "Embedded")) {
                if (isSpringDataEmbeddedAnnotation(annotation)) {
                    String prefix = attrString(annotation, "prefix");
                    return prefix != null ? prefix : "";
                }
            }
            return "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isSpringDataEmbeddedAnnotation(PsiAnnotation annotation) {
        try {
            // 轨 1：注解类型可解析（真实项目有 SD jar）→ 按全名判定
            String qualified = annotation.getQualifiedName();
            if (qualified != null &&
                    (qualified.equals(FQN_RELATIONAL_EMBEDDED)
                            || qualified.startsWith(FQN_RELATIONAL_EMBEDDED + "."))) {
                return true;
            }
            // 轨 2：不可解析（jar 缺失/测试夹具）→ 按注解文本兜底。全限定写法（含捷径 `.Nullable`/
            // `.Empty` 与带属性 `(...)` 两种后缀形态）逐字匹配；短名捷径 `@Embedded.` 点号形态 jakarta
            // 不存在、不误伤；裸 @Embedded 无从区分且此时 SD 必不在 classpath，不认（交给 jakarta 轨）
            String text = annotation.getText();
            if (text == null) return false;
            return text.startsWith("@" + FQN_RELATIONAL_EMBEDDED + ".")
                    || text.startsWith("@" + FQN_RELATIONAL_EMBEDDED + "(")
                    || text.startsWith("@Embedded.");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** @Column(insertable)，未指定时规范默认 true。 */
    public static boolean columnInsertableOf(PsiField field) {
        PsiAnnotation column = findFlex(field, FQN_COLUMN);
        Boolean value = column != null ? attrBoolean(column, "insertable") : null;
        return value != null ? value : true;
    }

    /** @Column(updatable)，未指定时规范默认 true。 */
    public static boolean columnUpdatableOf(PsiField field) {
        PsiAnnotation column = findFlex(field, FQN_COLUMN);
        Boolean value = column != null ? attrBoolean(column, "updatable") : null;
        return value != null ? value : true;
    }

    /**
     * @AttributeOverride(s) 覆写清单（T-J18）：嵌入字段名 → 覆写列名。
     * 兼容 repeatable 直接重复标注与 @AttributeOverrides 容器两种形态。
     */
    public static Map<String, String> attributeOverrides(PsiModifierListOwner owner) {
        try {
            Map<String, String> result = new LinkedHashMap<>();
            if (owner == null || owner.getModifierList() == null) return result;
            for (PsiAnnotation annotation : owner.getModifierList().getAnnotations()) {
                String shortName = annotation.getNameReferenceElement() != null
                        ? annotation.getNameReferenceElement().getReferenceName() : null;
                if ("AttributeOverride".equals(shortName)) {
                    putAttributeOverride(annotation, result);
                } else if ("AttributeOverrides".equals(shortName)) {
                    for (PsiAnnotation inner : attrAnnotationArray(annotation, "value")) {
                        putAttributeOverride(inner, result);
                    }
                }
            }
            return result;
        } catch (Throwable ignored) {
            return new LinkedHashMap<>();
        }
    }

    private static void putAttributeOverride(PsiAnnotation annotation, Map<String, String> into) {
        String attributeName = attrString(annotation, "name");
        if (attributeName == null) return;
        PsiAnnotation column = attrAnnotation(annotation, "column");
        if (column == null) return;
        String columnName = attrString(column, "name");
        if (columnName != null) into.put(attributeName, columnName);
    }

    /**
     * 嵌入对象展平产物（T-J18）。
     *
     * <p>内部字段访问器说明见各参数文档。</p>
     */
    public static final class EmbeddedColumnSpec {
        private final PsiField field;
        private final String fieldName;
        private final String columnName;
        private final String fieldType;
        private final boolean insertable;
        private final boolean updatable;
        private final String embeddedPath;
        private final boolean fromAttributeOverride;

        /**
         * @param field      嵌入对象内部字段（注释提取用）
         * @param fieldName  内部字段名（ColumnInfo.fieldName；非完整路径——嵌套路径见 {@link #getEmbeddedPath()}）
         * @param columnName 展平列名（@AttributeOverride &gt; 内部字段 @Column &gt; 兜底命名）
         * @param fieldType  内部字段类型文本（参数回填引号推断用）
         * @param insertable 内部字段 @Column(insertable)
         * @param updatable  内部字段 @Column(updatable)
         * @param embeddedPath 完整嵌入段驼峰路径（实体根起算，各级嵌入字段名首字母大写，如
         *   {@code address.zipcode.zip} → {@code addressZipcodeZip}）——两级以上嵌入链的派生查询路径
         *   （{@code findByAddressZipcodeZip}）据此命中本列（G2 修复点）
         * @param fromAttributeOverride 列名是否来自 @AttributeOverride——JPA 轨覆写名即最终列名，
         *   SD @Embedded(prefix) 不再叠加（同口径声明防撞车场景，G1 修复点）
         */
        EmbeddedColumnSpec(PsiField field, String fieldName, String columnName, String fieldType,
                           boolean insertable, boolean updatable, String embeddedPath,
                           boolean fromAttributeOverride) {
            this.field = field;
            this.fieldName = fieldName;
            this.columnName = columnName;
            this.fieldType = fieldType;
            this.insertable = insertable;
            this.updatable = updatable;
            this.embeddedPath = embeddedPath;
            this.fromAttributeOverride = fromAttributeOverride;
        }

        public PsiField getField() {
            return field;
        }

        public String getFieldName() {
            return fieldName;
        }

        public String getColumnName() {
            return columnName;
        }

        public String getFieldType() {
            return fieldType;
        }

        public boolean isInsertable() {
            return insertable;
        }

        public boolean isUpdatable() {
            return updatable;
        }

        /** 完整嵌入段驼峰路径（实体根起算，各级嵌入字段名首字母大写；G2 修复段落盘）。 */
        public String getEmbeddedPath() {
            return embeddedPath;
        }

        /** 列名是否来自 @AttributeOverride（JPA 轨覆写名即最终列名，SD prefix 不叠加；G1 修复点）。 */
        public boolean isFromAttributeOverride() {
            return fromAttributeOverride;
        }
    }

    /**
     * 展平嵌入对象字段为列清单（T-J18）：逐字段解析 @AttributeOverride 覆写 →
     * 内部字段 @Column → 兜底命名；嵌套 @Embedded 递归展平。
     * 嵌入类型不可解析时返回空列表（调用方降级为单列形态）。
     *
     * <p>T-Embedded D1（点路径覆写）：{@code overrides} 的键支持三种形态，查找按点路径优先、裸名兜底——
     * <ul>
     *   <li>实体根相对点路径（类级 @AttributeOverride，如 {@code address.zipcode.zip}——覆写嵌套嵌入必须用
     *       点路径，jakarta persistence javadoc「a dot (".") notation form must be used」）；</li>
     *   <li>嵌入对象相对点路径（字段级 @AttributeOverride，规范 Example 2 的 {@code zipcode.zip}）；</li>
     *   <li>裸属性名（既有形态，T-J18 用例不回归）。</li>
     * </ul></p>
     *
     * @param rootKeyPrefix 实体根起算的键前缀（顶层嵌入字段名 + {@code .}，如 {@code address.}）——类级覆写键
     *   相对实体根，而展平内部按嵌入对象相对路径查找，靠它把两个键空间对上
     */
    public static List<EmbeddedColumnSpec> flattenEmbeddedColumns(PsiField field, Map<String, String> overrides) {
        return flattenEmbeddedColumns(field, overrides, EmbeddedColumnNaming.NONE);
    }

    /**
     * 展平嵌入对象字段为列清单（T-J18）：逐字段解析 @AttributeOverride 覆写 →
     * 内部字段 @Column → 兜底命名；嵌套 @Embedded 递归展平。
     * 嵌入类型不可解析时返回空列表（调用方降级为单列形态）。
     *
     * <p>T-Embedded D1（点路径覆写）：{@code overrides} 的键支持三种形态，查找按点路径优先、裸名兜底——
     * <ul>
     *   <li>实体根相对点路径（类级 @AttributeOverride，如 {@code address.zipcode.zip}——覆写嵌套嵌入必须用
     *       点路径，jakarta persistence javadoc「a dot (".") notation form must be used」）；</li>
     *   <li>嵌入对象相对点路径（字段级 @AttributeOverride，规范 Example 2 的 {@code zipcode.zip}）；</li>
     *   <li>裸属性名（既有形态，T-J18 用例不回归）。</li>
     * </ul></p>
     *
     * <p>T-EmbeddedColPrefix：兜底命名由 {@code naming} 决定——{@link EmbeddedColumnNaming#NONE} 用内层字段名本身
     * （JPA 默认），{@link EmbeddedColumnNaming#COMPONENT_PATH} 把嵌入段路径并进列名（Hibernate
     * component-path）。两档<b>只影响既无 @AttributeOverride 也无内层 @Column(name) 的兜底档</b>。</p>
     *
     * @param rootKeyPrefix 实体根起算的键前缀（顶层嵌入字段名 + {@code .}，如 {@code address.}）——类级覆写键
     *   相对实体根，而展平内部按嵌入对象相对路径查找，靠它把两个键空间对上
     * @param naming 嵌入列命名约定（默认无前缀，保持既有行为）
     */
    public static List<EmbeddedColumnSpec> flattenEmbeddedColumns(
            PsiField field, Map<String, String> overrides, EmbeddedColumnNaming naming) {
        PsiClass clazz;
        try {
            clazz = field.getType() instanceof PsiClassType ct ? ct.resolve() : null;
        } catch (Throwable ignored) {
            return List.of();
        }
        if (clazz == null) return List.of();
        String rootFieldName;
        try {
            rootFieldName = field.getName();
        } catch (Throwable ignored) {
            rootFieldName = null;
        }
        if (rootFieldName == null) rootFieldName = "";
        String rootKeyPrefix = rootFieldName.isEmpty() ? "" : rootFieldName + ".";
        // camelPathPrefix：实体根起的驼峰路径累积（顶层嵌入字段名起算），逐段拼给末端列
        // snakePathPrefix：同一条路径的「下划线连接」形态，仅 COMPONENT_PATH 档用于拼列名前缀
        return flattenEmbeddableClass(clazz, overrides, new LinkedHashSet<>(), rootKeyPrefix, "",
                rootFieldName, rootFieldName.isEmpty() ? "" : rootFieldName + "_", naming);
    }

    private static List<EmbeddedColumnSpec> flattenEmbeddableClass(
            PsiClass clazz, Map<String, String> overrides, Set<PsiClass> seen,
            String rootKeyPrefix, String pathPrefix, String camelPathPrefix, String snakePathPrefix,
            EmbeddedColumnNaming naming) {
        if (!seen.add(clazz)) return List.of();
        List<EmbeddedColumnSpec> result = new ArrayList<>();
        // D254：嵌入对象同样走实体列语义收集（同名合并取子类；static/transient 已由收集器排除）
        for (PsiField field : EntityFieldCollector.collectColumns(clazz)) {
            String fieldName;
            try {
                fieldName = field.getName();
            } catch (Throwable ignored) {
                continue;
            }
            if (fieldName == null) continue;
            // 第二道防线：serialVersionUID 归一化名字（规范形态为 static final，已被收集器过滤；
            // 保留判定以挡住非规范写法）
            if (fieldName.toLowerCase().replace("_", "").equals("serialversionuid")) continue;
            if (findFlex(field, FQN_TRANSIENT) != null) continue;
            // 嵌套 @Embedded / @Embeddable：递归展平（D1）。覆写键空间双层并存——类级键（实体根相对）
            // 随 overrides 逐层传递、pathPrefix 累积；该字段自身 @AttributeOverride(s) 的相对键合并进
            // overrides（右侧覆盖左侧，字段级相对语义与改前「只传该字段覆写」一致）
            if (isEmbeddedField(field)) {
                PsiClass nestedClass;
                try {
                    nestedClass = field.getType() instanceof PsiClassType ct ? ct.resolve() : null;
                } catch (Throwable ignored) {
                    nestedClass = null;
                }
                if (nestedClass == null) continue;
                Map<String, String> merged = new LinkedHashMap<>(overrides);
                merged.putAll(attributeOverrides(field));
                result.addAll(flattenEmbeddableClass(
                        nestedClass, merged, seen, rootKeyPrefix, pathPrefix + fieldName + ".",
                        camelPathPrefix + upperFirst(fieldName), snakePathPrefix + fieldName + "_", naming));
                continue;
            }
            PsiAnnotation columnAnnotation = findFlex(field, FQN_COLUMN);
            // 覆写查找三级兜底（实体根点路径 → 嵌入对象相对点路径 → 裸名）；命中即 JPA 轨最终列名
            String override = overrides.get(rootKeyPrefix + pathPrefix + fieldName);
            if (override == null) override = overrides.get(pathPrefix + fieldName);
            if (override == null) override = overrides.get(fieldName);
            String columnName = override;
            if (columnName == null && columnAnnotation != null) columnName = attrString(columnAnnotation, "name");
            if (columnName == null) columnName = embeddedFallbackColumnName(snakePathPrefix, fieldName, naming);
            String fieldType;
            try {
                fieldType = field.getType().getCanonicalText();
            } catch (Throwable ignored) {
                fieldType = null;
            }
            Boolean insertable = columnAnnotation != null ? attrBoolean(columnAnnotation, "insertable") : null;
            Boolean updatable = columnAnnotation != null ? attrBoolean(columnAnnotation, "updatable") : null;
            result.add(new EmbeddedColumnSpec(field, fieldName, columnName, fieldType,
                    insertable != null ? insertable : true, updatable != null ? updatable : true,
                    camelPathPrefix + upperFirst(fieldName), override != null));
        }
        return result;
    }

    /**
     * 嵌入列的<b>兜底</b>列名（既无 @AttributeOverride、也无内层 @Column(name) 时，T-EmbeddedColPrefix）。
     *
     * <ul>
     *   <li>{@link EmbeddedColumnNaming#NONE}（JPA 默认）：内层字段名本身（{@code baseQty} → {@code base_qty}）；</li>
     *   <li>{@link EmbeddedColumnNaming#COMPONENT_PATH}：把嵌入段路径并进列名
     *       （{@code qty} + {@code baseQty} → {@code qty_base_qty}；多级嵌入逐段累积 {@code a} + {@code b} +
     *       {@code c} → {@code a_b_c}）。</li>
     * </ul>
     *
     * <p>COMPONENT_PATH 的形态与 Hibernate 一致：hibernate-core 5.6.4
     * {@code ImplicitNamingStrategyComponentPathImpl.transformAttributePath} 用<b>原始属性名</b>按 {@code _}
     * 递归连接父路径，下划线化由 PhysicalNamingStrategy 完成；此处对连接后的整串做一次 snake 化，
     * 结果等价（{@code toSnakeCase("qty_baseQty") = qty_base_qty}）。</p>
     *
     * @param snakePathPrefix 自实体根起的嵌入段路径（原始字段名以 {@code _} 连接，含尾随 {@code _}；顶层为空串时无前缀）
     */
    private static String embeddedFallbackColumnName(String snakePathPrefix, String fieldName,
                                                     EmbeddedColumnNaming naming) {
        if (naming == EmbeddedColumnNaming.COMPONENT_PATH) {
            return NamingStrategy.INSTANCE.toSnakeCase(snakePathPrefix + fieldName);
        }
        return NamingStrategy.INSTANCE.toSnakeCase(fieldName);
    }

    /** Kotlin {@code replaceFirstChar { it.uppercaseChar() }} 等价（空串安全）。 */
    private static String upperFirst(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------
    // 关联外键列（T-J7）
    // ------------------------------------------------------------------

    /**
     * @ToOne 字段的外键列清单（T-J7）：
     * <ul>
     *   <li>@JoinColumns 容器 → 复合外键多列；</li>
     *   <li>repeatable @JoinColumn 直接重复标注 ≥ 2 个 → 复合外键；</li>
     *   <li>单个 @JoinColumn（或无）→ 单列（无注解时推断 `字段名_id`，与既有列名语义一致）。</li>
     * </ul>
     */
    public static List<JoinColumnRef> readToOneJoinColumns(PsiField field, String fieldName) {
        try {
            PsiAnnotation container = findFlex(field, FQN_JOIN_COLUMNS);
            if (container != null) {
                List<PsiAnnotation> columns = attrAnnotationArray(container, "value");
                if (!columns.isEmpty()) {
                    List<JoinColumnRef> result = new ArrayList<>();
                    for (PsiAnnotation column : columns) {
                        result.add(toJoinColumnRef(column, fieldName));
                    }
                    return result;
                }
            }
            List<PsiAnnotation> direct = directAnnotations(field, "JoinColumn");
            if (direct.size() > 1) {
                List<JoinColumnRef> result = new ArrayList<>();
                for (PsiAnnotation column : direct) {
                    result.add(toJoinColumnRef(column, fieldName));
                }
                return result;
            }
            List<JoinColumnRef> result = new ArrayList<>();
            result.add(toJoinColumnRef(direct.isEmpty() ? null : direct.get(0), fieldName));
            return result;
        } catch (Throwable ignored) {
            List<JoinColumnRef> fallback = new ArrayList<>();
            fallback.add(new JoinColumnRef(NamingStrategy.INSTANCE.toSnakeCase(fieldName) + "_id"));
            return fallback;
        }
    }

    /** 是否存在复合外键声明（@JoinColumns 容器或多个 @JoinColumn）——多列外键不落单列。 */
    public static boolean hasCompositeJoinColumns(PsiField field) {
        if (findFlex(field, FQN_JOIN_COLUMNS) != null) return true;
        return directAnnotations(field, "JoinColumn").size() > 1;
    }

    private static JoinColumnRef toJoinColumnRef(PsiAnnotation annotation, String fieldName) {
        if (annotation == null) {
            return new JoinColumnRef(NamingStrategy.INSTANCE.toSnakeCase(fieldName) + "_id");
        }
        String name = attrString(annotation, "name");
        Boolean insertable = attrBoolean(annotation, "insertable");
        Boolean updatable = attrBoolean(annotation, "updatable");
        return new JoinColumnRef(
                name != null ? name : NamingStrategy.INSTANCE.toSnakeCase(fieldName) + "_id",
                attrString(annotation, "referencedColumnName"),
                insertable != null ? insertable : true,
                updatable != null ? updatable : true);
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
    public static CollectionAssociationInfo readCollectionAssociation(
            PsiField field, java.util.function.Function<PsiClass, String> targetTableNameOf, String entityTableName) {
        try {
            String fieldName = field.getName();
            if (fieldName == null) return null;
            CollectionKind kind;
            if (findFlex(field, FQN_ELEMENT_COLLECTION) != null) {
                kind = CollectionKind.ELEMENT_COLLECTION;
            } else if (findFlex(field, FQN_ONE_TO_MANY) != null) {
                kind = CollectionKind.ONE_TO_MANY;
            } else if (findFlex(field, FQN_MANY_TO_MANY) != null) {
                kind = CollectionKind.MANY_TO_MANY;
            } else {
                return null;
            }
            String mappedBy = mappedByOf(field);
            PsiAnnotation orderByAnnotation = findFlex(field, FQN_ORDER_BY);
            String orderBy = orderByAnnotation != null ? attrString(orderByAnnotation, "value") : null;
            PsiAnnotation orderColumnAnnotation = findFlex(field, FQN_ORDER_COLUMN);
            String orderColumn = orderColumnAnnotation != null ? attrString(orderColumnAnnotation, "name") : null;

            // @ElementCollection：集合表（@CollectionTable）为目标表
            if (kind == CollectionKind.ELEMENT_COLLECTION) {
                PsiAnnotation collectionTable = findFlex(field, FQN_COLLECTION_TABLE);
                String tableName = collectionTable != null ? attrString(collectionTable, "name") : null;
                if (tableName == null) {
                    tableName = entityTableName + "_" + NamingStrategy.INSTANCE.toSnakeCase(fieldName);
                }
                List<JoinColumnRef> joinColumns = new ArrayList<>();
                if (collectionTable != null) {
                    for (PsiAnnotation jc : attrAnnotationArray(collectionTable, "joinColumns")) {
                        joinColumns.add(toJoinColumnRef(jc, fieldName));
                    }
                }
                JoinTableInfo joinTable = null;
                if (collectionTable != null) {
                    joinTable = new JoinTableInfo(tableName,
                            attrString(collectionTable, "schema"),
                            attrString(collectionTable, "catalog"),
                            joinColumns, List.of());
                }
                return new CollectionAssociationInfo(fieldName, kind, tableName, mappedBy,
                        joinTable, joinColumns, orderBy, orderColumn);
            }

            // @OneToMany / @ManyToMany：元素实体表；元素类型不可解析时按泛型文本推断（优雅降级）
            String targetTable = elementEntityTableName(field, targetTableNameOf);
            if (targetTable == null) return null;
            // @JoinTable 中间表（ManyToMany 常规、OneToMany 少见合法形态）
            JoinTableInfo joinTable = null;
            PsiAnnotation jt = findFlex(field, FQN_JOIN_TABLE);
            if (jt != null) {
                List<JoinColumnRef> joinColumns = new ArrayList<>();
                for (PsiAnnotation jc : attrAnnotationArray(jt, "joinColumns")) {
                    joinColumns.add(toJoinColumnRef(jc, fieldName));
                }
                List<JoinColumnRef> inverseJoinColumns = new ArrayList<>();
                for (PsiAnnotation jc : attrAnnotationArray(jt, "inverseJoinColumns")) {
                    inverseJoinColumns.add(toJoinColumnRef(jc, fieldName));
                }
                joinTable = new JoinTableInfo(attrString(jt, "name"), attrString(jt, "schema"),
                        attrString(jt, "catalog"), joinColumns, inverseJoinColumns);
            }
            // 单向 @OneToMany + @JoinColumn：外键列在目标表（独立 UPDATE 子表语义）
            List<JoinColumnRef> joinColumns;
            if (kind == CollectionKind.ONE_TO_MANY && mappedBy == null && hasJoinColumnsAnnotation(field)) {
                joinColumns = readToOneJoinColumns(field, fieldName);
            } else {
                joinColumns = List.of();
            }
            return new CollectionAssociationInfo(fieldName, kind, targetTable, mappedBy,
                    joinTable, joinColumns, orderBy, orderColumn);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 字段是否存在任何 @JoinColumn 声明（容器或直接标注）。 */
    private static boolean hasJoinColumnsAnnotation(PsiField field) {
        if (findFlex(field, FQN_JOIN_COLUMNS) != null) return true;
        return !directAnnotations(field, "JoinColumn").isEmpty();
    }

    /** 集合字段元素实体表名：元素类可解析走回调，否则按泛型文本推断蛇形表名。 */
    private static String elementEntityTableName(
            PsiField field, java.util.function.Function<PsiClass, String> targetTableNameOf) {
        PsiClassType type;
        try {
            type = field.getType() instanceof PsiClassType ct ? ct : null;
        } catch (Throwable ignored) {
            return null;
        }
        if (type == null) return null;
        PsiType[] parameters = type.getParameters();
        PsiClassType elementType = parameters.length > 0 && parameters[0] instanceof PsiClassType ct ? ct : null;
        PsiClass elementClass = elementType != null ? elementType.resolve() : null;
        if (elementClass != null) return targetTableNameOf.apply(elementClass);
        String text;
        try {
            text = field.getType().getCanonicalText();
        } catch (Throwable ignored) {
            return null;
        }
        if (text == null) return null;
        int lt = text.indexOf('<');
        int gt = text.indexOf('>');
        String elementText = "";
        if (lt >= 0 && gt > lt) {
            String inner = text.substring(lt + 1, gt);
            int dot = inner.lastIndexOf('.');
            elementText = dot >= 0 ? inner.substring(dot + 1) : inner;
        }
        return !elementText.isBlank() ? NamingStrategy.INSTANCE.toSnakeCase(elementText) : null;
    }
}
