import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(1198,833)
time.sleep(2.5)
pyautogui.screenshot().save(os.path.join(TMP,"w9_e1.png"))
pyautogui.press("enter")
time.sleep(5.0)
pyautogui.screenshot().save(os.path.join(TMP,"w9_e2.png"))
print("DONE")
