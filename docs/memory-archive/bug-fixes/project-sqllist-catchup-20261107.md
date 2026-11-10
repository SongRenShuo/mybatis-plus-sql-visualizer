---
name: project-sqllist-catchup-20261107
description: T-SqlListCatchup SQL列表追赶战役收官——九券+H 全合 master(80512ec7/终态 7cf2b1d3)；七轮压测验收闭合(券H 排水税 23x→已收158,015/丢弃110/秒停)；合流+清理+经验沉淀(LESSONS L21~23)已落账；全量门禁交 CI；追加券 I(EventBus 溢出聚合账本，t-sqllist-catchup-i)代理遇 exceed quota limit 后已 SendMessage 续跑
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e57e9560-6824-4f8d-9b3d-2634fabc99fc
---

T-SqlListCatchup（20261107 立项当日收口）：起因是 join-demo 压测（7134 请求全 200，脚本 `mybatis-plus-sql-visualizer-demo/stress-test/stress_capture.py`，剔除 page/mapFamily 两个 demo 固有 500 接口）发现面板刷新滞后于请求速率、结束后仍追赶。

- 集成分支 `t-sqllist-catchup`（工作树 `.worktrees/sqllist-catchup`，tip `6859384d`）：券A notifyUi 150ms 时间窗（`7be82118`）+ 券B 计数增量化（`5a32e33d`）+ 券C 证伪结案 + 券D 追赶徽章/探针（`5bdefafc`）+ 小券B2 pending 悬挂冲洗（`47f762ef`）。定向 244 例全绿（集成 tip 十类 101 例 + B2 侧 143 例）。
- **券C 重大证伪（计划 §五.1 前半被推翻）**：控制台桥是 filter 逐行制（解析在控制台输出线程同步完成，`SqlLogConsoleBridge.kt:388-390` KDoc 自证），`scanAll` 从不读文本、无扫描位——「桥逐拍解析积压」机制不存在，残余追赶归因＝EDT 刷新队列（A/B 域）+ 平台 ConsoleViewImpl 文本 flush（插件不可达）。
- 计划文档：`docs/开发计划/20261107-1140-T-SqlListCatchup-...md`（master 落底 `b2ccd4f9`，§九含全部实施记录与 not run 条款）；CHANGELOG `[Unreleased]` 已回填（`7fe6fc09`）。
- **待办**：①合入 master 等用户点名（未合）；②压测前后对比读数——需把集成分支新插件包装进用户 IDE（日更 jar 覆盖流程要关 IDE 一次），用户已说「方便时再约」；③全量门禁 testShards 未跑（L13：平台 UI 测试只在全量跑到，用户指令本轮只做代码收口）；④真机 GUI 走查待用户。
- 坑复训：python -c 双引号串写含反引号的 markdown 进文档又被 bash 吃字（`--tests` 变空），Edit 工具补回——AGENTS.md 既有教训第三次应验。
- **清理完毕（20261107 用户令「保留 t-sqllist-catchup 分支，清子代理分支和工作树」）**：五条子券分支（a/b/c/d/b2，tip 均已 `merge-base --is-ancestor` 复验）+ 四棵子工作树全删；保留集成分支 `t-sqllist-catchup`（tip `6859384d`）与其工作树 `.worktrees/sqllist-catchup`（后续出新包做压测对比直接用）。顺带给主仓开了 `git config core.longpaths true`（Windows 长路径，留在配置里）。

**第二轮：新构建真机压测（20261107 13:40-13:43，用户 runIde 沙盒）与残余落队**
- 用户走 runIde 沙盒路线（不用关真 IDE）：从集成工作树启 runIde **自动部署当前源码构建**，沙盒 jar 是否新版用类内字符串探针验（python zipfile 扫 class 找 `MIN_FLUSH_INTERVAL_MS`/`CountSnapshotAccumulator`/`catchUpBehind`/`hasStalePendingNotify` 四标记全命中）；buildPlugin 报 prepareSandbox mmap 锁失败≠没部署，先探针再决定重装。
- 读数：~10 万请求全 200（RPS 876/988），数十万次语句执行过双通道捕获；沙盒 idea.log 插件 SEVERE=0；demo 全 GC 后存活集仅 ~30MB（**无泄漏**，2GB WS 是堆扩张 commit 不还 OS）。**HTTP 侧与上午基线不可比**（上午 demo 多挂 cool-request 的 SQL hook agent，18 倍 RPS 差是环境差异）。
- 券 D 探针首战读数：`LogPanel.refreshList` 慢拍仅 13 次、**单拍 102~232ms（visible=200）跑在 EDT**——风暴期 150ms 拍距 × 100-200ms 拍体 ≈ EDT 占用 50-70%，**饿停平台 ConsoleViewImpl 文档冲刷**＝用户看到「控制台没输出了」；结束后 EDT 让出积压才被逐行 filter 消化＝「列表追好久」。上午两症状的残余主嫌疑收敛到这条链。
- **用户实测反馈（20261107）**：仍有落队且「控制台停输出后列表加载好久才结束」→ 已派**券 E**（分支 `t-sqllist-catchup-e`，工作树 `.worktrees/sqllist-catchup-e`，并行登记 `sqllist-e`）：①refreshList 拆 4 段探针（snapshot/filters/counts/diffModel）拿账单；②按账单瘦身目标单拍 <30ms；③**淘汰路径计数增量化已从「若命中才做」提为主攻**——用户确认压测时面板 SQL 去重**没勾**，异键风暴实锤命中券 B 已知边界（buffer 恒满每拍有键扫出域→整表全量回退），与 102~232ms 读数互证；④桥侧补两探针（逐行耗时直方图 + settled 积压 gauge）。E 的沙盒隔离做法＝build.gradle.kts 临时加 `sandboxContainer.set(layout.buildDirectory.dir('sqllist-e-sandbox'))` 用完还原。
- 机制口径：落队有下限不可能归零——控制台文本必须先落控制台文档才能逐行解析，目标是「追好久」压到秒级 + 徽章可见；复测对比轮建议用户把去重勾上（默认形态），去重关闭+2500 语句/秒本质是 200 行整屏翻滚。

**反向验证提效（20261107 用户问「反证耗时怎么提效」，三板斧）**
1. 验收集分级（已落为纪律口径）：反证只跑「锚定该机制的最小用例集」禁带回归面——券 A 已实践（摘窗只跑 FlushWindowTest 7 例而非 51 例），后续派活写死。
2. 真机验收自动化（**已交付**）：`mybatis-plus-sql-visualizer-demo/stress-test/verify_capture.py`——从沙盒 idea.log 自动提取 refreshList 慢拍分布/追赶徽章事件/插件 SEVERE，用法 `python verify_capture.py [log] --since "YYYY-MM-DD HH:MM:SS"`，已用 13:40 风暴窗口校准（13 拍/p50 145ms/EDT 占用 1.9%/徽章 5 条/异常 0）；判读线＝慢拍总 EDT 占用 <3% 且 max<50ms 达标、徽章成对出现=可见化正常。
3. 反证自动化（**已交付**，用户问「加开关会不会冗余代码」后否决了系统属性方案——生产决策点常驻 if 分叉＝永久死代码+守卫连坐，正解是真摘实现的自动化）：`dev-support/reverse_probe.py`（`e7cb820d`）双模式——`--commit` 整提交反打仅适用「改既有行为+既有测试」提交；**新增能力型提交（机制与测试缝同批落地）整提交摘除必然 compileTestKotlin FAILED（脚本如实报「不可判」exit 3）**，改走 `--patch` 手术式补丁（禁机制判定保测试缝，补丁入库 `dev-support/reverse-probes/` + README 清单登记）。四态判定（通过/失败/冲突/不可判）。券 A 校验：补丁 `7be82118-disable-flush-window.patch` 反证 7 例 5 红 2 绿与人工分布逐位一致。脚本开发踩坑已固化：`./gradlew` 在 Win 下要 `gradlew.bat`、基线分支可能被检出须 `--detach`、GBK 控制台要 `sys.stdout.reconfigure`、git 路径要正斜杠。标准条款已写进 `docs/开发流程-派活与验收清单.md`（`78a3238a`）。

**券 E 交付（20261107 已合入集成 `74b4a02b`，分支 t-sqllist-catchup-e）**
- 提交四笔：`9d760fa0` 探针（拆拍+桥侧+recordGauge）/ `c1764eaa` 瘦身 / `4703ed64` 淘汰增量核销 / `13cb27d0` 计划文档回填。
- **两个主控假设被账单证伪**：①「每拍全量回退计数是大头」——counts 段实测仅 0.6~1.4ms；②钻取定位真凶＝**贴底滚动 `ensureIndexIsVisible` → BasicListUI 变高布局 O(N) 逐格全扫**（KEYWORD_WRAP 模式下模型变更后首 bounds 27~31ms、温态 0.05ms，风暴期每拍必付）——与真机 102~232ms 同型。
- 修法：`RailCellRendererBase`（日志页+历史页共用）条目级 preferred size 缓存（IdentityHashMap 按条目身份键控、代戳变化整缓存作废、上限 1024、bind 先解锚再重锚）——**单拍 22~33ms → 7~9ms，<30ms 达标**。
- 淘汰扫出键域增量核销（确定性核销：presence 减一、归零摘键、被淘汰键随变更键集出域；其余五类兜底点逐字不动）；等价性＝恒满异键风暴 2 种子×300 恒异键增量与全量逐键相等**且全程断言不置全量标记**（空绿防线）；S2 反向恢复置位恰 2 红。
- 定向 12 类 163 例 0 红 + 渲染器连坐面 5 类 115 例全绿；diffModel 余量 ~7ms 达标未动。
- 第三轮真机压测（15:36-15:40，去重勾选态）：99,731 请求全 200，**refreshList 慢拍 13→0**（风暴全程无拍超 INFO 阈值）、插件 SEVERE=0、demo GC 后存活集小（2.2GB WS 仍是堆扩张非泄漏）。徽章日志事件 0 是预期（券 D 徽章状态机无日志，可见性靠用户肉眼）。

**第三轮验收发现两缺陷 → 券 F 在跑（分支 t-sqllist-catchup-f，工作树 .worktrees/sqllist-catchup-f，基线 6c52564a）**
- F1 跨通道重复：面板出现条目 `#1 [15:36:09] —`（无耗时/无[MYSQL]角标/无⨉N/无来源＝控制台通道签名）与增强捕获条目在**去重勾选态**并存。候选机制＝两套还原器指纹分叉（AgentEventAdapter 头注释声称同指纹——证伪点）或控制台条目被平台冲刷延迟>5s 错过 crossChannelIndex 折叠窗。修法方向：控制台条目入队时追加「现存同指纹」兜底二次对账（agent 先到不覆盖，保耗时/角标全的字段；指纹不同才留新条目）。先写复现测试（同执行异指纹/超窗同指纹两形态）再修。
- F2 agent 队列风暴溢出（根因实锤）：`EventBus.java:30-52` `QUEUE_CAPACITY=2048` ArrayBlockingQueue + `offer()` 满即丢（DROPPED 计数已有）——风暴 ~2500 语句/秒下队列打满大量丢弃；真机证据＝状态栏「已收事件 2,227 条」在控制台停输出后仍缓慢上涨（≈队列容量+排空尾量）。丢弃非阻塞本身正确（红线：不阻塞宿主），修法＝F2a `droppedCount()` 随既有上报通道带到 IDE、状态栏诊断行暴露「已丢弃 N 条」（bundle 双语占位对齐）；F2b 队列满改**同语句模板聚合**（同模板只留最新+累加计数、腾容量给新模板；事件结构加计数字段协议向后兼容：老 IDE 忽略新字段/新 IDE 按旧事件 1 计）；`offer()` 恒非阻塞不碰宿主、不改「已收事件」口径。协议改动照 AgentProtocolSyncTest 源码扫描锁定约束走，agent 改动收口须补跑 `:sql-visualizer-agent:test`。
- 复测验收判据（用户第四轮）：去重勾选态无角标条目消失、状态栏出现「已丢弃 N 条」、已收事件水位不滞后。
- 并行车道注意：goldendb（t-goldendb-driverproxy）与 release（t-release-20261108-beta1）两会话 gradle 在跑，券 F 已要求 check+register+沙盒隔离避让。
- 券 F 代理曾被并发限额挡停两次，均 SendMessage 续跑同一 agentId 恢复（不丢上下文）。

**第四轮压测（15:43-15:47 已完成，去重关闭 + 券 E 构建）——淘汰增量化真机验证通过**
- 104,806 请求（warm 相位 1 次客户端 TimeoutError 偶发，风暴两相位 0 失败）；与第二轮同构对比：慢拍 **13→5**、耗时 **102~232ms→100~148ms**（p50 145→112）、慢拍 EDT 占用 **1.9%→0.9%**（远离饿停控制台区间）、徽章事件 1 次、插件 SEVERE=0。
- **已知边界（记档不再追）**：去重关闭+buffer 恒满异键风暴未达「max<50ms」线——每拍 200 行全异键的 diff/模型换血成本仍在（券 E 账单 diffModel 余量 headless ~7ms，真机渲染竞争下放大）；该形态视觉本就是 200 行整屏翻滚，再压一刀（diff 增量换血）留待 F 落地后用户再议。
- **用户第四轮复测肉眼判定（20261107，主控推断被证伪）**：控制台**没有**滚到结束（仍会停）、SQL 日志还在加载、追平**不是**秒级——「慢拍 EDT 占用 0.9% 已远离饿停区间」的归因错误，刷新瘦身解决的只是支线。
- **第四轮后的重新归因**：①控制台停摆主劳＝**平台 ConsoleViewImpl 文档管线在 55 万行量级自身跟不上**（追加+冲刷+逐行 filter 调用，插件不可直接修平台）；插件侧能做的是把自己的逐行成本归零——agent 通道健康时桥 filter 只做了「解析短路」（EP 接管态守卫判完即丢），**filter 调用本身仍每行在走**，升级为「agent 健康期注销桥 filter、熔断时经 CaptureActivityFallback 滞回挂回」；②列表追平尾巴＝agent 队列（2048）排空节奏——去重关闭时条目几乎全靠 agent 事件到达才上屏，队列尾排多久列表追多久（F2b 聚合缓解信号量，排空速率量化归券 G）。

**券 G 计划（未派，F 落地后派，三件）**
- G1 用户诊断行截图新发现：「发现未登记 JDBC 实现：com.zaxxer.hikari.pool.HikariProxyPreparedStatement/ProxyStatement/ProxyConnection」＝**连接池包装层被当驱动误报**（HikariCP 人人用，提示会常驻误导）——修法＝已知池包装族前缀表（hikari/c3p0/dbcp/tomcat-jdbc/druid）从「未登记」曝光排除、捕获路径继续走解包装登记内层（Oracle 同族先例 pitfall-oracle-statement-wrapper-registry-mismatch）。
- G2「重放守卫已丢弃 138,243 行 + 控制台产出 0 条」——EP 接管态（接口兜底匹配 5 个驱动已织入）下大概率设计内（rehighlight 重放被守卫按设计丢、桥让位 EP 单路产出），但 13.8 万量级必须复现实验核验「重放噪音 vs 流式误丢」，不许拍脑袋结案。
- G3 控制台停摆量化与让位升级（第四轮用户证伪后新增）：探针量「文档长度 vs 已处理行」落差 + 桥逐行 filter 耗时分布 → 确认大头后实现「agent 健康期注销桥 filter、熔断时滞回挂回」。**待用户答复的取舍前提：不开增强捕获（纯控制台通道）时控制台是否也停**——若纯控制台也停＝平台成本占大头，G3 只能救「增强开启」场景。
- 截图附带好消息：已收事件 9,256 条实时在涨（上轮卡 2,227）——agent 队列这轮在排空，F2 的溢出形态随负载波动，聚合修法有效性的判据要看 DROPPED 水位而非已收数。
- **主仓副本处置（20261107 用户令「集成分支有的，主仓先删掉等后面合并」）**：reverse_probe 工具曾误提交 master（`7cc3fb11`），已 `reset --mixed` 撤销并删除主仓工作树副本；工具只活在集成分支（`e7cb820d`+条款 `78a3238a`），随集成分支合 master 时自然带上。顺带观察：master tip 已被 release 车道推进到 `d88b6ea4`（2026.11.8-beta.1 版本提交），本战役合 master 点名时先看 release 进度。**未决**：demo 仓 `stress-test/`（压测+取证脚本）仍是未跟踪，已向用户提议提交留底、用户未答（demo 仓还有一处别人的 UserServiceImpl.java 改动勿碰）。

**券 F/G 交付与集成收口终态（20261107 晚）**
- **券 F 已合入（`fc15e8da`）**：F1 定因＝**超窗非指纹分叉**（crossKey 原材料两侧同源、六族分叉被 18 例证据排除；真缺口＝5s 窗+墙钟惰性清除把 agent 键提前摘掉）→ 修＝索引取消时间清除+越窗 60s 兜底对账（保 agent 字段、不计 ⨉N；`f7c20f7b`，S2 恰 3 红；「接管沿截断形态」明确不修）。F2a＝dropped 随心跳上报→状态栏「已丢弃 N 条」（`bb5652ed`）；F2b＝队列满同模板聚合（`SqlEvent.aggregatedCount`，JSON 键 c 仅>1 写出、协议双向兼容、offer 非阻塞红线未碰、`AgentProtocolSyncTest` 三约束未触；`2521991a`）。agent 全量 270 例 + IDE 478 例绿。
- **券 G 已合入（`43b1f7bd`）**：G1＝六前缀池代理白名单（CatchAllMatcher `POOL_WRAPPER_PREFIXES`，Hikari/c3p0/DBCP 1.x+2.x/Tomcat-JDBC/Druid；捕获解包装已有先例 StatementRegistry.java:155-180 无需补；S2 恰 2 红）。**G2＝挖出真丢数据缺陷并修**：控制台 clear 是同文档实例原地删除（ConsoleViewImpl.kt:698/:1438）→ 重放守卫水位陈旧 → **流式新行被持续误判 Replay 丢弃至追平旧水位**（真机 138,243 行丢弃正中此因；replayGuard.reset() 全仓无调用点）→ 修＝decide 先核文档总长度、缩小即核销水位按 Reset（`cc2d3606`，S2 恰 4 红）。**G3＝薄交付**：`handleConsoleLine` 在控制台输出线程**不在 EDT**（主控前提再被证伪），桥逐行实测 0.02~0.36% 单核——停摆＝平台 ConsoleViewImpl 文档管线 55 万行量级自身上限，插件不可修；三关边界登记（clearMessageFilters 连坐/计数冻结/水位冻结），「agent 健康期注销 filter」不做。
- **集成收口终态**：tip `207a03f5`（八券 A/B/C/D/B2/E/F/G）。IDE 16 类 **217 例 0 红** + agent 272 例全绿（1 既有 skip）。**先撞 17 红伪读数**：F2b 改 AgentEvent data class 签名后 Kotlin 增量编译残留旧字节码（NoSuchMethodError），删 build/classes 重编即消——F 报告同款教训两度应验，**改 data class 签名后跨工作树读数必须核对重编**。
- CHANGELOG [Unreleased] 已补齐 F1/F2/G1/G2 四条（`df92576b`/`207a03f5`）。**用户否决「默认显示捕获时间」提案**（logTimestampSource 两档设置本已存在，翻转默认需 touched 哨兵迁移——放弃不立项）。demo 仓 stress-test/ 已提交留底（`bac92b4`）、UserServiceImpl.java 按用户令回滚。
- **第五轮真机复测（20261107 21:00-21:04，去重勾选态，F+G 构建 runIde 沙盒）技术面全绿**：107,240 请求仅 2 次客户端侧抖动（1 超时 + 1 WinError 10055 系统套接字缓冲耗尽＝压测脚本自身千连接速率下的 OS 限制，非 HTTP 错误），RPS 977/954；慢拍 **0 条**、插件 SEVERE **0 条**、徽章事件 0（＝无追赶发生，F2b 聚合后队列无长尾的旁证）。
- **F2a/G2 真机铁证（用户截图）**：状态栏出现「已丢弃 **1,402** 条」（风暴期队列 2048 实溢出量，F2a 可见化生效）；清屏后「重放守卫已丢弃 **3 行**」（修复前同场景 **138,243**，G2 陈旧水位缺陷确认已死）；面板 ⨉N 巨额聚合（⨉529/⨉868/⨉713）+「同结构 N 条」徽章正常。EP 接管态新读数形态：「EP 通道已短路 1,055,955 行 + 重放守卫丢弃 3 行 + 产出 0 条」为设计内。
- **已知形态（定界不再追）**：「已收事件 11,136 还在涨」＝agent 队列 2048 容量物理排空尾（F2b 聚合已压信号量、F2a 已可见化），设计内取舍（宿主安全红线优先）；控制台停摆＝平台文档管线上限（G3 定界）。低速率清屏后 24 发 gentile 请求全 200 验证捕获恢复。
- **待用户肉眼验收五点**（跨通道双行消失/已丢弃 N 可见/不再长爬/Hikari 误报消失/清屏后恢复——前三与四五从截图已确认大半，等用户终判）；**待用户点名**：合 master（先看 release 车道 2026.11.8-beta.1 进度）；全量门禁 testShards 未跑。


**战役收官（20261107 晚，全部闭环）**
- **券 H（最后一券，已合入 master）**：「没跟上」最终根因＝SenderLoop 排水每轮 64 条后付一次 1s 入站读超时（agent→IDE 平时无入站流量）→ 投递钉死 ~64 条/s（真机 74 实测吻合）。修＝burst 达上限先 in.available()>0 探测、无数据零等待继续排（0d50931d，512 条 726ms→31ms ≈23 倍；心跳/看门狗/协议字节面三守卫）。第七轮真机：已收 158,015 条（上轮 11,136，14 倍）、已丢弃 110（上轮 1,402）、秒停（用户确认）——战役验收闭合。
- **合 master**：80512ec7（基线 v2026.11.8 正式版 659a44cc；CHANGELOG 双段保留）。**全量门禁按用户令交 CI**（本地 testShards 启动即停）。**清理**：五工作树+五分支全删（删前逐条 is-ancestor 复验）。**沉淀**：docs/经验总结/20261107-2230-T-SqlListCatchup-SQL列表追赶九券战役经验总结.md + LESSONS L21（data class 签名跨工作树伪红 Seen 2x）/L22（后台 tail 吞失败正文）/L23（prepareSandbox mmap 锁=沙盒占用）+ 计划册终态（master 7cf2b1d3）。
- **收尾坑实录**：python -c 双引号串反引号第四次应验且最重——bash 把 markdown 行当命令执行，仓根产出 3+1 个乱码名垃圾文件（含 U+FFFD/私用区字节名，python 需按 b'20261107' 字节匹配删除）；同命令内 LESSONS/经验总结经 Write/Edit 工具写入未受伤，「回读校验关键内容在场」再次救命。
- 丢弃数口径（用户问过）：EventBus 2048 有界队列满即丢不碰宿主（红线）；已收事件 < 语句总数是正常形态（F2b 同模板聚合 c>1），面板 ⨉N 才是真实执行次数；丢弃持续增长=生产超采集预算（正常降精度信号）。

**券 I 追加（20261107 用户令「尽量不丢、红线也遵守」，t-sqllist-catchup-i 工作树，基线 master 7cf2b1d3，sqllist-i 代理在跑）**
- 方案＝**溢出聚合账本**：`evictOldestAndOffer` 逐出最旧者不再直接 DROPPED++，改为「模板→次数」记入有界 CHM 账本（OVERFLOW_MAP_CAPACITY=512 键封顶，超限才真丢）；SenderLoop 排空队列后把账本转成聚合事件发出（复用 aggregatedCount/协议键 c，协议零改动；发出成功才条件 remove）。真丢失语义收窄＝「账本也满时的超限部分」。
- 红线核对（任务书写死）：入账只走 CHM compute/put（宿主线程 <1µs 无锁无 sleep，异常回落丢弃计数）；内存上界 <1MB；队列容量 2048 **不动**（加大会放大 F2b `tryAggregateOnFull` 在宿主线程的 O(n) 队列扫描）；`clearQueued()` 停采下降沿同步清账本入 CLEARED（停采不补投语义不回破）；同模板入账直接累加（不管队列里有没有它）。
- 验证面：>2048+512 种异模板灌爆复现（修复前 DROPPED≈全部、修复后仅账本满部分）+ 账本满反向 + 红线静态断言（offer 路径无 sleep/锁）+ 停采清空 + agent 全量 276 例回归。交付后走合 master→用户 runIde 复测看「已丢弃」趋近 0。
- **EventBus 满队三级处置实况（读码确认）**：`offer()` 非阻塞入队 → 满则 `tryAggregateOnFull`（线性扫队列找同 sql 折叠，宿主线程 O(n)）→ 失败则 `evictOldestAndOffer`（poll 队头 + DROPPED++）→ 异常兜底 DROPPED++。券 I 要改的正是第三级（逐旧即丢）与第二级并存后的残余损失。
- **代理曾因 `exceed quota limit` 中断一次**（用户口径「额度已恢复 继续刚刚的进度 如有子智能体直接恢复 不要新派」）→ 已 SendMessage 续跑同一 agentId，恢复令第一条写死「先 `git log`/`git status` 核对已提交与半成品、清 `build/classes` 残留再续」，防接手重做与 L21 伪红。
