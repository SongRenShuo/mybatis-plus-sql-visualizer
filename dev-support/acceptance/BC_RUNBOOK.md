# 批 C 探针执行清单（BC_RUNBOOK）

口径权威：`mybatis-plus-sql-visualizer-dev/docs/验收文档/20261016-T-DevFullUI-合流后全功能沙盒验收记录.md`（下称「主册」）44 组审计表 + 增量记录；分功能步骤引用 `docs/验收文档/20261008-1623-dev-bak对比master-新增功能验收文档.md` 与 `20261007-1244-T-SqlToJavaPromote-*-验收文档.md`。

## 总前置（每组开工前核一遍）

1. 沙盒：`MPSV-MasterMerge-UI243` 已起、全屏+置顶、演示工程 `mybatis-plus-sql-visualizer-demo` 已开、无遗留模态弹窗。脚本自寻 PID（`build/<沙盒>/…/config/.lock`），跨沙盒用环境变量 `BC_SANDBOX=<沙盒名>` 指定。
2. 数据源：last-used `mp_demo` 在场（此前 UI030 批已绑定过即可）；U17-P5 / U19 需要它。
3. 执行方式：**一律提权跑**——`record/_run_elev.bat probe_bc_UXX.py`，读数看 `record/_elev_run.log`。直接双击/非提权跑会被 UIPI 挡键。
4. 定位路线优先级：robot-server（`//div[@accessiblename=…]`）→ anchor 模板 → 窗口几何。**没有手工屏幕坐标**。robot-server 未启时脚本自动降级并把语义断言落在截图（日志里会打一行「robot-server 不可用」）；标了「必须校准模板」的步骤没有模板就别跑。
5. 截图统一落 `mybatis-plus-sql-visualizer-dev/build/ui243-shots/bc-*.png`；每组收口日志尾部打 `==== UXX 收口：N 步，失败 M ====`，M=0 才算探针自身全过。
6. 串行执行，**不许两个探针同时跑**；U30 会清空系统剪贴板，跑之前别留要用的内容。

## 每组清单（前置 / 坐标怎么来 / 断言 / 失败判据）

### U04 快速预览首次引导 — `probe_bc_U04.py`（8~12 min）
- 前置：捕捉开启；`dev/build/bc-u04-hist` 可建；设置页存储组**建议先人工把捕获日志保存目录指到 `dev/build/bc-u04-hist` 并开「自动保存历史 SQL」**（脚本会尝试按名点「自动保存历史 SQL」，目录字段自动化不稳，见含糊点 ①）。
- 坐标：设置开关/历史页签 robot 按名；文件打开走 `open_file`（像素护栏 + Enter 检测重试 + 窗口几何兜底双击）。
- 断言：专属目录恰 3 个历史文件（文件系统，硬断言）；首切历史不手动刷新即显示 3 条（robot 文本「3 条」，无 robot 降级截图人工数）；引导跨页不重弹（气泡区指纹）；`onboardingAnchorTipShown` 在沙盒 options 配置可核。
- 失败判据：目录 0 文件=捕捉/自动保存链路没通（环境问题，不判产品）；首切仍 0 条=UI-004 回归。
- 重启维度（RUNBOOK 步骤）：改沙盒 `config/options/mp-sql-visualizer.xml` 删 `onboardingAnchorTipShown` 行 → 重启沙盒 → `_run_elev.bat probe_bc_U04.py --guide-recheck`——断言打开可预览文件**不**再弹引导（持久化「至多一条」）；若要验「首弹」形态，需在删除标志后的**新会话首开文件**时刻盯气泡（该瞬时人盯，脚本只收尾核对不重弹）。

### U10 Kotlin 构造行/选择区 — `probe_bc_U10.py`（5~7 min）
- 前置：无特殊。入口 `KotlinWrapperScenarios.kt` L46 ktQuery 链（主册 L200 引 :47 为空行偏移，以 L46 为准）。
- 坐标：文件/光标 `open_file`+`goto_text`（链内唯一片段 `.isNotNull(KUser::email)`）；gutter 检测=编辑器区截图上 gifkit 绿/紫像素聚类（条带 x=30..150 相对编辑器）。
- 断言：构造行与选择区各一次——气泡区变化（通知出现）+ 编辑器无插件 gutter（count=0）+ 无预览浮窗；普通行（@Service）有反馈且无预览；Java 正对照（UserServiceImpl.java:36）有 gutter 且预览浮窗出现。
- 失败判据：气泡无变化=主动入口没给限制说明；Kotlin 行检出 gutter=假入口；**Java 正对照失败时本轮全部 Kotlin 断言作废**（先修环境）。

### U17 五条金路径同次对照 — `probe_bc_U17.py`（12~18 min）
- 前置：last-used mp_demo 在场；SQL 历史有语料（P4 用；空则该步记无语料）。
- 坐标：五个入口全 `open_file`+`goto_text`/`goto_col`（Wrapper 分页 UserServiceImpl `new Page<>…`；XML `selectUserListByXml`；HQL CommonDaoHibernateImpl L231 col62；历史首条 robot JBList 顶部或模板 `bc_hist_first_row`；EXPLAIN 按钮 robot「执行计划」/模板 `bc_btn_explain`）。
- 断言：每条路径产出四元组截图 q1 参数 / q2 SQL / q3 面板含数据源指示行 / q4 结果；P2 XML 填参后 SQL 区指纹必变；P5 点执行计划后面板必变（UI030 静默绑定+结构化表格形态）。**语义（参数值=SQL 片段=目标=结果一致）由截图人工落表，禁止跨样本拼证。**
- 失败判据：某路径浮窗不出=该入口回归；P5 面板无变化=按钮没点中或绑定失败（看 Database 工具窗）。

### U20 CRUD 批量/Version/两预设 — `probe_bc_U20.py`（10~15 min）
- 前置：无特殊；**全程不点「执行」**。
- 坐标：实体右键菜单「生成 CRUD SQL」robot 按名/模板 `bc_menu_crud_sql`（**必须有一个**，否则候选段中断）；批量三入口 `open_file`+`goto_text`（`this.saveBatch(toInsert)` / `this.updateBatchById(toUpdate)` / `userService.saveOrUpdateBatch`）；「管理预设…」模板 `bc_btn_manage_presets`。
- 断言：六候选逐项切换有画面变化+截图；批量 INSERT/UPDATE/UPSERT 三形态预览开启；updateWithVersion 预览开启；fill 模拟开关可点开、两预设另存往返可完成。
- 失败判据：CRUD 菜单点不中=定位路线缺失（校准模板后重跑），不算产品失败；候选切换全程无变化=候选控件形态与假设不符（截图裁量）。语义（version 在 WHERE 不在 SET、UPSERT 全 ?、AUTO 未勾）从 `bc-U20-*` 截图人工落表。

### U23 预设 20 上限+OGNL — `probe_bc_U23.py`（15~22 min）
- 前置：`selectByAnnotationId` 现存命名预设越少越好（上限触发点=既有数+21）；U29/U30 不依赖本组清场结果。
- 坐标：管理对话框全 robot 按名（「管理预设…」「另存为预设…」列表项名=预设名）；上限文案 robot contains「已达上限」。
- 断言：连存命名预设直至上限提示出现（robot 文本命中为硬断言；无 robot 时降级=逐张 save 截图人工分拣）；每张截图存档；OGNL 两边界（username 空串条件消失 / age=0 条件保留）用 SQL 区指纹+截图；末尾自动清场删 BC-Pxx（默认项受保护）。
- 失败判据：23 次另存无上限提示=上限未生效或同名冲突吞次数；清场删不掉=残留 BC-Pxx 需人工（不阻塞他组）。
- 重启维度：跑完后按批 B 打包重启 → `_run_elev.bat probe_bc_U23.py --after-restart`（断言管理对话框可开、既有命名预设截图留底）。

### U29 检索三选/超千行 Diff — `probe_bc_U29.py`（8~12 min）
- 前置：历史 ≥3 条语料；万条合成文件在册（目录名子串缺省 `ui004-b5`，可 `BC_U29_BIGFILE` 环境变量改）；找不到万条文件时 K 段记「无语料」不判产品失败。
- 坐标：历史列表首行 robot JBList/MyList 顶部（或模板 `bc_hist_first_row`），行距按 34px 内推（**唯一一处行高近似**，选错行不影响「菜单置灰/Diff 真开」两个判据）；菜单「对比选中两条」robot 按名/模板 `bc_menu_compare`。
- 断言：三选右键菜单截图（置灰人工核对——组件树没有 enabled 位，不作自动断言）；双选点「对比选中两条」后**独立 Diff 窗口出现**（EnumWindows 新窗口，硬断言）；万条文件内双选同法。
- 失败判据：双选 Diff 窗不出=入口回归；三选若误开 Diff=负例失败。

### U30 真正空剪贴板 — `probe_bc_U30.py`（4~6 min）
- 前置：**会清空系统剪贴板**；工具窗可达。
- 坐标：页签「日志 → SQL」与按钮「从剪贴板读取」robot 按名/模板 `bc_tab_log_to_sql`、`bc_btn_read_clipboard`。
- 断言：正对照（有日志 → 读取 → 输入区填充）；真空态双证（`EnumClipboardFormats==0` + `GetClipboardData` 为 None，硬断言）；点读取后「剪贴板中没有文本」提示（robot contains，降级截图）+ **输入区指纹零写入**（硬断言）；收口恢复无害剪贴板。
- 失败判据：真空态输入区被写入=读空剪贴板回填旧内容，登记；提示文案不匹配=截图裁量。

### U33 R24 原生注入 — `probe_bc_U33.py`（默认模式 4~6 min；--check-tree 即时）
- 三态文件系统断言（无需 IDE，demo 仓直接跑）：
  - `python probe_bc_U33.py --check-tree clean`——native-mybatis-demo 无生成物、config 无 `<plugin>`（无 config/非法 plugins 形态的 fail-closed 面）；
  - `--check-tree injected`——`SqlSourceInterceptor.java`+`SqlVisualizerConfig.java` 在 src 树、`<plugin interceptor=` 已注册；
  - `--check-tree corrupted`——`<plugins>` 结构破坏/多 config 造态后，断言**仍未注入**。
- IDE 确认面（默认模式）：设置页「自动注入源码捕获拦截器」开关在册（**脚本不点它**，点了会真生成文件）；SQL 日志列表截图（注入+应用重启后，原生条目应带源码位置）。
- **需要重启/造态配合的步骤（主控裁量排程）**：①造 injected 形态=沙盒 IDE 打开 native-mybatis-demo → 设置页勾「自动注入源码捕获拦截器」→ 确认成功通知 → `--check-tree injected` → 启动该原生应用 → 回 IDE 看 SQL 日志源码位置面；②clean/corrupted 造态=临时移走 `mybatis-config.xml` / 改坏 `<plugins>`（改前备份），重复 ① 的启动观察后**还原**；③Boot 回归=mybatis-plus-demo 照常启动有源码位置（既有能力，不注入 native 两件）。
- 失败判据：corrupted/clean 态出现生成物=fail-closed 被击穿，登记；injected 态缺 `<plugin>` 注册=装配点缺陷。

### U42 真 JAR 嵌入前缀 — `probe_bc_U42.py`（5~8 min）
- 前置：无特殊（实体在依赖 JAR 的形态由工程自带）。
- 坐标：两入口 `open_file(WmsInventoryJarQueryService)`+`goto_text`（拼接变量 `'"from WmsInventoryEntity inv "'` / StringBuffer `'sb.append("from WmsInventoryEntity inv ")'`）。
- 断言：宿主 HQL 行有插件 gutter（JAR 实体被认出，硬断言）；预览浮窗开启；语义三查（表名 `wms_inventory_jar_demo`、前缀列三者任一、不串兄弟表 `wms_inventory_embed_demo`）——robot 能读预览编辑器文本才自动断言，读不到**诚实降级**放大截图人工核对（不假失败）；填参后 SQL 区联动截图。
- 失败判据：gutter 缺失=依赖库实体识别失效（先查 U43 的后台扫描开关）；截图确认裸列名（`base_qty` 等）=复现报障缺陷。

### U43 表名反查 — `probe_bc_U43.py`（8~12 min）
- 前置：mp-jar-model-lib 在依赖（B3 已配）；首趟全库扫描可达数十秒（脚本等 120s）。
- 坐标：SQL → Java 页签、转换按钮、对话框按钮全 robot 按名/模板（`bc_btn_convert`、`bc_btn_scan_libs`、`bc_btn_browse`）；「选择实体类」对话框用 EnumWindows 独立窗口判出现/消失。
- 断言：JAR 表 SQL（`ui_reservation_ledger`）转换触发对话框且行内无候选（快路径未命中）；点「在依赖库中查找」后候选刷新（行区指纹或 `LegacyBooking` 文本命中）；二次刷新保留已选；取消后对话框消失；Goto Class 逐字符键入 4s 内全部有响应且最差 ≤ 基线 2×（不卡硬断言）；设置项「后台扫描依赖库以反查实体」在册（只存档不改值）。
- 失败判据：120s 无刷新=显式扫描失效；刷新后已选被清=保留语义破坏；键入超时=连续输入卡顿。

### U19 AI 上下文 16KiB — `probe_bc_U19.py`（5~7 min）
- 前置：last-used 数据源在场（不执行）；**会临时替换预览 SQL，末尾 Ctrl+Z 还原**。
- 坐标：预览内 SQL 编辑器点入按面板几何（1/4 高、1/3 宽，非手工坐标）；「更多」「复制为 AI 上下文」robot 按名/模板（`bc_btn_more`、`bc_menu_ai_context`——弹出菜单项**必须校准**或开 robot）。
- 断言（Python 直读剪贴板，全部硬断言）：块头 `-- SQL Visualizer AI context v2`；UTF-8 字节 ≤16384；出现 `omitted` 整段丢弃行；原 SQL 未整段回填；块内 `local-only; no acquisition`/`not obtained` 本地声明（零新查询的文本侧证）。
- 失败判据：块超 16384=预算未生效；无丢弃行却超限=截断行为与实现不符；剪贴板空=复制动作未发生（先查菜单定位）。

## 建议执行顺序（主控串行）

U10 → U42 → U43 → U17 → U19 → U30 → U23 → U20 → U29 → U33(默认面) → U04；
随后按批 B 打包一次重启覆盖 U04/U23 的重启维度（`--guide-recheck` / `--after-restart`）；
U33 的三态 `--check-tree` 与造态、重启穿插点见该组节。先跑 U10/U42 的理由：纯只读、无状态残留，可当定位路线冒烟（robot/模板缺口在低风险组先暴露）。

## 需要先标定的 anchor 模板（仅 robot-server 不启时必需）

`python anchor.py capture <名> <l> <t> <w> <h>`（坐标从当轮截图量，模板有纯色保护会拒绝无效样本）：
`bc_btn_exec_sql`（U17/U04 执行 SQL）、`bc_btn_explain`（U17 执行计划）、`bc_menu_crud_sql`（U20 右键菜单项）、`bc_btn_manage_presets`（U20/U23 管理预设）、`bc_menu_compare`（U29 菜单项）、`bc_btn_read_clipboard`（U30）、`bc_tab_log_to_sql`（U30 页签）、`bc_btn_convert`/`bc_btn_scan_libs`/`bc_btn_browse`（U43）、`bc_btn_more`/`bc_menu_ai_context`（U19）、`bc_hist_first_row`（U17/U29 历史首行）。robot-server 启了则全部不需要。

## 口径含糊点（主控执行前裁量）

1. **U04 目录切换自动化边界**：捕获日志「保存目录」字段在设置页的位置随窗口宽度漂，脚本只自动点「自动保存历史 SQL」开关；目录指到 `bc-u04-hist` 这步建议 RUNBOOK 前置手工完成，否则 A5 的「恰 3 个文件」会落到旧目录上（判失败属环境噪声）。
2. **U10 夹具行号**：主册 L200 写 `KotlinWrapperScenarios.kt:47`，实测 47 是空行、ktQuery 链在 46（版本漂移）。脚本用文本锚不依赖行号；落表时以「链行」表述，别照抄 :47。
3. **U17 「Wrapper 分页/UserServiceImpl.java:36」**：审计表把「Wrapper 分页」与「:36 构造行」写在一起，但 36 行是 `listByLambdaQuery` 的构造行（无分页），真分页在 `pageByWrapper`（~L168）。脚本两个都采（P1 用分页方法），四元组落表时分别登记，别把 :36 当分页样本。
4. **U20 「两预设」归属**：审计表把「两预设/清理/换目标」列在 U20，但其预设机制与 U23 的 ParamScheme 同源（面板「预设:」行）。脚本按「CRUD/fill 面板上的预设行」实现；若主控认定「两预设」指 FieldFill 专属快照（非 ParamScheme），U20-D 段结论只作参考。
5. **U29 行高 34px 内推**：唯一一处几何近似（历史列表行距）。它只影响「选中了哪几条」，不影响置灰/Diff 判据；若历史行显示密度设置改过，从截图重标。
6. **U29 万条语料**：万条合成文件在此前批次的 `ui004-b5` 目录，批 B/C 之间是否被清理未知；不在场时 K 段记「无语料」，是否补造由主控裁量（补造会动历史目录，U04 若后跑需重新空扫）。
7. **U23 起点预设数**：上限触发点=既有命名预设数+21，既有数含此前会话的 `UI-Shared`。触发点落在第几次由脚本日志如实记录，预期语义写「既有数+21」而不是死抄 21。
8. **U33 设置开关**：`autoInjectInterceptor` 是纯动作开关（勾选立即生成、自动复位），脚本刻意不点它；「单 config 成功注入」的触发动作由主控在 RUNBOOK 步骤里人工勾选完成，别让探针代劳（会真改 demo 源树）。
9. **U42 语义断言的自动化上限**：robot-server 的 `@text` 通常读不到编辑器内容，三查（表名/前缀列/不串兄弟）在无命中时降级人工——这不是探针偷懒，是组件树覆盖面的真实边界；若主控要全自动语义断言，走插件定向测试而非 GUI 探针。
