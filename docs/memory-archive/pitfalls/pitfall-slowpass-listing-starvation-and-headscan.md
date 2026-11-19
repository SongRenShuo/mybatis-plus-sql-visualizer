---
name: pitfall-slowpass-listing-starvation-and-headscan
description: 慢趟 LineMarkerProvider 里做全项目清单扫描（PSI 全量解析）在大工程/根工程 scope 下被 JPS 写盘反复取消饿死——修法=清单移出慢趟改异步+不写 MISS+落地一次性 restart；清单本身改 VFS 原始头扫替代全量 PSI；附静态缓存跨测试污染与探针方法论
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4043a4bb-4569-4ef9-8e28-84e10cd22b20
---

T-GutterFirstOpenDelay Java 侧续刀（20261115，`6bc12e9c8`/`81884a539`/`588d64604`）实证的四条硬事实：

## 1. 慢趟 ReadAction 里的全项目清单扫描会被写盘饿死，且结果固化

`MapperJavaToXmlLineMarkerProvider`（慢趟 collectSlowLineMarkers）依赖 `MapperXmlLocator.locate` 建「全项目 mapper XML 清单」——demo **根工程** scope≈4841 XML（含 2042 个 `target/classes` 拷贝，比单模块工程翻倍）时，首扫（逐个 PSI 解析 rootTag）在慢趟 ReadAction 里被 JPS 持续写盘反复 PCE 取消 → **慢趟饿死 → 图标永不落地**；cust-big-demo 单模块工程（~2100 XML）尚可自愈。慢趟不会自动重跑：一次「暂时的失败」固化为长期缺席，直到文件改动触发 daemon 重分析（敲空格+撤销即回，实锤）。判据=两个 mapper Java 文件全局无图标（非文件特异）+ daemon 重跑即回。

**修法**（MapperXmlLocator）：无可用槽 → 只踢后台异步构建（`ReadAction.nonBlocking + inSmartMode + expireWith + coalesceBy`，可取消）+ 立即返回 null 且**不写 MISS memo**（「还没查到」不得固化为「真没有」）；构建落地 `missedWhilePending` 置位 → 一次性 `DaemonCodeAnalyzer.restart()` 让慢趟重跑（防自激：只在 pending 期有落空才重启）；TTL 过期 → **serve stale + 后台刷新**（stale-while-revalidate，图标不因刷新缺席）。

## 2. 清单本身改「VFS 原始头扫」替代全量 PSI 解析

读每个 XML 头 16KB 原始字节（`vf.inputStream.readNBytes`，ASCII 兼容 GBK），正则提取 `<mapper …>` 开标签内的 namespace（`<mapper[\s>/]` 锚不误收 `<mappers`；namespace 双/单引号都认）——4841×16KB≈2~3s 后台完成；慢趟定位只对 namespace 命中的 1~2 个候选付 PSI。**ORM 兼容兜底**：头扫窗内没看到 `<mapper` 但文件名含 Mapper/sqlmap 的（MBG/各代 ORM 生成物命名惯例，覆盖根标签前超长注释等盲区）回退一次 PSI 取根标签 namespace；iBatis `<sqlMap>` 与旧口径一致不收入（独立定位链负责）。**曾有跨代描述符缓存（path|stamp|length 键）被砍**：LightVirtualFile（内存 fixture）path 固定、stamp 不可靠，跨测试/跨代毒化；TTL 30s 下重建频率低，头扫本身够便宜。

## 3. 静态缓存/槽的跨测试污染＝「单跑绿、全套件红」的典型形态

`MapperXmlLocatorTest.test命中` 全套件跑红、按方法单跑绿——污染源是 object 级静态 `fileListSlot`/memo/旗标跨用例残留（BasePlatformTestCase 每方法新建 project，但静态槽是全局的）。修法＝测试类 setUp/tearDown 强制 `clearFileListCacheForTest()`（连带清旗标）。同族先例：candidate-scan 的注入缝 tearDown 复原。**曾试过的 descCache（path|stamp|length 键）在 Light fixture 下必毒化**（见上），别再捡回来。

## 4. 探针方法论：BasePlatformTestCase 下 Logger.info 不进 JUnit XML

要看测试环境内的中间状态，用 `System.err.println`（进 JUnit XML 的 system-err），别用 Logger.info；阶段探针（slotFiles/match/PSI rootNs/tagFound）比断言消息更快定位「哪一环断了」。另：python heredoc 往 Kotlin 写字符串模板时 `${'$'}` 转义曾把插值写成**字面量**（cacheKey 变常量串 → 全部文件共用一条缓存互相毒化）——跨语言写代码后必回读核验关键行。

关联：[[project-gutter-first-open-delay-20261114]]（全案时间线与提交）、[[pitfall-mapped-exec-file-write-triggers-dumb]]（同役惰性口径）、[[pitfall-m2-warm-retry-self-exciting-loop]]（restart 防自激同款约束）。
