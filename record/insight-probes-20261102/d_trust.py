import os,time
import pyautogui
pyautogui.FAILSAFE=False
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
pyautogui.click(541,408)
time.sleep(0.5)
pyautogui.click(700,438)
time.sleep(1.0)
pyautogui.click(700,438)
time.sleep(1.5)
pyautogui.screenshot().save(os.path.join(TMP,"d_trust.png"))
print("DONE")
