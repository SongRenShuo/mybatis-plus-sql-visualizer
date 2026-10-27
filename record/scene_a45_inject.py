# -*- coding: utf-8 -*-
"""A45 原生 MyBatis 拦截器自动注入（interceptor-inject.gif）20261011：
设置页勾选「自动注入源码捕获拦截器」→ 选择注入模块对话框（prep 已打开）→
只保留 native-mybatis-demo → OK → 生成拦截器 + mybatis-config.xml 注册 + 通知气球。
标定（20261011 实测，屏幕坐标＝区域坐标+277/+25）：模块复选框 x=740，行 y=468/503/538；OK (1052,628)。
录制后须还原 demo 仓工作树（注入会写文件）。
"""
import sys
import time

from gifkit import Recorder, click

REGION = (285, 26, 1354, 974)
OUT = "perf/a45_inject.mp4"

CHK_DEMO = (740, 468)
CHK_KOTLIN = (740, 503)
OK_BTN = (1052, 628)


def main():
    # ---- prep（不入镜）：打开设置 → SQL Preview → 勾「自动注入」→ 触发模块对话框 ----
    # 20261027：原脚本无 prep，前 221 帧（14.7s）全静止被 assemble 裁掉
    import gifkit
    import pyautogui
    from robot import find_components as _fc, click_component as _cc
    # 关残留弹窗
    for _ in range(2):
        if _fc("//div[@accessiblename='Cancel']"):
            _cc("//div[@accessiblename='Cancel']"); time.sleep(1.0)
    # 开设置并进 SQL Preview 页（坐标：左树 SQL Preview 行）
    pyautogui.hotkey("ctrl", "alt", "s"); time.sleep(2.6)
    pyautogui.click(320, 440); time.sleep(1.8)      # 左树 SQL Preview
    # 勾「自动注入」（文本定位）
    inj = None
    for xp in ("//div[contains(@accessiblename,'自动注入')]",
               "//div[contains(@text,'自动注入')]"):
        if _fc(xp): inj = xp; break
    if inj:
        _cc(inj); time.sleep(2.0)
        print("[prep] 已勾自动注入")
    else:
        print("[prep] 未找到「自动注入」勾选框")
    # **关键**：设置对话框是模态的，必须点 OK 关掉，模块选择对话框才会独立显示
    #（20261027：上一版没关，设置框一直盖住半幅画面）
    ok = None
    for xp in ("//div[@accessiblename='OK']",):
        if _fc(xp): ok = xp; break
    if ok:
        _cc(ok); time.sleep(2.4)
        print("[prep] 设置已关闭（OK）")
    else:
        pyautogui.press("enter"); time.sleep(2.0)
    # 断言：模块选择对话框应在场（有复选框列表）
    from robot import find_components as _f2
    mod_dlg = _f2("//div[contains(@accessiblename,'选择')]") or _f2("//div[contains(@class,'JBCheckBox')]")
    print("[prep] 模块对话框在场:", bool(mod_dlg))

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.4)

    # 1. 只保留 native-mybatis-demo
    click(*CHK_DEMO, move_dur=0.3, pauses=(0.15, 0.25))
    time.sleep(0.5)
    click(*CHK_KOTLIN, move_dur=0.3, pauses=(0.15, 0.25))
    time.sleep(0.6)

    # 2. OK → 注入
    click(*OK_BTN, move_dur=0.35, pauses=(0.2, 0.2))
    time.sleep(2.4)  # 生成文件 + 通知气球
    # 二次真实变化：打开已注入的拦截器产物（展示「自动注入」落点）
    try:
        from mcpnav import open_file
        open_file("mybatis-plus-join-demo/src/main/java/cn/song/mpjdemo/config/"
                  "SqlSourceInterceptor.java", settle=2.4)
    except Exception as e:
        print("(打开产物跳过)", str(e)[:50])
    time.sleep(2.0)
    # 凝视产物内容（类注释里的标记格式 + beforeQuery/beforeUpdate 两个方法）
    # ⚠️ 落点必须避开标识符：压在方法名上会弹列文档浮层且「粘住」，
    # 把左半幅整块挡住（20261027 实测，坑 27）。改停右侧空白列。
    pyautogui.moveTo(1150, 400, duration=0.9); time.sleep(2.0)
    pyautogui.moveTo(1150, 620, duration=0.8); time.sleep(2.0)
    pyautogui.moveTo(1150, 300, duration=0.8); time.sleep(1.8)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
