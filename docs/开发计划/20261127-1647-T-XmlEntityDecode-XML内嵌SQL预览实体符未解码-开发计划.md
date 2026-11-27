# T-XmlEntityDecode — XML 内嵌 SQL 预览把 `&gt;`/`&lt;` 原样带进产物 — 开发计划

- 立项时间：2026-11-27 16:47
- 触发：用户报障（截图：`<sql id="orderSideMaxUpdateTimeUnion">` 片段预览，编辑器区显示
  `WHERE o.update_time &gt;= #{fromTime}`，红波浪线报错；箭头指向 XML 源里的 `&gt;=`）
- 归属：`master`（本册在 master 实施；分支名即当前工作分支）
- 性质：**缺陷修复**（预览/复制的 SQL 与真机执行语义不一致）
- **状态：实施中（2026-11-27）**

---

## 一、现象与证据

### 1.1 用户可见现象

XML 里写 `WHERE o.update_time &gt;= #{fromTime}`（合法 XML，实体形态），点 gutter 图标预览该
`<sql id>` 片段，预览面板 SQL 编辑区出现：

```
WHERE o.update_time &gt;= #{fromTime}
AND o.update_time &lt;= #{cutoffTime}
```

而不是真机执行时的 `>=` / `<=`。点「复制 SQL」「执行 SQL」都会把 `&gt;=` 送进数据库 → 语法错误
（截图里编辑器的红波浪线即语法校验报错）。

### 1.2 复现（已红，读数在案）

新增 `src/test/kotlin/cn/song/mybatisplus/visualizer/xml/XmlDirectTextTest.kt`（7 例），
`./gradlew test --tests "cn.song.mybatisplus.visualizer.xml.XmlDirectTextTest"`：

```
7 tests completed, 5 failed
```

| 用例 | 期望 | 实测 |
|---|---|---|
| 比较运算符实体_普通文本段解码 | `WHERE a >= #{fromTime} AND b <= #{toTime}` | `WHERE a &gt;= … &lt;= …` |
| 五标准实体与数字引用_全部还原 | `< > & " ' > <` | `&lt; &gt; &amp; &quot; &apos; &#62; &#x3C;`（`&quot;` 被 PSI 解掉，其余原样） |
| 只解码一次_双重转义保持字面 | `a &gt;= b` | `a &amp;gt;= b` |
| 普通文本与CDATA混排_各按各的口径 | 普通段解码、CDATA 段字面 | 普通段 `&gt;=` 未解码（CDATA 段恰好正确） |
| 子标签内容不混入 | `A > 0` | `A &gt; 0` |
| CDATA段_字面量不解码 | `WHERE a &gt;= 1` | ✅ 通过（现状即字面） |
| 未知实体_原样保留 | `a &nbsp; b` | ✅ 通过（现状即原样） |

两条「通过」是反向锚点：修复必须**不破坏**「CDATA 内字面量不解码」与「未知实体保留原文」。

### 1.3 根因（代码级，非推断）

`src/main/kotlin/cn/song/mybatisplus/visualizer/xml/XmlDirectText.kt`：

```kotlin
internal fun unwrapCdata(text: String): String = text.replace(CDATA_WRAPPER, "$1")

internal fun directTextOf(tag: XmlTag): String =
    tag.children
        .filterIsInstance<XmlText>()
        .joinToString("") { unwrapCdata(it.text) }   // ← 只剥 CDATA 包裹，从不做实体解码
        .trim()
```

平台 `XmlText.text` 返回的是 **PSI 源文本**（实测读数见 §1.2：`&lt;` `&gt;` `&amp;` `&apos;`
`&#62;` `&#x3C;` 全部原样，仅 `&quot;` 被平台提前解掉）。MyBatis 运行时走 XML 解析器（DOM），
文本节点由解析器还原实体，真机 SQL 得到 `>=`——**预览链与真机链在文本层就分叉了**。

### 1.4 为什么 mapper 语句预览没报障

mapper `<select|insert|update|delete>` 走 DOM 链：`XmlMapperParser` → `SecureXml.parse`（DOM，
默认展开实体引用）→ `element.textContent` / `XmlScriptBuilder` 的 `Node.TEXT_NODE.nodeValue`
均已解码。既有测试 `XmlPreviewPanelTest:249` 的 `&gt;=` 样本断言 `>=` 正是这条链的绿证。

`<sql id>` 片段**不属于** mapper 语句白名单（`STATEMENT_TAGS` 只含 select/insert/update/delete），
被 `XmlInlineSqlDetector` 的「通用内嵌 SQL」兜底通道认领 → `XmlInlineSqlPreviewService` →
`directTextOf` → **PSI 原文直取**，于是漏解码。

---

## 二、影响面（同一根因的全部消费者）

`directTextOf` / `unwrapCdata` 的消费点（grep 全仓）：

| 消费方 | 用户可见后果 |
|---|---|
| `XmlInlineSqlDetector.extractSqlText` → `XmlInlineSqlPreviewService.preview` | 本次报障：`<sql id>` 片段、`<datasource>`/`<statement>` 等通用载体预览/复制/执行 |
| `ConvertToSqlAction.previewXmlTag` 的 inline 分支（`:847`） | 选择区/光标「转换 SQL」同一产物 |
| `XmlInlineSqlGutterLineMarkerProvider` 点击 | 同上 |
| `HbmXmlEntityResolver.extractNamedQueryText` → `SqlPreviewService.previewHbmNamedQueryTag` | hbm `<query>`/`<sql-query>` 命名查询预览（含 HQL/原生 SQL） |
| `XmlInlineSqlDetector.firstNonBlankTextPrefix`（`unwrapCdata`） | 首词判定——实体不影响首词，**不改** |

不受影响（已证）：mapper 语句 DOM 链、`XmlIncludeExpander`（DOM 就地修改）、跨命名空间片段
（`serializeChildren` 重新转义再解析）、iBatis 链（`SqlMapParser` DOM）、pages 链（`PagesXmlParser`
DOM）、MCP `sqlv_render_sql` 的 mapper 分支（DOM）。

### 2.1 同类问题排查（用户 2026-11-27 要求「同类一起修」）

全仓逐个入口核对「XML 源文本 → 用户可见 SQL」的路径：

| 入口 | 取文本方式 | 结论 |
|---|---|---|
| 通用内嵌 SQL（inline） | PSI `XmlText.text` | **本缺陷**，已修 |
| hbm 命名查询 | PSI `XmlText.text`（同 `directTextOf`） | **同缺陷**，随主修一起修好 |
| mapper 语句 / 注解 `<script>` / iBatis / pages / MCP | DOM（`SecureXml.parse`） | 正确，无需改 |
| 跨命名空间片段传递 | DOM → `serializeChildren` 重新转义 → 再 DOM | 正确（转义-解析往返），无需改 |
| 独立工作台粘贴（`StandaloneSqlSourceResolver`） | 自写 `replace` 链 | **同类缺口**：不认数字引用（`&#62;`）、CDATA 内会被误解；实现已统一到 [decodeXmlTextSegment] |
| 配置文件修复（`WorkspaceJsonPropertyRepair`） | 自写单趟解码（含数字引用） | 与本次无关（IDE 配置域），实现本身正确，保留 |
| 编辑器内补全（`SqlTextAnalysis.stripXmlTags`） | PSI 文档原文 | 需要等长偏移映射，**不得解码**（解码会改变偏移），保持 |
| PSI 属性值（`id`/`name`/`type`/`entityClass`/`compareValue` 等） | `getAttributeValue` | 承载值均为标识符；动态表达式与 SQL 属性全在 DOM 链消费，无实体面 |
| 生成 HTML 报告的转义（`HtmlReportFormatter` 等） | 输出方向 `escape` | 与本次方向相反（输出加固），保留 |

---

## 三、方案

在 `XmlDirectText.kt` 内新增 **CDATA 感知的实体解码**，替换 `directTextOf` 的拼接口径：

```
decodeXmlTextSegment(raw):
  扫描 <![CDATA[ … ]]> 区间
    区间外 → decodeXmlEntities（&lt; &gt; &amp; &quot; &apos; + 十进制/十六进制字符引用）
    区间内 → 原样保留（含 & 字符，绝不解码）
  未闭合的 <![CDATA[ → 其后全部原样（PSI 容错文本，不猜）
```

判据与约束：

1. **只解码一次**：`&amp;gt;` → `&gt;`（不再还原成 `>`）。实现按单趟扫描、命中即替换，
   绝不做「先替换 amp 再整体重扫」。
2. **CDATA 不解码**：与 DOM 链（`PagesXmlParserTest`「CDATA 内文本为字面量」）和 MyBatis 行为一致。
3. **未知实体保留原文**：`&nbsp;` 等非 XML 标准实体不猜解（MyBatis 只认 DTD 声明的实体，
   未声明会解析失败，预览侧保留原文比猜成空格更接近事实）。
4. **非法字符引用保留原文**：`&#0;` / 越界码点 / 空 `&#;` 不抛异常、不改写。
5. **幂等性**：对已解码文本再调一次，结果不变（不含 `&xxx;` 形态时原样返回）。
6. `unwrapCdata` 保留（`firstNonBlankTextPrefix` 的首词判定仍用），但 `directTextOf` 改走新函数。

---

## 四、实施步骤

1. `XmlDirectText.kt`：新增 `decodeXmlTextSegment` + `decodeXmlEntities`（私有）+ 数值引用解码；
   `directTextOf` 改用 `decodeXmlTextSegment(it.text)`。
2. 测试：
   - 新增 `XmlDirectTextTest`（7 例，已先红，见 §1.2）覆盖实体/数字引用/只解一次/CDATA/混排/未知实体/子标签排除；
   - `XmlInlineSqlPreviewServiceTest` 加端到端一例：`<sql id>` 片段（普通文本 + `&gt;=`）经
     `preview(null, tag)` 后 `result.sql` 含 `>=`；
   - `HbmNamedQueryResolverTest` 加一例：hbm `<sql-query>` 文本 `&gt;=` → `>=`。
3. 反向验证：把 `decodeXmlTextSegment` 的调用改回 `unwrapCdata`（或等价摘除解码分支），
   贴「红」读数；还原后贴「绿」读数。
4. 文档：`CHANGELOG.md` `[Unreleased]` 一条（带提交哈希）；本册实施记录回填；`docs/索引/开发计划.md` 一行。

---

## 五、验收

| 门 | 判据 |
|---|---|
| 定向测试 | `XmlDirectTextTest` 7/7、`XmlInlineSqlPreviewServiceTest`、`HbmNamedQueryResolverTest` 全绿（读数贴 build/test-results XML 计数，不写「全绿」两字） |
| 反向验证 | 摘掉解码 → `XmlDirectTextTest` 至少 5 红；还原 → 7 绿 |
| 不回归 | mapper 语句 DOM 链既有用例（`XmlPreviewPanelTest` 的 `&gt;=` 断言）保持绿 |
| 真机（留档） | runIde 沙盒打开用户同形态 `<sql id>` 片段，预览面板显示 `>=`——**本册实施阶段未跑**，列入待验证前提 |

---

## 六、待验证前提（实施者先证伪再动手）

1. 前提：`XmlText.text` 对 `&quot;` 已解码、对其余实体保留原文。→ 已由 §1.2 实测确认（`&quot;` 解掉）。
   即便平台行为随版本变化，本方案只做「解码已有实体」，对已解码文本幂等。
2. 前提：CDATA 在 `XmlText.text` 里是「带 `<![CDATA[ ]]>` 包裹的字面全文」（O-72 实测，本文件
   KDoc 在案）。→ 本方案按此扫描；若平台改为已展开内容，则「区间内不解码」退化为「无区间」，
   仍不解码 CDATA（因探不到标记），语义不倒退。
3. 前提：mapper `<sql id>` 片段确实走通用内嵌 SQL 链而非独立片段链。→ `isClaimedByKnownStructure`
   只认领 select/insert/update/delete + hbm + pages + iBatis，`<sql>` 落兜底；`ConvertToSqlAction:847`
   与 gutter provider 两处入口一致。已读码确认。

---

## 七、实施记录（收口时回填）

- （待填）改动文件、提交哈希、定向读数、反向验证读数、not run 清单。