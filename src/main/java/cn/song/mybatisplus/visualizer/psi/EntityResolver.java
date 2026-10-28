package cn.song.mybatisplus.visualizer.psi;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings;
import cn.song.mybatisplus.visualizer.model.AssociationInfo;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRole;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader;
import cn.song.mybatisplus.visualizer.util.NamingStrategy;
import cn.song.mybatisplus.visualizer.util.TableConfigSignature;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiModifierList;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 实体类解析器（F-13）：从实体 {@link PsiClass} 解析出 {@link TableInfo}。
 *
 * <p>规则（对齐 MyBatis-Plus）：</p>
 * <ul>
 *   <li>表名：优先 {@code @TableName("xxx")} 注解，否则按命名策略由类名推断（DeviceInfoEntity → device_info）</li>
 *   <li>列名：优先 {@code @TableField("xxx")} 注解，否则按命名策略由字段名推断</li>
 *   <li>主键：{@code @TableId} 标记；{@code @TableField(exist = false)} 的字段（非表字段）跳过</li>
 *   <li>T-M16.2-02：字段递归<b>父类</b>收集（父类字段在前，组内对齐 MP 声明顺序语义）——继承实体的
 *       SELECT 全列展开与 @TableLogic/@TableId/@Version（T-M22-02）识别同样生效</li>
 *   <li>T-M23.3：字段按<b>角色分组</b>稳定排序——@TableId(PRIMARY) 置顶、业务字段(BUSINESS) 居中、
 *       fill 自动填充(AUDIT) 与 @TableLogic/@Version(SYSTEM) 沉底，组内保持声明顺序</li>
 *   <li>T-M16.2-03：表前缀/后缀配置（<b>推断语义</b>——仅作用于推断表名；T-M19.4 用户反馈收窄，
 *       @TableName 显式表名即最终表名、优先级最高、豁免修饰）</li>
 *   <li>T-M19.4：表名推断策略（无注解时 SNAKE 驼峰转下划线 / CAMEL 纯保留驼峰）、忽略字段名、
 *       移除表名前缀/后缀（{@code ;} 分隔首匹配）；添加表前缀/后缀为<b>单值</b></li>
 *   <li>T-EntityCache：解析结果按「PsiFile + classQualifiedName + configSignature」三维缓存，
 *       文件修改戳（modificationStamp）+ 配置签名双维度失效，避免大项目重复 PSI 遍历</li>
 *   <li>O-58：返回边界接 {@link ColumnNameCalibrator}——数据源可用时用真实列集合纠正猜测出的列名
 *       （{@code orderID} → {@code order_i_d} 而库里是 {@code order_id}），不可用/查不了/开关关闭时逐字段原样返回</li>
 * </ul>
 */
public class EntityResolver {

    /**
     * 解析实体类为表信息（带缓存）。
     *
     * <p>缓存键：PsiFile + classQualifiedName + configSignature（三维）。
     * 失效机制：文件修改（modificationStamp 变化）→ 缓存自然失效；
     * 配置签名变化（tablePrefix/tableSuffix/tableNameInference 等设置变更）→ 缓存失效。</p>
     *
     * <p>O-58（列名数据源校正）：缓存<b>继续只存未校正的 PSI 产物</b>，校正在返回前作为一步廉价后处理
     * （{@link ColumnNameCalibrator#calibrate(Project, TableInfo)}，对 DB 列集合做几次 map 查找，无遍历）执行。
     * 这样 DDL 变更的失效面只有数据源 schema 缓存一处（已登记 MetadataInvalidator），不必给实体缓存键扩 DB 签名。
     * 代价是校正生效时返回的是新对象而非缓存里那一个——已核实全仓无消费方依赖 TableInfo 实例同一性。</p>
     *
     * @param entityClass 实体 PsiClass（如 DeviceInfoEntity）
     * @return 表信息；类名缺失等异常情况返回带推断表名、空列的表信息（保证不抛异常）
     */
    public TableInfo resolve(PsiClass entityClass) {
        String className = entityClass.getName();
        if (className == null) return new TableInfo("", "", List.of());
        Project project = entityClass.getProject();
        PsiFile file = entityClass.getContainingFile();
        if (file == null) return ColumnNameCalibrator.calibrate(project, resolveInternal(entityClass));
        String qName = entityClass.getQualifiedName() != null ? entityClass.getQualifiedName() : className;
        String configSig = TableConfigSignature.projectSignature(project);

        // T-Perf-18：缓存键不持 PsiFile 强引用（VirtualFile.url 字符串标识——PsiFile 随键
        // 永久驻留是长会话内存泄漏源；URL 相同的 PSI 重建由 modificationStamp 维度失效）
        EntityCacheKey key = new EntityCacheKey(
                file.getVirtualFile() != null ? file.getVirtualFile().getUrl() : null,
                System.identityHashCode(file), qName, configSig);
        long stamp = file.getModificationStamp();
        Entry entry = cache.get(key);
        if (entry != null && entry.stamp == stamp) {
            return ColumnNameCalibrator.calibrate(project, entry.value);
        }

        TableInfo value = resolveInternal(entityClass);
        // 券02（T-PerfSweep27-02）：写入前容量守卫——超限整体清空再写（上限语义见 maxCacheEntries）。
        if (cache.size() >= maxCacheEntries) cache.clear();
        cache.put(key, new Entry(stamp, value));
        return ColumnNameCalibrator.calibrate(project, value);
    }

    /**
     * 内部解析（完整 PSI 遍历，缓存未命中时调用）。
     *
     * <p>原有 resolve 逻辑整体迁移至此——缓存层透明，调用方无感。</p>
     */
    private TableInfo resolveInternal(PsiClass entityClass) {
        String className = entityClass.getName();
        if (className == null) return new TableInfo("", "", List.of());
        // T-M19.4（用户反馈：@TableName 应为最高优先级）：显式 @TableName 即最终表名——
        // 豁免推断策略与修饰链（添加/移除表名前缀后缀仅作用于推断表名；
        // T-M16.2-03 的「全局语义」按用户反馈收窄为推断语义）
        String annotated = annotationValue(entityClass, TABLE_NAME);
        String tableName = annotated != null
                ? annotated
                : decorateTableName(inferTableName(className, entityClass.getProject()), entityClass.getProject());

        // T-M16.2-02：递归父类收集字段（父类字段在前；@TableLogic/@TableId/@TableField 标注随字段本身生效）
        List<PsiField> fields = collectFieldsIncludingSuper(entityClass, new HashSet<>());
        // T-M19.4-03-②：忽略字段名配置（`;` 分隔，按 Java 字段名匹配）——命中字段不进
        // SELECT 全列展开/参数对照/补全列集合（resolveColumn 返回 null 跳过；@TableField(exist=false) 注解优先）
        // T-M23.3：按角色分组稳定排序（PRIMARY 置顶 → BUSINESS 居中 → AUDIT/SYSTEM 沉底，
        // 组内保持声明顺序）——三处消费点（SELECT 全列/批量面板/补全）共用 columns 自动统一生效
        // T-Embedded D3：类级 @AttributeOverride（混合注解实体的嵌入覆写，与 JPA 轨同语义）；
        // 嵌入字段可能展开为多列，列收集改 flatMap
        Map<String, String> classOverrides = JpaMetadataReader.attributeOverrides(entityClass);
        List<ColumnInfo> columns = new ArrayList<>();
        for (PsiField field : fields) {
            columns.addAll(resolveColumns(field, ignoredFieldNames(entityClass.getProject()), classOverrides));
        }
        columns.sort(java.util.Comparator.comparing(ColumnInfo::getRole));
        // T-M15-07：逻辑删除列（@TableLogic 标注字段；T-M16.2-02 修复：父类字段同样识别）
        PsiField logicField = firstFieldWithAnnotation(fields, TABLE_LOGIC);
        String logicDeleteColumn = logicField != null ? columnOfField(logicField) : null;
        // T-M22-02：乐观锁版本列（@Version 标注字段——MP OptimisticLockerInnerInterceptor 语义：
        // UPDATE 自动追加 `version = version + 1` + `AND version = ?`；列名 = @TableField 值或 snake_case；
        // 与 @TableLogic 同模式：父类字段同样识别）
        PsiField versionField = firstFieldWithAnnotation(fields, VERSION);
        String versionColumn = versionField != null ? columnOfField(versionField) : null;
        // T-M16-05：表注释（类级注解 > javadoc > 行注释；schema 预留最高优先级，当前返回 null）
        String comment = resolveComment(entityClass);
        // T-Embedded D3：@EmbeddedId 展平列回填复合主键清单（isId 已在展平分支标记；
        // 普通 @TableId 单主键不进清单，与 JPA 轨口径一致）
        boolean hasEmbeddedIdField = false;
        for (PsiField field : fields) {
            if (JpaMetadataReader.hasEmbeddedIdAnnotation(field)) {
                hasEmbeddedIdField = true;
                break;
            }
        }
        List<String> embeddedIdColumns = new ArrayList<>();
        if (hasEmbeddedIdField) {
            for (ColumnInfo c : columns) {
                if (c.isId() && c.isEmbedded()) embeddedIdColumns.add(c.getColumnName());
            }
        }
        return new TableInfo(className, tableName, columns, logicDeleteColumn, versionColumn, comment,
                List.of(), null, null, null, null, null, List.of(), List.of(), List.of(), List.of(),
                List.of(), null, null, null, embeddedIdColumns, null);
    }

    /** 逻辑删除/版本列取值：@TableField 值优先，否则 snake_case（字段名）。 */
    private static String columnOfField(PsiField field) {
        String annotated = annotationValue(field, TABLE_FIELD);
        return annotated != null ? annotated : NamingStrategy.INSTANCE.toSnakeCase(field.getName());
    }

    private static PsiField firstFieldWithAnnotation(List<PsiField> fields, String qualifiedName) {
        for (PsiField field : fields) {
            if (hasAnnotation(field, qualifiedName)) return field;
        }
        return null;
    }

    /**
     * 递归收集类及其所有父类的字段（T-M16.2-02）：
     * <ul>
     *   <li><b>父类字段在前、子类字段在后</b>（对齐 MP 声明顺序语义）；</li>
     *   <li>仅沿 {@code superClass} 类继承链——接口字段不纳入（M16.2-R2），{@code Object} 无字段天然过滤；</li>
     *   <li>{@code seen} 集合防循环继承（异常 PSI 场景防御）。</li>
     * </ul>
     */
    private static List<PsiField> collectFieldsIncludingSuper(PsiClass clazz, Set<PsiClass> seen) {
        if (!seen.add(clazz)) return List.of();
        List<PsiField> result = new ArrayList<>();
        if (clazz.getSuperClass() != null) {
            result.addAll(collectFieldsIncludingSuper(clazz.getSuperClass(), seen));
        }
        result.addAll(Arrays.asList(clazz.getFields()));
        return result;
    }

    /**
     * 解析单个字段为列信息（T-Embedded D3 起可能展开为多列——嵌入对象展平）；
     * 非表字段（@TableField(exist=false) / T-M19.4 忽略字段名配置）返回空列表。
     */
    private static List<ColumnInfo> resolveColumns(PsiField field, Set<String> ignoredFields,
                                                   Map<String, String> classOverrides) {
        String fieldName = field.getName();
        PsiAnnotation tableFieldAnnotation = findAnnotation(field, TABLE_FIELD);
        // @TableField(exist = false)：非数据库字段，跳过（注解优先）
        if (Boolean.FALSE.equals(annotationAttributeBoolean(tableFieldAnnotation, "exist"))) {
            return List.of();
        }
        // T-M19.4-03-②：忽略字段名（`;` 分隔，Java 字段名匹配）——与 @TableField(exist=false) 并存
        // （注解优先）；命中即跳过：SELECT 全列展开、参数对照、补全列集合均不含该字段（M19.4-R3）
        if (ignoredFields.contains(fieldName)) {
            return List.of();
        }
        // T-M42（用户实证：SQL 含 serial_version_u_i_d 列时生成 User::getSerialVersionUID 编译失败）：
        // serialVersionUID（static final 序列化字段，无 getter/setter，MP 官方不映射为列）默认忽略——
        // 归一化匹配（小写+去下划线）覆盖全部形态；正向（SELECT 全列展开/批量面板/补全）与
        // 反向（columnToField）统一受益
        if (fieldName.toLowerCase(Locale.ROOT).replace("_", "").equals("serialversionuid")) {
            return List.of();
        }
        // T-Embedded D3（混合注解实体）：jakarta/javax @Embedded / @Embeddable / @EmbeddedId 值对象字段
        // 展平为宿主表列。MP 运行时本身不展平（该字段按平铺伪列生成 SQL、真实库中不存在），插件口径
        // =跟真实数据库（计划 §四 D3 决策 A，与 O-58 校正层「用真值纠正猜测」同哲学）；
        // @TableField 显式列名时 MP 语义优先，走下方单列路径不展平
        if (annotationValue(field, TABLE_FIELD) == null && JpaMetadataReader.isEmbeddedField(field)) {
            return resolveEmbeddedColumns(field, classOverrides);
        }
        String annotatedColumn = annotationValue(field, TABLE_FIELD);
        String columnName = annotatedColumn != null ? annotatedColumn : NamingStrategy.INSTANCE.toSnakeCase(fieldName);
        boolean isId = hasAnnotation(field, TABLE_ID);
        // T-M23-04：主键是否自增（@TableId(type = IdType.AUTO)）——取 type 属性文本判含 "AUTO"；
        // 注解类可解析且未显式写 type 时 findAttributeValue 返回默认值 IdType.NONE（不含 AUTO，恒勾选），
        // 与既有 fill 属性读取同一 API 形态
        boolean idTypeAuto = false;
        if (isId) {
            PsiAnnotation idAnnotation = findAnnotation(field, TABLE_ID);
            idTypeAuto = idAnnotation != null && idAnnotation.findAttributeValue("type") != null
                && idAnnotation.findAttributeValue("type").getText().contains("AUTO");
        }
        // M13：记录字段类型（参数回填引号推断用）；PSI 类型解析异常时兜底 null
        String fieldType;
        try {
            fieldType = field.getType().getCanonicalText();
        } catch (Throwable e) {
            fieldType = null;
        }
        // T-M15-08：自动填充标注（@TableField(fill = FieldFill.INSERT_UPDATE) 的 fill 属性文本，
        // 枚举引用非字面量，取 attributeValue 文本；先确认含 FieldFill 再取短名；
        // 批量 INSERT 模板据此排除 UPDATE 填充列）
        String fill = null;
        if (tableFieldAnnotation != null && tableFieldAnnotation.findAttributeValue("fill") != null) {
            String fillText = tableFieldAnnotation.findAttributeValue("fill").getText();
            if (fillText.contains("FieldFill")) {
                int idx = fillText.lastIndexOf('.');
                String shortName = idx >= 0 ? fillText.substring(idx + 1) : fillText;
                if (shortName.startsWith("FieldFill")) {
                    shortName = shortName.substring("FieldFill".length());
                }
                fill = shortName;
            }
        }
        // T-M16-01：字段注释（注解 > javadoc > 行注释；schema 预留最高优先级，当前返回 null）
        String comment = resolveComment(field);
        // T-M23.3：角色分组——主键置顶(PRIMARY)、fill 自动填充沉底(AUDIT)、逻辑删除/乐观锁沉底(SYSTEM)
        ColumnRole role;
        if (isId) {
            role = ColumnRole.PRIMARY;
        } else if (fill != null) {
            role = ColumnRole.AUDIT;
        } else if (hasAnnotation(field, TABLE_LOGIC) || hasAnnotation(field, VERSION)) {
            role = ColumnRole.SYSTEM;
        } else {
            role = ColumnRole.BUSINESS;
        }
        return List.of(new ColumnInfo(fieldName, columnName, isId, idTypeAuto, fieldType, fill, comment, role));
    }

    /**
     * 展平嵌入对象字段（T-Embedded D3）：覆写与列名推导复用 JPA 轨
     * {@link JpaMetadataReader#flattenEmbeddedColumns}（类级/字段级 @AttributeOverride、点路径覆写、
     * 嵌套递归同语义）；@EmbeddedId 展平列全部标记主键（由 resolveInternal 回填
     * TableInfo.compositeIdColumns）。嵌入类型不可解析时降级为单列形态（字段名推断列名），不丢字段。
     */
    private static List<ColumnInfo> resolveEmbeddedColumns(PsiField field, Map<String, String> classOverrides) {
        String fieldName = field.getName();
        boolean isEmbeddedId = JpaMetadataReader.hasEmbeddedIdAnnotation(field);
        Map<String, String> overrides = new LinkedHashMap<>(classOverrides);
        overrides.putAll(JpaMetadataReader.attributeOverrides(field));
        List<JpaMetadataReader.EmbeddedColumnSpec> specs;
        try {
            specs = JpaMetadataReader.flattenEmbeddedColumns(field, overrides);
        } catch (Throwable ignored) {
            specs = List.of();
        }
        // T-EmbeddedIdentify W1：MP 轨（D3 混合注解）嵌入列诊断日志，语义与 JPA 轨一致；
        // MP 轨命名约定恒为默认 NONE（对齐 MP 运行时），来源标 mp-track 免与 JPA 轨混淆。
        try {
            String owner;
            try {
                owner = field.getContainingClass() != null ? field.getContainingClass().getName() : null;
            } catch (Throwable ignored) {
                owner = null;
            }
            if (owner == null) owner = "?";
            if (specs.isEmpty()) {
                String fieldType;
                try {
                    fieldType = field.getType().getCanonicalText();
                } catch (Throwable ignored) {
                    fieldType = null;
                }
                LOG.warn("[T-EmbeddedIdentify] 嵌入展平降级单列（类型不可解析/全被跳过） entity=" + owner
                        + " field=" + fieldName + " fieldType=" + fieldType
                        + " naming=NONE/mp-track overrides=" + overrides.size());
            } else {
                StringBuilder summary = new StringBuilder();
                for (JpaMetadataReader.EmbeddedColumnSpec spec : specs) {
                    if (summary.length() > 0) summary.append(", ");
                    summary.append(spec.getFieldName()).append("→").append(spec.getColumnName());
                }
                LOG.info("[T-EmbeddedIdentify] entity=" + owner + " field=" + fieldName
                        + " naming=NONE/mp-track columns=[" + summary + "]");
            }
        } catch (Throwable ignored) {
        }
        if (specs.isEmpty()) {
            String fieldType;
            try {
                fieldType = field.getType().getCanonicalText();
            } catch (Throwable ignored) {
                fieldType = null;
            }
            return List.of(new ColumnInfo(fieldName, NamingStrategy.INSTANCE.toSnakeCase(fieldName),
                    isEmbeddedId, false, fieldType, null, resolveComment(field),
                    isEmbeddedId ? ColumnRole.PRIMARY : ColumnRole.BUSINESS,
                    true, true, null, true, false, fieldName));
        }
        List<ColumnInfo> result = new ArrayList<>();
        for (JpaMetadataReader.EmbeddedColumnSpec spec : specs) {
            result.add(new ColumnInfo(spec.getFieldName(), spec.getColumnName(), isEmbeddedId, false,
                    spec.getFieldType(), null, resolveComment(spec.getField()),
                    isEmbeddedId ? ColumnRole.PRIMARY : ColumnRole.BUSINESS,
                    spec.isInsertable(), spec.isUpdatable(), null, true, false, fieldName,
                    spec.getEmbeddedPath()));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // M16：注释读取（四级优先级：SCHEMA > ANNOTATION > JAVADOC > LINE_COMMENT）
    // ------------------------------------------------------------------

    /**
     * 注释来源优先级（M16，schema 预留）：
     * SCHEMA（数据源 schema 列/表注释，方案 B 接入后自动覆盖一切代码内注释）最高，
     * 当前实现 schema 返回 null——实际生效顺序 = 注解 &gt; javadoc &gt; 行注释。
     *
     * O-58 兑现的是「方案 B」的<b>列名</b>半边（{@link ColumnNameCalibrator} 在 {@link EntityResolver#resolve}
     * 返回边界用数据源真值纠正列名）；<b>注释</b>半边（本枚举的 SCHEMA 源）仍未接入，故此处保持 null 占位。
     */
    public enum CommentSource { SCHEMA, ANNOTATION, JAVADOC, LINE_COMMENT }

    /**
     * 提取字段注释（PsiField）：注解白名单 &gt; javadoc &gt; 行注释。
     * schema 源未接入（返回 null），未来方案 B 将 {@link CommentSource#SCHEMA} 结果注入合并链。
     */
    private static String resolveComment(PsiField field) {
        return firstNonBlank(Arrays.asList(
            null, // SCHEMA 预留：方案 B 接入后注入
            annotationComment(field),
            javadocComment(field.getDocComment() != null ? field.getDocComment().getText() : null),
            lineComment(field)
        ));
    }

    /** 提取类级注释（T-M16-05）：类注解白名单 &gt; 类 javadoc &gt; 类上方行注释。 */
    private static String resolveComment(PsiClass psiClass) {
        return firstNonBlank(Arrays.asList(
            null, // SCHEMA 预留
            annotationComment(psiClass),
            javadocComment(psiClass.getDocComment() != null ? psiClass.getDocComment().getText() : null),
            lineComment(psiClass)
        ));
    }

    /** 取最高优先级非空注释（按来源顺序，schema 为空时注解生效）。 */
    private static String firstNonBlank(List<String> comments) {
        for (String comment : comments) {
            if (comment != null && !comment.isBlank()) {
                return comment.trim();
            }
        }
        return null;
    }

    /**
     * 注解注释提取：白名单注解按专属属性（@Schema(description)、@ApiModelProperty(value/description)、
     * @ExcelProperty(value) 等），通用属性（description/name/comment/desc）兜底；
     * <b>排除 MP 注解</b>（@TableName/@TableField/@TableId/@TableLogic——value 是表名/列名非注释；
     * @TableField(comment = ...) 的 comment 属性除外，MP 3.4+ 支持）。
     */
    private static String annotationComment(PsiClass psi) {
        return annotationComment(psi.getModifierList() != null ? psi.getModifierList().getAnnotations() : new PsiAnnotation[0]);
    }

    private static String annotationComment(PsiField field) {
        return annotationComment(field.getModifierList() != null ? field.getModifierList().getAnnotations() : new PsiAnnotation[0]);
    }

    private static String annotationComment(PsiAnnotation[] annotations) {
        for (PsiAnnotation annotation : annotations) {
            if (annotation.getNameReferenceElement() == null) continue;
            String simpleName = annotation.getNameReferenceElement().getReferenceName();
            if (simpleName == null) continue;
            if (MP_ANNOTATION_NAMES.contains(simpleName)) {
                // @TableField(comment = "...") 例外：comment 属性是字段注释
                if ("TableField".equals(simpleName)) {
                    String comment = attributeString(annotation, "comment");
                    if (comment != null && !comment.isBlank()) return comment;
                }
                continue;
            }
            // 白名单注解专属属性
            List<String> specific = COMMENT_ANNOTATION_ATTRIBUTES.get(simpleName);
            if (specific != null) {
                for (String attr : specific) {
                    String comment = attributeString(annotation, attr);
                    if (comment != null && !comment.isBlank()) return comment;
                }
            }
            // 通用属性兜底
            for (String attr : COMMENT_ATTRIBUTE_NAMES) {
                String comment = attributeString(annotation, attr);
                if (comment != null && !comment.isBlank()) return comment;
            }
        }
        return null;
    }

    /**
     * JavaDoc 描述提取：去块注释定界符与行首 {@code *}，取 {@code @} 标签前描述段落，空行折叠为空格。
     */
    private static String javadocComment(String docText) {
        if (docText == null) return null;
        String text = docText;
        if (text.startsWith("/**")) text = text.substring(3);
        if (text.endsWith("*/")) text = text.substring(0, text.length() - 2);
        StringBuilder sb = new StringBuilder();
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.trim();
            if (line.startsWith("*")) line = line.substring(1).trim();
            // takeWhile { !it.startsWith("@") }：遇 @ 标签段落即止
            if (line.startsWith("@")) break;
            if (line.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(line);
        }
        String cleaned = sb.toString();
        return cleaned.isBlank() ? null : cleaned;
    }

    /**
     * 行注释提取（兜底）：{@code // xxx} 行注释。
     *
     * <p>双路径：① LightJava 将元素上方的行注释<b>包含进元素 textRange</b>（{@code field.text} 以
     * {@code //} 开头）——先检查元素自身文本首行；② 完整 IDE 中注释是独立兄弟节点——回退文档文本
     * 向上逐行扫描（跳过空行与 {@code @} 注解行，第一个非空行若是 {@code //} 则提取，否则停止）。</p>
     */
    private static String lineComment(PsiElement element) {
        // 路径 ①：元素自身文本首行（LightPlatform 注释绑定进 textRange）
        String ownText = element.getText();
        if (ownText != null) {
            int nl = ownText.indexOf('\n');
            String ownFirstLine = (nl >= 0 ? ownText.substring(0, nl) : ownText).trim();
            if (ownFirstLine.startsWith("//")) {
                String comment = ownFirstLine.substring(2).trim();
                if (!comment.isBlank()) return comment;
            }
        }
        // 路径 ②：文档文本向上扫描
        int offset = element.getTextRange().getStartOffset();
        String text = element.getContainingFile().getText();
        String before = text.substring(0, offset);
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

    // ------------------------------------------------------------------
    // 注解辅助
    // ------------------------------------------------------------------

    private static boolean hasAnnotation(PsiClass psi, String qualifiedName) {
        return findAnnotation(psi, qualifiedName) != null;
    }

    private static boolean hasAnnotation(PsiField field, String qualifiedName) {
        return findAnnotation(field, qualifiedName) != null;
    }

    /**
     * 按注解查找：优先全限定名；粘贴片段/测试环境注解类不可解析时，
     * 按短名兜底匹配（{@code @TableName} → TableName），保证 {@code @TableName("t_user")} 可识别。
     */
    private static PsiAnnotation findAnnotation(PsiClass psi, String qualifiedName) {
        if (psi.getModifierList() != null) {
            PsiAnnotation found = psi.getModifierList().findAnnotation(qualifiedName);
            if (found != null) return found;
        }
        return findAnnotationByShortName(psi.getModifierList() != null ? psi.getModifierList().getAnnotations() : null, qualifiedName);
    }

    private static PsiAnnotation findAnnotation(PsiField field, String qualifiedName) {
        if (field.getModifierList() != null) {
            PsiAnnotation found = field.getModifierList().findAnnotation(qualifiedName);
            if (found != null) return found;
        }
        return findAnnotationByShortName(field.getModifierList() != null ? field.getModifierList().getAnnotations() : null, qualifiedName);
    }

    private static PsiAnnotation findAnnotationByShortName(PsiAnnotation[] annotations, String qualifiedName) {
        if (annotations == null) return null;
        String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        for (PsiAnnotation annotation : annotations) {
            if (annotation.getNameReferenceElement() != null
                    && simpleName.equals(annotation.getNameReferenceElement().getReferenceName())) {
                return annotation;
            }
        }
        return null;
    }

    /** 读取注解的 value 属性字符串值（如 @TableName("t_user") → t_user）。无注解或无值返回 null。 */
    private static String annotationValue(PsiClass psi, String qualifiedName) {
        PsiAnnotation annotation = findAnnotation(psi, qualifiedName);
        return annotation != null ? attributeString(annotation, "value") : null;
    }

    private static String annotationValue(PsiField field, String qualifiedName) {
        PsiAnnotation annotation = findAnnotation(field, qualifiedName);
        return annotation != null ? attributeString(annotation, "value") : null;
    }

    private static String attributeString(PsiAnnotation annotation, String attribute) {
        if (annotation.findAttributeValue(attribute) == null) return null;
        if (!(annotation.findAttributeValue(attribute) instanceof PsiLiteralExpression)) return null;
        Object value = ((PsiLiteralExpression) annotation.findAttributeValue(attribute)).getValue();
        if (!(value instanceof String)) return null;
        // T-M16.3-03：真实项目注解类可解析时 findAttributeValue 返回默认值（如 @TableField 的
        // `value() default ""`）——空白视为未指定（回退命名策略），否则 @TableField(fill=...) 无 value
        // 的字段列名解析为空串（用户实测 `SELECT id, , , deleted, ...` 空列名根因）
        String s = (String) value;
        return s.isBlank() ? null : s;
    }

    private static Boolean annotationAttributeBoolean(PsiAnnotation annotation, String attribute) {
        if (annotation == null || annotation.findAttributeValue(attribute) == null) return null;
        if (!(annotation.findAttributeValue(attribute) instanceof PsiLiteralExpression)) return null;
        Object value = ((PsiLiteralExpression) annotation.findAttributeValue(attribute)).getValue();
        return value instanceof Boolean ? (Boolean) value : null;
    }

    // ------------------------------------------------------------------
    // Kotlin companion object → 静态区（外层 static 转发 + Companion 嵌套类双路兼容）
    // ------------------------------------------------------------------

    private static final String TABLE_NAME = "com.baomidou.mybatisplus.annotation.TableName";
    private static final String TABLE_FIELD = "com.baomidou.mybatisplus.annotation.TableField";
    private static final String TABLE_ID = "com.baomidou.mybatisplus.annotation.TableId";
    private static final String TABLE_LOGIC = "com.baomidou.mybatisplus.annotation.TableLogic";

    private static final Logger LOG = Logger.getInstance(EntityResolver.class);
    // T-M22-02：乐观锁版本注解（OptimisticLockerInnerInterceptor 的 @Version 字段识别；
    // 与 TABLE_LOGIC 同模式走 findAnnotation 短名兜底）
    private static final String VERSION = "com.baomidou.mybatisplus.annotation.Version";

    /**
     * T-M2：主键序列注解（Oracle/PG/DM 等序列方言实体的主键取值声明）。
     * 常量全限定名 com.baomidou.mybatisplus.annotation.KeySequence。
     */
    private static final String KEY_SEQUENCE = "com.baomidou.mybatisplus.annotation.KeySequence";

    // ------------------------------------------------------------------
    // T-M2：@KeySequence 主键序列读取
    // ------------------------------------------------------------------

    /**
     * 读取实体类级 KEY_SEQUENCE 注解的序列名（value 属性，String[] 取首元素）。
     *
     * <p>消费方：BatchOperationConverter INSERT 模板——实体含 @KeySequence 且当前方言为
     * Oracle/DM/KingBase 时，追加「主键取序列 {seq}」注释提示 + 主键列保持 {@code ?} 占位
     * （不做真实 nextval 渲染，边界文档化见 buildInsertBatch KDoc）。
     *
     * <p>解析容错（不抛异常）：注解缺失返回 null；value 支持单值字面量与数组Initializer
     * （{@code @KeySequence("seq")} / {@code @KeySequence(value = ["seq1", "seq2"])} 取首个）；
     * 非字符串字面量（常量引用等静态不可得）返回 null。
     * 注解类不可解析的环境按短名 "KeySequence" 兜底（与 findAnnotation 同模式）。
     *
     * <p>注意：MP {@code @KeySequence} 的 {@code value()} 声明为 {@code String[]}；历史版本亦接受单值。
     */
    public static String keySequenceValue(@NotNull PsiClass entityClass) {
        try {
            PsiModifierList modifierList = entityClass.getModifierList();
            com.intellij.psi.PsiAnnotation annotation = modifierList != null
                    ? modifierList.findAnnotation(KEY_SEQUENCE) : null;
            if (annotation == null && modifierList != null) {
                for (com.intellij.psi.PsiAnnotation a : modifierList.getAnnotations()) {
                    String shortName = a.getNameReferenceElement() != null
                            ? a.getNameReferenceElement().getReferenceName() : null;
                    if ("KeySequence".equals(shortName)) {
                        annotation = a;
                        break;
                    }
                }
            }
            if (annotation == null) return null;
            com.intellij.psi.PsiAnnotationMemberValue memberValue = annotation.findAttributeValue("value");
            if (memberValue == null) return null;
            String value;
            if (memberValue instanceof com.intellij.psi.PsiLiteralExpression literal) {
                value = literal.getValue() instanceof String s ? s : null;
            } else if (memberValue instanceof com.intellij.psi.PsiArrayInitializerMemberValue arrayValue) {
                value = arrayValue.getInitializers().length > 0
                        && arrayValue.getInitializers()[0] instanceof com.intellij.psi.PsiLiteralExpression first
                        ? first.getValue() instanceof String s ? s : null : null;
            } else {
                value = literalString(memberValue);
            }
            return value != null && !value.isBlank() ? value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 非标准容器形态兜底：直接按字符串字面量读取。 */
    private static String literalString(@NotNull com.intellij.psi.PsiAnnotationMemberValue memberValue) {
        return memberValue instanceof com.intellij.psi.PsiLiteralExpression literal
                && literal.getValue() instanceof String s ? s : null;
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

    /** MP 注解（value 是表名/列名而非注释，不参与提取；@TableField(comment) 例外）。 */
    private static final Set<String> MP_ANNOTATION_NAMES = Set.of("TableName", "TableField", "TableId", "TableLogic");

    // ------------------------------------------------------------------
    // T-EntityCache：实体解析缓存（三维键 + 双维度失效）
    // ------------------------------------------------------------------

    /** 实体解析缓存键（fileUrl/fileIdentity + classQualifiedName + configSignature；T-Perf-18 不持 PsiFile 强引用）。 */
    private static final class EntityCacheKey {
        final String fileUrl;
        /** T-Perf-18：文件实例同一性辅助键——light/mock 文件（测试 fixture）url 与 stamp 均可能相同，
         *  仅靠 url 会跨实例误命中；生产环境同一逻辑文件恒为同一 PsiFile 实例，不受影响。 */
        final int fileIdentity;
        final String classQualifiedName;
        final String configSignature;

        EntityCacheKey(String fileUrl, int fileIdentity, String classQualifiedName, String configSignature) {
            this.fileUrl = fileUrl;
            this.fileIdentity = fileIdentity;
            this.classQualifiedName = classQualifiedName;
            this.configSignature = configSignature;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EntityCacheKey)) return false;
            EntityCacheKey that = (EntityCacheKey) o;
            return fileIdentity == that.fileIdentity
                && Objects.equals(fileUrl, that.fileUrl)
                && Objects.equals(classQualifiedName, that.classQualifiedName)
                && Objects.equals(configSignature, that.configSignature);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fileUrl, fileIdentity, classQualifiedName, configSignature);
        }
    }

    /** 缓存条目：文件修改戳 + 解析结果。 */
    private static final class Entry {
        final long stamp;
        final TableInfo value;

        Entry(long stamp, TableInfo value) {
            this.stamp = stamp;
            this.value = value;
        }
    }

    /** 缓存键 → 条目（项目级，跟随 EntityResolver 生命周期）。 */
    private static final ConcurrentHashMap<EntityCacheKey, Entry> cache = new ConcurrentHashMap<>();

    /**
     * 券02（T-PerfSweep27-02）缓存容量上限：配置签名变化后旧键永不命中也永不淘汰，
     * 无上限时长会话缓慢累积——超限整体清空（HbmXml 负缓存同范式；键轻值纯，
     * 重解析成本可接受）。internal 可写仅测试缝（生产不改）——包私有（Kotlin internal 等价）。
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
     * 缓存大小估算（设置页展示 KB/MB）：键/值内字符串按 UTF-16 字符 2 字节 + 每条目/每列
     * 固定开销近似——展示用，非精确 JVM 内存（PsiFile 引用本身不计）。
     */
    public static long cacheSizeBytes() {
        long bytes = 0L;
        for (Map.Entry<EntityCacheKey, Entry> e : cache.entrySet()) {
            EntityCacheKey key = e.getKey();
            bytes += (key.classQualifiedName.length() + key.configSignature.length()) * 2L + 64;
            TableInfo info = e.getValue().value;
            bytes += (info.getEntityClassName().length() + info.getTableName().length()) * 2L;
            if (info.getLogicDeleteColumn() != null) bytes += info.getLogicDeleteColumn().length() * 2L;
            if (info.getVersionColumn() != null) bytes += info.getVersionColumn().length() * 2L;
            if (info.getComment() != null) bytes += info.getComment().length() * 2L;
            for (ColumnInfo column : info.getColumns()) {
                bytes += (column.getFieldName().length() + column.getColumnName().length()
                        + (column.getFieldType() != null ? column.getFieldType().length() : 0)
                        + (column.getFill() != null ? column.getFill().length() : 0)
                        + (column.getComment() != null ? column.getComment().length() : 0)) * 2L + 48;
            }
            for (AssociationInfo association : info.getAssociations()) {
                bytes += (association.getFieldName().length() + association.getJoinColumn().length()
                        + association.getTargetTable().length() + association.getTargetAlias().length()) * 2L + 32;
            }
        }
        return bytes;
    }

    // ------------------------------------------------------------------
    // T-M19.4：表名推断策略 + 统一修饰链（静态化——实体类不可解析的兜底路径复用，
    // 见 inferDecoratedTableName，保证「全局语义」在全部推断点一致生效）
    // ------------------------------------------------------------------

    /**
     * 无 @TableName 时的表名推断（T-M19.4-03-①）：
     * <ul>
     *   <li>{@code INFERENCE_SNAKE}（默认，对齐 MP）：驼峰转下划线（BikeDeviceInfoEntity → bike_device_info_entity）；</li>
     *   <li>{@code INFERENCE_CAMEL}：<b>纯保留驼峰</b>——仅首字母小写，<b>不去 Entity 后缀</b>
     *       （BikeDeviceInfoEntity → bikeDeviceInfoEntity，BikeDeviceInfo → bikeDeviceInfo）。</li>
     * </ul>
     * 设置服务不可用时兜底默认 SNAKE。
     */
    private static String inferTableName(String className) {
        return inferTableName(className, null);
    }

    private static String inferTableName(String className, Project project) {
        String strategy = effectiveTableSetting(ProjectPluginSettings::effectiveTableNameInference, project);
        if (strategy == null) {
            try {
                strategy = PluginSettings.getInstance().getState().getTableNameInference();
            } catch (Throwable e) {
                strategy = PluginSettings.INFERENCE_SNAKE;
            }
        }
        if (PluginSettings.INFERENCE_CAMEL.equals(strategy)) {
            if (className.isEmpty()) return className;
            return Character.toLowerCase(className.charAt(0)) + className.substring(1);
        }
        return NamingStrategy.INSTANCE.toSnakeCase(className);
    }

    /**
     * T-M19.4-03-③：统一表名修饰链（全局语义——`@TableName` 显式表名同样被修饰，沿用 T-M16.2-03）：
     * {@code 最终表名 = 添加前缀 + 移除后缀(移除前缀(表名)) + 添加后缀}；
     * <ul>
     *   <li>移除前缀/后缀：{@code ;} 分隔，首个匹配（大小写敏感），不匹配则不修饰（M19.4-R2）；</li>
     *   <li>添加前缀/后缀：<b>单值</b>（用户反馈定稿，不再 {@code ;} 分隔拼接；T-M16.2-03 原语义）。</li>
     * </ul>
     */
    private static String decorateTableName(String raw) {
        return decorateTableName(raw, null);
    }

    private static String decorateTableName(String raw, Project project) {
        String removedPrefix = raw;
        for (String prefix : removeTablePrefixList()) {
            if (raw.startsWith(prefix)) {
                removedPrefix = raw.substring(prefix.length());
                break;
            }
        }
        String removedSuffix = removedPrefix;
        for (String suffix : removeTableSuffixList()) {
            if (removedPrefix.endsWith(suffix)) {
                removedSuffix = removedPrefix.substring(0, removedPrefix.length() - suffix.length());
                break;
            }
        }
        return tablePrefix(project) + removedSuffix + tableSuffix(project);
    }

    /**
     * T-M19.4（用户实测：表名配置在部分路径不生效）：实体类不可解析的<b>兜底推断</b>统一入口——
     * 按配置推断策略（SNAKE/CAMEL）+ 统一修饰链，与 {@link #resolve} 的推断路径语义一致。
     * WrapperLocator 文本兜底、MpjLambdaConverter 子表推断等全部走此函数（全局语义）。
     *
     * <p>Kotlin {@code @JvmOverloads}：project 为调用方已知的所属项目；调用方只有类名字符串时留空——
     * 留空沿改动前的「首个打开项目」取值（见 {@link #effectiveTableSetting}），不引入新行为。</p>
     */
    public static String inferDecoratedTableName(String className, Project project) {
        return decorateTableName(inferTableName(className, project), project);
    }

    /** Kotlin 默认参数等价重载：{@code inferDecoratedTableName(className)}（project = null）。 */
    public static String inferDecoratedTableName(String className) {
        return inferDecoratedTableName(className, null);
    }

    /**
     * T-M19.4：{@code ;} 分隔配置解析为列表（trim + 去空；设置服务不可用时兜底空列表——
     * 测试环境与未配置时零影响）。
     */
    private static List<String> listSetting(Function<PluginSettings.State, String> get) {
        String raw;
        try {
            raw = get.apply(PluginSettings.getInstance().getState());
        } catch (Throwable e) {
            raw = "";
        }
        List<String> result = new ArrayList<>();
        for (String part : raw.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
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
    private static String effectiveTableSetting(java.util.function.Function<ProjectPluginSettings, String> get, Project project) {
        // runCatching 包裹整体：纯单测（无 Application/ProjectManager 实例）与无打开项目时
        // 一律降级 null → 调用方回退全局设置，与原全局读取行为一致
        try {
            Project target = project;
            if (target == null) {
                Project[] openProjects = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
                target = openProjects.length > 0 ? openProjects[0] : null;
            }
            if (target == null) {
                return null;
            }
            String value = get.apply(ProjectPluginSettings.getInstance(target));
            return value != null && !value.isBlank() ? value : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** T-M16.2-03 全局语义：添加表前缀（<b>单值</b>，用户反馈定稿）。 */
    private static String tablePrefix(Project project) {
        String effective = effectiveTableSetting(ProjectPluginSettings::effectiveTablePrefix, project);
        if (effective != null) {
            return effective;
        }
        try {
            return PluginSettings.getInstance().getState().getTablePrefix();
        } catch (Throwable e) {
            return "";
        }
    }

    /** Kotlin 默认参数等价重载：{@code tablePrefix()}（project = null）。 */
    private static String tablePrefix() {
        return tablePrefix(null);
    }

    /** T-M16.2-03 全局语义：添加表后缀（<b>单值</b>，用户反馈定稿）。 */
    private static String tableSuffix(Project project) {
        String effective = effectiveTableSetting(ProjectPluginSettings::effectiveTableSuffix, project);
        if (effective != null) {
            return effective;
        }
        try {
            return PluginSettings.getInstance().getState().getTableSuffix();
        } catch (Throwable e) {
            return "";
        }
    }

    /** Kotlin 默认参数等价重载：{@code tableSuffix()}（project = null）。 */
    private static String tableSuffix() {
        return tableSuffix(null);
    }

    /** T-M19.4：移除表名前缀列表（`;` 分隔，最终表名首个匹配移除）。 */
    private static List<String> removeTablePrefixList() {
        return listSetting(state -> state.getRemoveTablePrefix());
    }

    /** T-M19.4：移除表名后缀列表（`;` 分隔，最终表名首个匹配移除）。 */
    private static List<String> removeTableSuffixList() {
        return listSetting(state -> state.getRemoveTableSuffix());
    }

    /** T-M19.4：忽略字段名集合（`;` 分隔，按 Java 字段名匹配）。 */
    private static Set<String> ignoredFieldNames(Project project) {
        String effective = effectiveTableSetting(ProjectPluginSettings::effectiveIgnoredFields, project);
        String raw;
        if (effective != null) {
            raw = effective;
        } else {
            try {
                raw = PluginSettings.getInstance().getState().getIgnoredFields();
            } catch (Throwable e) {
                raw = "";
            }
        }
        Set<String> result = new HashSet<>();
        for (String part : raw.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /** Kotlin 默认参数等价重载：{@code ignoredFieldNames()}（project = null）。 */
    private static Set<String> ignoredFieldNames() {
        return ignoredFieldNames(null);
    }

    /** Kotlin companion 兼容层（{@code EntityResolver.Companion.clearCache()} 调用形态）。 */
    public static final class Companion {
        private Companion() {}

        public void clearCache() {
            EntityResolver.clearCache();
        }

        public int cacheSize() {
            return EntityResolver.cacheSize();
        }

        public String inferDecoratedTableName(String className) {
            return EntityResolver.inferDecoratedTableName(className);
        }
    }

    public static final Companion Companion = new Companion();
}
