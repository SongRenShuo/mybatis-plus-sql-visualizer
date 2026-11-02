import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(541,408)
time.sleep(0.5)
pyautogui.click(700,438)
time.sleep(1.0)
pyautogui.click(700,438)
time.sleep(1.8)
pyautogui.screenshot().save(os.path.join(TMP,"d_trust2.png"))
print("DONE")
