---
name: project-magpie-research-gems
description: magpie 调研+T-MagpieGems 七项全实施+衍生（复盘定时任务在役/O-150 回滚 W1+W2 已实施），20261024 t-magpie-gems 15 笔未 push 未合
metadata:
  node_type: memory
  type: project
  originSessionId: session_a1a95c905cbd97e6bfa2af1fb65fe1d4
---

2026-10-23 用户指派对 magpie（github.com/yetone/magpie，MIT，本地 `F:\code\Java\Github\other\magpie`，tip `fa22bc64`）做全方面精华吸取调研。三路子代理并行解剖完成，双文件落盘并已提交（主仓 `7731d395`、内部仓 `10acda2`）：

- 调研报告：`docs/内部/调研报告/20261023-2311-magpie-全代理驱动的本地AI网关-调研报告.md`（全部结论带 magpie 侧 `文件:行号` 证据）
- 开发计划：`docs/开发计划/20261023-2311-T-MagpieGems-magpie精华吸取-开发计划.md`（W1~W7，含 4 条待验证前提 P-1~P-4，未占 O/R/D/V 编号）

**实施（2026-10-23/24 用户下令全内容实施，工作树 `F:\code\Java\Gitee\SongRenShuo\mpsv-magpie-gems`，分支 `t-magpie-gems` 自 master@f9e1650f）**：

- 前提证伪（三路 Explore）：P-1 脱敏现状=常量 `***`（恒定属平凡确定，四件套实质全无；真缺口=日志列表 Ctrl+C 复制不走脱敏 `LogEntryFiltering.kt:147-149`、错误信息回显原值）→ W2 收窄为分级/白名单/Hint/复制缺口，**不做假名化产品语义变更**；P-2 成立（平台 FileBasedStorage 坏文件→null→默认 State→blockSaving 不拦→下次保存覆写，@State 无读失败钩子）→ W3 改**旁路 guard 方案**（不撤 @State 不自管化）；P-3 有真缺口（执行链仅 ConnectionErrorClassifier 一个消费点，语法/权限/对象缺失全同路、无自动 failover）→ W4 落分类器扩展。
- 已提交（t-magpie-gems）：`8f6b3faa` W1（docs/LESSONS.md 四段式账本+code-standards S1~S10+仓根 AGENTS.md）+W6（docs/开发流程-派活与验收清单.md）；`36e70767` W3（config/StateFileLastGood.kt 纯函数三件套 + SettingsLastGoodStartup postStartup daemon 巡检 + StateFileLastGoodTest 11 例）+W4（execute/ExecutionErrorCategory.kt 五档分类，权限压过对象缺失、连接排除表沿用，format 单点接线出路提示，ErrorPositionExtractorTest 断言同步）+ W7 预览执行链子系统文档（docs/架构设计/子系统-预览执行链.md）；`f201597d` W5（code-standards 增「机制导入」节 M1：扩展点失败策略显式成契约+注册返回 remove+「每候选/每请求」钩子分接口+fail-closed 须有按需刷新）。
- **押后批已完成（2026-10-24）**：W2 后台子代理交付（`acc4a276`，29 文件 +793/−107，16 测试类 266/0/0，反向白名单摘除恰 2 红）；统一批 `b57d23d2` 守卫收口（EnumReachability 面板选项携带枚举本体=WARN/BLOCK 产出点、BannedApi 三处 catch(Throwable) 补 `FatalErrors.isFatal`、BundleParity mode 4 键 + W3 通知键/plugin.xml 注册 + W4 提示键 + W5 fail-closed 兜底 faultHook 探针缝）；`490b2e9b` 收尾（展示脱敏链子系统文档 + LESSONS L11 + 计划 §六实施记录 + 索引状态）；`231378e1` AGENTS.md 入库（.gitignore:67 删除放行，实测宽规则 AGENTS 不命中 AGENTS.md）；`b5e5a8b9` verifier 收口（loadValidRoot 换非废弃 JDOMUtil.load(Path)，deprecated 41→40 回基线）。
- **终态读数**：定向 71/0/0（W3/W4/W5 四类）+266/0/0（W2）；反向 W3 恰 3 红/W4 恰 2 红/W5 恰 1 红/W2 恰 2 红，全还原回绿零残留；testFast **4168/0/7**（338 文件）；verifyPlugin verdict **Compatible**（我方类零 verifier 记账，MISSING/DEPRECATED/EXPERIMENTAL 均基线豁免项；verifyPlugin 已降级报告生成器、权威 failureLevel=COMPATIBILITY_PROBLEMS）；全量 testShards 未跑（留下轮统一全量）、真机 not run；工作树干净、并行登记已注销。**t-magpie-gems 未 push 未合回 master。**
- 四条有意偏离（详见计划 §六）：displayMaskMode 空串哨兵（写死 MASK 会让新装用户默认开打码）；mode 复用既有搜索键；W3 旁路 guard 替代自管化 store；W4 处置收敛在提示层不做自动切源。
- 协作坑（本任务实证）：同工作树双车道时**避免同改一个文件**（PluginSettings.kt 让给 W2，W3 靠独立类+不碰 loadState 挂钩）；bundle/plugin.xml 延迟到无冲突批；**车道每笔提交至少过 compileKotlin**（36e70767 未编译堵死 W2 验证被代修 060ffaeb，已入 LESSONS L11）。

**20261024 用户追问「想用 magpie 的每日多次发行+自动夜间复盘模式需要什么资源」——评估结论（未立项，待用户拍板）**：

- 复盘线几乎万事俱备：运行器=ZCode 定时自动化（CronCreate 挂 23:30）或闲时任务即可，不需买 CI；缺三件=①复盘提示词协议文档（~200 行：reviewed-through 水位线消费、四段式格式、量化日志格式、「文档承认的洞当轮开 issue」防自欺条款）②webhook 失败/完成告警（企微/钉钉/邮件任一）③早上 5 分钟人审（代理产出是草稿）。LLM 预算量级=2M~8M token/晚（读多写少），默认浅审（flash 级模型起步）+按 flagged 提交升级深审。
- 发行线受两道硬约束，照搬必须降级：①本机资源（2 路并发/Commit 74.7GB 上限/全量门禁 15min+）→ 现实上限**每日 1~3 个 beta 版**，magpie 单日 317 commit 的节奏物理做不到；②Marketplace 审核节奏 → **全自动只对 beta/内测通道，稳定版保持人工周更**。
- 发行线还缺：tag→verifyGate 全绿→buildPlugin→Gitee release+update.json 的串联脚本（等绿再发+失败中止）；二进制回滚出口已核实并立项为 T-SelfUpdateRollback/O-150（见文末衍生段）。
- 落地顺序共识：先手动半自动复盘跑两周验证 LESSONS 格式有效 → CronCreate 挂夜间+人审 → beta 通道单条自动化链跑稳一个月再提频。

magpie 头条事实：**71 万行 Go 项目完全由 AI 代理编写/审查/合并/发行**，日均 100~300 commit，靠「教训→标准→守卫测试→CI」四级沉淀管道。要点：magpie 是 MIT 开源项目，公开仓提及不受 [[project-competitor-docs-open-source-exposure]] 红线约束；但其调研报告按惯例仍放 `docs/内部/调研报告/`。

**后续衍生（2026-10-24，`t-magpie-gems` 15 笔未 push）**：①夜间复盘协议 `docs/开发流程/夜间复盘协议.md`（108 行，水位线在 LESSONS.md 头部 reviewed-through，起始 e35a0d7c；提示词全文内嵌复制即用——四类分桶/量化日志/四段式入账/零写集硬条款/零教训日不硬凑；早上 5 分钟人审清单+停机条件）；②自更新回滚上一版 **T-SelfUpdateRollback（O-150）W1+W2 已实施**（`5776bf23` 实现+`f535035d` 计划回填；参考 DBX 反向恢复两步+magpie exe.old；UpdateBackupStore 293 行=zip 快照〔顶层目录名与发行包同构、manifest Properties、sha256 强校验、同版本幂等、只留一份、fail-open〕+接线 downloadAndInstall 的 install seam **之前**+UpdateSection 回滚按钮〔确认框→调度 DeleteCommand+UnzipCommand 与升级脚本同构→当前版本自动入 VersionIgnore〕；P-1~P-4 全核销：备份形态定案 zip——平台 CopyCommand 只收 isRegularFile 目录不可拷、P-1 证伪「零成本标记」PluginInstaller :117/:157/:209 三路全删旧、脚本路径有意不改 Delete→move〔运行期已备份〕；定向 4 类 56/0/0+反向三组恰红〔sha256/ignore/接线各 1〕+testFast 4177/0/7；真机端到端 not run 留用户）。流程文档已归拢新目录 **docs/开发流程/**（派活清单+复盘协议两篇 git mv，链接同步零残留；架构设计/ 不收过程规范）。

**夜间复盘定时任务已在役（2026-10-24 挂）**：automation-`b4ed2a18`，cron `30 23 * * *` 常驻。**消费口径=双线去重零合并**（用户建议「先合并 master 再审」改为等价零风险方案：`git log <sha>..HEAD` ∪ `git log <sha>..master` 按 sha 去重——worktree 共享对象库跨分支可查；夜间禁 merge/rebase/pull〔无人值守合并冲突即整轮卡死+违反零写集〕，发现账本分支落后 master 在摘要「分支健康」段记笔数提醒人工白天合并；水位线推进双线 tip 较新者）；协议 §三/§四/硬条款/输出四处同步+§八修订记录。**待办：t-magpie-gems 合回 master 后须把任务 prompt 里的工作树路径改成主仓。**

**2026-10-24 第二专题「测试体系与发行门禁」（用户追问「怎么自动化测试保证功能无误才发行，特别是 UI 交互」）**：双文件已提交（主仓 `8872ba42`、内部仓 `1f26900`）——调研报告 `docs/内部/调研报告/20261024-1340-magpie-测试体系与发行门禁-调研报告.md`、计划 `docs/开发计划/20261024-1340-T-TestGateGems-吸收magpie测试体系与发行门禁精华-开发计划.md`（W1~W6 + 待验证前提 P-1~P-5，未占编号）。核心取舍＝**能机械判定的 UI 规则全部下沉成秒级守卫进 CI，人工部分强制留可审计记录**。

实测读数（一手取证，非子代理转述）：magpie 四层拦截＝①Go 侧 UI 守卫（硬，进 CI）②原生外壳纯函数/子进程测试（硬，进 CI）③**849 个浏览器用例 127k 行（软，明确不在 CI，`code-standards.md:78`）**④ui-preview 自动录屏（软，PR 证据）。**最反直觉＝真正拦发行的是第①层几十个秒级守卫，不是那 849 个用例**。发行四条件在 `code-standards.md:120-140`（CI 绿灯/cancelled 不算过、tag 在 main 祖先、16 资产、标 Latest）；`release.yml` 实测**零测试步骤**（只校验版本号正则+资产数+中文注缺失开 issue），「绿灯才打 tag」纯属纪律；唯一机械化护栏＝`gh pr merge --match-head-commit <reviewed sha>`。

六条可抄守卫：脚本体积预算（`core_budget_test.go:22-50`，常量注释逐条记提额理由+「with no room added」）、声明 vs 实际加载（`builtins_test.go:191-244`）、四语言字符串齐全（`:246`）、日期跟随界面语言（`intl_lang_test.go:17-38`，正则扫全部调用点 + 一个贵浏览器用例证行为）、生成物不过期（`purpose_asset_test.go`）、缓存语义（`web_compress_test.go:109`）。原生面三招＝把外观抽成参数断言（`panel_options_test.go:14-38`，不起窗口）、AppKit 子进程自我 exec、**明确登记「测不到」**（`gui-shell.md:378/380`、验收第 5 条「a GOOS build is not a test」）。

对本仓的缺口（实测）：`releaseGate` 五环（buildPlugin→verifyGate→verifyMarketplace→testShards→e2eFakeIde）**没有一环碰 UI 交互**；`docs/测试沙盒验证清单.md` 共 42 条靠人工勾选无产物；已有 97 个守卫 + `BannedApiScanSupport` 扫描基建可复用；缺「硬编码中文扫描/图标资源存在性/体积预算」。（原判「动作可用性矩阵零先例」**已被推翻**，见下）

**✅ 2026-10-24 已全实施（`0fbb43aa` 19 文件 +1185/−14，补验证 `0077c73e` +190/−1，未 push）**：W1-a 硬编码 UI 中文扫描守卫 + **6 处存量全改 bundle**（中英各 +5 键，实测存量清零）；W1-b 图标资源存在性守卫；W1-c 巨型文件体积预算（top10 + 1.5% 余量，提额须留理由）；W3 动作可用性矩阵（6 个选中类动作）；W5 `dev-support/gate-readout.ps1` 门禁读数 HEAD sha 台账（record/verify/show）；W6-a `record/gifkit.py` 禁用词表护栏（`label=` 语义）；W6-b `record/BASELINE.md` 产物局限声明（三类盲区+模板）；W6-c/d `record/plan_runner.py`（plan 校验+确定性重放+dry-run）+ `record/uitree.py`（可定位树）+ `plans/` 目录说明与示例。**W2/W4 经核实已有等价物（`SqlPreviewDialog.initialSize` 参数化三档断言、派活清单第 7 条 not run 条款），未重复建设**。定向 **19/0/0**（含 BundleParity 5、SettingsSearchable 4 回归）；反向七组全命中（植入中文/删图标/摘 isEnabled/篡改 sha/六类危险词/三组非法 plan/**摘 uitree 可见性剪枝**），全部还原零残留。全量 testShards / verifyGate / 真机走查 **not run**。

**🆕W6-d 首版弱验证教训（复核发现，`0077c73e` 补齐）**：`uitree.py` 依赖 JVM Swing 组件，首版交付时**只有「文件存在」一条证据**（未 py_compile、未执行）——同批的 plan_runner/gate-readout/gifkit 都做了正反验证，唯独它漏了。补法＝新增 `record/verify_uitree.py`：最小 Swing 桩（`isShowing`/`getAccessibleContext`/`getComponents`/`getName`/`getLocationOnScreen`/`width`/`height`/`getText`）跑 build_tree/find_by_text 正反例 + 摘剪枝反向恰 1 红。**通用教训：产出「依赖宿主环境、正常跑不到」的工具时，必须配一个桩测试，否则等于没验过**（与 L12/L13 同源）。

**🆕三条实施坑（已入 LESSONS L12/L13）**：①**守卫口径要以生产代码实际契约为准**——W3 第一版断言「空上下文 `isVisible==false`」，实测 3 个选中类动作本就常驻可见、按上下文灰置（`SelectedTableSqlTemplateAction.update` 只设 `isEnabled`）⇒ 假红；改断 `isEnabled==false` 才真；自证用例每动作须用**独立 AnActionEvent**（共用 Presentation 会污染）。②**`.ps1` 必须存 UTF-8 BOM**——无 BOM 时 PS 5.1 按 ANSI 解码中文注释，报「Missing closing '}'」且行号指向无关位置；Git Bash 直调 `powershell -File` **会吞退出码**，核验须 `-Command "& '...ps1' ...; exit $LASTEXITCODE"`。③**P-2 被推翻**：`ConvertSqlToJavaActionTest.kt:32-40` 早有 `update()`+`isEnabledAndVisible` 模式可扩展。

**用户 20261024 追问「按这个调整后排门禁会操作 UI 吗、会影响我正常使用吗」——实测答复：不会。** 三条证据：①门禁里唯一接近 UI 的 `e2eFakeIde` 是**自写 loopback socket 服务端**，`dev-support/e2e-fake-ide/README.md:3` 原文「替代 IntelliJ GUI（不跑 runIde）」；②`src/test/kotlin` 全量 grep `JFrame|JDialog` 仅 **2 个文件**命中，且都是 `DialogWrapperInitOrderGuardTest` 这类**读源码文本做静态扫描**的守卫，`show()`/`isVisible = true`/`pack()` 零命中；③W1/W2 是读文件做字符串与路径比对（与既有 `BannedApiScanSupport` 同构、毫秒级），W3 是唯一理论碰平台的，但计划写死「先写最小探针，验证不过整条撤掉」，且 `update()` 只改 Presentation 不弹窗不抢焦点。**要如实说清的两点**：现有 `testShards` 5 片本来占资源（用户已在用，非新增）；IntelliJ 平台测试在 Windows 偶发闪一个空 Swing 帧，与改动无关。**会操作 UI 的只有 runIde 沙盒 + pyautogui 验收——那部分本次一行未动、也不在 `releaseGate` 里**，仍由用户手动批次化执行。

🆕坑（O-150 实施实测，可入 LESSONS 候选）：**Windows 上 ZipFile.use 块内对同一文件 Files.move 必句柄锁**（FileSystemException「另一个程序正在使用此文件」）——单跑侥幸绿不算判据，testFast 全量首跑即翻车；修法=先读完关句柄再重写。反向探针形态教训：源码守卫（indexOf 文本断言）对「`if (false)` 前缀条件化」不敏感（方法文本仍在），探针必须按真实回归形态（整行删除）做。

## 🆕2026-10-29 master 并入 + 合并后功能验收（tip `76f59a5c` 未 push）

- **合并**：master 121 笔并入 t-magpie-gems（merge `5b0492ed`，master 成完整祖先）。冲突 3+1 处：HistorySqlLogPanel/LogEntryRendering/SqlListV179HeadlessTest 均取 master 版再叠 W2 适配——master 已把渲染器收拢进新文件 `RailCellRendererBase.kt`，W2 的 maskMode 缓存键改造重新落位到 base 的 `sqlLineFragmentsFor`（`cachedListLineFragmentsMaskMode` 四态互异）；docs/MEMORY.md 同 insight 取 master 瘦身版为基底补 7 篇分支独有。夜间复盘任务写的 LESSONS.md 脏改动单独提交（`ed7acd35`）。
- **合并连带红**：GiantFileBudgetTest 1 红（DataSourceSelectorResults.kt 2162>2133，master 侧 ExecFileBinding +29 行）→ 按守卫提额条款 2133→2194（`a815b5d3`，先例=10-25 同理由提额）。定向 16 类 248/248。
- **验收**（`docs/验收文档/20261028-2340-T-MagpieGems-合并master后功能验收-验收文档.md`，沙盒 `build/MPSV-MagpieGems-UI243`，启动脚本 `.local/start-magpie-ui243.ps1` 不入库）：通过 7 组（W2 四态渲染/复制/导出从严、W3 坏 State 存证+内存恢复+警示通知、O-150 无备份态 tooltip、文档守卫面、合并语义）；部分 3 组（MG-06 错误分类=测试收口+真机人工一次执行；MG-08 回滚主链=备份磁盘契约实证+人工兜底，设置树 About 节点在重启后会话 pyautogui 与 robot-server 两路点击均不导航）。
- **🆕两个验收缺陷已修复并复验（20261029 用户令「修复问题直到全部修复」）**：UI-001 P2＝白名单实参只接进停靠快速预览链（`SqlDockedPreview.kt:111`），`SqlListFragmentRenderer.kt:40/44`、`LogEntryFiltering.kt:162`、`SqlExportService.kt:53/176`、`ReplayScriptExport.kt:92` 全没传 `displayMaskAllowList()`（GUI 实证：白名单 tel+MASK 列表仍 `***`，模式循环排除缓存）；UI-002 P3＝`refreshRollbackAvailability()` 只在语言切换 `updateTexts` 调用、`resetState()` 不刷新（真机主流程不受影响）。
- **语料端点**：W2 脱敏验收用 demo 既有 `GET /mpj/locator/joinPlaceholder?tel=13812345678`（还原 SQL 含手机号字面量），零 demo 改动。
- **沙盒坑**：runIde 前必须 `python record/bootstrap-robot-server.py`（与 dev 树缓存不共享，缺了 build.gradle.kts:181 直接 fail）；Properties.store 手写 manifest 别写中文时区注释（latin-1 编码炸）；W3 恢复的是内存态，主文件等平台下次保存自愈——验收判据是设置页 GUI 值不是文件内容。

- **🆕缺陷修复批（20261029 深夜，修复 `2e8ee383`、文档闭环 `8a53f3c2`）**：UI-001＝displayMaskAllowList() 贯穿列表/复制/导出/重放/停靠预览复制五链（纯逻辑函数加 allowList 形参默认空=旧行为逐字等价）+ 摘要与语义两级缓存键并入白名单维度 + DisplayMasker BARE_NUMBER 分支补 hint 查白名单（此前 hint 只给 LITERAL）；UI-002＝UpdateSection.resetState() 补调 refreshRollbackAvailability()。定向 6 类 99/0；反向探针两组各恰红（摘 RailCellRendererBase 接线 / 摘 reset 刷新）后还原复绿。**GUI 原路径复验通过**：白名单 tel+MASK 列表显示原文（修复前 '***'）、Ctrl+C 剪贴板含原文、O-150 按钮→确认框→「已回滚——重启 IDE 后生效」状态行+idea.log 已调度回滚+ignoredUpdateVersions 落盘；MG-08 重启回落段在 runIde 沙盒结构性不可判别（prepareSandbox 每次重建 plugins/），真机人工一次重启即可判。
