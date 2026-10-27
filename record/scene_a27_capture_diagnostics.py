# -*- coding: utf-8 -*-
"""捕获空态诊断、停止后的引导与增强捕获设置链接。"""
from pathlib import Path
import sys,time,ctypes
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE))
import robot,anchor,pyautogui
from gifkit import Recorder
from locate import windows,sandbox_pid
REGION = (285, 26, 1354, 974)
OUT=HERE/'perf'/'a27_diagnostics_native.mp4'
CAPTURE='//div[contains(@class,"ActionButton") and contains(@accessiblename,"捕捉")]'
STOP='//div[contains(@class,"ActionButton") and contains(@accessiblename,"停止")]'


def main():
    pid=sandbox_pid()
    frames=[h for h,p,t,r in windows() if p==pid and r[2]-r[0]>1200]
    if len(frames)!=1:raise RuntimeError('Sandbox window not unique')
    u=ctypes.windll.user32;u.SetWindowPos.argtypes=[ctypes.c_void_p,ctypes.c_void_p,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_uint]
    u.SetWindowPos(ctypes.c_void_p(frames[0]),ctypes.c_void_p(-2),0,0,0,0,0x13)
    anchor.require_anchor('native_diagnostics_settings',region=(1130,180,470,790))
    if not robot.click_component(CAPTURE):raise RuntimeError('Initial capture disabled state not confirmed')
    time.sleep(.7)
    rec=Recorder(REGION,str(OUT));rec.start()
    try:
        time.sleep(1)
        if not robot.click_component(STOP):raise RuntimeError('Stop capture unavailable')
        time.sleep(.7)
        hit=anchor.require_anchor('native_diagnostics_settings',region=(1130,180,470,790))
        pyautogui.moveTo(hit[0],hit[1],duration=.3);pyautogui.click(hit[0],hit[1]);time.sleep(1)
        robot.require_component('//div[@accessiblename="Cancel"]')
        time.sleep(.8)
        robot.screenshot(out=str(HERE/'shots'/'diagnostics-native-settings.png'))
        time.sleep(1)
    finally:
        print('frames',rec.stop(),'output',OUT,flush=True)
        if robot.find_one('//div[@accessiblename="Cancel"]'):robot.click_component('//div[@accessiblename="Cancel"]')

if __name__=='__main__':main()
