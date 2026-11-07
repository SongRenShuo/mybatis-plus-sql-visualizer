---
name: project-idle-queue-f15-20261106
description: F-01~F-15 新功能实施战役（20261106 用户全授权）——三组子代理并发、worktree 布局、组1/组2 已交付读数、组3 在途九项、集成分支 b8b03520、D 阶段收口步骤与红线
metadata:
  node_type: memory
  type: project
  originSessionId: sess_6974b678-058d-4588-aa81-d7d4f1597caa
---

用户 2026-11-05 授权 15 项新功能全部实施（27 册 §〇之二 A 11 + DBX 4），工作流令：**dev 先合 master→testFast→从 dev 建分支+工作树→多子代理并发（写集互斥，冲突就拆分支）→主控合并→只跑定向测试不跑全量门禁**。任务书＝`docs/开发计划/20261105-2350-闲时任务待办清单-开发计划.md` §一之二。

## 布局与分支

- dev 合并 master 后 tip=`f80d7aab`（见 [[project-master-into-dev-merge-20261103]] 第二轮）。
- 工作树全在 **`<dev>/.worktrees/{integration,f1,f2,f3}`**（git worktree add，`.worktrees/` 已加主仓 `.git/info/exclude`）；分支 `t-idle-integration` + `t-idle-f1/f2/f3`，均基于 dev。
- **写集互斥是防冲突的硬约束**：组1＝还原链+inspection+plugin.xml；组2＝设置三件套（PluginSettings/Panel/ScenarioGroups）+历史+agent+SqlLogPanel 启动态；组3＝log parser/导出/结果列头/超大 XML/agent 子项目。派活时逐组写明禁改文件清单。

## 交付状态（2026-11-06 时点）

- **组1 ✅**（t-idle-f1：`9e0f1a14` F-01 + `39f7d28c` F-02）：`TypedLiteralRenderer`（六类型×方言字面量，判不了返 null 走既有启发式；ORA-01722 主场景=BigDecimal 科学计数裸渲染救回）；`ParamResolveRules/Inspections` 三规则（map.key 缺失/@Param 未引用/片段缺失）——**全部 enabledByDefault=false**（索引盲区：JAR 内 mapper/未编译接口，误报 0 未满不默认开，规则①刻意不判 Map/Collection）。定向 83 例全绿；反向 F-01 恰红 4、F-02 恰红 8。**遗留**：log 包 `LogSqlParam.toHint()` 接 typeText 留收口（禁改面）。
- **组2 ✅**（t-idle-f2 五笔：`1dc23e1e` F-08 / `965dcd32` F-12 / `848fed0a` F-03 / `b3a1515d` F-07 / `11f628dd` 文档）：历史条目 +projectName 与 ForProject 收窄 API；历史右键「重新执行」（`HistorySqlRerunner` 纯函数，送既有执行链，**同方言强绑数据源未做**——深耦合 DataSourceSelector 热点）；随项目启动开关（`LogStartupCapture.decideStartupToolWindowAction` 纯函数 + ProjectActivity，**复选框计数 41→42 实跑核对**）；agent 残留清理 `AgentResidueCleaner`——**刻意不订阅 DynamicPluginListener**（卸载回调内反手触自身类有 classloader 时序风险，仓内定例走容器级联 dispose），清理挂 dispose+最后持有者闸+落盘修剪三处。新增 34 例全绿+存量回归 21 类全绿+反向 2 恰红。
- **组3 在途**（t-idle-f3，agent_178b557c-9fae-4c96-9ef6-5aecef9b6a05）：F-13 列排序（单一纯函数+三面板复用）/F-14 无 BOM CSV（设置计数 42→43）/F-06 三小件/F-04 ShardingSphere 染色日志 parser/F-09 超大 SQL 护栏（阈值先常量不接 UI）/F-15 空间类型保真/F-05 JPA 捕获（最小切片）/F-10 widget 菜单化（场景化入口只出方案）/F-11 EP 化方案+版本监听最小件。三件有明确降级口径。
  - **20261106 07:39 状态**：已提交仅 `1874e101`（F-13）；F-14 半成品在工作树（设置三件套接线+HistorySqlLogPanel/SqlExportService/SqlLogPanelActions/SqlPreviewPanelExecution+双语 bundle），build.gradle.kts 是沙盒登记临时改动须还原。第一次 SendMessage 唤醒未生效（死代理消息只排队不送达，见 [[feedback-subagent-quota-recovery]] §17），已重发含现场快照的完整续作令。
- **集成分支** `t-idle-integration`=`b8b03520`（f1+f2 `--no-ff` 零冲突并入，compileKotlin+compileTestKotlin 绿）。
- 闲置任务 `offpeak-22907412`（队列 #224）＝额度中断后续跑：**SendMessage 恢复组3（不新派，feedback-subagent-quota-recovery）**→D 收口。注意 OffPeakCreate 曾连续两次 30s 超时未建成功，第三次才入队——超时后先 OffPeakList 核实再重试。

## D 阶段收口（剩余步骤）

f3 合入 integration（冲突逐 hunk）→ 编译+testFast+受影响定向（红了只定向修）→ integration 合入 dev（已授权）→ 文档回填（闲时清单 §五/27 册/索引，两侧各自提交）→ `parallel_tasks.py done --name S1-7-idle` → 逐项汇报。**红线**：禁合 master、禁 push、禁发行、禁 GUI、禁全量门禁、构建并发 ≤2（组3 在跑时主控不编译）。

已知环境债：C 盘 0.6GB（见 [[reference-c-drive-space-layout]]）；testFast 6 例存量红（TypeFilter 3+O147 3，合并前 HEAD 复现）。

关联：[[project-panorama-round27]]、[[feedback-parallel-subagents-no-blocking-wait]]、[[pitfall-new-capability-hits-repo-wide-convention-guards]]。
