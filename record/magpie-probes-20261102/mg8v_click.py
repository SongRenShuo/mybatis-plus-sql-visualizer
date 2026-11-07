import os,time
import pyautogui
pyautogui.FAILSAFE=False
pyautogui.click(958,405)
time.sleep(1.2)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","mg8v_confirm.png"))
print("DONE")
