import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
pid, hwnd, title, rect = W.ide_window()
# 提示气球按钮行：从本帧量得（y 与窗口底无关，气球贴工具窗下半部）
W.assert_front()
pyautogui.click(1455, 602)   # 开启快速预览
time.sleep(1.5)
W.shot("w16-quickpreview-on")
