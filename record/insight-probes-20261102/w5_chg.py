import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(800,388)
time.sleep(1.2)
pyautogui.screenshot().save(os.path.join(TMP,"w5_a.png"))
print("DONE")
