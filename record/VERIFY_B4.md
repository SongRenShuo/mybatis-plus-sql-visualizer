# 批4 内容核验与交付状态（2026-10-27 21:00 终版）

**判据**：时长 AND 内容双达标；只看时长会假绿（本轮实测 13 张「时长够」里有 4 张内容错）。

## ✅ 已入库（6 张，逐张抽帧核验通过，commit 84afbea）

| 图 | 时长/体积 | 核验到的画面 |
|---|---|---|
| explain-plan | 10.4s/559KB | 结构化 EXPLAIN 计划表四列 + 两行 SIMPLE + 原始 SQL |
| ai-context-copy | 10.1s/878KB | 预览 + 性能建议行 + AI 上下文 v2 两条通知 |
| execute-sql | 9.1s/313KB | 执行结果网格 2 行（充电桩A-01/A-02）+ Output 页签 |
| pagination-sim | 7.6s/245KB | LIMIT ?,? + COUNT 包裹（PaginationInnerInterceptor）|
| dml-impact | 8.3s/805KB | UPDATE 预览 + 更多菜单（预估影响行数）|
| perf-advice | 7.2s/619KB | MPJ 预览 + ORDER BY 无 LIMIT 建议 + 条件勾选收缩 |

## ⚠️ 内容对但不完整（3 张，可接受但未达复核要求）

| 图 | 问题 |
|---|---|
| n08_dialect_gate | 7.3s，「方言不一致」提示已入镜（4d9fed44 新语义生效），但**执行未继续**——预览头显示「未绑定数据源」，执行链在准备阶段就停；放行代码本身正确（allowDialectGate return true），缺的是控制台准备前置 |
| r11_sqllog | 6.2s，日志列表 + 捕获通知入镜，但**预览弹窗盖住列表**；关预览的 prep 因工具窗页签点击免疫未生效 |
| n03_explain_history | 6.6s，EXPLAIN 计划表正确，**缺跨次对比 diff**（需先执行两次建立 baseline）|

## ❌ 内容错（4 张）

| 图 | 实际拍到 | 根因 |
|---|---|---|
| a22_dialect | Plugins 页搜 "MySQL" | 设置页导航用坐标，树行位置漂 + 搜索框被打入方言名 |
| a15_sql_snippets | schema.sql + 结果网格 | 前置未满足（光标没压在 car_device_info 上，快捷模板菜单没弹）|
| n12_display_mask | xml-exec-guard 确认弹窗 | 设置页 SQL Log 页的「展示与导出脱敏」在首屏之下（坑35：设置页不能合成滚动）|
| a40_logtosql | SQL 日志页（未切到日志→SQL）| 工具窗页签对合成点击免疫（连点两次也没切）|

## ⏱ 偏短（5 张，内容基本对）

s2_perf_advice 已修到 7.2s✅｜a25_xml_exec_guard 5.2s｜a39_badge 4.2s｜a14_ognl 4.0s｜a45_inject 3.6s

## 根因汇总（可复用）

1. **同名文件**（NativeUserMapper ↔ UserMapper）→ 批量改 MCP 按路径开（已落地 patch_openfile_mcp.py）
2. **动作后长静置被 assemble 裁光** → 加二次真实动作（再执行/勾回/重唤预览），实测 2s→8s
3. **设置对话框不在 robot 组件树** → 设置类图只能像素坐标，且树行会漂
4. **工具窗页签对合成点击与键盘都免疫**（20261027 实测四次）：
   - 单击/连点两次 (1042,92) → 不切换
   - 先点内容区取焦再点页签 → 不切换
   - Ctrl+PageDown ×2 → 不切换
   - Ctrl+Tab → 不切换（焦点跑到编辑器）
   → r11（关预览）与 a40（切「日志 → SQL」页签）都因此失败。
   可能原因：工具窗页签是自定义组件，不同于编辑器页签；需 `SetForegroundWindow` 到工具窗自身
   或改用 IDE MCP 的动作接口（当前 MCP 只有文件/数据源语义接口，无 UI 动作）。
5. **方言键被 IDE 退出回写清空** → 已做 prep_env.py（固定「杀→写→启」顺序）

## 下一步（需决策或专项）

- 设置类 6 张（a22/a15/n12/a27/a36/a45）：建议改用「直改配置 + 只拍效果页」，或请用户手动滚设置页到目标分组后录
- 工具窗类 2 张（r11/a40）：先把工具窗页签点击问题攻下来（可能需 SetForegroundWindow + 真实鼠标移动）
- n08：需先解决「未绑定数据源时执行链准备阶段停止」，可能是 `DataSourceSelector.prepareConsole` 的前置
