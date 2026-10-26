package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRole
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import cn.song.mybatisplus.visualizer.util.TableConfigSignature
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiWhiteSpace
import java.util.concurrent.ConcurrentHashMap

/**
 * 实体类解析器（F-13）：从实体 [PsiClass] 解析出 [TableInfo]。
 *
 * 规则（对齐 MyBatis-Plus）：
 *  - 表名：优先 `@TableName("xxx")` 注解，否则按命名策略由类名推断（DeviceInfoEntity → device_info）
 *  - 列名：优先 `@TableField("xxx")` 注解，否则按命名策略由字段名推断
 *  - 主键：`@TableId` 标记
 *  - `@TableField(exist = false)` 的字段（非表字段）跳过
 *  - T-M16.2-02：字段递归**父类**收集（父类字段在前，组内对齐 MP 声明顺序语义）——继承实体
 *    （如 `BaseEntity {id, createTime, deleted}`）的 SELECT 全列展开与 @TableLogic/@TableId/
 *    @Version（T-M22-02）识别同样生效
 *  - T-M23.3：字段按**角色分组**稳定排序——`@TableId`(PRIMARY) 置顶、业务字段(BUSINESS) 居中、
 *    `fill` 自动填充(AUDIT) 与 `@TableLogic`/`@Version`(SYSTEM) 沉底，组内保持声明顺序
 *    （用户反馈：BaseEntity 审计字段不应把业务字段挤到后面——
 *    `id, 业务字段…, create_time, update_time, deleted`）
 *  - T-M16.2-03：表前缀/后缀配置（**推断语义**——仅作用于推断表名；T-M19.4 用户反馈收窄，
 *    `@TableName` 显式表名即最终表名、优先级最高、豁免修饰）
 *  - T-M19.4：表名推断策略（无注解时 SNAKE 驼峰转下划线 / CAMEL 纯保留驼峰）、忽略字段名
 *    （`;` 分隔 Java 字段名）、移除表名前缀/后缀（`;` 分隔首匹配）；添加表前缀/后缀为**单值**
 *    （用户反馈定稿，不再 `;` 分隔）
 *  - T-EntityCache：解析结果按「PsiFile + classQualifiedName + configSignature」三维缓存，
 *    文件修改戳（modificationStamp）+ 配置签名双维度失效，避免大项目重复 PSI 遍历
 *  - O-58：返回边界接 [ColumnNameCalibrator]——数据源可用时用真实列集合纠正猜测出的列名
 *    （`orderID` → `order_i_d` 而库里是 `order_id`），不可用/查不了/开关关闭时逐字段原样返回
 */
class EntityResolver {

    /**
     * 解析实体类为表信息（带缓存）。
     *
     * 缓存键：PsiFile + classQualifiedName + configSignature（三维）。
     * 失效机制：
     *  - 文件修改（modificationStamp 变化）→ 缓存自然失效
     *  - 配置签名变化（tablePrefix/tableSuffix/tableNameInference 等设置变更）→ 缓存失效
     *
     * O-58（列名数据源校正）：缓存**继续只存未校正的 PSI 产物**，校正在返回前作为一步廉价后处理
     * （[ColumnNameCalibrator.calibrate]，对 DB 列集合做几次 map 查找，无遍历）执行。这样 DDL 变更的
     * 失效面只有数据源 schema 缓存一处（已登记 MetadataInvalidator），不必给实体缓存键扩 DB 签名。
     * 代价是校正生效时返回的是新对象而非缓存里那一个——已核实全仓无消费方依赖 TableInfo 实例同一性。
     *
     * @param entityClass 实体 PsiClass（如 DeviceInfoEntity）
     * @return 表信息；类名缺失等异常情况返回带推断表名、空列的表信息（保证不抛异常）
     */
    fun resolve(entityClass: PsiClass): TableInfo {
        val className = entityClass.name ?: return TableInfo("", "", emptyList())
        val project = entityClass.project
        val file = entityClass.containingFile
            ?: return ColumnNameCalibrator.calibrate(project, resolveInternal(entityClass))
        val qName = entityClass.qualifiedName ?: className
        val configSig = TableConfigSignature.projectSignature(project)

        // T-Perf-18：缓存键不持 PsiFile 强引用（VirtualFile.url 字符串标识——PsiFile 随键
        // 永久驻留是长会话内存泄漏源；URL 相同的 PSI 重建由 modificationStamp 维度失效）
        val key = EntityCacheKey(file.virtualFile?.url, System.identityHashCode(file), qName, configSig)
        val stamp = file.modificationStamp
        cache[key]?.let { entry ->
            if (entry.stamp == stamp) return ColumnNameCalibrator.calibrate(project, entry.value)
        }

        val value = resolveInternal(entityClass)
        cache[key] = Entry(stamp, value)
        return ColumnNameCalibrator.calibrate(project, value)
    }

    /**
     * 内部解析（完整 PSI 遍历，缓存未命中时调用）。
     *
     * 原有 resolve 逻辑整体迁移至此——缓存层透明，调用方无感。
     */
    private fun resolveInternal(entityClass: PsiClass): TableInfo {
        val className = entityClass.name ?: return TableInfo("", "", emptyList())
        // T-M19.4（用户反馈：@TableName 应为最高优先级）：**显式 @TableName 即最终表名**——
        // 豁免推断策略与修饰链（添加/移除表名前缀后缀仅作用于**推断表名**；
        // T-M16.2-03 的「全局语义」按用户反馈收窄为推断语义）
        val tableName = annotationValue(entityClass, TABLE_NAME)
            ?: decorateTableName(inferTableName(className, entityClass.project), entityClass.project)

        // T-M16.2-02：递归父类收集字段（父类字段在前；@TableLogic/@TableId/@TableField 标注随字段本身生效）
        val fields = collectFieldsIncludingSuper(entityClass)
        // T-M19.4-03-②：忽略字段名配置（`;` 分隔，按 Java 字段名匹配）——命中字段不进
        // SELECT 全列展开/参数对照/补全列集合（resolveColumn 返回 null 跳过；@TableField(exist=false) 注解优先）
        // T-M23.3：按角色分组稳定排序（PRIMARY 置顶 → BUSINESS 居中 → AUDIT/SYSTEM 沉底，
        // 组内保持声明顺序）——三处消费点（SELECT 全列/批量面板/补全）共用 columns 自动统一生效
        // T-Embedded D3：类级 @AttributeOverride（混合注解实体的嵌入覆写，与 JPA 轨同语义）；
        // 嵌入字段可能展开为多列，列收集改 flatMap
        val classOverrides = JpaMetadataReader.attributeOverrides(entityClass)
        val columns = fields
            .flatMap { field -> resolveColumns(field, ignoredFieldNames(entityClass.project), classOverrides) }
            .sortedWith(compareBy { it.role })
        // T-M15-07：逻辑删除列（@TableLogic 标注字段，X07——真实项目 BaseEntity.deleted + 全局 logic-delete-field；
        // T-M16.2-02 修复：父类字段同样识别，继承实体的 deleted 不再丢失）
        val logicDeleteColumn = fields
            .firstOrNull { hasAnnotation(it, TABLE_LOGIC) }
            ?.let { annotationValue(it, TABLE_FIELD) ?: NamingStrategy.toSnakeCase(it.name) }
        // T-M22-02：乐观锁版本列（@Version 标注字段——MP OptimisticLockerInnerInterceptor 语义：
        // UPDATE 自动追加 `version = version + 1` + `AND version = ?`；列名 = @TableField 值或 snake_case；
        // 与 @TableLogic 同模式：父类字段同样识别）
        val versionColumn = fields
            .firstOrNull { hasAnnotation(it, VERSION) }
            ?.let { annotationValue(it, TABLE_FIELD) ?: NamingStrategy.toSnakeCase(it.name) }
        // T-M16-05：表注释（类级注解 > javadoc > 行注释；schema 预留最高优先级，当前返回 null）
        val comment = resolveComment(entityClass)
        // T-Embedded D3：@EmbeddedId 展平列回填复合主键清单（isId 已在展平分支标记；
        // 普通 @TableId 单主键不进清单，与 JPA 轨口径一致）
        val embeddedIdColumns =
            if (fields.any { JpaMetadataReader.hasEmbeddedIdAnnotation(it) }) {
                columns.filter { it.isId && it.embedded }.map { it.columnName }
            } else {
                emptyList()
            }
        return TableInfo(
            className,
            tableName,
            columns,
            logicDeleteColumn,
            versionColumn,
            comment,
            compositeIdColumns = embeddedIdColumns,
        )
    }

    /**
     * 递归收集类及其所有父类的字段（T-M16.2-02）：
     *  - **父类字段在前、子类字段在后**（对齐 MP 声明顺序语义）；
     *  - 仅沿 `superClass` 类继承链——接口字段不纳入（M16.2-R2），`Object` 无字段天然过滤；
     *  - `seen` 集合防循环继承（异常 PSI 场景防御）。
     */
    private fun collectFieldsIncludingSuper(clazz: PsiClass, seen: MutableSet<PsiClass> = mutableSetOf()): List<PsiField> {
        if (!seen.add(clazz)) return emptyList()
        val superFields = clazz.superClass?.let { collectFieldsIncludingSuper(it, seen) } ?: emptyList()
        return superFields + clazz.fields.toList()
    }

    /**
     * 解析单个字段为列信息（T-Embedded D3 起可能展开为多列——嵌入对象展平）；
     * 非表字段（@TableField(exist=false) / T-M19.4 忽略字段名配置）返回空列表。
     */
    private fun resolveColumns(
        field: PsiField,
        ignoredFields: Set<String>,
        classOverrides: Map<String, String>,
    ): List<ColumnInfo> {
        val fieldName = field.name
        val tableFieldAnnotation = findAnnotation(field, TABLE_FIELD)
        // @TableField(exist = false)：非数据库字段，跳过（注解优先）
        if (annotationAttributeBoolean(tableFieldAnnotation, "exist") == false) {
            return emptyList()
        }
        // T-M19.4-03-②：忽略字段名（`;` 分隔，Java 字段名匹配）——与 @TableField(exist=false) 并存
        // （注解优先）；命中即跳过：SELECT 全列展开、参数对照、补全列集合均不含该字段（M19.4-R3）
        if (fieldName in ignoredFields) {
            return emptyList()
        }
        // T-M42（用户实证：SQL 含 serial_version_u_i_d 列时生成 User::getSerialVersionUID 编译失败）：
        // serialVersionUID（static final 序列化字段，无 getter/setter，MP 官方不映射为列）默认忽略——
        // 归一化匹配（小写+去下划线）覆盖 serial_version_uid / serial_version_u_i_d / serialVersionUID /
        // SERIAL_VERSION_UID 等全部形态；正向（SELECT 全列展开/批量面板/补全）与反向（columnToField）
        // 统一受益
        if (fieldName.lowercase().replace("_", "") == "serialversionuid") {
            return emptyList()
        }
        // T-Embedded D3（混合注解实体）：jakarta/javax @Embedded / @Embeddable / @EmbeddedId 值对象字段
        // 展平为宿主表列。MP 运行时本身不展平（该字段按平铺伪列生成 SQL、真实库中不存在），插件口径
        // =跟真实数据库（计划 §四 D3 决策 A，与 O-58 校正层「用真值纠正猜测」同哲学）；
        // @TableField 显式列名时 MP 语义优先，走下方单列路径不展平
        if (annotationValue(field, TABLE_FIELD) == null && JpaMetadataReader.isEmbeddedField(field)) {
            return resolveEmbeddedColumns(field, classOverrides)
        }
        val columnName = annotationValue(field, TABLE_FIELD)
            ?: NamingStrategy.toSnakeCase(fieldName)
        val isId = hasAnnotation(field, TABLE_ID)
        // T-M23-04：主键是否自增（@TableId(type = IdType.AUTO)）——取 type 属性文本判含 "AUTO"；
        // 注解类可解析且未显式写 type 时 findAttributeValue 返回默认值 IdType.NONE（不含 AUTO，恒勾选），
        // 与既有 fill 属性读取同一 API 形态
        val idTypeAuto = isId && findAnnotation(field, TABLE_ID)
            ?.findAttributeValue("type")?.text?.contains("AUTO") == true
        // M13：记录字段类型（参数回填引号推断用）；PSI 类型解析异常时兜底 null
        val fieldType = runCatching { field.type.canonicalText }.getOrNull()
        // T-M15-08：自动填充标注（@TableField(fill = FieldFill.INSERT_UPDATE) 的 fill 属性文本，
        // 枚举引用非字面量，取 attributeValue 文本；先确认含 FieldFill 再取短名；
        // 批量 INSERT 模板据此排除 UPDATE 填充列）
        val fill = tableFieldAnnotation?.findAttributeValue("fill")?.text
            ?.takeIf { it.contains("FieldFill") }
            ?.substringAfterLast('.')
            ?.removePrefix("FieldFill")
        // T-M16-01：字段注释（注解 > javadoc > 行注释；schema 预留最高优先级，当前返回 null）
        val comment = resolveComment(field)
        // T-M23.3：角色分组——主键置顶(PRIMARY)、fill 自动填充沉底(AUDIT)、逻辑删除/乐观锁沉底(SYSTEM)
        val role = when {
            isId -> ColumnRole.PRIMARY
            fill != null -> ColumnRole.AUDIT
            hasAnnotation(field, TABLE_LOGIC) || hasAnnotation(field, VERSION) -> ColumnRole.SYSTEM
            else -> ColumnRole.BUSINESS
        }
        return listOf(
            ColumnInfo(fieldName, columnName, isId, idTypeAuto, fieldType, fill, comment, role)
        )
    }

    /**
     * 展平嵌入对象字段（T-Embedded D3）：覆写与列名推导复用 JPA 轨
     * [JpaMetadataReader.flattenEmbeddedColumns]（类级/字段级 @AttributeOverride、点路径覆写、
     * 嵌套递归同语义）；@EmbeddedId 展平列全部标记主键（由 [resolveInternal] 回填
     * TableInfo.compositeIdColumns）。嵌入类型不可解析时降级为单列形态（字段名推断列名），不丢字段。
     */
    private fun resolveEmbeddedColumns(field: PsiField, classOverrides: Map<String, String>): List<ColumnInfo> {
        val fieldName = field.name
        val isEmbeddedId = JpaMetadataReader.hasEmbeddedIdAnnotation(field)
        val overrides = classOverrides + JpaMetadataReader.attributeOverrides(field)
        val specs = runCatching { JpaMetadataReader.flattenEmbeddedColumns(field, overrides) }
            .getOrDefault(emptyList())
        // T-EmbeddedIdentify W1：MP 轨（D3 混合注解）嵌入列诊断日志，语义与 JPA 轨一致；
        // MP 轨命名约定恒为默认 NONE（对齐 MP 运行时），来源标 mp-track 免与 JPA 轨混淆。
        runCatching {
            val owner = runCatching { field.containingClass?.name }.getOrNull() ?: "?"
            if (specs.isEmpty()) {
                val fieldType = runCatching { field.type.canonicalText }.getOrNull()
                LOG.warn(
                    "[T-EmbeddedIdentify] 嵌入展平降级单列（类型不可解析/全被跳过） entity=$owner field=$fieldName " +
                        "fieldType=$fieldType naming=NONE/mp-track overrides=${overrides.size}",
                )
            } else {
                val summary = specs.joinToString(", ") { "${it.fieldName}→${it.columnName}" }
                LOG.info("[T-EmbeddedIdentify] entity=$owner field=$fieldName naming=NONE/mp-track columns=[$summary]")
            }
        }
        if (specs.isEmpty()) {
            val fieldType = runCatching { field.type.canonicalText }.getOrNull()
            return listOf(
                ColumnInfo(
                    fieldName,
                    NamingStrategy.toSnakeCase(fieldName),
                    isEmbeddedId,
                    false,
                    fieldType,
                    null,
                    resolveComment(field),
                    if (isEmbeddedId) ColumnRole.PRIMARY else ColumnRole.BUSINESS,
                    embedded = true,
                    embeddedParent = fieldName,
                ),
            )
        }
        return specs.map { spec ->
            ColumnInfo(
                fieldName = spec.fieldName,
                columnName = spec.columnName,
                isId = isEmbeddedId,
                idTypeAuto = false,
                fieldType = spec.fieldType,
                fill = null,
                comment = resolveComment(spec.field),
                role = if (isEmbeddedId) ColumnRole.PRIMARY else ColumnRole.BUSINESS,
                insertable = spec.insertable,
                updatable = spec.updatable,
                embedded = true,
                embeddedParent = fieldName,
                embeddedPath = spec.embeddedPath,
            )
        }
    }

    // ------------------------------------------------------------------
    // M16：注释读取（四级优先级：SCHEMA > ANNOTATION > JAVADOC > LINE_COMMENT）
    // ------------------------------------------------------------------

    /**
     * 注释来源优先级（M16，schema 预留）：
     * SCHEMA（数据源 schema 列/表注释，方案 B 接入后自动覆盖一切代码内注释）最高，
     * 当前实现 schema 返回 null——实际生效顺序 = 注解 > javadoc > 行注释。
     *
     * O-58 兑现的是「方案 B」的**列名**半边（[ColumnNameCalibrator] 在 [resolve] 返回边界用数据源
     * 真值纠正列名）；**注释**半边（本枚举的 SCHEMA 源）仍未接入，故此处保持 null 占位。
     */
    enum class CommentSource { SCHEMA, ANNOTATION, JAVADOC, LINE_COMMENT }

    /**
     * 提取字段注释（PsiField）：注解白名单 > javadoc > 行注释。
     * schema 源未接入（返回 null），未来方案 B 将 [CommentSource.SCHEMA] 结果注入合并链。
     */
    private fun resolveComment(field: PsiField): String? =
        firstNonBlank(listOf(
            null, // SCHEMA 预留：方案 B 接入后注入
            annotationComment(field),
            javadocComment(field.docComment?.text),
            lineComment(field),
        ))

    /** 提取类级注释（T-M16-05）：类注解白名单 > 类 javadoc > 类上方行注释。 */
    private fun resolveComment(psiClass: PsiClass): String? =
        firstNonBlank(listOf(
            null, // SCHEMA 预留
            annotationComment(psiClass),
            javadocComment(psiClass.docComment?.text),
            lineComment(psiClass),
        ))

    /** 取最高优先级非空注释（按来源顺序，schema 为空时注解生效）。 */
    private fun firstNonBlank(comments: List<String?>): String? =
        comments.firstOrNull { !it.isNullOrBlank() }?.trim()

    /**
     * 注解注释提取：白名单注解按专属属性（@Schema(description)、@ApiModelProperty(value/description)、
     * @ExcelProperty(value) 等），通用属性（description/name/comment/desc）兜底；
     * **排除 MP 注解**（@TableName/@TableField/@TableId/@TableLogic——value 是表名/列名非注释；
     * @TableField(comment = ...) 的 comment 属性除外，MP 3.4+ 支持）。
     */
    private fun annotationComment(psi: PsiClass): String? = annotationComment(psi.modifierList?.annotations ?: emptyArray())

    private fun annotationComment(field: PsiField): String? = annotationComment(field.modifierList?.annotations ?: emptyArray())

    private fun annotationComment(annotations: Array<PsiAnnotation>): String? {
        for (annotation in annotations) {
            val simpleName = annotation.nameReferenceElement?.referenceName ?: continue
            if (simpleName in MP_ANNOTATION_NAMES) {
                // @TableField(comment = "...") 例外：comment 属性是字段注释
                if (simpleName == "TableField") {
                    attributeString(annotation, "comment")?.takeIf { it.isNotBlank() }?.let { return it }
                }
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
     * 双路径：① LightJava 将元素上方的行注释**包含进元素 textRange**（`field.text` 以 `//` 开头）——
     * 先检查元素自身文本首行；② 完整 IDE 中注释是独立兄弟节点——回退文档文本向上逐行扫描
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

    /** MP 注解（value 是表名/列名而非注释，不参与提取；@TableField(comment) 例外）。 */
    private val MP_ANNOTATION_NAMES = setOf("TableName", "TableField", "TableId", "TableLogic")

    // ------------------------------------------------------------------
    // 注解辅助
    // ------------------------------------------------------------------

    private fun hasAnnotation(psi: PsiClass, qualifiedName: String): Boolean =
        findAnnotation(psi, qualifiedName) != null

    private fun hasAnnotation(field: PsiField, qualifiedName: String): Boolean =
        findAnnotation(field, qualifiedName) != null

    /**
     * 按注解查找：优先全限定名；粘贴片段/测试环境注解类不可解析时，
     * 按短名兜底匹配（`@TableName` → TableName），保证 `@TableName("t_user")` 可识别。
     */
    private fun findAnnotation(psi: PsiClass, qualifiedName: String): PsiAnnotation? {
        psi.modifierList?.findAnnotation(qualifiedName)?.let { return it }
        return findAnnotationByShortName(psi.modifierList?.annotations, qualifiedName)
    }

    private fun findAnnotation(field: PsiField, qualifiedName: String): PsiAnnotation? {
        field.modifierList?.findAnnotation(qualifiedName)?.let { return it }
        return findAnnotationByShortName(field.modifierList?.annotations, qualifiedName)
    }

    private fun findAnnotationByShortName(annotations: Array<PsiAnnotation>?, qualifiedName: String): PsiAnnotation? {
        val simpleName = qualifiedName.substringAfterLast('.')
        return annotations?.firstOrNull { it.nameReferenceElement?.referenceName == simpleName }
    }

    /**
     * 读取注解的 value 属性字符串值（如 @TableName("t_user") → t_user）。
     * 无注解或无值返回 null。
     */
    private fun annotationValue(psi: PsiClass, qualifiedName: String): String? {
        val annotation = findAnnotation(psi, qualifiedName) ?: return null
        return attributeString(annotation, "value")
    }

    private fun annotationValue(field: PsiField, qualifiedName: String): String? {
        val annotation = findAnnotation(field, qualifiedName) ?: return null
        return attributeString(annotation, "value")
    }

    private fun attributeString(annotation: PsiAnnotation, attribute: String): String? {
        val memberValue = annotation.findAttributeValue(attribute) ?: return null
        val literal = memberValue as? PsiLiteralExpression ?: return null
        val value = literal.value as? String
        // T-M16.3-03：真实项目注解类可解析时 findAttributeValue 返回**默认值**（如 @TableField 的
        // `value() default ""`）——空白视为未指定（回退命名策略），否则 @TableField(fill=...) 无 value
        // 的字段列名解析为空串（用户实测 `SELECT id, , , deleted, ...` 空列名根因）
        return value?.takeIf { it.isNotBlank() }
    }

    private fun annotationAttributeBoolean(annotation: PsiAnnotation?, attribute: String): Boolean? {
        val memberValue = annotation?.findAttributeValue(attribute) ?: return null
        val literal = memberValue as? PsiLiteralExpression ?: return null
        return literal.value as? Boolean
    }

    companion object {
        private val LOG = Logger.getInstance(EntityResolver::class.java)
        private const val TABLE_NAME = "com.baomidou.mybatisplus.annotation.TableName"
        private const val TABLE_FIELD = "com.baomidou.mybatisplus.annotation.TableField"
        private const val TABLE_ID = "com.baomidou.mybatisplus.annotation.TableId"
        private const val TABLE_LOGIC = "com.baomidou.mybatisplus.annotation.TableLogic"
        // T-M22-02：乐观锁版本注解（OptimisticLockerInnerInterceptor 的 @Version 字段识别；
        // 与 TABLE_LOGIC 同模式走 findAnnotation 短名兜底）
        private const val VERSION = "com.baomidou.mybatisplus.annotation.Version"

        /**
         * T-M2：主键序列注解（Oracle/PG/DM 等序列方言实体的主键取值声明）。
         * 常量全限定名 com.baomidou.mybatisplus.annotation.KeySequence。
         */
        private const val KEY_SEQUENCE = "com.baomidou.mybatisplus.annotation.KeySequence"

        // ------------------------------------------------------------------
        // T-M2：@KeySequence 主键序列读取
        // ------------------------------------------------------------------

        /**
         * 读取实体类级 [KEY_SEQUENCE] 注解的序列名（value 属性，`String[]` 取首元素）。
         *
         * 消费方：[BatchOperationConverter] INSERT 模板——实体含 @KeySequence 且当前方言为
         * Oracle/DM/KingBase 时，追加「主键取序列 {seq}」注释提示 + 主键列保持 `?` 占位
         * （不做真实 nextval 渲染，边界文档化见 buildInsertBatch KDoc）。
         *
         * 解析容错（不抛异常）：注解缺失返回 null；value 支持单值字面量与数组Initializer
         * （`@KeySequence("seq")` / `@KeySequence(value = ["seq1", "seq2"])` 取首个）；
         * 非字符串字面量（常量引用等静态不可得）返回 null。
         * 注解类不可解析的环境按短名 "KeySequence" 兜底（与 findAnnotation 同模式）。
         *
         * 注意：MP `@KeySequence` 的 `value()` 声明为 `String[]`；历史版本亦接受单值。
         */
        @JvmStatic
        fun keySequenceValue(entityClass: PsiClass): String? = runCatching {
            val annotation = entityClass.modifierList?.findAnnotation(KEY_SEQUENCE)
                ?: entityClass.modifierList?.annotations?.firstOrNull {
                    it.nameReferenceElement?.referenceName == "KeySequence"
                }
                ?: return null
            val memberValue = annotation.findAttributeValue("value") ?: return null
            when (memberValue) {
                is PsiLiteralExpression -> memberValue.value as? String
                is PsiArrayInitializerMemberValue ->
                    (memberValue.initializers.firstOrNull() as? PsiLiteralExpression)?.value as? String
                else -> literalString(memberValue)
            }?.takeIf { it.isNotBlank() }
        }.getOrNull()

        /** 非标准容器形态兜底：直接按字符串字面量读取。 */
        private fun literalString(memberValue: PsiAnnotationMemberValue): String? =
            (memberValue as? PsiLiteralExpression)?.value as? String

        // ------------------------------------------------------------------
        // T-EntityCache：实体解析缓存（三维键 + 双维度失效）
        // ------------------------------------------------------------------

        /**
         * 实体解析缓存键。
         *
         * @param file               PsiClass 所在文件（文件修改戳作为失效依据）
         * @param classQualifiedName 类全限定名（PsiClass 对象不稳定，用全限定名跨 PSI 重建保持同一性）
         * @param configSignature   配置签名（表名推断策略 + 忽略字段 + 表前缀后缀拼接串）
         */
        private data class EntityCacheKey(
            val fileUrl: String?,
            /** T-Perf-18：文件实例同一性辅助键——light/mock 文件（测试 fixture）url 与 stamp 均可能相同，
             *  仅靠 url 会跨实例误命中；生产环境同一逻辑文件恒为同一 PsiFile 实例，不受影响。 */
            val fileIdentity: Int,
            val classQualifiedName: String,
            val configSignature: String,
        )

        /** 缓存条目：文件修改戳 + 解析结果。 */
        private data class Entry(val stamp: Long, val value: TableInfo)

        /** 缓存键 → 条目（项目级，跟随 EntityResolver 生命周期）。 */
        private val cache = ConcurrentHashMap<EntityCacheKey, Entry>()

        /** 清空缓存（配置变更 / 项目关闭时调用）。 */
        @JvmStatic
        fun clearCache() = cache.clear()

        /** 当前缓存条目数（测试 / 诊断用）。 */
        @JvmStatic
        fun cacheSize(): Int = cache.size

        /**
         * 缓存大小估算（设置页展示 KB/MB）：键/值内字符串按 UTF-16 字符 2 字节 + 每条目/每列
         * 固定开销近似——展示用，非精确 JVM 内存（PsiFile 引用本身不计）。
         */
        @JvmStatic
        internal fun cacheSizeBytes(): Long {
            var bytes = 0L
            for ((key, entry) in cache) {
                bytes += (key.classQualifiedName.length + key.configSignature.length) * 2L + 64
                val info = entry.value
                bytes += (info.entityClassName.length + info.tableName.length) * 2L
                info.logicDeleteColumn?.let { bytes += it.length * 2L }
                info.versionColumn?.let { bytes += it.length * 2L }
                info.comment?.let { bytes += it.length * 2L }
                for (column in info.columns) {
                    bytes += (column.fieldName.length + column.columnName.length +
                        (column.fieldType?.length ?: 0) + (column.fill?.length ?: 0) +
                        (column.comment?.length ?: 0)) * 2L + 48
                }
                for (association in info.associations) {
                    bytes += (association.fieldName.length + association.joinColumn.length +
                        association.targetTable.length + association.targetAlias.length) * 2L + 32
                }
            }
            return bytes
        }

        // ------------------------------------------------------------------
        // T-M19.4：表名推断策略 + 统一修饰链（companion 静态化——实体类不可解析的兜底路径复用，
        // 见 [inferDecoratedTableName]，保证「全局语义」在全部推断点一致生效）
        // ------------------------------------------------------------------

        /**
         * 无 @TableName 时的表名推断（T-M19.4-03-①）：
         *  - [PluginSettings.INFERENCE_SNAKE]（默认，对齐 MP）：驼峰转下划线（BikeDeviceInfoEntity → bike_device_info_entity）；
         *  - [PluginSettings.INFERENCE_CAMEL]：**纯保留驼峰**——仅首字母小写，**不去 Entity 后缀**
         *    （用户反馈：文案「保留驼峰」与行为一致；BikeDeviceInfoEntity → bikeDeviceInfoEntity，
         *    BikeDeviceInfo → bikeDeviceInfo）。
         * 设置服务不可用时兜底默认 SNAKE。
         *
         * @param project 实体所属项目（决定读哪一份项目级设置；null 见 [effectiveTableSetting]）
         */
        private fun inferTableName(className: String, project: Project? = null): String {
            val strategy = effectiveTableSetting({ it.effectiveTableNameInference() }, project)
                ?: PluginSettings.INFERENCE_SNAKE
            return if (strategy == PluginSettings.INFERENCE_CAMEL) {
                className.replaceFirstChar { it.lowercaseChar() }
            } else {
                NamingStrategy.toSnakeCase(className)
            }
        }

        /**
         * T-M19.4-03-③：统一表名修饰链（全局语义——`@TableName` 显式表名同样被修饰，沿用 T-M16.2-03）：
         * `最终表名 = 添加前缀 + 移除后缀(移除前缀(表名)) + 添加后缀`；
         *  - 移除前缀/后缀：`;` 分隔，首个匹配（大小写敏感），不匹配则不修饰（M19.4-R2）；
         *  - 添加前缀/后缀：**单值**（用户反馈定稿，不再 `;` 分隔拼接；T-M16.2-03 原语义）。
         *
         * @param project 实体所属项目（决定读哪一份项目级设置；null 见 [effectiveTableSetting]）
         */
        private fun decorateTableName(raw: String, project: Project? = null): String {
            val removedPrefix = removeTablePrefixList().firstOrNull { raw.startsWith(it) }?.let { raw.removePrefix(it) } ?: raw
            val removedSuffix = removeTableSuffixList().firstOrNull { removedPrefix.endsWith(it) }?.let { removedPrefix.removeSuffix(it) } ?: removedPrefix
            return tablePrefix(project) + removedSuffix + tableSuffix(project)
        }

        /**
         * T-M19.4（用户实测：表名配置在部分路径不生效）：实体类不可解析的**兜底推断**统一入口——
         * 按配置推断策略（SNAKE/CAMEL）+ 统一修饰链，与 [resolve] 的推断路径语义一致。
         * WrapperLocator 文本兜底、MpjLambdaConverter 子表推断等全部走此函数（全局语义）。
         *
         * @param project 调用方已知的所属项目；调用方只有类名字符串时留空——
         *   留空沿改动前的「首个打开项目」取值（见 [effectiveTableSetting]），不引入新行为。
         */
        @JvmStatic
        @JvmOverloads
        fun inferDecoratedTableName(className: String, project: Project? = null): String =
            decorateTableName(inferTableName(className, project), project)

        /**
         * T-M19.4：`;` 分隔配置解析为列表（trim + 去空；设置服务不可用时兜底空列表——
         * 测试环境与未配置时零影响）。
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

        /** T-M16.2-03 全局语义：添加表前缀（**单值**，用户反馈定稿）。 */
        private fun tablePrefix(project: Project? = null): String =
            effectiveTableSetting({ it.effectiveTablePrefix() }, project)
                ?: runCatching { PluginSettings.getInstance().state.tablePrefix }.getOrDefault("")

        /** T-M16.2-03 全局语义：添加表后缀（**单值**，用户反馈定稿）。 */
        private fun tableSuffix(project: Project? = null): String =
            effectiveTableSetting({ it.effectiveTableSuffix() }, project)
                ?: runCatching { PluginSettings.getInstance().state.tableSuffix }.getOrDefault("")

        /** T-M19.4：移除表名前缀列表（`;` 分隔，最终表名首个匹配移除）。 */
        private fun removeTablePrefixList(): List<String> = listSetting { it.removeTablePrefix }

        /** T-M19.4：移除表名后缀列表（`;` 分隔，最终表名首个匹配移除）。 */
        private fun removeTableSuffixList(): List<String> = listSetting { it.removeTableSuffix }

        /** T-M19.4：忽略字段名集合（`;` 分隔，按 Java 字段名匹配）。 */
        private fun ignoredFieldNames(project: Project? = null): Set<String> =
            (effectiveTableSetting({ it.effectiveIgnoredFields() }, project)
                ?: runCatching { PluginSettings.getInstance().state.ignoredFields }.getOrDefault(""))
                .split(';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
