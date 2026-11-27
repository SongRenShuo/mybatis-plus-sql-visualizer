---
name: project-docs-site-merge-and-compare-page-20261123
description: 文档站 20261123 两批——合并 dev 23e5608 + 对比页中英重写 + 侧栏行距（master 72bde8e）；晚批次合并后全站审查修 about 页描述缺失/LLM 提示反斜杠并部署 e913123；含双远端推送机制（GitHub 禁 credential.helper、Gitee 必须用系统凭据）与部署验证口径（wrangler→CF Pages→EdgeOne 回源、HTML max-age=0 无需等 24h、node_modules 残缺会致 build 静默失败）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_7736ad03-fd53-4d18-b592-9ff76db294f0
---

# 文档站 2026-11-23 本地工作（dev 合并 + 对比页优化）

文档站源码仓＝[mybatis-plus-sql-visualizer-docs](F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-docs)（VitePress，双工作树：`-docs`=master、`-docs-dev`=dev），远端 Gitee `songrenshuo/mybatis-plus-sql-visualizer-docs` + GitHub 镜像。plugin 仓同级目录。

## dev → master 合并（已提交，2026-11-23 已推双仓）

- 用户点名「文档站 dev 合并到主分支，本地启动看效果」；master 工作树合并 dev tip `0b5a422`（含 67 笔：可发现性优化批、D251 GEO 对比页、弱网媒体优化、SEO 补齐），合并提交 **`23e5608`**（--no-ff，沿用既有 "Merge branch 'dev'" 先例）
- 冲突预演 `git merge-tree` + 实际合并均只有两个构建期数据快照 `.vitepress/data/facts.json` / `plugin-version.json` 双边改：master 侧较新（downloads 1230 / syncedAt 11-22）vs dev 侧 11-21 ⇒ **取 master 侧 `--ours`**（下次 build 脚本会重刷）。合并后工作树干净。
- **2026-11-23 用户点名「docs-dev 推送」后已推双仓**：master 本地=head=远端 `72bde8e`（ahead=0）；dev 67 笔推至 GitHub `0b5a422` + Gitee `0b5a422`，`ls-remote` 三方一致。

## 对比页中英重写（未提交）

用户反馈 `guide/compare-sql-tools`（D251 GEO 对比页）内容需再优化，经 AskUserQuestion 全选四方向：①一眼选型引导 ②中立克制 ③精简冗余 ④补对比维度表。已重写 `guide/compare-sql-tools.md` + `en/guide/compare-sql-tools.md`：
- 顶部「一眼选型」短表（按处境三档直接给推荐）
- 自家插件从盘点表移出单列「本插件定位」块，表格里去掉「本页即其团队维护」字样，下载量口径移到页尾统一说明
- 三个族介绍去重压缩；新增「对比维度」表（是否需启动应用/参数来源/能否写码时看到 SQL/能否拿真实耗时/主要产出），只陈述能力事实不评价优劣
- 注意：项目既有约定（docs/开发计划/20260906 等）对逐项能力对比保守（风险高、维护成本大），本次是用户明确点名才突破该口径

## 侧栏行距 CSS（未提交）

`.vitepress/theme/style.css` 给 `.VPSidebar .VPSidebarItem.level-1.is-link > .item` 加 `padding-top/bottom: 7px !important`。VitePress 侧栏 DOM 实测：每项 `<div class="VPSidebarItem level-1 is-link"><div class="item"><a class="VPLink link">`，默认无 padding、line-height 24px；加后单项高 24→38px。浏览器实测 padding 7px/7px 生效。

## 验证与状态

- dev server `npm run dev -- --host 127.0.0.1` 后台起在 **http://127.0.0.1:5173/**（首页与新增对比页 HTTP 200）
- `npm run build` exit 0（64 页、8913 词索引，无死链）；build 会刷新 `facts.json`/`plugin-version.json`，已 `git checkout --` 还原这两个副产物，临时 `.local-docs-*.log` 已删
- 中英两页浏览器渲染正常（各 6 h2、4 表）
- **工作树状态（2026-11-23 末）**：对比页中英两文件 + style.css 已随 dev 链提交并推送，两棵工作树干净。**线上已生效**：对比页 zh/en 均 HTTP 200、首页 title 带 MyBatis/JPA、JSON-LD alternateName 收窄、反馈页评分条与首页 Star 引导在线。

## docs 仓推送与部署机制（2026-11-23 实测）

- **双远端**：`github`=github.com/SongRenShuo/mybatis-plus-sql-visualizer-docs、`origin`=gitee.com/songrenshuo/...；仓内**无推送脚本**（scripts/ 只有 changelog/pagefind 等）。
- **GitHub 推送**走令牌 URL 且必须禁凭据助手：`git -c credential.helper= push "https://x-access-token:$TOKEN@github.com/..." <ref>`（令牌读插件仓 `.local/github-token.json`）。注意 URL 直推**不更新 `github/dev` remote-tracking ref**，别拿它判成功，用 `ls-remote` 验。
- **Gitee 推送恰恰相反：不能加 `-c credential.helper=`**（报 `unable to read askpass response` / `could not read Username`），要用系统凭据直推 `git push origin <branch>`。
- **Gitee `ls-remote` 有一次返回本地不存在的提交**（边缘节点一致性延迟，实测读到假值 `2d695a9`），复测即恢复真值——推送后核对要读两次。
- **部署与 git push 无关**：`npm run deploy` = build + pagefind + `wrangler pages deploy`（CF Pages）直传，token 在仓根 `.cf-token`。

## 20261123 晚批次：合并后审查 + 修复 + 部署（已上线）

用户「合并了两个分支并部署了，你来审查下」→ 全站体检（sitemap 64 页逐页 curl）后修三类问题，提交 **`e913123`**（18 文件）、双远端推送、`npm run deploy` 上线：

- **8 个 about 页缺 description**（中英各 4：bounty/feedback/friends/roadmap）回落到站点默认值——中文 4 页雷同 78 字、英文 4 页 380 字会被搜索截断；各写独立描述（中文 94~152、英文 145~151）。判据：某页描述异常先看源文件有没有这个字段，`friends.md` 是空 frontmatter、`roadmap.md` 连 frontmatter 都没有
- **5 个 GEO 重点页描述偏短**已扩写：FAQ 46→152、快速开始 56→145、不用 p6spy 59→91、日志参数 63→92、SQL 日志 95→135
- **vitepress-plugin-llms 反斜杠路径**（真缺陷）：所有内页顶部给 LLM 的提示是 `/guide\faq.md`（Windows `path.join` 产物），AI 抓取必 404；在 `transformHtml` 里与 logo alt 合并成一条替换链，63 页命中数 60 → 0

**部署与验证口径**：`npm run deploy` = build + pagefind + `wrangler pages deploy` 到 CF Pages 项目 `sql-visualizer-docs`（wrangler 4.125 在 `~/.nvmd/bin`，不在 node_modules/.bin）；EdgeOne 自动回源 `sql-visualizer-docs.pages.dev`，**无需单独操作**。HTML 的 `Cache-Control: public, max-age=0, must-revalidate` ⇒ 边缘 `EO-Cache-Status: MISS` 直接回源拿新产物，**不用等 24h**（24h 窗口只针对 `/demo/*`、二维码这类静态素材）。三处都要核：pages.dev 生产别名 / 本次部署域 `f97269e6.…` / 生产域 `sql-visualizer.jvtools.cn`。

**当日晚间环境坑**：这棵工作树 `node_modules` 是残的（无 `.bin` 目录 + 缺 `@11ty/gray-matter`），`npm run build` 报 `'vitepress' is not recognized` 但**外层 exit 0**——别把 exit 0 当成功，要读日志正文；`npm install` 补齐即恢复，`package-lock.json` 未被改动。另外安装截图换代要带 `?v=YYYYMMDD` 参数绕边缘缓存（无参 URL 仍是旧图，直到过期）。

**审查通过、无需改动的项**：64 页全 200、sitemap 含新页 compare-sql-tools、hreflang（zh-CN/en-US/x-default）+ canonical + og 齐全、四类结构化数据（WebSite / SoftwareApplication / HowTo / FAQPage）都在、事实一致（15 方言 / 11 只读 MCP 工具 / IDE 2024.3、MCP 需 2025.2）、无冲突标记残留、无文件被删。

**Why:** 文档站与 plugin 仓并行，跨会话最易漂移的是「改了没提交 / 推了没部署」；且双远端两套凭据口径正好相反（GitHub 禁 helper、Gitee 必须用 helper），是实际踩过的坑。

**How to apply:** 续跑先 `git -C <docs 仓> status` + `git log -1` + `git ls-remote`（读两次）确认三处状态；推送按远端分别用上面两条命令；线上验证 curl 对比页与首页 title；build 会脏 facts.json/plugin-version.json，属正常、还原即可。
