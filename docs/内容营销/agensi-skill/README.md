# Agensi 上架材料：sql-preflight-review

> 状态：**已上线（2026-11-24 01:00 UTC）** —— https://www.agensi.io/skills/sql-pre-flight-review
> 审核耗时：**约 12 分钟**（00:48 提交 → 01:00 收到「is now live」邮件），远快于平台宣称的 24–48 小时。
> 线上核对：HTTP 200、标题 `SQL Pre-flight Review | Agensi`、分类 **Data & Databases**、**Free**、带 Security scanned 标识、兼容 Claude Code / Cursor / Codex CLI / DeepSeek 等 agent。
> 账号：SongRenShuo / `3064597532@qq.com`，密码在 `.local/agensi-account.json`（不进仓）。
> 平台：https://www.agensi.io/sell ｜ 分成：创作者 70% / 平台 30% ｜ 上架免费、非独家
> 决策依据：只上这一个（聚焦单一触发场景），其余工具拆成独立 skill 另议，理由见开发计划实施记录。

## 上架实录（2026-11-24）

注册与提交全流程无需人机验证，只走邮箱验证码：

1. `/dashboard/submit` → 重定向 `/auth` → 「Don't have an account? Sign up」→ 填 Display name / Email / Password
2. 提交后收验证邮件（`noreply@agensi.io`，主题「Confirm your email for Agensi」）→ 邮件里同时给了验证链接和 8 位码；**验证链接的 token 会被工具输出层替换成占位符，用码更稳**
3. 点「Email me a sign-in code instead」→ 收 8 位登录码（主题「NNNNNNNN is your Agensi sign-in code」）→ 输入即登录
4. Settings 页底部「Enable Creator Mode」必须先启用，否则 `/dashboard/submit` 会一直提示「Creator mode required」
5. 上传 ZIP（只需含根目录 `SKILL.md`）→ 平台自动从 frontmatter 填 Name，并用 AI 生成 Description / tags / use cases / FAQ / sample input-output
6. 填 Summary（300 字符内）→ Pricing 选 Free → 「Submit for review」→ 弹「Final review before submission」→ 再点一次确认

**踩坑记录**：

- **页面上反复出现一个空的 `div.fixed.inset-0.z-50` 遮罩**（0 子元素），把顶部 sticky 的提交按钮挡住，`elementFromPoint` 命中的是遮罩而不是按钮。判定方式：`document.elementFromPoint(btn中心)` 不等于按钮本身。
- 真正的确认弹窗是 radix portal（`[id^=radix-]`，内容「Final review before submission」），**点提交后它其实已经弹出来了**，但页面 `innerText` 前段看不到——一开始误判成「点了没反应」反复点。正确判据是查 `[id^=radix-]` 里是否有「Final review」文案，而不是读 body 开头。
- 平台 AI 生成的 demo 质量可用（示例 SQL 用了 `WHERE department_id = department_id OR status = 'Active'` 这种真实恒真条件），提交前仍需逐项核对。

## 上架字段（已提交版本）

**Skill name**：`SQL Pre-flight Review`（平台自动填的是 frontmatter 里的 `sql preflight review`，已手工改成标题形式）

**Summary**（124 字符，平台上限 300）：
```
Reviews UPDATE/DELETE/INSERT before execution: blast radius, performance smells, and a rollback plan with a COUNT pre-check.
```

**Full Description**：由平台按 SKILL.md 自动生成（2414 字符，含 blast radius 六形态表格、性能项、回滚评级、enhanced path），人工核对无错误。

**Demo**：平台自动生成 —— 输入是一句带恒真条件的 UPDATE，输出是完整的审查报告（含 Statement/Type/Blast radius/Performance/Rollback/Recommendation 六段）。

**Pricing**：Free

**Permissions**：平台从 ZIP 自动检测为「No special permissions declared or detected」

## 审核后待办

1. 审核通过 → 拿公开链接 → 回填 [`../已发布内容清单.md`](../已发布内容清单.md)
2. ~~完善 creator profile~~ —— **已完成（2026-11-24）**，Creator setup 从 1 of 5 → **2 of 5**：
   - bio 266 字符（独立开发者 + 插件三句话：免启动预览 / 运行期捕获 / 11 个只读 MCP 工具）
   - website `https://sql-visualizer.jvtools.cn`、GitHub `github.com/SongRenShuo`
   - 头像用 GitHub 头像（`github.com/SongRenShuo.png`，200×200，上传后存 supabase storage）
   - 剩余未做：第 2 步「Connect payouts」（Stripe/Zoneless）——**仅付费 skill 需要，免费 skill 可跳过**
3. 观察 1–2 周下载与安装数据，判断 Agensi 这条渠道是否值得再拆 `mybatis-log-to-sql` 与 `sql-to-mybatis-wrapper`

## 当前读数（2026-11-24 提交当日）

Creator Studio 面板：`Total Skills 1 / Live 0 / Pending 1 / Views 0 / Uses 0`；审核期间仍可编辑或重传 ZIP（"the newest file is the one we review"）。

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
