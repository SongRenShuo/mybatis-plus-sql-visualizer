---
name: project-release-v2026-11-24-beta.1-inflight
description: 发行 v2026.11.24-beta.1 内测在途（20261124）——物料/tag/双仓推送/CI 派发已做，CI
  仍在跑，Gitee 同步与落账回填未完成
metadata:
  node_type: memory
  type: project
  originSessionId: sess_92f7fc39-ba21-44a5-96bc-69c93449f148
---

发行 `v2026.11.24-beta.1` 内测于 2026-11-24 启动，**对话在此任务中途结束（CI 仍在跑）**，后续会话须按此状态续跑并在动手前用 gh/git 实测复核。

**范围**：`2026.11.23` 正式版之后区间唯一用户可感 `src/main` 改动 = `beef3fa18`（T-MapperNavYieldBySide，让位只作用于 XML 语句行、Mapper 接口方法行恒显示本插件图标；UAST MetaLanguage 投递 + 两家图标同款是根因，详见 [[project-mapper-nav-yield-by-side-20261124]]）。区间其余提交全是内容营销/计划/调研/索引类文档。无新能力类别，plugin.xml 描述与市场页功能矩阵不动。

**新基号**：`2026.11.23` 已被正式版占用 → 换 `2026.11.24-beta.1`（内测版，Marketplace 不收，CI 走 `marketplace_lite=true`）。

**已完成（有据）**：
- 前置：master 树干净（会话快照里的内容营销改动已随 `5a2734039` 提交），本地领先双远端 1 笔无缺失，pull --ff-only 干净；并行登记 `release-2026.11.24-beta.1`（sandbox release-master）已注册，**尚未 done**。
- 物料五处：内测说明 `docs/发行说明/20261124-2117-Mapper接口方法行跳转图标让位分侧修复-内测说明.md`、CHANGELOG beta.1 段、gradle.properties、change-notes（本版块 1 条 + 评分句）、preview.update.json。tag 前源码判据 `check-change-notes.py --version 2026.11.24-beta.1` + `--self-test` 全绿（C1~C9 PASS、反向 9/9 恰红）。
- 发行提交 `fd3fd57b1`；tag `2026.11.24-beta.1` 双仓推送走 push-all.ps1 令牌通路（`x-access-token:***@` 无弹窗），本地/双仓 master/tag 四方 ref 一致 = `fd3fd57b1`。
- CI 已派发：`gh workflow run build-plugin.yml --ref master -f mode=release -f notes=docs/发行说明/...内测说明.md -f marketplace_lite=true`，run id **45402634095**，headSha `fd3fd57b1`。派发时八腿（build/agenttest/test×5+fast）in_progress。⚠️ 该 head 含 `beef3fa18`，但当时 `test (5)` 的红尚未修——push-test run 45400815977（head `f59c1124d`，本 tag 的父提交）已因 `IbatisNavigationLineMarkerProvidersTest` 的「MyBatis 对照半边 assertEmpty」红在 test(5)；该修复后续只在本地完成未提交，故 release CI 的 test 分片很可能同红。续跑先看 test 分片，修复推上后需重派或跟一版。

**未完成（续跑入口）**：
1. 等 CI 45402634095 结论（取 `gh run view --json conclusion` / `ci-status.py wait`，别用 watch 退出码）。后台 wait 任务在派发会话中挂着；若会话已断须重新查。
2. CI 绿后 Gitee 同步：`python dev-support/gitee-release.py --version 2026.11.24-beta.1 --notes "docs/发行说明/20261124-2117-Mapper接口方法行跳转图标让位分侧修复-内测说明.md" --from-github --prerelease --yes`，再 `--verify-only --yes`（[3/3] Verified）。发行说明尾部构建记录行已提前填 45402634095，建版自带省 PATCH。
3. 落账回填（工作树已有占位改动，未提交）：`docs/变更记录.md` + `docs/MEMORY.md` 的 CI 结论 / GitHub Release id / Gitee 发行版 id / 附件字节与 sha256 占位待替换；提交落账（不进 tag）。
4. `parallel_tasks.py done --name release-2026.11.24-beta.1` 注销（禁裸 done）。
5. `update/update.json` 不动，用户本人维护（preview→update）。

**Why**：发行链中途断在 CI，若下一个会话按记忆当「已发」会跳过 Gitee 与落账，造成断供（LESSONS 教训：发行提交≠发行）。
**How to apply**：续跑第一步先 `git tag -l 2026.11.24-beta.1`、Gitee `GET /releases/tags/2026.11.24-beta.1`、CI run 45402634095 三者实测现状，禁止按本记忆里的「未完成」字面盲跑（Z-36 纪律）。相关：[[project-release-v2026-11-23-zhengshi]]。
