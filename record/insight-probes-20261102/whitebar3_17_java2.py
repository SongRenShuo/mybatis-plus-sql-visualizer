import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import whitebar3_win as WIN
import pyautogui

pid = W.sandbox_pid()
# 菜单仍开着：直接点菜单项（坐标由 w3-24 帧量得）
pyautogui.click(810, 972)
time.sleep(5.0)
W.shot("w3-27-java-after-click")
W.log("windows", [(t, r) for _, _, t, r in WIN.by_pid(pid)])
for lbl in ("选择实体", "MP Java", "复制", "未找到", "转换失败"):
    W.log("TXT", lbl, W.texts(lbl, 6))
W.log("DONE")
