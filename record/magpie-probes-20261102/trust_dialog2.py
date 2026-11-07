# -*- coding: utf-8 -*-
"""Elevated scene (2nd pass): screenshot state, click Trust Project, verify."""
import os
import time

import pyautogui
import pygetwindow as pg

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")
os.makedirs(TMP, exist_ok=True)


def shot(name):
    p = os.path.join(TMP, "trust_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)
    return p


def find_dialog():
    wins = [w for w in pg.getAllWindows()
            if w.title and "Trust and Open Project" in w.title]
    return wins[0] if wins else None


def main():
    dlg = find_dialog()
    print("DIALOG_PRESENT=%s" % (dlg is not None))
    shot("state0")
    if dlg:
        try:
            dlg.activate()
        except Exception:
            pass
        time.sleep(0.4)
        pyautogui.click(935, 583)   # Trust Project
        time.sleep(1.2)
    shot("after_trust")
    dlg2 = find_dialog()
    print("DIALOG_STILL_PRESENT=%s" % (dlg2 is not None))
    return 0 if dlg2 is None else 1


if __name__ == "__main__":
    raise SystemExit(main())
