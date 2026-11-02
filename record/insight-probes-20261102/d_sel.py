import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(658,445)
time.sleep(1.2)
pyautogui.screenshot().save(os.path.join(TMP,"d_sel.png"))
print("DONE")
