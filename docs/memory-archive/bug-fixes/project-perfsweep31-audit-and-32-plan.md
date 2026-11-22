---
name: project-perfsweep31-audit-and-32-plan
description: 第九期性能分析（分支态审计）+ T-PerfSweep32 已实施（t-perfsweep31 分支 4 笔提交，2026-11-18）——销账矩阵全过、券02 键卫生、收口轮修分支遗留 3 红
metadata:
  node_type: memory
  type: project
  originSessionId: sess_8add355f-a681-4a00-9d4d-52f638fd1241
---

2026-11-18 主控完成第九期 IDEA 性能影响深查（报告 `docs/性能分析/20261118-0001-IDEA性能影响-性能分析.md`、计划 `docs/开发计划/20261118-0001-T-PerfSweep32-…-开发计划.md`，master `0960b0197`），随后用户令「master 并入 t-perfsweep31 并实施计划全部内容，全量交 CI」——已在 **t-perfsweep31 分支**实施完毕（合并 `803e31f86` + 券02 `db3816ec4` + 收口轮 `95d2e46e2` + docs `5feeb657b`，已推双仓，CI run 44130501661 mode=tests）。

关键结论（引用前先 git 实测）：

- **第八期全部余额已在 `t-perfsweep31`（tip 8820443fb，27 文件 +677/−199）实施完毕**：券01 typeAlias 索引化（新索引 `SqlMapConfigAliasIndex` v1，键 `cfg`+`ta:<alias>`）、券02 DumbMode 六闸（比计划四处多两处）、券03 预览链 cache-only+runWhenSmart 重试可见归因、券04 四处缓存键加 `locationHash|` 前缀、券05 HqlGutter type 直判。master 侧一笔未动——合流即销账，禁在 master 写第二份等价修复。
- **发行树流程风险**：分支 tip 基线在 `164e6a884` 之前，不含 v2026.11.17 修复；merge-tree 实测 src 零冲突（唯一 both-modified＝CHANGELOG.md 一个 hunk），IbatisStatementPreview −40 等负数是 master 单方增量的口径假象。**发行物料必须以合并后树构建**。
- **第九期唯一新发现 P3**：`MapperNavigationLineMarkerProviders.resolvedStatementKeys`（:39，键 `$namespace#$id` 无项目身份，五触达点）与 iBatis 缓存族同族 → T-PerfSweep32 券02（master 独立小刀，与分支触达面不重叠）；`XmlSqlGutterLineMarkerProvider.warmedContextFiles` 口径补登即结。另有归属修正：optimisticLoggedKeys 实际在 IbatisJavaToXml:113。
- **新索引成本边界登记**：SqlMapConfigAliasIndex 对每个 XML 内容变更至少 2 次全文根锚正则（`contains("alias")` 早退在根锚之后）；`#config` 头窗化被分支语料否证（`<properties>` 超窗 67085 字节）——两个头窗化方向都不立项。
- hiddenbug 支性能券基准：券 D 历史读取 15~16.6s→701ms、券 Q 顶格扫描 O(n²)→0ms、券 T 判重→14ms；**索引口径订正（券03 走查实证）**：dev 与 master 同为 2 个索引 EP（SqlMapNamespaceIndex 即 iBatis DAO 双图标索引本体），合流后 master=3（+SqlMapConfigAliasIndex）——初稿「六索引」是按提交标题误判。PREWARM_WAIT_MS=500 禁盲调（T0 重采样移交）。

**实施要点（2026-11-18）**：
- 券02＝resolvedStatementKeys 五触达点改 `cacheKey(project,…)` 带 locationHash 前缀 + warmedContextFiles 补登记 + 反向守卫；券01 销账矩阵 14 项全过（含 164e6a884 幸存确认）；反向双探针（typeAlias 插禁用 token / cacheKey 摘 locationHash）各红→绿。
- **分支遗留 3 处测试红（stash 基线实证非本次引入，8820443fb 未跑绿轮）**：①IbatisNavigation 箭头门槛三用例裸键断言未随券04 同步；②SqlMapConfigAliasIndexTest 同路径二次 addFileToProject 抛 IOException（改 WriteAction 内 setBinaryContent）；③**IbatisTypeAliasResolverHotPathTest 三方法缺 @Test 无 TestCase 血统＝券01 反向守卫死了一整轮**（JUnit3/4 双扫描器静默零执行，`--tests` 指名跑在混合 pattern 下连 No tests found 都不报）。已修并新增 TestClassVisibilityGuardTest 元守卫防复发。
- dev 合流后复验项：SettingsLastGoodStartup 应用级裸 daemon（无 Disposable）收编、XmlCrossNsFragmentResolver 显式 dumb 闸、EXPLAIN 前热 cacheOnly 对齐。
- 本地跑测试遇并行任务占默认沙盒 → build.gradle.kts 临时加 else 分支 sandboxContainer=file("build/<自己的沙盒名>")，用后还原勿提交；fileValue 要 File 不吃 Provider。
- **CI 全量绿（run 44133130041，build+test(1~5)+fast 全 success）**；第一轮 44130501661 独红＝fqnOf 用例同名别名随索引候选顺序漂移（addFileToProject 不建新模块，定向跑恒绿、分片才暴露——`5b7cdba18` 改别名去歧义）。收尾撞上别车道同补丁双语言提交分仓各推（Gitee 中文 `3406cb0da` / GitHub 英文 `28b8323f1`，同父同树）→ rebase 自动去重 + GitHub 快进 + Gitee force-with-lease（先 `git diff 旧tip 新tip` 证树等价再推）。分支终态 tip `efcfad0b6` 双仓同步。

相关：[[project-pending-plans]]、[[reference-worktree-cleanup-recipe]]。
