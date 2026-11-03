# T-TestGapSpeed：踩坑盲区补测与全量门禁提效

- 立项：2026-11-04 00:53，主分支 master（HEAD `1cbbf2b6`）
- 工作树：`F:/code/Java/Gitee/SongRenShuo/mpsv-testgap-speed`，分支 `t-testgap-speed`（扁平名）
- 背景：用户令——插件开发中「单测绿但真机废」的问题反复出现（UI 交互等），要求①分析踩坑记录与记忆，全方面补充一次测试用例；②缩短全量测试运行时间提效。

## 一、侦察结论（先证伪后的余量）

对本仓 24 份最相关踩坑复盘 + 60+ 条记忆逐条对账「是否已有测试」，结论：**大部分历史坑已事后补测**（这是多轮复盘纪律的成果）。已确认有覆盖、本轮不动的：

- 20261103 五层真因（折叠/清场/工具栏文案）→ `PreviewResultIsolationTest` 6 例在场
- editorHolder 单槽位 → `PreviewEditorAssemblyKeepsConsoleTest` / `ConsoleHostTest` 在场
- agent 心跳窗口 → `AgentHeartbeatWindowTest` 在场
- JDBC execute 四态、XML 膨胀诱饵、fatal 透传、SpeedSearch、暂停显隐矩阵、DBN scanBailed、控制台签名覆盖、零产出冲刷等 → 各自在册用例（出处见各 pitfall 档 C 栏）

**真缺口**（本轮要补的）与**真机-only**（自动化永远复现不了，只能制度化）分列如下。

### 真缺口（可自动化）

| # | 缺口 | 证据 | 出处 |
|---|---|---|---|
| G1 | `ReplayExportDialog` 全仓 **0 个测试文件引用** | grep 实测（同包 Dialog 有 3~18 处引用） | `src/main/.../ui/ReplayExportDialog.kt` |
| G2 | `BrowserUtil.browse` 泄漏修复只有点状 `isUnitTestMode` 收敛，**无扫描守卫防复发**（新增降级出口不会被抓） | pitfall-test-sandbox-browser-leak 修复靠三处收敛；convention 守卫族无此规则 | `docs/memory-archive/pitfalls/pitfall-test-sandbox-browser-leak.md` |
| G3 | 权重画像陈旧：入库 TSV 619 类 vs 实际测试类 812 个（2026-10-10 画像），新重类被中位数插值成轻类 → 临界片失衡长尾（该形态已有实证：批次8 PluginSettingsPanelUiTest 4 例 19.5s 被按 ~1.5s 插值致 +13.7% 失衡） | 画像头部注记 `generated=2026-10-10 files=619`；失衡史 jsonl | `dev-support/test-shard-weights.tsv` |
| G4 | 负载敏感性能用例（LogEntryRailPerfTest / LogPipelinePerfTest）混在并发分片，负载波动假红 → 整轮重跑税（32m57s 级） | v2026.11.2 发行说明 flake 处置记录 | `docs/发行说明/20261102-1100-*.md:83` |
| G5 | `testShardsProgress.ps1` 硬编码 5 片；收口轮 SOP 无「自适应限流替代单路」口径（单路 33min vs 限流 5 路 5m02s 读数在案） | 脚本 `$shardCount = 5`；T-ReleasePerf9 读数 | dev-support 脚本 |

### 真机-only（自动化复现不了 → 制度化补进走查清单）

模态冻结默认 modality 的 invokeLater（M36.5.13，48 秒零重试铁证）、`ModalityState.any()` write-unsafe 快照、ToolWindow 标题动作悬停渲染、JOIN 图像素级重叠、设置页内导航聚焦、262 read-access 运行期读数（文档自认只有上报栈）、控制台 `use` 成功语句落 timeout hint、键位冲突通知必然出现、平台「选中即关联」持久化副作用。这些补进 `docs/规范/测试沙盒验证清单.md`，把「靠用户报障发现」变成「走查清单逐项确认」。

### 登记不修（本任务边界，防范围失控）

- `use` 语句 timeout hint：真缺陷，但行为修复需真机报错形态验证，登记缺陷票不动生产。
- 键位冲突结构性死路：待用户拍板，不动。
- 回滚 identity T-IIR-02：O-59 在册，不动。
- `releaseGate` stagger 240s 静态错峰改动态让路：列为可选 T7，前六券收口且有余量才做。

## 二、券清单

### 线一：测试盲区补测

**T1-ReplayExportDialog 补测**
- 新建 `src/test/kotlin/cn/song/mybatisplus/visualizer/ui/ReplayExportDialogTest.kt`，不碰生产代码。
- 用例面：DialogWrapper 结构（标题/按钮行动作与禁用态）、导出内容断言（快照字段→导出文本的映射）、非法输入（空快照/缺字段）不抛异常给兜底文案。
- 范式照抄同包 `RollbackSqlDialogTest`（DialogWrapper headless 构造先例）。

**T2-BrowserUtil 泄漏扫描守卫**
- 在 convention 守卫族新增扫描规则：`src/main` 中 `BrowserUtil.browse` / `BrowserUtil.open` 调用点必须处于 `isUnitTestMode` 收敛分支，或在守卫内显式豁免清单（写明理由）。
- **反向验证必做**：临时摘除收敛分支 → 守卫必须红，贴两次读数。
- 陷阱（前车之鉴）：豁免前缀表字面量必须留在被扫数组里，不得提成常量；守卫期望计数与生产调用点同步。

**T3-真机走查清单补齐**
- `docs/规范/测试沙盒验证清单.md` 增补上节九项真机-only 判据，每项带：触发路径、预期表现、失败时该抓的日志行。措辞照清单现有条目风格，不写 AI 味条目。

### 线二：全量门禁提效

**T4-权重画像刷新与保鲜告警**
- 用最近 clean 轮 XML 刷新 `dev-support/test-shard-weights.tsv`（619 → ~800 类）。
- `mpv-testing.gradle.kts` 权重段加陈旧告警：`discoveredTestClasses` 中既无本地样本又无画像覆盖的类占比 >15% 时 WARN 提示跑 `weight-refresh.ps1`（WARN 不挡构建）。

**T5-perf 用例串行尾道**
- 先实测两类 perf 用例在最近轮的 exec 时间，评估摘出代价。
- 摘出并发分片（`testShardExcludeClasses` 同机制），testShards 聚合新增固定串行尾任务单跑（独立 JVM），执行完再出聚合报告。
- 目的：负载敏感假红不再打翻整轮分片，重试面收敛到单个尾任务。

**T6-脚本参数化与收口 SOP**
- `testShardsProgress.ps1` 片数参数化（读 `-PtestShardCount` 或参数，缺省 5）。
- CONTRIBUTING 或规范补收口轮口径：机器空载收口用 `-PtestShardMaxLane` 自适应限流（5m02s 在案），不再单路 33min；禁降片红线不变。

**T7（可选）-releaseGate 动态让路**：stagger 静态 sleep 改「verifier 结束即放行」，须 A/B 双轮读数才定案；余量不足则登记待办不实施。

## 三、实施波次与并行约束

- W1（三路并行，≤3 上限）：T1、T2、T4 各一路子代理，在 t-testgap-speed 工作树内做，**每完成一件可提交的就立即提交**，计划 §实施记录回填写各自任务书指定段落。
- W2（W1 收口后）：T5（碰 `mpv-testing.gradle.kts` 聚合段，与 T4 同文件故错波）、T6 并行；T3 主控自己写文档。
- 收口：单派只读代理跑定向复核；主控并行更新本册实施记录；全量 testShards 后台跑（带进度显示，`dev-support/testShardsProgress.ps1`），禁阻塞等待。
- 纪律：跑 gradlew 前 `python C:/Users/USER/.zcode/cli/parallel_tasks.py check` 并登记；子代理只跑定向测试（`--tests "<FQCN>"`），不跑全量、不 `clean`、不 `--stop`；构建并发 ≤2 路。

## 四、待验证前提（实施者先证伪再动手）

1. **ReplayExportDialog 可 headless 构造**：若它依赖平台 DataContext/文件系统态超出 DialogWrapper 常规，则降级为「结构守卫 + 纯函数提取」路径，先证伪再写。
2. **weight-refresh.ps1 污染闸**：脚本带 `execSum > 900s 拒写` 闸，20261103 轮 5 片合计 961.8s（并发膨胀口径）——确认闸的统计口径，若被闸拒需用干净单路样本或明确调闸并留痕，不得绕闸静默写。
3. **两类 perf 用例确属负载敏感**：以发行说明 flake 记录 + 最近轮该类时间分布为准；若实测它们在 1 lane 也稳定红，按回归处理不摘道。
4. **buildSrc WARN 段不破坏 configuration-cache**：照既有 providers 范式写，改完跑一次 `testFast` + 一次分片配置期（`./gradlew :tasks` 级别）验证。
5. **T2 守卫扫描口径**：确认 `BrowserUtil` 的调用面（可能不止 browse/open 两名），扫描器与豁免清单同源单遍。
6. master 与 dev 存在分叉（dev 18 笔未合），本任务全部改动落在 master 线；合流时 T1/T2 新文件零冲突，T4/T5 的 `mpv-testing.gradle.kts` 段可能与 dev 在途线撞——合流前重跑预演。

## 五、实施记录回填

（各券完成后在此回填：提交号、定向读数、反例读数、与计划的偏差。）

- T1：
- T2：
- T3：
- T4：
- T5：
- T6：
- T7：
- 收口：
