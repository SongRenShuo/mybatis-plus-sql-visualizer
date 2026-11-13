# -*- coding: utf-8 -*-
"""Replay the R0 (cold) shard packing to recover shard5's include order.

R0 config had zero local XMLs, so weights came purely from
dev-support/test-shard-weights.tsv + median interpolation for classes the
profile lacks. The greedy algorithm mirrors mpv-testing.gradle.kts:
  - clipboard-affinity classes pinned to shard 1 first
  - remaining items sorted by (weight desc, name asc)
  - each appended to the currently lightest shard (ties -> lowest index)

ASCII-only stdout.
"""
import os
import re

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SRC = os.path.join(ROOT, "src", "test", "kotlin")
TSV = os.path.join(ROOT, "dev-support", "test-shard-weights.tsv")

DB_INTEGRATION = {
    "cn.song.mybatisplus.visualizer.execute.DbConsoleIntegrationTest",
    "cn.song.mybatisplus.visualizer.execute.DataSourceSelectorIntegrationTest",
    "cn.song.mybatisplus.visualizer.execute.HeavyNativeDatabaseDiagramJoinChainTest",
    "cn.song.mybatisplus.visualizer.execute.ExecDocGateIntegrationTest",
    "cn.song.mybatisplus.visualizer.execute.DbDriverPipelineTest",
}
EXTERNAL = {
    "cn.song.mybatisplus.visualizer.mcp.McpDbSignatureCrossVersionTest",
    "cn.song.mybatisplus.visualizer.update.PlatformSignatureCrossVersionTest",
    "cn.song.mybatisplus.visualizer.acp.AcpSignatureCrossVersionTest",
    "cn.song.mybatisplus.visualizer.config.SettingsRealityGuardTest",
    "cn.song.mybatisplus.visualizer.convention.BannedApiSourceScanTest",
    "cn.song.mybatisplus.visualizer.convention.EnumReachabilityGuardTest",
}
SHARD_COUNT = 5


def discover():
    entries = []
    for dirpath, _, names in os.walk(SRC):
        for n in names:
            if not (n.endswith("Test.kt") or n.endswith("Test.java")):
                continue
            p = os.path.join(dirpath, n)
            text = open(p, encoding="utf-8", errors="replace").read()
            pkg_m = re.search(r"(?m)^\s*package\s+([\w.]+)", text)
            pkg = pkg_m.group(1) if pkg_m else ""
            classes = re.findall(
                r"(?m)^(?:public\s+|internal\s+|open\s+|abstract\s+|final\s+|data\s+|sealed\s+|annotation\s+)*class\s+(\w+)\b",
                text)
            classes = [c for c in classes if c.endswith("Test")] or [n.split(".")[0]]
            affinity = "CopyPasteManager" in text or "systemClipboard" in text
            for c in classes:
                fqcn = c if not pkg else pkg + "." + c
                entries.append((fqcn, affinity))
    return sorted(set(entries))


def main():
    profile = {}
    for line in open(TSV, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        tab = line.find("\t")
        if tab <= 0:
            continue
        try:
            w = float(line[tab + 1:].strip())
        except ValueError:
            continue
        if w > 0:
            profile[line[:tab].strip()] = w

    known = sorted(profile.values())
    median = known[len(known) // 2] if known else 1.0

    entries = discover()
    planned = [fq for fq, _ in entries if fq not in DB_INTEGRATION and fq not in EXTERNAL]
    affinity = [fq for fq, aff in entries if aff and fq not in DB_INTEGRATION and fq not in EXTERNAL]

    weights = {fq: profile.get(fq, median) for fq in planned}
    print("discovered=%d planned=%d affinity=%d profile=%d median=%.3f" % (
        len(entries), len(planned), len(affinity), len(profile), median))

    shards = [[] for _ in range(SHARD_COUNT)]
    loads = [0.0] * SHARD_COUNT
    order = []
    for fq in sorted(affinity):
        shards[0].append(fq)
        loads[0] += weights.get(fq, median)
        order.append((0, fq))
    items = sorted((w, fq) for fq, w in weights.items() if fq not in set(affinity))
    items.sort(key=lambda t: (-t[0], t[1]))
    for w, fq in items:
        i = loads.index(min(loads))
        shards[i].append(fq)
        loads[i] += w
        order.append((i, fq))

    print("loads=", " ".join("%.1f" % l for l in loads))
    s5 = [fq for i, fq in order if i == 4]
    print("shard5 count=%d" % len(s5))
    target = "cn.song.mybatisplus.visualizer.ui.SqlPreviewServiceOfficialApiTest"
    if target in s5:
        pos = s5.index(target)
        print("OfficialApiTest position=%d of %d" % (pos + 1, len(s5)))
        print("== classes BEFORE it in shard5 (include order) ==")
        for fq in s5[:pos]:
            print("  %.1fs  %s" % (weights.get(fq, median), fq))
    else:
        print("OfficialApiTest NOT in shard5 — replay mismatch, assignment drift?")


if __name__ == "__main__":
    main()
