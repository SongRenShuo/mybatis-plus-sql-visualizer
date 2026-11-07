import os,time
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
os.makedirs(TMP,exist_ok=True)
pyautogui.click(715,543)
time.sleep(0.5)
pyautogui.click(898,583)
time.sleep(1.0)
pyautogui.click(898,583)
time.sleep(1.5)
pyautogui.screenshot().save(os.path.join(TMP,"i_trust.png"))
print("DONE")
