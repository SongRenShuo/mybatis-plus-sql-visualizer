import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui,pygetwindow,pyperclip
pyautogui.FAILSAFE=False
u32=ctypes.windll.user32
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
ide=None
for w in pygetwindow.getAllWindows():
    if w.visible and "mybatis-plus-sql-visualizer-demo" in (w.title or ""):
        ide=w; break
if not ide:
    print("NO_IDE"); raise SystemExit(1)
try: ide.activate()
except Exception: pass
u32.SetWindowPos(ide._hWnd,-1,0,0,0,0,0x2|0x1)
try: ide.maximize()
except Exception: pass
time.sleep(0.8)
pyautogui.click(1640,80)
time.sleep(0.6)
pyautogui.hotkey("ctrl","shift","a")
time.sleep(1.2)
pyperclip.copy("data sources")
pyautogui.hotkey("ctrl","v")
time.sleep(1.2)
pyautogui.press("enter")
time.sleep(2.0)
pyautogui.screenshot().save(os.path.join(TMP,"d_ds.png"))
print("DONE")
