# -*- coding: utf-8 -*-
"""U04 快速预览与首次引导——批 C 探针。

**验收口径来源**
- `mybatis-plus-sql-visualizer-dev/docs/验收文档/20261016-T-DevFullUI-合流后全功能沙盒验收记录.md`
  审计表 U04 行（L29，待验子项：引导跨页/重启、多选/空白、切文件迟到）+ UI-004 行（L117）
  + B5 复验记录（空扫 0 文件 → 落盘 3 条 → 首次切历史直接显示）。
- 主控批 C 计划（同文档 L581）：本脚本覆盖「设置空扫→落盘 3 条→首次切历史自动显示」
  +「引导跨页」可自动化面；「重启」只留说明（重启后加 `--guide-recheck` 复跑）。

**前置条件**
- 沙盒 IDE（MPSV-MasterMerge-UI243）已起、演示工程已开、窗口可置前；
- 数据源 last-used=mp_demo 在场（此前 UI030 批已绑定过即可）；工具窗「捕捉」开启；
- robot-server 可选（未启时相关语义断言降级为截图+指纹）。

**预计时长**：8~12 分钟（含 3 次预览执行落盘等待）。

**模式**
- 默认：A 部分（空扫→落盘 3 条→首切历史自动显示）+ B 部分（引导跨页重演前置检查）；
- `--guide-recheck`：重启后复跑引导持久化断言（配合 RUNBOOK 的重启步骤）。
"""
import os
import sys
import time
import glob

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 工具层在 acceptance/

import bc_common as bc
from bc_common import Reporter, ide, front, shot, esc, fingerprint, diff_score
import pyautogui

HIST_DIR = os.path.join(bc.DEV_TREE, "build", "bc-u04-hist")


def _guide_flag_files():
    """沙盒应用级配置 mp-sql-visualizer.xml（onboardingAnchorTipShown 落点），glob 兜多沙盒。"""
    return glob.glob(os.path.join(bc.DEV_TREE, "build", "*", "mybatis-plus-sql-visualizer",
                                  "*", "config", "options", "mp-sql-visualizer.xml"))


def part_a_history_first_switch(rep):
    """UI-004 首切自动显示：专属空目录 → 落盘 3 条 → 首次切 SQL 历史直接显示，不手动刷新。"""
    rep.step("A1 建专属空目录并清场：%s" % HIST_DIR)
    if os.path.isdir(HIST_DIR):
        for f in glob.glob(os.path.join(HIST_DIR, "*")):
            os.remove(f)
    else:
        os.makedirs(HIST_DIR, exist_ok=True)
    rep.check(os.path.isdir(HIST_DIR) and not os.listdir(HIST_DIR),
              "空目录就绪", "空目录未就绪（权限/残留）")

    rep.step("A2 打开设置（Ctrl+Alt+S）→ 存储：核「自动保存历史 SQL」与目录字段在场（目录指到空目录按 RUNBOOK 前置手工完成）")
    pyautogui.hotkey("ctrl", "alt", "s")
    time.sleep(2.5)
    rep.shot_check(True, "bc-U04-01-settings", "设置对话框已开（截图核对停在存储页）",
                   "设置对话框未确认", region=bc.preview_rect() or bc.balloon_region())
    # 只探测不点击：点「打开捕获历史目录」会真开一个资源管理器窗口抢焦点，不是本组要的动作
    comp = bc.find_named("打开捕获历史目录")
    rep.check(bool(comp), "robot 探到存储页「打开捕获历史目录」控件（存储组在场）",
              "robot 未能探到存储页控件（未启 robot-server？）——目录切换按 RUNBOOK 手工步骤执行后重跑 A3")
    # 自动保存开关按名点击（JBCheckBox 文本可命中）
    if bc.click_named("自动保存历史 SQL"):
        rep.ok("「自动保存历史 SQL」开关已点击（截图核对勾选态）")
        shot("bc-U04-02-autosave-toggle")
    esc(3)   # 关设置对话框（别用 Ctrl+Shift+A——那是 Find Action）

    rep.step("A3 首次切「SQL 历史」页签确认空态基线")
    # 页签定位：robot 按名；未启 robot 则提示人工先点（历史页签在工具窗首行，几何路线交给模板）
    tab = bc.click_named("SQL 历史")
    rep.check(bool(tab), "已切到 SQL 历史（robot 按名命中页签）",
              "未能按名点中「SQL 历史」页签——手工切过去后重跑本脚本（首切语义只能验一次）")
    time.sleep(1.5)
    shot("bc-U04-03-history-empty")

    rep.step("A4 经预览面板执行 3 次查询制造落盘（同次：id=1/2/3）")
    bc.open_file(bc.P_USER_MAPPER_XML, rep=rep)
    for i in (1, 2, 3):
        bc.caret(text="selectUserListByXml", rep=rep)
        rect = bc.trigger_preview(rep)
        if not rect:
            break
        time.sleep(1.0)
        # 参数面板首字段填 id 并执行（模板/组件树定位按钮；两路线皆无则留人工）
        bc.click_button(rep, "执行 SQL", anchor_name="bc_btn_exec_sql", region=rect, must=False)
        shot("bc-U04-04-exec-%d" % i, region=rect)
        bc.close_preview()
        time.sleep(1.0)

    rep.step("A5 文件系统断言：专属目录恰落 3 个历史文件")
    files = sorted(glob.glob(os.path.join(HIST_DIR, "*")))
    rep.check(len(files) == 3, "落盘 3 条（%s）" % ", ".join(os.path.basename(f) for f in files),
              "期望 3 个文件，实得 %d（捕捉未开/执行未发生/自动保存目录未指对）" % len(files))

    rep.step("A6 首次切入 SQL 历史：断言未手动刷新即显示 3 条")
    if bc.robot_available():
        comp = bc.find_named("3 条")
        rep.check(bool(comp), "历史结果计数「3 条」在场（robot 文本命中，未手动刷新）",
                  "未找到「3 条」计数——首切自动显示未达成或计数文案不同（截图人工核对）")
    else:
        rep.fail("robot-server 未启：首切计数断言降级——截图 bc-U04-05-history-first-switch 人工数条数")
    shot("bc-U04-05-history-first-switch")


def part_b_guide_cross_page(rep):
    """引导跨页：一次性引导通知已展示态下，跨文件打开不再重弹（重演需重启+清标志，见 RUNBOOK）。"""
    rep.step("B1 引导一次性状态在位检查（application 级 onboardingAnchorTipShown）")
    shown = any("onboardingAnchorTipShown" in open(p, encoding="utf-8", errors="replace").read()
                for p in _guide_flag_files() if os.path.exists(p))
    rep.check(shown, "引导「已展示」标志可在沙盒配置核到（跨页不重弹的前提）",
              "未在沙盒 options/mp-sql-visualizer.xml 核到 onboardingAnchorTipShown（标志未落或路径漂移）——只影响 B2 判读")

    rep.step("B2 跨页：连续打开两个含可预览 SQL 的文件，断言引导气泡不再出现")
    _pid, _hwnd, rect = ide()
    g = bc.Guard(region=bc.balloon_region(rect))
    g.mark("balloon")
    for q in (bc.P_USER_SERVICE, bc.P_KOTLIN_SCENARIOS):
        bc.open_file(q, rep=rep)
        time.sleep(1.2)
        changed, score = g.changed("balloon", wait=0.5)
        rep.check(not changed, "打开 %s 后气泡区无变化（引导未重弹，diff=%.1f）" % (q, score),
                  "气泡区出现变化（diff=%.1f）——可能引导重弹，截图人工核文字" % score)
        shot("bc-U04-06-crosspage-%s" % q)


def guide_recheck():
    """重启后复跑：断言引导仍不出现（持久化「至多一条」）。配合 RUNBOOK 重启步骤。"""
    rep = Reporter("U04-guide-recheck")
    front()
    g = bc.Guard(region=bc.balloon_region())
    g.mark("balloon")
    bc.open_file(bc.P_USER_SERVICE, rep=rep)
    time.sleep(2.0)
    changed, score = g.changed("balloon", wait=1.0)
    rep.shot_check(not changed,
                   "bc-U04-07-after-restart",
                   "重启后打开可预览文件引导未再弹（持久化成立）",
                   "重启后气泡区出现变化（diff=%.1f）——引导重复出现，登记缺陷" % score)
    return rep.finish()


def main():
    front()
    esc(2)
    rep = Reporter("U04")
    if "--guide-recheck" in sys.argv:
        return guide_recheck()
    part_a_history_first_switch(rep)
    part_b_guide_cross_page(rep)
    print("\n[说明] 「引导跨页/重启」的重启维度的执行方法：按 RUNBOOK U04 节清一次性标志 → 重启沙盒 → "
          "python probe_bc_U04.py --guide-recheck")
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
