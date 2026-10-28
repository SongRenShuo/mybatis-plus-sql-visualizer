# -*- coding: utf-8 -*-
"""R04 XML 动态 SQL 预览重录（T-DocsGifRecord1025 批 1）。

画面：UserMapper.xml 第 74 行 `selectUserByTrim` 上按 Alt+Shift+Q → 候选框列出六条
语句 → 选中 `update updateUserByXml (第 56 行)` → OK → 预览面板打开（UPDATE + SET
段）→ 逐个取消 email / status 条件，SET 段实时收缩 → 取消关闭 → 回跳到起始行。

**2026-10-25 修订的三处（旧片 93% 时长卡在候选框、从未渲染出预览）**：
  1. `POPUP_UPDATE_ROW` / `POPUP_OK` 坐标全错。实测（本机 120% 缩放、目录树收起）：
     候选框第 3 项 `updateUserByXml (第 56 行)` 在屏幕 y≈477，OK 在 (1116, 669)。
  2. 方向键在候选框里**不生效**（连按两次 down 仍停在首项），只能鼠标点行。
  3. 点行之后必须再点 OK，不能按 Enter——Enter 确认的是列表当前高亮项，
     而高亮并不跟随鼠标点击，实测会打开错误语句。
  4. 收尾改点「取消」：旧版点「复制 SQL 并关闭」会留一张常驻通知卡（独立 balloon
     窗口，robot-server 查不到、Esc 关不掉），尾帧与首帧对不上。

前置（prep）：UserMapper.xml 打开、goto(74)、无弹窗。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

import pyautogui
from PIL import Image
from gifkit import Recorder, click, goto_line, shot
from nav import force_en_us

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "r04_xml.mp4")

OX, OY = REGION[0], REGION[1]


def abs_pt(p):
    """region 相对坐标 → 屏幕绝对坐标。"""
    return (p[0] + OX, p[1] + OY)


# 「选择要预览的 SQL」候选框：updateUserByXml 是第 3 项，行中心 region 相对 y=452
POPUP_UPDATE_ROW = (623, 452)
# 候选框 OK 按钮（region 相对）
POPUP_OK = (839, 644)
# 预览面板动态条件复选框（region 相对，20261026 按图标中心重标——
# 旧值 (296,339) 在 x/y 双偏：x=296 不在图标上（热区=图标中心 x≈126），
# y=339 落在 username 行带上，上一 take 实测取消的是 username 而非 email）
COND_EMAIL = (126, 356)
COND_STATUS = (126, 386)
# 收尾「取消」按钮（region 相对）
CANCEL = (1212, 798)


def _open_panel():
    """候选框 → updateUserByXml → OK，等面板画完。"""
    pyautogui.hotkey("alt", "shift", "q")
    time.sleep(1.4)
    click(*abs_pt(POPUP_UPDATE_ROW), move_dur=0.3, pauses=(0.2, 0.7))
    click(*abs_pt(POPUP_OK), move_dur=0.25, pauses=(0.15, 1.8))


def _blue_rows():
    """当前面板里蓝色（勾选）复选框的行 y 集合。"""
    from gifkit import find_blue_checkboxes
    path = os.path.join(BASE, "perf", "_norm_r04.png")
    shot(REGION, path)
    rows = find_blue_checkboxes(path, strip_x=(110, 150), y_range=(300, 430))
    return set(y for y, _ in rows)


def _normalize_conditions():
    """复选框状态跨预览持久化——重录前跑 tmp_norm_r04.py（点面板「全选」按钮归一，
    像素检测在本面板判不准已弃用），主控目视 _norm_r04_after.png 确认。"""
    pass


def _assert_email_is_the_unchecked_one():
    """图标级校验：email 框必须已变灰、username/status 框仍为蓝（勾选）。"""
    import numpy as np
    path = os.path.join(BASE, "perf", "_chk_r04.png")
    shot(REGION, path)
    img = np.asarray(Image.open(path).convert("RGB"), dtype=np.int16)

    def is_blue(cy):
        # 用 10px 内芯判勾选：勾选内芯实心蓝（实测 79/85），未勾只剩边框（0）——
        # 18px 全框判据会把未勾框的蓝色边框算进去（76），分不开
        box = img[cy - 5:cy + 5, 121:131]
        r, g, b = box[..., 0], box[..., 1], box[..., 2]
        return int(((b - r > 60) & (b > 120)).sum()) > 40

    assert not is_blue(356), "email 复选框仍是勾选态——取消的不是 email，中止"
    assert is_blue(327) and is_blue(386), "username/status 复选框被误取消，中止"


def _assert_both_unchecked():
    """email 与 status 都已取消，username 仍勾选（与旧片双条件分镜一致）。"""
    import numpy as np
    path = os.path.join(BASE, "perf", "_chk_r04b.png")
    shot(REGION, path)
    img = np.asarray(Image.open(path).convert("RGB"), dtype=np.int16)

    def is_blue(cy):
        box = img[cy - 5:cy + 5, 121:131]
        r, g, b = box[..., 0], box[..., 1], box[..., 2]
        return int(((b - r > 60) & (b > 120)).sum()) > 40

    assert not is_blue(356) and not is_blue(386), "email/status 未双双取消，中止"
    assert is_blue(327), "username 被误取消，中止"


def main():
    force_en_us()
    time.sleep(0.4)
    # 开录前按路径精确打开目标文件（同名文件问题的根治手段，T-DemoGifFix1026 §2.2）
    try:
        from mcpnav import open_file
        open_file("mybatis-plus-demo/src/main/resources/mapper/UserMapper.xml", settle=1.6)
    except Exception as e:
        print("MCP 打不开，中止（不退回搜索方式，宁可不录也不开错文件）:", e)
        raise
    goto_line(74)
    time.sleep(0.4)
    # 首帧必须与尾帧同态：光标停在第 74 行（当前行有高亮底色）、gutter 图标已渲染。
    # 这里**不能点编辑器**——点一次就把光标挪到点击所在行（新构图下 (700,383) 落在
    # 第 73 行注释上，尾帧与首帧差 12.96%）。Esc 只关 goto 框，不动光标。
    pyautogui.press("esc")
    time.sleep(1.8)

    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(0.5)
        _open_panel()                                                        # 1-3. 候选框 → updateUserByXml → 面板

        from verify import Guard
        g = Guard(region=(470, 240, 720, 210))
        g.mark("sql")
        # 4. 取消 email → SET 段实时少一行。icon 中心 (126,356)；附近 y 兜底重试
        done = False
        for y in (356, 350, 362, 327):
            pyautogui.click(*abs_pt((126, y)))
            time.sleep(1.0)
            ok, score = g.changed("sql", wait=0.2)
            if ok:
                done = True
                break
        if not done:
            raise AssertionError("取消 email 后 SQL 区无变化——点击落空，中止")
        _assert_email_is_the_unchecked_one()
        # 4b. 再取消 status（与旧片分镜一致：双条件逐个取消，SET 段实时收缩）
        pyautogui.click(*abs_pt(COND_STATUS))
        time.sleep(1.2)
        _assert_both_unchecked()
        # 5. 收尾凝视（裸屏捕获不含光标，纯静置会被 assemble 裁掉——用缓慢移动撑住
        #    「SET 段实时少一行」的定格时长，同时把收窄后的 SET 段带一遍）
        pyautogui.moveTo(700, 292, duration=0.6)
        time.sleep(0.8)
        pyautogui.moveTo(640, 340, duration=0.7)
        time.sleep(0.8)
        pyautogui.moveTo(600, 390, duration=0.7)
        time.sleep(0.8)
        pyautogui.moveTo(900, 389, duration=0.8)
        time.sleep(0.8)
        pyautogui.moveTo(620, 300, duration=0.8)
        time.sleep(1.2)

        # 5. 收尾（T-DemoGifFix1026 要求）：**停在预览弹窗上**，画面里留着
        #    「取消 email 后 SET 段实时少一行」的结果——旧片尾帧约 20% 是静态
        #    编辑器（复核判定的空尾巴）。首尾同态在本片让位：结构性跳变，
        #    验收按「尾帧停在演示效果上」口径（计划 §三 #4）。
        time.sleep(1.6)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
