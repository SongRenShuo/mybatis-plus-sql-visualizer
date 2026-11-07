import time
import walk_util as W
import pyautogui
import robot

W.front()
time.sleep(0.3)
pyautogui.press("esc")
time.sleep(0.4)
xp = "(//div[contains(@class,'SquareStripeButton') and contains(@accessiblename,'SQL')])[1]"
print("click:", robot.click_component(xp))
time.sleep(2.0)
W.shot("w04-toolwindow")
for line in W.texts("SQL Visualizer", 10):
    W.log("TW>", line)
for line in W.texts("已挂载", 5):
    W.log("CAP>", line)
for line in W.texts("捕捉", 5):
    W.log("CAPBTN>", line)
