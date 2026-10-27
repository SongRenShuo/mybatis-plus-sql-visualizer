# -*- coding: utf-8 -*-
"""录制历史导出；原生保存框由 Computer Use 按控件填入目标，后台校验文件。"""
from pathlib import Path
import sys,time
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE))
import robot,pyautogui
from gifkit import Recorder
REGION = (285, 26, 1354, 974)
OUT=HERE/'perf'/'a36_export_native.mp4'
TARGET=HERE/'tmp'/'history-export-verified.sql'


def main():
    if TARGET.exists():raise RuntimeError('Choose a new export target; existing file must not be overwritten')
    rec=Recorder(REGION,str(OUT));rec.start()
    try:
        pyautogui.moveTo(1240,220,duration=.3);pyautogui.rightClick(1240,220);time.sleep(.6)
        if not robot.click_component('//div[contains(@class,"ActionMenuItem") and contains(@accessiblename,"导出")]'):raise RuntimeError('Export action missing')
        robot.require_component('//div[@accessiblename="OK"]');time.sleep(.8)
        robot.screenshot(out=str(HERE/'shots'/'export-native-format.png'))
        if not robot.click_component('//div[@accessiblename="OK"]'):raise RuntimeError('Format confirmation missing')
        print('AWAIT_NATIVE_SAVE',TARGET,flush=True)
        deadline=time.monotonic()+240
        while not TARGET.exists() and time.monotonic()<deadline:time.sleep(.3)
        if not TARGET.exists():raise RuntimeError('No verified export saved within deadline')
        content=TARGET.read_text(encoding='utf-8-sig')
        if 'SELECT' not in content.upper() or 'COUNT' not in content.upper() or 'car_device_info' not in content:
            raise RuntimeError('Export content does not match selected SQLs')
        print('verified_export',TARGET,'bytes',TARGET.stat().st_size,flush=True)
        time.sleep(.8)
    finally:print('frames',rec.stop(),'output',OUT,flush=True)

if __name__=='__main__':main()
