import time
import walk_util as W
import pyautogui
import pyperclip

BEFORE = ('{"schema":"sql-visualizer-schema-v1","databaseContext":"mp_demo@localhost","version":"v1",'
          '"dialect":"MYSQL","tables":[{"nameParts":["t_order"],"columns":['
          '{"name":"id","typeName":"BIGINT","nullable":false},'
          '{"name":"amount","typeName":"DECIMAL","nullable":true}]}]}')
AFTER = ('{"schema":"sql-visualizer-schema-v1","databaseContext":"mp_demo@localhost","version":"v2",'
         '"dialect":"MYSQL","tables":[{"nameParts":["t_order"],"columns":['
         '{"name":"id","typeName":"BIGINT","nullable":false},'
         '{"name":"amount","typeName":"DECIMAL","nullable":false},'
         '{"name":"remark","typeName":"VARCHAR","nullable":true}]}]}')

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(670, 370)
time.sleep(0.3)
pyperclip.copy(BEFORE)
pyautogui.hotkey("ctrl", "v")
time.sleep(0.8)
pyautogui.click(1275, 370)
time.sleep(0.3)
pyperclip.copy(AFTER)
pyautogui.hotkey("ctrl", "v")
time.sleep(0.8)
W.shot("w35-schema-pasted")
pyautogui.click(1157, 473)
time.sleep(3.0)
W.shot("w35-schema-compared")
