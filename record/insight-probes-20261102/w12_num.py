import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui,pyperclip
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(567,660)
time.sleep(0.4)
pyperclip.copy("1")
pyautogui.hotkey("ctrl","v")
time.sleep(0.4)
pyautogui.click(1483,236)
time.sleep(1.0)
pyautogui.press("end")
time.sleep(0.3)
pyautogui.press("enter")
time.sleep(3.0)
pyautogui.screenshot().save(os.path.join(TMP,"w12_num.png"))
print("DONE")
