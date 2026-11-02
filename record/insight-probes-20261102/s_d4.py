import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui,pyperclip
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(594,209)
pyautogui.hotkey("ctrl","a")
pyautogui.press("backspace")
pyperclip.copy("dialect")
pyautogui.hotkey("ctrl","v")
time.sleep(2.0)
pyautogui.screenshot().save(os.path.join(TMP,"s_d4.png"))
print("DONE")
