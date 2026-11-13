# -*- coding: utf-8 -*-
"""反向验证自动化：逆向补丁摘除修复实现 -> 只跑锚定测试类 -> 断言红。

用法（Git Bash / 任意 shell）:
  python dev-support/reverse_probe.py --commit <生产提交哈希> --tests <FQCN>[,<FQCN>...] \
      [--base <基线分支>] [--repo <主仓路径>]

流程:
  1. 临时 worktree 检出基线分支（默认 t-sqllist-catchup）
  2. 对该提交触达的 src/main/ 生产文件逆向打补丁（测试文件不动——锚定测试必须原样在场）
  3. 增量编译 + 只跑 --tests 指定的类（in-process）
  4. 解析 JUnit XML：失败数 > 0 => 反证通过（exit 0）；全绿 => 反证失败（exit 1）
  5. 清理临时 worktree

退出码: 0=反证通过(有红)  1=反证失败(跑成但全绿)  2=补丁冲突/环境错误  3=构建失败不可判
注意: 跑前须按仓库并行纪律 register gradle 沙盒；冷 worktree 编译约 3~5 分钟。
"""
import argparse, glob, os, shutil, subprocess, sys, xml.etree.ElementTree as ET

# 中文 Windows 控制台是 GBK：gradle 输出按 utf-8 解出的替换符不能再打回 GBK
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

def sh(args, cwd=None, capture=True):
    r = subprocess.run(args, cwd=cwd, capture_output=capture, text=True,
                       encoding="utf-8", errors="replace")
    if r.returncode != 0 and capture:
        sys.stderr.write(r.stdout[-3000:] + "\n" + r.stderr[-3000:] + "\n")
    return r

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--commit", help="生产提交哈希（整提交反打模式）；--patch 模式下可省")
    ap.add_argument("--tests", required=True, help="逗号分隔 FQCN")
    ap.add_argument("--base", default="t-sqllist-catchup")
    ap.add_argument("--patch", help="手术式反证补丁文件（正向 diff，仅改机制判定；优先于 --commit 反打）")
    ap.add_argument("--repo", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument("--keep", action="store_true", help="保留临时 worktree 供排查")
    a = ap.parse_args()

    repo = os.path.abspath(a.repo)
    probe_id = a.commit[:7] if a.commit else os.path.splitext(os.path.basename(a.patch or ""))[0][:16]
    wt = os.path.join(repo, ".worktrees", "rp-" + probe_id)
    wt_name = wt.replace("\\", "/")  # git 要正斜杠；基线分支可能已被其他工作树检出，用 --detach

    # 0. 前置校验：模式分派——整提交反打（--commit）或手术式补丁（--patch）
    if a.patch:
        if not os.path.isfile(a.patch):
            print("[rp] 补丁文件不存在: " + a.patch); return 2
        print("[rp] 模式=手术式补丁: " + a.patch)
    else:
        r = sh(["git", "-C", repo, "show", "--name-only", "--format=", a.commit])
        if r.returncode != 0:
            print("[rp] 提交不存在: " + a.commit); return 2
        files = r.stdout.split()
        prod = [f for f in files if f.startswith("src/main/")]
        if not prod:
            print("[rp] 该提交没有 src/main/ 生产文件，反证无意义"); return 2
        print("[rp] 模式=整提交反打 (%d): %s" % (len(prod), ", ".join(prod)))

    # 1. 临时 worktree（--detach：基线分支可能已被其他工作树检出）
    sh(["git", "-C", repo, "worktree", "remove", "--force", wt_name])
    r = sh(["git", "-C", repo, "worktree", "add", "--detach", wt, a.base])
    if r.returncode != 0:
        print("[rp] worktree 创建失败"); return 2
    print("[rp] worktree: %s @ %s (detached)" % (wt_name, a.base))

    # 2. 摘除实现：整提交反打 或 手术式补丁（正向）
    if a.patch:
        r = sh(["git", "-C", wt, "apply", "--whitespace=nowarn", os.path.abspath(a.patch)])
        if r.returncode != 0:
            print("[rp] 手术式补丁应用失败（基线已漂移？对照补丁上下文核对）:\n" + r.stderr[:2000])
            sh(["git", "-C", repo, "worktree", "remove", "--force", wt_name])
            return 2
        print("[rp] 手术式补丁应用成功（机制已禁用，测试缝原样在场）")
    else:
        rdiff = sh(["git", "-C", repo, "diff", a.commit + "^", a.commit, "--"] + prod)
        r = subprocess.run(["git", "-C", wt, "apply", "-R", "--whitespace=nowarn"],
                           input=rdiff.stdout, capture_output=True, text=True, encoding="utf-8")
        if r.returncode != 0:
            print("[rp] 逆向补丁冲突（后续提交改过同一区域，需人工定位反打锚点）:\n" + r.stderr[:2000])
            sh(["git", "-C", repo, "worktree", "remove", "--force", wt_name])
            return 2
        print("[rp] 逆向补丁应用成功（修复实现已摘除，测试原样在场）")

    # 3. 定向测试
    tests = []
    for x in a.tests.split(","):
        if x.strip():
            tests += ["--tests", x.strip()]
    gradlew = "gradlew.bat" if os.name == "nt" else "./gradlew"
    cmd = ([gradlew, "test"] + tests +
           ["-Pkotlin.compiler.execution.strategy=in-process", "--console=plain"])
    print("[rp] 运行: " + " ".join(cmd))
    r = subprocess.run(cmd, cwd=wt, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    out = r.stdout or ""
    tail = out.strip().splitlines()[-4:]
    print("[rp] gradle 尾部:\n  " + "\n  ".join(tail))
    xmls = glob.glob(os.path.join(wt, "build", "test-results", "test", "TEST-*.xml"))

    # 4. 判读：先分「没跑成」与「跑成了」——反打连带摘掉测试缝会编译失败，必须如实报不可判
    if r.returncode != 0 and not xmls:
        err_lines = [l for l in out.splitlines()
                     if ("e: " in l or "error:" in l or "FAILED" in l)][:12]
        print("[rp] ===== 反证不可判（构建失败，测试未执行）=====")
        for l in err_lines:
            print("  " + l.strip()[:200])
        print("  常见成因：反打连带摘掉了锚定测试依赖的注入缝（生产文件里定义的 forTest 成员）。")
        print("  处置：改用注入缝口径反证（测试内短路新路径），或人工摘除机制分支保留缝后复跑。")
        if a.keep:
            print("[rp] worktree 保留: " + wt)
        else:
            sh(["git", "-C", repo, "worktree", "remove", "--force", wt_name])
            if os.path.isdir(wt):
                shutil.rmtree(wt, ignore_errors=True)
            sh(["git", "-C", repo, "worktree", "prune"])
        return 3

    total = fails = errs = 0
    for p in xmls:
        root = ET.parse(p).getroot()
        total += int(root.get("tests", 0)); fails += int(root.get("failures", 0))
        errs += int(root.get("errors", 0))
    verdict = (fails + errs) > 0
    print("[rp] ===== 反证判定 =====")
    print("  例数=%d 失败=%d 错误=%d" % (total, fails, errs))
    print("  结论: " + ("反证通过——摘除修复后锚定用例转红，机制确在起作用" if verdict
                      else "反证失败——摘除后仍全绿，锚定用例没咬住该机制！"))

    # 5. 清理
    if a.keep:
        print("[rp] worktree 保留: " + wt)
    else:
        sh(["git", "-C", repo, "worktree", "remove", "--force", wt_name])
        if os.path.isdir(wt):
            shutil.rmtree(wt, ignore_errors=True)
        sh(["git", "-C", repo, "worktree", "prune"])
        print("[rp] worktree 已清理")
    return 0 if verdict else 1

if __name__ == "__main__":
    sys.exit(main())
