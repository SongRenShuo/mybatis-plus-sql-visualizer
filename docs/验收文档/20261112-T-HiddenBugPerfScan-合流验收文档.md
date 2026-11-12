# T-HiddenBugPerfScan 合流验收文档

- 日期：2026-11-12
- 任务编号：T-HiddenBugPerfScan（全仓隐藏缺陷与性能问题扫描修复）
- 验收对象：`t-hiddenbug-perf-scan` 分支 @ `aeeaaa96e`（基线 `t-domestic-db-dialects@19a7146af`，领先 47 提交、落后 0）
- 计划文档：[20261111-0550-T-HiddenBugPerfScan-隐藏缺陷与性能问题修复-开发计划.md](../开发计划/20261111-0550-T-HiddenBugPerfScan-隐藏缺陷与性能问题修复-开发计划.md)
- 来源：用户指令「全方位扫描分析当前插件的隐藏 bug 或者性能问题……直到所有问题全部修复」+「未收手的全做掉」
- 本文件只写**实测读数与判断**；未覆盖的面在「未验项与证据边界」逐条声明。

---

## 一、结论

**判断：可以合并到 master（基线为 t-domestic-db-dialects，其自身尚未回 master——本分支的合流动作应随其宿主分支的合流节奏走）。** 45 条发现全部处置完毕：36 笔修复提交 + 1 笔整体回退（券 Y7 拒判）+ 1 笔守卫收口；独立审计（子代理第二双眼）账实核对 45/45 全覆盖、揪出的 4 项收口问题已全部落地；testFast 417 类 4850 例全绿、全量分片 5 片 474 类 4194 例全绿；反向探针 4 笔反证成立；双仓已推送（Gitee + GitHub @ `aeeaaa96e`）。

需注意的事项（不阻塞正确性）：

| 编号 | 事项 | 级别 | 说明 |
|---|---|---|---|
| K-1 | 券 O 方言盲反斜杠残余 | known-limitation 已登记 | `SqlTypeClassifier.maskQuoted` 的反斜杠成对消费为 MySQL 语义；标准 SQL 方言下 `\'` 为「字面反斜杠+闭合」时特定形态可反向 fail-open。已登记 KDoc + 计划册，方言感知掩码另立项 |
| K-2 | AcpSignatureCrossVersionTest 1 例红 | 环境红，非本分支引入 | 断言本机已装 IDE 2025.3 的 ml-llm 插件携带 ACP 模块——本机该插件目录无此类（MISS「本品未携带」）。该测试读取本机 IDE 安装目录，与本分支 47 笔提交零交集 |
| K-3 | 合流时 CHANGELOG [Unreleased] 必冲突 | 预判 | 本分支 38 条回填插在 Fixed 段顶；与宿主分支/合流目标侧的后续条目需并集处理（同 T-SecurityAuditFixes 先例） |
| K-4 | 性能券 M 的 psi 大文件基准未建 | 已结构性收口 | 券 M 以语义回归 + 死循环副本删除 + 结构性证据收口；基准类仅覆盖纯逻辑面（D/Q/T） |
| K-5 | gradle.properties 未随分支携带任何改动 | ✓ 干净 | 本机代理故障期间的工作树内临时改动均未入库（工作树当前残留的未提交 `M gradle.properties` 为代理恢复后的本地还原态，不随合并面走） |

---

## 二、扫描与方法

- **4 路只读审计代理**（受账户并发限额约束串行执行）分片覆盖 27 个包 + agent 子项目：UI/动作/历史/onboarding/share、日志/MCP/acp/feedback/update/agent、sql/execute/database/converter、psi/xml/psource/reverse/generator + config/context/model/util/横切。
- **产出 45 条候选**（P1 5 / P2 19 / P3 21），主控逐条读码复核 45/45 确认为真。
- **排除清单**：另一分支（t-security-audit-fixes）待合流的 25 条安全线索——三路代理均未重复上报。
- 各代理均附「确认健康面」清单（如 StatementWeaver 配对、SenderLoop、McpDbExecutor、各缓存并发机制等），防误疑。

## 三、处置总账（45 条 → 39 笔提交）

| 券 | 哈希 | 一句话 |
|---|---|---|
| A | 9241e0430 | and/or 块体 lambda 嵌套调用泄漏→重复条件（双过滤修复） |
| B | 8d4fbdb58 | HQL/JPQL 参数替换先掩码字面量（:word/?N 不再被换占位符） |
| C | 016601b60 | EP 主通道解析配置固化修复 + applyFilter 补 ctx 锁 |
| D | e206097b4 | 历史文件读取单遍化 + 块级读（19MB 热路径 15~16.6s 口径） |
| E | a7ae24f94 | SQL→Java 批量路径补兜底（不再静默无反应） |
| F | ad8f3c54e | 9 处 catch(Throwable) 补取消协议（PCE 不再吞成「未找到」） |
| G | b0e5fe69d | SqlHistoryManager 13 入口与写路径同锁 |
| H | 38a9683c6 | EXPLAIN 对话框 Disposer 挂载改「常驻一份 + 字段间接」 |
| I | d9e337be4 | 参数导入解析器两缺陷（嵌套 JSON 断链 + CSV 误分流） |
| K | 500730ffa | OGNL 大数字穿透收敛层 + 整型除法语义对齐 |
| L | e545272ea | selectFunc 参数位运算符优先级（别名位不再被吞） |
| M | 21802789c | 行注释提取零大拷贝反向回扫（两文件单源化） |
| N | e487dc735 | 字符串版多列 orderByAsc 不再整体丢 ORDER BY |
| O | 6237755ba | SqlTypeClassifier 掩码补反斜杠转义对（fail-closed 方向） |
| P | 718c6ead1 | renderLike LIKE 字面量补单引号转义（不再断串） |
| Q | e3e98fc8a | 顶格关键字扫描 O(n²) 归零 + 评分词边界补 `_` |
| R | 72362d4b0 | agent EventBuilder 反射缓存改 ClassValue（券 04 红线补漏网） |
| S | 00f25d6c3 | agent EventBus 满队扫描降本（快照 + 尾部快路径，F2b 语义零收窄） |
| T | 446a6b375 | dedupeExisting O(n²)→O(n)（万级裁剪不再冻结 EDT） |
| V | 1c9831fe3 | 设置 JSON 导入后发布 SETTINGS_CHANGE（管线/CONFIG 即时生效） |
| W | f639b7a95 | McpConfigurable.apply 走 loadState(copy)（**项目级页拒判**，见 §五） |
| X | bf9e9bbbb | PageRequest.of(运行时page, 字面量size) 不再丢 OFFSET 与参数行 |
| Y1 | a6a6db76f | HTML 报告导出写盘移出 EDT |
| Y2 | 09252a5a6 | 删除两处 dispose 自处置（Disposer 重入） |
| Y3 | 2f341f216 | PerfRuleEngine 异常补 WARN 留痕 |
| Y4 | e780838a8 | MPJ ON-lambda apply 统一片段通道 |
| Y5 | 268149e37 | extractConsoleError 锚定最后一条命中 |
| Y6 | 27c69b9a0 | setIncrBy/setDecrBy 方言读取传 project |
| Y7 | d284620ae → **5132ebf9c 回退（拒判）** | # 注释与 iBatis `#param#` 不可区分（实证回归） |
| Y8 | 59ead55f0 | ExpressionEvaluator Char 分支补单引号转义 |
| Y9 | ecc93df6f | SQL→Java 列对列 qualifiedName + 双侧转义 |
| Y10 | f2e00cbde | 批量链零列实体五处 first() 收敛 null 降级 |
| Y11 | a88b1020c | NamedParamParser 跳过字面量/注释（幻影参数） |
| Y12 | 30b238e30 | OFFSET n ROWS 单独形态报错给可行动文案（**范围收窄**：模型不可表达） |
| Y13 | 72e9ac861 | TrimSqlNode 无 prefix 不剥 prefixOverrides（对齐 MyBatis） |
| Y14 | b5c9464df | SettingsIO 病态数字收敛 ImportException + version<1 拒绝 |
| Y15/Y16 | d2277ba75 | 设置面板 rebuild 监听器幂等 + 非 UI 页 apply 不误报 rebuild |
| Y17 | c76729072 | Querydsl BooleanBuilder 引用搜索限 useScope |

分支总构成：38 修复 + 1 回退（5132ebf9c）+ 1 守卫收口（9e009b044）+ 1 审计收口（aeeaaa96e）+ 文档/回填/补测/探针登记 6 笔 = **47 提交**（`git log t-domestic-db-dialects..HEAD`）。

## 四、验证读数

### 4.1 定向测试（每券先红后绿 + 同族回归）

每券至少一个「摘掉必红」锚定（读数逐笔在提交 body）。汇总代表性读数：

| 券 | 锚定测试 | 修复前 → 修复后 |
|---|---|---|
| A | ArgResolverNestedBlockLambdaTest | 嵌套链 `[eq,or,eq]` 红 → `[eq,or]` 绿（2/0/0） |
| B | HqlConverterLiteralParamTest | `:word` 形态 2 红 → 4/0/0 |
| C | EpContextRefreshTest | 刷新断言红 → 3/0/0 |
| D | SqlLogFileReaderSinglePassTest | fileOpenCount=2 红 → 2/0/0 |
| E/F | BatchGuardTest + ActionPayloadGuardTest | 守卫缺失红 → 3/0/0 + 3/0/0 |
| I | ParamImportParserSkipResumeTest | 幻影/错位红 → 4/0/0 |
| K | OgnlNumericBoundaryTest | 抛穿透 + 3.5 红 → 4/0/0 |
| N | QueryWrapperParserStringOrderByTest | orderBy.size=0 红 → 3/0/0 |
| Y13 | TrimSqlNodeTest 追加例 | 剥前缀红 → 1/0/0 |
| Y14 | SettingsIOTest 追加例 | 裸 NFE 红 → 13/0/0 |
| R | EventBuilderTest（agent） | 结构红线缺失红 → 8/0/0 |

### 4.2 反向探针（reverse_probe.py，4 笔反证成立）

| 券 | 模式 | 摘除后读数 |
|---|---|---|
| Y13 | --commit 整提交反打（temp worktree 手工补跑口径） | TrimSqlNodeTest 16 例 1 红（`AND x=1` 被剥成 `x=1`） |
| I | --commit 整提交反打（同上） | SkipResumeTest 4 例 3 红 |
| A | --commit 整提交反打（同上） | NestedBlockLambdaTest 2 例 1 红（`[eq,or,eq]`） |
| E | --patch 手术式补丁（已入库 reverse-probes/ 并登记 README） | BatchGuardTest 3 例 1 红 |

> 环境注：探针 temp worktree 携带已提交的死代理配置（本机代理当时未开），自动跑分不可判（exit 3），按「--keep 保留现场 + 修代理 + 手工补跑测试」完成；代理恢复后（2026-11-12）已还原两处配置。

### 4.3 全量守卫与分片

| 面 | 读数 | 说明 |
|---|---|---|
| testFast | **417 类 4850 例 0 失败 0 错误 8 跳过**（BUILD SUCCESSFUL） | 首跑 6 红全部归因修复（9e009b044：fatal 判定/JUnit3 语义/BrowserUtil 计数/GUARD_CLASSES/豁免表/PruneGuard 偶发） |
| testShards 5 片 | **474 类 4194 例 0 失败 0 错误 6 跳过** | 逐片 exit=0；DemoMp census 经 junction 通过 |
| testExternal | AcpSignatureCrossVersionTest 1 例红 | 环境红（本机 IDE 插件清单），见 §一 K-2 |
| agent 子项目定向 | EventBuilderTest 8/0/0；EventBus 族 12/0/0 | 券 R/S |

### 4.4 性能基准读数（L18 口径：正确性判据 + 打点登记）

| 券 | 场景 | 修复前口径 | 修复后实测 |
|---|---|---|---|
| D | 19MB/8708 条历史文件 readFromFile | 15~16.6s（20261006 实测） | **701ms** |
| Q | 50KB SQL 顶格关键字规则 | 单规则秒级 | **三轮均值 0ms** |
| T | 12000 条缓冲 dedupeExisting | 10⁷~10⁸ 次字符串比较 | **14ms** |
| S | 满队聚合探针 | O(n) 锁步进 | 尾部快路径 ≤2 探针（TAIL_PROBES 缝） |
| M | 行注释提取 | O(字段数×文件大小) 分配 | 结构性证据（零大拷贝） |

### 4.5 独立审计轮（子代理第二双眼）

账实核对 **45/45 全覆盖 ✓**（38 笔哈希逐一真实、拒判/收窄均有提交级证据、CHANGELOG/计划册/message 三账主体一致、27 个新增测试类语义干净）。揪出 4 项并全部收口（aeeaaa96e）：

1. 券 M 的 JpaEntityResolver 残留首版死循环助手副本（补丁脚本路径子串匹配缺陷）→ 已删、单源恢复
2. CHANGELOG 券 Y7 条目未反映回退 → 已补拒判标注
3. 券 O 方言盲反斜杠残余 → known-limitation 登记（K-1）
4. 券 C 桥侧刷新不持 ctx 锁 → 已补 synchronized(ctx)

收口后定向：6 类 129 例 0 失败。

## 五、拒判与范围收窄（均有实证）

- **券 W（部分拒判）**：项目级设置页 loadState(copy) 被 ProjectPluginSettingsConfigurableTest 两例（TFS08/O58）打回——该页契约是「外部捕获的 state 引用跨多次 apply 存活且持续可变」；MCP 页照常落地。
- **券 Y7（整体回退）**：iBatis `#param#` 占位语法与 MySQL `#` 行注释在本仓不可区分（IbatisXmlPreviewServiceTest 实证回归）。
- **券 Y12（范围收窄）**：OFFSET n ROWS 单独形态 LimitClause.count 非空不可表达，落地为可行动报错；接受面扩大另立项。

## 六、未验项与证据边界

1. **双项目同开真机形态（券 Y6）**：本验证以「单项目覆盖项目级方言」钉透传链；双项目并行场景仍 not run（需双项目 fixture）。
2. **性能券真机大文件复测**：读数为本机 6C12T/32GB 单机口径；券 M 的 psi 大文件基准未建（结构性与语义回归收口）。
3. **设置面板面（Y15/Y16）**：全量分片已跑（474 类含面板 UI 用例）——L13 收口达成，但 2026.2/263 平台态未覆盖（构建基线 243 口径）。
4. **券 O 方言感知掩码**：known-limitation，另立项。
5. **真机 runIde 走查**：本验收全部为测试口径，未做真机 runIde 人工走查（历史惯例：合流后由用户真机验收）。

## 七、合并注意事项

1. **CHANGELOG [Unreleased] 必冲突**（本分支 38 条回填插在 Fixed 段顶）——按「保留双方」并集解。
2. **BannedApiScanRuleTest 豁免表**：ConvertToSqlAction（7→0）/GenerateCrudSql 两动作（1→0）条目已删、ConvertSqlToJavaAction（1→2）已更新——若合流目标侧同文件有并行改动，需重核站点数。
3. **不含** `update/update.json`、`plugin.xml` 版本号变更——合并面干净。
4. **demo 仓**：已按用户指示自 Gitee 快进至 `3bb4489`（钉定值 fcb1faf 可达性核对过）；`.worktrees/mybatis-plus-sql-visualizer-demo` junction 为本机工作树便利设施，不合流。
