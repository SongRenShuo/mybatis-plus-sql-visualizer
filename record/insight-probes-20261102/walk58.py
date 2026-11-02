import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(1496, 840)   # 取消关闭预览
time.sleep(2.0)
W.assert_front()
pyautogui.click(900, 420)    # 编辑器
time.sleep(0.5)
pyautogui.hotkey("ctrl", "end")
time.sleep(0.6)
W.shot("w58-editor-end")
pyautogui.press("up")
pyautogui.press("up")
pyautogui.press("up")
time.sleep(0.4)
W.shot("w58-editor-cursor")
