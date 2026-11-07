import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui,pyperclip
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(940,276)
time.sleep(1.0)
pyautogui.screenshot().save(os.path.join(TMP,"s_d5a.png"))
pyautogui.press("down")
time.sleep(0.15)
pyautogui.screenshot().save(os.path.join(TMP,"s_d5b.png"))
print("DONE")
