# -*- coding: utf-8 -*-
"""闸门 1（20261026-0330 计划 §5.2）：录制窗口内禁止 goto*。

扫描 record/scene_*.py：若 `Recorder.start()` / `.start()` 调用**之后**出现
`goto_line(` / `goto_col(` / `goto_token(` / `goto(`，报错退出（退出码 1）。

文本级启发，不做语法分析——scene 脚本是平铺直叙的动作序列，字符串匹配已够用：
- 以 `.start()` 首次出现的行为界（prep 阶段的 goto 不受限，这正是计划修法 1 的形态）；
- 行首 `#` 注释里的 goto 不算（只提醒不拦）；
- `from nav import ... goto...` 的 import 行不算。

用法：python gate_goto_guard.py [scene 文件...]   # 缺省扫全部 scene_*.py
"""
import glob
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
GOTO_RE = re.compile(r"(?<![\w.])(goto_line|goto_col|goto_token|goto)\s*\(")
COMMENT_RE = re.compile(r"^\s*#")
START_RE = re.compile(r"\.start\s*\(")


def check(path):
    with open(path, encoding="utf-8") as f:
        lines = f.readlines()
    started = False
    hits = []
    for no, line in enumerate(lines, 1):
        if START_RE.search(line) and "Recorder" in "".join(lines[max(0, no - 6):no]):
            # start() 且前几行有 Recorder 构造——进入录制窗口
            started = True
            continue
        if not started:
            continue
        m = GOTO_RE.search(line)
        if m and not COMMENT_RE.match(line) and "import" not in line.split(m.group(1))[0]:
            hits.append((no, line.rstrip()))
    return hits


def main():
    targets = sys.argv[1:] or sorted(glob.glob(os.path.join(HERE, "scene_*.py")))
    bad = 0
    for path in targets:
        hits = check(path)
        if hits:
            bad += 1
            print(f"[GATE-1 FAIL] {os.path.basename(path)}：start() 之后仍有 goto* 调用")
            for no, line in hits:
                print(f"    L{no}: {line.strip()}")
    if bad:
        print(f"\n{bad} 个脚本未过闸门 1（录制窗口内 goto* 会把 Ctrl+G 弹窗带进画面）")
        sys.exit(1)
    print(f"[GATE-1 PASS] {len(targets)} 个 scene 脚本：录制窗口内零 goto*")


if __name__ == "__main__":
    main()
