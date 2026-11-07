import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui,pyperclip
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(1060,384)
time.sleep(1.5)
pyautogui.screenshot().save(os.path.join(TMP,"d_drv.png"))
print("DONE")
