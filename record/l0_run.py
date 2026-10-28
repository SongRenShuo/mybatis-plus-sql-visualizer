# -*- coding: utf-8 -*-
"""L0 类图统一驱动（T-DemoGifFix1026 批4）。

动机（20261027 复盘）：L0（预览面板内交互）成功率接近 100%，值得批量跑；
但要保证「每个 take 至少 3 条断言」+「转制即入库」，不能像昨天那样裸跑。

用法：python l0_run.py <scene 名> [--preview <文件相对路径> <行> <列>] [--gif <输出名>]

--preview 给定时先在 prep 阶段用 MCP 开文件 + goto_col + 唤起预览（不入镜）；
不给时假定预览已由调用方开好（脚本自带前置断言）。
"""
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

REGION = (285, 26, 1354, 974)


def prepare_preview(rel_path, line, col):
    """prep（不入镜）：MCP 开文件 → 定位 → 唤起预览 + 断言面板就位。"""
    import pyautogui
    from nav import goto_col
    from verify import assert_panel_open, assert_no_dialog, assert_panel_closed
    from mcpnav import open_file

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    # 清残留（20261027：候选框「选择要预览的 SQL」会挡住 MCP 调用，导致 open_file 超时，
    # 进而开错文件——必须先点 Cancel 关掉，Esc 不够）
    from robot import find_components, click_component
    for _ in range(2):
        cleared = False
        for xp in ("//div[@accessiblename='Cancel']",
                   "//div[contains(@accessiblename,'选择要预览的 SQL')]"):
            if find_components(xp):
                if "Cancel" in xp:
                    click_component(xp)
                else:
                    pyautogui.press("esc")
                cleared = True
                time.sleep(1.2)
                break
        if not cleared:
            break
    try:
        assert_no_dialog()
    except AssertionError as e:
        print("[prep] 仍有残留弹窗:", e)
        pyautogui.press("esc")
        time.sleep(0.6)
    try:
        assert_panel_closed(timeout=2.0)
    except AssertionError:
        # 预览开着：点「取消」关掉
        pyautogui.click(REGION[0] + 1212, REGION[1] + 798)
        time.sleep(1.0)

    open_file(rel_path, settle=1.6)
    goto_col(line, col)
    pyautogui.press("esc")
    time.sleep(0.4)
    pyautogui.hotkey("alt", "shift", "q")
    time.sleep(3.6)
    # 硬断言：预览必须真的打开。
    # 20261027 血的教训——预览没开就往下走，脚本里的 gifkit.paste 会把文字
    # **直接打进编辑器**：UserMapper.xml 被截成一行（262 行丢失）、
    # PageSimController 被截 84 行，都要 git checkout 才恢复。
    assert_panel_open()
    print("[prep] 预览已就位（已断言）")


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 1
    scene = args[0]
    rel = line = col = None
    if "--preview" in args:
        i = args.index("--preview")
        rel, line, col = args[i + 1], int(args[i + 2]), int(args[i + 3])

    if rel:
        prepare_preview(rel, line, col)

    print(f"\n===== run {scene} =====", flush=True)
    t0 = time.time()
    r = subprocess.run([sys.executable, os.path.join(HERE, scene)], capture_output=True, text=True)
    out = ((r.stdout or "") + (r.stderr or "")).strip()
    print(out[-900:], flush=True)
    print(f"exit={r.returncode}  {time.time()-t0:.0f}s", flush=True)
    return r.returncode


if __name__ == "__main__":
    sys.exit(main())
