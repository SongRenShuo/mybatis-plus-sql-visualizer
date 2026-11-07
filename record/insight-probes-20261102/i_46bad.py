import os,time
import pyautogui,pyperclip
pyautogui.FAILSAFE=False
pyautogui.click(600,400)
time.sleep(0.4)
pyperclip.copy("garbage")
pyautogui.hotkey("ctrl","v")
time.sleep(0.3)
pyautogui.click(1160,490)
time.sleep(1.2)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","i_46bad.png"))
print("DONE")
