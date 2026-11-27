# PluginDescSlimAndQrValign — 插件页文案精简 + 二维码垂直错位 — 开发计划

- 立项时间：2026-11-27 13:39
- 触发：用户 2026-11-27 报两件事——①插件页文字内容太多，应适当精简；②两张二维码仍未对齐，
  「上次修复了一版没生效」。附 IDE 插件页两张截图（概览 tab 上/下两屏）。
- 归属：`master`（描述主体在 `plugin.xml`），同步面 `dev` / `feature/kotlin-to-java`
- 编号声明：本册占 **D263**。主控实测：`D263` 在 `docs/` 下仅出现 2 次，均为
  `20261127-1007-T-GiantFileBudgetDevRed` 册的「下一全新 D263」指针，无正文条目占用。
  `V250` / `O-167` 同样只有指针命中（各 2 次）。故下一全新 **D264 / V251 / O-168**。
- 性质：**UI 文案 + 描述结构改造**。本文前八节是立项时的实测读数与方案，§九是实施记录。
- 状态：**已实施**（2026-11-27 14:03）。用户确认「按推荐的来实施」，文案按 10→7 条落地、
  二维码按并排形态落地。master `a7d8063bb`（+ `1f6c77a38` 回填 CHANGELOG 哈希）、
  k2j `a0547f67b`；dev 两文件已改，待收口阶段跑测试后提交。`docText` 精简文案已交付用户后台手改。

---

## 一、现象与证据

### 1.1 文案体量：两屏内容，来源是三份数据

用户看到的正文来自三处，来源不同，处置方式也不同：

| 区块 | 用户所见 | 数据源 | 仓库可改？ |
|---|---|---|---|
| 中文 10 条 bullet + English 10 条 + 触发/要求行 | 截图 1 全屏 | `src/main/resources/META-INF/plugin.xml` 的 `<description>` | ✅ |
| 「作者微信 / 交流群」+ 两张二维码 + 说明 | 截图 2 中段 | 同上（`plugin.xml` 尾部） | ✅ |
| 「Getting started」3 条 + 「运行期增强捕获默认开启」段 | 截图 2 下段 | Marketplace **后台独立字段 `docText`** | ❌ 只能在 jetbrains 后台改 |

实测读数：

- 包内 `plugin.xml` 的 `<description>` 全长 **7656 字符**（含 HTML 与英文段）；`GET /api/plugins/33633?fullInfo=true`
  返回的脱敏版是 7557 字符（标签被剥、注释消失后的形态）。
- 同响应 `docText` 全长 **486 字符**，正是截图 2 下半屏那段；`description` 里搜 `Getting started`
  / `安装插件` 均为 False。
- 三棵树 `grep -rn "Getting started"` 零命中，构建脚本也没有 `docText` 配置项 —— 确认这一块
  不在仓库内，属 Marketplace 后台维护。
- `preserveUIEdits = false`（本轮实测），即线上描述以**包内 `plugin.xml` 为准**，改仓库 + 发版
  就能覆盖线上描述；`docText` 不受此开关影响，仍需后台手工改。

按中文段计，10 条 bullet 约 1400 字，密集枚举（Wrapper 六种、SQL 来源十三种、国产库八种、
方言八种名字）占了近半篇幅。用户「太多」的观感来自这一层密集枚举，不是条目数本身。

### 1.2 二维码问题：不是一大一小，是右边那张整体下移了 7px

先把「上次为什么没生效」的前提推翻。对截图 2 做像素测量（纯码带 `y=447..614` 内做列投影）：

| | 用户截图 | JBR 25 复现（同款 HTML） |
|---|---|---|
| 左（微信绿码） | `x=[576,753]` w=**178**，`y=[440,614]` h=175 | w=**177**，起点 y=1680 |
| 右（交流群黑码） | `x=[782,960]` w=**179**，`y=[447,619]` h=173 | w=**179**，起点 y=1686 |

两个结论：

1. **宽度本来就相等**（178 vs 179，差 1px 属内容 bbox 取整）。`<td width="200">` 在 IDE 渲染器里
   是**生效的**——两列起点间距实测 206px（≈200 + 边框/间距），不是自由分配。
2. **真正的错位是垂直方向**：右码起点 y=447，比左码的 440 低 **7px**；底边也低 5px。

用户看到的「一大一小」是和垂直错位叠在一起的观感（绿码点阵稀疏、黑码几乎满格，密度差异放大了
这种印象），但根因只有一个。

### 1.3 根因链（已证）

```
<td width="200" align="center" valign="top">  ← 本地 plugin.xml 写法
                      ↓ 平台白名单剥离
<td width="200">                              ← 线上 API 实际返回值（已 curl 核实）
                      ↓ align/valign 都没了
默认 vertical-align: middle
                      ↓ 两列内容高度不同
左列说明 3 行（"扫码添加作者为朋友，欢迎交流插件使用与开发话题；加好友时请备注「SQL Visualizer」。"）
右列说明 2 行（"扫码加入 SQL Visualizer 交流群；群码失效时可添加作者微信。"）
                      ↓
内容少的右列整体向下居中 → 图片起点下移 7px
```

平台白名单事实：jsoup `Safelist.relaxed` 里 `td` 允许 `abbr/axis/colspan/rowspan/width`，
**没有 `align`、也没有 `valign`**。上一轮（20261103，`5bc068b8` 等三棵树）只补了 `width`
——那是水平方向的修法，垂直方向从头到尾没被触及，所以「修了一版没生效」不是修复失效，
而是**修的是另一个轴**。

### 1.4 渲染器差异（本轮新取证，写清楚以免下次再绕）

IDE 插件页（Settings → Plugins → 概览）与 Marketplace 网页是**两套渲染器**：

- Marketplace 网页：浏览器 + `.mdContent img{width:100%;max-width:800px}`，`img width` 属性被 CSS 盖掉。
- IDE 插件页：Swing `JEditorPane + HTMLEditorKit`（从截图排版特征判断），`img width` 属性生效，
  但 `td width` 在不同 JDK 上行为不一致：

| 运行时 | `<td width="200">` 下的结果 |
|---|---|
| JBR 25（IDE 2026.2 自带，`OpenJDK 25.0.4.1`） | 两列等宽（177/179），右码下移 6px —— 与用户截图一致 |
| JDK 8（`D:/HuanJing/Java/jdk1.8.0_181`） | **右码被挤出可视区**，只剩文字 |

也就是说 `td width` 这个属性在 IDE 侧不但没解决对齐，还是个跨 JDK 行为分裂的隐患。
任何继续依赖 `td width` 的方案都不该再走。

复现装置（本轮已搭好，但落在 gitignore 的 `.local/probe/swing/`，**不会进版本控制**，下次要重建；
若实施时想长期留存，移到 `dev-support/` 下并加进索引）：

- 线上 `description` 导出 → `desc_local.html`（图片换成本地 file URL，避免 Swing 异步取图导致空图）
- `HtmlRender.java`：`JEditorPane + HTMLEditorKit`，预加载图片后 `pane.paint()` 出 PNG，再像素量测
- 跑法：`"<IDE>/jbr/bin/java" -cp <classes> HtmlRender desc_local.html 455 out.png`
- 关键细节：Swing 默认 `AsyncImage` 取图，不等加载完就 paint 会得到空图；须先用
  `Toolkit.getImage(url)` + `MediaTracker.waitForAll()` 预热同一 URL 的缓存

---

## 二、待验证前提（实施前先证伪）

| # | 前提 | 现状 | 证伪后怎么办 |
|---|---|---|---|
| P1 | IDE 插件页用 Swing `HTMLEditorKit` 渲染描述 | **部分证实**：JBR 25 下 Swing 复现出的尺寸（177/179）与右码下移（6px）同用户截图（178/179、7px）吻合到 1px 级，排版特征也一致；仍未读平台源码确认组件类 | 若实为 JCEF（浏览器语义），推荐方案同样成立（两列内容等高即无错位），只是白名单剥离点要改按浏览器 CSS 解释 |
| P2 | 浏览器侧对推荐方案的渲染 | **未实测**：本机 Playwright 浏览器实例被其它会话占用，未强行另开 | 推理：td 内容只有 img，`img{width:100%}` 与固有宽循环依赖下浏览器取 220 → 两列等宽。**发版后必须回读线上截图复核** |
| P3 | `docText` 可在 jetbrains 后台单独编辑且即时生效 | 只证了「API 有、仓库无」，后台入口与生效时延未验 | 由用户后台确认；若不可编辑，则 Getting started 段只能随下次发版整体收敛 |

---

## 三、方案

### 3.1 二维码：表格只放图片，说明文字移到表格外

```
现结构（一列里既有图又有 2~3 行文字 → 行高不等 → middle 居中错位）
<table><tr>
  <td width="200"><img …><br><b>作者微信</b><br>扫码添加…（3 行）</td>
  <td width="200"><img …><br><b>交流群</b><br>扫码加入…（2 行）</td>
</tr></table>

新结构（两列内容都只有一张同样高的图 → 行高相同 → 天然顶对齐；宽度也由图片唯一决定）
<table><tr>
  <td><img src="…/wechat-qr.jpg" width="180" height="180" alt="作者微信二维码"></td>
  <td><img src="…/group-qr.jpg"  width="180" height="180" alt="交流群二维码"></td>
</tr></table>
<p><b>作者微信</b>：扫码添加作者为朋友，欢迎交流插件使用与开发话题；加好友时请备注「SQL Visualizer」。</p>
<p><b>交流群</b>：扫码加入 SQL Visualizer 交流群；群码失效时可添加作者微信。</p>
```

为什么这个形态在两套渲染器下都对：

- Swing：td 里只有 img(180×180)，列宽 = 180，两列相等；两列等高，无垂直居中偏移。
- 浏览器：td 里只有 img，`img{width:100%}` 撑满的宽度由 img 固有宽（220）决定，两列都 220，相等；
  两列等高，同样无偏移。
- 不再依赖 `td width`、`align`、`valign` 三者中的任何一个（两个已被白名单剥，一个跨 JDK 分裂）。

本轮已在 JBR 25 下渲染验证：两码顶边对齐、宽度相同、说明文字在下方独立成段（见 §1.4 装置）。

### 3.2 文案：中文 10 条 → 7 条，密集枚举收敛，细节指向文档站

保留硬约束（守卫会对账，不能删）：`15 种数据库方言`、`11 个确定性`、`Alt+Shift+Q`、`2024.3`、
首 40 字符英文、`<h3>中文</h3>` 在 `<h3>English</h3>` 之前、中英 `<li>` 数相等。

草案（中文，约 620 字，原约 1400 字）：

```html
<li><b>编码期预览 · 全 SQL 来源</b>：Wrapper 链（Lambda / Query / Update / Delete、MPJ 联表）、
mapper XML 动态标签、<code>@Select</code> 注解、Spring Data / JPA / HQL、JdbcTemplate、Querydsl、
iBatis 2.x 全覆盖，还原为可执行 SQL；XML、注解与 Wrapper 链内补全表名列名与 <code>#{}</code> 参数</li>
<li><b>真实参数回填</b>：<code>?</code> 换成实际值（LIKE 自动通配、按类型加引号），命名参数按名回填；
逻辑删除 / 乐观锁 / 多租户按 MyBatis-Plus 拦截器语义还原；内置格式化覆盖 15 种数据库方言（达梦、
人大金仓、GaussDB、OceanBase、GoldenDB 等）</li>
<li><b>运行期捕获（默认开启）</b>：向运行中的应用挂轻量 agent 采集真实执行——目标库、耗时、影响行数、
报错，慢查询标红并给静态建议，真实源码位置一键跳回；不依赖日志格式，控制台日志（MyBatis / iBatis /
Hibernate / JdbcTemplate）作为不改工程的回落通道</li>
<li><b>生成与回滚</b>：SQL 反向生成可编译的 QueryWrapper / LambdaQueryWrapper / MPJLambdaWrapper 代码；
实体类或表右键直出 CRUD SQL；INSERT 预览推导逆向 DELETE，UPDATE / DELETE 支持快照回填</li>
<li><b>执行与分析</b>：内嵌 Database 控制台执行，一键 EXPLAIN 按方言提交，JOIN 关系图导出 PNG / SVG；
执行前有 <code>${}</code> 注入确认、无界写清单与超时护栏（需 Ultimate）</li>
<li><b>工作流增强</b>：mapper XML 语法高亮、动态标签折叠与双向跳转；SQL 快捷模板；历史记录钉住 /
正则检索 / 导出 / 版本对比；参数预设按方法隔离；分页模拟 LIMIT 与 COUNT 分别控制</li>
<li><b>面向 AI · MCP 工具</b>：11 个 <code>sqlv_*</code> 确定性只读工具（渲染 / 日志还原 / 核查 /
执行计划 / 回滚预案 / CRUD / 反向转换），设置页逐项门控</li>
```

合并口径（10 → 7）：

| 原条目 | 去处 |
|---|---|
| ① 编码期预览 | 保留，枚举收敛（去掉 EasyQuery / Xbatis / MDS / Kotlin Wrapper 长说明，进文档站） |
| ② 真实参数回填 | 保留，方言清单从 8 个名字收到 5 个 |
| ③ 运行期捕获 | 保留，国产库驱动清单（达梦/金仓/GaussDB/磐维/OceanBase/GoldenDB）并入文档站，删「磐维」 |
| ④ 生成与转换 ⑤ CRUD SQL ⑥ 一键回滚 | 合并为「生成与回滚」 |
| ⑦ 执行与分析 | 保留 |
| ⑧ 调用栈悬停 ⑨ 工作流增强 | 合并为「工作流增强」（调用栈悬停作为运行期捕获的附属，可不单列） |
| ⑩ 面向 AI · MCP | 保留 |

英文段同构压到 7 条，一一对应。

### 3.3 `docText`（Getting started）：后台手工精简

当前 486 字符（3 条有序列表 + 1 段）。建议压到 2 条 + 1 句：

```
1. 安装插件 —— Settings → Plugins → Marketplace 搜 MyBatis SQL Visualizer，Install；装完没看到入口重启一次 IDE。
2. 光标放到 Wrapper 链 / Mapper 方法 / @Select 注解 / mapper XML 语句上，按 Alt+Shift+Q（或点左侧 gutter 图标）看带真实参数的最终 SQL；跑起应用则自动捕获真实执行记录。
```

这一块**不在仓库内**，需要用户在 jetbrains 后台 Marketplace 页面编辑。本册只出文案，不落码。

---

## 四、改动面与实施步骤

### 4.1 改动清单（master）

| 文件 | 改动 |
|---|---|
| `src/main/resources/META-INF/plugin.xml` | `<description>` 中文/英文段精简；尾部二维码改为「表格只放图 + 说明独立段落」 |
| `src/test/kotlin/cn/song/mybatisplus/visualizer/config/PluginXmlStructureGuardTest.kt` | 「二维码卡片两列须显式等宽」改写（见 §五） |
| `CHANGELOG.md` | `[Unreleased]` 补一句（用户可感改动） |
| `docs/LESSONS.md` | 补一条：描述表格的 `align`/`valign` 均被白名单剥，垂直对齐不能靠属性，只能靠内容等高 |

### 4.2 步骤

1. 改 `plugin.xml` 描述（文案 + 二维码结构）。
2. 改守卫测试（见 §五），先让它在新结构下绿、在旧结构/坏形态下红。
3. 定向测试：`./gradlew test --tests "cn.song.mybatisplus.visualizer.config.PluginXmlStructureGuardTest"`
   （后台启动，按并行登记表 register/done）。
4. 用 §1.4 装置在 JBR 25 下渲染新描述，确认两码顶边对齐、宽度相同（留图作证据）。
5. 同步 `dev`、`feature/kotlin-to-java` 两棵树的同名两文件（按锚点逐棵改，不整文件覆盖）。
6. 发版后回读：`GET /api/plugins/33633?fullInfo=true` 的 `description` 应含新结构，
   并按 P2 复核线上截图。
7. 用户后台改 `docText`。

---

## 五、守卫测试同步（必做，否则改完即红）

现有规则「二维码卡片两列须显式等宽」断言：两个 `<td>` 都有 `width`、彼此相等、且与 `<img width>` 一致。
新结构里 td 不再有 width → **不改这条必红**。

改写方向：把断言对象从「td 宽度」换成「结构形态 + 图片尺寸一致」：

1. 二维码卡片仍是 2 个 `<td>`，每个 `<td>` 内的 `<img src="…qr.(png|jpg)">` 存在；
2. 两个 `<img>` 的 `width` / `height` 显式声明且彼此相等（这是不同渲染器下唯一稳定的等宽依据）；
3. `<td>` 内**不得再出现说明文字**（`<b>` / 中文正文）——这是防「又有人把文字塞回单元格、
   行高不等重新引入 7px 错位」的正向约束，也是本次根因的定点回归；
4. 描述里不出现 `valign` / `align`（白名单会剥，写了只是误导维护者）。

反向验证（按仓库纪律必须做）：去掉 `img height`、把两图尺寸改成不等、把说明文字塞回 td，
三种坏形态各应恰好 1 红，恢复后全绿。

---

## 六、验收标准

- 守卫测试 4 条（等宽、无文字入格、尺寸一致、无 align/valign）在新描述下全绿；
  三种坏形态反向验证各恰 1 红。
- JBR 25 渲染图：两码顶边 y 相同、宽度相同（差 ≤1px）。
- 文案中英 `<li>` 数相等（7/7），首 40 字符全 ASCII，`15` / `11` / `Alt+Shift+Q` / `2024.3` 仍在。
- 发版后 `GET /api/plugins/33633` 的 `description` 与包内一致；线上截图两码对齐。
- `docText` 精简需用户在后台完成，本册仅交付文案。

---

## 七、风险与回退

| 风险 | 说明 | 处置 |
|---|---|---|
| 精简删掉卖点 | 国产库驱动适配、方言清单是差异化卖点，收短可能被用户认为「说不清」 | §3.2 草案已保留代表性子集；幅度可谈，只改条目详略不动 7 条骨架 |
| 浏览器侧未实测（P2） | 新结构在 Marketplace 网页的实际观感未见 | 发版后回读线上截图，异常则临时回退到「表格只放图 + 两图上下排列」的兜底形态（绝对无对齐问题，仅多占竖向空间） |
| 三棵树漂移 | dev / k2j 的 plugin.xml 注释与基线不同 | 逐棵按锚点改，改后各自跑本棵守卫测试 |
| `docText` 不可编辑（P3） | 若后台无该字段入口，Getting started 段不受控 | 由用户确认；必要时把该段内容并入 `plugin.xml` 描述统一管理 |

---

## 八、备注（给下次排查的人）

- 「插件页文字太多」与「二维码没对齐」是两件独立的事，但都指向同一个认知偏差：
  **描述在两套渲染器下表现不同**。以后动描述区样式/布局，先在 §1.4 装置里过一遍 Swing，
  再发版看浏览器，别只按其中一套推。
- `align` / `valign` / `style` 三个属性在 Marketplace 白名单里都不可用（前两个已实测被剥，
  `style` 按记忆档案的抽样结论也未见留存）。表格布局只能靠内容自撑。

---

## 九、实施记录（2026-11-27）

### 9.1 落地读数

| 项 | 读数 |
|---|---|
| 描述字符数 | 7656 → **5149**（master 包内，−33%）；k2j 5058；dev 5050 |
| 中/英条目 | 10 / 10 → **7 / 7** |
| 渲染总高（JBR 25，宽 455） | 1951px → **1240px**（−36%） |
| 二维码 | 两码同为 180×180，`td` 无任何属性，说明文字在表格外段落 |
| 守卫 | master 23/0、k2j 23/0 |
| 反向验证 | 三形态各恰 1 红：尺寸不等→`[(180,180),(200,180)]`；文字入格→「第 1 个二维码单元格里不该有说明文字」；td 带 valign→`[<td valign="top">]` |
| 提交 | master `a7d8063bb` + `1f6c77a38`（哈希回填）；k2j `a0547f67b` |

### 9.2 实施中踩到的两处

1. **条目数数错**：立项时按印象写「11 条」，实际 `git show HEAD:…plugin.xml` 数出来是 10 条。
   已回改计划册、索引、CHANGELOG 三处。教训：写数量类论断前先跑一次机器计数。
2. **`11 个确定性` 被标签打断**：初稿写成「11 个 `<code>sqlv_*</code>` 确定性只读工具」，
   守卫正则 `(\d+)\s*个确定性` 匹配不到（数字与「个确定性」之间夹了标签），首轮测试即红。
   改成「11 个确定性只读的 `<code>sqlv_*</code>` 工具」后通过。

### 9.3 未完成项

- **dev 树**：`plugin.xml` 与守卫测试已按锚点改完（保留 dev 独有的 Screenshots 旧 slug
  `33633-sql-visualizer`，未顺手改），但当时并行任务 `T-GiantFileBudgetDevRed-D262` 占着 dev 的
  `build/` 目录，未跑测试、未提交 → 交收口阶段。
- **`docText`**：Marketplace 后台字段，仓库触达不到。精简文案见 §3.3，需用户粘进 jetbrains 后台。
- **浏览器侧渲染（P2）**：本机 Playwright 实例被其它会话占用，未实测；发版后按 §6 回读线上截图。
