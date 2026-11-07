import time
import walk_util as W
import robot
import pyautogui

W.front()
time.sleep(0.3)
xp = "//div[contains(@class,'SqlLogPanel')]"
comps = [c for c in robot.find_components(xp) if c.get("width", 0) > 0]
W.log("SqlLogPanel hits:", len(comps))
x, y = robot.component_screen_center(xp, comp=comps[0])
W.log("panel center:", x, y)
W.assert_front()
pyautogui.click(x, y - 100)     # 列表区（面板上部）内点一下拿焦点
time.sleep(0.6)
pyautogui.press("home")
time.sleep(0.3)
pyautogui.press("down")
time.sleep(0.3)
pyautogui.press("down")
time.sleep(0.5)
W.shot("w11-row-selected")
pyautogui.press("enter")
time.sleep(3.0)
W.shot("w11-enter")
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
