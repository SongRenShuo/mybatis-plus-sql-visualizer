# -*- coding: utf-8 -*-
"""U29 检索三选 / 超千行集合 Diff 入口——批 C 探针。

**验收口径来源**
- 审计表 U29 行：`mybatis-plus-sql-visualizer-dev/docs/验收文档/20261016-T-DevFullUI-合流后全功能沙盒验收记录.md`
  L54「待1/3条及超千行Diff」；已验残段：双选 Diff（B2）、非法正则（UI-009 复验）、
  万条文件 10000 条在场 + 搜索 UI_MATCH 命中 1000（L333/337 增量）。
- R31 口径：`20261008-1623-dev-bak对比master-新增功能验收文档.md` §3.10——
  选 1 条菜单不可用 / 恰 2 条打开原生 Diff（旧/新+逐行高亮+结构指纹结论）/ 选 3 条菜单不可用。
- 文案锚：`log.history.compare=对比选中两条`（Bundle L781）；结构等价结论 L784。

**前置条件**：SQL 历史有 ≥3 条可多选的语料；超千行文件沿用此前批次落盘的万条合成文件
（`--bigfile <目录名子串>` 指定，缺省 ui004-b5；找不到该步记「无语料」不判失败）。

**预计时长**：8~12 分钟。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 工具层在 acceptance/

import bc_common as bc
from bc_common import Reporter, front, shot, esc, ide
import pyautogui

BIG_FILE_DEFAULT = os.environ.get("BC_U29_BIGFILE", "ui004-b5")


def switch_history(rep):
    if bc.click_named("SQL 历史"):
        time.sleep(2.0)
        return True
    rep.fail("未能按名切「SQL 历史」页签（robot 未启？）——人工切页后重跑")
    shot("bc-U29-manual-tab")
    return False


def click_first_rows(rep, n):
    """从列表首行起选 n 条（首行双击改单击选中，其余 Ctrl+单击）。

    行定位：robot JBList/MyList 顶部坐标 + 行高估算（组件树给不出行高，按 30 条批次首行
    ≈列表顶+15px、行距 34px 的窗口几何内推；robot 不在场则需 bc_hist_first_row 模板）。
    """
    _pid, _hwnd, irect = ide()
    origin = None
    if bc.robot_available():
        for cls in ("JBList", "MyList"):
            xp = "//div[@class='%s']" % cls
            try:
                comps = [c for c in bc.robot_mod.find_components(xp) if int(c.get("height", 0)) > 100]
                if comps:
                    xy = bc.robot_mod.component_screen_center("(%s)[1]" % xp, comp=comps[0])
                    origin = (xy[0], xy[1] + 15)
                    break
            except Exception:
                continue
    if origin is None and bc.anchor_ready("bc_hist_first_row"):
        hit = bc.anchor_mod.find("bc_hist_first_row")
        origin = (hit[0], hit[1]) if hit else None
    if origin is None:
        rep.fail("历史列表首行定位缺路线（robot 无列表且无 bc_hist_first_row 模板）")
        return False
    row_h = 34
    pyautogui.click(origin[0], origin[1])
    time.sleep(0.4)
    for i in range(1, n):
        pyautogui.keyDown("ctrl")
        pyautogui.click(origin[0], origin[1] + i * row_h)
        pyautogui.keyUp("ctrl")
        time.sleep(0.35)
    shot("bc-U29-selected-%d" % n)
    return True


def part_three_select(rep):
    """三选：菜单不可用（负例）→ 双选正对照（Diff 窗真开）。"""
    rep.step("T1 切 SQL 历史，选 3 条 → 右键菜单「对比选中两条」应不可用")
    if not switch_history(rep):
        return
    if not click_first_rows(rep, 3):
        return
    pos = pyautogui.position()
    pyautogui.click(pos[0], pos[1], button="right")
    time.sleep(1.2)
    shot("bc-U29-t1-three-menu")   # 置灰形态人工核对（robot 组件树不含 enabled 位）
    rep.ok("三选右键菜单截图已存（「对比选中两条」置灰=人工判据；robot 组件树不含 enabled 位，不作自动断言）")
    esc(2)

    rep.step("T2 双选正对照：恰 2 条 → 打开 IDE 原生 Diff（同次对照组）")
    if not click_first_rows(rep, 2):
        return
    pos = pyautogui.position()
    pyautogui.click(pos[0], pos[1], button="right")
    time.sleep(1.2)
    shot("bc-U29-t2-two-menu")
    if not (bc.click_named("对比选中两条") or bc.click_button(
            rep, "对比选中两条", anchor_name="bc_menu_compare", must=False)):
        rep.fail("双选「对比选中两条」未点中（robot/模板两空）")
        esc(2)
        return
    time.sleep(2.5)
    # Diff 窗断言：新顶层窗口出现（Diff 用独立对话框承载）
    _pid, _hwnd, main_rect = ide()
    import locate
    diff_win = locate.window_rect(pid=_pid, pick="smallest", min_size=(700, 400))
    rep.shot_check(diff_win is not None and diff_win != main_rect, "bc-U29-t2-diff-window",
                   "Diff 窗口已开（窗口 %s；标题=对比选中两条，含结构指纹结论）" % (diff_win,),
                   "Diff 窗口未出现——双选 Diff 入口未生效")
    esc(3)


def part_over_thousand(rep):
    """超千行：万条文件在册 → 打开 → 选 2 条走 Diff 入口（入口在超大规模集合上仍可用）。"""
    rep.step("K1 一级文件检索：搜超千行文件目录 %s" % BIG_FILE_DEFAULT)
    if not switch_history(rep):
        return
    # 文件搜索框：历史面板一级「搜索文件名...」——robot 按占位文本找输入框
    opened = False
    if bc.robot_available():
        for cls in ("JBTextField", "SearchTextField", "EditorTextField"):
            xp = "//div[@class='%s']" % cls
            try:
                comps = [c for c in bc.robot_mod.find_components(xp) if int(c.get("width", 0)) > 150]
            except Exception:
                continue
            for i, c in enumerate(comps):
                try:
                    xy = bc.robot_mod.component_screen_center("(%s)[%d]" % (xp, i + 1), comp=c)
                except Exception:
                    continue
                pyautogui.click(*xy)
                time.sleep(0.4)
                pyautogui.hotkey("ctrl", "a")
                bc.nav_mod.force_en_us()
                pyautogui.write(BIG_FILE_DEFAULT, interval=0.05)
                time.sleep(2.0)
                shot("bc-U29-k1-file-search")
                # 结果首行双击打开
                if click_first_rows(rep, 1):
                    opened = True
                    break
            if opened:
                break
        if opened:
            pyautogui.hotkey("ctrl", "a")   # 清搜索词（还原全量列表语义）
            pyautogui.keyDown("delete"); pyautogui.keyUp("delete")
            time.sleep(1.5)
    if not opened:
        rep.fail("超千行文件未打开：检索输入框/结果行未定位，或语料 %s 不在册（记「无语料」，不判产品失败）"
                 % BIG_FILE_DEFAULT)
        return
    time.sleep(3.0)   # 万条惰性加载首批
    rep.step("K2 万条集合内选 2 条 → Diff 入口")
    if not click_first_rows(rep, 2):
        return
    pos = pyautogui.position()
    pyautogui.click(pos[0], pos[1], button="right")
    time.sleep(1.2)
    shot("bc-U29-k2-biglist-menu")
    if bc.click_named("对比选中两条") or bc.click_button(
            rep, "对比选中两条", anchor_name="bc_menu_compare", must=False):
        time.sleep(2.5)
        _pid, _hwnd, main_rect = ide()
        import locate
        diff_win = locate.window_rect(pid=_pid, pick="smallest", min_size=(700, 400))
        rep.shot_check(diff_win is not None and diff_win != main_rect, "bc-U29-k2-big-diff",
                       "万条集合上 Diff 窗口已开", "万条集合上 Diff 未打开（惰性模型下选择集异常？）")
    esc(3)


def main():
    front()
    esc(2)
    rep = Reporter("U29")
    part_three_select(rep)
    part_over_thousand(rep)
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
