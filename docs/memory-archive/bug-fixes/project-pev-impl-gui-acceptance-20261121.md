---
name: project-pev-impl-gui-acceptance-20261121
description: PEV-01~06 已全部实施于分支 t-pev-absorb（20261121，未合 master，用户明令不主动合）+ 262
  GUI 验收 13 通过/3 部分/1 未取得 + 抓到 UI-001 双键统计条真缺陷已派修（t-pev-fix-stats 后台）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_89603c38-9aa2-446d-aabe-f19632e9bc75
---

postgres-explain-visualizer/pev2 精华吸收六件（PEV-01~06）**已全部实施**，2026-11-21，公开仓分支 `t-pev-absorb`（基于 master `216b38e01`）。用户令「从主分支新建工作树和分支，按照你的推荐实施所有内容，主控+1个子智能体后台提效」+「最后不要主动往主分支合并」。环境链与测试实例见 [[reference-pev-gui-acceptance-env-20261121]]、[[reference-pev-test-db-instances-20261121]]。

**提交链**：`370a85bf2`（主控车道 PEV-02 徽章/03 统计条/05 控制台直出/06 首屏收拢，10 文件 751 行）→ `3c02721c8`+`3385a6c31`（子代理车道 PEV-01 text 解析器 586 行纯逻辑 + PEV-04 徽章族三规则入 Advisor 单源）→ `f7356a46e` 零冲突合流 → `5e9850e33`（PEV-01 接线：工作台「粘贴执行计划」入口 + ResultView prebuiltRoots + adviseText 单源口 + 三道诚实门）→ `c55544bf6`（CHANGELOG Unreleased 五条）→ `1304d31e5`（验收册）→ `e930f2d6a`（记录表回填）。两工作树 `.worktrees/t-pev-absorb`、`t-pev-absorb-text` 留验。

**读数**：testFast 97/0（TextPlanTree 26 + Advisor 48 + JsonTree 19 + Stats 4）+ 平台类 26/0（ResultView 20 + PastePlan 6）；反向三轮全红→还原绿（合并摘除 6 红 / 解析分派 3 红 / 规则发射 8 红）。

**262 GUI 验收（验收册 `docs/验收文档/20261121-1930-PEV执行计划可视化吸收六件-功能验收文档.md`，分支上）**：主批 13 通过 → **补验后 17 通过全闭环**（`ba4b07384`）：A4 psql 框线成树（img9-8）、A6 截断负例精确提示（img9-9）、B3 MySQL 表格视图+**isSupported 收敛实证**（img9-13/16：MySQL 菜单仅普通项亮、Analyze 变体禁用）、D2 双通道第二腿实证（img9-27/28 面板按钮 R33 开关默认关走直送原生控制台正常出 QUERY PLAN；对比条属结构化弹窗特性，关闭态不出现=预期，R61 36 例定向覆盖）。亮点：A2 一帧同时实证 PEV-01/02/03；B5 DML+ANALYZE 拦截通知含副作用文案 + 库端 val=37 未变双判据；C1 双档色阶（×50 WARN 黄 / ×1200 CRITICAL 红）；B7 收拢 (+49) 与手动展开共存。

**UI-001（本批唯一真缺陷，GUI 抓到定向测试盲区）——已修复并复验闭环**：ANALYZE 档 JSON 顶层是 `{"Plan":…, "Planning Time":…}` **双键**，`ExplainJsonPlanTree.collectPlanObjects` 的 `obj.size()==1` 塌缩不下钻 → `ExplainPlanStats.analyze` 收不到节点 → **控制台直出链耗时统计条整块失效**（恰好是统计条唯一该出现的场景）；粘贴链 JSON 单键不受影响。修复 `c4fa3edcb`（非单键下钻放宽）+ CHANGELOG `c73ad6670`（t-pev-fix-stats 子代理交付：定向 37/0、反向摘除恰 4 红→还原绿）合流 `25b033730` → 第六轮重启 → **原路径复验通过（img8-11）**：pev_big ANALYZE 弹窗统计条三象限全齐（表 pev_big·5.3ms / 节点类型 Aggregate·8.4ms·Seq Scan·5.3ms / 未解释 37%），树与建议条不回归。记录表终版 `7185463df`、私有仓回写 `2714d0a`。教训=纯逻辑测试夹具必须收真机输出形态。

**实施坑两记**（合 master 时入 LESSONS）：①平台测试 newPanel 不挂 `Disposer.register(testRootDisposable, panel)` → 平台 teardown 抛 Editor not released 全类连坐红；②主类 ABI 变更（新增构造参数）后未改动的测试源增量编译不重编 → 运行时 NoSuchMethodError 全类红——测试源加一行注释强制重编即解。

**技术事实**：`Dbms.isMysql()` 小写 y（javap 2024.1 实证；isMySQL 不存在）；`ExplainActionBase$Ui` 是 static 内类、243 在位（compileJava 探针证伪通过）；Marketplace 评论端点＝`/api/plugins/{id}/comments?family=intellij`（reviews 缺 family 报 400）。**待办**：PEV-xx 合 master 时取正式 D 号；徽章阈值 10/100/1000 与警示三常量为 pev2 起步值须真机采样钉终值；HIGH 档（×250）未单独 GUI 采样。关联 [[project-pev2-explain-absorb-20261121]]、[[project-pending-plans]]。
