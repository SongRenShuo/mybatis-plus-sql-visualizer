---
name: project-k2j-178-divergence-and-179
description: 2026-11-17 k2j 端口分支发现同号 178 双支线分叉的处置与 T-SyncK2JMaster179 开轮——本地旧
  178 备份后重置到 origin 完成版，只同步真实增量 183c98b3d→906039f29
metadata:
  node_type: memory
  type: project
  originSessionId: sess_90f531a6-d524-4ff9-8407-28f12055752a
---

# k2j 同号「178」双支线分叉处置 + T-SyncK2JMaster179 开轮（2026-11-17）

## 事实（开工 git 实测，非推断）

- `feature/kotlin-to-java` 本地 worktree（`F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-kotlin-to-java`）在会话开始时 HEAD=`c35d84313`，跑的计划是 `20261117-0854-…T-SyncK2JMaster178-…`，声称冻结 master tip `906039f29`（范围 `95e107b1..906039f29`，523 提交/527 文件），且 plan §8 写「生产面 compileJava 绿、测试面 1558 错未收口」。
- 但 `origin/feature/kotlin-to-java`=`1b46c290a`（Gitee origin；GitHub 同名远程另计）**已于 2026-11-14 独立完成了另一个 T-SyncK2JMaster178**（计划 `20261113-0007-…`），锚点同为 `95e107b1`、冻结 tip `183c98b3d`（188 提交/333 文件），全绿门禁收官、`.port-sync synced_master=183c98b3d`、note 写明 5 片全绿 8400+/agent 290/verifyGate 三 Compatible/buildPlugin sha。
- 两线在 `efd3f9e0a`（2026-11-09，177 轮末）分叉：本地 49 笔、origin 207 笔；`git rev-list --left-right --count HEAD...origin/feature/kotlin-to-java`=49/207，互不为祖先。`183c98b3d` 是 `906039f29` 的祖先，两者差 **209 提交/257 文件**（master 侧 58 生产 .kt + 57 测试 + 资源/构建链；agent 子项目本轮零增量）。
- 根因：本地那条 stale 支线把 origin 已完成的 `95e107b1..183c98b3d` 又当未完成增量重做，导致大量「缺失生产类」编译错（其实 origin 早有），且会话中一边修测试一边又补产生产生更多不一致。

## 用户定案与处置

- 用户选「**重置到 origin 再续**」（另两选项：继续修本地旧支线 / 只做调查）。执行：
  1. 本地 49 笔备份分支 `backup/k2j-178-local-stale-20261117`（HEAD=`397d9fe9c`）。
  2. `git reset --hard origin/feature/kotlin-to-java` → HEAD=`1b46c290a`，干净。
  3. 新计划 `docs/开发计划/20261117-T-SyncK2JMaster179-主分支功能同步-开发计划.md`（W0），范围冻结 `183c98b3d→906039f29`，明确不追 master 现 tip `a4ad7a5bc`（2026.11.17-beta.1，多出 16 提交含 T-IbatisAtShapeGate 修复——属另一条活跃任务车道）。
- fresh origin 树 `compileJava + compileTestKotlin` BUILD SUCCESSFUL / 0 错（基线实证）。

## 复用备份的教训（重要）

- 备份支线各时点生产树**自洽性不同**：会话起点 `c35d84313`（W1 采用）`compileJava` **绿**（0 错，176 文件）；而备份末态/各中途点（含我会话轮2/7 改过的 ParamPanelKt 3125 行版）**不绿**——`SqlPreviewPanelParamPanelKt` 引用了 `SqlPreviewPanel` 没有的 `paramLabelRefs` 等字段，110+ 错。
- `git checkout <ref> -- <dir>` 不会删除 origin 有而该 ref 没有的文件 → 残留重复类（`IbatisXmlAttributeReferenceProvider` 等 12~14 个），必须按 tree diff 显式 `git rm`。
- 结论：**整棵树复用必须挑「当初 compileJava 绿」的那个时点**（本轮 `c35d84313`），并显式删 origin-only 文件；混用 origin 与备份的 M 文件会产生 174 错的不一致集。
- 具名实参批量转换脚本 `build/convert_named.py`（在 worktree build/，非记忆文件）曾有 bug：把 `kind == IbatisTagKind.ITERATE` 里的 `==` 误判为具名实参 `=` → 生成 `IbatisTagNode(= IbatisTagKind.ITERATE, …)`，用前必 dry-run 复核。

## 收官状态（本会话已完成）

- **最终 41 笔提交**：`6ced8d044`（W0 立项）→ `2bcc37b12`（flake 根治后的文档订正收官），双仓已 push（Gitee origin + GitHub 镜像），工作树净。收官链：`0d4641574`（原收官）→ `4d523ace7`（CI flake 根治）→ `2bcc37b12`（文档订正）。
- **范围**：真实增量 `183c98b3d..906039f29`（209 提交/257 文件）；`.port-sync` 锚点推进到 `906039f29`，note 记录本轮全文；计划 §8 回填；台账两册各加本轮行。
- **执行**：主控 + 最多 4 子代理并行波次。W1 生产树采用 `c35d84313`（自洽、compileJava 绿）；删 9 件误加 .kt 测试孪生件（保 origin .java 承载）；A/B 波测试编译适配；6 波生产 internal helpers 回填 38 符号；C 波 docs/direct-copy + 台账三方合并；生产行为回填 8 族。
- **本地门禁**：`compileJava`+`compileTestKotlin --rerun-tasks` 0/0；45 个曾失败类本地全绿。
- **CI push-test**：首轮 93 失败/42 类 → 4 代理修红至 4 → 二轮 1（EntityClassFinder 缓存失效+gutter seam 漏）。曾残余 **1 条 CI-only flake**（现已根治，见下）：`McpSourceLocatorRenderAtLineTest::testiBatis_Java调用行`，只在 CI testShard3 长分片出现 `SOURCE_NOT_FOUND`；本地单测/组合跑/完整本地 testShard3 全绿，生产定位链与 master 冻结 tip 逐字一致。诊断（grep 索引）证明 SqlMapNamespaceIndex 索引可见，但长分片 resolveStatementAnchors 返回空锚。多轮修复无效：接缝自清（测试 setUp/tearDown）、addSqlMap 机制切换、`FileBasedIndex.ensureUpToDate`、EDT-pump 等 dumb；`waitForSmartMode()` 在 EDT 测试线程挂死 CI 17+ 分钟已回退。**根治**：`4d523ace7` — 根因是长分片下写 sqlMap 触发 dumb mode，索引查询抛 `IndexNotReadyException`→空锚；生产链与 master 逐字一致，是测试对索引异步可见性的环境时序依赖（索引正确性另有 `SqlMapNamespaceIndexTest` 专测）。修法＝`addSqlMap()` 用该链自带 `lookupOverrideForTest` 缝从刚建 PSI 直接提供 id 锚点（按 `namespace.id` 取裸 id 匹配），测试对 dumb/索引时序完全免疫，`tearDown` 复位。**教训＝对抗时序（接缝自清/EDT-pump/ensureUpToDate/waitForSmartMode 全无效或挂死）不如消除时序（测试缝注入确定性锚点）**。CI run 44077846390 + 44082606101 的 test(3) 连续两轮转绿，根治确认。
- **坑沉淀（本轮新增）**：①同号双支线分叉以 `.port-sync` + origin tip 判已做，不能只看本地 plan 文件名；②整树复用必须挑「当初 compileJava 绿」的时点并显式 `git rm` origin-only 文件；③Kotlin 顶层/密封/具名实参 → Java 门面/枚举常量/位置参是编译适配主形态，Java record 无 `copy()`；④object 级静态测试缝（`lookupOverrideForTest` 等）跨用例泄漏致 CI-only 顺序依赖失败，setUp/tearDown 自清；⑤`waitForSmartMode()` 在 EDT 测试线程挂死，改用有界 EDT-pump；⑥写 CI 诊断时不要直接调索引查询（dumb 下抛 IndexNotReady 盖住真因）。

## 判据/纪律

- **续轮（2026-11-17）补完 test(5) 真绿**：`test(5)` 此前每次 31m2x 撞 30 分钟步骤超时（日志零输出），被误记「历轮稳定绿」。两步真因：①**超时吞掉该片 15 个失败**——用 `dev-support/test-shard-replay.py`（新增，按入库权重离线复算分片，打印行与 CI 逐字一致）拿到 shard5 的 160 类，本地整片跑得 15 红/5 类，全是 Kotlin→Java 漏移植（lexer iBATIS 单字符定界符四态 `69a132e56`；`XmlPreviewService` 多文本静态常量 + 测试漏 setUp 隔离 `e145e7f79`；`<pages>` 误接全扫探针 `8f2668c8a`；设置面板漏 `localDirField` 的 isModified `33ad7ac6e`）；②**超时本身独立成因**＝`plugin.xml` 的 `<standardResource>`（iBATIS DTD）令平台把插件自身 jar 算进索引根，而测试沙盒 `plugins_<任务>/…jar` 不在 `VfsRootAccess` 放行集合 ⇒ 冷沙盒首建索引抛 `VfsRootAccessNotAllowedError` → 扫描中断 → dumb 不退 → EDT 索引等待自旋（修 `96d99e378`：测试任务 `environment("NO_FS_ROOTS_ACCESS_CHECK","1")`，平台官方逃生门）。**热沙盒不触发**（持久化索引已有该根），所以本地反复复跑一直绿、CI 冷树必挂——判「分片绿」必须冷沙盒复跑（挪走 `build/test-sandbox/<片>/system_<任务>` 强制重建索引）。冷沙盒修复后 `:testShard5` 160 类全落盘 BUILD SUCCESSFUL 6m11s；CI push-test 连续两轮全绿（`44106324994` @`96d99e378` test(5) 10m0s、`44108659553` @`bdcfe5551` test(5) 9m16s），gate `44108708496` @`bdcfe5551` 全 job success（build/test×6/verifyplugin/verify243·253·262/marketplace，publish skipped）。另加 CI 分片进度诊断步骤（`if: always()` 打 completedClasses/首末完成时刻），此前 `completedClasses=0` 即此坑的判据。
- 判「某轮是否已做」以 `.port-sync synced_master` + `origin/feature/kotlin-to-java` 实际 tip 为准，**不能只看本地 worktree 的 plan 文件名/编号**——同号 178 存在于两条不相交支线。
- 见 [[project-kotlin-to-java-port-sync]]（总机制与 44 条翻译坑）。
- **续修 2（同日）**：`PlatformSignatureCrossVersionTest` 本机红＝测试侧 `src/test/java/PlatformSignatureChecker.java` 漏移植 T-DynUpdate263 的「卸载预检能力族」（`06c35f319` 从 master 整文件直取）——注意 `src/test/java`（非 kotlin）也在移植扫描盲区里；反向还原旧版恰红（IU-263 MISS allowLoadUnloadWithoutRestart）。姊妹面 Mcp/Acp 两个跨版本签名测试横审无残留旧断言（差异属 K2J 适配注释），`:testExternal` 整桶 42 例 0 红。
