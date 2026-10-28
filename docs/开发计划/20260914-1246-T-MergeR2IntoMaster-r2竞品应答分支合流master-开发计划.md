# T-MergeR2IntoMaster：r2 竞品应答分支合流 master 开发计划

- 立项日期：2026-09-14
- 任务性质：分支合流清账（第三轮竞品调研 §8.1「本轮最高优先级」建议落地）
- 依据文档：`docs/调研报告/20260914-0211-竞品横向对比与功能建议-第三轮调研报告.md` §1/§8.1/§9.1（分支债首位风险）
- 目标：`feature/r2-competition-response` 63 提交合入 master，语义冲突逐项定案，N-6/N-7 文案回补，全量测试绿 + verifyPlugin 三目标 Compatible

## 一、分支账本（2026-09-14 git 实测）

| 分支 | 领先/落后 master | 内容 | 本轮动作 |
|------|-----------------|------|---------|
| feature/r2-competition-response | +63 / −63（merge-base ffe3ff7） | 上轮九条建议（N-6~N-10 / O-5~O-8）+ T-RollbackSql | **本轮合入 master** |
| feature/t-m56-00-consumer | +3 / −177 | M56 agent 字节码增强（消费端） | 不合，另立任务（性能报告 §2：合入前需独立性能评估） |
| feature/t-m56-01-agent-base | +9 / −177 | M56 agent 基座 | 同上 |
| dev | 0 / −61 | 已于 2026-09-13 全部并入 master（T-MergeDevCrudcolorIntoMaster） | 无动作 |

master 侧 merge-base 后的对面演进（合流冲突来源，按时间序）：T-DynUpdate / T-HistParam / T-ReverseParenWira / v2026.9.11 发行 / T-TestGuard·T-TestPerf / T-MergeDevCrudcolorIntoMaster（dev 119 提交）/ T-MergeFollowup 四缺陷 / T-FeedbackDlg / T-PreviewExecDs / T-JumpCapture。

工作树另有 7 个未跟踪文件（5 份调研报告 + ZCode 经验总结 + `dev-support/export_zcode_wiki_to_gitee.py`），**先行单独提交**再开合并（T-MergeFollowup 计划外发现教训：`git add -A` 曾把并行会话文档捎带进提交，已 reset 拆分过一次）。

## 二、r2 交付物与合流动作（master 现状逐项 git 实测）

| 产物 | r2 侧位置 | master 现状 | 合流动作 |
|------|----------|------------|---------|
| T-RollbackSql 回滚 SQL | `sql/rollback/` 全目录 + `ui/RollbackSqlDialog.kt` + 4 测试 | `git ls-tree` 无该目录 | 自动带入 |
| N-8 X1~X7 竞品翻车防御 | `xml/XmlMapParamSpecMatrixTest.kt` | 无此文件 | 自动带入 |
| X8 HeavyXml 增强 | `heavy/HeavyXmlParamTypeResolverTest.kt` | merge-base 后 master 未改（T-M29.5 早于分叉点 ffe3ff7） | 自动带入 |
| O-5 CI 自动 verify | `dev-support/ci-verify.ps1` | 无 | 带入；**Windows 计划任务需本机重注册**（不在 git 内） |
| O-7 弹窗克制红线 | `docs/规范/设计规范-弹窗克制红线.md` | 无 | 带入 |
| N-10 Ultra 对标报告 | r2 侧 `docs/调研报告/`（7 文件） | 无 | 带入 |
| N-6/N-7 首屏 hero + 迁移 FAQ | `marketplace-custom-page.html`（r2 版完整，实测含 JPA New 标签等文案） | `e1a9807` 单源化时回退丢失 | **按 r2 版回补**（§3.5） |
| O-6 WrapperSearchService 服务链补全 | `log/WrapperSearchService.kt` 增强 | **master 已整族删除（T-JumpCapture，2026-09-14 上午合入）** | **废弃，保持 master 删除决断**（§3.1） |
| N-9 测试资产真实断言增强 | 测试文件增强 | master 已下线「生成测试」按钮（M37.4，v2026.9.10） | 代码废弃；schema 推导思路留档（§四） |

## 三、冲突决断表（8 个双改文件，`git diff` 交集实测）

### 3.1 WrapperSearchService.kt / WrapperSearchServiceTest.kt —— 最高优先级决断

master 侧 T-JumpCapture（ffc518c）已把 `WrapperSearchService` 整族删除，依据是用户定性「按表名搜 Wrapper 定位」不可用（同表名 ≠ 同调用点，命中即误导），日志跳转改为仅依赖拦截器捕获位置 + 缺失时三按钮引导。

r2 侧 O-6 是对该服务的能力增强（IService 四无参链起点 + 简名→FQN 五类对照）。

**定案：保持 master 删除。** 合并时该文件呈 delete/modify 冲突，一律取删除侧；O-6 的增强价值已被「捕获位置」路线整体取代。O-6 计划文档（20260906-0220）随合流入库留档，状态标注「已随 T-JumpCapture 路线收敛，增强废弃」。

### 3.2 SqlPreviewPanel.kt —— 按钮行融合

master 侧四波演进：T-MergeFollowup-01 补回「执行计划」按钮（04eb8f9）+ T-PreviewExecDs 预热三级降级链 + T-AutoFold 六点位接入 + T-CrudSql showResult 入口；r2 侧为 T-RollbackSql 回滚按钮。

**定案：语义共存。** 按钮行以 master 当前序列为基（复制错误/压缩/格式化/关键字换行/竖向排列/执行计划/刷新/更多），回滚按钮按 T-RollbackSql 原设计插入（执行计划之后、刷新之前），`moreActions()` 不动；预热链以 master T-PreviewExecDs 版本为准（r2 分叉时无此链）。

### 3.3 plugin.xml —— master 为基、增量并入

master 侧含 9.14-beta.2 描述与 M37M38 系列注册；r2 侧为 N-6/N-7 兼容句改写（"including the full 2025.1–2025.3 line"）与 "MyBatis SQL log" 搜索短语。

**定案：以 master 为基，r2 三处兼容句与搜索短语逐 hunk 比对并入。**

### 3.4 bundle 双语（en/zh）

**定案：双向键并集。** 合流后跑 `BundleParityGuardTest`（T-TestGuard 交付）守卫中英 parity 与占位符；r2 新键逐条核对未被 master 侧同名键漂移。

### 3.5 marketplace-custom-page.html —— 文案回退事故修复

master 侧 `e1a9807`（9-11「双副本合并单源」）以不含 N-6/N-7 改动的 src 副本为基，hero 首屏与 Log Free 迁移 FAQ 被回退丢失。

**定案：以 master 单源结构为基，从 r2 版回补 hero 段（「别的插件给你文本」三理由）与「从 MyBatis Log Free 迁移？」FAQ 段**；回补后人工核对两段在单源内只出现一次。

### 3.6 其余单侧改动（干净带入，特征符号抽查即可）

`Dialect.kt` / `SqlGenerator.kt` / `UpdateDeleteStatement.kt` / `DatabaseSchemaResolver.kt` / `WrapperLocator.kt` / `OgnlEvaluatorTest` / `XmlPreviewModelTest` / `SqlPreviewSpecMatrixTest`：merge-base 后仅 r2 侧改动，自动合并后抽查。

## 四、N-9 vs M37.4 冲突决断

master 已在 v2026.9.10 下线「生成测试」按钮（M37.4：静态骨架不感知测试栈、断言需手工补齐，CHANGELOG Removed 有案）；r2 的 N-9 恰为该功能的断言增强。

**定案：以 master 决断为准，生成测试保持下线，N-9 增强代码废弃。** notNullColumns 推导与 B 档双模板思路留档于此，供未来「接入真实测试框架」重做方向参考（如 Testcontainers + JUnit5 真实断言生成，与 O-10 方言矩阵借 Lab 配方扩容天然衔接）。

## 五、实施顺序

1. 未跟踪文档单独提交（docs 提交与代码提交分离，绝不 `git add -A`）
2. `git merge-tree` 预演 r2 → master，核对冲突块与 §三决断表一致性（本仓惯例，T-MergeDevCrudcolorIntoMaster 先例）
3. 执行合并，按决断表逐块定案（预期冲突 8 文件内；文本冲突逐 hunk 审）
4. 特征符号终审：master 上 `git grep` 核对 X1~X8 / 弹窗红线 / ci-verify.ps1 / rollback 全链在位，且 `git ls-files` 确认 WrapperSearchService 保持缺席
5. 全量 `./gradlew test`（基线 3978/0/6 + r2 带入测试增量；build cache 已关闭，testShard1/2 可双开并行）
6. verifyPlugin 三目标（IU-243/253/262）Compatible + buildPlugin 出包——合流属「大功能」口径，按用户 2026-09-14 定案执行
7. 合流后清理：r2 本地 + 远端分支删除（对齐 T-JumpCapture「用后即删」惯例）；m56 两分支保留待独立立项
8. 变更记录总册 / MEMORY 计划索引 / 验收文档回填

## 六、验收清单

- [ ] master 树 `git grep`：XmlMapParamSpecMatrixTest（X1~X7）、HeavyXml X8、ci-verify.ps1、弹窗克制红线、RollbackSnapshotStore 全部在位
- [ ] `git ls-files` 无 WrapperSearchService.kt（删除决断未被合并复活）
- [ ] marketplace HTML 含 hero 三理由段 + Log Free 迁移 FAQ（文案回退事故闭合）
- [ ] BundleParityGuardTest 绿（中英 parity）
- [ ] 全量测试 0 失败；verifyPlugin 三目标 Compatible；buildPlugin 出包
- [ ] 变更记录总册行 + MEMORY 索引行回填；O-6/N-9 计划文档状态标注

## 七、风险与说明

1. 双向漂移 63:63 且对面演进密度高（SqlPreviewPanel 四波、plugin.xml 多波），merge-tree 预演与逐 hunk 审不可省。
2. ci-verify 的 Windows 计划任务不随 git 走，合流后需本机重注册（每周三 03:00，命令以 r2 版 ci-verify.ps1 为准）。
3. O-6 / N-9 废弃属正确沉没：前者被用户定性否定，后者被 master 产品决断取代，均已有更强路线（捕获位置跳转 / 真实测试框架方向）。
4. m56 两分支不在本轮：agent 字节码增强合入前需按性能报告 §2 做独立评估，届时另立计划。
5. 并行会话铁律：跑 Gradle 前查测试沙盒共享登记表（M26.1 台账）；占用默认沙盒时用 `-PparallelSandbox` 独立沙盒（T-PreviewExecDs 先例）。

## 八、实施记录（2026-09-15 交付回填）

**合流提交**：`3fdccd8`（分支 `t-fourplans-20260914`，独立工作树 mpsv-fourplans；本轮四计划共采一集成链——T-MergeR2IntoMaster 先行，T-McpToolset / T-PreviewUx-Pack / T-PerfTail 三分支子工作树并行开发后逐个合入）。

**冲突 9 文件**（merge-tree 预演与实际一致，计划预估 8 文件中 SqlPreviewPanel.kt 与双语 bundle 实际自动合并）：

- WrapperSearchService.kt / WrapperSearchServiceTest.kt（delete/modify）：按 §3.1 保持 master 删除，`git rm`；O-6 增强废弃随本计划留档
- Dialect.kt（计划未预料的新冲突——master 侧 T-ExplainDbl 在分叉后也改了该文件）：两侧接口尾部新增成员共存——master `renderExplainStatementIdempotent` + r2 `supportsDeleteLimit`（基类默认 false + MySQL 族三 override + SqlGenerator 消费链全在位）
- plugin.xml：按 §3.3 以 master 9.14 重写描述为基，回补 r2 三处——en/zh 兼容承诺句 + 「MyBatis SQL log」搜索短语（EN Highlights 与 ZH 核心能力日志 bullet 内嵌）；r2 侧 `idea-plugin` 无 url 属性系分叉早于 master 加点，取 master
- marketplace-custom-page.html：按 §3.5 名义执行但两处前提证伪——①hero「别的插件给你文本」三理由段 master 现行版 TAGLINE 区逐字在位，无回退可修；②「Log Free 迁移 FAQ」不在 r2 的 HTML（全树 grep 实证），实为 r2 README.md 的「## 从 MyBatis Log Free 迁移？」段。实际并入：FAQ 首条回补 r2 适配承诺段（5 工作日适配 + Windows 计划任务每周 verifyPlugin 三目标兜底），r2 旧 14 条 FAQ 形态不回潮（master 7 条精简定稿保持）
- README.md：更新检查 bullet 取 master 热替换新版（T-DynUpdate 晚于分叉），回补 r2 独有 Marketplace 评分/一次性迁移指引 bullet；r2 四理由与兼容句块已被 master 现行版覆盖
- CHANGELOG.md：取 master [2026.9.14] 块；r2 对 CHANGELOG 仅一行改动且系把 [Unreleased] 误改名 [2026.9.10]（63 提交交付物无条目）——弃该行，新增 [Unreleased] 块收录合流交付物（回滚 SQL 按钮 + 兼容承诺/Log Free 迁移文案回补）
- docs/变更记录.md / docs/MEMORY.md：表格并集——r2 侧 T-RollbackSql 与 T-MergeDevR2Round3 两行按日期插回 09-12 组首位，master 09-14 全链行保持

**语义终审**：SqlPreviewPanel.kt 按钮行自动合并落点与 §3.2 不符（回滚按钮在刷新之后），已手正为执行计划之后、刷新之前；预热三级降级链原样在位；特征符号八项核对全过（X1~X7 矩阵测试 45 处命中 / X8 / ci-verify.ps1 / 弹窗红线 / rollback 全链 / WrapperSearchService 仅存于历史计划文档、源码零残留 / rollback.* 31/31 parity / N-10 Ultra 报告随 20 个新文件带入——实际落 docs/开发计划/ 非本计划所述调研报告/，内容无损）。

**验证**：全量 `./gradlew test` **4167/0/0/6 绿**（6m07s，独立工作树；1 例 CopyFeedbackUiTest 剪贴板竞争 flake 单类重跑绿，回归假失败清单在案）。verifyPlugin 三目标 + buildPlugin 留集成链终验（本轮四计划合并状态统一跑一次）。

**附加交付**：ci-verify Windows 计划任务 `MPSqlVisualizer-WeeklyVerify` 本机重注册确认（-Force，Ready，每周三 03:00，下次 2026-09-16，任务体指向 dev 工作树路径与脚本注释一致）。O-6/N-9 状态标注随本节与 CHANGELOG [Unreleased] 落档。

## 八、实施记录（交付后回填）