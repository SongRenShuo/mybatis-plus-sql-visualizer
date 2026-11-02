import os,time
import ac
ac.ensure_ide_front()
ac.key("ctrl","shift","a")
time.sleep(1.2)
ac.paste_text("data sources")
time.sleep(1.4)
import pyautogui
pyautogui.press("enter")
time.sleep(2.5)
p=ac.shot("d_ds2")
print("DONE")
