---
name: project-marketplace-screenshot-watermark-cleanup
description: Marketplace 上架页 10 张截图曾全带 Start Free Trial 水印（用户已核）；本地源即 docs/images/demo-*.gif（MD5 一致）已随 16 个孤儿图一并删除（2213b8c7）；**用户 20261027 已上传无水印版，10/10 MD5 与本地包一致、旧 URL 全换、水印扫描归零**；文档站 install-marketplace.png 与推文配图同步换新
metadata:
  node_type: memory
  type: project
  originSessionId: session_e78fc4db18e9fe0ea0e501cef4f21a12
---

# Marketplace 截图水印事故 + 仓库 demo 图清理（2026-10-27，仓库侧已闭环）

## 事实链（已实测，可直接复用）

1. 用户给的两张 Marketplace 截图里，`Start Free Trial` 落在**详情页内嵌预览小窗的标题栏右上角**（外层真实 IDE 窗口工具栏是干净的）。
2. Marketplace 线上第 1 张截图（API `plugins.jetbrains.com/api/plugins/33633` 的 `screens` 数组 → `/files/33633/screenshot_<uuid>`，**下载必须带 `-A "Mozilla/5.0"` 且 `-L`**，否则拿到 0 字节）：水印在这张图里落在**主窗口工具栏**上，位置与用户那两张不同。
3. **用户自己看完了全部 10 张，确认「全部都有水印」**——不用再逐张验。
4. **`docs/images/demo-01-wrapper-preview.gif` 与线上第 1 张是同一个文件**：MD5 均 `64159b20a5d5bb727b7bd29392f130aa`、1636719 字节，即那 10 个 demo 图就是当年上传给 Marketplace 的源。
5. **水印与插件装没装无关**：用户问「卸载再截图行不行」，答案是不行。文档站已入库的 `install-marketplace.png` 右侧预览区右上角同样有水印，而真实 IDE 外层窗口是干净的 ⇒ 水印来自**内嵌预览窗口本身**，卸载重装不会消失，只能换许可生效的环境截（根因见 [[pitfall-sandbox-license-needs-ideakey]]）。

## 仓库侧已闭环（`2213b8c7`）

删掉 16 个孤儿文件、共约 12MB：10 个 `docs/images/demo-0N-*.gif` + 6 个 `docs/images/screenshot-0N-*.png`。删前二次 grep 确认全仓零嵌入引用（只在历史计划/调研/变更记录里被提及）。

同步更新三处：
- `docs/文档命名规范-说明文档.md`：`demo-0N` / `screenshot-0N` 两条命名约定**作废**，改为「功能演示图不入库，统一引用 `https://sql-visualizer.jvtools.cn/demo/{name}.gif`」；顺带修掉两处指向已删文件的引用示例。
- `docs/MEMORY.md` 图片章节：登记删除原因与新口径。
- `.gitignore`：忽略 `.tmp-marketplace-upload/`。

删后 `docs/images/` 只剩 `wechat-qr.png`、`group-qr.png`、`cool-request-icon.svg`、`design/` 两张。

`README.md` 正文 8 图**早已**指向官网（30 个官网 URL 实测全 200），不需要改——用户记忆里的「现在是尽量引用官网」是对的。

## 无水印上传包（已备好，待用户上传）

`.tmp-marketplace-upload/`（已 gitignore，不入库）内 10 张 GIF 共 6.5MB，源取文档站 `public/demo/`——**那一批是无水印重录版**，抽帧核对过主窗口工具栏没有 `Start Free Trial`。

命名沿用用户旧风格但改 ASCII：`screenshot-0N-{slug}.gif`。

**顺序按「读者第一次接触时该先理解什么」排，不是按功能清单**：
01 静态预览（`perf-advice`，最核心最好懂，且顺带修掉旧 01「名叫 wrapper-preview 却拍的是 XML 预览」这个 SERIOUS）→ 02 运行期捕获 → 03 跳回源码（02+03 连成「写代码时 / 跑起来后」完整叙事）→ 04 EXPLAIN → 05 无界写警示（这两张回答「用起来安全吗」）→ 06 SQL 转 Java → 07 CRUD → 08 XML → 09 JPA（广度证明放后段）→ 10 MCP（压轴、最新、有话题性；因需 IDEA 2025.2+ 不宜太前）。

同目录 `README-上传说明.md` 写了每张画面要点、排序理由、三条上传注意：**Marketplace 支持 GIF 直接传**（用户纠正过我「只收静态图」的错误假设，别再转 PNG）；最大 2039KB 的 10 号若被拒先传其余；**先传新图再删旧图**，避免详情页空档。

## 文档站与推文侧已闭环（20261027）

用户上传后重截了正确的 `Settings → Plugins` 图（Marketplace 页签 + 安装按钮 + **无水印**，1286×807），我裁两版同时更新两处：

- **文档站** `fd17704`：`public/demo/install-marketplace.png` 换成 1286×730（切掉底部按钮行与内嵌预览区）；`guide/getting-started.md` + `en/guide/getting-started.md` 的 alt 去掉写死的「下载量 434」（实际已 546，且这个数字每次截图都会变）。已推 Gitee + GitHub，已部署，线上实测：页面 200、新 alt 在位、旧文案 0 命中、图片 URL 200 且尺寸与本地一致。
- **推文** `6e9076bb`：第十节插入 1286×224 横条版；附录 A2 配图表补行；三个基于旧图裁的版式与 HTML 复刻件全部删除。
- 部署命令的 token 来源见 [[reference-docs-deploy-cloudflare-token]]。

## 全部闭环（用户 20261027 已上传并无水印验证通过）

用户上传后，我**从 Marketplace API 把 10 张新图全下回来逐张 MD5 比对**（新 URL 全换，旧的 10 个一个不留；`10/10` 与 `.tmp-marketplace-upload/` 本地包一致；顺序也符合既定设计）。**水印扫描归零**：线上 10 张右上角工具栏绿色采样最高 21，带水印那批是 82~107；目视抽查 02/10 两张工具栏只有运行/调试/搜索/设置等正常图标。展示尺寸 1280×920 与文档站 `public/demo/` 源文件一致，无二次压缩变形。

`README-上传说明.md` 与 10 张包按用户要求**保留**在 `.tmp-marketplace-upload/`（已 gitignore），作为换图时的存档参考。

三处（Marketplace 详情页 / 文档站 getting-started / 推文配图）现已统一到无水印版。**无遗留动作。**

关联：[[project-wechat-article-20261026]]、[[feedback-marketing-copy-style]]、[[pitfall-sandbox-license-needs-ideakey]]、[[reference-docs-deploy-cloudflare-token]]
