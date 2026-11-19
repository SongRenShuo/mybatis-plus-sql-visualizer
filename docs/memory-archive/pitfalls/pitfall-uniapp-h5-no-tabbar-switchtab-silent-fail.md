---
name: pitfall-uniapp-h5-no-tabbar-switchtab-silent-fail
description: uni-app 里 tabBar 若只对小程序条件编译，H5 上 uni.switchTab 会静默失败（不报错也不跳转）——自建顶栏点导航没反应；另附 H5 三重标题的成因与**「navigationStyle: custom 挂 globalStyle 会连带干掉所有二级页返回箭头」这一自造回归**
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fd128d0e-d320-4865-9871-208872610cd6
---

2026-11-02 openget H5 验收时用户报「首页/热门/我的这几个 tab 点击没有跳转」。

## 根因：`uni.switchTab` 只对 tabBar 页生效，而 H5 没有 tabBar

`pages.json` 的 `tabBar` 写在 `// #ifdef MP-WEIXIN` / `// #ifdef MP-TOUTIAO` 里（H5 用自建 TopNav 顶栏，见 H5 互通方案 3.1-1）。于是 H5 产物根本没有 tabBar，而 `uni.switchTab({url})` 在 H5 上**不报错、不跳转、也没有控制台警告**——点上去就是没反应。

全仓 9 处 `switchTab` 全部中招，不止三个 tab：额度角标进「我的」、搜索空态「去看看热门」、下载完成回首页、历史/邀请/详情的返回兜底。

**修法**：抽 `utils/nav.js` 的 `goTab(url)`，按平台分派——H5 走 `uni.reLaunch`（清栈、不产生返回入口，语义与切 tab 一致），小程序仍走 `uni.switchTab`（tabBar 页必须用它，用 reLaunch 会绕过 tabBar 状态）。

```js
export function goTab(url) {
  // #ifdef H5
  return uni.reLaunch({ url })
  // #endif
  // #ifndef H5
  return uni.switchTab({ url })
  // #endif
}
```

**判据**：这类失败在 mock 模式、构建、单测里全都看不见，只有真点一次才会暴露——凡是「条件编译让三端页面注册结构不同」的地方（tabBar、navigationStyle、easycom 分包），导航 API 都要按平台分派，不能共用一套。

## 附带：H5 出现三重标题

同一页面同时出现「系统标题栏『码上拿』+ 顶栏品牌『码上拿』+ 页面内大标题『码上拿』」，额度角标也重复两份。成因是三处各写了一份：

- `pages.json` 的 `navigationBarTitleText`（H5 会渲染成原生标题栏）
- `components/top-nav.vue` 的品牌文字（桌面态才该出现，但基础样式没写 `display:none`，移动端也在渲染）
- 页面内的 `og-title`

**修法**：顶栏品牌 `display:none` 打底、`@media (min-width:1024px)` 才显；页面内额度角标改 `#ifndef H5`（H5 由顶栏承担）；`navigationStyle: custom` 只挂在**自带 TopNav 的三个 tab 页各自的 style 里**（见下节）。

## ⚠️ 自造回归：custom 挂 globalStyle 会干掉所有二级页的返回箭头

第一版修法图省事，把 `"navigationStyle": "custom"` 写进了 `globalStyle`（`// #ifdef H5` 包住）。H5 的原生标题栏**同时承载返回箭头**，全局关掉等于把详情、下载、识别、历史、流水、协议、邀请、搜索结果这**八个二级页的返回入口一起删了**——用户下一轮直接报「好多页面缺少返回按钮」。

**正确做法**：`custom` 只下放到三个自带 TopNav 的 tab 页（首页/热门/我的）各自的 `style`，其余页面保留原生标题栏（带返回箭头 + 标题）。

**验收判据（生产构建实测，不能只信 dev）**：
- 首页 `#/` → 无 `uni-page-head` 元素（custom 生效，只剩 TopNav，不重复）
- 详情页 → 有 `uni-page-head` 且 `.uni-page-head-btn` 存在、标题正确
- `mp-weixin/app.json` 里**不应**出现 `navigationStyle`（出现即条件编译漏了）

dev server 与生产构建都可能因缓存不重编，改完 pages.json 必须 `reload()` 再验；必要时把 `dist/build/h5` 起静态服务器验生产形态（本轮就是这么确认的）。

相关：[[project-openget-multiclient]]、[[pitfall-uniapp-component-swap-dom-and-state]]、[[pitfall-css-token-batch-substitution-invalid-declaration]]。
