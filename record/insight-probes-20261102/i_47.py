import os,time
import pyautogui
pyautogui.FAILSAFE=False
pyautogui.click(1500,475)
time.sleep(1.5)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","i_47.png"))
print("DONE")
