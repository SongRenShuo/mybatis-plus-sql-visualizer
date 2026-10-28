# -*- coding: utf-8 -*-
"""历史文件进入二级 SQL 列表、搜索真实慢查询并双击预览，结束清空搜索。"""
from pathlib import Path
import sys,time
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE))
import robot,anchor,pyautogui
from gifkit import Recorder,paste
REGION=(277,25,1370,984)
OUT=HERE/'perf'/'a3_history_native.mp4'
SEARCH='//div[contains(@class,"SearchTextArea")]'


def search(text):
    c=robot.require_component(SEARCH);pt=robot.component_screen_center(SEARCH,c)
    pyautogui.moveTo(*pt,duration=.2);pyautogui.click(*pt);pyautogui.hotkey('ctrl','a')
    if text:paste(text)
    else:pyautogui.press('backspace')
    time.sleep(.6)


def main():
    search('')
    hit=anchor.require_anchor('native_history_back',region=(1130,140,100,40));pyautogui.click(hit[0],hit[1]);time.sleep(.6)
    hit=anchor.require_anchor('native_history_oct13',region=(1130,180,470,790))
    rec=Recorder(REGION,str(OUT));rec.start()
    try:
        time.sleep(.7);pyautogui.moveTo(hit[0],hit[1],duration=.3);pyautogui.doubleClick(hit[0],hit[1],interval=.15);time.sleep(.9)
        robot.require_component('//div[contains(@class,"ViewportWidthSqlEntryList")]')
        search('SLEEP');time.sleep(.8)
        robot.screenshot(out=str(HERE/'shots'/'history-native-search.png'))
        hit=anchor.require_anchor('native_history_sleep',region=(1130,180,470,790))
        pyautogui.moveTo(hit[0],hit[1],duration=.3);pyautogui.doubleClick(hit[0],hit[1],interval=.15);time.sleep(1)
        robot.require_component('//div[@accessiblename="取消"]')
        robot.screenshot(out=str(HERE/'shots'/'history-native-preview.png'))
        if not robot.click_component('//div[@accessiblename="取消"]'):raise RuntimeError('Preview cancel missing')
        time.sleep(.5);search('');time.sleep(.7)
    finally:print('frames',rec.stop(),'output',OUT,flush=True)

if __name__=='__main__':main()
