---
name: project-branch-align-merge-20261116
description: T-BranchAlignMerge 全分支对齐合流收官（20261117）：八支吃 master/dev 双仓已推+八轮 CI gate 全绿；含分支级 CI dispatch 用法与依赖校验冷缓存结构性收口两条可复用经验
metadata:
  node_type: memory
  type: project
  originSessionId: sess_3aea635f-1bc3-4480-a9a5-307ea4cccb67
---

# T-BranchAlignMerge 全分支对齐合流（2026-11-16~17 收官）

用户定案：master→dev 后其余支**分组对齐**（非每支吃两份）——**吃 master 组 6 支**（preview/dialects/rollback/security/hiddenbug/merge-route，单笔 `merge master`，判据双证＝behind 接近度+T-BranchMergeRoute 先例）+ **吃 dev 组 insight**（单笔 `merge dev` 顺带吃 master；硬前置＝预演+NestGate 默认值重拍+四件守卫）；k2j 全程未动；删 `t-standalone-hotreload`（被 master 吸收）+ demo 仓孤儿克隆。计划与终局账本：`docs/开发计划/20261116-2342-T-BranchAlignMerge-*.md`（§六 逐文件裁决+§六.1 修红账本），终局提交 `09f129ae5`。

## 终局读数（2026-11-17 03:06）
八支 CI gate 全绿（dispatch `build-plugin.yml mode=gate` 逐支）：preview `43907520040` / dialects `43915730620` / rollback `43907537976` / security `43931919466` / hiddenbug `43916284026` / merge-route `43916478233` / dev `43917243979` / insight `43932008569`。全部工作树干净、master/dev/八支均已推双仓。修红四类：①描述方言数 15→25（dialects/hiddenbug，master 重写描述段覆掉分支声明）②dev 守卫账本提额/归组 `ce8fbd03b` ③依赖校验清单（见 [[pitfall-dep-verify-cold-cache-whackamole]]）④security 转义位置真缺陷 `8a67887ae`（T-SAF-09/10 转义钩子落 master 新渲染下游，纯文本态显示字面 `&lt;-`；修法＝挪到两处 `<html>` 拼装边界+新增 HTML 态回归用例）。

## 可复用经验
- **分支也能跑 CI**：`gh workflow run build-plugin.yml --ref <分支> -f mode=gate`（令牌读 `.local/github-token.json`）；push-test 只挂 master push，分支推完必须手动 dispatch gate 才有全量读数。
- **NestGate 跨线裁决**：insight 线默认关 vs 主线默认开——合并态显式改回主线默认开（KDoc 留档旧裁决+回退 `-Dsqlvis.agent.nestGate=false`），agent 侧守卫按回退开关形态适配。
- **修红纪律落地**：守卫账本漂移（描述数/文件预算/设置归组）按对账修、禁弱化语义；真缺陷修生产侧；insight 10 类 UI 测试落 testFast 快桶（源码无 `com.intellij` 字面量但被测生产类走平台 UI）按协议加排除名单，整桶 5623/0 权威读数；`gh run rerun --failed-jobs` 偶发不生效时改派全新 run。
- 并行车道推 master 前先 fetch 判祖先关系再 rebase（本轮 88cf7bd5/b79b75aea 两次并车均如此消化）。
