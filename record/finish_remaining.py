# -*- coding: utf-8 -*-
"""补录剩余 5 张动图的统一入口（T-DocsRefresh1023 收尾）。

前置条件（缺任一即中止，不硬跑）：
1. 沙盒已用 `runIde -PgifRecord=true -PgifRobotServer=true --args=<demo 工程>` 启动；
2. 预览绑定指示行显示 **mpj_demo@localhost**（workspace 的 last.runner 已改，
   但 IDE 若在改动前启动过需重启才生效）；
3. demo 应用可选（R11 已录，本批不需要 8081）。

用法：
    python record/finish_remaining.py --check          # 只检查前置，不出片
    python record/finish_remaining.py --only n02       # 单张
    python record/finish_remaining.py --all            # 按顺序全跑

设计取舍：每张单独 try/except，一张失败不拖垮其余；失败项打印原因并继续。
"""
import argparse
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from robot import find_components  # noqa: E402

PREVIEW = "//div[@accessiblename='SQL Visualizer - SQL 预览']"

# 顺序有讲究：N02/N03 共用同一次预览会话（先打开一次，两张连拍），
# N11 需要先做一次 UPDATE 预览，N12 走设置页。
# 每张图的「隐藏前提」——不满足时不要硬跑，否则会录出与图题不符的画面。
PRECONDITIONS = {
    "n02": ["预览绑 mpj_demo@localhost（该库 URL 带 sql_mode=ANSI_QUOTES）",
            "预览语句为 UPDATE / DELETE（SELECT 不支持影响面预演）"],
    "n03": ["设置页『SQL 预览 → 预览面板内结构化渲染 EXPLAIN』已开启（实验特性，默认关）",
            "『与上次执行对比』入口只存在于结构化视图（ExplainPlanResultView）"],
    "n11": ["预览语句为 UPDATE（INSERT 走自动回滚，没有快照查询入口）",
            "快照查询需要可用数据源"],
    "n12": ["SQL 日志列表里已有含 password / 手机号形态的条目"],
    "n08": ["插件方言已手工改为 PostgreSQL，目标是 MySQL 的 mpj_demo",
            "闸门在点『执行 SQL』时触发，不在渲染时"],
    "r10": ["AI Assistant 对话面板已开（右侧工具窗，不是主工具栏 AI 按钮）",
            "agent（如 Codex）已完成认证，能看到 agent 名与认证行",
            "能调通 MCP 工具（sqlv_check_sql 等），且 idea.log 出现 [MCP-AUDIT] 行"],
}

STEPS = [
    ("n02", "scene_n02_dml_impact.py", "DML 影响面预演 → dml-impact.gif"),
    ("n03", "scene_n03_explain_history.py", "EXPLAIN 跨次对比 → explain-history-compare.gif"),
    ("n11", "scene_n11_rollback_snapshot.py", "回滚快照双态 → rollback-snapshot-dual.gif"),
    ("n12", "scene_n12_display_mask.py", "展示脱敏 → display-mask.gif"),
    ("n08", "scene_n08_dialect_gate.py", "方言闸门 → dialect-gate.gif"),
    # R10 依赖 ACP agent 认证，脚本未写；登记在此以便清单完整（脚本缺失时 preflight 会报 MISS）
    ("r10", "scene_r10_mcp_agent.py", "MCP agent 真实调用 → mcp-agent.gif"),
]


def preflight():
    """检查前置。返回 (ok, 报告行列表)。"""
    lines = []
    ok = True

    # 1. robot-server 可达（不可达不提前返回：离线也要能审阅脚本与人工前提）
    robot_ok = False
    try:
        roots = find_components("//div[@classname='com.intellij.openapi.wm.impl.IdeFrameImpl']")
        lines.append(f"[OK] robot-server 可达，IdeFrame 节点 {len(roots)} 个")
        robot_ok = True
    except Exception as e:
        lines.append(f"[FAIL] robot-server 不可达：{e}")
        ok = False

    # 2. 预览是否已开（不是前置要求，只报状态）
    if robot_ok:
        if find_components(PREVIEW):
            lines.append("[INFO] 预览已打开——N02/N03 可直接跑")
        else:
            lines.append("[INFO] 预览未打开——需手工开好再跑")

    # 3. 隐藏前提提示（人工核对项，本脚本无法自动判定）
    for key, _, _ in STEPS:
        for pre in PRECONDITIONS.get(key, []):
            lines.append(f"[MANUAL] {key}: {pre}")

    # 4. 场景脚本齐备性
    for key, script, desc in STEPS:
        p = os.path.join(HERE, script)
        lines.append(f"[{'OK' if os.path.isfile(p) else 'MISS'}] {key}: {script}  ({desc})")
        if not os.path.isfile(p):
            ok = False

    return ok, lines


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--only", choices=[s[0] for s in STEPS])
    args = ap.parse_args()

    ok, lines = preflight()
    print("\n".join(lines))
    print()
    if args.check:
        return 0 if ok else 1
    if not ok:
        print("前置不满足，按上面的 MISS 补齐脚本后再跑。")
        return 1

    todo = [s for s in STEPS if args.only is None or s[0] == args.only]
    if not (args.all or args.only):
        print("指定 --all 或 --only <key>。")
        return 1

    results = []
    for key, script, desc in todo:
        print(f"\n===== {key}: {desc} =====")
        try:
            mod = __import__(script[:-3])
            mod.main()
            results.append((key, "OK"))
        except Exception as e:
            print(f"[FAIL] {key}: {e}")
            results.append((key, f"FAIL {e}"))
        time.sleep(2)

    print("\n===== 汇总 =====")
    for key, st in results:
        print(f"  {key}: {st}")
    return 0 if all(s == "OK" for _, s in results) else 1


if __name__ == "__main__":
    sys.exit(main())
