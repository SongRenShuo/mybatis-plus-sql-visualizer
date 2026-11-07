import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(1050,385)
time.sleep(1.8)
pyautogui.screenshot().save(os.path.join(TMP,"d_drv2.png"))
print("DONE")
