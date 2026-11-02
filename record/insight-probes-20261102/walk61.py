import time
import walk_util as W
import pyautogui
import pyperclip

PATH = r"F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer-demo\walk-fixture-candidate.sql"

W.front()
time.sleep(0.3)
pyperclip.copy(PATH)
pyautogui.hotkey("ctrl", "a")
time.sleep(0.3)
pyautogui.hotkey("ctrl", "v")
time.sleep(1.0)
W.shot("w61-path-typed")
pyautogui.press("enter")
time.sleep(3.0)
W.shot("w61-opened")
W.log("title:", W.ide_window()[2])
