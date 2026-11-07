import os,time
import pyautogui
pyautogui.FAILSAFE=False
pyautogui.click(1483,233)
time.sleep(1.0)
pyautogui.press("down")
time.sleep(0.15)
pyautogui.press("enter")
time.sleep(1.8)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","i_41.png"))
print("DONE")
