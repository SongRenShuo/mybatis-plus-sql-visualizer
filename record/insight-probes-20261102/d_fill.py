import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui,pyperclip
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(1021,527)
time.sleep(0.4)
pyperclip.copy("root")
pyautogui.hotkey("ctrl","v")
time.sleep(0.4)
pyautogui.click(905,757)
time.sleep(8.0)
pyautogui.screenshot().save(os.path.join(TMP,"d_fill.png"))
print("DONE")
