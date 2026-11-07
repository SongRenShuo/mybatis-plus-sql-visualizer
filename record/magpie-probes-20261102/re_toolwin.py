import os,time
import ctypes
import pyautogui,pygetwindow,pyperclip
pyautogui.FAILSAFE=False
u32=ctypes.windll.user32
for w in pygetwindow.getAllWindows():
    if w.title and "mybatis-plus-sql-visualizer-demo" in w.title and w.visible:
        try: w.activate()
        except Exception: pass
        u32.SetWindowPos(w._hWnd,-1,0,0,0,0,0x2|0x1)
        break
time.sleep(0.5)
pyautogui.hotkey("ctrl","shift","a")
time.sleep(1.0)
pyperclip.copy("sql visualizer")
pyautogui.hotkey("ctrl","v")
time.sleep(1.2)
pyautogui.click(741,311)
time.sleep(1.5)
pyautogui.click(1266,128)
time.sleep(1.0)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","re_toolwin.png"))
print("DONE")
