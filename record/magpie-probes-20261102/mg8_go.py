# -*- coding: utf-8 -*-
"""Elevated scene: About page -> rollback click -> confirm -> shots."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(552, 843)   # About
time.sleep(1.3)
shot("mg8_about3")
pyautogui.click(958, 405)   # rollback button
time.sleep(1.3)
shot("mg8_confirm3")
print("DONE")
