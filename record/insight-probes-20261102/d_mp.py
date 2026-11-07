import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui,pyperclip
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(1306,833)   # Apply (persist mpj_demo)
time.sleep(1.0)
pyautogui.click(658,421)    # select mp_demo@localhost
time.sleep(1.2)
pyautogui.screenshot().save(os.path.join(TMP,"d_mp1.png"))
pyautogui.click(1021,527)   # password field (same layout)
time.sleep(0.4)
pyperclip.copy("root")
pyautogui.hotkey("ctrl","v")
time.sleep(0.4)
pyautogui.click(843,790)    # Test Connection
time.sleep(7.0)
pyautogui.screenshot().save(os.path.join(TMP,"d_mp2.png"))
print("DONE")
