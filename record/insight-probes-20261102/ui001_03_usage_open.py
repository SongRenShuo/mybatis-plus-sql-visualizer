import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui001_util as W
import robot
import pyautogui
import nav
import pyperclip

W.front()
time.sleep(0.5)
W.assert_front()
# 打开 demo 的 OrderMapper.xml（走 Ctrl+Shift+N，避免依赖上次会话恢复）
pyautogui.hotkey("ctrl", "shift", "n")
time.sleep(1.2)
pyperclip.copy("OrderMapper.xml")
pyautogui.hotkey("ctrl", "v")
time.sleep(1.0)
pyautogui.press("enter")
time.sleep(3.0)
W.shot("ui03-file-open")
nav.goto_text("selectOrdersByCondition")
time.sleep(0.8)
W.assert_front()
pyautogui.hotkey("alt", "shift", "q")
time.sleep(4.0)
W.shot("ui03-preview")
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
# 更多按钮：按 accessible name 唯一命中
xp_more = "//div[contains(@accessiblename,'更多')]"
comps = [c for c in robot.find_components(xp_more) if c.get("width", 0) > 0]
W.log("more candidates", [(c.get("width"), c.get("height")) for c in comps])
if not comps:
    W.log("no more button; abort")
    raise SystemExit(1)
comps.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
x, y = robot.component_screen_center(xp_more, comp=comps[0])
W.log("click more at", x, y)
pyautogui.click(x, y)
time.sleep(1.2)
items = robot.menu_items()
W.log("menu items", len(items))
for i, it in enumerate(items):
    W.log("  item", i, it)
# 对象使用与影响 = moreActions 第 7 项（0 基索引 6）
if len(items) > 6:
    rect = robot.click_menu_item(6)
    W.log("clicked item6", rect)
else:
    W.log("menu too short; keyboard fallback")
    for _ in range(6):
        pyautogui.press("down"); time.sleep(0.2)
    pyautogui.press("enter")
time.sleep(4.0)
W.shot("ui03-usage-panel")
# 诊断：面板与四个按钮的屏幕矩形
for label in ("反查对象引用", "打开选中引用", "取消", "关闭分析", "扫描所选范围"):
    xp = "//div[contains(@accessiblename,'%s')]" % label
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    W.log("BTN", label, "n=%d" % len(hits))
    for c in hits[:3]:
        try:
            sx, sy = robot.component_screen_center(xp, comp=c)
            W.log("   screen", sx, sy, c.get("width"), c.get("height"))
        except Exception as e:
            W.log("   coord err", e)
# 面板整体与状态区
for cls in ("InsightDetailPanel",):
    xp = "//div[contains(@class,'%s')]" % cls
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0]
    W.log("PANEL", cls, "n=%d" % len(hits))
    for c in hits[:3]:
        try:
            sx, sy = robot.component_screen_center(xp, comp=c)
            W.log("   screen", sx, sy, c.get("width"), c.get("height"))
        except Exception as e:
            W.log("   coord err", e)
for t in ("仅显式扫描当前文件", "显式数据库上下文", "表名分段用/分隔", "列（可选）"):
    hits = W.texts(t, 3)
    W.log("TXT", t, len(hits), hits)
W.log("DONE")
