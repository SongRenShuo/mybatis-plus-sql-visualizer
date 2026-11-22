---
name: pitfall-marketplace-description-css-overrides-img-width
description: Marketplace 描述区 CSS `.mdContent img{width:100%;max-width:800px}`
  会盖掉 `<img width>` 表现属性——图片改按所在 td 撑满，td 不写 width
  时列宽由说明文字长度决定，两张并排二维码因此一大一小；修法＝给 td 写死等宽
metadata:
  node_type: memory
  type: project
  originSessionId: sess_01762413-f08e-493e-9eb7-1e2f02818dff
---

# 插件市场描述区：`<img width>` 会被 CSS 覆盖，并排图的等宽要写在 `<td>` 上（20261103）

## 症状

Marketplace 插件页「作者微信 / 交流群」两张二维码明显一大一小（截图量得 448px vs 313px）。两张源图本身没问题：都是 220×220，内容留白也一致（bbox 195×195 / 197×198）。

## 根因

描述区容器带 class `mdContent--_RJIbjv`，其样式表（`/static/versions/38046/6359.css`）里有一条：

```css
.mdContent--_RJIbjv img{width:100%;max-width:800px}
```

`<img width="200">` 是 **HTML 表现属性**，优先级低于 CSS 规则 → 被完全盖掉，图片不再按 200px 渲染，而是**撑满所在 `<td>`**。两个 `<td>` 又都没写宽度，`table-layout: auto` 下两列宽度由单元格里说明文字的长度决定：左边「作者微信」说明更长 → 列更宽 → 左边那张码更大。

按线上同款样式 + 真实 plugin.xml 片段在 headless Chrome 复现：**526px vs 368px，比例 1.43**，与线上截图 448 vs 313（同为 1.43）一致。改成 `<td width="200">` 后两图都是 **202×202**（200 + 两侧 1px 边框）。

## 修法

给并排的每个 `<td>` 写同样的 `width`，`img` 撑满的宽度随之相等：

```html
<td width="200" align="center"><img src="…" width="200" alt="…">…
<td width="200" align="center"><img src="…" width="200" alt="…">…
```

守卫：`PluginXmlStructureGuardTest`「二维码卡片两列须显式等宽」——要求两个 td 都有 width、彼此相等、且与 img 的 width 一致。反向验证（去掉 td width）恰好 1 红，23 tests 其余全绿。

## 平台白名单（决定能不能用哪个属性）

拿 `GET https://plugins.jetbrains.com/api/plugins/33633` 的 `description`（**这是脱敏后的成品 HTML**）对比本地 plugin.xml：

- `<td align="center">` → 线上 `<td>`：**align 被剥掉**；
- `<img width="200">` → **width 保留**。

这个组合与 jsoup `Safelist.relaxed` 一致（`td` 含 `abbr/axis/colspan/rowspan/width` 无 `align`；`img` 含 `align/alt/height/src/title/width`），所以 **td 的 width 可用**；`style` 不在白名单，**不要靠内联 style 顶 CSS**（抽样 152 个插件描述，`<td width>`/`<table width>` 命中 0、img 带 style 命中 0/1 且不可复现——td width 是「按白名单推断允许」，不是「观测到有人在用」，发版后应复核）。

## 排查手法（页面是 JS 渲染的，直接 curl 拿不到描述）

1. `curl -H "Accept: application/json" https://plugins.jetbrains.com/api/plugins/<id>` → `description` 字段＝脱敏后 HTML；
2. 页面 HTML 里搜不到 `<img>`/描述，改用 headless Chrome `--dump-dom` 抓最终 DOM（能确认容器 class 与属性是否被剥）；
3. 样式表在页面 `href="/static/versions/<v>/*.css"`（分多块，全部下下来 grep `img` 规则）；
4. 量尺寸：把描述片段 + 线上 CSS 写成本地 HTML，注入脚本把 `getBoundingClientRect()` 写进 `<pre>`，再 `--dump-dom` 读（本机无 CDP 客户端，这招最省事）。

## 两条连带事实

- **描述是两份数据，时序由 `preserveUIEdits` 决定**：包内 `plugin.xml <description>` 与后台页面描述分开存。详见 [[pitfall-marketplace-description-two-stores-preserveui]]（20261104 报障复盘，含 `preserveUIEdits` 语义与设置入口）。
- 顺带查出 `https://sql-visualizer.jvtools.cn/group-qr.png` **实际是 JPEG 字节**却按 `.png` 返回 `image/png`——已在当轮修掉（补 `group-qr.jpg`、旧 `.png` 保留），细节见 [[pitfall-static-asset-extension-vs-bytes]]。

## 改动落在哪些地方（20261103 已提交）

同一处描述要同步**三棵树 + 站点**：master `5bc068b8` / dev `43891d81` / feature/kotlin-to-java `23555b7e`，站点 `2eeb67a`（未部署）。三棵树的 plugin.xml 描述文案、测试基线各不相同（k2j 是 Java 端口线、dev 的设置页基数与主仓不一致），所以**逐棵按锚点插入，不要整文件覆盖**。

⚠️ 动 k2j 工作树前先确认它没有正在跑的门禁（本轮它有一轮 testShards 在跑，等它 11:04 结束才落笔，否则那道绿立刻作废）；判活进程用 `Get-CimInstance Win32_Process` 查命令行含 `mybatis-plus-sql-visualizer-kotlin`。

相关 [[pitfall-gitee-hotlink-302]]（外链图在 Marketplace 的另一类坑）、[[reference-html-mockup-headless-render]]（本机 headless 截图/量尺寸的通用路径）、[[pitfall-static-asset-extension-vs-bytes]]。
