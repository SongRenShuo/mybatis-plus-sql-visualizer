# T-RuntimeConcatAndDeleteNull：运行期拼接占位破坏 SQL 与 `delete(null)` 无预览（2026-11-06）

- 立项：2026-11-06 12:55
- 状态：**已实施**，集成分支 `t-runtime-write-integration` 待合并 master——R1/R2/R3 `53de1ee4`、R4 `6fbc763b`、JPA 券 `0c26618b`；合流回归修复与最终读数见 §十三 13.6
- 目标分支：`master`（实施在集成分支 `t-runtime-write-integration`，按用户要求暂不合 master）
- 来源：用户两条反馈 + 两张截图
  1. 「运行时的数据查询sql拼接的有问题」——`GBAreaCalpointCodeServiceImpl.getAreaPowerGeneration()` 的 InfluxDB SQL 预览里出现 Java 三元表达式原文与悬空 `*/`，`WHERE (...)` 的右括号丢失；
  2. 「这种调用没 sql 预览图标 `deviceInfoMapper.delete(null)`，而 `deviceInfoMapper.delete(new LambdaQueryWrapper<DeviceInfoEntity>().eq(DeviceInfoEntity::getId, 1))` 就可以」
- 取证方式：`master`（`10b04950`）源码静态走查 + 用户截图；MyBatis-Plus `3.5.17` jar 用 `javap` 核对签名与注入 SQL

## 一、结论

**问题一不是用户代码的运行期 SQL 有错**。`idStr` 在循环里拼出 `id='1' or id='2' `，再拼进 `WHERE (...)`，运行期产出是正确的 InfluxQL；出错的是插件对这段运行期拼接的**静态还原**。

根因有三层，前两层叠加造成截图里那条不可用 SQL：

1. **占位符格式本身不是合法 SQL**（P0）。`SqlStringConcatenator` 对静态不可求值的片段输出 `/*运行时值*/` + **原样 Java 源码** + `*/`。前缀 `/*运行时值*/` 是一个**当场闭合**的块注释，源码因此变成活 SQL；末尾 `*/` 悬空；源码里的 `"` / `'` 又会把 SQL 词法器带偏——截图里 `WHERE (...)` 的右括号丢失与这条链路一致（P-1 待用格式化器复现确认）（`SqlStringConcatenator.kt:174/228/284`）。
2. **条件表达式没有分支处理**（P1）。`concat()` 的分派只认字面量 / `+` 拼接 / 变量引用（`SqlStringConcatenator.kt:65-73`），`k == 0 ? "..." : "..."` 落到兜底分支，整段三元被当成一个运行时值原样输出。
3. **控制流被当直线代码**（P1，同族）。`collectAppendCalls` 收集方法体内全部 `append` 后按 offset 线性拼接（`SqlStringConcatenator.kt:255-292`），不区分循环 / 条件分支——用户代码的 `for` 循环只还原一次，既有测试里 `if (StringUtils.hasText(username))` 内的可选条件也被当成必选片段拼进去。

**问题二的根因是注册表缺项**：`delete` 从未登记进 `BatchOperationConverter.BATCH_OPERATIONS`（`BatchOperationConverter.kt:69-110`）。`delete(new LambdaQueryWrapper<>()...)` 的图标来自 **Wrapper 链锚点**（`new` 关键字，场景 A），跟 `delete` 这个方法名无关；`delete(null)` 没有 Wrapper 链可锚，批量锚点名单又不认 `delete`，于是 gutter 与预览两处都没有入口。

**同类审计**（§3.6）：添加族（`insert`/`save`）已覆盖，没有同类缺口；修改族除 `updateById` 外，`update(entity, null)` 与 `delete(null)` 同型缺失；`remove(null)` 同族；查询族（`selectOne`/`selectPage`/`exists`）是同一模式的更大一片，另券处理。

**防回滚是硬约束**：`delete` / `update` / `remove` 同时是 Wrapper 工厂名、链终结点、服务链消费名，新增方法名级锚点的最高风险是同一语句出现两枚图标。本册用「空 Wrapper 精确形态 + 复用 `hasWrapperArgument` 让位 + 场景 C 顺序不动 + 整文件锚点集合断言」四道防线钉住，见 §4 R4.5 与 §6.2 第 6/7 项。

## 二、问题一：证据链

### 2.1 用户代码与预览产物

```java
StringBuilder idStr = new StringBuilder();
for (int k = 0; k < ids.size(); k++) {
    idStr.append(k == 0 ? "id='" + ids.get(k) + "' " : "or id='" + ids.get(k) + "' ");
}
String sql = "SELECT * FROM hplt.autogen.calpoint " +
    " WHERE (" + idStr + ") and time >= '" + startTime + "' tz('Asia/Shanghai')";
```

预览面板实际显示（截图第 5~7 行，原样摘录）：

```
/*运行时值*/
k == 0 ? "id='" + ids.get(k) + "' " : "or id='" + ids.get(k) + "' " /* */
AND time >= '/*运行时值*/startTime*/' tz ('Asia/Shanghai') );
```

对照原 SQL，`WHERE (...)` 的右括号消失；`AND time` 前面的空格数量也不对（源码里 `") and time >= '"` 的 `)` 被词法器吞掉）。这不是格式化器的小毛病，是**活 SQL 里出现了 Java 源码**导致的连带损伤。

### 2.2 根因 A：占位符是「闭合注释 + 活源码 + 悬空结束符」

三处兜底用的是同一模板（`SqlStringConcatenator.kt:174/228/284`）：

```kotlin
parts += "$DYNAMIC_PLACEHOLDER_PREFIX${operand.text}*/"   // 前缀 = "/*运行时值*/"
```

展开后是：

```
/*运行时值*/   k == 0 ? "id='" + ids.get(k) + "' " : ...   */
└─ 完整注释 ─┘ └────────── 活 SQL（不是注释）──────────┘   └ 悬空
```

- `/*运行时值*/` 自己闭合，后面的源码没有被任何注释包住；
- 源码里的 `"` 在 MySQL 里是标识符引用、`'` 是字符串边界，词法器从这里开始错位；
- 末尾的 `*/` 没有对应的 `/*`，是非法 token。

这不是「预览不完整」的合理降级，是**降级产物本身破坏了 SQL 的语法结构**。面板横幅「预览未完整还原」只能解释「内容不全」，解释不了「括号没了、引号错位」。

### 2.3 根因 B：`PsiConditionalExpression` 没有分派

`concat()` 的分派（`SqlStringConcatenator.kt:65-73`）：

```kotlin
return when (expr) {
    is PsiLiteralExpression -> concatLiteral(expr)
    is PsiBinaryExpression, is PsiPolyadicExpression -> concatPolyadic(expr)
    is PsiReferenceExpression -> concatReference(expr)
    else -> null          // ← 三元表达式落到这里
}
```

三元表达式的两个分支都是字符串拼接（`"id='" + ids.get(k) + "' "`），本来可以聚合；因为没分派，整个三元被当成一个不可求值操作数，走 2.2 的兜底。

`startTime` 是另一种形态：`concatReference` 只看 initializer 的四种类型（字面量 / 拼接 / 引用 / `new StringBuilder`，`SqlStringConcatenator.kt:189-200`），`DateUtils.format(...)` 是方法调用，落到 `else -> null`，同样走兜底。

### 2.4 根因 C：控制流被当直线代码

`collectAppendCalls`（`SqlStringConcatenator.kt:255-292`）的做法是「把方法体内所有对目标变量的 `append` 按 offset 排序后依次拼接」，没有判断这些调用是否在循环里、是否在条件分支里。两个已存在的证据：

1. 用户代码的 `for` 循环：`idStr.append(...)` 只还原一次，预览暗示 `idStr` 只有一个元素；
2. 既有用例 `JdbcTemplateSqlPreviewServiceTest.testPreview_dynamicSqlWithStringBuilder_runtimeValuesMarkedIncomplete`（`src/test/.../JdbcTemplateSqlPreviewServiceTest.kt:137-188`）里：

```java
if (StringUtils.hasText(username)) {
    sql.append("AND u.username LIKE '%").append(username).append("%' ");
}
```

预览把这段条件片段当成必选片段直接拼进 `WHERE`。用例只断言「片段在场」，没有断言「它是否该在场」，所以一直绿。

「含运行时值 → `complete=false`」的降级标记在这两类场景下都会置位（循环变量 / 参数引用求值失败），但**片段仍然以活 SQL 的形态出现**，用户无法从 SQL 本身看出「这段可能不出现 / 可能重复 N 次」。

### 2.5 影响面

`SqlStringConcatenator` 被三条预览通道共用：

| 通道 | 入口 | 消费点 |
|---|---|---|
| JdbcTemplate / 自研 DAO 字符串 SQL | `JdbcTemplateSqlLocator.locateFromCall` | `JdbcTemplateSqlPreviewService.preview` |
| 自定义 DAO HQL | `CustomDaoHqlLocator` | `concatUnwrappingToString`（`:493`） |
| Hibernate HQL | `HqlQueryLocator` | `concatUnwrappingToString`（`:386`） |

因此 R1/R2/R3 的改动会同时影响 JDBC SQL、自定义 DAO HQL、Hibernate HQL 三处预览；三处都有对应测试类，见 §6。

## 三、问题二：证据链

### 3.1 有图标的那个为什么有

`deviceInfoMapper.delete(new LambdaQueryWrapper<DeviceInfoEntity>().eq(...))` 的图标挂在 **`new` 关键字**上，走的是 `WrapperAnchorDetector` 场景 A（`WrapperAnchorDetector.kt:90-95`）：`new` 的父节点是 `PsiNewExpression`，类型 `LambdaQueryWrapper` 命中 `WrapperLocator.detectWrapperType`，且不在 Wrapper 变量初始化表达式内 → 返回锚点。

点击后 `WrapperLocator.locateWithTable` 认出整条链，`delete` 在 `deleteConsumerMethodNames` 里（`WrapperLocator.kt:1407`），链被标记为删除消费，最终渲染 `DELETE FROM ... WHERE ...`。**整条路径没有用到 `delete` 方法名做锚点**。

### 3.2 `delete(null)` 为什么没有

gutter 快速路径只问 `WrapperAnchorDetector.anchorElement`（`SqlGutterLineMarkerProvider.kt:50`）。逐个场景排除：

| 场景 | 判定 | `delete(null)` 是否命中 |
|---|---|---|
| A（`new` 关键字） | `new` 的类型是 Wrapper | 否，没有 `new` |
| B（Wrapper 变量名） | 变量类型是 Wrapper | 否，`deviceInfoMapper` 不是 Wrapper |
| C（工厂方法名 / 服务链） | 方法名 ∈ `wrapperFactoryMethodNames` 或服务链起点 | 否，`delete` 不在服务链起点名单 |
| D（批量操作） | 方法名 ∈ `BatchOperationConverter.batchOperationMethodNames` | **否，`delete` 不在 `BATCH_OPERATIONS`** |

`BATCH_OPERATIONS`（`BatchOperationConverter.kt:69-110`）登记了 `deleteById` / `deleteByIds` / `removeById` / `deleteByMap` / `removeByMap`，唯独没有 `delete(Wrapper)`。`git log -S` 也查不到曾经加过又删掉的记录——是遗漏，不是刻意排除。

另外两条 provider 也接不住：

- `JdbcTemplateSqlGutterLineMarkerProvider` 要求「存在字符串形态实参」（`JdbcTemplateSqlGutterLineMarkerProvider.kt:72`）。`null` 字面量不是字符串 → 不锚；且 `PreviewReceiverResolver.classify` 按 `Mapper` 后缀把 `deviceInfoMapper` 归为 `CUSTOM_DAO`（`PreviewReceiverResolver.kt:37,126`），该 provider 本来就排除 CUSTOM_DAO。
- JPA CRUD 锚点（场景 E）要求 receiver 是 Repository / EntityManager / HibernateTemplate（`WrapperAnchorDetector.kt:167-171`）。MyBatis mapper 不是 JPA 家族 → 不锚。

### 3.3 预览层同样没有入口

即使绕过 gutter 用右键 / 快捷键（`ConvertToSqlAction`）触发，`SqlPreviewService.preview` 的第 3 步是批量路径（`SqlPreviewService.kt:521-531`），而 `BatchOperationConverter.convert` 第一件事就是查注册表：

```kotlin
val operation = BATCH_OPERATIONS[methodName] ?: return null   // :194
```

`delete` 不在表里 → 直接返回 null。第 1 步 Wrapper 路径对 `delete(null)` 也无链可定位。所以 `delete(null)` 是「图标 + 预览」双缺失，不是单纯少一个图标。

### 3.4 MyBatis-Plus 侧的真实语义（`javap` 3.5.17 实证）

- `BaseMapper.delete(Wrapper<T>)` 存在（`javap com.baomidou.mybatisplus.core.mapper.BaseMapper`），参数允许 `null`；
- 注入器 `Delete`（`com.baomidou.mybatisplus.core.injector.methods.Delete`）按 `TableInfo.isWithLogicDelete()` 分两条：
  - 无逻辑删除 → `SqlMethod.DELETE`：`DELETE FROM <table>` + `sqlWhereEntityWrapper`；wrapper 为 `null` 时该片段不产生 WHERE；
  - 有逻辑删除 → `SqlMethod.LOGIC_DELETE`：`UPDATE <table> SET <logic set>` + `sqlWhereEntityWrapper`；后者无条件追加 `TableInfo.getLogicDeleteSql`，即 `WHERE deleted = 0`。

因此预览应当输出：

```sql
-- 无 @TableLogic
DELETE FROM device_info_entity

-- 有 @TableLogic
UPDATE device_info_entity
SET deleted = 1
WHERE deleted = 0
```

并附「无 WHERE 条件，将删除全表数据」警示（与 JPA 的 `BLOCK_ATTACK_WARNING` 同文案）。

### 3.5 相关边界

- JPA `repository.delete(entity)` 目前也没有 gutter 图标：`delete` 被 `WRAPPER_FACTORY_COLLIDING_NAMES` 从 JPA 锚点名单里剔除（`JpaCrudOperationConverter.kt:153-167`），注释写明「宁可漏标不可误标」。按用户意见**另立项**，见 [T-JpaDeleteAnchor 开发计划](20261106-1330-T-JpaDeleteAnchor-JPA仓储delete与HibernateTemplate-update调用行预览图标-开发计划.md)；本券不碰 JPA 锚点顺序。
- Kotlin 侧走 `KtWrapperDetector` 文本级判定，本券不改（该通道明确不做无法生成 SQL 的链）。

### 3.6 同类缺口审计：添加 / 修改 / 查询

按 `BaseMapper`（`javap` 3.5.17 实证）与 `IService` 的方法面逐条比对 `BATCH_OPERATIONS`、`WrapperAnchorDetector` 场景 A~E、`WrapperLocator` 消费名单：

| 方法 | 注册表 | 调用行锚点（Wrapper 为 null / 变量时） | 预览 | 结论 |
|---|---|---|---|---|
| `insert(T)` / `insert(Collection[, int])` | ✅ INSERT | ✅ 场景 D | ✅ | **添加已覆盖**；`insert(null)` 会出模板但运行期必 NPE（无害的假正例） |
| `save` / `saveBatch` / `saveOrUpdate` / `insertOrUpdate` | ✅ | ✅ | ✅ | 已覆盖 |
| `updateById` / `updateBatchById` | ✅ UPDATE | ✅ | ✅ | 已覆盖 |
| `update(T, Wrapper)` | ❌ | ❌（`update(entity, null)` 无链可锚） | ❌ | **同类缺口（修改）**，本券 R4 一并修 |
| `update(Wrapper)` | ❌ | —（wrapper 为 null 时 SQL 本身非法：`UPDATE t SET` 无赋值） | ❌ | 不补；非空 wrapper 由链锚点承载 |
| `delete(Wrapper)` | ❌ | ❌（`delete(null)`） | ❌ | 本次报告，R4 |
| `remove(Wrapper)`（IService） | ❌ | ❌（`remove(null)`） | ❌ | 同族；撞名面大，守卫可解，见 D-4 |
| `deleteById` / `deleteByIds` / `deleteByMap` | ✅ DELETE | ✅ | ✅ | 已覆盖 |
| `deleteBatchIds`（3.5.x 已废弃，`BaseMapper` 仍保留） | ❌ | ❌ | ❌ | 废弃方法，不补（与 `deleteByIds` 同语义，用户应迁移） |
| `removeById` / `removeByIds` / `removeByMap` / `removeBatchByIds` | ✅ DELETE | ✅ | ✅ | 已覆盖 |
| `selectList` / `selectMaps` / `selectObjs` / `selectCount` / `selectById` / `selectByIds` / `selectByMap` / `listByIds` / `listByMap` / `getById` / `getOptById` / `selectAll` | ✅ SELECT | ✅ | ✅ | 已覆盖 |
| `selectOne(Wrapper[, boolean])` | ❌ | ❌（`selectOne(null)`） | ❌ | 查询族同型缺口，另券 |
| `selectPage(P, Wrapper)` / `selectMapsPage` / `selectList(P, Wrapper)` / `selectMaps(P, Wrapper)` | ❌ | ❌（`selectPage(page, null)`） | ❌ | 查询族同型缺口 + 分页渲染，另券 |
| `exists(Wrapper)` | ❌ | ❌（`exists(null)`） | ❌ | 查询族同型缺口，另券 |
| `selectWithCursor(Wrapper)` | ❌ | ❌ | ❌ | 低频，另券 |

结论：

1. **添加（insert/save 族）没有同类缺口**——注册表与锚点都在，用户看到的「添加没图标」若真出现，应另查实体解析（`isLikelyEntity` / `@TableName`）或接收者守卫，而不是注册表；
2. **修改（update）确有缺口**——`updateById` 覆盖了，`update(entity, wrapper)` 没覆盖；wrapper 为 `null` 时（`update(entity, null)`，更新全表）既无链锚点也无批量入口；
3. **删除（delete/remove）**——`delete` 本次报告，`remove` 同族；
4. **查询族**（`selectOne` / `selectPage` / `exists` 等）是同一模式的更大一片，但还牵涉分页渲染，建议单独立项，不与本券混做。

`insert` 侧的另一个既有问题是**锚点过宽**：场景 D 只按方法名判定，`map.insert(k, v)` 这类非 MP 调用也会亮图标，点击后预览为 null（死图标）。本券的 `hasMpCrudReceiver` 守卫只作用于新增的 `delete` / `update` / `remove`，不改变 `insert` 的既有行为；是否把守卫扩到 `insert` 见 D-7。

## 四、修复方案

### R1 占位符改为单条合法注释（P0）

#### R1.0 形态选择：为什么是 `/* ... */`，不是 `--`

用户问「`/*运行时值*/` 这种注释是不是不太友好，正常应该是数据库注释吧」。分两层回答：

1. **它本来确实是 SQL 块注释，但现在的拼法让它不是注释**：`/*运行时值*/` 当场闭合，后面那段 Java 源码是活 SQL，末尾 `*/` 悬空。用户看到的「不像注释」是拼接 bug 造成的，不是块注释这种形态的问题。
2. **`--` 行注释不适合内联占位**：`WHERE (-- 运行时值: xxx) AND time >= ...` 会把同一行后面的 `) AND time >= ...` 全部注释掉，SQL 结构直接被吃掉。块注释 `/* ... */` 是内联安全的，也是 14 个方言都支持的标准写法。

因此默认形态改为**带空格与冒号的单行块注释**：

```sql
WHERE (/* 运行时值: k == 0 ? "id='" + ids.get(k) + "' " : ... */)
AND time >= '/* 运行时值: startTime */' tz('Asia/Shanghai')
```

比现在的 `/*运行时值*/` 多两处可读性：`/*` 与内容之间留空格、`运行时值:` 带冒号。它是一条真正的数据库注释，DB 侧执行时会整段忽略。

边界：占位落在字符串字面量内部时（`"... time >= '" + startTime + "' ..."`），DB 侧看到的是字符串内容而不是注释——`'/* 运行时值: startTime */'` 是一个字符串值。这是静态还原的边界，由面板的「未完整还原」提示兜底；要彻底消除这种歧义，需要 `?` + 参数行形态（D-6 的备选）。

备选形态（不在本券默认范围，见 D-6）：

- `?` + 参数面板行（与 Wrapper 路径一致，最像 prepared statement）——但拼接器分不清「值位置」和「语句片段位置」，`WHERE (?)` 语义不对；
- `? /* 运行时值: ... */`——两者兼得，但同样有 `WHERE (?)` 的语义问题；
- `-- 运行时值: ...`——只适合独占一行的位置，内联位置会吃掉后续 SQL。

#### R1.1 实现

把三处兜底统一收口到一个函数：

```kotlin
// SqlStringConcatenator
private fun dynamicPlaceholder(exprText: String): String
```

规则（缺一不可）：

1. **整段是一条注释**：`/* 运行时值: <label> */`，不能出现「注释先闭合、源码裸露」的形态；
2. **消除所有可能提前终止上下文的字符**：`*/` → `* /`；`'` / `"` 替换为安全同形字符（如 `’` / `”`），保证占位落在字符串字面量内部时不会截断字符串；
3. **折叠空白**（换行/制表 → 单空格），避免多行注释打乱格式化器的行结构；
4. **长度上限**（建议 120 字符，超出截断加 `…`），避免整段三元把 SQL 撑爆；
5. 常量 `DYNAMIC_PLACEHOLDER_PREFIX` 的取值随之改为 `"/* 运行时值: "`（或保留常量名、改值），三处调用点改传表达式文本而不是自己拼 `*/`。

改完后用户样本的预览应为：

```
WHERE (/* 运行时值: k == 0 ? "id='" + ids.get(k) + "' " : ... */)
AND time >= '/* 运行时值: startTime */' tz('Asia/Shanghai')
```

右括号回来，引号平衡，`*/` 只剩注释自己的那一个。

### R2 条件表达式分派（P1）

`concat()` 增加 `is PsiConditionalExpression -> concatConditional(expr)`：

| 条件形态 | 处理 |
|---|---|
| 条件是 `true` / `false` 字面量 | 直接聚合对应分支 |
| 两个分支都聚合成功且文本相同 | 取该文本，`complete` = 两分支的与 |
| 其余（分支不同 / 有一支失败） | 单条占位注释（R1 的函数），`complete=false` |

不猜分支、不把两个分支都拼进去——三元在运行期只会走一支，拼两支等于编造 SQL。

### R3 控制流感知（P1）

`collectAppendCalls` 对每个 `append` 计算它相对方法体的控制流位置（向上找最近的 `PsiForStatement` / `PsiForeachStatement` / `PsiWhileStatement` / `PsiDoWhileStatement` / `PsiIfStatement` / `PsiSwitchStatement` / `PsiConditionalExpression` / `PsiLambdaExpression`）：

| 位置 | 建议处理 |
|---|---|
| 循环体内 | 该 `append` 的贡献整体降级为一条占位注释（运行时重复 N 次，线性拼接必错）；`complete=false` |
| 无 `else` 的条件分支 | 保留片段，但前面插入一条说明注释（`/* 运行时值: 条件追加，可能不出现 */`）；`complete=false` |
| `if/else` 两支都对同一 builder 追加 | 两支的贡献都降级为注释（互斥分支不能同时出现在 SQL 里）；`complete=false` |
| 直线代码 | 维持现状（既有回归用例保证） |

`if` 分支「保留片段 + 注释」是信息量与安全性的折中；若用户倾向更严格（整段条件追加都降级为注释），见决策点 D-3。

### R4 写族补全：`delete` / `update` / `remove`（P0）

统一按「注册表 + 空 Wrapper 分支 + 接收者守卫 + 全表警示」四件套做，`delete` 是本次报告，`update` / `remove` 是同族缺口（§3.6）。

#### R4.1 注册表

`BATCH_OPERATIONS` 增加：

```kotlin
"delete" to Operation.DELETE,   // BaseMapper.delete(Wrapper)
"update" to Operation.UPDATE,   // BaseMapper.update(T, Wrapper)
"remove" to Operation.DELETE,   // IService.remove(Wrapper)（D-4 建议纳入）
```

`update(Wrapper)`（单参）不登记：wrapper 为 null 时 `UPDATE t SET` 无赋值，SQL 本身非法；非空 wrapper 由链锚点承载。

#### R4.2 空 Wrapper 分支

`buildDeleteBatch`（`BatchOperationConverter.kt:490-519`）增加 `delete` / `remove` 分支：

- 实参是 `null` 字面量（含一层 `PsiTypeCastExpression` 包裹）→ 不生成 WHERE；
- 实参非空且不是可解析的 Wrapper → **返回 null**（不猜条件，更不能退化成「无 WHERE」）；
- `@TableLogic` 命中 → `UPDATE ... SET <logic>=1 WHERE <logic>=0`；
- 无逻辑删除 → `DELETE FROM <table>`。

`buildUpdateBatch`（`BatchOperationConverter.kt:406-420`）增加 `update` 分支：

- 两参且第二参是 `null` 字面量（含一层 `PsiTypeCastExpression` 包裹，`update(entity, (Wrapper<X>) null)`）→ SET 走实体列（与 `updateById` 同口径），**不生成任何 WHERE**（含 `@Version` 乐观锁条件也不得生成——没有 wrapper 就没有可定位的行）；
- **不能直接复用 `modelBuild`**：`BatchModel.renderSql` 的 UPDATE 分支把 `fixedColumns` 硬编码成 `WHERE`（`BatchModel.kt:139-150`），复用会凭空多出 `WHERE id = ?`。本分支改为直接拼 SQL、`batchModel = null`（面板不提供列勾选）；不建议给 `BatchModel` 加 `whereDisabled` 标志——那会动到 `updateById` 的既有渲染路径，与「防回滚」冲突；
- 第二参非空 / 只有一参（`update(Wrapper)`）→ 返回 null（交回 Wrapper 路径或如实无预览）；
- 无 WHERE 时附全表更新警示（`⚠ 无 WHERE 条件，将更新全表数据！`）。

两处都需要把 `BatchBuild` 改成可携带 `warning`（私有 data class 加默认字段），`convert` 透传到 `BatchPreview`。

#### R4.3 锚点收窄 + 接收者守卫

场景 D 对新增名字只认「空 Wrapper」这一种精确形态，避免 `service.update(null)`（非法 SQL）这类死图标：

| 方法 | 锚点条件（除名字在注册表外） |
|---|---|
| `delete` / `remove` | 恰好 1 个实参且是 `null` 字面量（解一层 cast） |
| `update` | 至少 2 个实参且第 2 个是 `null` 字面量（解一层 cast） |

再加 `BatchOperationConverter.hasMpCrudReceiver(call)`（形态对齐 `JpaCrudOperationConverter.hasJpaCrudReceiver`）：

- qualifier 变量 / 形参 / 字段的类型（含父类型链）是 `BaseMapper` / `IService` / `ServiceImpl` / `AbstractModel`（FQN 前缀 `com.baomidou.mybatisplus` 优先，简名兜底）；
- qualifier 是 `getBaseMapper()` 或变量名为 `baseMapper` 时直接放行；
- 隐式 / 显式 `this` → 所在类父链含 `ServiceImpl` / `IService`。

守卫与空 Wrapper 判定收口成一个共享函数（如 `isNoWrapperConsumingCall(call)`），同时用于 `WrapperAnchorDetector` 场景 D（`WrapperAnchorDetector.kt:127-158`）与 `WrapperLocator.isSqlCandidate`（`WrapperLocator.kt:140-141`）——后者是「选择区多 SQL 候选」的入口，不加会让 `redisTemplate.delete(null)` 之类进入候选列表。

#### R4.4 警示

`BatchPreview` 增加 `val warning: String? = null`（`model/BatchPreview.kt:23-31`），无 WHERE 时填：

- `⚠ 无 WHERE 条件，将删除全表数据！`（delete / remove）
- `⚠ 无 WHERE 条件，将更新全表数据！`（update）

`SqlPreviewService.kt:532-541` 把 `warning = batch.warning` 透传到 `PreviewResult`。文案与 JPA 的 `BLOCK_ATTACK_WARNING`、EasyQuery/MDS 的全表警示保持一致。

#### R4.5 去重不变量（防回滚，硬性）

用户特别提醒「不要出现回滚问题，例如 sql 预览图标重复出现」。本券新增的是**方法名级**锚点，而 `delete` / `update` 同时是 Wrapper 链工厂名、链终结点、服务链消费名，重复图标是最高风险项。R4.3 的「空 Wrapper 精确形态」是结构性防线，另加一张不变量表逐形态钉住：

| 调用形态 | 期望锚点位置 | 必须不出现 |
|---|---|---|
| `mapper.delete(null)` | `delete` identifier（本券新增） | — |
| `mapper.delete(new LambdaQueryWrapper<>()...)` | `new` 关键字（场景 A） | `delete` identifier |
| `mapper.delete(wrapperVar)` | wrapper 变量声明行（场景 B） | `delete` identifier |
| `JoinWrappers.delete(Entity.class)` | `delete` 工厂方法名（场景 C） | 批量锚点 |
| `service.lambdaUpdate().eq(...).update()` | `lambdaUpdate`（链起点） | 终结点 `update` |
| `service.update(wrapper)` | wrapper 变量/工厂行 | `update` identifier |
| `mapper.update(entity, null)` | `update` identifier（本券新增） | — |
| `mapper.update(entity, new LambdaUpdateWrapper<>()...)` | `new` 关键字 | `update` identifier |
| `mapper.update(entity, wrapperVar)` | wrapper 变量声明行 | `update` identifier |
| `service.remove(null)`（D-4 纳入时） | `remove` identifier | — |
| `list.remove(null)` / `list.remove(0)` / `map.remove(k)` | 无图标（守卫拒绝） | `remove` identifier |
| `mapper.insert(entity)` | `insert` identifier（既有） | 不变（不因本券改守卫而消失） |
| `mapper.selectJoinMaps(wrapper)` | 调用行（T-MapResultVo 契约） | 不变（`MAPS_TERMINAL_NAMES` 豁免） |

落地要求：

1. 复用既有 `hasWrapperArgument`（`WrapperAnchorDetector.kt:228-242`）让位逻辑，不另写一套；
2. 场景 C 在场景 D 之前的分支顺序**不动**——`delete`/`update` 若命中工厂或服务链，C 先返回，D 不应再判；
3. 测试用 `WrapperAnchorDetectorTest.collectedAnchors()` 做**整文件锚点集合**断言（不是逐元素断言）——这是 T-GutterDupFreeze 的既有做法，能抓住「多一枚」；
4. 本券不改变 `insert` / `selectJoinMaps` / `selectJoinMapsPage` 的既有锚点行为，避免把去重契约一起改坏。

### R5 文档与触发面同步

- `docs/架构设计/预览触发面清单-说明文档.md`：第 1 行场景 D 说明、第 34 行「批量调用」示例补 `mapper.delete(null)`；
- `docs/架构设计/01-项目架构总览.md` 的 `psource` 一行如需提占位口径，补一句（可选）；
- `CHANGELOG.md` 的 `[Unreleased]` 按「提交即回填」补一条（R1/R2/R3 一句、R4 一句，带提交哈希）。

## 五、影响面与兼容性

| 改动 | 直接用户可见变化 | 风险 |
|---|---|---|
| R1 | 所有含运行时片段的 JDBC/HQL 预览里，`/*运行时值*/源码*/` 变成 `/* 运行时值: 源码 */`；之前被引号破坏的 SQL 恢复合法 | 占位文本形态变化，`CustomDaoHqlLocatorTest:554` 的 `contains("/*运行时值*/")` 断言需同步改为 `contains("/* 运行时值: ")` |
| R2 | 三元条件不再整段吐源码；常量条件能取到真实分支 | 三元分支相同才折叠，分支不同仍占位——用户可能期待「看到两个分支」，需在文档/提示里说明 |
| R3 | 循环内片段不再假装只出现一次；可选条件带注释 | 既有 `if` 用例的输出变化（断言多为 `contains`，大概率仍绿，但需逐条核对） |
| R4 | `mapper.delete(null)` / `mapper.update(entity, null)` / `service.remove(null)` 出现图标并可预览；全表写操作有红字警示 | 新增 resolve 判定（守卫），daemon 热路径多一次类型解析；`update` 撞名面大于 `delete`，守卫必须与空 Wrapper 判定同时生效 |

## 六、测试与验证

### 6.1 定向用例（全部加在既有测试类里，避免新类登记成本）

| 类 | 新增用例 |
|---|---|
| `SqlStringConcatenatorTest` | ① 用户三元样本 → 单条注释、无悬空 `*/`、`complete=false`；② 常量条件三元 → 取对应分支；③ 两分支相同 → 折叠；④ 循环内 append → 不线性拼接；⑤ 直线 append 回归；⑥ 占位注释形如 `/* 运行时值: ... */`（含空格与冒号） |
| `JdbcTemplateSqlPreviewServiceTest` | ① 用户 `getAreaPowerGeneration` 样本 → 括号在场、引号平衡、warning 在场；② 既有 `if` 样本按 R3 新口径核对 |
| `WrapperAnchorDetectorTest` / `WrapperAnchorDetectorNegativeTest` | ① `mapper.delete(null)` → `delete` identifier 是锚点；② `mapper.delete(new LambdaQueryWrapper<>()...)` → `delete` 不锚（让位给 `new`）；③ `redisTemplate.delete(keys)` / `cache.delete(null)` → 不锚；④ `list.remove(null)`（`List.remove(Object)` 合法且常见）→ 不锚；⑤ `mapper.update(entity, null)` → 锚点；⑥ `mapper.update(entity, wrapperVar)` → `update` 不锚（让位给变量行）；⑦ `service.update(null)`（单参）→ 不锚 |
| `SqlPreviewServiceBatchTest` | ① `delete(null)` 无逻辑删除 → `DELETE FROM ...` + 全表警示；② `@TableLogic` → `UPDATE ... SET deleted = 1 WHERE deleted = 0`；③ `delete(wrapper)` 回归（Wrapper 路径仍出条件）；④ `update(entity, null)` → `UPDATE ... SET ...`（无 WHERE）+ 全表更新警示；⑤ `update(entity, wrapperVar)` 回归（Wrapper 路径仍出条件） |
| `GutterPreviewConsistencyTest` | `delete(null)` / `update(entity, null)` 锚点存在 → preview 非 null（接缝守卫） |
| `CustomDaoHqlLocatorTest` | 占位断言改新前缀（`:554`） |

### 6.2 反向验证（本仓纪律，必须贴两次读数）

1. 去掉 R1 的 `*/` / 引号清洗 → 用户样本用例必红（悬空 `*/` 或引号不平衡）；
2. 把 R2 的 `concatConditional` 临时改成返回 `null`（退回兜底） → 三元用例必红（占位里出现完整源码而不是「分支折叠/单条注释」）；
3. 去掉 R3 的控制流判定 → 循环用例必红（片段出现一次且无标记）；
4. 去掉 `BATCH_OPERATIONS` 的 `delete` → `delete(null)` 的锚点与预览用例必红；
5. 去掉 `hasMpCrudReceiver` → `redisTemplate.delete(null)` 负例必红（出现图标）；
6. **去掉空 Wrapper 精确判定**（改成「名字在注册表就锚」）→ `delete(new LambdaQueryWrapper<>()...)` / `update(entity, wrapperVar)` 的整文件锚点集合用例必红（同一语句出现两枚图标）；
7. **去掉 `hasWrapperArgument` 让位** → 同样触发重复图标用例变红。

每项贴「改前红 / 改后绿」两次读数。第 6/7 项是本次用户特别提醒的「回滚 / 重复图标」防线，必须单独留读数。

### 6.3 分层与命令

- 定向（实施者自跑）：

```
./gradlew test --tests "cn.song.mybatisplus.visualizer.psource.SqlStringConcatenatorTest" \
  --tests "cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewServiceTest" \
  --tests "cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetectorTest" \
  --tests "cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetectorNegativeTest" \
  --tests "cn.song.mybatisplus.visualizer.ui.SqlPreviewServiceBatchTest" \
  --tests "cn.song.mybatisplus.visualizer.ui.GutterPreviewConsistencyTest" \
  --tests "cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocatorTest"
```

- 全量：所有实施完成后由只读收口代理跑一次 `testShards`（独立沙盒，按登记表）；
- 构建校验：本券不碰 `build.gradle.kts` / `plugin.xml` EP / since-build，按「大改动」判据不触发 `verifyGate/verifyPlugin/buildPlugin`。

## 七、任务拆分

| 券 | 内容 | 依赖 |
|---|---|---|
| W1 | R1 占位符收口 + 三处调用点 + `CustomDaoHqlLocatorTest` 断言更新 | 无 |
| W2 | R2 条件表达式分派 + 单测 | W1（复用 `dynamicPlaceholder`） |
| W3 | R3 控制流感知 + 单测 + 既有 `if` 用例口径核对 | W1 |
| W4 | R4 写族补全（`delete` / `update` / `remove` 登记 + 空 Wrapper 分支 + 接收者守卫 + 警示透传）+ 锚点/预览/负例/去重不变量用例 | 无 |
| W5 | R5 文档（触发面清单 / CHANGELOG）+ 定向全跑 + 反向验证（含重复图标两项）留痕 | W1~W4 |

W1/W2/W3 与 W4 写集不重叠，可并行两路（构建并发上限 2 路）。

## 八、待验证前提（先证伪再动手）

| 编号 | 前提 | 证伪方法 | 被推翻的后果 |
|---|---|---|---|
| P-1 | 截图里右括号丢失是「Java 引号把 SQL 词法器带偏」 | 用用户样本跑 `SqlFormatter.format`，在测试里断言格式化前 SQL 含 `") and time >= '"`、格式化后括号丢失；R1 后再断言括号在场 | 若与格式化器无关，问题只剩占位文本难看，R1 优先级可降为 P1 |
| P-2 | `PsiConditionalExpression` 是 Java 三元在 PSI 里的实际类型 | 用 `BasePlatformTestCase` 解析用户片段，`PsiTreeUtil.findChildOfType(file, PsiConditionalExpression::class.java)` 非空 | 需改用 `PsiTypeCastExpression` / 其他分支，R2 实现位置调整 |
| P-3 | MP 3.5.17 `delete(null)` 运行期是 `DELETE FROM t`；`@TableLogic` 转 `UPDATE ... WHERE del=0` | 已用 `javap` 核对 `BaseMapper.delete(Wrapper)` 与 `Delete`/`AbstractMethod` 注入链（本册 §3.4）；实施前再用 demo 工程跑一次真机 SQL 日志确认 | 若逻辑删除形态不同，`buildDeleteBatch` 的 WHERE 需要调整 |
| P-4 | `deviceInfoMapper` 的实体能经 `resolveServiceEntityClass` 解析 | 在测试夹具里用 `interface DeviceInfoMapper extends BaseMapper<DeviceInfoEntity>` 走一遍；真机工程里 `@TableName` 实体同理 | 解析失败则预览仍为 null；需补 `getBaseMapper()` / 字段声明类型的兜底 |
| P-5 | 接收者守卫的简名兜底不会误伤用户工程里的非 MP `BaseMapper` | 在目标工程 grep `interface .*BaseMapper` / `class .*ServiceImpl`，看是否有同名自有基类 | 有同名自有基类 → 只认 FQN 前缀，放弃简名兜底（测试夹具改用 FQN 桩） |
| P-6 | 既有 `if` 用例在 R3 新口径下仍可断言 | 先按 R3 规则手算预期输出，再改断言；不拿「跑绿了」反推口径 | 若断言大面积失效，R3 降级为「只标记、不改片段位置」的最小版本 |
| P-7 | `IService.remove(Wrapper)` 在目标 MP 版本存在且语义等同 `delete(Wrapper)` | 本地只缓存了 `mybatis-plus-core`（extension jar 不在），实施前从 demo 工程或依赖树取 `mybatis-plus-extension` 用 `javap` 核对；不存在则 D-4 直接关闭 | `remove` 无法登记，D-4 关闭，只做 `delete` / `update` |
| P-8 | `update(entity, null)` 运行期是「全列 SET + 无 WHERE」 | 用 demo 工程打日志确认；MP 的 `SqlMethod.UPDATE` 走 `sqlWhereEntityWrapper`，null wrapper 不产生 WHERE（与 §3.4 同源） | 若 MP 对 null wrapper 抛异常，则预览改为返回 null + 警示，而不是输出 SQL |
| P-9 | 现有整文件锚点集合（`collectedAnchors()`）在加 `delete`/`update`/`remove` 前后只差预期项 | 先在改动前跑一次基线并留读数，再改；改动后逐形态对照 R4.5 表 | 若基线本身就有重复图标，先修基线再叠加本券，避免把存量问题算到本券头上 |

## 九、风险

| 风险 | 缓解 |
|---|---|
| R1 的占位文本清洗把用户想看的源码改得太多 | 只替换 `*/` 与引号，保留其余原文；长度上限 120 字符并在面板 tooltip/文档说明 |
| R3 改变既有预览形态，被当成回归 | 用例逐条核对；`CHANGELOG` 写明「条件/循环拼接改为带注释降级」；保留 D-3 的严格/宽松两档 |
| `delete` / `update` / `remove` 登记后误亮（`redisTemplate.delete(keys)` 等） | `hasMpCrudReceiver` 守卫 + 负例反向验证；宁可漏标不可误标 |
| **同一语句重复图标（用户明确提醒的回滚形态）** | 空 Wrapper 精确判定 + 复用 `hasWrapperArgument` 让位 + 场景 C 顺序不动 + `collectedAnchors()` 整文件集合断言 + §6.2 第 6/7 项反向验证 |
| `delete(wrapper)` / `update(entity, wrapper)` 在批量路径被误当「无 WHERE」 | 只有 `null` 字面量走无 WHERE；非空不可解析一律返回 null |
| `update(entity, null)` 误复用 `modelBuild` → 凭空多出 `WHERE id = ?` | `BatchModel.UPDATE` 的 WHERE 是硬编码（`BatchModel.kt:139-150`）；该分支直接拼 SQL、`batchModel=null`，并在用例里断言 SQL 不含 `WHERE` |
| 新增 `BatchPreview.warning` 破坏构造点 | 字段带默认值追加在末尾；全仓只有 `BatchOperationConverter` 一处构造，改后全量编译 |
| 三通道共用拼接器，改动外溢到 HQL | 三条通道各跑既有测试类；`CustomDaoHqlLocatorTest` 的占位断言同批更新 |
| 并行会话同改 `BatchOperationConverter` / `SqlPreviewService` / `WrapperAnchorDetector` | 开工前查登记表；W4 与 W1~W3 写集不重叠，但 W4 与 JPA 另券都动 `WrapperAnchorDetector`，必须串行或同批 |

## 十、决策点

| 编号 | 问题 | 建议 |
|---|---|---|
| D-1 | `delete` 走「注册表 + 接收者守卫」还是「只为 `delete(null)` 加窄锚点」 | 选注册表 + 守卫 + 空 Wrapper 精确形态：与 `deleteById`/`removeById` 同族，`delete(wrapper)` 的让位逻辑已有（`hasWrapperArgument`），守卫把误亮面压到 MP 接收者；窄锚点只用于锚点判定，预览层仍按注册表分派 |
| D-2 | `delete(null)` / `update(entity, null)` 的预览是否要红字警示 | 要。全表删除/更新是高危动作，与 JPA `BLOCK_ATTACK`、EasyQuery/MDS 同文案 |
| D-3 | R3 对「无 else 的 if 追加」保留片段 + 注释，还是整段降级为注释 | 建议保留片段 + 注释（信息量大）；若用户更看重「不能误执行」，改整段降级 |
| D-4 | 是否同批补 `service.remove(null)` | 建议纳入。守卫 + 空 Wrapper 精确形态已把撞名面压住；负例 `list.remove(0)` / `map.remove(k)` 必测。若 P-7 证明 `IService.remove(Wrapper)` 不存在，则关闭本项 |
| D-5 | 是否顺带让 JPA `repository.delete(entity)` 也有图标 | 已按用户意见**另立项**：[T-JpaDeleteAnchor 开发计划](20261106-1330-T-JpaDeleteAnchor-JPA仓储delete与HibernateTemplate-update调用行预览图标-开发计划.md)。两券都动 `WrapperAnchorDetector`，必须串行或同批合流 |
| D-6 | 占位注释形态：`/* 运行时值: ... */` / `?` + 参数行 / `? /* ... */` / `--` | 默认 `/* 运行时值: ... */`（单行块注释，14 方言通用，内联安全）；`--` 内联会吃掉后续 SQL，不采用；`?` 形态留作后续「值位置」增强（拼接器当前分不清值与语句片段） |
| D-7 | 是否把 `hasMpCrudReceiver` 扩到既有的 `insert` / `save` 等名字 | 本券不做。`insert` 的误亮是存量行为，扩面会改变既有锚点集合，与「防回滚」相冲突；另开券评估，并先取整文件锚点基线 |

## 十一、变更记录

| 日期 | 内容 |
|---|---|
| 2026-11-06 | 立项。两问题的源码级根因、MP 3.5.17 注入 SQL 核对、R1~R5 方案、测试与反向验证清单、6 条待验证前提、5 个决策点。未改生产代码。 |
| 2026-11-06 | 按用户三点补充修订：① 增加 §3.6 同类缺口审计（`insert`/`save` 已覆盖，`update(entity, null)` / `remove(null)` 同型缺失，查询族另券）；R4 扩为写族补全（delete/update/remove）；② R1 增加「占位形态」论证，默认改为 `/* 运行时值: ... */`，并说明 `--` 内联不安全，新增 D-6；③ JPA `repository.delete(entity)` 另立项（D-5），本券不碰 JPA 锚点顺序。另按用户提醒增加 R4.5 去重不变量表、§6.2 第 6/7 项重复图标反向验证、P-9 锚点基线前提、D-7（不扩 `insert` 守卫）。 |

## 十二、实施记录

实施代理：T-RuntimeConcat（只做 R1/R2/R3；分支 `t-runtime-concat`，工作树 `mpsv-t-concat`）。R4/R5 未动，`CHANGELOG` 与 `docs/架构设计/` 由主控收口。

### 12.1 提交

| 哈希 | 内容 |
|---|---|
| `53de1ee4` | `feat(concat): well-formed runtime placeholder, ternary and control-flow awareness` —— R1/R2/R3 生产改动 + 三个测试类的新增/更新用例 |
| 本条（计划回写） | 仅文档：本文件 §十二 |

### 12.2 改动点

生产（单文件 `src/main/kotlin/cn/song/mybatisplus/visualizer/psource/SqlStringConcatenator.kt`）：

- **R1**：新增 `dynamicPlaceholder(exprText)` + `sanitizePlaceholderText`，三处兜底（`concatPolyadic` / `concatNewStringBuilder` 初值 / `collectAppendCalls` 实参）全部收口到它；`DYNAMIC_PLACEHOLDER_PREFIX` 取值改为 `"/* 运行时值: "`，收尾 `" */"` 由同一函数生成。清洗：`*/`→`* /`、`'`→`’`、`"`→`”`、空白折叠单空格、上限 120 字符（超出截断加 `…`）。
- **R2**：`concat()` 增加 `is PsiConditionalExpression -> concatConditional(expr)`；条件是 `true`/`false` 字面量取对应分支；两支聚合文本相同 → 折叠；其余 → 单条占位注释 + `complete=false`。两支从不同时拼入。
- **R3**：新增 `analyzeAppendPosition` / `containsAppendTo` / `AppendFlow` / `AppendPosition`。`collectAppendCalls` 对每条 append 向上找最近的循环（for/foreach/while/do-while，优先）/ if / switch / 三元 / lambda，遇方法即停：
  - 循环内 → 整条贡献降级为占位注释（`循环内追加，运行时重复执行`）；
  - if/else 两支都对同一 builder 追加 → 两支都降级（`互斥分支追加，运行时只取一支`）；
  - 无 else 的 if → 保留片段，前置说明注释（`条件追加，可能不出现`）；switch / 三元 / lambda 同口径（各自文案）；
  - 直线代码 → 原路径不变。
  - 同一上下文（同一循环 / 同一 if 等）只降级或只标注一次，避免链式 append 刷出三条注释。

测试：

- `SqlStringConcatenatorTest`：新增 8 例（R1 清洗 1、R2 三元 3、R3 循环/互斥/可选/直线 4）。
- `JdbcTemplateSqlPreviewServiceTest`：新增用户 `getAreaPowerGeneration` 样本 1 例；既有 `if` 样本补「条件追加应带说明注释」断言。
- `CustomDaoHqlLocatorTest:554`：`contains("/*运行时值*/")` → `contains("/* 运行时值: ")`。

### 12.3 定向读数（52 例，逐类）

| 类 | tests | failures | errors | skipped |
|---|---|---|---|---|
| `psource.SqlStringConcatenatorTest` | 15 | 0 | 0 | 0 |
| `psource.JdbcTemplateSqlPreviewServiceTest` | 10 | 0 | 0 | 0 |
| `psi.jpa.CustomDaoHqlLocatorTest` | 27 | 0 | 0 | 0 |
| 合计 | 52 | 0 | 0 | 0 |

命令：`./gradlew test --tests "...SqlStringConcatenatorTest" --tests "...JdbcTemplateSqlPreviewServiceTest" --tests "...CustomDaoHqlLocatorTest" -Pkotlin.compiler.execution.strategy=in-process`（首轮 1 红：自拟断言把 `tz('Asia/Shanghai')` 写成整串，格式化器输出 `tz ('Asia/Shanghai')`，改为分段断言后全绿；非生产断言问题）。

用户样本实际产物（测试内断言原文）：

```
SELECT * FROM hplt.autogen.calpoint
WHERE ( /* 运行时值: 循环内追加，运行时重复执行 */ )
AND time >= '/* 运行时值: startTime */' tz ('Asia/Shanghai');
```

右括号在场、引号 4 个平衡、`*/` 只剩占位自己的一个。

### 12.4 反向验证（改前红 / 改后绿）

| # | 临时改动 | 红读数 | 还原后 |
|---|---|---|---|
| ① | `sanitizePlaceholderText` 直接 `return exprText`（去掉清洗） | `SqlStringConcatenatorTest` 25 例 1 红：`应只有一个注释结束符（不允许多余的悬空结束符）: SELECT * FROM t WHERE note = '/* 运行时值: raw("a*/b") */' expected:<1> but was:<2>` | 该用例转绿 |
| ② | `concatConditional` 首行 `return null`（退回兜底） | 15 例 2 红：`expected:<AND a = 1 > but was:</* 运行时值: true ? ”AND a = 1 ” : ”AND b = 2 ” */>`、`expected:<SELECT 1 AND a = 1 > but was:<SELECT 1 /* 运行时值: flag ? ”AND a = 1 ” : ”AND a = 1 ” */>` | 两例转绿 |
| ③ | `analyzeAppendPosition` 首行恒 `return AppendPosition(AppendFlow.STRAIGHT)` | 15 例 3 红：`条件追加应标记 incomplete`、`应标明循环内重复追加: SELECT * FROM t WHERE 1=1 or id='/* 运行时值: ids.get(k) */'`（循环片段被线性拼接一次）、`互斥分支追加应标记 incomplete` | 三例转绿 |

三次临时改动均用 `git checkout -- <生产文件>` 还原，还原后 `grep -c "RED PROBE"` = 0，并复跑一次三类定向全绿（52/0/0/0）。

### 12.5 偏差与未完成

1. **R3 的 if/else 成对判定按「另一支是否存在对同一变量的 append」**，不做「两支文本互补」推断；`else` 存在但无 append 时按可选处理（保留片段 + 说明注释）。
2. **三元作为 append 实参时不重复判控制流**：R2 已把「两支不同」定为占位；`concatConditional` 的「两支相同→折叠」仍判 `complete=true`（文本确定），与 §4 R3「非直线形态 complete=false」的字面表述有细微差异，理由是运行期走哪支文本都一样。若主控要求更严格，改一行 `concatConditional` 的折叠分支即可。
3. **同一上下文去重**是新增行为（计划未写）：同一循环/同一 if 内多条链式 append 只降级/标注一次，避免预览里刷出多条同文案注释。
4. 未跑全量、未跑 `testShards`、未跑 `verifyGate/verifyPlugin/buildPlugin`、未 clean（按派活纪律）。**本报告「全绿」只指上表 52 例定向读数**。
5. P-1 未单独用 `SqlFormatter.format` 做「格式化前含 `") and time >= '"`、格式化后括号丢失」的对照实验；R1 后的产物（上表 SQL）已直接证明括号与引号恢复正常，反向验证 ① 也证明「不清洗 → 注释提前闭合」这一环。P-1 的「格式化器参与度」仍属未证前提。

## 十三、实施记录（R4 写族，t-write-family）

### 13.1 提交

| 哈希 | 内容 |
|---|---|
| `6fbc763b` | `feat(write-family): null-wrapper delete/update/remove anchors and previews`（9 文件，+764/−5） |

### 13.2 改动点

生产（5）：

- `BatchOperationConverter`：注册表加 `delete→DELETE` / `update→UPDATE` / `remove→DELETE`；新增 `nullWrapperWriteMethodNames`、`isNullWrapperShape`（delete/remove 恰 1 个 null 字面量；update ≥2 参且第 2 参 null，均解一层 `PsiTypeCastExpression`）、`hasMpCrudReceiver`、`isNoWrapperConsumingCall`（注册表 + 形态 + receiver 三道门）、`isBatchOperationCall`；`buildNoWhereDeleteBatch`（无逻辑删除 `DELETE FROM 表`；`@TableLogic` → `UPDATE 表 SET 标记 = 1 WHERE 标记 = 0`；非 null 实参返回 null）；`buildNoWhereUpdateBatch`（直接拼 SQL、`batchModel=null`、无任何 WHERE，含 `@Version` 也不生成；SET 与 updateById 同口径）；`BatchBuild.warning` 默认字段 + `convert` 透传。
- `BatchPreview`：末尾加 `val warning: String? = null`。
- `WrapperAnchorDetector`：**场景 D-0 先于工厂块**——`delete`/`update` 同时在 `wrapperFactoryMethodNames` 里，场景 C 对「非工厂、非链起点」的调用会先 `return null`，D-0 放在 C 之前才可达；形态门是精确空 Wrapper，`delete(new ...)` / `delete(wrapperVar)` / `update(entity, wrapperVar)` 不满足，仍落回 C/D，锚点归链。
- `WrapperLocator.isSqlCandidate`：改用 `BatchOperationConverter.isBatchOperationCall`。
- `SqlPreviewService`：批量分支 `warning = batch.warning`。

测试（4 个既有类）：`WrapperAnchorDetectorTest`（+6 例，整文件 `collectedAnchors()` 集合断言）、`WrapperAnchorDetectorNegativeTest`（+4 负例：`redisTemplate.delete(null)` / `cache.delete(null)` / `list.remove(null)` / `list.remove(0)`）、`SqlPreviewServiceBatchTest`（+5：delete(null) 物理/逻辑删除、update(entity,null)、remove(null)、delete(wrapper) 回归）、`GutterPreviewConsistencyTest`（+2 接缝）。

### 13.3 定向读数

| 类 | tests | failures | errors | skipped |
|---|---|---|---|---|
| `WrapperAnchorDetectorTest` | 36 | 0 | 0 | 0 |
| `WrapperAnchorDetectorNegativeTest` | 6 | 0 | 0 | 0 |
| `SqlPreviewServiceBatchTest` | 18 | 0 | 0 | 0 |
| `GutterPreviewConsistencyTest` | 10 | 0 | 0 | 0 |

### 13.4 反向验证（改前红 / 改后绿）

| # | 临时改动 | 红读数 | 还原后 |
|---|---|---|---|
| ① | 去掉注册表 `delete` | 4 红：锚点为 null、Gutter 接缝红、两个 delete(null) 预览 null | 全 0 |
| ② | 去掉 `hasMpCrudReceiver` | 1 红：`非 MP CRUD receiver 上的 delete/remove 不应产生任何锚点，实际: [delete, remove]` | 6/0 |
| ③ | 去掉空 Wrapper 精确判定（名字在注册表就锚） | 5 红：`delete(内联 Wrapper)` → `[delete, new]`、`update(entity, wrapperVar)` → `[w, update]`、`service.update(null)` → `[update]`、既有 `service.update(wrapper)` 被锚、NegativeTest `[delete, remove]` | 36/0、6/0 |
| ④ | 去掉 `hasWrapperArgument` 让位 | 1 红：`selectList(wrapperVar) 只应在 Wrapper 变量上有锚点 expected:<[w]> but was:<[w, selectList]>` | 36/0 |

偏离说明（如实）：按「空 Wrapper 精确形态」落地后，`delete/update` 的 Wrapper 实参已被形态门结构性排除，`hasWrapperArgument` 在其上只是第二道防线，因此 ④ 的可红性由既有 `selectList(wrapperVar)` 不变量行承载，不冒充「delete 行能单独抓 ④」。

### 13.5 偏差与主控注意

1. **场景 D-0 必须在场景 C 之前**（与 §4 R4.5「场景 C 顺序不动」的字面表述不同）：因为 `delete`/`update` 在 `wrapperFactoryMethodNames` 里，C 会先 `return null`；D-0 只认空 Wrapper 精确形态，`JoinWrappers.delete(Class)` 不满足，仍走 C 工厂分支。合并后顺序为 **D-0（空 Wrapper 写族）> C（工厂 / 服务链 / JPA）> D（批量）**，已用整文件锚点集合用例钉住。
2. **`remove(Wrapper)` 签名已 javap 实证**：`mybatis-plus-extension-3.5.18.jar` 的 `IRepository` 含 `remove(Wrapper<T>)` 与 `update(T, Wrapper<T>)`，`mybatis-plus-spring-3.5.18.jar` 的 `IService` 继承它；接收者守卫按 `com.baomidou.mybatisplus` FQN 前缀判定，3.5.17 / 3.5.18 包路径差异不影响（P-7 关闭）。
3. `update(entity, null)` 的 SET 排除 `@Version` 列且不生成版本 WHERE；只有主键（+版本列）的实体会因「无 SET 列」返回 null（`UPDATE 表 SET` 非法）。
4. **Bash 管道陷阱**：`./gradlew ... | tail` 后台跑时构建失败仍可能报 exit 0；后续一律 `> log 2>&1; echo EXIT=$?` 并回读 `build/test-results/test/TEST-*.xml` 计数。
5. 计划 §R4.5 的 `service.remove(null)` 行（D-4）已实现并有锚点 + 预览两类用例；`insert` / `selectJoinMaps` / `selectJoinMapsPage` 的既有锚点路径未改（D-7 未扩守卫）。

### 13.6 合流说明

- 三条实施分支合入 `t-runtime-write-integration`：JPA 券 `008c5c9a`、拼接器券 `9b81f72a`、写族券（本条，合并提交见下方「主控合流收口」）。
- `WrapperAnchorDetector.kt` 的 C 券改动（场景 C 内 JPA 分支）与 B 券改动（场景 D-0）位置不同，`ort` 自动合并成功；合并后逐形态核对：`repository.delete(entity)` / `ht.delete(entity)` / `ht.update(entity)` 仍由 C 的 JPA 分支锚定，`delete(null)` / `update(entity,null)` / `remove(null)` 由 D-0 锚定，`JoinWrappers.delete/update` 仍归工厂，`service.update(wrapper)` / 链终结点 `update` 不锚。
