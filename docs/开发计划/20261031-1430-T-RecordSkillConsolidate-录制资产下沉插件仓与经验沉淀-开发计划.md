# T-RecordSkillConsolidate：录制/验收资产下沉插件仓 + 本轮经验沉淀

> 2026-10-31 立项。触发：R15 演示动图补录（六张）完成后用户要求「深度总结沉淀 + 记忆 + 项目录制文档
> + skill 全部更新，并把录制/验收两个 skill 从全局移到插件仓，之后录制都从插件仓开始」。
> 本文只出计划，实施另起批次。

## 一、目标与范围

三件事，按依赖顺序：

1. **资产下沉**：`ide-demo-gif`（录制）、`ide-ui-acceptance`（验收）两个 skill 从
   `~/.zcode/skills/` 移到 `<插件仓>/.zcode/skills/`；文档站里可复用的录制产物（指南文档、
   校验脚本）一并迁入插件仓。
2. **经验沉淀**：本轮六张片子的实测经验写成插件仓 `docs/经验总结/` 一篇，并把可通用部分
   并进 skill 坑表。
3. **记忆更新**：项目记忆里与录制相关的条目改写成「插件仓起点」口径，新增本轮结论条目。

**不在范围**：改动任何 GIF 素材、改动站点页面文案、动 demo 工程代码。

## 二、现状盘点（2026-10-31 实测）

### 2.1 两个 skill（全局）

| skill | 位置 | 体量 | 内容 |
|---|---|---|---|
| `ide-demo-gif` | `~/.zcode/skills/ide-demo-gif/` | `SKILL.md` 361 行 + `scripts/` 8 个 py | 管线/构图/定位三件套/坑表 78 条/环境还原 |
| `ide-ui-acceptance` | `~/.zcode/skills/ide-ui-acceptance/` | `SKILL.md` 113 行，无脚本 | runIde 沙盒 + robot/图像锚点/像素带 + 证据分级 + 批次验收 |

`scripts/`：`assemble.py` `black_underlay.py` `clipread.py` `gifkit.py` `locate.py`
`scene_template.py` `shot.py` `verify.py`（**权威源**）。

### 2.2 插件仓 `record/`（工作位置，已入库）

- 170 个 tracked 文件、其中 158 个 `.py`；`scene_*.py` 约 90 个（a/r/t/n/s/m 五批 + 本轮 n01~n05）
- 与 skill 同名的 8 个基建**已在 `record/` 有副本**（gifkit/assemble/locate/verify/shot/
  scene_template/black_underlay/clipread）→ 双份维护，正是要收口的地方
- 项目特有件：`robot.py` `anchor.py` `mcpnav.py` `preflight.py` `prep_env.py` `BASELINE.md`
  `lib/nav.py` `_run_elev.bat` `sandbox.vmoptions` `templates/`
- 一次性探针 `probe_*.py` 约 30 个、`open_sqlvis*.py` `patch_openfile_mcp.py` 等历史件仍混在顶层
- `.gitignore` 已排除 `record/perf|shots|robot-plugins|templates|tmp`

### 2.3 插件仓 `.zcode/`（已存在，但无 skills）

现有 `inc/ plans/ probe/ triage.json triage.py workflow-drafts/ workflow-runs/`。
ZCode 工作区 skill 约定路径是 `<repo>/.zcode/skills/<name>/SKILL.md`（与 `~/.zcode/skills/` 同构）。

### 2.4 文档站可迁移产物

| 文件 | 体量 | 处置 |
|---|---|---|
| `docs/演示GIF录制指南-说明文档.md` | 189 行 | 迁入插件仓 `docs/录制/`，站点留指针页 |
| `scripts/verify-crudsql-gif.mjs` | 133 行 | 迁入插件仓 `record/tools/`（GIF 产物校验） |

`docs/验收文档/*.md` 是历史记录，不迁移（保留在站点仓作交付凭证）。

## 三、目标结构

```
mybatis-plus-sql-visualizer/
├── .zcode/skills/
│   ├── ide-demo-gif/
│   │   ├── SKILL.md            # 路径全部改插件仓视角
│   │   └── scripts/            # 8 个基建（权威源）
│   └── ide-ui-acceptance/
│       └── SKILL.md
├── record/                     # 场景脚本 + 项目特有件（保持不变）
│   ├── scene_*.py
│   ├── lib/  templates/  perf/  shots/  tmp/
│   ├── BASELINE.md  preflight.py  prep_env.py  robot.py  anchor.py  mcpnav.py
│   └── tools/verify_gif.mjs    # 迁自文档站
└── docs/
    ├── 录制/演示GIF录制指南-说明文档.md   # 迁自文档站
    ├── 经验总结/20261031-XXXX-*.md       # 本轮新增
    └── 开发计划/（本文件）
```

## 四、任务分解

### T1 skill 迁移与路径改写

1. `mkdir -p <插件仓>/.zcode/skills`，整体复制两个 skill 目录（含 `scripts/`）。
2. 改写 `ide-demo-gif/SKILL.md` 内所有全局路径与「本项目」表述：
   - `~/.zcode/skills/ide-demo-gif/scripts/` → `.zcode/skills/ide-demo-gif/scripts/`（相对插件仓根）
   - 「本项目 record/」保留，但显式写明 **cwd = 插件仓根**，场景脚本 `record/scene_*.py`
   - demo 工程绝对路径 `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-demo`
   - 沙盒启动命令加一句「在插件仓根执行」
3. `ide-ui-acceptance/SKILL.md` 同步（runIde 命令、record/ 锚点路径）。
4. **删除全局两份**（同名时用户级优先，留着会让工作区副本永远不生效）。
5. 验证：新会话在插件仓内用自然语言触发，确认 `Skill` 解析到 `.zcode/skills/...` 路径。

### T2 文档站指南迁移

1. `docs/演示GIF录制指南-说明文档.md` → `<插件仓>/docs/录制/演示GIF录制指南-说明文档.md`，
   正文里「插件仓库 `record/`」「skill 在 `~/.zcode/skills/`」等表述一并改。
2. 站点侧原文件改为短指针（保留 URL 不 404）：一句话 + 指向插件仓路径；`search: false` 保留。
3. 检查站点内其他文档对该指南的引用（`grep 录制指南`），改成指插件仓或不指。

### T3 站点脚本迁移

`scripts/verify-crudsql-gif.mjs` → `<插件仓>/record/tools/verify_gif.mjs`；同步改插件仓
`package.json`（若无则建一个最小 scripts 入口）与 skill 内的引用。

### T4 本轮经验沉淀（核心）

新增 `<插件仓>/docs/经验总结/20261031-XXXX-第十五轮演示动图补录与录制链根因-经验总结.md`，
骨架＝§五 的 19 条，按「现象 → 根因 → 处置 → 判据」四段写，每条给实测读数。

### T5 skill 坑表更新

把 §五 中**可通用**的条目并入 `ide-demo-gif/SKILL.md` 坑表（编号续 79 起）：
沙盒外部工程配置、构建静默死亡判据、Database 控制台不进捕捉面、runner 方言过滤、
ANSI_QUOTES 与建源参数校验、模态对话框吞键导致误伤源文件、全屏 topmost 抢前台、
工具窗宽度影响 chip 可见、combo 同值不触发与键盘路径、栈弹层是悬停不是点击、
poster 选帧与光标避让。项目特有坐标进 `record/BASELINE.md`。

### T6 记忆更新

| 动作 | 目标 |
|---|---|
| 新增 | 本轮「录制链根因与六张交付」条目（plugin 仓起点口径） |
| 新增 | 「skill 已下沉插件仓」条目（含全局已删、新路径、触发方式） |
| 更新 | `sandbox-launch-canonical-command`、`gif-recording-*`、`sandbox-maven-import-*` 等旧条目里「从文档站开始」的表述 |
| 更新 | `MEMORY.md` 索引行（一行一条，控制在 200 字符内） |

### T7 收口验证

1. 插件仓：`.zcode/skills/**` 入库、`record/tools/` 入库、`docs/录制/` 入库、经验总结入库。
2. 全局 `~/.zcode/skills/` 两份确认消失（`ls` 为空）。
3. 文档站：`npm run build` 退出码 0；指针页可访问；`grep` 无失效引用。
4. 冷启动复验：新会话（插件仓为 cwd）问「录一张演示 GIF」，确认命中的是插件仓 skill 且
   scripts 路径可达。

## 五、本轮经验清单（19 条，经验总结与 skill 坑表的素材）

### A. 沙盒构建与启动

1. **沙盒 IDE 构建静默死亡**：症状＝`build.log` 停在 `Pre-loaded process ready`（无编译行、
   无错误行）；运行配置报 `Process not started by some reasons. Probably build process failed`；
   MCP `build_project` 180s/300s 都 `timedOut:true problems:[]`。
2. **真源是沙盒外部工程配置**：`<sandbox>/system/projects/<proj>.<hash>/external_build_system/
   modules/<module>.xml` 的 `LANGUAGE_LEVEL="JDK_17"`（由该模块 pom `maven.compiler.release=17`
   导入）与项目 SDK 8 冲突。**改 demo `.idea/compiler.xml` 对沙盒完全无效**（两套配置）。
   改成 `JDK_1_8` 后构建通过。
3. **判据链**：修完必须验三步——构建面板出 `Finished`/`Build Output` 有编译行 →
   `execute_run_configuration` 返回 `fullOutputPath` → `curl :8081/user/list` 200。

### B. 捕捉面边界

4. **Database 控制台（DbConsole）的 SQL 不进捕捉面**：诊断行恒「已挂载 0 个」。要 DDL/DML
   素材必须让**应用自己执行**（定时任务 / 接口 / 启动期脚本）。
5. **捕捉开启时机**：应用已在跑时开捕捉能挂上（实测），但历史条目不回扫，需重新触发动作。

### C. 平台 runner 枚举

6. **runner 按 exec 文件方言过滤**：有匹配方言的数据源时只给它（`runners=1` → 静默单跑、
   选择器永不弹）；无匹配源时回退全部源（`runners=6` → 弹选择器）。做「方言不一致」演示
   必须让**渲染方言没有对应数据源**（临时移出 `.idea/dataSources.xml` 的 PG 源）。
7. **`FROM "user"` 要跑通的两条件**：目标库有全字段（`mp_demo` 而非只有五列的 `mpj_demo`）
   + 数据源 URL 带 `sessionVariables=sql_mode=ANSI_QUOTES`。`create_database_connection` 拒
   带该参数的 URL → 先纯 URL 建源、再改 `dataSources.xml` 补参数。
8. **放行态信息行会被真实报错覆盖**（`showStatus` 下一拍 EDT，错误随后覆盖同一 infoLabel）
   → 想让「方言不一致」提示停在画面里，执行必须成功。

### D. UI 交互实测

9. **调用栈弹层是悬停触发**：预览面板 meta 行「调用栈:」悬停 400ms（`StackFrameHover`），
   **不是**日志列表 chip 的点击（chip 点了只选中条目）。
10. **预设 combo 三坑**：选**同值项不触发回填**（必须经过另一项再切回）；首击可能只给焦点
    不开弹层（需重试）；JBPopup 项鼠标点易落空 → 键盘 `Up/Down + Enter` + 回填像素断言兜底。
11. **工具窗宽度决定 chip 可见性**：窄窗下 `[SchedulerDemoTask.java:40]` 被截断就无法点，
    拉宽后坐标全变——录制前先定宽，再标坐标。
12. **列表内嵌提示条点不掉**：「快速预览」说明条对合成点击免疫，只能容忍入镜或先关引导。
13. **模态对话框吞键 → 误伤源文件（本轮两次）**：预览对话框开着时 `Ctrl+A`/键入打到对话框；
    对话框关掉后同样的键打到编辑器。两次把 `WrapperExtServiceImpl.java` 清空/插入垃圾。
    处置：任何「全选+替换」前先断言目标窗口存在性与焦点；误伤恢复顺序＝**先关页签 → git
    checkout → 重开**；**IDE 退出时会把脏文档写回盘**，所以 IDE 退出后要再 checkout 一次。

### E. 产物与验收

14. **poster 默认取 kept frame #N，不一定是关键定格** → 按内容选帧；**光标 overlay 要避开
    关键文字**（R1 第一版光标压在「方言不一致」信息行上，重录）。
15. **接缝 diff 口径**：结构性变化（首帧纯预览 / 末帧带提示）允许 >1 并登记（本轮 6.06）；
    同态场景应 <1（N1 0.03 / N2 0.33 / N4 0.18）。
16. **抽帧核对用 ffmpeg `select=eq(n,N)` + PIL crop/zoom**，比整图目视快且能定位到行。

### F. 前台

17. **全屏 topmost 应用在前台时，点 IDE 区域会打到它**（本轮 magpie）。合成输入前必须
    `GetForegroundWindow == IDE hwnd` 断言；不一致先 Alt 按放 + `SetForegroundWindow` 再断言。

### G. 插件缺陷（已修/待查）

18. **`ParamBraceTypedHandler.inStatementText` 只看一层 parent** → `<where><if>` 体内敲 `#`
    不接管（真实 mapper 主流排版全灭）。已修 `e69c6e5d`（T-XmlBraceNested），22/22 绿。
19. **`#{…,` 结构属性键补全不出**，伴随平台 `AsyncCompletion` SEVERE
    （`MpSqlMapper:UserMapper.xml != MpSqlMapper:UserMapper.xml`，注入身份断言）→ 待查，
    站点文案「`#{}` 补 jdbcType/javaType/typeHandler」需加限定或回落。

## 六、待验证前提（实施者先证伪）

1. **工作区 skill 生效**：ZCode 工作区 skill 路径是 `<repo>/.zcode/skills/`（配置指南 §Skills
   一行），但本仓从未放过 skill——首个 skill 落地后必须用新会话实测命中，不能只看文件存在。
2. **删除全局副本不会影响其它仓**：用户已定「之后录制都从插件仓开始」，其它仓不再需要该 skill；
   若日后有别的仓要录，走「复制回全局」而非双份维护。
3. **文档站指针页不影响构建**：`--ignoreDeadLinks` 开着，指针页只要不写死链即可；
   实测 `npm run build` 退出码 0 再收。
4. **`record/` 里的 8 个基建副本**：与 skill `scripts/` 是「权威源 + 工作副本」关系，
   迁移后要定一条同步纪律（改 skill 权威源 → 同步到 `record/`），否则又回到双份漂移。

## 七、风险

- **路径改写遗漏**：skill 里路径散落在命令块、坑表、验收节三处，漏改会在下次录制时才炸。
  缓解：改完 `grep -n "~/.zcode\|mybatis-plus-sql-visualizer-docs" SKILL.md` 必须零命中。
- **记忆索引超限**：`MEMORY.md` 已 26.5KB 超 24.4KB 上限（部分行未加载）。本次新增条目必须
  同时精简旧条目，否则索引继续恶化。
- **迁移期间录制不可用**：全局删了、工作区没生效时会「skill 找不到」。缓解：先复制+改写+实测
  通过，最后一步才删全局。

## 八、验收标准

1. 插件仓 `.zcode/skills/ide-demo-gif/`、`.zcode/skills/ide-ui-acceptance/` 在位且 SKILL.md
   内零全局路径残留。
2. 全局 `~/.zcode/skills/` 下两份已删除。
3. 新会话（cwd＝插件仓）触发录制 skill 成功，脚本路径可解析。
4. `docs/录制/演示GIF录制指南-说明文档.md` 在位；站点原路径仍可访问（指针页）。
5. `record/tools/verify_gif.mjs` 在位且能跑（对现有六张 GIF 出校验读数）。
6. 经验总结文档含 §五 全部 19 条（现象/根因/处置/判据四段齐全）。
7. 记忆：新增 2 条、更新 ≥3 条、`MEMORY.md` 索引不超限。
8. 站点 `npm run build` 退出码 0。

## 实施记录

2026-10-31 当天实施完成。

**T1 skill 迁移**：两个 skill 复制到 `.zcode/skills/`，录制 skill 改了三处结构性内容——
①开头新增「工作目录＝插件仓根」与三层工具表（skill scripts＝权威源 / `record/` 场景与项目件 /
`record/` 内未通用化的 anchor·robot·mcpnav）；②`python scripts/xxx.py` 全部改成
`python .zcode/skills/ide-demo-gif/scripts/xxx.py`；③「权威源约定」整节重写（含「全局已删、
同名时用户级优先」的原因）。验收 skill 加位置块 + 两条本轮新发现（Database 控制台不进捕捉面、
构建静默死亡先查外部工程配置）。全局两份已删。

**T5 坑表**：录制 skill 坑表续 **79~89** 共 11 条（构建静默死亡 / Database 控制台 / runner 方言
过滤 / ANSI_QUOTES 与建源校验 / 信息行被报错覆盖 / 模态对话框吞键误伤源文件 / 全屏 topmost 抢
前台 / 工具窗宽度决定 chip 可见 / 栈弹层是悬停 / combo 三坑 / poster 选帧与接缝口径）。

**T2 指南迁移**：`docs/录制/演示GIF录制指南-说明文档.md`（187 行）——去掉站点 frontmatter、
头部改插件仓视角、`~/.zcode/skills/` 改 `.zcode/skills/`、补 `record/tools/verify_gif.mjs` 用法。
文档站原文件改成路径占位页，`docs/MEMORY.md` 两处引用同步改指向。

**T3 校验工具（与原计划有偏差，已改设计）**：原计划是把文档站 `scripts/verify-crudsql-gif.mjs`
整份迁入；实读后发现它是**站点页面级**校验（puppeteer + GifPlayer 组件 + 亮暗主题 + 中英页），
依赖 `puppeteer-core`（插件仓无 node_modules），且与「录制素材规格」不是一回事。故改为：
站点那份留在文档站（不迁）；插件仓新写**无依赖**的 `record/tools/verify_gif.mjs`（只用 ffprobe，
校验尺寸/帧数/帧率/时长/体积/poster 六项）+ `gif_spec_allow.json` 例外清单（6 条历史口径差异，
每条带 why）。实测全站 69 张 **69/69 通过**。

**T4 经验总结**：`docs/经验总结/20261031-1430-第十五轮演示动图补录与录制链根因-经验总结.md`
（八节，含量化归因表、构建/捕捉面/runner/UI 交互/产物/插件缺陷六组、下轮动作）。

**T6 记忆**：新增 2 条（`r15-recording-chain-root-causes`、`record-skill-lives-in-plugin-repo`），
5 个相关旧条目顶部加迁移口径注，`MEMORY.md` 索引从 **45.7KB 瘦到 23.9KB**（78 条描述截断到
≤118 字符，并把此前因超限被截掉的 8 个条目补回）——现 107 个记忆文件全部入索引、零孤立、零悬空。

**T7 收口**：`.zcode/` 原本整目录被 gitignore，改为 `.zcode/*` + `!.zcode/skills/`（其余 `.zcode/inc|plans|probe` 仍忽略），skill 得以入库；skill frontmatter 三个字段齐全；8 个脚本 `import` 全通。

**待用户侧确认的一项**（计划 §六.1）：工作区 skill 的实际命中要**新会话**才验证得到——本会话的
skill 是启动时从全局加载的，路径改写后需重开会话用自然语言触发一次（「录一张演示 GIF」）确认
解析到 `.zcode/skills/ide-demo-gif/`。
