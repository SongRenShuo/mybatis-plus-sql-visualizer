---
name: reference-html-mockup-headless-render
description: 本机给用户做 UI 方案 HTML 对照稿时，把片段渲染成 PNG 自查的可行路径（render.py 坏、playwright
  模块缺，直接用 ms-playwright 里的 chrome.exe 无头截图），以及轮播片段必须补的 hidden 样式
metadata:
  node_type: memory
  type: reference
  originSessionId: session_a1d9f2190f4a83fd6665645de62303d3
---

# UI 方案 HTML 对照稿：本机渲染自查路径

20261026 给 SQL 列表方案做多方案对照（viz-carousel）时实测。

## 三条渲染路径的可用性

1. **`python scripts/render.py <fragment> <out.html>`（visualize skill 自带）——本机直接失败**：报 `bundled resource hash mismatch: floating-ui-core-1.7.3.min.js`。导出/打包路径不通，别再试。
2. **`python -c "import playwright"` —— 模块不存在**（`ModuleNotFoundError`）；`npx playwright` 也要现装（npm 取消）。别指望这两条。
3. **✅ 可行：直接用已缓存的 Chromium 二进制**：
   `C:\Users\USER\AppData\Local\ms-playwright\chromium-1223\chrome-win64\chrome.exe`
   ```
   chrome.exe --headless=new --disable-gpu --hide-scrollbars \
     --force-device-scale-factor=1 --window-size=1100,860 \
     --screenshot=<png> file:///<wrapped.html>
   ```
   `--dump-dom` 同款参数可回读真实 DOM（验证脚本确实注入了内容，而不是只看 `<div class="stage">` 空壳）。
   无头启动会打一条 `Network service crashed or was terminated, restarting service` 到 stderr——**无害**，screenshot 照常产出。

## 自查流程（每轮必做）

1. 先把片段包一层最小宿主 HTML：外部 CSS 只需补 `--foreground`/`--muted-foreground`/`.form-range`/`.viz-controls`/`.text-small`/`.text-muted`，再加一段选择要看的 section 的脚本。
2. `node --check` 校验片段里 `<script>` 的语法（正则抽出脚本文本写临时 .js）。
3. 逐个 section 截图 → Read 图片人眼确认布局。
4. 需要量测具体宽度/高度时，在宿主页 `window.addEventListener('load', ...)` 里把 `getComputedStyle`/`getBoundingClientRect` 结果写进 `document.title`，再 `--dump-dom` 取 `<title>`——比截图目测准。

## 🆕 轮播片段的坑：`hidden` 被 `display:flex` 顶掉

`viz-carousel` 的 section 通常写 `display:flex;flex-direction:column` 来居中舞台，而 **`display` 声明会盖过 `hidden` 属性自带的 `display:none`** ⇒ 所有方案同时可见、页面被拉成几屏高，轮播导航看起来「点了没反应」。

修法：片段里自己补一条
```css
#root .viz-carousel > section[hidden]{display:none}
```

## 其它实测细节

- IntelliJ 风格 mockup **不能用可视化输出框架的 CSS 变量**（`--card`/`.card`/`.btn` 等），必须在 mockup 根上重定义产品自己的色板与字号；容器查询用 `container-type:inline-size` 让 mockup 内部按宽度降级（窄栏去列/去来源），这比媒体查询贴合「侧栏可拖宽」的真实场景。
- 容器查询里的选择器**必须带 mockup 前缀**（`.mock .va .c-db`），裸写 `.va .c-db` 会被同文件前面的规则顺序压掉，表现为「降级规则完全不生效」——本轮踩过一次，靠 dump-dom 打出 `gridTemplateColumns` 才发现。
- 顶部放一个宽度滑杆（`min=400 max=760 value=590`，590 即用户截图里的真实侧栏宽）比做多张固定宽度图有效，用户能自己拖到极限窄栏看降级。

## 🆕 代码形态的 mockup 必须照抄真实规则，否则对照稿会骗用户

同一轮里给「关键字换行 / 格式化模式」出对照稿时，我自己在 JS 里近似实现 `formatClauseLine`，踩了三处：

1. **用 `TOKEN_RE` 重组 token 会吞掉 token 间空格**（`SELECTt.idASid` 这种粘连），用户看到的形态直接失真。改法＝**按字符扫描原文**（保留原字符，只在命中子句关键字的位置插断点），不要用正则重拼。
2. **`LEFT`/`JOIN` 必须按限定词规则同行**：真实格式化器里 `JOIN` 前一个是 `LEFT/RIGHT/INNER/FULL/CROSS` 时保持同行，否则顶层新行；漏了这条会把 `LEFT JOIN x` 画成两行，用户对着错的形态做决策。括号内不换行、AND/OR 不换行同样要照抄。
3. **多行 SQL 必须一行一个 `<div>`**：单个 `white-space:pre` 容器只能整体裁切，拿不到逐行 ellipsis；每行一个 div 才能复现「每行按宽度省略」。

另有一条对照稿纪律：**每个变体都要有能体现差异的样例数据**。本轮「格式化封顶六行」与「无封顶」在只有 6 个子句的语料下渲染结果逐像素相同，等于空变体——补一条带双 LEFT JOIN + GROUP/HAVING/ORDER/LIMIT 的长 SQL 才看得出。

## 🆕 文本压缩别取前缀，要留尾部

同一轮第五批：轨道里显示类名，我先按「取前 11 字符 + …」压缩，三个 `Biz*ServiceImpl` 全渲染成 `BizCarStati…`，视觉上完全无法区分——用户直接反馈「缺少类名」。通用做法是**先剥公共后缀（ServiceImpl/Impl/Controller/Service/Mapper）再保留尾部 N 字符**，业务辨识词通常在尾部。这类「看起来加了信息、实际零区分度」的压缩，是做对照稿时最容易自欺的一种，出图后要专门看一眼它是否真能区分样例。

## 🆕 同一个字在不同上下文可能指代不同对象

用户说「类显示在底部」时，我按上下文理解成 SQL 类型（查/增/改/删），做了一整版才被纠正——他要的是**类名**（`XxxServiceImpl`）。信息密度高的中文 UI 讨论里，「类」「类型」「来源」都可能指向同一堆东西；改版前用一句话复述自己的理解，比出图后再返工便宜得多。

## 🆕 用户中途插话时的处置顺序

本轮用户在实施中途连发三条追加要求（性能不许退化 → 压测要几千几万条 → 搜索筛选排序也要压）。处置是：**先用一两句话正面回答他问的那件事，再继续手上的活儿**，不要因为「正在跑测试/正在改代码」就不理。用户插话往往是对当前方向的补充约束，不是打断——尤其「验收文档里记得写压测」这种，落不到文档就等于没做。

## 🆕 滑杆初始值要能被脚本驱动（自查窄栏降级时）

顶部宽度滑杆（`<input type="range">`）默认 590，但无头截图只能拍一个状态。要在自查脚本里模拟拖到 460/420：
```js
var r=document.getElementById('mock-w'); r.value=460; r.dispatchEvent(new Event('input'));
```
**只改 `r.value` 不派发 `input` 事件无效**——依赖事件监听的重绘不会触发。验窄栏降级是否真生效，同样用 `--dump-dom` 打出 `getComputedStyle(el).gridTemplateColumns`，别只靠截图目测。

## 🆕 版面形态类决策：先出预览，别把问卷答案当定稿

同一轮第七批：用户提三点版面意见后，我先提了 `AskUserQuestion` 三问、用户全部作答，我就据此改了计划文档。结果用户马上说「**你先给我生成预览效果让我确认**」，看完图又连改两轮（先「左侧侧边还是要得」、再「左侧栏也不要了」），把自己刚选的选项也推翻了。

结论：**问卷答案是意向，预览才是决策依据**。UI 形态讨论里问卷只用来收敛方向，落计划/动代码前必须先出图；并预期用户看到图后会改主意，每次改动前用一句话复述当前理解。

## 🆕 多滑杆自查（宽度 + 密度这类正交维度）

本轮最终稿有两个独立滑杆（面板宽度 380~720、条目间隔 4~24）。做法同单滑杆：两个 `<input type="range">` 各自 `addEventListener('input', ...)` 写 CSS 变量（`--mock-w` / `--row-gap`），行内边距用 `padding: var(--row-gap) 9px`，这样拖间隔时行高真的变、能看出「拉大留白」的分割效果。**别把间隔写成固定 px**——用户要对比的就是密度本身。

## 关联

- 方案内容与结论见 [[project-sql-list-highlight-layout-gap]]。

## 🆕 同一套路径可直接产出「对外封面图」（20261026 微信长文封面）

给公众号长文做 2.35:1 封面时，最终形态是**独立 HTML + 无头 Chrome 2x 截图 + PIL 缩放**，不在文档站里改任何东西（用户明确要求不动首页）。

- **尺寸做法**：`--window-size=1800,766 --force-device-scale-factor=2` → 得 3600×1532，再 `resize((1800,766), LANCZOS)` 落地。1:1 方图同法（600×600 → 1200×1200 → 缩回）。
- **不要用截图裁切代替重绘**：从官网首页 hero 截一帧代码块再裁，字号受原始页面限制（约 14px），放大到封面尺寸后要么太小要么模糊。**按同版式用 HTML 重绘**，字号、配色、间距全是 CSS 参数，改一行就能重出。用户对「字太小」「代码块太大」的两次驳回，都靠调 CSS 数字解决。
- **代码内容的语义一致性是硬要求**：封面若呈现「Java → SQL 转换」，两边必须逐项对应。曾出过 Java 侧 `new LambdaQueryWrapper<Device>()`（无任何条件）+ SQL 侧两个 WHERE 的封面，被判「转换前后代码不一致不正确」。**最终采用官网 hero 原版那对语义一一对应的代码**：`DeviceInfoEntity` ↔ `device_info_entity`、`eq(getId, 1001L)` ↔ `WHERE id = 1001`、`isNotNull(getStationId)` ↔ `AND station_id IS NOT NULL`。**别自己编一段简化的示例代码**——自编的极易漏条件，直接用官网现成的一对最稳。
- **布局协调**靠整体居中 + 单一主体，不要「标题居左、面板居右、要点居左下」这种三块各自为政的拼贴（那样会被判「很不协调」）。**标题一行、代码面板居中占主体**是最终被采纳的形状；左侧再挂「三条要点」的第一版被判不协调后删除。
- **中文不换行时按字宽反推块宽**：Consolas 单行 73 字符 × 39px 需要约 1720px 块宽；写死块宽再调字号，必有一边溢出（第一版就溢出了）。
- **方图（1:1）的 Java 行要主动折行**：600px 宽放不下完整 Wrapper 链，按官网竖排习惯折成四行（`wrapper =` 换行接 `new LambdaQueryWrapper<>()`），否则第一行会被面板右缘裁掉。

同类参考：[[project-wechat-article-20261026]]。
