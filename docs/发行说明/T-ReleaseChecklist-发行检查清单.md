# T-ReleaseChecklist 发行检查清单

- 任务编号：T-ReleaseChecklist（第八轮报告 §9.2 N-28；全景批次 P1-7）
- 定位：发行链由用户亲手执行，本清单是其操作底稿——每一步给动作、命令、判据，做完一项勾一项，判据不满足不得勾选。
- 性质：发行流程可靠性清单，不改产品行为。发行上传本身维持人工（首次必须网页手工），清单解决的是「靠人记」变成「靠清单＋可回读验证」。
- 唯一权威：本文件是发行动作的唯一权威清单（T-RC-01）。`Marketplace发布流程-说明文档.md` §11 的 12 条复选框与 `Gitee自动发行工作流-说明文档.md` 的八处同步清单是素材来源，执行时以本文件为准。
- 素材与依据：`docs/开发计划/20260919-0449-T-ReleaseChecklist-双通道发行动作清单化与可验证化-开发计划.md`（含 §九 增补登记：2026.9.23 第二案例、审核响应回路、hasUnapprovedUpdate 第三档语义、Z-36 runbook 纪律）；第十一轮/第十二轮调研报告 §Z-36；版本号口径见 `docs/发行说明/版本号规范-说明文档.md`。

---

## 一、硬约束（动任何步骤前先读）

### 1.1 版本号三条硬约束

| # | 约束 | 判据 |
|---|------|------|
| 1 | 三段纯数字 `{年}.{月}.{日}`，月份与日号不补前导零；禁字母、禁四段（`2026.8.13.1` 不是合法 SemVer 上架会被拒）、禁前导零（`2026.08.13` 违规）、发布版本不带 `-SNAPSHOT` 等后缀 | `gradle.properties` 的 `version=` 肉眼核对三段 |
| 2 | Marketplace 不接受同一版本号重复上传，每次更新必须升版本；一日一版，同日热修并入次日（禁同日递增后缀） | 本次版本号 > 在售最新版本号（回读 updates 端点核对，见 §四） |
| 3 | 双渠道版本号必须一致（update.json version = Marketplace 版本 = Gitee Release tag）；git tag 与 Gitee 发行版标签 = `gradle.properties` 完整版本号字符串一字不差，不带 `v` 前缀——downloadUrl 按标签段拼接，标签差一个字符用户点「立即更新」就是 404 | tag 名与 `version=` 逐字符比对 |

内测通道例外：Gitee 内测允许 `-beta.N` 后缀（`YYYY.M.D-beta.N`），上架 Marketplace 的版本必须去掉后缀。

### 1.2 changeNotes 格式红线

- `gradle/plugin-change-notes.html` 新增 `<h2>v{版本}</h2>` 块置顶：只主标题、无括号、无冒号副题。
- 末尾补一行 ≤60 字 Marketplace 评分引导（中性措辞，如「好用的话，欢迎到 Marketplace 页评分留言（搜 SQL Visualizer）」，无激励、无模板评语）。
- changeNotes / preview.update.json / CHANGELOG / 发行说明四处承担对外可见性；description 与市场页是纯能力陈列，不承担「最近更新」播报（2026-09-12 定案）。

### 1.3 Z-36 发行身份纪律（zMyBatis 精华吸收条）

- 发行 zip 只能由本清单这一条路产出（构建产物不直接上传）；上传落位以回读 updates 端点 + hasUnapprovedUpdate 旗标确认为准。
- 发行中断后重跑前，先核对「版本号 / git tag / Gitee 发行版」三者的已落位状态，禁止按记忆续跑（核对命令见 §五）。2026.9.23 当天「盘点 34 版记『不建 tag 不调 API』→ 实际下午全做了」的状态漂移就是对口场景。

---

## 二、准备段（门禁与元数据）

- [ ] RC-P1 权威门禁三关：`./gradlew releaseGate`（buildPlugin → verifyGate 三目标 243/253/262 → testShards 全量）。判据：BUILD SUCCESSFUL，且 `build/reports/pluginVerifierGate/IU-{243,253,262}/.../verification-verdict.txt` 三份均标 Compatible。注意：日常巡检脚本 `verify-full.ps1` 跑的 `verifyPlugin` 已降级为报告生成器，发版只认 `verifyGate`。
- [ ] RC-P2 版本号核对：`gradle.properties` 满足 §1.1 三条硬约束；CHANGELOG `[Unreleased]` 已改名为 `[{版本}] - {日期}`。
- [ ] RC-P3 changeNotes：`gradle/plugin-change-notes.html` 新 `<h2>v{版本}</h2>` 块置顶，满足 §1.2 红线。
- [ ] RC-P4 发行 8 处同步（详见 `Gitee自动发行工作流-说明文档.md` 八处同步清单）：

| # | 文件 | 内容 |
|---|------|------|
| 1 | `docs/发行说明/{YYYYMMDD}-{HHMM}-{主题}-发行说明.md` | 新建完整发行说明，全文兼作 Gitee 发行版正文 |
| 2 | `CHANGELOG.md` | `[Unreleased]` → `[{版本}] - {日期}` |
| 3 | `gradle.properties` | `version=`（内测追加 `-beta.N`） |
| 4 | `gradle/plugin-change-notes.html` | 新 `<h2>v{版本}</h2>` 块置顶 |
| 5 | `plugin.xml` `<description>` | 仅出现新能力类别时并入一条，无新类别不动 |
| 6 | `update/preview.update.json` | 新版本号 + downloadUrl + changeNotes（与 #4 同文案）+ publishDate + sinceBuild |
| 7 | `marketplace-custom-page.html` | 功能矩阵行 + 「为什么选它」按能力类别判定 |
| 8 | `docs/MEMORY.md` + `docs/变更记录.md` | 发行说明索引最新在上，总册按日期倒序补齐 |

- [ ] RC-P5 产物核对：`unzip -l build/distributions/mybatis-plus-sql-visualizer-{版本}.zip`——应含 `lib/*.jar`、`META-INF/plugin.xml`、图标、messages 语言包；不应含测试类或无关依赖。
- [ ] RC-P6 沙盒冒烟：`./gradlew runIde`，zip 经 Settings → Plugins → ⚙ → Install Plugin from Disk 安装后验证 gutter 图标 / 右键转换 / 日志面板 / 设置页。

## 三、双通道发布段

- [ ] RC-R1 git tag：发行提交之后 `git tag {版本号}` → `git push origin {版本号}`。判据：tag 名与 `version=` 一字不差（§1.1 第 3 条）。
- [ ] RC-R2 Gitee Release：网页创建发行版，标签同名，附同名 zip，正文用发行说明全文。判据：Release 页可见附件且文件名含正确版本号。
- [ ] RC-R2b GitHub Release 镜像：`python dev-support/mirror-releases-to-github.py --tag {版本号}`（幂等，可反复跑；已存在则只补附件）。判据：`--verify-only` 通过、GitHub Releases 页出现同版本且附件 size 与本地 zip 一致。**注意上传走 `uploads.github.com`，不是 `api.github.com`（用后者返 404）**。
- [ ] RC-R3 update.json 收尾（**仅用户本人操作，AI 会话一律不得代改**）：发行后由用户独立单文件提交 `preview.update.json → update.json`（Gitee raw 为自更新清单源）。判据：`update/update.json` 的 version 与 downloadUrl 指向本版 tag 路径，浏览器打开 downloadUrl 能下载。会话侧的发行职责止于 RC-R2 + RC-R7 回读通过，报告里只写「update.json 待用户手改」。
- [ ] RC-R4 Marketplace 上传：`build/distributions/*.zip` 经网页表单上传（首次必须网页手工；后续可 `./gradlew publishPlugin -PintellijPlatformPublishingToken=…`）。表单：Apache-2.0 + 源码链接 **`https://github.com/SongRenShuo/mybatis-plus-sql-visualizer`**（2026-10-22 起改填 GitHub 镜像；Gitee 仍为主仓）+ tags + 截图（1200×760）。
- [ ] RC-R5 后台 listing 确认：上传后在 Marketplace 后台确认 listing 显示名已同步为 SQL Visualizer，页面 slug URL 不变。
- [ ] RC-R6 后台审核状态确认：作者后台（https://plugins.jetbrains.com/author/me）→ My Plugins → 版本列表，核对 Pending 版本号与内容；预期内等放行，误传则后台撤回（撤回不可逆，处置前截图留档）。判据：Pending 清零或结论留档。
- [ ] RC-R7 回读断言：执行 §四，判据不满足不得勾选本清单任何「发布完成」项。

## 四、回读断言（T-RC-03，发布未完成的直接防线）

上传（含审核响应后的重传）之后立即执行；不执行回读不算发布完成。

```bash
# 1. 在售条目回读：最新条目应为本次版本
curl -s https://plugins.jetbrains.com/api/plugins/33633/updates | python -m json.tool | head -40

# 2. 旗标回读：插件主对象含 hasUnapprovedUpdate（存在未过审记录时 true）
curl -s https://plugins.jetbrains.com/api/plugins/33633 | python -m json.tool | grep -E "hasUnapprovedUpdate|cdate"
```

判定分三档（hasUnapprovedUpdate 三档语义，§九.3 增补实证）：

| 读数形态 | 语义 | 动作 |
|---|------|------|
| updates 新条目在场，`approve=true`、`listed=true` | 已过审上架 | 发布完成，可勾选 RC-R7 |
| 插件主对象 `hasUnapprovedUpdate=true`（公开列表不含待审条目，是正常形态） | 待审压着，已落位审核队列未放行 | 回读结论记「在审」，等审期不重传；每轮调研复读旗标 |
| 无新条目且旗标 `false` | API 不可见——「队列延迟」与「上传未落位」两解不可区分 | **不得静默通过**。显式判为「未确认」，去 Marketplace 后台人工确认是唯一闭环手段；后台也无记录则按 §五 runbook 排查后重传 |

断言口径：最新条目 `version` = `gradle.properties` 的 `version`；cdate 与上传时点吻合。注意方法论：公开 updates 列表不含待审条目（2026.9.23 案例反证入册），所以「列表里没有」本身不构成失败证据，必须结合旗标分档。

## 五、审核响应回路（T-RC-01 增补，2026.9.23 首次实走模板）

审核退回不是终点，按此步骤链走完整回路：

1. 收拒信 → 读被拒理由归类（internal API usage / compatibility problems / 其他），本地用上传原包复现 Plugin Verifier 读数（`verifyMarketplace` 口径），逐条对上。
2. 根因修复：被点名平台类型一律改「Class.forName 探测 + 动态代理 + 程序化注册」路线，字节码零平台静态引用；守卫测试断言零引用属性防回归（2026.9.23 案例：internal 7 条→代理化清零；compatibility 3 块→全动态化，2025.3/2026.2 转 Compatible）。
3. `verifyMarketplace`（在线、无豁免清单）门禁复验：全目标 verdict 均 Compatible、compatibility-problems.txt 零块，才允许重传。
4. 回复审核邮件：话术骨架见 `docs/发行说明/20260923-marketplace-审核回复稿.md`（逐条列修复项 + 本地验证读数 + 残余项如实说明）。
5. 重传 → **重传后立即执行 §四回读**（第二案例的教训：零痕迹正是重传后没回读才两天后被外部调研发现）。
6. 回复稿落库 `docs/发行说明/`，登记 `docs/变更记录.md`。

## 六、恢复 runbook（Z-36，发行中断/失败时）

工作流失败不等于发布失败；pending 身份在场时禁止盲目重跑。重跑前先核对三者已落位状态：

```bash
# 1. git tag 是否已存在
git tag -l "{本次版本号}"

# 2. Gitee 发行版是否已建（用 Gitee API 回读，token 走 URL query）
curl -s "https://gitee.com/api/v5/repos/SongRenShuo/mybatis-plus-sql-visualizer/releases/tags/{本次版本号}" | python -m json.tool | grep -E '"tag_name"|"name"'

# 3. Marketplace 侧按 §四 三档判定
```

- 三者全无 → 按记忆续跑前仍先 `git status` / `git log` 核对发行提交是否在场，再从 RC-P1 起走。
- 部分在场 → 只补缺的那一段；Gitee 旧发行版附件不可改，版本号落位过的场景建新 tag 新发行版（同日内测走 `-beta.N`）。
- Marketplace 侧已上传但 API 不可见 → 按 §四 第三档走后台人工确认，不要直接再传（同版本号重复上传会被拒）。

## 七、事故登记（T-RC-05 复盘，两个案例都在册）

| 案例 | 形态 | 发现方式 | 根因归类 | 防复发 |
|------|------|---------|---------|--------|
| 2026.9.18 断供（第一案例） | **没传**：Gitee 已发、Marketplace 无条目、`hasUnapprovedUpdate=false`（连待审痕迹都没有） | updates 端点回读 | 执行漏步，非能力缺失 | T-RC-03 回读断言 |
| 2026.9.23 零痕迹 + B5 重传（第二案例） | **传了但 API 不可见**：tag+Gitee 上线+上传 → 审核两次退回（第一轮 internal API 7 条，第二轮 compatibility problems）→ 代理化改造 + 回复稿 v2 重传后，updates 仍 8 条止 2026.9.14、旗标 false（20260924 01:15 主控 curl 复核）→ 实为已落位审核队列（09-24 上午旗标 false→true + cdate 翻转），09-27 过审上架（updates 第 9 条 approve=true、listed=true） | 竞品调研兜住（外部发现，两天后才闭环）；后台人工确认是唯一闭环手段 | 回读时机与语义，不是执行漏步：重传后没回读；「无条目 + 旗标 false」被留成静默；且过审后本地文档仍写「B5 待重传」——已上架版与仓库叙述不一致，状态漂移再次现形 | 回读必须在每次上传（含重传）后立即执行；三档判定显式处置（§四）；Z-36 纪律「发布状态以产物为准，禁止按记忆续跑」 |

---

## 八、执行节奏参考

正常发行：§二 → §三 → §四（第一档收口）→ N-24 破零三件套。审核退回：§五回路后回 §四。任何中断：§六先行。发行窗口、跟版与破零动作的调度口径见全景批次开发计划（P0-2 窗口）。
