# -*- coding: utf-8 -*-
"""Elevated scene: open SQL Visualizer settings page via right-pane link."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(794, 670)   # right-pane link 'SQL Visualizer'
time.sleep(1.2)
shot("sqlvis_page")
print("DONE")
