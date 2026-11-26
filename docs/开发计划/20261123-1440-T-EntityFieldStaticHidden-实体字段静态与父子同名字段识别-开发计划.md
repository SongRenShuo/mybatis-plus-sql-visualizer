# 实体字段静态与父子同名字段识别（T-EntityFieldStaticHidden）-开发计划

- **日期**：2026-11-23 14:40
- **编号声明**：本册新占 **D254**（D253 册 :4 声明「下一全新 D254」，grep 全 docs 除该指针外零占用）；下一全新 **D255 / V246 / O-164**。
- **来源**：用户 2026-11-23 反馈——「SQL 预览的时候，实体类当中有一些静态字段，还有一些父类和子类当中相同的字段都识别的有问题」。用户要求先分析原因、生成开发计划。
- **范围**：仅插件仓，`psi` 层字段收集（MP 轨 / JPA 轨）+ 参数类型展开 / XML 补全 / 反向列映射的同类面 + 定向测试。不改 SQL 渲染器、不改执行链、不改 UI 布局。

## 一、现状核查（源码级实证 + jar 取证，非推断）

| # | 事实 | 证据 |
|---|---|---|
| F1 | **MyBatis-Plus 官方字段收集语义**（真机权威）：`ReflectionKit.getFieldList` 先取子类 `getDeclaredFields()`，再沿父类链累加；`excludeOverrideSuperField` 把**子类放进 LinkedHashMap 占位，父类同名字段被排除**（`!fieldMap.containsKey(name)`）；**去重之后**才对 `fieldMap.values()` 过滤 `Modifier.isStatic` 与 `Modifier.isTransient` | `mybatis-plus-core-3.5.17-sources.jar` → `com/baomidou/mybatisplus/core/toolkit/ReflectionKit.java:127-173`（`getFieldList` / `excludeOverrideSuperField`） |
| F2 | 上述顺序是判据的一部分：子类 static 字段与父类实例字段同名时，**父类实例字段也一并被排除**（去重在前、过滤在后） | 同上 `getFieldList`：`excludeOverrideSuperField(...)` 返回值再 `.filter(!isStatic)` |
| F3 | **本仓 MP 轨未实现两条语义**：`EntityResolver.collectFieldsIncludingSuper` 只做 `superFields + clazz.fields`（父类在前 + 本类字段），既无同名去重，也无 static / transient 过滤 | `psi/EntityResolver.kt:154-158`（收集）、`:105`（调用） |
| F4 | MP 轨唯一的静态过滤是 **T-M42 只堵 `serialVersionUID` 一个名字**（归一化匹配），其它 static / static final 常量（`TABLE_NAME`、`LOGGER`、`DEFAULT_*`）全部当列 | `psi/EntityResolver.kt:185`；测试 `EntityResolverSerialVersionUidTest.kt` |
| F5 | **本仓 JPA 轨同样未实现**：`JpaEntityResolver.collectFieldsIncludingSuper` 与 MP 轨同构（`superFields + clazz.fields`），无去重、无 static 过滤 | `psi/jpa/JpaEntityResolver.kt:175-182`、`:112` |
| F6 | JPA 轨 `columns` 有 `distinctBy { columnName }`，**保留第一个**——因父类字段在前，父子同名同列时**留下父类那份，子类定义被丢弃**；`versionColumn` / `logicDeleteColumn` 同样 `firstOrNull` 取到父类那份 | `psi/jpa/JpaEntityResolver.kt:121`、`:131`、`:134-137` |
| F7 | MP 轨 `columns` **没有**列名去重 → 同名列出现两行（SELECT 全列展开、INSERT 模板、批量面板都按行渲染） | `psi/EntityResolver.kt:113-116`（`flatMap` 后仅 `sortedWith`，无 `distinct`） |
| F8 | **平台 `PsiClass.allFields` 语义**：反编译 `PsiClassImplUtil.getAllFields` → `getAllByMap` → `MemberCache` 构造 `myAllSupers`（BFS：本类 → 直接父类 → 更上层）→ 缓存值由 `myAllSupers.flatArray(type::getMembers)` 直接数组拼接，**不按名去重、不过滤 static** | 平台 `java-impl.jar`（2024.1）反编译 `com/intellij/psi/impl/PsiClassImplUtil` + `$MemberCache`；旁证：本仓三处调用点各自写 `.distinct()` 兜同名重复 |
| F9 | 所有走 `allFields` 的消费点继承 F8 缺陷：DTO 参数展开（`sink[key] =` 后写覆盖前写，**本类先、父类后 → 同名最终取父类的类型**，与「子类优先」相反）、resultMap/foreach 属性补全、Easy-Query 生成主键列 | `ui/XmlParamTypeResolver.kt:182-190`；`ui/inject/ResultMapColumnCompletionContributor.kt:109`；`ui/highlight/XmlCompletionSymbols.kt:126,168`；`converter/EasyQueryConverter.kt:770` |
| F10 | 嵌入对象展平（`flattenEmbeddableClass`）**已**过滤 static 与 serialVersionUID，但用 `allFields` 仍不去同名重 | `psi/jpa/JpaMetadataReader.kt:734-737` |
| F11 | **参数访问层语义与实体列语义不同**：MyBatis `Reflector.addFields` 只排除 `serialVersionUID` / `class` / `$` 前缀（`isValidPropertyName`），static 常量**可被读取**（static final 只不生成 setter，getter 仍注册）→ 参数键集**不能**照抄「排除 static」的实体列规则 | `mybatis-3.5.19-sources.jar` → `org/apache/ibatis/reflection/Reflector.java:238-256`（`addFields`）、`:275-277`（`isValidPropertyName`） |
| F12 | `@类@静态成员` 语法补全**故意**取 static 成员，修复时不可误伤 | `ui/highlight/XmlSqlCompletionContributor.kt:496-500`（`staticMembersOf`） |
| F13 | 缓存失效只看**当前实体文件** `modificationStamp`：父类文件被修改（加 `@TableLogic` / 改字段）后，子类解析结果仍命中旧缓存 | `psi/EntityResolver.kt:79`、`psi/jpa/JpaEntityResolver.kt:82`；`EntityResolverCacheTest.kt` 只覆盖本文件修改 |
| F14 | T-M23.3 角色分组 + 「父类字段在前、子类在后」顺序已被测试钉住（`EntityResolverColumnOrderTest`），修复不得改动无重名场景的输出顺序 | `psi/EntityResolver.kt:36-44`（类注释）、`EntityResolverColumnOrderTest.kt` |

### 根因链

1. **收集层**（F3/F5）把「类继承链字段」当成朴素拼接：同名字段不合并、static 不过滤，下游所有 `TableInfo.columns` 消费点（SELECT 全列展开、批量 INSERT/UPDATE 模板、参数对照、补全列集合、字段填充模拟、反向 `columnToField`）同时失真。
2. **取首项逻辑**（F6）在重复列表上做 `firstOrNull` / `distinctBy`：因父类排在前，**注解语义（@TableId/@TableLogic/@Version/@Column）一律取父类那份**，子类重写字段被架空。
3. **`allFields` 调用点**（F8/F9）相信平台会去重，实际不去重；配合 `sink[key] = ...` 的后写覆盖，同名类型取到父类（方向与真机相反）。
4. T-M42（F4）只堵了 `serialVersionUID` 这一个名字，把「静态字段不能映射为列」当成单个名字的特例，而非一条类级规则。

## 二、影响面矩阵（消费者清单）

| 消费者 | 读什么 | 现状症状 |
|---|---|---|
| `MpjQueryConverter` / `LambdaQueryConverter` / `QueryWrapperParser` 的 SELECT 全列展开 | `TableInfo.allColumnNames` | 静态常量为列、父子同名列重复 |
| `BatchOperationConverter` / `CrudSqlGenerator` / `JpaCrudOperationConverter` 的批量 INSERT/UPDATE | `TableInfo.columns` | 重复列、静态列进 INSERT；参数面板出现多余行 |
| 预览参数面板「字段填充模拟」 | `BatchModel.orderedColumns`（源自 `columns`） | 同上（列来源同源） |
| `ConvertSqlToJavaAction` / `SqlToJavaPanel` 反向 `columnToField` | `columns.associate { columnName to fieldName }` | 同名列后者覆盖（子类胜出属偶然）；`JavaCodeGenerator` 的 `!!` 缺列风险 |
| `GenerateCrudSqlFromEntityAction`（含 MCP `sqlv_crud_sql`） | `EntityResolver.resolve` | 生成带静态列的 DDL/DML 与 `getXxx` 调用 |
| XML resultMap / foreach 属性补全 | `allFields` + `.distinct()` | 补出 `serialVersionUID`、静态常量等非表属性 |
| `BaseMapper` 参数类型展开（引号/类型提示） | `XmlParamTypeResolver.collectFieldTypes` | 同名取父类类型（与真机子类优先相反）；`serialVersionUID` 进键集（真机拿不到） |
| `@类@静态成员` 补全 | `clazz.fields` 的 static | **正常，勿动**（F12） |
| `EasyQueryConverter` 生成主键列 | `allFields` | 静态/重名字段参与判定 |

## 三、修复方案

### M1 新增共用字段收集器，MP 轨接入（1 次提交）

新增 `psi/EntityFieldCollector.kt`（object，纯 PSI 逻辑，可独立测试）：

```kotlin
object EntityFieldCollector {
    fun collect(clazz: PsiClass): List<PsiField> {
        // 1) 链：本类 → 父类 → …（DFS 收集，seen 防环；接口不纳入——沿用既有语义）
        // 2) byName：子类先占位（putIfAbsent），父类同名者丢弃 —— 对齐 MP excludeOverrideSuperField
        // 3) 输出顺序：仍按「父类在前、子类在后」遍历链（F14 不变），只输出 byName 命中的那个对象
        //    （无重名场景顺序与现状逐项相同）
        // 4) 最后过滤 hasModifierProperty("static") 与 hasModifierProperty("transient")
        //    —— 过滤必须在去重之后（F2 顺序语义）
    }
}
```

- `EntityResolver.collectFieldsIncludingSuper` 改为委托 `EntityFieldCollector.collect`（保留原私有函数名或直接替换调用点 `:105`）。
- `EntityResolver.resolveColumns:185` 的 serialVersionUID 特判保留为第二道防线（不删除，避免行为回退），但不再是唯一的静态防护。
- 语义收益：子类 `@TableId` / `@TableLogic` / `@Version` / `@TableField` 重写字段生效（`:119`、`:125` 的 `firstOrNull` 现在只会看到子类那份）。

### M2 JPA 轨接入同一收集器（1 次提交）

- `JpaEntityResolver.collectFieldsIncludingSuper` 改为委托；`:112` 调用点不变。
- `:121` `distinctBy { columnName }` 保留（那是「不同字段映射同一列」的用户代码错误兜底，与「同名字段」是两个维度），但注释里补一句：同名字段已在收集层合并，此处只挡跨字段列名撞车。
- `:236` serialVersionUID 特判保留为第二道防线。
- 结构变更随之修正：`@Version` / `@LogicDelete`（`:131`、`:134`）取到子类重写字段；`associations`（`:125`）不再产出父类那份的关联元数据。
- Java `transient` 过滤与 Hibernate 语义一致（Hibernate 不持久化 `transient` 字段），但属行为收紧——**登记为可回退子项**：若定向测试发现既有用例依赖 transient 字段落列，回退该行并单独登记。

### M3 参数类型展开链（1 次提交）

- `XmlParamTypeResolver.collectFieldTypes:182-190` 不再用 `allFields`，改用 `EntityFieldCollector.collect`（同名合并方向变为子类优先）。
- **不排除 static**（F11：真机可读），但排除真机取不到的键：`serialVersionUID`、`class`、`$` 前缀（与 `Reflector.isValidPropertyName` 同口径），实现为一个小 `isReadablePropertyName(name)` 判定，KDoc 写明与 MyBatis 3.5.19 的对应关系。
- 递归嵌套 DTO 时同样走该收集器（避免子层再吃到 `allFields`）。

### M4 XML 补全链（1 次提交，与 M3 同批可）

- `ResultMapColumnCompletionContributor:109`、`XmlCompletionSymbols:126/168`：改用收集器（同名合并 + static 过滤），`.distinct()` 保留（名字层面仍可能有跨字段重复）。
- `XmlSqlCompletionContributor.staticMembersOf` **不动**（F12）。
- `EasyQueryConverter:770` 的 `allFields` 改用收集器（主键列判定不吃静态/重名）。

### M5 缓存父类失效（独立子项，默认做，可降级）

- 现状（F13）：`stamp` 只取当前文件。改为「当前文件 + 链上每个父类所在文件的 modificationStamp」的组合签名（实现一次父类链遍历，结果与 M1 的链可复用）。
- 命中路径会多一次父类链 PSI 访问，**性能影响需实测**（T-Perf 系列曾专门优化过缓存命中路径）。若实测命中成本超过阈值（建议：单次 < 30µs 才保留；否则降级为「父类文件 url+stamp 集合进缓存 key」的写法或单独排期）。
- 若不纳入本批，必须在计划册登记为独立待办，不得静默跳过。

## 四、待验证前提（实施者先证伪再动手）

| # | 前提 | 证伪方法 |
|---|---|---|
| P1 | 平台 `allFields` 在**目标平台**（IPGP 2024.3.7.1 / 用户 2026.2）同样不去重、不滤 static | 反编译目标平台 `java-impl.jar` 的 `PsiClassImplUtil$MemberCache` 复核；或 headless 写两条同名字段用例断言 `allFields.size == 2`。2024.1 反编译结论仅作起点 |
| P2 | 父类链遍历顺序在 PSI 中是稳定的（`superClass` 单一继承 + `seen` 防环足够），不存在匿名类/内部类中断 | 读收集器实现 + 用例：三层继承 + 中间层重名字段 |
| P3 | 「父类位置 + 子类字段对象」这一组合不破坏既有 `EntityResolverColumnOrderTest` 与角色分组 | 先跑既有测试类，再跑新增用例；顺序断言逐项比对 |
| P4 | `transient` 过滤不打破既有 JPA 用例 | 定向跑 `psi/jpa` 全包测试类；若红则回退该行并登记 |
| P5 | 预览面板「字段填充模拟」的列来自 `columns`，去重后不会丢失可编辑行 | 读 `BatchModel.orderedColumns` 构造链；headless 断言 fill 字段行数 |

## 五、测试与反向验证

### 新增/补充测试

1. `psi/EntityFieldCollectorTest.kt`（新，headless PSI）：
   - 父子同名字段：产出**一份**，且是子类对象（列名/类型按子类），位置在前者槽位；
   - 三层继承中间层重名；
   - static 字段（`private static final String TABLE = "t"`）不出现；
   - **子类 static 与父类实例同名** → 该名字整体不出现（F2 顺序语义，最关键的一条）；
   - `transient` 字段不出现（MP 轨）；
   - 无重名场景：输出顺序与既有「父先子后」逐项相同（回归锚）。
2. `psi/EntityResolverInheritanceFieldTest.kt`（新）：`@TableLogic` / `@TableId` / `@Version` 在子类重写字段上生效（父类那份不再抢占）。
3. `psi/jpa/JpaEntityResolverInheritanceFieldTest.kt`（新）：同名字段合并、static 排除、`@Column` 子类优先、`distinctBy` 行为不变。
4. `heavy/HeavyXmlParamTypeResolverTest.kt` 补用例：DTO 同名取子类类型；`serialVersionUID` / `class` 不进键集；其它 static 常量**仍进**（真机可读，防过度收紧）。
5. 补全：既有 resultMap / foreach 补全测试补断言——候选不含 `serialVersionUID`，`@类@` 语法的静态成员仍可补出。
6. `psi/EntityResolverCacheTest.kt` 补用例（M5 生效时）：改父类文件 → 子类 resolve 结果更新。

### 反向验证（用户 2026-11-21 定例：git 补丁方式）

- 提交后 `git diff` 出补丁，`git checkout` 还原 → `git apply` 只摘「同名去重」分支 → 同名字段用例必须变红；再摘「static 过滤」→ 静态用例必须变红。两次读数贴进实施记录，随后 checkout 还原复跑绿。
- 禁止「只往分支补一份」式的假反向（F2 顺序语义用例专门防这个：把过滤移到去重前，该用例必须变红）。

## 六、验收标准（用户真机，给编号步骤）

1. 准备 `BaseEntity`（`id`、`createTime`、`deleted`、`serialVersionUID`、`public static final String TABLE_NAME = "t_user"`）与 `User extends BaseEntity`（重写 `createTime` 为另一列名，例如 `@TableField("created_at")`；再放一个 `private static final Logger log`）。
2. 在 Mapper 里对 `User` 写 `selectList(null)`（或 `new LambdaQueryWrapper<User>()` 的空链）触发预览 → **SELECT 全列不含** `serial_version_uid` / `table_name` / `log`；`create_time` 只出现一次且按子类列名 `created_at`。
3. 触发 INSERT/批量预览 → 参数面板列行与 SQL 列**逐项一致**，无重复行、无静态列。
4. 把 `User` 的 `deleted` 重写为普通字段（去掉 `@TableLogic`）→ 预览不再自动追加 `AND deleted = 0`（子类语义生效）；删除重写字段（恢复继承）→ 逻辑删除条件恢复。
5. XML 里 resultMap 的 column 补全、foreach 的 item 属性补全：候选不出现 `serialVersionUID`；`@java.lang.Integer@MAX_VALUE` 这类 `@类@` 补全仍正常。
6. 修改父类文件（如给 `BaseEntity` 加 `@TableLogic` 字段）后不重启 IDE 再次预览 → 新语义立即生效（M5 生效时）。

## 七、待拍板事项（默认按推荐执行，用户可改）

1. **顺序策略**：推荐**方案 A**——保留既有「父类在前、子类在后」位置，同名时用子类字段对象替换父类槽位。理由：不改无重名场景输出（F14 零回归）；Hibernate 的 LinkedHashMap 覆盖语义恰好等价于 A。方案 B（完全对齐 MP 真机「子类字段在前」）会改动大批顺序断言与用户可见列序，不在本批。
2. **参数键集是否排除 static**：推荐**不排除**（仅排除 `serialVersionUID` / `class` / `$` 前缀），依据 F11 真机行为；若用户认为「参数区就不该出现静态常量」，则改为排除，但需同时接受与真机键集不一致。
3. **`transient` 是否同批**：推荐 MP 轨必做、JPA 轨做且可回退（见 M2）。
4. **M5 缓存父类失效**：推荐纳入本批；若实测命中成本超标，降级为独立待办并在册内留痕。

## 八、实施记录（实施期回填）

### 落地内容（提交 `8b2b15e13`，master）

- **M1**：新增 `psi/EntityFieldCollector.kt`（`collectColumns` / `collectProperties` 两面共用一条继承链+同名合并规则）；`EntityResolver.collectFieldsIncludingSuper` 改为委托。`resolveColumns` 内 serialVersionUID 特判保留为第二道防线。
- **M2**：`JpaEntityResolver.collectFieldsIncludingSuper` 改为委托；`distinctBy(columnName)` 保留并补注释（只挡不同字段撞同列名）；`JpaMetadataReader.flattenEmbeddableClass` 同步改走收集器。
- **M3**：`XmlParamTypeResolver.collectFieldTypes` 改用 `collectProperties`（同名取子类、保留 static 常量、排除 `serialVersionUID`/`class`/`$` 前缀）。
- **M4**：`ResultMapColumnCompletionContributor`、`XmlCompletionSymbols`（resultMap 属性 + foreach item）、`EasyQueryConverter.generatedKeyColumns` 改走收集器；`XmlSqlCompletionContributor.staticMembersOf`（`@类@` 语法）未动。
- **M5**（按用户令纳入本批）：新增 `psi/EntityInheritanceStamp.kt`（`inheritanceChainStamp`，链上父类文件 modificationStamp 滚动签名），`EntityResolver` / `JpaEntityResolver` 的缓存条目与命中判定都带上它。
- **transient**（按用户令同批）：MP 轨与 JPA 轨都排除 Java `transient`；定向全绿，未触发回退。
- 用户可感说明已回填 `CHANGELOG.md` `[Unreleased] / Fixed`。
- 真机复现语料：已移到 demo 仓（`mybatis-plus-sql-visualizer-demo`）——
  `mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/repro/d254/`（父类 / 子类 / Mapper / 触发点四个类）
  + `src/main/resources/repro-d254/D254UserMapper.xml` + `mybatis-plus-demo/D254-REPRO.md`（五个触发点与反向对照判据）。
  纯 PSI 语料：不进 `@MapperScan` 的 mapper 包、XML 不在 `mapper-locations` 目录，不注册 Bean、不连库、不影响 demo 启动。

### 定向测试读数

- **152 例 0 失败**（17 类）：新增 4 类（收集器 7、MP 继承 4、JPA 继承 3、父类缓存失效 Heavy 2）+ `HeavyXmlParamTypeResolverTest` 新增 1 例（17 例全绿）+ 既有 13 类回归（Cache 8 / ColumnOrder 5 / Comment 17 / Embedded 4 / SerialVersionUid 3 / TableConfig 12 / JpaEntityResolver 26 / JpaJoinOn 17 / JpaEmbeddedNaming 8 / ResultMapColumns 6 / ResultMapColumnsGap 4 / XmlSymbolNameIndexCache 8 / SqlPreviewServiceConfig 18）。

### 反向验证（git 还原 + 探针，三次）

1. **摘掉 static/transient 过滤**（`collectColumns` 判据恒 true）→ Light 2 类恰 3 红：`[id, TABLE_NAME, LOG_TAG, runtimeCache, name]`、`[id, token, name]`（子类 static 遮蔽父类实例字段的顺序语义用例同时红）。还原复绿。
2. **摘掉同名去重**（输出条件恒 true）→ 收集器 4 红（`[code, code, label]`、`[token, id, name]` 等）+ MP 继承 3 红（`@TableLogic` / `@Version` 又取父类）+ JPA 继承 2 红（`[id, create_time, created_at]` 重复列）。还原复绿。
3. **摘掉 `inheritanceStamp` 命中判据**（两轨 `entry.stamp == stamp` 单条件）→ Heavy 缓存 2 红：父类新增 `@TableLogic` / `@Version` 后 `logicDeleteColumn` / `versionColumn` 仍为 null。还原复绿。

### 装置坑（后续写同类 Heavy 测试直接抄结论）

- Heavy 沙盒里 `createTestProjectStructure()` 每次调用会新建结构——父类与子类必须复用**同一个** root（测试内缓存）。
- 目录必须与 `package` 声明一致（`src/com/example/X.java`）：索引版 `findClass` 能命中，但 `extends` 引用解析会因包目录不匹配而恒 null。
- `src` 需显式注册为模块 source root（`ModuleRootModificationUtil.updateModel` + `entry.addSourceFolder`），否则同样 `superClass == null`。
- 重写已存在文件必须走 **Document** 路径（`document.setText` + `commitDocument`）；`VfsUtil.saveText` 直写 VFS 时 PSI 与 modificationStamp 不刷新，缓存失效判据读不到变化。

### 已审计未改（同族面，留痕防重复立项）

- `JpaJoinOnResolver` 的 `allFields.firstOrNull { name == ... }`：`allFields` 顺序为本类先，取首项方向与「子类优先」一致；静态关联字段无现实形态，不改。
- `JpaMetadataReader` 生成器注解扫描（`:346`）与「疑似实体」判定（`:531`）：同名列由 `distinctBy(name)` 兜底，注解收集对重复不敏感，不改。
- `XmlSqlCompletionContributor.staticMembersOf`：`@类@静态成员` 是 MyBatis 3.5.10+ 静态引用语法，**必须**取 static，刻意保留。

