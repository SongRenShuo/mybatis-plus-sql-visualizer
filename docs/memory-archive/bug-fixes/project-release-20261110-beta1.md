---
name: project-release-20261110-beta1
description: v2026.11.10-beta.1 内测发行（2026-11-10，iBatis 交互补全 T-Ibatis2Gap 券
  A/B/C/D）——门禁/发布/双通道落位全绿，两次事故与恢复记录，update.json 待用户提升
metadata:
  node_type: memory
  type: project
  originSessionId: sess_c0d561d8-362b-43ca-913b-a6bfb07cc567
---

# v2026.11.10-beta.1 内测发行（2026-11-10）

## 范围与载体

- 范围＝`2026.11.9-beta.1` tag（`f9c5b0c3`，**不含** T-Ibatis2Gap）..HEAD，25 提交；主体＝T-Ibatis2Gap 券 A/B/C/D（双向跳转箭头、预览参数可交互、parameterMap 参数名按位对齐、降级可见性），另有 CI demo 克隆主备修复（非用户可见）。
- 发行提交 `44ab2393`（tag `2026.11.10-beta.1`，经两次前移）；GitHub Release **439581247**（prerelease，附件 7,852,093 B）；Gitee 发行版 **1292357**（prerelease，sha256 `8185814a65e5e4fe…` 校验 OK，`--verify-only --prerelease` [3/3] Verified）。

## CI 读数

- 门禁 run **42690035522** 15/15（build/verifyplugin/marketplace×3/verify×3/test×5/fast）；release run **42693049970** 17/17（含 publish）。
- 推送轮 push-test @ `e6f5ccc9` 7/7；夜间全量 42679665178 三红归因见下。

## 两次事故与恢复（都已按既有 runbook 处理）

1. **快桶腿红**：`IbatisXmlPreviewServiceTest`「数量不符保持失败档」——L3 夹具写在券 C 按位对齐**之前**的语义上，合并后 paramNames 按位取前 N → 数量相符 → 信息级。修法＝夹具改真错配形态（声明 1/位点 2）+ 补按位对齐信息级正例（`7d640980`）。这是 L3 报告里预警过的合流联动的兑现。
2. **changeNotes 全角冒号**：本版块第 2 条「预览参数可填值：动态条件…」含全角 `：`（L25 第 2 例，形态与 beta.1 的括号不同）→ `inspect-plugin-zip.py` FAIL → 文案改 `——`（`44ab2393`）+ tag 两次前移 + 门禁/发布重跑。**教训升级**：发版前跑守卫测试（绿）不够——全角判据在检查器里、需要先有 zip，正确顺序是 tag 前**本地 buildPlugin + 跑检查器**。

## 平台怪癖（新入册）

- tag force-move 后 GitHub `/releases/tags/{tag}` 恒 404（`/releases` 列表在、PATCH 重关联无效）→ 删 release 对象 + `mode=publish` 重建 → by-tag 恢复。详见 [[pitfall-github-release-bytag-404-after-tag-move]]。
- `gitee-release.py --verify-only` 不带 `--prerelease` 会把内测版误报 prerelease mismatch。

## 待用户

- `update/update.json` 提升（从 `preview.update.json` 单文件提交推送）——AI 不代改。
- 日常 IDE 检查更新 → 装 `2026.11.10-beta.1` → 真机复核六项（跳转箭头〔CodeHelperPro 同装〕/条件勾选+填值/iterate/parameterMap 参数名/降级横幅）。

## 发布后真机反馈（2026-11-10 当日，装包即验）

用户在日常 IDEA 2026.3 装 `2026.11.10-beta.1` 后仍报四项：`selectByCondition` 勾选填值 SQL 不变、`insertUser` 参数不回显、`insertUser` 的 selectKey 那条查询不显示、SQL 里 `# username #` 被拆散。本地探针已把后两项复现为真缺陷（selectKey 服务未调用 `renderSelectKey`；`SqlFormatter` 不认 iBatis `#x#` 原子 token），前两项归因见 [[project-ibatis2-gap-fix-round]]。→ **需要随后续修复再发一版内测**（版本号按「同日热修并入次日」取次日号）。

同时用户要求补 demo 的真实调用面（DAO/实现/执行入口），已交付并自检 20 条全通过，见 [[project-ibatis2-demo-real-exec]]。

## 环境

- **北京测试服务器 103.36.220.245 整机离线**（9 实例端口 + SSH + ping 全不通；对照机 114.66.55.245 全通）→ 夜间轮 `dialect`（9 实例连接秒失败）与 `e2e`（demo 应用 120s 未就绪，库在 CI 本机、与北京无关，可重试抖动）两腿红。服务器需用户到供应商控制台处置；恢复后重跑夜间轮闭合。

相关：[[project-release-20261109-beta1]]、[[project-pending-plans]]、T-Ibatis2Gap 计划册（`docs/开发计划/20261109-2252-…`）。

## 同日最终落位：v2026.11.10 正式版（2026-11-10）

beta.1 → beta.2 → beta.3 → **正式版** 四轮同日走完，正式版把四轮内测一起转正（同一基号下正式版 > `-beta.N`）：

| 版本 | GitHub Release | Gitee | 说明 |
|---|---|---|---|
| `2026.11.10-beta.1` | 439626933 | 1292357 | iBatis 交互补全（券 A/B/C/D） |
| `2026.11.10-beta.2` | 439883402 | 1292702 | 预览四项真机缺陷 |
| `2026.11.10-beta.3` | 439939643 | 1293016 | 双向跳转补链 + 图标归位 + jdbcType 引号 |
| **`2026.11.10`（正式版）** | **439966289** | **1293132** | release run `42758016138` @ `8561fafc` 17/17；附件 7,867,872 字节、sha256 `28711e56bfdbe635…`；`--verify-only` [3/3] |

**转正动作三条口径（本轮现学，已写进 `T-ReleaseChecklist` §1.2）**：
1. **删掉被正式版取代的内测块**——`gradle/plugin-change-notes.html` 与 `CHANGELOG.md` 两处都删（先例 `0692f222`）；用户口径「有正式版最新的了，beta 版本的就不应该存在了」。逐条细目靠 `docs/发行说明/` 的内测说明留档。
2. **正式版块第一条别写全角冒号**——L25 第 3 例就在这轮（`iBatis 2.x 全面支持：…`），`inspect-plugin-zip` 必 FAIL，且**只有 buildPlugin 产出 zip 之后才能发现**。
3. **范围锚点＝上一个正式版 tag，逐条核不重复**（用户事后指出）——本轮把 `v2026.11.8` 已修的两条（日期族 ANSI 字面量、2026.3 换源退化）又写进了 `v2026.11.10` 的 changeNotes/发行说明/变更记录，四处同批收敛（`1ef0b1fd`，不重打包）。落笔前先 `git log {上一正式版tag}..HEAD --oneline` 拉清单对照；同一任务族的后续修复（`T-DateLiteralBackfill` 后的「执行链只发 TIMESTAMP」）要写明是后续修复。


**发版踩点**：`MPV-Build` 每次构建唯一 ⇒ 本地 zip 哈希必与 CI 不同，`gitee-release.py --from-github` 之前先把 `build/distributions/` 同名旧包挪走。

**已知问题留给下版**：iBatis `selectByCondition` 多条件语句的勾选联动仍不正确（填值路径正常），用户明确「下个版本修复」。

