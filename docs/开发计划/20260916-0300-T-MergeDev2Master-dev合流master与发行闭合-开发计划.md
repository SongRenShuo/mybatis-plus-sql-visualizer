# T-MergeDev2Master dev 合流 master 与发行闭合 — 开发计划

- 日期：2026-09-16 03:00
- 任务编号：T-MergeDev2Master（第五轮竞品调研报告 §8.1 合流与发布清账，**最高优先级，不占 N/O 编号**；本任务为第五轮全部开发任务的前置项）
- 来源：第五轮报告 §1/§8.1——交付债第三犯且创纪录：dev（`e587593`）领先 merge-base 94 提交（七任务 + T-ShortcutSettings + T-McpToolset2），master 独有 26 提交；master/dev 各有 5/2 提交未推送；feature/t-m56-01-agent-base 领先 17 提交且无远端
- 性质：合流 + 推送 + 发行闭合（无新功能）
- 实施方式：主工作树直接操作 master，不建任务分支
- 验证基线：合并后全量 `./gradlew testShards` 绿（计数口径以本任务实测钉死，消除 dev 侧 385 类/4508 与 367 类/4421 两套声明并存）

---

## 一、背景与依据

第五轮报告认定第一矛盾在交付链：4421 绿的成果与全场最宽的 MCP 十工具面全部滞留 dev，master 用户一个也看不到。双轨债连续三轮出现（第三轮「分支睡觉」、第四轮 75/29、本轮 94/26），合流动作应固定为「每个交付批次合完即回合」。本任务同时收口推送留底与 v2026.9.18 号段的正式发行。

## 二、现状核实（实测）

| 面 | 现状 | 位置/证据 |
|---|---|---|
| 分支拓扑 | master=`3213a21`、dev=`e587593`、merge-base=`56c9003`；master..dev=94、dev..master=26；origin/master..master=5、origin/dev..dev=2；feature/t-m56-01-agent-base=`efa2400` 无远端，master..feature=17、feature..master=5 | `git rev-list --count` 实测 |
| dev 侧 CHANGELOG | [Unreleased] 13 条顶层条目（Added 8/Changed 4/Test 1）待随发行入册 | `dev:CHANGELOG.md:5` |
| master 侧 CHANGELOG | 顶部即 [2026.9.18-beta.1]（Gitee 内测通道已占用 9.18 号段） | `master:CHANGELOG.md:5` |
| 版本号 | master `gradle.properties` = 2026.9.18-beta.1 | 实测 |
| 门禁基建 | releaseGate 一命令串行编排 + verifyGate 权威门禁离线化（T-ReleasePerf）已就位 | `99d89af`/`d0c0a16` |
| 已知合并坑 | CHANGELOG 合并条目落错版本块（历史实锤）；合并加字段首跑须清 build/classes+build/kotlin 防陈旧产物劫持 | docs/MEMORY 台账两坑 |

## 三、方案

**T-MD-01（合并前留档）**：跑前按铁律并行登记；快照 master/dev 计数（94/26）与三线 HEAD 留进实施记录；确认两工作树干净。

**T-MD-02（merge dev → master）**：冲突预判清单——`CHANGELOG.md`（master 顶部 beta.1 版本块 vs dev [Unreleased] 13 条，**逐条核对落对版本块**，Unreleased 内容归新版本块不落 beta.1）；`docs/MEMORY.md` 台账行两侧并集；`gradle.properties`/`build.gradle.kts` changeNotes 取正式版口径。合并后首跑前清 build/classes + build/kotlin。

**T-MD-03（全量与口径钉死）**：testShards 全量绿；实测类数/用例数写入实施记录，作为第五轮各任务的新验证基线，dev 侧两套声明就此作废。

**T-MD-04（推送留底）**：push master 与 dev；feature/t-m56-01-agent-base 推远端留底（本地独有分支尽快 push）；master 缺口的 5 提交（4d4fc74 + T-FeedbackBatch2 簇）增量合入 feature/t-m56（对齐其「master 三次增量合入」既有节奏，不反向展开 M56 内容）。

**T-MD-05（发行闭合）**：发行正式版（版本号实施时定案：v2026.9.18 正式（beta.1 内容并入）或顺延 v2026.9.19——beta.1 仅 Gitee 通道、Marketplace 未占用，两案均可；建议后者，避免「内测版转正」的 CHANGELOG 语义纠缠）：版本号 + CHANGELOG 新版本块（Unreleased 13 条入册）+ 变更记录 + 发行说明 + preview 清单 → releaseGate（含 verifyGate 三目标）→ 上 Marketplace → 过审状态核对（可复用 check_competitor_watch.ps1 的 API 拉取逻辑核对自家 update）。

## 四、验收标准

1. master HEAD 含 MCP 十工具注册（mcp-optional.xml 十条）与七任务全部提交；`git log origin/master..master` 为 0。
2. 全量 testShards 绿且计数单一口径（实施记录可查）。
3. dev 与 feature/t-m56 均有远端对应。
4. 新正式版在 Marketplace updates 列表过审可见，CHANGELOG 版本块正确（beta.1 节内容未被污染）。

## 五、真机走查清单（留人工）

1. 安装正式版：设置页可见「快捷键」节与 MCP 独立子页（十工具复选框），IDEA MCP Server 面出现十个 sqlv_* 工具。
2. ${} 注入标注、批量 IN 合并参考行、无界写确认清单各抽一例真机走查。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| CHANGELOG 版本块错位 | Unreleased 条目误落 beta.1 节（历史实锤） | T-MD-02 逐条核对 + 验收 4 专查 |
| 陈旧产物劫持 | 合并后首跑 NoSuchMethodError 成片 | 合并后清 build/classes + build/kotlin |
| 并行会话互踩 | 主树直接合并，登记表只管 gradle 沙盒不管文件 | 跑前 check/登记；合并与提交在同一会话内完成不留未提交改动 |
| verifyGate 沙盒锁 jar | mmap 锁残留/verifier 缓存损坏 | 既有处置：杀残留沙盒 IDE、rm .pluginVerifier 缓存重跑 |

## 七、实施顺序

```
T-MD-01（登记+留档）→ T-MD-02（merge+清产物）→ T-MD-03（全量+口径钉死）
→ T-MD-04（推送留底 + m56 增量合入）→ T-MD-05（发行+上市场+过审核对）
```

## 八、实施记录（实施后回填）

（未实施）
