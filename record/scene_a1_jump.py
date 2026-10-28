# -*- coding: utf-8 -*-
"""捕获条目右键跳转源码；编辑器从文件顶部精确返回业务执行行。"""
from pathlib import Path
import sys,time
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE))
import robot,anchor,pyautogui
from gifkit import Recorder
from locate import windows,sandbox_pid,ensure_foreground
REGION=(277,25,1370,984)
OUT=HERE/'perf'/'a1_jump_native.mp4'
LIST='//div[contains(@class,"ViewportWidthSqlEntryList")]'
EDITOR='//div[contains(@class,"EditorComponentImpl")]/parent::div'
JUMP='//div[contains(@class,"ActionMenuItem") and contains(@accessiblename,"跳转到 Java")]'


def main():
    p=sandbox_pid();fs=[(h,r) for h,pid,t,r in windows() if pid==p and r[2]-r[0]>1200]
    if len(fs)!=1 or fs[0][1]!=(277,25,1647,1009):raise RuntimeError('Sandbox viewport mismatch')
    if not ensure_foreground(fs[0][0]):raise RuntimeError('Sandbox not foreground')
    if not any(pid==p and 'UserServiceImpl.java' in t for h,pid,t,r in windows()):raise RuntimeError('Wrong source file')
    editor=robot.require_component(EDITOR);ex,ey=robot.component_screen_center(EDITOR,editor)
    pyautogui.moveTo(ex,ey,duration=.25);pyautogui.click(ex,ey);time.sleep(.3)
    pyautogui.hotkey('ctrl','home');time.sleep(.6)
    c=robot.require_component(LIST);cx,cy=robot.component_screen_center(LIST,c)
    hit=anchor.require_anchor('native_source_sql',region=(cx-c['width']//2,cy-c['height']//2,c['width'],min(120,c['height'])))
    rec=Recorder(REGION,str(OUT));rec.start()
    try:
        time.sleep(.7);pyautogui.moveTo(hit[0],hit[1],duration=.3);pyautogui.rightClick(hit[0],hit[1]);time.sleep(.7)
        robot.require_component(JUMP)
        robot.screenshot(out=str(HERE/'shots'/'source-native-menu.png'))
        if not robot.click_component(JUMP):raise RuntimeError('Source jump unavailable')
        time.sleep(1.6)
        if not any(pid==p and 'UserServiceImpl.java' in t for h,pid,t,r in windows()):raise RuntimeError('Wrong target source')
        pyautogui.moveTo(1510,650,duration=.2);time.sleep(1)
        robot.screenshot(out=str(HERE/'shots'/'source-native-final.png'))
    finally:print('frames',rec.stop(),'output',OUT,flush=True)

if __name__=='__main__':main()
