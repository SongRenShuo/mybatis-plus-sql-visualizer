import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(997,574)
time.sleep(4.0)
pyautogui.screenshot().save(os.path.join(TMP,"w_exec3.png"))
print("DONE")
