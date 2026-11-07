import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(750,543)
time.sleep(5.0)
pyautogui.screenshot().save(os.path.join(TMP,"w_pick.png"))
print("DONE")
