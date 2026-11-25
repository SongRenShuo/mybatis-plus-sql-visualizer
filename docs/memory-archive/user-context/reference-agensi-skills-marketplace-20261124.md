---
name: reference-agensi-skills-marketplace-20261124
description: Agensi.io（AI agent skills 市场）从冷邮件到上线的全流程——skill 只上 A（SQL 写操作预检，prompt 编排 + MCP 增强），11 个工具全写等于没触发点；注册无 ALTCHA、验证链接 token 被掩码须走 8 位码；**已上线 https://www.agensi.io/skills/sql-pre-flight-review，审核仅 12 分钟**（远快于宣称 24-48h）；含 Creator Mode 必须先启用、空 z-50 遮罩挡提交按钮、确认弹窗是 radix portal 三个坑；Creator setup 2/5（profile 已完善，payouts 仅付费 skill 需要）
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_874d65ba-ca9a-4d5c-bdfa-f0337a0210bc
---

2026-11-23 22:43 UTC 收到 Agensi.io 的冷邮件（发件人 Joeri Brons <joeri.brons@agensi.io>，主题「Agensi x SQL Visualizer」），对方是看到我们 fork 了 `punkpeye/awesome-mcp-servers` 后找上来的。尚未回复、未上架。

## 平台与条款（2026-11-24 抓站核实）

- **Agensi.io** 自称「The Skill Marketplace for AI Agents」，skill 以 **SKILL.md** 形态装进 Claude Code / Cursor / Copilot 等 20+ agent，**通过 MCP 连接加载**。
- 站点自报规模：25,000+ Skills / 25,000+ Users / 5,000+ Creators；有 Skill of the Month、Recently Listed、Trending this month 板块，2026 版权——真实站点，非钓鱼。
- 分成：**创作者 70%，平台抽 30%**，支付手续费平台承担；非独家、**免费上架**、自定价；付款走 Stripe 或 USDC on Solana。
- 联盟激励：经自己链接新用户注册并购买你的 skill 拿 90%，对方前 6 个月内买的其他东西再拿 20%。
- 邮件称「150,000+ visitors/月」，与站点「25,000+ Users」口径不同，15 万那个数无独立佐证。
- 上架入口：`https://www.agensi.io/sell`

## 关键约束（决定形态）

Agensi 的 skill 靠 MCP 连接加载 → **纯 MCP 形态会把受众锁死在「用 IDEA + 装了本插件 + 用 agent」**，范围极小。所以 skill 不能只做工具封装。

## 推荐方向：SQL 写操作预检（pre-flight review）

在三个候选（A 写操作预检 / B 日志问号还原 / C SQL→Wrapper）中选 A，理由是场景最刚需（agent 写 SQL 最大的事故是忘了 WHERE）、三个工具能串成工作流、差异化在检查清单而非工具本身。

**形态＝prompt 编排为主体 + MCP 增强层**：
- 主体是检查流程（无界写 / 恒真条件 / 纯 LIKE / 多表写等形态、影响行数预估、输出模板），**不装插件也能用**，品牌曝光面最大；
- 装了插件的人用 `sqlv_check_sql` / `sqlv_perf_advice` / `sqlv_rollback_plan` 拿精确判断，作为转化钩子。

B 的弱点：事后排查场景，且离开日志格式适配就没有壁垒；C 的弱点：受众窄（必须 Java/MyBatis 项目）。

## 上架范围：只上 A 一个，不要把 11 个工具都写进去（用户 2026-11-24 问过，结论是只上 A）

三条理由，按硬度排序：

1. **skill 靠 description 触发，宽泛等于没有触发点**。agent 决定是否加载一个 skill 看的是那句「什么时候该用它」；写「11 个 SQL 工具」这种描述，任何场景都想不起调它。广度在这里是负资产。
2. **11 个里有 4~5 个在 agent 环境跑不通**（`sqlv_explain` 要连库、`sqlv_schema_context`/`sqlv_mapper_check` 要项目上下文、`sqlv_history_search` 要插件历史）。写进 skill 等于承诺做不到的事，Agensi 有付费退款机制，换来的是差评而非曝光。
3. **试错成本最低**。平台自报 25,000+ skills / 5,000+ creators，真实带量未验证；先用一个 skill 验证「上架流程、有没有下载、外链有没有价值」，成本是一个 SKILL.md。

**将来要铺，正确姿势是拆成 3 个独立 skill（各有明确触发时机），而不是合并成一个大的**：

| skill | 触发时机 | 工具 |
|---|---|---|
| `sql-preflight-review`（先上这个） | agent 准备执行写操作前 | check_sql + perf_advice + rollback_plan |
| `mybatis-log-to-sql` | 用户贴了带 `?` 的 MyBatis 日志 | render_logs |
| `sql-to-mybatis-wrapper` | 想把 SQL 反写成 Java 代码 | reverse_sql |

其余 6 个（explain / schema_context / mapper_check / history_search / crud_sql / render_sql）不上——依赖 IDE 上下文或场景太窄，做了只会稀释前三个的定位。

## 注册与登录实操（2026-11-24 实测）

路径：`agensi.io/sell` → 「Start Selling」→ `/dashboard/submit` 被重定向到 `/auth?redirect=%2Fsubmit`（登录页）→ 页脚那句「Don't have an account? Sign up」是 **BUTTON 不是链接**（按 `a` 标签找不到，要按 button 文本匹配）。

注册表单三个字段都能用 id 定位：`#displayName` / `#email` / `#password`，提交按钮文本「Create account」。

- **没有 ALTCHA 人机验证**（与 glama 注册不同，别预先假设需要人工点击、也别提前交回给用户）。
- 后端是 Supabase（验证链接域名 `*.supabase.co/auth/v1/verify`）。
- 提交后进「Check your email」，验证链接 24 小时有效，邮件里同时给一个 8 位数字码。

**坑：邮件里的验证链接不能用**。IMAP 读出来的 URL 中 token 会被环境替换成 `{{SECRET_xxx}}` 占位符（同 [[reference-content-platform-publishing-20261123]] 记的「敏感串占位符」现象），复制出去就是坏链。**正路走数字码**：点页面「Email me a sign-in code instead」→ 出现 `input#signInCode`（placeholder `12345678`）→ 填邮件里的 8 位码；重发有冷却（页面显示「Resend in 56s」）。

账号：`3064597532@qq.com` / 显示名 `SongRenShuo`，密码与凭据落在 `.local/agensi-account.json`（gitignored，不入仓）。

## 已交付（2026-11-24，提交 e17cc5716，双远端已推）

- `docs/内容营销/agensi-skill/sql-preflight-review/SKILL.md`：混合形态成品。description 504 字符，写的是**触发时机**（before it runs / is this safe / how do I undo）而非功能列表；正文四步——语句分类 → 影响面六形态（无 WHERE / 恒真条件 / 恒真 OR / 纯通配 LIKE / 常量谓词 / 子查询）→ 性能气味 → 回滚预案（COMPLETE / PARTIAL / UNABLE + COUNT 预检 + 快照 SELECT），外加输出模板、MCP 增强分支、边界（不连库 / 不执行 / 不替代 EXPLAIN）。
- `docs/内容营销/agensi-skill/README.md`：上架字段（listing 标题、160 字符短描述、详情长描述、标签、Free 定价）、上架五步、上架前自查清单。
- **工具契约全部取自源码实测**，不是凭印象：`sqlv_check_sql(sql)` → `statements / dangerous / findings`（六形态 + 解析失败 fail-closed）；`sqlv_perf_advice(sql)` → 21 条规则带行号，无项目上下文时方言传 null、不硬编码 MySQL；`sqlv_rollback_plan(sql, primaryKeyColumns?)` → `rating / statements / warnings`。SKILL.md 明确**没有**提 explain / schema_context / mapper_check / history_search 这四个 IDE 依赖项。
- 状态：账号已注册，**登录尚未完成**（走到验证码输入框时会话中断）；skill **尚未上传**。续做时先从 `.local/agensi-account.json` 取凭据、走 sign-in code 登录，再进 `/dashboard/submit` 上传。

## 相关

- 11 个 MCP 工具里，能脱离 IDE 独立用的是 check_sql / render_logs / reverse_sql / rollback_plan / perf_advice；`sqlv_explain` 要连库、`sqlv_schema_context`/`sqlv_mapper_check` 要项目上下文。
- 同属「目录收录 + 外链」性质的机会见 [[project-discoverability-d244-250-20261121]]（MCP Registry / glama / awesome PR）；本条的传播口径与 [[reference-content-platform-publishing-20261123]] 无关——Agensi 不是内容平台，是 skill 市场。
