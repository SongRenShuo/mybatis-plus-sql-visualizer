import time
import walk_util as W
import pyautogui
import pyperclip

W.front()
time.sleep(0.5)
W.assert_front()
pyautogui.hotkey("ctrl", "alt", "s")
time.sleep(4.0)
pyautogui.click(575, 210)      # 搜索框
time.sleep(0.4)
pyperclip.copy("SQL Visualizer")
pyautogui.hotkey("ctrl", "v")
time.sleep(3.0)
# 点左树 SQL Visualizer 节点（crop 显示在 y≈373 → abs 373？此处直接用键盘：搜索后焦点在结果树）
pyautogui.click(585, 372)
time.sleep(1.5)
pyautogui.press("down")        # 进入第一个子页 Ui and Actions
time.sleep(2.5)
W.shot("w84-ui-page")
