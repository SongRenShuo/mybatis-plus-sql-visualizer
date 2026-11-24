# T-AboutSocialMatrix · 文档站关于页「账号矩阵」展示 · 开发计划

- 创建：2026-11-24
- 目标仓库：`mybatis-plus-sql-visualizer-docs`（master）/ `-docs-dev`（dev）
- 涉及文件：`about/index.md`、`en/about/index.md`、`public/`（新增图标）、`.vitepress/`（可能需少量配置）

## 一、背景

2026-11-23 到 24 集中铺了 11 个内容平台（知乎、掘金、CSDN、dev.to、51CTO、博客园、腾讯云、阿里云、今日头条、小红书、微信公众号），加上 Agensi 的 skill 目录和 GitHub/Gitee/Marketplace 三个代码向入口。

但文档站的「关于」页目前只列了**开发向**的 5 个链接（Gitee / GitHub / 需求池 / Bug 悬赏 / Marketplace），**没有任何内容平台的入口**。读者看完功能文档，想继续看作者的其他文章或关注账号，没有路径。

同时，这些账号已经能反哺项目：2026-11-24 实测 DeepSeek 回答「MyBatis 调试插件推荐」时，引用了我们的掘金文章和知乎回答。把矩阵放到 about 页，既是读者服务，也是可发现性的一环。

## 二、现状（2026-11-24 实读源码）

- `about/index.md` 共 103 行，分区依次是：作者 → 联系方式（5 行表格）→ 微信交流（`.qr-cards` 两栏卡片）→ 开源协议 → 技术栈 → 致谢
- `en/about/index.md` 同结构，英文
- `public/` 里社交图标只有 `gitee-icon.png`、`github-icon.png`
- `package.json` 依赖：只有 `vitepress` + `image-size` / `marked` / `pagefind` / `puppeteer-core` / `vitepress-plugin-llms`，**没有图标库、没有组件库**
- 页面**已有内联 `<style>` 的先例**（`.qr-cards` / `.qr-card` 一族，含 hover 边框变色），说明矩阵可以沿用「Markdown + 内联 CSS」的写法，不必为此新增 Vue 组件

## 三、需求

### 形态（2026-11-24 用户定案）

**独立页面**，不是 about 页里的一个区块。理由：承载更多视觉表现，且可以当「链接聚合页」单独分享（类似 bio.link / linktree，但技术风）。about 页只放一个入口指向它。

**要「炫酷」**——动效可以放开，不再限制纯 CSS。

### 硬性

1. 13 个平台各自带 logo（不是纯文字链接）
2. 点击跳对应主页（地址见 §四）
3. 深色 / 浅色两套主题下都清晰可读
4. 可访问性：每个链接有可读名称、键盘可聚焦、焦点态可见；**动效不能妨碍点击**
5. 不显著拖慢构建与首屏；移动端可用

### 软性

6. 视觉冲击力（用户明确要求「炫酷」）
7. 与站点技术文档调性协调，不要做成纯娱乐页
8. 中英两页同步
9. about 页 + 顶部导航有入口

## 四、数据（已就绪，逐个访问验证过）

| 平台 | 主页地址 | 备注 |
|---|---|---|
| 知乎 | https://www.zhihu.com/people/shi-jiu-yu-ta-77-53 | **URL 是拼音 slug，不是 SongRenShuo** |
| 掘金 | https://juejin.cn/user/116975001416044 | 数字 UID |
| CSDN | https://blog.csdn.net/weixin_66219471 | `weixin_` 前缀是注册默认名 |
| dev.to | https://dev.to/songrenshuo | |
| 51CTO | https://blog.51cto.com/u_17818757 | |
| 博客园 | https://www.cnblogs.com/songrenshuo | |
| 腾讯云社区 | https://cloud.tencent.com/developer/user/10751767 | |
| 阿里云社区 | https://developer.aliyun.com/profile/xxji5q4pz2xyi | |
| 今日头条 | https://www.toutiao.com/c/user/861619969071632/ | 用数字 ID，别用 token 长链 |
| 小红书 | https://www.xiaohongshu.com/user/profile/5fe8a14b0000000001008764 | |
| GitHub | https://github.com/SongRenShuo | 已有图标 |
| Gitee | https://gitee.com/SongRenShuo | 已有图标 |
| JetBrains Marketplace | https://plugins.jetbrains.com/vendor/SongRenShuo | |

微信公众号没有公开主页（只有文章链接 + 二维码），已在「微信交流」区覆盖，**不放进矩阵**。

## 五、设计方案（2026-11-24 调研定稿）

调研对象：Vercel / Supabase / Resend / Linear / Tailwind 的 footer、GitHub 个人 profile、MagicUI IconCloud、Aceternity logo-clouds、若干开发者个人站（Dan Abramov / Lee Robinson / 阮一峰）。

**核心结论：13 个入口在头部产品里没有先例**——他们普遍只放 3–5 个，且多是纯文字链接（Vercel、Linear、Tailwind 均如此）。到这个数量只能自建，且必须靠「分组 + 文字标签」来保证可读性。

### 排除的方案

| 方案 | 为什么不用 |
|---|---|
| VitePress 原生 `socialLinks` | 源码固定 36px 命中区 + 20px 图标、无文字标签，13 个横排溢出 |
| 纯文字链接列（Vercel/Linear 式） | 不满足「要有 logo」 |
| IconCloud（canvas 3D 球） | 球体背面图标反向、无法扫读；需为 Vue 重写拖拽/旋转 |
| logo marquee 滚动带 | 一次只露 4–5 个，要等它转过来；链接位置不可预期 |
| `backdrop-filter` + 持续动画 | 拖 TBT，且无法暂停（违反 WCAG 2.2.2） |

### 选定：分组卡片网格 + 轻量动效

- 3 组：**技术社区 8 / 内容平台 2 / 代码与分发 3**；桌面 4 列、移动 2 列
- 卡片 = 单色图标 + 平台名；hover 时图标转品牌色、卡片上浮 3px、品牌色投影
- 「炫酷」的落脚点放在三处：标题渐变、跟随光标的光晕（仅精确指针设备）、错峰入场
- 不做持续动画（性能与可访问性优先，可读性优先于炫技）

### 两条硬约束

1. **图标默认单色**：GitHub `#181717`、dev.to `#0A0A0A`、JetBrains `#000` 三个纯黑在深色主题下会消失 → 品牌色只用于 hover
2. **必须带文字标签**：51CTO / 博客园 / 腾讯云这类中文平台纯图标辨识度低

### 品牌色（实测，非印象）

| 平台 | 色值 | 备注 |
|---|---|---|
| 知乎 | `#0084FF` | |
| 掘金 | `#007FFF` | |
| CSDN | `#FC5531` | |
| dev.to | `#0A0A0A` | 纯黑，深色下必须走 currentColor |
| 51CTO | `#E31136` | **反直觉**：站点 UI 蓝 `#3E71F6` 是主题色，品牌色取 favicon 实测红 |
| 博客园 | `#2E7ACC` | **反直觉**：不是绿色，官方 logo 是蓝色 |
| 腾讯云 | `#006EFF` | favicon 向 `#00A3FF` 渐变 |
| 阿里云 | `#FF6A00` | |
| 今日头条 | `#FF373C` | |
| 小红书 | `#FF2442` | |
| GitHub | `#181717` | 纯黑 |
| Gitee | `#C71D23` | |
| JetBrains | `#000000` | 纯黑 |

### 图标资产

- **9 个走 simple-icons SVG**（`fill: currentColor`，可着色）：知乎 / 掘金 / CSDN / dev.to / 阿里云 / GitHub / Gitee / JetBrains + 今日头条（用 `icon-park:jinritoutiao`）
- **4 个走官方 favicon 彩色位图**（不能改色，只参与缩放）：51CTO 48×48 / 腾讯云 32×32 / 博客园 32×32 / 小红书 32×32

**取图过程踩的坑**：

- 51CTO 与腾讯云的 `favicon.ico` 直接反爬——`curl` 裸请求返回 986B 混淆 JS，**必须带浏览器 UA 加 `Accept: image/*`** 才拿得到真图（拿到后分别是 PNG 48×48 / ICO 32×32）
- 各家 `apple-touch-icon.png` 全是 404 页面（约 986B），不是图标
- 博客园官网有 `logo.svg`，但它是 150×55 横向含文字的完整 logo，塞不进方形图标位，最终取了 favicon（蓝色圆环，图形版清晰）
- 第三方 favicon 服务（`favicon.im` / `faviconkit`）返回的是灰底 "f" 占位图，不可用
- 小红书 favicon 与 simple-icons 版本都是**文字型 logo**（红底「小红书」三字），22px 下偏糊；暂用官方位图，若观感不够可换纯图形印章

### 首版已落地（2026-11-24）

- `links/index.md` + `en/links/index.md`（`layout: page`）
- `.vitepress/theme/components/links/SocialMatrix.vue`
- `.vitepress/theme/data/social-links.ts`（13 条，含 SVG path 数据）
- about 页中英各一个入口卡片（顺带修了 Marketplace 旧 slug `33633-sql-visualizer` → `33633-mybatis-sql-visualizer`）

### 已修的视觉缺陷（2026-11-24）

**踩坑：`filter: blur()` + `overflow: hidden` 会画出一圈「格子框」**

首版在 `.sm::before` 上放了一个 680×340 的模糊光斑（`filter: blur(46px)`），父容器带 `overflow: hidden`。结果浅色主题下整个矩阵区沿容器边界出现一圈极淡的矩形边——肉眼明显，但**像素采样抓不到**（沿中线取点全是纯白 255,255,255），因为那是合成层的 1px 抗锯齿边。

处置：删掉该光斑，`.sm` 的 `overflow: hidden` 一并去掉。「炫酷」改由**限定在元素内部**的效果承担：卡片 hover 时用 `background-image: radial-gradient(...)` 注入品牌色顶光，被 `border-radius` 自然裁切，不会溢出成整页色块。

教训：给容器加 `filter` 类效果（blur/drop-shadow）时，只要容器还有 `overflow: hidden`，就要当心边界伪影；这类伪影用采样式验证会漏判，必须看截图。

### 待调整

- 视觉强度现为「克制版」；若要更炫，可加分组标题的流光、卡片 hover 渐变描边、页面级微粒子背景——每一项都要单独评估 TBT 增量
- 3 个字母徽标待换官方 SVG
- 主导航尚未加入口（目前靠 about 页卡片）

## 六、技术方案

### 路由与文件

- 新页面：`links/index.md`（中文）、`en/links/index.md`（英文），路径 `/links` 与 `/en/links`
- frontmatter 用 `layout: page`（脱离文档侧栏，独占视口），`sidebar: false`
- 主视觉组件：`.vitepress/theme/components/SocialMatrix.vue`，在 md 里以 `<SocialMatrix />` 引入（VitePress 默认支持 theme components 全局注册，或用 md 内 `<script setup>` 局部引入）
- 入口：about 页「联系方式」上方加一条卡片式跳转；顶部导航「更多」或页脚加 `/links` 链接

### 图标

- 11 个新 SVG 放 `public/icons/social/`（GitHub/Gitee 可复用现有 png，但为统一建议一起换 SVG）
- **不引 CDN、不装 npm 图标包**——站点目前零运行时依赖，图标包会进构建产物且拖慢首屏
- 来源优先 simple-icons（MIT）的路径数据，内联进组件，省一次请求
- 品牌色：每个平台一个主色 hex，用于 hover 光晕/描边；浅色主题下降低饱和度

### 动效（待 §五 定档，技术上限如下）

- Vue 3 + 原生 CSS：`transform` / `filter` / `opacity`，避免 `top/left` 触发重排
- 需要 3D 时用 CSS `transform-style: preserve-3d` + `rotate3d`，或 canvas；**优先 CSS**，canvas 只在图标云方案里用
- 鼠标跟随/视差用 `pointermove` + `requestAnimationFrame` 节流，组件卸载时移除监听
- `@media (prefers-reduced-motion: reduce)` 下关闭一切持续动画与视差
- 动画只在进入视口后运行（`IntersectionObserver`），避免后台耗电

### 性能预算

- 页面 JS 增量 ≤ 30KB（gzip 前）
- 图标总字节 ≤ 40KB（SVG 路径数据）
- 首屏 LCP 不因动画劣化：动画元素不参与 LCP 候选，或加 `content-visibility`

### 主题

- 用 VitePress 变量 `--vp-c-bg` / `--vp-c-bg-soft` / `--vp-c-divider` / `--vp-c-brand` / `--vp-c-text-1`
- 深色下给 logo 加轻微发光（`drop-shadow`），浅色下改为描边，两套分别调

## 七、实现步骤

1. 取 11 个平台 SVG（simple-icons 路径数据），统一 viewBox 与尺寸
2. 建 `links/index.md` 骨架 + `SocialMatrix.vue`，先做静态网格（保证可用性先行）
3. 叠加主视觉动效（按 §五 选定的形态），双主题各调一轮
4. 加 `prefers-reduced-motion` 与 `IntersectionObserver` 降级
5. 中英两页 + about 页入口 + 导航入口
6. `npm run build`；用 Lighthouse 对比改动前后（Performance / Accessibility）
7. 真机：桌面 1440、窄屏 375、深色浅色四组截图自查

## 八、验收标准

- 13 个入口全部可点，跳转地址与 §四 一致
- 主视觉动效在首屏即可见（不需要滚动才出现）
- 深色主题下 logo 不糊、不发灰；浅色主题下不刺眼
- 键盘 Tab 能逐个聚焦，焦点态可见；动效层不拦截点击（`pointer-events` 正确）
- `prefers-reduced-motion: reduce` 下无持续动画，页面仍完整可用
- 移动端 375px 宽度下不横向滚动，13 个入口全部可达
- 构建通过；JS 增量与图标字节有实测数字，不超 §六 预算
- 中英两页内容一致

## 九、不做的事

- 不引入第三方 UI 组件库 / 图标 npm 包（构建产物会变大，且站点目前零运行时依赖）
- 不用 CDN 加载图标、字体或动画库
- **不做「无 JS 就白屏」的矩阵**——退化为静态网格，链接必须可点
- 不把微信公众号硬塞进矩阵（没有公开主页，放二维码区更合适）
- 不做需要用户「先玩一下动画才知道能点」的交互（可发现性优先于炫技）

## 十、风险

- **品牌 logo 授权**：各平台 logo 用于「指路到该平台的本账号」，属合理使用，但仍需用简化版（不带注册商标符号）
- **视觉过载**：13 个图标一字排开会显得杂；如果调研结论支持，优先「网格 + 平台名」而非纯图标
- **中英同步遗漏**：英文页容易忘记，验收时逐项对照
