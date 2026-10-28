# -*- coding: utf-8 -*-
"""真实 N+1 查询、同结构聚类菜单和右键同结构聚焦；结束恢复时间升序。"""
from pathlib import Path
import sys,time,urllib.request
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE))
import pyautogui
import robot,anchor
from gifkit import Recorder
from locate import windows,sandbox_pid,ensure_foreground
REGION=(277,25,1370,984)
OUT=HERE/'perf'/'s3_n1_native.mp4'
CLEAR='//div[contains(@class,"ActionButton") and contains(@accessiblename,"清空")]'
ASC='//div[contains(@class,"ActionButton") and contains(@accessiblename,"时间升序")]'
CLUSTER='//div[contains(@class,"ActionButton") and contains(@accessiblename,"同结构聚类")]'
FOCUS_CLOSE='//div[contains(@accessiblename,"同结构 5 条")]/parent::div//div[contains(@class,"Button")]'
LIST='//div[contains(@class,"ViewportWidthSqlEntryList")]'


def click(xpath):
    if not robot.click_component(xpath):
        raise RuntimeError('Missing UI control: '+xpath)
    time.sleep(.5)


def feed(path):
    with urllib.request.urlopen('http://127.0.0.1:8082'+path,timeout=15) as r:
        if r.status!=200:
            raise RuntimeError('Failed demo endpoint: '+path)
        r.read()


def reset():
    pyautogui.press('esc')
    if robot.find_one(FOCUS_CLOSE):
        click(FOCUS_CLOSE)
    if robot.find_one(CLUSTER):
        click(CLUSTER)
        click('//div[contains(@class,"JRadioButtonMenuItem") and contains(@accessiblename,"时间升序")]')
    robot.require_component(ASC)


def main():
    pid=sandbox_pid()
    frames=[(h,r) for h,p,t,r in windows() if p==pid and r[2]-r[0]>1200]
    if len(frames)!=1 or frames[0][1]!=(277,25,1647,1009):
        raise RuntimeError('Sandbox geometry mismatch')
    if not ensure_foreground(frames[0][0]):
        raise RuntimeError('Sandbox not focused')
    reset()
    if not any(p==pid and 'UserServiceImpl.java' in t for h,p,t,r in windows()):
        raise RuntimeError('Safe source background is not active')
    click(CLEAR)
    feed('/mpj/device/entityList')
    time.sleep(.7)
    rec=Recorder(REGION,str(OUT)); rec.start()
    try:
        feed('/mpj/perf/n1'); time.sleep(1.2)
        robot.screenshot(out=str(HERE/'shots'/'n1-native-badge.png'))
        click(ASC)
        robot.require_component('//div[contains(@accessiblename,"同结构聚类")]')
        time.sleep(.8)
        click('//div[contains(@class,"JRadioButtonMenuItem") and contains(@accessiblename,"同结构聚类")]')
        robot.require_component(CLUSTER)
        c=robot.require_component(LIST); cx,cy=robot.component_screen_center(LIST,c)
        region=(cx-c['width']//2,cy-c['height']//2,c['width'],c['height'])
        hit=anchor.require_anchor('native_n1_sql',region=region)
        pyautogui.moveTo(hit[0],hit[1],duration=.3)
        pyautogui.rightClick(hit[0],hit[1]); time.sleep(.7)
        robot.require_component('//div[contains(@class,"ActionMenuItem") and contains(@accessiblename,"同结构")]')
        robot.screenshot(out=str(HERE/'shots'/'n1-native-context.png'))
        click('//div[contains(@class,"ActionMenuItem") and contains(@accessiblename,"同结构")]')
        robot.require_component(FOCUS_CLOSE)
        pyautogui.moveTo(1510,780,duration=.2); time.sleep(1.4)
        robot.screenshot(out=str(HERE/'shots'/'n1-native-final.png'))
    finally:
        print('frames',rec.stop(),'output',OUT,flush=True)
        reset()
        robot.screenshot(out=str(HERE/'shots'/'n1-native-reset.png'))

if __name__=='__main__':
    main()
