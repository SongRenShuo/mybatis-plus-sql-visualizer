import os,time
import pyautogui
pyautogui.FAILSAFE=False
pyautogui.click(552,544)
time.sleep(1.2)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","b2_about.png"))
print("DONE")
