---
name: project-github-mirror-sync
description: 双仓同步（Gitee 主仓 + GitHub 镜像）的现状核查结论、待用户准备/拍板的清单、已实测的推送姿势与四项待改代码/文档面；**20261022 已全部落地，本档转为「盘点快照」，落地终态见 project-dual-repo-github-mirror**；**并纠正一条实测错误：Marketplace sourceCodeUrl 早已是 Gitee、不是 null**
metadata:
  node_type: memory
  type: project
  updated: 2026-10-22
  originSessionId: session_312502c2d7f637393f32f25483bcdada
---

2026-10-22 用户提出「把插件仓库同步到 GitHub，之后双仓同步，Gitee 是主仓」。**本轮只做到盘点与清单（未建仓、未改文件、未推送）**——用户要自己去准备 token 等，随后才动手。**⚠️ 下文是当时的盘点快照，同日晚间已全部落地，终态读数与脚本口径一律以 [[project-dual-repo-github-mirror]] 为准；本档保留仅作「当时怎么判、错在哪」的对照。**

## ⚠️ 勘误：Marketplace `sourceCodeUrl` 早就填了 Gitee（20261022 用户当场纠正）

本档下文明细表里写的「`sourceCodeUrl` 与 `homepageUrl` **都是 null**」**是错的**——我查了 API **顶层**字段，而真实数据在 **`urls` 子对象**里：

```
urls.sourceCodeUrl = https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer
urls.bugtrackerUrl = https://sql-visualizer.jvtools.cn/about/feedback
urls.url / docUrl  = https://sql-visualizer.jvtools.cn
vendor             = SongRenShuo，url = https://gitee.com/songrenshuo
```

用户原话「我看了下目前的 Source Code 不是空的，目前是指向了 gitee」。**教训**：`GET /api/plugins/{id}` 的链接类字段集中在 `urls` 子对象（`url`/`forumUrl`/`licenseUrl`/`privacyPolicyUrl`/`bugtrackerUrl`/`docUrl`/`sourceCodeUrl`/`customContacts`），顶层只有 `link`（站内相对路径）与 `vendor`；判「某字段是否为空」前先把 `urls` 整个打出来，别按 key 名猜层级。

## 源码链接该不该从 Gitee 换成 GitHub（20261022 讨论结论）

**建议换成 GitHub，但「目前没影响」这个判断在事实上成立**——依据是**已实测过审**：`Marketplace发布流程-说明文档.md` 记录 2026-08-18 首版就用 Gitee 填该字段，审核未要求换，之后 9 个版本一路过审。官方硬要求只有「公网可达、公开可访问」，Gitee 满足；文档举例只列 GitHub/GitLab，**未明确列 Gitee**，所以那是个「实测能过、但口径不背书」的状态。

换 GitHub 的三条理由：①受众匹配（海外用户看到中文站常不点）；②可用性（Gitee 对境外不稳，GitHub 对境内不稳，二选一时 GitHub 是全球默认页）；③审核口径若收紧，这是唯一会被点名的字段。

**换的边界**：Gitee 仍是主仓，发行 / Release / `update.json` / 部署触发都不动；改的只是后台那一个展示用跳转字段。操作在 `plugins.jetbrains.com/author/me` → Plugin details → Source Code URL，**只能用户本人做**。

## 顺带查出：发布流程文档已有自相矛盾的陈旧记载

`docs/发行说明/Marketplace发布流程-说明文档.md` 里 §1.2 写「稳妥做法：双仓库…填 GitHub」，而 §5.1 / §5.4 / §10.2 又写 2026-08-18 定案「直接用 Gitee，GitHub 镜像可选未启用」，并附「仓库未配置 github 远程，`scripts/push-all.sh` 不存在」——**这三句在落地后全部过时**（github 远程已配、镜像在跑、双推脚本实名 `push-all.ps1`）；§10.1 收尾清单第 5 项也还是旧的手工命令 `git push github master`。下次发版前必须订正，否则权威流程文档会误导人。

## 现状实测（20261022）

- 仓库只有 `origin` 一个远程＝Gitee `https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer`；master 与 origin 完全同步；28 个 tag 本地与 Gitee 逐字一致；本地分支 4 个：`master` / `dev` / `feature/kotlin-to-java` / `t-sql-insight`（**只有前三个在 Gitee 上；`t-sql-insight` 是本地独有**，见 [[project-sql-insight-campaign]]）。
- 历史 3951 笔、单一作者 `SongRenShuo <3064597532@qq.com>`；`.git` 83MB、跟踪内容 45.9MB（最大是 2.7MB 的 `docs/images/demo-03-jpa-preview.gif`）；历史里**没有超过 5MB 的 blob**，不需要 LFS。
- **不需要另外准备 GitHub token**：本机 Windows 凭据管理器（GCM）已存 `gho_` 开头 40 位 token，实测 `X-OAuth-Scopes: gist, repo, workflow`；`api.github.com/user` 能取到 `login=SongRenShuo`（id 128769222，17 个公开仓）；`git ls-remote` 对已有仓库成功；对不存在仓库 dry-run push 返回 `Repository not found`（＝认证已过、只是仓库没建）。
- 目标仓库名 `mybatis-plus-sql-visualizer` 与 `sql-visualizer` 在 GitHub 上**都还是 404**（尚未创建）。

## 推送姿势（已在旧任务里实证，本仓同样适用）

- 全局 git 配置有 `url.https://ghfast.top/https://github.com/.insteadOf=https://github.com/` ⇒ 读写都被重写到只读代理，**push 会被 Connection reset**。
- 正确姿势＝remote 用带登录名的 URL 形态绕开重写：`https://SongRenShuo@github.com/SongRenShuo/<repo>.git`（前缀不同，insteadOf 不匹配），并挂本地代理：`git -c http.proxy=http://127.0.0.1:7890 push ...`，**`-c` 必须放在 `push` 子命令之前**。
- GitHub login（`SongRenShuo`）≠ GCM 里存的用户名标签（`XiaoSongTX`）。`gh` CLI 本机未安装，全程 git + curl 即可；`api.github.com` 直连可通、无需代理（建仓可走 `POST /user/repos`）。
- 本仓无 SSH key（`~/.ssh` 只有 `known_hosts`），`git@github.com` 报 Permission denied ⇒ 走 HTTPS。

## 待用户拍板/准备（我给的推荐）

| 事项 | 推荐 |
|---|---|
| 仓库名 | `mybatis-plus-sql-visualizer`（与 Gitee 同名；产品名 `sql-visualizer` 与插件 ID 路径不一致） |
| 可见性 | Public（Apache-2.0，Marketplace 源码链接要求公网可达） |
| 镜像分支 | master + dev + feature/kotlin-to-java；`t-sql-insight` 本地独有，建议不推 |
| 历史 Release | **不回填** 28 个（自更新 downloadUrl 指向 Gitee，GitHub Release 零消费方；回填要多推约 200MB 附件）；新版本起可选同步 |
| GitHub Issues | 建议关闭只留 PR（否则反馈分三处：官网需求池 / Gitee Issue / GitHub Issue；README 已把反馈统一到官网） |
| Marketplace 源码链接 | 建仓后去后台把 Source code 填 GitHub 地址——实测现在 `sourceCodeUrl` 与 `homepageUrl` **都是 null**，vendor 仍是 `https://gitee.com/songrenshuo`；官方只举例 GitHub/GitLab，Gitee 不在明确列表（见 [[project-release-workflow]] 与 `docs/发行说明/Marketplace发布流程-说明文档.md` §2.1） |

## 待改的代码/文档面（等用户点头才动手）

1. `.github/` 模板：把 `.gitee/PULL_REQUEST_TEMPLATE.zh-CN.md` 与两个 Issue 模板迁一份（按上表关 Issues 则只迁 PR）。
2. `CONTRIBUTING.md`：第 38/40 行 `git clone gitee.com/...` + `remote add upstream`，第 189 行「可在 Gitee 仓库提 Issue」都要改成双仓口径。落点结构见 [[project-contrib-onboarding-demo-fixture]]。
3. `README.md` 顶部导航行加 GitHub 链接。
4. `dev-support/` 加一个双推脚本（把「Gitee 直连 + GitHub 绕 ghfast + 挂代理」封成一条命令）；`gitee-release.py` 建议先不动，GitHub Release 真要用再加 `--github` 分支。

## 需要用户判断的风险（我没替用户决定）

- **20+ 份跟踪文档里写着闭源竞品名**（`MyBatisCodeHelperPro` / `MyBatis-Flex` 等），分布在 `docs/变更记录*`、`docs/发行说明/`、`docs/内容营销/`、`docs/开发计划/`、`docs/memory-archive/`、`docs/MEMORY.md`。Gitee 上已公开，不算新增泄露面，但 **GitHub 被搜索引擎收录的强度明显更高**，等于抬高竞品对比的可见度。清不清是产品决策，见 [[project-competitor-docs-open-source-exposure]]（那里已有的判据是「只提及竞品名不清、实现细节才清」）。

## 已确认不需要担心的

历史里从未提交 `gitee-token.json`（`.local/` 整目录忽略、根目录 `gitee-token.*` 与 `*.token` 有安全网）；`docs/内部/` 私有嵌套仓从未入库（`git log --all -- "docs/内部"` 零命中）；全仓 0 处 `F:\code\Java` 类绝对路径泄漏（`record/` 与 `dev-support/` 的敏感串扫描也为空）。

## 发版口径要跟着改的地方（别只改 remote 就说双仓通了）

- `buildSrc/src/main/kotlin/mpv-release.gradle.kts` 的 `writeUpdateManifest` 把 downloadUrl **写死 Gitee**；`UpdateSource.kt` 的 `DEFAULT_MANIFEST_URL` 与 `GITEE_RELEASES_URL` 也是 Gitee 常量 ⇒ **用户可见的更新通道仍必须走 Gitee**，双仓只影响源码托管面，不要让 GitHub 掺进自更新链。
- `docs/发行说明/T-ReleaseChecklist-发行检查清单.md` RC-R1（push tag）/ RC-R2（Gitee Release）与「日常同步＝`git push origin master`」都要补一步镜像推送；`project-release-workflow` 里的「T-RC-04 改 Gitee 直连、GitHub 镜像标注可选未启用」这条结论届时作废，需要订正。
- 服务器是 Gitee 单点：双仓后即便 Gitee 挂了，客户端的自更新仍会断——这是同一个决策的已知代价。

关联 [[project-contrib-onboarding-demo-fixture]] [[project-release-workflow]] [[project-competitor-docs-open-source-exposure]]。
