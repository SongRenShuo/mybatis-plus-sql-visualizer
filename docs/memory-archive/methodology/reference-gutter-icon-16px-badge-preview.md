---
name: reference-gutter-icon-16px-badge-preview
description: 给 gutter 图标加角标前先做 16px 真尺寸预览的可行路径——Chrome headless（Edge headless 本机报错）+ PIL NEAREST 放大核对；四种角标在 16px 下的可见度排序（右下角青方块最优）
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_7933450a-6d57-4756-a185-caac0d14c652
---

2026-11-24 做 Mapper 跳转图标角标时用的非 GUI 预览法（不需要 runIde）：

1. 用 Python 读仓内 SVG（与待对比的对方 SVG），在 `</svg>` 前插入角标元素生成变体，写 HTML（深色 `#1E1E1E` / gutter 背景 `#2B2B2B`）。
2. 渲染：`chrome.exe --headless=new --disable-gpu --no-first-run --no-default-browser-check --user-data-dir=<temp> --screenshot=<png> --window-size=<w,h> file:///<html>`。**本机 Edge headless 直接 exit 2 无输出，Chrome 可用**。
3. 16px 真尺寸校验：页面里只放 `<img width=16 height=16>`，截图后用 PIL `Image.NEAREST` 放大 5~8 倍看像素——矢量放大到 64px 会误导（角标看着清楚，实际 16px 糊没）。

四种候选在 16px 下的实测（深色主题、与 MPJ 原图并排）：

- A 右下角青方块（`rect x=10.4 y=10.4 w=4.8 h=4.8 rx=1.2 fill=#00CBCC stroke=#FFF`）：清楚、不压主体，**推荐**。
- B 右上角青圆点：清楚但压住图标主体（鸟头）。
- C 右下角橙方块：最醒目，但橙色与 CRUD 颜色语义冲突。
- D 整体青色细环：64px 好看，16px 几乎不可见且贴边有裁剪风险，弃。

用法：任何 gutter/16px 视觉改动先出这张预览图给用户拍板，再改仓内资源（用户 2026-11-24 要求「先给我看看效果」）。

**结局（2026-11-24，同会话后段）**：四种角标全部被否（原话「这些图标不合适」）；第二轮又出 E1 蓝色主图 / E2 青色主图 / F 圆角底牌 / G 双向箭头四版——16px 下 E1 与 G 可辨（E1 保留鸟形、G 语义化），F 底牌占满格子偏重、E2 青色与 XML 侧撞色；**仍未被采纳**，最终用户决定「图标先不换」。结论：预览只能筛掉「16px 下看不清」的方案，不能保证方向被接受；拿到明确选型前不要动仓内资源。

关联 [[reference-mapper-jump-icons-identical-to-mpj]]、[[feedback-visual-change-preview-first]]。