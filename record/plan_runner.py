# -*- coding: utf-8 -*-
"""演示 plan 重放器（T-TestGateGems W6-c，2026-10-24）。

来源：magpie `.github/ui-preview/record.mjs:455` 的 `PLAN_FILE` 机制——LLM 读 diff 规划一次动作序列，
产出 JSON；之后**同一份 plan 可以确定性重放**，不再调用 LLM。原文口径：「LLM 的不确定性只发生一次，
之后全确定性」。

为什么这对本仓重要：现在 82 个场景脚本全是手写坐标 + 手写动作序列。一旦构图漂移就要人工改，
而且「这段演示到底点了什么」无法 review。把动作序列抽成 plan.json 后：
  - plan 是可 diff、可 review 的资产（点哪个控件、说明写什么，一眼看清）；
  - 坐标与语义分离：plan 里写的是 `target` 语义键，坐标由 `targets` 映射提供（构图一变只改映射）；
  - 重放确定性：同一 plan + 同一 targets = 同一动作序列。

plan 格式：
```json
{
  "name": "a11-explain",
  "region": [277, 25, 1370, 984],
  "targets": { "previewButton": [1256, 829], "dsRow": [420, 300] },
  "steps": [
    {"do": "click", "target": "previewButton", "label": "执行 SQL 按钮"},
    {"do": "wait", "ms": 2200},
    {"do": "move", "target": "dsRow", "dur": 0.6},
    {"do": "shot", "name": "perf/a11_final.png"},
    {"do": "type", "text": "select 1"},
    {"do": "key", "key": "enter"}
  ],
  "unseen": ["真机正式许可下的表现", "大工程索引规模"]
}
```

用法：
    python plan_runner.py record/plans/a11-explain.json            # 重放
    python plan_runner.py plan.json --dry-run                      # 只校验 plan，不动鼠标
"""
import argparse
import json
import os
import sys

VALID_STEPS = {"click", "double_click", "right_click", "move", "wait", "shot", "type", "key", "drag"}


def load_plan(path):
    with open(path, encoding="utf-8") as f:
        plan = json.load(f)
    validate_plan(plan, path)
    return plan


def validate_plan(plan, path="<plan>"):
    """静态校验：动作类型合法、target 有映射、step 必填字段齐全。干跑也走这条。"""
    errs = []
    if not isinstance(plan, dict):
        raise ValueError(f"{path}: plan 必须是对象")
    steps = plan.get("steps")
    if not isinstance(steps, list) or not steps:
        raise ValueError(f"{path}: steps 必须是非空数组")
    targets = plan.get("targets") or {}
    for i, step in enumerate(steps):
        if not isinstance(step, dict):
            errs.append(f"step[{i}]: 必须是对象")
            continue
        do = step.get("do")
        if do not in VALID_STEPS:
            errs.append(f"step[{i}]: do={do!r} 不在 {sorted(VALID_STEPS)}")
            continue
        if do in ("click", "double_click", "right_click", "move", "drag"):
            t = step.get("target")
            if t is None:
                errs.append(f"step[{i}] ({do}): 缺 target")
            elif t not in targets:
                errs.append(f"step[{i}] ({do}): target={t!r} 在 targets 里没有坐标映射")
        if do == "wait" and "ms" not in step:
            errs.append(f"step[{i}] (wait): 缺 ms")
        if do == "shot" and not step.get("name"):
            errs.append(f"step[{i}] (shot): 缺 name")
        if do == "type" and "text" not in step:
            errs.append(f"step[{i}] (type): 缺 text")
        if do == "key" and not step.get("key"):
            errs.append(f"step[{i}] (key): 缺 key")
    if errs:
        raise ValueError(f"{path} 校验失败：\n" + "\n".join("  " + e for e in errs))
    return True


def replay(plan, dry_run=False):
    """按 plan 逐步执行。dry_run 只打印将要做什么，不碰鼠标。"""
    targets = plan.get("targets") or {}
    steps = plan["steps"]

    if dry_run:
        print(f"[dry-run] plan={plan.get('name', '<unnamed>')} steps={len(steps)}")
        for i, s in enumerate(steps):
            desc = s["do"]
            if s.get("target"):
                desc += f" target={s['target']} at {targets.get(s['target'])}"
            if s.get("label"):
                desc += f" label={s['label']!r}"
            print(f"  {i:02d} {desc}")
        unseen = plan.get("unseen") or []
        if unseen:
            print(f"[dry-run] unseen（产物局限声明，成片说明必须带上）: {unseen}")
        return

    # 延迟导入：dry-run 不需要 pyautogui/屏幕
    from gifkit import click, double_click, move, pyautogui, right_click, shot, typewrite

    for s in steps:
        do = s["do"]
        if do == "click":
            click(*targets[s["target"]], label=s.get("label"))
        elif do == "double_click":
            double_click(*targets[s["target"]], label=s.get("label"))
        elif do == "right_click":
            right_click(*targets[s["target"]], label=s.get("label"))
        elif do == "move":
            move(*targets[s["target"]], s.get("dur", 0.6))
        elif do == "wait":
            pyautogui.PAUSE = 0
            import time
            time.sleep(s["ms"] / 1000.0)
        elif do == "shot":
            shot(s["name"])
        elif do == "type":
            typewrite(s["text"])
        elif do == "key":
            pyautogui.press(s["key"])
        elif do == "drag":
            from gifkit import drag_select
            end = s.get("to")
            if end is None:
                raise ValueError("drag 需要 'to' target")
            drag_select(*targets[s["target"]], *targets[end], s.get("dur", 0.5))

    unseen = plan.get("unseen") or []
    if unseen:
        print("unseen（产物局限声明，成片说明必须带上）:")
        for u in unseen:
            print(f"  - {u}")


def main():
    ap = argparse.ArgumentParser(description="按 plan.json 确定性重放演示动作")
    ap.add_argument("plan", help="plan JSON 路径")
    ap.add_argument("--dry-run", action="store_true", help="只校验并打印，不碰鼠标")
    args = ap.parse_args()

    if not os.path.isfile(args.plan):
        print(f"plan 不存在: {args.plan}", file=sys.stderr)
        return 2
    try:
        plan = load_plan(args.plan)
    except ValueError as e:
        print(str(e), file=sys.stderr)
        return 1
    replay(plan, dry_run=args.dry_run)
    return 0


if __name__ == "__main__":
    sys.exit(main())
