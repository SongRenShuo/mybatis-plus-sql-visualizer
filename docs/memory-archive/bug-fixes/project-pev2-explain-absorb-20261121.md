---
name: project-pev2-explain-absorb-20261121
description: PEV/pev2 吸收调研（20261121，私有仓 1645f50）：postgres-explain-visualizer 壳解剖
  + pev2 特性面 + PEV-01~06 候选登记；私有仓铁律与工具坑在册
metadata:
  node_type: memory
  type: project
  originSessionId: sess_89603c38-9aa2-446d-aabe-f19632e9bc75
---

postgres-explain-visualizer（Marketplace 18804）吸收调研，2026-11-21 完成，两份文档落**私有仓** `docs/内部/`（嵌套独立 git，gitee `sql-visualizer-internal-docs`，提交 `1645f50`，未推）：

- 调研报告 `docs/内部/调研报告/20261121-1756-postgres-explain-visualizer-查询计划可视化深度解剖与吸收规划-调研报告.md` + 计划 `docs/内部/开发计划/20261121-1756-postgres-explain-visualizer精华吸收-开发计划.md`（PEV-01~06，全部停等点名）。**公开仓不留占位指针**（私有仓 README 铁律：公开材料写「内部资料库」中性标签，占位文件本身会暴露藏了逆向报告）。
- 壳体：tjhelmuth/postgres-explain-visualizer（978 行 Java 零测试，最后 push 2024-03-22；**货架版 1.12.2.x 未开源有代差**）——`ExplainActionBase.Ui` 白拿选区（`VisualizerActionBase.java:22`）、`PlanData.java:14` 写死 EXPLAIN 命令零配置零防护、假域 `http://myapp` scheme handler + 手拼 executeJavaScript 注入三反面教材。
- 精华源头 dalibo/pev2 v1.25.0（3,618 star 活跃）：text/JSON 双解析器 + psql 边框/折行/worker/JIT/WAL 清洗（113 夹具可当测试基线）、estimation factor >10/100/1000 三档徽章（无忽略带=我方要加小样本豁免）、四象限 Stats、六族徽章、unaccounted-time ≥25%；**无折叠/无对比/无 i18n**（`collaps` 全源码只在 CSS border-collapse——佐证口径）。
- 我方对位：EXPLAIN 五件在 master（R33 ExplainPanelChannel 双通道 fail-closed / ExplainJsonPlanTree / R61 ExplainPlanDiff / ExplainIndexAdvisor / R90 ExplainPlanSnapshot）+ **`SqlTypeClassifier.kt:103` 拦 EXPLAIN ANALYZE+DML 变体=独占安全面**；`ExplainSupport` 10 方言档位单源。
- 候选：PEV-01 计划粘贴即读（text 解析链，与 D234 粘贴即体检同构）/ PEV-02 estimation 徽章（+小样本豁免）/ PEV-03 四象限 Stats / PEV-04 徽章族扩 `ExplainIndexAdvisor` 单源 / PEV-05 控制台直出（继承 ExplainActionBase.Ui 前先证伪 243~263 存在性）+ 选项渐进披露 / PEV-06 大计划首屏（热点子树排序+默认收拢）。

工具坑：Marketplace 评论端点＝`/api/plugins/{id}/comments?family=intellij`（reviews 端点缺 family 参数报 400）。**后续：2026-11-21 用户点名实施，PEV-01~06 已全部落地分支 t-pev-absorb 并完成 262 GUI 验收（13 通过+UI-001 双键统计条缺陷派修）——实施与验收实录见 [[project-pev-impl-gui-acceptance-20261121]]**。关联 [[project-r44-fullaudit-20261121]]、[[project-pending-plans]]。
