---
name: pitfall-static-asset-extension-vs-bytes
description: 站点静态资源的扩展名可能与实际字节不符（group-qr.png 里是 JPEG）——按扩展名返回的
  Content-Type 与内容不符，配 nosniff 会被按 MIME 判的客户端拒渲染；修法＝加一份字节相同的正确扩展名
  副本、旧文件保留，且必须先部署站点再改引用方（否则 404）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_01762413-f08e-493e-9eb7-1e2f02818dff
---

# 静态资源扩展名 ≠ 实际字节：Content-Type 会跟着扩展名错（20261103）

## 症状

文档站 `https://sql-visualizer.jvtools.cn/group-qr.png` 返回 `200 image/png`，但**文件内容其实是 JPEG**：magic `FF D8 FF E0 00 10 4A 46 49 46`（JFIF），15663 字节；同目录 `wechat-qr.png` 是真 PNG（`89 50 4E 47`）。响应头还带 `x-content-type-options: nosniff`。

Chrome 照常渲染（所以线上一直没人发现），但按 MIME 判类型再决定是否解码的客户端/代理会拒渲染。

## 判据

**只看前 8 字节，别信扩展名，也别信 Content-Type**——这两者在故障态下是互相印证的同一个错误。一行判定：

```bash
python -c "b=open(p,'rb').read(8); print(b.hex())"   # ffd8ffe0… = JPEG；89504e47… = PNG
curl -sI https://…/x.png | grep -i content-type      # 只会告诉你服务器按扩展名猜的结果
```

## 修法

1. 复制一份字节相同的 `group-qr.jpg`（内容不动，只是扩展名对了 → Content-Type 与内容一致）；
2. 引用方改指 `.jpg`；
3. **原 `.png` 不删**——已发布的插件版本、历史文档、别人贴过的链接仍指向它，删了就是死链。

## 顺序风险（这条最容易踩）

换 URL 是「先有产物、再改引用」类动作：**站点没部署，新 URL 就是 404**。本轮实测 `.jpg` 404 / `.png` 200，所以插件描述、README、release 工作流、站点 about 页里的 `.jpg` 引用**必须等文档站重新部署后才会真的可用**——发版前不部署，交流群码会变破图。与 docs 站「先放图 → 再改代码 → 再部署」是同一条纪律。

## 本轮落地面

- 站点：`mybatis-plus-sql-visualizer-docs` 的 `public/group-qr.jpg`（新增）+ about 中英文页改引 `.jpg`（提交 `2eeb67a`，未部署）；
- 插件三棵树（master `5bc068b8` / dev `43891d81` / feature/kotlin-to-java `23555b7e`）：plugin.xml 描述、README、`.github/workflows/build-plugin.yml` 三处改 `.jpg`，各树 `docs/images/group-qr.jpg` 同步一份；原 `.png` 全部保留；
- 守卫：`PluginXmlStructureGuardTest` 的二维码用例正则放宽为 `qr\.(?:png|jpg)`（写死 `.png` 会因换扩展名判红）。

相关 [[pitfall-marketplace-description-css-overrides-img-width]]（同一轮的另一半：并排二维码一大一小）、[[reference-docs-deploy-cloudflare-token]]（部署方式）、[[pitfall-gitee-hotlink-302]]（外链图的另一类坑）。
