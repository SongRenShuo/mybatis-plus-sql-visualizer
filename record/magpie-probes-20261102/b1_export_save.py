# -*- coding: utf-8 -*-
"""Elevated scene: OK export format dialog, handle save file chooser."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(968, 563)   # OK (format SQL)
time.sleep(1.5)
shot("save_chooser")
pyautogui.press("enter")    # confirm default path/name
time.sleep(1.5)
shot("after_save")
print("DONE")
