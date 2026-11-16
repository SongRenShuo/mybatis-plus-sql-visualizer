# GitHub Actions 工作流运行邮件标题自定义调研

- 任务性质：只读调研，未修改任何代码与 CI 配置
- 调研时间：2026-11-11 10:19
- 触发：用户希望在 CI 通知邮件标题里直接看到本轮是绿还是红（QQ 邮箱收到的「[SongRenShuo/mybatis-plus-s…] 测试（自动） workflow run …」标题不带结论）
- 一句话结论：**GitHub 内置的工作流运行邮件标题无法自定义**；想在标题里带结论，只能由工作流自己发一封。方案已给出，用户明确「先不做」，本次仅记录结论。

---

## 一、内置通知能改什么、不能改什么

官方 Managing GitHub Actions notifications 全文只讲投递方式。设置页路径为账号级 Settings → Notifications → System → Actions，可选项只有三个：

> To opt in to web notifications, from the dropdown menu, select "On GitHub."
>
> To opt in to email notifications, from the dropdown menu, select "Email."
>
> Optionally, to only receive notifications for failed workflow runs, from the dropdown menu, select "Only notify for failed workflows", then click Save.

即：开/关邮件、或者只收失败的那封。没有主题模板、没有变量插值、没有 webhook 或 API 能改标题。严格说官方没有一句明文写「标题不可改」，结论来自「设置面只有投递方式与失败过滤两类开关，全站找不到标题模板配置」；GitHub Community 与 Marketplace 也未见内置自定义入口。实施前若设置页新增相关开关，需重新确认这一点。

补充两点：

- 该设置是**账号级**的，不是仓库级。关掉内置邮件只影响自己，不动其他人的通知。
- 截图里标题跟的是工作流的 `name`（`推送测试（自动）`），不是 `run-name`。`push-test.yml:26` 另有 `run-name: 推送测试 · ${{ github.ref_name }} @ ${{ github.sha }}`，邮件并未采用它——说明标题由 GitHub 按固定模板拼装，与仓库里的显示名配置无关。

本仓在役的三个工作流（用项目令牌调 GitHub API 核对 `actions/workflows`，返回 3 个 active，与本地文件一致）：

| 工作流 | 文件 | 触发 |
|---|---|---|
| 推送测试（自动） | `.github/workflows/push-test.yml` | push master |
| 夜间全量（自动） | `.github/workflows/nightly-full.yml` | schedule + workflow_dispatch |
| 插件门禁与发行（手动） | `.github/workflows/build-plugin.yml` | workflow_dispatch |

三个文件里都没有 notify / mail / smtp / subject 相关步骤（全仓 grep 仅命中 `build-plugin.yml:696` 的一处「结论」文案，与发信无关），即目前没有自定义通知通道。

## 二、能做到「标题带红绿」的做法（未实施）

思路：新增一个独立的 `workflow_run` 工作流，监听上述三个工作流完成，读上游 `github.event.workflow_run.conclusion` 自己拼 subject，用 SMTP 发信。

```yaml
name: CI 结论邮件

on:
  workflow_run:
    workflows:
      - 推送测试（自动）
      - 夜间全量（自动）
      - 插件门禁与发行（手动）
    types: [completed]

permissions:
  contents: read

jobs:
  mail:
    runs-on: ubuntu-latest
    steps:
      - uses: dawidd6/action-send-mail@v3
        with:
          connection_url: ${{ secrets.CI_MAIL_CONNECTION }}
          subject: "${{ github.event.workflow_run.conclusion == 'success' && '✅ 绿' || '❌ 红' }} | ${{ github.event.workflow_run.name }} No.${{ github.event.workflow_run.run_number }} | ${{ github.event.workflow_run.head_branch }}"
          to: ${{ secrets.CI_MAIL_TO }}
          from: CI-Bot <${{ secrets.CI_MAIL_FROM }}>
          body: |
            结论：${{ github.event.workflow_run.conclusion }}
            详情：${{ github.event.workflow_run.html_url }}
```

标题形如 `✅ 绿 | 推送测试（自动） No.27 | master`。

`connection_url` 是 dawidd6/action-send-mail 支持的写法：用一个 URL 把 SMTP 服务器、端口、账号和凭据合成单个 secret；也可以拆成服务器地址、端口、账号、凭据四个独立输入，README 均已列明。QQ 邮箱这里要用邮箱后台生成的 SMTP 专用凭据，不是网页登录密码。

为什么用独立的 `workflow_run`，而不是在三个工作流末尾各加一对 `if: success()` / `if: failure()` 的发信 step：job 被取消、超时、runner 中途挂掉时，末尾 step 根本不会执行，那几轮恰好是最需要知道结论的。`workflow_run` 在上游运行结束后才触发，`conclusion` 已定稿，cancelled / timed_out / startup_failure 都能覆盖。代价是每轮 CI 多一个几秒的 ubuntu 运行，以及要配 secrets。

不想配 SMTP 的折中：设置里选 "Only notify for failed workflows"，「收到邮件即红、没邮件即绿」。但绿轮没有任何通知，不满足「直接看到」。

## 三、两个容易白折腾的方向

- 改 `run-name` 塞结论行不通。官方 workflow-syntax 写明 `run-name`「can include expressions and can reference the github and inputs contexts」，且在工作流触发时求值——那一刻本轮的 `conclusion` 还不存在，也没有 `needs.*.result` 可用。
- 内置通知设置是账号级，无法按仓库改成「标题带结论」；也不存在按仓库覆盖邮件模板的入口。

## 四、落地清单（待用户授权，本次未执行）

1. 新增 `.github/workflows/ci-notify.yml`（上节内容）。
2. 仓库 Settings → Secrets and variables → Actions 加三个 secret：`CI_MAIL_CONNECTION`、`CI_MAIL_TO`、`CI_MAIL_FROM`。
3. 账号级 Settings → Notifications → System → Actions 关掉内置邮件，否则一轮会收两封。
4. 推一次 master 触发验证——`workflow_run` 工作流文件必须在默认分支存在才会生效（官方 events-that-trigger-workflows 的 Note：「This event will only trigger a workflow run if the workflow file exists on the default branch.」）。
5. 验证判据：造一轮红、一轮绿，两封标题分别以 `❌ 红` / `✅ 绿` 开头。

用户 2026-11-11 明确「先不做」，以上仅为记录，未创建任何 CI 文件、未改配置、未推送。

## 五、取证口径

- GitHub Docs 三页经本地代理 `127.0.0.1:7890` 抓取正文（WebFetch 直连返回 404 / ECONNRESET，改 `curl -x` 走代理成功），页面正文按「去掉脚本样式 → 反转义 → 压空白」提取，引用句为原文。
- 仓库工作流清单用 `.local/github-token.json` 的 `github_personal_access_token` 调 `GET /repos/SongRenShuo/mybatis-plus-sql-visualizer/actions/workflows` 核对，返回 3 个 active 工作流；三个 yml 的 `name` / `on` / job 列表由本地文件读取。
- `dawidd6/action-send-mail` 输入项取自其仓库 README（raw.githubusercontent.com 经同一代理），`connection_url` / `subject` / `to` / `from` / `body` 均为文档列明的输入；`subject` 是必填项，这是自定义标题的落点。
- 结论里的「不可自定义」为推断（官方文档未提供主题模板设置，亦无明文否定句），不是官方声明的能力边界。

## 参考

- https://docs.github.com/en/subscriptions-and-notifications/how-tos/managing-github-actions-notifications
- https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax
- https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows
- https://github.com/dawidd6/action-send-mail
