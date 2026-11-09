# T-Ibatis2Support：iBatis 2.x 静态渲染与编辑器能力全面支持（2026-11-09）

- 立项：2026-11-09 11:30
- 状态：**实施完成（2026-11-09，master 线；券 0 / A / B / D / E / F / H 全部落地，最终合并态定向 397 类 3906 例 0 失败）**——决策点已全部拍板（§七之二），实际派工与权威读数见 §8.2 / §8.3；**剩余券 G（类型映射）与真机 GUI 验收（交用户）**；全量与门禁交 CI
- 目标分支：待定（改 `plugin.xml` EP、新增注入面与设置项，属大改动，倾向 dev 线先行，见 §七 D-1）
- 基线：master `b3b5c556`
- 来源：
  1. 用户要求「目前插件不支持 ibatis，现在需要全面支持」；要求先并行调研（调研/现有功能/接入方式），再由主控出开发计划；
  2. 用户补充的现场反馈：**「ibatis 文件左侧点击 SQL 预览图标打开预览窗口速度很慢」**，疑与未适配有关（第四路专查，见 §三 券 0）
- 输入材料（非仓库产物）：`F:\code\Java\Gitee\SongRenShuo\_ibatis-research\`
  - `jars/ibatis-sqlmap-2.3.4.726.jar`（Maven Central `org.apache.ibatis:ibatis-sqlmap:2.3.4.726`，384,363 B）+ `-sources.jar`（202 个 `.java`）
  - `jars/ibatis-sqlmap-2.3.0.jar`（383,531 B）、`dist/v217/lib/ibatis-sqlmap-2.jar`（2.1.7）
  - `dtd/sql-map-2.dtd`、`dtd/.../sql-map-config-2.dtd`（jar 内权威副本）
  - `probe/Probe.java`（JDK8 + 仅 `ibatis-sqlmap-2.3.4.726.jar` classpath 的运行时探针，实测通过）
  - `dist/iBATIS-SqlMaps-2_en.pdf`（官方开发者指南，`pdftotext` 2161 行）
- 红线（本册全程）：不改 `update/update.json`；不 push、不合流、不建 tag；`src/main` 在用户点名开工前零改动

---

## 零、证据分级口径

本册所有结论按三档标注，**不混写**：

| 档 | 含义 | 本册占比 |
|---|---|---|
| **实测** | jar 字节层（zipfile 枚举 / `javap`）、jar 内源码（`-sources.jar` 逐行）、运行时探针（JDK8 真执行）一手读数，命令与输出可复现 | §一 全部 |
| **文档推断** | 从官方 DTD / 指南 PDF 原文或源码推出的**语义**判断，未被本项目渲染器验证 | §一 少部分（已逐条标出） |
| **未验证** | 无真机 IDE、无 iBatis 用户工程样本，只登记问题不给答案 | §二 的「慢预览」成因、§四 全部 P 项 |

**本册不含任何「已支持」宣称**——现状是「静态面零覆盖」，见 §二。

---

## 一、iBatis 2.x 技术事实

> 全部来自实测（jar 内源码 / `javap` / 运行时探针），主控已抽查复核 4 处（`<sql>/<include>` 存在性、OGNL 缺失、`prepend` 不补空格、占位符两分支分派），逐条吻合。

### 1.1 版本、坐标与制品形态

三个制品时代（`archive.apache.org/dist/ibatis/binaries/ibatis.java/` 实测目录）：

| 版本 | 制品 | jar 拆分 | `Sql` 接口 scope 参数 |
|---|---|---|---|
| 2.1.5 / 2.1.6 / **2.1.7.597** | `iBATIS_DBL-2.1.x.zip` | **拆三个**：`ibatis-sqlmap-2.jar`(304KB) + `ibatis-common-2.jar`(85KB) + `ibatis-dao-2.jar` | `RequestScope` |
| 2.2.0.638 | `iBATIS_DBL-2.2.0.638.zip` | 同上 | `RequestScope` |
| 2.3.0.677 | `ibatis-2.3.0.677.zip` | **单 jar**（383,531 B，`com.ibatis.common.*` 已并入） | `RequestScope` |
| 2.3.1.710 ~ **2.3.4.726** | `ibatis-2.3.x.NNN.zip` | 单 jar | **`StatementScope`（2.3.1 起）** |

Maven Central 只有两个 2.x 坐标：`org.apache.ibatis:ibatis-sqlmap:2.3.0`（383,531 B，与 `ibatis-2.3.0.677.jar` 字节数一致）与 `org.apache.ibatis:ibatis-sqlmap:2.3.4.726`（384,363 B）。

- `org.apache.ibatis:ibatis-common` **在 Central 不存在**（404）。2.3.x 单 jar 内已含 54 个 `com/ibatis/common/**` 条目，无需额外依赖。
- 老项目的 `ibatis-sqlmap-2.jar` + `ibatis-common-2.jar` 是 2.1/2.2 时代的裸 jar。
- `org.apache.ibatis:ibatis-core:3.0` 是 MyBatis 3.0 的 `org.apache.ibatis.*` 包，与 iBatis 2 的 `com.ibatis.common.*` 无关，**不是** iBatis 2 的别名。

**对计划的意义**：测试面保真对照只需引入 `ibatis-sqlmap:2.3.4.726` 一个 jar（详见 §五）。

### 1.2 DTD 变化（逐属性 diff 2.1.7 → 2.3.4.726）

```
parameter : +resultMap +numericScale
result    : +notNullColumn            ← 2.3.0 无，仅 2.3.4.726 有
statement/select/insert/update/delete/procedure : +timeout
selectKey : +type (pre|post)          ← 2.3.0 已含；2.1.7 无
```

`remapResults`、`removeFirstPrepend`、`<sql>/<include>`、`<isNotParameterPresent>`、`<isNotPropertyAvailable>` **在 2.1.7 已全部存在**。

**DTD 双源分叉（实测）**：网站 `https://ibatis.apache.org/dtd/sql-map-2.dtd` 与 jar 内副本**逐字节相同**；但 `sql-map-config-2.dtd` 网站版是 `sqlMapConfig (... sqlMap*)+`（零个也合法）+ 一条「2.3.1 起 `maxSessions`/`maxTransactions`/`maxRequests` 不再支持」注释，jar 内是 `sqlMap+`。**运行时以 jar 内为准**——`SqlMapClasspathEntityResolver` 把 4 个 publicId/systemId（含 `-//iBATIS.com//DTD SQL Map 2.0//EN`）全部映射到 classpath 副本，**不联网取 DTD**。

**对计划的意义**：插件内置 DTD 副本时必须以 **jar 内版本**为准；把网站版当权威会在 `<sqlMapConfig>` 边界上判错。

### 1.3 核心 API 面与「运行时取最终 SQL + 参数」最小配方

```
public interface com.ibatis.sqlmap.client.SqlMapClient extends SqlMapExecutor, SqlMapTransactionManager
public class com.ibatis.sqlmap.client.SqlMapClientBuilder
  static SqlMapClient buildSqlMapClient(Reader | Reader,Properties | InputStream | InputStream,Properties)
public class com.ibatis.sqlmap.engine.impl.SqlMapClientImpl
  public MappedStatement getMappedStatement(String);  public SqlMapExecutorDelegate getDelegate();
public class com.ibatis.sqlmap.engine.impl.SqlMapExecutorDelegate
  public Iterator getMappedStatementNames();  public MappedStatement getMappedStatement(String);
  public ParameterMap getParameterMap(String);  public ResultMap getResultMap(String);
```

`Sql` 接口（2.3.4.726）：

```
public interface com.ibatis.sqlmap.engine.mapping.sql.Sql {
  String       getSql(StatementScope, Object parameterObject);
  ParameterMap getParameterMap(StatementScope, Object);
  ResultMap    getResultMap(StatementScope, Object);
  void         cleanup(StatementScope);
}
```

2.1.7 / 2.3.0 的同一接口把 `StatementScope` 换成 `RequestScope`（`javap` 实测）。

四个实现（**注意子包名**，任务书里写错过）：

| 类 | 何时产生 | `getSql` 行为 | `getParameterMap` |
|---|---|---|---|
| `mapping.sql.stat.StaticSql` | 无动态标签且无 `$` | 构造时 `\r`/`\n` 已替换为空格后的定值 | statement 自带的 |
| `mapping.sql.simple.SimpleDynamicSql` | 无动态标签但有 `$` | 每次重跑 `processDynamicElements` 做 `$x$` 替换 | 同上 |
| `mapping.sql.dynamic.DynamicSql` | 有任一动态标签 | 跑 SqlTagHandler 树拼出，缓存在 `statementScope.dynamicSql` | 缓存于 `statementScope.dynamicParameterMap` |
| `mapping.sql.raw.RawSql` | 仅 `XMLSqlSource` 中间产物 | 原样 | **直接抛 RuntimeException** |

`RawSql` 不可执行，`MappedStatementConfig.setSqlForStatement()` 会转成前三者，故运行时只可能是前三者之一。

**无反射、无数据库、无事务的取 SQL 配方**（`MappedStatement.initRequest` 是 public）：

```java
SessionScope ss = new SessionScope(); ss.setSqlMapClient(client);
StatementScope scope = new StatementScope(ss);
ms.initRequest(scope);                                       // 必须，否则静态语句取不到 parameterMap
Sql sql = ms.getSql();
ParameterMap pm = sql.getParameterMap(scope, param);          // DynamicSql 在此完成一遍 tag 树求值
String sqlText = sql.getSql(scope, param);                    // "... WHERE A = ? AND B = ?"
Object[] values = pm.getParameterObjectValues(scope, param);  // 原始 Java 对象，非 JDBC 字面量
sql.cleanup(scope);                                           // 复用同一 scope 时必须调
```

探针实测输出（`_ibatis-research/probe/Probe.java`，仅 iBatis jar 在 classpath）：

```
===== demo.dyn  param={orgName=Org, flag=1, orgId=A001}
  Sql impl  : com.ibatis.sqlmap.engine.mapping.sql.dynamic.DynamicSql
  SQL       : SELECT * FROM T_ORG WHERE ORG_ID = ? ANDORG_NAME LIKE '%Org%' ANDFLAG = ? ORDER BY ORG_ID
  mappings  : 2   [0] prop=orgId  [1] prop=flag
  values    : [A001 <String>, 1 <String>]
===== demo.iter  param={ids=[X1, X2, X3]}
  SQL       : SELECT * FROM T_ORG WHERE ( ORG_ID = ? OR ORG_ID = ? OR ORG_ID = ? )
  mappings  : prop=ids[0] / ids[1] / ids[2]
```

注意 `ANDORG_NAME`——见 §1.6。

### 1.4 参数摊平链路

`ParameterMap.getParameterObjectValues` 只做 `dataExchange.getData(scope, this, obj)`；`DataExchange` 按 `parameterClass` 分派：

- `ComplexDataExchange`（JavaBean / Map）：`parameterObject == null` → **`new Object[1]`**（不是空数组）；`typeHandlerFactory.hasTypeHandler(clazz)`（String/Integer/Date 等标量）→ **每个 mapping 都填同一个 parameterObject**；否则 `PROBE.getObject(parameterObject, mappings[i].getPropertyName())`。
- `ListDataExchange`：按 `.` 拆属性路径 + `[n]` 下标（`propName.split("\\.")`）。
- `NoParameterMap`：恒返回空数组。

**返回原始 Java 对象**；JDBC 字面量化在 `ParameterMap.setParameters(...)` → `setParameter(...)`：

```java
Object value = parameters[i];
String nullValueString = mapping.getNullValue();
if (nullValueString != null) {
  if (mapping.getTypeHandler().equals(value, nullValueString)) value = null;   // 哨兵 → NULL
}
```

探针实测：`orgName=N/A` → `bind setNull(2, 12)`（12 = `Types.VARCHAR`）；`orgName=other` → `bind setString(2, other)`。

**`nullValue` 的语义是「参数值等于该字符串即视为 SQL NULL」，不是「值为 null 时填这个字面量」**——极易搞反。

### 1.5 XML 语法全清单（`sql-map-2.dtd` 原文）

`<sqlMapConfig>`：

```
<!ELEMENT sqlMapConfig (properties?, settings?, resultObjectFactory?, typeAlias*, typeHandler*,
                        transactionManager?, sqlMap+)+>
<!ELEMENT settings EMPTY>   <!ATTLIST settings classInfoCacheEnabled|lazyLoadingEnabled|statementCachingEnabled
   |cacheModelsEnabled|enhancementEnabled|errorTracingEnabled|useStatementNamespaces|useColumnLabel
   |forceMultipleResultSetSupport (true|false) #IMPLIED
   maxSessions|maxTransactions|maxRequests|defaultStatementTimeout CDATA #IMPLIED>
<!ELEMENT sqlMap EMPTY>     <!ATTLIST sqlMap resource CDATA #IMPLIED  url CDATA #IMPLIED>
<!ELEMENT typeAlias EMPTY>  <!ATTLIST typeAlias alias CDATA #REQUIRED type CDATA #REQUIRED>
```

`useStatementNamespaces` 默认 **false**（`XmlParserState.useStatementNamespaces = false`）。为 true 时 `applyNamespace(id)` 生效，且 **id 里已含 `.` 就不再加前缀**；而 `parameterMap`/`cacheModel`/`resultMap`/`extends` 属性**无条件**走 `applyNamespace`，statement 的 `id` 受开关控制（`SqlStatementParser.java:37`）。

`<sqlMap>` 根：`(typeAlias* | cacheModel* | resultMap* | parameterMap* | sql* | statement* | insert* | update* | delete* | select* | procedure*)+`，属性仅 `xmlns:fo`（#IMPLIED）与 `namespace`（#IMPLIED）——**namespace 可选**。

元素 → 实现类：`statement`→`MappedStatement`、`insert`→`InsertStatement`、`update`→`UpdateStatement`、`delete`→`DeleteStatement`、`select`→`SelectStatement`、`procedure`→`ProcedureStatement`。

`<parameterMap>` / `<parameter>`：

```
<!ELEMENT parameterMap (parameter+)>
<!ATTLIST parameterMap id CDATA #REQUIRED  class CDATA #REQUIRED>     ← class 是 #REQUIRED（文档说可选，DTD 不是）
<!ATTLIST parameter property CDATA #REQUIRED javaType #IMPLIED jdbcType #IMPLIED typeName #IMPLIED
                    nullValue #IMPLIED mode (IN|OUT|INOUT) #IMPLIED typeHandler #IMPLIED
                    resultMap #IMPLIED numericScale #IMPLIED>
```

`mode` 只在 `OUT`/`INOUT` 时回写参数对象。

`<resultMap>` / `<result>`：`result` 是 **EMPTY**（不能有子元素）；N+1 是**父 `groupBy` + 嵌套 `<result ... resultMap="...">`** 组合，不是「嵌套 `<result>`」。

语句元素属性：

- `select` / `statement` / `procedure`：`id`(#REQUIRED)、`parameterMap`、`parameterClass`、`resultMap`、`resultClass`、`cacheModel`、`resultSetType (FORWARD_ONLY|SCROLL_INSENSITIVE|SCROLL_SENSITIVE)`、`fetchSize`、`xmlResultName`、`remapResults (true|false)`、`timeout`
- `insert` / `update` / `delete`：只有 `id`、`parameterMap`、`parameterClass`、`timeout`（**无 resultMap/resultClass**）
- `procedure` 额外少 `resultSetType`
- `resultMap` / `resultClass` 支持**空格分隔多值**

`<sql>` / `<include>`：**存在**（2.1.7 起）。

```
<!ELEMENT sql (#PCDATA | include | dynamic | iterate | isParameterPresent | isNotParameterPresent | isEmpty
             | isNotEmpty | isNotNull | isNull | isNotEqual | isEqual | isGreaterThan | isGreaterEqual
             | isLessThan | isLessEqual | isPropertyAvailable | isNotPropertyAvailable)*>
<!ATTLIST sql id CDATA #REQUIRED>
<!ELEMENT include EMPTY>  <!ATTLIST include refid CDATA #REQUIRED>
```

`XMLSqlSource.parseDynamicTags` 对 `include`：先按 `refid` 找，找不到再 `applyNamespace(refid)` 找，都找不到抛 `RuntimeException`。**片段在配置期内联展开**，片段里的 `#x#` 与主体同批解析。

`<selectKey>`：`(#PCDATA | include)*`，属性 `resultClass` / `keyProperty` / `type (pre|post)`。执行顺序默认由**位置**推断（在 insert SQL 前则 pre），`type` 显式覆盖位置；只允许挂在 `<insert>` 下，生成的 statement id 是 `<insertId>-SelectKey`。

### 1.6 动态标签语义（与 MyBatis 3 机制完全不同）

处理器注册表 `SqlTagHandlerFactory` 共 16 个：`isEmpty isEqual isGreaterEqual isGreaterThan isLessEqual isLessThan isNotEmpty isNotEqual isNotNull isNotParameterPresent isNotPropertyAvailable isNull isParameterPresent isPropertyAvailable iterate dynamic`。

**求值机制＝`Probe` + `ConditionalTagHandler`，无表达式语言**（全树 grep `ognl` 零命中，jar 与源码 jar 内 ognl 条目均为 0）：

```java
Object value1 = PROBE.getObject(parameterObject, prop);        // prop == null 时取整个 parameterObject
Class  type   = PROBE.getPropertyTypeForGetter(parameterObject, prop);
if (comparePropertyName != null) return compareValues(type, value1, PROBE.getObject(parameterObject, comparePropertyName));
else if (compareValue != null)   return compareValues(type, value1, compareValue);
else throw new RuntimeException("Error comparing in conditional fragment.  Uknown 'compare to' values.");
```

`compareValues` 把 `compareValue` 按 `value1` 的声明类型转换（Byte/Short/Character/Integer/Long/Float/Double/Boolean/`Date`(掩码 `yyyy/MM/dd hh:mm:ss`)/BigInteger/BigDecimal），非 Comparable 则双转 String；返回 `long`，`NOT_COMPARABLE = Long.MIN_VALUE`。判定符号：`isEqual`→`==0`、`isNotEqual`→`!=0`、`isGreaterThan`→`>0`、`isGreaterEqual`→`>=0`、`isLessThan`→`<0`、`isLessEqual`→`<=0`。

`<isNull>/<isNotNull>/<isEmpty>/<isNotEmpty>` **不参与 compare**，各自实现 `isCondition`：

- `IsEmptyTagHandler`：`parameterObject == null` → true；否则取 `prop` 值（prop 空则用 parameterObject）；Collection → `size()<1`；数组 → `Array.getLength()==0`；其他 → `value==null || String.valueOf(value).equals("")`
- `IsPropertyAvailableTagHandler`：`parameterObject instanceof Map` → `containsKey(property)`（**刻意绕开 Probe，因 `Probe` 对 Map 恒返回 true**）；否则拆「最后一段点号」后 `PROBE.hasReadableProperty(...)`
- `IsParameterPresentTagHandler`：`parameterObject != null`

**`<dynamic>` 的 prepend 消费机制**：`DynamicTagHandler` 只做 `ctx.pushRemoveFirstPrependMarker(tag); return INCLUDE_BODY;`。真正的「父 prepend 吃掉子 prepend」在 `SqlTagContext` 的 marker 栈 + `BaseTagHandler.doPrepend`：

```java
if (tag.isPrependAvailable()) {
  if (tag.getParent() != null && ctx.peekRemoveFirstPrependMarker(tag)) {
    ctx.disableRemoveFirstPrependMarker();          // 子 prepend 被吃掉
  } else {
    bodyContent.insert(0, tag.getPrependAttr());     // 否则拼上去——不补空格
  }
}
```

`pushRemoveFirstPrependMarker` 分支：①`DynamicTagHandler` 有 `prepend` → marker=true（**`<dynamic>` 只要写了 prepend 就默认吃掉第一个子 prepend**）；②标签自身 `removeFirstPrepend="true"|"iterate"` → true；③标签无 prepend 且父级 `removeFirstPrepend` 为 true/iterate → true；④其余 false。注意 `disable`/`peek` 都操作 `stack.get(1)`（**父层**，实现刻意如此）。

**`<iterate>`**：`IterateContext` 接受 `Collection`/`Iterator`/数组，否则抛 `SqlMapException`。`doEndFragment` 规则：第一个产出内容者 `insert(0, open)` 并启用 prepend；非第一个 `insert(0, conjunction)`；最后一个且有过内容 `append(close)`；`doPrepend` 只在 `isPrependEnabled()` 时执行一次。`#item[]#` 的 `[]` 是**下标占位符**，由 `iteratePropertyReplace` 逐次替换为 `ids[0]`/`ids[1]`/`ids[2]`。嵌套 iterate 用 `property="orConditions[].conditions"`。`removeFirstPrepend="iterate"` 表示每次迭代都重新剥 prepend（2.2.0+）。

**`postParseRequired`**：处于 iterate 内的文本节点，`#`/`$` 只存原文，留给每次迭代重新解析。即 **`#`/`$` 解析时机分两档：静态语句配置期一次，动态语句每次执行时重算**。

### 1.7 占位符语法（源码为准）

常量：`PARAMETER_TOKEN = "#"`、`PARAM_DELIM = ":"`。分派（`InlineParameterMapParser`）：

```java
if (token.indexOf(PARAM_DELIM) > -1) mapping = oldParseMapping(token, parameterClass, typeHandlerFactory);
else                                mapping = newParseMapping(token, parameterClass, typeHandlerFactory);
```

- **高级语法（无冒号走这条）**：`#prop,javaType=?,jdbcType=?,mode=?,nullValue=?,handler=?,numericScale=?#`——`StringTokenizer(token, "=,", false)`，首 token 是 propertyName，其后成对 field/value；未知 field 抛 `SqlMapException`
- **简单语法（含冒号走这条）**，按 `StringTokenizer(token, ":", true)` 的 **token 数**分档：

| token 数 | 形态 | 结果 |
|---|---|---|
| 3 | `name:jdbcType` | `propertyName=name, jdbcTypeName=type` |
| ≥5 | `name:jdbcType:nullValue`（nullValue 内可再含 `:`，多余的被拼回） | 三段全设 |
| 其他 | — | 抛 `SqlMapException("Incorrect inline parameter map format: ...")` |

**所以 `#propertyName:nullValue#` 会被当成 `jdbcType=nullValue`，不是 nullValue。** 官方指南同结论：「you cannot specify the null value replacement without also specifying the type」。

- **转义**：`##` → 字面量 `#`；未闭合 `#` 抛 `SqlMapException("Unterminated inline parameter ...")`
- **`$x$`（`SimpleDynamicSql`）**：`value = PROBE.getObject(parameterObject, token)`（标量参数走 `hasTypeHandler` 分支取整个对象）；`value != null` 才 `append(String.valueOf(value))`，**null 时静默丢弃**。`$$` → 字面量 `$`。`$` **永远在动态标签之后才替换**
- **`#value#` 里的 `value` 不是保留字**——`Probe` 对标量参数走 `hasTypeHandler` 分支，属性名不参与取值

**必须复刻的坑**：`MappedStatementConfig.applyInlineParameterMap` 有 `if (parameterMap == null)` 守卫——**声明了 `parameterMap` 的语句，`#...#` 原样保留、不进 `?`**（探针实测：`<select parameterMap="pm1">` 体内 `#orgId#` 原样留下）。这类语句真正生效的是手写 `?`。渲染器必须按「有 parameterMap ⇒ `#` 不转 `?`」处理，否则会把配置错误渲染成合法 SQL。

### 1.8 与 MyBatis 3 的差异（只列影响静态渲染的项）

| 维度 | iBatis 2.x | MyBatis 3 |
|---|---|---|
| 命名空间 | 可选；`useStatementNamespaces=false` 默认 | 必填，id 恒为 `namespace.id` |
| 占位符 | `#prop#`、`#prop:jdbcType#`、`#prop:jdbcType:nullValue#`、`#prop,jdbcType=?#`、`$prop$` | `#{prop}`、`#{prop,jdbcType=…}`、`${prop}` |
| 转义字面量 | `##` / `$$` | 无 |
| 条件标签 | 14 个 `<isXxx>` | `<if test="OGNL">` / `<choose>` |
| 表达式 | **无表达式语言**，纯 `property` + `compareProperty`/`compareValue` | OGNL |
| 循环 | `<iterate property open close conjunction>` + `#x[]#` | `<foreach collection item index>` + `#{item}` |
| 容器 | `<dynamic prepend open close>` | 无需容器 |
| prepend 覆盖 | 父 prepend 吃掉第一个子 prepend（marker 栈），**不补空格** | `<where>/<set>/<trim>` 显式处理 |
| `<sql>/<include>` | **有**，namespace aware，配置期内联 | 有，运行期引用 |
| 参数摊平 | `DataExchange` 子类按 `parameterClass` 分派 | `ParamNameResolver` + `TypeHandlerRegistry` |
| 主键 | `<selectKey type="pre\|post">`，位置或 type 决定顺序 | `useGeneratedKeys`/`keyProperty` 为主 |
| `nullValue` | `#p:JDBC:nullValue#` 或 `<parameter nullValue>`，**哨兵→NULL** | 无对应概念 |
| 结果 N+1 | `groupBy` + 嵌套 `resultMap` | `<collection>`/`<association>` |
| 求值时机 | 静态语句配置期一次；动态语句每次执行重算 | 每次执行 OGNL |

### 1.9 官方资料位置（全部实测可访问）

| 资源 | 位置 |
|---|---|
| DTD（在线） | `https://ibatis.apache.org/dtd/sql-map-2.dtd`（25464 B，与 2.3.4.726 jar 内逐字节相同）、`…/sql-map-config-2.dtd`（11542 B，比 jar 内新） |
| DTD（离线权威） | jar 内 `com/ibatis/sqlmap/engine/builder/xml/{sql-map-2.dtd, sql-map-config-2.dtd}`，由 `SqlMapClasspathEntityResolver` 强制加载 |
| 官方开发者指南 | `https://ibatis.apache.org/docs/java/pdf/iBATIS-SqlMaps-2_en.pdf`（644,621 B；已落 `_ibatis-research/dist/`）。`<sql>/<include>` p19-20、inline parameter p28-30、动态标签 p46-51、`$` p51 |
| 发行包归档 | `https://archive.apache.org/dist/ibatis/binaries/ibatis.java/`（2.1.5.582 ~ 2.3.4.726 全量） |
| Maven Central | `https://repo1.maven.org/maven2/org/apache/ibatis/ibatis-sqlmap/`（仅 2.3.0、2.3.4.726） |
| GitHub `mybatis/ibatis-2` | **未验证**——`api.github.com` 匿名额度被共享出口 IP 耗尽（403 rate limit）。源码已由 Central `-sources.jar`（202 `.java`）取得，等价性更高 |

### 1.10 给渲染器的实现要点

1. **纯静态解析为主，不依赖引擎运行时**：IDE 里没有运行时参数对象。引擎可用于「无参基线渲染」（`parameterObject = null`，走 `<isNull>`/`<isEmpty>` 分支），但 `ComplexDataExchange` 对 null 返回 `new Object[1]` 而非空数组，须单独处理。
2. **`#`/`$` 解析严格照 `InlineParameterMapParser` 两分支 + `StringTokenizer` 语义**，含 `##`/`$$` 转义、`nullValue` 可含 `:`、两段式第二段是 jdbcType。
3. **`prepend`/`conjunction`/`open`/`close` 逐字拼接、不补空格**——渲染结果必须保留原空白，**禁止美化重排**（与现有 XML 链的输出习惯冲突）。
4. **`removeFirstPrepend` marker 栈**是 `<dynamic>` 吃掉子 prepend 的唯一机制，且操作「父层」；`<iterate removeFirstPrepend="iterate">` 每次迭代重置。
5. **声明 `parameterMap` 的语句 `#` 不转 `?`**，必须单独分支（否则把配置错误渲染成合法 SQL）。
6. **`nullValue` 是哨兵→NULL**：参数值等于 `nullValue` 时输出 `NULL`，不是把 `nullValue` 当字面量填进去。
7. **版本分支**：`RequestScope`(≤2.3.0) vs `StatementScope`(≥2.3.1)；`notNullColumn`（仅 2.3.4.726）；`selectKey type`、嵌套 `<iterate>`、`removeFirstPrepend="iterate"`（≥2.2.0）。若不支持 2.3.1 以下，可只按 `StatementScope` 写（但纯静态解析本来就用不到 scope 类，此项只影响「引擎驱动对照」）。
8. **`<sql>` 片段与主体同批解析**，`include` 在配置期内联；`refid` 先原样找、再 `applyNamespace` 找。

---

## 二、插件现状与缺口

### 2.1 已有基线：运行期捕获通道（**不欠账**）

2026-11-06 的 T-IbatisPanwei 券 A/B 已落地并合入 master（`6a5cef96`），本次不重复立项：

| 已有能力 | 落点 |
|---|---|
| iBatis 控制台日志三行组识别（`{pstm-N}`/`{conn-N}`） | `log/MyBatisLogParser.kt:225-300`（`feedIbatis` 分发 + `onIbatisExecuting/Parameters/Types`） |
| 参数方括号剥离 + `Types:` 按 pstm id 并入 | 同上 `parseIbatisParams` / `applyIbatisTypes` |
| 守卫用例 | `src/test/kotlin/.../log/IbatisLogParseTest.kt`（14,164 B） |
| 增强捕获（agent 织入 JDBC） | 对任何走 JDBC 的 ORM 天然生效（`sub-projects/sql-visualizer-agent/.../WeaveSpecs.java` + `CatchAllMatcher.java`） |

已知边界（登记在类注释）：元素内逗号/嵌套方括号按逗号切分（`List.toString` 不可逆）；iBatis 批处理一期不支持。

### 2.2 静态面缺口（本次主战场）

| # | 缺口 | 证据（文件:行） |
|---|---|---|
| G1 | **iBatis XML 零解析**：全仓 `sqlMap`/`SqlMap` 字面量零命中（`MpSqlMapper`/`SqlMapper*` 是 MyBatis 侧命名，不算） | `grep -rn "sqlMap\|SqlMap" src/main` 仅命中 `MpSqlMapper`/`SqlMapper*` |
| G2 | **根标签判定硬编码 `<mapper>`（8 处）**，iBatis 的 `<sqlMap>` 全部不满足 | 见 §2.3 表 |
| G3 | **XML 引擎强绑 MyBatis 3.5.19 语义**：未知标签直接抛异常 | `xml/XmlScriptBuilder.kt:31-43`（`trim/where/set/foreach/if/choose/when/otherwise/bind`，`:42` 抛 `XmlScriptException`） |
| G4 | **占位符定界符硬编码 `#{}`/`${}`（4 处）** | `xml/TokenParser.kt`、`xml/TextSqlNode.kt:34`、`xml/XmlSqlRenderer.kt:87-93`、`ui/inject/SqlMapperHighlightingLexer.kt:135-195` |
| G5 | **注入属性白名单无 iBatis 属性** | `ui/inject/XmlSynHiGate.kt:336`（`ATTRIBUTE_WHITELIST` 缺 `property`/`compareProperty`/`compareValue`/`prepend`/`conjunction`/`removeFirstPrepend`/`refid`/`resultClass`/`parameterClass` 等） |
| G6 | **`SqlSource` 无 `IBATIS` 值**，iBatis 条目复用 `MYBATIS` | `log/LogSqlModels.kt:6`（7 值）；扩散 30 处引用；`log/LogEntryPreviewRecognizer.kt:241-243` 是 `when(source)` 穷举点 |
| G7 | **无 iBatis 预览入口**（gutter provider / Action / 设置开关） | `ui/gutter/` 无 `SqlMap*` provider；`GutterGroups.PROVIDER_GROUP`（`ui/gutter/GutterGroups.kt:25-42`）无 iBatis 组 |
| G8 | **无 iBatis 专用跳转**（`queryForObject("ns.id", …)` ↔ `<select id>`） | 全仓无 `SqlMapClient` 引用 |
| G9 | **方言/数据源解析在无 namespace / 无接口 / 无实体注解的 iBatis 文件上无结论路径** | 见 §三 券 0（第四路专查） |

**当前症状的直接后果**：iBatis 的 `<select>` 标签唯一可能被认领的通道是**内嵌 SQL 兜底**（`ui/gutter/XmlInlineSqlGutterLineMarkerProvider` + `xml/XmlInlineSqlDetector`，三套白名单之后兜底、不限根元素、总开关 `xmlInlineSqlEnabled` 默认开），走的是通用降级路径——**这正是用户反馈「点预览图标打开很慢」的候选入口**（§三 券 0）。

### 2.3 跨切面清单：根标签判定 8 处

| 文件:行 | 判定 | 面 |
|---|---|---|
| `ui/ref/MybatisXmlTargets.kt:48` | `isMapperFile` | 引用 / 跳转 / 查找用法 |
| `ui/inject/XmlSynHiGate.kt:342-349` | `inMapperFile` | **注入收口点**（着色/补全/折叠/反缩进全部经此） |
| `ui/inject/SqlMapperBackspaceHandler.kt:48` | `rootTag?.name == "mapper"` | 反缩进 |
| `ui/inject/SqlMapperChangeLocalityDetector.kt:23` | 同上 | 变更局部性 |
| `ui/inject/MybatisSqlTemplateContextType.kt:24` | 同上 | 模板上下文 |
| `ui/inject/SqlMapperFoldingBuilder.kt:48` | 同上 | 折叠 |
| `xml/XmlMapperParser.kt:56` | 根元素非 `<mapper>` → `XmlParseException` | 解析器 |
| `log/MapperXmlLocator.kt:182` | 语句标签集 | 日志条目跳转 |

### 2.4 可直接复用的层（ORM 无关）

| 层 | 落点 | 复用度 |
|---|---|---|
| 输出模型 | `model/PreviewResult.kt:36`（已有 `pagesColumns`/`namedParamHints` 等按来源追加字段的先例） | 直接复用 |
| 参数替换与提示 | `sql/ParameterReplacer.kt`（`buildParamTable`/`buildExecutableSql`/`replaceNamedParams`/`replaceRuntimeSlots`）、`sql/ParamHint.kt`、`sql/PlaceholderPositions.kt`、`sql/ParamQuote.kt` | 直接复用（`#p#`→`?` 后可接） |
| 参数类型契约 | `xml/ParamKind.kt` 六类 | 直接复用 |
| 方言与渲染 | `sql/Dialect.kt` / `DialectRegistry` / `sql/SqlKeywords.kt` | 直接复用 |
| 预览面板与动作 | `ui/SqlPreviewService.showResult` / `previewAsync`、`ui/SqlPreviewDialog`、`actions/ConvertToSqlAction` | 直接复用 |
| 安全 XML 解析 | `xml/SecureXml.kt`（全仓唯一 `DocumentBuilder` 工厂 + 膨胀预扫） | 直接复用 |
| 模拟参数对象 | `xml/DynamicContext.kt` + `MockObject` | 可复用（iBatis 条件求值改造后可挂） |

**不可复用**：`xml/Ognl.kt`（iBatis 无表达式语言，见 §1.6）；`xml/XmlScriptBuilder.kt`（标签集不同）；`xml/TokenParser.kt` 的定界符（需参数化或另写）。

### 2.5 可照抄的先例链：`<pages>`（同为非 MyBatis XML 族）

`xml/PagesXmlParser` → `ui/PagesXmlPreviewService` → `ui/gutter/PagesXmlGutterLineMarkerProvider` → `actions/ConvertToSqlAction` → 预览面板，另有 `xml/PagesSourceLanguage.kt` 判源语言、`ui/PagesEntityProbeResolver.kt` 解析实体。iBatis 应按同一形态**新增独立链**，而不是往 MyBatis 链里塞分支。

另参照 `xml/PagesXmlParserTest` / `PagesXmlPreviewServiceTest` 的测试落点形态。

---

## 三、方案（分券）

### 3.0 方案选型（A/B/C/D 对比与定论）

| 维度 | A 复用现有引擎 + 适配层 | **B 独立解析 + 复用输出层（推荐）** | C 仅运行时捕获 | D 仅 DTD 轻量支持 |
|---|---|---|---|---|
| 实现路径 | iBatis DOM → 翻译成 MyBatis `SqlNode` 树（`isNotNull`→`IfSqlNode(test="x != null")`、`iterate`→`ForeachSqlNode`、`#x#`→`#{x}`）→ 现有渲染器 | 新 parser（复用 `SecureXml` 与 include 展开口径）+ 新模型（iBatis 标签语义）+ 直接产 `PreviewResult` | 不加静态能力，靠 agent 与日志通道 | 只注册 DTD + 描述符，做高亮/校验/补全/跳转 |
| 复用面 | 渲染器、`XmlPreviewModel`、条件勾选面板、分页、方言 | `PreviewResult`、参数面板回填、方言格式化、分页模拟、执行/EXPLAIN、历史、gutter 基础设施 | 已存在的采集/展示链路 | 平台 XML 编辑器（零自研） |
| 代价 | 翻译层约 600–900 行，且语义映射是**持续**负担 | parser+model+renderer 约 800–1,200 行，一次性 | 0（已存在） | 约 200–300 行 + DTD 资源 |
| 风险 | **语义漂移**：`prepend`/`removeFirstPrepend`、`isEqual compareProperty`、`iterate` 无 item、`#ids[]#` 命名全部无法无损表达；未知标签让 `XmlScriptBuilder` 直接抛异常（`xml/XmlScriptBuilder.kt:42`） | 需把面板对 `result.xmlModel` 的具体类型依赖抽成接口 | 丢失「服务未启、SQL 先知」核心卖点 | 不解决核心卖点 |
| 可验证性 | 中（可对 oracle，但偏差来自**设计**而非实现，oracle 只会告诉你「不一样」，改不动） | **高**（iBatis 2.3.4 jar 直接产期望 SQL，见 §五） | 高（真实执行） | 高（平台 DTD 校验行为确定） |

**定论：采用方案 B。** 核心理由两条：

1. **语义正交**：iBatis 2 的动态标签是「属性存在性判定 + prepend 文本拼装」，与 MyBatis 3 的 OGNL 表达式求值不是同一件事。§1.6 实测的 `prepend` 逐字拼接（`ANDORG_NAME`）、`removeFirstPrepend` marker 栈、`<iterate>` 无 `item`、`#ids[]#` 命名，在 `SqlNode` 树上都无法无损表达——硬塞只能得到**有偏差**的 SQL。
2. **值钱的那层本来就与 MyBatis 解耦**：`PreviewResult`（`model/PreviewResult.kt:36`）、`sql/ParameterReplacer.kt`、`Dialect`、分页模拟、执行/EXPLAIN、历史，任何 renderer 产出 `PreviewResult` 即可复用。

**方案 D 不单独立项**，作为 B 的第二期增强（成本极低、收益明确）。**方案 C 已存在**，不重复建设。

**方案 B 的已知代价（须写进任务书）**：面板对 `xmlModel` 有 37 处引用、集中在 `ui/SqlPreviewPanelParamPanel.kt`（15 处）与 `SqlPreviewPanelSqlView/ResultView/Execution`，iBatis 的条件勾选模型要落进去需要先把这层抽成接口（见 §三 券 C-3）。

### 3.1 券清单

> 「券」= 一个可独立提交、可独立验证的工作单元。写集互斥的车道可并行（§八）。

#### 券 0（P0，**独立缺陷，先行止血**）内联兜底通道的实体探针慢路径

**这不是 iBatis 支持的一部分，而是用户报的「打开慢」的真因**，且对**所有**用兜底通道看纯 SQL 的用户都成立（存量面大于 iBatis 用户）。建议按 §七 D-4 拆成独立任务先行。

**机制（全部带 `文件:行`）**：

```
点图标 → XmlInlineSqlGutterLineMarkerProvider.navigate
        → SqlPreviewService.previewAsync（池线程 ReadAction.compute，不可取消）
        → XmlInlineSqlPreviewService.preview:52-58
        → PagesSourceLanguageDetector.detect 第 4 步（PagesSourceLanguage.kt:111-124）
        → PagesEntityProbeResolver.probe（:22-39）
        → EntityAliasSweep.resolve（:62-76）→ 5000 短名全扫（:118）
```

- 默认档 `pagesXmlSourceMode = PAGES_SOURCE_AUTO`（`config/PluginSettings.kt:100`），故默认就走这条。
- iBatis 语句**必然不带** `entityClass`（那是本插件自研 pages 框架属性，iBatis 无此概念）→ detect 第 2/3 步被跳过 → **必然落第 4 步**。
- 根标识符来自 `HqlEntityNameExtractor`（`FROM|UPDATE|INTO|DELETE` 后的标识符）——iBatis 场景下是**物理表名**（`t_order`），即「必然查不到」的最贵输入。
- `EntityAliasSweep` 自带事故注释：同类形态**曾实测 70 秒冻结 EDT**（`psi/jpa/EntityAliasSweep.kt:20-33`），现行上限下单次约 1s（`:67-70` 注释口径，非本次实测）；缓存随 PSI 改动失效，且**每个新表名各付一次**。

**修法候选（待 §七 D-3 拍板，或任务内以实测取舍）**：

| # | 修法 | 说明 |
|---|---|---|
| 0-1 | `EntityAliasSweep` 入口加廉价前置门 | 「含 `_` 或全小写且形如表名」直接跳过全扫。⚠ 会误伤「实体名恰好小写」用例，须真值表 + 反向验证 |
| 0-2 | XML 来源 context 跳过全扫 | XML 里的名字要么是表名、要么带 `entityClass`（后者本已短路）；等价于「XML 链不做别名反查」 |
| 0-3 | 把探针挪出关键路径 | detect 先用「无探针」结论出默认 SQL，探针结果异步到达后再刷新面板（与 `SqlPreviewPanelSqlView.kt:115-131` 同款形态） |

**顺带项（不属本册范围，登记供主控决策）**：master 不含 dev 的 T-PerfSweep30 券 01a（cache-only 预热，`grep -rn cacheOnly src/main` → 0 命中；`git merge-base --is-ancestor 25cb1eec HEAD` = NO），该项直接削掉「控制台准备 + 预热」那条（§四 瓶颈 2）的重复写盘。若用户装的是 master 包，瓶颈 2/3 权重上升。

**验收（券 0）**：控 3 对照（`pagesXmlSourceMode = PAGES_SOURCE_SQL` 后明显变快 ⇒ 坐实本券）；`[T-PreviewFast] request→dialog` 与 `request→console-visible` 两段读数分开取；反向验证（摘掉前置门 → 目标用例必红）。

#### 券 A（P0，止血）iBatis 识别 + 让位/接管

**动因——当前半成品态比没有更伤**（第三路实证，主控复核判定链成立）：

- `<sqlMap>` 下的 `<select|insert|update|delete|statement|sql>`（**仅当标签是叶子**）会被通用兜底通道认领并出预览图标；
- 但走的是原文直显：**`#name#` 原样留着、参数面板显示「无参数」**（iBatis 文本里没有 `?`，占位符计数为 0）；
- 含 `<dynamic>`/`<isNotNull>` 子标签的语句 `subTags.isNotEmpty()` → **一个图标都没有**（`xml/XmlInlineSqlDetector.kt:36-41`）。

**落地**：

1. **识别器**（新，纯逻辑可单测）：判据「根元素 `sqlMap`/`sqlMapConfig`」+「DOCTYPE publicId/systemId」+「依赖或类特征 `com.ibatis.sqlmap.client.SqlMapClient`」**三选二**。三条纠正（实测）：
   - `-//Apache Software Foundation//DTD SQL Map 2.0//EN` **不是** iBatis 的 publicId（全 jar 仅 1 处命中，在 `META-INF/MANIFEST.MF` 的 `Implementation-Vendor`；`SqlMapClasspathEntityResolver` 不认它）——**容忍但不作为必要条件**；
   - **无 DOCTYPE 的裸 `<sqlMap>` 不是运行时可用形态**（`NodeletParser.setValidation(true)`，Xerces 报错并抛异常）——所以不能只信 DOCTYPE，也不能只信根元素；
   - DOCTYPE 四条合法值：systemId `http://ibatis.apache.org/dtd/sql-map-2.dtd` / `http://www.ibatis.com/dtd/sql-map-2.dtd`（config 同名换 `sql-map-config-2`），publicId `-//iBATIS.com//DTD SQL Map 2.0//EN` / `-//ibatis.apache.org//DTD SQL Map 2.0//EN`。
2. **让位**：`xml/XmlInlineSqlDetector.isClaimedByKnownStructure`（:44-60）增加 `sqlMap` 分支（`tag.parentTag?.name == "sqlMap"` 或根为 `sqlMap`），防双图标。
3. **接管**：新增 `ui/gutter/SqlMapXmlGutterLineMarkerProvider`，navigate **直连原生 SQL 直显 + iBatis 参数识别**路径，**完全绕开 `PagesSourceLanguageDetector`**（iBatis 2.x 无 HQL 概念，语言判定对它整体是无效计算——这同时消掉 §券 0 的慢路径）。
4. **让位与接管必须同批落地**：只让位不接管 = iBatis 文件图标全消失（用户观感更差）。本券最小可用面 = 出图标 + 原生 SQL 直显 + `#name#` 参数识别（不要求条件求值）。
5. **设置项**：`config/PluginSettings.kt` 加开关（默认开）；`ui/gutter/GutterGroups.kt:25-42` 登记组（倾向 `mapperXml`，见 §七 D-8）；两个 bundle 中英双份。
6. `revision` 面：`ui/inject/XmlSynHiGate.kt:342-349` 等 8 处根标签判定按 §2.3 表逐个放宽——**必须带「iBatis 文件才放宽」的二次判定**，不得影响 MyBatis 路径（本券只动最小集：`XmlSynHiGate` + `SqlMapXmlGutterLineMarkerProvider` 自身判定；其余 6 处随券 E 处理）。

#### 券 B（P1，主体）iBatis 解析器 + 模型 + 渲染器（方案 B 核心）

新增包 `xml/ibatis/`（命名待定，见 §七 D-9）：

| 新件 | 职责 | 对应实测事实 |
|---|---|---|
| `SqlMapParser` | JDK DOM 解析（复用 `xml/SecureXml.kt` 唯一工厂）；根 `sqlMap`；语句标签 6 种（`select/insert/update/delete/statement/procedure`）；`<sql>/<include>` 展开口径（先按 `refid` 找、再 `applyNamespace(refid)` 找）；`<selectKey>`、`<parameterMap>`、`<resultMap>` 清单 | §1.5 |
| `SqlMapScriptBuilder` | `<dynamic>` / 14 个 `<isXxx>` / `<iterate>` → iBatis 语义节点树；**未知标签降级为「原文 + 警告」，不抛异常**（与 `XmlScriptBuilder.kt:42` 的口径相反） | §1.6 |
| `SqlMapConditionEvaluator` | `Probe` 语义的属性取值 + `compareValues` 类型换算（Byte/Short/Integer/Long/Float/Double/Boolean/Date(`yyyy/MM/dd hh:mm:ss`)/BigInteger/BigDecimal，非 Comparable 双转 String）；`NOT_COMPARABLE` 语义；`<isXxx>` 各自的 `isCondition` | §1.6 |
| `SqlMapPrependMarker` | `removeFirstPrepend` marker 栈（操作**父层** `stack.get(1)`）；`<dynamic prepend>` 默认吃掉第一个子 prepend | §1.6 |
| `SqlMapTokenParser` | `#`/`$` 两套定界符：分派（含 `:` → 简单语法；否则高级语法）、`StringTokenizer` token 分档（3=两段、≥5=三段）、`##`/`$$` 转义、未闭合抛错、`#ids[]#` 下标占位 | §1.7 |
| `SqlMapSqlRenderer` | 拼装 **逐字不补空格**（`open`/`prepend`/`conjunction`/`close` 全部原样 insert）；`$` 在动态标签之后替换；**声明 `parameterMap` 的语句 `#` 不转 `?`**（单分支）；`nullValue` 哨兵→`NULL`；`${`注入面标注接既有 `PreviewResult.hasDollarRawSegments`（`model/PreviewResult.kt:86`） | §1.7、§1.10 |
| `ui/SqlMapPreviewService` | 产 `PreviewResult`（复用 `SqlPreviewService.showResult` / `previewAsync`） | §2.4 |

**C-3 子项（抽象成本，必须在券 B 内做）**：`xmlModel` 37 处引用（`SqlPreviewPanelParamPanel.kt` 15 处）须先把「条件勾选模型」抽成接口，iBatis 模型才能落进去。既有 MyBatis 路径须零回归（§四 P-7）。

#### 券 C（P1）golden oracle 保真对照集

照 `xml/fidelity/RealEngineFidelityTest` 的既有范式（那套是拿真 MyBatis 3.5.19 对 34 场景）：

- **测试面**加依赖 `org.apache.ibatis:ibatis-sqlmap:2.3.4.726`（Maven Central 有货，384,363 B）。仓库红线是「零 mybatis 依赖仅约束 `implementation`」，测试 classpath 无冲突。
- **oracle 用法**（两路代理独立跑通，互相印证）：
  ```java
  SessionScope ss = new SessionScope(); ss.setSqlMapClient(client);
  StatementScope scope = new StatementScope(ss);
  ms.initRequest(scope);                                   // 必须，否则静态语句取不到 parameterMap
  Sql sql = ms.getSql();
  ParameterMap pm = sql.getParameterMap(scope, params);     // DynamicSql 在此完成一遍 tag 树求值
  String sqlText = sql.getSql(scope, params);
  Object[] values = pm.getParameterObjectValues(scope, params);
  sql.cleanup(scope);
  ```
  **不需要连库**（可用返回 null 的 FakeDriver 建 SqlMapClient），离线、可进 `testFast` 桶。
- **用例形态**：「同一 sqlMap + 同一参数 → 我方渲染结果与 iBatis 真实输出**逐字节**比对」。
- **覆盖域**（按 §1 实测事实设计）：`<dynamic prepend>` 的 prepend 消费、14 个 `<isXxx>` 各自判定、`compareProperty` vs `compareValue`、`<iterate>` open/close/conjunction 与 `#ids[]#`、`$` 后置替换、`##`/`$$` 转义、两段/三段占位符、`parameterMap` 分支（`#` 不转 `?`）、`nullValue` 哨兵、`<sql>/<include>`（同文件 + 跨 namespace）、`<selectKey>` pre/post。

#### 券 D（P2）注入 / 着色 / 折叠 / 补全扩展

| 改动点 | 内容 |
|---|---|
| `ui/inject/XmlSynHiGate.kt:342-349` | `inMapperFile` 放宽到 `sqlMap`（带二次判定） |
| `ui/inject/XmlSynHiGate.kt:336` | `ATTRIBUTE_WHITELIST` 补 iBatis 属性：`property`/`compareProperty`/`compareValue`/`prepend`/`conjunction`/`open`/`close`/`removeFirstPrepend`/`refid`/`parameterClass`/`resultClass`/`parameterMap`/`resultMap`/`nullValue`/`jdbcType`/`javaType`/`mode`/`selectKey` 族 |
| `ui/inject/SqlMapperHighlightingLexer.kt:135-195` | 加 `#x#` / `#x:VARCHAR#` / `#x:VARCHAR:nullValue#` / `$x$` 四态 |
| `ui/inject/SqlMapperFoldingBuilder.kt:48` 等其余根判定 6 处 | 按 §2.3 表逐个放宽（带二次判定） |
| 补全 | `ui/highlight/XmlSqlCompletionContributor.kt:87-89` 的根 `mapper` 守卫放宽；statement `id` ↔ `refid` 补全 |

#### 券 E（P2）DTD 离线登记

- 内置两份 DTD（jar 内权威副本，合计约 37 KB），Apache-2.0（DTD 头 `Copyright 2004 Clinton Begin`）→ **须在 `THIRD-PARTY-NOTICES.md` 登记**。
- 登记方式（任选，需实测生效）：声明式 `standardResource`（bean `com.intellij.javaee.StandardResourceEP`，字段 `url`/`resourcePath`/`version`）或编程式 `ExternalResourceManagerEx.addResource(url, path, project)`。
- **不登记的后果**：平台联网取 DTD 失败（`www.ibatis.com` 域早已失效，见 §四 P-3）→ 整篇报「无法解析 DTD」并级联满屏「必须声明元素类型」。
- 口径：以 **jar 内副本**为准（网站版 `sql-map-config-2.dtd` 比 jar 内新，`sqlMap*` vs `sqlMap+`，见 §1.2）。

#### 券 F（P2）跳转与全局 id 表

- `queryForObject("ns.id", …)` / `queryForList("id")` ↔ `<select id>` 双向跳转（`ui/ref/` 侧）。
- **`useStatementNamespaces` 默认 false**（`sql-map-config-2.dtd` 里 `#IMPLIED`）→ statement id 全局唯一，跨文件同名即歧义。需要解析 `sqlMapConfig` 的 `<settings>` 与 `<sqlMap resource|url>` 清单建**全局 id 表**；找不到 config 时按「同文件优先、多候选给选择器」降级。
- **注意**：全仓无任何 `SqlMapClient` 引用可参照，DAO 侧调用形态需新取证（§四 P-6）。

#### 券 G（P3）类型映射

- iBatis 参数类型来自 `parameterClass` 与 `<parameterMap>/<parameter property javaType jdbcType>`，**与 Java 接口无必然绑定**，现有 `ui/XmlParamTypeResolver.kt`（namespace→Mapper 接口→方法参数）**完全不适用**。
- 直接决定引号与占位提示质量，是方案 B 里最容易被低估的一块。

#### 券 H（P1，运行期捕获面收尾）`SqlSource.IBATIS`

兑现 2026-11-06 T-IbatisPanwei §八 D-3 的欠账（当时为控扩散面复用 `MYBATIS`）：

- `log/LogSqlModels.kt:6` 加第 8 值；30 处引用同步；`log/LogEntryPreviewRecognizer.kt:241-243` 的 `when(source)` 穷举点加分支 + i18n 键（中英双份）；
- 筛选标签、历史序列化兼容（旧条目 `MYBATIS` 不迁移）；
- 跨通道去重键不受影响（键按还原后文本 + 参数签名，与来源枚举无关）——**需用例钉住**。
- 守卫：`convention/EnumReachabilityGuardTest`（新枚举值必须有产出点，否则红）。

#### 券 I（文档与登记）

- `docs/架构设计/预览触发面清单-说明文档.md`：§一 入口矩阵**加行** + §三 负例边界补 iBatis 形态 + 至少一条端到端正例（三登记义务，见该文档 `:57-63`）；
- `docs/架构设计/其他ORM官方API覆盖矩阵.md`：新增 iBatis 2.x 条目（含编号前缀，沿用该矩阵两字母体系，见 §七 D-10）与成本档结论；
- `docs/架构设计/02-核心流程详解.md:317-337`：渲染链加 iBatis 分支；
- `CHANGELOG.md` `[Unreleased]`（提交即回填，一句一条带哈希）；`docs/索引/开发计划.md` 索引行；`docs/LESSONS.md` 收口后补记。

---

## 四、待验证前提

| # | 前提 | 为何必须证伪/证实 | 怎么验 |
|---|---|---|---|
| P-1 | iBatis 2.x 真实工程的形态：具体版本档（2.0/2.1/2.3）、`useStatementNamespaces` 取值、`*_SqlMap.xml` 命名约定是否普遍 | 决定版本分支范围（§七 D-6）与全局 id 表必要性 | 用户工程样本 / 真机 |
| P-2 | 「打开慢」两段真实墙钟（`request→dialog` / `request→console-visible`） | 券 0 的修法取舍依赖「是弹窗慢还是控制台区慢」 | 真机 `idea.log`（锚点见 §五） |
| P-3 | 平台是否本地映射 `http://www.ibatis.com/dtd/sql-map-2.dtd` / `http://ibatis.apache.org/dtd/...` | 决定券 E 的紧迫度；域已失效，不映射会走联网超时 | 全仓 `grep registerLocalDTD\|dtds` **零命中**；无 IDE 环境无法确认平台侧行为 |
| P-4 | `standardResource` 的 `url` 字段是否接受上面的 http 形态并能真正生效 | 券 E 的登记方式选择 | 沙盒实测（注册后打开 sqlMap 文件看是否仍报 DTD 错） |
| P-5 | `<statement>` 手写 `?`（配 `<parameterMap>`）在真实工程里的占比 | 决定「该语句参数名不可知」提示的必要性（§六 风险 5） | 用户样本 |
| P-6 | iBatis DAO 侧调用形态（`SqlMapClient` 的 `queryForObject`/`queryForList`/`insert` 等签名与 statement id 传参形态） | 券 F 跳转的锚点判定依据；仓内零 `SqlMapClient` 引用可参照 | 新取证（jar `javap` + 工程样本） |
| P-7 | `xmlModel` 抽成接口后，既有 MyBatis / pages 路径零回归 | 37 处引用，抽错会让既有 XML 条件勾选失效 | 券 B 内做，跑既有 `xml/` 与 `ui/` 定向 + 反向验证 |
| P-8 | 是否需要支持 2.3.1 以下（`RequestScope`） | 决定是否写版本分支（纯静态解析本用不到 scope 类，只影响 oracle 对照） | 依 P-1 |
| P-9 | 券 0 的「XML 来源跳过全扫」是否误伤既有用例 | 有既有用例可能依赖「XML 内表名反查到实体」 | 改前先跑 `xml/` + `ui/` 定向建基线 |
| P-10 | 竞品零覆盖结论的覆盖面 | 本册只解包扫了 3 个包（MyBatisX/CodeHelperPro/FreeMyBatisTool）；Marketplace 上还有 2013–2018 的 mini-plugin 族 | 已扫三包均 0 命中（主控复核）；mini-plugin 族只到「导航级」，无静态渲染 |
| P-11 | 用户是否另装 MyBatisCodeHelperPro | 影响「图标来自哪条通道」的归因（`MyBatisCompetitorGate` 只对 Mapper 跳转图标让位，不影响内联兜底图标） | 问用户 / 设置页可见性 |

**已推翻的假设（记录在案，防复发）**：
- ✗ 「iBatis 2 没有 `<sql>/<include>`」——**有**，2.1.7 起（DTD 190/213 行）。
- ✗ 「条件判定可以用 OGNL」——**无 OGNL**（jar 与源码 jar 内 ognl 条目均为 0），走 `Probe` + `ConditionalTagHandler`。
- ✗ 「`#name:nullValue#` 合法」——非法，两段式第二段永远是 jdbcType。
- ✗ 「`-//Apache Software Foundation//DTD SQL Map 2.0//EN` 是 iBatis publicId」——不是（仅出现在 manifest 的 `Implementation-Vendor`）。
- ✗ 「`org.apache.ibatis:ibatis-common` 是 iBatis 2 的依赖」——Central 404；`com.ibatis.common.*` 54 类已并入 `ibatis-sqlmap` jar；`ibatis-core:3.0` 是 MyBatis 3.0 beta，无关。
- ✗ 「用 `<sqlMap>` ↔ `<mapper>` 换根标签可判定慢的成因」——**混淆对照**（整条链路都换），不成立；正解是切 `pagesXmlSourceMode = SQL`。

---

## 五、验收

### 5.1 定向测试（实施子代理随报告交出）

| 券 | 定向范围 | 桶位 |
|---|---|---|
| 0 | `psi/jpa/EntityAliasSweepTest`（或同类）+ `ui/XmlInlineSqlPreviewServiceTest` | Light（`test`） |
| A | 识别器纯逻辑 + `XmlInlineSqlDetectorTest` 负例（`sqlMap` 让位）+ 新 provider 测试 | 识别器可走 `testFast`（零 `com.intellij`）；provider 走 `test` |
| B | `xml/ibatis/*` 纯逻辑全套：`SqlMapParserTest` / `SqlMapScriptBuilderTest` / `SqlMapTokenParserTest` / `SqlMapSqlRendererTest` / `SqlMapConditionEvaluatorTest` / `SqlMapPrependMarkerTest` | `testFast`（纯逻辑，JDK DOM） |
| C | `xml/ibatis/fidelity/SqlMapFidelityTest`（golden oracle，逐字节） | `testFast`（无 DB，可用 FakeDriver） |
| D | 注入/着色/折叠/补全各 provider 测试 | `test` |
| E | DTD 登记生效性（沙盒实测，见 5.4） | 沙盒 |
| F | 跳转 provider + 全局 id 表纯逻辑 | 混合 |
| H | `log/` 枚举扩散各消费点 + `EnumReachabilityGuardTest` | 混合 |

### 5.2 反向验证（每条修复至少一条「摘掉必红」）

逐券列探针，优先走 `dev-support/reverse_probe.py`（改既有行为用 `--commit` 整提交反打；新增能力型用 `--patch` 手术式补丁 + 入库 `dev-support/reverse-probes/`）。**必须包含**：

- 券 0：摘前置门 → 目标用例必红（且要证明探针有判别力，合成样本不够）；
- 券 A：摘 `isClaimedByKnownStructure` 的 `sqlMap` 分支 → 双图标用例必红；
- 券 B：`prepend` 补空格 → `ANDORG_NAME` 保真用例必红；`##` 转义删掉 → 字面量 `#` 用例必红；`parameterMap` 分支删掉 → 「`#` 不转 `?`」用例必红；
- 券 H：摘 `IBATIS` 枚举产出点 → `EnumReachabilityGuardTest` 必红。

### 5.3 触发面三登记义务（`docs/架构设计/预览触发面清单-说明文档.md:57-63`）

① 入口矩阵加行；② 负例边界补形态；③ 至少一条端到端正例（参照 `sql/spec/SqlPreviewSpecMatrixTest` 与 `PagesXmlPreviewServiceTest`）。

### 5.4 真机验收（**交用户执行**，样式＝编号步骤 + 预期结果 + 失败判据）

1. 打开一个 iBatis `*_SqlMap.xml`：左侧 gutter 应在 `<select|insert|update|delete|statement>` 行出图标，**含 `<dynamic>`/`<isNotNull>` 子标签的语句也要出**（当前无图标是已知缺陷）。
2. 点图标 → 预览应**秒开**；日志里 `[T-PreviewFast] request→dialog` 应显著小于修复前读数（§四 P-2 取基线）。
3. 预览里 `#name#` 应被识别为参数（面板列出参数名），**不再显示「无参数」**；`#p:TYPE:TYPE#` 三段形态与 `$p$` 也应正确。
4. 打开含 `<dynamic prepend="WHERE">` 的语句：渲染结果应保留 `WHERE` 且**只出现一次**（prepend 消费正确）；且不出现 `ANDX` 这类粘连以外的额外美化——**逐字对照 iBatis 实际下发 SQL**。
5. sqlMap 文件里不应再出现「无法解析 DTD」与满屏「必须声明元素类型」（券 E 生效判据）。
6. iBatis 语句**不应**被当作 HQL 尝试转换（不应出现「已按 HQL 转换」标记）。
7. **跳转（券 F）**：在 `<include refid="cols"/>` 上 Ctrl+B / Ctrl+Click，应落到同文件 `<sql id="cols">`；在 `resultMap="userResult"` / `parameterMap="userParamMap"` 的属性值上同样应跳；`<resultMap extends="base">` 亦然。
8. **跳转（券 F）从 Java 侧**：在 DAO 里 `sqlMapClient.queryForObject("user.selectById", id)` 的语句 id 字符串上 Ctrl+B，应落到 XML 里 `<select id="selectById">` 的 `id` 属性值。**负例**：同一形态但项目里没有对应 sqlMap 语句时，按下应**无跳转、也不报红**。
9. **快捷键（券 A 收尾）**：在 sqlMap 文件里按 Alt+Shift+Q，候选列表应能列出该文件的 iBatis 语句（此前为空）。
10. 失败时把 `idea.log` 里 `[T-PreviewFast]`/`[M36]` 行与 `PreviewDegradationReason` 值回传。

### 5.5 not run 条款

本册为**调研与计划**，未跑任何构建、未跑测试、未做真机读数。所有「耗时量级」均为仓内既有文档读数或源码注释口径，**非本轮实测**。全量 `testShards`、`verifyGate` / `verifyPlugin` / `buildPlugin`、runIde 沙盒均**未跑**（大改动判据成立时按 §六 补跑）。

---

## 六、风险与口径

1. **半成品态比没有更伤**——当前 iBatis 语句能出图标但显示 `#name#` 原样 + 「无参数」，且带动态标签的语句**没有图标**。券 A 的「让位」必须与「接管」同批，否则观感更差。
2. **EOL 与存量**：iBatis 2 已在 Apache Attic，最后发行物 2010 年。存量规模无可信数据（Central 不公开下载量、GitHub 匿名限流 403）。唯一真实需求信号是 2026-11-06 用户真机报障。**功能设计按「小众但明确」处理：默认不产生噪音，只在识别为 iBatis 文件时启用。**
3. **`prepend` 逐字拼接不补空格**：渲染结果**禁止美化重排**，否则与真实下发 SQL 不一致。这条与现有 XML 链输出习惯冲突，须在渲染器类头写死并配保真用例。
4. **`$param$` 直拼的安全面**：`SimpleDynamicSql` 字节码确认是字符串直替，风险等同 MyBatis `${}`——**必须接进既有 `${}` 注入确认闸门**（`PreviewResult.hasDollarRawSegments`，`model/PreviewResult.kt:86`），不得新开无闸门路径。
5. **`<statement>` 手写 `?`**：DTD 允许 `#PCDATA`，参数由 `parameterMap` 提供，静态渲染只能得到无属性名的 `?`，面板按 `param1..N` 兜底（与现有通用通道同口径）。**UI 须明说「该语句参数名不可知」**，这不是缺陷但要说清。
6. **双图标**：`isClaimedByKnownStructure` 不加 `sqlMap` 会让新旧两条通道同时出图标。
7. **DTD 外链**：`www.ibatis.com` 域已失效；不内置 DTD 会走联网/超时并级联满屏报错。
8. **类型来源缺失**（券 G）：iBatis 参数类型与 Java 接口无必然绑定，`XmlParamTypeResolver` 不适用——引号与占位提示质量是方案 B 最易被低估的一块。
9. **平台红线**（改 `src/main` 时逐条对照）：
   - EDT / 跨线程 PSI 读取：新增 gutter provider 的跨线程读取须走 `PsiSnapshot` 两段式协议（`ui/gutter/SqlGutterLineMarkerProvider` 已有先例）；`getLineMarkerInfo` 必须 **O(1)**，不做 resolve / 全文件遍历；
   - 新增 EP 声明**必须带 `dynamic="true"`**，否则整个插件退化成必须重启（`config/PluginXmlExtensionPointResolutionTest` 会抓）；
   - 新增设置项要过「假配置四断言」（`config/SettingsRealityGuardTest`）+ 设置搜索索引登记（`PluginSettingsFolderConfigurable.ideSearchEntries()`）；
   - 新增守卫判定函数要配自证语料并登记 `convention/GuardSelfTestCoverageMetaGuardTest`；
   - **巨文件预算**：`GiantFileBudgetTest` 存在于 `origin/dev`、**不在 master**——若在 dev 线开发，新增的 parser/renderer 大文件会撞预算红线，须按该守卫自带流程提额留痕或拆分。
10. **目录与编号**：本仓有「新建目录前先查既有」的要求——`xml/` 下已有 `fidelity/`、`PagesXmlParser` 等先例，iBatis 包路径与命名须先与既有风格对齐（§七 D-9）。「其他 ORM 矩阵」的编号前缀须先 grep 查重（§七 D-10）。

---

## 七、决策点

> 全部**待用户拍板**；每项给推荐项与理由。推荐项可直接作为派活依据。

| # | 决策 | 推荐 | 理由 |
|---|---|---|---|
| D-1 | 目标分支 | **dev 线先行**（功能分支 → dev），master 只接「券 0 慢路径止血」 | 本任务改 `plugin.xml` EP、新增注入面与设置项，属「大改动判据」；且 dev 有 master 缺的巨文件预算守卫与 T-PerfSweep30 预热优化 |
| D-2 | 是否新增 `SqlSource.IBATIS` 枚举值 | **新增**（券 H） | 用户要按来源筛选；旧决策「复用 MYBATIS」是为控扩散面，现在有明确需求；扩散面 30 处但集中在 `log` 包，且 `EnumReachabilityGuardTest` 能兜住 |
| D-3 | 券 0 慢路径修法 | **0-2（XML 来源跳过全扫）为主 + 0-3（探针移出关键路径）兜底** | 0-1 的「snake_case 表名」启发式会误伤「实体名恰好小写」用例；0-2 语义清晰（XML 链不做别名反查），0-3 不改判定只改时序，风险最低 |
| D-4 | 是否把「券 0」拆成独立任务先行 | **拆** | 券 0 是**所有**用兜底通道看纯 SQL 的用户都受影响（面大于 iBatis 用户），且与 iBatis 支持无依赖，可立即止血 |
| D-5 | DTD 是否内置 | **内置**（券 E） | 约 37 KB、Apache-2.0、可随包分发；不内置则 `www.ibatis.com` 失效域导致满屏报错 |
| D-6 | 版本支持范围 | **只按 2.3.x 建模**（未知标签降级为「原文 + 警告」） | 2.0/2.1 与 2.3 元素集有差异；2.3.4.726 是 Central 上最新且唯一带 `notNullColumn` 的档 |
| D-7 | `<statement>` 手写 `?` 的呈现 | `param1..N` 兜底 + UI 明示「参数名不可知」 | 与现有通用通道同口径，不新造语义 |
| D-8 | iBatis provider 归哪个 gutter 组 | **`mapperXml` 组** | 语义最接近（都是「XML 里的 SQL 语句」）；新增独立组会多一个设置开关与 i18n 面 |
| D-9 | iBatis 新件包路径与命名 | 待定，需先与既有风格对齐（`xml/` 下扁平放 `SqlMapXxx` vs 新子包 `xml/ibatis/`） | 仓内既有是扁平（`PagesXmlParser` 直接放 `xml/`）；解析+渲染+模型共 6~8 件，扁平会显著抬高 `xml/` 目录密度 |
| D-10 | 「其他 ORM 矩阵」编号前缀 | 待定，**取号前必须 grep 查重** | 已有 SD/JO/QD/EQ/JM/XB/DS/FM/DV/BS/ST，需避撞 |
| D-11 | 券 0 的 master 止血包是否与 T-PerfSweep30 券01a 合并投放 | 待定 | 两项都削预览打开耗时，合并投放用户观感更好，但跨任务耦合 |

---

## 七之二、拍板记录（2026-11-09，用户指令）

用户 2026-11-09 指令：「点名的也根据你推荐来，本次从主分支开始实施，使用多个子智能体后台并行开发提高效率，如有冲突，使用单独的工作树和分支，最后由主控来进行合并，合并后清理无用的分支和工作树，只跑定向测试全量测试和门禁交给 ci」。

据此**冻结**全部决策点（下表「拍板值」列为施工依据，实施者不得自行变更）：

| # | 决策 | 拍板值 | 备注 |
|---|---|---|---|
| D-1 | 目标分支 | **master 起步**（用户指令，**覆盖**原推荐的 dev 先行） | 分支与工作树见 §8.2；巨文件预算守卫在 dev 才有，master 无此约束（合回 dev 时需复核） |
| D-2 | `SqlSource.IBATIS` | **新增** | 兑现 T-IbatisPanwei §八 D-3 欠账 |
| D-3 | 券 0 修法 | **0-3（探针移出关键路径）为主 + 可取消化 + 在证明不误伤前提下降低扫描预算**；**明令禁止**「按名字形态一刀切跳过」（`EntityAliasSweep` 的设计输入本就是表名） | 施工者须先厘清 `PagesSourceLanguage.detect` 第 4 步语义再动手 |
| D-4 | 券 0 是否拆独立任务 | **拆**（本批 L1 独立车道） | 影响面大于 iBatis 用户，先止血 |
| D-5 | DTD 内置 | **内置**（jar 内权威副本，Apache-2.0，须登记 `THIRD-PARTY-NOTICES.md`） | 「以 jar 内为准」：网站版 `sql-map-config-2.dtd` 是 `sqlMap*`、jar 内是 `sqlMap+` |
| D-6 | 版本支持范围 | **只按 2.3.x 建模**；未知标签降级为「原文 + 警告」不抛异常 | |
| D-7 | `<statement>` 手写 `?` | `param1..N` 兜底 + UI 明示「参数名不可知」 | 与通用兜底通道同口径 |
| D-8 | iBatis provider 归组 | **`mapperXml` 组** | 不新增 gutter 组 |
| D-9 | 新件包路径 | **新建子包 `xml/ibatis/`**（不往 `xml/` 扁平塞） | 解析+渲染+模型约 8 件，扁平会显著抬高 `xml/` 密度 |
| D-10 | ORM 矩阵编号前缀 | **`IB`**（已 grep 查重：现有 BS/DS/DV/EQ/FM/JM/JO/QD/SD/XB，`IB` 未占用） | |
| D-11 | 是否与 T-PerfSweep30 券01a 合并投放 | **不做**（该优化只在 dev，本次 master 线不跨任务合并）；**登记为关联项** | master 上「控制台准备 + 预热」那条链（§券 0 瓶颈 2/3）仍存在 |

---

## 八、实施分工与实施记录

### 8.1 车道划分原则

写集按**文件级**互斥（同目录不同文件不算冲突；git 合并只对同文件同区域敏感）。共享文件（`messages/*.properties`、`plugin.xml`）的处理口径：**只追加、不重排**，合并冲突由主控按「保留双方」解。

### 8.2 实施记录

**第一波（2026-11-09，三路并行，基线 master `054af3f8`）**

| 车道 | 分支 / 工作树 | 券 | 写集 | 状态 |
|---|---|---|---|---|
| L1 | `t-ibatis-slowfix` / `.worktrees/ibatis-slowfix` | 券 0（慢路径止血） | `psi/jpa/EntityAliasSweep.kt`、`ui/PagesEntityProbeResolver.kt`、`xml/PagesSourceLanguage.kt`、`ui/XmlInlineSqlPreviewService.kt`、`ui/SqlPreviewService.kt`（探针时序）+ 测试 | 实施中 |
| L2 | `t-ibatis-core` / `.worktrees/ibatis-core` | 券 B（解析/模型/渲染）+ 保真对照骨架 | 新建 `xml/ibatis/**` + 新建 `test/.../xml/ibatis/**` + `build.gradle.kts`（仅加一行测试面依赖） | 实施中 |
| L3 | `t-ibatis-recognize` / `.worktrees/ibatis-recognize` | 券 A 识别器 + 券 H（`SqlSource.IBATIS`） | 新建 `xml/ibatis/IbatisXmlRecognizer.kt`、`log/LogSqlModels.kt`、`log/MyBatisLogParser.kt`、`log/LogEntryPreviewRecognizer.kt`、`log/SqlEntryPresentation.kt`、两个 bundle + 测试 | 实施中 |

派活硬约束（三路一致，抄自 `docs/开发流程-派活与验收清单.md`）：独立工作树 + 扁平分支名｜只跑本任务最相关定向（`./gradlew test --tests "<FQCN>"`；**`testFast` 自带固定类清单，叠加 `--tests` 必失败**）｜**不跑全量 / 不跑构建校验 / 不 `clean`**｜跑 gradle 前 `parallel_tasks.py check` → `register --name <车道名> --sandbox <工作树绝对路径>` → 跑完 `done --name <车道名>`（**禁裸 `done`**）｜每完成一件可提交的立即提交、`git add` 具体文件（**禁 `git add -A`**）、消息写文件后 `git commit -F`（**消息禁反引号**）｜报告须给改动文件 / 提交哈希 / 定向读数（类数·tests·失败·错误·skip）/ 反向验证读数 / 未完成项；**没跑过的读数不许写「全绿」**｜不 push、不合流、不建 tag、不动 `update/update.json`、不碰他人工作树｜**不改本计划册与 `CHANGELOG.md`**（主控合并后统一回填）。

**第二波（2026-11-09，实际派工与结果）**

| 车道 | 分支 / 工作树 | 券 | 结果 |
|---|---|---|---|
| L4 | `t-ibatis-wire` / `.worktrees/ibatis-wire` | 券 A 接线（让位 + 接管 + 设置 + i18n + 触发面） | `0fa46b44` + `eb49dd14`；22 类 197 例 0 失败；两条反证各 2 红；含**热路径零查询守卫**（源码扫描 + 自证样本） |
| L5 | `t-ibatis-inject` / `.worktrees/ibatis-inject` | 券 D（注入/着色/折叠/补全） | `e8553325`→`3ac7dacf` 五笔；既有 51 类 **485 例逐位不变** + 新增 3 类 35 例；两条反证（8 红 / 4 红） |
| L6 | `t-ibatis-dtd` / `.worktrees/ibatis-dtd` | 券 E（两份 DTD 离线内置与 8 条标识登记） | `56d2ba18` / `ce3ab462` / `01eb8ed5`；8 条 url 全部解析到随包 jar 内 DTD；反证 3 例 1 红 2 绿；发行包 +9.0 KiB |
| L7 | demo 仓（独立 git） | demo 语料模块 | demo `94b6285a`：`ibatis2-demo` 17 条语句 20 次渲染全通（`buildSqlMapClient` 成功＝语料语法合法）；Maven 三验（模块编译 / 根 reactor / 离线复编）全 SUCCESS |

**第三波（2026-11-09）**

| 车道 | 分支 / 工作树 | 券 | 状态 |
|---|---|---|---|
| L8 | `t-ibatis-nav` / `.worktrees/ibatis-nav` | 券 F（XML 内部 id 引用 + Java DAO → XML） | `e744b9d6`（F-1）+ `75b84d7a`（F-2）+ `049d523c`（反证补丁）；既有 41 例两次复跑逐位不变，F-1 14 例 / F-2 15 例全绿；两条反证 5 红 / 6 红；热路径 `indexScanCount==0` 有断言钉住 |
| — | — | 券 G（`parameterClass`/`parameterMap` 类型映射）、券 I（文档登记） | 券 G 待办；券 I 随本册回填完成 |

### 8.3 实施结果（主控合并与权威复跑）

**合并顺序**：L2 → L1 → L3（第一波）→ 主控修 `EnumReachabilityGuardTest` → L4 → L6 → L5（第二波）。冲突均在 `dev-support/reverse-probes/README.md`（多路同时追加登记行），一律按「保留双方」并集解，解完全文回读校验（无残留标记、行数对上）。

| 提交 | 内容 |
|---|---|
| `3080cad3` | 第一波三路合并（券 0 / 券 B / 券 A 识别器 + 券 H） |
| `d593e253` | 合并后曝出的守卫真失败修复：`IbatisStatementKind` / `IbatisTagKind` 的产出点由 `entries.firstOrNull` 改为显式全量表（守卫只统计 `EnumName.CONST` 限定形态，隐式查表产出对它不可见） |
| `f6209b02` | L4 接线 + L6 DTD（含 `plugin.xml` 尾部标准资源块） |
| `1c1c07ae` | L5 注入 |
| `c587e911` | 让位侧效应修复：Alt+Shift+Q 在 sqlMap 文件补回 iBatis 候选（三路来源收敛成单一判据 `isXmlStatementCandidate`）+ L21 升版 |
| `80e26053` | L8 券 F（SQL 跳转）；两处尾部追加块冲突按「保留双方」解（`plugin.xml` 两个 `<extensions>` 各自闭合、`reverse-probes/README.md` 并集；解完用 python 复核配平与冲突标记归零） |

**合并态定向复跑（权威读数，非各支自报之和）**

| 轮次 | 覆盖 | 读数 |
|---|---|---|
| 第一轮（未清产物） | `xml.*` `log.*` `convention.*` `psi.jpa.*` `*Pages*` `*XmlInlineSql*` `i18n.*` `ui.gutter.*` | 299 类 2981 例 **202 红**——全部是合成构造器 `NoSuchMethodError`（`HibernateLogParserTest` 56/56 全红，而该合并无人碰过 Hibernate） |
| 第二轮（`rm -rf build/classes` 后） | 同上 + `log.agent.*` + 守卫 | **186 类 2041 例 0 失败 0 错误 0 skip**，BUILD SUCCESSFUL |
| 第三轮（清产物） | `actions.ConvertToSqlActionTest` `ui.IbatisXmlPreviewServiceTest` `ui.gutter.*` `xml.*` `config.*` | **93 类 806 例 0 失败 0 错误 0 skip**，BUILD SUCCESSFUL |
| **第四轮（最终，券 F 合入后，清产物）** | `xml.*` `log.*` `log.agent.*` `convention.*` `config.*` `psi.jpa.*` `ui.gutter.*` `ui.inject.*` `ui.highlight.*` `ui.ref.*` `ui.nav.*` `actions.*` `i18n.*` `*Pages*` `*XmlInlineSql*` `*Ibatis*` | **397 类 3906 例 0 失败 0 错误 0 skip**，BUILD SUCCESSFUL（8m39s）；关键类抽查：跳转 14/15、`ConvertToSqlActionTest` 7、`IbatisDtdOfflineMappingTest` 3、`SqlMapFidelityTest` 3、`EnumReachabilityGuardTest` 3、`MapperNavigationGotoDeclarationHandlerTest` 16（MyBatis 零回归）|

口径：`--tests` 多 pattern 是并集且未命中会被静默忽略，因此每轮都按 `build/test-results/test/*.xml` 的**类数**核对命中，不看 `BUILD SUCCESSFUL`。退出码写进日志文件后读取（不在管道尾取 `$?`，L22）。

**实施期新发现（已入册）**

1. **`L21` 升为 Seen 4×**：最高频触发源是 `config/PluginSettings.State` 加字段（任何加字段的提交都会让全仓引用旧构造器 / `copy$default` 的测试类失效）。判据＝某类整类全红且报错为 `<init>` / `copy$default` 的 `NoSuchMethodError` → 先清产物。
2. **`<sql>` 片段必须声明在引用它的语句之前**（demo 实测，iBatis 配置期按文档顺序内联，片段放后面 `buildSqlMapClient` 直接抛）。本仓渲染器是「先收全表再展开」，**支持前向引用**——比引擎宽容但**当前不给告警**，已登记为后续项（缺省方向安全，但要补 warning 让不可运行的文件可见）。
3. **iBatis 属性值不注入**（L5 判定）：属性名与 MyBatis 大面积重叠，注入会改变 `<mapper>` 既有行为；且 iBatis 无表达式语言。结论写进 `XmlSynHiGate.allowAttributeValue` 的 KDoc 防回退。
4. **平台 EP 属性名是 `path` 不是 `resourcePath`**（`javap -v` 显示 `resourcePath` 字段带 `@Attribute("path")`），本册任务书原写法有误，已由 L6 订正。
5. **`dynamic="true"` 的适用面订正**：它是 `<extensionPoint>` **声明**属性，扩展**注册**元素不承载它；真正的判据是「注册落在平台 dynamic 点位」。本册 §6.9 原表述过宽，以本条为准。

**收口约定**：各车道自提交、不推送、不合流；主控在 master 上按「保留双方」合并冲突，合并后**只跑定向**复跑，全量与门禁交 CI；合并完成清理本批分支与工作树（`git worktree remove` + `git branch -d`；删分支前先验 tip 可达性，见 `pitfall-branch-delete-orphans-unmerged-tip`）。


