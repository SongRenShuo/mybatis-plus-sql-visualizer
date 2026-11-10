# v2026.11.10 正式版发行说明

- 发行：2026-11-10
- 版本：`2026.11.10`（同日 beta 线 `beta.1` ~ `beta.3` 转正；同一基号下正式版大于 `-beta.N`，装了内测版的用户会正常收到这次更新）
- 范围＝`v2026.11.8` 正式版发行包之后的全部用户可见增量（含内测 `v2026.11.9-beta.1` 一轮，与 `v2026.11.10-beta.1` ~ `beta.3` 三轮）

## 本次三块新能力

### 一、iBatis 2.x 全面支持

`<sqlMap>` 文件从「能看看 XML」变成有完整编辑器能力：

- **语句预览**：`select` / `insert` / `update` / `delete` / `statement` / `procedure` 行左侧出预览图标，点开得到按 iBatis 2 语义渲染的 SQL——`#p#` / `#p:jdbcType#` / `#p:jdbcType:nullValue#` / `#p,jdbcType=?#` 四种占位形态、`$p$` 直替、`##` 与 `$$` 转义、14 个 `<isXxx>` 条件标签、`<dynamic prepend>`、`<iterate>`、`<sql>/<include>`、`<selectKey>`。渲染保真以 **iBatis 官方 2.3.4.726 引擎做黄金对照**（20 种形态不连库直取真值、逐字节一致），连「`prepend` 逐字拼接不补空格」这种反直觉行为也原样复现。
- **参数可交互**：动态条件按行勾选、值可填、`<iterate>` 可填值列表，SQL 随填值变化；`parameterMap` 语句显示真实参数名（不再是 `param1..N`）。
- **双向跳转**：XML 内部 id 引用（`<include refid>` / `resultMap` / `parameterMap` / `extends`）与「Java DAO ↔ XML 语句」双向都通，`Ctrl+B` 与 `Ctrl+Click` 两个方向都可用。让位门与 MyBatisCodeHelperPro **解耦**——竞品没有 iBatis 能力、不存在图标冲突，装了它箭头照常显示。
- **编辑体验**：`#name#` 四态注入着色、语句折叠、关键字补全（属性值与 `#{}`/OGNL 刻意不注入，避免改变 `<mapper>` 文件既有行为）；两份 DTD 随包内置，`www.ibatis.com` 早已失效也不影响，离线可用（包增约 9 KiB）。
- **来源区分**：SQL 列表新增 `IBATIS` 来源，与 MyBatis 条目可分别筛选。

### 二、中兴 GoldenDB 方言（第 15 方言）

渲染形态依据《GoldenDB 开发支持 V6.1.03.10》：分页 `LIMIT [offset,] count`（0 起算）、反引号标识符、upsert `ON DUPLICATE KEY UPDATE`、`DELETE ... LIMIT` 文法级支持；保留字词表按官方 PDF 三表并集落 384 词。连接面接全（`jdbc:goldendb://` URL 识别、列表角标、MCP 归一、执行路由），增强捕获按驱动 jar 真实字节登记连接/语句/prepare 三面并补 `com.goldendb.` 框架帧前缀。

能力矩阵如实标注：**EXPLAIN 行为 false**（PDF 有专章，但输出格式未逐字取证，不登记模板），UPSERT 与 DELETE LIMIT 为 true。

### 三、SQL 列表追赶与风暴期刷新优化

- 刷新加 150ms 时间窗下限，风暴期不再退化成每秒几十拍高频小刷新；日常单条 SQL 秒显体验不变。
- 定位并消除真正的卡顿源：不是计数，而是贴底滚动触发的 Swing 列表**整表布局重扫**（每拍 27~31ms 重算全部行高）。现给渲染器加条目级尺寸缓存，**刷新单拍从实测 102~232ms 降到 7~9ms**。
- 状态栏新增「追赶中：落后 N 条」，追平自动消失。
- 捕获链路四类静默故障改可见/可恢复：清屏后不再静默数分钟、高峰期丢弃数上报、排水不再被 1 秒超时税钉在约 64 条/秒（512 条 726ms→31ms）、风暴末尾最后几条不再等下次执行才上屏。

## 本轮修复

- **iBatis 两轮真机反馈（六项）**：`<selectKey>` 子语句不展示；`#x#` 占位符被格式化拆散；勾选填值后重渲染失败被静默吞掉（现在面板直接显示失败原因并在 `idea.log` 记栈）；信息性说明混进红色失败行；DAO 调用行与**方法定义行**各多一枚点开没有 SQL 的预览图标；`#x:jdbcType#` 填值不带引号；XML 语句 `id` 上 `Ctrl+Click` 报「Cannot find declaration to go to」。
- **iBatis 文件点预览图标打开很慢**：慢在预览前的源语言判定（拿 FROM 根标识符反查实体类，必然落到全项目类短名扫描，单次约 1 秒）。现同步判定只做直查 + 只读别名缓存。**影响面大于 iBatis 用户**——所有走通用兜底通道看纯 SQL 的文件都少付这笔钱。
- **PostgreSQL 等库执行预览 SQL 只提交 `TIMESTAMP` 一个词报 42601**：折行后光标落在以 `TIMESTAMP` 开头的片段上，DBN 按光标所在语句提交；现单语句预览整批提交，绕开光标与选区变量。
- **误报类**：Hikari 等连接池代理、Oracle 驱动的五层连接继承链不再被提示「未登记 JDBC 实现」（后者此前占满了上报位，真出现未知驱动会被静默挤掉）。
- **运行期占位回填两处错位**：三元占位注释里的 Java 问号被当成 JDBC 占位符；可选 if 片段填值重复或被拒。

> 上一版（`v2026.11.8`）已修的两项（日期族 ANSI 类型字面量改回填、IDE 2026.3 换源退化）不再重复计入本版范围——本版范围严格从 `v2026.11.8` 发行包之后算起。

## 已知问题

- **iBatis 预览里 `selectByCondition` 这类多条件语句的「勾选联动」仍不正确**（勾选条件行后 SQL 未按预期联动）。已定位、下个版本修复。当前影响面限于「勾选条件」这一条交互，直接填值路径正常。

## 验证读数

| 关卡 | 读数 |
|---|---|
| 合并态定向（beta.1） | 435 类 4461 例（唯一红＝守卫抓 `catch(Throwable)` 未判 fatal，修复后复绿） |
| iBatis 保真 | 官方 2.3.4.726 引擎黄金对照 20/20 形态逐字节一致 |
| 定向（beta.2） | 144 例 0/0/0，保真零回退；反向验证 5 条补丁分别 10 红 / 5 红 / 1 红 / 1 红 |
| 定向（beta.3） | 5 类 39 例 0/0/0，含双向跳转**真实分派链路**用例（真后台线程走 `GotoDeclarationAction.findAllTargetElements` + GTDU 判定）；反向验证同时摘掉两处修复恰 2 例红 |
| changeNotes 机器判据 | 打 tag 之前：`ChangeNotesRatingLineGuardTest` 绿 + `inspect-plugin-zip.py` PASS（本版块零全角、评分句唯一） |

## 升级方式

- **Marketplace（Stable 通道）**：正式版已去 `-beta.N` 后缀，装内测版的用户会正常收到更新提示。作者已于 2026-11-10 上传；**上传后有一段时间的审核期，审核通过前公开页面与 `GET /api/plugins/33633/updates` 仍显示上一版（2026.11.8），这是正常状态、不是上传失败**。
- **Gitee / GitHub**：发行页下载 zip 手动安装。
- `update/update.json` 由用户本人从 `preview.update.json` 提升后生效（官方通道清单）。
