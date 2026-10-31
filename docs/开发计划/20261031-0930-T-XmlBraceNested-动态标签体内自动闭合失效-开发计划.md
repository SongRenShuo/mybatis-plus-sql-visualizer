# T-XmlBraceNested：mapper XML 动态标签体内 `#`/`$` 自动闭合失效修复计划

> 2026-10-31 立项。来源＝R15 演示动图补录实测（文档站 20261030-1545 计划 N2）：在 2026.2 沙盒
> `UserMapper.xml` 的 `<where><if>` 体内行尾敲 `#`，只落裸 `#`、不补 `{}`。本文先证伪再动手。

## 一、结论（谁坏谁没坏）

| 站点声称能力 | 判定 | 依据 |
|---|---|---|
| `#`/`$` 自动补 `{}` | **插件缺陷，本计划修** | 根因见 §二；`UserMapper.xml` 的真实排版（`<where><if>` 嵌套）100% 命中 |
| `#{` 结构属性补全（jdbcType 等） | 未复现≠缺陷 | 贡献器分支在（`XmlSqlCompletionContributor` e 分支）；首轮探针键序用错（`#{j` 无逗号走「参数名」分支，无 j 开头参数＝零候选）。修复后用 `#{status,j` 复测 |
| 标签属性值补全（refid→sql id） | 未复现≠缺陷 | b 分支在（`PsiTreeUtil.getParentOfType(position, XmlAttributeValue)`）；首轮探针 Home 后连按 Left 落到了上一行行尾。修复后按正确落点复测 |
| 动态标签折叠 | 非缺陷 | `<if>`/`<where>` 折叠是**平台** XmlFoldingBuilder 既有行为；本插件 `SqlMapperFoldingBuilder` 只做「语句标签带 id 占位」（KDoc 自述与 plugin.xml 注释一致）。折叠判据在 §五.4 重验 |

## 二、根因（代码级）

`ParamBraceTypedHandler.inStatementText`（`ui/inject/ParamBraceTypedHandler.kt`）：

```kotlin
if (tag.name in STATEMENT_TAGS) return true          // ① 光标所在标签本身就是语句标签
val parent = tag.parentTag ?: return false           // ② 只看一层 parent
if (parent.name !in STATEMENT_TAGS) return false     //    `<where>`/`<choose>`/`<when>` 全被漏掉
return offset >= tag.textRange.endOffset             // ③ 两态分界：子标签「边界之后」才算父语句内容
```

两层叠加效应：

1. **嵌套漏判**：`<if>`（不在 STATEMENT_TAGS）的 parent 是 `<where>`（也不是）→ 直接 false。
   真实 mapper 几乎全是 `<where><if>`、`<choose><when>` 排版 ⇒ 功能在主流形态下全灭。
2. **两态判据过宽**：`offset >= tag.textRange.endOffset` 把「子标签**体内正文**」（offset < endOffset）
   与「子标签**自身标记区**」（`<trim prefix=|>` 属性间隙）混为一谈，前者本该接管。
   该判据是 20260928「子标签标记区不接管」修复引入的（`testD5_子标签标记区内_不接管`），
   当时修 A（标记区误接管）引入 B（正文误放行），而 B 恰好被主流嵌套排版放大成全灭。
3. **同族洞**：光标落在**语句标签自身属性区**（`<select id="a|">`）时走 ① 直接接管——
   属性区敲 `#` 会插出 `#{}`，属同根误判（现有用例未钉住）。

测试缺口：`ParamBraceTypedHandlerTest` 六个用例里没有一个覆盖「嵌套动态标签体内正文」——
既有用例全是单层（直接子标签），恰好在盲区外。

## 三、修复方案

`inStatementText` 改为「最近语句标签祖先（任意深度）+ 标记区精确判定」：

```kotlin
private fun inStatementText(tag: XmlTag, offset: Int): Boolean {
    if (!XmlSynHiGate.inMapperFile(tag)) return false
    // ① 最近语句标签祖先：动态标签（if/where/choose/trim/…）可任意嵌套，只看一层 parent
    //    会把 `<where>` 下的 `<if>` 体内正文全部漏判（R15 录制实测）
    val statement = generateSequence(tag) { it.parentTag }
        .take(64)
        .firstOrNull { it.name in STATEMENT_TAGS } ?: return false
    // ② 标记区判定（替代旧 offset vs 子标签 endOffset 的宽判）：
    //    - 落在语句标签自身的起始标记内（`<select id=|>`）→ 标记区，不接管
    //    - 落在子标签起始标记内（`<trim prefix=|>`，含属性间隙）→ 标记区，不接管
    //    - 落在子标签体内正文 / `</if>` 右侧（offset >= 子标签 endOffset）/ 自闭合标签之后
    //      → 语句文本，接管
    statement.startTag?.let { if (offset <= it.textRange.endOffset && offset >= it.textRange.startOffset) return false }
    var cur: XmlTag? = tag
    while (cur != null && cur !== statement) {
        if (offset < cur.textRange.endOffset) {
            cur.startTag?.let { if (offset <= it.textRange.endOffset) return false }
        } else {
            return true   // 该子标签之后＝父（或更外层）语句内容
        }
        cur = cur.parentTag
    }
    return true
}
```

要点：

- 深度上限 64 与 `take(64)` 防畸形深树（与既有 O(1) 红线不冲突：链长受 XML 嵌套自然约束）；
- 自闭合子标签（`<include .../>` 无 endTag）之后：`offset >= endOffset` 直接 true，与旧语义一致；
- 空子标签体（`<if></if>` 中间）：offset 大于 startTag.endOffset 且小于 endOffset → 落到「体内正文」
  → true（那里敲 `#` 插 `#{}` 合理）；
- 不改 `beforeCharTyped` 的其余链路（先查后插、host 还原、`shouldYield` 门全保留）。

**不改**：`XmlSynHiGate`、injector、`SqlMapperBackspaceHandler`（成对删除按文本特征工作，与本判据无关）。

## 四、测试

`ParamBraceTypedHandlerTest` 新增（沿用 openFile + beforeCharTyped 直调形态）：

1. `嵌套where下if体内_接管`——`<where><if>and status = #<caret>` → STOP（本缺陷主形态）
2. `choose下when体内_接管`——二级嵌套变体
3. `语句标签自身属性区_不接管`——`<select id="a"| >` → CONTINUE（补同族洞）
4. `自闭合子标签之后_接管`——`<include refid="x"/><caret>` → CONTINUE 之外的 STOP 回归钉
5. 全类回归：既有六用例（尤其 `子标签标记区内_不接管`、`子标签边界之后_接管`）必须原样绿

跑法：`./gradlew test --tests "cn.song.mybatisplus.visualizer.ui.inject.ParamBraceTypedHandlerTest"`
（纯逻辑改动，不跑全量、不 clean）。

## 五、验证与承接

1. 定向测试绿（上列新 5 + 旧 6）。
2. 沙盒真机复测（`runIde -PgifRecord=true`）：`UserMapper.xml` `<where><if>` 体内敲 `#` 出 `#{}`；
   `<trim prefix=|>` 属性间隙敲 `#` 不插；语句标签属性区不插。
3. 承接文档站 N2 录制（批 3）：
   - 镜 1：`<if>` 体内敲 `#` → `#{}`；
   - 镜 2：续 `#{status,j` → 结构属性键弹窗（`javaType=`/`jdbcType=`/`typeHandler=`…）；
   - 镜 3：`<include refid="` 属性值 → `<sql id>` 清单（Ctrl+Space）；
   - 镜 4（若批 0 踩点确认平台折叠箭头可点）：折叠 `<select>` 呈 `select#selectUserListByXml` 占位。
   修复后的插件 jar 进沙盒必须走 `runIde -PgifRecord=true` 重建安装，不能复用旧沙盒。

## 六、待验证前提（实施者先证伪）

1. `generateSequence(tag){it.parentTag}` 的 `tag` 一定非 null（调用点 `PsiTreeUtil.getParentOfType(el, XmlTag)` 已保证）。
2. `startTag` 对空元素 `<if></if>` 非 null（XML PSI 恒有 startTag；自闭合 `startTag!=null && endTag==null`）。
3. 262 平台 `beforeCharTyped` 收到的注入片段 file/host 还原链路不变（本次不触碰）。

## 七、风险

- 行为面变化＝「更多场景接管」，不存在「以前插现在不插」的回归方向；唯一收紧是语句标签自身属性区
  （旧行为是错误接管，收紧属修复）。
- 若外部（共存插件）依赖旧误判……不存在此类契约：判据是纯内部私有方法。

## 实施记录

2026-10-31 当天实施完成。

1. **修复落地**：`inStatementText` 重写为「最近语句标签祖先（`generateSequence(parentTag).take(64)`）
   + 体内容孩子（XmlTag/XmlText/XmlComment）推导标记区」。计划 §三 草案里的 `startTag`/`endTag`
   **不存在**——262/2025.3 平台 `XmlTag` javap 实证无此访问器（`module-intellij.xml.psi.jar`），
   编译期即拦下（第一条教训：平台 API 以 javap 为唯一权威，草案代码先验 API 再写）。
2. **两处实施偏差（相对 §三 草案）**：
   - 标记区判定改为 `offset < bodyStart(tag)`（体内容孩子起点），空标签整段算标记；
   - 语句标签自身体末端点**含在内**（`offset in bodyStart..bodyEnd`）——紧贴 `</select>` 前的
     正文追加位应接管（首轮跑出「原 D5 用例变红」即此边界，属修法 bug 非回归）。
3. **测试**：`ParamBraceTypedHandlerTest` 新增 5 用例 + 原 6 用例，`XmlGapEditingAssistTest` 11 用例
   同批回归，22/22 绿（`./gradlew test --tests …ParamBraceTypedHandlerTest --tests …XmlGapEditingAssistTest`）。
   首轮 2 红中有 1 红是**夹具错**（光标放在 `{` 前撞上「先查后插」守卫，与判据无关）——
   写用例前先看清被测函数的早退分支。
4. **反向验证**：待沙盒真机复测（§五.2）+ N2 录制成片。
5. CHANGELOG 已记 [Unreleased]。

