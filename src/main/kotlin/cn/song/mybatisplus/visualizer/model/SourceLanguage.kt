package cn.song.mybatisplus.visualizer.model

/**
 * 预览源语言（T-PagesHql）。
 *
 * 非 [SQL] 时表示「面板里的 SQL 是由该语言的原文转换而来」，面板 meta 行据此展示
 * 「来源：HQL（已转换）」，并可用 [PreviewResult.originalSql] 提供原文只读页签——
 * 让用户能核对插件到底做了什么，而不是只看到一段结果 SQL。
 *
 * 只有 SQL / HQL 两档：`type="jpql"` 与 JPQL 语义在本插件里走同一条 HqlConverter 管道、
 * 面板文案也一致，故不再单列（枚举常量必须有真实产出点，见 EnumReachabilityGuardTest）。
 */
enum class SourceLanguage { SQL, HQL }
