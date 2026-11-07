import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(1500,678)
time.sleep(2.0)
pyautogui.screenshot().save(os.path.join(TMP,"w10_dml.png"))
print("DONE")
