import os,time
import pyautogui
pyautogui.FAILSAFE=False
for i in range(17):
    pyautogui.press("down")
    time.sleep(0.08)
pyautogui.press("enter")
time.sleep(1.8)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","i_49c.png"))
print("DONE")
