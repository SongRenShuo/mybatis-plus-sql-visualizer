---
name: project-release-20261107-beta1
description: v2026.11.7 发行链（beta.1 → beta.2 → 正式版，2026-11-07 三连发全部已发行）——落位读数与发行路径变更：正式版走 gate→publish→gitee --from-github，tag 钉发行提交
metadata:
  node_type: memory
  type: project
  originSessionId: sess_33620d7f-a4ec-4f92-8fbe-dcd146afc43f
---

# v2026.11.7 发行链（2026-11-07 三连发）

## 三版落位

| 版本 | 发行提交 / tag | Gitee | GitHub | 产物 |
|---|---|---|---|---|
| `2026.11.7-beta.1` | `9f7be229` | 1282011 | 437233093 | 7,585,659B / `9beb9d57…` |
| `2026.11.7-beta.2` | `0d0f22d0` | 1282162 | 437261310 | 7,585,275B / `ae0287d2…` |
| `2026.11.7`（正式版） | `c3d25918` | **1282197** | **437269094** | 7,585,295B / `0a4eb297…` |

- beta.2 带并行会话的 OPTIONAL 回填修复（`f8adf101` / `bd641efe`），用户原话「这个bug修复在今天的第二个内测版发布出去」。
- 正式版＝四个内测版合并转正；`plugin.xml` description 与市场页**未动**（磐维复用 Oracle 方言，方言总数仍是 14，「14 种方言」不用改）。
- 后续推送测试：`a60c4f0d` 那轮 42329759858 全绿（前两轮被并发组 cancel，属正常）。

## 门禁与出包口径

- beta.2：`build-plugin.yml mode=gate` run **42324880484**（15 job 全 success，verify 243/253/263 三目标 Compatible，verifyplugin 6m55s），出包走 **`mode=publish` run 42328028854** 复用门禁产物 `plugin-31`——发出去的字节就是门禁验过的那份。详见 [[reference-ci-release-publish-mode]]。
- 正式版：用户令「**全量门禁一类的可以跳过 因为刚跑过**」→ 沿用 run 42324880484 的读数（发行提交相对它只改版本号与文档，`src/main`/`src/test` 零差异，符合 RC-P1c），但**转正产物未重跑 `verifyGate`**，已在发行说明「没跑」段如实登记（版本号字符串本身未经 verifier 复核）。
- 正式版走**本机 `buildPlugin` + Gitee 直发 + `mirror-releases-to-github.py`**（不走 publish，因为 publish 复用门禁产物会带着 beta.2 的版本号）。

## 收口纪律（本轮验证有效）

- **tag 钉发行提交**，回填 docs 提交在其后、不移动 tag；tag 推送只推该 ref。
- 两侧发行正文都要在回填后重 PATCH：Gitee 必须同时带 `tag_name`/`name`/`prerelease`（漏 `prerelease` 会被静默重置成 false），GitHub 只传 `body`；镜像脚本复制的是回填前的正文。
- `update/update.json` 全程不动：beta.2 那次是用户自己在 10:19 提交的（`be288fda`），转正后仍需用户再改指 `2026.11.7`（[[feedback-update-json-user-only]]）。
- 更新日志合并口径见 [[feedback-change-notes-merge-order]] 追加三（beta 合并、转正合并、方言支持曾漏收）。

相关：[[project-release-20261106-beta2]]、[[project-release-workflow]]、[[reference-ci-release-publish-mode]]、[[pitfall-sandbox-ide-jvm-lock-and-launcher-shell]]。
