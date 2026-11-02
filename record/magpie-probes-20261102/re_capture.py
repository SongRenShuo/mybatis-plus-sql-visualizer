import os,time
import pyautogui
pyautogui.FAILSAFE=False
pyautogui.click(1443,128)
time.sleep(1.0)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","re_capture.png"))
print("DONE")
