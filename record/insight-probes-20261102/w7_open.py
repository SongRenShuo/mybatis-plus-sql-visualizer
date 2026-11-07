import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(1050,704)
time.sleep(1.2)
pyautogui.click(700,357)
time.sleep(0.4)
pyautogui.hotkey("alt","shift","q")
time.sleep(2.5)
pyautogui.screenshot().save(os.path.join(TMP,"w7_open.png"))
print("DONE")
