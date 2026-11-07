import os,time
import pyautogui
pyautogui.FAILSAFE=False
pyautogui.click(700,311)
time.sleep(1.5)
pyautogui.click(1266,128)
time.sleep(1.0)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","re_tw2.png"))
print("DONE")
