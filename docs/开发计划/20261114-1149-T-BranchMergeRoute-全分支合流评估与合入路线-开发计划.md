# T-BranchMergeRoute：全分支合流评估与合入路线 — 开发计划

- 日期：2026-11-14 11:49
- 任务编号：T-BranchMergeRoute
- 来源：用户 2026-11-14 指示「先把远程所有分支拉到本地，再看下目前哪些分支适合往主分支合并的，列出来分支命令和主要内容，生成开发计划文件」
- 性质：合流路线提案（**只评估不实施**；按 AGENTS.md 规则 9，dev↔master、insight→dev 等合流动作一律等用户点名授权后才执行）
- 状态：**计划（未实施，生产代码零改动）**
- 侦察基线：master `1659c7091`（v2026.11.14-beta.1 发行后）；实测口径 `git fetch --all --prune` + `rev-list --count` + `git cherry`（patch-id 判等）+ `merge-base` 祖先链，2026-11-14 11:35~11:49 实测

---

## 一、全分支现状总账（fetch 后实测）

| 分支 | 领先 master | 落后 master | tip 日期 | 与 master 的 merge-base | 一句话内容 |
|---|---|---|---|---|---|
| `t-domestic-db-dialects` | **9** | 296 | 11-11 | 11-11 02:42（67a574ab 后） | 国产/信创方言扩展 15→25：10 个方言对象 + URL/角标/EXPLAIN/执行链/MCP/驱动前缀全链接线 + 真机验证 9/10 腿全绿 |
| `t-hiddenbug-perf-scan` | **57** | 296 | 11-12 | 11-11 02:42（同上） | 隐藏缺陷与性能扫描大收口：45 条处置总账（38 笔修复 + 拒判/收窄/not run 如实登记），含 testFast 守卫补齐、独立审计四项 |
| `t-security-audit-fixes` | **61** | 295 | 11-11 | 11-11 02:58（v2026.11.11 release） | 安全审计 24+1 账本闭环：SqlReverseParser 深度守卫、OGNL 异常封闭、依赖校验严格化、日志符号链接拒写等 |
| `dev` | **341** | 117 | 11-13 | 11-12 23:59 | master↔dev 双向漂移已成事实（0510/2334 调研报告定案口径）：dev 侧含 T-MagpieGems 全量（LESSONS 账本/派活清单/lastgood 防护/错误分类学）+ 11-13 两轮 master 反哺收口 |
| `t-sql-insight` | **568** | 295 | 11-11 | 11-11 02:58 | SQL Insight 语义引擎全量（结构树/关系图/洞察五子页/agent 采集/离线分析），193 笔归 11 域全部收口，11 条遗留项在册 |
| `feature/kotlin-to-java` | 1027 | 4194 | 11-14 | **2026-08-31** | Kotlin→Java 100% 全量端口支（**永不合并**，走 k2j-port-sync 手工翻译跟版；178 轮收官锚点 183c98b3d） |

patch-id 判等结论：六支 `git cherry origin/master` 的**独有未吸收提交均不为 0**（284/8/56/55/469/1026），不存在「内容已被 master 吸收、只剩删支」的空支；旧三支残留（p05/rename/merge-integration）已按 2334 调研报告 §4.2 终审为删支候选，不在本轮范围。

## 二、合入适配度评级（往 master）

| 评级 | 分支 | 理由 |
|---|---|---|
| **A（最适合先合）** | `t-domestic-db-dialects` | 体量最小（32 文件 +1619/−24）、功能自包含（纯新增方言对象与接线）、已过端到端真机验证（9/10 腿全绿）、收口文档齐（testFast 4717 例读数在档）。与 master 冲突面预计只落 CHANGELOG + 方言注册表 + 计划索引，无行为级二选一 |
| **B（次合）** | `t-hiddenbug-perf-scan` | 45 条处置账已闭环、验收文档与独立审计齐；但 125 文件 +4514/−256，且与 master 近期修复在同族文件有交叠风险（TrimSqlNode/占位符扫描等；Y7 已回退拒判）。**注意与 dialects 支共享同基与三笔同 patch 文档提交**——先合 dialects 再合本支可自然去重 |
| **B（次合）** | `t-security-audit-fixes` | 24+1 账本闭环、验收文档齐；100 文件 +8443/−533，深度守卫类修复与 master 侧无同题竞争，但构建链（mavenCentral 置前 + dependency verification 356 组件 sha256）属基础设施改动，合入后须全量构建验证 |
| **C（需先消差，不直合）** | `dev` | 双向漂移已成事实；dev 已吃进 11-12 之前大部分 master 增量（落后仅 117），341 笔领先中含 MagpieGems 制度线与代码线。按在册定案（2026-10-19），dev↔master 合流须用户显式授权；建议先小步试合侦察（merge-tree 预演）再点名 |
| **C（两段式，不直合）** | `t-sql-insight` | 真冲突面实测 17 处（2334 报告 §3.4），裁决模板现成，但合流硬前置 4 条在册（NestGate 默认值显式重拍、preserveCaptureConfirmation 清理、CI 红带入口径、merge-tree 预演+四件定向守卫）。定案路线：**master→dev 消差一轮 → insight 重演车道5 流程并入 dev → dev→master**，两段都要用户点名 |
| **不适用** | `feature/kotlin-to-java` | 全量端口支，按设计永不合并进 master（Kotlin 源仍在生产）；只走 k2j-port-sync 跟版，本轮无动作 |

## 三、推荐合入顺序与分支命令

> 顺序设计原则：小而自包含先行 → 同基支按「先合者去重」排序 → 大支走消差+两段式。每步合入前先 `master 并入该支`（对齐主干）再走该支自身收口轮（编译+定向测试+验收文档），最后才合回 master——与本仓 11-13 dev 合并轮同款流程。

### 第 1 步：t-domestic-db-dialects → master（A 级）

```bash
git checkout t-domestic-db-dialects
git merge --no-ff origin/master          # 对齐主干（CHANGELOG 按主分支叙述段 + 保留方言新增段，同 11-11 先例）
# 该支收口轮：编译 + DialectDomesticFamilyTest 8 例 + 方言黄金表定向 + testFast
git checkout master
git merge --no-ff t-domestic-db-dialects  # 需用户点名授权后执行
```

### 第 2 步：t-hiddenbug-perf-scan → master（B 级，踩在第 1 步之后）

```bash
git checkout t-hiddenbug-perf-scan
git merge --no-ff origin/master          # 先吃 dialects 合入后的新 master，共享文档提交自动去重
# 该支收口轮：全量 14 红面复测 + 探针 4 笔复核 + 合流验收文档对账
git checkout master
git merge --no-ff t-hiddenbug-perf-scan  # 需用户点名授权后执行
```

### 第 3 步：t-security-audit-fixes → master（B 级）

```bash
git checkout t-security-audit-fixes
git merge --no-ff origin/master          # 对齐主干（11-11 已并过一次发行批，冲突面小）
# 该支收口轮：dependency verification 全量构建 + BannedApiScan + 递归守卫回归
git checkout master
git merge --no-ff t-security-audit-fixes # 需用户点名授权后执行
```

### 第 4 步（独立轮，用户点名后）：dev ↔ master 消差 → insight 两段式

```bash
# 4a. master 并入 dev（dev 侧流程在册，13 冲突逐 hunk 裁决先例 11-13 刚走过）
git checkout dev && git merge --no-ff origin/master
# 4b. dev 并入 t-sql-insight（重演车道5：merge-tree 预演 + NestGate 默认值重拍 + 四件定向守卫）
git checkout t-sql-insight && git merge --no-ff dev
# 4c. insight 收口后 dev→master（须用户显式授权）
```

### 收尾（第 1~3 步全部落地后，须用户点名）

```bash
git branch -d t-domestic-db-dialects t-hiddenbug-perf-scan t-security-audit-fixes   # 本地
git push origin --delete t-domestic-db-dialects t-hiddenbug-perf-scan t-security-audit-fixes  # 远端删支
```

## 四、每步合入的共同前置（验收清单口径）

1. **合入前**：`git merge-tree`（或试合）预演真冲突清单；CHANGELOG `[Unreleased]` 冲突按「主分支叙述段 + 支侧新增段保留」统一解决。
2. **合入中**：冲突逐 hunk 裁决并留痕（同 11-13 dev 合并轮格式）。
3. **合入后**：`testFast` 守卫面 + 受影响域定向测试 + 合并态编译；CI 推送测试 workflow 已覆盖 dev/t-sql-insight，master 走发行链既有门禁。
4. **随批回填**：合并提交进 `CHANGELOG.md` [Unreleased] 一句一条带哈希；`docs/索引/开发计划.md` 登记本轮条目。
5. **not run 条款**：任何未跑的面在合流验收文档里如实登记（派活模板口径）。

## 五、决策点（等用户拍板）

| 编号 | 决策 | 建议 |
|---|---|---|
| D-1 | 第 1~3 步三支的合入是否启动、按本顺序还是调整 | 建议按 A→B→B 顺序，逐支点名、合完一支再点下一支 |
| D-2 | dev↔master 与 insight 两段式何时启动 | 建议三支清完、master 稳定一版后再启动（insight 落后 295 且有 4 条硬前置） |
| D-3 | 三支合入后是否删远端支 | 建议删（内容已全部吸收），但删支动作必须用户点名 |
| D-4 | 旧三支残留（p05/rename/merge-integration）删支与 1.3GB hprof 库容释放 | 2334 报告已终审安全，同样等用户点名 |

## 六、待验证前提

- P-1：t-hiddenbug-perf-scan 与 master 近期修复在 TrimSqlNode/占位符扫描族的交叠面——第 2 步试合时须逐 hunk 对账行为级取舍（insight §3.4 已警示同族文件双侧都动）。
- P-2：t-security-audit-fixes 的 dependency verification 356 组件 sha256 元数据与 master 当前依赖树的一致性——合入后立即全量构建验证。
- P-3：dev 的 341 笔领先中，MagpieGems 制度线（LESSONS/派活清单）与 master 现行文档可能有同位编辑——4a 试合侦察在册先例（13 冲突文件）可直接复用。

## 七、验收

- A1：本计划只产文档与侦察读数，生产代码零改动（已满足）。
- A2：六分支的 ahead/behind/merge-base/cherry 读数为 2026-11-14 11:49 实测，后续启动合流时以当时重测为准（master 仍在前进）。
- A3：任何 merge/push/删支动作在用户点名前不执行（AGENTS.md 规则 9）。
