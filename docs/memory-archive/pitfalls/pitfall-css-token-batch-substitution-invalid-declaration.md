---
name: pitfall-css-token-batch-substitution-invalid-declaration
description: 把硬编码色值机械替换成设计令牌时，正则会把 #fff 的尾巴拼进 var() 里造出非法声明——浏览器整条丢弃且不报错，页面表现为「样式根本没做过」，而改动记录、构建、截图前的自检全都显示「已令牌化」
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fd128d0e-d320-4865-9871-208872610cd6
---

2026-11-02 openget 的 UI「大厂风令牌化」交付后用户验收仍说「布局没重构，还是很丑」。实查发现 14 处 CSS 声明**整条失效**：

```css
/* 机械替换产物：把 #fff 的尾巴留在了 var() 后面 */
background: var(--og-gray-0, var(--og-gray-0, #ffffff)fff);
```

浏览器对非法声明是**静默丢弃**（不进 DevTools 的报错、不打断解析），于是卡片、按钮、热词标签全丢了白底，视觉上就像「从来没做过样式」。分布在 9 个文件（顶栏、额度角标、弹层、热门、详情、识别、首页、协议、历史）。

**判据**：令牌化之后不能只看「文件里有没有 `var(--og-*)`」（那是覆盖率，不是有效性）。要抓**语法形状**：`grep -rn ')fff' --include=*.vue` 这类「括号后面还粘着东西」的形态一扫就现形；更稳的是渲染后看 computed style 是否为空。

**修法**：`var(--og-gray-0, var(--og-gray-0, #ffffff)fff)` → `var(--og-gray-0, #ffffff)`。同时把「嵌套同名的 `var(--x, var(--x, #fff))`」一并清掉——那是替换脚本叠加执行留下的痕迹，虽然合法但会掩盖真问题。

## 同一 bug 的第二个变体：`#ffffff)beb`（本轮又中一次）

修完上面 9 个文件后，热门页「约几分钟」标仍然**没有琥珀底色**，原因是另一处拼接残留：

```css
background: var(--og-warning-bg, var(--og-warning-bg, var(--og-gray-0, #ffffff)beb));
```

`#ffffff)beb` 不是合法颜色，整条声明被丢弃。**同一个正则 bug 会在不同色值上留不同尾巴**（`#fff` 留 `fff`、`#fffbeb` 留 `beb`），所以「扫 `)fff`」只能抓到一半。

**正确扫法**：扫「`var(` 括号嵌套结构异常」，而不是扫某个具体尾巴——例如按 `var(` 出现次数与 `)` 配平、或直接找「`)` 后面紧跟非空白非 `;` 非 `,` 字符」的形态。**一次修完必须全仓复扫，不能只修报障那一处。**

## 更上层的教训：令牌化 ≠ 布局重构

这一轮的「UI 改造」实际只做了三件事：抽令牌、把硬编码色值换成变量、圆角统一。**布局结构一行没动**——所以用户看到的是「换了绿色，但还是那个排版」。同时交付报告里写的是「15 个页面接令牌、462 处 `var(--og-*)`」，听起来像改造完成，实际是覆盖率指标冒充了效果指标。

**How to apply**：UI 类交付的验收口径必须是**渲染结果**（截图/真机看），不是「替换了多少处」。报告里写清「改的是颜色与圆角，布局未动」比写「462 处令牌化」诚实；真要动布局就得改模板结构（分区、层级、留白），那和换色是两件事。

相关：[[pitfall-uniapp-h5-no-tabbar-switchtab-silent-fail]]、[[pitfall-uniapp-component-swap-dom-and-state]]、[[feedback-ui-match-existing]]、[[project-openget-multiclient]]。
