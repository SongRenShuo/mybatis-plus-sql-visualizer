import os,time
import pyautogui,pygetwindow,pyperclip
pyautogui.FAILSAFE=False
for w in pygetwindow.getAllWindows():
    if w.title and "mybatis-plus-sql-visualizer-demo" in w.title and w.visible:
        try: w.activate()
        except Exception: pass
        break
time.sleep(0.4)
pyautogui.hotkey("ctrl","shift","a")
time.sleep(1.0)
pyperclip.copy("data sources")
pyautogui.hotkey("ctrl","v")
time.sleep(1.2)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","b3_findaction.png"))
print("DONE")
