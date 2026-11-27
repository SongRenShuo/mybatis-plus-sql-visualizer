# GEO 检索来源观察

> 用途：记录「AI 回答某类问题时到底检索了哪些站点」，用来排发布优先级。
> 与 CSDN 后台 GEO 工具（`mp.csdn.net/geo`）互补：那个工具看的是**单篇文章的评分**，这份看的是**AI 实际抓了谁**。
> 每次做同类观察就在下面追加一节，旧记录不删。

## 一、为什么单独记这个

CSDN 的 GEO 工具给的是「来源域名被引用次数」这种聚合数（2026-11-23 读数：`blog.csdn.net` 131 次、`juejin.cn` 27 次），容易读成「优先发 CSDN」。

但 2026-11-24 直接看 DeepSeek 的一次实时会话，15 个检索来源里 **CSDN 一个都没有**，知乎占了 4 个。聚合计数和单次实际检索的结论不一致——前者是历史累计（含老文章），后者反映「新文能不能被这次搜索捞到」。

两个数都要看，不能只信一个。

## 二、2026-11-24 首次实测：DeepSeek「MyBatis 调试插件推荐」

**会话入口**：`chat.deepseek.com`，开启「智能搜索」，用户提问「推荐下 MyBatis 与 SQL 调试的 IDEA 插件」，随后追问「把你搜到的网页链接都给我列出来」，AI 回答「搜索到 22 个网页」。

### 2.1 AI 推荐的插件与引用次数

| 插件 | 引用数 | AI 给的定位 |
|---|---|---|
| MyBatisX | 12 | Mapper↔XML 跳转、代码生成，首选 |
| MyBatis Log Free | 8 | 控制台日志还原成 SQL |
| MyBatisCodeHelper-Pro | 10 | 跳转 + 右侧日志面板，部分付费 |
| **MyBatis SQL Visualizer** | **5** | 「想在写代码时就预览 SQL（不启动服务）」，免费开源、需 IDEA 2024.3+ |
| DebugTools | 1 | 附着后直接调用 Mapper 方法 |
| PawSQL | 4 | SQL 审核、重写、索引推荐 |

我方插件被排在**场景补充段的第一位**，且 AI 主动补了一句「你之前特别认可的 MyBatis SQL Visualizer」——说明它在会话上下文之外已经对该名字有印象。

### 2.2 实际检索到的 15 个来源（页面 DOM 里的真实链接）

| 域名 | 条数 | 具体内容 |
|---|---|---|
| `zhuanlan.zhihu.com` | 3 | 三篇 MyBatis 相关专栏文 |
| `www.zhihu.com` | 1 | 问题「MyBatis 的实用插件有哪些？」的**我方回答** |
| `baomidou.com` | 2 | MyBatis-Plus 官方文档（中/英 MyBatis-X 页） |
| `docs.pawsql.com` | 1 | PawSQL 官方文档 |
| `debug-tools.cc` | 1 | DebugTools 官方文档 |
| `plugins.jetbrains.com` | 1 | Marketplace 插件搜索页 |
| `cloud.tencent.cn` | 1 | MyBatisCodeHelper-Pro 介绍文 |
| `developer.aliyun.com` | 1 | mybatis-sql-viewer 介绍文（竞品） |
| `juejin.cn` | 1 | **我方文章**《改完 Wrapper 先重启服务再翻日志？》 |
| `www.cnblogs.com` | 1 | 他人 MyBatis 文章 |
| `m.toutiao.com` | 1 | 今日头条聚合文 |
| `github.com` | 1 | MyBatisCodeHelper-Pro quickstart |
| `blog.csdn.net` | **0** | — |

### 2.3 我方被引用的事实

这是第一次拿到「AI 直接引用我方内容」的硬证据：

- **掘金文章**：AI 在「MyBatis SQL Visualizer 相关网页链接汇总」里第一项就是它，并注明「包含静态预览、JVM 捕获、N+1 检测等功能说明」——这是它描述该插件的**主要信息来源**。
- **知乎回答**：出现在 `www.zhihu.com` 那条链接（问题 585217905），即 2026-11-23 发的三答之一。
- AI 同时承认「我这次搜到的直接官方链接不多」——说明官网 `sql-visualizer.jvtools.cn` 和 GitHub 仓库都没有被检索到。

对照组：此前 GEO 基线是 0/5（AI 完全不点名我方插件）。现在状态是「被点名 + 被引用 + 排第 4」。

## 三、与 CSDN GEO 工具读数的对照

| 指标 | CSDN GEO 工具（2026-11-23） | DeepSeek 实测（2026-11-24） |
|---|---|---|
| CSDN 域名 | 被引用 131 次，排第一 | **0 条**，完全没出现 |
| 掘金域名 | 27 次 | 1 条（是我方文章） |
| 知乎 | 未单列 | 4 条，占比最高 |
| 单篇新文收录 | CSDN 两篇均 0 分（发布约 1 天） | 掘金文章被采信为「信息最集中的来源」 |

结论有三条：

1. **域名权重 ≠ 单篇会被收录。** CSDN 站内老文被大量引用，但新发文章抓取明显慢；掘金反而更快进入检索池。
2. **知乎是本次最大的来源池，而它是我们覆盖最薄的一块**——3 条回答、0 篇文章（截至 2026-11-24 上午）。这也直接触发了当天的知乎专栏文章发布。
3. **官方站点（官网 + GitHub）检索权重为 0。** 对粉丝型读者官网够用，但对 AI 检索几乎无效。要改善得靠被第三方站点引用/转载，而不是自建站本身。

## 四、站点优先级（按本次观察调整）

原来的顺序是「CSDN > 掘金 > dev.to」，现在改成按**目的**分：

| 目的 | 首选 | 理由 |
|---|---|---|
| 让 AI 搜得到并引用 | 知乎（回答 + 文章 + 专栏）、掘金 | 本次 4+1 条来源；掘金文被当主源 |
| 长期域名权重积累 | CSDN | 站内老文引用池大，但要接受新文 3–7 天才被索引 |
| 技术社区口碑 / 转载扩散 | 博客园、51CTO、腾讯云、阿里云 | 各有检索入口；博客园对 AI 声明敏感 |
| 英文语料 | dev.to | 单篇已被 CSDN GEO 工具识别为「同主题最佳引用源」之一 |
| 品牌词守位 | 全部 | 搜插件名时各平台都能命中即可 |

## 五、观察口径（下次照这个来）

1. 用同一个 AI（DeepSeek）、同一类问题（如「MyBatis 日志工具推荐」「IDEA SQL 插件推荐」）、全新会话，不要用带历史上下文的会话——上下文会让 AI 优先提已出现过的名字，污染读数。
2. 追问「把你搜到的网页链接都给我列出来」，然后从**页面 DOM 的 `<a>` 取真实链接**，不要只复制 AI 正文里的列表（正文列表可能省略或改写）。
3. 记录三样：来源域名分布、我方内容是否被引用、AI 对目标的描述是否准确（错了说明它读到的是别家的内容）。
4. 与 CSDN GEO 工具评分并列记录，两者不一致时以「实际检索」为准做发布决策。

## 六、待观察

- 知乎专栏文章（2026-11-24 发布）需 1–3 天后重测，看是否进入检索池。
- 博客园、腾讯云、阿里云三篇同样待测（腾讯云已上线、阿里云审核中）。
- 官方站检索权重为 0 这件事，需要单独想——可能的路径是被第三方引用（转载、被 awesome 列表收录、被竞品文章提及），而不是改站内结构。

## 七、2026-11-28 实测：11-27 那批内容发出后 0–1 天

**口径**：沿用 §五，同一条 prompt「推荐下 MyBatis 与 SQL 调试的 IDEA 插件」+ 追问「把你搜到的网页链接都给我列出来」，全新会话、不带历史上下文。
**时点**：北京时间 2026-11-28 00:10–01:20（11-27 集中发的知乎 1 篇 / 掘金 1 篇 / CSDN 1 篇，发出后不足 24 小时）。
**窗口与 profile**：全部读数取自同一个 Edge 调试实例 `127.0.0.1:9222`、profile `.local/edge-pub-profile`（未换 profile、未关实例、未动任何登录态；各入口的登录态都由该 profile 提供）。

### 7.1 逐入口读数（8 个入口）

| 入口 | 是否真检索 | 是否提及我方 | AI 给的定位 | 来源可取证情况 |
|---|---|---|---|---|
| **DeepSeek** `chat.deepseek.com`（已开「智能搜索」） | 是（14 个网页） | **是** | 「备选与细分场景」清单**第 2 条**（四条备选里的第 2 条，位于首选 Log2SQL 之后），小标题「深度调试（不依赖日志）」：免费开源、不解析控制台而是在 JDBC 层挂 agent 采集真实 SQL/参数/耗时/影响行数；独特优势是静态预览 Wrapper 链、光标悬停即出最终 SQL；适合排查 N+1 与慢查询；要求 IDEA 2024.3+、执行计划依赖 Ultimate。结尾「避坑提示」再次点名（「走 JDBC 层采集的 MyBatis SQL Visualizer 反而更可靠」） | 14 条来源里 3 条与我方有关：掘金《改完 Wrapper…》（我方，2026-11-23）、知乎回答 492349381（我方，2026-11-26）、`locdd.com` 论坛帖（第三方，2026-11-24，正文直接写「叫 MyBatis SQL Visuali…」） |
| 知乎直答 `zhida.zhihu.com` | 是（「全部来源 16」） | **是** | 「编码期 SQL 预览与质量校验」段第 2 条（全场第 6 款，排在 `mybatis-sql-viewer` 之后）：Wrapper/XML 动态标签/注解/JdbcTemplate/JPA 派生方法渲染成带参 SQL、动态条件复选框切换、运行期 JDBC agent 捕获真实参数与耗时、N+1 标红可跳源码、Apache-2.0、需 IDEA 2024.3+、执行 SQL 与 EXPLAIN 依赖 Ultimate | 正文角标落在 `SongRenShuo` 与「我分享的文件」（10.87KB 单文件知识库，与 11-27 导入的 .md 体量相符）；另有 macrozheng / 東方幽静響 / 技术咖啡馆C / 程序员Leo / 百度开发者中心 / CSDN博客 ×3 / 一码归一码 |
| 文心一言 `wenxin.baidu.com` | 是（4 个关键词、18 篇资料） | 否 | 只推 2 款：MyBatisCodeHelper-Pro（首选）+ MyBatis Log Plugin | 18 条来源：`blog.csdn.net` ×6、`zhihu` ×3、文心快码 ×2、`yeyulingfeng.com` ×2、`hqwc.cn` ×2、`plugins.jetbrains.com` ×1、`cloud.tencent` ×1、网易 ×1 |
| 秘塔AI搜索 `metaso.cn` | 是（14 条引用、10 张来源卡） | 否 | 只推 4 款：MyBatis Log Plugin、MyBatisX、Free MyBatis Tool、MyBatisCodeHelperPro | 来源卡只显示标题与 favicon，DOM 不暴露原文 URL（唯一可取证的是一条 `www.kmdin.net` 的 PDF） |
| 豆包 `doubao.com` | 未见（无来源块、无检索过程） | 否 | MyBatisX（首选）+ Free MyBatis plugin + IDEA 内置 Database Tools + p6spy + MyBatis Log Plugin | 无 |
| Kimi `kimi.com` | 未见（页面只有自身静态资源） | 否 | MyBatisX、MyBatisCodeHelperPro、MyBatis Log Plus、SQL Params Setter、Database Navigator | 无 |
| Qwen `chat.qwen.ai`（手开「网页搜索」） | 有检索过程，正文不出源列表 | 否 | MyBatisX、MyBatisCodeHelperPro、MyBatis Log、Free MyBatis Tool、Database 面板、EasyCode、p6spy | 无 |
| 纳米AI `bot.n.cn` | 未见 | 否 | MyBatis Log Plugin、MyBatis Code Helper、Database Tools、Rainbow Brackets、MyBatis Dynamic SQL Helper | 无 |

**提及率：2/8**（DeepSeek、知乎直答）。

### 7.2 DeepSeek 的 14 个来源逐个对账

| # | 站点 | 内容 | 是否我方 |
|---|---|---|---|
| 1 | `docsearch.algolia.com` | DebugTools 插件文档 | — |
| 2 | `plugins.jetbrains.com` | Free MyBatis Tool（Marketplace API） | — |
| 3 | `www.cnblogs.com` | MyBatisLogFree 介绍（2026-01-28） | — |
| 4 | `juejin.cn/post/7655624648047542291` | DebugTools（2026-06-27） | — |
| 5 | `juejin.cn/post/7709812277021999142` | Log2SQL（2026-11-20）——本次「首选」的出处 | — |
| 6 | `www.cnblogs.com` | 同上站点随笔档案页 | — |
| 7 | `cloud.tencent.cn/developer/article/2567757` | 好用的 IDEA 插件推荐（2025-09-14） | — |
| **8** | **`juejin.cn/post/7711198817501216804`** | **《改完 Wrapper 先重启服务再翻日志？》（2026-11-23）** | **我方** |
| **9** | **`www.zhihu.com/question/492349381/answer/2109101657493595034`** | **「相见恨晚的 IDEA 插件」回答（2026-11-26）** | **我方** |
| 10 | `github.com/chengyuxing/rabbit-sql-plugin` | rabbit-sql 插件 README | — |
| 11 | `github.com/luceat-lux-vestra/zMyBatis` | zMyBatis README | — |
| **12** | **`locdd.com/t/topic/100094/6`** | **论坛帖「求 idea 插件」（2026-11-24），有人在帖里转述我方插件** | **第三方转述** |
| 13 | `github.com/qwq1999/dsh-ide-database` | dsh 数据库插件 README | — |
| 14 | `juejin.cn/post/7594831933516955694` | 「5 款神级插件」（2026-01-14） | — |

域名分布：`juejin.cn` 4、`github.com` 3、`www.cnblogs.com` 2、`www.zhihu.com` 1、`cloud.tencent.cn` 1、`plugins.jetbrains.com` 1、`docsearch.algolia.com` 1、`locdd.com` 1。**11-27 那批（知乎第 7 篇 / 掘金第 4 篇 / CSDN 第 4 篇）在 14 条里 0 条。**

### 7.3 域名是否进来

| 域名 | 本轮命中 | 证据 |
|---|---|---|
| `juejin.cn` | **是** | DeepSeek 14 条里占 4 条，其中第 8 条是我方 11-23 那篇；另外三条是 Log2SQL / DebugTools / 老文 |
| `zhihu.com` | **是** | DeepSeek 第 9 条是我方知乎回答；知乎直答整池都是知乎内容，角标两处落在我方账号与「我分享的文件」 |
| `blog.csdn.net` | **是** | 文心一言 18 条里 6 条、知乎直答角标 3 次；但**没有一条引到我方 CSDN 四篇** |
| 我方官网 / GitHub | **否** | 八个入口无一处引用 `sql-visualizer.jvtools.cn` 或 GitHub 仓库（与 11-24 结论一致） |

### 7.4 与 11-24 同口径对比（DeepSeek 一家对一家）

| 指标 | 2026-11-24 | 2026-11-28 |
|---|---|---|
| 是否提及我方 | 是 | 是 |
| 正文引用角标次数 | 5 | **3**（备选段我方条内 2 处「-8-12」+ 结尾避坑提示 1 处「-12」） |
| 位置 | 「场景补充段」第一位 | 「备选与细分场景」清单第 2 条（首选被竞品 Log2SQL 拿走） |
| 我方来源条数 | 2（掘金 1 + 知乎 1） | **3**（掘金 1 + 知乎 1 + 第三方论坛转述 1） |
| 来源总数 | 15 | 14 |
| CSDN 条数 | 0 | 0 |
| 首选插件 | MyBatisX | **Log2SQL**（出处是 11-20 的掘金文） |
| 11-27 新发内容的痕迹 | — | **0 条**（DeepSeek 侧）；知乎直答侧有（2026.3 兼容口径） |

### 7.5 结论与口径限制

1. **AI 提及没有起跳，强度还略降**：8 个入口 2 家提及（DeepSeek、知乎直答），与 11-24「1 家测、1 家提及」比，覆盖面变宽但只多出 1 家（知乎直答）；同口径的 DeepSeek 侧，角标 5→3、位置从「场景补充段第一位」退到「备选清单第 2 条」，唯一增量是来源里多了 1 条第三方论坛转述。**结论：维持，没涨；DeepSeek 侧甚至略退。**
2. **11-27 那批在 DeepSeek 侧完全不见**（0/14），但在知乎直答侧已被引用（2026.3 兼容段落出自「我分享的文件」）。两种索引速度差一个量级，说明**知乎内自有索引比外部网页索引快**，这也解释了 11-24 掘金当天被捞到属于个例而非规律。
3. **渠道有效、位置靠内容形态争**：掘金池子被深挖（14 条里占 4 条），竞品 Log2SQL 仅凭 11-20 的一篇掘金文就坐上「首选」，我方 11-23 那篇只承接了「深度调试」这一格——同一个渠道能出首选位，说明问题不在渠道，在文章给的是不是结论位。要继续投就争「首选 / 黄金组合」这类断言，不是加发稿量。
4. **CSDN 有池无引用**：CSDN 在文心与直答里出现频次最高（6 条 / 3 次角标），但没一条是我方四篇——CSDN 的新文索引慢于其老文池，与 11-23 CSDN GEO 工具「新文 0 分」的读数一致。
5. **时间窗 0–1 天**，对「外部网页索引」这一侧本就测不出东西；本轮的「没动」不能当反证，真正可比的观察点在 11-30 之后。
