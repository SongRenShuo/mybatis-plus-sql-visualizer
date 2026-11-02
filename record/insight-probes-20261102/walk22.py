import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(1488, 234)
time.sleep(1.0)
for _ in range(4):
    pyautogui.press("down")
    time.sleep(0.2)
pyautogui.press("enter")
time.sleep(4.0)
W.shot("w22-run-analysis")
for key in ("工作负载", "启用所有会话的分析", "刷新快照", "请求基线", "隐私", "事务"):
    hits = W.texts(key, 6)
    W.log("== %s (%d)" % (key, len(hits)))
    for h in hits:
        W.log("   ", h)
