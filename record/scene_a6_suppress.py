# -*- coding: utf-8 -*-
"""屏蔽心跳 SQL 并在规则管理中删除本次演示规则，恢复原列表。"""
from pathlib import Path
import sys,time,urllib.request
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE))
import robot,anchor,pyautogui
from gifkit import Recorder
REGION=(277,25,1370,984)
OUT=HERE/'perf'/'a6_suppress_native.mp4'
MANAGE='//div[contains(@class,"ActionButton") and contains(@accessiblename,"管理屏蔽规则")]'
CLOSE='//div[@accessiblename="关闭"]'
EMPTY='//div[contains(@accessiblename,"暂无屏蔽规则")]'
LIST='//div[contains(@class,"ViewportWidthSqlEntryList")]/parent::div'


def click(x):
    if not robot.click_component(x):raise RuntimeError('Missing control: '+x)
    time.sleep(.5)


def main():
    if not robot.find_one(EMPTY):raise RuntimeError('Expected verified empty suppression manager before scene')
    click(CLOSE)
    c=robot.require_component(LIST);cx,cy=robot.component_screen_center(LIST,c)
    region=(cx-c['width']//2,cy-c['height']//2,c['width'],c['height'])
    hit=anchor.require_anchor('native_heartbeat',region=region)
    rec=Recorder(REGION,str(OUT));rec.start()
    try:
        time.sleep(.6);pyautogui.moveTo(hit[0],hit[1],duration=.3);pyautogui.rightClick(hit[0],hit[1]);time.sleep(.6)
        click('//div[contains(@class,"ActionMenuItem") and contains(@accessiblename,"屏蔽此 SQL")]')
        time.sleep(.8)
        if anchor.find('native_heartbeat',region=region,min_score=.9):raise RuntimeError('Heartbeat row still visible after suppress')
        robot.screenshot(out=str(HERE/'shots'/'suppress-native-hidden.png'))
        click(MANAGE)
        with urllib.request.urlopen('http://127.0.0.1:8081/logFeed/heartbeat?times=2&intervalMs=200',timeout=12) as r:r.read()
        time.sleep(.7)
        robot.screenshot(out=str(HERE/'shots'/'suppress-native-rule.png'))
        # 开始前规则列表为空；这里只删除录制中创建并自动选中的一条规则。
        click('//div[@accessiblename="删除"]')
        robot.require_component(EMPTY)
        click(CLOSE)
        anchor.require_anchor('native_heartbeat',region=region,min_score=.9)
        pyautogui.moveTo(1510,650,duration=.2);time.sleep(1)
        robot.screenshot(out=str(HERE/'shots'/'suppress-native-final.png'))
    finally:print('frames',rec.stop(),'output',OUT,flush=True)

if __name__=='__main__':main()
