---
name: reference-github-api-token-authoritative-readings
description: 竞品调研里 GitHub Release/stars/tags 权威读数一律走 .local/github-token.json
  项目令牌（Bearer + curl -x 代理），禁 git credential fill；20261110 zMyBatis 三端点全 200 实锤
  0/0/1、releases=[]、tags=[]，连续三轮「匿名额度耗尽缺读数」销案
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_3111cf66-9bce-4284-ae1a-46c00894655d
---

竞品横向对比报告里凡涉及 GitHub 侧权威读数（Release 数、stars/forks/watchers、tag 清单），**用仓内 `.local/github-token.json` 的 `github_personal_access_token` 直查 REST API**，不要再以「api.github.com 匿名额度被共享出口 IP 耗尽」为由留缺读数。

**Why**：r35/r36 两轮 zMyBatis 六信号里的 ② GitHub Release、③ stars 都因匿名 API 403 记作「缺权威读数、大概率推断」，2026-11-10 用户令「用我的 githubtoken 解决」后三端点全部 HTTP 200 拿到实锤——推断再合理也不等于读数。令牌通路同时避开 GCM GUI 凭据弹窗（与 [[project-dual-repo-github-mirror]] 第 10 条同源）。

**How to apply**：

```bash
TOKEN=$(python -c "import json;print(json.load(open('.local/github-token.json',encoding='utf-8'))['github_personal_access_token'])")
curl -sS -x http://127.0.0.1:7890 -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/luceat-lux-vestra/zMyBatis/releases?per_page=100"
```

- 三端点：`/repos/{owner}/{repo}`（stars/forks/subscribers/created_at/pushed_at/default_branch）、`/releases?per_page=100`、`/tags?per_page=100`；`[]` 就是 0 个，不是端点失败。
- **禁 `git credential fill`**（会弹 GCM 抢焦点）；令牌只从文件读入变量，不写进命令行历史；提交前不回显令牌值（只 `print(list(d.keys()))`）。
- Git Bash 的 `/tmp` 与 Windows 临时目录不共通：`curl -o /tmp/x.json` 后 `python open('/tmp/x.json')` 会 FileNotFoundError；一律用绝对 Windows 路径如 `C:/Users/USER/AppData/Local/Temp/mpsv-r37-gh/`。
- 原始响应留档路径写进报告 §11 核读与口径注（r37 新增口径㉛），后续轮次直接沿用。

**20261110 实测读数（zMyBatis 真仓 `luceat-lux-vestra/zMyBatis`）**：releases `[]`、tags `[]`（与 `git ls-remote --tags` 双路互证）、stars/forks/watchers=**0/0/1**、created `2026-02-28T13:22:06Z`、pushed `2026-11-08T21:42:25Z`、default_branch `main`。r35 记的 0/0/1 与 0 Release 由此拿到权威复核。三端点全 200，代理 `-x 127.0.0.1:7890` 一次通。

相关：[[project-dual-repo-github-mirror]]（令牌文件位置/字段、推送与 API 都别碰 credential helper）、[[project-competitive-round36-report]]（若已建档）、[[feedback-subagent-quota-recovery]]（调研型子代理只留给多请求网络路）。
