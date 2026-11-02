import time
import walk_util as W
import pyautogui
import pyperclip

SAME_VERSION = ('{"schema":"sql-visualizer-schema-v1","databaseContext":"mp_demo@localhost","version":"v2",'
                '"dialect":"MYSQL","tables":[{"nameParts":["t_order"],"columns":['
                '{"name":"id","typeName":"BIGINT","nullable":false}]}]}')

W.front()
time.sleep(0.3)
W.assert_front()
# 6.1 版本相同应拒绝
pyautogui.click(670, 370)
time.sleep(0.3)
pyautogui.hotkey("ctrl", "a")
pyperclip.copy(SAME_VERSION)
pyautogui.hotkey("ctrl", "v")
time.sleep(0.8)
pyautogui.click(1157, 473)
time.sleep(2.5)
W.shot("w36-schema-same-version")
