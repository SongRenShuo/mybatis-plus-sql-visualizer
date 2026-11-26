---
name: project-old-name-seo-cleanup-20261114
description: 20261114 旧名残留排查 + SEO 提排名方案——用户改令「先不改仓库 URL，先做搜索引擎排名」；站点体检读数+四步方案+长尾词落点已定（docs 站源码仓=同级 mybatis-plus-sql-visualizer-docs，VitePress；待用户点头出改动清单）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_98e304f3-7fad-448e-b06c-633c2113957e
---

用户 2026-11-14 报：网上/AI 搜插件仍出现旧名「mybatis-plus-sql-visualizer」。实测排查结论：

- 展示名已是新名：plugin.xml `<name>SQL Visualizer`、Marketplace id=33633 页面 slug `/plugin/33633-sql-visualizer`；WebSearch 实测第一命中即 Marketplace 新名页。
- **改不了（勿再立项）**：①插件 xmlId `cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer`（发布后不可变，Marketplace API 公开返回）；②包名 `cn.song.mybatisplus.visualizer.*`（改=全仓重构零收益，定案不动）；③已发行历史版本的下载 URL。
- **能改、也是搜索/AI 主要采信面**：①GitHub 仓库名 `SongRenShuo/mybatis-plus-sql-visualizer`（含 demo 仓 `-demo` 同名）；②Gitee 仓库名；③`rootProject.name`→zip 产物名（CI build-plugin.yml:824 zipName 写死）；④docs 站 sql-visualizer.jvtools.cn 首页 4 处旧仓库链接 + 英文页 1 处；⑤README/CONTRIBUTING/AGENTS.md/THIRD-PARTY-NOTICES/nightly-full.yml 硬编码旧仓库 URL；⑥用户自己的介绍文章（需用户自查）。

**改名方案**（已给用户，⚠️ 20261114 用户改令：**暂不改仓库 URL，先做搜索排名**——改名方案挂起，仓内替换点清单留档）。

## SEO 提排名（当前活跃线）

用户追问：搜「sql visualizer」自己的站点（sql-visualizer.jvtools.cn）靠后。体检实测：

- **站点 on-page 基建没问题**：title/description/og 齐全、canonical 在、hreflang×3、robots+sitemap 正常、Google 已收录（site: 返回 10+ 页）、Marketplace 描述反链站点、GitHub 仓库 homepage 字段已设、topics 含 `sql-visualizer`。
- **排名靠后三根因**：①「sql visualizer」泛化头部词，前排全是老牌在线工具（SQLFlow/Reddit/GitHub 仓库/VS Code 扩展）且搜索意图是「在线画 SQL」不是 IDEA 插件；②权重低——子域新站、外链少、**GitHub 0 star**；③站长平台大概率未登记。
- **四步方案（按 ROI）**：
  1. 用户登记 Google Search Console / Bing Webmaster（可从 GSC 导入；Bing 索引喂 ChatGPT/Copilot AI 搜索，兼治「AI 出旧名」）/ 百度搜索资源平台；
  2. 关键词战略=放弃头部词，主攻零竞争长尾：mybatis 日志转真实 SQL、MyBatis-Plus Wrapper 预览 SQL、FAQ 型 H2（「mybatis 怎么查看最终执行的 sql」）、英文 mybatis log sql formatter；英文首页 title 可改 `SQL Visualizer — Preview MyBatis/JPA SQL in IntelliJ IDEA`；
  3. 外链=掘金/CSDN/知乎/SegmentFault 实战文、回答既有提问、友链页 /about/friends 换链、README 引导 star；
  4. 站内可做：JSON-LD 只有 `WebSite` 型且 description 过短 → 补 `SoftwareApplication` + 功能页 `FAQPage` 富摘要；sitemap 补 lastmod。

## 实施（20261114 用户令「开始」，①②④ 三项获批；后续进度以 git 实测为准）

- **④ 站内小改大半早已存在**（初轮体检只抓到第一个 JSON-LD 块导致误判缺 SoftwareApplication）：config.mts 里 `SoftwareApplication` JSON-LD、`transformHead` 注入 canonical+hreflang、cleanURL 自动带 sitemap lastmod、`llmstxt` 插件产 llms.txt 全都在。真正要做的只剩 WebSite JSON-LD 描述加长 + FAQ 页 FAQPage。
- **分工**：A2（32 页 frontmatter 批改，zh 7 features+9 guide / en 同构）派**后台子代理**做（用户令「并发上限 1、不阻塞主控」），4 笔里程碑提交，铁律=只动 title/description、正文含 H1 一字不动、git add 只点明确路径；主控做 config.mts + 新页。
- **主控已落**：新页 `guide/view-final-sql.md` + `en/` 镜像（「怎么看 MyBatis 最终执行的 SQL」5 场景问答，frontmatter head 带 FAQPage JSON-LD——**VitePress frontmatter head 语法是数组的数组**：`- - script` / `- type: application/ld+json` / `- |` JSON 块，不是 `script: {attrs…}` 对象写法）；中英 sidebar 各加一条挂载；WebSite JSON-LD description 已加长。
- **部署机制**：`npm run deploy` = vitepress build + pagefind + `wrangler pages deploy --project-name sql-visualizer-docs`（CF Pages；非交互环境需 CLOUDFLARE_API_TOKEN，见 reference-docs-deploy-cloudflare-token）。站长平台验证文件上线走这条路。
- **B 线进度**：GSC 已在内置浏览器打开到 Google 登录页，**等用户登录**（用户令：需要登录就叫用户）；登录后做站点验证（验证文件要能上线上域名）→ Bing（可从 GSC 导入）→ 百度 → 三平台交 sitemap。
- A5 本地 `npm run build` 后台验证中（build 前两脚本 sync-plugin-version/generate-changelog 可能改 tracked 数据文件，属正常）。

## 长尾词落点与文档站源码（20261114 追问后核实）

- **文档站源码仓**＝[mybatis-plus-sql-visualizer-docs](F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-docs)（插件仓同级目录；VitePress，中文 `features/` 12 页 + `guide/` 9 页，`en/` 镜像，另有 `-docs-dev` 工作树）。
- **长尾词只写三处**（每页）：frontmatter `title:`（→`<title>`，最强信号）、frontmatter `description:`（→meta 摘要）、正文 `## H2` 问句标题；正文不堆关键词。
- **现有文档实测大部分不用动**：`features/sql-log.md` title 已是长尾句（「MyBatis 日志转完整 SQL（问号自动替换参数）」）；`features/wrapper-preview.md` title 偏品牌化（「Wrapper → SQL 预览」）需改一行；`guide/comparison.md` 无 frontmatter 靠 H1 兜底需补。
- **已报给用户的改动口径**（待用户点头开工）：①12 features + 9 guide 逐页核 frontmatter，只改 title/description 各一行；②新建 1 页 FAQ 汇总（不动现有页，倾向此案，替代「现有页尾加 FAQ H2」）；③`en/` 镜像同步英文长尾词（mybatis log sql formatter / preview mybatis sql intellij）。
- 预期口径已声明：这套改动只决定长尾词排名，不改变「sql visualizer」头部词格局（那是站长平台+外链的事）。

状态：**一轮全部收官（20261114 同日）**——见下方收官段；GSC 数据两周后回读做第二轮调优。

## 收官（20261114 当日全链跑通，读数以平台实测为准）

- **A 线全落地并已上线**：A2 子代理 4 笔提交（`4825b19`/`0d40eef`/`e9ecd97`/`72e3ba4`，子代理收尾撞并发限额但四笔全落库、主控抽查后接手收口）；主控 `c488571`（FAQ 双语页+sidebar+WebSite 描述）；部署 4 次走 `CLOUDFLARE_API_TOKEN=$(cat .cf-token) npm run deploy`（token 在 docs 仓根 54 字节 gitignore；非交互 bash 必须带此环境变量）。
- **GSC**：HTML 文件验证通过（`public/googled5d4e7a48f2a3071.html`，提交 `8e49a68`）+ sitemap 提交成功（62 页被发现）。
- **Bing**：站点本就在册（近 3 月 63 展示/5 点击），无需验证；sitemap 提交成功 Processing（0 错 0 警）。
- **百度**：账户信息（用户手填）→ 站点 `https://sql-visualizer.jvtools.cn/`（领域=信息技术+工具服务及在线查询，30 天内只能改一次）→ 文件验证两轮 308 失败后修中间件（[[pitfall-cf-pages-html-308-baidu-verifier]]，提交=functions/_middleware.js 一笔）→ **验证成功**；sitemap 提交页配额 0 条（新站限制，须站点属性填 ICP 备案号提额）→ 绕道 API 推送 10 条核心 URL 全收（`data.zz.baidu.com/urls?token=z2v4MGAVPCRPXasA`，日配额 10 条已用满）。
- **遗留用户动作**：①百度站点属性填备案号提额后补交 sitemap.xml；②GSC 两周后拿查询数据复盘；③GitHub/Gitee 改名方案仍挂起；④docs 仓改动未 push（部署是 wrangler 直传，与 git push 无关）。
- **验证文件全集**（成功后不可删）：`public/googled5d4e7a48f2a3071.html`（`8e49a68`）、`public/baidu_verify_codeva-QIoZykcN8c.html`（内容=MD5 串）。部署链 `CLOUDFLARE_API_TOKEN=$(cat .cf-token) npm run deploy`（token 54 字节在 docs 仓根 gitignore；jvtools-home 20260924 计划记载该 token 曾泄漏待用户轮换）。
- **效果时间线（给用户「多久能看到排名提升」的口径）**：Google 收录新页数天~2 周；摘要换新 title/description 1~3 周；**长尾词出排名 2~6 周（最可能先出结果的层）**；百度收录 2~6 周起（API 推送次日看抓取诊断）；「sql visualizer」头部词数月级。观测节奏＝第 3~5 天看 GSC 索引量与百度抓取诊断，**第 2 周 GSC「效果」报告第一份真实查询词数据＝复盘节点**。加速杠杆：掘金/知乎实战文外链、GitHub star 从 0 攒起。

