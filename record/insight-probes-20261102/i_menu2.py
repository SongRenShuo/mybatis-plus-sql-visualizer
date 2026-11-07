import os,time
import pyautogui
pyautogui.FAILSAFE=False
pyautogui.click(1483,233)
time.sleep(1.2)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","i_menu2.png"))
print("DONE")
