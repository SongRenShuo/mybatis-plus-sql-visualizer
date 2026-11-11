# T-SqlOperatorFidelity：格式化把 `||` 等多字符运算符拆成两半，预览/复制/执行的 SQL 失真 — 开发计划

- 日期：2026-11-11 17:10
- 任务编号：T-SqlOperatorFidelity
- 分支 / 工作树：`t-sql-operator-fidelity` / `.worktrees/t-sql-operator-fidelity`（从 `master` @ `4d9494561` 切出）
- 来源：用户 2026-11-11 真机反馈（两张截图，见 §2.1）「两个 `||` 中间多加了一个空格」
- 性质：缺陷（**产物失真**，不是排版偏好）——预览显示、复制可执行 SQL、直接执行三条链共用同一份被改坏的文本
- 前置：无硬前置。相关既有件＝`ui/highlight/SqlLexer`（分词）、`ui/highlight/SqlFormatter`（三条格式化路径）、`ui/SqlPreviewPanelSqlView`（显示模式分派）、`ui/components/CopyExecutableSqlAction` + `share/SqlShareManager`（复制/分享）、`ui/SqlPreviewPanelExecution`（执行）
- 红线：不改格式化器的空格规则（`运算符两侧空格` 是既有约定，改了会牵动上百条既有期望值）；不动执行链、不动参数回填链、不动 iBatis/MyBatis 占位符合并逻辑（`#x#` / `#{x}` / `${x}`）

---

## 一、需求拆解

一句话：用户 SQL 里的 `'%' || #GROUP_NAME# || '%'`（Oracle/达梦/PG 字符串拼接）在预览里被显示成 `'%' | | '弘博' | | '%'`——**一个 `||` 运算符被拆成了两个 `|` 字符、中间补了一个空格**。这不是「多打了个空格」的观感问题：`| |` 在 Oracle 里是语法错误，而预览面板的编辑器文本同时就是复制与执行的输入源。

做三件事：

1. **修根因**：词法器把多字符运算符当成两个单字符 token，格式化器按「运算符两侧空格」规则拼回时就地把运算符劈开。改成词法器按**最长匹配**整体吐一个 token。
2. **同类面一起修**：`||` 不是孤例——`::`（PG/GaussDB/Vastbase 强转）、`<=>`（MySQL 空安全等值）、`->`/`->>`（JSON）、`&&`、`<<`、`>>`、`:=`、`@>`、`<@`、`!~`、`~*`、`=>` 全部同因同形。逐个造用例，一次性收口。
3. **钉住不回归**：三条格式化路径（`format` / `formatClauseLine` / `formatVertical`）各来一条带 `||` 的用例；词法器来一条「一个运算符 = 一个 token」的用例。

不做：不重排格式化器的空格/换行规则；不改 `?` 占位符、`${}`/`#{}`/`#x#` 的既有合并口径；不动 `-1` 这类一元符号的空格（`x = - 1` 仍可执行，属观感，另案）；不动日志面板的解析/还原链（它们只是把已还原文本交给同一个格式化器）。

## 二、复现与证据

### 2.1 用户截图（真机，iBatis `<isNotEmpty>` 内的 LIKE）

- 源 XML：`<isNotEmpty prepend="AND" property="GROUP_NAME"> GROUP_NAME LIKE '%' || #GROUP_NAME# || '%' </isNotEmpty>`
- 预览面板（格式化态）：`AND GROUP_NAME LIKE '%' | | '弘博' | | '%'`，参数面板 `#param1 = 弘博`

即：占位符回填正确（`'弘博'` 有引号、位置正确），**只有运算符被劈开**——说明坏在「回填之后、显示之前」的格式化层。

### 2.2 根因（源码级）

**词法器只合并 4 个双字符运算符**（`src/main/kotlin/.../ui/highlight/SqlLexer.kt:165`）：

```kotlin
private fun scanOperator() {
    myTokenEnd++
    when {
        cAt(myTokenStart) == '!' && cAt(myTokenStart + 1) == '=' -> myTokenEnd++
        cAt(myTokenStart) == '<' && (cAt(myTokenStart + 1) == '=' || cAt(myTokenStart + 1) == '>') -> myTokenEnd++
        cAt(myTokenStart) == '>' && cAt(myTokenStart + 1) == '=' -> myTokenEnd++
    }
    myTokenType = SqlTokenTypes.OPERATOR
    ...
}
```

`|` 不在名单里 → `||` 被切成 `|` + `|` 两个 `OPERATOR` token。

**格式化器对每个 `OPERATOR` token 一律「前补空格、后置空格待补」**（`SqlFormatter.kt:218-224`、`:381-388`；竖排路径 `renderElem` 同一语义）：

```kotlin
type == SqlTokenTypes.OPERATOR -> {
    flushSpace()          // 第二个 `|` 前面补一个空格
    sb.append(text)
    pendingSpace = true   // 下一个 token 前再补一个空格
}
```

于是 `|` + `|` 拼成 `| |`。这正是用户看到的形态，逐字吻合。

**为什么这条链路上是致命的**：面板编辑器里的文本就是下游三处的输入源——

| 下游 | 取值点 | 后果 |
|---|---|---|
| 复制可执行 SQL | `CopyExecutableSqlAction.kt:168` → `SqlPreviewPanel.editorDocumentText()`（`SqlPreviewPanel.kt:1144` = `editor.document.text`） | 剪贴板里是不可执行 SQL |
| 分享（复制/导出/评论） | `share/SqlShareManager.kt:36 / :57 / :81`、`share/ui/ShareDialog.kt:139 / :164 / :201` 同走 `editorDocumentText()` | 同上 |
| 直接执行 | `ui/SqlPreviewPanelExecution.kt:1261-1266`（`nativeEditorDoc?.text` → `editor?.document?.text`） | 语法错（Oracle 报 `ORA-00920` 一类的非法运算符），用户以为插件'生成错了 SQL' |

**默认就命中**：`PluginSettings.kt:51` `formatSql: Boolean = true`，即安装后默认格式化输出；`SqlPreviewPanelSqlView.kt:422-425` 四种显示模式里 NORMAL（默认）/ FORMAT / KEYWORD_WRAP / VERTICAL 全部走格式化器，`formatVertical` 也共用同一套 token。

### 2.3 为什么此前没被发现

- 词法器的已知保真缺口此前修过两例，都是**同一个 class 的不同表现**：双引号字符串被拆成 `"`+`1`+`"` 拼出 `" 1 "`（`SqlLexer.kt:63-65` 的 `scanDoubleQuoted`，T-M29.3）；`#x#`/`#{x}`/`${x}` 占位符被拆开拼出 `# username #`（`SqlFormatter.kt:1077+` 的 `mergeParamPlaceholders`，T-IbatisFix2）。
- 前两例都是在**看到具体形态后**就地补的口子，没有回到「词法器该按最长匹配吐运算符」这个层面收口，所以 `||` 这一类至今裸露。
- 测试面缺口：`SqlLexerTest` / `SqlFormatterTest` 里 `grep "||"` 零命中——没有任何用例覆盖多字符运算符。

## 三、影响面

| 面 | 是否受影响 | 说明 |
|---|---|---|
| SQL 预览面板显示 | ✅ | 默认开格式化；`||`→`| |`，`::`→`: :`，`<=>`→`<= >`，`&&`→`& &` 等 |
| 复制可执行 SQL | ✅ | 取面板编辑器文本，产物直接带出坏 SQL |
| 直接执行 / EXPLAIN | ✅ | 同上，坏 SQL 进数据库 |
| 分享面板（复制/导出/评论） | ✅ | 同取 `editorDocumentText()` |
| SQL 日志面板（条目格式化、点击预览、最近执行记录） | ✅（显示） | `SqlFormatter.format` 同源；日志条目复制若取 `restoredSql` 则不受影响（需在实施第一步核一遍 `LogToSqlPanel` / `SqlExportService` 的取文本点） |
| JdbcTemplate / 注解预览 | ✅（显示+执行） | `JdbcTemplateSqlPreviewService.kt:108` 直接把 `SqlFormatter.format` 的产物当预览 SQL |
| iBatis / MyBatis XML 渲染本身 | ❌ | 渲染与占位符合并不碰运算符；坏文本是格式化阶段产生的 |
| 参数回填（`?`、`#x#`、`:name`） | ❌ | `:name` 在 JPA 原生链上已被 `NATIVE_NAMED_PARAM`（含 `(?<!:)` 前视）换成 `?`，不会以 `:` 形态进入格式化器 |

## 四、同类面审计（用户要求「同类问题一起修」）

判据：**词法器吐出的 token 边界 = 一个语义原子**。按此把「会被拆开」的运算符逐个列出（`现状` 一列由代码推演，实施第一步用红用例钉住实际读数）：

| 运算符 | 语义 / 方言 | 现状（会把一个原子拆成两个 token） | 期望 |
|---|---|---|---|
| `||` | 字符串拼接：Oracle/达梦/金仓/PG/SQLite；MySQL 逻辑或 | `| |` | 整体 `||` |
| `::` | PG/GaussDB/Vastbase/瀚高 强转 `x::numeric` | `: :` | 整体 `::` |
| `:=` | Oracle 赋值、MySQL 变量赋值 | `: =` | 整体 `:=` |
| `<=>` | MySQL 空安全等值 | `<= >`（`<=` 命中了既有合并，`>` 另起） | 整体 `<=>` |
| `->` / `->>` | JSON 取值：MySQL / PG | `- >` / `- > >` | 整体 |
| `&&` | MySQL 逻辑与 / PG 数组重叠 | `& &` | 整体 |
| `<<` / `>>` | MySQL 位移 | `< <` / `> >` | 整体 |
| `!~` / `~*` | PG 正则不匹配 / 大小写不敏感匹配 | `! ~` / `~ *` | 整体 |
| `@>` / `<@` | PG jsonb/数组包含 | `@ >` / `< @` | 整体 |
| `=>` | Oracle/PL 具名实参 `proc(a => 1)` | `= >` | 整体 |
| `!=` `<>` `<=` `>=` | 既有 4 个 | ✅ 已整体 | 保持不动（产物逐字节不变） |

**明确排除（附理由，避免下一轮又当成遗漏）**：

| 排除项 | 理由 |
|---|---|
| `#>` / `#>>`（PG jsonb） | 与 iBatis 内联参数 `#id#` 的**闭合 `#`** 冲突：`WHERE id#>0` 会把闭合 `#` 与 `>` 并成一个 token，`mergeParamPlaceholders` 再也配不上闭合界（`SqlFormatter.kt` 的 `matchHashClose` 只认 `OPERATOR "#"`）。收益极低、回归面真实，不合并 |
| `?|` / `?&`（PG jsonb 存在性） | `?` 是**未解析参数占位符**（本体语义、`placeholderPositions` 与回填链都按它记账），与 `|`/`&` 合并会和占位符口径打架；且 `?` 后紧跟 `|` 的场景在插件产物里可忽略 |
| `#-`（PG jsonb 删键） | 同 `#>` 的闭合 `#` 冲突 |
| `..`（Oracle PL/SQL 区间） | 不在插件格式化的 SQL 面（DML/DDL/查询）内；且 `a..b` 与数字 `1.5` 的 `scanNumber` 已吃 `.` 的规则相邻，风险大于收益 |
| `E'...'` / `$tag$...$tag$`（PG 转义串与美元引号） | 属**字面量**类别而非运算符，是另一个 class（`scanString` 范畴）；本轮只在计划里登记，不夹带 |
| `-1` / `+1` 一元符号 | 拼成 `- 1` 仍可执行，属观感；且 `-` 与 `--` 注释判定相邻，另案 |

## 五、方案

### 5.1 词法器：最长匹配的运算符表（唯一改点）

`SqlLexer.scanOperator` 改为按表最长匹配：

```kotlin
/** 多字符运算符（**长的在前**，最长匹配优先）。单字符走兜底。 */
private val MULTI_CHAR_OPERATORS = listOf(
    "<=>", "->>",                                   // 3 字符
    "!=", "<>", "<=", ">=", "||", "::", ":=",       // 2 字符
    "->", "&&", "<<", ">>", "!~", "~*", "@>", "<@", "=>",
)

private fun scanOperator() {
    for (op in MULTI_CHAR_OPERATORS) {
        if (matchesAt(myTokenStart, op)) {
            myTokenEnd = myTokenStart + op.length
            myTokenType = SqlTokenTypes.OPERATOR
            myTableState = 0
            return
        }
    }
    myTokenEnd++
    myTokenType = SqlTokenTypes.OPERATOR
    myTableState = 0
}
```

要点：

- **只在词法器改**：三条格式化路径（`format` / `formatClauseLine` / `formatVertical`）共用 `lex()`，一处生效三处；`mergeParamPlaceholders` 的既有契约（`#x#`/`#{x}`/`${x}`）不受影响——它只看 `#`、`$`、`{`、`}`，这些不在新表里。
- **既有 4 个运算符的产物逐字节不变**：`!=`/`<>`/`<=`/`>=` 的匹配结果与旧实现等价（旧实现也是整体合并，只是判据散在 `when` 里）。
- **不动格式化器的空格规则**：`| |` 的拼法是「两个 token 各自需要空格」的结果，token 变一个就没有中间空格，规则本身不需要例外。

### 5.2 测试（三条路径 + 词法层 + 真机原样语料）

| 编号 | 用例 | 断言 |
|---|---|---|
| T1 | `SqlLexerTest`：`"%' || x || '%'"`、`a::int`、`a<=>b`、`a->>'k'`、`a&&b`、`a<<2`、`a~*b` 等 | 每个运算符**单 token**；`!=`/`<=`/`>=`/`<>` 维持既有断言 |
| T2 | 用户原样语料：`... GROUP_NAME LIKE '%' || #GROUP_NAME# || '%'` | `format` 产物含 `'%' || #GROUP_NAME# || '%'`，且**不含 `| |`**；`#GROUP_NAME#` 整段原样 |
| T3 | 三条路径各一条：`format` / `formatClauseLine` / `formatVertical` | 产物含 `||`，不含 `| |` |
| T4 | 同类运算符矩阵（PG 强转 / JSON / MySQL 空安全等值 / PG 正则 / 包含） | 各运算符在 `format` 产物里整体保留 |
| T5 | 回归守卫：`#ids[0]#`（带下标的 iBatis 参数）、`#{x}`、`${x}`、`a != b`、`a <= b` | 产物与改前逐字节一致（防合并表把它们吃掉） |
| T6 | 排除项负向守卫：`#id#>0`（无空格）仍按 iBatis 闭合 `#` 配对 | 证明「不合并 `#>`」这条决定是必要的，也钉住现状 |

### 5.3 反向验证

按仓规「守卫类改动必做反向验证」：**先提交修复**（避免 `git checkout --` 连带还原未提交改动，见 LESSONS 的 `pitfall-git-checkout-reverts-uncommitted-probe`），再用 `Edit` 把 `scanOperator` 临时改回旧实现 → T1/T2/T3/T4 必须变红（贴读数）→ 改回 → 全绿（贴读数）。

## 六、验证计划

- 定向：`./gradlew test --tests "cn.song.mybatisplus.visualizer.ui.highlight.SqlLexerTest" --tests "cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatterTest" --tests "cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatterOperatorFidelityTest" --tests "cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatterIbatisTokenTest" --tests "cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatterVerticalTest"`（格式化器同族全带，防期望值被牵动）。
- 交叉面（选择性）：`ExecuteSqlSupportTest`、`SqlPreviewPanelDisplayEditorTest`、`ExecutableSqlFormatterTest`（这些是「复制/执行取文本」的直连用例）。
- 不跑：`testShards` 全量 / `verifyGate` / `verifyPlugin` / `clean` / `--stop`（按派活纪律，交给统一收口代理）。
- **not run（如实登记）**：真机 runIde 验收（面板里 `||` 是否原样显示、复制出的 SQL 能否在 Oracle/达梦上执行）——交付时列编号步骤交给用户自验，不冒充已验证。

## 七、风险与回退

| 风险 | 评估 | 处置 |
|---|---|---|
| 新合并表误伤既有语料（把两个本应分开的字符并成一个 token） | 中低：表中的组合在两字符写法下都不是合法 SQL 的「两个独立运算符」序列（`a < @b`、`a & &b` 这类不成立） | T5 回归守卫 + T6 负向守卫；若某条既有期望值变红，逐个判定是「本来就该合」还是「不该合」 |
| `#>` 排除后，PG jsonb 用户继续看到 `# >` | 已知残余 | 计划内明示；等 iBatis `#x#` 闭合界改成「按占位符上下文配对」后再解 |
| 格式化器空格规则被误改 | 无（本轮不动 `SqlFormatter` 的运算符分支） | 代码审查点：diff 只允许落在 `SqlLexer` |
| 下游取文本点漏改（本轮不需要改，但要确认没有第四条取文本链） | 低 | 实施第一步 `grep editorDocumentText` / `nativeEditorDoc` 收口核对；发现新增链只登记不扩改 |

## 八、实施步骤

| 步 | 内容 | 完成判据 |
|---|---|---|
| S1 | 写 T1~T6 用例（先红），跑一次定向拿到「拆开」的真实读数 | 红用例逐条列出实际产物（`| |`、`: :` 等） |
| S2 | 改 `SqlLexer.scanOperator` 为最长匹配表 | T1~T4 转绿，T5 逐字节不变 |
| S3 | 反向验证（临时回退 → 红 → 恢复 → 绿） | 两次读数都贴进实施记录 |
| S4 | 同族格式化器用例全带跑一遍 | 无既有期望值被牵动 |
| S5 | 回填 `CHANGELOG.md` `[Unreleased]`（带提交哈希）、本文件 §九、`docs/索引/开发计划.md` | 三处齐 |

## 九、待验证前提（实施者先证伪再动手）

1. **P1**：`||` 的坏化只发生在格式化层，iBatis 渲染本身不插空格。→ 用 T2 的 `compactSql`/未格式化态各跑一次比对（若渲染层也坏，方案要扩到 `xml/ibatis` 链）。
2. **P2**：`SqlFormatter.formatClauseLine` 与 `formatVertical` 确实共用 `lex()`（同一份 token）。→ 读 `SqlFormatter.kt:264` / `:438` 两处 `lex` 调用点确认；T3 用例兜底。
3. **P3**：日志面板的「复制」取的是 `restoredSql` 而非格式化文本（即复制链不受影响）。→ `grep` 取证后回填 §三 表格该行。
4. **P4**：`scanNumber` 不吃掉 `||` 邻接的数字（`1||2`）。→ T1 里加一条 `1||2` 形态。

## 十、实施记录（收口回填）

（待实施后回填：提交哈希、定向读数、反向验证读数、not run 清单。）