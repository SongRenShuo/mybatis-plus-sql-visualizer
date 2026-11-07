import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(700,357)
time.sleep(0.6)
pyautogui.hotkey("alt","shift","q")
time.sleep(3.0)
pyautogui.screenshot().save(os.path.join(TMP,"w2_q.png"))
print("DONE")
