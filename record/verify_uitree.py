# -*- coding: utf-8 -*-
"""uitree 最小验证（T-TestGateGems W6-d 配套，2026-10-24）。

`record/uitree.py` 依赖 JVM 里的 Swing 组件对象，正常无法在无 IDE 的环境跑。本脚本用**最小 Swing 桩**
（只实现 uitree 会碰的接口：`isShowing` / `getAccessibleContext` / `getComponents` / `getName` /
`getLocationOnScreen` / `width` / `height` / `getText`）验证判定内核本身没写坏——正例证明「看得见该看的」，
反例证明「看不见不该看的」。

为什么要这份文件：`uitree.py` 首次交付时只有「文件存在」一条证据（未编译、未执行），是明显的弱验证。
本脚本把验证固化成可重跑的一步：`python record/verify_uitree.py`。

用法：
    python record/verify_uitree.py        # 退出码 0 = 全通过
"""
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import uitree  # noqa: E402


# ---------------- 最小 Swing 桩 ----------------
class _Role:
    def __init__(self, name):
        self._n = name

    def __str__(self):
        return self._n


class _Ctx:
    def __init__(self, name, role):
        self._name, self._role = name, role

    def getAccessibleName(self):
        return self._name

    def getAccessibleRole(self):
        return _Role(self._role) if self._role else None


class _Point:
    def __init__(self, x, y):
        self.x, self.y = x, y


class FakeComp:
    """假组件：类名用构造参数（uitree 取 type(comp).__name__，故子类化以得到真实类名）。"""

    def __init__(self, text=None, name=None, showing=True, rect=(10, 20, 100, 30),
                 role=None, children=None):
        self._text, self._name = text, name
        self._showing, self._rect, self._role = showing, rect, role
        self._children = children or []

    def isShowing(self):
        return self._showing

    def getAccessibleContext(self):
        return _Ctx(self._text, self._role)

    def getComponents(self):
        return self._children

    def getName(self):
        return self._name

    def getLocationOnScreen(self):
        if self._rect is None:
            raise RuntimeError("not on screen")
        return _Point(self._rect[0], self._rect[1])

    @property
    def width(self):
        return self._rect[2] if self._rect else 0

    @property
    def height(self):
        return self._rect[3] if self._rect else 0

    def getText(self):
        return self._text


class JBPanel(FakeComp):
    pass


class JBLabel(FakeComp):
    pass


class ActionButton(FakeComp):
    pass


class JButton(FakeComp):
    pass


def build_fixture():
    hidden = JButton("删除", showing=False)
    nested = JBPanel(children=[JBLabel("嵌套标签")])
    root = JBPanel(role="panel", children=[
        JBLabel("执行 SQL"),
        ActionButton("执行计划", rect=(1256, 829, 90, 28)),
        hidden,
        nested,
    ])
    return root


def main():
    root = build_fixture()
    failures = []

    # ---------- 正例 ----------
    lines = uitree.build_tree(root)
    texts = [t for _, t in lines]
    if not any("执行 SQL" in t for t in texts):
        failures.append("build_tree 漏了可见标签「执行 SQL」")
    if not any("执行计划" in t for t in texts):
        failures.append("build_tree 漏了可见按钮「执行计划」")
    if not any("rect=(1256,829 90x28)" in t for t in texts):
        failures.append("build_tree 没带出屏幕矩形")
    if not any("嵌套标签" in t for t in texts):
        failures.append("build_tree 漏了嵌套子组件（递归失效）")
    if not any("[role=panel]" in t for t in texts):
        failures.append("build_tree 没带出 accessible role")
    if not any(t.lstrip().startswith("JBPanel") for t in texts):
        failures.append("build_tree 没带出组件类名")

    rect = uitree.find_by_text(root, "执行计划")
    if rect != [1256, 829, 90, 28]:
        failures.append(f"find_by_text 命中结果错：{rect!r}")

    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "tree.txt")
        n = uitree.dump_tree(root, out)
        body = open(out, encoding="utf-8").read()
        if n <= 0 or "执行 SQL" not in body:
            failures.append("dump_tree 落文件内容不对")

    # ---------- 反例 ----------
    if any("删除" in t for t in texts):
        failures.append("build_tree 把 isShowing=false 的组件也 dump 了（可见性剪枝失效）")
    if uitree.find_by_text(root, "删除") is not None:
        failures.append("find_by_text 命中了隐藏组件（剪枝失效）")
    if uitree.find_by_text(root, "根本不存在的标签") is not None:
        failures.append("find_by_text 对不存在的文本没返回 None")
    if uitree.find_by_text(root, "") is not None:
        failures.append("find_by_text 对空 needle 没返回 None")

    many = JBPanel(children=[JBLabel(f"行{i}") for i in range(50)])
    cut = [x for _, x in uitree.build_tree(many, max_lines=10)]
    if not any("(cut)" in t for t in cut):
        failures.append("max_lines 截断没写 (cut)")
    if len(cut) > 11:
        failures.append(f"max_lines 未生效（输出 {len(cut)} 行）")

    if failures:
        print("FAIL:")
        for f in failures:
            print("  -", f)
        return 1

    print("PASS 正例：build_tree 带出可见组件/类名/role/rect/嵌套；find_by_text 命中并给矩形；dump_tree 落文件")
    print("PASS 反例：隐藏组件不进树且不可命中、不存在返回 None、空 needle 返回 None、max_lines 截断生效")
    print(f"       tree lines = {len(lines)}, cut lines = {len(cut)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
