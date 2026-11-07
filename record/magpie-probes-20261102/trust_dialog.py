# -*- coding: utf-8 -*-
"""Elevated scene: handle the Trust and Open Project dialog of the fresh sandbox.

Steps: focus dialog -> click 'Trust all projects' checkbox -> verify -> click
'Trust Project' -> verify dialog gone. All prints ASCII only.
"""
import time

import pyautogui
import pygetwindow as pg

pyautogui.FAILSAFE = False

LOG = []


def shot(name):
    p = "record/tmp/trust_%s.png" % name
    pyautogui.screenshot().save(p)
    LOG.append("shot %s" % p)
    return p


def find_dialog():
    wins = [w for w in pg.getAllWindows()
            if w.title and "Trust and Open Project" in w.title]
    return wins[0] if wins else None


def main():
    dlg = find_dialog()
    if not dlg:
        # dialog may be a child window without title; fall back to full-screen click
        LOG.append("NO_DIALOG_WINDOW_TITLE")
    else:
        try:
            dlg.activate()
        except Exception:
            pass
        time.sleep(0.4)
    # checkbox 'Trust all projects in SongRenShuo folder'
    pyautogui.click(715, 543)
    time.sleep(0.5)
    shot("after_checkbox")
    # Trust Project button
    pyautogui.click(935, 583)
    time.sleep(1.2)
    shot("after_trust")
    dlg2 = find_dialog()
    LOG.append("DIALOG_STILL_PRESENT=%s" % (dlg2 is not None))
    for line in LOG:
        print(line)
    return 0 if dlg2 is None else 1


if __name__ == "__main__":
    raise SystemExit(main())
