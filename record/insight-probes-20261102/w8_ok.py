import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.press("enter")
time.sleep(0.5)
pyautogui.press("enter")
time.sleep(1.5)
pyautogui.screenshot().save(os.path.join(TMP,"w8_ok.png"))
print("DONE")
