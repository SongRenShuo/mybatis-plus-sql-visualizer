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
        break
time.sleep(0.4)
pyautogui.press("escape")
time.sleep(0.5)
pyautogui.press("escape")
time.sleep(0.5)
pyautogui.hotkey("alt","f4")
time.sleep(1.5)
pyautogui.press("enter")
time.sleep(1.5)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","exit1.png"))
pyautogui.press("enter")
time.sleep(1.5)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","exit2.png"))
print("DONE")
