# -*- coding: utf-8 -*-
"""界面可定位树 dump（T-TestGateGems W6-d，2026-10-24）。

来源：magpie `.github/ui-preview/record.mjs:57-86` 的 `outline()`——喂给 LLM 的不是截图，而是
`tag#id.class[data-x="…"] "自己的文本"` 的缩进树（折叠线以下标 `(below the fold)`）。理由写在它的注释里：
"The page as a compact outline the planner can pick real selectors from"。

为什么要抄这一条：本仓场景脚本的坐标靠人工标定（`record/BASELINE.md`），构图一变整表作废。而
「让 LLM 看截图猜坐标」不可靠（magpie 的 git log 记录过两次失败：一次说「没有可见变化」、一次点错
拖拽把手）。正确分工是：**语义层回答「点哪个控件」，锚点层回答「它在屏幕哪里」**。

本工具只做前半段：把 IDE 当前界面 dump 成可定位树（类名 / 无障碍名 / 可见文本 / 屏幕矩形），
供 LLM 产出 plan.json 的 `target` 语义键；坐标仍由人工/锚点标定填入 `targets`。

用法（需 IDE 在跑；纯 Swing 遍历，走 JVM 内调用或 attach 后执行）：
    # 方式一：在 IDE 内（如 robot-server / DebugTools 的 Groovy 脚本）调用 dumpTree()
    # 方式二：本文件作为脚本被 IDE 插件进程加载时使用（见 buildTree 的注释）

输出格式（缩进树，最多 max_lines 行）：
    JBPanel<...>[role=panel]
      JBLabel "执行 SQL"
      ActionButton "执行计划"  rect=(1256,829 90x28)
      ... (cut)
"""
import json


def _text_of(comp):
    """取组件自身文本：优先无障碍名，其次已知 getText/文本属性。"""
    # 无障碍名（Swing 的 accessibleContext.accessibleName 最接近「用户看到的标签」）
    try:
        ctx = comp.getAccessibleContext()
        if ctx is not None:
            name = ctx.getAccessibleName()
            if name:
                return str(name)
    except Exception:
        pass
    for attr in ("getText", "getTitle", "getToolTipText"):
        try:
            fn = getattr(comp, attr, None)
            if fn is None:
                continue
            v = fn()
            if v:
                return str(v)
        except Exception:
            continue
    return ""


def _role_of(comp):
    try:
        ctx = comp.getAccessibleContext()
        if ctx is not None:
            r = ctx.getAccessibleRole()
            if r is not None:
                return str(r)
    except Exception:
        pass
    return ""


def _rect_of(comp):
    """屏幕坐标矩形（物理像素，与 pyautogui 同坐标系）。不可见时返回 None。"""
    try:
        if not comp.isShowing():
            return None
        p = comp.getLocationOnScreen()
        return [int(p.x), int(p.y), int(comp.width), int(comp.height)]
    except Exception:
        return None


def _name_of(comp):
    """稳定标识：组件名（setName）优先，其次类简名。"""
    try:
        n = comp.getName()
        if n:
            return f"#{n}"
    except Exception:
        pass
    return ""


def build_tree(root, max_depth=12, max_lines=420):
    """把组件树转成 [(depth, 行文本)] 列表，按可见性剪枝。

    - 只保留可见组件（`isShowing`）——折叠/隐藏的内容不该进 LLM 的候选集；
    - 无文本、无 role、无子节点的容器也保留（它可能正是可点击容器），但只留类名；
    - 超过 max_lines 写 `... (cut)`，与 magpie 一致（让模型知道后面还有）。
    """
    out = []

    def walk(comp, depth):
        if len(out) >= max_lines:
            return
        if depth > max_depth:
            return
        try:
            visible = comp.isShowing()
        except Exception:
            visible = False
        if not visible:
            return

        cls = type(comp).__name__
        parts = [cls + _name_of(comp)]
        role = _role_of(comp)
        if role:
            parts.append(f"[role={role}]")
        text = _text_of(comp)
        if text:
            parts.append(json.dumps(text, ensure_ascii=False))
        rect = _rect_of(comp)
        if rect:
            parts.append(f"rect=({rect[0]},{rect[1]} {rect[2]}x{rect[3]})")
        out.append((depth, "  " * depth + " ".join(parts)))

        try:
            children = comp.getComponents()
        except Exception:
            children = []
        for child in children:
            walk(child, depth + 1)

    walk(root, 0)
    if len(out) >= max_lines:
        out.append((0, "  ... (cut)"))
    return out


def dump_tree(root, path=None, max_depth=12, max_lines=420):
    """dump 到 stdout 或文件。返回行数。"""
    lines = [text for _, text in build_tree(root, max_depth, max_lines)]
    body = "\n".join(lines)
    if path:
        with open(path, "w", encoding="utf-8") as f:
            f.write(body + "\n")
    else:
        print(body)
    return len(lines)


def find_by_text(root, needle):
    """在树里按可见文本找组件，返回第一个命中的屏幕矩形。

    这是「语义 → 坐标」的最小实现：plan 的 target 语义键由 LLM 产出后，
    坐标可用本函数按标签实时解析，不必写死。找不到返回 None（调用方决定是否中止）。
    """
    if not needle:
        return None
    stack = [root]
    while stack:
        comp = stack.pop()
        try:
            if not comp.isShowing():
                continue
        except Exception:
            continue
        text = _text_of(comp)
        if text and needle in text:
            rect = _rect_of(comp)
            if rect:
                return rect
        try:
            stack.extend(comp.getComponents())
        except Exception:
            continue
    return None
