# MyBatis 的 N+1 别再肉眼数了：判定就一条规则，工具或十行脚本都能做

## 先看一个熟悉的现场

列表页一条 SQL 查 50 行，渲染时每一行的关联字段又各查一次——1 + 50 条 SQL，这就是 N+1。本地开发时列表只有几行，每条查询 1 毫秒，日志里什么都看不出来；到了预发数据量上来，接口响应时间随行数线性变长，这时候去翻日志，几百行输出里全是长得差不多的查询。

懒加载是另一个高发面。MyBatis 的 `collection` 嵌套、JPA 的 `@OneToMany` 默认懒加载，代码里一行循环访问，日志里就是一串重复 SQL。写代码的时候每一行都合理，合在一起就是 N+1。

## 肉眼找它的三个障碍

在日志里找 N+1，实际是三个动作：忽略参数记住形状、判断是不是同一次请求、数次数。

1. **形状判断容易错**——MyBatis 的 `Preparing:`（占位符）和 `Parameters:`（参数）分开打，人要在脑子里做参数化归一，参数一多就开始出错；
2. **并发下交错**——几个请求的 SQL 按时间混排，同一次请求的几十条不一定相邻；
3. **阈值说不清**——重复 3 次算不算？批量场景一次合理查 20 个 id 算不算？没规则就只能凭感觉。

## 判定规则：把「肉眼在干嘛」写严格

写严格之后，N+1 判定就是一条机器能执行的规则：

| 维度 | 判定口径 | 为什么 |
| --- | --- | --- |
| 形状 | 抹掉 `?` 和参数后文本一致（参数化模板） | `WHERE id = 1` 和 `WHERE id = 2` 是同一条模板 |
| 归属 | 同一线程 | 跨线程累计会把正常并发判成 N+1，全是误报 |
| 时间 | 滑动窗内（默认 60 秒） | 全天累计会把零散同类查询攒成旧案 |
| 次数 | ≥5 判嫌疑（可调） | 更低的给轻提示，到线才算行动建议 |

这套口径是我插件里 N+1 检测的实现标准（**MyBatis SQL Visualizer**）：SQL 日志面板按模板聚合计数，满 3 条给琥珀色「×N 同模板」，同线程 60 秒滑窗满 5 条升级红色「疑似 N+1」。点红徽章，列表收成一组，顶部给总数、首末时间、涉及线程，每条带真实参数、耗时和发起调用的源码位置，点过去就是那行代码。

![N+1 红徽章与聚合视图](https://gitee.com/SongRenShuo/mybatis-plus-sql-visualizer/raw/master/docs/内容营销/img-20261128/nplus1-aggregate.gif)

顺带回答两个常见疑问：关掉「SQL 去重」不影响检测，去重只管列表折叠，计数和判定是解耦的；报错语句和 ShardingSphere 改写过的条目都计入——N+1 经常就藏在报错的那一圈里。

![运行期捕获：真实参数与耗时](https://gitee.com/SongRenShuo/mybatis-plus-sql-visualizer/raw/master/docs/内容营销/img-20261128/enhanced-capture.gif)

## 不想装工具？十行脚本也能数

手边只有一份日志时，把 Preparing 行按形状计数就完成了主要判定（Preparing 行本身就是参数化模板）：

```python
import re, collections

shape = re.compile(r"==>  Preparing: (.+)")
thread = re.compile(r"\[(http-nio-\d+-exec-\d+)\]")
counts = collections.Counter()

for line in open("spring.log", encoding="utf-8"):
    m = shape.search(line)
    if m:
        t = thread.search(line)
        counts[(m.group(1), t.group(1) if t else "?")] += 1

for (sql, th), n in counts.most_common():
    if n >= 5:
        print(f"{n:4d}  [{th}]  {sql[:90]}")
```

这个版本的简化是没做时间窗——线程维度的分组已经能挡掉大部分跨请求误报，同线程内同模板高频重复基本就是 N+1。要更准就给计数加上时间分桶（按分钟切片再数）。

## 修法与复验

修法全是教科书内容，难点在先找到：

1. 列表页改 JOIN，把关联字段一次查出来；
2. 循环里的逐条 `getById` 收成一次 `IN` 批量（MyBatis-Plus 的 `selectBatchIds`、JPA 的 `@BatchSize`）;
3. 一对多改 `FETCH JOIN` 或 subselect 这类批量加载形态。

改完重跑同一场景，红色徽章不再出现，就是修干净的信号——判定做成徽章的附带好处是修复有了可复验的判据，不用肉眼对比前后两份日志。

**结论收个尾**：N+1 判断的核心动作只有一个——数同形状 SQL 在同一线程短窗内出现了几次。工具把这个动作从每次排查做一遍，变成发生时自动做好；没有工具，十行脚本也够用。
