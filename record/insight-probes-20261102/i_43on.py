import os,time
import pyautogui
pyautogui.FAILSAFE=False
pyautogui.click(400,403)
time.sleep(4.5)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","i_43on.png"))
print("DONE")
