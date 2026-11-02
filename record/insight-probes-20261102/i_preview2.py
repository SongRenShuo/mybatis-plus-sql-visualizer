import os,time
import pyautogui
pyautogui.FAILSAFE=False
pyautogui.click(400,357)
time.sleep(0.4)
pyautogui.hotkey("alt","shift","q")
time.sleep(2.5)
pyautogui.screenshot().save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","i_preview2.png"))
print("DONE")
