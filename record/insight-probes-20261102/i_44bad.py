import os,time
import pyautogui,pyperclip
pyautogui.FAILSAFE=False
pyautogui.click(960,410)
time.sleep(0.4)
pyperclip.copy("not-json {{{")
pyautogui.hotkey("ctrl","v")
time.sleep(0.4)
pyautogui.click(452,480)
time.sleep(1.2)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","i_44bad.png"))
print("DONE")
