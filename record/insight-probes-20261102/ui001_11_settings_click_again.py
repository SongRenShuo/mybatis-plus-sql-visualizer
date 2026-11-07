import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui001_util as W
import robot
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
# 首击可能只被「点击聚焦」吞掉，连点两次（skill 双判据护栏）
pyautogui.click(574, 571)
time.sleep(0.8)
pyautogui.click(574, 571)
time.sleep(3.0)
W.shot("ui11-ui-actions")
for label in ("Gutter", "Source groups", "全部", "无", "已启用", "Show gutter"):
    W.log("TXT", label, W.texts(label, 6))
W.log("DONE")
