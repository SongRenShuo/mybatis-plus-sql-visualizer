# 知乎 ORM 选题池（来自用户「东哥」的关注列表）

> 采集时间：2026-11-26。来源：知乎用户 [dong-ge-15-66-56](https://www.zhihu.com/people/dong-ge-15-66-56) 关注的 20 个问题。
> 该账号本身无传播力（粉丝 1、累计赞同 10 次），**不建议关注本人**；有价值的是这份列表——相当于别人替我们筛过一遍的「长期有搜索需求且我们能答」的题库。
> 热度数据来自关注列表页的快照，未单独核验浏览量。

## 一、已答

| 问题 | 状态 |
| --- | --- |
| [Hibernate 和 Mybatis 哪家强？](https://www.zhihu.com/question/13394020029) | 2026-11-25 答 |
| [有没有人知道 mybatis-plus,mybatis-flex,mybatis-mp 那个好用？](https://www.zhihu.com/question/12377739798) | 2026-11-24 答 |
| [如何评价 MyBatis-Plus？](https://www.zhihu.com/question/279766037) | 2026-11-26 答（150 答） |
| [以后 Spring Data JPA 和 mybatis 哪个会更流行？](https://www.zhihu.com/question/317183937) | 2026-11-26 答（131 答） |
| [为什么 90% 的开发者放弃使用 Hibernate，而选择 MyBatis？](https://www.zhihu.com/question/532452772) | 2026-11-26 答（144 答） |
| [spring-data-jpa 和 mybatis 可以整合在一起使用有什么优缺点？](https://www.zhihu.com/question/53706909) | 2026-11-26 答（38 答，`answer/2109281997411575015`） |
| [Java 世界出现了新的 ORM 框架，你了解过吗？](https://www.zhihu.com/question/434004626) | 2026-11-26 答（6 答，`answer/2109296509728585710`） |

## 二、值得答（按性价比排序）

> 2026-11-26 实测全部重新采数。**原来的排序是错的**：两条 xbatis 题被标成「高优先级（独占价值最高）」，实测浏览量分别是 **3** 和 **375**——独占是真的，没人看也是真的。选型题的流量都压在老问题上，新题全是无人区。
>
> 采数工具：`.local/cdp/qstats.js`（见 §四 第 4 条）。

| 问题 | 实测数据 | 建议角度 | 优先级 |
| --- | --- | --- | --- |
| [spring-data-jpa 和 mybatis 可以整合在一起使用有什么优缺点？](https://www.zhihu.com/question/53706909) | **182 关注 / 28.3 万浏览 / 37 答** | 全池流量最高（是第二名的 28 倍），但**不是低竞争**——37 个回答里高赞都集中在 2017-2019 的选型争论。空档在「两者并存之后的运维成本」，现有回答没人写 | ~~高~~ **已答（2026-11-26）** |
| [Java 世界出现了新的 ORM 框架，你了解过吗？](https://www.zhihu.com/question/434004626) | 9 关注 / 1.0 万浏览 / 5 答 | 横评 xbatis / easy-query / MyBatis-Dynamic-SQL 三家表达方式，落到「SQL 能不能在写的时候看见」 | ~~中~~ **已答（2026-11-26）** |
| [2026 java 框架哪个好用？](https://www.zhihu.com/question/2038220884696097885) | 6 关注 / 546 浏览 / 4 答 | 选型题吃搜索流量，但年份新、存量小；可作为「2026」关键词的占位 | 中低 |
| [推荐 java 最好的用 orm 框架，来说一说你心中最好的那个！？](https://www.zhihu.com/question/2108970389091127685) | 3 关注 / 182 浏览 / 2 答 | 流量小、已有 2 答。给判断标准（SQL 归谁管、出错时能不能看见）而不是排名 | 低 |
| [谁知道 Java 中 xbatis 这个 ORM 框架，怎么样？](https://www.zhihu.com/question/1985659298865505153) | 1 关注 / 375 浏览 / 1 答 | 我们确实适配了 xbatis，是少数能答的；但已有 1 答（答主就是那位「东哥」），且流量极小 | 低（先不做） |
| [MyBatis-MP 的链式 API 如何提高开发效率，不是 plus？](https://www.zhihu.com/question/12381576478) | 1 关注 / **3 浏览** / 0 答 | 独占题，但浏览量个位数，等于自己写给自己看 | 低（先不做） |
| [初学 mybatis，文件路径下明明有文件，为什么运行时还找不到？](https://www.zhihu.com/question/6550641495) | 6 答 / 10 关注 | 纯答疑题（mapperLocations 通配、classpath 与 target 产物、jar 内路径），答好了涨信任度，但带不出插件 | 低 |
| [java 里面 mybatis 作用是什么？](https://www.zhihu.com/question/658318805) | 19 答 / 21 关注 | 入门题，可写「MyBatis 给你的是 SQL 的所有权」，但仍偏科普 | 低 |


## 三、不建议答

| 问题 | 原因 |
| --- | --- |
| [ORM 是否必要？](https://www.zhihu.com/question/23244681) / [为什么 Java 没有好用的 ORM 框架？](https://www.zhihu.com/question/323644106) | 86 答 / 629 关注，纯理念题，工具切进去会显得硬拗 |
| [MyBatis 为什么稳居中国最抢手 Java 数据库访问框架之首？](https://www.zhihu.com/question/465501525) | 与「90% 放弃 Hibernate」撞主题，已答后者 |
| [MyBatis 是什么？Hibernate 和 MyBatis 有什么区别？](https://www.zhihu.com/question/648910993) / [什么是 Mybatis？如何快速入门？](https://www.zhihu.com/question/484431019) | 入门科普，插件插不进去 |
| [2025 年 盘点有那些 ORM 框架？](https://www.zhihu.com/question/12409432093) | 1 答 / 2 关注，纯罗列题 |
| [App 产品做 aso 优化，如何找一个靠谱的积分墙？](https://www.zhihu.com/question/332371794) | 与主题无关（疑似误关注） |

## 四、复用方法

1. 每次要答知乎题时，先看这里有没有对口的，避免临时找题
2. 新答完一条就把它从「值得答」移到「已答」，并回填链接
3. 关注列表里出现新问题时（如果决定关注该用户），补充进「值得答」并重新排序
4. **热度必须实测，用 `qstats.js`**：`node .local/cdp/qstats.js <questionId> [更多id...]`，输出关注者/浏览/回答数/编辑器状态。**别用正则去 match 这三段**——知乎把零宽字符和全角空格混在 `innerText` 里，`/被浏览\s*([\d,]+)/` 在这条链路上直接返回 null（我已踩过一次，把「37 个回答」读成「3 答」）。正确做法是 `innerText.split('\n')` 后找 `'关注者'` 这个整行、取下一行。
5. **优先老问题、大浏览量**：知乎的问题页流量几乎全来自搜索和站内推荐，新问题的「无竞争」是假优势——没人看才是真的。但**高浏览 ≠ 低竞争**：`53706909` 有 28.3 万浏览，也有 37 个回答，高赞都在 2017-2019；这种题要答就得找现有回答都没写的角度