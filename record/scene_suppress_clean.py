# -*- coding: utf-8 -*-
"""分段录制屏蔽动作，通知关闭期间停录；用 --stage result 继续并删除本次规则。

起手要求：捕捉已开、SELECT 1 与普通查询在场、管理窗口已确认规则为空。
先运行 --stage action，手工或按实际 UI 关闭成功通知，核实画面干净后运行
--stage result。两段合成后，按 assemble.py 转制；不能把未检查的中间态当成片。
"""
import argparse
import subprocess
import time
from pathlib import Path

import anchor
import pyautogui
import robot
from gifkit import Recorder

HERE = Path(__file__).resolve().parent
REGION = (277, 25, 1370, 984)
MANAGE = '//div[contains(@class,"ActionButton") and contains(@accessiblename,"管理屏蔽规则")]'
EMPTY = '//div[contains(@accessiblename,"暂无屏蔽规则")]'


def click(xpath):
    if not robot.click_component(xpath):
        raise RuntimeError("Missing control: " + xpath)
    time.sleep(0.5)


def action():
    click(MANAGE)
    robot.require_component(EMPTY)
    click('//div[@accessiblename="关闭"]')
    xpath = '//div[contains(@class,"ViewportWidthSqlEntryList")]/parent::div'
    component = robot.require_component(xpath)
    cx, cy = robot.component_screen_center(xpath, component)
    region = (cx - component['width'] // 2, cy - component['height'] // 2,
              component['width'], component['height'])
    hit = anchor.require_anchor('native_heartbeat', region=region)
    recorder = Recorder(REGION, str(HERE / 'perf' / 'suppress_seg1.mp4'))
    recorder.start()
    try:
        time.sleep(0.7)
        pyautogui.moveTo(hit[0], hit[1], duration=0.3)
        pyautogui.rightClick(hit[0], hit[1])
        time.sleep(0.8)
        robot.require_component('//div[contains(@class,"ActionMenuItem") and contains(@accessiblename,"屏蔽此 SQL")]')
    finally:
        print('seg1 frames', recorder.stop(), flush=True)
    click('//div[contains(@class,"ActionMenuItem") and contains(@accessiblename,"屏蔽此 SQL")]')
    print('Close the notification and verify the clean viewport before --stage result.', flush=True)


def result():
    recorder = Recorder(REGION, str(HERE / 'perf' / 'suppress_seg2.mp4'))
    recorder.start()
    try:
        time.sleep(0.8)
        click(MANAGE)
        time.sleep(1)
        # 第一段已验证规则为空，第二段仅删除第一段创建并选中的演示规则。
        click('//div[@accessiblename="删除"]')
        robot.require_component(EMPTY)
        click('//div[@accessiblename="关闭"]')
        pyautogui.moveTo(1510, 650, duration=0.2)
        time.sleep(1)
    finally:
        print('seg2 frames', recorder.stop(), flush=True)
    concat = HERE / 'perf' / 'suppress-concat.txt'
    concat.write_text("file 'suppress_seg1.mp4'\nfile 'suppress_seg2.mp4'\n", encoding='utf-8')
    subprocess.run(['ffmpeg', '-y', '-v', 'error', '-f', 'concat', '-safe', '0',
                    '-i', str(concat), '-c', 'copy',
                    str(HERE / 'perf' / 'suppress_clean_native.mp4')], check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--stage', choices=('action', 'result'), required=True)
    args = parser.parse_args()
    (action if args.stage == 'action' else result)()
