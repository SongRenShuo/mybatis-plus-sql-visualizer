# -*- coding: utf-8 -*-
"""录制开工前环境就绪探测（preflight）。

动机（20261027 复盘）：批 4 两小时零产出，6 个废 take 里 4 个是「环境状态错」——
方言来源、索引期、前台、数据源记忆、工具窗。这些都能在**开录前 1 分钟**探测掉，
不该用 take 当探针。

用法：
  python preflight.py              # 探测全部，打印报告，任一 FAIL 退出码 1
  python preflight.py --fix        # 能自动修的项顺手修（窗口归位/夺前台/关弹窗）
  python preflight.py --demo       # 额外检查 demo 应用 8081（L2 类图需要）

设计：每项独立 try，互不阻塞；输出用 ASCII 标记（GBK 控制台不炸）。
"""
import ctypes
import ctypes.wintypes as wt
import json
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

REGION = (285, 26, 1354, 974)
WIN = (277, 25, 1370, 984)
EXPECT_RECT = (277, 25, 1647, 1009)
DEMO = "F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-demo"
SANDBOX_XML = ("F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer/build/"
               "MPSV-GifFresh/mybatis-plus-sql-visualizer/IU-2026.2.1/config/options/"
               "mp-sql-visualizer.xml")

FIX = "--fix" in sys.argv
NEED_DEMO = "--demo" in sys.argv
# --expect-dialect <名>：本次录制期望的渲染方言（默认 MySQL）。
# dialect-gate 那张需要 PostgreSQL 才能构造冲突场景，判据必须跟着变。
EXPECT_DIALECT = "MySQL"
if "--expect-dialect" in sys.argv:
    EXPECT_DIALECT = sys.argv[sys.argv.index("--expect-dialect") + 1]
results = []


def report(name, ok, detail, fixable=False):
    results.append((name, ok, detail, fixable))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}", flush=True)
    return ok


def u32():
    u = ctypes.windll.user32
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        u.SetProcessDPIAware()
    return u


def ide_hwnd(u):
    hit = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def cb(h, _):
        if u.IsWindowVisible(h):
            n = u.GetWindowTextLengthW(h)
            if n > 0:
                buf = ctypes.create_unicode_buffer(n + 1)
                u.GetWindowTextW(h, buf, n + 1)
                if "mybatis-plus-sql-visualizer-demo" in buf.value:
                    hit.append(h)
        return True
    u.EnumWindows(cb, None)
    return hit[-1] if hit else None


# ---------------- P1 窗口矩形 ----------------
def p1(u, hwnd):
    r = wt.RECT()
    u.GetWindowRect(ctypes.c_void_p(hwnd), ctypes.byref(r))
    got = (r.left, r.top, r.right, r.bottom)
    if got == EXPECT_RECT:
        return report("P1 window rect", True, str(got))
    if FIX:
        u.MoveWindow(ctypes.c_void_p(hwnd), *WIN, True)
        time.sleep(1.2)
        u.GetWindowRect(ctypes.c_void_p(hwnd), ctypes.byref(r))
        got = (r.left, r.top, r.right, r.bottom)
        return report("P1 window rect", got == EXPECT_RECT,
                      f"{got} (fixed)" if got == EXPECT_RECT else f"{got} != {EXPECT_RECT}")
    return report("P1 window rect", False, f"{got} != {EXPECT_RECT} (run --fix)", True)


# ---------------- P2 索引空闲 ----------------
def p2():
    """robot 组件树查 Analyzing（像素法三次校准都失败，见 20261027 复盘）。"""
    from robot import find_components
    for _ in range(40):                     # 最多等 240s
        try:
            hits = find_components(
                "//div[contains(@accessiblename,'Analyzing') or contains(@accessiblename,'索引')]")
        except Exception as e:
            return report("P2 indexing idle", True, f"robot 不可达，跳过（{str(e)[:40]}）")
        if not hits:
            return report("P2 indexing idle", True, "no Analyzing")
        time.sleep(6)
    return report("P2 indexing idle", False, "still Analyzing after 240s")


# ---------------- P3 前台是沙盒 ----------------
def p3(u, hwnd):
    if u.GetForegroundWindow() == hwnd:
        return report("P3 foreground", True, "sandbox has focus")
    if FIX:
        u.keybd_event(0x12, 0, 0, 0)
        u.keybd_event(0x12, 0, 2, 0)
        u.SetForegroundWindow(ctypes.c_void_p(hwnd))
        time.sleep(1.0)
        import pyautogui
        pyautogui.press("esc")
        time.sleep(0.3)
        ok = u.GetForegroundWindow() == hwnd
        return report("P3 foreground", ok, "sandbox has focus (fixed)" if ok else "still not foreground")
    return report("P3 foreground", False, "sandbox not foreground (run --fix)", True)


# ---------------- P4 渲染方言三处来源 ----------------
def p4():
    """方言三处来源逐一核（20261027 根因：第三处 sqldialects.xml 最易漏）。"""
    issues = []

    sqld = os.path.join(DEMO, ".idea/sqldialects.xml")
    if os.path.exists(sqld):
        txt = open(sqld, encoding="utf-8").read()
        import re as _re
        m = _re.search(r'url="PROJECT" dialect="([^"]*)"', txt)
        if m and m.group(1) != EXPECT_DIALECT:
            issues.append(f"sqldialects.xml PROJECT={m.group(1)} (expect {EXPECT_DIALECT})")

    proj = os.path.join(DEMO, ".idea/sql-visualizer/mp-sql-visualizer-settings.json")
    if os.path.exists(proj):
        try:
            d = json.load(open(proj, encoding="utf-8"))
            if d.get("dialect") and d.get("dialect") != "MySQL":
                issues.append(f"project json dialect={d.get('dialect')}")
        except Exception:
            pass

    if os.path.exists(SANDBOX_XML):
        txt = open(SANDBOX_XML, encoding="utf-8").read()
        import re
        m = re.search(r'name="dialect" value="([^"]*)"', txt)
        auto = re.search(r'name="dialectAuto" value="([^"]*)"', txt)
        if m and m.group(1) and m.group(1) != EXPECT_DIALECT:
            issues.append(f"global dialect={m.group(1)} (expect {EXPECT_DIALECT})")
        if auto and auto.group(1) == "false" and not (m and m.group(1)):
            issues.append("global dialectAuto=false but dialect empty")

    if issues:
        return report("P4 render dialect", False, "; ".join(issues))
    return report("P4 render dialect", True, f"all sources say {EXPECT_DIALECT}")


# ---------------- P5 数据源记忆 ----------------
def p5():
    ws = os.path.join(DEMO, ".idea/workspace.xml")
    if not os.path.exists(ws):
        return report("P5 last-used datasource", True, "workspace.xml absent")
    txt = open(ws, encoding="utf-8", errors="ignore").read()
    import re
    m = re.search(r'mpsql\.visualizer\.last\.runner&quot;: &quot;([^&]*)', txt)
    if not m:
        return report("P5 last-used datasource", True, "no memory entry")
    name = m.group(1)
    ok = "mpj_demo" in name or "mp_demo" in name
    return report("P5 last-used datasource", ok,
                  f"{name}" + ("" if ok else " (expected a MySQL demo source)"))


# ---------------- P6 无残留弹窗 ----------------
def p6():
    from robot import find_components
    dialogs = []
    for title in ("分享 SQL", "Connecting to", "Settings", "选择要预览的 SQL"):
        try:
            if find_components(f"//div[@accessiblename='{title}']"):
                dialogs.append(title)
        except Exception:
            pass
    if dialogs:
        return report("P6 no stray dialogs", False, ", ".join(dialogs) + " open")
    return report("P6 no stray dialogs", True, "none")


# ---------------- P8 demo 应用（可选） ----------------
def p8():
    try:
        with urllib.request.urlopen("http://127.0.0.1:8081/user/list", timeout=5) as r:
            ok = r.status == 200
        return report("P8 demo app :8081", ok, f"HTTP {r.status}")
    except Exception as e:
        return report("P8 demo app :8081", False, f"unreachable ({str(e)[:40]})")


def main():
    print("=== preflight (T-DemoGifFix1026) ===", flush=True)
    u = u32()
    hwnd = ide_hwnd(u)
    if not hwnd:
        report("P0 sandbox window", False, "sandbox IDE not found (runIde not running?)")
        return summarize()
    report("P0 sandbox window", True, f"hwnd={hwnd}")

    p1(u, hwnd)
    p2()
    p3(u, hwnd)
    p4()
    p5()
    p6()
    if NEED_DEMO:
        p8()
    return summarize()


def summarize():
    fails = [r for r in results if not r[1]]
    print("\n=== summary ===", flush=True)
    if not fails:
        print("ALL PASS -- safe to record", flush=True)
        return 0
    print(f"{len(fails)} FAIL:", flush=True)
    for name, _, detail, fixable in fails:
        print(f"  - {name}: {detail}" + ("  [--fix can help]" if fixable else ""), flush=True)
    return 1


if __name__ == "__main__":
    sys.exit(main())
