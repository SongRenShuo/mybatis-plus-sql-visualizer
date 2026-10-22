# master 合入 dev 与第六轮五计划/T-PerfSweep4/尾巴项批次验收文档

> **2026-09-20 防过期复核**：类名/工具名/入口路径已按当前 master 代码校正；全量 `testShards` 复跑 **5383/0/0/6 绿**。

> **验收对象**：2026-09-17 dev 批次——master 合入 dev（merge `ee376e3`，冲突 5 文件全解决 + dev 独有 mcpStub/snakeyaml 四块回植）+ 基于合并后 dev 交付的 2.2 第六轮竞品应答五计划（T-TxnReadOnlyProof/T-ExecTimeoutGuard/T-DmlAliasGuard/T-TsqlBatchSplit/T-UpdateZipBounds）+ T-PerfSweep4 性能七项收敛 + 2.4 尾巴项收口，提交 `83d4744`…`37eaf12`（8 提交，图谱：83d4744 flake 修复 → 8db852c 前两件 → 2d6dc96 → eea2a1a → 56beade → 021e8a4 → 2f2ccc2 文档 → 37eaf12 基线）。
> **面向读者**：功能验收人（含用户本人真机走查）。各任务实现细节与定向测试记录见对应开发计划 §实施记录（均已回填）；本文档只列验收步骤与预期，不重复实现细节。
> **回归基线**：全量 **4817/0/0/6 绿**（412 测试类，`./gradlew testShards`，2026-09-17 批次末实测；跳过 6=Docker 环境门控）。
> **真机走查总清单**：见 §四（汇总各计划 §五，共 13 条留人工）。

---

## 一、环境准备

任选其一：

- **装发行包**：`./gradlew buildPlugin` 产出 zip，IDE（2024.3+）「设置 → 插件 → ⚙ → 从磁盘安装」。
- **沙盒跑**：`./gradlew runIde`（dev worktree 惯例，JDK17+）。

建议准备：

1. 含 MyBatis-Plus 实体的演示项目 + 至少一个 MySQL 数据源；有 PG 数据源更佳（EXPLAIN ANALYZE / SELECT INTO 盲区语义最准）；
2. MCP 验收需 IDEA 2025.2+（内置 MCP Server）+ 任一 MCP client；
3. 执行链超时验收需能制造慢查询（`SELECT SLEEP(60)` / 锁等待）。

## 二、新增功能总览（入口速查）

### 2.1 本批用户可见面

| # | 功能 | 入口 | 来源任务 |
|---|------|------|---------|
| 1 | 可写 CTE / EXPLAIN ANALYZE / SELECT INTO 不再免确认 | 预览执行上述三类语句时弹确认（此前直通） | T-TxnReadOnlyProof |
| 2 | 设置项「执行超时（秒，0=不限）」 | 设置页 → SQL 预览分组（JdbcTemplate 预览行之后） | T-ExecTimeoutGuard |
| 3 | MCP explain 超时护栏（挂死查询不再无限等待） | MCP `sqlv_explain` 输出错误尾附 `timed out after Ns` | T-ExecTimeoutGuard |
| 4 | 增强捕获确认弹窗不再反复打扰：非主配置进程不弹、拒绝后 24h 冷却 | 首次 Run 项目（仅主配置启动触发一次） | T-PerfSweep4-01 |
| 5 | 双路解析封口（捕捉开启 × 控制台已存时序每行不再 ×2 解析） | 无观感，行为/性能观察项 | T-PerfSweep4-02 |
| 6 | 同 pid 3s 内重复 Run 不再重复排队 attach | 同一应用快速重启场景日志 | T-PerfSweep4-03 |
| 7 | 自更新包守卫：异常包（zip bomb/头谎报）下载安装时被拒并给可读原因 | Gitee 抢先体验通道下载安装 | T-UpdateZipBounds |
| 8 | T-SQL 控制流批不再拆碎（ELSE 起头碎句消失） | 日志还原/预览含 `IF…ELSE` 批的脚本，确认弹窗以整批出现 | T-TsqlBatchSplit |

### 2.2 无独立入口（行为/守卫观察项）

| 任务 | 验收看什么 |
|------|-----------|
| T-DmlAliasGuard | 无行为变化（三条生成链零别名现状的防回归守卫），真机随发版过一遍 crud_sql 产物即可 |
| T-PerfSweep4-04/05 | 无观感（正则常量化/签名截断 256），回归不劣化即可 |
| T-PerfSweep4-06/07 | 复核销账（T-PerfTail 已收口），无新行为 |
| 尾巴项 | SpeedSearch 二级列表已随 beta.4 修复在位；T-BranchHygiene 闭环（文档） |

## 三、分功能验收步骤

### 3.1 确认流只读豁免三盲区（T-TxnReadOnlyProof）

**前置**：PG 或 MySQL 数据源 + 预览面板已出 SQL。

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | 预览执行 `WITH del AS (DELETE FROM t WHERE id=1) SELECT * FROM del` | **弹确认**（此前首关键字 WITH 免确认直通真执行 DELETE）；弹窗文案为既有 DML 确认形态，零新形态 |
| 2 | 预览执行 `WITH cte AS (SELECT id FROM t) SELECT * FROM cte` | 直通不弹（普通 CTE 豁免逐字节不变） |
| 3 | 预览执行 `EXPLAIN ANALYZE DELETE FROM t WHERE id=1`（PG） | **弹确认**（ANALYZE 真实执行被修饰语句） |
| 4 | 预览执行 `EXPLAIN SELECT * FROM t` | 直通不弹 |
| 5 | 预览执行 `SELECT * INTO t_backup FROM t`（PG 建表）/ `SELECT … INTO OUTFILE '/tmp/x'`（MySQL） | **弹确认** |
| 6 | 预览执行 `SELECT COUNT(*) INTO @total FROM t`（会话变量） | 直通不弹（@var 豁免维持） |
| 7 | 执行含列名 `deleted_at` 的 CTE 查询 / SQL 字面量含 'delete from t' 的查询 | 直通不弹（字边界/字面量屏蔽不误判） |
| 8 | 混合脚本 `SELECT 1; WITH d AS (DELETE FROM t) SELECT * FROM d` | 弹确认（任一非 QUERY 即确认） |

### 3.2 执行超时护栏（T-ExecTimeoutGuard）

**前置**：MySQL 数据源；设置页「执行超时」保持默认 30。

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | MCP `sqlv_explain` 跑一条 `SELECT SLEEP(60)`（直连链） | 约 30s 后返回错误，消息含 `timed out after 30s (cancel requested)`（agent 可读，不悬挂） |
| 2 | 设置页把「执行超时」改为 0 并 Apply | 再跑同查询：不超时（0=不限），跑完正常返回 |
| 3 | 改回 30，跑正常查询 | 行为零变化（超时只在异常态触发） |
| 4 | 预览面板/日志转 SQL 执行慢查询（共享控制台链） | 如实观察：该链 DBN 托管暂无语句级超时（v1 登记不覆盖，不得虚标已护栏）；行为与此前一致 |

### 3.3 增强捕获确认流收窄与冷却（T-PerfSweep4-01）

**前置**：全新配置（删 `%APPDATA%/…/options/mp-sql-visualizer.xml` 或对应状态文件）；增强捕获默认开、未确认。

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | 首次 Run 主配置（Application/JUnit 等） | 弹一次注入确认框（EDT 模态，同既有形态） |
| 2 | 点「取消」（拒绝） | 本次不注入；**随后 24h 内**：再 Run 主配置、发 HTTP 请求、跑测试、profiler——均**不再弹**（冷却） |
| 3 | 设置页关闭增强捕获再重新开启 | 冷却重置——下次 Run 主配置**再次弹一次**确认（改主意入口保留） |
| 4 | 步骤 3 弹窗点同意 | 确认持久化；后续 attach 正常、增强条目正常入列（主通道语义不变），永不弹 |
| 5 | 已同意状态下跑 HTTP 请求/测试 | 不弹任何框（已确认链路维持现状） |

### 3.4 T-SQL 控制流批不可拆（T-TsqlBatchSplit）

**前置**：日志 → SQL tab；SQL Server 数据源更佳（无则观察拆分/确认行为即可）。

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | 粘贴 `IF @x = 1 UPDATE t SET a = 1; ELSE INSERT INTO t VALUES (1);` 转换 | **整批一条**（无 ELSE 起头碎句）；确认弹窗以整批为单位出现一次 |
| 2 | 粘贴 `IF EXISTS (…) BEGIN UPDATE …; END ELSE BEGIN INSERT …; END;` | 整批一条（块感知形态） |
| 3 | 粘贴 `SELECT IF(a > 1, 'x', 'y') FROM t; SELECT 2;`（MySQL IF 函数） | 正常拆两条（函数形态不误吞） |
| 4 | 粘贴 `BEGIN; UPDATE t SET a=1; COMMIT;`（MySQL 事务） | 按顶层切分三条（既有保守语义不变） |
| 5 | 整批 T-SQL 提交执行/确认 | 分类落 UNKNOWN → 需确认（fail-closed 既有行为） |

### 3.5 自更新包有界守卫（T-UpdateZipBounds）

**前置**：Gitee 抢先体验通道可用。

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | 走一遍「检查更新 → 下载 → 安装」（真实 beta 包） | 全链正常过闸（守卫对正常包透明）；idea.log 无 zip guard 拒绝行 |
| 2 | （可选，伪造面）构造条目数 >64 或声明超限的 zip 走安装 seam | 安装失败，错误信息含 `too many entries` / `single-entry cap` / `total cap` 等实际值，且**不触达平台解压**（无半解压残留） |

### 3.6 回归抽验（合并带入 + 无观感项）

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | 预览执行普通 SELECT/UPDATE（确认流） | 既有确认/直通行为逐字节不变 |
| 2 | 日志转 SQL 多语句产物逐条执行 | 拆分行为与此前一致（既有样例无回归） |
| 3 | MCP 工具全量跑一遍（sqlv_render_sql/sqlv_explain/sqlv_check_sql 等） | 既有输出形态不变（explain 仅在超时时新增文本行） |
| 4 | sqlv_crud_sql 产物肉眼过一遍 | 无 `AS` 别名（守卫面已锁，肉眼复核即可） |
| 5 | 开启捕捉后先 Run 后点捕捉（或反向） | 日志条目无重复、无缺失（双路封口后与单路时序对照） |

## 四、真机走查总清单（留人工，汇总各计划 §五）

| # | 走查项 | 来源 |
|---|--------|------|
| 1 | 可写 CTE / EXPLAIN ANALYZE 弹确认、普通 CTE 直通（§3.1 的 1~4） | T-TxnReadOnlyProof §五 |
| 2 | 回滚弹窗「执行回滚」链（dmlConfirmed 豁免）不受影响 | T-TxnReadOnlyProof §五 |
| 3 | 锁表场景执行 UPDATE：MCP 直连链超时可恢复、提示可读 | T-ExecTimeoutGuard §五 |
| 4 | 多语句脚本（5 条慢查询）预算线性放大且封顶生效 | T-ExecTimeoutGuard §五 |
| 5 | MCP explain 必然慢查询输出含 timed out 文本行 | T-ExecTimeoutGuard §五 |
| 6 | 确认流收窄+冷却全矩阵（§3.3 的 1~5，含 24h 语义可临时调短验证） | T-PerfSweep4 §五.1 |
| 7 | 双路时序条目数与单路对照无重复无缺失 | T-PerfSweep4 §五.2 |
| 8 | 同一应用快速重复 Run：去抖生效（同 pid 不重复 attach 排队）、会话正常建立 | T-PerfSweep4 §五.3 |
| 9 | SQL Server 数据源执行 IF…ELSE 批：确认以整批出现一次 | T-TsqlBatchSplit §五（环境受限时按「环境受限未验证」登记） |
| 10 | Gitee 真实包下载→安装全链过新守卫 | T-UpdateZipBounds §五 |
| 11 | sqlv_crud_sql 产物随下次发版肉眼复核（无别名） | T-DmlAliasGuard §五 |
| 12 | 信创驱动（DM 等）setQueryTimeout 生效性 | T-ExecTimeoutGuard §五（环境受限未验证口径） |
| 13 | File Cache Conflict 复发排查走查（若再现，先搜 `[T-ExecDocSync2][conflict-probe]` 日志指纹——上批遗留项，与本批无关） | 20260917-0753 文档遗留 |

## 五、门禁与测试记录

| 面 | 结果 |
|---|------|
| 全量 testShards（批次末） | **4817 tests / 0 failures / 0 errors / 6 skipped**（412 测试类，7m26s；跳过=Docker 门控） |
| 新增测试 | SqlTypeClassifierTest +20（三盲区正反例/防回归/T-SQL 批分类）· ExecutionTimeoutGuardTest 8 · DmlAliasGuardTest 4 · SqlStatementSplitterTest +8（控制流批）· UpdateZipGuardTest 6 · AgentManagerFallbackTest +4（冷却矩阵）· LogTimeCorrelatorTest +1（签名截断配对） |
| 既有定向复跑 | Splitter/Classifier/LogToSqlPanelExecute/UpdateRunner/DynamicInstall/AgentFallback/Correlator/EP provider/AgentLogTimeParity/AgentEventAdapter/Sniff 三类全绿 |
| 签名护栏 | McpDbSignatureChecker 新增 RemoteStatement.setQueryTimeout(int)/cancel() 两条登记（243 jdbc-console.jar + 262 intellij.database.jdbcConsole.jar javap 同形实证；testShards 内跨版本核验通过） |
| 合并冲突 | 5 文件全解决（build.gradle.kts 取 master 拆分侧 + dev 四块回植；CHANGELOG/变更记录并集；SqlFormatterTest 双语义合一；架构总览融合）；dev 侧 4817 基线 = 合并前 4754 + 本批 +63 |
| 附带修复 | 负缓存 TTL 边界 `<=`→`<`（83d4744，分片负载 flake 实测复现后修复） |

## 六、已知边界与登记项

- **共享控制台链无语句级超时**（DBN 托管，ExecOption 无超时面）——v1 如实登记不覆盖；预算函数作为该链接入规格先行交付（ExecutionTimeoutGuard 类注释载明）。
- **T-SQL 批首识别限定 IF**：裸 BEGIN 起头不并入（MySQL 事务形态既有保守承诺）；形态良好的 T-SQL BEGIN 块由块感知整段保护，不损失覆盖。
- **zip 头谎报暴露形态因 JDK 实现而异**：本机 JBR17 实测「读超声明+1」命中；守卫异常/读超两路都拦（UpdateZipGuard KDoc 载明）。
- **拒绝冷却只作用于弹窗频率不改变同意门槛**：24h 后主配置启动仍会再问一次（知情同意语义不变）。
- **testDatabase 四集成类**（含 ExecDocGateIntegrationTest）维持显式绿跳（驱动管线基建债在册，见 20260917-0753 文档 §遗留）。
- dev 领先 master 已达 126 提交——**T-MergeDev2Master 合流仍是全场唯一下一步主轴**（本批全部随之回合）。
