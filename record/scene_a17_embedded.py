# -*- coding: utf-8 -*-
"""A17 JPA 嵌入值对象 @Embedded 展平列名（jpa-embedded.gif）20261012。

前置：demo 的 `CustomerRepository`（jpa-hibernate-demo）有两个嵌套嵌入派生方法——
      `findByAddressZipcodeZip`（属性路径 address.zipcode.zip 两级穿透 → WHERE cust_zip）
      `findByBizAddressStreet`（@Embedded prefix=biz_ → WHERE biz_street，不是 biz_biz_street）。
流程：光标压在方法名 token 上 → Alt+Shift+Q 开预览（SELECT 出全部展平列名）
      → 往 #zip 参数框填值 → WHERE cust_zip = ? 变实值
      → 关弹窗 → 跳到第二个派生方法 → 再预览 → WHERE biz_street = ?（前缀展平对照）。

标定（20261012，IDE @ 277,25）：
  文件搜索结果行        screen (777,342)
  编辑器取焦落点        screen (600,500)
  参数框 #zip           screen (593,417)
  SQL 里的 cust_zip 行  screen (866,411)
  SQL 里的 biz_street 行 screen (852,477)
  SQL 里的 WHERE 行     screen (887,669)
  预览浮窗「取消」      screen (1485,828)

坑：① 跳行对话框会残留上次输入 → goto() 内已先 Ctrl+A 清空；
    ② IntelliJ 的 Home 是智能 Home，列号基准要按两下才到绝对行首 → goto_col() 内已处理；
    ③ 光标必须压在 token 上（落行首缩进 anchor=null，只弹通知不弹预览）。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

from gifkit import Recorder
from nav import close_preview, force_en_us, goto, goto_col, shot, trigger_preview

REGION = (285, 26, 1354, 974)
OUT = "perf/a17_embedded.mp4"

FILE = "CustomerRepository.java"
FILE_REL = "jpa-hibernate-demo/src/main/java/cn/song/jpademo/repository/CustomerRepository.java"
RESULT_ROW = (777, 342)
EDITOR_SPOT = (600, 500)
# 参数输入框（屏幕坐标，20261027 按当前面板重标：#zip/#street 输入框在 region(211,285)-(385,308)，
# 中心 abs=(583,322)——旧值 (593,417) 落在框下方，上一 take 实测没填上）
PARAM_ZIP = (583, 322)
PARAM_STREET = (583, 322)      # 第二幕 #street 输入框与 #zip 同位（面板布局相同）
SQL_CUST_ZIP = (866, 411)
SQL_BIZ_STREET = (852, 477)
SQL_WHERE = (887, 669)

LINE_A, COL_A = 21, 26          # findByAddressZipcodeZip
LINE_B, COL_B = 26, 26          # findByBizAddressStreet


def main():
    import pyautogui
    import gifkit

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    # ---- prep（不入镜）：开文件 + 光标压到方法名 token + 已开好第一张预览 ----
    # 开文件改走 MCP（按路径精确打开，绕开同名文件与中文 IME）
    close_preview()
    try:
        from mcpnav import open_file
        open_file(FILE_REL, settle=1.6)
    except Exception as e:
        print("MCP 打不开，退回搜索方式:", e)
        pyautogui.click(*EDITOR_SPOT)
        time.sleep(0.5)
        pyautogui.hotkey("ctrl", "shift", "n")
        time.sleep(1.4)
        pyautogui.hotkey("ctrl", "a")
        time.sleep(0.3)
        force_en_us()
        pyautogui.typewrite(FILE, interval=0.05)
        time.sleep(1.8)
        pyautogui.click(*RESULT_ROW)
        time.sleep(0.4)
        pyautogui.click(*RESULT_ROW)
        time.sleep(2.5)
    goto_col(LINE_A, COL_A)
    pyautogui.press("esc")
    time.sleep(0.5)
    trigger_preview()

    r = Recorder(REGION, OUT)
    r.start()

    # 1. 起手凝视：沿 SELECT 的展平列名往下走——assemble 会裁静止段，得用缓慢移动撑住时长，
    #    同时让 cust_street / cust_zip / biz_street / biz_zip 这一串「点路径展平」看清。
    pyautogui.moveTo(600, 300, duration=0.5)
    pyautogui.moveTo(*SQL_CUST_ZIP, duration=1.0)
    pyautogui.moveTo(*SQL_BIZ_STREET, duration=1.0)
    pyautogui.moveTo(*SQL_WHERE, duration=0.9)

    # 2. #zip 参数回填：WHERE cust_zip = ? → 实值（先全选清掉跨次残留再粘贴）
    pyautogui.click(*PARAM_ZIP)
    time.sleep(0.5)
    pyautogui.hotkey("ctrl", "a")
    time.sleep(0.2)
    pyautogui.press("delete")
    time.sleep(0.2)
    gifkit.paste("100085")
    time.sleep(1.8)

    # 3. 关弹窗 → 移到第二个派生方法（对照：@Embedded(prefix=biz_) 展平）
    #    显式等弹窗画完再继续：wait=0.0 靠后面 sleep 补，实测会录到半渲染态
    #    close_preview(wait=1.2)
    close_preview(wait=1.2)
    # **录制窗口内禁 goto***（T-DemoGifFix1026 §2.1）：21→26 只差 5 行，
    # 用 Down 键保列移动（col 26 在两行的方法名 token 上），零弹窗零文档副作用。
    for _ in range(LINE_B - LINE_A):
        pyautogui.press("down")
        time.sleep(0.05)
    time.sleep(0.6)
    trigger_preview(wait=3.4)

    # 4. #street 参数回填（第二幕对照动作）：WHERE biz_street = ? → 实值
    pyautogui.click(*PARAM_STREET)
    time.sleep(0.5)
    pyautogui.hotkey("ctrl", "a")
    time.sleep(0.2)
    pyautogui.press("delete")
    time.sleep(0.2)
    gifkit.paste("No.88 Renmin Rd")
    time.sleep(1.8)

    # 5. 收尾凝视：biz_street 与 WHERE 行同屏——证明前缀只拼一次
    pyautogui.moveTo(*SQL_BIZ_STREET, duration=1.0)
    pyautogui.moveTo(*SQL_WHERE, duration=1.0)
    time.sleep(0.6)

    shot("perf/a17_final.png")
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
