# -*- coding: utf-8 -*-
"""T-TestPerf13 S2: fixed-sleep audit over src/test.

Classifies every Thread.sleep / TimeUnit.*.sleep call site:
  polling  - guarded by a deadline/condition loop (exits as soon as the real
             signal arrives; harvest = 0, this is the correct shape)
  fixed    - unconditional wait; harvest candidate = full duration
  unknown  - heuristic could not decide; needs eyeball

Per-class totals line up against the shard weight table so the suite-level
harvest ceiling can be judged against execSum (~640s clean rounds).

ASCII-only stdout (Git Bash console mangles CJK).
"""
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "test", "kotlin"))

SLEEP_RE = re.compile(r"Thread\.sleep\((\d+)(?:L)?\)|TimeUnit\.(\w+)\.sleep\((\d+)(?:L)?\)")
UNIT_MS = {"MILLISECONDS": 1, "SECONDS": 1000, "MINUTES": 60_000}

POLL_CTX = re.compile(r"deadline|currentTimeMillis|isAlive|\.await|waitFor", re.IGNORECASE)
LOOP_RE = re.compile(r"\bwhile\b|\bfor\b")


def classify(lines, idx):
    """Look at +-8 lines around the call site."""
    lo = max(0, idx - 8)
    hi = min(len(lines), idx + 9)
    before = "\n".join(lines[lo:idx])
    after = "\n".join(lines[idx + 1:hi])
    in_loop = LOOP_RE.search(before) is not None
    guarded = POLL_CTX.search(before) is not None or POLL_CTX.search(after) is not None
    early_exit = re.search(r"\breturn\b|\bbreak\b", after) is not None
    if in_loop and (guarded or early_exit):
        return "polling"
    if in_loop:
        return "unknown"
    return "fixed"


def top_class(lines, path):
    m = re.search(r"(?m)^(?:public\s+|internal\s+|open\s+|abstract\s+|final\s+|class\s+)+class\s+(\w+)", "\n".join(lines))
    if m:
        return m.group(1)
    return os.path.splitext(os.path.basename(path))[0]


def main():
    rows = []  # (class, file-rel, line, ms, kind)
    for dirpath, _, names in os.walk(ROOT):
        for n in names:
            if not (n.endswith("Test.kt") or n.endswith("Test.java")
                    or n.endswith(".kt") or n.endswith(".java")):
                continue
            p = os.path.join(dirpath, n)
            rel = os.path.relpath(p, ROOT).replace("\\", "/")
            try:
                text = open(p, encoding="utf-8", errors="replace").read()
            except OSError as e:
                print("SKIP %s (%s)" % (rel, e))
                continue
            lines = text.splitlines()
            for i, line in enumerate(lines):
                m = SLEEP_RE.search(line)
                if not m:
                    continue
                if m.group(1) is not None:
                    ms = int(m.group(1))
                else:
                    unit = m.group(2)
                    if unit not in UNIT_MS:
                        print("SKIP-UNIT %s:%d TimeUnit.%s" % (rel, i + 1, unit))
                        continue
                    ms = int(m.group(3)) * UNIT_MS[unit]
                # support simple arithmetic like DEFAULT_SETTLE_MS + 150
                if "+" in line and "(" in line:
                    nums = [int(x) for x in re.findall(r"(\d+)(?:L)?", line)]
                    if nums:
                        ms = sum(nums)
                kind = classify(lines, i)
                rows.append((top_class(lines, p), rel, i + 1, ms, kind))

    fixed = [r for r in rows if r[4] == "fixed"]
    unknown = [r for r in rows if r[4] == "unknown"]
    polling = [r for r in rows if r[4] == "polling"]

    print("== TOTALS ==")
    print("sites=%d polling=%d fixed=%d unknown=%d" % (len(rows), len(polling), len(fixed), len(unknown)))
    print("fixed_sum_ms=%d (%.1fs) unknown_sum_ms=%d (%.1fs)" % (
        sum(r[3] for r in fixed), sum(r[3] for r in fixed) / 1000.0,
        sum(r[3] for r in unknown), sum(r[3] for r in unknown) / 1000.0))

    print("== FIXED SITES (harvest candidates) ==")
    for cls, rel, line, ms, _ in sorted(fixed, key=lambda r: -r[3]):
        print("%6dms  %s:%d  [%s]" % (ms, rel.replace("\\", "/"), line, cls))

    print("== UNKNOWN SITES (eyeball) ==")
    for cls, rel, line, ms, _ in sorted(unknown, key=lambda r: -r[3]):
        print("%6dms  %s:%d  [%s]" % (ms, rel.replace("\\", "/"), line, cls))

    # non-literal sleep arguments (e.g. Thread.sleep(SOME_CONST + 150)) the
    # literal regex cannot size; list them so the audit total stays honest
    print("== NON-LITERAL ARG SITES (manual accounting) ==")
    parsed_lines = {(r[1], r[2]) for r in rows}
    n_expr = 0
    for dirpath, _, names in os.walk(ROOT):
        for n in names:
            if not (n.endswith(".kt") or n.endswith(".java")):
                continue
            p = os.path.join(dirpath, n)
            rel = os.path.relpath(p, ROOT).replace("\\", "/")
            try:
                lines = open(p, encoding="utf-8", errors="replace").read().splitlines()
            except OSError:
                continue
            for i, line in enumerate(lines):
                if re.search(r"\.sleep\(", line) and (rel, i + 1) not in parsed_lines \
                        and not line.strip().startswith(("*", "//", "/*")):
                    n_expr += 1
                    print("  expr  %s:%d  %s" % (rel, i + 1, line.strip()[:100]))
    if n_expr == 0:
        print("  (none)")

    print("== POLLING PER-CALL MAX STEP (worst 10, for reference) ==")
    for cls, rel, line, ms, _ in sorted(polling, key=lambda r: -r[3])[:10]:
        print("%6dms  %s:%d  [%s]" % (ms, rel.replace("\\", "/"), line, cls))


if __name__ == "__main__":
    sys.exit(main())
