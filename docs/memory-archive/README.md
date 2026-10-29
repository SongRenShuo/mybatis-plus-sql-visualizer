# 项目记忆归档索引

> 本目录存放从本地记忆系统（`~/.zcode/cli/memories/projects/.../memory/`）归档的历史记忆文件。
> 这些内容已完成/已交付/已修复，不再需要每次会话加载，但作为历史记录保留。
>
> **归档时间**：2026-08-22（首次），2026-08-26（第二次补充），2026-09-04（第三次精简），2026-09-06（第四次精简），2026-09-07（第五次精简），2026-09-12（第六次精简），2026-09-13（第七次精简），2026-09-14（第八次精简），2026-09-15（第九次精简），2026-09-15（T-TestPerf2 提速经验与三篇踩坑归档），2026-09-16（第十次精简），2026-09-17（第十一次精简），2026-09-18（第十二次精简），2026-09-19（第十三次精简），2026-09-20（第十四次精简），2026-09-23（第十五次精简），2026-09-24（第十六次精简），2026-09-25（第十七次精简），2026-09-29（第二十一次精简），2026-09-30（第二十二次精简，零归档收拢轮），2026-10-01（第二十三次精简，零归档索引瘦身轮），2026-10-02~05（第二十四~二十七次精简，四连零归档读数刷新轮），2026-10-06（第二十八次精简，零归档勘正轮），2026-10-07（第二十九次精简，零归档勘正+双档去重轮），2026-10-08（第三十次精简，零归档勘正+双孤儿入册轮），2026-10-29（第三十一次精简，47 文件归档波次+索引瘦身 247→198 行）
> **原始位置**：`~/.zcode/cli/memories/projects/mybatis-plus-sql-visualizer-09474abd1ce62ee6/memory/`
> **全局备份**：`~/.zcode/cli/memories/global/`（116 个通用文件）
> **项目记忆**：保留 155 个内容文件 + MEMORY.md 索引（第三十一次精简后）

---

## 已修复 Bug（178 个）

| 文件 | 内容摘要 |
|------|----------|
| [project-dynamic-unload-barriers.md](bug-fixes/project-dynamic-unload-barriers.md) | 插件动态卸载根因分析与方案决策全档（2026-08-20 分析，闭环=T-DynUpdate 免重启热替换已交付并随 v2026.9.11 发行）——P0=自定义语言/FileType 结构性注册不可运行时移除（方案 A 移除、高亮改内置 SQL、Community 回落纯文本）；P1=EntityResolver 静态缓存持 PsiFile+PluginCacheManager 未接生命周期；P2=SqlPreviewService 非 Disposable。在役结论薄档留项目 memory（反射入口签名护栏等） |
| [project-keyword-wrap-format.md](bug-fixes/project-keyword-wrap-format.md) | 关键字换行格式化交付档（2026-08-21 `4d2703d` + T-ListMode 延伸已交付 dev `bbcc300` 后随批次入库）——formatClauseLine 第三显示模式（子句顶格换行+同行压缩）、五轮迭代踩坑（AND/OR 双空格/depth 计数/括号内空格与逗号）；T-ListMode=三显示模式枚举+BasicHTML 空格折叠须 `&nbsp;`（同族 pitfall-swing-basichtml-whitespace-collapse 在 pitfalls/） |
| [project-o77-ew-statement-scope.md](bug-fixes/project-o77-ew-statement-scope.md) | O-77 T-EwStatementScope 已交付（20260924 立项当日合并 `9464464` 并推送，master/dev/t-testperf5 三支均含）——XML 预览对无 ew 参数的普通语句误报「无 Wrapper 参数（ew）」红横幅：根因=MP 占位识别域（`XmlEwExpander.placeholders` 扫整文件）与解析域（当前语句）不一致，引入 `0f42a80b`（M26-B）起全 tag 受影响、发布版 2026.9.14 在内；修法=语句域扫描（`XmlStatement` 尾置默认字段 `rawText`，include 展开后 textContent 填充，null 回落 fileText），同族两形态（ew2 变量错配/跨命名空间 include 漏检）一并收敛；四轮读数含反向验证，详版=计划 §实施记录（`c3cb052`） |
| [console-capture-zero-output-flush.md](bug-fixes/console-capture-zero-output-flush.md) | O-70 T-ConsolePendingFlush 已交付（dev `efe2c73`）——控制台「已扫描 N 行 / 产出 0 条」根因＝按线程分桶后待闭合条目无产出点（JdbcTemplate 只开 DEBUG 无参数行+Tomcat 按请求新建线程+实时链路从不调 closePending）；静默冲刷修法（逐行+轮询两处冲刷点）、262 无 `removeMessageFilter` 的挂载代际号处置；归档注记含真机复验判据与 Hibernate 零参残留 |
| [enhanced-capture-gates-and-consent-scope.md](bug-fixes/enhanced-capture-gates-and-consent-scope.md) | 增强捕获闸门与确认框作用域定案（20260923，O-66 引导链随交付）——链上无项目类型闸门、Gradle 任务配置未确认态静默跳过、授权按 IDE 配置目录隔离；premain 经 init script 会挂进 Gradle fork 的 JavaForkOptions 任务 JVM（判「不合适」）；归档注记＝路线 A 已实施为 T-GradleTaskInjectScope（`b2f9f17`），受控实验仍在 pending-plans 真机复验节 |
| [reference-acp-session-verification.md](bug-fixes/reference-acp-session-verification.md) | ACP/SSE 链路 runIde 沙盒验证入口（⑯ 全四项闭合 `01c6a99`）——ACP 会话不需登录就能起（卡点是模型凭据非登录）；判据行由 `AcpIdeaMcpProviderImpl` 打（FINER）、传输走 EP 遍历；ml-llm 的类在 `lib/modules/*.jar`（只 glob lib 会误判类不存在）；沙盒 plugins 目录每次 runIde 重建；64342 免 token 端点实证（401 的是 64442 router） |
| [pitfall-hql-property-column-scope.md](bug-fixes/pitfall-hql-property-column-scope.md) | T-HqlAliasColumnScope（O-57）已交付（`7726337` 并入 master `fa1cf55`，k2j 随 Master184 移植）——HQL 副表别名属性原样透传致预览 SQL 不可执行：根因=属性→列名映射只遍历根实体（`HqlConverter.mapPropertyToColumn` 只吃 `table.columns`、正则不含别名），副表属性落在 JOIN ON 真实化与属性映射两链的缝里；修法=别名→TableInfo 映射按别名分派+未解析别名回落现状行为；**@Embedded 展平缺口（`updateInfo.` 段无人处理）按用户定案挂起**，恢复分析先读本档（含用户补充 HQL 原文与「现状产物逐字不变」回归约束） |
| [project-db-schema-calibration.md](bug-fixes/project-db-schema-calibration.md) | T-DbColumnCalibration（O-58）已交付（`7726337`，`ColumnNameCalibrator` 在 master）——实体列名数据源校正与自动降级：四层验收（能校正/数据源可用才校正/不可用降级等价/零性能回归）；能力底账=全仓零 JDBC、自省全走平台 DBN 反射（`columnsCached` 零遍历热路径读）、新 schema 缓存必登记 `MetadataInvalidator`、校正层不加引号（归 Dialect 渲染链）；可判定来源 H1~H4 用真值仲裁一次覆盖、H5（语义无关改名）明确不做 |
| [reference-sql-quick-templates-actions.md](bug-fixes/reference-sql-quick-templates-actions.md) | SQL 快捷模板三动作（EXPLAIN/COUNT/Sample 10 Rows）语义与执行链——选中文本送控制台；局限=LIMIT 10 硬编码不走 `Dialect.renderLimit`、无 ORDER BY、SELECT *、结果网格显示宿主代码上定不下来；默认键位已由 T-TemplateKeyDrop 摘除（`4fabf2e`）；候选「快捷模板 LIMIT 配置化」实施前先读本档 |
| [reference-gutter-recent-executions-popup.md](bug-fixes/reference-gutter-recent-executions-popup.md) | gutter 最近执行记录弹窗——读会话内 SQL 日志缓冲（非磁盘），同文件+锚点 ±30 行、列表 20 条上限不无限增长；两个可增长口子（最大条数无钳制/自动保存历史默认关 30×100MB）；与 F-7「SQL 历史」两套数据勿混 |
| [t-synck2jmaster92-delivered.md](bug-fixes/t-synck2jmaster92-delivered.md) | T-SyncK2JMaster92 已交付（20260919，锚点 6f78eda→be1d4e5，123 提交同步，testShards 5390/0/0/6 + verifyGate 三目标绿；4 生产新建翻译 FatalErrors/SecureXml/PsiSnapshot/AgentUrlRegistry + ACP 传输 + 10 MCP 工具类改名 + BannedApi 1035→2734 五族守卫）；两处结构性决策（Java fatal 透传 `throw (Error) t` 的 JLS 11.2.2 必要性 / fatal 守卫两段式 597 基线与落地集精确计数）与三处移植陷阱（掩码器 Java 树涂白/elvis 误译/改名全仓 grep）已并入活跃记忆 project-kotlin-to-java-port-sync 与 pitfall-scan-guard-mask-and-sample-power 续用 |
| [t-task-queue-skill.md](bug-fixes/t-task-queue-skill.md) | task-queue 任务队列 skill 定案交付（会话挂起守望进程、实施必须在会话内做；端到端实测通过并常态化运行）：含用户两度纠偏后的架构定案、废弃路线存档（CronCreate 底座平台 20 上限/zcode CLI headless 认证链）、闲时通道滥用请求三次拒绝边界（绕配额/计费类内容不分析不评估）、四个待选 skill 优化在册 pending-plans 续踪 |
| [project-merge-dev2master-plan.md](bug-fixes/project-merge-dev2master-plan.md) | T-MergeDev2Master 合流方案与实战（20260918 定案「除 K2J 外全合」→ 同日执行完成双树归一）：真实冲突面算法=以合流基点取「两侧都改过的文件」（11 交叠仅 2 真冲突）；三条风险应验实录（PS4 双实现未成冲突源/近三批改动须并集活着过——AgentManager 三方差集实证/合并≠发行）；大合流先打退路 tag 最省事；自动合并抓不到跨两侧语义耦合（A 侧加参 B 侧加调用点会静默断裂）；dev worktree 运维定例已并入项目记忆 pending-plans 合流债节 |
| [project-mcp-architecture-form.md](bug-fixes/project-mcp-architecture-form.md) | MCP 承载形态定案存档（20260917 评审，定案维持注册 IDEA 平台内置 MCP Server 而非插件独立 server）：核心判据=渲染三件套强依赖 IDE 进程内 PSI/数据源，独立 server 等于重造且丢差异化；十工具清单（4 需 IDE/6 纯逻辑）与设置页门控/[MCP-AUDIT] 审计配套面；「实际开发提效」三条件性评估口径（战略大、日常频率中偏低、纯手动用户零触达）；定案不再投入，等 agent 用户采纳实证，`sqlv_query_rows` 留决策位 |
| [coolrequest-rabbitmq-tool.md](bug-fixes/coolrequest-rabbitmq-tool.md) | Cool Request RabbitMQ 小工具独立交付（跨项目，F:\code\Java\Github\SongRenShuo\cool-request-tool-rabbitmq）：走查四轮修复（错 vhost null 报错统一翻译/非法 Headers 拦截+NO_ROUTE 检测/Peek 按钮叠放/406 冲突四组合试探+Type AUTO 档+EditorTextField 无 project 构造）真机验证通过；公开仓已建+PR #1 提交 houxinlin/cool-request-tools 待作者合并；构建=IDEA jbr JDK25+字面通配 classpath（git bash 须 MSYS2_ARG_CONV_EXCL）；版本规则=tools-rep 1.0-SNAPSHOT 一版到底 |
| [project-m56-agent-bytecode.md](bug-fixes/project-m56-agent-bytecode.md) | M56 agent 字节码增强一期（T-M56-00~09，随 v2026.9.18-beta.3 发行成默认通道）：connect 织入+动态代理包装主路径、ASM Tree API、sub-projects/sql-visualizer-agent 内嵌；挂载链路端到端（AgentLoadException(0) 良性/resolvePid 三步回退/Jsons 首成员逗号已修）；免注入文件通道（-javaagent）=二期在册续踪 project-pending-plans |
| [project-kotlin-to-java-port-sync.md](bug-fixes/project-kotlin-to-java-port-sync.md) | K2J 移植与 T-SyncK2JMaster176 交付（锚点→ed84f5b，176 提交同步含 M56 agent 全链，testShards 4286 绿+verifyGate 三目标 Compatible）：沉淀=is- 前缀访问器显式调用/split 语义差异/测试沙盒旧 jar 遮蔽等；O-24 增量同步决策续踪 project-pending-plans |
| [project-merge-dev-batch-round6.md](bug-fixes/project-merge-dev-batch-round6.md) | 20260917 dev 批次第六轮交付图谱：五计划+T-PerfSweep4+尾巴批次提交映射与关键定案（TSQL 批首仅 IF/超时护栏控制台链不覆盖/ZipGuard 双路谎报/冷却 24h/heredoc 反斜杠 JSON 层陷阱） |
| [project-competitive-round7.md](bug-fixes/project-competitive-round7.md) | 竞品横向对比第七轮（zMyBatis 全仓库解剖：真引擎求值最值得偷师、精华 Z-1~Z-13、定案不做可信执行器对标；Marketplace 快照 290 下载 0 评论第四周；DBX v6 #35/#36）；编号 N-24~N-27/O-30~O-33 已移交 project-pending-plans 续踪 |
| [project-m55-ultra-absorb.md](bug-fixes/project-m55-ultra-absorb.md) | M55 Ultra 精华融合四子任务交付 dev（20260917，c72d1ca/3af7eca/5ab01a2/7417c74，log 包 1034 用例绿）：01 跳转 mapper XML 双菜单/02 报错同列/03 方法聚焦页签/04 请求聚焦；标记行 3~5 段格式演化由 M56 T-M56-00 提前交付（消费端缺口才是 M55 本体）；含 Ultra 官方素材 UI 时代定案与 M56 agent 路线决策史；真机走查四项留人工（续踪 project-pending-plans） |
| [t-exec-doc-sync.md](bug-fixes/t-exec-doc-sync.md) | T-ExecDocSync 执行文件 File Cache Conflict 根治（master d784356，4180/0/6 绿）：旧 BGT 预热 document 分支每炸必裸写兜底=真机弹窗链本体；两条 262 Document API 硬发现（isModified 移除/setText BGT EDT-only）已升全局 pitfall-document-bgthread-edt-only；尾款（CHANGELOG 条目+真机走查三条）在 project-pending-plans 续踪 |
| [t-inparamrec.md](bug-fixes/t-inparamrec.md) | T-InParamRec IN 字面量 SQL 预览参数未识别（master 57dbd6a，全量门禁绿）：根因=字面量 SQL 零绑定行+LogEntryPreviewOpener 独立分支漏接 literalRecovery；三预览入口收编 toPreviewResult 统一通道；教训=多入口能力收编须 grep 全部调用点 |
| [t-log-sort-modes.md](bug-fixes/t-log-sort-modes.md) | T-LogSortModes 日志列表排序方式选择器（80762b4，取代耗时双态按钮）：默认时间升序=捕获顺序、选择器恒可见、空值双向沉底、时间口径跟显示时间源；Kotlin 下 DefaultComboBoxModel 无 List 构造器 |
| [t-agent-source-jump.md](bug-fixes/t-agent-source-jump.md) | T-AgentSourceJump 增强捕获源码跳转修正（a474447+三项收口 e0acf1d）：框架帧黑名单漏收 PageHelper 致误选插件帧→JAR 来源过滤+前缀补齐+候选帧 cands+fat jar 安全网；行数语义写反致「(-1 rows)」；(⚡增强) 角标按用户要求移除勿再加；耗时排序改结构化优先 |
| [t-agent-dialect-loop.md](bug-fixes/t-agent-dialect-loop.md) | T-AgentDialectLoop 字节码增强全方言与日志时间闭环（M56 分支 d317d78/f2b2343，20260916，agent 24 绿+主项目 4223/0/0/6 绿）：url 全链透传+目标库角标（JdbcUrlDialects 独立映射禁走 byName 未知回退 MySQL）+接管期日志时间嗅探回填（LogTimeCorrelator 512 FIFO 指纹）+JdbcTemplateLogParser 热点正则提常量；三沉淀=URLClassLoader 父优先遮蔽织入字节致测试空真（须子优先 loader，硬验收在 AttachGateTest）、测试保存值「finally 现读」还原恒失败串染后续类、LogSqlEntry 加构造字段陈旧产物劫持第八犯 |
| [m37-series-closure.md](bug-fixes/m37-series-closure.md) | M37 竞品融合系列（P-立项-06）全章闭环核实（20260916，无待办）：M37.1/2/3 均 cherry-pick 移植进 master（新 SHA eb27063d/aa47fd2b，dev 原 SHA 不被 --contains 命中）；M37.4 Wrapper→测试资产生成实施后整体下线定案（静态骨架价值不足，重做方向=接真实测试框架）；M37.5 Flex 调研暂缓+M38 吸收已落地（锚点负例回归+81 场景金样修 6 个 MP 语义误报） |
| [t-panorama-audit.md](bug-fixes/t-panorama-audit.md) | 全分支功能全景审计与长期演进（报告+四计划，master 6306cf8/44161eb，20260916）：八域资产×用户可见性，结论=功能深度过剩引导深度不足、短板全在第一次接触；立项 T-UxOnboard/T-ExplainIndex/T-BranchHygiene/T-PerfSweep3；K2J 线只有成本没进展（O-24 决策挂起）；N-20/N-21 市场动作随 N-17 窗口 |
| [t-perf-round5-report.md](bug-fixes/t-perf-round5-report.md) | 第五轮性能复查（报告+T-PerfSweep3 立项，master 44161eb，20260916）：默认零开销维持+T-PerfSweep2 六修复二遍实证全落地+FeedbackBatch2 防抖挂平台共享池；中低=JdbcTemplateLogParser:230 每行现场编译正则（一行修）；另 4 低含重放守卫/WrapperCache 两连续三轮在册 |
| [t-competitive-round5.md](bug-fixes/t-competitive-round5.md) | 第五轮竞品横向对比（报告 master 3213a21+十计划 b6c0e1b，20260916）：MyBatisX 1.7.7 包体解剖证伪盲区威胁（plugin.xml mcp/preview/explain 零命中、since 自收窄 251）；v2026.9.14 已过审上架 +13/日但 0 评论；DBX 清单 v4 #19~#26（#19 错误定位跳转 P0）+Marketplace API 口径沉淀；立项 T-MergeDev2Master 前置+N-18/N-19/O-15~O-21 |
| [t-feedbackbatch2.md](bug-fixes/t-feedbackbatch2.md) | T-FeedbackBatch2 批量报告多次弹窗合并（merge 416ec26，2026-09-15，4173/0/0/6 绿）：262 平台「报告全部」=reportAll 逐簇循环、reportMessage 恒造长度 1 事件数组（字节码实证）；修法=提交器 500ms debounce 累积合并+复合 consumer，批次 ≥2 才出分节；沉淀=javap 输出 CR 字节致 grep 二进制零匹配用 python、DialogWrapper 实例逻辑进 companion 才可 headless 测 |
| [t-ui-native-research.md](bug-fixes/t-ui-native-research.md) | T-UiNative UI/功能原生化调研+四批次实施（t-uinative 分支 8 提交 0e857ec..5ca0b76，20260916，4147/0/0/6 绿）：剪贴板统一 UiTextClipboard/19 处模态换通知/OnePixelSplitter/右键 ActionGroup/ListSpeedSearch/选中描边退役/JOIN 图工具栏/33 色 MpvsUiColors/JBLoadingPanel/项目页 UI DSL 试点；四沉淀=243 类在 gradle transforms app-client.jar、CopyPasteManager headless 不回写系统剪贴板、BannedApiSourceScanTest 钦定 updateActionsImmediately、finally「现读还原」测试污染范式已修 |
| [t-perfsweep2.md](bug-fixes/t-perfsweep2.md) | T-PerfSweep2 第四轮性能复查与六发现点收敛（merge 1b67f57，2026-09-15，4103 绿）：六任务实施要点与三处修订（03 批量附件固定 8k 改半幅均分、05 自测抓出丢唤醒洞、04 补丁过滤按 hunk 拆提交）；沉淀=测试调生产网络路径必先接 sender seam、BasePlatformTestCase basePath=null 且 VirtualFile 抽象类不可代理、Bash 的 /tmp 对 python.exe 不可见 |
| [t-releaseperf.md](bug-fixes/t-releaseperf.md) | T-ReleasePerf 发版链路提速（merge 99d89af，20260915）：合 master 后三目标 7m26s→3m06s（-58%）、smoke 35s 验证；releaseGate 一命令/verifyOnly；P2-2 诊断证伪；合并实战=零冲突、worktree remove 撞路径长度限 rm+prune 收尾 |
| [t-fourplans-20260914.md](bug-fixes/t-fourplans-20260914.md) | 四计划批次交付（r2合流+MCP+PreviewUx+PerfTail，dev 5f80b6c，4244 绿+verifyGate exit 0 复验）：三大沉淀=跨版本桩按最小公共接口面建模（K2 兼容桥 253 悬空）、K2 SpillingKt 高阶 suspend 结构性发射、IPGP verifyPlugin 不采纳 CLI 豁免（verifyGate 直驱 CLI 为权威门禁）；CHANGELOG 合并条目会落错版本块必查 |
| [t-intranet.md](bug-fixes/t-intranet.md) | T-Intranet 内网离线可用性审计（merge 003c926，4125/0/0/6 绿，20260916 核实）：默认配置内网完全可用，运行期仅 2 出口 4 调用点（更新/反馈通道）均降级不阻塞；无遥测/AI/内嵌 server/账号校验/驱动下载/DTD 联网；新发现=mpvs.update.manifest.url 只在进程启动时读一次（改 -D 需重启 IDE）；口径红线=禁写「完全不联外网」 |
| [t-listparamcollect.md](bug-fixes/t-listparamcollect.md) | T-ListParamCollect JdbcTemplate 集合参数方括号（merge 09cc6c6，随 v2026.9.18-beta.1 链发行）：只在 IN 位置剥括号、非 IN 逐字节不变、不声称集合传参、零 i18n 键；预览走 restoredSql 回退分支故只改解析器不够；坑=探针文件与构建产物混淆自证循环+isInKeyword 折行变量读写错位 |
| [t-mcp-toolset2.md](bug-fixes/t-mcp-toolset2.md) | T-McpToolset2 MCP 二期（dev f42b739 快进合并，4421 绿，20260915）：工具 3→10+设置页门控（总开关+工具级）；一工具一 toolset 类；平台 per-tool 门控只有 toolset 粒度 isEnabled（getEnabledToolsets 每次重新求值即时生效）；七工具核心抽非 suspend 纯函数可测；mcpStub 须挂 testShardN classpath；顺手收口 74f7842 合并回归三处 |
| [t-competitive-round4-plans.md](bug-fixes/t-competitive-round4-plans.md) | 第四轮竞品应答七任务（N-16+O-9~O-14，dev 8ad316b，4410 绿 +143 例，20260915）：三处偏差定案与 JSqlParser 5.3 解析形状实证（ParenthesedExpressionList/UnsupportedStatement/Block） |
| [t-shortcut-settings.md](bug-fixes/t-shortcut-settings.md) | T-ShortcutSettings 快捷键统一设置与 MCP 独立子页（aed3b07+38566f4+fe6f859，20260915）：5 个插件快捷键收归独立设置节+MCP 独立子页（位列关于上方；平台 Configurable 类名 com.intellij.mcpserver.settings.McpServerSettingsConfigurable，262 jar javap 实证）；编译四坑（Class.forName 强转、runCatching&lt;Unit&gt;、FormBuilder.addSeparator 拒字符串、JBTextField 参为列数） |
| [t-testperf2.md](bug-fixes/t-testperf2.md) | T-TestPerf2 全量测试一命令并行提速（merge 26eeb09，20260915，4081 绿）：testShards 一命令 3 片真并行 4m47s→2m05s 零用例改动；三大沉淀=build cache 用户级属性优先级覆盖项目（关闭落 settings buildCache.local 才生效）、分片类发现文件名≠类名改内容提取、剪贴板机器级互斥源码扫描亲和同片 |
| [t-marketinternal.md](bug-fixes/t-marketinternal.md) | T-MarketInternal 插件市场内部API整改（merge 208f9b3，20260915，4081 绿）：262 Internal 6→0 与 for-removal 3→0 双清零；MessagePool 标注 262 才有（243 verifySmoke 盲区）、错误池候选整链删除、findElement 直取链尾 DbModelRegistry |
| [t-competitive-round4.md](bug-fixes/t-competitive-round4.md) | 第四轮竞品对比（20260915-0123 报告）：N-12 MCP 三工具/N-13 四件套/r2 合流在 t-fourplans 集成链、「竞品 MCP 全空」失效（CodeHelperPro 校验我渲染）、DBX 增量 #14~#18（structuredContent 必须对象）、新建议 N-15/N-16+O-12~O-14 |
| [t-competitive-round3.md](bug-fixes/t-competitive-round3.md) | 第三轮竞品对比：Marketplace 三连发过审下载翻倍、MCP 规划 N-12 主轨改平台 MCP Server 体系、N-14 平台 LLM API 已证伪、Analyzer 威胁低、评论新端点 comments；r2 分支债清账、t-m56-00/01 待合跟踪 |
| [t-preview-exec-ds.md](bug-fixes/t-preview-exec-ds.md) | T-PreviewExecDs 预览补全/跨项目数据源（20260914，3978 绿）：Temp 预览文件+预热静默失败、cachedTempPsiFile 跨项目泄漏、console 被清扫 dispose 三根因与 F1~F5+P1-1~4 修复（预览文件迁项目内+内存 PSI 降级链）；P1-5 留二期 |
| [t-feedbackerr.md](bug-fixes/t-feedbackerr.md) | T-FeedbackErr 上报失败可见性+T-FeedbackBatch 批量限流规避三轮（c406063/d192216/9a5091f，4083 绿，20260914）：errorStream+服务端 message 优先、gzip 假失败根治（myGzip 默认 true）、2~5 条合并一帖+每 12 分钟错峰补发、附件 charset+BOM 双保险与乱码定性法、D1 诊断法；遗留=附件上传未端到端验证、FR-0023 测试帖待清 |
| [t-feedbackdlg.md](bug-fixes/t-feedbackdlg.md) | T-FeedbackDlg 报告对话框缺陷三波（96bcb62/943494c/23a8581，4025 绿，20260914）：堆栈勾选跳动 weighty 行须 toggleResizeDelta 高度补偿、手动形态堆栈自主输入+错误池按包名导入；红线=反馈 UI 别替用户自动决定 |
| [t-explain-dbl.md](bug-fixes/t-explain-dbl.md) | T-ExplainDbl 执行计划 EXPLAIN 前缀双加 1064（merge 470b5fe，3980 绿，20260914）：执行链回写控制台文档后二次包装；Dialect 幂等包装 renderExplainStatementIdempotent；教训=包装 SQL 的按钮须幂等 |
| [t-exec-slowops.md](bug-fixes/t-exec-slowops.md) | T-ExecSlowOps 线程化双 SEVERE（merge 80e7d9c，4028 绿，20260914）：DialectPreMapping 两段式映射单入口（BGT 读探测/首见 EDT+latch）+prewarmExecTempFile 挂 previewAsync+四缓存 @Volatile；守卫=setMapping 字面量单文件收口 |
| [t-intrupg.md](bug-fixes/t-intrupg.md) | T-IntrUpg 旧拦截器自位污染防线与升级（fa79944，4012 绿，20260914）：isInterceptorSelfPath 解析防线+needsInterceptorUpgrade 三条件纯函数+注入链升级执行；用户恢复=重新注入+应用重启 |
| [t-jumppath.md](bug-fixes/t-jumppath.md) | T-JumpPath 多模块跳转三缺陷（merge d9d3c02，4002 绿，20260914）：isSelf 运行时比对封自位污染、Resolver 三级解析补 sourceRoots、sameFile 段对齐封反查恒空、逐模块注入 successMulti |
| [t-jumpcapture.md](bug-fixes/t-jumpcapture.md) | T-JumpCapture 跳转仅依赖捕获位置（merge ffc518c，3975 绿，20260914）：Wrapper 表名回退链整体删、缺位置 SourceCaptureGuide 三按钮引导弹框 |
| [t-loghistoryrow-no-plan.md](bug-fixes/t-loghistoryrow-no-plan.md) | T-LogHistoryRowNo SQL 历史列表行号（master 9e01e26，4020 绿，20260914）：parseContent 读取层注入文件内顺序号、HistoryCellRenderer 复用 #N 片段 |
| [t-mergefollowup.md](bug-fixes/t-mergefollowup.md) | T-MergeFollowup 合并反馈四缺陷三轮（04eb8f9~fec7d33，20260914）：243 gutter 右键基类恒 null 须匿名 LineMarkerInfo 覆写 createGutterRenderer、BasicListUI cellHeights 缓存唯一可靠失效=list.updateUI()、下拉按钮与 SQL类型 同构 JButton+ArrowDown+JPopupMenu |
| [t-listpreviewcopy.md](bug-fixes/t-listpreviewcopy.md) | T-ListPreviewCopy 预览格式化+多选防误弹+批量复制（merge 280eddc，4039 绿，20260914）：预览跟随「预览时格式化还原SQL」设置、batchCopySql 与内置 Ctrl+C 逐字节同构；坑=测试挂起定时器真弹浮窗致 Editor 泄漏（finally dispose 停表） |
| [t-explainfirstnative.md](bug-fixes/t-explainfirstnative.md) | T-ExplainFirstNative 首点结果缺失+EXPLAIN 对齐原生（master，4081 绿，20260914）：DBN 空名占位结果 Tab 被当新结果第 1 拍强制换入；EXPLAIN=原生 MySQL 家族裸 EXPLAIN 表格行；教训=换入去重只认实例身份会漏「实例未变内容已变」 |
| [t-rollbacksql-plan.md](bug-fixes/t-rollbacksql-plan.md) | 预览回滚 SQL 按钮交付（r2 分支，F1~F6）：262 连接 API 不可达走快照粘贴半自动降级、JSqlParser 5.3 四坑、整行谓词恒 Partial；分支债归第三轮竞品对比跟踪 |
| [t-crudcolor-plan.md](bug-fixes/t-crudcolor-plan.md) | SQL 日志/历史列表 CRUD 类型颜色标记（44 例，2026-09-13 合入 master）：色条/整行淡背景/关闭三选一 + 四色自定义 + 2px 描边选中态 |
| [t-mergedevcrudcolor-delivery.md](bug-fixes/t-mergedevcrudcolor-delivery.md) | dev 与 t-crudcolor 两步真 merge master（4f60553+37e0728）：master 吸收 dev 全部 119 提交成全功能超集、渲染器组件池×色条融合四定式 |
| [t-m56-00-delivery.md](bug-fixes/t-m56-00-delivery.md) | M56 消费端先导（feature/t-m56-00-consumer）：条目四字段+AGENT 来源/标记行 3~5 段/pd 载荷扩展回环/聚焦过滤层 |
| [t-m56-agent-delivery.md](bug-fixes/t-m56-agent-delivery.md) | M56 agent 字节码通道一期（feature/t-m56-01-agent-base）：fat jar/attach 门禁 JDK17+JDK8 双目标实测/IDE 集成/consoleFilterProvider EP 双轨 |
| [branch-port-loss-audit.md](bug-fixes/branch-port-loss-audit.md) | dev→master 人工移植丢功能全量审计（假开关 4/缺失 9/带病 9）——2026-09-13 真合并 master 后全部回合清账，仅存历史价值 |
| [project-dynupdate-hotswap.md](bug-fixes/project-dynupdate-hotswap.md) | 自更新免重启热替换交付（master 28b884b）：五入口反射+跨版本签名护栏；「未完全卸载」警告=GC 时机非泄漏 |
| [t-pickmpjerr-delivery.md](bug-fixes/t-pickmpjerr-delivery.md) | SQL转Java 选类重渲染 MPJ 业务异常逃逸 EDT 根治（master ce62959）：generateCodeOrReport 单点收口 |
| [t-sql2java-regenerate-uncaught.md](bug-fixes/t-sql2java-regenerate-uncaught.md) | 同一笔修复的过程记忆（预览读断言连刷=旧构建，堆栈无 WIRA lambda 帧=判据） |
| [t-mergedevr2round3-delivery.md](bug-fixes/t-mergedevr2round3-delivery.md) | dev→r2 第三轮合并（73eaa90）：21 文件 40 冲突块取 dev 超集侧，3815 用例一次过 |
| [t-mergemasterintodev2-delivery.md](bug-fixes/t-mergemasterintodev2-delivery.md) | master→dev 第二轮合并（28765fa）：5 冲突块语义融合，dev 与 master 完全同步 |
| [t-autofold-plan.md](bug-fixes/t-autofold-plan.md) | SQL 预览字段列表自动折叠（master a120bfa，随 v2026.9.14-beta.1 发行）：程序化 FoldingModel |
| [t-crudsql-plan.md](bug-fixes/t-crudsql-plan.md) | 实体类+数据库表右键生成 CRUD SQL（master 3be4dfb 起，三轮弹窗原生化已合入） |
| [project-tagline.md](bug-fixes/project-tagline.md) | 产品标语「服务未启，SQL 先知」五处统一（master 2c1ee75）+ 反馈入口切官网需求池 |
| [project-idea-diagram-framework.md](bug-fixes/project-idea-diagram-framework.md) | JOIN 图 IDEA 原生框架接入全集（已发行；dev 已随 master 合入，2026-09-13 实证） |
| CoolRequest agent 分析（内部资料库） | M56 输入调研：CoolRequest premain 实态/驱动自拼 SQL/ByteBuddy retransform/relocate |
| DebugTools agent 分析（内部资料库） | M56 输入调研：DebugTools 真 attach+agentmain 反面教材与可照搬四手法 |
| [t-archdocs-sync-dev.md](bug-fixes/t-archdocs-sync-dev.md) | docs/架构设计 五文件同步 dev 基线（dev 2eaf8dc）；分支同步范围定案已收编 project-doc-conventions |
| [t-histparam-plan.md](bug-fixes/t-histparam-plan.md) | 历史页参数识别丢失根治 + 字面量恢复两轮（v2 pd 行 + LiteralParamRecovery，bceeee9/3d72025） |
| [t-perfsweep-delivery.md](bug-fixes/t-perfsweep-delivery.md) | dev 遗留四热点清扫（重放守卫惰性化/热路径 Regex/dedupeCounts 4096 上限） |
| [t-perfbatch-plan.md](bug-fixes/t-perfbatch-plan.md) | 监听器三出口分层 + 内容通知「20条/200ms」节流（dev 5ff1e29） |
| [t-previewslow-delivery.md](bug-fixes/t-previewslow-delivery.md) | 双击预览卡顿根治（预热等待 2000ms→60ms + 控制台挂载去 ReadAction） |
| [t-versionutil-delivery.md](bug-fixes/t-versionutil-delivery.md) | 版本比较改用平台 VersionComparatorUtil（beta 先行提示倒挂） |
| [t-previewdoclock2-delivery.md](bug-fixes/t-previewdoclock2-delivery.md) | EDT 读权限断言全库排雷 + 原生控制台挂载修复（3cba9f6） |
| [t-previewdoclock-delivery.md](bug-fixes/t-previewdoclock-delivery.md) | 262 读权限硬断言排雷（softAssert* 名字骗人，1ef8bf6） |
| [t-dynupdate-plan.md](bug-fixes/t-dynupdate-plan.md) | 自更新免重启动态安装全链 + 跨版本签名护栏（771b5f2/9f8821d） |
| [t-vpairanno-plan.md](bug-fixes/t-vpairanno-plan.md) | 竖排 VALUES 行尾列名标注配置项 + 复选框台账教训（ca1a376） |
| [t-previewdisplaymode-plan.md](bug-fixes/t-previewdisplaymode-plan.md) | 显示模式形态消失根因（预览编辑器=模型派生视图） |
| [t-previewdisplaymode-delivery.md](bug-fixes/t-previewdisplaymode-delivery.md) | applyDisplayMode 落 showSql 唯一收敛点（1305a5a） |
| [t-hibdualcapture-plan.md](bug-fixes/t-hibdualcapture-plan.md) | Hibernate 双份 SQL 参数分裂根治（隔离槽 !collecting 冲刷，95a83ef） |
| [t-hibdualcapture-repro-method.md](bug-fixes/t-hibdualcapture-repro-method.md) | 日志抓包复现方法论（demo 副本临时端口逐行喂解析器） |
| [t-execsilentnoop-plan.md](bug-fixes/t-execsilentnoop-plan.md) | 首次执行静默不执行（DBN 静默出口实证 + freshModel 回落兜底） |
| [t-vstack-plan.md](bug-fixes/t-vstack-plan.md) | 竖向排列三笔（INSERT 配对竖排/横向组展开/IN 阈值 <5 同行） |
| [t-previewtopoverlap-plan.md](bug-fixes/t-previewtopoverlap-plan.md) | 预览顶部元信息压按钮行（WEST→CENTER + 「更多」菜单，f2b0fe3） |
| [t-mergedevr2-delivery.md](bug-fixes/t-mergedevr2-delivery.md) | dev 合入 r2 + 生成测试双线下线（9b477fe/b632086） |
| [t-refreshdbsteal-delivery.md](bug-fixes/t-refreshdbsteal-delivery.md) | 多弹窗僵尸 rehost 监听抢共享控制台根治（9cb2985，8 用例） |
| [t-refreshdbsteal-plan.md](bug-fixes/t-refreshdbsteal-plan.md) | 刷新后内嵌 Database 控制台丢失三症状方案定稿 |
| [t-depapi-delivery.md](bug-fixes/t-depapi-delivery.md) | 三处弃用 API 换现代等价物（三平台弃用 40→38，08d75c4） |
| [t-execcopyfix-plan.md](bug-fixes/t-execcopyfix-plan.md) | 复制错误对号闪变 + 临时文件失败可见化（642d73b） |
| [t-logrownumber-plan.md](bug-fixes/t-logrownumber-plan.md) | SQL 日志列表行号 + 主配置 gating/跨窗口去重两轮 follow-up（c83fb56） |
| [t-autoshowmaster-delivery.md](bug-fixes/t-autoshowmaster-delivery.md) | 发现 SQL 自动打开日志窗口 master 假开关修复（6ede29d） |
| [t-master-merge-into-dev-20260908.md](bug-fixes/t-master-merge-into-dev-20260908.md) | master 合入 dev（ListMode 嫁接管线 + 自动合并陷阱实录，399eb3b） |
| [t-wia262-delivery.md](bug-fixes/t-wia262-delivery.md) | WIRA 重载 262 移除致 verifyPlugin 失败修复（6ea945e） |
| [t-dscover-t-clickpreview05-delivery.md](bug-fixes/t-dscover-t-clickpreview05-delivery.md) | 双击预览 Database 工具窗抢占 + 复制按钮对号反馈（a091811/de03f58） |
| [t-previewflash-plan.md](bug-fixes/t-previewflash-plan.md) | 预览点击侧边栏闪烁根治（重放守卫 + 慢操作消解 + 方言短路，89f63eb） |
| [t-previewlistreload-plan.md](bug-fixes/t-previewlistreload-plan.md) | 预览点击致列表重载方案收编入 T-PreviewFlash |
| [t-errreport-plan.md](bug-fixes/t-errreport-plan.md) | 错误一键上报官网需求池（errorHandler EP 直报，dev a2de464） |
| [t-jdbcmultiline-plan.md](bug-fixes/t-jdbcmultiline-plan.md) | 多行 JdbcTemplate 日志漏捕获两态机修复（1d0cb3e） |
| [t-twoline-list-meta-line-delivery.md](bug-fixes/t-twoline-list-meta-line-delivery.md) | 列表元信息行（dev aae6768，空元信息守卫） |
| [t-loglistfreeze04-delivery.md](bug-fixes/t-loglistfreeze04-delivery.md) | 列表 HTML 逐条解析 EDT 冻结 16s 根治（9223f44） |
| [t-portlossbatch1-delivery.md](bug-fixes/t-portlossbatch1-delivery.md) | 丢功能审计二/三类批量修复两笔（0c613d0/07b7d32） |
| [branch-dev-to-master-merge-probe.md](bug-fixes/branch-dev-to-master-merge-probe.md) | T-AutoShowRecover 会话抑制选择性恢复 + stopOnToolWindowClose 转正（b3c2ba3） |
| [project-dev-branch-state.md](bug-fixes/project-dev-branch-state.md) | dev 分支状态快照（worktree 布局/自动监听 cherry-pick 记录） |
| [project-f42-join-diagram-render.md](bug-fixes/project-f42-join-diagram-render.md) | JOIN 图自绘 Swing 引擎与踩坑（后被 IDEA 原生图框架替代） |
| [reference-competitor-plugin-links.md](bug-fixes/reference-competitor-plugin-links.md) | README「参考与致谢」6 项定稿（Marketplace API 核验法） |
| [project-exists-bugfix.md](bug-fixes/project-exists-bugfix.md) | exists 条件预览链终结点误判 |
| [project-iscrud-entity-resolution-bug.md](bug-fixes/project-iscrud-entity-resolution-bug.md) | IService/BaseMapper 实体解析后缀硬约束（v2026.8.19 已修复） |
| [project-iscrud-this-and-chain-qualifier-bug.md](bug-fixes/project-iscrud-this-and-chain-qualifier-bug.md) | 隐式 this 与链式调用预览失败 |
| [project-reverse-placeholder-bugfix.md](bug-fixes/project-reverse-placeholder-bugfix.md) | SQL→Java 反向 `?` 占位符识别缺陷 |
| [project-reverse-sql-java-improvements.md](bug-fixes/project-reverse-sql-java-improvements.md) | SQL→Java 反向链路质量大修（c1b8f56 + b77361f） |
| [project-reverse-join-fix.md](bug-fixes/project-reverse-join-fix.md) | SQL→Java 反向联表 JOIN 转换修复 |
| [project-reverse-invocation-has-mpj-interface-bug.md](bug-fixes/project-reverse-invocation-has-mpj-interface-bug.md) | invocationHasMpj 漏判接口继承 MPJBaseService |
| [project-reverse-entity-scan-performance.md](bug-fixes/project-reverse-entity-scan-performance.md) | SQL→Java 无调用类性能问题（P0 反查缓存 + P1 启发式 + P2 扫描优化） |
| [project-jpa-hibernate-fix-20260820.md](bug-fixes/project-jpa-hibernate-fix-20260820.md) | JPA/Hibernate 识别缺陷三波并行修复（a90dda6） |
| [project-jpa-test-hardening-20260821.md](bug-fixes/project-jpa-test-hardening-20260821.md) | JPA 测试体系三层加固 |
| [project-hibernate-jpa-support.md](bug-fixes/project-hibernate-jpa-support.md) | Hibernate/JPA 支持已交付（v2026.8.20） |
| [project-release-jpa-deferred.md](bug-fixes/project-release-jpa-deferred.md) | JPA/Hibernate 随 v2026.8.21 发行 |
| [project-sql-capture-no-project-required.md](bug-fixes/project-sql-capture-no-project-required.md) | SQL 监听手动启动无需等待项目（T-13，v2026.8.20） |
| [project-p051-import-dialog.md](bug-fixes/project-p051-import-dialog.md) | 参数导入增强（P-05.1，v2026.8.20） |
| [project-log-capture-lifecycle-design.md](bug-fixes/project-log-capture-lifecycle-design.md) | 日志捕获生命周期设计 |
| [project-sql-log-filterbar.md](bug-fixes/project-sql-log-filterbar.md) | SQL 日志面板过滤条 |
| [project-screenshot-placeholders.md](bug-fixes/project-screenshot-placeholders.md) | 功能截图占位已就位（d32d9a8） |
| [project-m51-api-cleanup-gaps.md](bug-fixes/project-m51-api-cleanup-gaps.md) | M51 清理后剩余 gap |
| [project-20260820-dev-plan-execution.md](bug-fixes/project-20260820-dev-plan-execution.md) | 2026-08-20 开发计划执行记录 |
| [project-test-speedup.md](bug-fixes/project-test-speedup.md) | 测试提速实测（clean test 带缓存 1m03s 最优） |
| [project-update-channel-beta-refactor.md](bug-fixes/project-update-channel-beta-refactor.md) | 更新机制改为 Gitee 抢先体验通道 |
| [project-architecture-docs.md](bug-fixes/project-architecture-docs.md) | docs/架构设计/ 三篇系列文档指针 |
| [m30-m33-features.md](bug-fixes/m30-m33-features.md) | M30-M33 全量开发完成（2026-08-23）：11 功能模块 |
| [m41-toolbar-i18n-cleanup.md](bug-fixes/m41-toolbar-i18n-cleanup.md) | M41 工具栏国际化修复 + 布局分离（2026-08-23） |
| [project-f42-join-diagram-batik.md](bug-fixes/project-f42-join-diagram-batik.md) | F-42 JOIN 图渲染修复完成（2026-08-25）：PlantUML→自绘 Swing |
| [project-f43-auto-save-sql.md](bug-fixes/project-f43-auto-save-sql.md) | F-43 自动保存历史 SQL（2026-08-25） |
| [project-f44-sql-log-merge-sidebar.md](bug-fixes/project-f44-sql-log-merge-sidebar.md) | F-44 SQL 日志合并主侧边栏 + 源码定位（2026-08-25） |
| [project-ide-freeze-20260824.md](bug-fixes/project-ide-freeze-20260824.md) | IDEA 2026.2 卡顿根因：SqlLogPanel O(N²) 事件风暴（M46 已修复） |
| [project-m28-update-channel.md](bug-fixes/project-m28-update-channel.md) | M28 内部更新机制 Gitee 抢先体验通道改造（2026-08-22） |
| [project-m47-jdbc-template-log.md](bug-fixes/project-m47-jdbc-template-log.md) | M47 Spring JdbcTemplate SQL 日志支持（2026-08-24） |
| [project-pages-xml-preview.md](bug-fixes/project-pages-xml-preview.md) | F-45 低代码框架 XML HQL 预览（2026-08-26） |
| [project-plantuml-render-error.md](bug-fixes/project-plantuml-render-error.md) | PlantUML JOIN 图渲染修复链（2026-08-23~25） |
| [project-plugin-xml-kotlin-optional.md](bug-fixes/project-plugin-xml-kotlin-optional.md) | plugin.xml Kotlin 语言 ID 注册修复（2026-08-25） |
| [project-settings-layout-optimization.md](bug-fixes/project-settings-layout-optimization.md) | 设置页布局优化 Phase 1-3（2026-08-26） |
| [project-settings-no-nested-configurable.md](bug-fixes/project-settings-no-nested-configurable.md) | 设置页不再注册 projectConfigurable（2026-08-26） |
| [toolwindow-button-tooltip-mechanism.md](bug-fixes/toolwindow-button-tooltip-mechanism.md) | F-41 右侧面板按钮 tooltip 机制修复（2026-08-25） |
| [m30-m33-features](bug-fixes/m30-m33-features.md) | M30-M33 全量开发已完成（2026-08-23 合并）——11 功能模块：SQL 诊断/复杂度评分/JOIN |
| [m41-toolbar-i18n-cleanup](bug-fixes/m41-toolbar-i18n-cleanup.md) | M41 完成——SQL 预览工具栏按钮国际化修复、性能警告栏布局分离、Copy Executable SQL 按钮移除 |
| [project-20260820-dev-plan-execution](bug-fixes/project-20260820-dev-plan-execution.md) | 20260820 全量优化与新功能开发计划——11 项立项、波次并行开发执行记录 |
| [project-architecture-docs](bug-fixes/project-architecture-docs.md) | 新增 docs/架构设计/ 三篇系列文档（Mermaid 架构图+流程图），面向新上手开发者与 AI 会话速览 |
| [project-exists-bugfix](bug-fixes/project-exists-bugfix.md) | 用户实测 SQL 预览缺 exists 条件——根因是 WrapperLocator 链终结点误判（exists |
| [project-f42-join-diagram-batik](bug-fixes/project-f42-join-diagram-batik.md) | F-42 JOIN图渲染修复完成（2026-08-25，commit 82797a0）——PlantUML 8059→1.2024.8（Smetana 引擎）；F-xx（2026-08-26，commit d175317）彻底移除 PlantUML + Batik，改用自绘 Swing Graphics2D 引擎，插件体积 42MB→5.3MB |
| [project-f43-auto-save-sql](bug-fixes/project-f43-auto-save-sql.md) | F-43 已完成（2026-08-25）——自动保存历史 SQL：onEvicted 回调 + |
| [project-f44-reliable-jump-and-test-coverage](bug-fixes/project-f44-reliable-jump-and-test-coverage.md) | F-44 实时日志可靠跳转源码方案（InnerInterceptor 标记行）+ 全插件 UI/集成测试覆盖（50+ 文件 200+ 用例，v2026.9.2 发行） |
| [project-f44-sql-log-merge-sidebar](bug-fixes/project-f44-sql-log-merge-sidebar.md) | F-44 已完成（2026-08-25）——SQL 日志合并到主侧边栏 + 源码定位 + 历史读取 |
| [project-hibernate-jpa-support](bug-fixes/project-hibernate-jpa-support.md) | Hibernate/JPA 全面支持已交付（2026-08-20）——4 阶段开发，已合并 master，1682 测试全绿 |
| [project-hql-association-xml-mapping](bug-fixes/project-hql-association-xml-mapping.md) | HQL 关联字段路径解析 + Hibernate XML 映射支持（feature/f45 分支），含 entity resolution 三级降级链 |
| [project-hql-variable-resolution](bug-fixes/project-hql-variable-resolution.md) | HQL 变量引用拼接初始化式解析的两次缺口：HqlQueryLocator |
| [project-ide-freeze-20260824](bug-fixes/project-ide-freeze-20260824.md) | IDEA 2026.2 卡顿根因分析（2026-08-24）——SqlLogPanel 列表刷新导致 20~30 秒 EDT |
| [project-iscrud-entity-resolution-bug](bug-fixes/project-iscrud-entity-resolution-bug.md) | IService/BaseMapper 内置 CRUD 方法（getById 等）预览失败——实体类无 Entity 后缀时 |
| [project-iscrud-this-and-chain-qualifier-bug](bug-fixes/project-iscrud-this-and-chain-qualifier-bug.md) | IService/BaseMapper 内置方法隐式 this 调用和链式调用预览失败——已修复（2026-08-20） |
| [project-jdbctemplate-sql-string-preview](bug-fixes/project-jdbctemplate-sql-string-preview.md) | JdbcTemplate/NamedParameterJdbcTemplate SQL 字符串正向预览（2026-09-03 已完成，feature/jdbc-template-sql-preview 分支）——命名参数 :name 按名回填 + StringBuilder 分片聚合 + 独立 psource 包 |
| [project-jpa-hibernate-fix-20260820](bug-fixes/project-jpa-hibernate-fix-20260820.md) | JPA/Hibernate 识别缺陷三波并行修复已完成并合并到 master |
| [project-jpa-test-hardening-20260821](bug-fixes/project-jpa-test-hardening-20260821.md) | JPA 测试体系三层加固（2026-08-21）——组件契约升级+端到端集成+面板行为测试，防回归 |
| [project-log-capture-lifecycle-design](bug-fixes/project-log-capture-lifecycle-design.md) | SQL 日志自动监听生命周期设计——Project 级 capturing 状态位、多模块/多项目行为、双保险触发机制、100ms 快速轮询、lastConsoleIds 移除检测 |
| [project-m28-update-channel](bug-fixes/project-m28-update-channel.md) | M28 内部更新机制已改造为 Gitee 抢先体验通道（2026-08-22）——双 JSON 文件、忽略此版本、本地测试工具 |
| [project-m36-direct-execute](bug-fixes/project-m36-direct-execute.md) | M36 SQL预览直接执行（2026-08-27~29）——M36.4/M36.5 原生控制台内嵌成功（共享复用+UiDataProvider+runWhenSmart）；M36.5.14~21d 终局解决"执行内容滞后一轮"（执行链切原生两步 findScriptModel→doRunQueryInConsole + TransactionGuard write-safe 事务） |
| [project-m365-console-embed-plan-a](bug-fixes/project-m365-console-embed-plan-a.md) | 方案A（原生控制台整控台嵌入）2026-08-30 落地——JdbcConsole 无 getComponent、持久整控台强制换底、A3 rehost 去抖防死循环、lag-一轮/8s误报修复、File Cache Conflict 三方同步、沙盒验证判据 |
| [project-m37-m38-competitive-flex-absorption](bug-fixes/project-m37-m38-competitive-flex-absorption.md) | M37 竞品精华融合 + M38 Flex 精华吸收均已实施完成(2026-09-02,分支 |
| [project-m47-jdbc-template-log](bug-fixes/project-m47-jdbc-template-log.md) | M47 已完成——Spring JdbcTemplate SQL 日志支持（2026-08-24），含标准 JdbcTemplate + NamedParameterJdbcTemplate 两种格式，修复 LogToSqlPanel 遗漏 |
| [project-m51-api-cleanup-gaps](bug-fixes/project-m51-api-cleanup-gaps.md) | T-M51 废弃API清理已实施完成（2026-08-19），双版本 0 Internal API，0 Scheduled-removal |
| [project-marketplace-publishing](bug-fixes/project-marketplace-publishing.md) | Marketplace 发布流程文档（双仓库方案/签名现状/审核 4 步/自更新联动），首次发布前必读 |
| [project-o4-diff-native](bug-fixes/project-o4-diff-native.md) | O-4 差异对比改用 IDEA 原生 Diff（DiffManager.showDiff），移除自研行内高亮与 SqlDiffDialog |
| [project-p05-dialect-dbx-envs](bug-fixes/project-p05-dialect-dbx-envs.md) | P-立项-05 方言真机验证已完成（2026-08-29，DBX MCP 通道七家族零缺陷）；DBX 测试环境清单 + 「渲染导出→MCP 执行」工作流与陷阱 |
| [project-p051-import-dialog](bug-fixes/project-p051-import-dialog.md) | P-05.1 参数导入弹窗+驼峰/下划线匹配+JSON 高亮+默认回显当前参数已交付（2026-08-20） |
| [project-pages-xml-preview](bug-fixes/project-pages-xml-preview.md) | F-45 已完成——低代码框架 XML（<pages>/<maintainPage>/<datasource> CDATA HQL）预览支持 |
| [project-plantuml-render-error](bug-fixes/project-plantuml-render-error.md) | PlantUML JOIN 图渲染修复链（2026-08-23~25）——!theme plain 不兼容 8059 已修复；后续发现 linetype ortho 依赖 Graphviz、8059 SVG 输出恒为 Graphviz 错误图（详见 F-42） |
| [project-plugin-xml-kotlin-optional](bug-fixes/project-plugin-xml-kotlin-optional.md) | plugin.xml 校验报错修复——Kotlin 语言 ID 需注册到可选依赖描述文件，supportsKotlinPluginMode 必须放在 extensions 内 |
| [project-release-jpa-deferred](bug-fixes/project-release-jpa-deferred.md) | JPA/Hibernate 内容已从 v2026.8.20 排除，2026-08-21 随 v2026.8.21 成功发行（预览是核心亮点） |
| [project-reverse-entity-scan-performance](bug-fixes/project-reverse-entity-scan-performance.md) | SQL→Java 无调用类性能问题已修复（P0 反查缓存 + P1 启发式候选 + P2 扫描优化，1111 全绿）——O(N) 全量扫描降 O(1)，估算 100-500 倍 |
| [project-reverse-invocation-has-mpj-interface-bug](bug-fixes/project-reverse-invocation-has-mpj-interface-bug.md) | SQL→Java 反向 invocationHasMpj 漏判接口继承 MPJBaseService（BikeDeviceInfoService 实证）——getInterfaces() 对 jar-loaded 泛型超类型返回空数组，改用 superTypes+resolve() 回退 |
| [project-reverse-join-fix](bug-fixes/project-reverse-join-fix.md) | SQL→Java 反向联表（JOIN）转换修复——反引号列/换行转义/lambda 联表容忍逻辑删除/selectAs 引用 VO/副表列归属/未映射误报消除，1218 全绿 |
| [project-reverse-placeholder-bugfix](bug-fixes/project-reverse-placeholder-bugfix.md) | SQL→Java 反向 `?` 占位符识别缺陷（round-trip 断点）——已交付（2026-08-16），实施细节并入 project-reverse-sql-java-improvements |
| [project-reverse-sql-java-improvements](bug-fixes/project-reverse-sql-java-improvements.md) | SQL→Java 反向链路质量大修已提交（c1b8f56 + b77361f，23+10 文件）——? 占位符/实体推断/调用形态 MP·MPJ 1.5.6 实证/继承链 MPJ 能力/性能修复/缓存管理/交互优化，1111 全绿 |
| [project-sandbox-jar-file-lock](bug-fixes/project-sandbox-jar-file-lock.md) | runIde 沙盒插件 jar 被 Windows 内存映射锁定 → prepareSandbox 静默不刷新 → |
| [project-screenshot-placeholders](bug-fixes/project-screenshot-placeholders.md) | 功能截图占位方案（d32d9a8）——docs/images/screenshot-01~04 |
| [project-settings-layout-optimization](bug-fixes/project-settings-layout-optimization.md) | 设置页布局优化（Phase 1-3 已完成）——折叠面板 + SQL 日志 Tab 拆分 + 搜索过滤 |
| [project-settings-no-nested-configurable](bug-fixes/project-settings-no-nested-configurable.md) | 设置页不再注册 projectConfigurable——移除嵌套的"MyBatis-Plus SQL |
| [project-settings-tree-nav](bug-fixes/project-settings-tree-nav.md) | 设置页左侧树导航 |
| [project-sql-capture-no-project-required](bug-fixes/project-sql-capture-no-project-required.md) | T-13 SQL 监听手动启动无需等待项目运行——Option A armed 态 + manual/auto 区分 + |
| [project-sql-log-filterbar](bug-fixes/project-sql-log-filterbar.md) | SQL 日志面板筛选条 UI 开发——布局修复/按钮样式演进/设置显隐控制/MessageBus 实时通知 |
| [project-sqltogjava-classpicker-edt-psi-read](bug-fixes/project-sqltogjava-classpicker-edt-psi-read.md) | SQL→Java 类选择器（ClassPickerField）EDT 无 ReadAction 直读 PSI 硬抛——已修复（2026-09-03，提交 7264828，dev 分支） |
| [project-test-speedup](bug-fixes/project-test-speedup.md) | 测试提速实测数据与方案（命令对比/并行沙盒坑/IPGP 分片 API）——2026-08-15 研究结论 |
| [project-update-channel-beta-refactor](bug-fixes/project-update-channel-beta-refactor.md) | 插件内部更新机制改为 Gitee 抢先体验通道（2026-08-22）——双 JSON 文件共存 + 忽略此版本 + 本地测试方案 |
| [toolwindow-button-tooltip-mechanism](bug-fixes/toolwindow-button-tooltip-mechanism.md) | 右侧面板按钮 tooltip 机制与 F-41 修复：init() 中 setStripeTitle 确保首次 hover 显示可读名称（非内部 ID） |
| [feedback-bundle-param-placeholder](pitfalls/feedback-bundle-param-placeholder.md) | IntelliJ bundle 消息参数占位符是 {0} {1}，不是 __URL__ 等自定义占位符 |
| [feedback-feature-f45-branch-stale-code](pitfalls/feedback-feature-f45-branch-stale-code.md) | feature/f45 分支有预先存在的编译错误（引用已删除的类），已通过创建存根修复 |
| [feedback-join-diagram-visual-validation](pitfalls/feedback-join-diagram-visual-validation.md) | JOIN 图标签重叠修复必须以真实 IDE 截图验证，单元测试通过不代表视觉布局正确 |
| [feedback-m36-native-console-misalignment](pitfalls/feedback-m36-native-console-misalignment.md) | Database 原生控制台内嵌成败两面——M36.2 八轮 0 尺寸失败（组件甄别铁律），M36.5 系列成功条件（UiDataProvider 三键/共享控制台复用/等 smart 提交/执行内容同步与原生两步执行链/write-safe 事务） |
| [feedback-settings-editor-internal-dep](pitfalls/feedback-settings-editor-internal-dep.md) | SettingsEditor 被 @ApiStatus.Internal 标记——须用 Class.forName |
| [feedback-settings-navigation-new-window](pitfalls/feedback-settings-navigation-new-window.md) | 设置页导航新开窗口 + 定位不准的完整根因与修复——Settings.KEY.getData(手工 DataContext) 取不到 settings.editor，须用 DataManager.getDataContext(component) 或反射；SectionConfigurable 须缓存面板 |
| [feedback-settings-titledborder-pitfall](pitfalls/feedback-settings-titledborder-pitfall.md) | 设置页折叠面板边框样式的已知问题与最终方案（无边框、无折叠、仅 TitledSeparator 横线） |
| [pitfall-criteriaquery-in-clause](pitfalls/pitfall-criteriaquery-in-clause.md) | CriteriaQuery IN 子句必须支持链式调用 .value() 并提供占位符——否则显示空 IN () 无法输入值 |
| [pitfall-cross-platform-filename](pitfalls/pitfall-cross-platform-filename.md) | 跨平台文件名生成陷阱——SimpleDateFormat 需指定 Locale.ENGLISH，Windows 文件名不允许冒号 |
| [pitfall-custom-dao-gutter-dedup](pitfalls/pitfall-custom-dao-gutter-dedup.md) | HQL gutter 图标去重——两个 provider 互相排除 + 导航 handler 直接传 PsiMethodCallExpression |
| [pitfall-dataclass-signature-clean-rebuild](pitfalls/pitfall-dataclass-signature-clean-rebuild.md) | Kotlin data class 增减字段后旧测试类触发 NoSuchMethodError——必须 clean 重建 |
| [pitfall-dbn-output-console-layout-collapse](pitfalls/pitfall-dbn-output-console-layout-collapse.md) | DBN Output 控制台 44px 塌缩——平台 EditorMergedHorizontalScrollBarLayout"输入编辑器优先"策略是根因；一次性 setSize 必败、布局替换才是正解；同类名组件多处存在导致搜索错目标（M36.5.60→76 十六轮踩坑全程） |
| [pitfall-dialog-editor-dispose](pitfalls/pitfall-dialog-editor-dispose.md) | DialogWrapper 中使用 IntelliJ Editor 必须 dispose 释放，否则测试报 "Editor hasn't been released" 且生产环境内存泄漏 |
| [pitfall-e2e-testing-gap](pitfalls/pitfall-e2e-testing-gap.md) | 单元测试无法捕获 IDE 集成 bug——必须编写端到端测试（PreviewEndToEndTest）模拟完整预览链路 |
| [pitfall-f42-join-diagram-render](pitfalls/pitfall-f42-join-diagram-render.md) | JOIN 图渲染"始终渲染中/错误图"终极修复（F-42）——PlantUML 8059 硬依赖 Graphviz + 模态弹窗阻塞 invokeLater + hidden 标签页失效，三层根因叠加 |
| [pitfall-filter-wrong-console](pitfalls/pitfall-filter-wrong-console.md) | 一条 SQL 显示两条——attachFilter 同时挂到 ConsoleViewImpl 和 HttpSingleResponseConsole，processTerminated 误触发致 filter 重复挂载 |
| [pitfall-gitee-hotlink-302](pitfalls/pitfall-gitee-hotlink-302.md) | Gitee raw 图片外链被 JetBrains Marketplace 302 防盗链拦截——plugin.xml 描述图全部不可见，推荐 jsDelivr CDN 替代 |
| [pitfall-gutter-provider-receiver-not-checked](pitfalls/pitfall-gutter-provider-receiver-not-checked.md) | gutter LineMarkerProvider 只按方法名判中、不校验接收者类型 → 通用方法名跨 provider 双收/漏收，产生重复或缺失预览图标 |
| [pitfall-hibernate-parser-strict-idx](pitfalls/pitfall-hibernate-parser-strict-idx.md) | HibernateLogParser.extractAfterKeyword 严格模式下 idx 偏移量翻倍 bug（2026-08-21） |
| [pitfall-hql-converter-structure](pitfalls/pitfall-hql-converter-structure.md) | HqlConverter 必须保留原始 HQL 结构——不能用碎片化正则替换，否则 SELECT 列/JOIN/WHERE 全部丢失 |
| [pitfall-hql-jpa-preview-debugging](pitfalls/pitfall-hql-jpa-preview-debugging.md) | HQL/JPA SQL 预览调试踩坑记录——单元测试无法覆盖的集成问题 |
| [pitfall-hql-locate-precision](pitfalls/pitfall-hql-locate-precision.md) | HqlQueryLocator.locate() 必须返回包含 anchor 的 createQuery |
| [pitfall-hql-log-multiline-parse](pitfalls/pitfall-hql-log-multiline-parse.md) | HQL 日志 multiline 解析全链路改造——状态机设计 + 双框架并发干扰 + Hibernate 前缀误收 bug |
| [pitfall-hql-param-extraction](pitfalls/pitfall-hql-param-extraction.md) | HQL 参数名提取——链式调用无变量赋值时，须直接从 HQL 字符串提取参数名 |
| [pitfall-hql-query-locator-compilation](pitfalls/pitfall-hql-query-locator-compilation.md) | HqlQueryLocator.kt 预存编译错误——PsiTreeUtil.collectElementsOfType 返回 Collection 非 List，传 List 参数须 .toList()（2026-08-21 修复） |
| [pitfall-in-clause-comma-split](pitfalls/pitfall-in-clause-comma-split.md) | IN 子句参数回填三层修复——括号补全+inList标记+引号类型判定（JPQL/XML/MP/MPJ 全路径） |
| [pitfall-jpa-converter-delete-and-params](pitfalls/pitfall-jpa-converter-delete-and-params.md) | JPQL 转换器的三个陷阱——①DELETE JPQL 被当 SELECT 处理 ②native SQL 命名参数不转换 ③预览缺 |
| [pitfall-jpa-gutter-anchor](pitfalls/pitfall-jpa-gutter-anchor.md) | JPA gutter 图标的两个锚点陷阱——①`element as? PsiMethod` 导致永远不显示 ②每个叶子都创建 marker 导致重复图标 |
| [pitfall-json-format-quotes](pitfalls/pitfall-json-format-quotes.md) | JSON 格式化时参数值必须统一加引号，不能做数值/布尔类型推断，否则数值引号丢失 |
| [pitfall-log-startup-capture-regression](pitfalls/pitfall-log-startup-capture-regression.md) | 启动自动监听回归——最终根因 Class.forName 使用插件 classloader 而非平台 classloader；修复=显式传入 platformClassLoader |
| [pitfall-model-field](pitfalls/pitfall-model-field.md) | 踩坑：测试引用错误字段名（智能体未编译验证的典型症状） |
| [pitfall-mpj-factory-duplicate-gutter-icon](pitfalls/pitfall-mpj-factory-duplicate-gutter-icon.md) | JoinWrappers.lambda() 工厂变量赋值形态双 gutter 图标——根因是场景 D 批量 selectAll 未去重（initializerHeadIsWrapper 回溯停在 qualifier 引用），已修复（2026-08-21） |
| [pitfall-plugin-update-jar-path-windows](pitfalls/pitfall-plugin-update-jar-path-windows.md) | 插件自更新在 Windows |
| [pitfall-psource-chained-append-ordering](pitfalls/pitfall-psource-chained-append-ordering.md) | SqlStringConcatenator 链式 append 多层共享同一 PSI startOffset，排序须用 textLength 作次键 |
| [pitfall-reverse-subquery-join](pitfalls/pitfall-reverse-subquery-join.md) | SQL→Java 反向解析器子查询 JOIN / FROM 子查询 / ext 条件 lambda / MConsumer 子查询形态全链路修复（2026-08-21） |
| [pitfall-selectcollection-preview-sql-bug](pitfalls/pitfall-selectcollection-preview-sql-bug.md) | selectCollection/selectAssociation 类参必须解析为 EntityRefArg（PSI 层），否则子查询表名推断错误/重复 JOIN/ON 别名不一致（2026-08-21 已修复，commit bfdd238） |
| [pitfall-settings-persist-inplace](pitfalls/pitfall-settings-persist-inplace.md) | PluginSettingsPanel.apply() 原地修改 state 属性导致 IntelliJ XmlSerializer 不触发 XML 持久化 |
| [pitfall-sidebar-merge-confusion](pitfalls/pitfall-sidebar-merge-confusion.md) | F-44 实施时搞反了侧边栏去除对象——应保留主侧边栏（log），去除独立历史侧边栏（history） |
| [pitfall-source-marker-path-resolution](pitfalls/pitfall-source-marker-path-resolution.md) | SqlSourceInterceptor 标记路径相对于源码根目录，resolveEntryJump 需在所有 sourceRoots 中搜索而非仅用 basePath |
| [pitfall-sql-formatter-clause-line](pitfalls/pitfall-sql-formatter-clause-line.md) | SqlFormatter.formatClauseLine() 实现中的 5 个迭代陷阱——AND/OR 双空格、子查询 depth |
| [pitfall-sqlformatter-atlinestart-space](pitfalls/pitfall-sqlformatter-atlinestart-space.md) | SQL 格式化器空格丢失双 bug——atLineStart 缺失 + formatClauseLine pendingComma/pendingSpace 未设置 |
| [pitfall-sqlformatter-namedparam-colon-space](pitfalls/pitfall-sqlformatter-namedparam-colon-space.md) | SqlFormatter 会把命名参数冒号当运算符空格化为“: name”，按名回填正则须容忍冒号后可选空白 [:]\s* |
| [pitfall-sqllogpanel-edt-freeze-html-render](pitfalls/pitfall-sqllogpanel-edt-freeze-html-render.md) | SqlLogPanel.refreshList 在 EDT 同步 addAll 大量日志触发 JLabel HTML 解析 + 全量 |
| [pitfall-startcapture-guard-bare-return](pitfalls/pitfall-startcapture-guard-bare-return.md) | 自动捕获守卫不能裸 return——跳过捕捉但不挂载检测机制，主控制台出现时永远检测不到（图标不刷新闭环断裂） |
| [pitfall-stopcapture-filter-leak](pitfalls/pitfall-stopcapture-filter-leak.md) | SQL 监听 stop 后仍持续捕获的完整根因与三层防御修复（2026-08-22） |
| [pitfall-test-coverage-gaps-jpa](pitfalls/pitfall-test-coverage-gaps-jpa.md) | JPA 功能测试覆盖率不足的三个典型 gap——①只验证"有没有"不验证"多不多/对不对" ②无负面/互斥测试 ③子智能体自写自测盲区 |
| [pitfall-tm51-api-cleanup](pitfalls/pitfall-tm51-api-cleanup.md) | T-M51 废弃API清理——Marketplace Internal API 阻断的解决思路（Kotlin→Java 改写 / |
| [pitfall-tm52-deprecated-api-replacements](pitfalls/pitfall-tm52-deprecated-api-replacements.md) | T-M52 废弃API替代方案全量实施——三版本字节码实证与跨版本陷阱 |
| [sandbox-deployment-pitfalls](pitfalls/sandbox-deployment-pitfalls.md) | 沙盒 IDE 构建部署陷阱（2026-08-25）—— prepareSandbox 文件锁失败、build/libs 陈旧 jar、手动复制注意事项 |
| [javap-platform-ui-debugging](methodology/javap-platform-ui-debugging.md) | 反编译平台 jar 定位 IntelliJ 平台私有布局/Swing UI 异常的完整流水线——一次 javap 顶十轮组件树探针；含沙盒 jar 定位、进程识别、部署与 stamp 校验 |
| [user-grok-api](user-context/user-grok-api.md) | 用户的 grok API 中转服务（api.openachieve.asia）——20 个模型清单、9 |
| [project-dev-merged-to-master-20260903](bug-fixes/project-dev-merged-to-master-20260903.md) | dev 分支合入 master（0413d6a，2026-09-03）——T-CallerPicker + |
| [project-dev-stale-test-failures-20260903](bug-fixes/project-dev-stale-test-failures-20260903.md) | dev 分支 3 |
| [project-help-browser-doc](bug-fixes/project-help-browser-doc.md) | 帮助按钮 → 本地 HTML 主路径零网络依赖（M36.5.80 定稿）；JCEF 不可用降级系统浏览器打开在线文档 |
| [project-hql-select-alias-expand](bug-fixes/project-hql-select-alias-expand.md) | M36.7 HQL SELECT 实体别名展开为列清单——修复 MySQL [1054] Unknown column 'u'（v2026.9.2）；2026-09-04 发现无别名裸 from 不展开缺口，待实施 |
| [project-log-filter-simplify](bug-fixes/project-log-filter-simplify.md) | SQL 日志筛选项精简（2026-08-27）——SQL类型多选按钮+弹窗、去重 ⨉N 徽章、Ctrl+F |
| [project-m36-native-finish](bug-fixes/project-m36-native-finish.md) | M36.6 原生控制台收尾（2026-08-30）——布局漂移根因闭环（分隔保险条件永不触发→首触无条件 60%）+ ▶灰探针假阴性第 |
| [project-native-sql-highlighting](bug-fixes/project-native-sql-highlighting.md) | 预览面板原生 SQL 高亮 + 补全（M34）——完整根因链：no PSI file（in-memory 不追踪）/ EDT SlowOperations（SqlLanguageSubstitutor）/ language=SQL 注册（全语言不被调度） |
| [project-preview-shortcut-bridge](bug-fixes/project-preview-shortcut-bridge.md) | T-PreviewShortcut 非 Wrapper 预览快捷键贯通（convertToSql 兜底链加 |
| [project-settings-layout](bug-fixes/project-settings-layout.md) | 设置页布局优化 |
| [project-dev-merge-all-branches](bug-fixes/project-dev-merge-all-branches.md) | dev 全量分支合并完成（2026-09-05，f4a5045）——除 kotlin-to-java 外六分支全入 dev，19 冲突块就地解决，3509/0/0/6 绿 + verifyPlugin 三目标过；冲突取舍明细在仓库 docs/开发计划/20260905-2306 计划验收记录节 |
| [project-m37-competitive-fusion](bug-fixes/project-m37-competitive-fusion.md) | M37 竞品精华融合 + M38 Flex 吸收全链（2026-09-04/05）——master 48 提交反向合入 + T-AutoShowSuppress 交付（手动关窗抑制自动展开，双门禁语义）；EXPLAIN 达梦/金仓真机矩阵、81 场景金样、6 缺陷修复；已知未修=字符串版 QueryWrapper 消费形态条件丢失（矩阵尾注 M1）；runIde 视觉验证未做 |
| [project-m39-orm-implementation](bug-fixes/project-m39-orm-implementation.md) | M39 ORM 缺口补全三波实施完成（feature/m39-orm-coverage，2026-09-05 合入 dev）——JpaCrudOperationConverter 修复 save 撞 MP 转换器、Specification/QBE/@EntityGraph/@Lock/@Procedure 等 19 任务，3321 测试绿；含多智能体分波模式与 Kotlin/JUnit3 编译踩坑全集 |
| [project-m44-orm-batch2](bug-fixes/project-m44-orm-batch2.md) | M44 其他ORM第一批实施完成（feature/m44-orm-batch2，2026-09-05 合入 dev）——SD 止血/xbatis/EasyQuery/MDS/Querydsl 5 转换器交付，3353 测试绿；含 xbatis 真实入口 QueryChain 纠偏与 7 个 Kotlin/JUnit3 编译级踩坑 |
| [project-orm-gap-analysis](bug-fixes/project-orm-gap-analysis.md) | 四框架功能缺口调研定稿（2026-09-04 两轮）——JPA 写路径整体为零、save 撞 MP 批量转换器根因、PartTree$Type 27 项权威清单；已由 M39 实施吸收，矩阵见 docs/架构设计/JPA-Hibernate官方API覆盖矩阵.md |
| [project-other-orm-research](bug-fixes/project-other-orm-research.md) | 候选 ORM 调研定稿（2026-09-05 四路 jar 反编译）——xbatis/EasyQuery/Querydsl/SD 立项已由 M44 第一批交付，jOOQ 缓做 MVP、Jimmer 暂缓（>15% 门槛）；矩阵见 docs/架构设计/其他ORM官方API覆盖矩阵.md |
| [project-r2-competition-response](bug-fixes/project-r2-competition-response.md) | 第二轮竞品调研响应 9 任务交付（2026-09-06，feature/r2-competition-response，worktree mpsv-r2）——O-8 字符串版 Wrapper DELETE 三缺陷/O-6 服务链搜索/N-9 测试断言 B 档/N-8 金样防御/O-7 弹窗克制红线/N-10 Ultra 付费墙调研/市场文档组合/O-5 CI 周验证+Windows 计划任务，3543 绿 + verifyFull 三平台过；**未合 dev 未 push**；含 docs/变更记录.md 分支必冲突脚本合并经验与 parallel_tasks done 必带 --name 新坑 |
| [project-string-sql-method-anchor](bug-fixes/project-string-sql-method-anchor.md) | T-DaoSql 自研 DAO 字符串 SQL 预览与方法定义锚点（2026-09-06 交付，master 8087c4a）——DAO_STRING_SQL_METHODS 白名单/实参扫描取 SQL/JdbcTemplate gutter 家族守卫排除制/StringSqlMethodGutterLineMarkerProvider；已知边界=运行时构造 SQL 静态不可还原；runIde 真机截图待补 |
| [project-m54-perf-redline-audit](bug-fixes/project-m54-perf-redline-audit.md) | M54 性能红线全面治理 24 任务交付（perf/redline-hardening 28 提交，2026-09-05，**已合 dev**）——禁卡死/禁拖慢/禁 ERROR 弹窗：LOG.error 与 ERROR 通知清零、3084 用例 0 失败；实施偏差 4 项备忘（T-Perf-23 正则 13/17、T-Perf-13 探针门控、T-Perf-18 URL 键、T-Perf-20 通知替代 HintManager）；真实防抖待 runIde 真机 |
| [project-log-jump-no-wrapper-rootcause](bug-fixes/project-log-jump-no-wrapper-rootcause.md) | SQL 日志右键跳转「未找到表对应 Wrapper」根因（2026-09-02 分析，**已随 T-ServiceChainSearch/O-6 修复**）——事后搜索 Wrapper 本质不可靠（仅静态工厂不含 IService 链），正解=F-44-7 SqlSourceInterceptor 执行时注入源码位置，含方案 A 实施全程 12 个编译/运行陷阱 |

## 已解决踩坑（81 个）

| 文件 | 内容摘要 |
|------|----------|
| [pitfall-docker-guard-hangs-indefinitely.md](pitfalls/pitfall-docker-guard-hangs-indefinitely.md) | docker 引擎半活态（管道在、守护进程不应答）让 `assumeDockerAvailable` 无限挂死而非跳过——HTTP 读无超时、`catch (Throwable)` 永不触发，整片门禁挂住且零错误读数；判别＝`timeout 10 docker info` 看退出码（管道接 head 会误读 0）；根治＝`mpvs.docker.tests.enabled` 显式开关（`e166ee1`，缺省关，`-PmpvDockerTests=true` 才跑容器验证），日常判别口径沉淀 AGENTS.md 与测试约定；dbx 替代 Oracle 用例未实施；含 testcontainers 策略写回、buildSrc 临时排除口径等实测细节 |
| [pitfall-xmltext-cdata-literal.md](pitfalls/pitfall-xmltext-cdata-literal.md) | 平台 PSI 的 `XmlText.text` 对 CDATA 返回带 `<![CDATA[ ]]>` 包裹的字面全文（「CDATA 已展开」说法被判别性读数证伪）——按展开口径写的 hbm 命名查询提取链带缺口且非判别性断言恒绿；修法＝unwrap 正则剥包裹+断言改「不含包裹标记」；hbm 缺口已修（dev `1406754` 共享 `XmlDirectText` 口径），新写 XML 文本提取代码前先读本档 |
| [pitfall-agent-config-channel-never-applied.md](pitfalls/pitfall-agent-config-channel-never-applied.md) | 增强捕获 CONFIG 帧格式错位——IDE 出站 `[4字节长度][1字节命令][body]`、应用侧 SenderLoop 错位读取→停采/脱敏/帧过滤三条指令从未生效；IDE 侧入队不判 capturing。O-54 交付（`a2b973a`）+ T-08 连接级跳过包装 P0（`e4e13f4`）+ D1 定案 MCP 不受捕获开关约束（`a295408`）；含 CONFIG ACK=5 回执+看门狗 15s+入队闸门 |
| [pitfall-agent-heartbeat-cadence-vs-online-window.md](pitfalls/pitfall-agent-heartbeat-cadence-vs-online-window.md) | 增强捕获 30 秒必掉线——agent 心跳记账耗时（实测 60.6s/发 vs 声明 10s）超 IDE 在线窗口 30s 必误杀，且 premain 只 accept 一次无法重连；六项修法交付 master+沙盒三轮验收九项全过；两条疑似缺陷实验证伪（复读历史行不成立/同 SQL 无新条目属去重预期，`×N` 徽标即丢弃数） |
| [pitfall-agent-locator-cache-stale-line.md](pitfalls/pitfall-agent-locator-cache-stale-line.md) | StackLocator L1 整帧缓存冻结行号——键取「首个非框架帧」而 MPJ 拦截器帧全应用共享→所有条目同一位置；O-55 交付（两缓存合并判定缓存+身份在场校验，反向验证读数与报障同形）；D3 定案=两侧跳过规则保持两份+差异登记+跨侧同步守卫 |
| [pitfall-rollback-identity-wrap-never-fires.md](pitfalls/pitfall-rollback-identity-wrap-never-fires.md) | 回滚链 identity 包装永不触发——快照登记点不传 dialect/identityColumns 而消费侧只从 snapshotEntry 取→SQL Server 回填仍报 544；单测直构 SnapshotEntry 全绿而产品不可用；O-59 交付（`ad13760`/`dece4b4`）+未知自增列显式告警（`eab58cb`）；「单测直构绕过生产接线」形态教训在册 |
| [pitfall-console-error-signature-coverage.md](pitfalls/pitfall-console-error-signature-coverage.md) | 预览面板错误条只认控制台文本 14 条短语词表——服务端 SQL 错误（如 `You have an error in your SQL syntax`）整类漏检→无红字无复制按钮、16s 后错向归因「密码/驱动」；T-ConsoleErrorCoverage 交付（`23d2ede`）=认结构性 `[SQLSTATE][errorCode]` 前缀+兜底呈现残余 |
| [ml-llm-acp-signature-divergence.md](pitfalls/ml-llm-acp-signature-divergence.md) | ml-llm 两份制品 AcpAgentId 包路径分歧（262.10968.97=`ml.llm.agents.acp.api` vs 262.9437.276=`com.intellij.platform.acp`，后者 jar 不含前者类）——O-41 跨版本签名护栏已按纯字节码解析交付（`52dec86`），迁桩 `102b44c` 定为平台包；分歧不再阻塞断言 |
| [native-diagram-relation-provider-null-ref.md](pitfalls/native-diagram-relation-provider-null-ref.md) | 原生 JOIN 图两参 `getForeignKeys(Project, DasTable)` 恒传 refTable=null=「不过滤返回全部 FK」（243/253/262 字节码同构）——误读成「无关系」致 B 路径连线全丢；N6 修复已合 master（`28a1efa`）；排障要点=日志打在守卫之后 hits=1 假健康、Heavy 用例三级软返回、扫 jar 用 Python zipfile |
| [pitfall-keymap-conflicts-never-resolve.md](pitfalls/pitfall-keymap-conflicts-never-resolve.md) | 快捷模板三动作默认键与备选键全撞平台既有键位（Alt+Shift+C/T、Ctrl+Alt+Shift+C/T 实测表）→「自动改绑」必然失败；T-TemplateKeyDrop 定案摘除默认键位+冲突检测下线（`4fabf2e`，三键位从未随发行物交付零影响）；附 classpath 上 plugin.xml 被构建重写成自闭合标签、裸正则取块吞下一个动作键位的坑 |
| [pitfall-unload-modality-write-unsafe.md](pitfalls/pitfall-unload-modality-write-unsafe.md) | 自更新用 `invokeAndWait(..., any())` 从后台派发平台卸载→模态串带 `[Unloading of SQL Visualizer]` 的 Write-unsafe context（write-safe 资格是进入模态那一刻的快照）；T-DynUpdateWriteSafe 收敛交付（`cd54905`）；含断言调用点清单与「读模态串判分支」判别法 |
| [pitfall-toolwindow-titleactions-hover-only.md](pitfalls/pitfall-toolwindow-titleactions-hover-only.md) | ToolWindow.setTitleActions 挂的动作新 UI 悬停/激活才渲染——主操作入口挂标题栏在用户视角「消失」（T-CaptureBack 实录：捕捉按钮「不见了」，截图实证存在但不渲染）；回迁面板内工具栏单一入口 + 删 setTitleActions；顺带教训=迁移动作引入硬编码中文漏接 bundle 键。平台通用部分已升全局同名文件 + AGENTS.md 摘要条 |
| [pitfall-dbn-objectkind-not-java-enum.md](pitfalls/pitfall-dbn-objectkind-not-java-enum.md) | DBN `ObjectKind` 是 type-safe enum 手写类（非 java.lang.Enum），反射 enumConstants 恒 null → DatabaseSchemaResolver 每次调用静默早退、schema 链全程空转且日志谎报 "not found in N data sources"；修法=getField("COLUMN").get(null)（T-DbSchemaCompletion 已交付，另 `To many roots from database` 是平台噪音非根因） |
| [pitfall-dbn-perfile-datasource-mapping.md](pitfalls/pitfall-dbn-perfile-datasource-mapping.md) | `.idea/data_source_mapping.xml` 把 exec 文件钉到数据源、控制台库上下文随之而定，与插件按数据源名选的 runner 错位 → 42S22/1054 Unknown column（跨库同名表陷阱）；已修 T-ConsoleDbContext（7a36f53：预览默认以数据源 URL 库为准、手调记忆保留、错误行附当前库） |
| [pitfall-console-doc-is-file-doc.md](pitfalls/pitfall-console-doc-is-file-doc.md) | attachConsole 挂在 exec 文件本体，写该文件≡改用户眼前控制台文本——File Cache Conflict 五轮与预览裸单行两类 bug 的共同底层；预热写原文+挂载后须回填渲染（回填点须在 embedConsole 之后）；写入成本直落眼前→差量快路径+程序性写入闸门（T-PreviewFast P1/P4） |
| [pitfall-ui-component-swap-drops-state.md](pitfalls/pitfall-ui-component-swap-drops-state.md) | 自绘组件换平台组件易丢显式状态（预览参数区变黑=T-UiNative P0a 换 OnePixelSplitter 丢 UIUtil.getPanelBackground，深色主题才显形）；定位=git show 替换提交 grep `Opaque|background|border`；修法两层（恢复祖先背景+末端自持命名色）+结构守卫用例 |
| [pitfall-speedsearch-cards-hidden.md](pitfalls/pitfall-speedsearch-cards-hidden.md) | ListSpeedSearch 构造于 CardLayout 隐藏卡仅对可见列表生效——已修复（cce2aa3 安装移出共享父类 init、切卡可见后按需装+守卫标志）；单参 getSupply headless 恒 null、断言用两参 getSupply(comp,true) |
| [pitfall-speedsearch-highlight-renderer.md](pitfalls/pitfall-speedsearch-highlight-renderer.md) | speed search 匹配高亮是渲染器职责（SpeedSearchUtil.applySpeedSearchHighlighting；setDynamicSearchMatchHighlighting 不存在被编译证伪）——已落码（6159828）归档 |
| [pitfall-dual-end-protocol-roundtrip-gap.md](pitfalls/pitfall-dual-end-protocol-roundtrip-gap.md) | 双端协议往返缺口：手写序列化↔对端解析两端各自绿、合流即断（Jsons.writeEvent 首成员逗号）——已修+JsonsEventFormatTest 格式守卫；教训=手写序列化器必配格式守卫与往返用例、静默丢弃点必打日志 |
| [pitfall-jdbc-execute-rows-semantics.md](pitfalls/pitfall-jdbc-execute-rows-semantics.md) | JDBC execute() 返 true=首结果 ResultSet（getUpdateCount -1 是哨兵）、返 false 才是更新计数——条件写反致 SELECT 显示「(-1 rows)」已修；负值转 null（0 合法须显示）、四态测试 |
| [pitfall-mermaid-node-id-collision.md](pitfalls/pitfall-mermaid-node-id-collision.md) | Mermaid 节点 ID 作用域是单张图，边引用未定义 ID 静默造孤立节点不报错——改图前先 grep 当前图节点边集，勿凭通读记忆连边 |
| [pitfall-test-fixture-shape-mismatch.md](pitfalls/pitfall-test-fixture-shape-mismatch.md) | 测试夹具形态不一致假绿三例（结构化字段 vs 文本解析/替身可访问性/单端协议断言）——教训已收编项目记忆 project-test-conventions fixture 节 |
| [pitfall-settings-tree-order-locale.md](pitfalls/pitfall-settings-tree-order-locale.md) | 设置树同级排序缺 groupWeight 时中英文子页顺序随语言漂移（中文码点序实证）；修复=plugin.xml 子页显式递减 groupWeight（d186d5c）+结构守卫测试；诊断路线=本机 IDEA lib jar 反编译+243 sources jar |
| [idea-plugin-url-marketplace-only.md](pitfalls/idea-plugin-url-marketplace-only.md) | idea-plugin 根元素 @url 属性在 IDE 插件列表零消费（262 全量扫描实证），展示位仅在 Marketplace 插件页 Homepage（api/plugins/33633 已收录）；想在 IDE 露出官网走 listing 的 Documentation URL；含常量池方法引用扫描法（类名方法名分开 UTF8 不能拼串 grep） |
| [pitfall-gradle-user-properties-override](pitfalls/pitfall-gradle-user-properties-override.md) | 用户级 `~/.gradle/gradle.properties` 按优先级（GRADLE_USER_HOME > 项目根）**静默覆盖**项目 gradle.properties 的 `org.gradle.caching`/`jvmargs`——项目「关闭 build cache」空转一整天（`5 from cache` 才暴露），修复=落 settings.gradle.kts `buildCache { local { isEnabled = false } }`（T-TestPerf2 实证） |
| [pitfall-windows-clipboard-cross-jvm-contention](pitfalls/pitfall-windows-clipboard-cross-jvm-contention.md) | Windows 剪贴板全机唯一（`WClipboard.openClipboard` 互斥），并发测试 JVM 互抢报 `cannot open system clipboard`——「剪贴板 flake」的并行放大版；对策=碰 `CopyPasteManager`/`systemClipboard` 的测试类配置期扫描固定同片（零测试代码改动） |
| [pitfall-shard-class-discovery-filename-vs-class](pitfalls/pitfall-shard-class-discovery-filename-vs-class.md) | 分片类发现不能按「文件名=FQCN」——Kotlin 文件名与类名可不同（`CrudPreviewFlowAndTypesTest.kt` 里是 `DbTypeQuoteHintTest`，3 用例静默漏跑致分片并集 4080≠串行 4083）；改内容提取列 0 顶层 class 声明 + 计数 parity 判绿 |
| [feedback-runide-hotswap-separation.md](pitfalls/feedback-runide-hotswap-separation.md) | hotswap 服务只挂 runIdeHotSwap、普通 runIde 绝不拉副作用（用户定案 d0afda0）——辅助服务按任务职责挂载，不为「点错配置」兜底 |
| [pitfall-k2j-stale-classes-hijack.md](pitfalls/pitfall-k2j-stale-classes-hijack.md) | K2J 期 build/classes/java/main 陈旧类劫持编译/运行六犯实录——标准前置动作（加 internal 新函数与合并后首跑先清 build/classes+build/kotlin）已收编 pitfall-regression-false-failure-flakes |
| [pitfall-swing-html-list-renderer.md](pitfalls/pitfall-swing-html-list-renderer.md) | JList+JLabel-HTML 每次 O(N) BasicHTML 解析冻 EDT——根治 SimpleColoredComponent 片段 + 固定行高 |
| [pitfall-vfs-refresh-write-intent-lock.md](pitfalls/pitfall-vfs-refresh-write-intent-lock.md) | 262 EDT ReadAction 包同步 VFS refresh 硬抛——须 WriteIntentReadAction（6f6a693） |
| [pitfall-test-sandbox-browser-leak.md](pitfalls/pitfall-test-sandbox-browser-leak.md) | 测试沙盒 HelpAction 降级分支 BrowserUtil 弹宿主真机浏览器——isUnitTestMode 守卫（f955d85） |
| [pitfall-criteriaquery-in-clause.md](pitfalls/pitfall-criteriaquery-in-clause.md) | CriteriaQuery IN 子句参数替换 |
| [pitfall-custom-dao-gutter-dedup.md](pitfalls/pitfall-custom-dao-gutter-dedup.md) | 自定义 DAO 方法 gutter 去重 |
| [pitfall-dataclass-signature-clean-rebuild.md](pitfalls/pitfall-dataclass-signature-clean-rebuild.md) | Data class clean rebuild 签名问题 |
| [pitfall-dialog-editor-dispose.md](pitfalls/pitfall-dialog-editor-dispose.md) | 对话框编辑器释放问题 |
| [pitfall-e2e-testing-gap.md](pitfalls/pitfall-e2e-testing-gap.md) | 端到端测试覆盖缺口 |
| [pitfall-filter-wrong-console.md](pitfalls/pitfall-filter-wrong-console.md) | 一条 SQL 显示两条 + 过滤条误触发 |
| [pitfall-gitee-hotlink-302.md](pitfalls/pitfall-gitee-hotlink-302.md) | Gitee 图片外链 302 重定向 |
| [pitfall-hibernate-parser-strict-idx.md](pitfalls/pitfall-hibernate-parser-strict-idx.md) | Hibernate 解析器严格索引越界 |
| [pitfall-hql-converter-structure.md](pitfalls/pitfall-hql-converter-structure.md) | HQL 转换器结构缺陷 |
| [pitfall-hql-jpa-preview-debugging.md](pitfalls/pitfall-hql-jpa-preview-debugging.md) | HQL/JPA 预览调试踩坑（10+ Bug） |
| [pitfall-hql-locate-precision.md](pitfalls/pitfall-hql-locate-precision.md) | HQL 定位精度问题 |
| [pitfall-hql-log-multiline-parse.md](pitfalls/pitfall-hql-log-multiline-parse.md) | HQL 日志多行 SQL 解析 |
| [pitfall-hql-param-extraction.md](pitfalls/pitfall-hql-param-extraction.md) | HQL 参数提取缺陷 |
| [pitfall-hql-query-locator-compilation.md](pitfalls/pitfall-hql-query-locator-compilation.md) | HQL QueryLocator 编译问题 |
| [pitfall-in-clause-comma-split.md](pitfalls/pitfall-in-clause-comma-split.md) | IN 子句参数回填三层修复（JPQL/XML/MP/MPJ） |
| [pitfall-jpa-converter-delete-and-params.md](pitfalls/pitfall-jpa-converter-delete-and-params.md) | JPA 转换器删除与参数处理 |
| [pitfall-jpa-gutter-anchor.md](pitfalls/pitfall-jpa-gutter-anchor.md) | JPA LineMarker 锚点必须是叶子元素 |
| [pitfall-json-format-quotes.md](pitfalls/pitfall-json-format-quotes.md) | JSON 格式化引号处理 |
| [pitfall-log-startup-capture-regression.md](pitfalls/pitfall-log-startup-capture-regression.md) | 日志启动捕获回归 |
| [pitfall-model-field.md](pitfalls/pitfall-model-field.md) | 测试引用错误字段名 |
| [pitfall-mpj-factory-duplicate-gutter-icon.md](pitfalls/pitfall-mpj-factory-duplicate-gutter-icon.md) | JoinWrappers.lambda() 工厂双图标 |
| [pitfall-plugin-update-jar-path-windows.md](pitfalls/pitfall-plugin-update-jar-path-windows.md) | 插件自更新 Windows 静默失败三断点 |
| [pitfall-reverse-subquery-join.md](pitfalls/pitfall-reverse-subquery-join.md) | 子查询 JOIN 反向解析修复 |
| [pitfall-selectcollection-preview-sql-bug.md](pitfalls/pitfall-selectcollection-preview-sql-bug.md) | selectCollection 预览 SQL 三缺陷 |
| [pitfall-settings-persist-inplace.md](pitfalls/pitfall-settings-persist-inplace.md) | SettingsPanel.apply() 原地修改不持久化 |
| [pitfall-sql-formatter-clause-line.md](pitfalls/pitfall-sql-formatter-clause-line.md) | SQL 格式化关键字换行 |
| [pitfall-startcapture-guard-bare-return.md](pitfalls/pitfall-startcapture-guard-bare-return.md) | SQL 监听全链路陷阱（11 轮迭代） |
| [pitfall-stopcapture-filter-leak.md](pitfalls/pitfall-stopcapture-filter-leak.md) | SQL 监听 stop 后 Filter 未移除 |
| [pitfall-test-coverage-gaps-jpa.md](pitfalls/pitfall-test-coverage-gaps-jpa.md) | JPA 测试覆盖缺口 |
| [pitfall-tm51-api-cleanup.md](pitfalls/pitfall-tm51-api-cleanup.md) | Marketplace Internal API 清理 |
| [pitfall-tm52-deprecated-api-replacements.md](pitfalls/pitfall-tm52-deprecated-api-replacements.md) | 废弃 API 替代方案全量实施 |
| [pitfall-cross-platform-filename.md](pitfalls/pitfall-cross-platform-filename.md) | 跨平台文件名生成陷阱（SimpleDateFormat Locale.ENGLISH + Windows 禁冒号） |
| [pitfall-sidebar-merge-confusion.md](pitfalls/pitfall-sidebar-merge-confusion.md) | F-44 侧边栏去除对象搞反（保留主侧边栏 log，去除独立 history） |
| [sandbox-deployment-pitfalls.md](pitfalls/sandbox-deployment-pitfalls.md) | 沙盒 IDE 构建部署陷阱（prepareSandbox 文件锁 + 陈旧 jar） |
| [pitfall-f42-join-diagram-render.md](pitfalls/pitfall-f42-join-diagram-render.md) | JOIN 图"渲染中/错误图"终极修复（F-42）——PlantUML 8059 硬依赖 Graphviz + 模态弹窗阻塞 invokeLater + hidden 标签页，三层根因（82797a0） |
| [pitfall-dbn-output-console-layout-collapse.md](pitfalls/pitfall-dbn-output-console-layout-collapse.md) | DBN Output 控制台 44px 塌缩（M36.5.60→76）——平台 EditorMergedHorizontalScrollBarLayout"输入编辑器优先"策略；setSize 对抗布局必败、布局替换正解、同名类组件搜索错目标（7294d38/bc9963b） |

## 方法论（1 个）

| 文件 | 内容摘要 |
|------|----------|
| [javap-platform-ui-debugging.md](methodology/javap-platform-ui-debugging.md) | 反编译平台 jar 定位私有布局/Swing UI 异常——一次 javap 顶十轮探针；含沙盒 jar 定位、沙盒进程精确识别（勿杀主 IDE）、构建产物 stamp 校验 |

## 用户环境（1 个）

| 文件 | 内容摘要 |
|------|----------|
| [user-grok-api.md](user-context/user-grok-api.md) | Grok 中转 API 配置 |

---

## 全局通用记忆（94 个）

> 已复制到 `~/.zcode/cli/memories/global/`，跨项目复用。

**方法论（7 个）**：agent-parallel-test-isolation / agent-task-split / agent-verification / agent-commit-rhythm / wave-parallel-development / methodology-research / methodology-test-first

**用户偏好（9 个）**：user-ide-environment / user-local-proxy / user-token-efficiency / feedback-doc-style-no-ai-flavor / feedback-compilable-generated-code / feedback-batch-param-ux / feedback-icon-button-style / feedback-update-json-manual-only / feedback-grep-first-reading

**平台陷阱（47 个）**：pitfall-actiontoolbar-refresh-api / pitfall-classloader-forname-execution-api / pitfall-contentmanagerlistener-unavailable / pitfall-edt-psi-read-in-callbacks / pitfall-execution-api-missing / pitfall-getJarPathForClass-243-missing / pitfall-gradle-windows / pitfall-headless-validate / pitfall-heavy-psi-interface-resolution / pitfall-ipgp-stripped-ui-classes / pitfall-jblist-copy-itemToText / pitfall-kotlin-incremental / pitfall-kotlin-synthetic-bridge-internal-api / pitfall-lightplatform / pitfall-notification-setlistener-no-2param / pitfall-notificationgroup-displayname / pitfall-panel-disposer-leak / pitfall-parallel-inflight-block / pitfall-plugin-xml-listener-unsupported / pitfall-psi-create-edt-readaccess / pitfall-runide-sandbox-warnings / pitfall-sandbox-corruption / pitfall-script-edit-kotlin-escapes / pitfall-swing-mouse-no-bubble / pitfall-toolwindow-manager-listener / pitfall-uicompatible-dataprovider / pitfall-verifyplugin-proxy / feedback-anaction-icon-update-presentation / feedback-baseplatformtestcase-junit3-runner / feedback-junit3-kotlin-lambda-testname / feedback-popup-chooserbuilder-single-click / feedback-swing-wrap-layout-clipping / pitfall-bash-backtick-inline-corruption / pitfall-editor-colors-scheme-dark-unavailable / pitfall-gradle-cache-locked-by-idea / pitfall-headless-ui-test-gotchas / pitfall-java-code-gen-no-kotlin-ext / pitfall-jcef-css-limitations / pitfall-kotlin-nested-class-prohibited-in-inner / pitfall-native-run-query-chain / pitfall-newbufferedwriter-truncate / pitfall-plugin-xml-configurable-displayname / pitfall-runcatching-debug-log-swallow / pitfall-settings-tree-ampersand-escape / pitfall-slowoperations-edt-readaction / pitfall-verifyplugin-kotlin-mode-optional-dep

**第六次精简升级（2026-09-12，8 个）**：hprof-idea-snapshot-analysis / parallel-session-index-commit-split / pitfall-merge-whole-side-take / pitfall-toolcall-newline-escaping / zcode-tps-footer-windows-install / feedback-doc-lists-keep-main-only / reference-api-proxies / reference-fernflower-decompile

**第七次精简升级（2026-09-13，1 个）**：pitfall-subagent-claims-verify（子代理断言先证伪）

**第八次精简升级（2026-09-14，共 9 个）**：pitfall-wira-not-in-readaction（ReadAction 内开 WIRA 硬抛、WIRA-in-WIRA 合法）/ pitfall-workbuddy-ai-sandbox-eats-nested-refs（WorkBuddy AI 沙盒吞嵌套 ref 事故与恢复法）/ verify-capability-before-claiming（能力验证对照实验，文档站）/ win32-shell-cjk-mojibake、win32-tsx-file-url-import（本机命令行与 TS 验证环境坑，管理后台）/ glm-relay-api-test、zcode-model-config-location、aliyun-token-plan-provider、dots-nova-relay-lines（中转线路与模型配置，管理后台）

**第九次精简升级（2026-09-15，1 个）**：pitfall-edt-slowops-coldpath（SlowOperations 断言仅 EDT 生效、allowSlowOperations 逃生舱 243+262 双重证伪禁再试、线程归位两段式）

**第十一次精简升级（2026-09-17，9 个）**：pitfall-github-push-ghfast-rewrite（push 被 ghfast.top 重写挡：https://&lt;login&gt;@github.com 形态+git -c http.proxy；GCM 标签≠实际 login）/ pitfall-msys-leading-slash-arg-mangling（Git Bash 首斜杠正则参数被 MSYS 改写，改 awk/-e）/ pitfall-proxy-upstream-dead-nonproxyhosts（gradle 代理上游死，-Dhttp.nonProxyHosts="*" 直连绕过）/ pitfall-subagent-parallel-rate-limit（4 路并发子代理触发 1302 限流，上限 ≤3；互读在途产物假告警）/ pitfall-kotlin-nested-block-comment（注释写字面 /* 开嵌套吞成员）/ pitfall-reflect-jdk-internal-class-access（反射 package-private 实现类按声明类可见性检查抛异常，改接口 cast）/ pitfall-http-error-body-swallowed（HttpRequests readString 丢 4xx 正文+myGzip 自动协商 gzip 手写读取不解压）/ pitfall-no-image-input-ocr-fallback（无图模型三视觉通道全断→PS CopyFromScreen 截屏+tesseract OCR 坐标路线）/ pitfall-document-bgthread-edt-only（262 Document.isModified 移除+文件 document setText BGT 硬抛 EDT-only，BGT 改文档唯一路线=派 EDT）

**第十二次精简升级（2026-09-18，8 个）**：idea-262-platform-jar-verify（262 平台类查 intellij.platform.ide.jar 常量池，执行/VM 参数类在 util-8 与 plugins 全盘扫描）/ user-env-jdk8（本机 JDK8=D:/HuanJing/Java/jdk1.8.0_181 即 PATH 默认；H2 2.x 是 Java 11 字节码、JDK8 目标换 1.4.200）/ reference-zcode-subagent-storage（ZCode 子智能体四作用域路径+状态文件+frontmatter+A→B 迁移要点）/ pitfall-embedded-native-loses-auto-behavior（手工构造编辑器嵌面板丢平台默认行为，JOIN 图 fitContent 实例；布局竞态指纹=异步任务收尾消费的设置须在触发前置位）/ pitfall-filecontents-charset（Gradle fileContents().asText 按 JVM 默认字符集 GBK 解码，须 asBytes+UTF-8 显式）/ pitfall-log-first-not-code-reasoning（同一 bug 修三轮仍复发=都在按代码推演；真机日志同秒多行直接锁因果链）/ pitfall-parallel-merge-wipes-worktree（并行登记表只管 gradle 沙盒，合并会清工作区未提交改动，改完立即提交）/ pitfall-stale-session-git-snapshot（会话启动 git 快照只抓一次，判交付状态先重跑 git 实测）

**第十四次精简升级（2026-09-20，2 个新文件 + 1 处内容合并）**：pitfall-python-requests-proxy-tls（python requests 走本地代理 127.0.0.1:7890 时 TLS 握手被掐 SSLEOFError，curl -x 同代理正常——抓取类任务直接用 curl -x；GitHub 匿名额度被共享出口 IP 耗尽时如实登记）/ pitfall-toolwindow-titleactions-hover-only（setTitleActions 新 UI 悬停才渲染，主操作入口放面板内工具栏；完整 T-CaptureBack 实例归档 pitfalls/，精简版升全局 + AGENTS.md 摘要条）/ pitfall-stale-session-git-snapshot 项目侧 20260919 副本的内容（盘点类结论几十分钟即过时 + Write 撞 "file modified since read" 先重读合并再写）合并进全局同名文件后删除副本。另：全局 feedback-junit3-kotlin-lambda-testname 已完整覆盖项目侧 pitfall-junit3-synthetic-lambda-method（project-test-conventions 亦有全记录），项目副本删除不归档

**第十五次精简升级（2026-09-23，3 个新文件 + 1 处内容合并 + 1 处重复删除）**：pitfall-statustext-no-html-no-wrap（JBList 空态是 StatusText 非 JLabel：不解析 HTML、`\n` 无声吞掉、超宽只裁不折，多行只能 appendLine 且它不更新 getText()）+ pitfall-workflow-amend-cannot-pass-args（AmendWorkflow 无 args 参数，必填参工作流修订即秒退 `ok:false`；循环内字面量 actor 名第二轮必 DuplicateActorName）+ reference-dev-flow-workflow（全局脚本 dev-flow.dwf.ts 七阶段骨架与迭代史，含改脚本编译校验法与模板字面量反引号截断 TS1005 坑）升全局；pitfall-git-commit-message-backticks-gitbash 的 commit -m 变体（信息静默缺段，一律 -F 文件）并入全局 pitfall-bash-backtick-inline-corruption 后删副本；pitfall-bash-python-write-not-persisted 与全局 pitfall-sandbox-python-write-not-persisted（20260923 并行会话新版）重复，删项目副本

**第十六次精简升级（2026-09-24，5 个归档 + 1 个原地瘦身）**：console-capture-zero-output-flush（O-70 T-ConsolePendingFlush 已交付 `efe2c73`）+ enhanced-capture-gates-and-consent-scope（O-66 引导链随交付；O-73 路线 A 已实施 `b2f9f17`，归档注记录其推翻文中「均未实施」）+ reference-acp-session-verification（⑯ 全四项闭合 `01c6a99`）归 bug-fixes 3 个；pitfall-xmltext-cdata-literal（hbm CDATA 缺口已修 dev `1406754`）+ pitfall-docker-guard-hangs-indefinitely（处置口径已沉淀 AGENTS.md 与测试约定）归 pitfalls 2 个——后两者系并行会话新建的漏索引孤儿，本次一并补账；mcp-tools-live-test-and-open-bugs 原地瘦身（2 bug 已修删细节留实测口径，filePath 段更新为 T-McpFilePathUnify `6a9afa3` 统一后口径）。另：project-pending-plans 瘦身（30KB→约 10KB，深夜批次收口债销案、合流债刷新=dev 领先 52 笔+master 独有 9 笔 docs 未推）

**第十七次精简升级（2026-09-25，1 个归档 + 1 个升全局并入后删 + MEMORY.md 重建纯索引）**：project-o77-ew-statement-scope（O-77 T-EwStatementScope 已交付 `9464464` 并推送）归 bug-fixes，系并行会话新建的漏索引孤儿本次补账；pitfall-python-c-backtick-command-substitution 增量点（stderr 混流+退出码 0 照常「成功」最隐蔽/回读校验防锚点被吃短）并入全局 pitfall-bash-backtick-inline-corruption 后删项目副本，AGENTS.md 反引号条同步补句；MEMORY.md 索引行再瘦身 16.4K→约 8K（承重细节先逐项 grep 验明已落各底层文件再瘦）；project-release-workflow 补 20260925 Marketplace「已落位审核队列未放行」新结论（原文停在第十轮 hasUnapprovedUpdate=false 旧口径）并订正 update.json 待办已消除（实测已指 2026.9.23）

**第二十一次精简升级（2026-09-29，2 个归档）**：project-keyword-wrap-format（关键字换行+T-ListMode 显示模式，交付档）与 project-dynamic-unload-barriers（动态卸载根因分析，T-DynUpdate 已交付闭环；活跃精要=反射入口签名护栏+卸载窗口 write-unsafe 语义留项目 memory 薄档）归 bug-fixes。同轮压缩（不产生归档）：project-test-conventions T4~T9 四代叙事收拢为「现行定例+在役机制+历代一行账+教训清单」、project-pending-plans 批次 1~7 多代叠写收拢为一段账+合流债第九次刷新、project-kotlin-to-java-port-sync 历轮读数压一行账（187 条目订正「未 push」→已 push 零债）、project-overview 四代历史状态清栈+since-build 253→243 实测订正（现值 `intellijIdeaUltimate("2024.3.7.1")`）、xml-engine 过期调试基础设施节清理。项目 memory 46→45 内容文件（+MEMORY.md）。

**第三十一次精简升级（2026-10-29，47 个归档 + 孤儿补账入册 + 索引重建）**：归档判定全部先 `git merge-base --is-ancestor` 实测（每个文件至少一枚 sha 命中 master HEAD）并过敏感内容扫描（无真实凭据/竞品私密材料）。归 **bug-fixes 43 个**——原「十一、已完成（待归档）」整节 10 个（settings-search-index-gap / preview-chain-noise / releaseperf8-verifier-home-shared / perfsweep26-batch / history-rewrite-20261019 / devfullui-acceptance-campaign / dev-vs-master-acceptance-manual / merge-master-to-dev-round3 / round4 / sync-k2j-496-inprogress）+ 跨节已交付档 26 个（一/二/八/九/十节：conn-cast-safety、dialect-doc-table-guard、watermark-cleanup、freeze-reports-assessment、memory-index-slim-20261027、github-mirror-sync、docs-gif-rerecord-1025、enhanced-capture-default-on、jrebel-premain-crash、agent-pause-visibility、ai-context-v2-unreadable、fieldfill-panel-ux、preset-method-scope-key、dml-impact-prominence、display-mask-degraded-preview、sql-list-highlight-layout、sql-log-tail-follow、sql-log-tabs-single-row、explain-fail-ux、dialect-status-live-refresh、dml-impact-editor-replacement、reverse-logic-delete-blind、embedded-identify-investigation、entity-name-scope、hql-buffer-concat、update-setsql-concat、xml-highlight-completion-gap）+ 历史轮次档 7 个（competitive-round27/28/30/31、panorama-round20、magpie-merge-master-20261025）。归 **pitfalls 4 个**（master-log-search-wiring-lost、partial-submission-loading-stuck、pause-resume-socket-channel-races、markdown-table-image-width-ignored——末者系漏索引孤儿补账）。同轮：MEMORY.md 索引 247→198 行（conn-cast 重复合并行删 1、超 200 字符行压短、节次重排「十一已完成」并入归档节）；漏索引孤儿 3 个补入册留役——`reference-docs-deploy-cloudflare-token`（部署在役）、`pitfall-doc-sample-path-is-test-contract`（docs 结构整理待拍板要用）、`pitfall-sql-dialect-mapping-lazy-load-on-edt`（T-SqlDialectPrewarm 未闭环机制档）。项目 memory 203→156 个 md（含 MEMORY.md）。

---

## 统计

| 位置 | 文件数 |
|------|--------|
| 项目 memory（保留） | 156（含 MEMORY.md） |
| 全局记忆 global/ | 116 |
| 归档 archive/ | 307（bug-fixes 219 + pitfalls 85 + methodology 1 + user-context 1 + README） |
| **合计** | **579** |
