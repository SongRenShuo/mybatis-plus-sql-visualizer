# 12 同级双仓：协同开发与经验继承机制

调研时间：2026-11-25 14:00 ~ 15:30（北京时间）。
调研对象：本机现有插件仓、其 worktree 与同级独立仓；ZCode 客户端配置与记忆实现；现有经验资产。
方法：只读本机文件与 ZCode 打包代码（`C:\Program Files\ZCode\resources\glm\zcode.cjs` 与 `app.asar`），辅以 SQLite 只读查询。未运行任何构建，未修改任何仓库、ZCode 配置或记忆文件。本文是本次调研唯一写入的文件。

---

## 0. 结论速览

| 问题 | 结论 | 关键证据 |
|---|---|---|
| 工作区形态 | 选 (a)：新仓作为主仓工作区的“附加工作目录”；另设一个只读共享知识仓承担跨仓真相源。不选 meta 工作区与 sub-projects | §2；`zcode.cjs` 会话 schema 与 skill 根扫描分支 |
| 经验继承最可靠通道 | ① 用户级 `~/.zcode/AGENTS.md`（每次都加载）；② 附加目录复用主仓项目记忆（路径 hash 证明）；③ 共享知识仓只放“可移植规则”，两仓以指针引用 | §3.2~§3.4 |
| 代码复用 | 引擎打 jar 作为 JVM sidecar 产物，新仓按版本号+sha256 引用；不整包复制、不 submodule、不用 composite build | §4；03 分册 §9、08 分册 §0.2 |
| 单点维护对象 | 方言表、性能规则、JSON 契约、语料、兼容矩阵，各只允许一个权威位置 | §5.1 |
| 最需要先做的补救 | 主仓 `MEMORY.md` 已达 214 行 / 44.6k 字符，超过 ZCode 注入上限（200 行 / 25,000 字符），当前只加载约前 110 行。不瘦身就“继承记忆”，继承到的是残缺索引 | §1.4；注入告警原文 |
| 明确不做 | 不复制 `docs/` 全量；不把 IntelliJ 平台经验当通用经验；不用 hardlink 共享可变文件；不依赖 `~/.zcode/cli/memories/global/` 自动加载 | §6 |

---

## 1. 现场事实核对

### 1.1 目录与仓库拓扑

先厘清一个容易误判的点：会话里出现的 `mybatis-plus-sql-visualizer-dev`、`-kotlin-to-java`、`-d256-integration`、`mpsv-sql-insight` 不是“同级副本”，而是同一个 git 仓的 worktree。

```text
$ git -C F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer worktree list
F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer            bef5787e7 [master]
F:/code/Java/Gitee/SongRenShuo/mpsv-sql-insight                       a8b5cf6ff [t-sql-insight]
F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-d256-integration  40e5b009f [d256-integration]
F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-dev        2d695a979 [dev]
F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-kotlin-to-java    eeedfa526 [feature/kotlin-to-java]
...（另有 .worktrees/t-* 共 8 个）
```

| 路径 | 类型 | git 根 | 用途 | 与本次设计的关系 |
|---|---|---|---|---|
| `mybatis-plus-sql-visualizer` | 主仓，`master`，`ahead 2`，工作树有未提交改动 | 自身 | 用户在用的 IntelliJ 插件仓 | 经验与引擎的来源仓 |
| `mybatis-plus-sql-visualizer-dev` | worktree，分支 `dev` | 主仓 | 跟版/合流车道 | 不是独立仓，不参与“双仓” |
| `mybatis-plus-sql-visualizer-kotlin-to-java` | worktree，`feature/kotlin-to-java` | 主仓 | 100% Java 端口分支 | 同上 |
| `mybatis-plus-sql-visualizer-d256-integration`、`mpsv-sql-insight`、`.worktrees/t-*` | worktree | 主仓 | 任务车道 | 同上 |
| `mybatis-plus-sql-visualizer-docs` | 独立仓（`git rev-parse --show-toplevel` 指向自身） | 自身 | 文档站 | 已有的“同级独立仓”先例 |
| `mybatis-plus-sql-visualizer-demo` | 独立仓 | 自身 | 复现语料宿主工程 | 已有的“同级语料仓”先例 |
| `sql-visualizer-admin` | 独立仓 | 自身 | 官网后台 | 同级仓先例 |
| `sql-visualizer-recording` | 独立仓 | 自身 | 录制沙盒归档 | 同级仓先例 |

同级目录 `F:\code\Java\Gitee\SongRenShuo\` 下已有 40 个条目，其中与本项目相关的 11 个（含 `.wiki` 与 `-docs-dev`）。新仓若按“同级”落地，路径是 `F:\code\Java\Gitee\SongRenShuo\<新仓名>`。建议名 `mybatis-plus-sql-visualizer-openidea`：与现有前缀一致，`ls` 一眼能归组，也避免和 `openget`、`openget-searchcache` 这类既有目录混淆。

结论：本机已经存在“一主仓 + 多独立同级仓（docs/demo/admin/recording）+ 多 worktree”的混合拓扑，新仓只是这张图里再添一个独立同级仓，不是新发明一种形态。

### 1.2 可复用资产盘点

| 资产 | 规模（实测） | 性质 | 对新仓的处置 |
|---|---|---|---|
| `docs/LESSONS.md` | 1,206 行；`### L*` 标题 101 条；`Seen N×` 105 处 | 带证据与淘汰压力的规则账本 | 机制可搬，内容只挑“跨平台”条目；不整文件复制 |
| `docs/规范/` | 6 文件 784 行；`编码与验收标准-规范.md` 11 条 S + 1 条 M | 升级后的硬标准 | 通用条目进共享仓；IntelliJ 条目留主仓 |
| `docs/经验总结/` | 48 文件 5,699 行 | 事件叙事与排查过程 | 按主题引用，不复制 |
| `docs/架构设计/` | 3 篇共 1,871 行（另有 8 篇专项） | 主仓架构真相源 | 新仓架构文档另写；只引用“引擎边界”相关节 |
| `docs/开发计划/` | 771 文件，约 14MB | 任务计划与实施记录 | 不搬。新仓计划另立 |
| `docs/memory-archive/` | 536 文件，3.9MB | 已交付/已解决归档 | 不搬 |
| `.agents/skills/` | 8 个 skill，2,461 行，800KB | 代理作业法 | 逐个判定，见 §3.7 |
| `dev-support/` | 208 文件，顶层脚本 41 个 | 门禁/发行/录制/DB 装置 | 只搬“跨仓通用”的少量脚本；其余按需引用 |
| `database-lab/` + `dev-support/db-env/` | 13 个数据库容器配方 | 真机环境 | 服务器级资产，宜提升到用户级/共享仓 |
| `src/test/` | 994 个 `.kt`，973 个 `*Test.kt`，13MB | JUnit + Light/Heavy 夹具 | 测试代码不可直接复用；测试“数据”可抽语料 |
| `src/main/kotlin/` | 613 文件 42,581 行 | Kotlin 引擎 + UI | 核心 16 包 41,583 行，其中 26,168 行（63%）零改动可编译进 sidecar（03 分册 §9.2、08 分册 §0.2） |
| `sub-projects/sql-visualizer-agent` | 独立子项目，产出 `mps-sql-agent.jar` | “子项目产出 jar 再内嵌”的现成范式 | 引擎 jar 可照此建模块（§4.3） |
| `src/main/resources/icons` + `messages` | 55 个 SVG/PNG；中英两份 bundle | 视觉与语言资产 | 挑用，不整目录搬（§4.5） |
| `LICENSE` / `THIRD-PARTY-NOTICES.md` | Apache-2.0；生产依赖仅 JSqlParser 5.3 | 许可边界 | 引擎 jar 随包分发时必须带 NOTICE 与许可证全文 |

### 1.3 ZCode 配置作用域

权威原文：`C:\Users\USER\.zcode\cli\plugins\cache\zcode-plugins-official\zcode-guide\0.3.0\skills\zcode-configuration-guide\SKILL.md`（下称“配置指南”）。

| 资源 | 用户级 | 工作区级 | 冲突/优先级 | 出处 |
|---|---|---|---|---|
| 指令 | `~/.zcode/AGENTS.md` | `<repo>/AGENTS.md` | 先注入用户级，再注入工作区级；后者可收窄前者 | 配置指南:23-24、:46 |
| Skills | `~/.zcode/skills/`、`~/.agents/skills/` | `<repo>/.zcode/skills/`、`<repo>/.agents/skills/` | 路径即身份；同名只加载发现顺序第一个（用户级优先） | 配置指南:30、:57-62 |
| Commands | `~/.zcode/commands/`、`~/.agents/commands/` | `<repo>/.zcode/commands/`、`<repo>/.agents/commands/` | 按归一化命令名去重，先到先得 | 配置指南:31 |
| MCP | `~/.zcode/cli/config.json` → `mcp.servers` | `<repo>/.zcode/config.json` → `mcp.servers` | 同名用户级覆盖工作区级 | 配置指南:32 |
| Hooks | `~/.zcode/cli/config.json` → `hooks` | `<repo>/.zcode/config.json` → `hooks` | 配置文件钩子要求 `hooks.enabled: true` | 配置指南:33 |
| Plugins | `~/.zcode/cli/config.json` 的启用状态 | 无 | 插件可贡献 skills/commands/hooks/MCP/agents | 配置指南:34 |

Skills 的完整发现顺序（配置指南:52-58；`diagnosing-skills` SKILL.md:12-21 同口径）：

```text
1 显式配置的根
2 ~/.zcode/skills
3 ~/.agents/skills
4 <repo>/.zcode/skills（从 cwd 逐级上溯到仓库根，每一级都算）
5 <repo>/.agents/skills
6 已启用插件的根
```

`AGENTS.md` 的解析范围比“上溯到仓库根”更窄，这一点已从打包代码核实：

```js
// zcode.cjs：projectRoot 默认为工作目录的 git 根；从 cwd 逐级上溯到 projectRoot（含）
async function V7s(e,t,n=process.env){
  let o=e.priorityFiles??["AGENTS.md"], a=e.projectRoot??await UJr(e.workingDirectory),
      c=await Y7s(o,n), u=await Z7s(e.workingDirectory,a,o), ...
}
async function Z7s(e,t,n){
  let o=path.resolve(e);
  for(;;){ for(let i of n){ let a=path.join(o,i); if(await exists(a)) return {filePath:a,fileName:i}; }
    if(o===t||o===path.dirname(o)) break; o=path.dirname(o); }
}
```

推论（对本次设计很关键）：仓库根以上的父目录里的 `AGENTS.md` 不会被读到；因此把共享规则放在 `F:\code\Java\Gitee\SongRenShuo\AGENTS.md` 这种“上层目录”不会生效。共享只能走用户级文件、共享仓引用或附加目录会话。

### 1.4 记忆隔离机制（硬核部分）

ZCode 的项目记忆根不是按“仓库名”或“远端 URL”分配，而是按工作区绝对路径的 hash 分配。打包代码原文：

```js
function tqe(e){
  let t=e.workspaceIdentity?.trim(),
      n=path.resolve(e.workspacePath),
      o=t || (process.platform==="win32" ? n.toLowerCase() : n),
      i=createHash("sha256").update(o).digest("hex").slice(0,16),
      a=t ? "project" : sanitize(basename(n)||"project");
  return join(cliStorageRoot,"memories","projects",`${a}-${i}`,"memory")
}
```

即：

```text
目录名 = <basename(工作区路径)>-<sha256(小写绝对路径)[:16]>
例外：workspaceIdentity 非空时 → project-<sha256(identity)[:16]>
```

实测校验（4/4 命中）：

| 仓库 | 计算出的目录名 | 磁盘上是否存在 |
|---|---|---|
| `openget` | `openget-38dd8ffc5702a615` | 存在 |
| `mybatis-plus-sql-visualizer-demo` | `mybatis-plus-sql-visualizer-demo-4ae36a99e72a1329` | 存在 |
| `mybatis-plus-sql-visualizer-docs` | `mybatis-plus-sql-visualizer-docs-52e80143e64f7072` | 存在 |
| `sql-visualizer-admin` | `sql-visualizer-admin-b901295846d6b547` | 存在 |

由此得到三条硬结论：

1. 同名不同路径各自独立：`mybatis-plus-sql-visualizer` 与 `mybatis-plus-sql-visualizer-dev` 是两个 hash，后者在当前磁盘上没有记忆目录。同级新建的 `-openidea` 必然拿到第三个空目录。
2. 记忆不跟 git 远端走、不跟仓库内容走，只跟路径走。移动/改名仓库等于换一份记忆。
3. `workspaceIdentity` 可以把多路径合并到同一个记忆根（目录名退化为 `project-<hash>`）。现有项目记忆里 `project-1c30f397d404ac04` 就是这一分支的实例；其余 22 个都是 `<basename>-<hash>`。该字段由客户端在特定工作区（远程/托管）写入，不是给本地双仓共享设计的，不建议手工利用。

记忆的实际注入方式与上限（同一份打包代码）：

```js
// MEMORY.md 注入：最多 200 行、25,000 字符；超限截断并追加 WARNING
z3n=200; g7e=25e3
```

主仓当前读数：

| 指标 | 实测 | 上限 | 结果 |
|---|---:|---:|---|
| `MEMORY.md` 行数 | 214 | 200 | 超 |
| `MEMORY.md` 字符数（JS length） | 44,635 | 25,000 | 超 |
| 磁盘 UTF-8 字节数 | 67,521 | —（仅参考） | — |
| 实际注入 | 约前 110 行 | — | 其余被截断 |

父会话模型请求里留有原文：

```text
> WARNING: MEMORY.md is 210 lines and 42.8KB. Only part of it was loaded:
  100 of 210 lines were cut off, starting at line 111
  ("- **物料与凭据**：[[reference-marketplace-api-quirks]]｜…")
```

出处：`~/.zcode/cli/rollout/model-io-sess_1e0efec0-*.jsonl`（父会话请求快照）。告警里的 “42.8KB” 是 JS 字符串长度（中文按 1 个字符计，不是磁盘字节）；“210 行”是注入时刻的读数。上表是 2026-11-25 15:25 的文件快照——父会话仍在并行更新该文件，行数与字符数会继续变。两个口径都远超上限，字符上限实际决定了截断点，最终只加载约前 110 行。这直接否定了“把主仓记忆整目录复制给新仓就能继承全部经验”：源索引本身已经只加载一半。

`~/.zcode/cli/memories/global/` 的定位也要说清：

| 事实 | 证据 |
|---|---|
| 现有 129 个 `.md`，`README.md` 120 条索引行 | `ls global/*.md \| wc -l`；README 表格行统计 |
| 该目录不会被 ZCode 自动加载 | global/README.md:6 原文“本目录不会被 ZCode 自动加载，需手动查阅”；打包代码里没有 `memories/global` 字面量 |
| 用户级 AGENTS.md 自述 115 个内容文件 | `~/.zcode/AGENTS.md:6`；与实际 129 已不一致 |

另一套 MCP 记忆（`magpie-memory`）已安装但为空：

```text
memory_context → Project: gitee.com/<owner>/mybatis-plus-sql-visualizer
memory_search → Nothing is remembered here yet.
```

所以今天真正生效的只有“按路径隔离的项目记忆 + 用户级 AGENTS.md”两条通道。

### 1.5 附加工作目录（additional working directories）

会话 schema 明确支持附加目录，最多 256 个：

```js
additionalDirectories: d.array(d.string().min(1)).max(256).optional()
```

桌面包 `app.asar` 里存在 UI 文案“添加文件夹”，`additionalDirectories` 出现 206 次，说明这是产品面能力，不是内部字段。

它同时影响三类发现：

| 影响面 | 代码行为 | 证据 |
|---|---|---|
| Skills | 附加目录下的 `.zcode/skills`、`.agents/skills` 被扫描，且 `namespace = basename(目录)` | `zcode.cjs`：`...lAn(h,"project",u).map(_=>({..._,namespace:basename(h)}))` |
| Subagents | 附加目录下的 `.zcode/agents` 被扫描，同样按 basename 加命名空间 | `zcode.cjs`：`n.push({path:join(u,".zcode","agents"),source:"project",namespace:basename(u)})` |
| Bash | 附加目录进入 `additionalWorkingDirectories`，命令可在其中执行 | `zcode.cjs`：`additionalWorkingDirectories:[...o]` |

本会话可直接观察到的现象与源码一致：技能清单里出现 `mybatis-plus-sql-visualizer-dev/db-env-provisioning`、`mybatis-plus-sql-visualizer-kotlin-to-java/...` 这类“目录名/技能名”的命名空间形态。

三个限制必须写进设计：

1. 附加目录的 `AGENTS.md` 不会被加载。指令解析只使用 `workingDirectory` 与单一 `projectRoot`（§1.3 的 `V7s`/`Z7s`），没有遍历附加目录。
2. 附加目录不改变记忆根。记忆根按会话的工作区路径计算（§1.4），附加目录共享同一份项目记忆。这既是“继承经验”的通道，也是“两个仓的规则混在一份索引里”的风险。
3. 附加目录的持久化位置不在可读配置文件里。`~/.zcode/cli/config.json` 只有 plugins/mcp/skills/command/hooks 五段；`%APPDATA%\ZCode\data\.zcode\v2\setting.json` 里与工作区相关的只有 `recentProjects` 与 `lastWorkspaceSession`，没有 `additionalDirectories`。它随会话记录走（`createSessionRecord({additionalDirectories})`），具体落库位置标“待验证”（§7）。

另有一条与“多根”相关的事实：任务索引库支持一个项目多个根，但当前没被使用。

```sql
-- 库：%APPDATA%\ZCode\data\.zcode\v2\tasks-index.sqlite
-- 表 project_roots：project_id, root_path, position, is_primary
-- 实测 5 个项目，每个只有 1 个 root，全部 is_primary=1
```

这意味着“多根工作区”在产品模型里是存在的，但日常还没有用起来；双仓场景若要长期使用，需要先在桌面里显式添加第二个根。

### 1.6 技能能否跨仓共享

| 事实 | 实测 |
|---|---|
| 用户级技能根存在 | `~/.zcode/skills/` 存在：8 个 apifox 技能的符号链接 + `huawei-cloud-grow-daily` 实体目录 + 一个 zip |
| 用户级 `.agents` 技能根存在 | `~/.agents/skills/` 有 10 个条目（apifox 系列 + `advanced-iterative-mode` + `sam`） |
| 主仓 8 个技能是否已提升到用户级 | 没有。`~/.agents/skills` 与 `~/.zcode/skills` 都看不到 `release-workflow`、`ide-ui-acceptance` 等 |
| 符号链接是否被跟随 | 现象上可用，但证据不唯一：`~/.zcode/skills/apifox-*` 是软链，`config.json` 里登记的禁用路径是解析后的 `C:/Users/USER/.agents/skills/...`，而 `.agents/skills` 本身也是扫描根，无法区分“跟随了软链”还是“直接扫了真目录”。标待验证（§7） |
| 同名遮蔽规则 | 用户级优先于工作区级；同一层 `.zcode` 先于 `.agents`（配置指南:57-62、`diagnosing-skills`:21） |

对设计的含义：用户级是“跨仓共享”的正确位置，但每提升一个技能，都会在所有工作区（包括无关项目）里多一段技能描述进入模型上下文。提升名单必须按“通用性”筛，不能整目录搬。

---

## 2. 目录与工作区形态

三个候选的对比：

| 维度 | (a) 两仓各自独立工作区，靠附加工作目录单会话同开 | (b) 上层 meta 工作区，两仓作附加目录 | (c) 新仓放主仓 `sub-projects/` 下 |
|---|---|---|---|
| 记忆归属 | 主仓记忆（若以主仓为主根）或新仓记忆（若以新仓为主根），二者取一 | meta 目录的记忆（当前为空），两仓项目记忆都不可见 | 跟随主仓记忆，但两仓物理耦合 |
| 经验继承 | 附加目录共享主仓记忆，零复制 | 需要把共享规则写进 meta 的 AGENTS.md，等于重新建一份 | 共享主仓记忆 |
| AGENTS.md 生效 | 主根 AGENTS.md 生效；附加目录的 AGENTS.md 不生效 | 只有 meta/AGENTS.md 生效 | 主仓 AGENTS.md 生效 |
| 独立演进 | 两仓完全独立，CI/发行/许可互不牵连 | 两仓仍独立，但多一层目录 | 新仓被主仓 git 边界裹住，独立发行困难 |
| 已有先例 | docs/demo/admin/recording 都是同级独立仓 | 无 | 只有同仓构建子项目（agent），不是独立仓 |
| 代价 | 需要每次显式“添加文件夹”；主仓索引要容纳两仓活跃项 | 记忆与技能都要重建；多一层心智负担 | 嵌套 git 仓、构建扫描、许可与发行全部纠缠 |

推荐：以 (a) 作为日常协同形态，另设一个“只读共享知识仓”（不是工作区，不作为 meta 打开）承担跨仓真相源。(b) 的问题不是“多一层目录”，而是记忆与指令都以 meta 为零点：主仓 214 行的记忆、新仓未来的记忆都不会被加载，等于把最贵的资产留在门外。(c) 与用户“同级”的前提冲突，且 `sub-projects/README.md:1-13` 明确该目录是“同仓组合构建”的约定（`include + projectDir 重定向`），不是独立仓的容器。

落地方式：

```text
F:\code\Java\Gitee\SongRenShuo\
├─ mybatis-plus-sql-visualizer\           主仓（IntelliJ 插件）
├─ mybatis-plus-sql-visualizer-openidea\  新仓（OpenIDEA 插件，独立 git）
└─ mpsv-shared\                            共享知识仓（只读引用，独立 git）

ZCode 日常会话：工作区 = 主仓；附加目录 = 新仓（+ 需要时 mpsv-shared）
新仓独立会话：工作区 = 新仓；靠用户级 AGENTS.md + 新仓 AGENTS.md 兜底
```

为什么共享仓不放进 meta 目录：`mpsv-shared` 需要自己的版本、tag、CI 与许可声明；作为独立 git 仓才能被两仓以固定 tag 引用并做校验。它不承担“工作区”角色，只承担“事实源”角色。

---

## 3. 经验继承的具体机制

### 3.1 通道排序

| 序 | 通道 | 覆盖范围 | 是否自动 | 可靠性 | 维护成本 | 结论 |
|---|---|---|---|---|---|---|
| 1 | 用户级 `~/.zcode/AGENTS.md` | 所有工作区 | 每次会话注入 | 高（平台保证） | 低（单文件） | 必做，只放跨仓硬规则 |
| 2 | 附加目录共享主仓项目记忆 | 该会话 | 自动 | 高（路径 hash 保证） | 低 | 日常协同主通道；先瘦身索引 |
| 3 | 共享知识仓（规则/契约/语料） | 两仓 | 需显式读取/引用 | 中高（有版本与校验） | 中 | 必做，解决“同一事实两处漂移” |
| 4 | 新仓自带 `AGENTS.md` + 精简 LESSONS | 新仓 | 自动 | 高 | 低 | 必做，承载新平台特有规则 |
| 5 | 用户级 skills | 所有工作区 | 自动发现 | 高 | 低 | 只提升通用技能 |
| 6 | `~/.zcode/cli/memories/global/` | 全域 | 不自动 | 低（需手动查） | 中 | 不作为主通道；内容转写进 AGENTS.md 摘要 |
| 7 | 克隆项目记忆目录 | 新仓 | 自动 | 低（陈旧 + 截断 + 污染） | 高 | 不推荐 |

排序依据不是“技术能不能”，而是“下次开会话时它会不会自己出现”。只有 1、2、4、5 是自动的；3 需要一次显式读取（或写进 1/4 作为指针）；6、7 是手工且易腐。

### 3.2 通道一：用户级 AGENTS.md

现状（实测）：

| 项 | 值 |
|---|---|
| 路径 | `C:\Users\USER\.zcode\AGENTS.md` |
| 规模 | 20,302 字节 / 98 行 |
| 章节 | 执行纪律、多智能体并行开发铁律、用户偏好、IntelliJ 平台通用陷阱、验证与收口纪律、研究方法论 |
| 加载上限 | 单文件 100KB（`W7s=100*1024`） |

该文件已经承担了“跨项目经验摘要”的角色，但内容高度偏 IntelliJ 与主仓工作流。新仓需要的增量不是再写一份长文，而是加一节 10~15 行的“跨仓协作”规则，并把已有的通用纪律保留：

建议新增小节（只写新仓会违反、且两仓都成立的事）：

```markdown
## 跨仓协作（MPSV 双仓）

- 主仓 = mybatis-plus-sql-visualizer（IntelliJ/Kotlin），新仓 = mybatis-plus-sql-visualizer-openidea（Rust/WASM）。
  两仓各自独立 git、独立 CI、独立发行；任何跨仓事实只允许一个权威位置（见 mpsv-shared/README）。
- 改共享契约（方言表/性能规则/JSON schema/语料）前，先确认 mpsv-shared 的当前 tag；
  两仓都必须跑契约校验，禁止只改一边。
- IntelliJ 平台经验（PSI/EDT/ExtensionPoint/runIde 沙盒）不适用于新仓，不得作为通用经验引用。
- 新仓没有 PSI 与类型系统；涉及 Java 语义的方案先查 docs/调研报告/20261125-openidea-support/ 的四档结论。
- 跨仓改动在提交信息里写 `cross-repo: <共享仓 tag>`；两仓 CHANGELOG 各记各的，不互相代记。
```

风险与对策：

| 风险 | 证据 | 对策 |
|---|---|---|
| 文件继续膨胀，挤压每次会话的上下文 | 20KB 已占不少；上限 100KB | 给该文件设 40KB 软上限；超限把细节移入 `mpsv-shared/` 并留指针 |
| 与仓库级 AGENTS.md 重复 | 主仓 `AGENTS.md:1-16` 与新仓将要写的规则会重叠 | 用户级只写“两仓都成立”的话；仓库级写“本仓特有”的话；同一句只出现一次 |
| 手工维护的计数会漂移 | `~/.zcode/AGENTS.md:6` 写“115 个内容文件”，实际 129 个；global README 写 18/23/68，实际表 120 行 | 不在 AGENTS.md 里写会变的计数；需要计数就写“以 `ls` 为准” |

### 3.3 通道二：附加目录 + 主仓项目记忆

这是“继承现有全部编程经验”的最短路径，机制上成立且无需复制：

1. 以主仓为工作区打开 ZCode（当前即如此）。
2. 用“添加文件夹”把新仓加入附加目录。
3. 会话的技能清单会出现 `mybatis-plus-sql-visualizer-openidea/<skill>` 命名空间；Bash 可在两个目录执行；模型上下文里注入的仍是主仓 `MEMORY.md`（路径 hash 未变）。
4. 在新仓里做的决策，写回主仓 `MEMORY.md` 或共享仓，而不是新仓的独立记忆（因为本会话根本不会读新仓记忆）。

必须先做的补救：主仓索引已超注入上限（§1.4），当前只加载约前 110 行。若直接以此会话开发新仓，模型看到的是残缺索引，且看不到索引后半段的大量参考条目。建议先做一次“索引瘦身”：

| 动作 | 判据 |
|---|---|
| 把已交付/已解决的条目移入 `docs/memory-archive/` | 按 `docs/规范/记忆精简规范.md:14-33` 的三类标准 |
| 把“跨项目通用”的条目移入共享仓或用户级摘要 | 同上“全局标准”节 |
| 控制 `MEMORY.md` ≤ 200 行且 ≤ 25,000 字符 | 重新开会话后注入文本中不再出现 `WARNING: MEMORY.md` |
| 活跃项（双仓在途）保留在最前 100 行 | 保证截断发生时先丢的是低价值条目 |

附加目录形态的边界要说清：

| 能做 | 不能做 |
|---|---|
| 一个会话同时读改两仓 | 新仓 `AGENTS.md` 不会自动加载 |
| 共享主仓项目记忆与用户级指令 | 两仓各自的 `.agents/skills` 会同时出现，同名技能按 basename 命名空间并存（不会互相遮蔽，但会增加上下文） |
| 用 Bash 在两仓执行命令 | 新仓的 `MEMORY.md` 不会被读取（若它自己开工作区则相反） |
| 跨仓提交（在各自仓内 `git -C`） | 一次提交跨两仓（git 不支持；只能两次提交 + 交叉引用） |

### 3.4 通道三：共享知识仓

共享仓只装“两边都必须一致、且会变”的东西。候选内容与归属：

| 内容 | 是否进共享仓 | 理由 |
|---|---|---|
| 可移植教训（方法论、验证纪律、代理协作、用户偏好） | 是 | 两仓通用，且最容易两处漂移 |
| 方言能力表、性能规则表、分享 JSON schema | 是（机器可读 JSON） | 跨语言消费，必须单点 |
| 跨仓兼容矩阵（插件版本 ↔ 引擎 jar ↔ 契约 tag） | 是 | 发布协同的唯一索引 |
| 语料（SQL/XML/期望输出，语言中立 JSONL） | 是 | 两仓测试共用的“对拍基准” |
| IntelliJ 平台陷阱（PSI/EDT/EP） | 否 | 新仓无此平台，带过去只有噪音 |
| 新仓 Rust/WASM 平台陷阱 | 否（留新仓） | 主仓不消费 |
| 主仓架构文档、开发计划、发行说明 | 否 | 单仓事实，引用即可 |
| 用户环境/代理/服务器凭据 | 否 | 敏感；只在用户级或 `.local/` |

三种引用方式对比：

| 方式 | 一致性 | CI 可用 | 离线可用 | 风险 | 结论 |
|---|---|---|---|---|---|
| git submodule | 强（固定 commit） | 是（需 CI 递归拉取） | 是（已初始化时） | 子模块指针易被误改；Windows 长路径与凭证偶发 | 推荐，共享仓体量小 |
| junction/符号链接 | 强（同一份文件） | 否（CI 不认本机链接） | 是 | 可变文件被两边同时编辑；Windows 链接需权限；git 不跟踪链接目标 | 只允许用于“本机只读视图”，不进仓 |
| 复制 + 校验和 | 弱（靠脚本发现漂移） | 是 | 是 | 双份腐化，靠人记得跑校验 | 只在无法 submodule 时用，且必须有 CI 校验 |

推荐组合：共享仓作为 submodule 挂到两仓的 `shared/`（只读约定），CI 里 `git submodule update --init --recursive`；本机不建 junction，避免“IDE/代理写到链接目标”的隐性双写。若用户不喜欢 submodule，退路是“固定 tag + CI 下载归档 + sha256 校验”，仍然保持单点。

共享仓的最小目录：

```text
mpsv-shared/
├─ README.md                  # 索引 + 版本策略 + 谁拥有哪类事实
├─ lessons/portable.md        # 只收跨平台规则（每条带 Seen 与证据）
├─ standards/                 # 通用编码/验收/文档规范
├─ contracts/
│  ├─ dialects.json           # 方言能力矩阵
│  ├─ perf-rules.json         # 性能规则表
│  ├─ share-schema.json       # 分享报告 schema
│  └─ compat-matrix.md        # 插件版本 ↔ 引擎 jar ↔ 契约 tag
├─ corpus/
│  ├─ cases.jsonl             # 输入 + 期望（语言中立）
│  └─ README.md               # 用例来源与不可变性约定
└─ LICENSE / NOTICE
```

归属清单（回答“哪些必须每仓一份”）：

| 必须每仓一份 | 理由 |
|---|---|
| `AGENTS.md`（仓库级） | 两仓平台、构建、验证纪律不同；用户级只放两仓共有的硬规则 |
| `CHANGELOG.md`、发行说明 | 发行节奏独立，读者对象不同 |
| 版本号文件（`gradle.properties` / `Cargo.toml`） | 版本线独立，强绑会让无关改动触发对方发版 |
| `.github/workflows/` | CI 执行环境与门禁不同（Gradle/verifyPlugin vs cargo/wasm check） |
| 平台特有 `LESSONS.md` 内容 | IntelliJ 的 PSI/EDT 经验与新仓的 WASI/资源限额经验互不适用 |
| 平台测试与夹具 | JUnit + Light/Heavy 与 `cargo test` + mock host 不能共用代码 |
| `.tasks/` | 本地会话状态，跨仓共享会让任务归属失真 |
| `LICENSE` / `NOTICE` | 各自发行物必须自带许可声明 |

| 可共享（单点维护） | 理由 |
|---|---|
| 可移植教训、通用规范 | 与方法论/验证纪律相关，与平台无关 |
| 契约 JSON（方言/性能/schema） | 跨语言消费，必须同一份 |
| 语料（语言中立 JSONL） | 两仓对拍基准 |
| 兼容矩阵 | 发布协同的唯一索引 |
| i18n 术语表 | 同一产品的语言资产 |
| 服务器运维配方（13 个 DB 容器等） | 基础设施不因仓而变 |

### 3.5 通道四：global 记忆目录

事实：不自动加载（§1.4）。它现在的价值是“人工知识库”，不是“代理记忆”。两种处置：

| 处置 | 做法 | 适用 |
|---|---|---|
| 转写摘要 | 把真正跨项目的规则压缩进用户级 AGENTS.md（已有先例：AGENTS.md 就是 global 的精炼摘要） | 推荐；保证每次都进上下文 |
| 保留为查阅库 | 在共享仓 README 与用户级 AGENTS.md 里各留一条“需要时读 `~/.zcode/cli/memories/global/README.md`” | 作为索引存在，但不指望自动生效 |
| 改用 magpie-memory 的 global scope | 该 MCP 已装但为空；`memory_context` 显示 global 与 project 分开检索 | 可选；跨代理（非 ZCode）时才有额外收益 |

不建议把新仓经验写进 `memories/global/` 就认为“继承完成”——下一次会话不会读它。

### 3.6 通道五：项目记忆目录的“克隆”

技术上可行：新仓路径 hash 是确定的，把主仓 `memory/` 复制成 `<新仓名>-<新hash>/memory/`，下次以新仓为工作区时就会注入。但风险大于收益：

| 风险 | 说明 |
|---|---|
| 陈旧引用 | 主仓记忆里大量条目写的是主仓文件路径、提交哈希、分支名、worktree 名；在新仓里全部指错 |
| 索引已截断 | 源索引 214 行 / 44.6k 字符，复制过去仍然只加载前 110 行，且截断点落在别人的条目上 |
| 语义污染 | “T-MapperNavYieldBothSides 已推 master”这类状态对新仓是纯噪音，会诱导代理做错误判断 |
| 双向维护 | 复制后两边各自更新，很快出现两份互不一致的“同一段历史” |
| junction 更糟 | 两仓共用一份可变索引，任何一边写入都会改变另一边的上下文；且并发会话会互相覆盖 |

结论：不复制、不 junction 整目录。若确实要“带一点底子”，只允许复制一份人工挑选的 `portable-seed.md`（20~30 条方法论/偏好），作为新仓 `MEMORY.md` 的初始内容，并在文件头写明“来源：主仓 2026-11-25 快照，主仓为准”。

### 3.7 技能共享取舍

逐个判定（依据：技能正文是否绑定 IntelliJ/本仓路径/本仓脚本）：

| 技能 | 行数 | 绑定情况 | 对新仓 | 建议归属 |
|---|---:|---|---|---|
| `security-audit` | 192 | 通用安全审计流程，无仓内路径 | 完全适用 | 提升到用户级（`~/.agents/skills`），两仓共用 |
| `task-queue` | 95 | 只依赖 `.tasks/` 相对协议；`.tasks/` 已被 `.gitignore` 忽略 | 完全适用 | 提升到用户级；两仓各自维护自己的 `.tasks/` |
| `db-env-provisioning` | 948 | 面向测试服务器与 Docker 数据库；引用 `dev-support/db-*` 与 gradle 命令 | 部分适用（新仓若走 JDBC sidecar 仍需真机库） | 放共享仓/用户级；正文里主仓专用命令改成“按需替换” |
| `srv-backup-restore` | 331 | 服务器级运维，引用 `dev-support/svbackup/` 作为事实源 | 适用（服务器不因仓而变） | 放用户级或共享仓；事实源仍留主仓，避免搬家 |
| `release-workflow` | 218 | 强绑定 IntelliJ 插件八处同步、changeNotes、`update.json`、Marketplace | 不适用 | 留主仓；新仓另写一份 `openidea-release` |
| `ide-ui-acceptance` | 117 | runIde 沙盒、robot-server、EDT 证据分级 | 不适用 | 留主仓 |
| `ide-demo-gif` | 458 | IntelliJ 沙盒启动命令与许可、gutter 像素定位 | 部分适用（pyautogui/GIF 转制通用，启动与锚点不通用） | 留主仓；新仓按需抽通用脚本，不整技能搬 |
| `k2j-port-sync` | 102 | 仅服务于 Kotlin→Java 端口分支 | 无关 | 留主仓 |

提升方式与验收：

```text
# 推荐：复制到用户级（保持仓库内副本不动，用户级优先遮蔽）
cp -r <repo>/.agents/skills/security-audit  ~/.agents/skills/security-audit
cp -r <repo>/.agents/skills/task-queue      ~/.agents/skills/task-queue
```

验收：新开一个与主仓无关的工作区，`Settings → Skills` 能看到这些技能；再在主仓开会话，确认同名时用户级胜出、工作区副本被遮蔽（同一技能不会两份同时进入上下文）。

注意：不要把 `release-workflow` 提升到用户级。它在任何工作区都会宣称“发版走八处同步”，在新仓是错误指令。技能描述会进入模型上下文，错误指令的代价高于省下的复制成本。

---

## 4. 代码能力复用

### 4.1 三条路线对比

| 维度 | (a) 复制 26,168 行 + 手动移植流程 | (b) 引擎抽成独立产物（jar + 版本号） | (c) composite build / submodule 直接引用主仓模块 |
|---|---|---|---|
| 首次可用时间 | 最快（复制即可编译） | 中（要在主仓加导出模块与产物流水线） | 中（新仓要装 JDK/Gradle 并拉主仓） |
| 后续上游变更 | 手工 diff + 移植 + 语料对拍，长期人力 | 升级版本号 + 跑对拍；引擎内部改动对消费方透明 | 直接跟主仓 HEAD，变更立即进入 |
| 发布耦合 | 无（复制后完全分叉） | 弱（消费方钉版本；可滞后升级） | 强（构建时依赖主仓状态） |
| 对主仓独立演进的影响 | 无，但两边逐渐分叉 | 小（只多一个导出任务/模块） | 大（主仓目录结构、构建脚本成为对外契约） |
| 许可与归属 | 简单（同许可） | 简单（jar 附 LICENSE/NOTICE；JSqlParser 双许可择 Apache-2.0） | 简单但发布物边界不清 |
| 新仓技术栈适配 | 需自己搭 JVM 构建 | 天然适配 sidecar（新仓只需随包带 jar） | 需要在新仓引入 Gradle/JDK，与 cargo 构建链并行 |
| 适用前提 | 新仓准备永久分叉 | 新仓把引擎当黑盒 | 两仓同语言同构建系统 |

推荐 (b)，理由与 03 分册的结论一致：核心 16 包 41,583 行中 26,168 行（63%）可零改动编译进 sidecar（03 分册 §9.2），真正要重写的是 `psi/` 与 PSI 直连 converter（约 19.7k 行）。把“可复用的 63%”冻结成 jar，正好把重写工作留在新仓，把演进留在主仓。

(a) 只在一种情况下选：新仓确定要长期维护自己的 SQL 引擎分支，且不打算跟主仓版本。以当前信息看没有这个必要。

(c) 不选：新仓是 cargo 工程，引入 Gradle composite build 等于把两套构建系统绑在一起；submodule 拉整个主仓（含 33MB docs、771 个开发计划）成本过高，且主仓目录结构会变成对外契约。

### 4.2 产物设计

```text
主仓
└─ sub-projects/engine-jar/            # 新模块，照 sql-visualizer-agent 的约定
   ├─ build.gradle.kts                 # sourceSets 指向 ../../src/main/kotlin，按包 include/exclude 圈定
   └─ README.md                        # 收录哪些包、排除哪些、升级流程

产物：mpsv-engine-<主仓版本>.jar
附带：mpsv-engine-<版本>.sha256、LICENSE、THIRD-PARTY-NOTICES.md
```

为什么不物理搬代码：`settings.gradle.kts:5-13` 已有“include + projectDir 重定向”的先例；新模块可以直接把源集指向主源码树，用 `include`/`exclude` 圈定 16 个纯包，零代码移动、零行为变化。等边界稳定后再考虑物理抽取。

新仓侧：

```text
mybatis-plus-sql-visualizer-openidea/
├─ engine/
│  ├─ mpsv-engine-2026.11.25.jar
│  ├─ mpsv-engine-2026.11.25.jar.sha256
│  ├─ ENGINE_VERSION            # 版本号 + 来源提交 + 契约 tag
│  ├─ LICENSE
│  └─ THIRD-PARTY-NOTICES.md
```

`ENGINE_VERSION` 建议内容：

```text
engine=2026.11.25
source=mybatis-plus-sql-visualizer@<commit>
contracts=mpsv-shared@<tag>
sha256=<jar 的 sha256>
```

升级流程（每步都有读数）：

1. 主仓打引擎产物：`./gradlew :engine-jar:jar`，记录 jar 的 sha256。
2. 把 jar 与 sha256 放入新仓 `engine/`，更新 `ENGINE_VERSION`。
3. 新仓跑语料对拍：`cargo test --test corpus`，要求 N/N 全过（N 为 `shared/corpus/cases.jsonl` 行数）。
4. 任一条失败：不降级、不放宽断言；在主仓开 issue 标 `cross-repo`，把失败用例与期望差异贴全。
5. 通过后提交，提交信息写 `engine: bump to <版本> (contracts <tag>)`。

### 4.3 语料与测试

不能直接搬测试代码：主仓 973 个 `*Test.kt` 依赖 JUnit 4、Light/Heavy fixture、IntelliJ 平台测试框架（`docs/架构设计/01-项目架构总览.md` 技术栈节）。新仓的 WASM 组件测试走 `cargo test` + mock host（00-README 第 15 条）。

可复用的是“数据”而不是“测试”：

| 资产 | 处理 |
|---|---|
| Wrapper 链 → SQL 的输入/期望 | 抽成 `corpus/cases.jsonl`：`{id, dialect, input, expectedSql, notes}` |
| XML 动态 SQL 的输入/期望 | 同上，`kind: "xml"` |
| 方言能力断言 | 抽成 `contracts/dialects.json`，两仓各自写断言读同一份 |
| 性能规则命中 | 抽成 `contracts/perf-rules.json`，规则 id 与阈值进契约 |
| 日志还原样本 | 脱敏后进 `corpus/logs/`；主仓专用字段（PID/时间）不进契约 |

对拍机制：两仓各自实现 runner，读同一份 `cases.jsonl`，输出 `{id, pass/fail, actual}` 的 JSON；共享仓 CI 或新仓 CI 比较两边结果集。这样“引擎行为漂移”会在数据层暴露，不依赖人读代码。

### 4.4 许可与归属

| 项 | 事实 | 动作 |
|---|---|---|
| 主仓许可 | Apache-2.0（`LICENSE` 首行） | 引擎 jar 随包附 `LICENSE` 全文 |
| JSqlParser | `build.gradle.kts:141` 引入 5.3；pom 双许可 LGPL-2.1 / Apache-2.0 | 发行物 `THIRD-PARTY-NOTICES.md` 显式记录“择 Apache-2.0 分支” |
| 新仓许可 | 建议同为 Apache-2.0 | 两仓许可一致，减少分发摩擦 |
| 归属 | 同一作者/组织 | jar 的 `NOTICE` 写“来源仓 + 版本 + 提交” |

### 4.5 视觉与文案资产

| 资产 | 实测 | 可复用性 | 处置 |
|---|---|---|---|
| 图标 SVG/PNG | `src/main/resources` 下 55 个；核心在 `icons/` 与 `icons/toolbar/` | 中。SVG 本身与平台无关，但 OpenIDEA 的 `check` 会对 SVG 做消毒（00-README 第 15 条），且尺寸/配色需按新平台设计系统复核 | 先挑插件主图标与 5~8 个核心动作图标，过 `openidea-plugin check` 与 16px 预览；不整目录复制 |
| i18n 文案 | `messages/MpSqlVisualizerBundle.properties` + `_zh` 两份 | 高。产品术语、错误提示、设置项名称是同一套语言资产 | 抽成共享 `locales/`（键值表），两仓各自打包；先比对键集合，避免把 IntelliJ 专有概念（如“工具窗口”）带过去 |
| 演示动图与截图 | 主仓 `docs/录制/`、`docs/images/` | 低。IntelliJ 的界面截图在新平台不可用 | 不复用成品；复用转制流水线（GIF/MP4/WebP 三件套脚本）与录制方法 |
| 文档站页面 | 独立 `-docs` 仓 | 中。产品介绍、能力对比、FAQ 可复用文案 | 新平台页面另建；文案从共享仓引用，不复制 HTML |

---

## 5. 协同机制

### 5.1 单点维护表

| 事实 | 唯一权威位置 | 消费方 | 同步方式 | 漂移判据 |
|---|---|---|---|---|
| 方言能力矩阵 | `mpsv-shared/contracts/dialects.json` | 主仓 Kotlin、新仓 Rust | submodule 固定 tag | 两仓各写一份枚举/常量即视为违规；CI 校验覆盖 |
| 性能规则 | `mpsv-shared/contracts/perf-rules.json` | 同上 | 同上 | 规则 id 集合不一致即红 |
| 分享报告 schema | `mpsv-shared/contracts/share-schema.json` | 主仓 formatter、新仓 web 视图 | 同上 | schema 校验不过即红 |
| 语料 | `mpsv-shared/corpus/cases.jsonl` | 两仓测试 runner | 同上 | 用例数/通过数不一致即红 |
| 跨仓兼容矩阵 | `mpsv-shared/contracts/compat-matrix.md` | 人 + 发布脚本 | 每次发行手写一行 | 发布 tag 与矩阵行对不上即停 |
| 主仓行为契约 | 主仓 `docs/架构设计/` | 主仓 | 同批更新 | 代码与文档不一致即违规（主仓 S9） |
| 新仓行为契约 | 新仓 `docs/架构设计/` | 新仓 | 同批更新 | 同上 |
| 用户级硬规则 | `~/.zcode/AGENTS.md` | 所有会话 | 人工 | 与共享仓规则重复即删一处 |

原则：同一条事实只允许一个正文位置，其他位置只能是指针（链接 + 版本 tag）。这是主仓已经用过的办法：docs 仓把 ACP/MCP 文档迁回插件仓，理由写的是“两份分居两仓必然漂移”（`mybatis-plus-sql-visualizer-docs/docs/MEMORY.md:23`）。

### 5.2 CI 联动

推荐三层，按成本从低到高：

| 层 | 做法 | 触发 | 成本 |
|---|---|---|---|
| 契约校验（必做） | 两仓 CI 各跑 `contract-check`：读固定 tag 的 `contracts/`，校验本仓常量/schema 覆盖 | 每次 push | 低（秒级） |
| 跨仓对拍（必做） | 新仓 CI 用 `engine/` jar 跑 `corpus`；主仓 CI 用同一份 `cases.jsonl` 跑自己的 runner；两边结果集贴到同一个 artifact | 每次 push / 每晚 | 中（取决于语料规模） |
| 变更广播（可选） | 共享仓打 tag 时 `repository_dispatch` 到两仓的 `contract-sync` workflow | 共享仓 tag | 低 |

关键点：不要靠“改共享规则的人记得通知另一边”。共享仓 CI 在 tag 前跑一次 schema 校验 + 兼容矩阵完整性检查，缺行直接拒绝打 tag。

### 5.3 版本与 CHANGELOG

| 仓 | 版本形态 | CHANGELOG | 与对方的关系 |
|---|---|---|---|
| 主仓 | 日期式 `YYYY.M.D`（`gradle.properties:3` 当前 `2026.11.25`） | `CHANGELOG.md` 的 `[Unreleased]` + 发行段 | 引擎 jar 版本 = 主仓版本；每次影响引擎的发行在兼容矩阵加一行 |
| 新仓 | 建议日期式同构（降低心智成本）或 semver；一旦定下不混用 | 自己的 `CHANGELOG.md` | 记录 `engine=` 与 `contracts=` 两个钉住值 |
| 共享仓 | 语义化 tag（如 `v1.4.0`），只增不改 | `CHANGELOG.md` 只记契约破坏性变更 | 兼容矩阵写明“哪些插件版本可用这个契约 tag” |

不建议两仓共用同一个版本号：发行节奏不同，强绑会让“只改一边文档”也触发另一边版本号变化。

跨仓改动的最小记录格式（写进两仓 CHANGELOG 各一条）：

```text
cross-repo: mpsv-shared@v1.4.0 dialects.json 增 vastbase 能力位；主仓 2026.11.25 / 新仓 0.3.0 起生效
```

### 5.4 Issue 与任务归属

| 场景 | 归属 | 交叉引用 |
|---|---|---|
| 契约变更 | 共享仓开 issue（唯一决策点） | 两仓各开一个实施 issue，正文链接共享仓 issue |
| 主仓引擎缺陷 | 主仓 issue | 新仓若受影响，开 issue 标 `blocked-by: 主仓#N` |
| 新仓平台适配缺陷 | 新仓 issue | 若需主仓改引擎接口，反向链接 |
| 语料新增 | 共享仓 PR | 两仓 CI 自动消费，不各开 issue |
| 发行协同 | 各仓自己的发行 issue | 兼容矩阵行号互相引用 |

任务队列（`.tasks/`）按仓各建一份：`.tasks/` 已被主仓 `.gitignore:108` 忽略，状态是本地会话资产，跨仓共享只会让“谁在做哪件事”失真。

### 5.5 防止“同一份经验两处漂移”

可执行的四个闸门：

1. 写前查重：新增一条规则前，在共享仓 `lessons/portable.md` 与两仓 `LESSONS.md` 各 grep 关键词；命中就改一处、其余留指针。
2. 镜像清单：维护一份 `mpsv-shared/mirrors.txt`，列出“允许在两仓各有一份”的少量文件（如各自的 AGENTS.md），其余一律禁止镜像；CI 用 `sha256` 比对清单内文件是否超出预期差异。
3. 生成物优先：方言表、性能规则、schema 由脚本生成，禁止手写第二份。生成脚本的输入在主仓源码，输出进共享仓；CI 重新生成后 `git diff --exit-code`。
4. 发布前对账：每次发行前跑一次“事实对账”（方言数、规则数、语料数、兼容矩阵行数），四项读数写进发行说明。主仓已有类似防呆口径（方言/MCP 计数），可复用其脚本思路。

已有的漂移证据（说明这不是假想风险）：

| 现象 | 证据 |
|---|---|
| 手工复制的仓库级 AGENTS.md 已经分叉 | `mpsv-sql-insight/AGENTS.md` 32 行，主仓 `AGENTS.md` 98 行；前者没有主仓 2026-11-23 新增的 E1 执行纪律 |
| 手工编号的教训账本已经撞号 | `docs/LESSONS.md` 中 `### L12`、`### L13` 各出现两次（`grep -oE '^### L[0-9]+' \| sort \| uniq -d`） |
| 水位线落后于内容 | 文件头 `reviewed-through: f7e25e5f (2026-10-27)`，但该文件最后一笔提交是 2026-11-25（`git log -1 -- docs/LESSONS.md`） |
| 计数自述与实际不符 | global README 分节写 18/23/68，实际表格 120 行；用户级 AGENTS.md 写 115 个文件，实际 129 个 |

结论：任何“靠人记得同步”的机制都会漂。共享仓 + 生成物 + CI 校验是唯一可持续的组合。

---

## 6. 反模式

| 反模式 | 为什么错 | 证据/替代 |
|---|---|---|
| 把主仓 `docs/` 全量复制到新仓 | 33MB / 1,750 个 md，立刻出现两份会各自演进的“同一事实”；新仓读者无法判断哪份有效 | docs 仓已经踩过：把 ACP/MCP 文档迁回插件仓，理由“两份分居两仓必然漂移”（`mybatis-plus-sql-visualizer-docs/docs/MEMORY.md:23`）。替代：只复制 20~30 条可移植规则，其余链接 |
| 把 IntelliJ 平台经验当通用经验带过去 | 新仓没有 PSI、没有类型系统、没有 EDT/EP；PSI/EDT 经验在新仓是错误指令 | 00-README 第 12、16 条：官方明确“没有 IntelliJ 意义上的 PSI，也没有跨语言语义模型”。替代：新仓经验按平台分档，`psi/` 相关一律标“主仓专用” |
| 用 hardlink 共享可变文件（LESSONS/规范/AGENTS） | 两边编辑同一 inode，git index 会看到“文件被外部修改”，提交内容不可预期；Windows 上 hardlink 还不能跨卷 | 替代：只读资产用 submodule；本机只读视图用 junction，且约定“不在链接目标里编辑” |
| 把主仓项目记忆整目录复制/链接到新仓 hash 目录 | 陈旧路径与提交哈希会误导代理；源索引已超注入上限，复制的是残缺索引；junction 还会让两仓上下文互相污染 | §3.6；注入告警原文（§1.4） |
| 依赖 `~/.zcode/cli/memories/global/` 自动加载 | 它不会被加载，写进去等于没写 | global/README.md:6；打包代码无 `memories/global` 字面量。替代：摘要进用户级 AGENTS.md |
| 在 `F:\code\Java\Gitee\SongRenShuo\` 建一个 git 仓把两仓当子目录 | 子仓的 `.git` 会被父仓当成未跟踪目录或子模块，`git status` 噪音、IDE 索引翻倍、独立发行被父仓边界干扰 | `sub-projects/README.md:1-13` 的约定只适用于“同仓构建子项目”，不适用于独立产品仓 |
| 手工复制一份仓库级 AGENTS.md 给新仓 | 已经在 `mpsv-sql-insight` 上发生分叉（32 行 vs 98 行） | §5.5；替代：新仓 AGENTS.md 只写新平台特有规则，通用规则指向用户级/共享仓 |

---

## 7. 待验证项与验证方法

| 项 | 现状 | 验证方法 | 预期读数 |
|---|---|---|---|
| `additionalDirectories` 的持久化位置 | 不在 `config.json` / `setting.json`；随会话创建传入 | 添加一个新目录后，只读查询 `~/.zcode/cli/db/db.sqlite` 的 `session` / `session_entry`，对比新增行的 JSON；或对比添加前后会话请求体的差异 | 能找到含该路径的字段；找不到则说明仅内存态、每次会话需重加 |
| 桌面“添加文件夹”的具体入口 | 包内有文案“添加文件夹”，未在 UI 实测 | 打开 ZCode，工作区面板/项目名右键/设置里找该文案；截图记录 | 能添加上第二个目录，且重启后仍在 |
| 附加目录的 `AGENTS.md` 不加载 | 源码推断（只解析单一 projectRoot） | 在新仓 AGENTS.md 写一行哨兵（如 `SENTINEL_OPENIDEA_AGENTS`），以主仓为主根 + 新仓为附加目录开会话，让代理原样输出它读到的 AGENTS.md 行 | 哨兵不出现＝推断成立；出现＝需修正本文 §1.5 |
| 附加目录是否共享记忆根 | 源码推断（记忆按 workspacePath 计算） | 同一会话里让代理输出注入的 `MEMORY.md` 路径；应显示主仓 hash 目录 | 显示 `mybatis-plus-sql-visualizer-09474abd1ce62ee6` |
| junction 目录能否被技能发现 | 软链现象存在但证据不唯一 | 建 `~/.zcode/skills/_probe/SKILL.md`，再建一个 junction 指向它；重启 ZCode 看 `Settings → Skills`；测试后删除 | 出现则可用；不出现则只能用实体目录或 submodule |
| 多根项目是否会同时加载两个根的 AGENTS.md | 未实测；模型支持 `project_roots` 多行 | 把两仓设为同一项目的两个根，各写哨兵，开一次会话看两个哨兵是否都出现 | 只出现主根＝本文结论成立 |
| `MEMORY.md` 注入上限是否仍为 200 行/25,000 字符 | 本机版本实测告警与之吻合 | 把索引改到 190 行/24,000 字符 与 210 行/26,000 字符 各开一次会话，看是否出现 WARNING | 前者无告警、后者有告警 |
| 新仓 `cargo test` 的 mock host 覆盖面 | 00-README 第 15 条称 SDK 带 mock host | 在新仓脚手架里跑 `cargo test`，记录可测与不可测的能力面 | 逻辑层可测；平台行为需真机 |

### 7.1 本次调研的关键命令与读数

| 目的 | 命令 | 读数 |
|---|---|---|
| 仓库拓扑 | `git -C <主仓> worktree list` | 13 个 worktree，含 dev/k2j/d256/insight 与 8 个 `.worktrees/t-*` |
| 记忆目录 | `ls ~/.zcode/cli/memories/projects/` | 23 个项目目录；主仓为 `mybatis-plus-sql-visualizer-09474abd1ce62ee6` |
| 记忆上限告警 | 父会话 rollout 检索 `WARNING: MEMORY.md` | `210 lines and 42.8KB`，仅加载 110 行 |
| 全局记忆规模 | `ls ~/.zcode/cli/memories/global/*.md \| wc -l` | 129 |
| 主仓资产 | `find docs -name '*.md' \| wc -l`、`du -sh docs` | 1,750 个 md / 33MB |
| 引擎规模 | `find src/main/kotlin -name '*.kt' \| xargs wc -l` | 613 文件 / 42,581 行 |
| 测试规模 | `find src/test -name '*Test.kt' \| wc -l` | 973 |
| 技能规模 | `find .agents/skills -name SKILL.md \| xargs wc -l` | 8 个 / 2,461 行 / 800KB |
| 多根支持 | `sqlite3 tasks-index.sqlite "select * from project_roots"` | 5 个项目，各 1 个 root |
| 附加目录源码 | `zcode.cjs` 检索 `additionalDirectories` | 会话 schema max 256；技能/代理按 basename 命名空间 |

---

## 8. 建设清单

按顺序执行。每步给出文件/命令与验收方式；“验收”必须留下可复核读数，不接受“看起来对”。

### 第 0 步：拍板三件事

| 决策 | 建议值 | 记录位置 |
|---|---|---|
| 新仓路径与名 | `F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer-openidea` | 本文档 + 共享仓 README |
| 工作区形态 | (a) 主仓工作区 + 新仓附加目录；共享仓作 submodule | 本文档 §2 |
| 共享仓位置 | `F:\code\Java\Gitee\SongRenShuo\mpsv-shared` | 共享仓 README |

验收：三条都写进 `mpsv-shared/README.md` 的“决策记录”节，带日期。

### 第 1 步：验证记忆公式（只读，先做）

```bash
cd <主仓>
node -e "const p=require('path'),c=require('crypto');const a=p.resolve(process.cwd());const k=process.platform==='win32'?a.toLowerCase():a;console.log(p.basename(a).replace(/[^a-z0-9._-]+/g,'-')+'-'+c.createHash('sha256').update(k).digest('hex').slice(0,16))"
```

用 `process.cwd()` 而不是手敲路径，避免显示层对路径段做过替换时算错。

验收：输出 `mybatis-plus-sql-visualizer-09474abd1ce62ee6`，与 `~/.zcode/cli/memories/projects/` 下的目录名一致。不一致说明本机 ZCode 版本改了算法，后续所有“按路径继承”的判断都要重做。

### 第 2 步：主仓记忆索引瘦身

文件：`~/.zcode/cli/memories/projects/mybatis-plus-sql-visualizer-09474abd1ce62ee6/memory/MEMORY.md`。

动作：

1. 按 `docs/规范/记忆精简规范.md:14-33` 把已交付/已解决项移入 `docs/memory-archive/`。
2. 把跨项目通用项移入共享仓 `lessons/portable.md` 或用户级 AGENTS.md 摘要。
3. 把双仓在途项放到索引最前 60 行。

验收：

```bash
python - <<'EOF'
p=r"C:/Users/USER/.zcode/cli/memories/projects/mybatis-plus-sql-visualizer-09474abd1ce62ee6/memory/MEMORY.md"
t=open(p,encoding='utf-8').read()
print("lines", len(t.splitlines()), "chars", len(t), "utf8-bytes", len(t.encode()))
EOF
```

读数须 ≤ 200 行且 ≤ 25,000 字符（JS 字符串长度口径，`len(t)` 近似）；再开一次会话，注入文本里不得出现 `WARNING: MEMORY.md`。

### 第 3 步：用户级 AGENTS.md 增补“跨仓协作”

文件：`C:\Users\USER\.zcode\AGENTS.md`。
动作：加入 §3.2 的 5 条规则；删除会变化的计数（现有“115 个内容文件”）。
验收：新开任意工作区，让代理原样输出 `~/.zcode/AGENTS.md` 中“跨仓协作”一节的标题与第一条；文件总大小 ≤ 40KB。

### 第 4 步：建共享仓骨架

目录：`F:\code\Java\Gitee\SongRenShuo\mpsv-shared`。
文件：

```text
README.md
lessons/portable.md
standards/README.md
contracts/dialects.json
contracts/perf-rules.json
contracts/share-schema.json
contracts/compat-matrix.md
corpus/cases.jsonl
LICENSE
NOTICE
```

动作：`git init`；首个 commit 只放骨架与“决策记录”。
验收：`git log --oneline` 一笔；`jq empty contracts/*.json` 全部通过；README 的“决策记录”包含第 0 步三行。

### 第 5 步：两仓挂载共享仓

主仓与新仓各执行：

```bash
git submodule add <共享仓远端或本地路径> shared
git commit -F <消息文件>
```

验收：`git submodule status` 显示固定 commit；两仓的 `shared/` 指向同一 commit；CI 配置里 `submodules: recursive` 已加。

若用户不接受 submodule，改走“固定 tag + CI 下载 + sha256”：

```bash
curl -L <release>/mpsv-shared-<tag>.zip -o /tmp/shared.zip
sha256sum /tmp/shared.zip   # 与 manifest 比对
```

验收：manifest 的 sha256 与下载物一致。

### 第 6 步：引擎产物化

主仓新增 `sub-projects/engine-jar/build.gradle.kts`，源集指向 `../../src/main/kotlin`，按 03 分册 §9.1 的“原样”列圈定包（先只收 `model`、`sql`（含子包）、`xml`、`xml/ibatis`、`reverse`、`generator`、`psource` 等纯包），排除 `ui/`、`execute/`、`actions/`；根 `settings.gradle.kts` 加：

```kotlin
include(":engine-jar")
project(":engine-jar").projectDir = file("sub-projects/engine-jar")
```

命令与验收：

```bash
./gradlew :engine-jar:jar
sha256sum build/libs/mpsv-engine-*.jar
```

读数要求：jar 内 `jar tf` 能列出 `cn/song/mybatisplus/visualizer/{model,sql,xml,reverse,generator,psource,util}/` 等包；不包含 `ui/`、`execute/`、`actions/`；jar 大小与 26,168 行的量级相称。

### 第 7 步：新仓脚手架与钉版本

命令（按 07 分册 §A.2，工具版本必须 0.1.51）：

```bash
openidea-plugin new mybatis-plus-sql-visualizer-openidea
```

新仓根新增：

```text
rust-toolchain.toml        # 11 分册 §5.2：channel=1.100.0, targets=wasm32-wasip2
AGENTS.md                  # 30~40 行，只写新平台特有规则 + 指针
.agents/skills/            # 新平台技能（先空）
engine/                    # 第 6 步产物
.github/workflows/ci.yml
```

验收：`cargo build --release --target wasm32-wasip2` 产出组件；`openidea-plugin check` 通过（00-README 第 15 条）。

### 第 8 步：新仓 AGENTS.md 与 LESSONS

文件：`mybatis-plus-sql-visualizer-openidea/AGENTS.md`。
内容只写：

1. 本仓技术栈与平台边界（WASM/WASI、无 PSI、provider 预算）。
2. 引用共享仓与主仓调研报告的路径（绝对路径或 URL）。
3. 本仓的验证纪律（`cargo test` + `openidea-plugin check` + 真机冒烟）。
4. 明确“IntelliJ 平台经验不适用”。

验收：文件 ≤ 60 行；`grep -c "PSI"` 只出现在“不适用”上下文；不复制主仓 LESSONS 正文。

### 第 9 步：语料与契约抽取

从主仓测试中抽 20~50 条代表性用例，写入 `mpsv-shared/corpus/cases.jsonl`；从 `Dialect.kt` 与 `sql/perf/rules/` 抽 `dialects.json` / `perf-rules.json`。

验收：

```bash
wc -l mpsv-shared/corpus/cases.jsonl     # 记录基数
jq '.dialects | length' mpsv-shared/contracts/dialects.json
```

两仓各自的测试 runner 读取同一文件；主仓侧先加一个只读消费测试（不改变现有测试行为），新仓侧加 `corpus` 测试。

### 第 10 步：CI 联动

主仓 `.github/workflows/push-test.yml` 增加 `contract-check` job（读 `shared/contracts` 校验常量覆盖）。
新仓 `.github/workflows/ci.yml` 增加：

1. `cargo fmt --check && cargo clippy -- -D warnings`
2. `cargo test`
3. `cargo build --release --target wasm32-wasip2`
4. `openidea-plugin check`
5. `corpus` 对拍

共享仓 tag 时 `repository_dispatch` 触发两仓 `contract-sync`。

验收：在共享仓改一行 `dialects.json` 并打 tag，两仓 CI 都出现 `contract-sync` 运行记录；不改任何一仓代码，`contract-check` 因覆盖不一致变红（反向验证）。

### 第 11 步：技能提升

```bash
cp -r <主仓>/.agents/skills/security-audit ~/.agents/skills/security-audit
cp -r <主仓>/.agents/skills/task-queue     ~/.agents/skills/task-queue
```

`db-env-provisioning` 与 `srv-backup-restore` 二选一：要么提升到用户级，要么在共享仓放一份“服务端事实源”并由两仓技能引用；不要两处都写正文。

验收：在与主仓无关的工作区开会话，`Settings → Skills` 可见；主仓内同名时用户级胜出、工作区副本被遮蔽（同一技能不会两份同时进入上下文）；`release-workflow`、`ide-*`、`k2j-*` 不在用户级列表。

### 第 12 步：首次双仓联调（验收日）

在一个会话里完成三件事，作为机制成立的证据：

1. 以主仓为工作区，添加新仓为附加目录；让代理输出注入的 `MEMORY.md` 路径与技能命名空间，确认是主仓 hash 与 `mybatis-plus-sql-visualizer-openidea/...`。
2. 同时改一处共享契约（共享仓）、一处主仓消费点、一处新仓消费点；三次提交各自独立，提交信息都带 `cross-repo: mpsv-shared@<tag>`。
3. 两仓 CI 全绿；把三次提交哈希与 CI run 号写进共享仓 `contracts/compat-matrix.md`。

验收读数：`git -C <仓> log -1 --format=%H` 三处；两仓 CI run URL；兼容矩阵新增一行。任何一项缺失，机制视为未验收。

### 第 13 步：把“不要做”写进两仓 AGENTS.md

在两仓 AGENTS.md 各加 5 行“反模式”指针，指向本文 §6。目的不是重复正文，而是让后续代理在改代码前看到“全量复制 docs / 复制记忆目录 / hardlink 共享可变文件”是明确禁止项。

验收：`grep -n "反模式"` 在两仓 AGENTS.md 各命中一次；两处都链接到本文，不复制条目正文。
