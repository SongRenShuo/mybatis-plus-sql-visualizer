import time
import walk_util as W

W.front()
time.sleep(0.3)
for i in range(2):
    import pyautogui
    pyautogui.press("esc")
    time.sleep(0.25)
print("shot:", W.shot("w01-clean"))
for key in ("SQL 日志", "SQL日志", "SQL Visualizer", "数据库", "更多", "commit"):
    hits = W.texts(key, limit=8)
    print("== %s (%d) ==" % (key, len(hits)))
    for h in hits:
        print("   ", h)
