# -*- coding: utf-8 -*-
"""Elevated scene (robot-server): About -> rollback -> confirm OK, shots at each step."""
import os
import time

import pyautogui
import robot

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


try:
    robot.click_component("//div[@accessiblename='About']")
    time.sleep(1.2)
    shot("mg8_r_about")
    robot.click_component("//div[@accessiblename='Rollback to previous version']")
    time.sleep(1.2)
    shot("mg8_r_confirm")
except Exception as e:
    print("STEP_ERR %r" % e)
    shot("mg8_r_error")
print("DONE")
