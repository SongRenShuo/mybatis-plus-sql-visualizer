# -*- coding: utf-8 -*-
"""A14 XML OGNL 表达式面板（xml-ognl.gif）20261011 终版：
前置：UserMapper.xml 已开、光标在 29 行 <where> 内、预览弹窗已开（点行内 + Alt+Shift+Q）。
流程：勾掉 age 条件（SQL 少 AND age >= ?）→ #username 参数框填 'tom'
→ LIKE concat('%', ?, '%') 实值替换。
标定（20261011 屏幕坐标，本次弹窗实测）：age 复选框 (367,425)；#username 输入框 (578,502)。每次弹窗打开位置须现标。
"""
import sys
import time

from gifkit import Recorder

REGION = (285, 26, 1354, 974)
OUT = "perf/a14_ognl.mp4"

CHK_AGE = (412, 414)      # age 复选框（probe_panel_coords 实测）
IN_USERNAME = (685, 460)  # #username 输入框（probe 实测，旧值整体偏左 220px）
IN_STATUS = (685, 509)    # #status 输入框（probe 实测）


def main():
    import pyautogui
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    import gifkit

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.8)

    # 1. 勾掉 age → WHERE 实时少一行 AND age >= ?（定格久一些，让观众看清）
    pyautogui.click(*CHK_AGE)
    time.sleep(2.6)

    # 2. 勾回 age（双向演示；也为下一步「填值即替换」建立有效条件）
    pyautogui.click(*CHK_AGE)
    time.sleep(2.2)

    # 3. #username 填值 → LIKE concat 里的 ? 实时替换成实值
    #    20261027 修：复核指出旧片「填值落在已取消勾选的条件上、SQL 零变化」，
    #    故先勾回再填，保证「填值即替换」真实成立
    pyautogui.click(*IN_USERNAME)
    time.sleep(0.5)
    pyautogui.hotkey("ctrl", "a")
    time.sleep(0.2)
    pyautogui.press("delete")
    time.sleep(0.2)
    gifkit.paste("tom")          # 不带引号：引号由插件按字段类型决定
    time.sleep(2.6)

    # 4. #status 填值 → AND status = ? 变实值
    pyautogui.click(*IN_STATUS)
    time.sleep(0.5)
    pyautogui.hotkey("ctrl", "a")
    time.sleep(0.2)
    pyautogui.press("delete")
    time.sleep(0.2)
    gifkit.paste("1")
    time.sleep(2.8)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
