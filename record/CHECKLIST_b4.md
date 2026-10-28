# 批4 逐张状态（2026-10-27 18:20）

## 已完成（达标）
| 图 | 时长/体积 | 说明 |
|---|---|---|
| n06_ai_context | 10.1s / 878KB | 预览+建议行+两条 AI 上下文通知，PASS |
| a34_pagination | 7.2s / 245KB | LIMIT ?,? + COUNT 包裹全可见（修同名文件后） |

## 需修（内容对但时长短）
| 图 | 时长 | 待修 |
|---|---|---|
| s2_perf_advice | 5.7s | 补真实变化（复选框点击需用 probe 坐标） |

## 待重录（坐标/流程问题）
| 图 | 问题 |
|---|---|
| a14_ognl | 坐标已 probe（CHK 412,414 / IN 685,460,509），仍短——paste 未生效待查 |
| a22_dialect | 设置页搜索框被写入方言名（焦点错位），需重标 open_settings 坐标 |

## 待录（未开始）
r11_sqllog(L2) a40_logtosql a36_export(L3) n12_display_mask(L2) a13_execute_sql(L1)
r12_execute(L1) a39_badge a27_capture_diagnostics a45_inject a11_explain(L1)
n03_explain_history(L1) n05_replay(L3) n02_dml_impact(L1) a25_xml_exec_guard(L1)
r09_xml_rollback a15_sql_snippets(L3) a23_logic_delete

## 关键教训（本轮新增）
1. **同名文件是 L0 失败头号根因**：Ctrl+Shift+N+typewrite 必开错（NativeUserMapper.xml）。
   已批量补丁 4 张为 MCP 按路径开。
2. **脚本自带 prep 会覆盖驱动的 prep**：a34/a22 都在自己 main() 里重开文件，
   导致 l0_run 的 prep 白做。
3. **probe_panel_coords.py 有效**：一次量准复选框/输入框坐标，比目测省 3+ take。
4. **残留「选择要预览的 SQL」候选框会挡住 MCP** → open_file 超时 → 开错文件（连锁）。
   已给 l0_run 加清理。
