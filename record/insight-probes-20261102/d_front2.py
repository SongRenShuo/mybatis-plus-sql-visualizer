import os,time
import ctypes
import pyautogui,pygetwindow
pyautogui.FAILSAFE=False
u32=ctypes.windll.user32
BLOCK=("magpie","ZCode","Edge","Chrome","需求","反馈","Visual")
for w in pygetwindow.getAllWindows():
    t=(w.title or "")
    if w.visible and any(b in t for b in BLOCK) and "demo" not in t:
        try: w.minimize()
        except Exception: pass
time.sleep(0.8)
ide=None
for w in pygetwindow.getAllWindows():
    t=(w.title or "")
    if w.visible and "demo" in t:
        ide=w; break
print("IDE", ide.title[:50] if ide else None)
if ide:
    try: ide.activate()
    except Exception: pass
    u32.SetWindowPos(ide._hWnd,-1,0,0,0,0,0x2|0x1)
    try: ide.maximize()
    except Exception: pass
time.sleep(1.2)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","d_front2.png"))
print("DONE")
