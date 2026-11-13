#!/usr/bin/env python3
"""
检查 buildPlugin 产出的插件 zip 是否与发行信息一致（发布前置检查，可复用）

用法:
  # 基本核查：内层 jar 名 / plugin.xml <version> / MANIFEST Version / change-notes 目标版本块
  python dev-support/inspect-plugin-zip.py --version 2026.10.9

  # 额外：反汇编内层 jar 里的某个类，断言符号在场/不在场（证明某修复真的进了产物）
  python dev-support/inspect-plugin-zip.py --version 2026.10.9 \
      --disasm cn.song.mybatisplus.visualizer.log.RecentExecutionsDialog \
      --require-present menuActions --require-absent getChildren

  # 指定 zip 路径（缺省 build/distributions/mybatis-plus-sql-visualizer-{version}.zip）
  python dev-support/inspect-plugin-zip.py --zip build/distributions/xxx.zip

检查项（任一不过即退出码 1）:
  1. 内层主 jar 名带版本号（lib/mybatis-plus-sql-visualizer-{version}.jar）
  2. META-INF/plugin.xml 的 <version> == --version
  3. META-INF/MANIFEST.MF 的 Version == --version
  4. <change-notes> 存在 v{version} 块（首个 <h2> 即该版本），块内条目数不为 0
  5. change-notes 该版本块逐条扫「（ ） ：」零命中（changeNotes 红线：只主标题、无括号冒号副题）
  6. （可选）--disasm 类的反汇编里 --require-present / --require-absent 全部满足

javap 定位顺序：--javap > PATH 上的 javap > ~/.jdks/*/bin/javap（取版本号最大的，
JDK8 的 javap 读不了高版本 class 文件，故不优先用 PATH 上的默认 java）。
"""
import argparse
import glob
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def find_javap(explicit):
    if explicit:
        return explicit
    cands = glob.glob(os.path.join(os.path.expanduser("~"), ".jdks", "*", "bin", "javap.exe"))
    cands += glob.glob(os.path.join(os.path.expanduser("~"), ".jdks", "*", "bin", "javap"))
    if cands:
        return sorted(cands)[-1]
    which = shutil.which("javap")
    if which:
        return which
    return None


def main():
    ap = argparse.ArgumentParser(description="检查插件 zip 与发行信息是否一致")
    ap.add_argument("--version", help="目标版本号（缺省从 zip 文件名推断）")
    ap.add_argument("--zip", help="zip 路径（缺省 build/distributions/mybatis-plus-sql-visualizer-{version}.zip）")
    ap.add_argument("--disasm", help="反汇编内层 jar 中的类（全限定类名）")
    ap.add_argument("--require-present", action="append", default=[], help="反汇编里必须出现的符号（可多次）")
    ap.add_argument("--require-absent", action="append", default=[], help="反汇编里必须不出现的符号（可多次）")
    ap.add_argument("--javap", help="javap 可执行文件路径")
    args = ap.parse_args()

    version = args.version
    zip_path = args.zip
    if not zip_path:
        if not version:
            print("ERROR: 给 --version 或 --zip 之一", file=sys.stderr)
            return 2
        zip_path = os.path.join(REPO_ROOT, "build", "distributions",
                                f"mybatis-plus-sql-visualizer-{version}.zip")
    if not os.path.isabs(zip_path):
        zip_path = os.path.join(REPO_ROOT, zip_path)
    if not version:
        m = re.search(r"mybatis-plus-sql-visualizer-(.+)\.zip$", os.path.basename(zip_path))
        if not m:
            print("ERROR: 无法从 zip 名推断版本号，请显式给 --version", file=sys.stderr)
            return 2
        version = m.group(1)

    if not os.path.exists(zip_path):
        print(f"ERROR: zip 不存在: {zip_path}\n  先跑 ./gradlew buildPlugin", file=sys.stderr)
        return 2

    failures = []

    def check(label, ok, detail=""):
        print(f"  [{'OK ' if ok else 'FAIL'}] {label}" + (f" — {detail}" if detail else ""))
        if not ok:
            failures.append(label)

    print(f"zip     : {zip_path}")
    print(f"version : {version}")
    z = zipfile.ZipFile(zip_path)

    jar_suffix = f"lib/mybatis-plus-sql-visualizer-{version}.jar"
    jar_hits = [n for n in z.namelist() if n.endswith(jar_suffix)]
    check(f"内层主 jar 名 = {jar_suffix}", bool(jar_hits), jar_hits[0] if jar_hits else "未找到")
    if not jar_hits:
        print("\n结果: FAIL")
        return 1

    jz = zipfile.ZipFile(io.BytesIO(z.read(jar_hits[0])))

    px = jz.read("META-INF/plugin.xml").decode("utf-8")
    m = re.search(r"<version>([^<]*)</version>", px)
    check(f"plugin.xml <version> == {version}", bool(m) and m.group(1) == version,
          m.group(1) if m else "未找到")

    mf = jz.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
    mv = re.search(r"^Version:\s*(.+)$", mf, re.M)
    check(f"MANIFEST Version == {version}", bool(mv) and mv.group(1).strip() == version,
          mv.group(1).strip() if mv else "未找到")
    mb = re.search(r"^MPV-Build:\s*(.+)$", mf, re.M)
    if mb:
        print(f"         MPV-Build = {mb.group(1).strip()}")

    cn = re.search(r"<change-notes>(.*?)</change-notes>", px, re.S)
    if not cn:
        check("change-notes 存在", False)
    else:
        block = cn.group(1)
        h2s = re.findall(r"<h2>([^<]*)</h2>", block)
        check(f"change-notes 首个 <h2> == v{version}", bool(h2s) and h2s[0] == f"v{version}",
              " / ".join(h2s[:3]))
        tb = re.search(r"<h2>v%s</h2>\s*<ul>(.*?)</ul>" % re.escape(version), block, re.S)
        if not tb:
            check(f"change-notes 有 v{version} 块", False)
        else:
            lis = [re.sub(r"\s+", " ", x).strip() for x in re.findall(r"<li>(.*?)</li>", tb.group(1), re.S)]
            check(f"v{version} 块条目数 > 0", len(lis) > 0, f"{len(lis)} 条")
            for i, li in enumerate(lis, 1):
                print(f"         {i}. {li}")
            bad = [li for li in lis if ("（" in li or "）" in li or "：" in li)]
            check("v%s 块扫『（ ） ：』零命中" % version, not bad, "; ".join(bad[:3]))

    if args.disasm:
        javap = find_javap(args.javap)
        if not javap:
            check("javap 可用", False, "未找到 javap，用 --javap 指定")
        else:
            cls_file = args.disasm.replace(".", "/") + ".class"
            try:
                cls_bytes = jz.read(cls_file)
            except KeyError:
                check(f"内层 jar 含类 {args.disasm}", False)
                cls_bytes = None
            if cls_bytes is not None:
                tmp = tempfile.mkdtemp(prefix="mpsv-disasm-")
                dst = os.path.join(tmp, *args.disasm.split("."))
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                with open(dst + ".class", "wb") as f:
                    f.write(cls_bytes)
                out = subprocess.run([javap, "-p", "-c", "-cp", tmp, args.disasm],
                                     capture_output=True, text=True, errors="replace").stdout
                for sym in args.require_present:
                    check(f"反汇编含符号 {sym}", sym in out)
                for sym in args.require_absent:
                    check(f"反汇编不含符号 {sym}", sym not in out)

    ok = not failures
    print(f"\n结果: {'PASS' if ok else 'FAIL'}" + ("" if ok else f"（{len(failures)} 项未过）"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
