import os,time
import pyautogui,pyperclip
pyautogui.FAILSAFE=False
pyautogui.click(1055,362)
time.sleep(0.4)
pyperclip.copy("t_order")
pyautogui.hotkey("ctrl","v")
time.sleep(0.3)
pyautogui.click(1456,362)
time.sleep(1.5)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","i_45scan.png"))
print("DONE")
