# -*- coding: utf-8 -*-
"""Elevated scene: navigate to About, click rollback, confirm dialog, shot status."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(552, 787)   # About tree node (this dialog layout)
time.sleep(1.2)
shot("mg8_about")
pyautogui.click(958, 405)   # rollback button
time.sleep(1.2)
shot("mg8_confirm")
print("DONE")
