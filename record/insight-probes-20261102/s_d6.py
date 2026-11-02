import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(933,879)
time.sleep(0.5)
pyautogui.click(1306,854)
time.sleep(0.8)
pyautogui.click(1231,854)
time.sleep(1.5)
pyautogui.screenshot().save(os.path.join(TMP,"s_d6.png"))
print("DONE")
