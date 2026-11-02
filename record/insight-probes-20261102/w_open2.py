import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.press("escape")
time.sleep(0.6)
pyautogui.click(350,357)
time.sleep(0.4)
pyautogui.hotkey("alt","shift","q")
time.sleep(2.5)
pyautogui.screenshot().save(os.path.join(TMP,"w_open2.png"))
print("DONE")
