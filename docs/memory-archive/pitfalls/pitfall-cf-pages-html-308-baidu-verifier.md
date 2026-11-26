---
name: pitfall-cf-pages-html-308-baidu-verifier
description: CF Pages HTML 规范化把 /xxx.html 308 到 /xxx，百度验证器不跟跳转报「未知原因:308」，中间件精确放行修复
metadata:
  node_type: memory
  type: project
  originSessionId: sess_98e304f3-7fad-448e-b06c-633c2113957e
---

CF Pages 内建 HTML 规范化：静态资产 `foo.html` 存在时，`/foo.html` 自动 **308 → `/foo`**（站点用 VitePress cleanUrls，`_redirects` 不存在、`functions/_middleware.js` 只管旧域名跳转，308 来自平台层）。**百度站长平台文件验证器不跟 308**，报「未知原因:308」验证失败；GSC/Bing 验证器跟跳转所以能过。

**Why**：20261114 百度验证 `baidu_verify_codeva-*.html` 两轮失败才定位到平台层，与自有代码无关。

**How to apply**：`functions/_middleware.js` 对验证文件路径（`baidu_verify_*|googled*|BingSiteAuth.html`）按**无后缀**路径 `context.env.ASSETS.fetch()` 取资产原文、以 200 直返（中间件先于平台规范化执行）；新验证文件自动被同一正则覆盖。百度验证通过后，sitemap 提交页**新验证站今日配额=0 条**（输入框 disabled），需站点属性填主体备案号提额；绕道=普通收录 API 推送（`data.zz.baidu.com/urls?site=…&token=…`，token 在普通收录页可见，新站日配额实测 10 条）。关联 [[project-seo-webmaster-platforms-20261114]]。
