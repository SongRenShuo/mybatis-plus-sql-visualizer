import os,time
import pyautogui
pyautogui.FAILSAFE=False
pyautogui.click(1483,233)
time.sleep(1.0)
for i in range(3):
    pyautogui.press("down")
    time.sleep(0.12)
pyautogui.press("enter")
time.sleep(1.8)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","i_42.png"))
print("DONE")
