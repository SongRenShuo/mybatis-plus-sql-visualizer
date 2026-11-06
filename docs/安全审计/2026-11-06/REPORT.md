# REPORT — mybatis-plus-sql-visualizer run-1

Generated 2026-11-06 by the Cloudflare security-audit-skill workflow (installed at `.agents/skills/security-audit/` in the target repo).

## 1. Run profile, scope, and execution statement

- **Profile**: `standard`. **Budget**: none set (no agent cap). **Source ref**: `30312d461e3fc54d5be553662dc3beb516bfa4e8` (worktree dirty only by the audit skill itself, `.agents/skills/security-audit/`, untracked).
- **Scope**: full repository — main plugin (`src/main/kotlin`, `src/main/java`), agent sub-project (`sub-projects/sql-visualizer-agent`), buildSrc, CI workflows, dev-support, database-lab, update manifests. Nothing deferred or out of scope within this definition; the two wave-2 units added by the coverage critic were hunted and closed.
- **Execution policy**: `sandboxed-source-and-local-only`. Reconnaissance established that the Windows audit host **cannot enforce the required OS sandbox** (empty allowlisted environment, no external network, read-only target, scratch-only writes, resource limits) nor the scratch-promotion procedure. The run was therefore **source-review only**: no target-controlled code was built or executed. The consequence is structural: every surviving candidate is `needs_validation` (the skill forbids assigning severity or `confirmed` without a bounded locally observed result). WSL is present but has no Linux distro installed.
- **Prior runs**: none exist; first audit pass. No prior confirmed exclusions, no carried records.
- **Agents spent vs planned**: 4 reconnaissance + 14 hunters (13 wave-1 + 1 wave-2) + 2 coverage critics (post-wave + distinct final-clean) + 7 Phase-3 candidate verifiers + 7 Phase-5 record verifiers = **34 agent invocations**, matching the standard-profile plan (verifiers batched per subsystem; each candidate and each final record individually dispositioned).
- **Adaptations (documented deviations)**: (1) `validate-*.cjs` run via run-local copies with a Windows `lstat` fallback replacing the unavailable POSIX `O_NOFOLLOW/O_NONBLOCK` open flags (skill originals unmodified; note in `run-metadata.json`); (2) hunter/verifier prompts embed the skill's verbatim method/contract blocks via the parent-owned shared context file `context/hunter-context.md`; (3) Phase 3/5 verifiers grouped by subsystem (one agent per group, per-record verdicts) instead of one agent per record.

## 2. Security posture summary

The plugin's core capture pipeline is a **single-user desktop tool whose risk concentrates where it crosses out of the IDE process**: an unauthenticated loopback channel to the bytecode agent, MCP tool surfaces exposed to AI agents, live SQL execution against developer datasources, and a self-update chain that installs downloaded code. Source review found **no cross-principal privilege escalation that is exploitable without a local foothold or a compromised update channel**, and several genuinely strong controls (SecureXml hardened parser, fail-closed MCP SELECT guard layering, bounded feedback/zip handling, clean secrets hygiene in shipped code). The most owner-actionable item is a **real database credential committed in a documentation file**. Systemic patterns worth fixing once rather than per-site: input-length caps present on one channel but missing on sibling channels to the same sink; per-site ad-hoc HTML escaping; client-side-only guard semantics advertised as stronger guarantees (read-only hint, DML confirm).

## 3. Confirmed findings

**None.** With no OS-enforced sandbox on the audit host, no candidate could produce the bounded local observed result the `confirmed` verdict requires. Zero confirmed records is a property of this run's execution boundary, not a claim that the codebase is vulnerability-free — the 25 verified leads below are exactly the places where one bounded local check would settle the question.

## 4. Needs-validation leads (25, independently verified)

Full traces, evidence, blockers, and non-destructive resolution plans: **NEEDS-VALIDATION.md**. Priority order (owner-actionability first):

| # | Lead | Boundary | Settle via |
|---|---|---|---|
| 1 | Working DB credential committed in `.agents/skills/db-env-provisioning/SKILL.md:160` (xc3 @ public test server 103.36.220.245:25866) | repo docs → network-reachable DB instance | L+D — **rotate + scrub file & git history** |
| 2 | Loopback agent channel has no peer authentication (inject events / seize capture control / poison attach table / eavesdrop on SQL frames) | local process ↔ IDE | L+D |
| 3 | MCP `sqlv_explain` read-only is client-side only (SELECT-invoked functions execute with full datasource privileges while `readOnlyHint=TRUE`) | AI agent → DB | L+D |
| 4 | Native ▶/Ctrl+Enter in the embedded preview console bypasses the plugin DML-confirm/unbounded-write/dialect-gate chain | captured SQL → DB | L+D |
| 5 | Update plugin-id gate desynced from platform parser via XML comments | update channel → IDE | L+D |
| 6 | Update zip guard delegates entry-name sanitization (zip-slip/Windows names) to unverified platform extractors | update channel → filesystem | L+D |
| 7 | `sqlv_history_search` ignores the MCP output-masking setting (sensitive literals reach agents) | history → AI agent | L+D |
| 8 | Imported/VCS-shared settings fields concatenated into rendered/executed SQL with no content validation | settings file → SQL | L+D |
| 9 | HTML report export interpolates `tableName`/source names unescaped, auto-opens in browser | project metadata → browser | L+D |
| 10 | Three Swing HTML sinks interpolate lower-trust text unescaped (rollback dialog, preview meta, error tooltip) | DB/console text → UI | L+D |
| 11 | MCP guard INTO check not recursive into CTE/derived tables | AI agent → DB | L+D |
| 12 | Token-less log lines share one fallback bucket → cross-thread Preparing/Parameters mispairing reaching execution | console → DB | L+D |
| 13 | Autosave writer follows symlinks (revision path enforces NOFOLLOW) | project dir → arbitrary file append | L |
| 14 | Dependency resolution mirror-first (Aliyun) with no `verification-metadata.xml` | mirror → shipped plugin | L+D |
| 15 | Gradle wrapper from Tencent mirror without `distributionSha256Sum` | mirror → build toolchain | L+D |
| 16 | Release gate picks verifier-cli jar by first-match walk, ignoring the version pin (in-repo incident recorded) | CI cache → release verdict | L+D |
| 17 | Four MCP text tools parse agent SQL with no input-length cap (sibling channel enforces 20k) | AI agent → IDE process | L |
| 18 | SqlReverseParser has no recursion-depth guard (StackOverflowError via `sqlv_reverse_sql`) | AI agent → IDE process | L |
| 19 | OGNL evaluator leaks NumberFormatException/StackOverflowError out of its Err contract | mapper XML → MCP/UI | L |
| 20 | MyBatis Parameters text untruncated into quadratic regex | console → parse thread | L |
| 21 | ExecutionTimeParser whitespace-run quadratic backtracking | console → parse thread | L |
| 22 | MyBatis/Hibernate pending parser buckets never evicted (unbounded retention) | console → memory | L |
| 23 | History `.sql` reader has no per-line/per-file byte bound (incl. MCP-triggered path) | planted file → IDE heap | L |
| 24 | Agent-supplied history-search regex has no cost budget | AI agent → IDE thread | L |
| 25 | Imported dynamic-table-name regex rules unbounded on the render path | settings → IDE render | L |

## 5. Rejected records

None — no candidate was disproved during validation. Several recon-level suspicions were refuted during hunting before becoming candidates (notably: `CommunityDialog` escaper completeness, settings-import reaching the autosave path, and EXPLAIN wrapping executing non-SELECT statements); these are recorded as checks inside the ledger, not as rejected findings.

## 6. Hardening notes (non-finding improvements)

Highlights (full list in `hardening-notes.json` / ledger bookkeeping):

- **Unify sibling-channel bounds**: apply the 20k `maxParseLength` invariant to the four MCP text tools; add a reader-side per-line/per-file byte cap in `SqlLogFileReader`; bound agent-regex evaluation in history search.
- **Unify escaping**: one shared HTML-escape utility replacing the six per-site variants; apply the existing `html.disable` client-property pattern to the three unescaped labels/tooltips.
- **Agent channel**: add a shared secret/capability token to HELLO (both directions), verify claimed pid before serving re-bind recovery, cap desync warning floods.
- **Update chain**: add artifact checksum/signature to `update/update.json` (publish sha256 alongside downloadUrl); validate entry names in `UpdateZipGuard`; escape/cap the manifest version line in notifications.
- **Build**: commit `gradle/verification-metadata.xml`; add `distributionSha256Sum` to the wrapper; enforce the verifier-cli pin at all three resolution sites; pin Actions to commit SHAs.
- **Docs**: pre-commit guard for credential literals in `.agents/**`, `docs/**`, `dev-support/**`.
- **Positive patterns verified**: SecureXml single-parse-funnel with fail-closed pre-scan; McpSelectGuard fail-closed parse + layered gates; feedback channel bounded reads + fixed endpoints; no secrets in shipped source; TLS everywhere; clean SHA-256 usage; mcpStub excluded from packaging; fail-closed file resolver with symlink canonicalization.

## 7. Coverage summary (from the ledger)

| Status | Count |
|---|---|
| covered | 8 |
| candidate (closed with verified leads) | 19 |
| blocked / deferred / out_of_scope | 0 |

27 units across two waves (wave 2 added by the post-wave critic: `sqlv_schema_context`, MCP text-tools parse bound); **final-clean critic returned `stop=true`** — no missing units, no reassignments. Both validators pass on the final artifacts (`validate-findings.cjs`: 25 records valid; `validate-coverage-ledger.cjs`: 27 units valid). This is the first run for this repo: per the skill, one pass covers roughly half of what repeated runs find — the ledger and `findings.json` are the additive base for any future run.
