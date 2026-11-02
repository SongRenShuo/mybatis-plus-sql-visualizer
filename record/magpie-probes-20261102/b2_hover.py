import os,time
import pyautogui
pyautogui.FAILSAFE=False
pyautogui.moveTo(958,405)
time.sleep(2.0)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","b2_tooltip_none.png"))
print("DONE")
