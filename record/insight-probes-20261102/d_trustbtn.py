import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(933,584)
time.sleep(0.8)
pyautogui.click(933,584)
time.sleep(2.0)
pyautogui.screenshot().save(os.path.join(TMP,"d_trustbtn.png"))
print("DONE")
