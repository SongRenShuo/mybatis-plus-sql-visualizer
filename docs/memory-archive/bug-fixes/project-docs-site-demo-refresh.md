---
name: project-docs-site-demo-refresh
description: 文档站演示动图重录与内容同步（T-DocsRefresh1023，20261021~22）——17 张动图重录/新增 + 15 项正文完成；后续轮次已补完原「待补录 9 项」并全部上线（2026-11-02 核实闭环）；含录制根因与踩坑清单
metadata:
  node_type: memory
  type: project
  originSessionId: session_20261021_docs_demo_refresh
---

# 文档站演示动图重录与内容同步（T-DocsRefresh1023）

独立仓 `mybatis-plus-sql-visualizer-docs`（VitePress，master 直接部署，`npm run deploy`）。计划 `docs/开发计划/20261021-2043-文档站演示动图重录与内容同步-开发计划.md`，验收 `docs/20261021-T-DocsRefresh1023-演示图重录与内容同步-验收文档.md`。20261021~22 一轮，**已 push 未部署**（用户明令需逐图验收后才部署）。

## 已完成（都在 master，tip `d07fec6`）

- **10 张重录**（覆盖同名文件）：crud-sql / wrapper-preview(1440 宽) / annotation-preview / xml-preview / jpa-preview / batch-operation / code-completion / sql-to-java / xml-preview-rollback / sql-log。均 1280 宽 15fps，像素采样复核**零试用横幅**，接缝 0.04~7.88。
- **7 张新增**：preview-copy-and-close / fieldfill-simulate / replay-script-export / ai-context-copy / diagnostic-report / history-jump-last / log-community。
- **15 项正文**（D01~D15）：wrapper-preview 加 DML 影响面预演、跨会话计划历史、更多菜单九项、方言闸门、执行 SQL 补返回预览；batch-operation 加 FieldFill；sql-log 加展示脱敏与交流群、历史补直达末条与检索开关；xml-preview 补回滚快照双态；configuration 补 5 行设置项与三个清理入口；faq 补三条；roadmap 三分组补 14 项并按版本重写底注；shortcuts 补 7 项入口。
- 同批还改了**增强捕获授权口径**（6 组文件中英）：站点原写「24 小时冷却/重新授权/三态确认弹窗」，早已被 T-EnhancedCaptureDefaultOn 移除——详见 [[project-enhanced-capture-default-on]]。
- 校验口径：`check-en-parity` 一致；`npm run build` 退出码 0；`public/demo/` 引用扫描**零缺失**（本轮 139 处）。**改素材后必须重跑引用扫描**，缺素材的 `<GifPlayer>` 会破坏构建。

## 原「待补录 9 项」已全部闭环（2026-11-02 实测销账）

上一版记忆写「9 项因数据源绑定与设置页交互受阻留待补录」——**该状态已过期**。实际由后续几轮陆续补完，只是没人回头销账：

| 项 | 提交 | 时间 | 备注 |
|---|---|---|---|
| `dml-impact.gif` | `84afbea` | 10-27 | 批4 MINOR 优化首批，825KB |
| `explain-history-compare.gif` | `c1750ca` | 10-28 | 第三轮补录，1.06MB |
| `rollback-snapshot-dual.gif` | `802ae93` | 10-28 | 按正规绑定路径重录（真实 mp_demo 1 行×13 列） |
| `dialect-gate.gif` | `96ca427` | 10-31 | 按**放行形态**重录（旧片是阻断形态，与正文口径相反），770KB |
| `display-mask.gif` | `84f76d3` | 11-01 | 调色板重压，1.49MB |
| `mcp-agent.gif` | `84f76d3` | 11-01 | 1.60MB |
| S01/S03/S04 设置页截图 | `251c02d` | 10-23 | 三张重拍补齐新增设置行；S02 计划原文即「无需重拍」 |

**闭环判据（本次核实用法，可复用）**：① 素材在仓 `public/demo/` 且 `git log -- <path>` 有提交；② 中英两侧正文都有 `<GifPlayer src=...>`（只查一侧会漏，zh 在 `features/`、en 在 `en/features/`）；③ **生产产物**抓页面 grep `demo/xxx` 命中且素材 URL 200——三项齐了才算上线，缺一项都只能算「文件在仓库里」。

## 原始根因（录制资产再扩展时仍有参考价值）

1. **数据源密码未持久化**：沙盒里 `mpj_demo@localhost` / `mp_demo@localhost` 首连报 `Access denied for user 'root'@'localhost' (using password: NO)`，右键→Change Credentials 补 `root` 后 `mpj_demo` 连上（树显 `1 of 14`）。`dataSources.local.xml` 里有 `dbms="MYSQL"` 完整连接信息，但 secret 是 master_key 存储、沙盒读不到。
2. **预览绑错库导致 `[1064]`**：预览的「活控制台」绑定优先取当前有控制台的数据源——`mp_demo` 有活控制台就被绑了它，而 **`mp_demo` 的 JDBC URL 没带 `sessionVariables=sql_mode=ANSI_QUOTES`**，MPJ 渲染的双引号表名 `"user"` 在该库非法。`mpj_demo` 的 URL 带该参数（见 `demo/.idea/dataSources.xml`）。**正解=让预览绑到 mpj_demo**：先在 mpj_demo 上 `F4` 开控制台（成为活控制台）再开预览。
3. **数据源下拉是 JBPopup，合成点击不可靠**（与 [[pitfall-jbpopup-popup-interaction-debt]] 同源）——需先 hover 再 click，或干脆绕开。
4. **设置页树滚动与内容区过滤在本沙盒不可靠**：滚轮/PageDown/拖树滚动条全无效；左侧搜索框能定位到 `SQL Visualizer` 根节点但内容区过滤框输入「mask」只出补全候选 + `Nothing to show`。设置页截图与 `display-mask` 因此取不到。

## 录制踩坑（本轮新实证，已写进场景脚本注释）

- **gutter 图标位置随滚动漂移** → 同一坐标时而命中时而落空；且条带里混着**编辑器折叠箭头（x≈117/125）不是插件图标**，过滤要按 `95 ≤ x ≤ 105`（JPA 文件是 110）。**最稳的是「先点编辑器语句行内，再按 `Alt+Shift+Q`」**（光标在语句内直接开预览，不进候选框）；XML 文件里则弹「选择要预览的 SQL」候选框，按 id 选目标语句比像素定位确定。
- **`Escape` 会连整个预览窗一起关掉**，不能用来「只关菜单」——关残留菜单改成点预览标题栏空白处。
- **JBPopup 菜单项必须先 hover 再 click**：同一坐标有时成、有时只把菜单关掉。已固化成 `hover_click()` 辅助（`moveTo` → sleep 0.5 → click）。
- **`gifkit.click()` 收的是屏幕绝对坐标**，传 region 相对值会点到别处（首轮 R01 整条白录）。脚本里统一 `abs_pt(p) = (p.x+277, p.y+25)`。
- **批量参数面板左栏 fill 字段要拖滚动条才能看到**（滚轮/PageDown 无效），模拟值行在 `create_time [fill=INSERT]` 下方。
- **robot 组件树看不见预览弹窗内控件**（弹窗是独立窗口），也看不见 JBPopup 菜单项与 JTree 节点——这些只能像素定位。
- **`sql-to-java` 用 `user` 表会弹「选择要转换的 SQL」多候选框挡住画面**（demo 多模块都有 User 实体），换 `t_order`（demo 唯一）即直出。
- **接缝高（>10）多半是首帧或末帧不是编辑器态**：要么残留预览窗没关（写进 prep），要么收尾 `goto_line` 把弹窗录进去了。接缝 0.04~0.7 才算干净。

## 报告口径

用户要求「实施计划所有内容」——本轮 17/26 项完成、9 项因环境受阻，**必须如实分列已完成与待补，不能把「我录不出来」写成「已交付」**（见 [[pitfall-verification-answer-must-state-evidence-limit]]）。待补项的正文写好、图引用移除是稳妥折中：下轮补上素材时只需把 `<GifPlayer>` 行加回，正文不用动。

关联 [[project-doc-conventions]]（验收文档骨架）、[[runide-demo-verify-workflow]]（沙盒与录制环境）、[[project-toolbar-icon-semantics]]（工具栏 SVG 是本轮重录要体现的变化之一）。
