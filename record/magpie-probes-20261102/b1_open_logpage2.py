import os,time
import pyautogui
pyautogui.FAILSAFE=False
pyautogui.click(794,480)
time.sleep(1.5)
p=pyautogui.screenshot(); p.save(os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp","b1_log_page2.png"))
print("DONE")
