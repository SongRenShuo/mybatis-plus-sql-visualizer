---
name: project-xml-highlight-completion-gap
description: mapper XML 高亮/补全与竞品再对标（第二轮，20261012 立）——头条=共存让位按宿主而非按区间判定，用户真机上我方 XML 高亮实际接近零；附补齐的竞品能力面与我方缺口语义
metadata:
  node_type: memory
  type: project
  originSessionId: sess_7c252f84-929f-48ef-84b9-41e903214ff0
---

# mapper XML 高亮与补全差距（第二轮，源起 2026-10-12 用户「吸取得不很到位」）

材料落点：报告 `docs/内部/调研报告/20261012-1552-CodeHelperPro-XML高亮与补全再深挖-调研报告.md`（逆向细节入私有仓）+ 公开仓占位指针 `docs/调研报告/20261012-1552-…md`；配套计划 `docs/开发计划/20261012-1552-T-XmlGap-…-开发计划.md`，占号 **O-144 / R95 / D155 / V178**（20261012 台账实测：O 占用至 O-143、R 至 R88、D 至 D150、V 实占至 V172）。前作＝`docs/内部/调研报告/20260924-0442-…`（基于 CHP 3.5.9）+ T-XmlSynHi 计划（D1~D5 已交付，D6 用户走查在册）。

## 头条结论（双侧证据已闭合）

**共存让位门控按「宿主元素」判定、不按「着色区间」判定 ⇒ 用户真机上我方 XML 高亮实际接近零。**

- 我方：`ui/inject/XmlSynHiGate.kt` 的 `foreignInjectionProbe` 逐个第三方注入器试调 `getLanguagesToInject`，`ProbeRegistrar` 只记 `startInjecting` 是否被调（不看 `addPlace` 区间）；`ui/inject/SqlMapperInjector.kt:42` 一旦让位即**整宿主 return**。
- 竞品：`p/c`（`${}`，宿主 `XmlText`）与 `ak/f`（`#{}`，宿主 `XmlTextImpl`）都是「先取区间列表、有区间才 `startInjecting`」。
- 合并 ⇒ 语句里只要有任意一个 `#{}` 或 `${}`，竞品就 `startInjecting`，我方让出**整条语句**的全部着色（关键字/注释/字符串都没了）。
- 真机条件：用户 IU 2026.2 `%APPDATA%/JetBrains/IntelliJIdea2026.2/options/MyBatisCodeHelper.xml` 实测 **`valid=true`**（CHP 已激活）；`plugins/` 下 `MyBatisCodeHelper-Pro`、`zMyBatis`、`mybatis-plus-sql-visualizer` 三者同在 ⇒ 必然命中，非偶发。**「能力写了但被自己的让位逻辑关掉」＝「吸取不到位」的第一来源。**
- 顺带：`ui/inject/ParamBraceTypedHandler.kt:46` 用的是单参 `shouldYield(project)`（只查设置开关），**不受 per-host 线影响**，故 `#{` 自动闭合仍工作（靠「先查后插」防双插）；该文件 KDoc 声称「CodeHelperPro 在位即不接管」与实现不符，应订正。

## 我方 XML 链路现状要点（源文件未变，仅记结论）

- 注入：`SqlMapperLanguage`(lexer-only，"MpSqlMapper") + `SqlMapperInjector`（宿主 `XmlText`/`XmlAttributeValue`）；属性白名单 11 个（test/value/collection/item/index/open/close/separator/prefix/prefixOverrides/suffixOverrides）；程序化注册 `SqlMapperParserDefinition`（单叶 PLAIN_TEXT，非 EP）。
- **`#{}` 与 `${}` 同为 `SqlMapperTT.PARAM` 一个 token、映到 `TEMPLATE_LANGUAGE_COLOR` 一个键** ⇒ 两者同色不可区分；表达式内部不切分＝无细着色；IDENTIFIER/OPERATOR 不映射（表名列名运算符无色）。
- 关键字＝`SqlMapperLanguage.KEYWORDS` 41 词平集，**不读 `sql/Dialect.kt`（11 方言）**＝无方言感知。
- 补齐两个语义命名：**「整段着色」**＝表达式一个 token 一种颜色（我方现状）；**「词法级细着色」**＝表达式内部再分关键字/字符串/数字/运算符（竞品）。引用时用这两个词，别混。
- 缺口清单（我方无）：`colorSettingsPage`（颜色不可配，竞品有 2 个）、`lang.foldingBuilder`、live template、`dom.fileMetaData`（平台免费的标签/属性/类引用补全）、`psi.referenceContributor`+`PsiReference`+`gotoDeclarationHandler`（引用跳转/重命名**整体为 0**）、`${}` 内 OGNL 细色、`#{}` 内结构属性（`,` 后 jdbcType/javaType/typeHandler/mode…）、`arg0..N`/`_databaseId`、SQL 函数、select 列表按 resultType 类属性、单字母关键字捷径、语句标签自身属性名（select/insert 的 id/resultType/parameterMap…）、属性值面除 test/collection 外全空（含 refid/resultMap/resultType/parameterType/keyProperty/namespace）、resultMap property、`<include><property name>`、mybatis-config.xml、foreach item·index 与 `<bind>` 变量进后续候选、`@类@静态成员`。

## 竞品 3.6.7 面（补上轮未覆盖的一半，供引用）

- 反编译工作区（一次性建好，可复现）：`F:/code/Java/Gitee/SongRenShuo/idea-plugin-crack/MyBatisCodeHelperPro/chp367/{classes,src}`；jar 源 `C:/Users/USER/AppData/Roaming/JetBrains/IntelliJIdea2026.2/plugins/MyBatisCodeHelper-Pro/lib/instrumented-MyBatisCodeHelper-Pro241-3.6.7+2321.jar`（1939 条目 → 1150 java）；命令＝IDEA 捆绑 JBR + `plugins/java-decompiler/lib/java-decompiler.jar` 的 ConsoleDecompiler（PATH 的 java 1.8 跑不动），工具链详见 `~/.zcode/cli/memories/global/reference-fernflower-decompile.md`。
- 门控实测：`p/g.java:29` 与 `p/c.java:36` 均有 `if (…l.a.a().getValid())`（**已激活**）；`ak/f.java`（`#{}` Param）**无激活门**＝免费档。⇒ 行号易变，判门控以「注入器 `getLanguagesToInject` 体内有没有 `Profile.getValid()` 调用」为准。
- EP（3.6.7 plugin.xml，未注释行）：multiHostInjector ×3（658 `P.g` 属性值 / 659 `P.c` `${}` / 665 `ak.f` `#{}`）、`lang.syntaxHighlighterFactory language=Param`（680）、`colorSettingsPage` ×2（652 MybatisOgnl / 681 Param）、`lang.foldingBuilder language=XML`（635）、`typedHandler` ×2（527 order=first / 647）、`liveTemplateContext` ×3、`daemon.changeLocalityDetector`（690）。
- live template 全清单（`classes/liveTemplates/*.xml`）：`table`（`<!--@Table -->`）、`sql`（`<!--@sql -->`）、`ignore`（`<!--@ignoreSql-->`）、`cd`/`CD`（`<![CDATA[…]]>`）、`co`/`CO`（`<foreach>` 骨架，collection 动态弹参，**变量表达式是竞品自定义 `collectionName`**）、`in`/`nin`（OGNL）、`gtxt`（Struts2）。其中 `table`/`sql`/`ignore` 是竞品**引导注释体系**（要用户手写 `<!--@Table-->` 才让表名获得 SQL 上下文）——**不照搬**（我方不做校验器就无此负担）。
- **勘误上轮报告**：`ParamMysqlParserDefinition` / `IntellijSqlParameterReferenceContributor` 不是「3.6.x 新能力」——3.6.7 实测仍无对应类，且 `plugin_database.xml:60-62,85` 的注册行**本身就是 XML 注释**（厂商自己关掉的死声明）。
- 环境坑（本轮新踩）：解包后 `com/ccnode/codegenerator/P/` 与 `p/` 在 Windows 是同一目录（NTFS 大小写不敏感，`ls -d p P` 都「存在」，FernFlower 后写覆盖先写）⇒ 引用竞品类名必须带**包名与文件名大小写原样**（`P.g` vs `p/g.java` 是两个类），判归属看文件内 `@Metadata` d2 与 import，别以路径大小写为准。

## 红线与边界（本轮确认）

- 「不做校验器形态回应 CodeHelperPro」（第十二轮红线）继续有效；本轮范围＝高亮 + 补全 + 导航。
- **引用导航与错误标注要分开看**：只做「跳转/查找用法」不报红不构成校验器形态（IDEA 平台自身也这么分）——是否纳入本轮由配套计划决策点定，勿一句「红线挡着」否掉。
- 竞品把语言实现塞进 `com.intellij.lang.*` 包名（伪装平台包）的手法**不模仿**（Marketplace 兼容审查风险）。

关联 [[project-pending-plans]] [[project-competitor-docs-open-source-exposure]] [[project-doc-conventions]] [[pitfall-user-impact-reads-real-source]]（同族：「影响面/观感结论必须读真实生效路径，别信能力清单自述」）。
