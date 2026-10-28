# -*- coding: utf-8 -*-
"""多选两条不同历史 SQL，经右键打开平台原生差异视图。"""
from pathlib import Path
import sys,time
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE))
import robot,anchor,pyautogui
from gifkit import Recorder
OUT=HERE/'perf'/'a37_compare_native.mp4'
REGION=(277,25,1370,984)


def main():
    pyautogui.press('esc');time.sleep(.3)
    x='//div[contains(@class,"ViewportWidthSqlEntryList")]/parent::div';c=robot.require_component(x);cx,cy=robot.component_screen_center(x,c)
    reg=(cx-c['width']//2,cy-c['height']//2,c['width'],200)
    a=anchor.require_anchor('native_history_count',region=reg);b=anchor.require_anchor('native_history_device',region=reg)
    rec=Recorder(REGION,str(OUT));rec.start()
    try:
        pyautogui.moveTo(a[0],a[1],duration=.2);pyautogui.click(a[0],a[1]);time.sleep(.5)
        pyautogui.keyDown('ctrl');pyautogui.click(b[0],b[1]);pyautogui.keyUp('ctrl');time.sleep(.6)
        pyautogui.rightClick(b[0],b[1]);time.sleep(.7)
        if not robot.click_component('//div[contains(@class,"ActionMenuItem") and contains(@accessiblename,"对比")]'):raise RuntimeError('Compare action missing')
        time.sleep(1.4)
        robot.screenshot(out=str(HERE/'shots'/'history-compare-native.png'));time.sleep(1.3)
    finally:print('frames',rec.stop(),'output',OUT,flush=True)

if __name__=='__main__':main()
