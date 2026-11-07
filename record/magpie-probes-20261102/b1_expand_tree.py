# -*- coding: utf-8 -*-
"""Elevated scene: expand SQL Visualizer tree node and open its child page."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(519, 765)    # select SQL Visualizer row in tree
time.sleep(0.4)
pyautogui.click(506, 765)    # expand arrow
time.sleep(0.8)
shot("tree_expanded")
print("DONE")
