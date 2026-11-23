---
name: pitfall-candidate-scan-cap-cache-and-test-traps
description: 联表候选（全项目 @TableName 实体逐个解析）的六条硬教训——截断要按「扫描数」而非「已解析数」、项目级缓存不能用 PsiModificationTracker（自己写的临时文档会把它顶掉）、后台 warm 必须包读动作否则异常被吞导致候选永不入缓存、Light 沙盒注解索引为空会让用例空绿、注入缝必须在 tearDown 复原否则整类连锁红、改签名后要 --rerun-tasks
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4043a4bb-4569-4ef9-8e28-84e10cd22b20
---

背景：`XmlSqlCompletionContextResolver.buildCandidates` 曾把全项目 `@TableName` 实体**逐个** `EntityResolver.resolve`，最后才 `take(50)`。真机（2500 表工程、IDE 已空闲）读数：

```
ctx detail: entity=5ms resolve=66ms candidates=3230ms candidatesCount=1
ctx detail: entity=1ms resolve=1ms  candidates=2116ms candidatesCount=1   ← 第二次仍慢
```

四条教训，每条都是当场踩出来的：

1. **上限要按「扫描了多少个」判，不能按「解析出几个」判。**
   多数实体解析结果 `tableName` 为空 ⇒ `resolved.size` 长期停在 0/1，`if (resolved.size >= CAP) break` 永远不触发，照样扫全工程。要么在循环顶部用 `scanned` 计数，要么给整段加**时间预算**（本仓 `candidatesBudgetMs=400`）。而且单次 `EntityResolver.resolve` 是秒级，**预算夹不住一次调用内部**——预算只是兜底，真正的解是不在打开路径上算它。

2. **项目级缓存不能用 `PsiModificationTracker` 做失效判据。**
   本插件预览链自己会写临时 SQL 文档，每次打开都把 modificationCount 顶掉 ⇒ 缓存形同不存在（第二次仍 2116ms）。候选这类「只服务补全提示、容忍陈旧」的数据改用 **TTL**（`candidatesTtlMs`，初值 120s）。

3. **Light 沙盒的注解索引是空的 ⇒ 用例会空绿。**
   `AnnotatedElementsSearch.searchPsiClasses` 在 `BasePlatformTestCase` 里返回空，`entitiesResolved=0`，于是「解析次数 ≤ 50」这种断言恒真、什么都没测。必须先断言夹具真产出 N 个实体类，并用注入缝 `tableNameEntitiesProviderForTest` 把真实 PSI 类喂进循环。

4. **注入缝必须在 tearDown 复原，否则同一 JVM 后续用例连锁红。**
   实测漏复原后 `XmlSqlCompletionContextResolverTest` 六例全红（拿到已 dispose 的 PSI）。同理，**项目级缓存会让同一测试类的用例互相串味**（共享 project 且测试间不改 PSI）⇒ 既有用例的 `setUp` 要 `clearCandidatesCacheForTest()`。

5. **后台 warm 候选必须自己包读动作，且异常不能静默吞。**
   `warmCandidates` 最初由面板 `executeOnPooledThread` 直接调用，而
   `buildCandidates → findTableNameEntities → JavaPsiFacade.findClass` 全是 PSI/索引读 ⇒ 抛
   `Read access is allowed from inside read-action only`（栈顶就是 `warmCandidates:312 → buildCandidates:265
   → findTableNameEntities:361 → JavaPsiFacadeImpl.findClass`）。异常被外层 `runCatching` 吞掉，
   结果是**候选永远写不进缓存**：用户看到「补全一直空」，同时 IDEA 刷一屏报错。
   修法＝`ReadAction.nonBlocking{…}.inSmartMode(project).expireWith(project).coalesceBy(this,key)`
   （与预取链同款：等索引不持读锁、可取消）+ 落地回调刷新；单测走同步分支；面板调用点去掉外层池线程。
   判据：日志里必须有 `[completion] warmCandidates ok=… cached=N`，没有这条就说明它根本没跑成。

6. **改了带默认参数的签名 → 旧测试类 NoSuchMethodError（本单已两次）。**
   Kotlin 默认参数会改合成构造/方法签名，增量编译不重编旧测试类 ⇒ `NoSuchMethodError` 而非编译错。
   处置：`./gradlew compileTestKotlin --rerun-tasks` 后再跑。

另外一条产品级取舍：候选**只服务 JOIN 表名补全**，预览打开不应该付这笔钱——`resolveXml(..., includeCandidates=false)` 给预览、后台 `warmCandidates` 补齐、面板 `cachedCandidateTables()` 只读回读 + 落地 `refreshCompletionContext()`。注意**别把 `candidateTables` 的唯一取值点一起切掉**：它只在打开那一次构造，切了就是「打开快、补全没了」（本单第 8 轮原样复发）。

关联：[[pitfall-preview-open-stage-split]]、[[project-startup-read-lock-freeze-20261113]]、[[feedback-verify-perf-non-gui-first]]。
