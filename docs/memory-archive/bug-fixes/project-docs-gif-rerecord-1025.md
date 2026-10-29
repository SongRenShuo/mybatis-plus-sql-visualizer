---
name: project-docs-gif-rerecord-1025
description: 20261025 演示动图重录——12 张全部闭环推送（未部署，含 10-26 补录 mcp-agent）；含 6+6 条系统性根因与修法
metadata:
  node_type: memory
  type: project
  originSessionId: session_502360ae808bfb35c69ac231233cbe92
---

# T-DocsGifRecord1025 演示动图事故修复（2026-10-25）

触发：用户发现 `code-completion.gif` 里那个"补全弹窗"其实是中文输入法候选框，要求全站自查。

## 已完成（9 张，已入库并推送，未部署）

文档站 master `c870222`（GIF 提交 `56481a0` + 验收文档 `c870222`）。

| 图 | 旧片问题 | 修后画面 |
|---|---|---|
| code-completion | 中文 IME 候选框当补全弹窗 | 真补全列表（user / user_address / "user" / k_user） |
| annotation-preview | 汉字「送」进了 `#username` | `WHERE username = 'song' AND status = 1` |
| xml-preview | 93% 时长卡在候选框，从未渲染预览 | 完整 UPDATE 预览 + 取消条件实时收缩 |
| jpa-preview | 整片演示的是 MyBatis XML | 真 JPA：`WHERE (username = ? OR email = ?)` |
| log-community | 3.5s 结束，二维码还在加载 | 停 4.0s，两张码清晰 |
| batch-operation | 挂 `211` 跳行输入框 | 全程无弹窗 |
| jpa-write-path | 挂 `56:35` | 全程无弹窗，INSERT + DELETE 两段 |
| jpa-embedded | 挂 `21:26` | 全程无弹窗，cust_/biz_ 展平对照 |
| jpa-criteria | 右下角浏览器通知卡 | 无卡片，参数回填 `LIKE 'ad'`，7.9s |

## 6 条系统性根因

1. **IME 未切英文** → `nav.force_en_us()`（`LoadKeyboardLayoutW('00000409')` + `PostMessage(WM_INPUTLANGCHANGEREQUEST)`）。只调 `ActivateKeyboardLayout` 无效。另：`paste()` 整段粘贴**不触发补全**，逐字符输入必须用 `typewrite`。
2. **Ctrl+G 输入框残留** → `goto_col` 末尾**无条件 Esc**。像素护栏不可靠（弹窗深灰与编辑器只差 3~5 灰度级，亮块判据恒 False）；要判就用蓝色 focus 边框（`b-r>60 & b>120`）。
3. **预览渲染中间态** → 等待从 1.2~2.6s 统一提到 3.4~3.6s。症状：弹窗本体看不见、只有参数列浮在编辑器上。
4. **构图错位与白边** → 外框对齐 `(277,25,1370,984)`，录制区内缩 8px 到 `(285,26,1354,974)`；高度须偶数。
5. **凝视段被当静止裁光** → 裸屏捕获不含光标，`moveTo` 零像素变化；要撑时长必须用真实 GUI 变化（填参数让 SQL 实时改）。
6. **开错同名文件** → 新增 `record/mcpnav.py`，走 IDE 内置 MCP `open_file_in_editor`。

## 未完成 4 张（结构性阻塞）→【同日晚间已闭环 3 张，仅剩 mcp-agent】

**2026-10-25 晚间收口（本会话后半段）**：

| 图 | 状态 | 产物 |
|---|---|---|
| dialect-gate | ✅ 重录完成 | 用户定案「方言不一致提示但不拦执行」→ 插件三处修复（闸门放行 `8619a3af` / 文案报渲染方言 / runner 枚举空时重映射 `e7a27700`），真机实测 PG SQL 打到 MySQL 源返回 `[42P01]`（执行真到达）。脚本 `scene_n08_dialect_gate.py`、docs 推送 `4f32f70` |
| replay-script-export | ✅ 重录完成 | 分镜补「打开 .sql 看注释头」收尾；`scene_n05_replay.py`、docs `19aec02` |
| sql-snippets | ✅ 重录完成 | 五轮返工后闭环；`scene_a15_sql_snippets.py`（提交 `179cdc86`）、docs `bcbf131`，成片 7.5s/0.4MB/接缝 0.90 |
| mcp-agent | ✅ 2026-10-26 补录完成 | ACP 认证复原（写 `options/acpAgents.xml` = `MANAGED_EXTERNALLY`）后真实跑通 `sqlv_check_sql`（idea.log 两条 `MCP-AUDIT status=OK`）；`scene_r10_mcp_agent.py`（提交 `9d91c8b8`）、docs `94c5e90` / 验收文档 `8cacebe`，成片 15.6s/2.0MB/接缝 0.53 |

**全部 11 张已闭环**（mcp-agent 另计为补录，合计动图 10 张 + 1 张补录）。

**sql-snippets 五轮返工的系统性教训**（skill 坑 61~65 详版）：
1. `goto_col` 的 Ctrl+G 往文档塞杂散空行 → 行号错位、锚点落空行。**定位只用 Ctrl+Home+滚轮**。脏文档恢复顺序死的：关页签（自动保存脏文档！）→ git checkout → Ctrl+Alt+Y → 重开。
2. 绑定数据源让 **SQL 粘性行复活** → 右键被粘性行吃掉。Find Action「Disable for SQL」。
3. 插件快捷模板走 **exec 通道文件** `.idea/sql-visualizer/exec/mpsql-exec.sql`，其数据源绑定在 `.idea/data_source_mapping.xml`；运行中改 XML 不生效，要在 exec 文件上用会话选择器切（All 子菜单必须真实鼠标移动悬停才展开）。
4. 插件方言「手动指定 PostgreSQL」+ MySQL 数据源 → 快捷模板被**方言闸门 fail-closed** 拦截。改 `dialect` 键必须重启沙盒（设置 UI 的「数据库方言」下拉不写这个键）。
5. 子菜单像素量测**两段式**：面板底色高于编辑器底色，单段判据整面板连成一簇、中心点空。先框面板、再按面板内中位数找文字行、滤边框孤立簇。



- ~~`dialect-gate`~~ 已闭环（见上表）
- ~~`replay-script-export`~~ 已闭环（保存框「超界 79px」结论作废——那是窗口位置不同时的读数，标准构图下完全在框内）
- `mcp-agent`：需 ACP agent（Codex + DeepSeek）真实跑 `sqlv_check_sql`，链路几分钟且不确定。
- ~~`sql-snippets`~~ 已闭环（窗口临时放高 1030 用户已授权，用完已还原 277,25,1370,984）

另有 `explain-plan` 待新增：执行计划已默认走 `submitExplainDirectConsole`（预览窗内嵌原生控制台），旧分镜不符。

## MCP 接入（本轮新能力）

IDE 设置 → Tools → MCP Server → Enable（授权读文件/触发动作/执行终端）→ 端点 `127.0.0.1:64342/sse`（**端口从 64442 变成 64342**，启用后自动带 token，不再 401）。67 个工具里对录制有用的：`open_file_in_editor`（吃 `filePath` + `projectPath`）、`get_all_open_file_paths`、`execute_run_configuration`、`search_symbol`。

ZCode 侧已把 `~/.zcode/cli/config.json` 的 `mcp.servers.idea` 从 stdio 改成 `{"type":"sse","url":"http://127.0.0.1:64342/sse","enabled":true}`（备份在同目录 `config.json.bak-mcp-*`）。**需重启会话才加载**；不影响 `record/mcpnav.py` 独立使用。

## 后续：dialect-gate 深挖（同日 13:00~15:30）

用户提供 dbx 开发环境的 PostgreSQL 库（`114.66.55.245:20033` / `dbx_test`）后继续攻这张，结论从「环境缺 PG 源」推进到「**发现两个真插件缺陷**」，详见 [[project-dialect-gate-not-blocking]]。

**MCP 数据源工具实测口径**（比手工改 `dataSources.xml` 靠谱得多）：
- `create_database_connection` / `edit_database_connection` 都只吃 `url`（凭据写进 JDBC URL 的 `?user=&password=`），参数名是 `filePath` 不是 `path`、`q` 不是 `searchText`（逐个撞过）
- `needToCheckDs:true` 会因探测超时让整个 MCP 调用超时（60s+），批量建源一律传 `false` 再单独 `test_database_connection`
- **首次连 PG 必弹 `Incomplete Configuration` / `Driver files are not downloaded`**，必须点 `Download Driver Files`（装 `postgresql-42.7.13`）；这框会挂住 MCP 调用直到被处理
- 建好后 `list_database_schemas` 才能返回 `dbx_test.public`；树里显示 `0 of 3` 只是「3 个库、0 个已加载」，不代表可用

**踩过的启动坑（新增）**：transform 缓存重建会把平台 `bin/idea64.exe.vmoptions` 的 qwdn 行清掉 → 许可失效；且 **demo 应用进程（jdk1.8 + 沙盒 agent）会占着 transform 里的 `idea_rt.jar`**，导致下一次构建报「Unable to delete directory …transforms/af228…」直接失败。两处都写进 [[pitfall-sandbox-license-needs-ideakey]]。

**未闭环**：本机 `pg_demo` 在平台侧 introspection 一直没完成（`database-info` 的 product/driver-name/version 全空、`list_database_schemas` 返回 `[]`、所有 MCP 数据源接口报 `Schema dbx_test.public is not found`）。用 MCP 重建一条（`pg_demo2`）+ 下载驱动后仍空。Database 树节点自绘，坐标点击/键盘导航/robot 三路都打不中，**需用户手工双击展开一次**才能继续复测。

相关：[[pitfall-sandbox-license-needs-ideakey]]、[[project-docsrefresh1023-remaining-recordings]]、[[project-dialect-gate-not-blocking]]
