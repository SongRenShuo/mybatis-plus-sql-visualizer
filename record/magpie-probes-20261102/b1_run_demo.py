# -*- coding: utf-8 -*-
"""Elevated scene: OK the settings dialog, then click Run for the demo app."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(1231, 854)   # OK
time.sleep(1.2)
shot("settings_closed")
pyautogui.click(1250, 41)    # Run 'MybatisPlusJoinDemoApplication'
time.sleep(2.0)
shot("run_clicked")
print("DONE")
