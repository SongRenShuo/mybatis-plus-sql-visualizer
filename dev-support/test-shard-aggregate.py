# -*- coding: utf-8 -*-
"""test-shard-aggregate.py —— testShards 聚合公式的仓外替身（T-TestPerf12 入库）。

背景：官方聚合在 testShards 任务 doLast 内联，依赖分片全过才执行；O-93 已知泄漏等
红任务在册期间聚合被整体压制（T11 §三.6 注记的「守卫类工具可用性不能依赖被测对象
全绿」缺口，T11 曾用未入库的 python 替身，本件转正入库）。

公式与 buildSrc/src/main/kotlin/mpv-testing.gradle.kts 聚合段逐式镜像：
  - mtime 闸：各分片目录 .round-start 戳之后的 XML 才计入（缺戳退化全计）
  - 各片 tests/failures/errors/skipped/time + expected 对照（--log 提供均衡计划行时）
  - 失衡告警：(max-avg)/avg > 12%（同轮相对量，漂移免疫）
  - 缺权告警（T12 归一化版）：(mx/expCrit)/(sum(actual)/sum(expected)) > 1.10
  - SHARD TOTAL 与 testExternal 桶汇总

用法：
  python dev-support/test-shard-aggregate.py --log <轮日志路径> [--results-dir build/test-results] [--shards 5]
"""
import argparse
import glob
import os
import re
import sys


def suite_attrs(path):
    """读 JUnit XML 头部 <testsuite> 标签属性（与 gradle 侧 parseTestSuiteAttrs 同口径：只读头 8 行）。"""
    with open(path, encoding="utf-8", errors="replace") as f:
        head = "".join(f.readlines()[:8])
    if "<testsuite" not in head:
        return None
    tag = head.split("<testsuite", 1)[1].split(">", 1)[0]
    return dict(re.findall(r'([\w.-]+)="([^"]*)"', tag))


def round_start_ts(shard_dir):
    p = os.path.join(shard_dir, ".round-start")
    if os.path.isfile(p):
        with open(p, encoding="ascii", errors="replace") as f:
            v = f.read().strip()
        try:
            return int(v)
        except ValueError:
            return 0
    return 0


def collect(shard_dir):
    n = f = e = s = 0
    t = 0.0
    counted = 0
    seen = set()
    xmls = glob.glob(os.path.join(shard_dir, "TEST-*.xml"))
    rs = round_start_ts(shard_dir)
    for x in xmls:
        if rs > 0 and os.path.getmtime(x) * 1000 < rs:
            continue
        attrs = suite_attrs(x)
        if not attrs:
            continue
        if attrs.get("name"):
            seen.add(attrs["name"])

        def num(k):
            try:
                return float(attrs.get(k, 0))
            except (TypeError, ValueError):
                return 0.0

        n += int(num("tests"))
        f += int(num("failures"))
        e += int(num("errors"))
        s += int(num("skipped"))
        t += num("time")
        counted += 1
    return n, f, e, s, t, counted, len(xmls), seen


def expected_loads_from_log(log_path, shard_count):
    """从轮日志的均衡计划行提取 expected（T12 起=补偿后有效预期）。"""
    if not log_path or not os.path.isfile(log_path):
        return None
    pat = re.compile(r"testShards: \d+ shards duration-balanced[^,]*, loads=(.*)")
    with open(log_path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = pat.search(line)
            if m:
                vals = re.findall(r"shard\d+=([\d.]+)s", m.group(1))
                if len(vals) >= shard_count:
                    return [float(v) for v in vals[:shard_count]]
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", default=None, help="本轮 gradle 轮日志（提取均衡计划行 expected）")
    ap.add_argument("--results-dir", default=os.path.join("build", "test-results"))
    ap.add_argument("--shards", type=int, default=5)
    ap.add_argument("--quarantine-dir", default=None,
                    help="隔离类串行补跑的 results 目录；其计数计入 SHARD TOTAL 以对齐官方口径")
    args = ap.parse_args()

    expected = expected_loads_from_log(args.log, args.shards)
    shard_exec = []
    total = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    seen_all = set()
    tsum = 0.0
    for i in range(1, args.shards + 1):
        d = os.path.join(args.results_dir, f"testShard{i}")
        n, f, e, s, t, counted, xmls_n, seen = collect(d)
        seen_all |= seen
        shard_exec.append(t)
        exp_txt = ""
        if expected:
            exp_txt = " expected=%.1fs" % expected[i - 1]
        stale = ""
        if xmls_n > 0 and counted == 0:
            stale = "  [mtime 闸排除全部 %d 个 XML——该分片本轮真跑了吗？]" % xmls_n
        print(f"testShard{i}: tests={n} failures={f} errors={e} skipped={s} time={t:.1f}s{exp_txt}{stale}")
        total["tests"] += n
        total["failures"] += f
        total["errors"] += e
        total["skipped"] += s
        tsum += t

    if args.quarantine_dir and os.path.isdir(args.quarantine_dir):
        n, f, e, s, t, counted, xmls_n, _ = collect(args.quarantine_dir)
        print(f"quarantine(serial): tests={n} failures={f} errors={e} skipped={s} time={t:.1f}s")
        total["tests"] += n
        total["failures"] += f
        total["errors"] += e
        total["skipped"] += s
        tsum += t

    # T-JUnit5Eval 方案 2：纯逻辑快桶腿（testShards 聚合 dependsOn testFast）。计进 SHARD TOTAL
    # 与官方聚合同口径——分片只装平台类后，这 4000+ 例是分片之外的第二条腿，不计就等于「少了一大块
    # 还说总数正常」；不进 shard_exec（失衡判据只看分片本体，与官方实现一致）。
    d = os.path.join(args.results_dir, "testFast")
    if os.path.isdir(d):
        n, f, e, s, t, counted, xmls_n, _ = collect(d)
        print(f"testFast: tests={n} failures={f} errors={e} skipped={s} time={t:.1f}s")
        total["tests"] += n
        total["failures"] += f
        total["errors"] += e
        total["skipped"] += s

    print(f"SHARD TOTAL: tests={total['tests']} failures={total['failures']} errors={total['errors']} "
          f"skipped={total['skipped']} execSum={tsum:.1f}s (类并集 {len(seen_all)}，parity 计划集校验以官方聚合为准)")

    d = os.path.join(args.results_dir, "testExternal")
    if os.path.isdir(d):
        n, f, e, s, t, counted, xmls_n, _ = collect(d)
        print(f"testExternal: tests={n} failures={f} errors={e} skipped={s} time={t:.1f}s")

    # 双告警（与 mpv-testing 聚合段同判据）
    if len(shard_exec) >= 2:
        mx = max(shard_exec)
        avg = sum(shard_exec) / len(shard_exec)
        crit_idx = shard_exec.index(mx)
        if avg > 0 and (mx - avg) / avg > 0.12:
            print(f"testShards WARN: 临界片失衡 +{(mx - avg) / avg * 100:.0f}%（shard{crit_idx + 1} {mx:.1f}s vs 片均值 {avg:.1f}s）"
                  f"——权重失准/新类缺权嫌疑，clean 轮后跑 dev-support/weight-refresh.ps1 刷新画像")
        if expected:
            exp_crit = expected[crit_idx]
            exp_sum = sum(v for v in expected if v > 0)
            act_sum = sum(shard_exec)
            drift = act_sum / exp_sum if exp_sum > 0 and act_sum > 0 else 1.0
            rel = (mx / exp_crit) / drift if exp_crit and exp_crit > 0 else 1.0
            mark = "" if rel <= 1.10 else "  <-- WARN"
            print(f"[归一化缺权判据] shard{crit_idx + 1}: (mx/exp)={mx / exp_crit if exp_crit else 0:.3f} "
                  f"drift={drift:.3f} relCrit={rel:.3f}{mark}")


if __name__ == "__main__":
    sys.exit(main())
