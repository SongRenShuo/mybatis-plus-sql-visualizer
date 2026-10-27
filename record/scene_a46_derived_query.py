# -*- coding: utf-8 -*-
"""★A46 派生查询深化（jpa-derived-query.gif）——T-DemoGifFix1026 重录版。

素材：`jpa-hibernate-demo` 的 `UserRepository.java`（demo 里已有全套派生方法，无需新增）。
成片三幕，各挑一个「形态差异明显」的派生方法开预览：
  ① `findTop10ByOrderByCreateTimeDesc`（L90）→ LIMIT 10；
  ② `findByUsernameLike`（L67）→ LIKE ?（参数值自动补 %）；
  ③ `Page<UserEntity> findByDepartmentIdAndStatusOrderByCreateTimeDesc`（L119）→ 返回类型感知，
     出「count + 数据」两条 SQL。

20261026 重录改动（复核：两处 Go to Line:Column 弹窗入镜，帧 25-44 / 153-171）：
  - REGION 对齐新构图 (285,26,1354,974)（旧脚本还是 (277,25,1370,984)）；
  - 开文件改 MCP 按路径精确打开（旧 Ctrl+Shift+N + typewrite 会撞 IME 与同名文件）；
  - **录制窗口内零 goto***：90→67 用 Up×23、67→119 用 Down×52（col 22 恰为三个
    派生方法名 token 的起始列，方向键保列即落 token），零弹窗零文档副作用；
  - 每次预览打开后 robot 断言面板就位（闸门 2）。

标定（20261026，IDE @ (277,25)，与 20261012 版同位）：预览 SQL 区 screen (880,430)/(880,500)/(880,620)。
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "lib"))
sys.path.insert(0, HERE)

import pyautogui  # noqa: E402
from gifkit import Recorder  # noqa: E402
from nav import close_preview, force_en_us, goto_col, trigger_preview  # noqa: E402
from robot import find_components  # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = os.path.join(HERE, "perf", "a46_derived_query.mp4")

FILE_REL = "jpa-hibernate-demo/src/main/java/cn/song/jpademo/repository/UserRepository.java"

LINE_TOP, COL = 90, 22          # findTop10ByOrderByCreateTimeDesc
LINE_LIKE = 67                  # findByUsernameLike
LINE_PAGE = 119                 # Page<...> findByDepartmentIdAndStatus...

GAZE_SQL_A = (880, 430)
GAZE_SQL_B = (880, 500)
GAZE_SQL_C = (880, 620)


def assert_preview():
    if not find_components("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
        raise RuntimeError("预览面板未出现，中止本次 take")


def goto_text_in_rec(text):
    """录制窗口内定位（T-DemoGifFix1026 修法 2）：Ctrl+F 唯一文本，不走 goto*。
    ⚠️ 不用方向键计数——编辑器折叠区（import/Javadoc）会让 Down 一次跳多行。"""
    import gifkit
    pyautogui.hotkey("ctrl", "f")
    time.sleep(0.45)
    pyautogui.hotkey("ctrl", "a")
    time.sleep(0.2)
    gifkit.paste(text)                                     # 文件内唯一串，绕 IME
    time.sleep(0.55)
    pyautogui.keyDown("return"); pyautogui.keyUp("return")  # 跳到匹配（选中态）
    time.sleep(0.4)
    pyautogui.keyDown("esc"); pyautogui.keyUp("esc")        # 先关查找条（焦点回编辑器——
    time.sleep(0.3)                                         # 条还开着时 Left 会被它吃掉）
    pyautogui.press("left")          # 收起到匹配起点：匹配末尾是 ')' 等
    time.sleep(0.25)                  # 非 token 字符，anchor 会判 null（20261027 实测）


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    # ---- prep（不入镜）：MCP 开文件 + 光标压到第一个派生方法 + 开好第一张预览 ----
    close_preview()
    try:
        from mcpnav import open_file
        open_file(FILE_REL, settle=1.6)
    except Exception as e:
        print("MCP 打不开，中止（不退回搜索方式）:", e)
        raise
    goto_col(LINE_TOP, COL)
    pyautogui.press("esc")          # 关掉 goto 输入框
    time.sleep(0.4)
    trigger_preview()
    time.sleep(1.0)
    assert_preview()

    r = Recorder(REGION, OUT)
    r.start()
    try:
        # ① Top10 → LIMIT（预览已在 prep 开好，凝视即可）
        pyautogui.moveTo(880, 430, duration=0.9); time.sleep(1.6)
        pyautogui.moveTo(*GAZE_SQL_B, duration=0.8); time.sleep(1.4)

        # ② Like → LIKE ?：关窗 → Ctrl+F 定位 findByUsernameLike → 再预览
        close_preview(wait=1.2)
        goto_text_in_rec("findByUsernameLike(String username)")   # 带签名防撞 L291 Jpql 变体
        trigger_preview(wait=3.4)
        assert_preview()
        pyautogui.moveTo(*GAZE_SQL_A, duration=0.8); time.sleep(1.4)

        # ③ Page → count + 数据 两条 SQL：关窗 → Ctrl+F 定位分页派生方法 → 再预览
        close_preview(wait=1.2)
        goto_text_in_rec("findByDepartmentIdAndStatusOrderByCreateTimeDesc(Long")
        trigger_preview(wait=3.4)
        assert_preview()
        pyautogui.moveTo(*GAZE_SQL_A, duration=0.8); time.sleep(1.2)
        pyautogui.moveTo(*GAZE_SQL_C, duration=0.9); time.sleep(1.4)
    finally:
        print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
