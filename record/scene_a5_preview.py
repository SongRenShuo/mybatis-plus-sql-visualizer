# -*- coding: utf-8 -*-
"""单击选择、显式开启快速预览、复制 SQL、双击完整弹窗，再恢复默认关闭。"""
from pathlib import Path
import sys,time,subprocess
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE))
import robot,anchor,pyautogui
from gifkit import Recorder
REGION=(277,25,1370,984)
OUT=HERE/'perf'/'a5_preview_native.mp4'
QUICK='//div[contains(@class,"ActionButton") and contains(@accessiblename,"快速预览")]'
TITLE='//div[@accessiblename="SQL 快速预览"]'
BUTTONS=TITLE+'/parent::div/parent::div//div[contains(@class,"Button")]'
LIST='//div[contains(@class,"ViewportWidthSqlEntryList")]/parent::div'


def click(x):
    if not robot.click_component(x):raise RuntimeError('Missing control '+x)
    time.sleep(.5)


def preview_button(right):
    controls=robot.find_components(BUTTONS)
    if len(controls)!=2:raise RuntimeError('Unexpected preview button layout')
    target=sorted(controls,key=lambda c:c['x'])[1 if right else 0]
    parent_path='('+BUTTONS+')/parent::div'
    parent=robot.require_component(parent_path)
    cx,cy=robot.component_screen_center(parent_path,parent)
    # 两个无名称图标共用按钮行；左为复制、右为关闭，取实际父容器坐标。
    return (cx-parent['width']//2+target['x']+target['width']//2,
            cy-parent['height']//2+target['y']+target['height']//2)


def row():
    c=robot.require_component(LIST);cx,cy=robot.component_screen_center(LIST,c)
    return anchor.require_anchor('native_source_sql',region=(cx-c['width']//2,cy-c['height']//2,c['width'],c['height']))


def main():
    if robot.find_one(TITLE):click(QUICK)
    if robot.find_one(TITLE):raise RuntimeError('Quick preview did not close')
    hit=row();rec=Recorder(REGION,str(OUT));rec.start()
    try:
        pyautogui.moveTo(hit[0],hit[1],duration=.3);pyautogui.click(hit[0],hit[1]);time.sleep(.8)
        if robot.find_one(TITLE):raise RuntimeError('Default click unexpectedly opened quick preview')
        click(QUICK);robot.require_component(TITLE);time.sleep(.7)
        copy_point=preview_button(False)
        pyautogui.moveTo(*copy_point,duration=.2);pyautogui.click(*copy_point);time.sleep(.6)
        copied=subprocess.run(['powershell','-NoProfile','-Command','Get-Clipboard -Raw'],capture_output=True,text=True,check=True).stdout
        if 'SELECT' not in copied.upper() or 'FROM' not in copied.upper():raise RuntimeError('SQL clipboard verification failed')
        robot.screenshot(out=str(HERE/'shots'/'preview-native-quick.png'))
        click(QUICK)
        hit=row();pyautogui.moveTo(hit[0],hit[1],duration=.2);pyautogui.doubleClick(hit[0],hit[1],interval=.15);time.sleep(1)
        robot.require_component('//div[@accessiblename="取消"]')
        robot.screenshot(out=str(HERE/'shots'/'preview-native-full.png'))
        click('//div[@accessiblename="取消"]')
        pyautogui.moveTo(1510,650,duration=.2);time.sleep(.8)
    finally:
        print('frames',rec.stop(),'output',OUT,flush=True)
        if robot.find_one(TITLE):click(QUICK)

if __name__=='__main__':main()
