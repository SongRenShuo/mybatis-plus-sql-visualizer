---
name: pitfall-probe-ime-and-widget-coords-262
description: 262 沙盒 GUI 探针四坑——系统 IME 中文态吞 ASCII 键入、状态栏 widget 真实 y、弹层只认键盘不认点击、设置页滚动债依旧
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1caa1cd6-ecff-4769-98f4-48bfeeca1eb0
---

2026-11-04 dev vs master 262 沙盒验收（[[dev-vs-master-262-acceptance-20261104]]）实测四坑：

1. **系统 IME 处于中文态时，pyautogui.typewrite 的 ASCII 全进组合缓冲**：u046 探针 `type`/`find` 打的字（如 TableMeta）只是 IME 候选组合，Enter 只提交组合不开文件；设置页 textarea 打的字根本没落地（报「说明必填」假失败）。对策：一律 `Set-Clipboard` + `ctrl+v` 粘贴打字；或 Enter 提交组合后再 Enter 触发。
2. **状态栏 widget 坐标**：1920×1080 下 IDE 最大化（rect 显示 bottom=1040）时状态栏实际在 **y≈1017**，y≥1046 已是任务栏（点 1668,1046 会把用户微信点开——教训：先从全帧 crop 放大量出精确位置再点）。
3. **弹层交互**：JBPopup/LightweightPopup 的菜单项合成点击 no-op，但**键盘 Down×(n−1)+Enter 可用**（「更多」菜单靠这个点成了切换数据源）；GutterGroupMultiSelect 自绘多选面板点击反而正常——自绘不等于不可达，要逐个控件试。
4. **设置对话框内容区滚轮/PageDown/滚动条点击三路全 no-op**（262 依旧），低处字段只能 XML 兜底或真机人工。

**Why**: 这些坑各消耗了 5~15 分钟盲试；根因（IME/DPI/弹层焦点）不看现场猜不出来。
**How to apply**: 262 沙盒跑 GUI 探针前：先 `Set-Clipboard` 粘贴路线替代一切打字；坐标必须从当帧 crop 量，禁止沿用上一轮坐标；弹层先试键盘导航。
