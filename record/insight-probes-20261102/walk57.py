import time
import walk_util as W
import pyautogui
import pyperclip

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(1277, 840)   # 复制SQL
time.sleep(1.2)
txt = pyperclip.paste()
W.log("CLIP<<<" + txt.replace("\n", "\\n") + ">>>")
