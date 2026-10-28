# -*- coding: utf-8 -*-
"""预览调用栈弹层中点击业务 Controller 帧并验证目标源码。"""
from pathlib import Path
import sys,time
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE))
import robot,anchor,pyautogui
from gifkit import Recorder
from locate import sandbox_pid,windows
REGION=(277,25,1370,984)
OUT=HERE/'perf'/'a2_stackjump_native.mp4'
LABEL='//div[contains(@class,"JBLabel") and contains(@accessiblename,"调用栈")]'
TREE='//div[contains(@class,"JTree")]'


def main():
    pid=sandbox_pid()
    robot.require_component(LABEL)
    pyautogui.moveTo(540,750,duration=.2);time.sleep(.7)
    if robot.find_one(TREE):
        pyautogui.press('esc');time.sleep(.4)
    robot.require_component(LABEL)
    rec=Recorder(REGION,str(OUT));rec.start()
    try:
        point=robot.component_screen_center(LABEL)
        pyautogui.moveTo(*point,duration=.3);time.sleep(1)
        c=robot.require_component(TREE);cx,cy=robot.component_screen_center(TREE,c)
        hit=anchor.require_anchor('native_stack_controller',region=(cx-c['width']//2,cy-c['height']//2,c['width'],c['height']))
        robot.screenshot(out=str(HERE/'shots'/'stack-native-popup.png'))
        pyautogui.moveTo(hit[0],hit[1],duration=.3);time.sleep(.3);pyautogui.click(hit[0],hit[1]);time.sleep(1.2)
        if not any(p==pid and 'UserController.java' in title for _,p,title,r in windows()):
            robot.screenshot(out=str(HERE/'shots'/'stack-native-failed.png'))
            raise RuntimeError('Business Controller navigation not confirmed')
        if not robot.click_component('//div[contains(@accessiblename,"取消")]'):
            raise RuntimeError('Cannot dismiss preview to show target source')
        time.sleep(.5)
        if robot.find_one(LABEL):
            raise RuntimeError('Preview still obscures target source')
        pyautogui.moveTo(1510,650,duration=.2);time.sleep(1)
        robot.screenshot(out=str(HERE/'shots'/'stack-native-final.png'))
    finally:print('frames',rec.stop(),'output',OUT,flush=True)

if __name__=='__main__':main()
