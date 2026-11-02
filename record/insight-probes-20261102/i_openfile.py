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
pyautogui.click(1533,61)
time.sleep(0.6)
pyautogui.hotkey("ctrl","shift","n")
time.sleep(1.2)
pyperclip.copy("OrderMapper")
pyautogui.hotkey("ctrl","v")
time.sleep(1.2)
pyautogui.press("enter")
time.sleep(2.0)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","i_openfile.png"))
print("DONE")
