# T-RollbackSnapshotPreExec 回滚快照（执行前快照 / 改前改后对比 / 前后置脚本）· 功能验收文档

- **编制**：2026-11-26 15:00，对照分支 `t-rollback-preexec`（代码基线 tip `a387476ba`；实施链见该分支计划册 §九）。
- **覆盖范围**：该分支 **V1 + P1~P7 七段全部代码**——执行前自动快照（P1/P2）、项目级落盘与剪枝（P1）、改后采集与原生表格 diff（P3）、历史窗口与回滚 SQL 生成（P4）、日志面板路径（P5）、脚本引擎内核（P6）、脚本编排与三档作用域 UI（P7）。
- **本册定位（重要）**：**代码在 `t-rollback-preexec` 支、未合 master；本轮约定不合流**。因此本册是**「待合流后执行」的预备验收册**——步骤按分支代码反推写成，编制时**一条 GUI 项都没跑过**（不启动 runIde、不做桌面自动化）。合流后按册执行，读数填 §十二。
- **验收口径**：每项给「路径 / 步骤 / 期望 / 回退开关」。判定以眼睛看到的 UI 与磁盘产物为准；拿不到的登记「未取得」。
- **读代码方式（不 checkout）**：`git show t-rollback-preexec:<path>`；计划册同理读 `docs/开发计划/20261111-1107-T-RollbackSnapshotPreExec-执行前自动快照与改前改后表格对比-开发计划.md`。
- **分支侧已有读数（引用，不在本册重跑）**：实施记录 §九 记 P0~P7 全部落地；P1/P2 反向验证「参数守卫短路 → 恰 2 红」；P3 对齐器 10 例 + 平台桶 7 例；P4 回滚生成 5 例；P6 引擎 15 例（含 500ms 超时中断、缓存命中零重编译、空模板零编译）；P7 编排 6 例。CI 二/三轮 15 绿（`test (fast)` 5020 例 / 五个分片 883+903+969+1018+…）。**本轮本树不可跑**：工作树 `buildSrc/src/main/kotlin/mpv-runide.gradle.kts` 有他人在途未提交改动，`:buildSrc:compileKotlin` 直接失败。

---

## 〇、前置准备

| # | 动作 | 期望 |
|---|---|---|
| P1 | 从 `t-rollback-preexec` 构建安装插件（合流后则用 master 构建），打开一个**可写**工程 | 插件启用；工程根可写（`V7`：只读工程会降级，见 §九） |
| P2 | 配好 MySQL（或任一支持事务的库）数据源，准备一张**有主键**的表（建议 ≥3 行，含一个可改的普通列）与一张**无主键**表 | 两者都能在预览面板执行 |
| P3 | Settings → Tools → SQL Visualizer → 组 5「界面与操作」，找到两个新开关 | 「执行 UPDATE / DELETE 前自动留存改前快照」（默认**未勾选**）、「启用 SQL 前/后置脚本」（默认**未勾选**）+「脚本管理...」按钮 |
| P4 | 记下工程根路径 | 后续要看 `<项目根>/.sql-visualizer/snapshots/` |

---

## 一、开关默认态（N1）

### A1 默认关、关时零成本

**路径**：Settings → Tools → SQL Visualizer → 界面与操作。
**步骤**：① 全新安装后看开关；② 保持关闭，在预览面板执行一条 `UPDATE ... WHERE 主键 = N`；③ 看工程根目录。
**期望**：① 开关**未勾选**；② 执行正常，**无**任何快照相关状态行；③ **没有** `.sql-visualizer/snapshots/` 目录（零额外查询、零额外 IO）。
**回退开关**：该复选框本身。
**判读**：关闭态出现目录或状态行＝不通过（默认关是硬契约）。

### A2 开关文案说明成本

**步骤**：悬停开关看 tooltip。
**期望**：说明「执行前多一次只读查询、落盘目录、500 行上限 / 2 秒超时 / 保留 7 天 200 条 50MB、采集失败不阻断执行」。
**判读**：文案里没有「多一次查询」或没有容量口径＝文案不达标（用户要知道代价）。

---

## 二、执行前快照自动落（N2，①）

### B1 UPDATE：确认 → 采集 → 提交（顺序可观测）

**路径**：预览面板（mapper 语句预览或工作台预览）→ 编辑到一条单表 `UPDATE t SET c = 'x' WHERE id = 1` → 「执行 SQL」。
**步骤**：① 打开快照开关；② 执行；③ 看结果面板状态行；④ 立即看数据源里的行（DML 二次确认点「是」之后）。
**期望**：③ 状态行出现「改前快照已留存：N 行 × M 列（可回滚、可看改前/改后对比）」，N = WHERE 命中行数；④ 数据确实被改（说明采集没有卡住提交；采集**先于**提交完成，否则会查到改后状态）。
**回退开关**：§A1 复选框。
**判读**：状态行显示 0 行但 WHERE 明明命中＝采集 SQL 拼错；状态行完全没有＝编排链没接上。

### B2 采集在确认之后、提交之前

**步骤**：执行一条 UPDATE，在 DML 二次确认弹窗里点「否」。
**期望**：**没有**快照条目、**没有**采集状态行（确认被否＝整链中止，不浪费一次查询）。
**判读**：取消后仍出现「已留存」＝顺序错，记缺陷。

### B3 不可采集形态：静默跳过（降级矩阵）

**路径**：预览面板。
**步骤**：依次执行下列语句，逐条看状态行与 `snapshots/` 目录：
1. `SELECT * FROM t`；
2. `INSERT INTO t(...) VALUES (...)`；
3. 多表写：`UPDATE a JOIN b ON ... SET a.c = 1`；
4. 含未填值的参数：`UPDATE t SET c = ? WHERE id = 1`（字符串 `'?'` 不算）；
5. 含 `{{表达式}}` 占位（MBG 产物形态）。
**期望**：五种形态**都不采集**，面板链**全部静默**（`Plan.NotCapturable` 在面板路径直接 `onProceed(null)`，不刷状态行——这是刻意的「不留噪音」口径，原因字符串虽随 Plan 带回但 v1 面板不展示）；执行本身照常。
**判读**：任一形态落了快照＝不通过（尤其 4：把带 `?` 的 SELECT 打到库端会报错）。若因「静默」而怀疑没生效，去 `snapshots/` 目录核对条数，不要以「没提示」为失败依据。
**注**：`{{}}` 与字符串内 `?` 的判定是保守近似（宁可误拒不误放），字符串字面量里的 `?` 不触发放弃。

### B4 无绑定数据源：跳过并如实说

**步骤**：在**没有**绑定控制台数据源的预览面板里执行一条 UPDATE。
**期望**：状态行「改前快照已跳过：当前无绑定数据源。」，执行照常继续（fail-open）。
**判读**：静默跳过＝文案缺失（用户会以为开关坏了）。

### B5 采集失败不阻断

**步骤**：把数据源指向一个查询会失败的目标（或临时撤权限），执行 UPDATE。
**期望**：状态行「改前快照采集失败：<库端原因>」/「改前快照已跳过（被阻断）：…」；**执行本身照常进行**，不弹阻断框。
**判读**：因快照失败导致 DML 没执行＝违反 fail-open 契约。

---

## 三、快照存哪（②）

### C1 磁盘形态

**路径**：`<项目根>/.sql-visualizer/snapshots/`。
**步骤**：采到一条快照后打开该目录。
**期望**：① 一条 `<id>.json`（id ＝ 时间戳前缀 + 随机尾，按文件名排序即时间序）；② 一份 `index.json`（只存列表用元数据：id / 时间 / 类型 / 表 / 行数 / 状态）；③ 目录内 `.gitignore` 内容为 `*` 与 `!.gitignore`（业务数据不进版本控制，`.gitignore` 自身可提交）；目录在首次落盘时创建。
**判读**：数据写进内存 LRU 而不落盘＝不通过（那是旧 `RollbackSnapshotStore` 的行为，本批明确不复用）。

### C2 条目字段齐备

**步骤**：用文本编辑器打开 `<id>.json`。
**期望**：含 `id / createdAtMs / mode(UPDATE|DELETE) / connectionName / table / statementSql / snapshotSelectSql / columns / beforeRows / rowLimitHit / warnings / status`；执行成功后另有 `afterColumns / afterRows / afterCapturedAtMs`，`status` 由 `captured` → `compared`。
**判读**：`snapshotSelectSql` 缺失＝无法复核采集口径；`beforeRows` 为空但状态是 captured＝可疑。

### C3 行上限与截断标记

**步骤**：对一张 >500 行的表做全表 `UPDATE`（无 WHERE 或 WHERE 命中 >500 行）。
**期望**：`beforeRows` 恰 500 行，`rowLimitHit = true`；状态行仍报「已留存：500 行 × M 列」（用户可从此看出被截断）。
**判读**：超过 500 不截断＝内存与磁盘风险；截断但不标记＝用户会误以为全量。

### C4 保留与剪枝

**步骤**：① 造 201 条以上条目（或用旧时间戳的假条目）；② 重启 IDE 再进目录。
**期望**：按「7 天 / 200 条 / 50MB」先到先剪；启动时有兜底剪枝（`StartupPruner`，开关关闭时不跑）。
**判读**：条目无限增长＝剪枝没生效。本条也可只做代码复核（`KEEP_DAYS=7 / MAX_ENTRIES=200 / MAX_TOTAL_BYTES=50MB`）。

### C5 项目级隔离

**步骤**：两个工程各采一条。
**期望**：各落各自工程根，互不串台。
**判读**：A 工程的快照出现在 B 工程＝项目身份没进路径（PROJECT service 取错 basePath）。

---

## 四、改前 / 改后表格对比（N5，③）

### D1 改后采集：UPDATE 按主键回查

**路径**：执行一条命中 2~3 行的 UPDATE，完成后再打开历史（见 §六）。
**步骤**：① 执行；② 等结果出现后 1~2 秒；③ 打开 `snapshots/<id>.json`。
**期望**：`status = compared`，`afterRows` 与 `beforeRows` 行数一致（按主键对齐），改动列的单元格值已变。
**判读**：`afterRows` 仍为 null＝改后采集没触发（`afterExecTrigger` 只挂在执行成功回调上，失败/取消时不该触发——这正好可做反向判读）。

### D2 DELETE：改后恒为空（省一次往返）

**步骤**：执行一条 `DELETE ... WHERE 主键 IN (...)`。
**期望**：`status = compared`，`afterRows = []`；对比窗口里表现为「全部行被移除」。
**判读**：DELETE 之后还发了一次 SELECT 回查＝偏离设计（应先落空结果）。

### D3 无主键表：降级为「仅改前」

**步骤**：对 §〇 P2 的无主键表执行 UPDATE。
**期望**：条目 `status` 保持 `captured`；`warnings` 里出现「no primary key detected: after-image not captured (before-only)」；点对比时提示「仅改前」。
**判读**：硬造一个假主键去回查＝不通过（诚实降级优于错误对齐）。

### D4 打开对比窗口（唯一的用户入口）

**路径**：预览面板「更多」菜单 →「**回滚快照历史**」→ 选中一条 `compared` 条目 → 左侧「**查看改前/改后对比**」。
**期望**：弹出 **IDEA 原生 diff 窗口**；内容区左右两个**只读表格**（左改前 / 右改后），按主键对齐，行内改动单元格着色、新增行/删除行分别着色；窗口底部/状态区有对齐摘要（对齐方式 + 四类行计数）与对齐告警。
**判读**：
- 出来的是纯文本 TSV 对比＝走了兜底路线 A，登记降级（设计首选路线 B＝表格形态）；
- 表格能看、能横向扫、能关掉即判「通过（三条判据按计划 §3.6 的取舍）」；**F7 差异导航与「接受/回退」平台动作本就不提供**，缺失不算缺陷。

### D5 仅改前条目点对比

**步骤**：对无主键表的条目点「查看改前/改后对比」。
**期望**：弹信息框说明「仅改前、无改后镜像」，**不**开空的 diff 窗口。
**判读**：开出一个两边一样的空 diff＝不通过。

---

## 五、回滚 SQL 生成与执行（N6，④）

### E1 生成：UPDATE 逐行 CAS

**路径**：回滚快照历史 → 选中 `compared` 的 UPDATE 条目 → 左侧「**生成回滚 SQL**」。
**期望**：弹出只读 SQL 框（标题「回滚 SQL」），内容为**按行一条**：`UPDATE t SET <改动列> = <旧值> WHERE <主键> = <旧值> AND <改动列> = <新值>;`——即「改回旧值 + 乐观锁谓词（改后值必须仍在）」。
**判读**：生成的 SQL 只有 `SET 旧值 WHERE 主键` 而**没有** `AND 改动列 = 新值`＝丢掉 CAS，不通过（会把别人后来的修改覆盖掉）。

### E2 生成：DELETE 走回填插入

**步骤**：选中 DELETE 条目 → 「生成回滚 SQL」。
**期望**：生成 `INSERT INTO t (列...) VALUES (旧值...);` 形态（复用既有 `BACKFILL_INSERT`，合批/截断语义继承）。
**判读**：生成 `UPDATE`＝分支判错。

### E3 诚实评级

**步骤**：看回滚 SQL 框的头部/说明（若有）或代码侧评级。
**期望**：UPDATE 档评级为 **PARTIAL**（主键来自启发式、字符串化快照对 NULL 与空串不可区分）；无主键时评级为 **UNABLE** 且给「无法生成」而不是半成品。
**判读**：宣称 COMPLETE＝虚假等级，登记缺陷。

### E4 复制与执行（v1 边界，必读）

**步骤**：① 点「复制」；② 把 SQL 贴进预览面板/控制台执行。
**期望**：① 剪贴板得到完整 SQL；② **执行走用户既有链**（预览面板执行链的确认弹窗 / 方言闸 / 写安全门）。
**重要边界**：历史对话框 v1 **不做旁路执行**——那里没有面板上下文，「从历史直接跑」需要另起临时执行链，已登记为后续项。所以「回滚在事务内跑、报每语句影响行数、真实回执才落 `rolledBack`」这些语义，由**既有回滚执行链**（预览面板「更多」→「回滚 SQL」）承载，本批只把文本生成接到快照条目上。
**已知边界**：状态常量里 `rolledBack` / `stale` / `noRowsAffected` 三个**生产路径当前都不写入**（只有 `captured` 与 `compared` 会被写），所以在本批历史窗口里**不要**期待「回滚后那行状态变成已回滚」——那是既有回滚链 + 后续项的事。
**判读**：如果验收人期待「点一下就在事务里跑完」——那是后续项，不算本册不通过；但若历史对话框里出现了会执行 SQL 的按钮，反而要查是否越过了既有安全门。

### E5 手工改脏后再回滚（CAS 生效，硬判据）

**步骤**：① 采一条 UPDATE 快照；② 在执行前后**手工**把那行改成第三个值；③ 用生成的回滚 SQL 执行；④ 看影响行数。
**期望**：该行**命中 0 行**（CAS 挡住），数据保持手工改后的值，不被回滚覆盖。
**判读**：该行被覆盖＝CAS 谓词没生效，不通过。

---

## 六、历史窗口（⑤）

### F1 入口与形态

**路径**：预览面板「更多」菜单（十项，顺序固定：回滚 SQL / **回滚快照历史** / 导入参数 / 差异对比 / 分享 / 导出可移植 SQL / 导出回放脚本 / 复制为 AI 上下文 / 复制为 AI 上下文（含表列名）/ DML 影响面）。
**步骤**：打开「回滚快照历史」。
**期望**：非模态对话框，标题「回滚快照历史」；表格五列：**时间 / 类型 / 表 / 行数 / 状态**；单元格不可编辑；左侧动作四项：**刷新 / 查看改前/改后对比 / 生成回滚 SQL / 删除**；主按钮「关闭」。
**判读**：菜单里没有该项、或菜单顺序变了＝入口契约被破坏（有一条布局守卫测试钉住十项顺序）。

### F2 列表内容

**步骤**：采 2 条快照（一条 UPDATE、一条 DELETE）后打开。
**期望**：两行都在；时间格式 `yyyy-MM-dd HH:mm:ss`；类型显示 `UPDATE`/`DELETE`；行数为采集行数；状态显示 `captured` / `compared`。
**判读**：列表为空但目录有 json＝索引没建/没读（`index.json` 与条目文件不同步）。

### F3 刷新与删除

**步骤**：① 开着对话框再采一条，点「刷新」；② 选中一条点「删除」。
**期望**：① 新条目出现；② 该行消失，磁盘 `<id>.json` 被删、`index.json` 重建。
**判读**：删除后索引仍列出已删条目＝索引未重建。

### F4 重启后仍在

**步骤**：重启 IDE 再打开历史。
**期望**：历史条目照旧可看、可对比、可生成回滚 SQL（这正是新建项目级存储的目的）。
**判读**：重启后空＝又退回了内存 LRU 语义。

### F5 日志面板路径（P5）

**路径**：SQL 日志页签 →「日志转 SQL」→ 执行。
**步骤**：打开快照开关，在日志转 SQL 面板执行一条单表 UPDATE。
**期望**：同样落快照；状态经面板状态行提示；若该链**没有**共享控制台绑定，则跳过并提示「当前无绑定数据源」。
**判读**：这条路径完全无采集＝P5 没接上。

---

## 七、前 / 后置脚本（N7/N8，⑥）

### G1 开关默认关 + 风险明示

**路径**：Settings → Tools → SQL Visualizer → 界面与操作。
**步骤**：看「启用 SQL 前/后置脚本」复选框与 tooltip。
**期望**：默认**未勾选**；tooltip 明示「以当前用户权限执行任意 Java 代码、无沙箱——仅在完全信任脚本内容时开启」与「每次执行都写脚本日志」，并说明合并顺序「全局 → 数据源 → 语句级」。
**判读**：没有安全警示＝不通过（红线三道闸之一）。

### G2 脚本管理对话框五页签

**路径**：同页「脚本管理...」按钮（设置页是 APP 级，取当前打开的第一个项目）。
**步骤**：打开对话框。
**期望**：五个页签——**全局级**（前/后置两个编辑器）、**数据源级**（每个数据源一个子页签，无数据源时显示「（暂无数据源）」）、**语句级（当前预览）**（无面板上下文时整页禁用并提示「语句级脚本挂在预览面板上；请从预览面板上下文打开本对话框编辑。」）、**变量表**（两列：变量名 / 值，可编辑）、**脚本日志**（只读多行区）。
**期望补充**：编辑器内是**模板**——前置 `public class SqlPreScriptImpl extends SqlPreScript` 覆写 `boolean handlerSql(SqlScriptLog log, SqlExecContext ctx, IEnv env)`；后置 `public class SqlPostScriptImpl extends SqlPostScript` 覆写 `void handlerResult(SqlScriptLog log, SqlExecResult result, IEnv env)`。
**判读**：模板没预填＝用户不知道签名；语句级在设置页里也能编辑＝作用域语义错（应为「挂在预览面板生命周期上」）。

### G3 前置脚本否决（软否决）

**步骤**：① 打开脚本开关；② 全局级前置脚本改成 `handlerSql(...) { log.println("veto test"); return false; }`；③ 在预览面板执行一条 UPDATE。
**期望**：**执行不发生**；结果面板状态行「本次执行已被前置脚本**否决**。」（不弹模态框）；脚本日志页签里能看到 `veto test`；**不产生快照**（编排顺序：前置 → 采集，被否就不查库）。
**判读**：弹模态框、或否决后仍落快照＝不通过。

### G4 前置脚本异常 / 编译失败 / 超时（硬阻断）

**步骤**：① 前置脚本里 `throw new RuntimeException("boom")`；② 改成语法错；③ 改成 `while(true){}`。
**期望**：① 状态行「本次执行被前置脚本阻断：前置脚本异常（GLOBAL）：boom」；②「…编译失败（GLOBAL）：<javac 诊断含行号>」；③ 约 **5 秒**（默认超时）后「…超时（GLOBAL，5000ms 已中断）」，执行不发生；三种情况后续执行链仍可用（不卡死、线程不残留）。
**判读**：死循环把 IDE 卡住＝超时/中断没生效（这是相对 CoolRequest 的必补项）。

### G5 后置脚本：拿结果、写变量

**步骤**：① 后置（全局）写 `log.println("rows=" + result.getAffectedRows()); env.setVar("lastCount", String.valueOf(result.getAffectedRows()));`；② 执行一条 UPDATE；③ 看脚本日志页签与变量表页签。
**期望**：②「日志」页签出现 `rows=N`；③ 变量表出现 `lastCount = N`（项目级持久化，重启仍在）。
**判读**：变量表没有新行＝`setVar` 没广播/没持久化。

### G6 变量跨次传递

**步骤**：把前置脚本改成 `log.println("prev=" + env.getVar("lastCount")); return true;`，再执行一次。
**期望**：脚本日志出现 `prev=<上一次的行数>`（后置写 → 前置读的闭环）。
**判读**：读到 null＝两个作用域没接同一份 `IEnv`。

### G7 后置异常不影响已执行结果

**步骤**：后置脚本 `throw new RuntimeException("post-boom")`，执行一条**正确的** UPDATE。
**期望**：UPDATE 确实执行了（库端值已变）；只在脚本日志记一行「后置脚本（GLOBAL）问题：…」，无模态框、不回滚已执行结果。
**判读**：因后置脚本异常把执行判失败＝违反后置语义。

### G8 三档合并顺序

**步骤**：全局 / 数据源 / 语句级各写一段会 `log.println` 的脚本，执行一次。
**期望**：脚本日志按 **全局 → 数据源 → 语句级** 顺序出现三段输出；空档位跳过；**空模板**（未改动的模板）不编译不执行（日志里没有对应输出，也没有编译耗时）。
**判读**：顺序错乱、或空模板也进编译＝不通过。

### G9 关闭开关后零成本

**步骤**：关掉脚本开关，执行 UPDATE。
**期望**：零编译、零执行、零日志；快照能力不受影响（两个开关相互独立）。
**判读**：关掉后仍有脚本日志＝开关没卡在最外层。

---

## 八、探针（ProbeTool，⑦）

> **文件名更正（实证）**：任务里提到的 `ui/diff/RollbackTableDiffProbeTool.kt` **在该分支不存在**（`git ls-tree` 全分支零命中，master 亦无）。实际形态是：P0 的平台探针已在 P3 **正式化并替换**成 `ui/diff/RollbackTableDiff.kt`（文件头 KDoc 原文「P0 探针的正式化，EP 同名替换」），探针的**验证面**留在测试类 `src/test/kotlin/.../ui/diff/RollbackTableDiffTest.kt`（平台桶 7 例）。

### H1 探针验的是什么（平台事实，验一次即可）

**路径**：P0 提交 `3015c193c`（计划册 §九）+ `RollbackTableDiff.kt` 头注释。
**三条平台事实**：
1. 243 / 262 的 `FrameDiffTool` 抽象方法都是 `createComponent(DiffContext, DiffRequest): DiffViewer`（不是旧资料说的 `createViewer`）；
2. 自定义 `diff.DiffTool` EP 注册后**不在** `DiffManagerEx.getDiffTools()` 清单最前（243 实测下标 8/13）——所以不能靠「排在前面」取胜；
3. 选择正确性由「**唯一 `canShow` 命中**」保证：内置工具对非 `ContentDiffRequest` 全部 say-no，我们的 `canShow` 只认 `RollbackTableDiffRequest`。

**用户视角的等价验收**：只要 §D4 真的弹出表格形态 diff 窗口，就说明这三条在目标 IDE 版本上成立（这是探针的唯一用户可感面）。**不需要**单独点一个探针按钮——本批没有、也不应该有面向用户的探针入口。

### H2 EP 注册在位

**路径**：`src/main/resources/META-INF/plugin.xml`。
**期望**：有一条 `<diff.DiffTool implementation="cn.song.mybatisplus.visualizer.ui.diff.RollbackTableDiffTool"/>`。
**判读**：缺失＝§D4 一定退化成兜底文本路线；但**不要**在 IDE 里手动找这个 EP 注册——用打包产物 `plugin.xml` 核对即可。

---

## 九、降级矩阵与边界（负例总表）

| # | 形态 / 场景 | 期望行为 | 判据 |
|---|---|---|---|
| X1 | 只读工程 / 远程 / WSL（快照目录写不进） | 记 WARN、静默降级、**执行不受影响** | 执行成功；无「已留存」误报 |
| X2 | 磁盘满 / 写盘异常 | fail-open，执行照常 | 无异常对话框 |
| X3 | 触发器把值改成第三个值 | 改后采集到的是**回读值**（可能与预测不同），条目 `warnings` 里应能看出 | 不伪造「预测值」 |
| X4 | UPDATE 命中 0 行 | 条目仍为 `captured`（`beforeRows` 为空），改后采集被空行短路并附一条 warning；不生成可用回滚 | 生成出非空回滚 SQL＝不通过。**已知边界**：状态常量 `noRowsAffected` 已定义但生产路径未写入（v1 未接），不要期待该状态值 |
| X5 | WHERE 含子查询 | 允许采集，但行定位有告警 | 无告警＝过度自信 |
| X6 | 多语句脚本（一次执行多条） | 只对「当前要执行的那一条」判定；不是单条 UPDATE/DELETE 就不采集 | 把整段脚本当一条采集＝不通过 |
| X7 | Community 版（无 Database 插件） | 开关可见但禁用 + tooltip 说明原因；执行链不受影响 | 开关可勾但点了无效果＝不通过 |
| X8 | 控制台原生 Ctrl+Enter | **不接**（首版明确不做，见计划 §3.2） | 该路径无快照属预期，不算缺陷 |
| X9 | 回滚动作本身 | **永不采集**（回滚不可再回滚） | 回滚执行后出现新条目＝不通过 |
| X10 | 无主键 + DELETE | 可采集（改后恒空），回滚走回填插入 | 生成 UPDATE 回滚＝不通过 |

---

## 十、开关总表

| 开关 / 项 | 位置 | 默认 | 作用 |
|---|---|---|---|
| 执行 UPDATE / DELETE 前自动留存改前快照（`rollbackSnapshotBeforeExec`） | Settings → Tools → SQL Visualizer → 界面与操作 | **关** | 总闸：关＝零查询零 IO 零目录 |
| 启用 SQL 前/后置脚本（`sqlScriptEnabled`） | 同上 | **关** | 脚本总闸：关＝零编译零执行；与快照开关**相互独立** |
| 脚本管理... | 同上（按钮） | — | 五页签：全局级 / 数据源级 / 语句级 / 变量表 / 脚本日志 |
| 快照保留（内部常量，无 UI） | — | 7 天 / 200 条 / 50MB；单次 500 行；超时 2 秒 | 计划 §3.1 固定值，不做成开关 |
| 脚本超时（内部常量，无 UI） | — | 5 秒（可传参） | 死循环中断阈值 |

> 本批**无**新增 Registry 键；两个开关都是设置页可视项。快照目录本身无需开关（随总闸）。

---

## 十一、not run / 环境受限（本册编制时全部未执行）

| 项 | 原因 |
|---|---|
| 本册**全部** GUI 项（A1~A2、B1~B5、C1~C5、D1~D5、E1~E5、F1~F5、G1~G9、H1~H2） | 代码未合 master；按纪律不启动 runIde、不做桌面自动化；交用户合流后执行 |
| 定向测试（本树） | `buildSrc/src/main/kotlin/mpv-runide.gradle.kts` 存在他人在途未提交改动（59 行新增），`:buildSrc:compileKotlin` 失败，本轮无法读数 |
| 真实大表性能（采集墙钟 / 500 行边界耗时） | 需真库真表；本册只给「不阻断 / 有上限」的定性判据 |
| 跨版本（243 / 253 / 262 / 263）diff 窗口表现 | 分支侧 CI `verify` 三目标已绿（计划 §九·1），真机窗口渲染需在目标 IDE 版本各验一次；本册默认按 2026.2 写 |
| 语句级脚本的持久化 | **已知偏离**：v1 语句级挂在预览面板生命周期上、不持久化（计划 §九「偏离计划已声明」①） |

---

## 十二、验收记录表（合流后执行、逐行填写）

| 编号 | 项 | 结果 | 证据（截图 / 文件 / 读数） | 备注 |
|---|---|---|---|---|
| A1 | 默认关、关时零成本 | 未执行 | — | 硬契约 |
| A2 | 开关文案说明成本 | 未执行 | — | — |
| B1 | UPDATE 采集顺序 | 未执行 | — | — |
| B2 | 确认被否不采集 | 未执行 | — | — |
| B3 | 五种不可采集形态 | 未执行 | — | — |
| B4 | 无数据源跳过 | 未执行 | — | — |
| B5 | 采集失败不阻断 | 未执行 | — | fail-open |
| C1 | 磁盘形态（json + index + .gitignore） | 未执行 | — | — |
| C2 | 条目字段齐备 | 未执行 | — | — |
| C3 | 500 行截断标记 | 未执行 | — | — |
| C4 | 剪枝（7 天/200 条/50MB） | 未执行 | — | 可代码复核 |
| C5 | 项目级隔离 | 未执行 | — | — |
| D1 | UPDATE 改后回查 | 未执行 | — | — |
| D2 | DELETE 改后恒空 | 未执行 | — | — |
| D3 | 无主键降级仅改前 | 未执行 | — | — |
| D4 | 原生表格 diff 窗口 | 未执行 | — | 表格形态为准 |
| D5 | 仅改前点对比给提示 | 未执行 | — | — |
| E1 | UPDATE 逐行 CAS 回滚 SQL | 未执行 | — | 硬判据 |
| E2 | DELETE 回填插入 | 未执行 | — | — |
| E3 | 评级 PARTIAL / UNABLE | 未执行 | — | — |
| E4 | 复制 + 既有链执行边界 | 未执行 | — | v1 不做旁路执行 |
| E5 | 手工改脏后 CAS 命中 0 行 | 未执行 | — | 硬判据 |
| F1 | 历史入口与五列/四动作 | 未执行 | — | 菜单十项顺序 |
| F2 | 列表内容 | 未执行 | — | — |
| F3 | 刷新与删除 | 未执行 | — | — |
| F4 | 重启后仍在 | 未执行 | — | — |
| F5 | 日志面板路径 | 未执行 | — | — |
| G1 | 脚本开关默认关 + 风险文案 | 未执行 | — | 红线 |
| G2 | 五页签 + 模板预填 | 未执行 | — | — |
| G3 | 前置否决（软否决、不采集） | 未执行 | — | — |
| G4 | 异常/编译失败/超时 硬阻断 | 未执行 | — | 5 秒中断 |
| G5 | 后置写变量 | 未执行 | — | — |
| G6 | 变量跨次传递 | 未执行 | — | — |
| G7 | 后置异常不影响结果 | 未执行 | — | — |
| G8 | 三档合并顺序 + 空模板跳过 | 未执行 | — | — |
| G9 | 关闭开关零成本 | 未执行 | — | — |
| H1 | 三条平台事实（等价于 D4 出表格窗口） | 未执行 | — | 无独立探针按钮 |
| H2 | `diff.DiffTool` EP 注册在位 | 未执行 | — | 读打包 plugin.xml |

---

## 十三、附：代码证据索引（合流后按此复核）

| 能力 | 文件 / 位置 | 要点 |
|---|---|---|
| 采集判定（纯逻辑） | `sql/rollback/RollbackSnapshotCapture.kt` | `ROW_LIMIT=500`、`TIMEOUT_SECONDS=2`、`SNAPSHOT_DIR_RELATIVE=".sql-visualizer/snapshots"`；`Plan.Capturable / NotCapturable`；`hasUnfilledParameterMarker`（剥字符串后看 `?` 与 `{{`） |
| 落盘与剪枝 | `sql/rollback/RollbackSnapshotFileStore.kt` | `<id>.json` + `index.json`；`KEEP_DAYS=7 / MAX_ENTRIES=200 / MAX_TOTAL_BYTES=50MB`；状态常量五个，生产路径只写 `captured` / `compared`（`rolledBack` / `stale` / `noRowsAffected` 未接） |
| 项目级门面 | `sql/rollback/RollbackSnapshotProjectStore.kt` | PROJECT service；`persist` 池线程写盘 + 顺带剪枝；`StartupPruner` 为 `ProjectActivity`，开关关闭时不跑 |
| 编排（面板链） | `ui/SqlPreviewPanelExecution.kt` `captureSnapshotBeforeExecThen` / `saveCapturedSnapshotBeforeExec` / `submitExecutionAfterSnapshotGate` / `capturePostSnapshot` | 顺序：确认 → 前置脚本 → 采集 → 提交 → 成功回调里「改后采集 + 后置脚本」 |
| 编排（无面板链） | 同文件 `captureSnapshotStandalone` | 日志转 SQL 路径；取 `boundConsoleDataSource`，取不到即跳过 |
| 对齐 | `sql/rollback/RollbackSnapshotAligner.kt` | `guessKeyColumnIndexes`（启发式）、`align`（主键对齐 / 未匹配）、`CHUNK_SIZE=500`、`buildPostSelectSql` |
| 回滚 SQL | `sql/rollback/RollbackSnapshotRollbackSql.kt` | UPDATE 逐行 CAS；DELETE 复用 `BACKFILL_INSERT`；评级恒 PARTIAL、无键 UNABLE |
| diff 三件套 | `ui/diff/RollbackTableDiff.kt` | `RollbackTableDiffRequest : DiffRequest`、`RollbackTableDiffTool : FrameDiffTool`（`canShow` 只认自定义 request）、`RollbackTableDiffViewer`（两个只读 `JTable` + statusPanel 摘要） |
| 历史窗口 | `ui/RollbackSnapshotHistoryDialog.kt` | 五列表 + 左侧动作「刷新 / 对比 / 回滚 SQL / 删除」；`RollbackSqlTextDialog` 只读 + 复制；`DiffManager.getInstance().showDiff(...)` |
| 脚本引擎 | `script/SqlScriptEngine.kt` | javac 内存编译 + MethodHandle 精确签名；源码 hash → 编译产物 LRU 32；`DEFAULT_TIMEOUT_MILLIS=5_000`；classpath 只给插件自身 + JDK |
| 脚本编排 | `script/SqlScriptRunner.kt` | `Scope.GLOBAL → DATA_SOURCE → STATEMENT`；`PreOutcome.Proceed / Vetoed / Blocked` |
| 脚本 UI | `script/SqlScriptManagerDialog.kt` | 五页签；日志为进程内环形缓冲 200 行（**不落盘**） |
| 脚本模板 / API | `script/SqlScriptTemplates.kt`、`src/main/java/.../script/api/*.java` | `handlerSql` / `handlerResult` 签名不得改；`SqlExecContext` 全只读（D-1：不给改写 SQL 的 API） |
| 平台 EP | `META-INF/plugin.xml` | `<diff.DiffTool implementation="...RollbackTableDiffTool"/>` |
