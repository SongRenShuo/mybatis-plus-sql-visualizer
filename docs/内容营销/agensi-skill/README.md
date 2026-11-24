# Agensi 上架材料：sql-preflight-review

> 状态：SKILL.md 已起草（2026-11-24），**尚未注册 / 未上架**。
> 平台：https://www.agensi.io/sell ｜ 分成：创作者 70% / 平台 30% ｜ 上架免费、非独家
> 决策依据：只上这一个（聚焦单一触发场景），其余工具拆成独立 skill 另议，理由见开发计划实施记录。

## 上架字段

**Skill name**：`sql-preflight-review`

**Listing title**（≤60 字符）：
```
SQL Pre-flight Review — catch the DELETE before it runs
```

**Short description**（列表页用，≤160 字符）：
```
Reviews UPDATE/DELETE/INSERT before execution: blast radius, performance smells, and a rollback plan with a COUNT pre-check.
```

**Long description**（详情页用）：
```
Most SQL mistakes are not syntax errors — they are scope errors. A missing WHERE clause, a
1=1 left in a debug session, a wildcard LIKE that matches every row: each one turns a routine
write into a data incident, and an AI assistant can produce any of them in one keystroke.

This skill is the checklist to run before the statement reaches the database:

- Classify every statement (a script that looks like a SELECT often ends with a DELETE)
- Six blast-radius forms with the exact failure behind each — missing WHERE, tautological
  predicates, tautological OR branches, pure wildcard LIKE, constant-only predicates,
  subqueries that depend on data you have not seen
- Performance smells with severity and line numbers: SELECT *, leading-wildcard LIKE, huge IN
  lists, NULL comparisons, functions wrapped around indexed columns
- A rollback plan rated COMPLETE / PARTIAL / UNABLE, plus the two things that actually save you:
  a COUNT(*) pre-check with the same predicate, and a snapshot SELECT of the values about to
  be overwritten

Works standalone as a reasoning checklist. If you also run MyBatis SQL Visualizer (an IntelliJ
IDEA plugin exposing read-only MCP tools), the skill routes through sqlv_check_sql,
sqlv_perf_advice and sqlv_rollback_plan for deterministic analysis instead of judgement.

Nothing here connects to a database or executes SQL. It makes the statement reviewable before
you run it — the COUNT pre-check is what tells you the real row count.
```

**Category / tags**：`database` `sql` `safety` `code-review` `developer-tools`

**Price**：Free

**Language**：English（Agensi 受众以英文 agent 用户为主；中文版可后续补）

## 上架步骤（待执行）

1. `agensi.io/sell` → 注册（邮件验证 + 可能的 ALTCHA 人机验证，后者需人工点击）
2. 上传 SKILL.md（平台通常接受单文件或目录）
3. 填上表字段，价格选 Free
4. 提交审核 → 通过后拿公开链接
5. 公开链接回填到 [`../已发布内容清单.md`](../已发布内容清单.md) 的「目录收录」位置

## 验证要点（上架前自查）

- [ ] frontmatter 的 `name` 与目录名一致（`sql-preflight-review`）
- [ ] `description` 写的是**触发时机**（"before it runs"、"is this safe"、"how do I undo"），不是功能列表
- [ ] 正文里提到的 MCP 工具参数与插件实际实现一致（`check_sql(sql)`、`perf_advice(sql)`、`rollback_plan(sql, primaryKeyColumns?)`）
- [ ] 明确写了边界：不连库、不执行、不替代 EXPLAIN
- [ ] 没有承诺 11 个工具里的 IDE 依赖项（explain / schema_context / mapper_check / history_search 均未提及）

## 后续可拆的 skill（等这个验证完再动）

| skill | 触发时机 | 工具 |
|---|---|---|
| `mybatis-log-to-sql` | 用户贴出带 `?` 的 MyBatis 日志 | `sqlv_render_logs` |
| `sql-to-mybatis-wrapper` | 用户想把 SQL 反写成 Java | `sqlv_reverse_sql` |
