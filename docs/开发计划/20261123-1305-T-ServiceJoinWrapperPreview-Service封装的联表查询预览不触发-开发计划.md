# Service 封装的联表查询预览不触发（自定义 Wrapper 子类）（T-ServiceJoinWrapperPreview）-开发计划

- **日期**：2026-11-23 13:05
- **编号声明**：本册新占 **D256**（D255 册 :4 声明「下一全新 D256 / V246 / O-164」；`grep -rn "D256" docs/` 除该指针外零占用）；下一全新 **D257 / V247 / O-165**。
- **来源**：用户 2026-11-23 真机反馈（两张截图）——业务层 `expPreMasterFileRepository.selectJoinList(ExpPreMasterFileDTO.class, jwEpmfe)` 无法触发 SQL 预览；用户补充「ServiceImpl 是 plus 的」，即自研 `CommonServiceImpl` 继承 MyBatis-Plus `ServiceImpl`、把 MPJ 的 `selectJoin*` 再包了一层。
- **范围**：`psi/WrapperLocator` 的 Wrapper 类型识别（父链/继承）+ `psi/WrapperAnchorDetector` 的锚点集合 + `psi/BatchOperationConverter` 的错误 SQL 守卫；不改 converter 渲染语义、不改执行链、不改面板布局。

## 一、现象与复现（按用户截图逐步对照）

| # | 用户动作 | 实际观察 | 期望 |
|---|---|---|---|
| S1 | 光标放在 `expPreMasterFileRepository.selectJoinList(...)` 调用行，触发预览 | 无任何结果（不弹面板 / 提示无可用预览） | 产出 `SELECT ... FROM ... WHERE ...`（含联表时含 JOIN） |
| S2 | 查看 `JoinLambdaWrapper<ExpPreMasterFileEntity> jwEpmfe = new JoinLambdaWrapper<>();` 声明行 | 无 gutter 预览图标 | 有图标，点击可预览 |
| S3 | 把 wrapper 换成 `MPJLambdaWrapper`（官方类名），其余结构不动 | 可预览 | 与 S1/S2 一致 |

### 复现装置（已落地，两处）

1. **demo 项目用例**（用户点验用）：`mybatis-plus-sql-visualizer-demo/mybatis-plus-join-demo`
   - `cn/song/mpjdemo/customwrapper/BaseJoin.java`：自研 wrapper 公共基接口（`extends MPJBaseJoin`）
   - `cn/song/mpjdemo/customwrapper/Wrapper.java`：自研最底层抽象基类（`extends MPJLambdaWrapper`，复刻用户第四层）
   - `cn/song/mpjdemo/customwrapper/AbstractJoinWrapper.java`：自研 join 抽象基类（`extends Wrapper<T>`，复刻用户第三层）
   - `cn/song/mpjdemo/customwrapper/AbstractLambdaJoinWrapper.java`：自研 lambda 中间基类（`extends AbstractJoinWrapper`，复刻用户第二层）
   - `cn/song/mpjdemo/customwrapper/JoinLambdaWrapper.java`：自研子类（`extends AbstractLambdaJoinWrapper`，复刻用户第一层）
   - `cn/song/mpjdemo/customwrapper/CommonService.java` / `CommonServiceImpl.java`：自研 Service 封装（`extends ServiceImpl`）
   - `cn/song/mpjdemo/customwrapper/ExpPreMasterFileRepository.java` / `...Impl.java`：Repository 形态
   - `cn/song/mpjdemo/customwrapper/ExpPreMasterFileQueryService.java`：三个调用点（S1 原样 / 带 leftJoin / 官方 wrapper 对照）
   - 编译验证：`mvnd -o -DskipTests compile` 通过（2026-11-23 12:35）
2. **插件仓 PSI 复现测试**：`src/test/kotlin/.../psi/CustomWrapperServicePreviewTest.kt`
   - 定向读数：见 §九实施记录（本计划落盘时同步回填）

## 二、现状核查（源码级实证，非推断）

| # | 事实 | 证据 |
|---|---|---|
| F1 | **Wrapper 类型识别只按简单类名白名单**：`detectWrapperType` 用 `PsiClassType.className` 与 13 个官方类名逐一匹配，`JoinLambdaWrapper` 不在集合内 | `psi/WrapperLocator.kt:1702-1727` |
| F2 | **变量判定没有父链回退**：`isWrapperVariable` 先 `detectWrapperType(variable.type)`，再仅对字面基类型 `Wrapper` / `AbstractWrapper` 做 initializer 降级；声明类型是自研子类时直接 `return false` | `psi/WrapperLocator.kt:287-297`、`:337-338` |
| F3 | **链解析三顺位共用同一浅层判定**：`buildChain` 依次 `detectWrapperType(variable.type)` → `detectWrapperTypeFromInitializer` → `wrapperTypeFromInitializerChain`，三者最终都落到 `detectWrapperType`，自定义子类全落空 → `locateWithTable` 返回 null | `psi/WrapperLocator.kt:963-971`、`:344-354` |
| F4 | **Service 封装不是断点**：`findWrapperVariable` 已能在任何方法调用处扫「直接实参」，把实参引用 resolve 成 wrapper 变量；只要类型认得出，`repository.selectJoinList(..., wrapper)` 调用行即可定位 | `psi/WrapperLocator.kt:239-262`（③ 实参分支） |
| F5 | **`selectJoinList` / `selectJoinOne` / `selectJoinMap` 没有任何注册**：`BATCH_OPERATIONS` 只有 `selectJoinMaps` / `selectJoinMapsPage`；消费方法集合只有 `selectJoinCount` / `selectJoinPage` / `selectJoinMapsPage` | `psi/BatchOperationConverter.kt:78-126`；`psi/WrapperLocator.kt:1666-1686` |
| F6 | **gutter 锚点同样受 F1 约束**：场景 B 变量声明 `detectWrapperType(variable.type) == null` 后要求字面类型属于 `Wrapper`/`AbstractWrapper`，否则 `return null` | `psi/WrapperAnchorDetector.kt:87-103` |
| F7 | **批量模板不能直接接管 join 族**：`buildSelectBatch` 对非 `selectAll`/全列/主键方法一律走 `else` 分支，产出 `WHERE id IN (?, ?, ...)`；且 `resolveEntityTable` 会把首参 `Dto.class` 当实体（`selectJoinList(Dto.class, w)` 场景） | `psi/BatchOperationConverter.kt:790-821`、`:418-460` |
| F8 | **对照组可用（已实测）**：同一 Service 封装 + 同一调用位置，wrapper 换成官方 `MPJLambdaWrapper` 时 `locateWithTable` 命中、链内调用收集为 `eq, in` | `CustomWrapperServicePreviewTest.testOfficialWrapperAtCallSiteLocated`（绿） |
| F9 | **Maps 族既有契约**：`selectJoinMaps` / `selectJoinMapsPage` 在批量注册表内，且按 T-MapResultVo 契约「调用行独立承载锚点」；`selectJoinList` / `selectJoinMap` / `selectJoinCount` 在既有不变量用例里期望**只有变量声明 1 个锚点** | `test/.../WrapperAnchorDetectorJpaAndWholeFileTest.kt:191-337` |
| F10 | **现有 MPJ 测试的锚点都在工厂链首**（`JoinWrappers.lambda(...)`），没有「anchor 落在 `selectJoinList` 调用行」的用例 | `test/.../WrapperLocatorTest.kt:498-537` |
| F11 | **父链识别的同构先例已存在**：`hasMpCrudReceiver` 沿 `superTypes` 走 3 层判 `BaseMapper`/`IService`/`ServiceImpl`/`AbstractModel`（FQN 优先、简名兜底） | `psi/BatchOperationConverter.kt:341-392` |
| F12 | **用户 `JoinLambdaWrapper` 的完整四层声明已取证**（2026-11-23 三张类声明截图）：<br>`JoinLambdaWrapper<T> extends AbstractLambdaJoinWrapper<T, JoinLambdaWrapper<T>> implements Query<JoinLambdaWrapper<T>>, Chain<T>, SelectWrapper<T, JoinLambdaWrapper<T>>`<br>`AbstractLambdaJoinWrapper<T, Children> extends AbstractJoinWrapper<T, Children> implements QueryJoin<Children, T>`<br>`AbstractJoinWrapper<T, Children> extends Wrapper<T> implements Compare<Children>, CompareStr<Children, String>, Nested<Children, Children> ...`<br>注意 `extends Wrapper<T>` 是**类继承**，而 MP 的 `com.baomidou...Wrapper` 是接口 → 这套是「MPJ 整套复制改名 + 自研 `Wrapper` 抽象类」的内部库 | 用户截图；`grep`/Maven 中央仓均 0 命中 `AbstractLambdaJoinWrapper` / `AbstractJoinWrapper`（非公开库） |
| F13 | **继承链层数与落点（四层自研 + MPJ）**：`JoinLambdaWrapper`(0) → `AbstractLambdaJoinWrapper`(1) → `AbstractJoinWrapper`(2) → 自研 `Wrapper`(3) → 某一 MPJ 基类（`MPJAbstractLambdaWrapper` 1.3.x / `JoinAbstractLambdaWrapper` 1.4+，第 4 跳）→ `...AbstractWrapper`(5) → MP `Wrapper` 接口(6)。**简单类名白名单在 0~5 跳全部落空**；识别只能靠三样东西：①第 4~6 跳的 `com.github.yulichang.` / `com.baomidou.mybatisplus.` FQN；②接口同名组合（`QueryJoin` + `Chain` + `SelectWrapper` + `MPJBaseJoin`）；③链上 join 方法 | `javap` 1.5.8/1.3.8 基类名实证；用户四层声明 |
| F14 | **自研 `Wrapper`(3) 的父类仍是未知数**：截图只显示 `AbstractJoinWrapper extends Wrapper<T>`，`Wrapper<T>` 自身的 `extends/implements` 未给——若它最终 `implements com.baomidou...Wrapper` 或 `extends ...AbstractWrapper`，父链可命中 MP 包；若完全独立，则只能靠接口同名组合/链方法启发或配置兜底 | 待用户补一行 `class Wrapper<T> extends ______` |

### 根因链

1. **类型识别层**（F1/F2/F3/F6）：插件用「官方类名枚举」认 Wrapper。用户的 `JoinLambdaWrapper` 是自研子类（父链最终接到 MPJ/MP），简单类名不在枚举内 → 变量不是 Wrapper、链首不是 Wrapper、锚点不亮、预览返回 null。
2. **调用点层**（F4/F8）：Service 封装、`getBaseMapper()` 转发、Repository 注入形态都**不是**阻断点——实参反查已能覆盖；同一结构换官方类名立刻可用，证明断点唯一。
3. **锚点/注册表层**（F5/F9/F10）：`selectJoinList` / `selectJoinOne` / `selectJoinMap` 未在任何注册表，调用行天然不是锚点（这是既有契约，非本次新增缺陷，但用户期望在此处触发）。
4. **错误 SQL 风险层**（F7）：若为了「调用行能点」而把 join 族塞进 `BATCH_OPERATIONS`，批量模板会用 DTO 当前实体、用 `WHERE id IN` 当条件，输出**比无预览更糟的错误 SQL**——修复必须同时加守卫。

一句话：**插件靠「身份证上的名字」认 Wrapper，用户给 Wrapper 发的是「继承来的工牌」；同一个调用结构换个名字就能认出来。**

## 三、影响面矩阵

| 消费者 | 读什么 | 现状症状 |
|---|---|---|
| gutter 变量声明锚点 | `WrapperAnchorDetector.anchorElement` → `detectWrapperType` | 自研 Wrapper 子类（`XxxWrapper`/`XxxJoin`）不亮 |
| gutter 链首锚点（内联 new / 工厂） | 同上 | 自研子类内联链不亮 |
| `WrapperLocator.locateWithTable` | `buildChain` 三顺位 | 自研子类整条链不可定位 |
| 预览服务 `SqlPreviewService.preview` | 路径 1（wrapper 链）→ 路径 2（批量） | 路径 1 null；路径 2 未注册 `selectJoinList` → 最终 null |
| 选择区多 SQL（Ctrl+Shift+…） | `isSqlCandidate` = 链首 ∪ 批量方法名 | 自研子类链首不入选 |
| 批量路径（`selectJoinMaps` 等已注册族） | `buildSelectBatch` | wrapper 未识别时可能落到 `WHERE id IN (?, ...)`（F7，需守卫） |
| 其他 ORM 的「自定义基类」同类面 | `EasyQueryConverter` / `XbatisConverter` / JPA | 各自有 entry/receiver 判定，不共用本白名单；本次不改 |

## 四、修复方案

### M1 根因修复：Wrapper 类型父链识别（核心，1 次提交）

新增 `WrapperLocator.detectWrapperTypeDeep(type)`：

```kotlin
// 伪代码
fun detectWrapperTypeDeep(type: PsiType?): WrapperType? =
    detectWrapperType(type) ?: run {
        // O(1) 前门：类型名不像 Wrapper 时不 resolve（守住 gutter 热路径）
        val simple = (type as? PsiClassType)?.className ?: return null
        if (!looksLikeWrapperName(simple)) return null
        wrapperTypeFromSuperChain(type)
    }
```

- `looksLikeWrapperName`：`endsWith("Wrapper") || endsWith("Join") || endsWith("Chain")`（`JoinLambdaWrapper` ✓、`BaseJoin` ✓、`String`/`List`/`Service` ✗）。
- `wrapperTypeFromSuperChain`：沿 `PsiClassType.resolve()?.superTypes` 递归（**深度上限 8**——F13 实测用户链路 0~6 跳，留一档余量），按以下顺序判定：
  1. **FQN 前缀族**（最强证据）：任意祖先/接口 `canonicalText` 以 `com.github.yulichang.` 开头 → `MPJ_LAMBDA`（`Chain`/`SelectWrapper`/`MPJBaseJoin`/`JoinAbstract*`/`MPJAbstract*` 均属此族）；以 `com.baomidou.mybatisplus.` 开头且简单名含 `Abstract*Wrapper`/`Wrapper` → **MP 家族确认**，继续第 3 步细分；
  2. **接口同名组合**（复制改名场景的兜底，F12）：祖先/接口简单名**同时**命中 `QueryJoin` 与 `Chain`（或 `SelectWrapper` / `MPJBaseJoin` 任一）→ `MPJ_LAMBDA`。单个 `Query`/`Chain` 不认（泛名，防误判）；三条任意组合出现即判定，因为这套接口名组合在其它 ORM 中不共存；
  3. **MP 家族细分**：家族已确认（第 1 步命中 baomidou）后，简单名含 `Lambda` → `LAMBDA_QUERY`；含 `Join` 或链上出现 `leftJoin`/`rightJoin`/`innerJoin`（3 参以上）→ `MPJ_LAMBDA`；否则 `QUERY`；
  4. **链方法启发**（最后兜底，F14 场景）：父链完全不可得（依赖未索引 / 自研 `Wrapper` 不接 MP 包）时，若变量初始化链的方法名集合含 join 方法（`leftJoin`/`rightJoin`/`innerJoin`/`selectAs`/`selectCollection`）→ `MPJ_LAMBDA`；若只含 `eq/ne/like/orderBy` 等通用条件方法**且**变量被已知 MP 消费方法（`selectList`/`selectOne`/`list`/`page`）引用 → `LAMBDA_QUERY`；
  5. 全部落空 → null（保持现状）。

| 父链命中 | 映射 |
|---|---|
| `com.github.yulichang.*` 或 `MPJLambdaWrapper` / `JoinAbstractLambdaWrapper` / `MPJBaseJoin` | `MPJ_LAMBDA` |
| `MPJQueryWrapper` / `JoinAbstractWrapper`（非 lambda） | `MPJ_QUERY` |
| `com.baomidou.mybatisplus.*AbstractLambdaWrapper` / `LambdaQueryWrapper` | `LAMBDA_QUERY` |
| `UpdateJoinWrapper` / `DeleteJoinWrapper` | `UPDATE_JOIN` / `DELETE_JOIN` |
| `AbstractWrapper`（无法细分 lambda/query） | `QUERY`（保守，链上方法名仍由 QueryWrapperParser 解析） |

- 结果缓存：`WeakHashMap<PsiClass, WrapperType?>`（类级，随类释放回收），避免 daemon 每趟重复 resolve。
- 接入点（全部换成 `detectWrapperTypeDeep`，浅层白名单语义不变）：
  1. `WrapperLocator.buildChain` 的 `wrapperType` 三顺位（`:967-971`）
  2. `isWrapperVariable` 首行（`:288`）
  3. `findWrapperNewExpression` / `buildChainFromNew` 的构造类型判定（`:411/415/436`）
  4. `WrapperAnchorDetector` 场景 A/B 两处（`:94`、`:108`）
  5. `ChainStateDetector`（`:110`，可选：只影响补全，不改也可）
- 性能：浅层白名单命中时零额外成本；未命中才走「名字前门 + resolve + 类级缓存」。`WrapperAnchorDetector` 现有文档化例外（`BATCH_CHAIN_COLLISION_NAMES` 的 selectAll 变量 resolve）同款口径，需在 KDoc 记录。

### M2 调用行锚点：MPJ 消费族独立承载（1 次提交）

- 新增 `WrapperLocator.mpjConsumerMethodNames = setOf("selectJoinList", "selectJoinOne", "selectJoinMap", "selectJoinCount")`（只做锚点/候选，不并入批量 SQL 模板；`selectJoinMaps` / `selectJoinMapsPage` 已在批量注册表且已有独立锚点契约，不重复登记）。
- `WrapperAnchorDetector` 场景 D 增加分支：方法名 ∈ `mpjConsumerMethodNames` 且 `hasMpCrudReceiver(call)`（receiver 是 MP Service/Mapper 家族，F11 复用）→ 返回锚点元素。
  - 与 `MAPS_TERMINAL_NAMES` 同口径：**不参与 S2 让位**（调用行独立承载），否则 wrapper 实参存在时反而 `return null`。
  - `selectJoinCount` 一并纳入：它有 `countConsumerMethodNames` 消费能力但调用行长期无锚点，属同族不一致，横向一并收口。
- 同步更新 F9 的三个不变量用例期望集合（`{wrapper}` → `{wrapper, selectJoinList}` 等）——这是**契约更新**，需在用例注释写明原契约与本轮变更理由（沿用 T-MapResultVo 的判定方式：`ee090f7` 同类先例）。

### M3 错误 SQL 守卫（横向审计，1 次提交）

- `BatchOperationConverter.convert` 对 `selectJoin*` 族（含既有 `selectJoinMaps`/`selectJoinMapsPage`）增加守卫：
  - 首实参是 Class 字面量且解析出的类**不是实体**（无 `@TableName`、无主键、简单名以 `DTO`/`VO`/`Dto`/`Vo` 结尾）→ 不返回模板 SQL；
  - wrapper 实参类型无法识别为 Wrapper → `return null`（交回 wrapper 路径或如实无预览）。
- 目标：宁可无预览，不可输出 `WHERE id IN (?, ?, ...)` 这类凭空条件。

### M4 设置兜底（P2，可延后，待拍板）

若 M1 的父链识别覆盖不到「完全独立实现、不继承 MP 基类」的自研 Wrapper，再引入设置项「自定义 Wrapper 类名/FQN 前缀」。默认不做，避免扩大配置面。

### 不做

- 不把 `selectJoinList` 族并入 `BATCH_OPERATIONS` 的 SQL 模板（F7：模板语义不匹配）。
- 不改 `MpjLambdaConverter` / `QueryWrapperParser` 的渲染语义。
- 不改 Service/Mapper 的 receiver 判定（F11 的 `hasMpCrudReceiver` 已够用）。
- 不做 GUI 自动化验收（按既有纪律由用户真机点验）。

## 五、待验证前提（实施者先证伪再动手）

| # | 前提 | 证伪方式 | 不成立时的处置 |
|---|---|---|---|
| P1 | 用户的 `JoinLambdaWrapper` 继承 MPJ `MPJLambdaWrapper` 或最终继承 MP `AbstractWrapper` | **部分确认**：四层声明已取证（F12/F13），0~3 跳全是自研类名；第 4~6 跳是否落到 MPJ/MP 包**取决于最底层自研 `Wrapper` 的声明**（F14 未给） | 若自研 `Wrapper` 完全不接 MP 包：父链规则 1/3 失效，退到规则 2（接口同名组合）或规则 4（链方法启发）；仍不成立则走 M4 配置兜底 |
| P1b | 最底层自研 `Wrapper<T>` 最终 `implements com.baomidou...Wrapper` 或 `extends ...AbstractWrapper` | **待用户补一行类声明**：`public abstract class Wrapper<T> extends ______` / `implements ______` | 同上：决定是走 FQN 父链（低风险）还是接口/链方法启发（需更严的反向负例） |
| P2 | 用户的 `BaseJoin<T>` 继承 MPJ `MPJBaseJoin` | 同上 | 不影响定位（wrapper 实例类型是 `JoinLambdaWrapper`），只影响父子判定文案 |
| P3 | 用户触发方式是 gutter 图标点击 / Ctrl+Enter / 右键菜单（三者至少其一） | 询问用户 | 若只用 gutter：M2 优先级提高；若只用快捷键：M2 可延后 |
| P4 | 父链 resolve 在真实大工程 100ms 内、无 EDT 阻塞 | 在 demo/大语料上打点 | 加 per-class 缓存 + 名字前门；仍超标则只在预览链路启用、gutter 侧维持浅层 |
| P5 | `selectJoinList(BaseJoin)` 单参重载也在使用 | 用户代码搜索 | 单参形态无实体类线索，需从 wrapper 泛型取实体（`buildChain` 已支持），M2 同批覆盖 |

### 已排除的同名来源（2026-11-23 取证）

| 来源 | 取证结论 |
|---|---|
| `com.github.yulichang:mybatis-plus-join-core`（当前本地 1.5.6~1.5.9 + Maven 历史 0.0.1~1.5.9） | 全部版本均无 `JoinLambdaWrapper` 类名（wrapper 类只有 `MPJLambdaWrapper` / `MPJQueryWrapper` / `UpdateJoinWrapper` / `DeleteJoinWrapper`）；基类名随版本变：**1.3.8 = `MPJAbstractLambdaWrapper` / `MPJAbstractWrapper`，1.4+ = `JoinAbstractLambdaWrapper` / `JoinAbstractWrapper`**（`javap` 实证），两者最终都 `extends com.baomidou...Wrapper`。用户类名 `AbstractLambdaJoinWrapper` 与之都不同 → **自研中间层**，其上才是 MPJ 基类 |
| `icu.mhb:mybatis-plus-join-core`（另一套 MPJ，2.0.0~2.1.3） | 有 `icu.mhb.mybatisplus.plugln.core.JoinLambdaWrapper`，但：①无无参构造（构造器均带 `Class<T>`/`T`）；②Service 方法名是 `joinList(Wrapper, Class)` / `joinGetOne` / `joinCount`，**不是** `selectJoinList(Class, BaseJoin)`；③`JoinServiceImpl extends ServiceImpl<M,T>`（与用户的自研 `CommonServiceImpl` 同构但方法名不同）。与用户截图写法不匹配 |
| 用户自研（`CommonService` / `CommonServiceImpl` / `BaseJoin` / `ExpPreMasterFileRepository`） | 与截图一致：自研 Service 基类 + 自研 wrapper 公共基接口，wrapper 为带继承的子类 |
| 公开库整体搜索 | `AbstractLambdaJoinWrapper` / `AbstractJoinWrapper` 在 Maven 中央仓 **0 命中**、公开 Web 搜索也无同名类；`QueryJoin` 只存在于 yulichang MPJ（`com.github.yulichang.wrapper.interfaces.QueryJoin`）。结论：现场是**把 MPJ 整套复制改名**的内部库（自研 `Wrapper` 抽象类在最底层），不是任何已发布依赖的固定版本 |

> M1 追加一条渲染兜底：父链命中 `AbstractLambdaWrapper`/`AbstractWrapper` 但无法细分 lambda/MPJ 时，若链上出现 `leftJoin`/`rightJoin`/`innerJoin`（3 参及以上）→ 按 `MPJ_LAMBDA` 渲染（保住 join 语义）；否则按 `LAMBDA_QUERY`。

## 六、测试与反向验证

### 新增 / 补充测试

| 测试 | 断言 |
|---|---|
| `CustomWrapperServicePreviewTest`（已落地） | 自定义子类调用行/链内 → null；官方类名 → 命中；gutter 锚点同口径；`SqlPreviewService.preview` 同口径 |
| `WrapperTypeSuperChainTest`（新增） | ①用户真实四层链 `JoinLambdaWrapper → AbstractLambdaJoinWrapper → AbstractJoinWrapper → Wrapper → MPJLambdaWrapper` → MPJ_LAMBDA；②`Xxx extends LambdaQueryWrapper` → LAMBDA_QUERY；③`superTypes` 只剩接口 `QueryJoin` + `Chain` + `SelectWrapper`（父类 resolve 失败）→ 仍判 MPJ_LAMBDA；④只有单个 `Chain` 接口、无组合 → null（防泛名误判）；⑤普通类 `extends Object` → null；⑥名字不像 Wrapper 的类零 resolve（探针计数）；⑦深度超限（9 层）→ null；⑧链上有 `leftJoin`、父链全断 → MPJ_LAMBDA（规则 4） |
| `WrapperAnchorDetectorJpaAndWholeFileTest`（改期望） | 三个不变量期望集合加入 `selectJoinList` / `selectJoinMap` / `selectJoinCount` 的调用行锚点（契约更新） |
| `BatchOperationConverterJoinGuardTest`（新增） | `mapper.selectJoinList(Dto.class, wrapper)` / `selectJoinMaps(unrecognizedWrapper)` → `convert` 返回 null，不产出 `WHERE id IN` |
| `MpjJoinOnAliasConverterTest`（不改） | 回归保持 |

### 反向验证（用户 2026-11-21 定例：git 补丁方式，patch 放 temp 不进仓）

1. 摘掉 M1 的父链分支 → `CustomWrapperServicePreviewTest` 的对照组仍绿、自定义子类两条转红（预期 2 红）。
2. 摘掉 M2 的锚点分支 → 锚点用例转红（预期 1 红）。
3. 摘掉 M3 的守卫 → 错误 SQL 用例转红（预期 1~2 红）。
4. 每次还原后复跑绿，读数进 §九。

### 定向测试清单（不跑全量）

```
./gradlew :test --tests "cn.song.mybatisplus.visualizer.psi.CustomWrapperServicePreviewTest" \
  --tests "cn.song.mybatisplus.visualizer.psi.WrapperLocatorTest" \
  --tests "cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetectorTest" \
  --tests "cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetectorJpaAndWholeFileTest" \
  --tests "cn.song.mybatisplus.visualizer.converter.MpjJoinOnAliasConverterTest" \
  --tests "cn.song.mybatisplus.visualizer.ui.SqlPreviewServiceOfficialApiTest"
```

## 七、验收标准（用户真机，编号步骤）

1. 打开 demo 工程 `mybatis-plus-join-demo`，进入 `ExpPreMasterFileQueryService.checkAllFileRead`；
   光标放 `expPreMasterFileRepository.selectJoinList(...)` 行 → Ctrl+Enter（或右键「转换为 SQL」）：
   应弹出预览，SQL 形如
   `SELECT id, device_name, station_id FROM car_device_info WHERE share_scheme_id = ? AND device_name IN (?, ?, ?, ?) AND station_id <> 0`（列名/表名以实体注解为准）。
2. 同一行左侧 gutter 应出现预览图标（M2 生效后）；点击结果与步骤 1 一致。
3. `JoinLambdaWrapper<DeviceInfoEntity> jwEpmfe = new JoinLambdaWrapper<>();` 声明行左侧应有图标，点击可预览（M1 生效后）。
4. `checkAllFileReadWithJoin`：预览应含 `LEFT JOIN station_info`（联表语义不丢）。
5. `checkAllFileReadOfficialWrapper`：行为与修复前一致（无回归）。
6. 负例：普通类上的 `selectJoinList`（如自定义非 MP 服务）不亮图标、不弹预览。

## 八、待拍板事项（默认按推荐执行，用户可改）

| # | 事项 | 推荐 |
|---|---|---|
| Q1 | 是否做 M4「自定义 Wrapper 类名配置」兜底 | 暂不做（先看 P1；父链识别覆盖绝大多数自研封装） |
| Q2 | `selectJoinOne` / `selectJoinMap` / `selectJoinCount` 是否同批给调用行锚点 | 同批做（与 `selectJoinList` 同族，避免半套） |
| Q3 | 父链深度上限 | 4 层（与 `MP_CRUD_SUPER_TYPE_DEPTH=3` 对齐并留一档） |
| Q4 | 三个 Maps 族既有不变量用例的期望更新是否算契约变更 | 算（沿用 T-MapResultVo 先例记 LESSONS） |

## 九、实施记录（实施期回填）

（待实施后回填：提交哈希、定向读数、反向读数、demo 编译读数。）

### 复现阶段已回填（2026-11-23 13:18）

- demo 新增 10 个文件（`customwrapper` 包 9 个 + `dto/ExpPreMasterFileDTO.java`）+ `CUSTOM-WRAPPER-REPRO.md` 点验说明。
  - 首版 `JoinLambdaWrapper extends MPJLambdaWrapper`，`mvnd -o -DskipTests compile` 通过（12:35）。
  - 收到第二张类声明截图后改为三层链（`JoinLambdaWrapper → AbstractLambdaJoinWrapper → MPJLambdaWrapper`），编译通过（13:06）。
  - 收到四层类声明截图后改为**与现场完全同构的四层链**：`JoinLambdaWrapper → AbstractLambdaJoinWrapper → AbstractJoinWrapper → Wrapper → MPJLambdaWrapper`，`mvnd -o -DskipTests compile` 再次通过（13:15）。
- `CustomWrapperServicePreviewTest` 复现读数（四层链夹具）：**6 用例 / 0 失败**，XML `tests="6" failures="0" errors="0"`（2026-11-23 13:18:01，`BUILD SUCCESSFUL in 57s`）：
  - `testCustomWrapperSubclassAtCallSiteNotLocated`：自定义子类在**调用行**定位为 null（复现 S1）
  - `testCustomWrapperSubclassInsideChainNotLocated`：自定义子类在**链内**定位为 null
  - `testGutterAnchorFollowsWrapperTypeWhitelist`：自定义子类变量声明**不亮**锚点、`selectJoinList` 调用名**不是**锚点；官方类名变量声明**亮**（复现 S2）
  - `testPreviewServiceReturnsNullForCustomSubclass`：`SqlPreviewService.preview` 对用户现场返回 null（「点了没反应」直接证据）
  - `testOfficialWrapperAtCallSiteLocated` / `testPreviewServiceProducesSqlForOfficialWrapper`：同结构换官方 `MPJLambdaWrapper` 后**定位命中 / 预览出 SQL**（证明 Service 封装不是断点，S3 对照）
- 并行冲突记录：期间另一会话（D255）共用 build 目录跑 test，`in-progress-results-generic.bin` 被清除导致一次 `NoSuchFileException`；重试后取得上述读数。收口阶段如需权威读数，按登记表在独立沙盒复跑本类。
