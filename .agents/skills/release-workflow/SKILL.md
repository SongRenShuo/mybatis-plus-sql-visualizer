---
name: release-workflow
description: 插件双通道发行作业法——把 master 上的发行提交做成 git tag，走 CI 门禁发 GitHub Release，再同步 Gitee 发行版并落账。当用户说「发内测版」「发行正式版」「跟版发行」「发版」「beta 转正」「补 Gitee 发行版」「更新发行说明」时使用；覆盖版本号与八处同步、changeNotes 红线、门禁、双通道发布、回读断言与落账的完整 SOP。冲突时以 docs/发行说明/T-ReleaseChecklist-发行检查清单.md 为准。
---

# release-workflow 双通道发行作业法

发行动作的唯一权威是 `docs/发行说明/T-ReleaseChecklist-发行检查清单.md`（其 §一自述 T-RC-01）；本 skill 是它的执行版，冲突时以清单为准。方向固定 CI → GitHub → Gitee（LESSONS L30）：Gitee 是主仓与自更新通道，GitHub 是镜像与 CI 发行通道。发行链由用户亲手执行，会话只做「准备、校验、建议」——push、建 tag、发行、上传 Marketplace 都须用户明确授权（AGENTS.md 第 9 条）。

占位符：`{版本}` = `gradle.properties` 的 `version=` 原串；`{notes路径}` = 本轮发行/内测说明的仓库相对路径；`{run_id}` = CI 运行 URL 末尾的数字；`{上一正式版tag}` = 上一个纯数字版本的 tag。

## 0. 先定形态

| 维度 | 内测 `-beta.N` | 正式版 |
|---|---|---|
| 编号 | `YYYY.M.D-beta.N`，同日多版递增 N；基号被正式版占用则换基号 | `YYYY.M.D`，一日一版，同日热修并入次日 |
| 说明命名 | `-内测说明.md`，dispatch 必须显式带 `-f notes={notes路径}` | `-发行说明.md`，CI 按版本号自动匹配 |
| marketplace_lite | 可带（只跑 243，省约 5 分钟） | 不带（243/253/263 全跑） |
| Marketplace 上传 | 不收内测包，不上传 | 网页表单上传（首次必须手工）+ 审核回路 |
| 转正删块 | 系列内只留最新一个 beta 块、内容累计；被吸收后删块 | 置顶正式块时删被吸收的 beta 块与 CHANGELOG 对应段 |
| prerelease | true（版本号带 `-` 自动；Gitee PATCH 必须带） | false |
| update.json | 用户本人维护，会话只改 preview | 同左 |
| 门禁 | 不豁免，打 tag 前派 `mode=gate` 或走一条龙脚本 | `releaseGate` 或 CI `mode=release` |

出处：`docs/发行说明/版本号规范-说明文档.md:11-35`、`T-ReleaseChecklist-发行检查清单.md:33`/`:54`/`:88`/`:211-213`、`.github/workflows/build-plugin.yml:286-295`。

## 1. 前置检查

1. 确认在 master 工作树、树干净：`git status --porcelain` 无输出；`git rev-parse --abbrev-ref HEAD` = master。
2. 核版本号：`grep '^version=' gradle.properties`，三段纯数字、月日不补零、无字母/四段/前导零/`-SNAPSHOT`；内测才允许 `-beta.N`（清单 §1.1）。
3. 核基号可用：`git tag -l "{版本}"` 为空；基号若已被正式版占用（如同日 `2026.11.14` 正式版已发），换次日基号（docs/变更记录.md 实证）。
4. 拉范围：`git log {上一正式版tag}..HEAD --oneline`，逐条与上一版 changeNotes/CHANGELOG/发行说明/变更记录比对，只把本版新修的写进来；beta 提交由该区间自然覆盖（清单 §1.2 范围锚点条）。
5. 并行登记：`python C:\Users\USER\.zcode\cli\parallel_tasks.py check` → 有活跃任务先 `register --name release-<版本> --sandbox <独立沙盒>`；全程跑完 `done --name release-<版本>`（禁裸 done，AGENTS.md 第 2 条）。
6. 中断重跑前先核三者：`git tag -l "{版本}"`、Gitee API `GET /releases/tags/{版本}`、Marketplace §四三档；禁止按记忆续跑（Z-36，清单 §一.3/§六）。

判据：四项前置任一不满足即停，且不做任何对外动作（`dev-support/release-beta.ps1:94-116` 已把这条做成闸门）。

## 2. 物料：八处同步 + 两条机器判据

单笔发行提交同步八处（清单 RC-P4，`Gitee自动发行工作流-说明文档.md:42-51`）：

| # | 文件 | 内容 |
|---|---|---|
| 1 | `docs/发行说明/{YYYYMMDD}-{HHMM}-{主题}-发行说明.md`（内测用 `-内测说明.md`） | 全文兼作 Gitee 发行版正文 |
| 2 | `CHANGELOG.md` | `[Unreleased]` → `## [{版本}] - {日期}` |
| 3 | `gradle.properties` | `version=` |
| 4 | `gradle/plugin-change-notes.html` | 新 `<h2>v{版本}</h2>` 块置顶 |
| 5 | `plugin.xml <description>` | 仅新能力类别时并入一条 |
| 6 | `update/preview.update.json` | 版本号 + downloadUrl + changeNotes（与 #4 逐字一致）+ publishDate + sinceBuild |
| 7 | `marketplace-custom-page.html` | 功能矩阵行 |
| 8 | `docs/MEMORY.md` + `docs/变更记录.md` | 索引最新在上，发行条目随发行提交落账 |

内测轮实际文件面更小（近三轮都是 4~7 文件）：无新能力类别时 #5/#7 不动，#8 可按发行后补账另起一笔提交（2026-11-14 正式版、2026-11-15 内测版先例）。

写文案的硬规矩：

- 新块置顶时同批删上一块那行评分引导句，句末 ≤60 字中性措辞；块内禁全角 `（ ） ：`；`@注解名` 用 `<code>` 包裹（LESSONS L27）。
- 两条机器判据必须跑在 `git tag` 之前（LESSONS L25）：
  ```
  ./gradlew test --tests "cn.song.mybatisplus.visualizer.convention.ChangeNotesRatingLineGuardTest"
  ./gradlew buildPlugin
  python dev-support/inspect-plugin-zip.py --version {版本}
  ```
  前者断言评分句全文只 1 次，后者六项判据全 OK 才继续（清单 RC-P3/RC-P5）。红了改文案重提交。
- 双通道正文不对齐：GitHub 由 CI 拼「本版变更 + 元信息 + `<details>` 折叠历史」，Gitee 用发行说明全文；说明尾部带一行 `构建记录：https://github.com/SongRenShuo/mybatis-plus-sql-visualizer/actions/runs/{run_id}`（清单 RC-P4b）。run id 未知时留占位，别编；CI 生成的 GitHub 正文已自带这一行，不需要再 PATCH GitHub。
- 转正时删掉被吸收的 beta 块 + `CHANGELOG.md` 对应 beta 段，按能力面合并，细目靠内测说明留档（清单 §1.2）。
- 已发布段准入看产物不看提交：条目能力必须在该版本 tag 的树里找得到落点（`Gitee自动发行工作流-说明文档.md:116-135`）。

提交信息写 UTF-8 文件 `git commit -F <msg>`（Git Bash 反引号会被命令替换吃掉）；`src/main` 用户可感改动随提交回填 `[Unreleased]`（AGENTS.md 第 6 条），发行批只核对不补账。

## 3. 门禁

默认本地 `./gradlew releaseGate`（约 51 分钟），判据 BUILD SUCCESSFUL + `build/reports/pluginVerifierGate/IU-{243,253,263}/.../verification-verdict.txt` 三份 Compatible（清单 RC-P1）。日常 `verifyPlugin` 已是报告生成器，发版只认 `verifyGate`。

- 门禁绿才是发行前置；`buildPlugin` 出包 + `inspect-plugin-zip` PASS 都只证明包内自洽，不证明平台兼容（LESSONS L28）。
- 内测不豁免：打 tag 之前派 `build-plugin.yml mode=gate`，绿了再发；已先发的立刻补派并把 run id 写进变更记录（清单 RC-P1）。
- 零代码改动转正可复用分片读数（RC-P1c 三约束）：`git diff --stat <上次全绿提交> HEAD -- src/main src/test` 为空、改动仅限版本号/文案类、受文案影响的守卫单独复跑、发行说明如实写「分片读数沿用」。`verifyGate` 不适用复用。
- 仅在需要离开机器 / 干净环境时走 CI：`gh workflow run build-plugin.yml --ref master -f mode=gate`。四种 mode：`gate` 不发布 / `release` 门禁后发 GitHub / `publish` 复用已成功门禁产物 / `tests` 只跑测试（build-plugin.yml:4-8）。`publish` 只认运行级 `conclusion=success`（:707-708），首发轮 publish 失败后带 notes 的重发只能走 `mode=release` 重跑门禁（清单 RC-P4c）。
- 产物核对：`python dev-support/inspect-plugin-zip.py --version {版本}` 退出码 0；沙盒冒烟 `./gradlew runIde` 后 Install Plugin from Disk 验 gutter / 转换 / 日志面板 / 设置页（清单 RC-P6）。
- CI 命中 `ClosedFileSystemException` 是 Gradle zip 文件系统竞态，不是测试失败：九处调用统一走 `dev-support/ci/run-gradle-retry.ps1`（先 `--stop` 换 daemon + 清 IDE 解包目录 + `--max-workers=1`），看到 `::warning::命中 Gradle zip 文件系统竞态` 即已自动重跑。

## 4. 发行提交与 tag

```
git tag {版本}
powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/push-all.ps1 -Branch master -Tags
```

- tag 名 = `version=` 逐字、**不带 `v`**；downloadUrl 按标签段拼接，差一个字符用户点「立即更新」就是 404（清单 §1.1 第 3 条）。
- 推送一律走令牌通路，禁裸 `git push github`（AGENTS.md 第 10 条）：`push-all.ps1` 固定令牌读 `.local/github-token.json`、`-c credential.helper=` 置空绕开 GCM、GitHub 段代理不通回落直连。判定读数＝日志出现 `x-access-token:***@` 且无弹窗。
- tag 存在且指向别的提交时**拒绝静默前移**（release-beta.ps1:131-150）；确需前移见 §8。
- `git tag -l {版本}` 与 `git ls-remote --tags origin` 两处都查得到，才算这个版本存在（`Gitee自动发行工作流-说明文档.md:129-132`）。

## 5. 对外：CI 发行轮

正式版：
```
gh workflow run build-plugin.yml --ref master -f mode=release
```

内测版（说明叫 `-内测说明.md`，CI 只匹配 `*-发行说明.md`，漏 notes 必红）：
```
gh workflow run build-plugin.yml --ref master -f mode=release -f notes={notes路径} -f marketplace_lite=true
```

内测一条龙（推荐）：
```
pwsh -File dev-support/release-beta.ps1 -Version {版本} -Notes "{notes路径}" -MarketplaceLite
```
它按七步走完：前置闸门 → gates（评分句守卫 + buildPlugin + inspect-plugin-zip）→ tag → `push-all.ps1 -Tags` → dispatch + 轮询 → GitHub 读数 → Gitee 同步 + `--verify-only`（清单 §八）。`-DryRun` 只打印；`-SkipGates` / `-SkipCi` / `-SkipGitee` 供分步重跑；脚本从不碰 `update/update.json`。

读数与轮询：
```
python dev-support/ci-status.py runs
python dev-support/ci-status.py run {run_id}
python dev-support/ci-status.py wait {run_id}          # 退出码 0 = success，适合后台任务
python dev-support/ci-status.py release {版本}
python dev-support/ci-status.py pick-artifact {run_id}
```
结论永远取 `gh run view {run_id} --json conclusion` / `ci-status.py wait` 的 `conclusion`；`gh run watch` 被代理掐断会提前退出，其退出码不算结论（LESSONS L51）。CI 轮约 12~18 分钟，用后台任务轮询，不空等。

CI 侧已知形态：tag 落在检出树 `HEAD` 而非 `github.sha`（build-plugin.yml:764-767）；版本号带 `-` 自动 `--prerelease`（:891-902）；`environment: release` 只放行 master（:671、清单 §1.2b）；gate 聚合把 `skipped` 判红，五腿全 success 才放行 publish（:624-661）；`marketplace_lite` 只是 matrix 少一条，不动五腿判定（:293-295）。`release`/`publish` 与 gate 分属不同并发组且永不取消（:46-65）。

## 6. 双通道：GitHub → Gitee

```
python dev-support/gitee-release.py --version {版本} --notes {notes路径} --from-github --prerelease --yes
python dev-support/gitee-release.py --version {版本} --prerelease --verify-only --yes
```

- `--from-github` 是 Gitee 拿到门禁产物的唯一通路（LESSONS L30）：取 GitHub Release 同名附件、不重新打包、沿用 prerelease、检查 Gitee tag 已推、摘要后人工 `yes` 确认（非交互须 `--yes`）。判据 `[3/3] Verified` + 附件 sha256 OK。
- 本地同名 zip 与 GitHub 附件哈希不同时脚本拒绝覆盖，按提示把本地 zip 移出 `build/distributions/`（留存 `.local/dist-stash/`）再续跑（gitee-release.py:320-327；docs/变更记录.md 11.14-beta.1 / 11.15-beta.1 两轮实证）。根因是 `MPV-Build` 时间戳每次构建唯一，本地包与 CI 包永不同哈希。下载走可续传 `Range` + 服务端 `assets[].digest` 判据，别拿「重试」当万灵药（L51）。
- 补正文/换附件用 `python dev-support/gitee-release-update.py --version {版本} --notes {notes路径}` 或 `PATCH`，**不要 `--replace`**（删了重建会重传附件、可能踩 CDN 缓存）。PATCH 是整体覆盖，`tag_name` / `name` / `prerelease` 三字段必须一并传：漏 `prerelease` 会静默把内测版改成正式版（清单 RC-R2c、`Gitee自动发行工作流-说明文档.md:251-272`）。改完回读查 `prerelease` + 正文尾段 + 附件名，不只看 HTTP 200。Gitee PATCH 要用 by-id 端点 `/releases/{id}`；`/releases/tags/{tag}` 是只读 GET，PATCH 会 404。
- Gitee API 三坑：正文参数名 `body` 不是 `description`；附件端点只有 `attach_files`；`PUT` 不支持（405），改正文用 `PATCH`（`Gitee自动发行工作流-说明文档.md:168-200`）。Gitee owner 是 `songrenshuo`，GitHub 是 `SongRenShuo`，两端 tag 必须指向同一提交。
- 本机发布（无 CI 产物）才需要 `mirror-releases-to-github.py --tag {版本}` 补 GitHub 侧，上传走 `uploads.github.com`；清单缓存只保 30 分钟、`--tag` 现场拉取（清单 RC-R2b）。
- 正式版再走 Marketplace：网页表单上传 `build/distributions/*.zip`（首次必须手工），Apache-2.0 + 源码链接填 GitHub 镜像 + tags + 1200×760 截图；随后确认 listing 显示名与后台 Pending 版本号（清单 RC-R4/R5/R6）。上传成功 ≠ 公网已生效，审核期内 updates 端点仍显示上一版是正常状态。

## 7. 回读断言（不执行不算发布完成）

```
curl -s https://plugins.jetbrains.com/api/plugins/33633/updates | python -m json.tool | head -40
curl -s https://plugins.jetbrains.com/api/plugins/33633 | python -m json.tool | grep -E "hasUnapprovedUpdate|cdate"
```

三档（清单 §四）：① 新条目 `approve=true` `listed=true` → 完成；② `hasUnapprovedUpdate=true` → 记「在审」，等审期不重传；③ 无条目且旗标 false → 显式判「未确认」，去 Marketplace 后台人工确认，不得静默通过。公开 updates 列表不含待审条目，「列表里没有」不是失败证据。Gitee 侧再跑一次 `--verify-only`：URL 串一致 + 附件 sha256 与本地逐字节一致；CDN 可能仍缓存旧包，隔一会儿复查。内测轮不上传 Marketplace，§7 只作正式版判据，内测以双通道回读（`prerelease` + 正文 + 附件 sha256）为完成线。

审核退回走完整回路（清单 §五）：归类 → 本地 `verifyMarketplace` 复现 → 根因改「Class.forName 探测 + 动态代理 + 程序化注册」→ 门禁复验 verdict 全 Compatible → 回复稿落 `docs/发行说明/` → 重传**后立即回读** → 登记变更记录。

## 8. 失败与回退

- 工作流失败 ≠ 发布失败；pending 身份在场时禁止盲目重跑。先按 §1.6 核三者，再按清单 §六：三者全无 → 仍先 `git status`/`git log` 核对发行提交在场，从 RC-P1 起走；部分在场 → 只补缺的那段；Marketplace 已上传但 API 不可见 → 按 §7 第三档后台确认，不要直接再传（同版本号重复上传会被拒）。
- tag 前移重发（文案/代码改了）：`git tag -f {版本}` → `git push -f origin {版本}`（双推走 `push-all.ps1 -Tags`，建议 `--force-with-lease` 口径）→ `gitee-release.py … --replace`。tag 前移不删发行版，仅 ref 被删除才联动删（`Gitee自动发行工作流-说明文档.md:109-114`）；改完回读 `target_commitish` 是否落到新提交。
- 同 tag 再建报 400 → `--replace`（先删后重建）；只撤版用 `--delete-only`，它不动远端标签（删标签才连坐删发行版）。
- 同日第二轮内测：旧发行版附件不可改，另起 `-beta.N+1` 新 tag 新建发行版，两版共存。
- 发行后源码树又变了：已发 zip 不含新代码，走重发，不要只改文档（`Gitee自动发行工作流-说明文档.md:109-114`）。已发布段失实只能在下一个发行块如实说明，历史段不改写。
- CDN 旧包：同名附件重传后 `--verify-only` 复查 sha256；`gitee-release-update.py` 换附件必须先删旧再传新（同名附件允许并存，下载命中先传的那个）。

## 9. 发行后落账

1. 把 CI 结论、run id、tag 指向提交、GitHub Release id/prerelease/附件字节数/sha256、Gitee 发行版 id 与 `--verify-only` 读数回填 `docs/变更记录.md` 的发行条目，同步 `docs/MEMORY.md` 索引行，并补齐发行说明尾部的构建记录行（清单 RC-P4b）。**读数未知就写「CI 读数待回填」，不编造 run id / Release id**。落账是发行后补账提交，不进 tag（2026-11-14 正式版、2026-11-15 内测版先例）。
2. 若只有 Gitee 正文需要补构建记录行：用 by-id `PATCH /releases/{id}` 只改 body（三字段必带），GitHub 正文由 CI 模板生成已自带该行，不要动附件。
3. 核对 `update/preview.update.json` 指向本版；`update/update.json` 由用户本人独立单文件提交 `preview → update`（Gitee raw 是自更新清单源），会话报告只写「待用户手改」（清单 RC-R3、AGENTS.md 第 7 条）。
4. Marketplace 侧按 §7 三档记结论；在审状态每轮调研复读旗标。
5. `parallel_tasks.py done --name release-<版本>` 注销登记（禁裸 done）。
6. 若为 beta 系列：下一发 beta 重复本流程，changeNotes 块替换为本发累计内容。

## 10. 不得自动做的事

- 不改 `update/update.json`（只用户本人，`AGENTS.md:11`）。
- 不自动 push 远端、建 tag、发行、上传 Marketplace、外发内容；合流 dev↔master / insight→dev 同样等用户点名（`AGENTS.md:9`）。
- 不裸 `git push github`；推送 GitHub 一律走项目令牌（`AGENTS.md:10`）；调 GitHub API 也别用 `git credential fill`，直接读 `.local/github-token.json`。
- 不跑全量门禁冒充 CI 结论、不在报告里写没跑过的读数；不把 `buildPlugin` 成功当门禁（L28）。
- 不编造 run id / Release id / CI 结论；不发版后再跑文案自检（L25）。
- 不在 Gitee 未推 tag 时建发行版；不拿本地 zip 先发 Gitee 再镜像 GitHub（L30 反路）。
- 不用 `--replace` 改正文；PATCH 不漏 `prerelease`。
- 不降分片换并行、不 `clean`、不 `--stop` 别人的 gradle（AGENTS.md 全局并行纪律）。

## 11. 为什么（规则一句话 + 出处）

- 版本号/tag 逐字一致：downloadUrl 按标签段拼接，差一字用户点更新就 404。（清单 §1.1 第 3 条）
- 两条 changeNotes 判据卡在 tag 前：文案会被 `patchPluginXml` 编进 zip，发出去再发现只能 tag 前移 + 双通道重发。（LESSONS L25）
- `@注解名` 用 `<code>`：GitHub 先解实体再识别提及，`&#64;` 与反引号都挡不住。（LESSONS L27）
- 门禁绿才是前置：`inspect-plugin-zip` 查包内自洽，`verifyGate` 查这份字节在 243/253/263 能不能加载。（LESSONS L28）
- 方向 CI → GitHub → Gitee：本地先发 Gitee 再镜像会让两侧附件字节与正文来源都不一致。（LESSONS L30）
- 结论取 `conclusion` 不取 `watch` 退出码：代理掐断会让 watch 提前退出。（LESSONS L51）
- 下载要续传 + 服务端 digest：一次 `read()` 没有续传语义，重试只是把同一失败重演，长度相等不是内容相等。（LESSONS L51）
- 推送脚本纯 ASCII + 仓名写死：PS 5.1 按 ANSI 解码中文注释会吃掉下一行赋值；凭据里的 `repo` 是元数据不保证与所在仓一致。（LESSONS L47、AGENTS.md 全局）
- publish 只认成功门禁轮：失败运行的产物没有门禁背书。（build-plugin.yml:707-708、清单 RC-P4c）
- PATCH 三字段必带：Gitee 是整体覆盖，未传字段回落默认值，内测版会被静默改成正式版。（清单 RC-R2c）
- 已发布段看产物不看提交：晚于 tag 落盘的能力从来没进过发行包。（`Gitee自动发行工作流-说明文档.md:116-135`）
- 发行提交不等于发行：没有 `git tag` 就没有产物，历轮把发行提交读成「已发」断供两周无人发现。（同上 :129-132）
- 回读三档：公开 updates 列表不含待审条目，「列表里没有」与「上传未落位」不可区分。（清单 §四）
- `skipped` 判红：GitHub 里 skipped 的 job 不会让整轮变红，等于「没验也过」。（build-plugin.yml:618-661）
- 提交信息 `git commit -F`：Git Bash 里反引号被命令替换吃掉，提交仍成功但信息静默缺段。（AGENTS.md 全局）
- 提交即回填 CHANGELOG：发行批只核对不补账，漏账不能靠发行批兜底。（AGENTS.md 第 6 条）

## 12. 实证（2026-11-15，仅作形态举例，不是通用值）

发行 `2026.11.15-beta.1`：发行提交 `1c36c369c`（7 文件）；因 `2026.11.14` 基号已被正式版占用改用新基号 `2026.11.15`；范围 `2026.11.14..HEAD` 23 笔；tag 指向发行提交，双仓 master 与 tag 同指该提交；本地三道门禁全绿后 `mode=release`（带 `notes` + `marketplace_lite=true`）run 43659175871 14/14 全绿；GitHub Release 445715456（prerelease，附件 8,253,623 字节、sha256 `b511cd14…`）、Gitee 发行版 1310184（`--from-github`，`--verify-only` [3/3] Verified，逐字节一致）；本地 gates 包与 CI 包哈希不同，按护栏移出 `build/distributions/` 后续跑；落账提交 `a95c74231` 回填读数与构建记录行，Gitee 正文用 by-id PATCH 补该行（GitHub 正文已自带）。`update/update.json` 未动，由用户本人维护。
