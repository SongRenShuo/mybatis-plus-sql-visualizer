# `.local/` — 机器本地文件目录

本目录存放**只属于当前机器**的文件：凭据、令牌、本机路径覆盖、临时本地配置。

## 约定

- **整目录被 git 忽略**（`.gitignore` 的 `.local/*`），**唯一例外是本文件**（`!.local/README.md` 白名单入库）——所以你在仓库里能看到这份说明，但看不到同目录下的任何真实内容。
- **新增机器本地文件直接放这里即可，不需要改 `.gitignore`**。这是本目录存在的意义：此前凭据类文件散落仓库根，新增一个就得多配一条忽略规则，容易漏。
- 注意 git 规则：父目录被整排除时无法再包含子文件，所以忽略规则必须是 `.local/*` + `!.local/README.md` 这两条；不要改成 `.local/`（那样本文件也会被忽略）。

## 当前清单

| 文件 | 用途 | 必填 |
|---|---|---|
| `gitee-token.json` | Gitee 发行 Personal Access Token（字段 `gitee_personal_access_token` / `owner` / `repo`），供 `dev-support/gitee-release.py` 读取 | 仅发布到 Gitee 时需要 |
| `github-token.json` | GitHub Personal Access Token（字段 `github_personal_access_token` / `owner` / `repo` / `login`，scope 需 `repo`），供 `dev-support/push-all.ps1` 推送镜像仓 | 仅推送 GitHub 镜像时需要 |
| `local.env` | 本机路径与代理覆盖（键名见 `local.env.example`），供 `dev-support/` 下的脚本 source | 不需要（缺省时脚本自行发现） |

## 迁移说明

`gitee-token.json` 原在仓库根，2026-10-10（T-RepoLayout）迁入本目录。

- `dev-support/gitee-release.py` 已改为**双路径回退**：先读 `.local/gitee-token.json`，读不到再回退根目录旧路径。已配置好的机器不因本次迁移中断。
- 根目录的 `gitee-token.*` / `*.token` / `.secrets/` 三条忽略规则**保留**作安全网——即便有人误放回根目录或别处，仍不会误提交。

## 相关

- `.gitignore` / `.zcodeignore` 的「机器本地文件」段
- `docs/开发计划/20261010-1407-T-RepoLayout-仓库目录结构优化与本地凭据收拢-开发计划.md`
- `docs/开发计划/20261010-1414-T-LocalEnvOverride-本机路径与代理归口local-env-开发计划.md`
