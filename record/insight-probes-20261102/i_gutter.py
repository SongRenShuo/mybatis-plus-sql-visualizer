import os,time
import pyautogui
pyautogui.FAILSAFE=False
pyautogui.click(95,204)
time.sleep(2.0)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","i_preview.png"))
print("DONE")
