import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui001_util as W
import robot
import pyautogui
import pyperclip

W.front()
time.sleep(0.3)
W.assert_front()
# 设置对话框的搜索框（robot 里通常暴露为 JTextField / SearchTextField）
px = W.window_rect(title="Settings", pid=W.sandbox_pid(), min_size=(800, 500))
W.log("settings rect", px)
if not px:
    raise SystemExit(1)
# 搜索框在标题栏下第一行：点它（几何来自设置窗矩形，非目测）
sx = px[0] + 120
sy = px[1] + 60
pyautogui.click(sx, sy)
time.sleep(0.5)
pyperclip.copy("gutter")
pyautogui.hotkey("ctrl", "v")
time.sleep(2.5)
W.shot("ui09-search-gutter")
for label in ("gutter", "Gutter", "来源", "分组", "界面与操作"):
    W.log("TXT", label, W.texts(label, 6))
# 结果列表：Down + Enter 命中第一条
pyautogui.press("down")
time.sleep(0.5)
pyautogui.press("enter")
time.sleep(2.5)
W.shot("ui09-after-enter")
for label in ("Gutter 图标来源分组", "全部", "无", "已启用"):
    W.log("AFTER", label, W.texts(label, 6))
W.log("DONE")
