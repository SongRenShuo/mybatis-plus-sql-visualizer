<!-- Marketplace 自定义页（第二页）Markdown 版：页名 Comparison & FAQ，整页复制到
     JetBrains Marketplace → 自定义页编辑器（仅渲染 Markdown）。
     2026-11-01 新建并在后台发布，地址 /plugin/33633-sql-visualizer/custom-pages/63591。
     与 marketplace-custom-page.md（主文档页 Documentation）分工：主文档页讲功能全集，本页只做
     竞品定位对照与常见疑问，竞品能力列一律保守留「—」，不写贬损。 -->

## How SQL Visualizer differs

| | SQL Visualizer | MyBatisX | MyBatisCodeHelperPro | MyBatis Log (free forks) |
|---|---|---|---|---|
| Focus | SQL preview + runtime SQL visibility | Mapper / XML navigation, code generation | Code completion, inspection, generation | Restore console logs into full SQL |
| Static preview of the generated SQL | Yes — Wrapper chains, mapper XML, annotations, JPA / HQL, JdbcTemplate | — | Partial, depending on feature set | — |
| Runtime capture without log configuration | Yes — enhanced capture, on by default | — | — | Console log parsing only |
| EXPLAIN / plan view | Yes, per dialect | — | — | — |
| Built-in MCP server (read-only SQL tools) | Yes | — | — | — |
| License | Free (Apache-2.0) | Free | Free + paid edition | Free |

These tools solve different problems and work side by side — plenty of users run a navigation or code-generation plugin together with SQL Visualizer.

## MCP: what do the read-only tools actually do?

SQL Visualizer registers 11 read-only tools (`sqlv_*`) into the IDE's built-in MCP server, so an agent can render the SQL behind a Wrapper chain / mapper statement / JPA method, turn captured log entries into executable SQL, run EXPLAIN against a configured data source, and check a statement against the current dialect before it runs.

Every tool is read-only: write statements are rejected with `MCP_READ_ONLY`, and the analysis runs locally.

## FAQ

**Is it free?** Yes — Apache-2.0, no paid tier, no sign-up.

**Does it send my SQL anywhere?** No. Analysis happens locally; the website only records anonymous page counts.

**Which IDEs are supported?** IntelliJ IDEA (Community and Ultimate) and Android Studio 2024.3+. The plugin depends on the Java module, so IDEs without Java support are not supported.

**Do I have to change my project?** No. Static preview needs no running app; runtime capture attaches a lightweight agent to the process you already run.
