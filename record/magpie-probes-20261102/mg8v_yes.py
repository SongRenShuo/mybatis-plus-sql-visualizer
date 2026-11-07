import os,time
import pyautogui
pyautogui.FAILSAFE=False
pyautogui.click(998,557)
time.sleep(1.5)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","mg8v_scheduled.png"))
print("DONE")
