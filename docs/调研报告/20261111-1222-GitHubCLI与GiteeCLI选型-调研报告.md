# GitHub CLI（gh）与 Gitee CLI（gitee-cli）选型调研

- 任务性质：**gh 已本机安装并验证**；**gitee-cli 只查官方资料，未安装、未登录、未执行任何命令**
- 调研时间：2026-11-11 12:22
- 触发：用户问「gh cli 推荐安装吗」→ 安装并固化；再问「gitee 有没有 cli」→ 调研后用户定「先不装 gitee 的」
- 一句话结论：gh 值得装——已装 2.103.3，认证走 `GH_TOKEN` 注入（本仓 PAT 缺 `read:org`，`gh auth login` 会被拒），包装固化在 `dev-support/gh.ps1`（`8dcb668f`），用于 CI 与发行观测；Gitee 有官方 `oschina/gitee-cli`（v0.3.3），但本仓 Gitee 是镜像、无流水线、发行链已有 Python 脚本，暂不引入。

---

## 一、结论与落地状态

| 工具 | 官方性 | 本机状态 | 本仓用途 | 结论 |
|---|---|---|---|---|
| GitHub CLI（`gh`） | GitHub 官方（cli/cli） | **已装 2.103.3**（winget MSI） | 看 Actions（`run list` / `run watch --exit-status` / 日志与产物）、发行与 PR 观测 | 装，已固化包装 |
| Gitee CLI（`gitee`） | Gitee 官方（`oschina/gitee-cli`，MIT） | 未安装 | 若要用 Gitee 侧 PR/issue/Release/pipeline 终端操作 | **暂不装**（用户 2026-11-11 定） |

本仓既有的双仓工具链（本轮未改动）：`dev-support/push-all.ps1`（Gitee 主仓 + GitHub 镜像推送，令牌通路、禁 GCM）、`dev-support/gitee-release.py`（Gitee 发行版创建，含 `--from-github` / `--verify-only`）、`dev-support/mirror-releases-to-github.py`（发行镜像与缓存）。

---

## 二、GitHub CLI（`gh`）

### 2.1 能力面

`gh` 覆盖仓库、Issue、PR、Release、Actions、Gist、`api` 原始调用等。对本仓实际有用的是 Actions 与 Release 两条：

- `gh run list --branch master`：看最近几轮推送测试的状态、commit、耗时、run id；
- `gh run watch <id> --exit-status`：阻塞到该轮结束，绿返回 0、红返回非 0，可作脚本判据；
- `gh run view <id> --json ...`：取 job 级结论（本轮用它确认 8 个 job 全绿）；
- `gh release view` / `gh release list`：核对 GitHub 镜像发行版与 tag。

### 2.2 本机安装与认证（实测）

安装（winget，用户级无需手工下载）：

```
winget install --id GitHub.cli --source winget --accept-source-agreements --accept-package-agreements --silent
```

读数：`GitHub CLI 2.103.3`，MSI 落 `C:\Program Files\GitHub CLI\gh.exe`；winget 同时把该目录写进机器 PATH（新开的终端可直接 `gh`，当次会话仍是旧 PATH，包装脚本因此带安装路径回落）。

认证遇到一个硬限制并已绕过：

- 本仓 PAT（`.local/github-token.json` 的 `github_personal_access_token`）作用域是 `gist` / `repo` / `workflow`，**没有 `read:org`**；
- `gh auth login --with-token` 会做作用域强校验，直接报 `error validating token: missing required scope 'read:org'`；
- 改用进程级 `GH_TOKEN` 注入后可正常使用，`gh auth status` 显示 `Logged in to github.com account SongRenShuo (GH_TOKEN)` + `Missing required token scopes: 'read:org'`（仅警告，不影响读 Actions/Release）。

令牌未进入命令行 argv、未写入 keyring；`gh auth setup-git` **刻意没有执行**——它会改 git 的 `credential.helper`，与仓内「禁裸 push、禁 GCM 弹窗」纪律冲突。推送路径仍只有 `push-all.ps1`。

### 2.3 固化形态

- `dev-support/gh.ps1`（`8dcb668f`）：读 `.local/github-token.json` → `GH_TOKEN` → 定位 `gh`（PATH 优先，回落 `Program Files\GitHub CLI\gh.exe` 与 winget Links）→ 透传全部参数；文件带 UTF-8 BOM（PS 5.1 解析中文注释的既有定例，见 `docs/LESSONS.md`）。
- `.local/gh.sh`：Git Bash 薄包装，一行转发到上面的 ps1（单一事实源，不入仓）。

用法：

```
powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/gh.ps1 run list --branch master
powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/gh.ps1 run watch <id> --exit-status
bash .local/gh.sh run view <id> --json conclusion,headSha,jobs
```

### 2.4 首次实测读数（2026-11-11）

- 本轮合流的推送测试 run `42955648280`（`0ff8cf29`）被后一次 push 按并发组**取消**；
- 覆盖合流提交的最新一轮是 `42956540847`（`6ad04dd2`，`0ff8cf29` 在其历史里）：**success**，耗时 10m32s；job 级 8/8 success——`fast (1)`、`fast (2)`、`test (1)`~`test (5)`、`nightly-nudge`；
- 两条 annotation 均非缺陷：`nightly-nudge` 提示「本轮已有夜间轮，跳过兜底派发」；GitHub 提示 `ubuntu-latest` 标签 2026-10-19 起迁移到 Ubuntu 26。

这条现场事实也说明：**push 之后要 watch 的是「最新一轮」**，按提交 hash 找自己那轮可能已被并发组取消。

---

## 三、Gitee CLI（`gitee-cli`）

### 3.1 官方性与能力面（文档，未本机验证）

- 仓库：`gitee.com/oschina/gitee-cli`，Gitee「开源中国」组织下，MIT，README 版权署名 Gitee，可视为官方；
- 形态：Go 编写（源码构建需 Go 1.25+），二进制名 `gitee`；
- 命令组：`pr`、`issue`、`repo`、`release`、`ssh-key`、`search`、`api`（原始 API 调用）、`ai`、`pipeline`（Gitee Go 流水线：list/view/run、build 状态、stage/job 重试与取消）；
- 版本：官方仓库最新发行版 `v0.3.3`（提交「docs: prepare v0.3.3 release」）。

### 3.2 安装与认证

- 安装：macOS/Linux 走 `scripts/install.sh`（校验 SHA-256）；**Windows 主要走 npm**：`npm install -g @gitee/gitee-cli`（按平台装对应二进制）；
- 认证：Personal Access Token；`gitee auth login` 或 `printf '%s' "$GITEE_TOKEN" | gitee auth login --with-token`；
- 凭证落点：默认 `~/.config/gitee/credentials.yml`；私有化实例令牌在 `~/.config/gitee/hosts.yml`。

### 3.3 本仓暂不引入的三条理由

1. **Gitee 在本仓是镜像，不是作业面**：CI 全在 GitHub Actions（push-test / nightly / build-plugin 三支），Gitee 侧没有流水线，`pipeline` 命令组零用途。
2. **发行链已有成熟脚本**：`gitee-release.py` 处理了 `--from-github` 下载、`--verify-only` 硬校验、正文 PATCH 必须带 `tag_name`+`name`、不传 `prerelease` 会被静默重置等一批踩坑；换 CLI 等于把这条链做成两份。
3. **多一份凭据存储**：CLI 会在 `~/.config` 另存一套 token，而仓内已有 `.local/gitee-token.json`（`push-gitee.ps1` 与发行脚本在用），两套凭据的轮换与失效口径会分叉。

### 3.4 何时重新评估

- 需要在终端处理 Gitee 侧 PR / Issue（例如 Gitee 成为主要协作面）；
- 真要用上 Gitee Go 流水线（当前没有）；
- 决定用 CLI 取代 `gitee-release.py`（届时要先比对 API 覆盖与既有坑，见 `pitfall-gitee-release-body-patch`）。

---

## 四、证据分级与未验证项

| 档 | 内容 |
|---|---|
| **实测** | gh 2.103.3 安装与路径；`auth status` 的作用域读数；`gh auth login --with-token` 的 `read:org` 报错；`run list` / `run view` 的 8 job 结论；`run watch --exit-status` 退出码；包装脚本 `dev-support/gh.ps1` 与 `.local/gh.sh` 的参数透传 |
| **文档推断（未本机验证）** | gitee-cli 的命令面、v0.3.3 版本、npm 包名、`credentials.yml` 路径、安装脚本行为——均来自官方仓库 README、pkg.go.dev 与 npm 页面，未下载二进制、未登录、未执行命令 |
| **待验证前提** | ① `gitee-cli` 在 Windows Git Bash 下的二进制落点与 PATH 行为；② 是否原生支持 `GITEE_TOKEN` 环境变量（README 只写了 `auth login --with-token`，未提环境变量）；③ 其 `release` 与 `api` 命令对 Gitee 发行版正文/`prerelease` 字段的覆盖是否与 `gitee-release.py` 等价 |

---

## 五、参考

- GitHub CLI 官方仓库：<https://github.com/cli/cli>
- Gitee CLI 官方仓库：<https://gitee.com/oschina/gitee-cli>
- Gitee CLI（pkg.go.dev）：<https://pkg.go.dev/gitee.com/oschina/gitee-cli>
- `@gitee/gitee-cli`（npm）：<https://www.npmjs.com/package/@gitee/gitee-cli>