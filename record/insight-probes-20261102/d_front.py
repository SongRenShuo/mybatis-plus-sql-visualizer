import os,time
import ctypes
import pyautogui,pygetwindow
pyautogui.FAILSAFE=False
u32=ctypes.windll.user32
pyautogui.click(1067,1053)
time.sleep(2.0)
wins=[w for w in pygetwindow.getAllWindows() if w.visible and w.title]
ide=[w for w in wins if "demo" in w.title or "IDEA" in w.title]
print("CANDIDATES", [(w.title[:40]) for w in ide])
for w in ide:
    try:
        w.activate()
    except Exception as e:
        print("act err", e)
    u32.SetWindowPos(w._hWnd,-1,0,0,0,0,0x2|0x1)
    try: w.maximize()
    except Exception: pass
    break
time.sleep(1.0)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","d_front.png"))
print("DONE")
