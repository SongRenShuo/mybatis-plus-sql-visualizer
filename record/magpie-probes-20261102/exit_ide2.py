import os,time
import pyautogui
pyautogui.FAILSAFE=False
pyautogui.click(1199,527)
time.sleep(2.0)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","exit3.png"))
pyautogui.press("enter")
time.sleep(3.0)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","exit4.png"))
print("DONE")
