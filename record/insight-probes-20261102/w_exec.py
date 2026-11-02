import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(1198,833)
time.sleep(3.0)
pyautogui.screenshot().save(os.path.join(TMP,"w_exec.png"))
print("DONE")
