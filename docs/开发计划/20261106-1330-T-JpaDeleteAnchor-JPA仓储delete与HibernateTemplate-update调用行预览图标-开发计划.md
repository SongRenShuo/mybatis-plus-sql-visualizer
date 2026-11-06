# T-JpaDeleteAnchor：JPA `delete(entity)` / `update(entity)` 调用行预览图标（2026-11-06）

- 立项：2026-11-06 13:30
- 状态：**待拍板**（独立立项，未改生产代码）
- 目标分支：`master`
- 来源：用户对 [T-RuntimeConcatAndDeleteNull](20261106-1255-T-RuntimeConcatAndDeleteNull-运行时SQL拼接占位与Mapper空Wrapper删除预览缺失-开发计划.md) 的补充意见——「是否顺带让 JPA `repository.delete(entity)` 也有图标，可以单独立项」
- 依赖：与 T-RuntimeConcatAndDeleteNull 的 R4 都改 `WrapperAnchorDetector`，两券必须串行或同批合流；本券不改变 MP 侧的锚点/注册表

## 一、现状与根因

### 1.1 预览早就支持，缺的只是锚点

`JpaCrudOperationConverter.convert` 已实现两条分支：

- `delete`（Repository 段，`JpaCrudOperationConverter.kt:421-428`）：`DELETE FROM t WHERE <主键> = ?`，注 `delete(entity)：EntityManager.remove 语义，按实体主键删除`；
- `update`（HibernateTemplate 段，`:636-650`）：`UPDATE t SET <非主键列> WHERE <主键> = ?`；
- `delete`（HibernateTemplate 段，`:652-656`）：`DELETE FROM t WHERE <主键> = ?`。

`SqlPreviewService.preview` 的派发顺序也是 JPA 在 MP 批量之前（`:498-518`）。也就是说，用右键 / 快捷键（`ConvertToSqlAction`）已经能出预览，只是 gutter 没有入口。

### 1.2 为什么锚点不可达

两个原因叠加：

1. `delete` / `update` 同时在 `WrapperLocator.wrapperFactoryMethodNames` 里（`JoinWrappers.delete` / `JoinWrappers.update` / 服务链 `update`，`WrapperLocator.kt:1337-1341`）。`WrapperAnchorDetector` 场景 C 遇到这两个名字时，若 qualifier 不是工厂类、也不是服务链起点，直接 `return null`（`WrapperAnchorDetector.kt:112-125`）——**在场景 E（JPA）之前就返回了**。
2. `JpaCrudOperationConverter.crudAnchorMethodNames` 又把这两个名字主动剔除（`WRAPPER_FACTORY_COLLIDING_NAMES`，`:153-167`），KDoc 写明「二者即便入锚点名单也永远不可达，刻意剔除避免误导」。

所以 `repository.delete(entity)` / `ht.delete(entity)` / `ht.update(entity)` 是「预览能力已存在、锚点被短路」的缺口；`em.remove(entity)` 不受影响（`remove` 不在工厂名单，场景 E 已能锚定）。

## 二、方案

### 2.1 推荐：场景 C 内加一个 JPA 分支（最小改动）

在场景 C 的「非工厂、非服务链」分支里，对 `delete` / `update` 先问一次 JPA 家族：

```kotlin
if (!isWrappersFactory && !isServiceChain) {
    // 与 Wrapper 工厂撞名的 JPA/HibernateTemplate 方法：receiver 家族命中则锚定
    if (ref.referenceName in JPA_COLLIDING_NAMES &&
        JpaCrudOperationConverter.hasJpaCrudReceiver(call)
    ) return element
    return null
}
```

其中 `JPA_COLLIDING_NAMES = setOf("delete", "update")`，直接复用 `hasJpaCrudReceiver`（`:185+`）——它按 receiver 家族（Spring Data Repository / EntityManager / HibernateTemplate）与「该方法名是否属于该家族」双重判定，不依赖 `crudAnchorMethodNames`，所以**不需要改 `WRAPPER_FACTORY_COLLIDING_NAMES`**。

为什么不选另外两条路：

- **把场景 E 提到 C 之前**：会让每个 JPA 方法名（`save`/`count`/`find`/`delete`/`update` 等）在工厂判定之前多做一次 resolve，daemon 热路径开销扩大；收益只是少一个分支。
- **把 `delete`/`update` 从 `WRAPPER_FACTORY_COLLIDING_NAMES` 移出**：E 仍然在 C 之后，名字还是不可达；要同时改顺序，等于把上一条的代价也吃进来。

### 2.2 覆盖范围

| 调用 | 家族 | 期望 |
|---|---|---|
| `userRepository.delete(entity)` | Spring Data Repository | 锚点 + 预览 `DELETE ... WHERE id = ?` |
| `hibernateTemplate.delete(entity)` | HibernateTemplate | 锚点 + 预览 `DELETE ... WHERE id = ?` |
| `hibernateTemplate.update(entity)` | HibernateTemplate | 锚点 + 预览 `UPDATE ... SET ... WHERE id = ?` |
| `entityManager.remove(entity)` | EntityManager | 保持现状（已有锚点） |
| `userRepository.deleteById(id)` | Spring Data Repository | 保持现状（已有锚点） |

## 三、去重不变量（防回滚，硬性）

用户明确提醒不要出现「同一语句两枚预览图标」。本券只加两个名字，但这两个名字同时是工厂名/链终结点，必须逐形态钉住：

| 调用形态 | 期望锚点位置 | 必须不出现 |
|---|---|---|
| `userRepository.delete(entity)` | `delete` identifier（本券新增） | 第二枚 |
| `hibernateTemplate.delete(entity)` | `delete` identifier（本券新增） | 第二枚 |
| `hibernateTemplate.update(entity)` | `update` identifier（本券新增） | 第二枚 |
| `JoinWrappers.delete(Entity.class)` | `delete` 工厂方法名（场景 C 工厂分支） | JPA 分支不得抢跑 |
| `JoinWrappers.update(Entity.class)` | `update` 工厂方法名（场景 C 工厂分支） | JPA 分支不得抢跑 |
| `service.lambdaUpdate().eq(...).update()` | `lambdaUpdate`（链起点） | 终结点 `update` |
| `service.update(wrapper)` | wrapper 变量/工厂行 | `update` identifier |
| `entityManager.remove(entity)` | `remove` identifier（场景 E 既有） | 第二枚 |
| `userRepository.deleteById(id)` | `deleteById` identifier（场景 E 既有） | 第二枚 |

落地要求：

1. JPA 分支只放在 `!isWrappersFactory && !isServiceChain` 里——工厂与服务链先命中，天然优先；
2. 与 MP 券的组合安全：MP 券的批量锚点只认「null 实参」（`delete(null)` / `update(entity, null)`），JPA 分支只认「非 null 实体实参」（`delete(entity)` / `update(entity)`），两者判定面不重叠；且场景 C 在 D 之前，JPA 命中即返回，不会再进 D；
3. `hasJpaCrudReceiver` 的家族判定失败时保持原来的 `return null`，不改变既有负例；
4. 测试用 `WrapperAnchorDetectorJpaAndWholeFileTest` 的整文件锚点集合断言（不是逐元素断言），抓「多一枚」；
5. 不改变 `crudAnchorMethodNames`、`WRAPPER_FACTORY_COLLIDING_NAMES` 与场景顺序，避免把既有锚点集合整体挪动。

## 四、测试

| 类 | 用例 |
|---|---|
| `WrapperAnchorDetectorJpaAndWholeFileTest` | ① `repository.delete(entity)` → `delete` 是锚点；② `ht.delete(entity)` / `ht.update(entity)` → 对应 identifier 是锚点；③ `JoinWrappers.delete(Entity.class)` / `JoinWrappers.update(Entity.class)` → 仍只锚工厂名，JPA 分支不抢；④ `service.update(wrapper)` / `service.lambdaUpdate()...update()` → 不锚 `update`；⑤ 整文件锚点集合恰好等于预期集合 |
| `GutterPreviewConsistencyTest` | `repository.delete(entity)` 锚点存在 → preview 非 null 且 SQL 以 `DELETE` 开头 |
| `SqlPreviewServiceJpaCrudTest` | `repository.delete(entity)` 预览回归（应已绿，作为接缝证明） |

## 五、反向验证

1. 去掉场景 C 里的 JPA 分支 → `repository.delete(entity)` 锚点用例必红；
2. 把 JPA 分支提到工厂判定之前 → `JoinWrappers.delete(...)` 的整文件锚点集合用例必红（出现两枚或锚点位置错）；
3. 去掉 `hasJpaCrudReceiver` 的家族判定（只按方法名） → `service.update(wrapper)` 负例必红（误锚）。

每项贴「改前红 / 改后绿」两次读数。

## 六、待验证前提

| 编号 | 前提 | 证伪方法 | 被推翻的后果 |
|---|---|---|---|
| P-1 | `hasJpaCrudReceiver` 对 `delete` / `update` 返回 true 只在 JPA 家族 | 读 `resolveFamily` / `hasJpaCrudReceiver` 实现，并用 `service` / `redisTemplate` 变量做负例 | 若判定过宽，需先收紧家族判定再挂锚点 |
| P-2 | `JoinWrappers.delete/update` 的 qualifier 能被 `isWrappersFactory` 识别 | 既有 `WrapperAnchorDetectorTest` 的工厂形态用例 + 新增整文件集合用例 | 若识别不到，工厂分支先于 JPA 分支的优先级失效，需改判定 |
| P-3 | `ht.update(entity)` 的预览 SQL 形态符合预期 | 跑 `SqlPreviewServiceJpaCrudTest` 的 `update` 分支；真机日志对照 | 若 SQL 形态不符，先修转换器再挂锚点（避免「点击有结果但结果错」） |
| P-4 | 两券同批时 MP 的 `update` 注册不影响 JPA 锚点 | 先合 JPA 券再合 MP 券，各跑一次整文件集合用例 | 若互相干扰，改为串行合流并各自回归 |

## 七、风险

| 风险 | 缓解 |
|---|---|
| `delete`/`update` 通用名误锚（非 JPA 家族） | `hasJpaCrudReceiver` 双重判定；负例 `service.update(wrapper)` / `redisTemplate` |
| 与 MP 券的 `delete`/`update` 注册表改动叠加后重复图标 | 两券写集都含 `WrapperAnchorDetector`，串行或同批；整文件集合断言兜底 |
| 场景 C 分支变复杂，后续维护误改顺序 | 分支处写注释说明「工厂 > 服务链 > JPA > 无锚点」的优先级；测试钉住 |
| `ht.update` 的预览 SQL 与实际 Hibernate 行为有偏差 | 现有转换器已实现，本券不改；若发现偏差另开券 |

## 八、决策点

| 编号 | 问题 | 建议 |
|---|---|---|
| D-1 | 场景 C 内加 JPA 分支，还是把场景 E 提前 | 选前者（最小改动、热路径开销只在两个撞名方法上） |
| D-2 | 是否同批覆盖 `ht.update(entity)` | 建议覆盖——同一个 `update` 名字、同一次分支改动，不额外增加判定面 |
| D-3 | 是否把 `deleteAll()` / `deleteAllById()` 等也纳入 | 不纳入本券。它们不在工厂名单，场景 E 已可达；先跑一次整文件集合基线确认 |
| D-4 | 是否顺带把 JPA 的 `update` 锚点扩到 `save` | 不扩。`save` 不在工厂名单，已有锚点（若没有，属另一问题） |

## 九、变更记录

| 日期 | 内容 |
|---|---|
| 2026-11-06 | 立项。现状（预览已支持、锚点被场景 C 短路）、场景 C 内 JPA 分支方案、去重不变量表、测试与反向验证、4 条待验证前提。未改生产代码。 |
