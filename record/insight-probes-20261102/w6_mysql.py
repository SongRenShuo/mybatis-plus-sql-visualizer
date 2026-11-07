import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(936,855)
time.sleep(0.5)
pyautogui.click(1050,704)
time.sleep(1.5)
pyautogui.screenshot().save(os.path.join(TMP,"w6_b.png"))
print("DONE")
