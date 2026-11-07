import os,time
import pyautogui
pyautogui.FAILSAFE=False
pyautogui.click(1500,677)
time.sleep(1.8)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","i_49b.png"))
print("DONE")
