import os,time
import pyautogui
pyautogui.FAILSAFE=False
pyautogui.click(1650,207)
time.sleep(0.4)
pyautogui.hotkey("ctrl","c")
time.sleep(0.8)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","re_copied.png"))
print("DONE")
