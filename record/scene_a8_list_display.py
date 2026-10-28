# -*- coding: utf-8 -*-
"""从默认关键字换行切换单行，展示列表重绘；录完恢复关键字换行。"""
from pathlib import Path
import sys,time
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE))
import robot,pyautogui
from gifkit import Recorder
OUT=HERE/'perf'/'a8_list_display_native.mp4'
REGION=(277,25,1370,984)
MODE='//div[contains(@accessiblename,"SQL 显示模式")]/parent::div//div[contains(@class,"ComboBox")]'
SETTINGS='//div[contains(@class,"ActionButton") and contains(@accessiblename,"设置")]'


def click(x):
    if not robot.click_component(x):raise RuntimeError('Missing control '+x)
    time.sleep(.5)


def reveal_mode():
    c=robot.require_component(MODE);cx,cy=robot.component_screen_center(MODE,c)
    if 280<cy<780:return
    bars='//div[contains(@accessiblename,"SQL 显示模式")]/ancestor::div[contains(@class,"JBScrollPane")]//div[contains(@class,"ScrollBar")]'
    vs=[c for c in robot.find_components(bars) if c['width']<c['height']]
    if len(vs)!=1:raise RuntimeError('Settings vertical scrollbar ambiguous')
    c=vs[0];chain=robot.find_components('('+bars+')/ancestor::div')
    roots=[i for i,p in enumerate(chain) if p['className'].endswith('$FloatDialog')]
    if len(roots)!=1:raise RuntimeError('Settings window root missing')
    cx=c['x']+c['width']//2+sum(p['x'] for p in chain[roots[0]:]);cy=c['y']+sum(p['y'] for p in chain[roots[0]:])
    pyautogui.moveTo(cx,cy+60,duration=.25);pyautogui.dragTo(cx,cy+235,duration=.8);time.sleep(.6)
    c=robot.require_component(MODE);cx,cy=robot.component_screen_center(MODE,c)
    if not 280<cy<780:raise RuntimeError('Display mode remains outside view')


def choose(index):
    click(MODE)
    pyautogui.press('home');time.sleep(.15)
    for _ in range(index):pyautogui.press('down');time.sleep(.15)
    pyautogui.press('enter');time.sleep(.6)


def main():
    # 选择框已打开时 Enter 确认当前默认值，不取消整个设置对话框。
    pyautogui.press('enter');time.sleep(.5)
    robot.require_component(MODE);reveal_mode()
    rec=Recorder(REGION,str(OUT));rec.start()
    try:
        click(MODE);time.sleep(.9)
        pyautogui.press('home');pyautogui.press('enter');time.sleep(.6)
        robot.screenshot(out=str(HERE/'shots'/'display-native-choice.png'))
        click('//div[@accessiblename="OK"]');time.sleep(1)
        if robot.find_one(MODE):raise RuntimeError('Settings did not close')
        robot.screenshot(out=str(HERE/'shots'/'display-native-single.png'));time.sleep(1)
    finally:
        print('frames',rec.stop(),'output',OUT,flush=True)
        if not robot.find_one(MODE):click(SETTINGS)
        robot.require_component(MODE);reveal_mode();choose(1)
        click('//div[@accessiblename="OK"]');time.sleep(.7)
        robot.screenshot(out=str(HERE/'shots'/'display-native-reset.png'))

if __name__=='__main__':main()
