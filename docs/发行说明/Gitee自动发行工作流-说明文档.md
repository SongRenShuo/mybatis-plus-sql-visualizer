# Gitee 自动发行工作流说明文档

> 沉淀自 v2026.9.14-beta.1 内测版全自动发行实录（2026-09-12，提交 34bd652，Gitee 发行版 id 1139592）。
> 覆盖从跑门禁到 Gitee 发行版上线的完整链路，可照单执行；正式通道上架流程见
> [Marketplace发布流程-说明文档.md](./Marketplace发布流程-说明文档.md)，版本号规则见
> [版本号规范-说明文档.md](./版本号规范-说明文档.md)。

## 一、总流程（顺序执行）

```
parallel_tasks check/register
  → ① releaseGate 一键（buildPlugin → verifyGate 三目标门禁 → testShards 全量测试；T-ReleasePerf-02）
  → ② 发行文档同步（八处，见下）
  → ③ 发行提交（单提交，10 文件量级）
  → ④ buildPlugin 产 zip（releaseGate 已产出；若跳过则此处单独跑）
  → ⑤ git tag + push master + push tag
  → ⑥ Gitee API 建发行版 + 上传 zip 附件（见第三节）
  → ⑦ 回读校验 downloadUrl
  → parallel_tasks done
  → 人工收尾（**仅用户本人，AI 会话不得代改**）：preview.update.json 拷入 update/update.json（独立单文件提交，不进发行提交）
```

跑任何 `./gradlew` 前按并行铁律先 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`，
有活跃任务先 `register`，全部跑完 `done --name <任务名>` 注销（裸 `done` 注销全部，禁用）。
说明：2026-09-15 起门禁由 `verifyGate`（直驱 verifier-cli，离线 + 豁免清单）承担，替代 IPGP
`verifyPlugin`（判定缺陷字节码实证见 build.gradle.kts）；`releaseGate` 把打包/门禁/测试串成一条命令，
消除「门禁与测试并行抢 CPU + 门禁对被测代码时序空挂」两个问题。

## 二、准备阶段要点

### 范围核算

- 区间 = 上一个发行标签到 HEAD：`git log --format="%h %ad %s" --date=format:"%m-%d %H:%M" <上版tag>..HEAD`。
- **打标签之后才落盘的提交不在上版发行包里**——本次实证：T-ReverseParenWira（a24a30e）晚于
  `2026.9.11` 标签，其条目原挂在 CHANGELOG `[2026.9.11]`，须移入本版块并加注说明。
- 纯 docs 提交（记忆精简、计划定稿）不计入发行内容。

### 八处同步清单

> 2026-09-30 起，发行动作的唯一权威清单为 [`T-ReleaseChecklist-发行检查清单.md`](T-ReleaseChecklist-发行检查清单.md)（N-28 / T-RC-01）；下表保留作素材，执行时以权威清单为准。

| # | 文件 | 内容 |
|---|------|------|
| 1 | `docs/发行说明/{YYYYMMDD}-{HHMM}-{主题}-发行说明.md` | 新建，完整发行说明（新增/修复/测试/兼容性/安装/反馈）；全文同时作为 Gitee 发行版正文 |
| 2 | `CHANGELOG.md` | `[Unreleased]` → `[{版本}] - {日期}`，Keep a Changelog 格式 |
| 3 | `gradle.properties` | `version=`；内测通道追加 `-beta.N` 后缀 |
| 4 | `gradle/plugin-change-notes.html`（T-BuildScriptSplit 起外置，原 `build.gradle.kts` `patchPluginXml.changeNotes` 内联） | 新增 `<h2>v{版本}</h2>` 块置顶（红线：只主标题、无括号、无冒号副题） |
| 5 | `plugin.xml` `<description>` | 仅当出现新能力类别时并入一条；无新类别不动（「最近更新」节已整体移除，见下） |
| 6 | `update/preview.update.json` | 新版本号 + downloadUrl + changeNotes（与 #4 同文案）+ publishDate + sinceBuild |
| 7 | `marketplace-custom-page.html` | 功能矩阵行 + 「为什么选它」按能力类别判定；单源（docs 副本已删） |
| 8 | `docs/MEMORY.md` + `docs/变更记录.md` | 发行说明索引最新在上；总册缺行按日期倒序补齐 |

description / 市场页定位是纯能力陈列，不承担「最近更新」播报（2026-09-12 用户定案）；
功能更新对外可见性由 changeNotes / preview.update.json / CHANGELOG / 发行说明四处承担。

### 内测 beta 系列专项规则

- 对外更新清单（changeNotes / preview.update.json）**只保留最新一个 `-beta.N` 标题块**，
  内容为全系列累计；后一发 beta 替换前一发的块；转正时改纯数字标题并将累计内容并入正式版块。
- `PluginVersionTest` 版本正则须容忍内测后缀：`\d{4}\.\d{1,2}\.\d{1,2}(-beta\.\d+)?`
  （2026-09-12 已放宽入测试，正式版无需回改）。
- 正式版 2026.9.14 发行时须包含 beta.1~N 全部更新日志的合并版。

### 门禁

- 全量测试：total/skipped/failures/errors 四计数记录进发行说明。本次基线 3353/0/0/6。
- verifyGate（T-ReleasePerf 起权威门禁）：BUILD SUCCESSFUL +
  `build/reports/pluginVerifierGate/IU-{243,253,262}/.../verification-verdict.txt` 三份 Compatible。
  warning 级弃用计数与上版对比（2026-09-18-beta.1 基线 243=3 / 253=6 / 262=52）留观察不阻断。
  离线 + 独立 verify-home 缓存实测三目标 3m06s（历史 verifyPlugin 中位 7m26s）。
- 测试假失败两类先证伪再排逻辑：`build/test-results` 中途损坏（`NoSuchFileException:
  ...in-progress-results-generic.bin` + 成片 ClassNotFoundException）→ `rm -rf build/test-results`
  重跑即绿，不是代码问题。

### 发行提交与产物

- 提交信息格式：`feat(release): 发行 v{ver}——版本号 + CHANGELOG + 变更记录 + 发行说明 + preview 清单`，
  正文分条列本次内容。
- 发行提交 = 单提交含全部同步文件（本次 10 文件）；**工作树必须干净才 buildPlugin**，
  否则半成品混进 zip。
- 标签名 = `gradle.properties` 版本号逐字（含 `-beta.N`），不带 `v` 前缀；
  `git tag {ver} && git push origin master && git push origin {ver}`。
- **打包后先自检产物再建发行版**：`python dev-support/inspect-plugin-zip.py --version {ver}`
  核内层主 jar 名带版本号、`plugin.xml` 的 `<version>`、`MANIFEST.MF` 的 `Version`、
  `<change-notes>` 首个 `<h2>` 即本版且该块逐条扫「（ ） ：」零命中（任一不过退出码 1）。
  需要证明「某修复确实进了产物」时，加 `--disasm {FQCN} --require-present {符号} --require-absent {符号}`
  反汇编内层 jar 里的类做符号断言——比看提交自述可靠（2026-10-08 实证：靠它确认
  `RecentExecutionsDialog` 已无 `getChildren` 调用）。
- **发行后源码树又变了怎么办**（改了 `src/main`、或修了门禁报项）：已发的 zip 就不含新代码，
  走重发——重打 zip → 新一笔发行提交 → **本地轻量 tag 前移**（`git tag -f {ver}`）＋
  `git push -f origin {ver}` → `gitee-release.py … --replace`。**tag 前移不会删发行版**
  （2026-09-24 与 2026-10-08 两度实证：force update 只是原地改指向，Gitee 仅在 ref 被删除时
  才联动删发行版），改完记得回读 `target_commitish` 是否落到新提交。
  标签先推远端，Gitee API 建发行版时才能引用。

### 已发布段准入约定

已发布段（`## [{版本}] - {日期}` 块）只写该版本产物里真实存在的内容，判据是**产物**而不是提交：
条目描述的能力必须能在该版本 tag 的树里找到落点。晚于 tag 落盘、只在 dev 上、或从未进过任何
发行包的内容，一律留在 `[Unreleased]`，等真随某次发行交付后再随发行块转正。本节是「范围核算」
节的镜像——那边管「tag 之前的提交是否都算本版」，本节管「已发布段的文字是否都算本版」。

- **合并 dev 的 `[Unreleased]` 块进已发布段时，逐条核对产物**。dev/master 双向合并会把 dev 上
  累积的未发行块折进已发布段：2026-09-18 事故即此形态——`319fae9` 把 dev 的 `[Unreleased]` 折入
  `[2026.9.18]`，12 行 MCP 条目写进了一个产物里根本没有 MCP 的版本块（而该版本其实从未发布过，
  三处都没有）。核对手段按条目的路径特征选，如 MCP 能力线：
  `git ls-tree -r --name-only <tag> | grep -c "^src/main/kotlin/.*/mcp/"`（`mcp-optional.xml` 同理）；
  条目没有路径特征时，用能力入口类名或 EP 名在 tag 树里 grep 一次，取不到就不许留在已发布段。
- **发行前确认 tag 真的建了**。发行提交（版本号 + CHANGELOG + 八处同步）只是「准备发行」，
  没有 `git tag` 就没有产物，Gitee 发行版与 Marketplace 也无从建起。历轮把发行提交 `1420673`
  读成「Gitee 已发 v2026.9.18」，断供两周无人发现。`git tag -l {版本号}` 与
  `git ls-remote --tags origin` 两处都查得到，才算这个版本存在（总流程第 ⑤ 步不可跳）。
- **已发布段的失实无法靠改文档消掉**：版本真发出去之后，段内文字会随 changeNotes 与 Gitee
  发行版正文一起公开。发现失实只能在下一个发行块里如实说明，历史段不改写——历史段是发行记录，
  不是待修文本。

## 三、Gitee API 发布（本次实证端点与参数）

> **一键脚本（2026-10-05 起）：`dev-support/gitee-release.py`**——把本节三步（建发行版 → 传附件 → 回读校验）
> 串成一条命令，动态内容全走参数：
>
> ```bash
> # 首次发布
> python dev-support/gitee-release.py --version 2026.10.6 \
>     --notes docs/发行说明/20260930-2213-嵌入实体识别与SQL日志屏蔽-发行说明.md
> # 内测通道：加 --prerelease，标题显式传「🎉 SQL Visualizer v{ver} 内测版」
> # 重发（同 tag 已有发行版、源码树变了要换附件）：--replace 先删后重建
> python dev-support/gitee-release.py --version 2026.10.9 --notes docs/发行说明/xxx.md --replace
> # 只回读校验现有发行版（含附件 sha256 与本地 zip 逐字节比对）
> python dev-support/gitee-release.py --version 2026.10.9 --verify-only
> # 只删发行版（不动远端标签）
> python dev-support/gitee-release.py --version 2026.10.9 --delete-only
> ```
>
> 前置：`gitee-token.json` 在 `.local/`（机器本地目录，2026-10-10 起由仓库根迁入）、`buildPlugin` 已产 zip、远端标签 `{version}` 已 push。
> `--replace` 按 `GET /releases/tags/{tag}` 查旧版后 `DELETE` 再重建（Gitee 无编辑接口，换附件
> 只能删了重建；同名附件下载 URL 不变）；`--delete-only` 只删发行版、**不动远端标签**——删标签
> 才连坐删发行版，所以换附件时**不要**去删标签。建版流程里的附件 sha256 比对只告警（同名附件
> 重传后 CDN 可能仍缓存旧包），发布后隔一会儿用 `--verify-only` 复查，那里不一致是硬失败。
> 下方为端点级明细与坑位（脚本已规避，排障时对照）。

鉴权一律 `access_token` 参数（Personal Access Token，需 projects 权限）。
令牌存 `.local/gitee-token.json`（机器本地目录 `.local/`，整目录 gitignore、仅 README 白名单入库；2026-10-10 起由仓库根迁入，字段 `gitee_personal_access_token`/`owner`/`repo`；
严禁提交/外传），脚本读取：
`TOKEN = json.load(open('.local/gitee-token.json', encoding='utf-8'))['gitee_personal_access_token']`。
以下 `{owner}/{repo}` = `songrenshuo/mybatis-plus-sql-visualizer`。

### 1. 建发行版

```
POST https://gitee.com/api/v5/repos/{owner}/{repo}/releases
```

| 参数 | 值 | 备注 |
|------|-----|------|
| `tag_name` | 完整版本号（含 `-beta.N`） | 远端标签必须已存在 |
| `target_commitish` | `master` | |
| `name` | `🎉 SQL Visualizer v{ver}`（内测版加「 内测版」后缀） | 沿用历次标题风格 |
| `body` | 发行说明 md 全文 | **参数名是 `body`，不是 GitHub 的 `description`** |
| `prerelease` | `'true'` | 即勾选「这是一个预览版本」 |

**坑位实录**：传 `description` 报 400 `{"messages":["body is missing"]}`——Gitee API 与
GitHub API 在此参数名上不兼容，网上抄的 GitHub 式脚本直接翻车。

成功响应里取 `id`（本次 1139592），上传附件要用。

### 2. 上传 zip 附件

```
POST https://gitee.com/api/v5/repos/{owner}/{repo}/releases/{id}/attach_files
Content-Type: multipart/form-data
```

multipart 两个字段：`file`（zip 二进制，filename 必须等于
`mybatis-plus-sql-visualizer-{ver}.zip`，即附件名直接决定下载 URL 尾段）+ `access_token`。
成功返回 201 与附件信息。

**坑位实录**：`/upload_files`（社区文章流传）与 GitHub 风格 `POST /releases/{id}/assets`
实测均 404，只有 `attach_files` 可用；`PUT /upload_files` 也 404。不要再试。

附件下载地址形如：

```
https://gitee.com/{owner}/{repo}/releases/download/{tag}/mybatis-plus-sql-visualizer-{ver}.zip
```

### 3. 回读校验（必做）

```
GET https://gitee.com/api/v5/repos/{owner}/{repo}/releases/{id}
```

断言三件事：`prerelease == True`；`assets` 里存在插件 zip（另两个 tag 自动归档 zip/tar.gz
是 Gitee 自带的，不算）；**附件 `browser_download_url` 与 `preview.update.json` 的
`downloadUrl` 逐字一致**——这是自更新通道能否命中包体的唯一保证。

**再加一层 sha256 对账（2026-10-08 起，脚本默认带）**：URL 串一致只证明「路径对」，
证明不了「线上下发的就是本地这一份」——把线上附件下回来与 `build/distributions/` 同名 zip
比 sha256 才算坐实（同名附件重传后尤其要看这里，CDN 可能仍在发旧包）。
`gitee-release.py --verify-only` 就是这层检查，不一致退出码 1。

### 4. 发行版补建与列表排序（2026-09-12 补建 2026.9.11 实证）

- **列表页排序 = 发行版创建时间倒序**（非标签名、非提交日期）。所以「补建的历史版本」
  一定排在既有版本之下——若要让某个后建的发行版仍显示在上，只能**删除后重建**该发行版
  （`DELETE /releases/{id}` 只删发行版，标签保留），再按第三节重新 POST + 传附件；
  重建后附件文件名不变即下载 URL 不变，update 清单无需改动。
- **`PUT /releases/{id}` 不支持**（405 Method Not Allowed），无法靠「编辑」刷新排序位置。
- 补建 zip 若与当前 master 内容有差（如历史版本落后若干提交），在 `gradle.properties`
  临时改版本号 `./gradlew buildPlugin -x test`，产物内层 jar 的 plugin.xml `<version>`
  由 patchPluginXml 注入，须回读 zip 确认；打完包把版本号改回，工作树不留痕。

### 5. 常见坑速查

| 症状 | 原因 / 处置 |
|------|-------------|
| 400 `{"messages":["body is missing"]}` | 描述参数用了 `description` → 改 `body` |
| 404 `upload_files` / `assets` | 端点不存在 → 用 `attach_files` |
| 405 `PUT /releases/{id}` | Gitee 不支持编辑发行版 → 删除重建 |
| 同 tag 再建报 400「该标签已经存在发行版」 | 已有发行版 → `gitee-release.py … --replace`（先删后重建） |
| 同名附件重传后线上仍是旧包 | CDN 缓存 → 稍后用 `--verify-only` 复查 sha256 |
| 补建版本显示在列表下方 | 列表按创建时间倒序 → 见 §4 删除重建 |
| 自更新收不到通知 | downloadUrl 与附件 URL 尾段不一致 |


### 附：Python 无依赖参考实现

> 正式脚本见 `dev-support/gitee-release.py`（已含参数化、错误前置校验、回读断言）；
> 下方为最小原理片段，供无脚本环境下手工照做。

```python
import json, os, urllib.request, urllib.parse

TOKEN = json.load(open('.local/gitee-token.json', encoding='utf-8'))['gitee_personal_access_token']
BASE = 'https://gitee.com/api/v5/repos/songrenshuo/mybatis-plus-sql-visualizer/releases'

# 1) 建发行版（body 参数名！）
data = urllib.parse.urlencode({
    'access_token': TOKEN, 'tag_name': VER, 'target_commitish': 'master',
    'name': f'🎉 SQL Visualizer v{VER} 内测版', 'body': release_notes_md,
    'prerelease': 'true'}).encode()
resp = json.load(urllib.request.urlopen(urllib.request.Request(BASE, data=data)))
rid = resp['id']

# 2) 上传附件（attach_files 端点！）
zip_path = f'build/distributions/mybatis-plus-sql-visualizer-{VER}.zip'
boundary = '----UploadBoundary'
body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
        f'filename="{os.path.basename(zip_path)}"\r\nContent-Type: application/zip\r\n\r\n'.encode()
        + open(zip_path, 'rb').read() + b'\r\n'
        + f'--{boundary}\r\nContent-Disposition: form-data; name="access_token"'
          f'\r\n\r\n{TOKEN}\r\n--{boundary}--\r\n'.encode())
req = urllib.request.Request(f'{BASE}/{rid}/attach_files', data=body, method='POST')
req.add_header('Content-Type', f'multipart/form-data; boundary={boundary}')
urllib.request.urlopen(req, timeout=180)  # 201

# 3) 回读校验
r = json.load(urllib.request.urlopen(f'{BASE}/{rid}?access_token={TOKEN}'))
urls = {a['browser_download_url'] for a in r['assets']}
assert r['prerelease'] and manifest_download_url in urls
```

## 四、发行后收尾

1. `parallel_tasks.py done --name <任务名>` 注销登记。
2. 人工步骤（**仅用户本人执行，AI 会话一律不得代改**）：`preview.update.json` 逐字节拷入 `update/update.json`（先 sha1 校验一致），
   独立单文件提交随官网推送，**不进发行提交**。会话的发行职责止于建发行版 + 回读校验，收口报告只写「update.json 待用户手改」。
3. 核对 Gitee 发行版页面：预览标记、附件可下载、正文渲染正常。
4. 若为 beta 系列：下一发 beta 重复本流程，changeNotes 块替换为本发内容（累计）。

## 五、GitHub 触发发布后的 Gitee 同步（--from-github）

**定位**：Gitee 仍是主仓，GitHub 只是镜像侧的构建与发行通道。GitHub 上的
`.github/workflows/build-plugin.yml` 只往 GitHub 写东西（无 Gitee 凭据、`persist-credentials: false`），
落到 Gitee 这一步永远由人手动执行：

```bash
python dev-support/gitee-release.py --version <版本> \
  --notes docs/发行说明/<那份>-发行说明.md --from-github
```

脚本做四件事，其余流程与手工发行完全一致：

1. 按 tag 查 GitHub Release，取**同名附件**（`mybatis-plus-sql-visualizer-<版本>.zip`）下载到
   `build/distributions/`——不重新打包，附件就是门禁验过的那一份。本地已有同名文件且哈希一致时直接复用，
   哈希不同则拒绝覆盖。
2. 沿用 GitHub 侧的预发布标记（`-beta.N` 版本自动是预发布）。
3. 检查 tag 是否已在 Gitee（未推会 400），只检查不自动推送。
4. 打印版本/附件/哈希/正文/Gitee 链接摘要，**要求人工确认**：终端里输入 `yes` 才继续；
   非交互环境必须显式 `--yes`（这是有意的二次确认，防止误把副本传上去）。

前提与注意：

- GitHub Release 必须已存在且非草稿（Actions 跑 `mode=release` 或 `mode=publish`）。
- tag 必须先双推：`git tag <版本> <目标提交>` → `dev-support/push-all.ps1 -Tags`。
  这样两端 tag 指向同一提交，流水线的 `gh release create` 会挂到既有 tag 上。
- **不动 `update/update.json`**：内测/自更新通道的数据源仍由本地 `writeUpdateManifest` + 手补 changeNotes
  维护，行为与本流程上线前一致。
- 反向（Gitee 已有 → 想补一份到 GitHub）没有对应模式；流水线的 `publish` 只复用 **GitHub 侧**的产物。

## 相关文档

- [版本号规范-说明文档.md](./版本号规范-说明文档.md) — 基号/内测后缀/标签规则
- [Marketplace发布流程-说明文档.md](./Marketplace发布流程-说明文档.md) — 官方市场上架（beta 转正时走）
- 各期 `-发行说明.md` — 每期范围与门禁计数实录
