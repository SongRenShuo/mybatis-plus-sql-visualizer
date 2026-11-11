# T-IbatisParamClassQuote：iBatis 裸 `#x#` 参数缺引号——开发计划

- 立项：2026-11-11
- 触发：用户真机验收（demo `ibatis2-demo` 的 `sqlmap/User.xml` → `selectTwoSegment`）
- 范围：`ui/IbatisXmlPreviewService.kt`、`ui/gutter/IbatisStatementPreview.kt`、新增
  `ui/IbatisParameterClassResolver.kt`、测试一组

## 一、现象

```xml
<select id="selectTwoSegment" parameterClass="User" resultMap="userResult">
  SELECT <include refid="userColumns"/>
  FROM T_USER
  WHERE USER_NAME = #username#
</select>
```

预览面板：参数行 `#username` 填 `1213`，产物是 `WHERE USER_NAME = 1213`——**没有引号**。
`USER_NAME` 是 `VARCHAR`（同文件 `userResult` 里 `property="username" jdbcType="VARCHAR"`），
`User.username` 是 `String`，真实执行时 iBatis 走 `setString`，等价 SQL 是 `= '1213'`。

对照组：同文件 `selectThreeSegment` 是 `WHERE AGE = #age:INTEGER:0#`，带 jdbcType，产物正确裸渲染。

## 二、根因

iBatis 预览链唯一的参数类型来源是占位符自带的 jdbcType：

- `IbatisParamTypeHints.hintOf(jdbcType)`（`xml/ibatis/IbatisParamModel.kt:336`）只在
  `#x:VARCHAR#` / `#x:INTEGER:0#` 这类带 jdbcType 的形态产出 `ParamHint`；
- 裸 `#username#` 的 jdbcType 为 null → `ParamHint()`（`quote=null`）→ 面板 `backfillParamsBySource`
  回落到 `NUMBER_REGEX` 启发式，`1213` 判成数字 → 裸渲染。

而 iBatis 2 的真实语义不是「看值的形态」，是「看 `parameterClass` 的反射类型」——`User.username`
是 String，引擎就 `setString`，SQL 里必须是字符串字面量。

同一份数据本来就在手边：`IbatisStatement.attributes`（`IbatisModel.kt:238`）已经装着
`parameterClass="User"`，只是**没有消费者**（全仓只有语法高亮关键词表提到这个词）。
`IbatisStatementPreview.previewTag(tag)` 手里同时有 `tag.project` 与 `tag.getAttributeValue("parameterClass")`，
`IbatisXmlPreviewService.preview()` 目前一个都没接。

## 三、方案

1. **链路带上类型上下文**：`IbatisXmlPreviewService.preview` 增 `parameterClass: String?` 与
   `project: Project?`；`IbatisStatementPreview.previewTag` 传 `tag.getAttributeValue("parameterClass")`
   与 `tag.project`。`toPreviewResult` 与 `rerender` 闭包共用同一份 `propertyHints`。

2. **新增 `ui/IbatisParameterClassResolver`（PSI）**：

   - `parameterClass` → `PsiClass`：全限定名直接 `findClass`；短名／`<typeAlias>` 别名走
     `PsiShortNamesCache.getClassesByName(name, allScope)`，**唯一命中才用**（多个候选＝弃权，宁缺勿错）。
     demo 的 `<typeAlias alias="User" type="cn.song.ibatis2demo.model.User"/>` 与类名同名，短名搜索直接命中。
   - 属性 → 类型：`#name#` 的裸属性名 `findFieldByName(name, true)`；含点（`#user.username#`）按段逐级解析。
   - 类型 → `ParamHint`：`String` / `UUID` / 字符族 → 引号；数值 / 布尔 → 裸；日期时间 → 引号 + `temporal`；
     其余（List / Map / 未知对象）→ 不产提示（维持现状）。

3. **装配优先级**：每个 `?` 位点先看 jdbcType（既有口径不动），jdbcType 缺省时才查 `propertyHints`。
   位点与参数名数量不符（走 `param1..N` 兜底）时两者都不用——名字与类型已无法对齐。

4. **解析不到时可见而非静默**：`parameterClass` 解析失败或属性查不到，维持现状（quote=null），
   并在预览横幅给一条信息级说明「`#x#` 未带 jdbcType、也未解析到 `parameterClass` 的类型，
   引号按值形态推断」——让「不知道」可见。

## 四、验证

- 纯逻辑（`IbatisParamClassQuoteTest`）：装配优先级（jdbcType 胜出 / 属性类型补位 / 数量不符两者都不用）；
  类型 → hint 映射（String/Integer/BigDecimal/LocalDate/List/Map）。
- PSI（Light/Heavy 视环境）：短名唯一命中、多候选弃权、含点属性、类不存在。
- 真机：demo 的 `selectTwoSegment` 填 `1213` → `WHERE USER_NAME = '1213'`；
  `selectThreeSegment` 填 `18` 仍裸渲染（jdbcType 优先未被属性类型覆盖）。
- 反向：摘掉「jdbcType 缺省才查属性」的分支 → 第一条用例转红。

## 五、边界（不做）

- 不猜列类型（不从 SQL 的列名/表元数据反推——那条路依赖数据源绑定，且截图里数据源未绑定）。
- 不改既有的 `#x:jdbcType#` 口径，也不动 `IbatisParamTypeHints` 的映射表。
- 不处理 Map 参数（`parameterClass="java.util.HashMap"` 且 `#key#` 无类型可查）——维持启发式。
- 不引入完整 iBatis 类型处理器模型（`<typeHandler>` 自定义映射不解析）。

## 六、待验证前提

- **V1**：用户真机项目里 `parameterClass` 用的是类短名还是 `<typeAlias>` 别名；短名搜索能否唯一命中
  （demo 可解析，用户项目若有多个同名 `User` 会弃权 → 退回现状）。
- **V2**：Light 测试环境能否跑 `PsiShortNamesCache`（决定用例进哪个桶）。
- **V3**：iBatis 的 `#name#` 在 `parameterMap` 声明场景是否另有类型来源（`parameterMap` 的 `<parameter>`
  也带 jdbcType）——若该链已产 hint，本方案只覆盖裸语句。

## 七、实施记录

### 落地（`cb4ec1ed3`）

- `ui/IbatisParameterClassResolver`（新）：`parameterClass` → `PsiClass`（FQN 直查 → 短名索引唯一命中）
  → 属性路径逐段解析字段 → `PsiType` → `ParamHint`。`resolveStrict` 是诊断/测试用的不吞异常入口，
  `resolve` 用它并吞成空表（生产降级）。
- `IbatisXmlPreviewService.preview` 增 `project` / `parameterClass` 两参数（默认 null = 纯文本测试链，
  行为与改前逐字节一致）；`toPreviewResult` 装配改为
  `hintOf(jdbcType) ?: propertyHints[name] ?: ParamHint()`；`rerender` 闭包携带首屏解析好的 hint。
- `IbatisStatementPreview.previewTag` 传 `tag.project` / `tag.getAttributeValue("parameterClass")`
  —— Java DAO 调用行预览（`previewResolved` → `previewTag`）与 XML 语句行同源覆盖。

### 实施中证伪的一条自设前提

第一版 `hintOfType` 用「`resolve()?.qualifiedName` + FQN 集合」匹配，Heavy 测试全红。诊断读数
`field=username type=PsiType:String typeHint=null`：**无 SDK 的测试环境里 `resolve()` 返回 null**，
只剩简单名 `String`，与 `java.lang.String` 对不上。修法：取值改为
`resolve()?.qualifiedName ?: canonicalText`，三类集合同时收 FQN 与简单名，匹配统一按
`substringAfterLast('.')`。真实 IDE 走 FQN 分支、测试环境走简单名，两条都命中。

### 读数

- `IbatisParamClassQuoteHeavyTest` 6 例全绿（裸参数加引号 / 数值裸渲染 / jdbcType 胜出 /
  类解析不到维持启发式 / 无 project 维持启发式 / 诊断链）；
- 同链回归 `IbatisXmlPreviewServiceTest`(44) + `DegradationBannerTest`(5) +
  `SqlPreviewPanelDynamicBatchTest` 全绿；
- 反向：摘掉 `?: propertyHints[name]` 补位分支 → `testBareParameterQuotedByParameterClass` 恰 1 红
  （6 例中 1 失败），还原复绿。

### not run

真机复验（沙盒当前加载 `2026.11.12-beta.2`，jar 内无本轮类；要验需重新构建或装包）。