---
name: sql-preflight-review
description: Review a SQL statement before it runs — especially UPDATE/DELETE/INSERT. Checks blast radius (missing WHERE, tautological conditions, wildcard LIKE, constant predicates), performance smells (SELECT *, leading-wildcard LIKE, huge IN lists, NULL comparisons), and produces a rollback plan with a pre-execution COUNT check. Use whenever you are about to execute, suggest, or hand over a write statement, or when a user pastes SQL and asks "is this safe", "will this delete everything", "how do I undo this".
---

# SQL Pre-flight Review

A write statement is the one thing in a coding session that can destroy data in a single keystroke. This skill is the checklist to run **before** the statement reaches the database, not after something breaks.

## When to use

Trigger this review when any of these is true:

- You are about to execute, or hand to the user, an `UPDATE`, `DELETE`, `INSERT ... SELECT`, `TRUNCATE`, or `DROP`.
- A user pastes SQL and asks whether it is safe, what it will affect, or how to undo it.
- You generated a write statement yourself (ORM builder, migration, ad-hoc query) and it is about to leave your hands.
- A migration script contains several statements and you have not yet counted them.

Skip it for plain `SELECT` — unless the user is asking about performance, in which case use Step 3 only.

## Step 1 — Classify before judging

Split multi-statement input first. A script that "looks like a select" often ends with a write.

For each statement record:

- type: `QUERY` / `DML` (INSERT, UPDATE, DELETE, MERGE) / `DDL` (CREATE, ALTER, DROP, TRUNCATE)
- target table
- whether it has a `WHERE` clause at all

Report the count. "This is 3 statements, the third one is a DELETE" is already useful information most reviewers skip.

## Step 2 — Blast radius (the part that matters)

For every write statement, check these six forms. Each one has a real-world failure behind it:

| Form | Why it is dangerous | What to say |
|---|---|---|
| No `WHERE` at all | Whole table | ERROR — refuses to run unattended |
| Tautological predicate: `WHERE 1=1`, `WHERE id=id`, `WHERE 'a'='a'` | Looks filtered, is not | ERROR |
| Tautological `OR` branch: `WHERE id = 5 OR 1=1` | One branch voids the rest | ERROR |
| Pure wildcard `LIKE '%'` | Matches every row | ERROR |
| Constant-only predicate: `WHERE status = status` or all-constant comparison | Filter evaluates identically for all rows | WARN |
| Subquery in `WHERE` | Scope depends on data you may not have seen | INFO — flag, do not block |

Rules for reporting:

- Never soften a missing-`WHERE` finding. Say plainly: "this updates every row in `user`."
- Quote the exact fragment that triggered the finding so the user can see it.
- If the statement cannot be parsed, treat it as **unsafe by default** and say so. Do not guess.
- If a statement mixes safe and unsafe clauses, report the unsafe one; partial safety is not safety.

## Step 3 — Performance smells (write and read)

Scan for these before execution, not after the incident:

- `SELECT *` on a wide table, or `INSERT` without a column list
- `LIKE '%term'` — leading wildcard defeats a normal B-tree index
- `IN (...)` with more than ~100 literals
- Comparisons against `NULL` using `= NULL` / `<> NULL` instead of `IS NULL`
- Functions wrapped around indexed columns in `WHERE` (`WHERE DATE(created_at) = ...`)
- `ORDER BY` on an unindexed column with a large expected result set
- `UPDATE`/`DELETE` predicates that cannot use an index (non-sargable)

Report severity as ERROR / WARN / INFO with the line number, and keep each item to one sentence plus the fix.

## Step 4 — Rollback plan

For every `UPDATE` / `DELETE` / `INSERT`, answer three questions before execution:

1. **Can this be undone precisely?** If the table has a primary key and the statement filters on it, per-row rollback is derivable (`COMPLETE`). If it filters on non-key columns, the rollback is an approximation (`PARTIAL`). If neither, say it is not derivable and stop (`UNABLE`).
2. **How many rows will be touched?** Provide a `SELECT COUNT(*)` with the *same* predicate as the write statement — the single highest-value pre-flight step.
3. **What does the data look like right now?** Provide a snapshot `SELECT` (key columns + columns about to be modified) so the old values exist somewhere before they are overwritten.

Output a rollback statement per affected shape: `UPDATE` → reverse `UPDATE ... SET old = new`; `DELETE` → `INSERT` of the captured rows; `INSERT` → `DELETE` by the inserted key.

## Output template

```
Statement: UPDATE orders SET status = 'shipped' WHERE created_at < '2026-01-01'
Type: DML · Table: orders · Rows: unbounded by key

Blast radius: WARN — predicate is non-sargable (DATE function on indexed column),
              and no primary key in the filter, so per-row rollback is approximate.

Performance:
  - WARN  line 1  function on indexed column `created_at` blocks index use → compare raw timestamps

Rollback: PARTIAL
  Pre-check:  SELECT COUNT(*) FROM orders WHERE created_at < '2026-01-01';
  Snapshot:   SELECT id, status FROM orders WHERE created_at < '2026-01-01';
  Revert:     UPDATE orders SET status = '<captured value>' WHERE id IN (<captured ids>);

Recommendation: run the pre-check first. If the count is larger than expected, narrow the
predicate to include the primary key so the revert becomes exact.
```

Always end with a recommendation, and make the first action a read-only one (count or snapshot).

## Enhanced path (if MyBatis SQL Visualizer MCP is available)

If the `sqlv_*` tools are reachable (IntelliJ IDEA + the MyBatis SQL Visualizer plugin, MCP enabled), prefer them over manual inspection — they run a deterministic analyzer instead of an LLM's judgement:

- `sqlv_check_sql(sql: string)` — returns `statements: N`, per-statement type, `dangerous: true|false`, and `findings:` with the matched fragment. Covers exactly the six forms in Step 2 plus unparseable-statement fail-closed handling.
- `sqlv_perf_advice(sql: string)` — 21 rules with `ERROR` / `WARN` / `INFO` severity and line numbers; dialect-aware when a project context exists.
- `sqlv_rollback_plan(sql: string, primaryKeyColumns?: string)` — returns `rating: COMPLETE|PARTIAL|UNABLE`, the rollback statements, warnings, plus the COUNT-check and snapshot templates. Pass `primaryKeyColumns` (comma-separated) to get exact per-row rollback.

Suggested chain: `sqlv_check_sql` → `sqlv_perf_advice` → `sqlv_rollback_plan`. All three are read-only and never touch the database.

When the tools are not available, run Steps 2–4 by reasoning over the SQL text — the checklist is the value, the tools just make it deterministic.

## Boundaries

- This is static analysis. It does not connect to a database, does not execute anything, and cannot tell you the real row count — that is what the `COUNT(*)` pre-check is for.
- It does not replace `EXPLAIN`. Step 3 is a smell detector; the real plan comes from the engine.
- It does not judge business intent. A whole-table `UPDATE` is sometimes exactly right — the review's job is to make sure that is a decision, not an accident.
- Dialect differences (quoting, pagination, `RETURNING`) affect what is valid, not what is dangerous. If the dialect is unknown, say so rather than assuming MySQL.
