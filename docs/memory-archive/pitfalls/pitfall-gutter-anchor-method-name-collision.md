---
name: pitfall-gutter-anchor-method-name-collision
description: 给 MP 的 delete/update/remove 加方法名级 gutter 锚点时，它们同时是 Wrapper
  工厂名/链终结点/服务链消费名——必须用「null 实参精确形态 + hasWrapperArgument 让位 +
  整文件锚点集合断言」，否则同一语句出现两枚预览图标
metadata:
  node_type: memory
  type: project
  originSessionId: sess_a1b62012-6329-49f6-8f63-ac96cb7d21a6
---

`deviceInfoMapper.delete(null)` 没有预览图标，而 `delete(new LambdaQueryWrapper<>()...)` 有——后者的图标其实挂在 `new` 关键字（场景 A），与 `delete` 方法名无关；`delete` 从未登记进 `BATCH_OPERATIONS`，`delete(null)` 既无链可锚也不在批量名单，gutter 与预览双缺失。

给 `delete`/`update`/`remove` 补方法名级锚点的重复图标风险：

| 形态 | 期望锚点 | 不得出现 |
|---|---|---|
| `mapper.delete(null)` | `delete` identifier | — |
| `mapper.delete(new LambdaQueryWrapper<>()...)` | `new` 关键字 | `delete` |
| `mapper.delete(wrapperVar)` | wrapper 变量行 | `delete` |
| `JoinWrappers.delete(Class)` | 工厂方法名 | 批量锚点 |
| `service.lambdaUpdate().eq(...).update()` | `lambdaUpdate` | 终结点 `update` |
| `mapper.update(entity, null)` | `update` identifier | — |
| `mapper.update(entity, wrapperVar)` | wrapper 变量行 | `update` |

四道防线：① 锚点只认空 Wrapper 精确形态（`delete`/`remove` 恰好 1 个 null 实参；`update` ≥2 参且第 2 参 null，解一层 cast）；② 复用既有 `hasWrapperArgument` 让位；③ 场景 C 顺序不动（工厂 > 服务链 > JPA > 无锚点）；④ 测试用 `collectedAnchors()` 做整文件锚点集合断言，不是逐元素断言。接收者守卫 `hasMpCrudReceiver`（BaseMapper/IService/ServiceImpl/AbstractModel，FQN 前缀优先、简名兜底）压住 `redisTemplate.delete(null)`、`cache.delete(null)`、`list.remove(null)`（`List.remove(Object)` 传 null 合法且常见）等负例。`insert` / `selectJoinMaps` / `selectJoinMapsPage` 的既有锚点行为不要改（`MAPS_TERMINAL_NAMES` 豁免）。

**Why**：这些名字的高撞名面决定了「名字在注册表就锚」必出双图标/误亮；`List.remove(null)` 这类合法调用让纯名字判定不可用。
**How to apply**：新增任何 MP 写操作锚点先按上表逐形态钉住，反向验证要能证明「去掉空 Wrapper 判定 / 去掉 hasWrapperArgument → 整文件集合用例变红」。关联 [[project-runtime-concat-delete-anchor-20261106]]、[[pitfall-new-capability-hits-repo-wide-convention-guards]]。
