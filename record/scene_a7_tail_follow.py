# -*- coding: utf-8 -*-
"""尾随五步：自动贴底、拖动暂停、后台新增不抢视口、滚底恢复、继续贴底。"""
import json
import sys
import time
import urllib.request
from pathlib import Path
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE))
import robot,pyautogui
from gifkit import Recorder
from locate import windows,sandbox_pid,ensure_foreground
REGION=(277,25,1370,984)
OUT=HERE/'perf'/'a7_tail_follow_native.mp4'
LIST='//div[contains(@class,"ViewportWidthSqlEntryList")]'
VIEW=LIST+'/parent::div'
BAR=LIST+'/ancestor::div[contains(@class,"JBScrollPane")]//div[contains(@class,"ScrollBar")]'
BOTTOM='//div[contains(@class,"ActionButton") and contains(@accessiblename,"滚动到底部")]'
CLEAR='//div[contains(@class,"ActionButton") and contains(@accessiblename,"清空")]'
DEDUPE='//div[contains(@class,"JCheckBox") and (contains(@accessiblename,"重复") or contains(@accessiblename,"SQL 去重"))]'


def click(xpath):
    if not robot.click_component(xpath):
        raise RuntimeError('Missing control: '+xpath)
    time.sleep(.4)


def feed(start):
    ids=','.join(str(i) for i in range(start,start+5))
    with urllib.request.urlopen('http://127.0.0.1:8081/logFeed/n1?ids='+ids,timeout=15) as r:
        if r.status!=200:raise RuntimeError('Feed failed')
        r.read()
    time.sleep(.6)


def state():
    c=robot.require_component(LIST);v=robot.require_component(VIEW)
    return {'y':c['y'],'height':c['height'],'viewport':v['height'],
            'bottom':abs(c['height']+c['y']-v['height'])<=3}


def main():
    pid=sandbox_pid();frames=[(h,r) for h,p,t,r in windows() if p==pid and r[2]-r[0]>1200]
    if len(frames)!=1 or frames[0][1]!=(277,25,1647,1009):raise RuntimeError('Sandbox geometry mismatch')
    if not ensure_foreground(frames[0][0]):raise RuntimeError('Sandbox not focused')
    if not any(p==pid and 'UserServiceImpl.java' in t for h,p,t,r in windows()):raise RuntimeError('Unsafe editor background')
    # 前置核验要求去重关闭；通过重复批次高度增长实证，避免反向切换开关。
    click(CLEAR)
    for start in (1,6,11,16):feed(start)
    h=state()['height'];feed(1)
    if state()['height']<=h:raise RuntimeError('Deduplication must be disabled before this scene')
    click(BOTTOM)
    if not state()['bottom']:raise RuntimeError('Initial viewport did not reach bottom')
    rec=Recorder(REGION,str(OUT)); evidence={};rec.start()
    try:
        feed(21);evidence['following']=state()
        if not evidence['following']['bottom']:raise RuntimeError('New rows did not follow')
        c=robot.require_component(BAR);cx,cy=robot.component_screen_center(BAR,c)
        y=cy+c['height']//2-30
        pyautogui.moveTo(cx,y,duration=.3);time.sleep(.2)
        pyautogui.dragTo(cx,y-300,duration=.9);time.sleep(.6)
        evidence['paused']=state()
        if evidence['paused']['bottom']:raise RuntimeError('Drag did not move away from bottom')
        robot.screenshot(out=str(HERE/'shots'/'tail-native-paused.png'))
        feed(26);feed(31);evidence['paused_new_rows']=state()
        if evidence['paused_new_rows']['height']<=evidence['paused']['height'] or evidence['paused_new_rows']['y']!=evidence['paused']['y']:
            raise RuntimeError('Paused viewport moved or new rows absent')
        robot.screenshot(out=str(HERE/'shots'/'tail-native-paused-new.png'))
        time.sleep(.8);click(BOTTOM);evidence['resumed']=state()
        if not evidence['resumed']['bottom']:raise RuntimeError('Resume did not reach bottom')
        feed(36);feed(41);evidence['following_again']=state()
        if not evidence['following_again']['bottom'] or evidence['following_again']['height']<=evidence['resumed']['height']:
            raise RuntimeError('Follow did not continue after resume')
        pyautogui.moveTo(1510,160,duration=.2);time.sleep(1)
        robot.screenshot(out=str(HERE/'shots'/'tail-native-final.png'))
    finally:
        evidence['frames']=rec.stop()
        print(json.dumps(evidence,ensure_ascii=True),flush=True)
        (HERE/'perf'/'a7_tail_follow_native-evidence.json').write_text(json.dumps(evidence,indent=2),encoding='utf-8')
        click(DEDUPE)
        click(BOTTOM)

if __name__=='__main__':main()
