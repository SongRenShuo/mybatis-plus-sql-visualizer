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
time.sleep(0.4)
pyautogui.hotkey("ctrl","alt","s")
time.sleep(2.5)
pyautogui.click(594,209)
pyautogui.hotkey("ctrl","a")
pyautogui.press("backspace")
pyperclip.copy("SQL Visualizer")
pyautogui.hotkey("ctrl","v")
time.sleep(1.5)
pyautogui.press("enter")
time.sleep(1.5)
pyautogui.click(552,544)
time.sleep(1.3)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","mg8v_about.png"))
print("DONE")
