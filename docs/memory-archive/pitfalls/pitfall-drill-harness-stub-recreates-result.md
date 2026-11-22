---
name: pitfall-drill-harness-stub-recreates-result
description: 用本地桩演练 CI shell 步骤时，桩会替被测逻辑补上缺的东西——把「写出」与「平台回读」指向同一文件即让反向场景恒绿；反向演练前先确认桩没有自愈
metadata:
  node_type: memory
  type: project
  updated: 2026-10-26
  originSessionId: session_1cc3e3259c26e0377a28bc4347b692ef
---

20261026 演练 GitHub Release 步骤（导出 YAML 里的 `run` 块，用 pwsh + `gh` 函数桩跑沙箱）时，同一类错误连犯三轮，每次都表现为「反向场景该红却绿」：

- 桩在 `release create` 分支里把 `--notes-file` **复制到「回读时返回的文件」**，等于桩自己把「平台没保存正文」这个缺陷修好了 ⇒ 「回读守卫」的反向用例恒绿。
- 先在回读文件预置假正文，但 `create` 又把它覆盖成真正文 ⇒ 同样是恒绿。
- 反向相反方向的假红：内测版预期首行我按「基号文档首行」写，实际脚本先按完整 `-beta.N` 命中了专属文档 ⇒ 正向用例假红。断言值必须取自**脚本实际选中的那个文件**，不能取我脑内假设的文件。

**Why:** 桩的职责是模拟平台行为（含不保存、丢弃、返回旧值），一旦桩复用了被测逻辑正要写出的同一份数据，就把「被测逻辑是否生效」这个变量消掉了——这正是 [[pitfall-fake-green-test-assertions]] 里「夹具预处理吃掉被测形态」的同族形态，只是发生在演练装置而非单测里。

**How to apply:** ①把「步骤写出了什么」与「平台回读会返回什么」设计成两个独立路径（create 复制到 capture，回读从 read 取），要模拟平台不吃正文就只让 create 不复制。②反向场景跑绿时，第一反应查「桩是不是替它做完了」而不是改生产逻辑。③断言里的期望值从脚本产出的实际选中文档里读，别手写。

相关：[[project-github-release-pipeline]]、[[pitfall-fake-green-test-assertions]]。
