import os,time
import ctypes
import pyautogui,pygetwindow
pyautogui.FAILSAFE=False
u32=ctypes.windll.user32
for w in pygetwindow.getAllWindows():
    if w.title and "mybatis-plus-sql-visualizer-demo" in w.title and w.visible:
        try: w.activate()
        except Exception: pass
        u32.SetWindowPos(w._hWnd,-1,0,0,0,0,0x2|0x1)
        try: w.maximize()
        except Exception: pass
        break
time.sleep(0.8)
pyautogui.click(900,500)
time.sleep(0.3)
pyautogui.hotkey("shift","f10")
time.sleep(3)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","i_run.png"))
print("DONE")
