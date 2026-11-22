---
name: pitfall-defect-id-collision-needs-dedup
description: 缺陷号 UI-045 已被 2026-10-23 占用，子代理凭感觉取的号撞车导致验收册/提交信息全要返工；派活时须把「取号前 grep 查重」写进约束
metadata:
  node_type: memory
  type: project
  originSessionId: sess_da4753b3-ef7f-4a1f-b5d2-a46a94883c80
---

# 缺陷/任务编号取号必须先查重（2026-11-01）

S2-8 真机批的验收册把「首次执行 runners=0」写成 **UI-045**，复核时发现撞车：
- `docs/发行说明/20261023-1200-*.md:78` 已用 **UI-045**（SQL 转 Java 类选择字段的描边/圆角对齐）
- `docs/LESSONS.md:130` 记的是 **UI-043/045** 自绘组件对齐
- 另外 FR-0045 走查还用过 **UI-045-01** 作子号

结果：整册要改号（UI-046 / UI-047）、提交信息重写、还要在正文留订正说明。

**做法**：派活时把下面两句写进硬约束——
1. 取新号前先跑 `grep -rhoE "UI-0[0-9]{2}" docs src | sort -u | tail`（或对应前缀 O-/D-/V-/S2-）确认号段空位；
2. 测试类名不嵌编号（如 `FirstExecUnconnectedSourceTest`），编号只出现在文档正文。

**同族坑**：同一份全景盘点里 round23 册的 S2-8＝k2j 跟版、round24 册的 S2-8＝真机验证债攒批，同号不同券——引用时**必须带册名**，否则下一个人照号找会找错。

相关：[[project-panorama-round23]]、[[feedback-subagent-must-use-repo-skills]]。
