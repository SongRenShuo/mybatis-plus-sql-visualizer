---
name: project-hql-buffer-concat-gap
description: 用户报障「StringBuffer/append 拼出的 HQL 传自定义 DAO 无法预览」——20261013 已实施并提交（8ae3fe76，未 push）；根因=提取层不认 sb.toString()；同族收敛 HqlQueryLocator 末支 + NewExpressionStripper（new 大小写）；后续 T-HqlHelperReturn（ee619b45）支持「首参是同工程方法返回的字符串」
metadata:
  node_type: memory
  type: project
  originSessionId: sess_00a325f2-4943-4c18-87d1-b80d31784e6f
---

2026-10-13 用户报障（当轮先出计划、次轮明令「提交开发计划 实施」→已交付）：Java 里
`StringBuffer sb` 逐句 `sb.append(...)` 拼好 HQL，传
`commonDao.findByQueryMaxNum(sb.toString(), new String[]{}, new Object[]{}, 0, 10)`，
该行不亮预览图标、手动触发动作弹「未在光标处找到 …MyBatis-Plus Wrapper…」通用提示。
**用户附带猜测「这个 var 可能是在 class 中，疑似依赖或者 jar 中的信息」——已证伪，见下。**

## 根因（代码级定位，非推断）

**不是跨 jar/依赖解析问题**：`CustomDaoHqlLocator` 认领靠「名字启发 + 内容启发」，
不解析 DAO 实现体——`PreviewReceiverResolver.classify` 对 qualifier 名 `commonDao` 命中
`Dao` 后缀即判 CUSTOM_DAO（**名字命中即返回，不触发 `qualifier.type` 的 resolve**）；
`matchesDaoQualifier` 同口径；`matchesQueryMethodName` 对 `findByQueryMaxNum` 命中 `Query` 关键字。
所以实现体在哪个 jar 里与本次识别无关。**推广：凡「认领靠名字/内容启发」的链路，用户报
「依赖在 jar 里所以认不出」时先证伪这条，别顺着实现体去查。**

**真卡点＝HQL 文本提取层不认 `sb.toString()`**：`isCustomDaoHqlCall` 的 CUSTOM_DAO 支要求
`(qualifier || 方法名) && matchesHqlContent`，前两项已命中，卡在 `matchesHqlContent`：门的
`PreviewReceiverResolver.isStringLikeFirstArg` 对 `toString()` 调用**放行**（true），
但随后 `extractArgumentText` → `extractHqlString`（`CustomDaoHqlLocator.kt:417-455`）的 `when`
只覆盖字面量 / `+` 拼接 / 变量引用，**`PsiMethodCallExpression` 落末支 `else -> null to false`**
⇒ 内容启发判否 ⇒ 整条调用不被认领。

## 交付（20261013，master 本地两笔，未 push）

- `fa6e3e94` 计划；**`8ae3fe76` 实施**（13 文件 +510/−88）。工作树干净（只剩他会话未跟踪件）。
- **`converter/jpa/NewExpressionStripper.kt`（新增）**＝`HqlConverter`/`JpqlConverter` 两份**逐字重复**的
  `handleNewExpression` 收敛为单一实现（整词 + `RegexOption.IGNORE_CASE` + 「`new` 空白 类名 `(`」形态）；
  两转换器私有实现删除、调用点改 `NewExpressionStripper.strip(...)`、类 KDoc 同步。
- `psource/SqlStringConcatenator` 新增**共享入口 `concatUnwrappingToString`**（`.toString()`/`.append(y)`
  解包到根变量后交既有 `concat`）——JdbcTemplate 通道早就在用同一聚合能力（`concatReference`→
  `concatNewStringBuilder`→`collectAppendCalls`），**不是新写第三套遍历**。注：`concat` 自身对
  `PsiMethodCallExpression` 无分支，必须带解包这步（`JdbcTemplateSqlLocator.resolveToStringCall` 先例）。
- `CustomDaoHqlLocator`：`extractHqlString` 新增 `PsiMethodCallExpression` 分支（`sb.toString()` /
  链式 `append(x).toString()`）与 `PsiNewExpression` 初始化变量分支，委托私有 `aggregateHqlText`。
- `HqlQueryLocator` 同族收敛：`hasStringHqlArgument` 放行 `toString`/`append` 形态；
  `extractHqlString` 接入聚合（含 `String hql = sb.toString()` 的 initializer 形态）；
  **末支 `else -> firstArg.text to false` 改为 `null to false`**（对齐 T-GutterDupFreeze S1 已删的
  「把表达式源码文本当 HQL」反模式——两条定位器口径此前已分叉）。
- `CHANGELOG.md` `[Unreleased] › Fixed` 顶部一条；计划 §八/§九回填。

## 验证读数（已实测）

定向 5 类 **89 例 0 失败 0 错误**：`CustomDaoHqlLocatorTest` 24（+5）、`HqlQueryLocatorTest` 23（+2）、
`NewExpressionStripperTest` 7（新增类）、`HqlConverterNewDtoTest` 3（+1）、`JpqlConverterTest` 32（+1）；
GRADLE_EXIT=0。**反向验证两次，都变红**：①摘掉提取分支 → CustomDao failures=3、HqlQuery failures=2；
②把剥壳器改回**大写-only `NEW` 嗅探**（复刻旧行为）→ `NewExpressionStripperTest failures=3`，红的正是
三条小写用例（`lowercase new Map` / `lowercase new with space` / `nested parens`），
`uppercase`/`renew`/`unbalanced`/`noNew` 保持绿（证明用例判别性）。还原后复跑 89/0/0。

## 自伤留档（本轮实测，与 T-MergeMasterToDev2 同型第二遇）

**`./gradlew ... | tail -60` 让管道退出码取 tail 的 0** ⇒ 任务完成通知报 exit code 0，
实际 `compileTestKotlin` 编译失败被裁掉。改「重定向到文件 + 单独 `echo "GRADLE_EXIT=$?"`」才看准。
**长命令收尾禁把 gradle 接在管道尾**。

## 遗留与边界（写进计划 §九，未解决）

1. **条件分支 append 会被全量聚合**：`SqlStringConcatenator.collectAppendCalls` 扫描整个方法体、
   不区分 `if` ⇒ `if (param.containsKey("仓库序号")) sb.append(" and loc.warehouseId = ")` 这类条件片段
   被无条件计入，用户这段代码的预览是「所有可选条件全在场」的**最大化 SQL**，非某次运行的真实 SQL。
   JdbcTemplate 通道既有口径，本轮**沿用未改**，需向用户说明。
2. **真实工程端到端未验**：`WmsLocation` 实体解析、`sub.qty.baseQty`（`@Embeddable` 组件路径）、
   `NOT EXISTS`/`IN ('I','N')`/`= false` 透传——见 [[project-embedded-col-prefix-gap]]（第三项已由该轮
   独立立项，正是同一句 HQL 的第二个缺陷）。
3. **`createQuery(sb)`（StringBuffer 变量直传）未放开**：Java 下不编译（`createQuery` 收 String），
   现实形态是 `createQuery(sb.toString())`；`hasStringHqlArgument` 的类型门仍只放行 String 变量。
3.5 **T-HqlHelperReturn 已补（`ee619b45`，本条是 §遗留的销项）**：用户真机报障——demo 复现用例把 HQL
   抽进 `buildAvailableInventoryHql()` 后，调用处首参成了**方法调用**，插件当时既不亮图标、快捷键预览
   也失败（`locate` 返回 null）；用户回「这种写法也很合理」遂**落成能力**（不是只让 demo 迁就插件）。
   实现＝`SqlStringConcatenator.concatUnwrappingToString` 改带深度私有重载，MCE 分支在
   「`.toString()`/`.append()` 解包」之外新增 `concatMethodReturn`：解析被调方法体、收其**自身**的
   `return`（排除嵌套方法与 lambda 体内的）逐条聚合，**只有全部 return 文本一致才采纳**
   （多分支返回不同内容 → null，不猜）；深度上限 `MAX_METHOD_FOLLOW_DEPTH = 3`（防递归/环）。
   放行判据新增 `PreviewReceiverResolver.returnsStringFromSourceMethod`＝**返回类型是 String 且
   `body != null`**——两道缺一不可：「返回类型 String」挡住 `selectList(<wrapper 链>)`（防 S1 老误判回归），
   「有源码体」把 JDK/三方库方法（`String.valueOf`）排除。`HqlQueryLocator.hasStringHqlArgument` 同步放行。
   **读数**：定向 16 类 **188/0/0**，其中特意带 `DemoRecognitionTest` + `DemoMpChainAnchorCensusTest`
   两道**既有 demo 语料锚点普查**（放宽判据最需防「既有语料凭空多冒图标」）；反向＝把「跟随方法返回」
   一档改成 `return null`（其余不动）→ `CustomDaoHqlLocatorTest`/`HqlQueryLocatorTest`/
   `DemoEmbeddedPrefixReproAnchorTest` **各 1 例红**、两条负向用例（多分支不一致、返回非 String）保持绿。
   **门禁翻正**：`ui/gutter/DemoEmbeddedPrefixReproAnchorTest`（新增）加载 demo 复现模块服务源码，
   断言三种承载形态（拼接变量 / StringBuffer+`sb.toString()` / 方法返回）**各恰 1 枚图标**，
   外加「返回非 String 的方法调用 → 0 图标」反向断言。
3.9 **demo 侧语料已扩到「实体来自 JAR」形态（20261016，与 [[project-embedded-col-prefix-gap]] 同批）**：
   `jpa-jar-model-demo`（8088）的 `WmsInventoryJarQueryService` 备了**两种**承载形态（拼接变量、
   StringBuffer+`sb.toString()`），DAO 名 `WmsJarCommonDao`；插件侧门禁
   `ui/gutter/DemoJarModelReproAnchorTest` 断言两处 `findByQueryMaxNum` **各恰 1 枚图标**
   （与 `DemoEmbeddedPrefixReproAnchorTest` 同款手法——**「新增 demo 复现语料必须同步加锚点门禁」已成定例**，
   起因是 20261013 那次「复现用例自己不可预览」）。
4. 旧文案佐证装机版本旧（截图窄口径提示在 `1b9b2a8e` 2026-10-11 已换全类型面文案，该笔**不含 tag**；
   本机实测装 `mybatis-plus-sql-visualizer-2026.9.18-beta.4.jar`），但本缺陷**在最新已发布 tag
   （2026.10.9）同样存在**——升级不是解法，修复只能进后续版本。

相关：[[project-embedded-col-prefix-gap]]（同批同句 HQL 的 @Embeddable 前缀缺陷）、
[[project-pending-plans]]（在册计划速查）、[[pitfall-verification-blindspots-and-wrapup]]
（§十三＝converter 级单测看不见生产入口断链）、[[pitfall-fake-green-test-assertions]]
（反向守卫：非字符串首参/wrapper 链必须仍不认领）。
