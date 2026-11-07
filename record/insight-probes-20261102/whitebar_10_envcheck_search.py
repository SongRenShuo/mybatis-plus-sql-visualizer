import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui
import pyperclip

W.front()
time.sleep(0.3)
W.assert_front()
# 清空设置搜索框并搜「环境体检」（插件自定义行已注册 SearchableField）
pyautogui.click(583, 205)
time.sleep(0.5)
pyautogui.hotkey("ctrl", "a")
time.sleep(0.3)
pyperclip.copy("环境体检")
pyautogui.hotkey("ctrl", "v")
time.sleep(2.5)
W.shot("wb16-search-envcheck")
for label in ("环境体检", "生成体检报告", "Nothing to show"):
    W.log("TXT", label, W.texts(label, 5))
W.log("DONE")
