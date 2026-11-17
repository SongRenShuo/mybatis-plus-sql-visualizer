#!/usr/bin/env python3
"""test-shard-replay.py —— 离线复算 testShardN 的类清单（CI 冷树口径）。

用途：CI 上某个分片红/超时时，日志里只有一行 `testShards: 5 shards duration-balanced,
loads=shard1=175.9s/158classes ...`，看不出该片装了哪些类；本脚本按
`buildSrc/src/main/kotlin/mpv-testing.gradle.kts` 的贪心装箱算法离线复算，打印类清单。

CI 冷树（新检出）没有 `build/test-results` 历史样本 ⇒ 权重只吃入库画像
`dev-support/test-shard-weights.tsv`，画像缺的类取已知权重中位数；无 S3 头部巨类拆分、
无 auto-handicap。因此分配是**确定性**的：脚本打印的 loads 行应与 CI 日志逐字一致，
可用它自校验是否复算到了同一份计划（`-PtestShardIgnoreLocalWeights=true
-PtestSplitHeavy=false` 的本地跑法也能对齐）。

用法：
  python dev-support/test-shard-replay.py                    # 复算当前工作树，打印 5 片摘要
  python dev-support/test-shard-replay.py --shard 5           # 另打印第 5 片的类清单（含画像权重）
  python dev-support/test-shard-replay.py --shard 5 --json     # 机器可读输出
  python dev-support/test-shard-replay.py --tree <dir> --shard 5   # 复算另一棵树（如 git archive 出的旧版本）
  python dev-support/test-shard-replay.py --verify "shard1=175.9s/158classes shard2=..."  # 与 CI 行比对

判读：打印行若与 CI 的 `loads=` 行一致 ⇒ 类清单可信；不一致说明权重画像/类集与 CI 不同
（例如本地有 build/test-results 样本时，别忘 `-PtestShardIgnoreLocalWeights=true` 的口径）。
"""

import argparse
import json
import os
import re
import sys

SHARD_COUNT_DEFAULT = 5
SHARD_EXCLUDE_DEFAULT = [
    # CI 与手动门禁同口径剔除的真机依赖类（见 .github/workflows/push-test.yml 的 -PtestShardExcludeClasses）
    "cn.song.mybatisplus.visualizer.execute.DataSourceJdbcSmokeTest",
    "cn.song.mybatisplus.visualizer.log.O147RealAgentAcceptanceTest",
]

# mpv-testing.gradle.kts 的 dbIntegrationClasses（只在 testDatabase 通道跑，不进分片）
DB_INTEGRATION = {
    "cn.song.mybatisplus.visualizer.execute.DbConsoleIntegrationTest",
    "cn.song.mybatisplus.visualizer.execute.DataSourceSelectorIntegrationTest",
    "cn.song.mybatisplus.visualizer.execute.HeavyNativeDatabaseDiagramJoinChainTest",
    "cn.song.mybatisplus.visualizer.execute.ExecDocGateIntegrationTest",
    "cn.song.mybatisplus.visualizer.execute.DbDriverPipelineTest",
    "cn.song.mybatisplus.visualizer.ui.annotate.PreviewUnknownColumnAnnotatorTest",
    "cn.song.mybatisplus.visualizer.execute.PreviewSideQueryIntegrationTest",
    "cn.song.mybatisplus.visualizer.database.PlatformSqlDialectRegistrationTest",
    "cn.song.mybatisplus.visualizer.execute.ExecPsiGateTest",
}

# 签名护栏外部分离桶（testExternal）
TEST_EXTERNAL = {
    "cn.song.mybatisplus.visualizer.mcp.McpDbSignatureCrossVersionTest",
    "cn.song.mybatisplus.visualizer.update.PlatformSignatureCrossVersionTest",
    "cn.song.mybatisplus.visualizer.acp.AcpSignatureCrossVersionTest",
    "cn.song.mybatisplus.visualizer.config.SettingsRealityGuardTest",
    "cn.song.mybatisplus.visualizer.convention.BannedApiSourceScanTest",
    "cn.song.mybatisplus.visualizer.convention.EnumReachabilityGuardTest",
}

PKG_RE = re.compile(r"^\s*package\s+([\w.]+)", re.M)
CLS_RE = re.compile(
    r"(?m)^(?:public\s+|internal\s+|open\s+|abstract\s+|final\s+|data\s+|sealed\s+|annotation\s+)*class\s+(\w+)\b"
)
WEIGHTS_TSV = os.path.join("dev-support", "test-shard-weights.tsv")


def discover(tree):
    """扫 src/test/kotlin 推导 (FQCN -> 源码文本)，与 buildSrc 的 testFileEntries 同口径。"""
    root = os.path.join(tree, "src", "test", "kotlin")
    out = {}
    for dirpath, _dirs, files in os.walk(root):
        for fn in files:
            if not (fn.endswith("Test.kt") or fn.endswith("Test.java")):
                continue
            path = os.path.join(dirpath, fn)
            with open(path, encoding="utf-8", errors="replace") as fh:
                text = fh.read()
            m = PKG_RE.search(text)
            pkg = m.group(1) if m else ""
            names = [c for c in CLS_RE.findall(text) if c.endswith("Test")]
            if not names:
                names = [fn.rsplit(".", 1)[0]]
            for cn in names:
                fq = f"{pkg}.{cn}" if pkg else cn
                out[fq] = text
    return out


def load_profile(tree):
    prof = {}
    path = os.path.join(tree, WEIGHTS_TSV)
    if not os.path.isfile(path):
        return prof
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            tab = line.find("\t")
            if tab <= 0:
                continue
            try:
                sec = float(line[tab + 1:].strip())
            except ValueError:
                continue
            if sec > 0:
                prof[line[:tab].strip()] = sec
    return prof


def plan(tree, shard_count, excludes):
    texts = discover(tree)
    prof = load_profile(tree)
    excluded = DB_INTEGRATION | TEST_EXTERNAL | set(excludes)
    classes = sorted(c for c in texts if c not in excluded)
    known = sorted(prof.values())
    median = known[len(known) // 2] if known else 1.0
    weights = {c: prof.get(c, median) for c in classes}
    missing = [c for c in classes if c not in prof]

    # 机器级全局资源亲和（Windows 剪贴板全机唯一）→ 固定第 1 片
    affinity = {c for c in classes
                if "CopyPasteManager" in texts[c] or "systemClipboard" in texts[c]}

    shards = [[] for _ in range(shard_count)]
    loads = [0.0] * shard_count
    for c in sorted(affinity):
        shards[0].append(c)
        loads[0] += weights[c]

    items = sorted(((c, weights[c]) for c in classes if c not in affinity),
                   key=lambda t: (-t[1], t[0]))
    for c, w in items:
        tgt = min(range(shard_count), key=lambda i: (loads[i], i))
        shards[tgt].append(c)
        loads[tgt] += w

    # 按权重降序重排槽位（testShard1 恒最重），与 buildSrc 一致
    order = sorted(range(shard_count), key=lambda i: -loads[i])
    shards = [shards[i] for i in order]
    loads = [loads[i] for i in order]
    return {
        "classes": len(classes),
        "profile_entries": len(prof),
        "missing_profile": missing,
        "median": median,
        "affinity": sorted(affinity),
        "shards": shards,
        "loads": loads,
        "weights": weights,
    }


def summary_line(res):
    return "testShards: %d shards duration-balanced, loads=" % len(res["shards"]) + " ".join(
        "shard%d=%.1fs/%dclasses" % (i + 1, res["loads"][i], len(res["shards"][i]))
        for i in range(len(res["shards"]))
    )


def main(argv=None):
    ap = argparse.ArgumentParser(description="离线复算 testShardN 的类清单（CI 冷树口径）")
    ap.add_argument("--tree", default=".", help="仓库（或 git archive 出的树）根目录，默认当前目录")
    ap.add_argument("--shard", type=int, default=0, help="打印第 N 片（1 起）的类清单")
    ap.add_argument("--shards", type=int, default=SHARD_COUNT_DEFAULT, help="片数（默认 5）")
    ap.add_argument("--exclude", action="append", default=[],
                    help="额外排除的 FQCN（可重复；默认已含 CI 的两类真机依赖）")
    ap.add_argument("--json", action="store_true", help="机器可读输出")
    ap.add_argument("--verify", default="", help="与 CI 的 loads 段逐字比对，例如 'shard1=175.9s/158classes ...'")
    args = ap.parse_args(argv)

    tree = os.path.abspath(args.tree)
    res = plan(tree, args.shards, SHARD_EXCLUDE_DEFAULT + args.exclude)
    line = summary_line(res)

    if args.json:
        out = {
            "classes": res["classes"],
            "profile_entries": res["profile_entries"],
            "missing_profile": res["missing_profile"],
            "median": res["median"],
            "loads": res["loads"],
            "shards": {str(i + 1): res["shards"][i] for i in range(len(res["shards"]))},
            "summary": line,
        }
        print(json.dumps(out, ensure_ascii=False, indent=2))
        return 0

    print("classes=%d profile=%d missing_profile=%d(median=%.2f)"
          % (res["classes"], res["profile_entries"], len(res["missing_profile"]), res["median"]))
    print(line)

    if args.verify:
        want = args.verify.strip()
        ok = want == line.split("loads=", 1)[1].strip()
        print("verify:", "MATCH (与 CI 分片计划一致)" if ok else "MISMATCH（权重口径不同，检查本地 test-results / 画像）")
        if not ok:
            print("  want:", want)
            print("  got :", line.split("loads=", 1)[1].strip())

    if 1 <= args.shard <= len(res["shards"]):
        sel = res["shards"][args.shard - 1]
        print()
        print("=== shard%d (%d classes) ===" % (args.shard, len(sel)))
        for c in sorted(sel, key=lambda c: -res["weights"][c]):
            mark = " [NO-PROFILE]" if c in set(res["missing_profile"]) else ""
            print("%8.2f  %s%s" % (res["weights"][c], c, mark))
    elif args.shard:
        print("shard 越界：1..%d" % len(res["shards"]), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
