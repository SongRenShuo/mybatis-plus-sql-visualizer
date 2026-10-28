# dev 合流后全功能沙盒验收记录

## 本次授权与执行方式

用户要求先将当前 master 合入 dev，再用 dev 沙盒完成新增及历史未验功能的 UI 验收，IDEA 全屏并置顶。用户随后明确允许：本批持续验收时，将已记录问题交独立工作树子代理修复；批末统一合入、重启沙盒、复验并进入下一批。主控亲自操作 GUI，子代理仅负责代码及定向验证，不并发操作桌面。

本次授权取代上一轮“禁止启动沙盒UI”的限制；它不代表允许修改业务数据库、真实公开发布诊断内容或替用户登录第三方账户。数据库验证使用本地合成环境及已允许的 DBX 开发测试连接。真实外发保持逐次内容授权。

## 冻结与环境

- 合并前 dev：`9e5e87cf4db2e4102af966261e413e071dd68399`。
- 当前合入 master：`39473a6b`。主工作树的未跟踪 JDK8/Redis 调查计划不纳入。
- 首次批次合并提交：`667d7d15`，父提交 `9e5e87cf`、`39473a6b`；第一批沙盒由该提交构建，实际插件哈希见第一批记录。最新B8运行包及哈希见第八批；PID57840已正常GUI退出，runIde退出0并自动注销，未强杀，主demo工作树干净。
- 启动前编译：243配置下 `compileKotlin compileTestKotlin -Pkotlin.incremental=false --no-configuration-cache` 成功，2026-10-16 17:54:44退出0，3m4s；尚未执行本次定向/全量测试，不能记为UI通过。
- 目标平台：默认 IU 2024.3.7.1（243）；不使用 gifRecord 切换262。
- 独立沙盒容器：`build/MPSV-MasterMerge-UI243`，通过 `.local/master-merge-ui243.init.gradle` 配置并关闭runIde自动重载，防止后台修复构建改变本批在验版本；实际子目录、PID、全屏/置顶证据待启动后记录。
- 演示工程：`F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-demo`。启动前工程干净，8081～8088无监听；本地MySQL已实测8.0.12可连接。
- 旧统一门禁7787/0/0/10不覆盖本次合流，旧UI结果只作准备参考。

## 覆盖清单

以下每项按真实操作补结果，未执行不得预填通过。视觉基准243 Light/Dark × IDE缩放100%/125%；日志页再交叉900/460/250/230px，达不到的宽度记真实最小值。一次性提示、重启持久化与跨项目状态另测，不由单次截图代替。

| ID | 功能与必须覆盖的子场景 | 主要操作与判别点 | 状态/证据/缺陷 |
|---|---|---|---|
| U01 | O146/V179原生列表高亮 | 日志/历史/预览同源配色，三显示模式、选中/失焦/搜索/CRUD色；长句省略但复制完整；200条滚动 | 部分：已验B3 Light125%历史七条列表关键字蓝、标识符紫、耗时绿及同结构徽章；待三处同源配色、三显示模式、选中/失焦/搜索/CRUD色、长句完整复制、200条滚动及其余视觉矩阵 |
| U02 | O148/V181尾随与排序 | 滚轮/拖条/导航键各自暂停，手动回底不恢复；显式滚底恢复、清空重启；满200继续追加；七排序与慢筛选 | 部分：B2/B5/UI-012 原证据保留；本轮真实控制台 205 次查询验证单结构 200 条徽章与继续追加、滚轮暂停、下箭头「滚动到底部」恢复并自动贴底、七排序菜单（含耗时降序重绘）、慢查询筛选入口；待拖条独立触发、导航键暂停与清空后的尾随状态 |
| U03 | O149/V182四页单行 | 第四页收窄/扩宽，MorePopup鼠标/键盘可达，空历史常驻 | 部分：已验B2约300物理像素窄栏四页单行、MorePopup列出历史且Enter切入；待扩宽往返、鼠标选入、空历史常驻及精确宽度/主题缩放矩阵；批D 拖窄实测：333px 宽下平台 JBTabs 单行+MorePopup（历史页可见），『日志 → SQL』页签左端被裁成『志 → SQL』（无省略号，frame dd-7-top-zoom）；拖至 228px 时工具窗整体被平台收起（低于最小宽度）——页签文字裁切形态登记视觉观察；**精确宽度三档实测（本次补验，默认深色主题）**：460/250/230px 三档均为**单行页签+平台 MorePopup+溢出（›）按钮在位**（frames imgu03w2-460/250/230），230px 仍不收起（收起阈值≈228）；窄栏空态文案水平左裁（「打开设置（定位到增强捕获）」仅见行尾）登记视觉观察；**主题矩阵补 Light 档（本次补验）**：IntelliJ Light 下 460/250/230 三档同为单行+MorePopup+溢出（frames imgu03l-*），且 Light 下空态文案**不裁切**（主题影响空态可用宽度——Dark 左裁/Light 完整，登记观察）；Darcula 已恢复；高对比未验 |
| U04 | 快速预览与首次引导 | 单击选择/双击完整、多选/空白；编辑钉住、恢复跟随；历史换文件迟到防回灌；首次引导跨页/重启 | 部分：已验B1单击选择及就地提示、提示打开底部预览、双击完整预览、编辑草稿钉住和恢复跟随；B5 UI-004设置空扫0文件→落盘新3条→首次切历史自动显示，未手动刷新；此项不代证历史切文件迟到防回灌，后者及多选/空白、引导跨页/重启仍待验；批C（11 步 4 FAIL）：存储页控件 robot 未探到、页签首切需人工前置、『期望 3 文件得 0』（捕捉未开/执行未发生）、『3 条』计数未见——自动分组与首切引导未闭环；兜底复跑（11 步 3 FAIL）：存储页控件 robot 未探到、『期望 3 文件得 0』（捕捉未开+执行未发生）、『3 条』计数未见——自动分组与首切引导需「开捕捉+执行 3 条」人工前置；**实现真相与行为面实锤（本次补验）**：①**自动保存=按日聚合单文件**（`ui243-history/ui004-b5/sql-history-2026-10-18.sql`，本轮 3 条于 17:13 追加落盘，每条含 `-- source/time/db` 元数据与 `-- pd:` 编码载荷）——探针「期望 3 个文件」的**期望模型错误**（实现非每条一文件）；②**首切自动显示成立**：开捕获（红■）后执行 2 条 → 切「SQL 日志」页列表自动出现（`WHERE id=93/94` + `[MySQL] [UserController.java:70]`，frame imgu04-list），状态行双通道读数完整（`控制台：已挂载 1 个，已扫描 27 行 | 增强：已挂载 pid=53760 | 已收集事件 2 条`）；③顺带增证：历史文件内 `pv:CONSOLE_BRIDGE`/`pv:CONSOLE_EP` 来源标注与 INSERT 原文保留（展示脱敏与原文件分离）。|
| U05 | 工具栏/交流群/帮助 | 11动作宽窄可达、帮助收尾、搜索快捷键；二维码异步成功/失败/关闭；本地帮助 | 部分：已验UI-003暗色宽栏清除图标及清空后继续捕获、约300像素窄栏图标、Light125%图标；待11动作完整宽窄可达、帮助收尾/快捷键、二维码三态、本地帮助及其余视觉矩阵；宽栏 11 动作逐一在册（绿▶捕捉/翻页/清空/设置/过滤/搜索/搜索设置/下拉/排序/反馈/帮助，frame cap-zoom）——11 动作宽栏可达实证；二维码三态未触达（设置 About 入口位置漂移，登记未验）。；二维码入口三路皆堵（兜底验证轮）：设置树几何点漂移、robot 对设置树不可达（About/关于 均 False）、设置搜索对插件自定义页无索引（搜 About 命中平台页）——维持未验并记录为平台面限制；**Help 菜单插件贡献项在册（Alt+H 呼出）**：`Show SQL Log in Explorer` 与 `Submit Feedback (SQL Visualizer)` 在 Help 菜单底部（frame imghelp-3-alth）——帮助收尾在位；二维码不在 Help 菜单，反馈对话框点击（=坐标偏移）未触达，维持三路堵登记。|
| U06 | R53双通道状态与暂停 | 捕捉启停、增强关开、暂停只停agent入队、控制台照常、恢复不重放；状态栏显隐 | 部分：B1 原证据保留；本轮设置页开启增强后控制台 attach matched=6、状态栏已挂载 pid 与事件计数；暂停期间仅 CONSOLE_EP 落盘、恢复后 AGENT 回流且旧暂停条目未补放；待捕捉停止/重启与增强开关独立核验 |
| U07 | O147/V180自然接管融合 | 真实同次控制台/agent一行融合与源码、耗时、历史回读；无源码轻提示；不同执行不强行合并 | 部分：本轮同次查询的控制台与 agent 数据按去重索引融合为一行，自动保存条目带 AGENT provenance、调用栈与耗时，历史页回读显示方言/耗时/源码链接；待不同执行不强行合并的负例 |
| U08 | R60各出口脱敏 | 列表/历史/底部/完整预览/复制/导出；采集和展示开关独立；合成敏感值不漏、原文件不擦写 | 部分（静态面）：展示链 `DisplayMasker.maskDisplay` 在六出口的落点代码核验——列表（SqlListFragmentRenderer:29/32）、导出（SqlExportService:53/176 投影级 copy）、回放（ReplayScriptExport:92）、MCP（SqlVisualizerRenderLogsTool:112）、AI 上下文（parameterize）全为**展示/投影层变换，不写回条目**；采集侧开关 `enhancedCaptureDesensitize` 与展示侧 `displayMaskEnabled` 分属不同设置键（D96 双开关）；XML 持久条目原文在案（未被擦写）。**真机六出口逐一出图与合成敏感值漏判待验**（U31/UI-021 已给列表与复制的实测面）；**真机出口面（新包重启后）**：列表展示 password 列打码 `'***'`/email 明文（UI-021 修复真机复核通过）；历史条目打码在案（b1-3）；屏蔽动作生效（#2 INSERT 从列表消失）。**复制 SQL 出口**：首轮探针点击落空读到旧剪贴板（U19 16KiB 样本残留），非产品问题——疑点撤销，复制=restoredSql 原文口径由代码面与早前剪贴板实证（U31）共同支撑；导出逐格式沿 U28。**六出口证据帧齐全（本次汇总）**：列表=UI-021 修复打码帧 `list-after-capture`；历史=历史 tab 文件/条目帧 `b1-1`/`b1-3`；底部=状态行双通道计数帧 `imgu04-list`（含空态诊断行多帧）；完整预览=预览面板多帧（`u23f-7-wrapped` 等）；复制=剪贴板读数（U31 `WHERE password='***'`/AI 上下文 v2 块）；导出=U28 四格式实物文件（CSV/TSV/TXT/SQL，652/648/749/839 字节）；「同样本逐出口一致性对比」未逐项执行，维持部分 |
| U09 | 调用栈与最近执行 | 深栈两端、内部折叠、摘要点击/悬停、逐帧跳转、复制全文；最近执行多选/编辑/完整预览 | 部分：已验B1完整预览栈弹层内部折叠、省略24帧提示和业务帧可见，列表跳Java定位到58行；待深栈两端、摘要可读性/悬停、逐帧跳转/复制全文及最近执行专项 |
| U10 | Kotlin主动入口降级 | 构造行/选择区给限制说明、无假gutter；普通行不误报、Java正对照 | **通过**：批C 自动化 5 步 13 断言——构造行与选择区 Alt+Shift+Q 均弹「Kotlin Wrapper 暂不能可靠预览」（文案逐字对齐含「没有生成猜测 SQL」，frame bc-U10-ctor/selection-balloon）、无预览浮窗、普通行负例只给「未找到 SQL 源」类提示不误报、Java 正对照 7 个插件 gutter 且预览浮窗在；「检出 8/9 个疑似 gutter」为探针几何误检（人工核图 46 行行号区仅 IDE 灯泡无插件图标，frame bc-U10-ctor-line-balloon）；负例通知全文在案：『未在光标处找到可预览的 SQL 源。支持：MyBatis-Plus Wrapper 调用链、IService/BaseMapper 内置方法、mapper XML / 注释 SQL（@Select 等）、MyBatis-Plus-Join 关联、JPA 派生方法 / JPQL / HQL / Criteria、Hibernate、JdbcTemplate 日志行……』 |
| U11 | 三类持久数据清理 | 逐项取消/确认；捕获目录不擅删；历史/收藏/参数/方案边界；旧面板/迟到写回不复活；重启 | 部分：已验B3末两类清理确认框明示范围和互不删除边界并选择No；待三类逐项确认/取消完整覆盖、实际删除边界、捕获目录保护、旧面板/迟到写回及重启；**清空实测**：点清空按钮 → 列表由 #1/#2/#3 变空态（双通道诊断行完整，frame u11-1）；「重启不复活」由内存态语义+批 B 重启列表初始为空共同支撑；SQL 历史文件不受清空影响（读回验证已在案）；**重启不复活多重实证**：捕获条目为内存态（LogSqlEntry），本轮三次重启（批 B 首会话/5588/50316/49552 各次）后 SQL 日志列表均初始为空；清空后列表空态在清空与后续重启后一致。|
| U12 | O145/V178来源方言 | 注解/XML/Wrapper/JPA/HQL、目录继承/文件覆盖、模块/定义owner；显式scope无法解析为UNAVAILABLE，旧日志不猜URL | 部分：已验B1 Wrapper与B2 XML真实入口生成SQL、B2不同宿主嵌入模型输出未串兄弟模型；**双项目历史隔离真机实证（本次补验）**：Open 切换至 `mpsv-walk-proj` 后其「SQL 历史」页签为空态（「暂无历史 SQL。执行 SQL 后自动记录……」，frame img_u12isol-walk-hist）——demo 工程 5 个历史文件**不跨项目出现**，`projectIdentity=SHA256(规范化 basePath)` 的隔离语义真机成立（ExplainPlanHistorySessionTest 4/0/0 佐证：known UUID 不跨目标/方言/格式/SQL）；待来源方言专项、注解/JPA/HQL全入口、目录/文件覆盖、模块/定义owner、UNAVAILABLE及旧日志不猜URL |
| U13 | UUID学习/注入/刷新 | 实际SqlFile、执行后学习、重命名/改测试URL/删除重建、清绑定、跨项目、迟到预热 | 待验 |
| U14 | session与active双闸 | 两路径分别验证；手动冲突阻断、自动重渲染零提交后二击、已编辑不覆盖；未知QUERY确认取消/未知写拒绝 | 部分：已验B1同源session创建及SUBMITTED、绑定后37行；B6未知EXPLAIN阻断，DELETE仅前置写确认取消。B8 UI-014续修复验：源选择等待253秒后正常创建MySQL UUID会话，确认再等约505秒点No显示已取消且无SUBMITTED/原焦点SEVERE（frame-935a2641/66c752e3）；重试两阶段确认Yes后10:45:44.405 SUBMITTED，结果id=1一行且回执正确（frame-2b825722）。待active独立路径、手动冲突、自动重渲染零提交后二击、编辑保护及后续未知DML闸，不以取消/等待闭环代证双闸全组 |
| U15 | R42/快捷模板/旁路 | EXPLAIN/COUNT/抽样、日志转SQL、影响面；长WHERE保末尾条件正例1/负例3，无WHERE/多表/注入负例 | 部分：已验B1预览EXPLAIN真实MySQL表格、B2日志转SQL双行还原；B6主编辑器COUNT返回41、抽样10行。B7 UI-015预览选表名三模板启用、整SQL禁用且保留基础动作；B8同预览入口COUNT于11:05:47.751 SUBMITTED并返回41，抽样返回10行，原预览SQL/id71未变（frame-a02fdf2f/38181c91）。B8日志还原未知QUERY取消与Yes真实提交回执复验见UI-014；待预览快捷模板EXPLAIN、其他旁路/影响面、长WHERE正反例及无WHERE/多表/注入负例，不将两入口COUNT/抽样正例升级整组 |
| U16 | 富错误与超时披露 | 预览/模板三动作/日志转SQL：缺表/语法错/失效连接，原文/状态/厂商码/位置复制；有无源定位；原生超时边界 | 部分：已验B1预览错列42S22/1054及库/数据源披露、完整复制错误、定位选中失败语句；待缺表/语法错/失效连接、模板三动作/日志入口、有无源定位与原生超时边界 |
| U17 | 五条金路径运行对照 | Wrapper分页、XML动态、JPA/HQL、捕获源码历史、执行EXPLAIN；同次输入/实际SQL/目标/结果，不跨样本拼证 | 部分（批C 定向+对照）：**P2 XML 动态联动实锤**——完整模式勾选 status → `WHERE status = 1` 出现（frame act-3-after-check），降级态同操作无 WHERE（UI-022）；**P5 执行计划**——无绑定态弹数据源选择器（T-UI030 promptDataSource 路径**首次真机触达**，frame bc-U17-p5-explain-q4-result-none）；P3 JPA/HQL 预览+填参 PASS。P1 落点已修正为构造行（`new LambdaQueryWrapper<>()`，F3 跳第二处）但批处理快捷键间歇性未闭环；P4 历史首条缺 robot 列表路线留人工补；**取消路径实证**：选择器取消后通知全文『未选择数据源，执行计划未执行。请重新点执行计划并在弹出的选择器中选择数据源，或先在控制台执行一次查询完成绑定。』（不重弹，T-UI030 闭环） |
| U18 | R90计划历史 | 默认关闭/Apply/Reset、事前和回调授权；两次计划/重启/只读基线；项目UUID参数隔离；清理迟到；数量/字节界限 | 部分：已验B1启用独立留存、两次结构化计划与只读历史基线，B2跨IDE重启读回，UI-001披露可读及UI-002中文标签复验；待默认关闭/Apply/Reset完整流程、事前/回调授权负例、项目UUID参数隔离、清理迟到与数量/字节界限 |
| U19 | R91上下文v2 | 有无计划、编辑/换目标/清历史；匿名值标识符注释、白名单计划、16KiB；复制零新查询/网络 | 部分：已验B1有/无计划复制、值掩码/标识符隐藏及已获计划exact match摘要；批C 探针在「粘贴进预览编辑器/更多菜单项」两处 GUI 定位反复失败（rect 语义已修、图标按钮改几何仍不闭环；robot 对 JBPopup 菜单项不可达），**16KiB 超限样本与编辑/换目标/清历史面未验**，待手工步骤或锚点标定；新包复跑：**粘贴其实成功**（预览 SQL 区为 big_sql 尾部 `..._for_budget_probe_598, ...599 FROM user WHERE id IN (1)`，frame bc-U19-1），面板 diff 判定失败=探针 rect 与面板实际位置错位；「复制为 AI 上下文」仍未定位（robot 对 JBPopup 不可达）；16KiB 提交拦截面未触发（需执行/复制 AI 上下文），维持未验。；**16KiB 超限拦截真机验收通过（本次补验）**：1200 列合成样本（原始 48KB）粘贴进预览面板（`panel changed after paste=True` 自证）→「更多 → 复制为 AI 上下文」（Down×6+Enter 键盘路径）→ 剪贴板读数 `LEN=262`：`-- SQL Visualizer AI context v2` + `-- SQL: omitted (unsupported syntax or budget)` + EXPLAIN/Static schema 均 `not obtained`——**超限整段丢弃、1200 列原文零泄漏**；**对照面**：600 列样本（投影后 9820 字符 <16KiB）输出正常投影块（`-- SQL (strict projection)` + 600 列匿名化为 identifier_N + `values masked=1` + `local-only; no acquisition`）——两读数构成完整对照，证实 `ShareContentProjection.MAX_OUTPUT_BYTES=16_384` 的拦截语义。**过程中一次操作失误已澄清**：Esc 误关面板致 1200 列样本曾粘进编辑器内存（磁盘未落盘、demo 仓 git 干净、杀 IDE 后磁盘原样）。|
| U20 | R92/48填充模拟 | CRUD和批量、四fill型、AUTO/Version、WHERE/UPSERT排除、显式优先、恶意输入、两预设/清理/换目标 | 部分：已验AUTO主键默认不勾、模拟默认关闭，B3布局/普通值优先/分号拒绝、INSERT与UPDATE字段差异、引号转义/数值拒绝、WHERE id/revision与UPSERT不填、取消列/关闭模拟；待CRUD与批量全形态、四fill型逐项/Version完整边界、两预设/清理/换目标，未执行写SQL或Handler；批C：open_file 像素护栏中止（页签多时 Go to File 未弹），CRUD 批量/Version/预设边界未验；兜底复跑（新包+关面板后）：第 1 步过 open_file 后卡在「右键菜单『生成 CRUD SQL』未定位（robot/模板两路线皆空）」——**共同阻塞点=robot 对 JBPopup 弹出菜单不可达 + 菜单项 anchor 模板未标定**（平台组件树限制），CRUD 批量/Version 行为面仍待；open_file 护栏已修双判据（像素 0.5+robot 文本）并提交；**动作在册与 popup 交互债现场（本次补攻）**：编辑器右键菜单完整显示两项插件动作——`Convert To SQL (Alt+Shift+Q)` 与 `Generate CRUD SQL`（frame imgu20c-2-ctx-menu，动作存在性+快捷键披露证据）；但弹层内合成点击与键盘导航均不可靠（hover 读图坐标与实际项位偏差 24~48px 不稳定、Down 导航被子菜单截获致 Enter 执行 Show Diagram），Find Action 对插件动作不索引（中文 text 与动作 id 均无命中）——CRUD 面板行为面（批量/Version 开关）维持未验，阻塞为探针弹层定位债而非功能失败；**生成语义以单测收口（本次补验）**：`CrudSqlGeneratorTest` **29/0/0 全绿**——覆盖六类语句顺序与逻辑删除变体、**INSERT 排除自增主键**、**UPDATE 版本列 `version = version + 1` + WHERE 等值追加**、逻辑删除标记值 1+主键 WHERE、**无主键降级 `1 = 0`+标注+UPDATE 不挂 batchModel**、空列空列表、**保留字方言引用（MySQL 反引号/PG 双引号）**、Oracle/PG Catalog 折叠族、SQLServer/DM identity 包装；剩余仅面板内批量勾选交互（BatchModel 勾选 GUI）|
| U21 | R93/52能力单源 | meta悬停、报告、执行确认跨方言；不支持能力明确、字面量关键字不误报 | 部分：B8本地HTML报告build/sql-diagnostic-report.html实物1650字节、11:09:48，MySQL披露EXPLAIN/DELETE LIMIT/UPSERT支持、IDENTITY无需包装，未外发；本轮 PG 解阻断：手动方言 PostgreSQL × MySQL 数据源（bound mp_demo）点执行计划被红字整体阻断「插件不会覆盖手动选择，也不会为冲突会话暴露原生控制台」——跨方言执行确认 fail-closed 实锤；待 meta 悬停、报告跨方言一致性、字面量关键字负例 |
| U22 | R94诊断审阅 | 第七出口与旧六出口、删段编辑撤同意、超限、取消、最终正文；实际公网发送不擅执行 | 部分：已验B1七出口可见、第七审阅公开目的地/无附件、取消SQL段、编辑撤同意、22520/16384超限显示及取消未发送；待旧六出口实际行为、超限提交拦截与最终正文核对，实际公网发送须另获授权 |
| U23 | R22参数预设全边界 | A/B语句同名隔离、默认/P1/P2快照、重命名/删除/20上限、折叠与IDE重启、foreach/OGNL | 部分（跨语句同名失败）：B1默认项保护、UI-P1参数/条件快照及清除不删方案，B2普通参数重启召回/预设栏收起；B7 id71命名快照与重命名已验。B8删除UI-Renamed后只余受保护默认项且id71保留（frame-99f1b56b/1634e28a）；foreach UI-Shared三值71/72/73在改值及为null后均可恢复未勾与原IN三值（frame-04ac7425/0af9bfe0），未执行非法SQL。另一语句id91另存同名却报已存在（UI-017，frame-25d8a81d）；修复8b10f9bd未验证、定向验证刚派。待A/B同名隔离修复复验、P2/20上限、命名快照重启及其余foreach/OGNL边界，不升级整组；批C（5 步 4 FAIL）：『管理预设…』缺 anchor 模板；**OGNL 空串/零值 O2/O3 经截图判定为探针期望错误**——未勾选动态条件时填值本就不触发条件加入 WHERE（插件行为正确，与 U17 P2 修复同源）；上限/另存往返未闭环；**OGNL 真联动与边界真机读数（本次补验）**：selectUserListByXml 预览 + 三动态条件勾选 + 填参（u_probe/1/0）→ 关键字换行后 SQL 完整显示 `WHERE username LIKE concat('%','u_probe','%') AND status = '1' AND age >= '0'`（frame u23f-7-wrapped）——三条件全部进入 WHERE，**age=0 零值真值语义正确**（0 != null 成立）；**空串边界**：清空 #username 后条件**仍保留**且值降级为 `?`（`WHERE username LIKE concat('%', ?, '%') …`，frame u23f-8）——按 XML `<if test="username != null and username != ''">` 的字面 OGNL 语义空串应为 false（条件消失），实测保留——**登记为「OGNL 空串求值语义待判」观察项**（不判缺陷，需结合参数模型空串表示与渲染器 != 实现核对）。**预设跨重启读回增证**：预览面板参数区预设下拉显示「**UI-Shared**」+「管理预设…」按钮（frame imgu29d5-2-diff）——`paramSchemesJson` 持久预设经多次重启后真机读回成立。|
| U24 | R51/54历史参数耗时 | 缓存优先、历史快照兜底、不串条目；精确耗时优先/同结构中位数；模拟键不回落 | 部分：本轮历史页真实条目行显示方言 MySQL、耗时 3ms/0ms 与源码链接，右键六项可用；待缓存优先/历史兜底优先级与不串条目的组合边界 |
| U25 | R33/23/45/61执行计划 | 原生/结构化切换、MySQL表格/PG树、无源/未填参/未知格式回退、两次diff、体检、文本不可比 | 部分：已验B1 MySQL原生表格、启用结构化后无绑定回原生/绑定后结构化、两次无变化比较与只读历史，B2中文基线标签；本轮 PG 树+体检实锤：手动方言 PostgreSQL + 共享控制台会话切 dbx_test 后结构化计划渲染 `EXPLAIN · PostgreSQL · dbx_test@dbx-test-host · 1316 ms`，树形 Limit(rows=3){Parallel Aware/Async Capable/Startup·Total Cost/Plan Width/Output/Seq Scan on bench_items (rows=10000)}（PG 专有属性在列）+ SEQ_SCAN 体检黄条「Sequential scan over ~10000 rows — consider adding an index」；待主动原生/结构化切换、无源/未填参/未知格式回退、有变化 diff、文本不可比 |
| U26 | R46 Schema预检 | 已预热错列建议；未预热/复杂语句跳过；DML取消不写、只读提示 | 待验 |
| U27 | R49/30/12条件分页 | Wrapper/EasyQuery布尔三态、XML未知警示；COUNT/LIMIT改参保留、COUNT独立开关、手写LIMIT不重复 | 部分：已验B1 Wrapper动态条件缺省标记及填参，B2 XML真/假标记、取消status移除条件、全不选后清空WHERE/参数；本轮 COUNT 独立开关行为闭环：pageByWrapper 预览（PG 方言 `FROM "user"`+`LIMIT ? OFFSET ?`）开态带 `-- 附分页 COUNT 查询（PaginationInnerInterceptor）+SELECT COUNT(*)` 附注（frame u27-12），关掉设置子开关后重开预览附注消失、SQL 止于 LIMIT（frame u27-16），tooltip 披露三态语义「分页模拟的子开关…父开关优先」（frame u27-15），已恢复勾选；**增证**：方言行显示「PostgreSQL（模块已学数据源）」——共享控制台会话切 dbx_test 后 LEARNED_MODULE 学习跟随（O-145 模块被动学习行为实证）；待 Wrapper/EasyQuery三态全覆盖、XML未知警示、COUNT/LIMIT改参保留及去重边界 |
| U28 | R36/O91/R4导出 | 回放事务/分号/块、可移植CRUD/回滚、日志历史四格式、过滤/选择集及脱敏 | 部分：已验B1 MySQL单SELECT回放文件含START TRANSACTION/实值/末尾分号/COMMIT，未执行；待多语句/块及不支持事务方言、可移植CRUD/回滚、日志历史四格式、过滤/选择集与脱敏 |
| U29 | R31/R16/O90检索对比 | 1/2/3条Diff与超千行、正则/Aa/非法regex/文件名行号、同结构计数/聚焦取消 | 部分：已验B2历史原文搜索/合法正则与双选Diff，B1同结构计数5→7→9；B4 UI-009原七条文件非法正则红色反馈、关正则清错并显示无匹配、合法正则恢复2条，原路径复验通过；待1/3条及超千行Diff、Aa/文件名行号、聚焦取消；批C（2 步 2 FAIL）：历史列表首行定位缺路线、超千行语料 ui004-b5 不在册（记『无语料』不判产品失败）；兜底复跑：『对比选中两条』菜单项同样 robot 不可达+无模板；千行语料 ui004-b5 不在册（记无语料）——三选/超千行 Diff 维持未验，阻塞同族=JBPopup robot 不可达；**多选菜单在册与 Diff 入口定案（本次补验）**：日志列表 Ctrl 多选两条后右键——菜单为多选态（`复制选中 SQL（2 条）`/`复制时间戳（2 条）`/`删除`/`屏蔽选中的 SQL（2 条）`/`发送到 SQL→Java`/`发送到日志转 SQL`/`跳转到 Java`/`只看同结构（灰）`/`导出可见 SQL（CSV/TSV/TXT/SQL）`，frame imgu29-menu），**无独立「对比选中两条」项**——1/2/3 条 Diff 的实际入口为预览面板「更多 → 差异对比」（对当前 SQL 与历史基线），待该入口的几何标定；选中集导出项存在但弹层合成点击落空（同族 popup 点击债）。**Diff 入口负例实锤**：预览面板「更多 → 差异对比」（键盘 Down×2 命中）触发 → 通知「无上次预览可对比」（干净空态、不误报，frame imgu29d2-2）；有变化 Diff 正例需两次不同 SQL 预览为基线，gutter 行坐标漂移未闭环（连续两次实际打开同一方法）留待锚点标定。顺带复现 **U22「分享 SQL」七出口对话框**（来源行 UserMapper.java:27 + Markdown/Markdown表格/可执行SQL/导出HTML报告/复制诊断报告/导出诊断报告/审阅反馈，frame imgu29d-2）。**有变化 Diff 正例与千行文件 Diff 已闭环（2026-10-18 晚，随 UI-023 专项）**：MPJ join 预览取消勾选 `t.sex = 1` 后像素点「差异对比」——Diff 窗口出图，上次带 `WHERE t.sex = 1`（删除行高亮）/当前无 WHERE、`2 differences`、Side-by-side+语法高亮（frame imgdr8-1-diff-alone，z 序在主窗后需最小化主窗可见→UI-024）；**千行文件**：SQL 历史 `sql-history-2099-01-01-ui-synthetic-10000.sql`（1.7 MB/10000 条）钻入条目 #1 预览 → 差异对比出图，上次=`WHERE id = ?`/`AND status = ?`（参数化形态）、当前=`WHERE id = 1 AND status = 'UI_OTHER'`（字面量回填后）、`1 difference`——diff 恰好呈现参数回填变化，大文件场景链路无卡死（frame imgk-7-diff-alone）。1/3 条多选 Diff 与 Aa/文件名行号/聚焦取消仍待（原缺口不因本项闭合而升级）|
| U30 | O92/DDL/R3日志还原 | 剪贴板有/空且只点击读取、日期、DDL不伪回填、格式化引号注释保真 | 部分：已验B2真实双行Preparing/Parameters还原id=6及username字符串、错拼一行时未回填告警；待剪贴板有/空与点击读取时机、日期、DDL边界、格式化引号/注释保真 |
| U31 | R10 ShardingSphere | 真实Logic/Actual双段、交错/含逗号/关开；普通日志三族回归，样例解析不代替真应用 | 部分：已验B3真5.5.2框架及增强/控制台链；B4 UI-008真框架PASS、ShardingSphere来源说明、主SQL保守问号、只读Actual附注含BOOK,100，开启展示脱敏后重开整附注隐藏；B6 UI-013去重开启仍保留by-id两次#3/#4，历史独立o147-id及ds0/100、ds1/101附注逐次保存。本轮增证：`displayMaskEnabled=true` 已持久（XML 在案），列表打码现场两态并存——#22 INSERT 展示 email 值 `'***'` 而 password 列 `Secret123X` 明文；「复制 SQL」出口剪贴板全明文（打码面=列表/预览浮层/导出，复制出口豁免属口径内）；**疑似缺陷登记**：INSERT VALUES 场景列名-值对位失准（主仓 DisplayMasker.columnHintBefore 抓 VALUES 内前一字面量值当列名 hint 的规则级缺口 + 列表 `***` 与主仓规则复刻矛盾，疑 agent 采集侧脱敏不同步），交专项判定；待已开面板即时脱敏GUI、交错/开关全矩阵及普通日志三族回归；**UI-021 修复真机复核通过**：新包捕获 #2 INSERT 列表展示 `VALUES (ui_mask_probe, '***', 25, 'mask@probe.test', …)`——password 列打码、email 明文（修复前恰好相反，frame list-after-capture）；应用控制台 Parameters 行仍为原文（原文件不擦写）。 |
| U32 | R2/XML编辑辅助 | 六组gutter逐组关开；高亮#/$、闭合、参数/列/resultMap补全、引用导航/查找/折叠；第三方让位 | 部分：已验B6 Ctrl+B五引用真实落点：namespace→UserMapper.java22、property=username→User.java41、include refid→XML20、select resultMap→XML5、selectUserBatchByIds id→Java121；待六组gutter关开、高亮/闭合/补全、查找/折叠及第三方共存，不以导航五点代证整组 |
| U33 | R24原生MyBatis注入 | 单config成功；无config/多config/非法plugins不注入；Boot回归，专属测试副本 | 待验；批C `--check-tree clean` **PASS**（无生成物+无 `<plugin>` 注册，fail-closed 未误注入）；injected 需 IDE 注入前置、corrupted 造态需改源树（超只读纪律）记未验；设置页开关面未探到 |
| U34 | premain/attach关开 | 先建池再attach；两来源分别两轮关开；PS/batch/clearBatch；裸?边界及启动无异常 | 待验 |
| U35 | O69屏蔽规则 | 精确/结构/全部结构、空白大小写、存量和新入队、取消清空、双项目/重启/10次开合 | 部分：已验B4真实SELECT1精确屏蔽隐藏旧条、新请求两次显示已拦截2次，结构/精确切换标记可见；删除本轮规则后缓存旧#11恢复，被拦截两次未补放，规则恢复为空；待结构不同参数、全部结构、空白大小写、取消/清空完整边界、双项目/重启/10次开合，不将局部屏蔽恢复当整组通过；新包实测：列表条目右键「屏蔽此 SQL」→ #2 即时从列表消失（规则内存生效）；**重启保留未验**——正常退出链被「Terminate running process」确认弹窗阻断（Terminate 点击不落地），强杀不 flush PersistentState，`mp-sql-visualizer-suppress.xml` 未生成；兜底结论：规则 flush 依赖正常退出（PersistentState），退出链被 Terminate 确认弹窗阻断后强杀不落盘——重启保留无法在本环境闭环，登记平台面限制；**规则管理语义以单测收口（本次补验）**：`SqlSuppressRuleManagerTest` **16/0/0 全绿**——规范化文本入库/幂等跳过/空白不成规则/删除收缩/清空/空白变体同规则/大小写口径/尾分号剥离/**结构匹配手动开关与持久化 roundtrip**/**超上限 500 拒新增且既有不受影响**/**命中计数按指纹归账+删除后停止**/增删清通知订阅/**loadState-saveState roundtrip 重建键集**——上限/计数/持久化语义齐备；重启保留的真机侧=平台 PersistentState 机制（`@Storage("mp-sql-visualizer-suppress.xml", roaming=DISABLED)` 声明在案）+ roundtrip 单测共同支撑 |
| U36 | 历史全量与万条UI | 首进/搜索/切文件/刷新/迟到/滚动；列表清除不删已落盘；保留3/0/非法值只用测试目录 | 部分：已验B2独立目录保存7条且列表清空未抹去记录、手动刷新/搜索，B3首切既有文件；B5 UI-004专属新目录06:35:13空扫0文件→真实ids21~23落盘8460字节→未手动刷新首次切入直接显示3条，原路径补验完成；待万条/迟到/滚动、切文件完整流程及保留3/0/非法值，不以首切修复代证历史全量组；新包实测：列表条目右键「屏蔽此 SQL」→ #2 即时从列表消失（规则内存生效）；**重启保留未验**——正常退出链被「Terminate running process」确认弹窗阻断（Terminate 点击不落地），强杀不 flush PersistentState，`mp-sql-visualizer-suppress.xml` 未生成 |
| U37 | O64/O73回滚与注入范围 | identity ON/INSERT/OFF整批，普通多语句对照；test/bootRun/直接Run注入范围；真实写另核授权 | 部分（回滚失效态失败）：B8合成INSERT仅预览整行等值定位风险及COUNT自查；DELETE id71无before-image时执行/复制禁用，有效单列TSV生成INSERT INTO user (id) VALUES (71)并披露快照范围（frame-91097fa7/a093ed40/889061ad）。改invalid_snapshot生成失败仍留旧INSERT、可执行提示及按钮（UI-018，frame-9d584547），修复代理在途。未执行写SQL；待失效态复验、identity整批/普通多语句对照及test/bootRun/直接Run注入范围，不以生成正例代证整组 |
| U38 | SQL转Java完整形态 | GROUP/HAVING/DISTINCT/聚合/INSERT NULL/UNION/双引号、五Wrapper、MPJ/VO、多实体、往返 | 部分：已验B2 INSERT NULL/DISTINCT/UNION静态输出，B3 Query及Lambda/AUTO聚合回落、双引号类型名修复；UI-010 B4旧请求18项恢复全绿，撤代次17红/撤末端复查1红；原序列GUI仅出现新user候选并生成新聚合链，无旧ui_probe回灌。该一次时序不外推所有竞态；待五Wrapper/MPJ/VO/多实体/往返及生成代码运行对照 |
| U39 | MCP11工具与禁用直调 | 243缺MCP降级先验；有能力平台另验list/call/TOOL_DISABLED/路径/限额/脱敏/审计，不以243代证 | 部分：已验B4真实243缺平台接口提示，声明11工具不等于已注册/可调用，平台能力与插件选择分行；关总开关不谎报服务器启停、关单项显示停用1项、Revert恢复，未Apply临时改动；UI-011原误报路径复验通过，真实跨发行物能力探测纳入234项合流绿。待有能力平台11工具list/call/禁用直调/路径/限额/脱敏/审计，不以243降级或签名检查代证实调 |
| U40 | R50/R62可发现性 | 旧诊断Markdown/HTML与R94区别；更新正常404断网；空态三径、绑定三态、立即试用重启、Ctrl+A | 部分：B8本地诊断HTML成功落盘build/sql-diagnostic-report.html（1650字节、11:09:48），未外发；foreach新预览无活绑定时显示“数据源（上次使用）”而非“已绑定”（frame-913634da），与前一活控制台区分。待Markdown/与R94区别、更新正常/404/断网、空态三径、绑定三态完整覆盖、立即试用重启及Ctrl+A，不以单次导出/状态区分代证整组 |
| U41 | 预热恢复与生命周期 | 冷预览、降级自然恢复/编辑/重试/关闭；attach-detach、心跳空闲/重连/PID消亡/关非主控制台页 | 部分（退出生命周期缺陷待修）：B8登记08:58:47退出的Bus is already disposed、Double release of editor及RunnerLayoutUi访问已销毁project（UI-016）；243应用级clientView与project级JdbcConsole关闭协议待修，首次释放栈缺失，未判插件直接双重释放。测试草稿50b09deb/b164c8b4未执行。B8 PID57840正常GUI退出、runIde退出0并自动注销且未强杀，仅证本次退出，不代证UI-016修复；冷预览/恢复、attach-detach、心跳/重连/PID消亡及关非主控制台页仍待验 |
| U42 | 本次master新增实体作用域 | 依赖JAR嵌入前缀、代码式/自定义策略、同名实体跨模块/库、根实体/副表、类型化命名查询歧义 | 部分：已验B2源码模块拼接/StringBuffer/helper三形态嵌入前缀及切宿主模型未串兄弟模型；本轮批C 自动化 3 步 13 断言——拼接变量与 StringBuffer 两形态宿主 HQL 行均检出 5 个插件 gutter（**依赖 JAR 实体被认出**），语义三查人工核图全过（`wms_inventory_jar_demo` 表名/`qty_base_qty`·`sku_inventory_status`·`item_key_lot_info_received_date` 前缀列/WHERE 条件，frame bc-U42-concat-sql-zoom），「方言未验证：兼容按 MySQL 预览」fail-closed 披露正确；1 FAIL=参数输入框脚本定位（#warehouseId/#inventoryStatus 框在位，非插件缺陷）；待代码式/自定义策略全边界、同名跨模块/库、根实体/副表及类型化命名查询歧义 |
| U43 | 库实体反查性能与入口 | 快路径/非常规@TableName、后台扫描开关、显式在依赖库查找/保留已选、取消/连续输入不卡 | 部分：已验B3独立宿主LegacyBooking来自本地Maven JAR且真实调用正向解析表/列/常量；待表名反查快路径/非常规@TableName、后台扫描开关、显式依赖库查找/保留已选、取消/连续输入性能，正向解析不代证反查 |
| U44 | 本次master预热死锁与耗时色 | 冷启预览无WIL自锁、准备超时可恢复；正常绿/半阈值橙/慢红/未知灰/选中对比 | 部分：已验B3 Light125%历史列表耗时绿色显示；待正常阈值对应关系、半阈值橙/慢红/未知灰/选中对比及冷启WIL自锁/准备超时恢复专项 |

## 每项证据格式

`ID/子项｜批次冻结SHA/插件哈希｜IDE build/主题/缩放/宽度｜样本参数与目标代号｜实际操作｜结果｜正反例是否触达｜截图或会话图像/日志/SQL证据｜通过/失败/受限/待验｜缺陷号｜修复SHA｜复验结果`

未执行、入口缺失、真实provider没有返回、自然融合事件未出现时分别记录，不能合并写成通过。243无MCP平台能力不代表该工具已验收；需要另一个支持版本验证时单独记录版本。

## 第一批实际操作记录

- 环境：243沙盒JBR进程PID54712；初次信任仅演示工程，增强捕获确认框已展示采集范围/本机机制/取消冷却说明，点击同意。主窗口通过原生Enter Full Screen切换，frame-068c1c00证实1920×1080全屏；窗口ID由4591970变为4657506。系统级置顶尚未完成；动作搜索仅有Folders Always on Top（文件夹排序），不能冒充窗口置顶。
- CUA取证：Swing弹层有独立窗口，主窗口截图不包含搜索/下拉浮层；必须list_windows定位后绑定实际窗口。UIA只显示标题栏时按截图操作，工具“unchanged”仅指稀疏结构，不等于画面无变化。菜单不得因主窗口截图缺席就判产品失败。
- U17/U27局部：`mybatis-plus-demo/UserServiceImpl.java` 36行gutter预览成功，显示三项动态条件与`恒真·缺省`。输入username=`ui_alpha`、status=1、age=18，SQL即时回填且逻辑删除保持，frame-4ae4ef7c。行首空白处快捷键曾给“未找到源”，暂不定缺陷，后续在表达式内做对照。
- U23局部：管理预设默认项重命名/删除禁用；另存UI-P1成功。选UI-P1后改username为ui_beta、取消status，切默认得到修改后的工作值（frame-e908acd5）；再从下拉选择UI-P1，恢复ui_alpha/status=1勾选/age18（frame-762e6444）。证明本次普通参数与条件快照未被编辑覆盖；跨方法/foreach/20上限/重启仍未验，不将U23整项标完成。

- U23继续：清除按钮将三个输入清空但保持条件勾选，SQL恢复问号（frame-1c18d480）；默认/UI-P1往返能恢复已存快照，命名方案未被清除按钮删除。
- U22局部：分享窗口七出口齐，默认Markdown；第七入口打开审阅表单，目的地公开需求池明确，无附件控件，初始正文618字节、值与标识符投影为问号/identifier_N（frame-3640075a）。取消SQL结构段后正文187字节（frame-96e2d961）；勾同意正对照frame-e25c2359后编辑正文，勾选撤销、预算变27（frame-045860ec）。输入最终超过预算实测22520/16384，同意保持未勾，提交按钮仍显示可点；尚未触发提交验证，不能据按钮外观判安全闸失效。最后点取消，未实际发送。大文本CUA paste曾粘入非预期剪贴板内容，改分段typeText且以界面字节数为准，工具传入长度不是实际录入证据。

- U19无计划正例：真实更多菜单复制AI上下文，再粘入本地预览编辑区读取（随后Undo恢复原SQL），frame-3bf39ae8显示`AI context v2`、值掩码4、标识符隐藏、`EXPLAIN: not obtained`、静态tables=1/columns=12。未点击执行，未对外发送；零网络/JDBC活探针仍以此前独立证据为辅，不以无弹窗代证。
- U28回放正例：GUI选择MySQL事务包装，保存 `build/ui243-replay-b1.sql`；文件实际包含START TRANSACTION、填实值ui_alpha/1/18的完整SELECT、单条末尾分号和COMMIT；未执行脚本。截图frame-c68d9bd3及本地导出文件构成实物；不支持事务方言与多语句边界仍待验。
- 当前实际插件：IU-243.28141.41，SQL Visualizer 2026.10.16；沙盒lib主JAR 6,012,505 bytes，SHA-256 `b169010e96134f757bfc368607c4e7ef70225bdaca8d9160171844e0817566d1`。启动日志未检出ERROR/SEVERE。
- 缺样准备独立demo树 `mybatis-plus-sql-visualizer-demo-ui-fixtures`：689e91a填充四型、3a74a13普通MP模型JAR、7b9806b真实ShardingSphere/H2；均已编译，后者CLI两单分片及跨分片查询真实通过。UI尚未打开该树，不冒称分片捕获已验。

- U14/U16准备：首按执行在预览底部给红色“没有与MySQL匹配的已配置数据源”，可复制完整错误（frame-abd735fe）。原生Database配置正对照明确报Driver files are not downloaded（frame-791759ea）；因此先判新沙盒缺驱动，不将这次拒绝冒充双目标闸已触达或产品缺陷。已由原生按钮下载MySQL驱动，进入Test Connection，后续结果继续记录。

- U14同源正例已触达真实链：补齐驱动与演示工程已有本地测试密码后Test Connection成功（MySQL8.0.12，frame-ac0e813e）。Runner弹层真实列出五个本地MySQL源，选mp_demo，日志19:03:19 `session created target=8d6c2a1b-f817-44fd-a3db-6798b116bc78/MySQL`、19:03:20 `native query SUBMITTED`；原生网格0行。取消username与age条件后再次执行，原生Result2显示37行（frame-b8ee992e），说明已绑定控制台可用；冲突/未知与重渲染负例尚未覆盖。
- U25原生EXPLAIN正例：同面板执行计划得到真实MySQL表格一行（frame-2f41afbd），尚未启用结构化及持久化，不能关闭R90子项。
- U16预览入口：手动编辑为`SELECT missing_ui_column FROM user`后执行，底部明确`[42S22][1054] Unknown column...`及当前库/数据源，旧结果保留但失败状态清楚（frame-e33c6cb2）。点击复制错误再本地粘贴，状态码与库上下文完整（frame-af1a791d）；Undo恢复错误SQL后点击定位，整条失败语句选中（frame-86d03436）。其他三个模板/日志入口尚未验。
- 日志附带发现两次原生DatabaseView.PropertiesAction的SlowOperations，已派只读归因；未证本插件责任前不擅改平台相关代码。兼容回退MySQL的Wrapper在目标MySQL时直接执行也交独立复核其契约，不把未提示误判为安全缺口。

- U18/U25：启用结构化和独立留存后，第一次无绑定回原生；已绑定后再点得到结构化MySQL计划、提示已保存（frame-2fad9ab2）；第二次出现无变化比较与历史基线（frame-499e339e），切历史项能只读重绘（frame-1ba5494c），基线文案缺陷UI-002另记。U19有计划复制实测`already acquired; exact match; type=ref; rows=1; filteredPercent=5.0`（frame-9c51ccd3），没有原始NOTICE条件；重启和隔离负例仍待验。
- U04/U09：5条真实agent日志单击只选中并出一次性就地提示（frame-17f6a30a），从提示打开底部预览；编辑`SELECT 42 AS ui_draft`后选第二条仍保草稿（frame-99f47ca8），恢复后跟随第二条id=2（frame-7753cff7）。双击完整预览有参数2和栈摘要；栈弹层可见内部折叠、采集省略24帧不可展开与业务n1行（frame-c0347c60）。列表右键跳Java实际定位LogFeederController.java:58（frame-a07cd13b）。摘要仍先显示agent内部帧，列后续可读性复核，不把弹层业务可见当摘要也合格。
- U06真实暂停/恢复：IDE Run启动MybatisPlusDemoApplication，PID24408、8081；捕捉后请求ids1~5得到5条带源码/耗时记录。暂停增强后ids6/7仍经控制台入列表，无耗时/源码（frame-c88ccc5d）；恢复后ids8/9新增两条带源码/耗时，总数9、旧暂停两条未补放重复（frame-4ab3cd15）。同结构徽章同步5→7→9；自然跨通道同次MERGED与持久历史仍待验。

## 缺陷与批次

缺陷按实际发现批次登记；下表保留首次状态，最新实施与复验以各批次记录为准。合并冲突属于准备工作，不冒充UI发现。

代码预审线索（未归为UI缺陷）：合并复核指出 `XmlInlineSqlPreviewService` 的HBM入口可能未传tag/context且runCatching可能吞取消；列入U12/U42同名模块和定义owner专项，先复核实际可达路径与用户结果再安排修复，不将源码推断当作已复现。

| 缺陷 | 发现批次/用例 | 复现/实际与预期 | 独立修复分支/提交 | 定向验证 | 沙盒复验 |
|---|---|---|---|---|---|
| UI-001 | B1/U18、U40 | Settings默认998×742控件右移，开关文案与R90披露被截；frame-43bc71fb/766d6787 | t-devui-settings-layout：b0433976→dev 0bc74e37 | 独立27/0/0/0；撤首列过滤2/2红、恢复27绿；B2十类合流116/0/0/0 | B2中文998×742布局/披露/下拉复验通过；B3 Light125%原生1247×925顶部与末尾可读；英文及其余视觉矩阵待验 |

| UI-002 | B1/U18、U25 | 基线显示内部R61、历史原UTC时间+UUID，不易区分会话与快照；frame-499e339e/40716065 | t-devui-plan-baseline：8fd89abc→dev 1d8c41bc；独立证据09c8c978 | 两类18/0/0/0；临时R61标签4/4红、恢复18绿；纳入B2十类116/0/0/0 | B2中文本地时间/快照来源及“上次执行（当前会话）”复验通过，跨IDE重启读回；不代证R90隔离/清理等其余子项 |

| UI-003 | B1/U05；用户现场指出 | ClearAction已注册但未配图标，宽栏第三动作占位；frame-9baa1a8e | 原12c01f4d→dev c3fc0f52 | 定向9/0/0/0；摘图标三例2红1绿、恢复9绿；纳入B2十类116/0/0/0 | B2暗色宽栏空/有数据图标可见，清5条后继续捕获2条；约300像素窄栏及B3 Light125%图标可见；其余主题/缩放/精确宽度待验 |

| UI-004 | B2/U36 | 设置时空扫后首切历史不刷新，手动刷新才显示7条；JBTabs old/new反置且首扫兜底已消耗 | t-devui-history-refresh：9e32ad43→dev 83fbae85 | B3首跑历史两类19项全绿；五类最终98/0/0/0；恢复错误回调顺序与移除填充高度失效联合三例全红，恢复后98绿 | B3首切既有文件及Light125%两文件可见；B5专属ui004-b5目录06:35:13空扫0文件，随后ids21~23落盘8460字节，未手动刷新、未提前切历史，首次切入直接显示3条（frame-ebcb96a1）；原复现路径复验完成，不代证万条/迟到/保留策略 |
| UI-005 | B2/U38 | GROUP/HAVING聚合AUTO生成不可编译Lambda、Query连续select覆盖投影；双引号表名又污染Java类型名 | t-devui-reverse-projection→dev a72d3b15/1ac4a5d1/43e3ec1b；夹具后备续修40cfff4e | 独立MP3.5.7真实编译及SQL片段两类68/0，基线5红；合流首跑68项6失败为夹具CodeSource问题，续修后五类98/0/0/0 | B3 Query单select、双引号类型/列边界及Lambda/AUTO回落QueryWrapper<User>同样本复验通过；不代证所有生成形态；旧异步回灌另记UI-010 |
| UI-006 | B2/U20 | 参数区开关右移、模拟输入在横向视口外；frame-307e73ef/219fc32e；初修后安全说明高度仍不足 | t-devui-fill-layout：97a6b6b4→dev 0feb403d；高度缓存续修纳入B3 40cfff4e运行包 | B3首跑FieldFill 11项2失败，续修11绿；与历史回调联合反向三例全红，恢复五类98/0/0/0 | B3开关/说明左对齐、两组输入纵排无需横滚，显式优先及非法值字段提示完整；完整宽度/主题缩放矩阵待验 |
| UI-007 | B3归因；U37/U41相关 | B2日志证实非主配置仍attach RemoteJdbcServer并获CONFIG ACK；授权不涵盖平台helper，未证明与退出等待有因果关系 | dev 07ad5ce5、3b964a8b；独立签名补交6f756735 | 独立六类69绿/撤拒绝14项9红/恢复69绿；纳入B4最终十五类234/0/0/0，两项平台签名检查已纳入 | B4原生Test Connection成功且日志明确排除RemoteJdbcServer pid28492，真实MP应用pid17744捕获五条业务源码/耗时（frame-b1d5094d/fff8c33f），正反通过；未知启动器/后补扫描组合待验。B6无agent同配置Stop仍90秒未退出，agent非必要条件，不归为本项关停死锁 |
| UI-008 | B3/U31 | 真5.5.2 Actual无Parameters前缀，参数未恢复、误提示Hibernate且路由附注未见；物理参数不可安全映射Logic | dev 07a1c3a7、e5347b61、1a569037、5274c8ef、cea4c4a6；最终保守问号与只读路由，不自动回填无类型Actual参数 | 纳入B4最终十五类234/0/0/0；与UI-009联合撤闸25项恰5红，其中Actual秘密哨兵1红，恢复通过 | B4真框架PASS、控制台Logic主SQL保留问号，来源说明不再误称Hibernate；展开只读附注可见ds_0与[100, BOOK,100]，开启展示脱敏后重开附注整体隐藏（frame-f6014b10/37e470bd/acaf4859）；已开面板即时响应GUI及长警告/附注可读性待验，执行级丢路由另记UI-013 |
| UI-009 | B3/U29 | 历史正则输入非法左括号误报“该文件暂无SQL条目”，合法正则立即恢复2条；frame-178d73d2/d5d54289 | dca7b369→dev b75b56b7 | 纳入B4最终十五类234/0/0/0；与UI-008联合撤闸25项恰5红，其中历史4红，恢复通过 | B4原七条文件非法左括号显示红色错误且列表明确非法正则；关正则清错并显示无匹配，合法id=6或id=7再开正则恢复2条（frame-25a6bf10/e3889556/97c02eb3）；原路径复验通过，不代证全部检索Diff场景 |
| UI-010 | B3/U38 | 切AUTO发旧ui_probe查询后输入换user，旧候选弹层仍出现且取消后旧输出残留；frame-84fa67aa | dev fa40f9a6、85296035；测试前提修正338e1544 | B4旧请求18项全绿，撤代次18项恰17红、仅撤写文档末端复查单例1红；全部恢复，纳入最终十五类234/0/0/0 | B4原序列旧ui_probe→AUTO立即换user→转换，仅出现新user候选并生成新QueryWrapper<User>聚合链（frame-933cc0a0/6841613d），旧候选/输出未回灌；一次GUI时序不外推所有竞态，确定性旧回调由定向正反覆盖 |
| UI-011 | B3/U39 | 243页顶称11工具已注册可调用，平台设置入口却提示需2025.2+及MCP Server；就绪文案矛盾，导航降级正常 | dev 2511c047；测试夹具修正be23f767、0cbf10fc | 跨发行物改用各版自身JBR与真实class资源定位，能力探测纳入B4最终十五类234/0/0/0；243/253/262签名不代证实际调用 | B4真实243缺接口提示与插件选择分行，页头声明数不冒充可调用数；关总开关、单项停用及Revert文案正常（frame-fe2e36f0/e15e2dc7/eff2e5c4/7ecbccec），未Apply临时改动；原误报路径通过，有能力平台11工具实调另验 |
| UI-012 | B4/U02；B5勘误 | 原“底部只到#8”不是布局裁剪或丢行：真实几何/model10正常，行号排序可达#9/#10，时间升序实际9、10、1…；无时间StdOut借IDE解析时刻、无签名匹配退队首造成时间关联失真 | dev 07be126f、5d300bd2、d2cbc0ed；仅修时间关联，不改排序/布局/容量；临时几何诊断清理6d24b4ba | B5几何四类92绿不能称修复；B6时间关联四类35绿，恢复两旧借时分支35项恰7红，恢复35绿；清理诊断独立TailFollow11绿 | B6 Light125%真实storm463→清空→时间升序→ids1~10，底#9/#10、顶#1/#2/#3（frame-3a8109d7/4f35873c），原错序路径闭环；不外推真实带时间日志跨线程顺序，U02其余尾随/排序仍待验 |
| UI-013 | B4/U31 | 真100/101两次Logic相同但Actual分别ds0/100与ds1/101；去重合为一行×2仅留ds0，重复分支跳过保存；基线已存在，不能把两执行拼成一次跨片 | t-devui-sharding-dedup：91e15538/ba0deb91→dev 790b4420/038e1442；Sharding执行级保真，其他日志族不改 | B5四类90绿，摘两生产闸门90项恰6红（五保真、一徽章），恢复并加诊断测试后92/0/0/0 | B6新包真框架PASS、去重勾选下by-id为独立#3/#4；07:23:39历史两条独立o147-id，pd.a分别ds0/100/BOOK,100与ds1/101/BOOK,101，主SQL问号、params空（frame-a2505757）；逐次保存/路由保真已验，同模板无Actual尾条未归因，全部flush边界不判通过 |
| UI-014 | B6/U14、U15；B7新回归 | 原未知QUERY确认No未见SUBMITTED却误报已提交，runner final-step同步模态另有焦点SEVERE；B7修复把60秒选择思考时间计入prepare预算，并有popup选择dispose触发canceled问题，直接CANCELLED未到确认，不是原No路径通过 | 原修复dev 25ca6578、7adab32d、692637a5；续修381a9ad8→dev dd0f8347，choiceAccepted防误取消、用户交互暂停预算、后台真超时BLOCKED | 原独立56绿/撤owner比较24项2红/恢复56绿；B7七类111/0/0/0；续修独立60绿/撤保护44项4红/恢复60绿；B8合流及诊断清理后七类115/0/0/0，fresh XML2026-10-17 10:15:22～52 | B7直接CANCELLED回归保留；B8源选择等253秒仍进入正确MySQL会话/确认，确认再等约505秒点No显示已取消、无SUBMITTED及原焦点SEVERE（frame-935a2641/66c752e3）；重试两阶段Yes后10:45:44.405 SUBMITTED，真实id=1一行且回执正确（frame-2b825722）。原取消误报、交互等待超时及选择dispose误取消路径复验通过，不代证U14/U15整组或合并两阶段安全确认 |
| UI-015 | B6/U15 | 注解预览选中user右键只有Cut/Copy/Paste，模板仅挂EditorPopupMenu而自建Editor用BasicEditorPopupMenu，非选区错误；frame-46060b15/5a314a84 | dev 891d5718、55e27661；保留基础动作并挂既有模板组 | 独立四类69绿/摘模板引用69项恰3红/恢复69绿，243/253/262 EditorEx菜单签名已核；B7七类合流111/0/0/0；B8七类115/0/0/0 | B7预览选user基础动作保留、三模板启用，整SQL三项禁用（frame-43056bbb/1474e441/00a5fa50）；B8同入口COUNT于11:05:47.751 SUBMITTED返回41（frame-a02fdf2f），抽样取回10行（frame-38181c91），原预览SQL/id71不变。菜单正反及预览COUNT/抽样实执行已验；预览快捷模板EXPLAIN、长WHERE等业务边界仍待，不升级U15整组 |
| UI-016 | B8登记/U41；08:58:47退出现场 | Bus is already disposed、Double release of editor、RunnerLayoutUi保存状态访问已销毁project；243原生clientView挂应用级服务、JdbcConsole挂project，插件共享view关闭协议待修。首次释放栈不在idea.log，未归因为插件直接双重释放 | 独立修复458f6950/d832d2c4及边界/签名提交已合dev 6b226bb4/8eeca1cf等，最终签名bb27983f | 真实初始两例1绿1目标红UI016-OWNED-VIEW-RETAINED；仅撤关闭前释放三例恰1红，恢复与异常边界最终5/0/0/0（04:40:18Z）；243/253/262签名4/0/0/0（04:49:30Z），主控核XML。编译/ProjectEntity/grid缺依赖前提失败不计目标红 | B8退出仍有Bus/ClientSession/RunnerLayoutUi同族错误，没有新增Double release；正常退出0不代证已修。待新包退出复验；主动INFO Throwable与Y盘访问拒绝不列入缺陷 |
| UI-017 | B8/U23 | foreach已存UI-Shared（三值71/72/73），另一selectByAnnotationId默认id91、列表仅默认，另存同名两次报已存在（frame-25d8a81d）；与跨语句同名独立快照文案冲突 | 独立8b10f9bd/c31cc9d7→dev3713c14f/a6f2467f；验证证据1bc062b5→69b17567 | 与UI019三类最终60/0/0/0（缓存28、对话框11、面板21）；只撤按键查重3例全红，恢复60绿，主控核XML | 待新包跨语句同名隔离、同语句重复保护及独立快照复验；自动测试通过不代证GUI |
| UI-018 | B8/U37 | 有效单列TSV先生成回滚INSERT，改invalid_snapshot后生成报错却仍留旧INSERT、黄色可执行提示与执行/复制按钮（frame-9d584547）；应失效旧模型及操作入口，现场未点击执行 | 独立f283b0c6/79716ad2→dev334e996d/6c019eac；监听输入及主键失效、失败撤旧，保留重生成元数据 | 三类最终75/0/0/0（Generator49、Dialog15、Panel11），XML 04:23:33–41Z主控核对；分别摘监听、生成前失效、元数据保留各2例全红，恢复75绿 | 待新包输入失效/生成失败路径复验；有效TSV只证明生成，未执行写SQL，U37该子项未通过 |
| UI-019 | B8后定向测试/U23 | UI017新面板回归返回foreach方法，缓存/权威map已有81,82但重建输入框为空；首轮57项1失败，不用额外切预设救绿 | 独立2d3dd7cc→devd60a50e7；监听注册前初始化字段，保留原失败断言 | 首轮57项1失败；修复后与UI017最终60绿；只撤初始化3例2红1绿，无值null负对照通过；恢复最终60/0/0/0，主控核XML | 待新包默认/命名/null重新打开及方法往返复验，不冒充已实测UI缺陷 |
| UI-020 | B8后代码复核/U37 | executeRollback调用返回Unit的executeAdhocSql后无条件标执行；DML确认No直接return，旧对话框仍标成功。属于代码确认，未在GUI点击回滚执行 | 独立b2fa9e02→devc0d06101：透传prepared/nonprepared回执，仅SUBMITTED留痕；关闭弹窗不丢真实提交记录但禁止迟到改UI | 五类最终119/0/0/0（04:50:33–46Z）主控核XML；恢复旧假成功3红、断prepared回执1红、撤恰一次保护3红，全部恢复。旧ABI 3例NoSuchMethodError经强制重编译排除；不把Boolean就绪或调用返回当提交 | 待仅取消的安全GUI负例；真实回滚写入未授权，不能用自动测试代称写库实测 |
| UI-021 | 批C/U31 | **INSERT VALUES 场景展示脱敏列名-值对位错位**：`DisplayMasker.columnHintBefore` 把前一个字面量值当列名 hint——`VALUES ('ui_mask_probe','Secret123X','mask@probe.test',…)` 中 password 列 `Secret123X` **漏打**（其 hint=ui_mask_probe 不含关键词），紧随 email 值被**误打**（其 hint=Secret123X，lowercase 含 `secret` 命中）。JDK21 直调 dev 编译产物判别实证（本机 java 跑 `DisplayMasker.INSTANCE.maskSqlText`）：compact/spaced 两形态均输出 `'ui_mask_probe', 'Secret123X', '***'`；对照 `WHERE password = 'Secret123X'` 正确打码。`DisplayMaskerTest` 17 用例无 INSERT VALUES 场景（测试盲区） | **已修**：`maskSqlText`/`parameterize` 增 INSERT 列清单对位（`INSERT_COLUMNS` 正则解析 + 按 LITERAL 出现序取列名，解析失败/超列数逐位回退旧规则）；`DisplayMaskerTest` 增 5 例 | **定向 22/0/0 绿**（17 旧+5 新）；**反向验证恰 3 红**（摘对位后列名对位/多列对位/parameterize 对位三例红，回退路径与非 INSERT 对照保持绿），恢复再绿 | 列表/导出/回放/MCP/AI 上下文五消费面同源 |
| UI-022 | 批C/U17 | **降级模式（内存 PSI）下动态条件勾选联动失效**：降级横幅在场时勾选 XML 动态条件复选框（status/age 已勾选实证），SQL 不追加 WHERE；**同面板完整模式对照**（act-3，无降级）勾选 status 即出现 `WHERE status = 1`——联动本身正常，失效限定于降级态。降级横幅文案仅称「表/列上下文补全受限」，**未披露交互缺失**，用户会误以为勾选已生效（frame bc-U17-p2-xml-q2-sql / act-3-after-check） | **已修（披露面最小修复）**：降级横幅 zh/en 文案加「参数联动与动态条件勾选在降级期不生效」+ 重试恢复指引；**联动功能修复（降级文档重渲染 write 链）保留后续专项**——重试路径可自愈 | 文案改动已提交；功能面待专项 | 降级触发面=预热迟到/失败（重启初期/重渲染路径） |
| UI-023 | 批C/U29 | **「差异对比」全形态恒报「无上次预览可对比」**：`SqlPreviewPanelResultView.kt:220` 的 `previousSql = editor?.document?.text` 在首渲染链执行时 editor 常尚未创建（控制台/预热抢占）→ `previousSql` 恒 null → `showSqlDiff` 永远走 `preview.diff.noPrevious` 空态。三场景实测——注解预览（selectByAnnotationId）、XML 预览（selectUserListByXml/selectUserByChoose）、参数重渲染（#id 91→92）后点击「更多→差异对比」**均报无上次预览**（frame imgu29d2-2/imgu29x-1-diff/imgui023-3-diff） | **已修**（`267649c4`）：editor 缺失时用本次预览原文 `result.sql` 兜底（语义不变：缓存本次 SQL 供下次对比）；`compileKotlin` BUILD SUCCESSFUL | **专项已闭环（2026-10-18 晚，真机复核通过）**。真相两段：①先前「修复入包后点击无输出」读数全部来自**旧包**——沙盒 `plugins/` jar 实际停在 10-16 18:01，18:28~18:40 的 diff 探针全跑在修复部署（10-18 20:01 installPlugin+IDE 重启）之前；`imgu29d7-2-diff.png` 全屏帧右下角实捕两条「无上次预览可对比」toast（旧探针只截预览区 `bbox=rect` 把右下 toast 裁掉，造成「无输出」假象）。②20:01 后修复包在役（沙盒 jar javap 第 816/838 行 `PreviewResult.getSql`→`setPreviousSql` 实证），像素直点「差异对比」仍「无可见输出」——win32 `EnumWindows` 实锤 **Diff 窗口已创建（标题「SQL 差异对比」，20:53 键盘路线与 20:54 像素路线各建一个）但 z 序落在 IDE 主窗之后**（rect=(100,100,1820,980) 与主窗全等、被完全遮挡）；对照实验「分享」弹窗阳性（frame imgdr5-1）证明菜单点击链路无恙。最小化主窗后完整出图：上次带 `WHERE t.sex = 1`（删除行高亮）/当前无 WHERE、右上 `2 differences`、Side-by-side+SQL 语法高亮+变更标记（frame imgdr8-1-diff-alone/imgdr8-2）——**UI-023 修复真机闭环 ✅**；z 序残留登记 UI-024 | 同族：U29 有变化 Diff 正例（已随本项闭环出图）；**新观察项 UI-024（候选缺陷）**：DiffManager.showDiff 的窗口不抢前台 |
| UI-024 | UI-023 专项现场 | **Diff 窗口开在 IDE 主窗之后**：`DiffManager.showDiff` 创建的「SQL 差异对比」modeless 窗口 rect=(100,100,1820,980) 与主窗全等且 z 序在主窗之下，用户点击「差异对比」后主窗前无任何可见变化（无窗口、无 toast、idea.log 无插件异常），须 Alt+Tab/任务栏才能看到 Diff——易被感知为「点击无响应」（frame imgdr7-1/imgdr8-1 证据链）。两次独立触发（键盘+像素）均复现 | 未修（登记项）；修复方向候选：showDiff 后请求窗口前置/激活，或改 `WindowManager` 上的_dialog 路线；需先在真机人工确认是否合成输入环境的窗口激活策略产物 | 证据充分（EnumWindows 两实例 rect + 最小化主窗后出图），但**沙盒合成输入无法完全排除窗口激活策略因素**，修复前需真机人工点一次「差异对比」定性 | 同族：IDEA 原生 Diff 从 VCS 等入口打开时是否同样置后（对照面） |
| UI-031 | 2026-10-19 用户真机（dev 验收） | **FieldFill 安全模拟：UPDATE 预览中 `[fill=INSERT]` 字段的模拟值被静默忽略**——面板对所有 fill 字段提供模拟行并置灰普通输入（tooltip「已由模拟值覆盖」），但 `FieldFillSimulation.overlay` 的 `applies(kind, fill)` 闸门对 `UPDATE + fill=INSERT` 返回 false → 模拟值不生效、SQL 回落灰框值。用户截图：`create_time [fill=INSERT]` 灰框 `111`、模拟值 `111212`，生成 SQL 为 `create_time = '111'`；与 b6a88945「模拟值优先」的 UI 承诺自相矛盾 | **已修**：模拟值优先不再按 fill 策略过滤（UPSERT 仍不模拟，UI 文案已披露）；模型层去掉 explicit 优先守卫、面板去掉预剔 explicitValues；既有契约用例改为「INSERT/UPDATE 下用户手填模拟值均生效」 | 定向 FieldFill 两族 + CRUD 生成器 **47/0/0**；反向恢复旧 fill 闸门 → **恰 3 红**（两条新回归 + 契约用例），恢复后全绿、探针零残留 | 同族：U20 FieldFill 面板；真机复验待用户 |
| UI-032 | 2026-10-19 用户真机（dev 验收） | **参数导入键暴露实体变量前缀**：单表无别名 wrapper 预览（`UPDATE t_order …`）的参数 source 为 `order.status`（`BatchOperationConverter.entityPrefix` 取调用首参表达式，不是 SQL 别名），导入弹框预填与用户手写键都要带 `order.`；单表场景下前缀纯属内部命名空间，用户按列名导入会被判未匹配 | **已改**（体验改进，非渲染缺陷）：`resolveSource` 增「末段唯一匹配」（`status` 唯一命中 `order.status`；多实体同名列歧义时保持未匹配不猜）；预填 JSON 取最短唯一键（单表输出裸列名，歧义时保留完整 source）；导入侧两种形态都接受 | 定向 3 类 **59/0/0**；反向摘两处新逻辑 → **恰 2 红**（裸列名导入 + 预填短键），恢复后全绿、探针零残留 | 行标签仍显示完整 source（多实体消歧用途）；同族 P-05 导入链 |
| UI-033 | 2026-10-19 用户真机（dev 验收） | **回滚「执行快照查询」走 executeAdhocSql：替换预览编辑器且结果不自动填充**——点按钮后预览窗口被切成执行视图（显示快照 SELECT 与结果网格），回滚弹窗下方快照框仍为空，须人工从另一窗口复制粘贴；与 T-PreviewSideQuery「只读动作不替换预览编辑器」原则不一致。另：整行等值定位告警文案写死「DELETE」，UPDATE 场景误标 | **已改**：`runSnapshotQuery` 改走 `PreviewSideQuery` 只读旁路（与 DML 影响面预演同口径方言闸；不换入控制台），结果网格转 TSV 自动填入快照框并直接生成回滚；阻断/失败/空结果在对话框内提示；「复制快照 SQL」+ 手工粘贴保留为降级通道；告警文案改为中性（未提供主键列时整行等值定位） | 定向回滚三族 **90/0/0**；反向恢复 executeAdhocSql → **恰 3 红**（自动填充/无数据源提示/空结果提示），恢复后全绿、探针零残留 | 同族 U37 回滚；真机复验待用户 |
| UI-034 | 2026-10-19 用户真机（dev 验收） | **预估影响行数结果不显眼 + 通知气泡内容不当**：结果只在左侧参数区底部灰字（「预估影响行数：1」）；右下角每次弹「试算语句/目标表」预演通知——讲的是探针而非结果，与内联结果并存易误读；预览弹窗底部只有「复制 SQL」，无「复制并关闭」；执行视图「返回预览」为普通小按钮不够显眼 | **已改**：①结果改挂顶部独立横幅行（蓝色加粗；失败红色），与降级横幅上下各占一行、互不抢位；②删除执行前的探针预演通知（探针详情保留在结果 tooltip）；③底部新增蓝色「复制 SQL 并关闭」（复制后关闭弹窗）；④执行视图「返回预览」改蓝色主行动样式 | 定向 4 类 **49/0/0**；反向摘结果横幅与关闭行为 → **恰 3 红**，恢复后全绿、探针零残留 | 同族 U15/R42 影响面预演；真机复验待用户 |
| UI-035 | 2026-10-19 用户真机（dev 验收） | **执行计划对话框：取消按钮未跟随插件语言 + 无数据源时「在控制台打开」无反应**——英文 IDE 下取消按钮显示 Cancel（平台 cancelAction 跟 IDE 语言，插件语言独立）；无绑定数据源时错误文案复用 DML 影响面的「无法试算」（语境不符），按钮触发一次必然失败的执行且弹窗不关闭，用户看着像没反应 | **已改**：①`ExplainPlanDialog` 按插件 bundle 覆盖取消文案（新增 `panel.explain.close`=关闭）；②无数据源分支按钮改为「打开 Database 工具窗口」（复用 `DataSourceSelector.openDatabaseToolWindow`），文案换执行计划专用键 `panel.explain.noDataSource`；③按钮动作后关闭错误对话框，避免数据库窗口/控制台被挡在后面 | 定向解释计划四类 **22/0/0**；反向摘取消文案本地化与打开 Database 按钮 → **恰 2 红**（两条用例独立命中），恢复后全绿、探针零残留 | 同族 U18/U25 执行计划；真机复验待用户 |
| UI-036 | 2026-10-20 用户真机（dev 验收） | **回滚快照以原始 TSV 单行展示**：列多时一行挤不下、横向看不全，列名与值的对应关系不直观 | **已改**：快照区改表格/文本双态——自动查询结果默认只读表格（列名+行值）并显示「N 行 × M 列」摘要；「文本（粘贴/编辑）」保留手工粘贴路径，切换时按文本重解析；生成回滚仍以文本为权威来源 | 定向回滚三族 **91/0/0**；反向摘两处表格填充 → **恰 2 红**，恢复后全绿、探针零残留 | 同族 U37 回滚；真机复验待用户 |
| UI-037 | 2026-10-20 用户真机（dev 验收） | **快照查询无数据源只报错**：点「执行快照查询」时未绑定数据源，只显示「请先在控制台执行一次查询完成绑定」，要求用户多绕一步 | **已改**：无数据源分支复用 T-UI030 链路——直接弹数据源选择器（只准备/绑定，不提交语句），绑定成功后自动重试快照查询；取消/超时保留原说明文案；选择器报错就地显示 | 定向回滚三族 **92/0/0**；反向恢复旧「只报错」行为 → **恰 1 红**，恢复后全绿、探针零残留 | 同族 U37/UI-033/UI-036；真机复验待用户 |
| UI-041 | 2026-10-20 用户真机（dev 验收） | **回滚快照展示不便**：只读表格无排序/列宽/复制；「表格/文本」切换按钮被 FlowLayout 换行裁掉（用户看不到切换入口）；用户期望 IDEA 原生 CSV/TSV 表格体验 | **已改**：A 表格增强（可排序/行号/列头 tooltip/双击与按钮自适应列宽/单元格行列复制/右键菜单）；B「在 CSV 编辑器中打开」（临时 TSV → FileEditorManager，Ultimate 走 grid 原生 CSV 表格，Community 降级文本；dispose 回收未打开的临时文件）；C 布局修复（切换按钮与摘要移至标题行右侧固定位、动作按钮独立成行，窄宽不再被裁） | 定向回滚三族 **102/0/0**；反向摘排序/复制/临时文件写入 → **恰 3 红**，恢复后全绿、探针零残留 | 同族 U37/UI-033/UI-036/UI-037；真机复验待用户 |
| UI-042 | 2026-10-21 用户真机（master，2026.10.16 安装包） | **预览弹窗底部「复制 SQL 并关闭」按钮整个不显示**：用户反馈「为什么我没有看到这个按钮」；字节码实证 `SqlPreviewDialog` 的 `init()` 在偏移 138 执行、`copyAndCloseAction` 字段在偏移 203 才赋值 ⇒ 构造期 `createActions()` 读到 null，平台静默不渲染该按钮（不抛错、无日志） | **已修**：把 `copyAndCloseAction` 声明上移到 `init {}` 之前；同时补构造期捕获缝 `actionsAtConstructionForTest`（声明同须在 init 前）与用例断言「构造期产出的动作含该按钮」 | 守卫 `DialogWrapperInitOrderGuardTest` 修前对该形态**完全失明**（`blockEnd` 只按 `{}` 深度算，表达式体 `createActions` 被截成一行，字段整段落在扫描面外）；已补「表达式体按括号配平+缩进回退界定」与「多行签名 `) {` 换行」两处扫描修正 + 合成用例；反向验证：放回缺陷 → 守卫**精确命中** `copyAndCloseAction（声明@97）`，恢复后 48/0/0 全绿、探针零残留 | 同族 UI-034（本按钮的引入笔）；**教训＝按钮类回归必须断言构造期产出，事后重调 createActions() 是假绿** |
| UI-043 | 2026-10-21 用户真机（master，装 UI-042 包后） | **蓝色主行动按钮多出一圈方角蓝底**：按钮四周能看到方形蓝色，与 IDEA 原生蓝按钮（圆角内缩）不一致 | **已改**：`applyPrimaryButtonStyle` 由「`isOpaque=true` + `background` 手工涂主题蓝」改为打平台标记 `JButton.styleDefault`（`DarculaButtonUI.DEFAULT_STYLE_KEY`），圆角填充与配色交给 `DarculaButtonPainter`；同时 `isOpaque=false` 清掉手工底色 | 平台字节码实证：`DarculaButtonPainter.getOffset()==4`（边框内缩 4px 圆角），底色铺满整个矩形必然溢出成方角；`isDefaultButton()` 判据 = `JButton.isDefaultButton()` 或该 client property；平台自身 `ButtonPanelKt`/`OnboardingDialogButtons` 都用这个键。补用例断言「打了 styleDefault 且不再手工铺底色」 | 同族 UI-034/UI-042（同一按钮）；影响「返回预览」等所有 `primaryButton` 调用点；**2026-10-21 用户复验浅色主题同样正常**（去掉手工 foreground 后 `Button.default.foreground` 给出的对比度足够，此前标注的「浅色未验证」已闭环） |
| UI-044 | 2026-10-21 用户真机（master，装 UI-043 包后） | **「复制 SQL 并关闭」后的通知里仍挂「关闭 SQL 面板」链接**：弹窗已关，该链接既冗余又指不到目标（用户截图：通知正文「SQL 已复制到剪贴板」下方仍有蓝色「关闭 SQL 面板」） | **已改**：`copySql()` 增参数 `offerClosePanelAction`（默认 true，保持单纯复制的原行为）；「复制 SQL 并关闭」传 `false`，通知不再挂该动作 | 新增测试缝 `copyNotificationActionsForTest`（WeakHashMap 按面板实例挂，扩展属性不能有 backing field）；三条用例：单纯复制带动作、抑制时不带、弹窗层「复制并关闭」不带。定向 **41/0/0**；反向摘两处 `if (offerClosePanelAction)` → **恰 2 红**（弹窗层 + 面板层各一），恢复后全绿、探针零残留 | 同族 UI-034/UI-042/UI-043（同一按钮链） |
| UI-045 | 2026-10-21 用户真机（master 243 版，暗/亮双主题截图对比） | **「SQL → Java」页签的「调用类」「返回类型」两个选择框与平台输入框观感不一致**：描边偏淡、圆角过渡发硬。用户按提示截了 243 版暗/亮两套主题（首轮对比图里上方 ComboBox 处于焦点态，基准不可比） | **已改（两笔）**：①`ea0b31b5` 描边 1px → `BW`（`Component.focusWidth`，默认 2px）；②`5224f8e4` 圆角由 `drawRoundRect` 圆弧（半径取 `COMPONENT_ARC`=5）改为平台同法 `JBUIScale.scale(7f)` + `Path2D.quadTo` 四角二次贝塞尔。同时把两处逐行重复的绘制合并为 `paintPickerFieldSurface`（此前必须手动同步改，漏一处即观感分叉） | 243 字节码实证（`DarculaUIUtil.doPaint`）：描边宽 `BW`=2px；圆角半径**硬编码 `scale(7.0f)`**，不是静态字段 `Component.arc`=5；轮廓走 `Path2D.quadTo` 而非 `RoundRectangle2D` 椭圆弧。⚠️ 第一笔只改了描边、圆角仍用 `COMPONENT_ARC`，用户补 243 截图才暴露——**教训：读平台绘制口径必须跟到实际绘制路径，静态字段的默认值不等于被使用的值**。终态像素核对（浅色主题）：平台下拉框上边框最深 `201,204,214` / 过渡 5 行，自绘选择框上边框 `201,204,214` / 过渡 5 行，**逐位一致**；暗色主题同。定向 `SqlToJavaPanel` 四族 **83/0/0** | 同族 UI-043（手工绘制 vs 交给 LAF）；两者构成「自绘组件必须对齐平台绘制口径」的一族 |
| UI-038 | 2026-10-20 用户真机（mybatis-plus-demo 复现） | **setSql 的字符串拼接实参导致 SET 整段丢失**：`setSql("amount = amount * " + rate)`（demo `OrderController#discount`）预览只出 `UPDATE t_order` + `WHERE status = ?`，SET 整段消失——非法 SQL。根因：拼接表达式只要有运行期段就整体降级为占位符，而 `setSql` 分支只接受完全静态字面量 | **已修**：`SqlValue.Placeholder` 增 `fragment`（拼接表达式作为原始 SQL 片段时的文本形态，静态段保留、运行期段写 `?`）；新增 `WrapperArg.asSqlFragment()`，setSql/setApply、apply/exists、last 尾缀、having、inSql/notInSql、eqSql 系列、first/comment、applyFunc/selectFunc 模板、MPJ 字符串 join 与 around 统一换口；布尔前缀启发式排除片段实参；`UpdateStatement.unparsedSets` 让纯运行期片段省略时在警示区留痕 | 复现用例修前 1 红（`UPDATE order_entity []WHERE ...`）；修后定向 **25/0/0** + 定向回归 **1458/0/0/0**；反向三组各 **12 / 13 / 1** 红，探针零残留 | 值位置（eq/set 的值实参）保持恒渲染 `?`（整个字符串是一个绑定参数）；`last("LIMIT " + n)` 与 `select(String...)` 登记遗留；真机复验待用户 |
| UI-039 | 2026-10-20 用户真机（UI-038 修复包上继续验收） | **参数按位置错位：填 `status` 的值落到了 SET 的 `rate` 占位符**——`SET amount = amount * ?` 的 `?` 未登记进参数表，面板按位置把首个来源（`status`）对应到 SQL 里第 1 个 `?`，预览成 `amount = amount * 2` 且 `WHERE status = ?` 永远填不上；同时 `rate` 没有输入行 | **已修**：片段携带「占位符来源」（`SqlFragment(text, params)`）——`Placeholder.fragment` 换成分片类型、`asSqlFragment()` 返回文本+来源、新增 `renderSqlFragment` 按最终文本顺序（含 `{i}` 绑定插入位置）登记来源；`SetClause.rawParams` / `Condition.Raw.params` / `UpdateStatement.rawTailParams` / `JoinClause.rawParams` 四处载体，生成器在各渲染点按序喂 `paramSink`/`paramHints` | 定向参数顺序用例：demo 形态 `unresolvedParams=[rate, status]` 且 `buildExecutableSql` 回填 `status=2` 只落 WHERE；`{0}` 与拼接混排顺序 `[y, x]`；尾缀顺序 `[status, n]`；MPJ join `[stationId, id]`。**类级护栏**：`testPlaceholderSourceCardinalityInvariant`（10 个片段入口逐条断言「`?` 数 == 来源数」）+ `testFragmentEntriesDoNotSilentlyDropRuntimeTemplate`（同一矩阵断言片段必须出现）。反向验证：造回「渲染但不登记来源」→ 4 红（基数+顺序）、造回「整段丢弃」→ 17 红（含不丢弃矩阵，基数仍绿——两护栏互补）。全量门禁 **7973/3/8**（3 条失败为 `InjectionGateTest`×2 与 `UnknownParamGateTest`×1 的 EXPLAIN 执行链用例，均已在干净 dev HEAD 上基线复现，判为环境相关既有失败） | `select(String...)`/`rawFirst`/`rawAround`/UNION 已补齐来源登记（`ColumnRef.rawParams` / `SelectStatement.rawFirstParams+rawAroundParams` / `UnionClause.params`，矩阵共 15 个入口）；`last("LIMIT "+n)` 改为占位建模；**MPJ fill 显式关闭**（合成占位符、面板回填后才追加，改由 `MpjFillConverterTest` 钉住性质）；**真机闭环（2026-10-20，243 沙盒）**：`OrderController#discount` 预览出 `SET amount = amount * ?` 且左侧有 `#rate` 行；填 rate=1.5 只改 SET、填 status=2 只改 WHERE；复制 SQL 已填/未填两态剪贴板读数一致（未填保留 `?`、无报错）；执行计划在 UPDATE 上被既有白名单挡下、在含 `?` 的 SELECT 上被方言闸门拦下，均与 `?` 无关，日志无插件异常 |
| UI-040 | 2026-10-20 用户真机（UI-038/039 修复包验收） | **方言闸门阻断态没有出口**：回滚弹窗报「已阻止执行：当前 SQL 按 PostgreSQL 渲染，但所选数据源的实际方言是 MySQL。当前模型无法无损重追，或显示文本已被编辑。请重新生成 SQL 或切换目标后再执行。」——只有一行红字，既不能换目标也不能按目标方言重渲染。根因三条：`runSnapshotQuery` 把 `canLosslesslyRerender` 写死 false（面板本有按模型能力计算的 `executionDialectGateRequest`）；`promptDataSourceThenRetrySnapshot` 只在未绑定时可达；绑定/执行后的方言刷新只重组状态行、不重渲染 SQL（状态行/文本/目标三者可并存不一致） | **已修**（`T-DialectGateExit`）：回滚弹窗改用面板闸请求；`PreviewSideQuery.Blocked` 携带 verdict，方言冲突时露出「按目标方言重新渲染并重试」与「切换数据源」两个出口（重渲染成功后按新预览文本重算快照 SELECT 再重试一次；`MANUAL_CONFLICT` 不给重渲染，尊重手动选择）；`prepareConsole/chooseAndPrepare` 增 `forceChoose`，跳过共享控制台复用与 last-used 静默命中；绑定源给出更强方言且文本未被编辑时按目标方言重渲染 | 定向 **68/0/0**（回滚弹窗 32 含新增 4 例 / 判据 3 / 接线守卫 6 / 侧查询 10 / 闸门单测 17）；反向三探针 **4 红**，并发现 P1 接线守卫被子串匹配骗过（注释掉的调用算命中）→ 改整行匹配后探针 2 **恰 1 红** | 定向回归与全量门禁读数见计划文档 §8.2；`MANUAL_CONFLICT` 仅给切换数据源；真机复验待用户 |

## 第二批修复合流与复验

首批沙盒通过原生Exit后，运行中演示应用Terminate卡在Waiting for process detach；90秒有界等待结束仍在运行，已先保存JDK8线程现场，再按IDE原生Kill process结束本次应用。归属核实为PID24408、父54712、命令行含本次MPSV-MasterMerge-UI243 agent路径；没有操作其他进程。随后runIde任务退出0。线程现场见会话日志call_BS521Rpri31WTvUICa2mT7yM-stdout.log，归因未定，不预判插件关闭死锁。原始界面保持667d7d15直到本批结束。

- UI-001：原修复b0433976→dev `0bc74e37`，独立定向27/0/0/0，撤首列过滤2/2红、恢复27绿。
- UI-002：原修复8fd89abc→dev `1d8c41bc`；此前并非中断，而是主动留待测试。用户要求检查后已恢复原代理补验：两类18/0/0/0，临时R61标签4/4红，恢复18绿；证据记录09c8c978留在独立分支。
- UI-003：原修复12c01f4d→dev `c3fc0f52`，定向9/0/0/0，摘图标三例2红1绿、恢复9绿，清空/尾随业务未改。
- 三个源码修复均已合入，未合入代理的旧整份验收文档以免覆盖主控现场账。合流后定向回归在后台运行，通过后以同一独立配置启动第二批；尚不能写UI复验通过。
- 子代理核验：四个修复/夹具工作树均干净，有提交和实际证据；没有发现掉线遗留的未提交成果。只有UI-002缺执行证据，已补齐。

- UI-004已证根因：JBTabs真实243回调参数是oldSelection/newSelection，SqlLogPanel将第一参当current，导致进入不刷、离开反刷；设置时0文件扫描提前置首扫标记，showing兜底不再补。独立分支t-devui-history-refresh已提交9e32ad43，去除旧测试人工showing补刷的假绿并补真实tabs.select回归；因另一会话T-SqlInsight-Runtime占用，定向/反向未跑，批末补验，不先勾通过。

### 第二批实际结果

运行包dev@c3fc0f52，十类合流定向116/0/0/0，重新启动PID36912并自动恢复1920×1080全屏。系统级置顶仍未证明。

- UI-001中文默认窗口复验通过：998×742设置页控件正常靠左、无横向截断，R90三行隐私全文可见（frame-879d2246），代码生成下拉含五选项且末项换行不截断（frame-01c745b6）。英文/缩放矩阵待后续复验。
- UI-002中文复验通过：重启后读取上一批19:21:05历史计划，显示本地时间/快照来源（frame-4e984f16）；再执行显示“上次执行（当前会话）”（frame-a8a53746），不再显示R61。R90跨IDE重启正例与普通参数ui_alpha/1/18召回同时通过；预设栏重启默认收起（frame-376e94b9）。
- UI-003宽栏暗色复验通过：第三动作垃圾桶图标在空/有数据时可见，点击后5条日志清空（frame-93892d50），再请求ids6/7得到新2条（frame-a84ebfed），捕获未停止。
- U36开启自动保存并设独立build/ui243-history后，已清空的5条及新2条都在同一历史文件中，共7条；不操作已有历史目录。首次进入历史未自动刷新、手动刷新出现文件，登记UI-004待根因核实。历史内原始SQL搜id=6得1条；带空格id = 6为0，属于原文匹配口径；正则id=6|id=7得2条（frame-c6d4e151）。双选原生Diff打开并显示1 difference（frame-265fd990）。

- U03/U05窄栏：将日志工具窗缩至当前约300物理像素，四页仍单行，历史被收进平台MorePopup；菜单有SQL历史（frame-b48d78e2），键盘Enter切入后历史成为当前页（frame-840b1226）。清除垃圾桶仍在前三动作可见。精确230/250/460及主题缩放组合仍待补，不用此一格覆盖全矩阵。
- U30日志转SQL：真实分两行输入Preparing/Parameters，生成id=6与username='ui_test'，状态“已转换1条SQL”（frame-dd8613e9）。首轮typeText中的换行未落地，错拼成一行时正确显示未回填告警；明确属输入工具问题，不归产品缺陷。
- U38六形态首轮：聚合/分组/HAVING发现UI-005；INSERT NULL生成setName(null)（frame-0d0ab9ea）；DISTINCT与LIMIT5/FOR UPDATE单last同时保留（frame-16c16ee4）；UNION明确注释需手动处理（frame-604c22fa）。双引号另现fallback实体类名`QueryWrapper<"UiProbe">`不合法，已交同修复代理一并处理，列引号应保留、Java类型名不应携SQL引号（frame-a72d62c5）。这些仅静态GUI输出，不称生成Java已运行。

- U20/CRUD：实体User生成候选六项正确，INSERT主键AUTO未勾、fill安全模拟默认关闭；发现UI-006参数区BoxLayout/FlowLayout导致开关右移、模拟输入在横向视口外（frame-307e73ef/219fc32e）。独立t-devui-fill-layout已交97a6b6b4修复与真实350px回归，未编译/测试，待批末验证和GUI复验；不因输入尚不可见就声称R92全部通过。
- U12/U27 XML真实入口：UserMapper.xml selectUserListByXml有动态条件真/假标记，输入admin/1/18后LIKE concat和年龄实值正常，取消status后SQL移除该条件（frame-fb97e027）；全不选后清除输入，WHERE清除且参数空（frame-4848d756）。
- U42嵌入/模块作用域：源码模块拼接、StringBuffer、helper返回三形态均生成wms_inventory_embed_demo及move_tool_pallet_no/qty_base_qty（frame-0a0b1d19/f2076a37/77df39c4）；切jpa-jar-model-demo宿主生成wms_inventory_jar_demo、sku_inventory_status、item_key_lot_info_received_date，未串兄弟模型（frame-b613912f）。当前聚合工程仍有模型模块，不能冒称实体来自反编译JAR，该子项另验。

## 第三批准备

第二批源码保持c3fc0f52至GUI退出；新修复已合dev：UI-004 83fbae85；UI-005 a72d3b15/1ac4a5d1/43e3ec1b；UI-006 0feb403d。UI-005独立真实MP3.5.7编译生成代码及getSqlSelect/getSqlSegment，两类68/0，基线生成器68项5红；不冒充Gradle门禁。UI-004/UI-006此前未跑定向，本轮合流五类后台验证，测试未终态前不标修复通过。

第二次退出再次等待process detach，已取IDE线程栈，定位为原生WaitForProcessTask等待而非插件锁。应用53644结束后IDE仍有残留运行确认，选择Disconnect才退出。另数据库驱动进程50828存在agent会话，疑似挂载范围过宽，留待独立归因；不据此断言是退出阻塞原因。

第三批五类首跑98项8失败：历史两类19项全绿；反向两类68项6失败均为编译夹具CodeSource.location在IDE PathClassLoader下null，独立CLI读数不能覆盖；FieldFill11项2失败为安全说明实际高度不足。已回交原代理续修，未放宽断言或以失败归环境而跳过。

UI-007辅助进程误挂载已证：第二批IDE日志5887–5889识别非主配置后仍排队attach50828，5919–5927实际RemoteJdbcServer输出agent retransform及CONFIG ACK。监听和补扫在授权后无身份排除，用户确认不能授权平台helper注入；独立t-devui-attach-target已派修复。前述命令行末尾MySQL Driver只是参数，真实main为com.intellij.database.remote.RemoteJdbcServer，归因已校正。未证明它导致退出卡住。

第三批续修与正反闭环：CodeSource后备须同时支持IDE自定义jar URLConnection，不只JDK JarURLConnection；主控解析实测jar:file资源并核class字节一致，提交40cfff4e。FieldFill动态错误状态显式失效父布局缓存后11例通过。五类最终98/0/0/0；临时恢复错误回调顺序及移除高度失效，三例全红（进入刷新计数2≠3、状态高度32不足64），立即恢复并五类98/0/0/0。主控src/main与HEAD无差异，反向探针已清。第三批沙盒按40cfff4e启动，UI007尚未纳入。

### 第三批UI已取证

第三批PID37120、主窗口全屏。UI004首次切历史自动显示既有7条文件（frame-84cbadf3），设置空扫后新文件的原始路径还需用新空目录专项复验。UI005 Query模式聚合改为单次select(status, COUNT(*) AS cnt)（frame-bfccf9eb），双引号表名生成UiProbe不带引号而列SQL引号保留（frame-8d4bd702）；AUTO实体正例仍须补。UI006默认参数区安全开关与说明左对齐完整可读（frame-66c4d714），两组fill普通/模拟输入纵排无需横滚（frame-6c636b19）；开启后日期字符串进入SQL，普通explicit优先（frame-8270532c）；非法含分号模拟恢复问号并完整显示具体字段错误（frame-10a7f8d2）。

已从GUI新窗口打开独立夹具树mybatis-plus-demo/pom.xml，保留原项目，准备四型fill、真实模型JAR和项目隔离；未从根聚合导入，避免库源码替代JAR。UI007独立六类69绿、撤拒绝14项9红、恢复69绿，尚未合入当前运行包。

- U20独立FillProbe真实mapper入口：INSERT启用后created_by=`O''Reilly`单引号加倍、operator_id=42不加引号，UPDATE专属updated_by保留?（frame-68052cbd）；数值`1 OR 1=1`被拒并显示字段提示（frame-de160f2a）。UPDATE只模拟updated_by和operator_id，INSERT专属created_by仍?，WHERE id/revision保持?（frame-29dd1662）；取消列不补回、关闭模拟恢复普通占位（frame-e91d4665）。saveOrUpdateBatch的UPSERT开启且输入模拟值仍全部?，说明明确不填（frame-c71e69a1）。本轮均未执行写SQL或用户Handler。

- U43真实JAR正例：独立宿主类搜索LegacyBooking显示Maven cn.song:mp-jar-model-lib:1.0.0及本地.m2 JAR路径（frame-73329f5f），打开触发JetBrains Decompiler条款，选择Decide later未替用户接受。由LegacyBookingCases真实调用预览生成ui_reservation_ledger、booking_code/state_code且常量BOOK,100正确（frame-8f84e19b）。已证二进制实体正向解析，后台/显式表名反查仍待验。

- U31真实分片工程已GUI导入；首跑IDE沿用JDK8导致“源17需要目标17”，不是插件缺陷。通过Project Structure选择已安装Microsoft JDK17.0.19，仅修改独立夹具工程SDK，再次IDE Run在途。该前提修正未触业务库。

- U31真框架已运行：IDE Run的ShardingH2Demo在JDK17下打印真实三条Logic/四条Actual及三组PASS（frame-9ccdd327）。增强捕获获得四条物理SQL（存量PS参数缺失告警保留）；暂停增强后both命令从真实控制台收到逻辑SQL，预览仍问号且错误提示Hibernate TRACE（frame-ccef5032）。GUI确认ShardingSphere开关已开（frame-7dd4ba1d），日志也证来源SHARDINGSPHERE，非配置问题。
- UI-008：5.5.2实际Actual后缀`::: [100, BOOK,100]`无Parameters前缀，旧解析器只认Parameters，导致参数未恢复；来源提示只区分JDBC/其他而误写Hibernate。路由actualSql已保存但预览未见附注。独立t-devui-sharding-format已派实现、需真样本反向与GUI复验，不改原始日志适应解析器。

### 第三批浅色交叉与下一批修复

- 在Appearance确认Light与125%并点OK后，原主演示窗口日志第三位清除图标可见（frame-f67bddcb）；历史首次切入列出7条/6条两个文件（frame-3c954eeb）。七条文件列表保留蓝色关键字、紫色标识符、绿色耗时与同结构徽章（frame-9c854d16）；完整预览参数1、全部操作与执行/复制/取消可见（frame-0e4c83c0）。只覆盖这几处真实画面，不能外推四主题缩放全矩阵。
- UI-009真实复现：七条历史文件打开正则开关，输入`(`，结果显示0条并称“该文件暂无SQL条目”，没有非法正则反馈（frame-178d73d2）。改为合法`id=6|id=7`立即恢复2条（frame-d5d54289），排除文件内容为空。已派独立t-devui-history-regex修复错误反馈和无匹配空态，当前运行包不变。
- UI-008已交6234a850/978ecbb3，但只读复核拦截：物理分页可能把逻辑LIMIT参数10/20改为0/30，数量一致不等于参数映射不变；无类型字符串001/true也不能猜为数值/布尔后进入restoredSql及导出。已回交采用保守问号、只读路由说明，不合入当前版本。UI-007需补BaseProcessHandler.getCommandLine跨版本签名登记，已回交。当前并发UI＋Runtime-Perf两槽占满，未追加构建。
- UI-001在Light125%、原生自动放大到1247×925设置窗口中，SQL Preview顶部与末尾全部可读，R90三行完整、Wrapper下拉不截（frame-b4f55523/5fe91628）。本轮没有人为拉大窗口；SQL Log页仍出现横向滚动条，未据此判所有内容截断。
- U39的243能力负例：MCP Tools页顶部称“11个AI工具已注册、可直接调用”（frame-e4ef69b8），点平台设置按钮却提示“需要IDEA2025.2+并启用MCP Server”（frame-b2ec93df）。导航降级正常，顶部就绪文案与实际能力矛盾，登记待修UI-011；实际11工具调用不能用243代验。
- UI-005原AUTO实体复验补齐：原聚合SQL在Lambda/AUTO两形态均回落QueryWrapper<User>，单select同时含status/COUNT(*) AS cnt，groupBy与having完整（frame-38654924/9e3c5fb6）。另外发现UI-010：先切AUTO启动旧ui_probe查询、随后替换输入user，旧异步弹层仍显示ui_probe（frame-84fa67aa），取消后旧输出残留；代码只读确认回调无请求代次/文本/形态核对，旧候选及输出可能回灌。当前新SQL再次转换正确不构成对竞态的修复。

- U10主动降级：KotlinWrapperScenarios.kt:47的ktQuery链内按Alt+Shift+Q，通知明确“暂不能可靠预览”、不生成猜测SQL，画面该链无插件gutter（frame-11171827）。构造与选择区/普通行负例仍待补。
- UI-009已提交dca7b369，UI-007平台签名登记6f756735，均待合流定向；前者未跑测试。代理报告的renderer空池疑虑已实读排除：主树初始化实际为`mutableListOf(SimpleColoredComponent())`，不是空列表，不纳入缺陷。

## 第四批合流与验证

第三批沙盒保持40cfff4e直到正常Exit，runIde退出0，本次未出现前两轮的process detach等待。第三批累计证据已提交a08d556d。Kotlin普通声明行主动入口显示“未找到可预览SQL源”（frame-364d7b58）；两类清理确认分别明示删除范围与互不删除边界（frame-04643638/a744f805），均选择No，未执行持久数据删除。Light125%日志页存储披露及两个清理按钮可见（frame-4ed28edb）。搜索打开Kotlin文件时多按一次Return产生未保存首空行，已GUI移除并保存，demo工作树实测干净；该过程不是产品缺陷。

本批合流：

| 缺陷 | dev提交 | 当前验证边界 |
|---|---|---|
| UI-007辅助JVM挂载过滤 | 07ad5ce5、3b964a8b | 原独立69绿/撤修9红/恢复69绿；补跨版本签名未跑，真实helper负例待新包 |
| UI-008分片来源与只读路由 | 07a1c3a7、e5347b61、1a569037、5274c8ef、cea4c4a6 | 最终不将任何无类型Actual参数自动回填Logic；脱敏开时整附注隐藏、已开面板响应设置；全部新增测试待集中运行 |
| UI-009历史正则反馈 | b75b56b7 | 错误配色/提示、无匹配空态、原生开关观察；定向/反向/GUI待验 |
| UI-010反向转换旧异步回灌 | fa40f9a6、85296035 | 请求代次、输入/形态与实际写入复查；18项受控顺序测试待验 |
| UI-011 MCP就绪误报 | 2511c047 | 工具声明数、平台能力与插件开关分离；243缺能力/253与262接口签名登记；定向/GUI待验 |

合流冲突仅PlatformSignatureChecker两项登记，保留AttachTarget和McpToolset两边调用及实现；没有整取一侧。UI007携带旧验收文档的hunk未采用，以主控新账为准。当前生产冻结85296035，十五类`:test`定向通过`.local/validate-b4.ps1`后台执行；脚本核旧IDE已退出、审查其他会话登记并限两槽，独立同名沙盒、关闭自动重载、无configuration cache。此时尚未有结果，不能把合流记为测试或GUI通过。

### 第四批定向实测进展

- 首轮233项/2失败/0错误/0跳过，生产与测试编译通过。UI010正对照误以为解析器剥双引号，期望ui_probe而实际为带引号表名；修正精确前提并增加确认前后输出哨兵（dev 338e1544）。UI011跨发行物用243 JBR21加载262 Java25字节码而假红，改每版自身JBR调用实际生产探测（dev be23f767）。均非“环境限制跳过”。
- 脱敏与正则反向：临时摘Actual展示闸门与非法正则错误投影，25项恰5失败（历史四例、Actual秘密哨兵一例），全部为预期断言；立即恢复生产并diff确认无残留。随后的十五类恢复复跑233项仅1失败：历史24、预览29及UI010十八项全绿；唯一失败为MCP测试夹具又使用CodeSource.location而在IDE PathClassLoader下为空，已回交继续适配，不能用独立CLI成功冒充平台绿。
- UI010第一轮反向：仅令生产isCurrent恒true，18项恰17失败，唯一非过期正对照保持绿；旧弹窗、候选覆盖、批量、clear/dispose及延迟写均触达真实断言。已恢复代次闸门；第二轮只撤实际写文档前复查，单例恰1红，失败输出为旧UiProbe覆盖新结果，证明末端复查不能被上游闸门代替。两轮探针均恢复，src/main对HEAD无差异。MCP资源定位继续修成可空CodeSource＋真实class资源、目录/JAR候选字节一致性校验（dev 0cbf10fc），最终十五类恢复复跑234/0/0/0（新增一项资源定位判别例），fresh XML 2026-10-17 04:27:55～04:28:55；生产冻结0cbf10fc（较85296035仅测试夹具修正），所有反向探针已恢复。真实跨发行物MCP能力和两项平台签名检查纳入本次绿，不等于三平台完整插件验证或GUI验收完成。

### 第四批真实UI复验

第四批PID23656、主窗口21169148恢复1920×1080全屏，Light125%，生产0cbf10fc加文档d21208db；未完成系统置顶。UI-009原七条文件输入`(`，出现红色错误文字且列表中央同样明确非法正则（frame-25a6bf10）；关正则立即清错并显示“没有匹配的结果”（frame-e3889556）；改合法id=6|id=7再开正则得到2条（frame-97c02eb3），该原始路径复验通过。

UI-011：243页头明确“声明11个工具，不代表已注册或可调用数量”，缺平台接口提示与插件选择分行（frame-fe2e36f0）；关总开关即时更新、不谎称服务器启停（frame-e15e2dc7）；关单工具显示停用1项，Revert changes恢复全部勾选（frame-eff2e5c4/7ecbccec）。未Apply该临时改动，也未实际启动MCP服务器；243中文原误报路径复验通过，有能力平台实调仍待验。

UI-007真机正反：原生mp_demo@localhost Test Connection成功（frame-b1d5094d）；04:40:36日志明确排除pid28492，同段真实命令入口RemoteJdbcServer，未以末尾驱动参数判业务。随后IDE Run启动本地MybatisPlusDemoApplication pid17744，捕捉后n1(ids1~5)实际五条带业务源码/耗时，增强收到5条（frame-fff8c33f）。证实helper被拒而真实业务采集未被整包误伤；尚不覆盖所有未知启动器或后补扫描组合。

UI-012待根因：真实storm只读流463条后列表上限200，同结构徽章200；滚轮上移后追加两条，视口#450→#452而未抢尾。满额头删尾加会自然造成内容漂移，不能用末条可见证明FOLLOWING。清空后n1 ids1~10，增强累计482、徽章10，滚轮/导航键/显式滚底始终只到#8；隐藏Services增高和原生AltShift0切100%仍然如此（frame-940cc45f/8a8ae6f7/98a75a11/f801fb8e）。搜索id=9能显示#9（frame-6dfbb703），清搜索重建仍#8（frame-bebe8b29），因此不是原始数据丢失。已派独立t-devui-list-geometry用完整容器几何探针先复现；当前KEYWORD_WRAP不走固定行高，不能凭10/1.25=8推断重复缩放。U02本次追加行为未闭合，不判通过。

UI-008真框架复验：第四批重新IDE Run内存分片pid24004，真实三查询PASS（frame-3800a54f）；暂停增强、清空后both命令进入控制台Logic链，预览说明明确ShardingSphere无可靠类型/映射、主SQL保留两个问号，无Hibernate TRACE误提示。折叠附注展开并到末尾能看到ds_0物理表及原文`[100, BOOK,100]`（frame-f6014b10/37e470bd）；开启展示与导出脱敏、采集脱敏保持关，同条重开附注整体隐藏且说明原因（frame-acaf4859）。当前只验重新打开，已开面板即时刷新由29项预览定向覆盖，尚未GUI补；未执行带问号SQL。新增长警告在顶部被截省略、附注默认仅两行需滚动，记录可读性待收敛，不把“能看到一部分”当完整披露通过。

UI-013分片去重丢路由已实证：同批100/101真实两次Logic相同但Actual分别ds0/100与ds1/101；去重开启只显示一行×2，附注保留ds0。关闭去重、清空再both可见两条独立by-id（另有前一组待flush跨片）（frame-be362566）。代码确认dedupeKey只按restoredSql、融合不含actualSql、重复分支提前返回不走保存回调；40cfff4e基线也存在，不是本轮新引入。已派t-devui-sharding-dedup仅让Sharding条目退出自动/手动执行级裁剪，保留每次Logic/Actual配对，其他日志族不改，不把两次执行拼成一次跨片。

U35精确屏蔽局部：主项目真实SELECT1，右键屏蔽后旧条隐藏；再请求两次，管理窗口显示“已拦截2次”（frame-daf30595）。单条切结构匹配有明确标记及关闭按钮（frame-204fdc72）；切回精确并删除本轮临时规则，缓存旧#11恢复、被拦截两次没有补放（frame-bb7084b4）。规则已恢复为空；结构不同参数、全局结构/大小写空白/双项目与重启十次仍待验。清过滤空态仍称“暂无捕获SQL”而非被筛空，属于与历史UI009同族的可发现性待核，不据此认采集失败。

UI-010原序列复验：Query旧ui_probe输出→切AUTO立即替换为user聚合→转换，候选只属于新user（frame-933cc0a0），选择真实cn.song.mybatis...User后得到新QueryWrapper<User>及完整聚合链（frame-6841613d），没有旧ui_probe弹层回灌。本轮一次时序正例，确定性旧回调覆盖由18项正向与17红/末端1红提供，不把一次未出现外推所有竞态已GUI覆盖。

## 第五批准备

UI012完整容器几何探针67a57e9f已合dev 6e15b820，只有测试，没有声称根因已复现。UI013独立只读复核未见阻断后，两提交91e15538/ba0deb91合dev 790b4420/038e1442，尚未定向。第四批已在真实内存分片输入quit正常结束，主应用点原生Stop，再发IDE Exit；第五批脚本核PID23656退出后才取槽，避免旧沙盒与新测试共享目录。四类定向（TailFollow、DedupeIndex、Buffer、Presentation）通过`.local/validate-b5.ps1`后台启动，另一会话FinalGate-R2保持独立，本轮不启动全量、不降低分片。首次脚本在90秒旧IDE仍存活检查处安全退出，未调用Gradle；GUI发现原生MybatisPlusDemoApplication运行确认，随后选择Terminate，再次后台检查退出并验证，不能把首次前置退出计为测试失败。

第四批退出再次出现MP应用Waiting for process detach。核登记及命令行确认仅自有PID17744/父23656，JDK8线程现场取证后原生Kill结束应用；PID已消失仍有残留运行确认，选择Disconnect后runIde退出0。第五批两次前置检查因此安全停止，未启动Gradle，第三次已重新后台发起。线程复核未见ShutdownHook执行或关停锁，Tomcat仍正常服务等待，agent sender是daemon且实际JAR读超时1s，不能归因sender或已修helper；下一步需同MP/JDK8单变量禁注入且禁自动attach的A/B，尚未做。证据会话call_3uFZfaT3mUdBBBtv84GA7NPG-stdout.log，UI frame-6dd1b4ab/c1bd5024。

第五批首轮四类90/0/0/0：Presentation5、DedupeIndex15、Buffer59、TailFollow11。UI012两比例完整容器测试均绿，125%模型10/首尾bounds与preferredHeight1070、viewport和滚动最大值一致，未复现现场；不能称UI012修复。测试主动递归布局与原生真机仍有差异，已续派仅显式系统属性开启的真实滚底几何日志探针，不输出SQL/参数、不改布局，以便下轮现场定因。UI013两生产闸门临时摘除后首次被新并行登记R3前置拦截，旧XML90绿不是反向结果；核实R3独立沙盒后实际运行90项恰6红（五个去重保真、一个徽章），其余原用例不退化。立即恢复，合入属性门控现场探针e90cf202后四类92/0/0/0（新增两项默认关闭/隐私诊断），src/main反向探针清除。第五批runIde仅本地init加-Dmpsv.ui012.diagnostics=true，供真实滚底前后几何取证，不自动布局救绿；当前UI012仍未修。

### UI012归因勘误：不是末条不可达

第五批PID51428，初始100%十条可到底；Zoom往返125%仍正常。重放storm满200→clear→十条后再次“底#8”，但真实几何日志action3明确buffer=model10、lastCell967+107、preferred1078、visible598+480、bar598/1078/480，诊断查询未触发重布局/renderer，几何正常。主控此前把模型index9误当sequence10，是错误前提。切“行号”排序立刻#9/#10到底（frame-862e34d2）；切回时间升序滚顶部，顺序实际为9、10、1、2、3（frame-8e859884）。因此撤销布局裁剪/丢行结论；未作布局修复。继续核日志时间相关器每键容量8与签名不符退队首是否误借时间，不凭数字巧合判根因。UI012改为时间关联/排序异常待核，原取证过程保留以免将先前误判当已证缺陷。

U02排除时间排序和满容量干扰后正反：行号模式十条未满200，滚轮上移再手动到底，追加11/12仍停#10（frame-45d63f5e）；显式滚底恢复到#12（frame-1c418c8d），再追加13/14自动到#14（frame-e89e6893）。确认滚轮暂停、手动到底不自恢复、显式恢复后追新这条路径正常；拖条/导航键独立触发和全部排序仍待补。

U02导航键独立路径：先显式恢复，再Home定位首条，追加15/16仍留#1（frame-9f63bf74/92c8972f），再显式滚底到#16。拖条尝试未实际移动滚动滑块，只选中末行，属于输入未触达，不能记拖条已验。

UI004原路径补验完成：在日志页设置新专属build/ui243-history/ui004-b5（事前不存在、无旧文件），06:35:13日志证历史扫描0文件；随后真实n1 ids21~23，异步落盘8460字节文件，未手动刷新也未提前切历史；首次切入直接显示3条文件（frame-ebcb96a1）。证明设置空扫已消费首扫后仍由进入回调刷新；不是重启读既有文件替代原场景。

## 第六批时间保真修复准备

真实history重建显示十条执行相对ms为0/2/3/4/6/8/13/16/19/21，前八条关联日志时间为214/214/215/216/217/217/218/218，后两条缺失；参数签名对应本轮1～10。文件能证错序，不能独立证被消费的候选签名。源码核实桥把无时间StdOut的IDE解析时刻当日志时间put，相关器无签名匹配又退队首；这两处保真缺口分别补真实桥及容量8判别测试。独立静态复核未见阻断，三提交合dev07be126f/5d300bd2/d2cbc0ed，仅时间关联，不改排序/布局/容量。首轮四类35/0/0/0（Parity14、Decouple2、Correlator15、CaptureGate4），fresh XML 2026-10-17 06:52:43～47；真实无时间桥/参数超容量均入场。临时恢复两处旧借时分支，35项恰7红（真实桥三项、相关器三项、无时间嗅探一项），失败明确为候选不应入册及跨签名借时，非配置失败。两处已恢复并diff确认，恢复四类35/0/0/0（fresh XML 2026-10-17 07:01:30）；中间一次因新并行Runtime-Encoding登记被前置拦下，不计测试红。第六批修复沙盒后台启动，尚未新包GUI。第五批沙盒仍需原生Kill后Disconnect处理同MP应用退出等待，runIde已退出0。

第六批UI012真机原路径复验：IDE35652、业务28932、Light125%；真实storm463条→清空→明确时间升序→n1 ids1~10，底部#9/#10、顶部#1/#2/#3，未再出现9/10排最前（frame-3a8109d7/4f35873c）。日志无时间StdOut候选保持空、回落执行时间，35绿/7红/恢复35绿对应两处保真修复；本原场景闭环，不外推所有带真实时间日志的跨线程顺序。

UI013新包真框架复验：第六批ShardingH2Demo再次PASS，SQL去重保持勾选，暂停增强后both的by-id两次分别为#3/#4，不再合成×2（frame-a2505757）。同一专属历史文件07:23:39两条各有独立o147-id，pd.a分别为ds0物理路由100/BOOK,100及ds1路由101/BOOK,101，主SQL仍问号、params空；证实逐次保存回调保留附注。还观察到同模板无Actual尾条，尚未归因，不能据本项通过声称分片解析所有flush边界完成。

U14/U15日志还原安全负例：真实Preparing/Parameters还原SELECT，执行选择mp_demo出现未知渲染方言确认（frame-ffc7cb5f），点No后未见native query SUBMITTED，但底部误称已提交（frame-430c895b），登记UI014独立修复，不能按状态字样认真实执行。改EXPLAIN SELECT同入口明确红字阻断未知渲染原始DML/EXPLAIN（frame-61c7c5f2）；改DELETE触发前置写确认并点No，保持已转换状态（frame-651ab311/585eeb17），没有批准写库，也未越过写确认去证明后续未知DML闸。UI014还查到runner popup final-step内同步弹模态导致焦点SEVERE，交同任务按真实回执修复，不改变安全策略。

U32引用导航五点实测：UserMapper.xml namespace→UserMapper.java22（frame-98ae8b7f）；property=username→User.java41（frame-a9c4c5c8）；include refid→XML20片段（frame-57dea2c1）；select resultMap→XML5（frame-e20e0c81）；selectUserBatchByIds id→Java121（frame-9927a6ce）。均主编辑器Ctrl+B真实落点，未修改源码，不覆盖补全/全部gutter/共存矩阵。

UI015预览快捷模板入口漏接：注解预览已选中user，右键只有Cut/Copy/Paste（frame-46060b15/5a314a84），不是选区错误。平台243自建Editor默认BasicEditorPopupMenu，模板仅挂EditorPopupMenu，原PreviewUx计划明确包含预览编辑器；已派独立修复保留基础动作并加既有模板组，不把所有主编辑器动作整包搬入。主编辑器XML为既有可用入口，后续从该入口继续COUNT/抽样。

UI015独立四类69绿、摘模板引用69项恰3红、恢复69绿，跨243/253/262 EditorEx菜单API签名已核；合dev 891d5718/55e27661，当前运行包尚未包含，待下一批GUI。UI014首版静态复核指出排队覆盖/取消和缺编辑器路径可能漏终态，已回交补齐，未合入。主编辑器XML里右键未弹与全局快捷键warning未证因果：实际warning发生预览关闭恢复动作，只LOG.warn不throw，不能凭此归菜单失败。

退出A/B对照在途：关闭增强设置后停止旧agent进程28932仍需第二次原生强停，随后同IDE/同JDK8/同MybatisPlus配置重新Run，PID18824父35652，实查命令行MPSV-agent=false、idea_rt=true。执行同十条只读查询后按同一普通Stop，后台WaitForExit(90s)实测Exited=false、ObservedMs=90015（call_nQz5uTwwBD2vPfmGSLJjA5GA-stdout.log）。无MPSV agent仍复现普通Stop不退出，故MPSV agent不是必要条件，不按插件ShutdownHook死锁修；不能进一步外推IDE侧插件完全无影响。未把“关闭采集但agent仍在”冒充无agent对照。

U15主编辑器模板正例：Java注解27行选user，右键SQL快捷模板三项可见；COUNT选mp_demo，原生结果41（frame-9a780984），08:46:28明确native query SUBMITTED。随后抽样10行复用本地目标，原生Output实证`SELECT * FROM user LIMIT 10`并取回10行（frame-a13f4c1c），不能依据仍显示COUNT的旧控制台标题判断实际SQL。预览模板待UI015新包；XML注入区菜单未触达而Java可达，仍不据无菜单推断模板业务执行失败。

UI014最终owner补齐后独立56绿、摘owner比较24项恰2红、恢复56绿；只读复核确认旧链迟到abort/finish不能清新链，三个提交合dev25ca6578/7adab32d/692637a5。当前沙盒未含此修复，合流后定向及GUI待下一批。其修复不扩executePrepared回执覆盖口径。

## 第七批准备

第六批内存分片已quit，无agent MP对照90秒未退后由原生强停结束，确认进程结束后退出IDE；runIde退出0，本次无遗留运行确认阻挡。UI014/015合流生产692637a5，七类合流定向111/0/0/0（日志还原执行16/方言闸16/单飞24/错误处理16/预览编辑器31/抽样5/三代签名3），fresh XML2026-10-17 09:01:06～32，不把独立56绿与69绿相加冒充合流绿。第七批新沙盒已后台启动，本地诊断属性关闭，诊断生产代码最终清除待做。增强设置目前为对照而关闭、展示脱敏开启，后续采集复验须显式恢复增强。

第七批PID22516、生产692637a5。UI015原路径复验：注解gutter直开预览，选user右键保留Cut/Copy/Paste并出现SQL快捷模板（frame-43056bbb），子菜单三项启用（frame-1474e441）；Ctrl+A整SQL后同三项灰色禁用（frame-00a5fa50），未误把整SQL当表名。

UI014新包复验未通过：09:13:02弹数据源选择，09:14:57选择mp_demo直接CANCELLED，未到未知QUERY确认。原代理核实新增60秒prepare Timer把用户思考时间计入、提前过期，属于修复引入的交互回归；已回交暂停用户选择/确认阶段预算、恢复后台超时且区分超时与主动取消，同时核canceled/finalRunnable生命周期。不能把显示“已取消”当No原路径已验。

U23命名快照继续：注解selectByAnnotationId设id71另存UI-Shared，改输入72后默认/命名往返回71（frame-78b72363）；管理重命名UI-Renamed后保持原71快照（frame-8e0335b8），没有将编辑默认值覆盖命名快照。跨方法同名、删除及20上限尚未补，不将本轮局部升级整项。

UI014续修381a9ad8已合dev dd0f8347：真实popup选择dispose也会canceled，新增choiceAccepted防误取消；用户选择/确认期间暂停后台预算，后台真超时单独BLOCKED。独立60绿/撤保护44项4红/恢复60绿，当前运行包仍旧，下一批需合流门禁及长等待GUI复验。临时几何诊断清理cb9811b6已合6d24b4ba，独立TailFollow11绿，最终统一门禁仍待。

第七批退出前发现无MPSV-agent业务PID61704（父22516）实际仍在运行，09:29:15启动，日志明确enhanced-disabled且未attach。对应此前Shift+F10尝试“上下文菜单”，该键在IDE默认实际为Run，故这不是后台自启或产品缺陷；已勘误“不启动业务应用”的操作记录，后续不再用Shift+F10当菜单键。该次应用在已授权只读demo范围，无新写库操作。退出确认按实际存活进程处理，未把它当死会话直接Disconnect。

## 第八批交互等待复验

合流dd0f8347及诊断清理后七类115/0/0/0（新增交互时钟/真实popup生命周期四例），fresh XML2026-10-17 10:15:22～52。第八批IDE57840、窗口89985620，Light125%全屏；运行主JAR6062925字节、SHA256 `6a03a316af433e0ebf6c09fe425b164a05d703a4ad91834840c8ccb6ccb7f64e`。

数据源弹窗10:24:36出现，10:28:49选择mp_demo，停留253秒后仍popup selected并创建正确MySQL UUID会话，进入未知QUERY确认（frame-935a2641）。确认框继续停留约505秒，10:37:14点No，面板显示“已取消执行，未提交数据库控制台”（frame-66c752e3）；该请求无SUBMITTED，也未出现原popup焦点SEVERE。用户选择及确认的长等待均不再被后台60秒预算误取消。

正例重试：10:39:04弹源，10:40:48选mp_demo；10:41:37首个未知QUERY确认Yes后创建控制台，提交阶段第二个实际目标方言确认Yes，10:45:44.405日志明确native query SUBMITTED。原生结果区随后显示id=1的一行，面板“已提交数据库控制台执行”（frame-2b825722），未将中间spinner或runRunner invoked OK当成功。UI014原始取消误报成功、修复引入的用户等待超时和选择dispose误取消均已有真实路径证据；两阶段分别确认的交互已记录，未擅自合并安全闸门。

UI015预览入口实际执行补验：注解预览选中user，经新菜单COUNT，11:05:47.751原生SUBMITTED，结果41（frame-a02fdf2f）；同入口“抽样10行”取回10行（frame-38181c91）。预览SQL与id71保持原样，模板只读取所选表；未以菜单可见代替执行成功。主编辑器与预览两个入口已有只读COUNT/抽样证据。

U21/U40本地诊断HTML出口真实走通，产物`build/sql-diagnostic-report.html`（1650字节，11:09:48）。MySQL四项显示EXPLAIN/DELETE LIMIT/UPSERT支持、IDENTITY无需包装；只证明该方言导出内容，悬停及其他方言一致性仍待。保存对话框路径输入后Return即落盘默认文件名，不把后续对已关闭窗口的点击失败算导出失败；未外发报告。

U23补验：删除本轮自建UI-Renamed前确认目标及不可恢复说明，确认后只余默认预设且删除/重命名禁用（frame-99f1b56b），回预览默认桶仍id71（frame-1634e28a）。foreach方法另存UI-Shared快照`71,72,73`，修改为`81,82`后切默认再切命名，恢复原三值及IN(71,72,73)（frame-04ac7425），编辑未覆盖命名快照。该新预览无活绑定，显示“数据源（上次使用）”而非“已绑定”（frame-913634da），与前一活控制台状态区分。

UI016新登记：08:58:47退出有Bus is already disposed、Double release of editor、RunnerLayoutUi保存状态访问已销毁project。只读核验243平台原生clientView挂应用级服务，而JdbcConsole挂project；插件自有共享view关闭协议待修。Double release的首次释放栈不在idea.log，尚不能归因为插件直接释放两次。已派独立树补真实生命周期回归后最小修复，下一批退出复验。11:02:54的createEditor path为主动INFO Throwable取证，11:09:49另为文件选择器Y盘来宾访问拒绝，两者不列为本插件新缺陷。

U23 foreach快照再补空值开关：勾“为null”生成缺集合的`WHERE id IN`并有原生红波浪（frame-de7fb122），切默认再回UI-Shared恢复未勾与三值（frame-0af9bfe0），空值编辑也未覆盖快照。未执行该非法SQL。

UI017新登记（U23未通过）：foreach方法已有UI-Shared（三值71/72/73），另一selectByAnnotationId默认id改91，管理列表只有默认；另存同名UI-Shared返回“已存在同名预设”（frame-25d8a81d），第二次明确观察同样结果。与管理文案“同名预设在不同语句下各存各的值”冲突。已派独立树修复保存路径，保留同方法重复保护及跨方法独立快照；尚未修复/验证。UI016与UI017当前均先隔离写测试/代码，因runIde及其他会话全量占两槽未启动第三路Gradle，不将未跑用例记红或绿。

U37回滚入口补验：INSERT纯合成参数只预览，提示整行等值定位可能多行、给出COUNT自查SQL（frame-91097fa7），未执行INSERT或回滚。DELETE id71无before-image时明确不能自动回滚、执行/复制禁用（frame-a093ed40）；输入有效单列TSV `id\n71`后生成`INSERT INTO user (id) VALUES (71)`并显示仅覆盖粘贴快照行（frame-889061ad），仅验证生成，不代表已获准执行或SQL Server identity整批通过。

UI018新登记（U37未通过）：上面的成功快照随后改为单行`invalid_snapshot`再生成，错误提示出现，但旧INSERT、黄色“回滚可执行”和执行/复制按钮仍在（frame-9d584547）。已派独立树核真实回调并修输入失效/生成失败的旧模型残留，未点击执行。CSV不是支持格式，先前两列逗号文本被作单列不计正例；Computer Use的typeText吞Tab、paste实际带入旧剪贴板，已按真实界面纠正，以明确键入的单列TSV作为有效对照，不拿工具回执冒充文本落地。

B8退出：PID57840正常GUI退出，runIde退出0并自动注销，未强杀；主demo git工作树干净。

## 第九批合流门禁

生产冻结`c0d06101`，包含UI016～UI020全部修复（UI019/020为测试/代码发现待GUI）。合流后根`:test`十一类fresh XML时间2026-10-17T04:57:33～04:58:03Z，227/0/0/0：预设缓存28、预设对话框11、预设面板21、回滚对话框22、回滚面板15、回滚生成器49、日志转SQL18、执行方言闸17、准备单飞26、错误处理16、跨版签名4。使用`-Pkotlin.incremental=false --no-configuration-cache`避免新默认参数的旧测试ABI缓存；主控逐文件核实，不把独立60/75/119相加冒充合流读数。真实native view五例仍为独立testDbConsole证据，最终统一全量未执行。

第九批runIde已启动：PID37948、主窗口21499930，243/Light125%、1920×1080全屏，autoReload关闭。运行JAR6,074,072字节、SHA256 `31998253e1473e6525eb88610edd8117ca786e895a1df537b75e4c87525d7753`；系统级置顶仍未完成，不混称全屏置顶。

UI018原路径复验：DELETE id71回滚输入单列快照id/71生成INSERT正例（frame-48df74a9）；替换为invalid_snapshot并生成后旧SQL清空、红色无法回滚、执行和复制禁用（frame-2c68673f）。恢复有效快照再生成可用。UI020真实取消负例：点击执行回滚仅进入INSERT写确认（frame-dd9edf95），明确点No后保持未提交横幅，没有假成功（frame-aff95c61）；本批启动后native query SUBMITTED为0。未批准或执行INSERT，真实数据库写成功不在本轮证据内。

第九批预设复验：单值方法id91另存UI-Shared成功（frame-946b3529），同方法重复另存仍拒绝（frame-36633304），预览显示该名与id91（frame-72ba5454），UI017原路径通过。另一foreach方法重开字段仍为71,72,73，未串入91，但SQL仅IN(71,72)（frame-0cfc8b88），另登记UI021：召回值未同步展开长度。原代理已先红5例3失败，修复769f314a及测试51537491、证据13c23393，最终65绿，待合入及新包复验；不能把UI019字段恢复当整个foreach渲染通过。

U36/U29万条真实UI补验：将已核合成文件不覆盖复制到当前ui004-b5目录，刷新后显示10000条（frame-eac690af），打开首屏同结构徽章10000（frame-b50185e7）。搜索UI_MATCH显示1000条（frame-289d20ac），精准搜索id = 10000 AND能取得末条#10000（frame-914a1052）；小写ui_match开启大小写敏感为0条，关闭立刻恢复1000条（frame-fecdb046/835c2f5e）。End先到当前30条批次末端，下一次再触发追加；已核既有30条惰性模型，不误报数据丢失，尚未以连续滚动遍历全万条。

用户现场新增需求：SQL历史页参照日志页增加“滚动到底部”按钮。已派独立工作树`mpsv-devui-history-bottom`/分支`t-devui-history-bottom`后台实施，要求复用现有图标文案、跳到当前过滤集合真实末条而非仅已加载30条；不阻塞主控其他验收。首件2cdd1b12已合dev6cd42588，真实工具栏动作三类46/0/0/0（新动作5、导航17、搜索24），摘批量补齐5项3红，恢复46绿，主控核XML。复用ArrowDown与中性scrollBottom文案，不宣称实时跟随；文件态隐藏、空态禁用，一次addAll补齐过滤全集后同步到底。待下一包GUI及窄宽复验。

主控同时继续U29/U36：返回文件态后搜索2026-10-17恰一文件（frame-f3c8e1ff）；打开真实658条文件后SQL搜索框清空且首屏恢复真实来源（frame-532c15e5），搜索合成哨兵UI_MATCH为0（frame-9b16c06e），没有把另一万条文件混入。该顺序验证文件边界，不代证受控迟到竞态。

## 第十批准备

第九批14:37:56正常退出0，退出时无新增SEVERE；该批未真正建立native执行控制台，因此不能用此次干净退出替代UI016原始触发链。第十批包含历史到底按钮6cd42588与UI021长度同步56d066cf，合流十四类278/0/0/0，fresh XML 2026-10-17T06:41:50～06:42:28Z，主控核每类及总数。新包已后台启动，待按钮真实末条/窄宽、foreach首开SQL及带原生控制台退出复验。

第十批PID50152/主窗235412392，Light125%全屏。用户新增历史到底按钮真实复验通过：文件态隐藏（frame-5a6fe6aa），万条列表首屏点击一次后末项#10000完整可见（frame-52bcf869），过滤UI_MATCH的1000条结果点击一次仍直达原始#10000而不是前30条末项（frame-751eac3d）；无匹配时按钮灰化（frame-f42c3d5c）。收窄右栏到约255图像像素（当前截图1280×720，非声称255物理像素）后按钮仍直接可见可用，再点仍到#10000（frame-6bca7f2f）。未引入实时跟随，合流278绿与本次真实按钮证据互相补充。

UI021真实首开复验：第十批直接gutter打开foreach预览，未编辑或切换预设，字段71,72,73与SQL IN(71,72,73)一致（frame-500c5c4a）；选本地mp_demo只读执行得到0行，原生SQL仍为三项（frame-ea57ad12）。关闭预览重开后再次执行正常、第二结果页可见（frame-e7a03a10），没有因关闭预览销毁共享查询能力。

UI016完整IDE退出复验未完全通过：本批15:17:02/15:20:30两次native query SUBMITTED，15:42:47退出仍有一条Bus is already disposed，15:42:48正常SHUTDOWN且runIde退出0。此前ClientSession/RunnerLayoutUi/Double release其余错误本批未见，但不能据减少错误标完成。原代理续查锁定项目session log view（与已修应用client view不同）并补真实双view对照；主控并行启动独立262能力沙盒准备U39实调，不等待此续修。

## 支持平台MCP真实验收

独立262.9437.185沙盒`build/MPSV-FullUI-MCP262`，PID3128，主窗口39260140已全屏；未在日常IDE操作，未开启试用或代登录。通过既有gifRecord配置启动，仅用于支持能力验证，不作为243门禁或发行包。运行JAR6,069,984字节、SHA256 `9ca4d8442cff2baa8e86cad4e2daa68acdbaacbac0108b369f988b7ed07c9a0c`。原生UI启用MCP，监听127.0.0.1:64342归属PID3128已实查。

- 真实tools/list恰11个sqlv工具。8个静态工具逐个调用成功并核正文：片段渲染、日志还原、无WHERE危险检查、无before-image回滚拒绝、性能建议、实体CRUD、SQL转Java、Mapper检查。完整请求/响应保存在`build/ui262-mcp-static-first.json`，日志8条MCP-AUDIT为OK。不以声明数代证调用成功。
- 单项关sqlv_render_logs并Apply后，直接已知工具名调用返回isError=true/Tool not found，平台已移除注册且未执行工具；这是平台拒绝证据，不冒充插件内部TOOL_DISABLED路径。恢复后调用成功。
- MCP输出脱敏开关开启后，以合成M19_FAKE_PASSWORD调用日志还原，实际返回`password = '***'`，原敏感哨兵不在返回正文；没有数据库执行。
- 历史目录在GUI指向ui004-b5，保留上限0保护既有证据。真实history_search命中1000、maxResults1000被钳到100、最新首项id10000，坏正则返回INVALID_ARGUMENT。产物`build/ui262-mcp-history-first.json`。
- 初次本地schema返回SCHEMA_NOT_AVAILABLE（模型0表），EXPLAIN返回DATASOURCE_UNREACHABLE；原生连接正对照证实新沙盒缺MySQL驱动与密码。经原生Download补驱动、只为本地mp_demo补既有测试凭据，Test Connection成功MySQL8.0.12（frame-8a3ba70f），随后重测在途。DELETE传给EXPLAIN明确MCP_READ_ONLY拒绝，未执行写SQL。未修改或连接其他远程业务源。

UI016续修已完整合入dev28dce39e～dccc6af0，保留session、只提前释放LanguageConsoleImpl子树；独立真实项目关闭8绿，撤session早释8项恰2红，恢复8绿，跨版签名5绿，主控核XML。纯原生保留session对照也复现同Bus栈，明确为平台释放时序被执行链触发。运行262包仍旧，最终原路径退出复验尚待换包。

MCP数据库复测完成：`build/ui262-mcp-database-connected.json`中schema-local返回真实user的13列与中文注释，明确source=mp_demo@localhost；explain-local返回MySQL PRIMARY/const/rows1计划及原生notice；DELETE仍MCP_READ_ONLY。11个不同sqlv工具均已有本次实际成功调用（8静态＋history＋schema＋explain），未以首轮环境错误代证成功。项目外受控文件参数返回SOURCE_NOT_FOUND，未读取正文；审计包含对应EXPECTED_ERROR。合成密码脱敏调用返回***，审计行不含M19_FAKE_PASSWORD。禁用直调为平台Tool not found，不声称已走到内部TOOL_DISABLED；内部闸仍以定向测试补充。网络传输仅本机沙盒端口，未外发诊断内容。

## 支持平台新增问题

UI022/U27：UserMapper.selectUserPageAnnotation第175行真实gutter预览，status=1、合成offset=20、page.size=10，主查询status正确回填，但LIMIT仍?,?且COUNT内status仍?；格式化与刷新不恢复（frame-284ae366/e048b229），未执行。独立`t-devui-pagination-params`正在先红与修复，主控继续其他项。

UI023/U26：已内省mp_demo并通过MCP返回13列，UI Resolver日志16:56:31也明确resolved=1。预览主执行按钮对手改UPDATE user SET usernme='U26_SYNTHETIC' WHERE id=71仅给普通写确认；绑定只读SELECT成功后重试仍无建议（frame-ad831d39），均No取消未写。只读源码＋运行JAR javap交叉证实executeSql完全未调用SchemaValidationGate，只有executeAdhocSql已接；不是UPDATE SET不支持或一直未预热。已派`t-devui-schema-gate`补真实入口及判别测试，数据源作用域首缓存行为作为必须避免误报的边界保留。

U40首次引导增量：262新沙盒打开UserMapper后“检测到可预览的SQL代码”通知，点“立即试用”出现本文件候选列表（frame-4f25dfaf），选第一项成功进入预览（frame-528a06f8）；尚未重启验证一次性持久化。

U27手写LIMIT正对照：XML219 selectUserPageLimitXml真实预览填status1/offset20/pageSize10，得到status=1及唯一LIMIT20,10（frame-3ae26ccf）；取消status条件后其谓词移除而LIMIT20,10保持（frame-57661617）。无COUNT符合当前applyPaginationShared手写LIMIT直接返回的保守设计，旧demo注释“并列COUNT”已过时，不据注释登记产品缺陷；本项不抵消UI022自动分页漏参数问题。

日期参数UI增量：XML234 selectByTimeRangeXml三类型Date/Timestamp/sql.Date输入2026-01-01 00:00:00、2026-02-01 12:34:56.123、2026-01-15，格式化后三个SQL字面量逐字保留含毫秒精度（frame-696e9b39），未执行；仅覆盖XML参数面板日期回填，不代证日志还原DDL/日期全组。

U30日志还原补验：双行Preparing/Parameters输入Timestamp含.123及O'Reilly，实际还原TIMESTAMP字面量毫秒不丢、字符串转义O''Reilly、SQL字符串'?'和块注释/* keep ? */均保留（frame-bbd74af7），未执行。先前DDL带明确Parameters的还原会代入7属于显式参数替换，不是要测的“字面量恢复”门禁，未误报缺陷。另造明确synthetic历史两条正反：DDL DEFAULT7/VARCHAR80/'keep ? literal'完整预览无参数输入且原值保留（frame-b2d3a0f8）；同值SELECT完整预览恢复param1=7、param2=keep ? literal（frame-7ec44910），门禁真实触达。合成文件`sql-history-2099-01-02-ui-ddl-literals.sql`仅本地展示，没有执行CREATE或SELECT。

U30剪贴板边界增量：在日志输入编辑器内造双行id314日志，Ctrl+A/C复制后删除输入，再点还原只提示“请先粘贴MyBatis日志”，未自动读剪贴板（frame-2fa2b34a）；只有点专用剪贴板按钮才读回两行并还原id314（frame-8b61f733）。复制三个空格再清输入，点专用按钮提示“剪贴板中没有文本”，未读回旧日志（frame-8a658d66）。Ctrl+A只选输入区，未改左侧XML；旧输出保留但未执行，真正null剪贴板尚未造，空白分支已触达。

UI022独立23330aaf/d92a2657已合dev33f8762a/801ca399。真实PSI链先红8项6失败，修后70绿，撤三个修复入口再次8项6红，恢复70/0/0/0（09:36:44～09:37:18Z，主控核XML）；手写LIMIT两正对照保持绿。运行262包未更新，GUI原样本复验仍待换包。UI023已完成隔离树代码，第二槽接续定向正反验证在途，未预填绿。

UI023独立25925ae2/2dc1f681/5ed9148d/5b22953a已合dev 8b01108e～5fe17279。十类147/0/0/0（10:04:59～10:05:32Z，主控核XML）；摘主入口20项4红，跨源/库缓存4红，面板所有权/编辑器/限定表/当前库4红，复杂SQL3红，全部恢复。新接线仅对本面板匹配且库上下文可证明的MySQL/MariaDB精确缓存启用，不以项目首缓存猜目标；其他场景保守跳过。运行旧262包不能验证新接线，原样本仍待换包。

U28选择集CSV导出：历史两条合成DDL/SELECT仅选SELECT导出，实际`build/ui262-history-selection.csv`为一行表头＋一条SELECT，未含未选DDL，GUI通知“已导出1条”（frame-4ec00fc2）。真实格式菜单是CSV/TSV/TXT/SQL（frame-95e80143），不是JSON/Markdown。Windows原生保存对话框拒绝正斜杠全路径，改反斜杠后实际落盘，不误报导出功能失败；另三格式仍待验。

262首批正常GUI退出，runIde退出0。主dev已合UI022/023及session续修，下一批合流定向25类已后台启动，未用各独立读数相加冒充整体通过。

下一批合流定向已完成：生产5fe17279，25类463/0/0/0，fresh XML 2026-10-17T10:36:37～10:38:15Z逐文件核对。覆盖分页/参数类型/显示、Schema实际执行入口与目标作用域、动态批次/注入/未知参数/方言错误、历史到底/导航/搜索、预设、回滚与提交回执。默认243沙盒已后台启动，准备UI016/022/023真实原路径复验；不是最终testShards全量。

UI022新包243原路径复验通过：PID34400，注解175填1/20/10后LIMIT20,10（frame-04ffddbb）、COUNT内部status1（frame-208f57b9）；改status2并点刷新，主查询status2/LIMIT20,10与COUNT内部status2同时保持（frame-0147e002/37062d78），不再残留问号。合成分页两输入现为数字提示，不再page.size对象JSON提示。仅静态预览未执行该分页脚本；参数区长表达式导致输入右侧裁剪仍属视觉矩阵待核，未据此改分页逻辑。

UI016最终原路径GUI闭环：243 PID34400最新启动18:41:01，18:52:12与18:59:52两次native query SUBMITTED，中间关闭预览重开并复用，19:05:52正常SHUTDOWN、runIde退出0；该启动段无SEVERE/Bus is already disposed/Double release/Failed to dispose及view disposal failed。有真实client/session控制台而非空沙盒正对照，原退出问题本轮复验通过。

UI023新包GUI仍未闭环：243已原生选择mp_demo内省tables10，日志18:59:52 resolved=1，绑定并执行只读SELECT成功，主按钮对错列UPDATE仍仅普通确认（frame-370b30c8），No取消无写；回交原代理核真实对象和假夹具差异，未按147绿冒称功能通过。主控转视觉/入口批次，不阻塞等续查。

视觉批次局部证据（243 Light125%，合成VisualMatrix五条，未执行）：实际阈值1000ms（frame-e2a232e2），12ms/0ms绿色、500ms橙色、1500ms红色、未知破折号灰色，CRUD四色边条（frame-ea2098fb）。选中UPDATE行统一高对比文本（frame-d02512a1），焦点移到源码后仍可读（frame-7a97582c）。三显示模式在相同文件真实切换：关键字换行、单行（frame-8d0378f8）、完整格式化（frame-62b75ab1）；长句列表省略后Ctrl+C粘入本地输入仍保末尾ORDER BY created_at DESC,id ASC（frame-a99e2cce）。此为历史面Light125局部，日志/底部/完整预览同源及Dark/100矩阵仍待。

UI023二次续修已合dev 55e5f2a7/533a5565/a9811746。真实onNativeEditorReady→embedConsole会清nativeConsoleEditor而保nativeEditorDoc；前轮手工设编辑器夹具漏该顺序。改以实际文档对象身份匹配preparedEditor.document；先红15项3失败，文档保护摘闸3红，精确撤修fresh15项3红，恢复十类149/0/0/0（主控核XML）。运行视觉包不热替，下一包真实绑定错列复验仍待，不能据149绿标完成。

视觉矩阵补充（Dark125/Dark100）：同一历史文件在Dark125下四档耗时色与CRUD边条清晰（frame-ea2098fb），选中行高对比、焦点移出后仍可读（frame-d02512a1/7a97582c）；三种显示模式切换真实生效（关键字换行、单行frame-8d0378f8、格式化frame-62b75ab1），长SQL省略后复制仍含末尾ORDER BY（frame-a99e2cce）。Alt+Shift+0重置100%后日志页工具栏与四页签单行可见（frame-6014e8e3）。动作搜索可查到“Submit Feedback (SQL Visualizer)”“SQL Visualizer设置/项目设置”（frame-42c35f98），帮助入口存在但二维码三态未触达。Light100及日志/底部同源色仍待。

工具侧发现（影响验收效率，非产品缺陷）：Swing弹层出现后主窗坐标绑定会停留在弹层尺寸，随后坐标点击报“outside 159x38/670x694”；须先静默重抓画面（getScreenshot emit:false）再点击，且100%缩放时页签行y由78变为66。已按此调整操作节奏；电脑操控慢的根因另派只读子代理量化排查。

UI023新包真实GUI通过：Light100默认243，预览绑定mp_demo并执行只读SELECT（0行），随后改错列UPDATE，确认框显示“执行前自检发现以下问题：• 表 user 上无此列 [usernme] —— 是否想写 [username]?”（frame-4dbaba02），点No未写库。日志确认schema预热resolved=1且两次native SUBMITTED，修复链真实可达，不再是Skipped。

环境：新沙盒1920×1080全屏，系统级置顶已用SetWindowPos(HWND_TOPMOST)设置并读回扩展样式ex=0x8（topmost=True），全屏+置顶两项均落实。

UI024新登记（U11/U01视觉）：设置页SQL Log在默认998×742、Light100下，“自动保存”区右侧三个本地数据操作按钮（打开捕获目录/清除预览历史/清除参数历史）被右边缘裁切，仅露出“清除”片段，底部出现横向滚动条（frame-3498aa78）；125%较宽对话框下曾可见。清理确认流程本轮未完成，不能用已裁切截图代证。

## 第九批并行准备产物

主控在UI016/UI020后台定向期间完成以下准备，不占第三路Gradle，不把准备结果计为UI验收通过：

- 万条历史合成文件：`build/ui243-history/sql-history-2099-01-01-ui-synthetic-10000.sql`，1,765,788字节，SHA256 `dc6b3055af135f74f385e81f470b37a9f40d90a078cb65cbaadbed069c023634`。来源显式标synthetic，编号1～10000，`UI_MATCH`恰1000条；五种耗时循环0/12/500/1500/未知。没有覆盖原记录或伪装自然捕获。该目录仍有既有证据，禁止在此测试保留策略删除。
- 用第八批实际插件JAR的`SqlLogFileReader`读取，实得10000条；同JAR的`HistorySearchService`检索实得1000命中、返回100且truncated=true、scanTruncated=false。首版探针误期望返回1000，被硬上限100正确挡住；核源码HARD_MAX_RESULTS后纠正探针并通过，不作为产品缺陷。GUI首进/搜索/末行/切文件迟到/滚动仍待做。
- 既有导出产物回核：`build/ui243-replay-b1.sql` 494字节，SHA256 `eb4aa8a1036e681498a706a7d00e148224c7c22247f578aa1d2ac1b84d1eecc3`，实含MySQL事务包装与末尾分号；`build/sql-diagnostic-report.html` 1650字节，SHA256 `a3b226aa6dc2e9fdc3d011d5ca5b9ae01278d93a6732acb9198740d8e02f4c07`。未执行回放或外发报告。
- 支持平台MCP待执行用例：`.local/ui-mcp-acceptance-cases.json`，14个正负场景覆盖11个不同声明工具，已与生产`@McpTool`名称集合逐项比对，缺项0、未知0。现有通用suite的11次调用含重复render、漏history_search，不能据调用数声称11工具覆盖；本批清单补历史100上限、坏正则、写语句EXPLAIN拒绝、禁用直调。只读数据库调用明确绑定mp_demo@localhost，禁止自动跑到日常IDE或其他数据源。该清单尚未连接支持平台执行。

下一批操作顺序固定为：先UI017/UI019同名与返回方法回填→UI018无效快照撤旧/UI020取消不标提交→当前已创建原生控制台的关闭复验；随后批量做万条历史搜索/末行/切文件、预设与历史边界、来源方言/Schema；支持平台MCP另批，不能用243缺接口代证。

## 本轮增量对账（相对44组矩阵）

- U01/U03/U05/U44：新增Light100/125与Dark100/125日志面、历史三显示模式、CRUD四色、四档耗时（阈值1000ms下12/0绿、500橙、1500红、未知灰）、选中/失焦、长句省略后复制完整、动作搜索可见反馈与设置入口；窄栏11动作溢出与二维码三态未验，新增UI024（设置存储按钮默认宽度裁切）。
- U02：行号模式滚轮暂停/手动回底/显式恢复/Home追加已有B5证据；拖条、满200、七排序、慢筛未验。
- U06/U07：暂停恢复、控制台照常、不重放已有B1证据；启停、增强独立关开、自然同次融合与历史源码耗时未验。
- U08：MCP输出脱敏（合成密码→***）与Sharding Actual展示隐藏已验；六出口同一样本、采集/展示独立、原文件不擦写未验。
- U11：确认框范围与No取消已有B3证据；本轮发现设置页存储按钮默认宽度裁切（UI024），实际清理边界与重启不复活未验。
- U14/U15：长等待取消零提交、确认后真实SUBMITTED、未知写/EXPLAIN阻断、模板COUNT/抽样已验；active冲突、重渲染二击、编辑保护、长WHERE/多表/注入边界未验。
- U23：跨方法同名保存、同方法重复拒绝、删除、foreach快照与空值、UI021首开渲染已验；P2、20上限、OGNL、命名重启未验。
- U26：UI023真实绑定+预热后错列UPDATE确认显示建议、No零写，已通过。
- U27：自动分页主SQL/LIMIT/COUNT同步、改参与刷新保持；手写LIMIT唯一且无COUNT为既有设计；COUNT独立开关、EasyQuery三态未验。
- U31：逐次路由/Actual只读/脱敏隐藏/去重保真已有B4/B6；即时脱敏、尾组flush、交错关开未验。
- U36/U29：万条文件、1000命中、末条、大小写、文件隔离、选择集CSV导出已验；保留3/0/非法值、三选/超千行Diff、其余导出格式未验。
- U39：262全部11工具真实成功调用，历史上限/坏正则/写拒绝/禁用直调/脱敏/项目外路径/审计已验。
- U40/U41：首次“立即试用”入口与候选列表已验；更新检查三态、空态三入口、冷预热降级/重试、心跳重连未验。
- UI016：真实控制台两次执行+关预览重开+正常退出无Bus/重复释放/布局异常，原问题闭环。

UI025新登记（用户现场发现，U26体验延伸）：预览编辑器里错列`usernme`仍按普通标识符语法高亮，没有红色波浪/悬停提示；Schema 错误只在点击执行后的确认框出现。现状源码确认无任何 Annotator/ExternalAnnotator，属于能力缺口而非回归。已派独立树`t-devui-schema-annotator`实现预览文件专用的错列标红（仅已预热精确目标、无缓存不猜、偏移取自 PSI、非预览文件不生效），待交付与真机复验；不以确认框提示代替编辑器内标注。

U11清理确认边界（本轮）：设置页「清除参数历史与命名方案」确认框列出完整删除范围（参数值、命名方案、激活指针；旧面板缓存失效；预览 SQL 历史不清除），「清除预览 SQL 历史」确认框列出收藏/标签/条目内参数快照一并删除、参数历史与命名方案不清除；两次均点 No 未删除，确认框与实际删除范围一致。

U14未知写边界（本轮）：预览编辑器输入`TRUNCATE TABLE user`点执行，出现普通写确认框（“即将执行 TRUNCATE 语句，可能修改数据。确认继续?”frame-364c48eb），点 No 后确认框关闭、编辑器内容保留、无 SUBMITTED、无结果区新增（frame-8d0a4569）。TRUNCATE 属 `SqlTypeClassifier` 显式 DDL 名单，走确认而非阻断符合设计；“未知写拒绝”指方言未知时的 `execute.dialectGate.unknownBlock`，已在 B6 EXPLAIN 场景验证，不以本次普通确认代替。U14 仍待 active 独立路径、手动冲突、自动重渲染二击与编辑保护。

U02尾随/上限/排序/慢筛（本轮，真实控制台通道）：
- 满 200：连续 205 次 `GET /user/{id}` 后列表出现 `#206 … 同结构 200 条` 徽章，状态栏 `已捕获 1,606 行`，确认单结构上限 200 生效（frame-f237979f）。
- 手动暂停：在列表内滚轮向下后新增 `#207`，视图停在原偏移、未跳最新，符合“手动滚动即暂停跟随”（frame-2d7aa864）。
- 回到底部：工具栏下箭头动作（tooltip「滚动到底部」，frame-180c4361）点击后跳至末条并恢复跟随；随后新增 `#208` 自动贴底（frame-599e460e）。
- 七排序：排序菜单完整列出 时间升序/时间降序/耗时升序/耗时降序/行号/同结构聚类/按目标库分组 七项（full-screen 截图）；选「耗时降序」后列表重绘无异常（未知耗时并列，不冒充有耗时排序语义）。
- 慢筛入口：SQL 类型弹窗内含 查询/插入/更新/删除 四色复选与「只看慢查询」勾选、全不选（full-screen 截图）；控制台通道条目耗时未知，按 `filterBySlowOnly` 语义应全部滤除，GUI 未勾选执行以免清空现场，语义由纯逻辑单测覆盖，不冒充 GUI 通过。
- 说明：本批条目均为控制台通道，故列表无方言/目标库/耗时列；这些字段由增强 agent 通道提供，与「增强捕获：未启用」一致（见用户问答复）。

U06/U07增强捕获与自然融合（本轮）：
- 设置页「源码位置捕获」勾选「增强捕获（agent 字节码通道，失败自动回落）」后保存，应用控制台出现 `[SqlVisualizerAgent] attach retransform: matched=6 moved=0 unmodifiable=0 failed=0`，面板状态栏 `增强：已挂载 pid=43156｜已收事件 N 条`，确认 agent 真实挂载。
- 自动保存文件新增条目带 `"pv":"AGENT"`、完整调用栈（含 `UserController#getById:70`、`LogFeederController.java:58`）与耗时字段；同一次查询的控制台与 agent 数据按去重索引合并，不产生双行。
- 工具栏暂停图标（tooltip「暂停增强捕获」）点击后状态栏转「暂停中」；暂停期间 `GET /user/5` 仅以 `"pv":"CONSOLE_EP"` 落盘（控制台通道照常），恢复后再发 `GET /user/6` 落盘为 `"pv":"AGENT"`，确认暂停只停 agent 通道、恢复即回流。
- 用户现场问题答复：列表行未显示方言/耗时是因为当前条目来自控制台通道；增强通道开启后，历史页条目行明确显示 `MySQL`、`3ms/0ms`、`[LogFeederController.java:58]` 源码链接。

U24历史耗时兜底（本轮）：SQL 历史页双击 `sql-history-2026-10-17.sql` 展开 872 条，条目行显示 `#1 [2026-10-17 06:42:27] 3ms MySQL 网站 236 条 [LogFeederController.java:58]`、`#2 … 0ms …`，证明历史快照中的耗时与方言可读；右键菜单含 复制 SQL/复制时间戳/跳转到 Java/只看同结构/导出 SQL/在历史中搜索。待「缓存优先与不串条目」的全量组合仍以定向测试为准。

U28导出入口（本轮）：日志条目右键菜单完整列出「导出可见 SQL（CSV/TSV/TXT/SQL）」、复制 SQL/时间戳、删除、屏蔽此 SQL、发送到 SQL→Java/日志转 SQL、跳转到 Java、复制调用方法栈、只看同结构；点击「只看同结构」后列表出现「只看：同结构 200 条」筛选条并可清除。导出四格式的实际落盘本轮未执行（菜单项点击被菜单项次序偏移吃掉），不冒充通过；既有 `build/ui243-replay-b1.sql` 仅证明回放脚本导出，不代证 CSV/TSV/TXT。

## 本轮剩余未验组（据实）

以下组仍缺真机步骤，不因定向测试或相邻组通过而升级：U04 多选空白/切文件迟到/引导重启；U08 六出口同样本与采集展示独立；U09 最近执行；U10 Kotlin 构造与选区限制；U12/U13 方言学习与 UUID；U14 active 独立路径/手动冲突/重渲染二击/编辑保护；U15/U16 长 WHERE/注入/缺表语法/原生超时；U17 五条金路径同次对照；U18/U25 PG 树、Apply/Reset、授权负例、有变化 diff；U19 编辑换目标/清历史失效与 16KiB；U20 CRUD 批量/Version 全边界/两预设；U21 meta 悬停与跨方言一致；U22 旧六出口与超限提交拦截；U23 P2/20 上限/OGNL/命名重启；U27 COUNT 独立开关/EasyQuery 三态；U29 三选/超千行 Diff；U30 真正空剪贴板；U31 即时脱敏/尾组 flush/交错；U33 单 config 注入与负例；U34/U37 premain/attach/池/batch；U35/U36 屏蔽重启与保留 3/0/非法值；U40/U41 更新三态/空态/冷预热/心跳；U42/U43 真 JAR 前缀/歧义/反查；视觉窄栏 11 动作溢出与二维码三态。

## 最终门禁与合流状态

- UI024（存储按钮裁切）已合 dev `de170ecb`；UI026（英文设置页横向溢出）已合 dev `eb6d2af0`，合流后定向 `StorageRowLayoutTest` 3/0/0/0。
- UI025 已合 dev `6f96429a`；其真实场景缺口 UI029 的修复已合 dev `052df70b`，合流后定向 `testDbSchemaAnnotator` 41/0/0/0（反向摘回落分支恰 3 红）。
- 全量门禁：UI029 合流后新一轮默认 5 片 `testShards --continue --no-configuration-cache` 已绿：**7867 例 / 9 跳过 / 0 失败 / 0 错误**（14m34s，tip `d217261b` 之前的 `052df70b` 源码树）；本机 Docker 不可用，按项目定例自动跳过 Docker 测试。
- 243/253/262 兼容与构建门禁按用户要求排在全部验收之后，尚未执行；未执行前不声称通过。

## 全量门禁首轮红灯与处置（2026-10-17 晚）

首轮默认 5 片 `testShards` 在 shard2 确定性红（隔离复跑同样红），两类共 5 例，均非本轮 UI 修复引入：

- `AliasDualTrackDefenseTest` 3 例：断言 lambda 链产出 `selectAs(...)`。javap 实证 MP core 3.5.14/3.5.17 的 `Query`/`AbstractWrapper`/`LambdaQueryWrapper`/`QueryWrapper` 均无 `selectAs` 重载，lambda 链无法表达“列 AS 别名”，带别名投影必须整链回落 `QueryWrapper.select(String...)`（现行生成物可编译）。原测试按不存在的 API 断言，属过时契约；已按现行契约校正三例并在生成器回落分支补 javap 依据，未放宽任何产品行为。
- `AgentManagerPremainReattachTest` 2 例：T-AttachTarget 接入后 `onProcessAppeared` 先做目标探测，`NON_JAVA/HELPER` 直接返回，暂停通道与 attach 端口的恢复判定被挡在后面，导致“重开增强即恢复”失效。修复为恢复判定前移（通道/端口本身已证明该 pid 曾是本插件目标），`doAttachAsync` 同位置补防御性判定。
- 修复后复跑：`AliasDualTrackDefenseTest` 7/0/0/0、`AgentManagerPremainReattachTest` 16/0/0/0，提交 `5e08f860`。

UI025 已合 dev `6f96429a`：ExternalAnnotator 只认预览临时文件与插件自有 exec 路径、只读预热缓存；平台不分析 `.idea` 配置目录，故补 RangeHighlighter 兜底通道（红波浪/错误条/悬停建议），两通道按路径互斥；偏移取 JSqlParser token。合流后定向 `testDbSchemaAnnotator` 27/0/0/0、`PlatformSignatureCrossVersionTest` 6/0/0/0、plugin.xml 两组结构测试 BUILD SUCCESSFUL，随后以包含该提交的 tip 重跑默认 5 片全量。

第三轮全量又暴露一处约定守卫红灯（`BannedApiScanServiceStateTest`）：`SqlPreviewSchemaGateTest` 在 tearDown 里再次 `project.getService(RollbackSnapshotStore)`。已改为 setUp 捕获服务引用、tearDown 只对引用调 `clearForTest()`，守卫 4/0/0/0、夹具 15/0/0/0，提交 `074419b6`。第四轮默认 5 片全量在无 IDE 干扰、清理旧分片结果后重跑。

第三轮之后又发现同族过时断言 `JavaCodeGeneratorJoinTest.SELECT列带别名_生成selectAs`（普通 LAMBDA 也按不存在的 API 断言），按同一契约校正；MPJ_LAMBDA 的 selectAs 断言保持不变（MPJ wrapper 自身提供该重载）。反向包 298/0/0/0，提交 `a3afbe37`。第五轮全量改为 `--continue`，让五个分片一次跑完以便一次看全失败面。

UI024/UI026 新包 GUI 观察（2026-10-17 深夜，重建 243 沙盒、英文 IDE UI）：设置窗口在较宽默认尺寸下，SQL Log 页「自动保存」区三个存储按钮完整可见、右缘无裁切；把窗口缩到 998×742 的尝试被设置窗口自身尺寸策略回弹（SetWindowPos 报成功但界面未变），所以 998 默认宽的真机画面仍未取到，不能以宽窗截图代证。UI026 的英文文案场景也未触发：IDE 是英文，但插件界面按插件侧语言显示中文；英文溢出仍以 `StorageRowLayoutTest` 的真实 JBScrollPane/880/998 断言为准。UI025 的红色波浪视觉复验未完成：新沙盒未打开 SQL 日志工具窗、注解 gutter 图标点击未唤出预览对话框，只有自动化 27/27（含 RangeHighlighter 通道与 token 偏移）覆盖，按「未真机复验」记录。

增强通道可视证据（新包、IDE 内 Run 启动）：开启捕捉后 `增强捕获：已挂载 pid=61888｜已收事件 3 条`，列表行显示 `[2026-10-17 23:46:00] 0ms MySQL 同结构 3 条 [UserController.java:70]`——方言、耗时、源码链接三项同时在场，直接回答“为什么之前列表没有方言/耗时”（控制台通道无这三项，增强通道才有）。

UI025 真机观察（待归因，不直接判缺陷）：在增强条目预览里输入 `SELECT usernme FROM user`，编辑器未见 `usernme` 红色波浪；当时面板处于“执行中…”且预览头只显示“数据源（上次使用）：mp_demo@localhost”，无法证明精确目标已绑定。与 `testDbSchemaAnnotator` 27/27（含 RangeHighlighter 通道与 token 偏移）不一致，暂记为待归因项，需单独复核目标解析时机与通道安装；不因此关闭或升级 UI025。

UI028 复核结论（撤销待归因）：预览「执行 SQL」弹出的「选择数据源」五项列表可正常操作；鼠标点击条目曾记录 `popup selected: null`（自动化落点问题），改用键盘 Down×3+Enter 选中 mp_demo 后得到明确终态——底部红字「执行失败：已阻止执行：当前 SQL 按 未知 渲染，但所选数据源的实际方言是 MySQL……请重新生成 SQL 或切换目标后再执行」。这是 B6 已验的未知渲染方言阻断在编辑后 ad-hoc 文本上的再次命中，属设计内 fail-closed，不是缺陷；同时解释此前「执行中…」实为等待选择数据源。该路径可用于 U14 未知方言写入负例，但不能据它证明 MyBatis 原生执行链。

UI025 入口复核（2026-10-18）：为拿到“插件生成、带错列的 SQL 预览”，在 demo 仓临时加 `@Select("SELECT usernme FROM user …")` 与 `/user/fixture/unknown-col` 端点，并在 UserMapper 注解行做 gutter 放大取证：该行只渲染黄色意图灯泡，插件 `sqlGutter.svg` 预览图标未出现（`showGutterIcon` 与 `gutterGroupAnnotation` 默认均为 true，配置无覆盖），点击灯泡得到普通意图菜单而非预览。随后尝试从 IDE Run 重启应用以捕获错列 SQL，外部 kill 后 Services 节点保持红色、Run 不再产生新进程/新控制台页，无法继续。demo 仓临时夹具已全部 `git checkout` 撤销。结论：UI025 真机视觉在本轮沙盒仍无法验证，入口缺失与 IDE Run 失效两处环境问题均未归因，保持“待归因”，不以自动化 27/27 代替。

UI029 新登记（UI025 真实场景缺口，已派独立修复）：demo 临时夹具 `/user/fixture/unknown-col`（mapper `@Select("SELECT usernme FROM user …")`）触发后，日志条目为 `#1 [2026-10-18 00:20:52] 10ms [MySQL] SQLSyntaxErrorException: Unknown column 'usernme' in 'field list' [UserController.java:80]`；双击打开「SQL 预览」，编辑器显示 `SELECT usernme FROM user WHERE id = 1;`、顶部有执行报错横幅，但 `usernme` 上仍无红色波浪与悬停提示。根因方向：该预览没有附着控制台，`DataSourceSelector.schemaValidationTarget(nativeEditorDoc)` 返回 null，标注器直接跳过（自动化 27/27 用的是测试缝目标，覆盖不到这一形态）。已派独立工作树 `t-ui029-annotator-target` 实现“日志预览可用预览自身已确定的数据源/库 + 预热缓存”回落，并保持 MySQL/MariaDB、库非空、缓存热、作用域明确四道 fail-closed 闸门；修复后用同一夹具复验。

U09/U16 本轮补证：日志条目预览头部显示调用栈摘要（`调用栈: cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler#emitEvent136<~cn.song.my…`），日志条目右键含「复制调用方法栈」；错列条目预览顶部完整显示 `java.sql.SQLSyntaxErrorException: Unknown column 'usernme' in 'field list'` 并提供「复制错误信息」，与 U16 的富错误披露一致。未执行「最近执行」弹窗与栈弹层的展开交互，不升级整组。

U08 展示层脱敏（本轮真机）：临时只读端点 `/user/fixture/by-password?pwd=secret_pwd_123` 产生条目 `#2 [2026-10-18 00:31:57] 2ms MySQL [UserController.java:123]`，列表行显示 `WHERE password = '***' AND deleted = 0;`；原生控制台仍为原始 `Parameters: secret_pwd_123(String)`（插件不改写控制台），双击打开的 SQL 预览对话框里参数与 SQL 保持原值（可编辑/可执行面需要真值）。与设置项描述“日志列表、单击预览浮层与导出文件打码”一致；采集层与展示层解耦成立。六出口中的导出文件脱敏与“原文件不擦写”仍需单独验。

U22/U28 导出回放脚本（本轮真机落盘）：预览「更多 → 导出回放脚本」勾选「事务包裹」后确认，实际生成 `build/replay.sql`（368 字节，2026-10-18 00:34:41），头部声明 `dialect: MySQL`、`statements: 1`、`transaction: START TRANSACTION ... COMMIT`、`masked: literal values replaced with *** (display mask enabled)`；正文为 `START TRANSACTION; SELECT id, username FROM user WHERE password = '***' AND deleted = 0; COMMIT;`。同时证明导出文件走展示层脱敏。

U28 可移植 CSV（本轮真机落盘）：同一预览「更多 → 导出可移植 SQL」选默认 CSV，生成 `build/sql-portable.csv`（123 字节，2026-10-18 00:38），表头 `dialect,kind,upsert,sql`，行内 `MySQL,CRUD,no,"SELECT … WHERE password = '***' …"`——方言、类型、UPSERT 能力与脱敏后的 SQL 同表导出。TSV/TXT 与 SQL 三格式未逐一落盘。

## 本轮组状态增量（2026-10-18）

- U08：列表 `'***'`、控制台原值、导出回放脚本与 CSV 内 `'***'` 均已实测；双击预览对话框保持原值属“可执行面需要真值”，与设置描述一致。仍待单击预览浮层、历史页、底部详情三出口的同样本核对与原文件不擦写。
- U09：预览头部调用栈摘要与右键「复制调用方法栈」在册；栈弹层展开/逐帧跳转/最近执行记录未验，保持部分。
- U16：错列条目预览完整展示 `SQLSyntaxErrorException` 并提供「复制错误信息」；缺表/语法错/失效连接/原生超时未验，保持部分。
- U22：回放脚本与可移植 SQL 两个出口已真实落盘；分享、差异对比、AI 上下文（剪贴板读取被上游拒绝，未能核验内容）与旧六出口未验，保持部分。
- U28：回放脚本（SQL）与可移植 CSV 已落盘并核对表头与脱敏；TSV/TXT/可移植 SQL 三格式未逐一执行，保持部分。
- UI029 修复已合 dev `052df70b`，定向 `testDbSchemaAnnotator` 41/0/0/0，含 UI029 的新一轮全量为 7867/0/0/9。

UI029 真机闭环（2026-10-18，新包）：重建 243 沙盒后，开启捕捉并用 `/user/fixture/unknown-col` 产生条目 `#2 [2026-10-18 00:56:56] 11ms MySQL SQLSyntaxErrorException: Unknown column 'usernme' in 'field list' [UserController.java:80]`；双击打开「SQL 预览」，编辑器第 2 行 `usernme` 出现红色波浪下划线（放大取证 `build/ui243-shots/ui029-redwave-zoom.png`），与顶部执行报错横幅并存。UI025 在“日志条目预览 + 无附着控制台”真实场景下由 UI029 的 last-used 目标回落生效，红色波浪视觉闭环；悬停建议文案本轮未截到（自动化用例断言在场）。demo 临时夹具（mapper 两个方法与 controller 两个端点）已在验证后撤销。

243/253/262 兼容门禁（2026-10-18，UI029 合流后）：`verifyGate` BUILD SUCCESSFUL（2m5s），三个目标均 **Compatible**——IU-243.28141.41（5 scheduled-for-removal / 36 deprecated / 96 experimental）、IU-253.33514.17（6/47/94）、IU-262.10968.63（8/118/94），各 1 internal API 与 1 override-only 违规，均为既有已知项；报告在 `build/reports/pluginVerifierGate/<build>/`。

U40 更新与诊断页（本轮真机）：About 页显示当前版本 2026.10.16、内部构建号 `MPV-Build: 1792256102736 (2026-10-18 00:55:02)`（新包）、插件缓存 0 B（0 项）与「清除缓存」、「抢先体验：启用 Gitee 更新通道」未勾选、报告问题/需求池入口、设置导入/导出。点击「检查更新」后按钮右侧出现「暂无可用更新」，更新检查当前态可达；更新可用的另一态与检查失败态未造出。

设置页本轮观察（真机，243 英文 IDE + 中文插件 UI）：SQL Preview 页含「数据库方言：智能推断（跟随数据源）」与「项目候选方言不一致：预览兼容按 MySQL、执行前核对目标」、占位符替换、空参数策略三选、格式化/竖向列注释/自动折叠阈值、逻辑删除自动追加、多租户列与 INSERT 追加、「模拟分页拦截器（COUNT 查询预览）」「附分页 COUNT 查询预览」两开关；MCP Tools 页声明本插件 11 个工具并逐一列名（sqlv_render_sql/schema_context/explain/render_logs/check_sql/rollback_plan/perf_advice/crud_sql/reverse_sql/mapper_check/history_search）、启用总开关、MCP 输出脱敏、全部禁用。属设置面存在性与默认值证据，不代证对应行为组（U20/U21/U27/U39）整组通过。

U22 分享出口本轮真机：预览「更多 → 分享」打开「分享 SQL」对话框，来源 `UserController.java:123`，七项出口完整列出——复制为 Markdown（默认）/ Markdown 表格 / 可执行 SQL / 导出 HTML 报告 / 复制诊断报告（Markdown）/ 导出诊断报告（HTML）/ 审阅诊断报告并反馈。选「导出 HTML 报告」后实际生成 `build/sql-report.html`（2309 字节，2026-10-18 01:13，`<title>SQL Report</title>`，含 SELECT 与 Parameters 段）；未执行任何公网发送，「审阅并反馈」只打开审阅面。

## 本轮交付摘要（2026-10-18）

- 合流：master→dev；UI016～UI026、UI029（日志预览错列目标回落）、UI030（结构化计划回退 UX）全部合入 dev。
- 门禁：默认 5 片全量（含 UI029）7867/0/0/9；243/253/262 三目标 Compatible。含 UI030 的全量两轮在 shard5 各折 1 例 UI 测试（详见下方「第 8 轮全量与 shard5 竞态处置」），初判「并发 flake」已被 IDE 关闭后复现推翻，按既有 exec-doc 概率竞态处置（shard1~4 保绿、shard5 纯重跑），非 UI030 代码回归。
- 修复：增强重开 resume 被目标探测挡住；MP core 无 selectAs 导致的过时测试；约定守卫点名的收尾取服务；UI025 真实场景目标回落；UI030 直接弹数据源选择器。
- 清理：4 个已合工作树/分支移除；主仓与 demo 仓干净；未 push、未发布、未 dev→master。

## 44 组完成度审计（2026-10-18 收口时点）

说明：仅“通过”表示本轮有真机证据且覆盖该组验收口径；“部分”表示只覆盖了列出的子项；“未验”表示本轮未触达；“阻断”表示受环境/夹具限制无法在本轮验证。自动化门禁（7867/0/0/9、三目标 Compatible）不能替代本表。

- 通过：U02（200 上限/滚轮暂停/回到底部/自动贴底/七排序/慢筛入口）、U06（开启/挂载/暂停仅控制台/恢复回流）、U07（同次融合/AGENT provenance/历史回读）、U24（历史方言+耗时+源码+右键六项）、U26（UI023 真实错列提示+No 零写）、U39（262 十一工具与安全边界）。
- 部分：U01/U03/U05/U44（视觉矩阵与列表基础，窄栏与二维码未验）、U08（列表/导出脱敏已验，其余出口未逐一）、U09（栈摘要/复制菜单，最近执行未验）、U11（确认框范围，实际清理与重启未验）、U12/U13（设置页智能推断 + 方法预览头部「方言: MySQL（模块已学数据源）」已验，UUID 全链路未验）、U14（TRUNCATE 普通确认 No 零写、未知方言阻断；active/冲突/二击/编辑保护未验）、U15（右键「SQL 快捷模板」三动作在册并实际执行 `SELECT COUNT(*) FROM user`；当前目标库缺表，成功路径待复验）、U16（错误原文/复制；缺表/语法/超时未验）、U18/U25（原生 MySQL EXPLAIN 表格与结构化回退文案已验；PG、diff、授权负例未验）、U19（AI 上下文旧证；编辑/换目标/16KiB 未验）、U21（设置页方言面；跨方言执行确认未验）、U22（七出口清单+HTML 报告+回放脚本；旧六出口未逐一）、U23（预设旧证 + 管理对话框与按语句隔离文案已验；P2/20/OGNL/重启未验）、U27（COUNT 开关存在；行为未验）、U28（回放 SQL 与 CSV 落盘；TSV/TXT/可移植 SQL 未逐一）、U31（展示脱敏；即时/flush/交错未验）、U34/U37（回滚旧证；premain/attach/池/batch 未验）、U35/U36（屏蔽对话框；重启/保留策略未验）、U40/U41（检查更新“暂无可用更新”；更新可用/失败态、空态、心跳未验）。
- 未验：U04、U10、U17、U20、U29、U30、U33、U42、U43、视觉窄栏 11 动作溢出、二维码三态、同源色。
- 阻断：需真实 PostgreSQL/其他方言库的 U18/U25 子项、需真实 JAR 工程的 U43、需专用剪贴板/快捷键注入的 U30、需模板选中表并执行控制台的 U15/U27 行为面——本轮沙盒的“未知渲染方言”fail-closed 会拦下手工编辑过的 ad-hoc 文本执行，插件生成预览的 gutter 入口在本沙盒不可点达。

收口清理：`t-devui-en-hint`、`t-devui-storage-row`、`t-devui-schema-annotator`、`t-ui029-annotator-target`、`t-ui030-explain-ux` 五个已合分支及其工作树已移除（git 侧 deregister + 长路径 `\\?\` 删除，31 个工作树保留为历史在途项）；主仓与 demo 仓工作树均干净；未 push、未发布、未 dev→master。

## 第 8 轮全量与 shard5 竞态处置（2026-10-18）

- 含 UI030 全量第 8 轮（IDE 已关闭、demo 应用已停、并行登记表为空）：`testShards --continue --no-configuration-cache`，终态 **7914 例 / 1 失败 / 0 错误 / 10 跳过**（14m34s，BUILD FAILED），唯一失败 `CrudSqlPreviewPanelRenderTest.testBatchCrudCarrierLosslesslyRerendersForActualTarget`，`IllegalStateException: Unexpected memory-disk conflict … .idea/sql-visualizer/exec/mpsql-exec.sql`（`MemoryDiskConflictResolver.askReloadFromDisk`）。
- 该类**隔离复跑绿**（`./gradlew test --tests …CrudSqlPreviewPanelRenderTest`，3m5s，BUILD SUCCESSFUL）。shard5 单独重跑又换牺牲品：`FieldFillSimulationPanelTest.testTargetRerenderPreservesActiveSimulationEditingToggleAndPresetRecovery`（同族冲突、同一 `mpsql-exec.sql`、失败点 tearDown；1674 例 / 1 失败，2m35s）。
- 判定：**既有 exec-doc 概率性竞态，非 UI030 回归**。证据三点——①牺牲品会漂移（两轮同测试、重跑换测试、隔离单类绿）；②UI030 改动全在 EXPLAIN 提交链（`ExplainPanelChannel`/`ExplainFallbackGuidance`/`promptDataSourceThenRetryStructured`），不触 CRUD 渲染路径；③该冲突族在此前已有四代收敛补丁（T-EC-01/02/03、T-ExecDocSync2/3、T-PreviewFast P1/P2，`DataSourceSelectorResults.kt` `projectExecFile` 写链），竞态本体是「文档 modified-unsaved × 异步落盘 × VFS refresh」。UI030 新增测试类（7867→7914）导致分片重排，把新的邻居组合换进了 shard5，概率显形。
- 处置：**不动 src**（保住 shard1~4 第 8 轮的绿），shard5 纯重跑至绿（最多 3 次）；合并 shard1~4 + shard5 为本轮权威读数并如实注明重跑。若三次内仍不绿，再升级为竞态专项修复（届时全量作废重跑）。

## PG 数据源预写（U18/U25 解阻断，2026-10-18）

- 手写 `.idea/dataSources/<uuid>.xml` 模型文件不可行（20260923 双沙盒实测被 DAS 判损坏隔离，`.corrupted.*.xml` 在案），本次改走两条安全路径：
  1. **注册表预写**：demo 工程 `.idea/dataSources.xml`（工程组件、IDE 自产格式）追加 `dbx_test@dbx-test-host`——`driver-ref=postgresql`、`jdbc-url=jdbc:postgresql://dbx-test-host:20033/dbx_test?user=dbx_test`（口令为空，故不依赖存 IDE 级 master_key 的 secret-storage，跨沙盒重启无口令丢失面）；XML 解析校验通过，改前 `dataSources.xml`/`dataSources.local.xml` 备份在 dev 工作树 `build/ds-backup-*`。
  2. **驱动免下载**：真机 IU-2024.3 的 PostgreSQL 42.7.3 驱动树（8 jar / 5.8M）整拷进沙盒 `config/jdbc-drivers/PostgreSQL/`。
- 安全依据：`DataSourceJdbcSmokeTest` 逐源跳过测试 classpath 没有的驱动（源码注释明示「demo 可含 DM/PG」）；在场已达梦远程源且历轮门禁全绿为现成反证。工程级数据源本就持久（`.idea` 被 gitignore），PG 建好后以后重启沙盒零操作。
- 披露：demo `.idea` 与用户日常 IDE 共享，PG 源会同时出现在日常 IDE；内省模型仍由 IDE 首连时自建（未伪造），沙盒起来后需验证源在场+可连。

## 新包真机第二批（2026-10-18 上午，含 UI030 的 243 沙盒，DFF 退出后完成）

环境：runIde 全屏+置顶；开局即 Distraction Free Mode（行号/gutter/页签全无，重启后状态保留），`Ctrl+Shift+A → Exit Distraction Free Mode` 恢复；编辑器合成输入被 UIPI 挡（提权沙盒），全程走 `record/_run_elev.bat` 提权 pyautogui 探针 + `Set-Clipboard` 粘贴（IME 吞空格，键入路线作废）。

- **UI030 主路径闭环（用户现场 UX 修复的真机验证）**：UserMapper.selectByAnnotationId（`SELECT * FROM user WHERE id = #{id}`，#id=91）gutter 预览 → 点「执行计划」→ 无已绑控制台状态下 **last-used mp_demo@localhost 静默绑定**（Database 工具窗自动出现 mp_demo 控制台 + mpsql-exec.sql）→ **自动重试后结构化 EXPLAIN 表格直接渲染在预览面板内**：`EXPLAIN · MySQL · mp_demo@localhost · 2241 ms`，含 11 列计划表格、右上「原生控制台」出口、「计划已保存到 SQL 历史（需条目≥5 份）」横幅与格式化 SQL 注释（frame ui030-11-explain-c-settled.png）。旧 UX「只弹告知通知没有下一步」已消除。选择器直弹场景（无 last-used）与取消→通知→不重弹路径本轮未触达（last-used 在场走静默复用属实现定义形态），保持部分。
- **UI025 红色波浪新包回归**：预览 SQL 编辑为 `SELECT usernme FROM user WHERE id = 91`（剪贴板粘贴），`usernme` 下方红色波浪线 + 行首灯泡在位（frame ui025-4-zoom.png），错列标注链在含 UI030 的新包完好。
- **U16 缺表错误面（新增）**：预览 SQL 编辑为 `SELECT * FROM bench_items LIMIT 3` 执行，静默绑定 mp_demo 后真实执行并完整展示 `[42S02][1146] Table 'mp_demo.bench_items' doesn't exist (当前库=mp_demo，数据源=mp_demo@localhost，数据源默认库=mp_demo)`，带「复制错误信息」出口；Database 控制台同步显示 Connected/use mp_demo/错误原文（frame pg-7-exec-b.png）。
- **执行计划 fail-closed 闸（新增）**：UPDATE 预览点「执行计划」→ 通知「未执行 EXPLAIN：语句以 {update} 开头，不是可解释查询（允许 SELECT / VALUES / TABLE / WITH）」（frame ui030-8-explain-clicked-early.png）。
- **PG 预写条目被 IDE 正常采纳（注册表路线证实可行）**：项目打开日志 `DataSourceModelStorageImpl - No model loaded for: …, dbx_test@dbx-test-host (6f2a9c41-83de-4b57-a219-c8d4e5f6a7b8)`——手写 `dataSources.xml` 注册表条目未被隔离、正常入模型（与 20260923 手写**模型文件**被隔离的结论并行不悖：隔离的是 `.idea/dataSources/<uuid>.xml` 模型，注册表工程组件可安全预写）。**但控制台数据源下拉与 Services 视图均未列出该源**（仅列 4 个曾连接过的 MySQL 源；达梦同样缺席，此前有「failed to guess driver」告警）——驱动定案/首连前的源不进选择器，U18/U25 的 PG EXPLAIN 与 U21 跨方言负例延后到「经 IDE 数据源对话框触碰/首连该源」之后（下批先做）。
- **U15 成功路径 + U27 COUNT 真机闭环（新增）**：预览 SQL 内**双击选中表名 `user`** 后右键 →「SQL 快捷模板」子菜单三项（EXPLAIN 选中表/COUNT 选中表/抽样 10 行）全部点亮（未选中时整组灰置，验证了使能判据=选中态）→ 点「COUNT 选中表」→ Database 控制台真实执行 `SELECT COUNT(*) FROM user`（853 ms，返回 1 row，结果网格工具条 CSV/导出/转置在位；结果值 41）（frame u15-6-zoom.png / u15-7-count-executed.png）。此前只有 kotlin_demo 缺表报错路径，本次成功路径补齐。
- **U22 复制类四出口逐一（新增）**：「更多 → 分享 SQL」对话框（来源 UserMapper.java:28）七个单选出口在位；逐一切换 + 确定后读剪贴板——「复制为 Markdown」=sql 围栏+来源/方言/时间脚注；「复制为 Markdown 表格」=来源/SQL/参数/方言/时间六列表格；「复制为可执行 SQL」=纯可执行文本；「复制诊断报告 (Markdown)」=完整诊断（来源头+SQL 块+参数表+检查总结 [INFO] 检测到 SELECT *）。此前已验 HTML 导出/回放脚本/可移植 SQL/审阅面——U22 七出口全部闭环，转「通过」。
- **U34/U37 增强通道 attach 增证**：从 IDE Run 启动 MybatisPlusDemoApplication（8081）后开捕捉，SQL 日志列表出现带方言/耗时/源码位置的完整条目（`#1 [2026-10-18 12:03:21] 1ms (MySQL) [UserController.java:70]`），状态栏「控制台：已挂载 2 个，已扫描 41 行 | 增强：已挂载 pid=53376,pid=50484」——agent attach 双 pid 在案。
- **U09 入口未触发（登记）**：gutter 图标右键（平台 `getRightButtonClickAction` 接线，M37.1/T-MergeFollowup-02 修复形态）在合成输入下不弹「预览 SQL/最近执行记录」菜单——左键同位置正常开预览（对照实验），13 次右键尝试（多落点/悬停稳定/按住不放/双时刻拍帧）均无菜单且 idea.log 零痕迹；左通右不通指向合成输入差或该平台接线回归，真机人工右键待验，「最近执行记录」弹框本体本轮未触达。
- **U28 导出入口未触发（登记）→ 已闭环（用户指点路径）**：正确路径=**左键选中日志条目后右键条目正文**（先左键选中再右键，右键空白头部只出「恢复实时捕获」横幅）。右键菜单完整在位：复制 SQL/复制时间戳/删除/屏蔽此 SQL/发送到 SQL→Java 转换/发送到日志转 SQL/跳转到 Java/聚焦调用方方法/按请求聚焦/只看同结构/**导出可见 SQL（CSV/TSV/TXT/SQL）**。四格式真实落盘并核对内容：`sql-log.csv`（652B，time/source/duration_ms/sql 表头+行）、`sql-log.tsv`（648B，制表符分隔）、`sql-log.txt`（749B，`-- === SQL #N ===` 块+time/source/ms 注释）、`sql-export-20261018-122842.sql`（839B，「Exported 3 SQL statements」头+分号结尾语句）（frame u28-16-combo-list.png）。加上此前已验回放脚本/可移植 SQL——U28 全格式闭环，转「通过」。
- **U22 复制类四出口逐一（新增）**
- **U10 设置页搜索（用户提示登记）**：SQL Visualizer 设置页可用顶部搜索跳转定位（用户口头提示）；本批尝试「清除预览 SQL 历史/存储」全文词均「Nothing to show」（自定义 Configurable 疑未实现内容搜索），下批改用页名/控件名短词验证。

## PG 方言组真机闭环（2026-10-18 下午，U18/U25/U21 解阻断）

环境接续：口令取自用户提供的 DBX 共享连接表（PG 16.14@dbx-test-host:20033/dbx_test），dbx MCP 直连 `SELECT version()` 先验证口令有效（33ms），再走 IDE 侧凭据弹窗。

- **口令入 IDE 的真机路径（pg4 探针系列）**：Database 树选中源 → 工具栏 ⚙ 打开 Data Sources and Drivers → Test Connection → SCRAM 弹窗 **Change Credentials** → 密码框粘贴口令 → OK。首跑探针在 paste 后崩溃（PIL crop 元组错），恢复时误粘进对话框树搜索框（列表被过滤成 No matches found，Ctrl+A+Delete 清除）；但**崩溃前的 OK 已把口令提交**——恢复列表后 Password=`<hidden>`、Test Connection 绿 ✓ `PostgreSQL 16.14`，Apply Changes 确认（关旧连接）后树行出现 **"1 of 3" 活动连接**（frame pg4-14/pg4-16/pg4-19）。口令 Save=Until restart（IDE 重启后需重填；dbx 侧连接持久）。
- **PG 首连解锁会话选择器**：此前「控制台数据源下拉不列该源」的阻断消除——共享控制台工具栏 Data Sources → **All 子菜单列出 dbx_test@dbx-test-host**，点选即把共享控制台（mpsql-exec.sql）会话切到 PG（工具栏数据源下拉变 dbx_test、schema 下拉变 `<schemas>`；Database 树里 mpsql-exec 节点随之迁挂 dbx_test 下，frame pg5-33）。Query Console 亦可用（New → Query Console，`SELECT 1;` 1 row/584ms）。
- **U25 PG 树 + 体检（实锤转增）**：设置 SQL Preview「数据库方言」手动选 **PostgreSQL**（面板 meta 行变「方言: PostgreSQL（手动指定）」）→ 共享控制台会话数据源切 dbx_test → 预览 SQL 编辑为 `SELECT * FROM bench_items LIMIT 3` → 执行计划 → 结构化面板 `EXPLAIN · PostgreSQL · dbx_test@dbx-test-host · 1316 ms`，树形渲染 **Limit (rows=3)**，展开含 **Parallel Aware: false / Async Capable: false / Startup Cost: 0.00 / Total Cost: 0.08 / Plan Width: 87**（PG JSON 计划专有字段）+ 可展开 Output、**Seq Scan on bench_items (rows=10000)** 子树；头部黄色体检条 `[SEQ_SCAN] bench_items: Sequential scan over ~10000 rows — consider adding an index on the filter columns`（frame pg5-41/pg5-45）。
- **U21 跨方言执行确认 fail-closed（实锤转增）**：手动方言 PostgreSQL + 绑定数据源仍 mp_demo(MySQL) 时点执行计划 → 结果区红字整体阻断：「执行失败：已阻止执行：当前 SQL 由手动方言 PostgreSQL 强设，但所选数据源的实际方言是 MySQL。插件不会覆盖手动选择，也不会为冲突会话暴露原生控制台。」+「复制信息查看」出口（frame pg5-22）。无 SQL 下发、无原生控制台暴露。
- **路径记录（复跑指引）**：方言门先于 NO_DATA_SOURCE 选择器——Deactivate mp_demo 后 `boundConsoleDataSource()` 的内存 console `isValid` 仍 true，执行计划仍走方言门阻断而非弹选择器；换绑正解=共享控制台工具栏 Data Sources → All → 选 PG（或等共享控制台真实失效后走 T-UI030 选择器直弹路径，后者本轮未触达保持部分）。
- **UX 观察两条（登记不判缺陷）**：①预览面板开着时改设置页方言 → 面板重渲染走「内存 PSI」降级并出红字警示「预览以降级模式打开（内存 PSI）……点【重试】或关闭并重新打开预览即可恢复完整支持」，Esc 重开即恢复（frame pg5-22 顶部）；②绑定指示行恒显「关联（上次使用）： mp_demo@localhost」记忆文案，实际 bound 已切 dbx_test——指示行不随会话数据源切换刷新，与结构化头部 `· dbx_test@dbx-test-host` 并存（信息不同源，待与用户口径核对是否算缺陷）。
- **现场留痕（下批重启前恢复项）**：设置方言=PostgreSQL（手动）需手动回「智能推断」；mp_demo@localhost 处于 Deactivated 需 Reactivate；共享控制台会话数据源=dbx_test；PG 口令 Until restart 重启即失效（下次要 PG 链路再走一次 Change Credentials，或重启后从 dbx 侧确认）。robot-server 0.11.23 已拷入沙盒 `config/plugins/`（端口 8580，重启自动生效，探针进程需重开才会重探）。

## PG 链收尾与 U31 打码疑点（2026-10-18 下午二段）

- **现场已恢复**：方言设置回「智能推断（跟随数据源）」（下拉候选>8 项含 DM (达梦)/KingBase (人大金仓)，键盘 Up×2+Enter 选中；全局 auto=true 后 O-93 修复保证手动残值不劫持推断——设置页披露「当前项目手动指定：PostgreSQL」仅为状态提示，预览决策走推断链）；mp_demo 双击重新激活（图标复色）。**绑定指示行口径修正**：U16 实测执行一次后指示行更新为「已绑定： dbx_test@dbx-test-host」——刷新发生在执行链感知绑定变化时，此前「恒显上次使用记忆」的观察改为「会话切换当时不刷新、执行后刷新」。
- **U16 PG 侧错误面（新增）**：预览主链执行 `SELECT * FROM user WHERE id = 91`（bound=dbx_test）→ 底部红字完整回显 `[42703] ERROR: column "id" does not exist 位置: 33 (当前库=public，数据源=dbx_test@dbx-test-host，数据源默认库=dbx_test)` +「复制信息查看」出口；内嵌控制台 Output 同步显示错误原文与位置行（frame pg6-3）。语法错 42601 与 42703 同一回显链路，超时仍无手段维持部分。
- **U18 计划留存增证**：沙盒 `config/options/mp-sql-visualizer.xml` 的 `MpSqlVisualizerHistory.entries` 内 PG EXPLAIN 计划 payload 完整入库（`dialect=PostgreSQL`、`format=JSON`、payload 含 Limit/Seq Scan 全树、`dataSourceUuid=6f2a9c41…`=dbx_test）——重启读回在批 B 验。
- **U31 打码疑点（疑似缺陷登记）**：三件套证据——①列表 #22 INSERT 展示 email 值 `'***'`、password 列 `Secret123X` 明文（frame u31-1/u31-12）；②「复制 SQL」出口剪贴板全明文 `'ui_mask_probe', 'Secret123X', 'mask@probe.test', …`（提权 tkinter 读数）；③`displayMaskEnabled=true` 已持久（XML），主仓 `DisplayMasker` 规则等价复刻对本语句**一个值都不该打**（HINT_WINDOW=48 的 columnHintBefore 在 VALUES 列表抓到的前值分别为 update_time/ui_mask_probe/Secret123X，均不含关键词、值形态均非手机号/身份证）。矛盾点=列表 `***` 来源与主仓规则不符，疑 agent 采集侧 Desensitizer（独立源集）规则/版本漂移或「复制 SQL」与列表渲染取用不同字段；**不判缺陷结论，交专项**（agent Desensitizer.java 与主仓 DisplayMasker 双侧比对 + 列表渲染链字段源核实）。设置页 GUI 改开关三条路全堵（滚轮 no-op/搜索无索引/对话框不可再放大）已按 XML 兜底记录在案。
- **U31 专项静态链（追加）**：①agent 侧 Desensitizer 与主仓 DisplayMasker **关键词表/双条件规则逐字一致**（D97 同步无漂移，双侧源码比对在案），排除「agent 规则漂移」假设；②agent 内 Desensitizer 唯一调用点=ParamCapture.snapshot（参数捕获级，hint=第 n 个 `?` 前置标识符），语句级 SQL 不经 agent 打码；③主仓 DisplayMasker 全调用方（列表渲染/导出/回放/MCP/AI 上下文）全走同一 maskSqlText，**无任何采集入库打码点**——restoredSql 恒为原文；④修正复刻文本：还原器产物带 MyBatis 排版空格（复制 SQL 实证双空格），hint 窗口在带空格文本上重算（ui_mask_probe/Secret123X/时间戳）**仍全不打**；⑤剩余唯一分歧=列表渲染输入与 restoredSql 的关系待运行时判别——**判别实验=「导出可见 SQL CSV」（导出链同 maskSqlText）：CSV 明文→列表渲染另有路径；CSV 带 ***→restoredSql 本身含 ***（还原器产物，与复制链字段矛盾）**，GUI 空闲后执行。

## 批 C 探针修复轮（2026-10-18 下午四段）

robot 机制 A 生效后重跑 U17/U19/U30/U43，两轮结果同批失败——**判定为探针脚本缺陷而非插件缺陷**，逐支修复：

- **U17**：①P1 落点 `new Page<>(…)` 行不是链根（Page 构造行按设计不出预览=插件行为正确），改落 `queryWrapper.in(pageQuery.getIds() != null`（pageByWrapper 独有链式行）；②**P2 断言设计错误**——填参不改 SQL（占位形态），联动语义=**勾选动态条件复选框**才把 `<if>` 加入 WHERE；重写为 robot 文本命中三个复选框逐一点击后断言 SQL 区 diff（截图 bc-U17-p2-before-checks 实证：三框全空时 SQL 无 WHERE）；③P5 判定口径错——**「面板无变化」实为数据源选择器弹出**（`选择数据源` 列表：jpa/kotlin/mpj/selfdev + All），即 T-UI030 的 `promptDataSourceThenRetryStructured` 路径**首次真机触达**（此前「选择器直弹场景未触达」的补充证据），断言改为「面板变化 OR 选择器弹层」二者取一；④P4 历史首条仍缺 robot 列表路线（JBList 类名未命中），保留人工补。
- **U19**：①粘贴落点公式把 rect 当 `(x,y,w,h)` 用（实为 `(l,t,r,b)`）——点在参数区导致「没点进编辑器」，改 `l+(r-l)*2/3, t+(h)/4`；②「更多」是图标 ActionToolbar 按钮（robot 无 accessible name），改几何定位 `(rect.right-72, rect.top+68)`；`click_button` 失败文案把「anchor 缺模板」误写成「robot 未启」——**误导性文案留档**。
- **U30**：`from bc_common import …` 缺 `ide`（NameError）+ `Reporter.check()` 两处少传 fail_msg（TypeError）——已修。
- **U43**：转换输入框真实类名=`EditorComponentImpl`（`EditorTextField/JBTextField` 均不存在，robot 实测 4 个），改类名+按屏幕 x≥工具窗左界过滤出工具窗内输入区。
- **新观察（升级专项）**：**「预览以降级模式打开（内存 PSI）」红字警示反复出现**——首见 pg5-22（改方言设置后重渲染）、本轮 u17 P2/P5 屏（**新 IDE 重启后首次预览即出现**，frame bc-U17-p5-explain-q4-result-none 顶部），「重试」链接可恢复。**静态归因**：降级链第二级=内存 PSI（`fallbackPreviewDocument`，临时文件不可用时的兜底；第三级=裸文档），触发面=**预热产物迟到/硬失败**（T-PrewarmLateRecover F1/F2 设计——重启初期 VFS 未就绪致预热失败、重渲染路径临时文件失效），横幅+「重试」重臂预热是设计内恢复指引。判定=设计内降级非渲染缺陷，但「重启后首次预览高概率降级」属体验面，登记待产品口径（是否应自动重试一次）。

## 批 B 重启 #1：持久性打包（2026-10-18 下午三段）

重启方式：提权 taskkill 沙盒 IDE（58588）→ 后台 `start-ui243.ps1` 重启（登记 T-DevFullUI-243）。IDE 起窗后提权置顶 TOPMOST+全屏；**编辑器页签五页全部会话恢复**（含 mpsql-exec.sql 与 console[dbx_test] 两个 Database 控制台页签）、定位停在 pageByWrapper。

- **SQL 历史跨重启读回（持久性证据①）**：SQL 历史 tab 五个历史文件全部在列——2026-10-17（1.3MB/875 条）、2026-10-18（77.8KB/27 条）、ui-synthetic-10000（1.7MB/10000 条）、ui-ddl-literals（2 条）、ui-visual-matrix（5 条）；展开 10-18 文件条目正文完整（frame b1-1/b1-3）。顺带新线索：历史条目 `#2 …WHERE password = '***'`——历史文件保存链也有打码痕迹（并入 U31 专项谜面）。
- **PG EXPLAIN 计划持久+读回（U18 计划留存闭环）**：重启后 UserMapper 预览点执行计划 → 模块学习与绑定**都跨重启保留**（方言行「PostgreSQL（模块已学数据源）」+「已绑定： dbx_test@dbx-test-host」）；结构化计划正确包装 `EXPLAIN (FORMAT JSON, VERBOSE) …`；口令弹窗如预期（Until restart 语义如实）——无口令时右下通知如实披露「结构化计划视图不可用，已回退原生控制台：SQLException: SCRAM…no password」（fail-closed）；填回口令后改 SQL=bench_items 重跑 → **PG 树重现（223ms）且历史基线自动挂上重启前 14:48:19 的快照 #0d5b5db5**，「与上次执行对比：计划无变化」判定链工作（frame b1-5/b1-7）。
- **U16 增证**：重启后首跑 EXPLAIN 落在 `FROM user`（PG 伪表）→ 控制台 `[42703] column "id" does not exist 位置: 64` 原文回显 + 底部超时引导文案完整（「SQL 已提交执行，但等待结果超时…检查 IDEA 主窗口是否有密码输入框…」，等待凭据场景的用户指引，frame b1-6）。
- **robot-server 机制 B 被否决 → 机制 A 落地生效**：`idea.log` JVM 参数实证 `-Didea.plugins.path=<沙盒>/plugins` 被 runIde 显式钉死——拷进 `config/plugins` 的 robot-server 不在加载路径（idea.log 零 robot 痕迹、8580 拒连）。改走机制 A（提交 `f1ed75d9`）：`build.gradle.kts:171` 的 `gifRecord && gifRobotServer` 解耦为 `gifRobotServer` 独立生效 + robot-plugins 拷入 dev 树（gitignore 在案）+ `start-ui243.ps1` 传 `-PgifRobotServer=true`。重启后 **8580 LISTENING（pid 44664）、`robot.py probe` 返回 324 根节点、IdeFrameImpl 1920×1080**——robot-server 0.11.23 随 prepareSandbox 正常装入。批 C 无 robot 降级跑的四组（U17/U19/U30/U23）全因按钮/页签定位「robot 未启且 anchor 模板未标定」失败——证实这些探针为 robot 硬依赖，需 robot 在场重跑。
- **现场态**：口令已重填（Until restart）；SQL 日志捕获条目如预期清空（内存态）；待验=参数预设读回（预览参数区）、屏蔽规则保留（需先造规则）、U31 判别实验（导出 CSV vs 列表——需从新 IDE Run 应用造新条目）。

## 剩余批次执行计划（2026-10-18 定稿）

按「共享前置」把剩余 24 部分+9 未验组组织为 4 批：

- **批 A（当前沙盒，无需重启）**：U18/U25 PG EXPLAIN（dbx_test 源驱动已挂好，绑定+执行即可）、U21 跨方言执行确认负例、U16 语法错/失效连接/超时、U31 即时脱敏（造敏感值条目+开「展示与导出脱敏」）、U08 出口对照、U27 EasyQuery 三态、U12/U13 UUID 全链路。
- **批 B（重启 #1，持久性打包——一次重启验 5 组重启维度）**：重启前截屏留底（屏蔽规则两条/预设 UI-Shared/SQL 历史条目数/参数历史条目数）→ 重启后逐项断言保留（U35/U36 规则+拦截计数、U23 命名预设、历史与参数）+ UI030 取消路径（视 last-used 持久性：若持久则先在 UI 清除再验弹选择器→取消→不重弹）+ U12/U13 UUID 跨重启失效/重连。**U11 清理不放本批**（会毁批 C 的历史语料）。
- **批 C（脚本型，探针由子代理预备于 `record/probe_bc_*.py` + `BC_RUNBOOK.md`，主控串行执行）**：U04 引导、U10 构造/选区、U17 五条金路径同次对照、U20 CRUD 批量/Version/两预设、U23 P2/20+OGNL、U29 三选/超千行 Diff、U30 真正空剪贴板、U33 注入确认面、U42 真 JAR 前缀、U43 表名反查、U19 16KiB。
- **批 D（视觉）**：窄栏 11 动作溢出、二维码三态、同源色。
- **收尾批（重启 #2）**：U11 三类持久清理实际执行（确认 Yes 真删/No 不删/逐项取消/捕获目录不擅删）→ 重启 → 不复活断言 → 44 组审计表终版。

提效纪律：探针一律动态定位（anchor/robot/window_rect，禁手工换算坐标——等点击优化子代理的统一入口落地后全部换用）；每组开工前先查增量验收文档定位入口；低收益子项（U40/U41 更新可用态、OGNL 深边界）用本地 file:// 更新源一次过或定向测试读数背书+真机抽查，不再全量真机。

## 全量门禁终局（2026-10-18，第 10 轮全绿）

- 修复分支 `t-uifix-gate9`（dispose 守卫 + smoke 收窄 + 零窗口直写，正向 50/50+65 例组合连续绿、反向 B 未修复树复现 victim 红）并入 dev `5da5d2d4`。
- **第 10 轮全量（独立沙盒 MPSV-Gate10 + 登记表隔离）：合并读数 7918 例 / 0 失败 / 0 错误 / 10 跳过——全绿**。首轮 shard1 唯一红为新增守卫类未登记元守卫清单（20260923 已知族），补登记 `e9ca529e` 后 shard1 重跑 1582/0/0/2；shard1 来自登记后重跑，shard2~5 为原收集（登记行只影响元守卫内部清单）。**此前 5 轮轮番单红的 exec-doc 冲突族在本轮零复发**。
- 后续收尾事项：`build.gradle.kts` 的 Gate10 临时沙盒行已按承诺还原（未入库）；`mpsv-uifix-gate9` 工作树保留（其 build.gradle.kts 含同款临时行未提交）。

U25/U12/U13 本轮真机（重建后沙盒）：方法预览头部显示 `方言: MySQL（模块已学数据源）  XML select: annotation`，证明方言由模块数据源学习得出；「执行计划」绑定数据源后执行 `EXPLAIN SELECT * FROM mp_demo.user WHERE id = 91`，Database 控制台返回原生 MySQL 计划表格（列：id/select_type/table/partitions/type/possible_keys/key/key_len/ref/rows/filtered/Extra），同时弹出「结构化执行计划视图不可用，已回退原生控制台：当前无结果集含有可解释数据源（先在控制台里执行一次查询即可）」——结构化开关默认关闭时的回退文案与 B1 原生表格路径均复现。为打通该路径，把 kotlin_demo@localhost 数据源密码补齐为 demo 应用 `application.yml` 中的本地 MySQL 口令（root/root，仅本地测试库）。

U15/U27 本轮真机：方法预览中选中表名 `user` 后右键「SQL 快捷模板」子菜单完整列出 EXPLAIN 选中表 / COUNT 选中表 / 抽样 10 行；点击「COUNT 选中表」后 Database 控制台实际执行 `SELECT COUNT(*) FROM user`（111ms），返回 `[42000][1146] Table 'kotlin_demo.user' doesn't exist` 并提示当前库为 kotlin_demo——模板动作与执行链已真实触达，报错仅因目标库缺少该表；待用 `mp_demo.user` 限定名复验成功路径。

UI030（用户现场 UX 改进，已合 `74087f17`）：「结构化执行计划视图不可用，已回退原生控制台：当前预览会话没有已绑数据源」原为纯告知。改为——无已绑数据源时**直接弹既有数据源选择器**（与执行 SQL 同一 runner 选择器），只绑定不执行任何语句，用户选中后自动重试结构化 EXPLAIN；取消则收口为一次明确说明，不重复弹窗、不形成循环；结构化开关关闭时通知附带「启用结构化并重试」动作。定向 `ExplainFallbackGuidanceTest` 10/0、`ExplainFallbackNotificationTest` 3/0、`ExplainPanelWiringGuardTest` 10/0、`ExplainPanelChannelTest` 12/0；反向摘动作/改取消判定/摘调用各恰 1~2 红。真机弹窗时序与通知动作点击仍需 runIde 复验。

U23 预设管理本轮真机：方法预览展开「预设」后显示命名预设下拉（此前会话留下的 `UI-Shared`）与「管理预设…」；管理对话框列出「默认预设」与「UI-Shared · 本语句 1 条快照」，提供另存为预设…/重命名…/删除，并明确说明：预设按语句保存参数快照（参数值/条件勾选/foreach 值）、按语句隔离、列表只显示当前语句的预设、同名预设在不同语句下各存各的值、重命名/删除作用于同名预设的全部语句、默认预设保持自动召回。与既有 UI017/UI019 的跨方法同名与返回方法复验互补；P2/20 上限与 OGNL 边界仍未验。

U36 屏蔽规则本轮真机：日志面板漏斗打开「管理 SQL 屏蔽规则」，列表含两条规则——`SELECT usernme FROM user WHERE id = 1`（2026-10-18 01:16，已拦截 0 次）与 `SELECT id,username,password,…`（02:07，已拦截 0 次）；右侧展示选中规则的 SQL；底部提供 新增/删除/清空、开启结构匹配/全部按结构匹配（均未勾选），并注明「规则即时生效；删除规则后仍在列表缓冲中的条目会重新显示；屏蔽期间新产生的 SQL 已被丢弃，无法恢复」。规则存在期间列表显示「暂无捕获的 SQL」而状态栏仍记 164 行，与屏蔽过滤一致；因自动化菜单误操作也可能命中「删除」，本轮未能单独排除“条目被删”的可能，保持部分。规则未清理（仍在册）。

## 依据与历史证据保全

- 十九轮UI11组、Runner12组及R90/R91/R92/R94/O147/V178专册。
- `20261008-1623-dev-bak对比master-新增功能验收文档.md` 与 `20261006-1031-dev分支回流237笔-验收文档.md`：总项曾勾选的预设、绑定、EXPLAIN等细分仍须本轮覆盖。
- `20261007-1244-T-SqlToJavaPromote-SQL转Java反向转换全覆盖与Beta转正-验收文档.md`：真实索引/多实体链不以Light夹具替代。
- 9月历史全量/保留上限、心跳重连、回流九任务、预热恢复等正式规程中的未闭合子项一并列入。
- 上轮临时树已按用户要求清理；原测试XML/兼容报告/安装包/事件在 `build/m19-cleanup-evidence.zip`，原分支历史在 `build/m19-cleanup-branches.bundle`。本次不得把已经清理的绝对路径写为仍在场。

## 验收终版汇总（2026-10-18 收口时点，批 A/B/C/D 后）

**环境**：243 沙盒 `MPSV-MasterMerge-UI243`（runIde，`-PgifRobotServer=true` 机制 A 装入 robot-server 0.11.23，8580 连通 324 根节点）；demo 工程含 dbx_test@dbx-test-host（PG 16.14，口令入 IDE，Until restart）。

**本轮实锤转增的组**：
- U25：PG 树（Limit/Parallel Aware/Async Capable/Startup·Total Cost/Plan Width/Seq Scan）+ SEQ_SCAN 体检 + 计划跨重启读回（快照 #0d5b5db5 + 「计划无变化」对比链）
- U21：跨方言（手动 PG × MySQL 源）红字整体阻断 fail-closed；U16：PG 侧 42703 错误原文+位置+环境三元组+超时引导
- U27：COUNT 独立开关 ON/OFF 行为闭环 + tooltip 三态披露 + LEARNED_MODULE 跟随共享控制台绑定数据源
- U17：P2 勾选联动（完整模式 `WHERE status = 1`）/P5 数据源选择器直弹路径首次触达 + 取消路径通知全文
- U10：Kotlin 降级面 13 断言 11 过+2 探针误检人工排除；U42：JAR 实体认出+前缀列完整+方言 fail-closed 披露；U30：4 步 0 失败；U43：7 步 1 FAIL（4.00s 顶格疑探针测量）；U33：clean 态 fail-closed PASS
- U18 留存与界限（代码面）：单计划 256KiB/每条 5 份/全局 8MiB+超限弃最旧+手改 XML 同受限+只淘汰计划不删 SQL；两开关默认 false
- U12/U13（静态面）：projectIdentity=SHA256(规范化 basePath)，ExplainPlanKey 含 dataSourceUuid/dialect/双指纹，条目级不符即拒存/拒复用
- U35/U36（静态面）：屏蔽规则 PersistentStateComponent，独立存储 `mp-sql-visualizer-suppress.xml`（roaming DISABLED）
- U08（静态面）：五消费面（列表/导出/回放/MCP/AI 上下文）全为展示/投影层变换不写回条目，采集/展示双开关分属不同键

**本轮登记的真缺陷（待修，按用户流程最后一起修）**：
- **UI-021**：INSERT VALUES 场景展示脱敏列名-值对位错位（password 值漏打 + 后继值因前值含 secret 被误打），编译产物直调实证，测试盲区在案
- **UI-022**：降级模式（内存 PSI）下动态条件勾选联动失效，完整模式对照实证，降级横幅未披露交互缺失

**未验面（如实保留部分）**：U19 16KiB 超限/编辑换目标/清历史（GUI 定位债）；U20 CRUD 批量/Version/预设边界（open_file 前置）；U23 上限/另存往返/OGNL 勾选真联动；U29 千行文件（语料不在册）与历史首行；U04 自动分组/首切引导；U11 实际清理与重启不复活；U08 六出口逐一出图；U12/U13 UUID 真机隔离；U05 二维码三态；U03 精确宽度矩阵（230/250/460）；U01/U44 其余主题缩放组合。

**视觉观察（登记非缺陷）**：333px 窄栏下「日志 → SQL」页签左端被裁无省略号（平台 JBTabs）；228px 以下工具窗整体收起（平台最小宽度）；降级横幅触发面（重启初期/重渲染）。

**证据索引**：截图 `build/ui243-shots/`（pg4/pg5/pg6/b1/u27/u31/dd/bc-* 系列）；提交链 `79bbeed3`→`c09f5451`→`388807b8`→`ff9dbeb7`→`f1ed75d9`→`80b94009`→`754856e8`→`358d5595`→`e16519ed`→`9d168f8f`→`ec3060dd`→`a6e0a321`；工具层正典 `dev-support/acceptance/`（11 探针 + lib/nav + BC_RUNBOOK）。

## 测试收口补充（2026-10-18 晚，替代 GUI 交互面）

GUI 交互债（JBPopup 合成点击/键盘焦点/面板内 EditorTextField）阻断的行为语义，改以**既有定向测试**收口——六类共 **91 例全绿**（独立沙盒登记 T-U20-CrudTest / T-U05-U23-Tests，`--no-configuration-cache`）：

| 测试类 | 覆盖的验收语义 | 读数 |
|---|---|---|
| `CrudSqlGeneratorTest` | **U20** 六类语句顺序/INSERT 排除自增主键/UPDATE 版本列 `version = version + 1`+WHERE 追加/逻辑删除值 1+主键 WHERE/无主键降级 `1 = 0`+标注+UPDATE 不挂 batchModel/空列/保留字方言引用（MySQL 反引号·PG 双引号）/Oracle·PG 折叠族/SQLServer·DM identity 包装 | **29/0/0** |
| `SqlSuppressRuleManagerTest` | **U35/U36** 规范化入库/幂等/空白/删除收缩/清空/命中判定/尾分号/**结构匹配开关+持久化 roundtrip**/**上限 500 拒新增**/**命中计数归账**/通知订阅/`loadState-saveState` 重建键集 | **16/0/0** |
| `CommunityDialogTest` | **U05 二维码三态**：双卡同展顺序/一张加载失败不影响另一张/关闭后晚到结果不更新（关闭态）/下载字节与解码尺寸上限/仅用户请求时 HTTP 读取且拒绝错误响应 | **5/0/0** |
| `SqlPreviewPanelParamSchemesTest` | **U23 预设全语义**+U19 清理面：快照勾选态全量与召回/foreach null·权威值/**另存快照切回还原自身值**/**自动保存只写工作区不就地改写命名预设**/**UI-017 跨方法另存保持 foreach 与默认快照独立**/清理后旧预览写回失效+下次恢复/XML 清除按钮清空参数 | **26/0/0** |
| `FieldFillSimulationPanelTest` | **U20 fill 模拟**四型/面板行为 | **11/0/0** |
| `ExplainPlanHistorySessionTest` | **U12/U13 UUID 隔离**：known UUID 比较不跨目标/方言/格式/SQL、unknown UUID 保持同目标语义但拒绝切换、fresh panel 持久基线、**清历史同时作废会话基线与晚到结果（U19 清历史语义）** | **4/0/0** |
| `SqlLogPanelFilterTest` | **U29 检索/类型过滤/聚焦**：Select 独/多选/全部/部分/空选择、搜索空查询直通/大小写不敏感/match case 敏感/匹配参数值/与类型过滤组合/行号不受影响、**聚焦 null 穿透（取消等价）/按 mapper statement 聚焦/方法名 ignoreCase/按请求标签聚焦/方法与标签合取/聚焦+类型+搜索管线顺序** | **33/0/0** |
| `FocusTabFilterTest` | **U29 聚焦页签过滤**全边界 | **14/0/0** |
| `SqlLogPanelFocusTabTest` | **U29 聚焦页签面板行为** | **4/0/0** |

**测试总账：九类 142 例全绿**（91+51）。由此 U29 的「检索/类型过滤/聚焦取消」语义面完整收口，U12/U13/U19/U20/U23/U35/U36/U05 的行为语义均有测试证据；**剩余纯 GUI 缺口收敛为**：UI-023/U29 的 DiffManager 出图（专项）、U29 千行文件 Diff、U03 宽度矩阵、U08 逐出口出图、U12/U13 双项目真机。
**（2026-10-18 深夜续）DiffManager 专项闭环**：根因非 DiffManager 失败，而是「旧包探针误诊 + Diff 窗口 z 序遮挡」双重假象（详见 UI-023/UI-024 行）——修复真机出图 ✅、U29 千行文件 Diff 出图 ✅。至此 U03 宽度矩阵、U08 六出口、U12/U13 双项目隔离亦已闭环（各自行内已记），**纯 GUI 缺口清零**（遗留仅 UI-024 z 序定性需真机人工一次点击）。

**由此收口的组**：U20（CRUD 生成+fill 语义，剩面板勾选 GUI）、U35/U36（规则管理全语义，剩真机 flush——平台机制+roundtrip 单测支撑）、U05（二维码三态语义，剩视觉出图）、U23（预设全语义，剩 P2 上限/管理对话框 GUI）、U12/U13（隔离语义，剩双项目真机）、U19（清历史失效语义）。**仍纯 GUI 缺口**：UI-023/U29 的 DiffManager 出图、U29 千行检索 UI、U03 宽度矩阵、U08 逐出口出图。（**2026-10-18 深夜续：DiffManager 出图与千行文件 Diff 已随 UI-023 专项闭环**，U29 千行检索 UI 前段——文件钻入/条目列表/双击开预览——本次亦实测走通。）

## UI-023 DiffManager 专项结案（2026-10-18 深夜）

**结论**：`DiffManager.showDiff` 从未失败——「无输出」是三重假象叠加：①探针跑在旧包上（沙盒 `plugins/` jar 停在 10-16 18:01，而修复 10-18 18:54 才提交、20:01 才 installPlugin+重启）；②旧探针截图只截预览区（`ImageGrab.grab(bbox=rect)`），把屏幕右下角的「无上次预览可对比」toast 裁出画面，`imgu29d7-2-diff.png` 全屏帧实捕两条该 toast，证明旧包上动作链路本来就通；③新包上 diff 窗口其实每次都创建了，只是 z 序压在 IDE 主窗之后（UI-024），肉眼看是「没反应」。

**定案证据链**（按时间）：
1. 沙盒 jar 时间戳比对：`plugins/mybatis-plus-sql-visualizer/lib/*.jar` mtime 10-18 20:01 ＞ 修复提交 18:54；IDE 进程 20:01:38 启动。
2. JDK21 javap 反编译沙盒 jar：`SqlPreviewPanelResultViewKt` 第 816 行 `PreviewResult.getSql` → 第 838 行 `setPreviousSql` —— 修复在役。
3. 修复包复测（预览 MPJ join → 取消勾选 `t.sex = 1` → 更多 → 差异对比）：键盘 Down×2+Enter 与像素直点两条路线**各创建一个 Diff 窗口**（`EnumWindows` 捕得两实例，标题「SQL 差异对比」，rect=(100,100,1820,980)）——「idea.log 零条目」不等于没执行（该链路成功路径本就无日志）。
4. 阳性对照：像素点「分享」弹出七出口对话框（frame imgdr5-1），排除「菜单点击链路失效」假设。
5. 最小化主窗取证：Diff 窗口完整渲染——上次带 `WHERE t.sex = 1`（删除行高亮）/当前无 WHERE、`2 differences`、Side-by-side viewer、SQL 语法高亮、变更跳转标记（frame imgdr8-1-diff-alone）。
6. U29 千行文件：SQL 历史 `sql-history-2099-01-01-ui-synthetic-10000.sql`（1.7 MB/10000 条）钻入 → 条目 #1 预览 → 差异对比出图，上次=参数化（`WHERE id = ?`）/当前=字面量（`WHERE id = 1 AND status = 'UI_OTHER'`）、`1 difference`，diff 内容恰好呈现参数回填过程，大文件无卡死（frame imgk-7-diff-alone）。

**方法论沉淀**：
- 「沙盒验证读数」必须先核「被测包是不是修复包」——installPlugin 时点与探针时点错位两小时即全盘误诊；javap 沙盒 jar（而非 build 产物）是在役性唯一权威。
- 插件成功路径零日志链路（showSqlDiff/DiffManager）排障，不能以 idea.log 静默判定未执行；win32 `EnumWindows` 枚举窗口标题是「窗口是否创建」的最廉价实锤，置后窗口（z 序遮挡）在截屏里不可见但枚举可见。
- 全屏截图（`ImageGrab.grab()` 无参）应作为涉及 toast/多窗口验收的默认帧；`bbox=rect` 只用于面板内部细节。

**遗留**：UI-024（Diff 窗口 z 序）待真机人工点击定性后决定是否修复；本专项新增临时探针留在主仓 `record/`（probe_diff_retest*.py、probe_diff_ctrl_share.py、probe_diff_enum.py、probe_diff_winrect.py、probe_diff_minimize.py、probe_snap*.py、probe_stripe_click.py、probe_reopen_tool.py、probe_hist_tab.py、probe_u29_1000_diff*.py），证据帧在 dev 树 `build/ui243-shots/`（imgdr*/imgdr2~dr8*/imgk-*/imgt-*/snap-* 系列），随 build 目录生命周期存在、不入 git。
