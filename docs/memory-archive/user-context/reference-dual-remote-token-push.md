---
name: reference-dual-remote-token-push
description: 双仓（Gitee 主仓 origin / GitHub 镜像 github）非交互推送的令牌写法，绕开 Git Credential Manager 弹窗
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_5abdc5bc-7142-48cc-a6f8-3d8379788c57
---

本仓 `origin` = Gitee、`github` = GitHub，且 `credential.helper` 指向 Git Credential Manager（会弹 GUI 抢焦点）。2026-11-12 两条令牌写法实测都通：

- GitHub：`git -c credential.helper= push "https://x-access-token:<token>@github.com/SongRenShuo/mybatis-plus-sql-visualizer.git" master`，token 取 `.local/github-token.json` 的 `github_personal_access_token`。
- Gitee：同法换成 `https://<owner>:<gitee_token>@gitee.com/songrenshuo/mybatis-plus-sql-visualizer.git`，字段是 `.local/gitee-token.json` 的 `gitee_personal_access_token` + `owner`（owner 就是 SongRenShuo）。
- 两条都带 `GIT_TERMINAL_PROMPT=0` 与 `-c credential.helper=`（空 helper＝彻底绕开 GCM，认证失败会硬报错而不是弹窗把命令挂住）；输出用 `sed` 把 token 掩成 `***`。
- `dev-support/push-all.ps1 -Branch <分支>` 是等价正路（内部同样用令牌 + 代理回落），但 Gitee 段是裸 `git push origin`、不带 `-c credential.helper=`，可能落到 GCM。2026-11-12 实测 `-Branch master` 两段都 OK（GitHub 段探测代理不通时自动回落直连，日志出现 `x-access-token:***@`、全程无弹窗）。

**推第二个仓（demo 仓）时踩到的两个坑（2026-11-12，都被远端挡下、零损害）**：

1. **别拿凭据文件里的 `owner/repo` 字段拼 URL 当目标仓**。demo 仓的 `.local/gitee-token.json` / `github-token.json` 是从插件仓整体拷过来的，`repo` 至今还写着 `mybatis-plus-sql-visualizer`——按它拼 URL 会把 **demo 的提交往插件仓推**；那次只因远端有更新被 `! [rejected] (fetch first)` 拦下才没出事（force push 就是灾难）。**仓库名必须写死成本仓真实名字**，与凭据文件解耦。
2. **推送脚本只写 ASCII**。PowerShell 5.1 对无 BOM 的 `.ps1` 按 ANSI 解码，中文注释会把**下一行赋值吃掉**——`$repoName = '...'` 被吞后 URL 变成 `https://gitee.com/<owner>/.git/`，返回 403 `The token is not authorized to access this project`，报错文案完全不指向「变量为空」。同一轮的对照：注释译成英文后同脚本 `30932a0..7689f60` 双仓正常推上。
   ⇒ 推送脚本三件套：仓名常量 + 回显目标 URL（token 打码）+ 纯 ASCII 注释。

关联：[[project-nightly-local-mysql-20261112]]、[[project-preview-openperf-m2-e2e-20261112]]
