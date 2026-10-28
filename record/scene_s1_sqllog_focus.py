# -*- coding: utf-8 -*-
"""按调用方方法聚焦日志、双击完整预览并关闭聚焦恢复列表。"""
from pathlib import Path
import sys,time
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE))
import robot,anchor,pyautogui
from gifkit import Recorder
REGION=(277,25,1370,984)
OUT=HERE/'perf'/'s1_focus_native.mp4'
CLOSE_FOCUS='//div[contains(@class,"JLabel") and contains(@accessiblename,"UserServiceImpl#")]/parent::div//div[contains(@class,"Button")]'
LIST='//div[contains(@class,"ViewportWidthSqlEntryList")]/parent::div'


def click(x):
    if not robot.click_component(x):raise RuntimeError('Missing control '+x)
    time.sleep(.5)


def row():
    c=robot.require_component(LIST);cx,cy=robot.component_screen_center(LIST,c)
    return anchor.require_anchor('native_source_sql',region=(cx-c['width']//2,cy-c['height']//2,c['width'],c['height']))


def main():
    if robot.find_one(CLOSE_FOCUS):click(CLOSE_FOCUS)
    tabs=robot.find_components('//div[contains(@class,"TabLabel") and (contains(@accessiblename,"SQL 日志") or contains(@accessiblename,"SQL 历史") or contains(@accessiblename,"日志 → SQL") or contains(@accessiblename,"SQL → Java"))]')
    if len(tabs)!=4 or len({c['y'] for c in tabs})!=1:raise RuntimeError('Main tabs are not one row')
    hit=row();rec=Recorder(REGION,str(OUT));rec.start()
    try:
        pyautogui.moveTo(hit[0],hit[1],duration=.3);pyautogui.rightClick(hit[0],hit[1]);time.sleep(.7)
        click('//div[contains(@class,"ActionMenuItem") and contains(@accessiblename,"聚焦调用方方法")]')
        robot.require_component(CLOSE_FOCUS);time.sleep(.8)
        robot.screenshot(out=str(HERE/'shots'/'focus-native-tab.png'))
        hit=row();pyautogui.moveTo(hit[0],hit[1],duration=.3);pyautogui.doubleClick(hit[0],hit[1],interval=.15);time.sleep(1)
        robot.require_component('//div[contains(@accessiblename,"执行 SQL")]')
        robot.screenshot(out=str(HERE/'shots'/'focus-native-preview.png'))
        click('//div[@accessiblename="取消"]')
        click(CLOSE_FOCUS)
        if robot.find_one(CLOSE_FOCUS):raise RuntimeError('Method focus not removed')
        pyautogui.moveTo(1510,650,duration=.2);time.sleep(1)
        robot.screenshot(out=str(HERE/'shots'/'focus-native-final.png'))
    finally:print('frames',rec.stop(),'output',OUT,flush=True)

if __name__=='__main__':main()
