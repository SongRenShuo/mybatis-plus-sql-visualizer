# W1 三件文案 + Marketplace 首屏与关键词稿（A-03/A-04 草稿箱）

> 状态：**草稿，未外发**（2026-11-06 主控起草，A-03/A-04）。发射与线上更新均须用户执行（27 册 D 组冻结项）。
> 口径承：对外内容稿写作口径（不写插件原名自夸、去 AI 味、隐私主张主语写「插件」）；「摘要给钩子、清单是正文的活」；V47 合规自查（评分话术 ≤60 字、不诱导、不承诺互惠）。

## 一、首评引导帖（Gitee / Marketplace，双语）

### zh

标题：用了这段时间，想听你说说真实的体验

插件成长这两年，最缺的不是功能，是「真实的反馈」——我们能看到下载量，看不到你在哪一步卡住、哪一个功能刚好戳中你。

如果你已经在用，欢迎花两分钟留一条评价：说清楚你是什么技术栈（MyBatis-Plus？JdbcTemplate？）、哪个功能你每天在用、哪里还别扭。批评比表扬有用。

- Marketplace 评分入口：插件页 → Reviews → Write a review
- Gitee 反馈：issue 任意模板即可，贴上插件版本与 idea.log 相关片段更快

不需要美言。真实的一条，比十条「好用」都值钱。

### en

Title: Two minutes of your honest experience?

Building this plugin, the hardest part is not features—it is hearing from real users. Download counts say nothing about where you got stuck or which part actually saved you time.

If the plugin is already part of your day, please leave a short review: your stack (MyBatis-Plus? JdbcTemplate?), the feature you use daily, and what still feels rough. Criticism is more useful than praise.

- Marketplace: plugin page → Reviews → Write a review
- Gitee: any issue template, attach plugin version and the relevant idea.log snippet

No need to be kind. One honest review beats ten "nice tool" ones.

### 合规自查（V47）

- 不承诺任何互惠（无「好评解锁」类表述）✓
- 不筛选情绪（明确欢迎批评）✓
- 不代填内容、不设模板引导好评倾向 ✓
- 主语为「插件/我们」非用户被指导口吻 ✓

## 二、评分引导话术（版本内，≤60 字，双语）

### zh（58 字）

用着顺手的话，留一条评价吧——你的一句真实体验，比十次下载更能帮到后来的人。

### en（11 words）

If it saves you time, an honest review helps the next user find it.

### 合规自查（V47）

- zh 58 字 ≤60 ✓；不出现「五星」「好评」定向词，只说「真实体验」✓
- en 不含诱导互惠 ✓
- 建议挂载点：NEW-19 版本亮点通知尾部一次性展示（OnboardingAnchorTip 同款持久化口径），设置页可关

## 三、内测邀请清单（用于下个 beta 通道）

- 目标人群：①曾报过障的真实用户（Gitee issue 作者）②达梦/金仓/瀚高等信创栈用户（方言矩阵反馈最稀缺）③agent 增强捕获用户（JRebel 共存、Oracle thin 等场景）
- 邀请文案要点： beta 通道=Gitee「抢先体验」发行页 + update.json 内测源；承诺「报障 48h 内响应、修复进下一版」
- 需用户确认后才可发出：邀请名单导出（issue 作者去重）、Gitee 通知方式（评论区 vs 私信）

## 四、致谢贡献者 JayF211（对外内容，随下版发行说明）

> 感谢 @JayF211 在 2026-10-31 贡献的 Mapper 接口与 XML 语句双向跳转（22 笔提交）——这是本插件第一笔来自社区的功能级贡献，导航实现与让位策略（与同类 MyBatis 插件共存时自动让出图标）均已随 v2026.11.x 系列发布。

## 五、Marketplace 首屏钩子改写稿（A-04）

### 现状问题

首屏为能力罗列（10 个 `<li>` 块），没有钩子；下载 791 / 评价 0——「被看见但没被选择」。

### 建议首屏（摘要段，替换现有 summary 首段）

zh：改你的应用才能拿到 SQL？不用。**服务未启，SQL 先知**——MyBatis-Plus / JdbcTemplate / JPA 的每条 SQL，带真实参数值直接渲染在你的 IDE 里，执行前就能看懂、能解读、能回滚。不改一行代码，不碰你的应用。

en：SQL visibility without touching your application. **See the exact SQL before it runs** — every MyBatis-Plus, JdbcTemplate and JPA query rendered with real parameter values, right in your IDE. No code changes. No agents in your app.

正文清单保持现有 10 块（清单是正文的活），只动摘要与首屏前两块顺序（把「执行前看到 SQL」与「方言支持面」提前）。

### 关键词池扩容候选（对照 SQLite Lens 样本路径，五词）

| 词 | 依据 | 预期位次改善 |
|---|---|---|
| mybatis sql log | 捕获还原主场景词，竞品描述覆盖弱 | 入前 20 |
| jdbc sql preview | JdbcTemplate 用户词，本插件已支持但描述未点出 | 入前 15 |
| sql with parameters | 「带参数执行」高频搜索意图 | 入前 25 |
| mybatis plus debugger | 竞品「debugger」词位次高但功能面窄，正面竞争 | 前 10 |
| explain sql intellij | R45/EXPLAIN 叙事词，抢先占位 | 入前 30 |

改动只涉 update 描述元数据（Marketplace 后台由用户操作），不触代码；preserveUIEdits 口径先核（承 pitfall-marketplace-description-two-stores-preserveui）。
