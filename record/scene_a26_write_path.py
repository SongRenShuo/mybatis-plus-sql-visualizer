# -*- coding: utf-8 -*-
"""A26 JPA Repository CRUD 写路径（jpa-derived-query.gif）20261012。

素材：`UserWriteServiceImpl`（jpa-hibernate-demo）一个文件里就能把写路径讲全——
      `save` / `deleteById` / `updateStatusByJpql` 三个调用点各自解析出 INSERT / DELETE / UPDATE。
流程：光标压在调用点的方法名 token 上 → Alt+Shift+Q 开预览
      第一幕 `save` → `INSERT INTO user (列…) VALUES (?, …)`，左侧还有「参数列」勾选面板，
      取消勾选 `user.age` 后 INSERT 的列清单实时少一列；
      第二幕 `deleteById` → `DELETE FROM user WHERE id = ?`，并带一行灰注释点明
      「逐实体 EntityManager.remove 语义（非 bulk DELETE）」。

标定（20261012，IDE @ 277,25）：
  编辑器取焦落点      screen (600,500)
  预览浮窗体内（关窗用）screen (760,400)
  「参数列」user.age 复选框 screen (429,713)
  SQL 文本区凝视落点  screen (820,340) / (820,410)

坑：① 跳行对话框残留输入 → goto() 内先 Ctrl+A 清空；
    ② IntelliJ 的 Home 是智能 Home → goto_col() 内按两下到绝对行首；
    ③ 光标必须压在 token 上（落行首缩进 anchor=null 只弹通知）；
    ④ 该浮窗尺寸随内容变（带「参数列」面板的更宽）→ 关窗用「点进窗体 + Esc」。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

from gifkit import Recorder
from nav import close_preview, force_en_us, goto_col, shot, trigger_preview

REGION = (285, 26, 1354, 974)
OUT = "perf/a26_write_path.mp4"

FILE = "UserWriteServiceImpl.java"
FILE_REL = "jpa-hibernate-demo/src/main/java/cn/song/jpademo/service/impl/UserWriteServiceImpl.java"
RESULT_ROW = (777, 342)
EDITOR_SPOT = (600, 500)
DLG_BODY = (760, 400)
# 「参数列」user.age 复选框（屏幕坐标，20261027 按当前面板重标：
# 8 行勾选框 x≈131(region)，user.age 第 5 行 region y≈527 → abs (416,553)；
# 旧值 (429,713) 落空——正是旧片「取消勾选没拍到、SQL 零差异」的根因）
CHK_AGE = (416, 553)
GAZE_A = (820, 340)
GAZE_B = (820, 410)

LINE_SAVE, COL_SAVE = 56, 35          # userWriteRepository.save(user)
LINE_DEL, COL_DEL = 102, 28           # userWriteRepository.deleteById(id)


def main():
    import pyautogui
    import gifkit

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    # ---- prep（不入镜）：开文件 + 光标压到 save 调用点 + 开好第一张预览 ----
    #    开文件改走 MCP（按路径精确打开）：Ctrl+Shift+N + typewrite 那条路在 demo 里
    #    会撞同名文件，而且 typewrite 在中文 IME 下会把文件名变成候选框（2026-10-25）。
    close_preview(body_xy=DLG_BODY)
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
    goto_col(LINE_SAVE, COL_SAVE)
    pyautogui.press("esc")          # 关掉 goto 输入框
    time.sleep(0.4)
    trigger_preview()
    # 归一：参数列复选框状态跨预览持久化——点面板「全选」保证 8 列全勾再开录
    # （「全选」按钮 region(319,291) → abs(604,317)，20261027 实测）
    pyautogui.click(604, 317)
    time.sleep(0.8)

    r = Recorder(REGION, OUT)
    r.start()

    # 1. 起手凝视 INSERT：沿 SQL 行缓慢下移（assemble 会裁静止段，靠移动撑时长）
    pyautogui.moveTo(660, 300, duration=0.5)
    pyautogui.moveTo(*GAZE_A, duration=1.0)
    pyautogui.moveTo(*GAZE_B, duration=1.0)

    # 2. 参数列勾选：取消 user.age → INSERT 的列清单实时少一列
    #    20261026 闸门补强：旧片这一步点击落空、SQL 逐帧零差异却照常入库——
    #    现在点击后断言 SQL 区必须变化，落空即重试再中止。
    from verify import Guard
    g = Guard(region=(600, 290, 520, 200))
    # thr=1.5：8×8 分块下「列清单少一词」的实测差异只有 2.1（20261027），4.0 会漏判
    g.after_click(CHK_AGE, retries=2, wait=1.2, thr=1.5,
                  msg="取消勾选 user.age 后 SQL 区无变化——点击落空")

    # 3. 切到 deleteById：关窗 → 定位 → 再预览（DELETE + 逐实体 em.remove 语义注释）
    #    **录制窗口内禁 goto***（T-DemoGifFix1026 §2.1，修法 2＝Ctrl+F 唯一文本定位）。
    #    ⚠️ 不用 Down×N：编辑器有折叠区（import/Javadoc），Down 穿折叠一次跳多行，
    #    上一 take 实测 46 次 Down 落到 108 而非 102。
    close_preview(body_xy=DLG_BODY, wait=1.1)
    pyautogui.hotkey("ctrl", "f")
    time.sleep(0.7)
    pyautogui.hotkey("ctrl", "a")
    time.sleep(0.2)
    gifkit.paste("userWriteRepository.deleteById")     # 文件内唯一（仅 L102 调用点）
    time.sleep(0.8)
    pyautogui.keyDown("return"); pyautogui.keyUp("return")   # 跳到匹配（选中态）
    time.sleep(0.5)
    pyautogui.keyDown("esc"); pyautogui.keyUp("esc")         # 先关查找条（条开着时 Left 被它吃掉）
    time.sleep(0.3)
    pyautogui.press("left")          # 收起到匹配起点（userWriteRepository 的 'u'，token 上）
    time.sleep(0.3)
    # wait=2.6 不够：实测弹窗要 ~3s 才画完，中途开录会录到半透明/错位的渲染中间态
    # （本轮第 70 帧就录成了「参数列浮在编辑器上、弹窗本体看不见」）。
    trigger_preview(wait=3.6)

    # 4. 收尾凝视：DELETE / WHERE 与灰注释同屏
    pyautogui.moveTo(*GAZE_A, duration=1.0)
    pyautogui.moveTo(760, 430, duration=1.0)
    time.sleep(0.6)

    shot("perf/a26_final.png")
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
