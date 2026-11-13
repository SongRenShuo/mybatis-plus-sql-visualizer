---
name: project-goldendb-dialect-20261107
description: GoldenDB（中兴）方言对接（20261108 已合 master e302f0c0 并双仓推送，CI run 42399728368 全 16 job
  success）——四券+P-1 销案+驱动级替代读数 PASS 384 词；文档站 dev 8b9e860 四表行；demo 仓 21 例已合
  master；措辞红线：只许「驱动级替代读数」，禁「真机验证」
metadata:
  node_type: memory
  type: project
  originSessionId: sess_33620d7f-a4ec-4f92-8fbe-dcd146afc43f
---

# GoldenDB（中兴）方言对接（2026-11-08 已合入 master 并发版验证）

## 状态

- 计划册在 master `538759ef`（基线 `d8fa12f9`）；用户令「实施计划全部内容、并发上限 2」。
- 三工作树：`.worktrees/goldendb-a`→`t-goldendb-mapping`（381c2550）、`goldendb-b`→`t-goldendb-capture`（a4aefa4a）、`goldendb-int`→`t-goldendb-integration`（**HEAD `62010c10`，共 12 笔**）。
- 合流零冲突（merge 提交 `5b734e47`）；波次 2 新增 3 笔：`e9a0fceb` plugin.xml 14→15、`2e8f828c` 券C、`62010c10` 券D。

## 波次读数

**券 A（6 笔）**：`GOLDENDB_RESERVED` 384 词（三表并集 385−STORAGECLUSTER；与 MYSQL_RESERVED 差集 147）；第 15 方言（byName 严格等价/两条 URL/角标/MCP/执行路由）；**P-3/P-6/P-8/P-9 全销案**——P-6「金篆」确证（p1 金篆信科 9 处，收别名；「中兴」0 命中不收）、P-8 IU-262.9437.185 全量 345 xml grep golden 零命中→自注册四件套成对（PairingGuard 绿）、P-9 EXPLAIN p323-325 只有语法无输出示例→落 `template=null` 条目（「完全不登记」会撞 ExplainSupport 全量覆盖守卫，必须 null 条目）。

**券 B（3 笔）**：五类三面登记（NonRegisteringDriver 必须+Driver 按名可查）；字节层 17/0/0 全织入过 JVM 校验；两处反向验证红恢复绿；G9 框架包前缀实锁 **4 份副本**（CallerSourceLocator:79/SourceInterceptorTemplates:284/StackDigestFrames:157/agent StackLocator:95+守卫锚点）非计划写的 3 处。**计划前提证伪**：①SPS 自有 `addBatch()` 无参、`Kind.ADD_BATCH` 只认 `addBatch(String)`（父类）→SPS 对 ADD_BATCH/EXEC 全零命中，full-face 覆盖来自 PARAM setter 族（批量 SQL 走父类 BATCH 形态，无缺口）；②五类登记进 `BYTE_VERIFIABLE_EXCLUDES` 而非计划的 `JAR_OPTIONAL_BYTE_LAYER`（后者会把 MariaDB/SQLite jar 用例整条误降级 skip）；③VerificationLoader 须补 getResource 覆写（GoldenDB `<clinit>` 触 ResourceBundle，磐维没踩到）。

**集成（波次 2）**：合流守卫批主仓 15 类 **114 例/0 失败/1 skip**（skip=无 .local 矩阵既有形态）+ agent 17 例 = 131；`verifyPlugin` IU-243/253/263 三版全过（3m6s）；券 C 反向验证成对读数（renderUpsert 置 null→4 failed 含文档站对账，还原绿）；券 D CHANGELOG 两条带 hash、架构文档 01 篇 14→15+goldendb/ 子包、计划册 §7 汇总、P-10 销案（架构文档无方言清单正文副本）。

**文档站兄弟仓**（用户令「dev 分支做、先 master 合 dev」）：master→dev fast-forward 到 `38073ed`；dev `8b9e860` 补四表行（wrapper-preview 分页 zh/en + batch-operation UPSERT zh/en，均 MySQL 形态）；守卫用 `MPV_DOCS_ROOT=…-docs-dev` 实测对账 9/0 绿。dev 工作树 11-03 旧数据同步未提交改动已留底 stash `stale-data-sync-20261103-留底-before-goldendb-docs`（内容已被 master 新数据覆盖，可 drop）。**守卫不读分支**（按兄弟目录文件读；主 checkout 在 master 则看不到 dev 行；dev 被 docs-dev 工作树占用，主 checkout 无法切 dev）。

## 待办/红线

- **已合入 master `e302f0c0`（2026-11-08）**：18 笔集成分支合流，CHANGELOG 冲突逐 hunk 并集仲裁（master 侧 T-SqlListCatchup 券A/B Changed + 分支侧 GoldenDB Added×2 / RuntimeSlotFill Fixed，段序 Added→Fixed→Changed）；合流后守卫 6 类 100 例全绿；双仓 master 均 `e302f0c0`；**CI run 42399728368 全 16 job success**（changes/database/extras/e2e/dialect/build/fast×2/tests×5/verifyplugin/marketplace×3）；`t-goldendb-*` 三支与三工作树已清理（删前验 tip 可达）。
- **`t-docker-gate-offload` 也已合入 master `95e107b1`（2026-11-08）**：LESSONS 冲突并集——master 侧 L19~L23 保留、分支原 L19（共享实例固定表名互删）重编号 **L24**；合流态 `DialectDockerVerificationTest` 6/0/0/6skip；双仓已推；**CI run 42402431078 全 success**；分支与 worktree 已清理。

- 集成分支终态 **`83f718ae`（16 笔）**：门禁唯一红（CrudSqlGeneratorTest 不折叠族守卫缺 GoldenDB，NONE 族默认继承）已修 `057e6982`→定向绿；§7.0.1 门禁读数回填；P-1 销案回填 `83f718ae`。
- **P-1 已销案为「不可部署」**（2026-11-07 T-GoldenDbDeploy 六源取证）：Docker Hub/quay 零命中、ghcr/ACR/SWR 未取到、GitHub/Gitee 仅适配工具（jzload DB.rar 来源不明单组件不可用）；官网介质需金篆社区注册申请。**即便拿到安装包，单机最小内存 ≈12GB vs 靶机预算 1.67GB**（available 2789×60%，12 存量容器不可动）。真机唯一路线=商务渠道索取镜像 tar/授权安装包+腾量或换大内存靶机。P-2/P-5/P-9/保留字引擎读数维持未验证。
- **demo 仓 GoldenDB 21 例已合 demo master `fcb1faf`**（8 类：分页/upsert/多行 VALUES/DELETE LIMIT/保留字/事务/REPLACE INTO/连接身份；skip 契约=环境变量未设显式 skip，MySQL 8.4 对照 19 例全绿，假端点红且异常链带 com.goldendb.jdbc）；插件夹具钉定随更 `c2358cb6`（demo-revision.txt→fcb1faf，db/*.sql 仍 74cc257 期）。
- **集成分支合回 master 等用户点名**；push/tag/文档站 dev push 均未做等授权。
- 文档站 dev→master 合流时机=插件发版（守卫比的是被测插件码的 DialectRegistry，插件 master 未含 GoldenDB 时 dev 行在主仓守卫反而报「未知方言」——时序自洽）。
- 恒久口径无真机不宣称「方言可用/兼容」（§6.1 措辞分档，CHANGELOG 已按「文档级」落）。
- **用户拍板「驱动级替代读数」档（20261107）**：用现有 MySQL 8.0 实例承载 GoldenDB 官方驱动跑插件验证面；**措辞红线=文档禁写「经过真机验证/GoldenDB 引擎验证」**，统一「驱动级替代读数（GoldenDB 官方驱动 × MySQL 协议实例承载），非 GoldenDB 引擎验证，引擎行为类结论仍为未验证」；已落 §7.6 + CHANGELOG 半句追加（`1b044d6f`）。
- **驱动级替代读数实测**：MySQL 8.0.46@25900（xc3-mysql8）vendor 驱动 5.1.46.99 **直连成功无回落**（caching_sha2_password 预案未触发，JDK8/17 双证）；矩阵 `goldendb GoldenDB words=384 batch=on` **PASS 1/0/0/0 零失败**（首轮 900s 预算 TIMEOUT 是 1g 容器 DDL 6.3s/词慢、非挂死，1500s 过）；外指腿 923s 过；三轮累计表零残留。**遗留决策**：测试 classpath 驱动经 `.local/goldendb-testcp.init.gradle` 临时注入，长效正解=仿磐维一行 `testImplementation(files(...))`，§7.6 留决策未落。

相关：[[reference-cn-db-driver-families]]、[[pitfall-sql-dialect-needs-parser-definition]]、[[pitfall-explain-format-tier-vs-reality]]、[[pitfall-dialect-doc-table-guard-cross-repo]]、[[feedback-vendor-fixtures-version-controlled]]。
