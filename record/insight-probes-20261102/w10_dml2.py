import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.press("end")
time.sleep(0.3)
pyautogui.press("enter")
time.sleep(2.0)
pyautogui.screenshot().save(os.path.join(TMP,"w10_dml2.png"))
print("DONE")
