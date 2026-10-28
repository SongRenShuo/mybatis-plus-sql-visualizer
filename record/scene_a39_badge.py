# -*- coding: utf-8 -*-
"""数据源角标完整上下文开关，结束恢复原先关闭状态。"""
from pathlib import Path
import time
import robot,pyautogui
from gifkit import Recorder
from scene_a8_list_display import reveal_mode,click,MODE,SETTINGS
HERE=Path(__file__).resolve().parent
REGION = (285, 26, 1354, 974)
OUT=HERE/'perf'/'a39_badge_native.mp4'
BADGE='//div[contains(@class,"CheckBox") and contains(@accessiblename,"数据源角标显示完整上下文")]'


def main():
    click(SETTINGS);robot.require_component(MODE);reveal_mode();robot.require_component(BADGE)
    rec=Recorder(REGION,str(OUT));rec.start()
    changed=False
    try:
        time.sleep(.7);click(BADGE);changed=True
        robot.screenshot(out=str(HERE/'shots'/'badge-native-setting.png'))
        click('//div[@accessiblename="OK"]');time.sleep(1.8)
        # 二次真实变化：打开 SQL Visualizer 工具窗并回日志页，看角标从短态变完整态。
        pyautogui.click(1618, 247);time.sleep(1.8)
        xp='//div[contains(@class,"TabLabel") and contains(@accessiblename,"SQL 日志")]'
        if robot.find_components(xp):
            robot.click_component(xp);time.sleep(1.2)
        pyautogui.moveTo(1510, 300, duration=.6);time.sleep(1.4)
        pyautogui.moveTo(1460, 400, duration=.7);time.sleep(1.4)
        robot.screenshot(out=str(HERE/'shots'/'badge-native-final.png'))
    finally:
        print('frames',rec.stop(),'output',OUT,flush=True)
        if changed:
            if not robot.find_one(MODE):click(SETTINGS)
            robot.require_component(MODE);reveal_mode();click(BADGE)
            click('//div[@accessiblename="OK"]');time.sleep(.6)
            robot.screenshot(out=str(HERE/'shots'/'badge-native-reset.png'))

if __name__=='__main__':main()
