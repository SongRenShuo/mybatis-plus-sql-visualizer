---
name: pitfall-commit-identity-wrong-email-owns-attribution
description: 提交身份填错邮箱会把提交归属到别人账号——coolrequest-vip 渠道仓误用上游作者邮箱
  2606710413@qq.com，Gitee 显示 hxl495（听风逝夜）；本人身份是 SongRenShuo
  <3064597532@qq.com>，修法＝--reset-author + 强推 + 仓内写死 user.email
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_cddb53d3-1842-447e-a693-e38c5fbee3e5
---

# 提交邮箱选错 → 提交被归属到别人账号（2026-11-10 coolrequest-vip 实证）

## 症状

用户看到渠道仓的提交「身份是听风逝夜，不是 SongRenShuo」。本地 `git log --format='%an <%ae>'` 显示
`SongRenShuo <2606710413@qq.com>` —— **用户名一直是对的，错在邮箱**。

## 根因

`2606710413@qq.com` 是 coolrequest-vip **上游作者（houxinlin）写在 plugin.xml vendor 里的邮箱**，
建渠道仓提交时直接抄了它。Gitee/GitHub 都按**邮箱**匹配账号，于是提交挂到 `hxl495`（昵称「听风逝夜」）
名下，网页上头像与昵称都是那个账号。

判据（别只看 `%an`）：`GET /repos/{owner}/{repo}/commits?per_page=1` 同时返回
`commit.author.{name,email}` 与**解析出的** `author.login/author.name`——后者才是网页显示的身份。

## 正确身份与修法

- 本人身份：**`SongRenShuo <3064597532@qq.com>`**（以主仓 `git config user.email` 与近若干提交为准）。
- 修历史（新建仓、无协作者时才可强推）：
  `git commit --amend --no-edit --reset-author` → `git -c credential.helper= push -f "<token URL>" master`。
- 修未来：在该仓写死本地配置 `git config user.name "SongRenShuo"` / `git config user.email "3064597532@qq.com"`，
  别每次靠 `-c` 临时指定（本项目 `-c user.email` 也漏过一次）。
- 复核：再次打 commits API，确认 `author.login` 已变成 `songrenshuo`。

## 规矩

在**别人的插件仓**（上游源码 + 自建渠道仓）里提交时，身份必须来自自己的 git 配置，
**绝不能从源文件（plugin.xml vendor / gradle 脚本 / LICENSE）里抄邮箱**——那些是上游作者的联系方式。

相关：[[project-coolrequest-vip-update-channel-port]]