#!/usr/bin/env python3
"""
平台 EP 动态性取证：`com.intellij.sql.dialect` 在各目标平台上是否 `dynamic="true"`。

## 为什么需要这个脚本

平台的动态插件机制有一条硬规则：插件扩展了**非动态** EP 就不可被动态卸载（更新只能重启生效）。
本插件在 `META-INF/database-optional.xml` 里为 DM / GaussDB / HighGo / Vastbase 注册了
`com.intellij.sql.dialect`，而该 EP 的动态性**随平台版本变化**——这条事实决定了
「免重启热替换」在哪些平台上可用。

2026-11-06 实测结论（本脚本可复跑）：

| 平台 | `com.intellij.sql.dialect` | 本插件可热卸载 |
|---|---|---|
| 243（IU-2024.3.7.1） | 无 `dynamic` | 否（只能重启生效） |
| 253（IU-253.33514.17） | `dynamic="true"` | 是 |
| 262（2026.2 最新发行） | `dynamic="true"` | 是（用户真机确认） |
| 263（IU-263.7307.200） | `dynamic="true"` | 是 |

## 做法（不落 1.6GB 整包）

对 JetBrains 官方发行 zip 走 HTTP Range：
1. 取 EOCD / 中央目录，定位 `plugins/DatabaseTools/lib/database-plugin.jar`；
2. 只下载该条目并按 deflate 解开；
3. 在 jar 内扫全部 XML，打印每一处 `<extensionPoint ... com.intellij.sql.dialect ...>` 声明。

243 不必走网络：本地 IPGP 解包目录里直读即可（`--local-home` 指到 transformed 的 IDE 根目录）。

## 用法

    # 三个在网发行版（默认 253 / 262 / 263）
    python dev-support/probe-platform-ep-dynamism.py

    # 追加/替换目标（任意多个，形式 版本名=zip 地址）
    python dev-support/probe-platform-ep-dynamism.py \
        --target 2026.2=https://download.jetbrains.com/idea/idea-2026.2.win.zip

    # 本地已解包的 IDE（243 这类走这条，别浪费 1.6GB 流量）
    python dev-support/probe-platform-ep-dynamism.py \
        --local-home "C:/Users/USER/.gradle/caches/9.7.0/transforms/<hash>/transformed/ideaIU-2024.3.7.1-win"

代理：默认 `http://127.0.0.1:7890`（本机约定），可用 `--proxy ""` 关掉。
"""
import argparse
import io
import os
import re
import subprocess
import sys
import tempfile
import zipfile
import zlib

DEFAULT_TARGETS = [
    ("253", "https://download.jetbrains.com/idea/idea-2025.3.5.win.zip"),
    ("262", "https://download.jetbrains.com/idea/idea-2026.2.win.zip"),
    ("263", "https://download.jetbrains.com/idea/idea-2026.3.win.zip"),
]

EP = "com.intellij.sql.dialect"
DB_PLUGIN_ENTRY = "plugins/DatabaseTools/lib/database-plugin.jar"
LOCAL_DB_PLUGIN = os.path.join("plugins", "DatabaseTools", "lib", "database-plugin.jar")


def curl(args):
    subprocess.run(args, check=True)


def http_size(url, proxy):
    cmd = ["curl", "-sS", "-I", "-L", "--max-time", "120"]
    if proxy:
        cmd[1:1] = ["-x", proxy]
    out = subprocess.run(cmd + [url], capture_output=True, text=True, check=True).stdout
    lens = re.findall(r"(?im)^content-length:\s*(\d+)", out)
    if not lens:
        raise RuntimeError(f"取不到 Content-Length：{url}")
    return int(lens[-1])


def http_range(url, start, end, out_path, proxy):
    cmd = ["curl", "-sS", "-L", "--max-time", "900", "-r", f"{start}-{end}", "-o", out_path]
    if proxy:
        cmd[1:1] = ["-x", proxy]
    curl(cmd + [url])
    with open(out_path, "rb") as f:
        return f.read()


def central_directory(url, proxy, tmpdir):
    total = http_size(url, proxy)
    tail_len = min(total, 1 << 20)
    tail = http_range(url, total - tail_len, total - 1, os.path.join(tmpdir, "tail.bin"), proxy)
    idx = tail.rfind(b"PK\x05\x06")
    if idx < 0:
        raise RuntimeError("EOCD 未找到")
    eocd = tail[idx: idx + 22]
    entries = int.from_bytes(eocd[10:12], "little")
    cd_size = int.from_bytes(eocd[12:16], "little")
    cd_off = int.from_bytes(eocd[16:20], "little")
    if cd_off == 0xFFFFFFFF or cd_size == 0xFFFFFFFF or entries == 0xFFFF:
        loc = tail.rfind(b"PK\x06\x07")
        z64_off = int.from_bytes(tail[loc + 8: loc + 16], "little")
        z = http_range(url, z64_off, z64_off + 55, os.path.join(tmpdir, "z64.bin"), proxy)
        entries = int.from_bytes(z[32:40], "little")
        cd_size = int.from_bytes(z[40:48], "little")
        cd_off = int.from_bytes(z[48:56], "little")
    cd = http_range(url, cd_off, cd_off + cd_size - 1, os.path.join(tmpdir, "cd.bin"), proxy)
    out, p = [], 0
    while p + 46 <= len(cd) and cd[p: p + 4] == b"PK\x01\x02":
        method = int.from_bytes(cd[p + 10: p + 12], "little")
        csize = int.from_bytes(cd[p + 20: p + 24], "little")
        nlen = int.from_bytes(cd[p + 28: p + 30], "little")
        elen = int.from_bytes(cd[p + 30: p + 32], "little")
        clen = int.from_bytes(cd[p + 32: p + 34], "little")
        lho = int.from_bytes(cd[p + 42: p + 46], "little")
        name = cd[p + 46: p + 46 + nlen].decode("utf-8", "replace")
        out.append({"name": name, "method": method, "csize": csize, "lho": lho})
        p += 46 + nlen + elen + clen
    return out


def fetch_entry(url, entry, proxy, tmpdir):
    lh = http_range(url, entry["lho"], entry["lho"] + 29, os.path.join(tmpdir, "lh.bin"), proxy)
    nlen = int.from_bytes(lh[26:28], "little")
    elen = int.from_bytes(lh[28:30], "little")
    data_off = entry["lho"] + 30 + nlen + elen
    raw = http_range(url, data_off, data_off + entry["csize"] - 1, os.path.join(tmpdir, "entry.bin"), proxy)
    return raw if entry["method"] == 0 else zlib.decompress(raw, -15)


def report_jar(jar_bytes, tag):
    zf = zipfile.ZipFile(io.BytesIO(jar_bytes))
    declarations = []
    for name in zf.namelist():
        if not name.endswith(".xml"):
            continue
        try:
            text = zf.read(name).decode("utf-8", "ignore")
        except Exception:
            continue
        for m in re.finditer(r"<extensionPoint[^>]*" + re.escape(EP) + r'"[^>]*>', text, re.S):
            # 只看**这一条声明**有没有 dynamic="true"——同文件里兄弟 EP（如
            # com.intellij.sql.dialectCodeStyleProvider）带 dynamic 不能算到它头上（首版就踩了这个坑）
            decl = " ".join(m.group(0).split())
            declarations.append((name, decl, 'dynamic="true"' in decl))
            print(f"  [{name}] {decl[:300]}")
    if not declarations:
        print(f"  !! {EP} 的声明没找到（jar 内 {len(zf.namelist())} 个条目）")
        return None
    dynamic_count = sum(1 for _, _, d in declarations if d)
    all_dynamic = dynamic_count == len(declarations)
    print(
        f"  => {tag}: 声明 {len(declarations)} 处，其中 dynamic={dynamic_count} 处 → "
        + ("dynamic=true（可热卸载）" if all_dynamic else "存在非动态声明（不可热卸载，更新只能重启）")
    )
    return all_dynamic


def probe_remote(tag, url, proxy):
    print(f"=== {tag}  {url}")
    with tempfile.TemporaryDirectory() as tmpdir:
        entries = central_directory(url, proxy, tmpdir)
        cand = [e for e in entries if e["name"] == DB_PLUGIN_ENTRY]
        if not cand:
            print(f"  !! 未找到 {DB_PLUGIN_ENTRY}")
            return None
        jar = fetch_entry(url, cand[0], proxy, tmpdir)
        print(f"  jar={len(jar)} bytes（共 {len(entries)} 个条目，未下载整包）")
        return report_jar(jar, tag)


def probe_local(home):
    print(f"=== 本地解包 {home}")
    jar_path = os.path.join(home, LOCAL_DB_PLUGIN)
    if not os.path.isfile(jar_path):
        print(f"  !! 不存在 {jar_path}")
        return None
    with open(jar_path, "rb") as f:
        return report_jar(f.read(), "local")


def main():
    ap = argparse.ArgumentParser(description="取证 com.intellij.sql.dialect 在各平台是否 dynamic")
    ap.add_argument("--proxy", default="http://127.0.0.1:7890", help="代理地址，传空串关闭")
    ap.add_argument("--target", action="append", default=None, metavar="名=zip地址",
                    help="覆盖默认目标，可多次给")
    ap.add_argument("--local-home", default=None, help="本地已解包的 IDE 根目录（如 243 transformed 目录）")
    args = ap.parse_args()

    targets = DEFAULT_TARGETS
    if args.target:
        targets = []
        for t in args.target:
            name, _, url = t.partition("=")
            if not url:
                sys.exit(f"--target 需要 名=zip地址 形式：{t}")
            targets.append((name, url))

    results = {}
    if args.local_home:
        results["local"] = probe_local(args.local_home)
    for tag, url in targets:
        try:
            results[tag] = probe_remote(tag, url, args.proxy)
        except Exception as e:  # 单个目标失败不影响其余
            print(f"  !! {tag} 失败：{type(e).__name__}: {e}")
            results[tag] = None
    print()
    print("汇总：", {k: ("dynamic" if v else ("非 dynamic" if v is False else "未取到")) for k, v in results.items()})


if __name__ == "__main__":
    main()
