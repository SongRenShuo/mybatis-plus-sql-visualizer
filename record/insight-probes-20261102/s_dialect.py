import os,time,ctypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui,pygetwindow,pyperclip
pyautogui.FAILSAFE=False
u32=ctypes.windll.user32
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
BLOCK=("magpie","ZCode","Edge","Chrome","需求","反馈")
for w in pygetwindow.getAllWindows():
    t=(w.title or "")
    if w.visible and any(b in t for b in BLOCK) and "demo" not in t:
        try: w.minimize()
        except Exception: pass
time.sleep(0.6)
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
pyautogui.hotkey("ctrl","alt","s")
time.sleep(2.5)
pyautogui.click(594,209)
pyautogui.hotkey("ctrl","a")
pyautogui.press("backspace")
pyperclip.copy("sql dialects")
pyautogui.hotkey("ctrl","v")
time.sleep(1.8)
pyautogui.screenshot().save(os.path.join(TMP,"s_dialect.png"))
print("DONE")
