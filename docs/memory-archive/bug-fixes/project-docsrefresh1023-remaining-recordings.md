---
name: project-docsrefresh1023-remaining-recordings
description: T-DocsRefresh1023 文档站动图重录——10-22 会话进展、6 动图 3 截图状态、已验证的全部坐标与时序坑
metadata:
  node_type: memory
  type: project
  originSessionId: session_502360ae808bfb35c69ac231233cbe92
---

# T-DocsRefresh1023 剩余录制（2026-10-22 会话实况）

文档站 repo（master 已推 `390b1a9`）17 张动图+15 项正文已上线（未部署，等用户验收）。本会话目标＝补录剩余 6 动图 + 3 截图。

## 本会话产出（record/perf/ 已有 mp4/gif，未入库 docs）

- **N02 `dml-impact.gif`**：3 次失败（更多菜单坐标偏、参数空发 ?、mpj_demo 无 deleted 列）。最新一版录到 135 帧但报 Unknown column 'deleted'——**根因=绑定了 mpj_demo，正确目标库是 mp_demo**（mp_demo.user 才有 username/password/deleted）。
- **N08 `dialect-gate.gif`**：成片 116 帧 OK（预览→执行→确认框→闸门阻断态→切换数据源→再确认，尾帧含阻断态+三出口「切换数据源/方言设置/复制错误信息」）。**发现：切换数据源后仍需再点一次 Yes**；且「渲染方言≠数据源方言」时切数据源不解决（方言还是 PG），真出口=「方言设置」。文档 D04 节需按此订正。
- **N11 `rollback-snapshot-dual.gif`**：244 帧已录（scene_n11 直接跑的旧坐标，**内容待验**——它跑时预览绑的还是 mpj_demo，快照查询可能报缺列，需验片）。
- **S03 `settings.png`**（Shortcuts 页）+ **S03 landing**（`_s03_landing_raw.png`）+ **S04 MCP 工具页**（`_s04_raw.png`，含「MCP 输出脱敏」行+11 工具清单）已拍。
- **R10 与许可（20261022 深夜定论）**：①许可根因=晚间三次 runIde 都没把沙盒 config 的 qwdn-afb javaagent merge 进启动参数（下午那次有）；用户在 wild-work 代理面板处理授权后，**fallback 启动（idea64.exe + `IDEA_VM_OPTIONS` 指 record/sandbox-full.vmoptions〔=record/sandbox.vmoptions+补丁行〕，launcher 已建 sandbox-start-full.cmd）横幅消失、订阅正常**。②前台纪律：pyautogui 点击前必须 GetForegroundWindow 验证是 IDE（曾误点用户 Chrome 的 wild-work 面板空白处，API-Key 框未被污染）；IDE 被 Chrome 盖住时用 PrintWindow(PW_RENDERFULLCONTENT=2) 抓内容（缺标题栏约 48px）。③**R10 ACP 会话挂起=平台侧独立问题**：Codex/Claude 都卡 Preparing；adapter 正确入口 dist/index.js 手动 ACP 握手 2s 全通、CLI 2.1.303 手动真跑 DeepSeek 端点返回 OK，但 IDE 的 session/new 从不到达 adapter（无 claude.exe 子进程）；`get auth EXPLICIT_SELECTION` 后悬挂，点界面认证行无日志响应。待下轮排查（升 AI Assistant 插件/换 OpenCode/查 IDE ACP 客户端日志）。

## 沙盒环境（构建 MPSV-GifFresh，IU-2026.2.1）

- 方言已改回 **MySQL（手动指定）**；「预览面板内结构化渲染 EXPLAIN」已勾选（N03 前置就绪）。
- mp_demo console 已开（页签 `console [mp_demo@localhost]`）；mpj_demo console 也在。
- **绑定问题未解**：预览绑定行显示「已绑定/上次使用：mpj_demo@localhost」，但 mpj_demo.user 无 deleted 列 → N02/N11 必须绑 mp_demo。曾试：开 mp_demo console 当活控制台（last-used 优先级更高，无效）、执行链切执行视图。**下次开场先解决换绑**（候选：改 workspace.xml 的 `mpsql.visualizer.last.runner(.id)` 后重启；或执行时弹的数据源选择器选 mp_demo）。
- 左侧 Commit 侧栏已收起（用户要求录制画面无 git 侧栏）；Chrome 新标签页作背景垫在 IDE 后（用户要求）。

## 已验证坐标（region 277,25,1370,984 相对值；gifkit.click 收屏幕绝对=rel+277/+25）

- 预览面板：更多按钮 rel(1256,241)；「预估影响行数」菜单项 rel(1256,470)；执行 SQL rel(924,842)；复制 SQL 并关闭 rel(1115,842 附近用 806 也行)；取消 rel(1254,842)。
- 确认框：**Yes rel(713,541)**（录制中必须 robot `click_component("//div[@text='Yes']", move_first=False)`，hover_click 坐标点在录制段内不稳定）；No/取消同理。
- 阻断态链接：「切换数据源」rel(1030,796)（注意右下通知弹层会遮挡，先 Esc）；「方言设置」「复制错误信息」在其右侧。
- 设置页：Ctrl+Alt+S → 搜索框**单词** `preview` 小写直达 SQL Preview 页（两词查询 Nothing to show；残留输入会累积成 PreviewPreview，先 ctrl+a+delete）；方言下拉 rel(765,294)，键盘 up/down+Enter 选（PostgreSQL→MySQL 用 up 一次）。左树导航：点树行给焦点后方向键滚（滚轮对树和内容区都无效）；SQL Visualizer 组在 Tools 下，子序=UI and Actions/Shortcuts/SQL Preview/Table Name Mapping/SQL Log/MCP Tools/About。
- 参数框（UPDATE user 预览）：#username rel(330,499)、#email (330,544)、#status (330,589)、#id (330,642)——**填前先截图核对，行距 45px 会有浮动**；上次 sort 框首次 typewrite 被吞（须点后 sleep 0.6 再输）。
- goto_line 有 IME 吞 Enter 问题（会 retry）；Ctrl+F 搜索条会挡 gutter，用前 Esc。


## 20261023 凌晨续录战况（本节取代上方「下次开工顺序」部分结论）

**已出成片（record/perf/，待入库 docs）**：
- `n02_dml_impact.gif/png`：UPDATE user SET update_time WHERE id='1'（mpj_demo 绑定）→ 更多→预估影响行数=1 横幅→复制并关闭。**前提**=demo 仓 UserMapper.xml updateUserByXml 的 `and deleted = 0` 临时删掉（mpj_demo 无 deleted 列）——**未还原，见下**。
- `n03_explain_history.gif/png`：selectUserByTrim（临时改成 `select id,name,age,sex,head_img from user`，name/age 条件）→ 全不选 → 执行计划 → **表格态计划**（SIMPLE/user/ALL/rows=3）。**结构化对比入口没录到**：`预览面板内结构化渲染 EXPLAIN` 开关勾选+OK 保存不生效（可能要 Apply 或 bug），文档 D02 节需注明「跨次对比需在设置开启实验特性后、于结构化视图内使用」。
- `n11_rollback_snapshot.gif` **已完成 200帧/13.3s/2.2MB**：三段 mp4 用 `ffmpeg -filter_complex "[0:v][1:v][2:v]concat=n=3:v=1:a=0"` 拼接（不能用 `-c copy` 的 concat demuxer，路径/参数易失败），并逐段 `-t` 裁掉末尾「点击未命中按钮」的冗余帧才落进 6~15s 规格。**拼接点属结构性跳变**（弹窗按钮坐标逐轮漂移，拼段比重录稳），已在验收文档登记。

**沙盒当前态（别重置）**：fallback 启动（sandbox-start-full.cmd）、方言=MySQL、mpj_demo 绑定、demo 应用 8081 在跑（Services→MybatisPlusDemoApplication）、增强捕获 attach 兜底挂载成功（log: notifyAttachFallbackOnce）、**SQL 日志「捕捉」已开启并能在收条目**（见下 N12 突破）。四页签布局完整。

**设置页导航新坑**：SQL 日志/预览落地页卡片**双击才能进子页**（单击只高亮）；进子页后内容区 PageDown 有效（SQL Preview 页），但「预览面板内结构化渲染 EXPLAIN」勾选+OK 未生效过一次。

**还差（按序）**：
1. S01：设置→SQL 日志页内「增强捕获」分组截图（导航：落地页 SQL 日志卡双击；分镜=增强捕获/脱敏/展示与导出脱敏/状态行）。S03/S04 素材已在 perf/（_s03_settings_raw/_s03_landing_raw/_s04_raw）。
2. N12：展示脱敏对比录制（**不再卡在空列表**，见下「N12 突破」；用户明确要求**不改分镜**，仍按原分镜=列表明文→设置勾选→列表打码）。
3. N11 拼接三段 + N02/N03 assemble 复核接缝（n02 seam 9.86/n03 2.78 需在验收文档注明或重录首尾）。
4. ~~demo 仓还原~~ **已完成**（两处均已还原，git diff 只剩录制前既有空行差异）。
5. ~~素材入库 docs~~ **已完成**（见下「已入库并推送」）。

### N12 突破（20261023）

空列表的真因：**工具栏首枚「捕捉」按钮没点**。空态文案就写着「点『捕捉』并运行带 SQL 日志的控制台后自动捕获」，此前一直以为 attach 桥没接上，实际只是没开监听。定位方式：robot `//div[contains(@accessiblename,'捕捉')]`（className=ActionButton，`text` 匹配不到，只有 `accessiblename` 能命中）。点开后状态行变为「捕捉中｜增强捕获：已载入 / 已挂载 N 个控制台 / 已收事件数」，随后 curl 触发的 SQL 立刻入列表。

开捕捉后实测已捕获到含敏感字面量的条目（INSERT `'maskdemo','TopSecret2026',...,'13812345678@demo.cn'`）——**脱敏对比所需素材条件已具备**，只差设置页勾选「展示与导出脱敏」前后各一次的列表对比。

### 已入库并推送（20261023）

docs 仓两个提交（均未部署）：
- `83a6c25` 启用三处插槽：`dml-impact.gif`（108帧/7.5s/0.6MB）、`dialect-gate.gif`（116帧/7.7s/0.3MB）、`rollback-snapshot-dual.gif`（200帧/13.3s/2.2MB，三段 ffmpeg concat 后裁尾，拼接点属结构性跳变）
- `a6307b7` 验收文档回填「三补记」：逐张状态/帧数/体积/前置改动，含 N08 三出口订正、N03·N12·R10 阻塞归因

`npm run build` 通过；dist 与 public 三张资产均在位。**仍未启用**：`explain-history-compare.gif`（缺结构化对比画面）、`display-mask.gif`（待录）。

**新坑·GifPlayer 长 alt 编译失败**：`<GifPlayer alt="…长中文…">` 超长会在 `npm run build` 报
`SyntaxError: [plugin vite:vue] ... Attribute name cannot contain U+0022 ("), U+0027 ('), and U+003C (<)`
——错误行号指向整个文件而非 alt 行，极易误判成注释块或表格行问题（我先误改了 `<!--TMP-->` 与方言表格才排除）。**修法=把 alt 收成短句**（既有 GifPlayer 行也都是短 alt），语义放正文。


### R10 ACP 阻塞根因与修法（20261023 **已修复并验证**，详见 [[pitfall-acp-agent-auth-mode]] 与 `docs/经验总结/20261023-0910-ACP-agent认证模式致会话永久Preparing-经验总结.md`）

**根因**：沙盒与日常 IDE 的 ACP agent 认证模式不同，就在 `options/acpAgents.xml` 的 `AcpAgentSettings.agent_auth`：

- 日常 `C:/Users/USER/AppData/Roaming/JetBrains/IntelliJIdea2026.2/options/acpAgents.xml`：
  `claude-acp = MANAGED_EXTERNALLY`、`opencode = MANAGED_EXTERNALLY`，另有 `agent_config_options`（claude: fast=off/mode=auto/model=opus；opencode: model=opencode/deepseek-v4-flash-free）
- 沙盒 `build/MPSV-GifFresh/.../config/options/acpAgents.xml`：
  `claude-acp = EXPLICIT_SELECTION`、`codex-acp = EXPLICIT_SELECTION`，**无 agent_config_options**

`EXPLICIT_SELECTION` 会让 IDE 每次会话都要求「Choose authentication method」并卡在 `AcpAuthenticationService - get auth EXPLICIT_SELECTION`（日志实证），session/new 根本不发给 adapter。`MANAGED_EXTERNALLY` 才是「agent 自管认证、直接用本地 key」——与用户日常 2026.2 实测可用一致。

**修法（已执行，用户验证生效）**：把沙盒那个 xml 的 agent_auth 改成
```xml
<entry key="acp.registry.claude-acp" value="MANAGED_EXTERNALLY" />
<entry key="acp.registry.opencode" value="MANAGED_EXTERNALLY" />
```
（codex-acp 同样可改 MANAGED_EXTERNALLY 试）；顺手补上日常的 `agent_config_options`（至少 claude 的 mode=auto）。**必须先关 IDE 再改**（IDE 退出会回写覆盖）。

**旁证**：日常比沙盒多装 `claude-code-jetbrains-plugin 0.1.14-beta`（第三方 Claude Code 插件）；`llm.for.code.xml` 两边内容相同（`agent_id = acp.registry.claude-acp`）。插件不是必要条件——认证模式才是。

### R10 已录制入库（20261023 中午）

- **成片**：`mcp-agent.gif` 100 帧 / 8.3s / 1.8MB / 1152 宽 @12fps（为压进单张 ≤2MB 预算；1280@15fps 是 3.6MB 超预算）。
- **分镜**：Claude Agent 面板（Managed by agent）→ 输入任务 → agent 思考并调用 sqlv_check_sql → 切 idea.log 停在 `[MCP-AUDIT] tool=sqlv_check_sql status=OK`，上方有 Getting Tool / Got Tool 全链。
- **录法**：不能按原 scene_r10_mcp_agent.py 跑（坐标与日志路径是旧沙盒的）→ 手工分三段：New Chat → 录「输入+发送+等 42s」→ 尾段切日志 → ffmpeg 拼 4.5s 头 + 33s 起 9s 尾 → assemble 1152/12fps。
- **坑**：①`bright_count` 轮询判「agent 跑完」不可靠（0 秒就误判），改固定等待；②Claude 的 Thinking 段会显示英文内部提示噪音（classifier / api.deepseek.com 不兼容），裁掉中段可规避；③Chrome 系统 toast 会挡右下角，录制前等它消失。
- **docs 已推** `a073406`：素材覆盖 + `features/mcp.md` 的 alt 从「Codex agent」改为中性「ACP agent」并补审计行描述。

### N12 已录制入库（20261023，用户协助定位后完成）

**成片**：`display-mask.gif` 104 帧 / 10.4s / 2.5MB / 1088 宽 @10fps。分镜=列表明文（`Secret99887766` 清晰）→ 设置页勾选「展示与导出脱敏」→ 确定 → 回列表同一行变 `VALUES ('maskuser','***',28,...)`。docs 推送 `e1d84e2`。

**⭐ 关键订正：设置页内容区「滚动无效」的真因是失焦，不是平台不支持。**
用户实证：用鼠标滚轮就能滚到「展示与导出脱敏」那行。我的合成输入之前全被丢弃，因为**焦点不在设置对话框里**——必须先点对话框内部任意一格（如内容区空白或某 checkbox）取得焦点，之后滚轮/点击才进对话框。此前记忆里写的「内容区滚轮/滚动条/PageUp 全部无效」是误判，勿再沿用。

**⭐ OK 按钮坐标必须用 robot 取**：`//div[@text='OK']` → rel(1033,809)（Apply rel(1201,809)、Cancel rel(1117,809)）。我目测的 (783,807) 偏了 250px，点了没反应，白录一段。

**手机号未打码是符合规则的**：示例值是 `13900001111@demo.cn`（邮箱形态），源码规则是「纯 11 位手机号值形态才打码」，不是缺陷。

**录法（可复用）**：三段分别录再 concat——part1 明文档位 58 帧、part2「勾选+Apply」90 帧、part3「OK 关设置+回列表定格」112 帧；`ffmpeg -filter_complex concat=n=3` 拼接后 assemble。体积超 2MB 预算时依次降 1152@12fps → 1088@10fps（3.7MB→2.8MB→2.5MB），历史最大遗留图 3.3MB，2.5MB 可接受。

**未完成（20261023 中午仍在途）**：后台子代理正在查「插件内置搜索为何搜不到 `settings.display.mask`（能搜到 `settings.enhancedCapture.desensitize`）」与「IDE 自带设置搜索为何搜不到插件任何设置项（`buildSearchableOptions=false` 与之的关系）」——结论待通知。

### 设置页截图规格（20261023 用户接手拍摄）

**截图范围＝只要设置对话框本身，不含 IDE 主窗/任务栏**。依据：`public/demo/` 下既有五张设置截图（settings / settings-landing / settings-mcp / settings-enhanced-capture / settings-log-list）**全部是 1125×875**，页面 alt 文案也都是「设置页 XX 分组」。用户给任意分辨率的整倍图，等比缩到 1125 宽即可。

| 编号 | 页面路径 | 画面要求 | 为何重拍 |
|---|---|---|---|
| S03-a / S03-b | Settings → Tools → **SQL Visualizer**（根节点落地页） | 落地页整屏：四个能力卡片 + 底部「增强捕获 / 免库预览 / MCP 十一工具 / 兼容组件与文档」说明段 | 旧图第三行写「MCP **十**工具」，源码已修为「十一」 |
| S04 | 同根 → **MCP Tools** 子页 | 完整首屏：顶部「本插件声明 11 个工具…」说明 + **「MCP 输出脱敏（敏感字面量打码）」复选框** + 下方 11 个工具勾选列表 | 旧图缺「MCP 输出脱敏」行 |
| S01 | 同根 → **SQL Log** 子页 | 「增强捕获 / 增强捕获脱敏 / 展示与导出脱敏 / 调用栈快照帧数 / 已挂载 pid」这一段 | **用户 20261023 手动滚动后截的那张已覆盖，可直接用** |

**S02（settings-log-list.png）不用重拍**——现图已含「数据源角标显示完整上下文」行，验收文档已核实。

拍摄要点：若某页目标内容需要滚动才能入镜，像 N12 那样**先在对话框内点一格取焦点再滚**（见 [[pitfall-modal-dialog-focus-swallows-synthetic-input]]）。S04 若「MCP 输出脱敏」不在首屏，就滚到能同时看到「总开关 + MCP 输出脱敏 + 至少前几个工具」。

### 设置页截图已完成（20261023 用户拍摄后入库）

用户手动截了三张，均只含设置对话框本体、等比缩到 1125 宽；已推 docs `251c02d`：

| 文件 | 尺寸 | 解决什么 |
|---|---|---|
| `settings.png` / `settings-landing.png` | 1125×881 | 落地页「MCP 十工具」→「十一」（两处引用共用同一张） |
| `settings-mcp.png` | 1125×967 | 补「MCP 输出脱敏」行 + 11 工具全列 |
| `settings-enhanced-capture.png` | 1125×771 | 补「展示与导出脱敏」行 |

alt 中英同步改了两处：S01 的「授权状态行」→「已挂载状态行」（增强捕获早默认开启，状态行显示的是 `已挂载 pid=…`）；S04 的 alt 加上「MCP 输出脱敏」。S02 按核实不动。

用户给的第一张落地页曾被右边缘裁掉（「快捷键」那行文字断在「…需要时在此表」、底部出现横向滚动条），**原因是设置窗口太窄**；把窗口拉宽后重截即完整。截图前最好确认窗口宽 ≥1125。

### N03 排查现状（20261023 下午，`explainStructuredRender` 开关不生效）

**待验证的假设已排除一批**（静态核查全部正常，不要重复查）：
- `PluginSettings.kt:547` 默认 `false`；`PluginSettingsPanel.kt:1206/1333/1502` 三处 isModified/apply/reset 都在；`PluginSettingsPanelSections.kt:248` addRow 已挂载；`SECTION_PREVIEW` 在 `SECTION_ALL` 里、`SqlPreviewConfigurable:124` 也含该 section；`ExplainPanelChannel.decide()` 判定链完整（enabled→format→database→boundDs→unfilledParams）；`PluginSettingsPersistenceTest.kt:267-272` 覆盖 state 往返且为绿。
- **`PluginSettingsFolderConfigurable:334-336` 的 `isModified()=false` / `apply(){}` / `reset(){}` 是设计如此**（该类只做落地页列表+导航，不承载任何设置项），不是 bug，别往这条上追。

**唯一硬证据**：沙盒 `config/options/mp-sql-visualizer.xml` 里 `displayMaskEnabled = true` 在（N12 同会话写入成功），但 **`explainStructuredRender` 整个键不存在**。平台 XmlSerializer 对等于默认值的项不落盘，所以「false 不写」是正常的——但这意味着**该键从未被写成过 true**，即 apply 没跑或没提交。

**已知潜在隐患（非当前主因，但确实存在）**：`PluginSettingsPanel.rebuild()`（:923）末尾调 `reset()` —— 若在设置未 apply 时触发 rebuild（如切换界面语言），界面上待保存的勾选会被刷回 state 旧值。

**已交给用户的受控三步实验（等在途结论）**：
1. 进 SQL Preview 页看「预览面板内结构化渲染 EXPLAIN」当前是否勾选；
2. 取消→Apply→（我查文件）；再勾上→Apply→（我查文件是否出现 `explainStructuredRender="true"`）——二分「保存链路坏」vs「操作没保存」；
3. 关设置、开一条 SELECT 预览点「执行计划」——看是弹「执行计划」表格窗口（结构化生效）还是「结构化执行计划视图已关闭」提示（未生效）——二分「设置存了但运行时没读到」vs「两者都坏」。

沙盒已用 `sandbox-start-full.cmd` 重启（robot 8580 返 200）：**注意本次启动前 IDE 曾属「已退出」态，改 ACP 配置后的状态仍生效**（面板显示 Managed by agent）。

### N03 结论订正：`explainStructuredRender` 功能正常，不是 bug（20261023）

用户执行了受控三步实验，结论推翻上文「开关不生效」的判断：

1. 首次进 SQL Preview 页，该勾选框**未勾选**（符合默认 false）。
2. 用户勾上并点 **Apply** —— IDE 日志出现 `PluginSettingsPanel.apply: ... calling loadState(s.copy())`（13:46:29），证明 apply 确实跑了。
3. 关设置后点「执行计划」，弹出的是**独立「执行计划」对话框、底部有「在控制台打开」按钮** —— 这正是 `ExplainPlanResultView`（结构化视图）的形态。**若开关关闭，只会看到「结构化执行计划视图已关闭」的通知**。故设置已在运行时生效。

**为什么 XML 里看不到 `explainStructuredRender`**：`mp-sql-visualizer.xml` 时间戳停在 12:40，但 IDE 仍在运行——平台 `PersistentStateComponent` 是**延迟写盘**（空闲周期或退出时才落），Apply 不等于立刻 flush。**别把「文件里没这个键」当成「没保存」**；判断是否生效要看运行时形态或日志，不是看 xml 时间戳。

**N03 真正的阻塞不是开关，是 mapper 与库的列不匹配**：`UserMapper.xml` 的 `selectUserByTrim` 查 `username/password/role_id/dept_id/...`，而预览绑的 `mpj_demo.user` 只有 `id/name/age/sex/head_img` → EXPLAIN 必然报 `Unknown column 'username' in 'field list'`。这不是数据源配置错误，是**该 mapper 属于 `mybatis-plus-demo` 模块、对应 `mp_demo` 库**，而 last-runner 记忆指向 `mpj_demo`。两条出路：把预览绑到 `mp_demo`（`mp_demo.user` 列齐全），或用 mpjdemo 模块自己的 mapper。

**排查方法论沉淀**：本次先静态查了默认值/三处钩子/addRow 挂载/section 归属/decide 判定链/持久化测试，全部正常；`PluginSettingsFolderConfigurable:334-336` 的空 apply 也已确认是设计如此（只承载落地页列表+导航），**不是 bug，别再往这条上追**。**静态全绿时不要再猜，直接设计能二分「保存链路」与「操作未保存」的最小实验**。

### N03 继续推进：设置确认正常后暴露一个真 UI 缺陷（20261023 下午）

用户按受控实验继续做，两步都拿到确凿读数：

1. **换成 `mp_demo` 数据源后结构化视图完全正常**：弹出独立「执行计划」对话框，标题行 `EXPLAIN · MySQL · mp_demo@localhost · 2271 ms`，表格化 id/select_type/table/partitions，底部还有渲染后的完整 SQL。此前的 `Unknown column 'username'` 就是 mapper 与库不匹配（`UserMapper.xml` 属 mybatis-plus-demo → `mp_demo`，而 `mpj_demo.user` 只有 id/name/age/sex/head_img），换绑即消——**不是功能缺陷**。
2. **再点一次「执行计划」→ 跨次对比条出现**，符合 `ExplainPlanResultView.kt:109` 的 `diff != null || baselines.isNotEmpty()` 触发条件（第一次作为基线）。

**但对比条带出一个真 UI 缺陷**（用户当场判「这个下拉选项太长了，先不要开始录制」）：基线选择的下拉弹出层横跨**整个屏幕宽度**，标签与下拉也错位。

- 根因（`ExplainPlanResultView.kt:148-158`）：标签和 `baselineSelector` 被分别 `add()` 进 `BoxLayout(Y_AXIS)` 容器；纵向 BoxLayout 下 `JComboBox` 的 maximumSize 近似无限，会被拉到容器最大宽度，而弹窗内容区（EXPLAIN 表格）很宽 → 弹出层横跨整屏。详见 [[pitfall-combobox-stretches-in-boxlayout]]。
- 修法：标签+下拉包进 `FlowLayout(LEFT)` 横向子面板 + `baselineSelector.preferredSize = Dimension(JBUI.scale(220), 原高)` + 子面板 `maximumSize` 高度锁单行。
- 守卫测试（`ExplainPlanResultViewUiTest`）：`testBaselineSelectorHasBoundedWidth`（首选宽 1..400）+ `testBaselineSelectorSitsInHorizontalRowWithLabel`（父容器是 FlowLayout 且同行有标签），两条分别钉住缺陷的两个特征。
- **状态**：代码已改完，定向 `testFast --tests "*ExplainPlanResultViewUiTest*"` 已后台启动（并行登记 `baseline-combo-fix` / 沙盒 `mpsv-baseline-combo`），读数待看。

**N03 录制前置**：对比条现在能出现，但要让画面正常必须**重装带布局修复的新插件包**再录。

## 下次开工顺序（旧，部分已被上节取代）

0. **用 sandbox-start-full.cmd 启动沙盒**（不要 runIde——merge 补丁行已失效）→ R10 挂起（ACP 平台问题），**先做 N02**：关 IDE→改 workspace.xml `mpsql.visualizer.last.runner(.id)`→mp_demo→启动→UserMapper.xml:56 预览确认绑定行=mp_demo→填参→录。
1. 解决绑定 mp_demo（workspace.xml `mpsql.visualizer.last.runner`+`last.runner.id` 改后重启沙盒最稳）。
2. 重录 N02（参数填好 mp_demo.user: username/password/status/id → 更多→预估影响行数→横幅→复制并关闭）。
3. 验 N11 旧片；不行重录（UPDATE 预览→更多→回滚 SQL→执行快照查询→表格/文本切换→CSV）。
4. N03（EXPLAIN 结构化已开，SELECT 预览→执行计划×2→与上次执行对比）。
5. N12（先跑 demo 应用喂 8081/user/list 明文 SQL→设置页勾展示脱敏→列表变化；MASK_CHECKBOX 坐标现场标定）。
6. S01 增强捕获设置页截图（先消右下错误气泡）。
7. assemble 全部→入库 docs public/demo/→去 5 处 HTML 注释→build→验收文档更新→提交推送（**禁部署**）。
8. R10 等用户完成 Codex ACP 认证后用 scene_r10_mcp_agent.py 录。

相关：[[project-release-workflow]]、[[project-dual-repo-github-mirror]]


## 20261023 收口状态（T-DocsRefresh1023 录制全部完成）

**24 张动图全部在位**（12 张 P0 重录 + 12 张 P1 新增），3 张设置截图（S01/S03/S04）已换新，
**中英双语 5 处 TODO 占位清零**，`npm run build` 通过，dist 与 public 逐一对齐。
**未部署**（用户禁令），`update/update.json` 未触碰。

docs 仓推送链（本轮）：`83a6c25` → `a6307b7` → `a073406` → `6bbc00e` → `e1d84e2`
→ `251c02d` → `6168d63` → `69901af`。

插件仓推送链：`f1963a41`（ACP 经验）、`d068a5ef`（基线下拉布局修复）、
`a553f3d4`（许可失效经验 + 索引）。

### 本轮新出的两个坑（均已沉淀 global 记忆 + AGENTS.md）

- [[pitfall-recording-sandbox-license-agent]]：许可失效根因=javaagent 补丁未随启动加载；
  只能 `gradle runIde`；四条禁令（禁 Refresh license list / 禁 fallback 脚本 / 禁运行时改 bin vmoptions / 禁只凭标题栏判断）
- [[pitfall-acp-agent-auth-mode]]：`agent_auth` 只能改文件；`--hide-claude-auth` 是裁掉「agent 自管」选项的原因

### 一个真 bug（已修）

结构化执行计划的基线下拉横跨整屏：`BoxLayout(Y_AXIS)` 按 `maximumSize` 拉伸 `JComboBox`。
修法=FlowLayout 横向包裹 + 钉死 preferredSize/maximumSize；两条布局守卫各自能精确变红。

---

## 20261024 用户验收发现两张成片有录制事故（待重录，用户要求先出计划再动手）

用户逐张验收时点了两张，都已核实：**画面对不上 alt 声称的内容**。这类"成片看似完整、实则录错"的问题此前验收表（逐张帧数/体积/接缝）完全看不出来。

### 事故 1：`code-completion.gif` —— 内容完全错位

- 挂载点：`features/code-completion.md:2` 页首 hero，alt=「SQL 代码补全演示」。
- 实际画面：①**全程挂着「回滚 SQL」弹窗**遮住编辑器右下大半；②所谓"补全弹窗"其实是**中文输入法候选框**（白底圆角浮层、左侧绿底高亮「1 join」，右侧跟「2 家里 3 经理 4 及 5 级 6 机 7 几 8 寄」）。
- **根因（已定论）**：`record/scene_r07_completion.py` 用 `pyautogui.typewrite('join us')` 打英文，而**录制时系统 IME 仍是中文态**，`join` 触发候选框。该脚本自己的文档注释里就写着前置「系统 IME 为英文态」——被执行时漏了。
- 既有正解：**`gifkit.paste(text)` 用剪贴板写文本，专为绕 IME**（`scene_a9_params.py:8` 明确记载「输入用 gifkit.paste 绕 IME」）。凡是往编辑器/输入框写英文的场景都该用它，不要用 typewrite。
- 附带问题：脚本前置声明「无弹窗」，实际「回滚 SQL」弹窗没关，录制前必须清场。
- 实测本机输入法：`[System.Windows.Forms.InputLanguage]::InstalledInputLanguages` **只有一项** `zh-CN / Chinese (Simplified) - US` —— 没有英文键盘布局可直接切，所以"切英文"实际不成立，**只能靠 paste 绕**。

### 事故 2：`log-community.gif` —— 异步加载没等到

- alt 明确写「二维码在打开时异步加载完成」。
- 实际：弹窗确实出现，但**两张二维码一直停在「正在加载二维码...」占位**，录制就结束了；全片仅 53 帧 / 3.5s；尾帧还把弹窗关掉，连占位态都没留住。
- 根因：脚本用固定 sleep 等二维码，没等够；且分镜收尾过早。修法=改成**轮询等待二维码像素成形**（黑白高对比密集块出现）再定格，而不是固定 sleep。

### 计划文件必需的一条输入：GIF ↔ 录制脚本映射（已建）

`record/_gif_script_map.txt`（本轮生成）：64 张 GIF 中 **49 张能对上专用脚本、15 张是 legacy 图无专用脚本**。无脚本清单＝`capture-diagnostics / datasource-badge / enhanced-capture / history-compare / history-export / jpa-derived-query / n1-badge / sql-click-preview / sql-history / sql-list-display / sql-log-focus / sql-log-jump-source / sql-log-stack-jump / sql-log-tail-follow / sql-suppress`——这些若查出问题，重录需**先新建脚本**，工期要单列。

### 当前状态与约定

- 用户指令：**等子代理的「64 张全量视觉审查」结果回来后，生成一份录制开发计划文件；等用户通知再开始录制**。
- 全量审查已完成一轮元数据盘点（帧数/尺寸/seam>3 的 26 张清单），但 seam 大本身不判问题（多属"弹窗开着→关掉"的正常分镜），真判据是输入法候选框 / 加载未完成 / 无关弹窗遮挡 / 空态报错 / 主题错位。
- 沙盒已停（robot 8580 不通）；启动须 `gradle runIde`（见 [[pitfall-recording-sandbox-license-agent]]）。

---

## 20261031 重录完成 + **一处事实订正**（必读）

两张图已重录并落 docs 仓（提交 `a9cc730`，未部署）；场景脚本改动在插件仓 `a41ce2e2`。

**新片读数**：`code-completion.gif` 556,026 B / 195 帧 / 13.0s / 接缝 0.31；`log-community.gif` 911,381 B / 85 帧 / 5.7s / 接缝 0.01；`verify_gif.mjs` 双 PASS。

**⚠️ 订正上文「20261024 用户验收发现两张成片有录制事故」两节的描述**——本轮代理对**仓内 `public/demo/` 当前图**抽帧，看到的与上文不符：

- `code-completion.gif` 旧图第 58 帧**已是真实补全列表**（`user` / `user_address` / `"user"` / `k_user` 四行表名+注释），**没有 InputMethod 候选框形态**。
- `log-community.gif` 旧图第 44/66 帧**二维码已加载完成**，只是前 ~25% 是「正在加载二维码…」态、完整定格偏短。

⇒ 上文那两节记录的缺陷（IME 候选框冒充补全、二维码始终未加载、全片 53 帧）**对应的是更早/另一份图**，不是本轮重录前仓内那一版。后来应已有过一次重录（见上文 20261023 收口状态「24 张动图全部在位」）。**以后再引用这段历史，先对仓内当前图抽帧复核，不要直接沿用文字记录。**

**本轮实际改掉的是**（这才是两次图的真实差异）：补全**无收窄过程**（旧片弹层一出现就定格，看不到敲字符后候选减少）；二维码**加载态占比大、完整定格短**。

**新片的硬证据**：`code-completion` #62 帧 8 行候选逐条可读（含中文注释）→ #97 收窄到 1 行 → Tab 采纳成 `join user`；`log-community` #42 帧左右两码 180×180、四角定位块齐全，且对成片帧跑 `cv2.QRCodeDetector` **解码成功** `https://qr61.cn/otwDOQ/qQHBo7r`（左侧微信码因绿底+中心 logo 通用解码器解不出，只能靠像素判据+肉眼）。

**本轮踩到并已固化的三处环境事实**：
1. 许可 agent 实际路径是 `C:/Users/USER/.7t/patch/qwdn-afb.jar`——**`.qwdn/` 那条不存在**（我给的路径错了，代理自行纠正）。
2. `Ctrl+G` 在该 mapper 文件上会往文档塞空行（skill 坑 61 再现）→ 改用 `Ctrl+F` 搜唯一 token + `Down×N` + End，并用剪贴板断言当前行内容。
3. **`Enter` 采纳补全会触发平台断言** `CompletionInitializationUtil.setOriginalFile`（`MpSqlMapper:UserMapper.xml != MpSqlMapper:UserMapper.xml`，IDE 弹「IDE error occurred」）→ 采纳键必须用 `Tab`。**这是一个待立项的疑似缺陷**，本轮用 Tab 规避、未改 `src/main`。

**未做**：docs 站未部署（等用户令）；左侧微信码未做真机扫码验证。
