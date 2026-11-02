import os,time
import pyautogui
pyautogui.FAILSAFE=False
pyautogui.click(1394,128)
time.sleep(0.6)
pyautogui.click(1500,196)
time.sleep(1.2)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","b1_docked_final.png"))
print("DONE")
