# 批4 状态（2026-10-27 18:35 实测）

## ✅ 达标（可直接入库）
| 图 | 时长/体积 | 关键画面 |
|---|---|---|
| n06_ai_context | 10.1s/878KB | 预览+建议行+AI 上下文 v2 两条通知 |
| a34_pagination | 7.2s/245KB | LIMIT ?,? + COUNT 包裹全可见 |

## ⚠️ 内容对但需微调
| 图 | 问题 | 修法 |
|---|---|---|
| s2_perf_advice | 5.7s 偏短 | 加真实变化段 |
| r11_sqllog | 预览弹窗挡住日志列表 | 关预览后再录 |

## ❌ 待重录（根因已定位）
| 图 | 根因 |
|---|---|
| a14_ognl | 两输入框 y 差 49px，点击落到邻框；paste 生效但填错框 |
| a22_dialect | 设置页搜索框被写入方言名（焦点错位） |

## ⬜ 未开始（15 张）
L0: a40_logtosql a39_badge a27_capture_diagnostics a45_inject r09_xml_rollback a23_logic_delete
L1: a11_explain a13_execute_sql a25_xml_exec_guard n03_explain_history r12_execute n02_dml_impact
L2: n12_display_mask
L3: a15_sql_snippets a36_export n05_replay

## 本轮新增工具/修复
- preflight.py（8 项环境闸门）→ 今天抓到方言配置问题
- menu_pix.py（弹层双帧定位）
- verify.py 语义断言（4 个）
- probe_panel_coords.py（像素定位面板控件）
- patch_openfile_mcp.py（批量改 MCP 开文件）
- l0_run.py（prep + 断言 + 跑 scene）
- run_elev_args.bat（多参数提权封装）

## 关键教训
1. 同名文件是 L0 失败头号根因（NativeUserMapper.xml）→ 已批量改 MCP
2. 脚本自带 prep 会覆盖驱动 prep（a34/a22 自己重开文件）
3. **预览未开就 paste = 把文字打进源文件**（UserMapper.xml 被截断 262 行！）
   → l0_run 已加 assert_panel_open 硬断言
4. 残留「选择要预览的 SQL」候选框会挡住 MCP → open_file 超时 → 开错文件（连锁）
5. assemble 静段裁剪：同态首尾会把中间长停顿全裁掉（a14 131 帧被裁 116）
