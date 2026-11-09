---
name: release-gate-timing-floor
description: releaseGate 墙钟地板与长杆（T6~T11 六期）——T11 第六期（20261103）market 三目标并行化（W2
  单跑 789→606s −23%）+ 测试链并发观测点四修链；整链新形态=market 与分片窗互为关键路径；648s/613s
  等旧地板声明作废，判读数看锚点分解
metadata:
  node_type: memory
  type: project
  originSessionId: sess_a9493574-3d5d-4cc1-8a7d-8a36bb9b757f
---

**T-ReleasePerf11（第六期，20261103）定案——先读这条**：**已并入 master（20261103 用户令，合并提交 `102795a0`，ort 零冲突；master 上复验 buildSrc 编译过、四任务在图内）**；工作树 mpsv-releaseperf11 产物已抢救完毕（见末条），可安全删除；计划 `docs/开发计划/20261103-0300-T-ReleasePerf11-…` §七全回填。合并注意：master 合并前被并行会话推进（架构三册 `bf944f19`），在共同祖先上正常落位；工作区并行会话在途文件与分支无交集时合并安全。

**券01 market 三目标并行化（在役）**：verifyMarketplace 拆 243/253/262 三子任务各配独立 verifier home（`marketplace-243` 等，一次性种子拷贝免 3×3.33GB 冷税，实测增量 ~13GB），**聚合任务保留原名**（releaseGate/gate 脚本/proc-sampler 无感），verdict 判定迁聚合 doLast（checkMarketplaceVerdicts 按报告目录首段取目标名，三目录逐一遍历）。**W2 暖窗读数：market 789→606s（−23%），下载 0 B×3，六 verdict 逐字 parity**；冷轮三进程 483ms 齐发无锁冲突；负测（-x 子任务+移报告目录）精准红。三子 home 保温靠 `verifyMarketplaceRefresh`（改普通 Task 内 ProcessBuilder 串行三段刷，stamp 写主 home）。

**券02 测试链加固**：分片装箱后**按权重降序重排槽位**（ShardPlan 构造前 classes/loads/frag 三张映射同步重映射，testShardN 名义与全部对账契约不动）；并发观测点 **laneAtEnd**（分片 JVM 注 `-DmpvShardMarker=<构建目录哈希>`，doLast 时 PowerShell CIM 扫描带标记的活执行器）——round5b 读数 `[1,1,1,1,0] peak=1` 与 lane2 两波形态精确吻合。

**观测点四修链（每步都是实测逼出来的，后人勿重蹈）**：①聚合 doLast 内 `Project.file()` 触发 CC「cannot serialize」→ 路径一律走配置期捕获值（同族第四次现形）；②「构建目录/test-sandbox/」路径字面量在 IPGP 执行器命令行不存在（沙盒走环境变量）→ 改显式 `-D` 标记；③doFirst 时测试 JVM 未出生，扫描恒 0 → 观测点迁 doLast；④**JDK ProcessHandle 在 Windows daemon JVM 内对其他进程 CommandLine 可见性受限**（同刻 PowerShell CIM 全可读）→ 改 CIM base64 通道；⑤Measure-Object 吐对象被 CLIXML 包成 `<Objs>` → PS 内转字符串前缀输出（`MPVLANE=N`，Regex 解析）。

**整链新形态（W3 权威轮 16m46s 全绿：8551/0/0/15、六 verdict、e2e 26/0）**：三目标并行后 market 段（606~801s）与分片窗（656~960s）**同量级互为关键路径**——W3 里 market 被分片(lane3)+gate 争用拖到 801s（单跑 606s），分片窗尾 978s 反超 market。**market 与分片深度错峰是下一轮的编排级空间**（stagger 拉长属自伤）。旧「648s/613s 地板」声明作废——判读数只看一次构建的锚点分解（START/END 戳 + XML mtime 重叠计算），别拿总墙钟推断。

**E1 取证定案（T10 遗留「同参数双形态」）**：冷树 100% 串行（等权装箱+首轮调度）、暖树 100% 并发（两轮复现）——根因=冷树零权重画像的等权形态，`test-shard-weights.tsv` 入库后不再出现；「agent:test 失败开局」假说证伪（串行轮 agent:test exit 0）。`SqlCaptureGateSniffTest` 新登记负载敏感 flake（单跑 4/0/0 绿）。

相关：[[parallel-build-resource-rules]]、[[pitfall-fake-green-test-assertions]]、[[pitfall-verification-blindspots-and-wrapup]]

**工作树产物抢救（20261103 用户令「有价值的都搬」）**：①**三子 home 暖缓存（~3.5GB）本就在机器级 `F:\gradle-home\mpv-verifier-home\marketplace-243/253/262`，工作树与 master 共用同一 GRADLE_USER_HOME 天然共享，删树不丢、无需搬**；②权重样本合并复制进 master `build/test-results/`（1436 XML，cp -n 合并式）+ `shard-imbalance-history.jsonl` 追加；③三份 verdict 原件 → master `build/reports/pluginVerifierMarketplace-*/`；④W3 e2e 证据 → master `build/mpsv-releaseperf11-e2e-evidence/`（独立子目录，不覆盖主树 evidence）；⑤zip 跳过（主树同名）。全部落 gitignored 的 build/，主树工作区零污染。删工作树前先这样抢救一遍是可复用套路。
