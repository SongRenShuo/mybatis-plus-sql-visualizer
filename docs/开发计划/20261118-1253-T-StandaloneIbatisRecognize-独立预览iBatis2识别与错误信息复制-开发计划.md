# T-StandaloneIbatisRecognize：独立预览的 iBatis 2 识别 + 错误信息复制口径 开发计划

- 立项时间：2026-11-18 12:53
- 任务源（用户原话）：「并且xml识别的也有问题 …（贴出一段 iBatis 2 `<select>` + `<dynamic>`/`<isNotNull>` 语句）… 我点击复制错误信息还复制的不对：Unknown element <dynamic> in SQL statement.」
- 关联计划：
  - `20261114-1046-T-SqlPreviewStandalone-预览独立打开-开发计划.md`（本功能的立项；本次缺陷都在其 D-1b 检测门与面板错误链上）
  - `20261118-1247-T-StandaloneDialogOpen-独立预览弹框打开失效与空输入放开-开发计划.md`（同日立项，同文件；建议同批实施、同一分支）
- 状态：**根因已定位（代码级确证），待实施**

## 一、现状取证

### 1.1 用户输入是 iBatis 2 SQL Map 语句，不是 MyBatis 3

```xml
<select id="selectByCondition" parameterClass="User" resultMap="userResult">
  SELECT <include refid="userColumns"/> FROM T_USER
  <dynamic prepend="WHERE">
    <isNotNull prepend="AND " property="id">ID = #id#</isNotNull>
    <isNotEmpty prepend="AND " property="username">USER_NAME LIKE '%$username$%'</isNotEmpty>
    <isEqual prepend="AND " property="status" compareValue="1">STATUS = #status#</isEqual>
    <isGreaterThan prepend="AND " property="age" compareValue="18">AGE &gt; #age#</isGreaterThan>
  </dynamic>
  ORDER BY ID
</select>
```

四处都是 iBatis 2 专有形态：`parameterClass`（MyBatis 用 `parameterType`）、`<dynamic>`（MyBatis 用 `<where>/<if>/<choose>`）、`<is*>` 条件标签族、`#id#` / `$username$` 占位（MyBatis 是 `#{}` / `${}`）。

### 1.2 独立预览的检测门只有 MyBatis 一支（主因）

`ui/standalone/StandaloneSqlSourceResolver.kt`：

- `:326-327` 检测门正则只认 `<select|insert|update|delete … id=` —— 用户的片段**命中**（`id="selectByCondition"`），于是被判为「XML 语句块」；
- `:90-123` `resolveAsXml` 的四个候选尝试（原文 / 实体解码 / 包 `<mapper>` 全量 / 包 `<mapper>` 仅语句块）**全部只调 `XmlPreviewService.preview(...)`**（`:100`），即 MyBatis 3 链；
- 全文件无任何 iBatis 分支、无风味判定、无第二链回退。

MyBatis 链拿到 `<dynamic>` 直接抛：`xml/XmlScriptBuilder.kt:42` `else -> throw XmlScriptException("Unknown element <${child.tagName}> in SQL statement.")`，被 `XmlPreviewService` 转成带 warning 的空结果——用户看到的就是这句。

**插件本身完整支持 iBatis 2**，链路现成且成熟：

- `ui/IbatisXmlPreviewService.kt:83-97` `preview(fileText, statementId, statementTagName, statementOrdinal, project = null, parameterClass, moduleName)`——`project` 可空，纯文本可用；
- `xml/ibatis/SqlMapParser.kt:31-33` 要求根元素 `<sqlMap>`（对粘贴片段需像 MyBatis 侧那样包根）；
- 动态标签全覆盖：`IbatisModel.kt:57-62` 明列「14 个条件标签 + `iterate` + `dynamic`」；`<include refid>` 缺失片段降级为语句级 warning（`SqlMapParser.kt:15`），不抛。

### 1.3 「复制错误信息」复制的是裸解析错误

- 按钮：`ui/SqlPreviewPanelResultView.kt:53-58`，动作体 `copyError()`；
- `:1044-1048` `copyError()` = `UiTextClipboard.copy(currentWarning)`；
- `currentWarning` 由 `:848-855` `mergePreviewWarnings(result.warning, xmlErrors)` 拼成，规则是「；」连接 + 去重（`:698-700`）；
- 方言状态段（`方言未验证：渲染按 MyBatis 兜底…`）只在**显示层**拼接（`ui/DialectStatusRefresh.kt:255-257` `metaLineHead = dialectStatus.visible + "   " + metaCoreText`），**不进** warning。

即：剪贴板里拿到的就是 `Unknown element <dynamic> in SQL statement.` —— 一句英文、无来源（哪条链/哪个语句）、无建议（该怎么办），贴给同事或提 issue 都无法定位。用户的「复制得不对」，根因与 1.2 同源（这是误识别产生的**假错误**），另加一层「错误文案不可行动」。

## 二、根因结论

1. **识别层缺风味判定**：`<select>` 这个标签名在 MyBatis 3 与 iBatis 2 里**同名**，现有检测门只看标签名 + `id` 属性，无法区分；发现差异的动态子标签/属性/占位语法全部没有参与判定，且失败后没有第二链回退。
2. **错误信息层缺结构化**：面板只有 `warning` 一个自由文本槽位，误识别时把「引擎内部报错」直接当成用户可读错误；没有「来源链 / 语句 / 原因 / 建议」四段，也没有「该片段其实是 iBatis 2」这类**可行动**结论。

## 三、方案

### 3.1 识别：片段级风味判定 + 双链择优（推荐 B）

**方案 A（不推荐）**：只要命中 `<dynamic>` / `is*` 就走 iBatis。实现最省，但把「一个片段里既有 iBatis 又有 MyBatis 标签」的畸形输入判死，且未来 MyBatis 新标签出现时容易误判。

**方案 B（推荐）**：`StandaloneSqlSourceResolver` 内新增**片段级风味评分** `detectXmlFlavor(text): XmlFlavor`（纯逻辑，零平台依赖，可单测）：

| 信号 | iBatis 2 | MyBatis 3 |
|---|---|---|
| 根元素 | `sqlMap` / `sqlMapConfig` | `mapper` / `configuration` |
| DOCTYPE | `sql-map-2.dtd` / `SQL Map 2.0` | `mybatis-3-mapper.dtd` |
| 专有子标签 | `dynamic` / `isNull` / `isNotNull` / `isEmpty` / `isNotEmpty` / `isEqual` / `isNotEqual` / `isGreaterThan` / `isGreaterEqual` / `isLessThan` / `isLessEqual` / `isPropertyAvailable` / `isNotPropertyAvailable` / `isParameterPresent` / `iterate` | `if` / `foreach` / `choose` / `when` / `otherwise` / `where` / `set` / `trim` / `bind` |
| 专有属性 | `parameterClass` / `resultClass` / `parameterMap` / `listClass` / `compareValue` | `parameterType` / `statementType` |
| 占位语法 | `#name#` / `$name$` | `#{name}` / `${name}` |

- 标签名表**复用** `xml/ibatis/IbatisModel.kt` 的 `IbatisNodeKind` 枚举与 MyBatis 侧既有标签常量，不在解析器里另抄一份（避免两处口径漂移——这是本仓在册纪律）。
- **注意不能直接复用 `xml/ibatis/IbatisXmlRecognizer`**：它是**文件级三选二**（根 `sqlMap` / DOCTYPE / 工程 classpath 上有 `SqlMapClient`），粘贴片段三条都取不到（根是 `select`、无 DOCTYPE、无工程），必然判否。本次需要的是**片段级**判定，是新增能力，不是改既有识别器；但两处的标签/DTD 常量应同源。

**执行顺序（双链择优）**：

1. 先按评分选首选链（同分时 MyBatis 优先——保住既有行为，iBatis 需要证据才抢）；
2. 首选链产出「可用结果」即返回。可用判据：`xmlModel != null`（MyBatis）或 `ibatisParamModel != null`（iBatis）且 `warning == null`；
3. 否则跑另一条链，若其可用则返回它（并丢弃首选链的假错误）；
4. 两条都不可用 → 返回**首选链**结果，但 warning 走 §3.2 的结构化口径。

这样即便评分判错（例如片段只有 `<select id>` + 纯文本，无任何风味信号），也能靠回退救回来——用户这条语句会在第 3 步被 iBatis 链正确接管。

### 3.2 错误信息：结构化 + 可复制（推荐 B）

**方案 A**：只改 iBatis 识别，错误文案不动。用户的「复制得不对」在识别修好后自然消失（不再有假错误）——但**真失败**（比如既不是 MyBatis 也不是 iBatis 的 XML）仍然只会给一句英文。

**方案 B（推荐）**：识别失败/双链皆败时，`warning` 由解析器**组装成四段**（纯文本、多行），面板与 `copyError` 直接受益（不改 `copyError` 实现，风险最小）：

```
SQL 预览未能还原该片段
来源：独立预览工作台（粘贴文本，非工程文件）
语句：selectByCondition（<select>）
原因：Unknown element <dynamic> in SQL statement.
建议：该片段含 <dynamic>/<isNotNull> 等 iBatis 2 标签，但未能按 iBatis 2 解析；请检查是否缺少 <sqlMap> 根或片段不完整。
```

- 四段各自可缺省（缺就整行不出现），不得出现空标题行；
- 双语走 bundle（新增 4~5 个键），不在代码里写死中文；
- **不改 `copyError()`**：它继续复制 `currentWarning`，因此四段文本自然进剪贴板。这是本次最省的做法，也避免动到全部预览链共享的复制语义。
- 补充：`mergePreviewWarnings` 用「；」拼接多条错误（`:698-700`）。多段结构化的 warning 若再与 xmlErrors 合并会变成一坨。故结构化 warning 走**单条**（解析器侧已把 xmlErrors 收进「原因」行，返回的 `xmlErrors` 置空），面板侧合并自然退化为原文。

### 3.3 顺手项（低成本、同一处代码）

- 检测门 `STATEMENT_TAG_REGEX` 目前要求语句标签带 `id=`。iBatis 的 `<select id>` 有 id，无问题；但 iBatis 的 `<statement id>` 与 `<procedure id>` 不命中——建议把 `statement` / `procedure` 纳入（iBatis 专有标签，纳入不会误伤 MyBatis）。
- 包根逻辑（`:125-136` `wrapAsMapper`）需要对称新增 `wrapAsSqlMap`：iBatis 链要求根 `<sqlMap>`。

## 四、待验证前提（P-x）

- **P-1（iBatis 链在 project=null 下的完整性）**：`IbatisXmlPreviewService.preview(text, id, tagName, 0, project = null)` 对用户这段片段能否产出 `ibatisParamModel`（4 个条件行 + 参数行）与可用 SQL。代码级看是纯文本链，但 `<include refid="userColumns"/>` 缺片段时是否只降级为 warning 而不整体失败，需实测。
- **P-2（`$username$` 与 `#id#` 的参数名口径）**：用户片段里 `$username$` 是 iBatis 全局属性 token（本链不替换、按 dollarRows 风险行呈现），`#id#` 应转 `?` 并给参数名 `id`。需实测面板上「参数行」与「风险行」的实际形态，确认不会出现「参数没识别」。
- **P-3（风味评分的误判面）**：`<select id>` + 纯文本（无任何风味信号）→ 评分同分 → MyBatis 优先 → 若片段其实是 iBatis 且只用了 `#x#`，则靠双链回退救回。需用夹具钉住：①纯 iBatis 片段；②纯 MyBatis 片段；③MyBatis 片段里含 `#x#` 文本（在字符串字面量里）；④畸形混合片段。**③是反向风险点**——不能因为出现 `#x#` 就判 iBatis。
- **P-4（双链择优的代价）**：两条链各跑一遍，解析开销是否可接受（纯文本 DOM + 脚本树，毫秒级）。若某条链在超大片段上慢，需要上限保护（沿用 `MAX_TEXT_LENGTH = 100_000`）。
- **P-5（结构化 warning 与既有分档的交互）**：`informationalWarning` 为真时面板用 ℹ 前缀；结构化失败文本必须走 ⚠ 档（真失败），不得被信息级吞掉。另需确认 `mergePreviewWarnings` 的「；」不会把多行结构化文本切碎（多行文本里没有「；」即可，但要用测试钉住）。
- **P-6（复制内容实测）**：代码级已确认 `copyError` = 复制 `currentWarning`；仍需真机点一次，粘贴比对，确认剪贴板内容与面板完整 warning 一致（用户报「复制得不对」，需排除 `UiTextClipboard` 或显示层截断带来的二次失真）。

## 五、实施拆解

- **D-1 风味判定纯逻辑**（`StandaloneSqlSourceResolver.kt` 或新文件 `XmlFlavorDetector.kt`）
  - `enum XmlFlavor { MYBATIS, IBATIS }` + `detect(text): XmlFlavor`；
  - 标签/属性/DTD 常量复用既有枚举，不另抄；
  - 纯逻辑零平台依赖 → 自动进 testFast 快桶。
- **D-2 双链执行 + 择优**（`resolveAsXml` 重构）
  - `wrapAsMapper` / 新增 `wrapAsSqlMap`；
  - 候选序列：首选链（全量包根 → 仅语句块包根）→ 另一链（同序）→ 择优返回；
  - `Resolved` 增字段 `flavor: XmlFlavor`（状态条要如实说「识别为 iBatis 2 SQL Map 语句」还是「MyBatis mapper 语句」——用户最需要知道插件认成了什么）。
- **D-3 检测门补 `statement` / `procedure`**（iBatis 专有，纳入不误伤）。
- **D-4 结构化错误文本**
  - 新增 bundle 键（en/zh）：标题 / 来源 / 语句 / 原因 / 建议 五行；
  - 解析器侧组装成单条多行 warning，`xmlErrors` 置空（避免面板再拼一次）；
  - 面板侧**不改** `copyError`。
- **D-5 状态条与 i18n**：`standalone.status.xml` 之外新增 iBatis 分支文案（`识别为 iBatis 2 语句：{0}`）。
- **D-6 测试**
  - 纯逻辑单测（快桶）：风味判定四类夹具（P-3 清单）、包根两形态、`statement`/`procedure` 命中；
  - 端到端纯逻辑单测：用户这段 iBatis 片段 → `Resolved.kind == XML_STATEMENT`、`flavor == IBATIS`、`ibatisParamModel` 条件行数 = 4、`warning` 不含 `Unknown element`；
  - 反向验证（必做）：把风味判定改回「恒 MyBatis」→ 上述用例必须变红，贴两次读数；
  - MyBatis 回归：既有 23 例（`StandaloneSqlSourceResolverTest`）必须全绿——尤其「完整 mapper」「裸语句块包根」「同文 sql 片段 include」三例。
- **D-7 文档**：本计划 §八 回填；CHANGELOG `[Unreleased]` 加一句（iBatis 2 片段识别 + 错误信息可复制）。

## 六、验收（A-x）

- **A-1** 页签粘贴用户这段 iBatis 片段 → 点「解析预览」：状态条显示「识别为 iBatis 2 语句：selectByCondition」；SQL 区出现渲染后的 SELECT（`<dynamic>` 展开、`#id#` → `?`）；左栏出现 4 个条件行（`isNotNull(id)` / `isNotEmpty(username)` / `isEqual(status)` / `isGreaterThan(age)`）。
- **A-2** 同上片段点「在弹框中打开」→ 弹窗出现且内容一致（与 T-StandaloneDialogOpen 修复联动）。
- **A-3** 无 `<sql id="userColumns">` 片段时：warning 如实说明「include 片段未找到」但**不是** `Unknown element`，SQL 其余部分仍渲染。
- **A-4** 点「复制错误信息」：剪贴板为结构化四段文本（标题/来源/语句/原因/建议），不含方言候选标识（`session-0-x`）等元信息；可整段贴给他人定位。
- **A-5** 纯 MyBatis 片段（`<if>` / `#{x}` / `<foreach>`）回归：仍判 MyBatis，行为与本次改动前一致。
- **A-6** 反向验证读数在报告里贴全（D-6）。
- **A-7** GUI 最终验收由用户做（沙盒贴这段 XML 走一遍 A-1/A-4）。

not run 条款：真机手感与弹窗置顶由用户验收；P-4 的解析耗时只做量级确认（毫秒级），不做基准测试。

## 七、风险

- **风味判定的误判**：`#x#` 出现在 MyBatis 的字符串字面量里（如 `WHERE a = '#x#'`）会给出 iBatis 信号。缓解：占位信号必须**在字符串外**才计数（复用 `placeholderPositions` 同族的 string-aware 扫描口径）；且双链回退兜底。
- **双链都失败时的错误文本膨胀**：两条链的原因都要进「原因」行会很长。定案：只保留**首选链**原因 + 一句「另一条解析链（iBatis/MyBatis）同样未能解析」，不并列两条完整报错。
- **面板 warning 槽位的既有语义**：结构化文本是「多行」，`metaLabel` 是单行 + 中段省略，多行文本在 meta 行会显示成带换行的怪异形态。缓解：meta 行只用**首行**（标题），完整四段进横幅与剪贴板——需在 D-4 里明确，并加一条纯逻辑用例钉住「首行 = 标题」。
- **并行**：只动 `ui/standalone/` 与 bundle，撞车面小；`IbatisXmlPreviewService` / `XmlPreviewService` 为只读调用。

## 八、实施记录

**实施于 2026-11-18（master 直接实施），提交 `fdfee15a3`（与 T-StandaloneDialogOpen 同批）。**

### 8.1 落位清单

| 落点 | 内容 |
|---|---|
| `XmlFlavorDetector.kt`（新） | 片段级风味判定：根元素 / DOCTYPE / 专有标签 / 专有属性 / **string-aware 占位语法**五类加权，iBatis 严格高于 MyBatis 才判 iBatis，同分判 MyBatis |
| `StandaloneSqlSourceResolver.kt` | `runChain` 双链（MyBatis / iBatis）+ 首选链失败后**择优**；`wrapAsSqlMap` 包 `<sqlMap>` 根；检测门补 `statement`/`procedure`；`Resolved.flavor` / `Resolved.failure`；截断片段（无闭合标签）也回退扫首个语句标签取 id |
| `SqlStandalonePreviewPanel.kt` | 失败文案组装「标题｜来源｜语句｜原因｜建议」（单行 `｜` 分隔——`JBLabel` 不渲染 `\n`，多行会挤成一坨）；状态条区分「识别为 iBatis 2 语句」/「mapper XML 语句块」 |
| bundle（en/zh） | `standalone.status.ibatis(.multi)`、`standalone.error.*` 共 8 键 |

### 8.2 计划前提的落地校正

- **P-5（结构化 warning 与面板合并）**：比计划更简单——`xmlErrors` 是**面板字段**而非 `PreviewResult` 字段，且只在 `xmlModel != null` 时参与合并（`SqlPreviewPanelResultView.kt:851-854`）；解析失败时 `xmlModel == null`，`result.warning` 即唯一来源，不存在「被 `；` 拼碎」的问题。故 `xmlErrors` 置空这一步不需要。
- **计划里的多行文案改为单行 `｜` 分段**：`JBLabel`（meta 行）与 `DegradationBanner` 都是纯文本 JLabel，不渲染 `\n`——多行会挤成一行且丢分隔。单行分段在 meta / 横幅 / 剪贴板三处形态一致。
- **失败文案组装放在面板而非解析器**：解析器保持零 bundle 依赖（快桶纯逻辑单测直接驱动），`Failure` 只承载事实（flavor / statementId / tagName / reason / otherChainFailed）。

### 8.3 验证读数

- **纯逻辑（快桶）**：`XmlFlavorDetectorTest` 9/9、`StandaloneSqlSourceResolverTest` 29/29（含用户原样片段 → 4 个条件行、`#id#` → 参数名 `id`、双链回退接管 `<statement>`、截断片段失败事实）。
- **平台桶**：`SqlStandalonePreviewPanelDialogTest` 4/4（含 iBatis 片段弹窗且不含 `Unknown element`）。
- **反向验证（两次读数）**：把 `detect()` 退化成恒 `MYBATIS` → `XmlFlavorDetectorTest` **3 红**、`StandaloneSqlSourceResolverTest` **恰 1 红**（`无动态标签的 iBatis 片段靠占位语法判对且参数名正确`）；恢复后全绿。
- **反向验证暴露的诚实结论**：双链回退能兜住「MyBatis 解析报错」的 iBatis 片段（`<dynamic>` 类），但**兜不住「MyBatis 解析成功却解析错」**的（无动态标签、只有 `#id#`——MyBatis 把 `#id#` 当普通文本、无参数）。那条用例就是风味判定的不可替代性证据，已写进用例注释。
- **not run**：真机 GUI 手感（A-1/A-2/A-4）由用户验收；P-4 解析耗时只做量级判断（两链皆毫秒级），未做基准。

### 8.4 遗留

- `IbatisXmlRecognizer`（文件级三选二）与 `XmlFlavorDetector`（片段级）**标签/DTD 名单目前是两份**，已各自写明同源纪律；后续新增 iBatis 标签需两处一起加（未做自动守卫，登记为技术债）。
