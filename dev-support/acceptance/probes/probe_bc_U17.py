# -*- coding: utf-8 -*-
"""U17 五条金路径同次对照——批 C 探针。

**验收口径来源**
- 审计表 U17 行：`mybatis-plus-sql-visualizer-dev/docs/验收文档/20261016-T-DevFullUI-合流后全功能沙盒验收记录.md`
  L42「Wrapper分页、XML动态、JPA/HQL、捕获源码历史、执行EXPLAIN；同次输入/实际SQL/目标/结果，
  不跨样本拼证」；B1 记录（L81：UserServiceImpl.java:36 预览、ui_alpha/status=1/age=18 回填、
  逻辑删除保持）；UI030 复验（执行计划 last-used 静默绑定 + 结构化 EXPLAIN 面板内渲染）。

**「同次对照」的证据形态**：每条路径在本脚本**一次运行内**连续完成——
输入参数（参数面板截图）→ 实际 SQL（SQL 区截图）→ 目标（数据源指示行截图，含在面板整体截图内）
→ 结果（执行/EXPLAIN 结果区截图或明示「未执行」）。四元组截图统一编号 bc-U17-p<路径>-q1..q4，
人工按截图记录四元组，**不允许**拿其他批次的样本拼证。

**前置条件**：沙盒起、演示工程开、last-used 数据源（mp_demo）在场；SQL 历史已有语料
（批 C 之前的批已落盘；若为空，P4 步骤记「无语料」而非失败）。

**预计时长**：12~18 分钟。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 工具层在 acceptance/

import bc_common as bc
from bc_common import Reporter, front, shot, esc
import pyautogui


def quad_shots(rep, tag, rect, result_region=None):
    """四元组截图：q1 参数面板 / q2 SQL 区 / q3 面板整体（含数据源指示行=目标） / q4 结果区。"""
    r = rect or bc.preview_rect()
    if not r:
        rep.fail("%s：预览浮窗不在场，四元组采集中断" % tag)
        return
    shot("bc-U17-%s-q1-params" % tag, region=r)          # 含参数面板
    shot("bc-U17-%s-q2-sql" % tag, region=(r[0], r[1], r[2], max(200, r[3] // 2)))
    shot("bc-U17-%s-q3-panel-with-target" % tag, region=r)
    if result_region:
        shot("bc-U17-%s-q4-result" % tag, region=result_region)
    else:
        shot("bc-U17-%s-q4-result-none" % tag, region=r)  # 明示「未执行」形态


def p1_wrapper_page(rep):
    """金路径 1：Wrapper 分页（UserServiceImpl.pageByWrapper）。"""
    rep.step("P1 Wrapper 分页：UserServiceImpl.pageByWrapper（~L168）")
    bc.open_file(bc.P_USER_SERVICE, rep=rep)
    bc.caret(text="queryWrapper.in(pageQuery.getIds() != null", rep=rep)   # pageByWrapper 独有的链式行
    rect = bc.trigger_preview(rep)
    if not rect:
        # 诊断：降级模式（内存 PSI）会出红字警示；截全屏留证据再退出
        degraded = bc.find_contains("降级模式") is not None or bc.find_contains("内存 PSI") is not None
        shot("bc-U17-p1-trigger-miss")
        rep.fail("P1：Alt+Shift+Q 未出预览（降级模式警示在场=%s，见 bc-U17-p1-trigger-miss）" % degraded)
        return
    bc.fill_text_fields(rect, [1, 5, "ui_alpha"], rep=rep)   # current/size/username（能定位几个填几个）
    time.sleep(1.0)   # 等 SQL 区随参数回填
    quad_shots(rep, "p1-wrapper-page", rect)
    rep.step("P1 结果：本路径只做预览对照，不执行（执行统一走 P5 EXPLAIN 面）")
    bc.close_preview()


def p2_xml_dynamic(rep):
    """金路径 2：XML 动态（UserMapper.xml selectUserListByXml，条件勾选联动）。

    联动语义（B2 口径）：填参不改 SQL（占位形态）；**勾选动态条件复选框**才把 <if> 条件
    加进 WHERE、取消勾选则移除。断言=勾选后 SQL 区出现 WHERE 变化。
    """
    rep.step("P2 XML 动态：selectUserListByXml 预览 + 条件勾选联动")
    bc.open_file(bc.P_USER_MAPPER_XML, rep=rep)
    bc.caret(text="selectUserListByXml", rep=rep)
    rect = bc.trigger_preview(rep)
    if not rect:
        return
    time.sleep(1.0)
    bc.fill_text_fields(rect, ["ui_xml", 1], rep=rep)
    time.sleep(1.0)
    shot("bc-U17-p2-before-checks")
    g = bc.Guard(region=rect)
    g.mark("sql-area")

    def click_cond(subtext):
        comp = bc.find_contains(subtext)
        if not comp:
            return False
        xp = ("(//div[contains(@text,'%s')]|//div[contains(@accessiblename,'%s')])[last()]"
              % (subtext, subtext))
        try:
            x, y = bc.robot_mod.component_screen_center(xp, comp=comp)
        except Exception:
            return False
        pyautogui.moveTo(x, y, duration=0.2)
        pyautogui.click()
        return True

    hits = [click_cond(s) for s in ("username != null and username != ''", "status != null", "age != null")]
    time.sleep(1.2)
    changed, score = g.changed("sql-area", wait=0.8)
    rep.check(changed, "XML 动态：勾选动态条件引发 SQL 区变化（diff=%.1f，勾选命中=%s）——同次联动成立"
              % (score, hits),
              "XML 动态：勾选后 SQL 区无变化——联动未发生（勾选命中=%s，截图判读）" % hits)
    quad_shots(rep, "p2-xml", rect)
    bc.close_preview()


def p3_jpa_hql(rep):
    """金路径 3：JPA/HQL（CommonDaoHibernateImpl findByValueBean :warehouseId 命名参数）。"""
    rep.step("P3 JPA/HQL：CommonDaoHibernateImpl L231 命名参数 HQL 预览")
    bc.open_file(bc.P_HIBERNATE_DAO, rep=rep)
    bc.caret(line=231, col=62, rep=rep)   # 字符串内 :warehouseId 附近
    rect = bc.trigger_preview(rep)
    if rect:
        bc.fill_text_fields(rect, ["wh_bc"], rep=rep)
        time.sleep(1.0)
        quad_shots(rep, "p3-hql", rect)
        bc.close_preview()


def p4_captured_history(rep):
    """金路径 4：捕获源码历史（历史条目带方言/耗时/源码链接）。"""
    rep.step("P4 捕获源码历史：切「SQL 历史」页签取首条带源码链接的条目")
    if not bc.click_named("SQL 历史"):
        rep.fail("未能按名切「SQL 历史」页签（robot 未启）——RUNBOOK 人工切页后单跑本步")
        shot("bc-U17-p4-history-manual")
        return
    time.sleep(2.0)
    shot("bc-U17-p4-q1-list")            # 列表整体：条目行含方言/耗时/源码链接
    # 打开首条：robot 找历史列表首行组件；没有则模板 bc_hist_first_row
    row = None
    if bc.robot_available():
        for cls in ("JBList", "MyList"):
            xp = "//div[@class='%s']" % cls
            try:
                comps = [c for c in bc.robot_mod.find_components(xp) if int(c.get("height", 0)) > 100]
                if comps:
                    xy = bc.robot_mod.component_screen_center("(%s)[1]" % xp, comp=comps[0])
                    row = (xy[0], xy[1] + 20)   # 列表顶部往下一条目高度
                    break
            except Exception:
                continue
    if row is None and bc.anchor_ready("bc_hist_first_row"):
        hit = bc.anchor_mod.find("bc_hist_first_row")
        row = (hit[0], hit[1]) if hit else None
    if row:
        pyautogui.doubleClick(*row)
        time.sleep(2.0)
        quad_shots(rep, "p4-history", bc.preview_rect())
    else:
        rep.fail("P4：历史首条定位缺路线（robot 无列表/无 bc_hist_first_row 模板）——截图已存，人工点开补 q2..q4")


def p5_explain(rep):
    """金路径 5：执行 EXPLAIN（selectByAnnotationId，id=91；UI030 同路径）。"""
    rep.step("P5 执行 EXPLAIN：UserMapper.java @Select 预览 → 执行计划")
    bc.open_file("UserMapper.java", rep=rep)
    bc.caret(text='SELECT * FROM user WHERE id = #{id}', rep=rep)
    rect = bc.trigger_preview(rep)
    if not rect:
        return
    bc.fill_text_fields(rect, [91], rep=rep)
    time.sleep(1.0)
    g = bc.Guard(region=rect)
    g.mark("pre-explain")
    bc.click_button(rep, "执行计划", anchor_name="bc_btn_explain", region=rect)
    time.sleep(6.0)      # 静默绑定 + EXPLAIN 往返（UI030 实测 2.2s+）
    changed, score = g.changed("pre-explain", wait=0.5)
    # 无绑定态（boundConsole=null）时 T-UI030 路径=直弹数据源选择器——弹选择器本身即有效行为，
    # 与「结果表格出现」同属通过面；只有两者皆无才判失败。
    prompt_shown = bc.find_contains("选择数据源") is not None or bc.find_contains("Choose Data Source") is not None
    rep.check(changed or prompt_shown,
              "EXPLAIN：点「执行计划」后有响应（面板变化=%s diff=%.1f / 选择数据源弹层=%s；结果表格见截图）"
              % (changed, score, prompt_shown),
              "EXPLAIN：面板无变化且无选择器弹层——按钮没点中或执行未发生（数据源/绑定判读看截图）")
    quad_shots(rep, "p5-explain", rect)
    shot("bc-U17-p5-q4-result-wide", region=(rect[0], rect[1], rect[2], rect[3]))
    bc.close_preview()


def main():
    front()
    esc(2)
    rep = Reporter("U17")
    p1_wrapper_page(rep)
    p2_xml_dynamic(rep)
    p3_jpa_hql(rep)
    p4_captured_history(rep)
    p5_explain(rep)
    print("\n[记录纪律] 五条路径的四元组（同次输入参数=实际 SQL=目标=结果）逐条按 bc-U17-p*-q* 截图登记，"
          "禁止用此前批次的样本拼证。")
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
