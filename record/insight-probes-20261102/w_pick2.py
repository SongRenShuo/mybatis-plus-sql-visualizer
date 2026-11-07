import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.press("down")
time.sleep(0.2)
pyautogui.press("enter")
time.sleep(6.0)
pyautogui.screenshot().save(os.path.join(TMP,"w_pick2.png"))
print("DONE")
