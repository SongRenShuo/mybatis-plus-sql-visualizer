# -*- coding: utf-8 -*-
"""R03 注解 SQL 预览重录（T-DocsRefresh1023）：@Select script 行 gutter → 预览 → 参数回填 → 复制并关闭。

相对 20260912 版（scene_t7）的差异：
- 底部按钮行改四枚，收尾点蓝色主行动「复制 SQL 并关闭」（旧片是点「取消」）；
- 参数框坐标按当前弹窗实测重标；
- gutter 图标仍走像素检测（组件树看不见 gutter）。

前置（prep）：UserMapper.java 打开、goto(33)、无弹窗。

【2026-10-25 T-DocsGifRecord1025 修订】旧片在参数框里出现中文输入法候选框
（绿底「1 送」+「2 Song 3 宋…」），并把汉字「送」打进 `#username`，最终渲染成
`WHERE username = '送'`。根因＝`typewrite` 没切英文布局，本机 IME 为中文态。
修法＝每次键入前调 `nav.force_en_us()`（`LoadKeyboardLayoutW('00000409')` +
`PostMessage(WM_INPUTLANGCHANGEREQUEST)`；只调 ActivateKeyboardLayout 无效，
2026-10-25 P1 证伪已实测）。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

from gifkit import (Recorder, click, find_gutter_icons, goto_line, pyautogui,
                    shot, typewrite, wait_stable)
from nav import force_en_us
from robot import click_component, find_components

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "r03_annotation.mp4")

OX, OY = REGION[0], REGION[1]


def abs_pt(p):
    """region 相对坐标 → 屏幕绝对坐标（gifkit.click 收绝对坐标）。"""
    return (p[0] + OX, p[1] + OY)


def has(xp):
    return len(find_components(xp)) > 0


def locate_gutter(y_range=(100, 860)):
    path = os.path.join(BASE, "perf", "_pre_r03.png")
    shot(REGION, path)
    # 条带必须覆盖插件图标本体：目录树收起后它落在 region 相对 x≈70~82
    # （2026-10-25 实测；默认 strip_x=(95,130) 只框到灯泡/折叠箭头，检测恒为空）
    icons = find_gutter_icons(path, strip_x=(85, 115), y_range=y_range, gap=12)
    if not icons:
        raise RuntimeError("gutter icon not found")
    center = (y_range[0] + y_range[1]) / 2
    y, x = min(icons, key=lambda p: abs(p[0] - center))
    print("icons:", icons, "-> picked", (y, x))
    return x + OX, y + OY


def param_fields():
    """参数输入框坐标（region 相对）：弹窗内 robot 组件树看不到，按当前布局实测标定。"""
    return [(303, 436), (303, 484)]


def main():
    force_en_us()                            # 先把输入法切英文，否则参数值会被转成汉字
    time.sleep(0.5)
    # 开录前按路径精确打开目标文件（同名文件问题的根治手段，T-DemoGifFix1026 §2.2）
    try:
        from mcpnav import open_file
        open_file("mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/mapper/UserMapper.java",
                  settle=1.6)
    except Exception as e:
        print("MCP 打不开，中止（不退回搜索方式，宁可不录也不开错文件）:", e)
        raise
    goto_line(37)
    gutter = locate_gutter()

    rec = Recorder(REGION, OUT)
    time.sleep(1.2)
    rec.start()
    try:
        time.sleep(0.5)
        click(*gutter)                       # 1. gutter 点开预览
        wait_stable(REGION)
        time.sleep(1.3)                      # 弹窗定格
        # 闸门 2（画面断言）：预览面板必须已就位，否则本 take 作废
        if not has("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
            raise RuntimeError("预览面板未出现，中止本次 take")

        # 参数框的值会跨次保留（上一轮录完不清空，下一轮再点进去旧值还在，
        # 直接 typewrite 就变成 songsongsong… / 111111）。所以先全选删干净再输。
        fields = param_fields()
        for idx, val in enumerate(("song", "1")):
            click(*abs_pt(fields[idx]), move_dur=0.3)
            pyautogui.hotkey("ctrl", "a")
            time.sleep(0.2)
            pyautogui.press("delete")
            time.sleep(0.2)
            typewrite(val, interval=0.06)
            time.sleep(0.5)
        time.sleep(1.8)                      # SQL 回填定格

        # 3. 取消关闭弹窗（收尾）
        #    旧版点的是蓝色主行动「复制 SQL 并关闭」，会在右下角留一张常驻的
        #    「SQL 已复制到剪贴板」通知卡——独立 balloon 窗口，robot-server 查不到、
        #    Esc 关不掉（2026-10-25 实测连按两次仍在），尾帧因此与首帧对不上
        #    （接缝 2.03%，27433 个差异像素里 11432 个是它）。
        #    改点「取消」：同样关闭弹窗，但不产生通知卡。
        click(*abs_pt((1212, 798)), move_dur=0.3)
        time.sleep(1.0)
        # 收尾**不再 goto**：关窗后光标仍在第 37 行（prep 落点），与首帧同态。
        # 旧版这里 goto_line(37) 在录制窗口内弹 Ctrl+G，正是旧片帧 140-163 的
        # 「Go to Line:Column」入镜来源（T-DemoGifFix1026 §2.1）。
        time.sleep(0.8)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
