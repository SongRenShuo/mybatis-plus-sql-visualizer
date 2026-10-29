---
name: project-devfullui-acceptance-campaign
description: T-DevFullUI 合流后全功能沙盒验收战役速查——44 组矩阵组织法、批次结构、142 例测试收口替代 GUI、批末统一修复流程、遗留面与权威文档指针
metadata:
  node_type: memory
  type: project
  originSessionId: sess_13bd728a-c47f-4204-a267-f528b9f57f9d
---

# dev 合流后全功能沙盒验收战役（2026-10-16 ~ 10-18，已收口）

**权威文档**：dev 树 `docs/验收文档/20261016-T-DevFullUI-合流后全功能沙盒验收记录.md`（约 700 行，44 组审计表 + 30 个 UI-0xx 缺陷行 + 十批操作实录 + 门禁终局）。本档只存不可从该文档推导的速查与定例。

**授权与执行方式（用户定调）**：master 并入 dev 后以 243 沙盒（`MPSV-MasterMerge-UI243`）做全功能 UI 验收；**主控亲自操作 GUI，子代理只写代码/定向验证，绝不并发操作桌面**；验收中发现的问题**登记不即修，批末统一交独立工作树子代理修复→批末合流→重启沙盒→复验**；等待后台通知期间主控继续验收其他项（不空等）。

**批次结构（十批+批A/B/C/D+收尾批的可复用节奏）**：冻结生产 SHA → 合流定向（`--no-configuration-cache`，各类 XML 逐一核对，不把独立读数相加冒充合流绿）→ 后台 runIde 新包 → GUI 验收（frame 编号留证）→ 缺陷登记（UI-0xx 表）→ 独立树修复（定向绿+反向摘修复恰 N 红）→ 批末合流 → 重启复验原路径。**每次重启打包多个重启维度**（批 B：一次重启验 5 组持久性——屏蔽规则/命名预设/SQL 历史/参数历史/计划留存）。

**测试收口替代 GUI（本轮最大提效）**：GUI 交互债（JBPopup 合成点击/键盘焦点/面板内 EditorTextField）阻断的行为语义，改用**既有定向测试类**收口——九类 142 例全绿入账（CrudSqlGenerator 29/Suppress 16/Community 5/ParamSchemes 26/Fill 11/ExplainSession 4/Filter 33/FocusTab 14/FocusPanel 4）。判据：行为语义有测试证据即可收口，纯视觉/纯交互才留 GUI。**登记时写明「测试收口」不冒充真机通过**。

**全量门禁终局**：第 10 轮 testShards（独立沙盒 MPSV-Gate10 + 登记表隔离）合并读数 7918/0/0/10 全绿；verifyGate 243/253/262 三目标 Compatible。此前 5 轮轮番单红的 exec-doc 冲突族本轮零复发（处置定例见 [[pitfall-regression-false-failure-flakes]] 第 3 条二犯）。

**PG 数据源预写路线（解 U18/U25/U21 阻断，20261018 定例）**：手写 `.idea/dataSources/<uuid>.xml` **模型文件**被 DAS 隔离（见 [[pitfall-das-seed-model-quarantined]]），但手写 **`.idea/dataSources.xml` 注册表条目**（工程组件、IDE 自产格式，driver-ref + jdbc-url，口令留空则不依赖 secret-storage）**可行**；驱动树整拷沙盒 `config/jdbc-drivers/<Driver>/` 免下载；口令入 IDE 走 Test Connection→SCRAM 弹窗 Change Credentials，Save=Until restart（重启即失效）。口令先用 dbx MCP `SELECT version()` 验有效性再走 IDE。

**台账续登（战役收口后新增行，仍写在同一份验收文档的 UI-0xx 表内）**：UI-031~UI-035（FieldFill 模拟值/参数导入键/回滚快照只读/影响面横幅/执行计划对话框）、UI-036（回滚快照表格化）、UI-037（快照查询无数据源直弹选择器）、**UI-038（20261020 setSql 拼接实参致 SET 整段丢失，见 [[project-update-setsql-concat-gap]]）**。新增缺陷一律续号 + 在该表补一行，不另起文档。

**遗留面（勿当已验）**：UI-024（Diff 窗口 z 序）待真机人工点击定性；U05 二维码三态、窄栏 11 动作溢出未触达；部分组保持「部分」状态（见文档 44 组审计表终版）；修复仅登记未修的：无（本轮登记缺陷全部修复合流，唯 UI-024 是新观察未定性）。

**沙盒现场态（收口时点）**：IDE 在跑（旧包 10-16 18:01 之后有 20:01 新包）；SQL Visualizer 工具窗可能被收起（重开=Find Action 搜「SQL Visualizer」）；设置方言曾改 PostgreSQL 已回智能推断；mp_demo 曾 Deactivate 已重新激活；PG 口令 Until restart 失效。关联 [[pitfall-sandbox-jar-staleness-and-window-enum]]、[[pitfall-jbpopup-popup-interaction-debt]]、[[runide-demo-verify-workflow]]。
