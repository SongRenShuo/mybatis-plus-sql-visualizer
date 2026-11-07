import time
import walk_util as W
import pyautogui

W.front()
time.sleep(1.0)
W.shot("w08-log-list")
# 诊断行与控制台行
for line in W.texts("控制台", 6):
    W.log("CONSOLE>", line)
for line in W.texts("已挂载", 6):
    W.log("MOUNT>", line)
# 列表行文本
for line in W.texts("UserServiceImpl", 6):
    W.log("SRC>", line)
for line in W.texts("[MySQL]", 6):
    W.log("DIALECT>", line)
