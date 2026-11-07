import time
import walk_util as W
import pyautogui
import pyperclip

W.front()
time.sleep(0.3)
pyautogui.press("esc")            # 关掉选择要预览的 SQL 对话框
time.sleep(0.8)
W.assert_front()
pyautogui.hotkey("ctrl", "w")     # 关闭 OrderMapper.xml 页签（强制回盘后重开）
time.sleep(1.0)
pyautogui.hotkey("ctrl", "shift", "n")
time.sleep(1.2)
pyperclip.copy("OrderMapper.xml")
pyautogui.hotkey("ctrl", "v")
time.sleep(2.0)
pyautogui.press("enter")
time.sleep(2.5)
W.shot("w49-reopened")
W.log("title:", W.ide_window()[2])
