# -*- coding: utf-8 -*-
"""N05「导出回放脚本」重录（T-DocsGifRecord1025，2026-10-25 二次重写）。

分镜（约 13 秒）：
1. 预览面板打开（MPJ 三表 JOIN），停 1.2s
2. 「更多」→ 键盘导航到「导出回放脚本」→ 导出对话框（事务包裹勾选项），停 1.2s
3. 取消 / 重勾「事务包裹」→ 说明行联动，停
4. 点 OK → 原生保存框（文件名已填 replay.sql 或提示「已导出」）
5. 保存 → 编辑器打开导出的 .sql，展示注释头（生成时间 / 方言 / 语句条数 / 事务包裹说明），停 2.5s

**2026-10-25 二次重录要点**（上一版成片污染，本轮全部修掉）：
- `replay.sql` 必须在沙盒**项目设置里配过 PostgreSQL 方言**，否则每次打开都弹
  「SQL dialect is not configured.」橙色横幅。配置路径：横幅 → `Change dialect to...`
  → 选 PostgreSQL → OK（写进 demo 工程 `.idea/sqlDataSources.xml` 同级配置，一次生效）。
- **保存框不要补 Enter**：点「保存(S)」已经生效，补的 Return 会落到保存后新激活的
  Chrome 上，把浏览器抢到前台（上一版就是这么污染的）。只点按钮。
- 「更多」菜单**必须键盘导航**（`End` 后 `Up×3`）：悬停态下鼠标点菜单项会先关菜单、
  点击穿透到编辑器。
- 打开 .sql 后用 robot 点「取消」关预览浮窗——它是 JDialog，不关就一直压着 .sql。
- 坐标全部按标准构图 REGION=(285,26,1354,974) 重测（窗口未放宽）。

前置（prep）：mpjdemo UserServiceImpl.java 打开、第 41 行、预览已开、replay.sql 未打开、无弹窗。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

import pyautogui

from gifkit import Recorder, click, shot
from locate import ensure_foreground, sandbox_pid, windows
from nav import force_en_us
from robot import click_component, find_components

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "n05_replay.mp4")

OX, OY = REGION[0], REGION[1]

# 20261028：预览弹窗位置会变（本轮实测落在 (368,159)-(1558,861)），写死的 region 相对坐标
# 会整体落空 —— 全部改成**相对预览弹窗/对话框自己的窗口矩形**计算（见 main 内）。
# 标定（相对预览弹窗左上角）：更多按钮 (1131, 57)；导出对话框内「事务包裹」(337, 323)。

# 导出产物路径（MCP open_file_in_editor 相对 IDE 工程根，工程根就是 demo 仓）
# 导出产物路径（MCP open_file_in_editor 相对 IDE 工程根，工程根就是 demo 仓）
EXPORTED_SQL = "replay.sql"


def focus_ide():
    """把沙盒 IDE 拉回前台。

    浏览器类窗口会在保存框关闭的空档抢前台（Chrome/magpie 仪表盘都犯过），
    保存后必须显式夺回，否则录制区全是浏览器画面。
    先按放 Alt 再 SetForegroundWindow，绕开 Windows 前台锁定。
    """
    import ctypes
    import win32gui

    hwnd = [0]

    def cb(h, _):
        t = win32gui.GetWindowText(h)
        if win32gui.IsWindowVisible(h) and "mybatis-plus-sql-visualizer-demo – " in t:
            hwnd[0] = h
        return True

    win32gui.EnumWindows(cb, None)
    if not hwnd[0]:
        raise RuntimeError("找不到沙盒 IDE 窗口")
    u32 = ctypes.windll.user32
    u32.keybd_event(0x12, 0, 0, 0)
    u32.keybd_event(0x12, 0, 2, 0)
    time.sleep(0.15)
    win32gui.SetForegroundWindow(hwnd[0])
    time.sleep(0.8)


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def find_window(title, timeout=8.0, min_w=0, max_w=10**6):
    """按标题找沙盒窗口，返回 rect。

    坑（20261028 实测）：导出选项框与原生保存框**标题都叫「导出回放脚本」**，
    只能按宽度区分——选项框 604px、保存框约 960px。
    """
    pid = sandbox_pid()
    deadline = time.time() + timeout
    while time.time() < deadline:
        for _h, p, t, r in windows():
            if p == pid and t.strip() == title:
                w = r[2] - r[0]
                if min_w <= w <= max_w:
                    return r
        time.sleep(0.3)
    return None


def focus_title(title, min_w=800):
    """把标题匹配的窗口置前（Alt 按放 + SetForegroundWindow）。

    20261028 实测：原生保存框会**开在 IDE 后面**（任务栏可见、屏幕不可见），
    直接按屏幕坐标点会打到 IDE 上 → 保存失败。必须先把它置前。
    """
    import ctypes
    import win32gui

    pid = sandbox_pid()
    target = [0]

    def cb(h, _):
        if win32gui.IsWindowVisible(h):
            t = win32gui.GetWindowText(h)
            if t.strip() == title:
                l, tp, r, b = win32gui.GetWindowRect(h)
                if (r - l) >= min_w:
                    target[0] = h
        return True

    win32gui.EnumWindows(cb, None)
    if not target[0]:
        return False
    u32 = ctypes.windll.user32
    u32.keybd_event(0x12, 0, 0, 0)
    u32.keybd_event(0x12, 0, 2, 0)
    time.sleep(0.12)
    win32gui.SetForegroundWindow(target[0])
    time.sleep(0.6)
    return win32gui.GetForegroundWindow() == target[0]


def preview_rect():
    r = find_window("SQL Visualizer - SQL 预览", timeout=3.0)
    if r is None:
        raise RuntimeError("预览弹窗未找到")
    return r


def has(xp):
    return len(find_components(xp)) > 0


def main():
    force_en_us()
    focus_ide()
    time.sleep(0.4)
    if not has("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
        raise RuntimeError("前置未满足：预览未打开。请先手工开好预览再跑本脚本")

    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(1.2)                                    # 1. 预览定格

        pop = preview_rect()

        # 2. 「更多」→ 键盘导航到「导出回放脚本」
        click(pop[0] + 1131, pop[1] + 57, move_dur=0.3, pauses=(0.2, 1.5))
        pyautogui.press("end")
        time.sleep(0.4)
        for _ in range(3):
            pyautogui.press("up")
            time.sleep(0.25)
        pyautogui.press("return")
        time.sleep(2.0)
        # 选项框是独立窗口，robot 组件树看不到（20261028 实测 has() 恒 False）→ 按窗口标题判
        if find_window("导出回放脚本", timeout=5.0, max_w=800) is None:
            raise RuntimeError("导出回放脚本对话框未打开，中止")

        # 3. 取消 / 重勾「事务包裹」——说明行跟随联动
        opt = find_window("导出回放脚本", timeout=5.0, max_w=800)
        if opt is None:
            raise RuntimeError("导出选项框未找到")
        click(opt[0] + 45, opt[1] + 66, move_dur=0.3, pauses=(0.15, 1.0))
        click(opt[0] + 45, opt[1] + 66, move_dur=0.3, pauses=(0.15, 1.2))

        # 4. OK → 原生保存框（等待列表加载完，加载期按键会被吞）
        click(opt[2] - 130, opt[3] - 32, move_dur=0.3, pauses=(0.2, 2.4))

        # 5. 保存：原生对话框按窗口标题定位，**点「保存(S)」按钮**（20261028 实测：
        #    Enter 在文件名框处于编辑态时不会提交，take 直接卡在保存框 →
        #    后续 MCP 打开 replay.sql 报不存在）。「保存(S)」相对窗口左上 (791, 498)。
        rect = find_window("导出回放脚本", timeout=10.0, min_w=800)
        if rect is None:
            raise RuntimeError("原生保存框未出现")
        time.sleep(0.6)
        focus_title("导出回放脚本", min_w=800)
        pyautogui.click(rect[0] + 783, rect[1] + 498)      # 「保存(S)」
        time.sleep(1.4)
        if find_window("导出回放脚本", timeout=1.5, min_w=800) is not None:
            # 20261028 实测：原生保存框有时**不绘制**（窗口存在、IsWindowVisible=1，
            # 但截屏里看不到、鼠标点击无效），只有 WM_COMMAND(IDOK) 能提交。
            # 后备路径直接向该窗口投 IDOK，效果等同点「保存(S)」。
            import win32con
            import win32gui

            hwnd = [0]

            def _cb(h, _):
                if win32gui.GetWindowText(h).strip() == "导出回放脚本":
                    l, t, r, b = win32gui.GetWindowRect(h)
                    if (r - l) >= 800:
                        hwnd[0] = h
                return True

            win32gui.EnumWindows(_cb, None)
            if hwnd[0]:
                win32gui.PostMessage(hwnd[0], win32con.WM_COMMAND, 1, 0)
                time.sleep(1.6)
        if find_window("导出回放脚本", timeout=1.5, min_w=800) is not None:
            raise RuntimeError("保存框未关闭（保存失败）")
        # 夺回前台：保存框关闭的空档 Chrome 会抢走前台，屏幕录制区会整段变成浏览器
        # （20261028 实测 take 帧 156+ 全白）。focus_ide 的 Alt+SetForegroundWindow 不够稳，
        # 改用 ensure_foreground（置前 + 取焦 + **校验前台句柄**）。
        pid = sandbox_pid()
        hwnd = [h for h, p_, t, r in windows() if p_ == pid and (r[2] - r[0]) > 1200][0]
        if not ensure_foreground(hwnd, click_xy=(700, 500), tries=6, wait=0.6):
            raise RuntimeError("保存后 IDE 未置前（Chrome 抢了前台）")
        time.sleep(1.2)

        # 6. 打开导出的 .sql 展示注释头；先关预览浮窗（JDialog 会压住 .sql）
        from mcpnav import open_file
        open_file(EXPORTED_SQL, settle=2.2)
        time.sleep(0.6)
        click_component("//div[@text='取消']")
        time.sleep(3.0)                                    # 注释头定格
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
