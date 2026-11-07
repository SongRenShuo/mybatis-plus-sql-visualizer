import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(700,383)
time.sleep(0.5)
pyautogui.hotkey("alt","shift","q")
time.sleep(2.5)
pyautogui.screenshot().save(os.path.join(TMP,"w9_open.png"))
print("DONE")
