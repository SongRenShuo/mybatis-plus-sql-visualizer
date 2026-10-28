# -*- coding: utf-8 -*-
"""统一验收探针入口 —— 把「截图→看图→换算坐标→点击→再截图验证」固化成带自证的四个动作。

**为什么要有这个**（2026-10-18 错位根因核实的落地）：本轮实测的错位全部出在
「裁剪截图 + 人工原点换算」——原点忘加/拿错、行号数错一行、界面重渲染后旧坐标过期。
提权诊断（`ac_diag_dpi.py`）证明 DPI 不是本机错位来源（100% 缩放三套坐标系数值重合），
但进程初态 awareness=0 是机制隐患（缩放 >100% 时首次截图前的点击会被虚拟化放大）。
本模块在 import 时显式 `SetProcessDpiAwareness(2)`，把这个隐患钉死。

**用法（脚本里）**：

    import ac
    ac.ensure_ide_front()                              # 动作前先置前（焦点类动作必需）
    p = ac.shot("before")                              # 全屏截图落盘
    hit = ac.find_image("btn_ok", region=ac.IDE_DIALOG_REGION)   # 找点（不点）
    ac.click_image("btn_ok", region=...,               # 找到即点
                   claim_region=(x, y, w, h))          # 点击后像素变化自证，落空即抛错
    ac.click_component("(//div[@accessiblename='...'])[1]")   # 组件树定位（需 robot-server）
    ac.key("ctrl", "shift", "a")                       # 快捷键
    ac.paste_text("some text")                         # 剪贴板粘贴（免疫中文 IME）

**三条铁律（写成机制，不靠自觉）**：
1. **找不到不猜坐标**——`click_image`/`click_component` 定位失败返回 False 或抛错，绝不退回估算；
2. **点击必须自证**——`claim_region` 给了就验像素变化（verify.Guard 同源），无变化=落空，
   抛 `ClickMissed` 并附「重截图+重新定位」的现场读数；
3. **同屏多实例必须圈范围**——整屏找「OK」这类通用按钮会命中别的窗口的同款
   （2026-10-18 实测：凭据弹窗的 OK 模板 0.99997 分数命中了 Data Sources 对话框的 OK），
   所以 `region=` 是这些函数的一等参数。

**CLI（手工探针）**：
    python ac.py shot <name> [l t w h]          # 截全屏或区域 → shots 目录
    python ac.py find <template> [l t w h]      # 找锚点，打印 (cx,cy,score,scale)
    python ac.py click <template> [l t w h]     # 找到即点（danger：真实点击）
    python ac.py probe_robot                    # robot-server 探活
"""
import ctypes
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

# ---- 进程级 DPI 感知：import 即设置（幂等；失败=E_ACCESSDENIED 已设过，同样安全）----
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    pass

import pyautogui  # noqa: E402
from PIL import ImageGrab  # noqa: E402

import anchor  # noqa: E402

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

DEFAULT_SHOT_DIR = os.path.join(HERE, "tmp")


class AcError(RuntimeError):
    pass


class ClickMissed(AcError):
    """点击已发出但画面无变化——坐标偏 / 被模态吞 / 控件置灰，三因同症。"""


# ---------------------------------------------------------------- 截图

def shot(name, box=None, out_dir=None):
    """全屏（或 box=(l,t,w,h) 区域）截图落盘，返回路径。物理像素坐标。"""
    d = out_dir or DEFAULT_SHOT_DIR
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, name if name.endswith(".png") else name + ".png")
    img = ImageGrab.grab()
    if box:
        l, t, w, h = box
        img = img.crop((l, t, l + w, t + h))
    img.save(p)
    return p


def screenshot_bytes():
    """当前全屏 PIL Image（内存），供连续判定用。"""
    return ImageGrab.grab()


# ---------------------------------------------------------------- 锚点（图像定位）

def find_image(name, region=None, min_score=0.85, **kw):
    """找模板 `name`（templates/<name>.png），返回 (cx,cy,score,scale) 或 None。"""
    return anchor.find(name, region=region, min_score=min_score, **kw)


def capture(name, box):
    """把屏幕 box=(l,t,w,h) 截成模板（标定一次，之后免疫漂移）。拒绝纯色区域。"""
    return anchor.save_template(name, box)


def click_image(name, region=None, min_score=0.85, claim_region=None,
                claim_wait=0.5, claim_thr=6.0, button="left", clicks=1,
                move_first=True, dry_run=False, shot_prefix=None,
                out_dir=None, offset=(0, 0), **kw):
    """锚点定位 → 点击 → 像素变化自证。

    - 定位失败：返回 None（**不猜坐标**）；
    - `offset=(dx,dy)`：点击点相对**模板中心**的偏移。带上下文的模板（为消歧加了
      周边内容的）目标不在模板中心——**必须给 offset**，否则点在目标旁边的缝隙里
      （2026-10-18 实测：OK+Cancel 上下文模板直接点中心会落在两钮之间）；
    - `dry_run=True`：只 moveTo 悬停并截图，不点击（模态对话框在场时的安全形态）；
    - `region`：搜索域，**必须容得下模板的完整范围**（模板盒越过窗口边缘时按模板
      宽高外扩，否则模板在真实位置放不进 region，匹配必然落空）；
    - `claim_region`：点击后对该区域验像素变化；无变化抛 `ClickMissed`，
      并附「模板此刻在屏上哪里」的重定位读数（诊断是坐标错还是控件动了）；
    - `shot_prefix`：给每次动作的前后截图命名（落 out_dir，默认 record/tmp）。

    返回命中 (cx,cy,score,scale)（已含 offset）。
    """
    hit = find_image(name, region=region, min_score=min_score, **kw)
    if not hit:
        return None
    cx, cy, score, sc = hit
    cx, cy = cx + offset[0], cy + offset[1]
    if shot_prefix:
        shot(shot_prefix + "-before", out_dir=out_dir)
    if dry_run:
        pyautogui.moveTo(cx, cy, duration=0.3)
        time.sleep(0.5)
        if shot_prefix:
            shot(shot_prefix + "-hover", out_dir=out_dir)
        print("[dry] %s -> (%d,%d) score=%.4f" % (name, cx, cy, score))
        return hit
    if move_first:
        pyautogui.moveTo(cx, cy, duration=0.25)
    before = _fingerprint(claim_region) if claim_region else None
    pyautogui.click(cx, cy, button=button, clicks=clicks, interval=0.08)
    if claim_region is not None:
        time.sleep(claim_wait)
        after = _fingerprint(claim_region)
        from verify import diff_score
        dscore = diff_score(before, after)
        if dscore <= claim_thr:
            where = find_image(name, region=region, min_score=min_score, **kw)
            raise ClickMissed(
                "点击 %s @( %d,%d ) 后画面无变化（差异 %.1f <= %.1f）。"
                "模板此刻重定位：%s。常见成因：坐标命中非交互区 / 模态对话框吞点击 / 控件被置灰。"
                % (name, cx, cy, dscore, claim_thr,
                   ("(%d,%d) score=%.4f" % where[:2 + 1] if where else "已不在屏上")))
    if shot_prefix:
        time.sleep(0.3)
        shot(shot_prefix + "-after", out_dir=out_dir)
    return hit


# ---------------------------------------------------------------- 组件树（robot-server）

def robot_available(base=None, timeout=3):
    """robot-server（IDE 内，默认 127.0.0.1:8580）是否在线。"""
    import urllib.request
    try:
        import json
        import urllib.error
        b = base or os.environ.get("ROBOT_SERVER", "http://127.0.0.1:8580")
        req = urllib.request.Request(b.rstrip("/") + "/xpath/components",
                                     data=json.dumps({"xpath": "//div"}).encode(),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode()).get("status") == "SUCCESS"
    except Exception:
        return False


def click_component(xpath, claim_region=None, claim_wait=0.5, claim_thr=6.0, **kw):
    """组件树定位点击（robot.py 同款，叠加 claim 自证）。

    robot-server 不在线时抛 AcError（**不静默降级到猜坐标**）——
    该沙盒要用组件树，须以 `-PgifRecord=true -PgifRobotServer=true` 重启 runIde。
    """
    if not robot_available():
        raise AcError("robot-server 不在线（127.0.0.1:8580 探活失败）。"
                      "本沙盒未带 -PgifRobotServer=true；组件树定位不可用，"
                      "请改用 click_image（图像锚点），或按 robot.py docstring 重启沙盒。")
    import robot
    comp = robot.find_one(xpath)
    if not comp:
        return None
    x, y = robot.component_screen_center(xpath, comp=comp)
    before = _fingerprint(claim_region) if claim_region else None
    if kw.get("move_first", True):
        pyautogui.moveTo(x, y, duration=0.25)
    pyautogui.click(x, y, button=kw.get("button", "left"))
    if claim_region is not None:
        time.sleep(claim_wait)
        from verify import diff_score
        dscore = diff_score(before, _fingerprint(claim_region))
        if dscore <= claim_thr:
            raise ClickMissed("点击组件 %s @( %d,%d ) 后画面无变化（差异 %.1f）" % (xpath, x, y, dscore))
    return x, y


# ---------------------------------------------------------------- 键盘 / 剪贴板

def key(*combo):
    pyautogui.hotkey(*combo)


def paste_text(text):
    """剪贴板粘贴——一切文本输入的默认通道（本机中文 IME 会吃 typewrite）。"""
    import subprocess
    subprocess.run(["powershell", "-NoProfile", "-Command",
                    "Set-Clipboard -Value '" + text.replace("'", "''") + "'"], check=True)
    time.sleep(0.2)
    pyautogui.hotkey("ctrl", "v")


# ---------------------------------------------------------------- 置前 / 窗口

def sandbox_pid(sandbox="MPSV-MasterMerge-UI243"):
    """当前主控沙盒 IDE 的 PID（读沙盒 config/.lock）。

    **glob IU-*，不硬编码版本号**：locate.sandbox_pid 把版本目录写死成 IU-2026.2.1，
    对 243 沙盒（IU-2024.3.7.1）恒返回 None（2026-10-18 实测踩到）。
    **同时查主仓与 dev 工作树的 build**：当前主控的沙盒起在 `-dev` worktree，
    只查本仓 build 会扑空。
    """
    import glob
    roots = [os.path.join(HERE, os.pardir, "build"),
             os.path.join(HERE, os.pardir, os.pardir,
                          "mybatis-plus-sql-visualizer-dev", "build")]
    for root in roots:
        base = os.path.join(root, sandbox, "mybatis-plus-sql-visualizer")
        for lock in glob.glob(os.path.join(glob.escape(base), "IU-*", "config", ".lock")):
            try:
                with open(lock) as f:
                    txt = f.read().strip()
            except OSError:
                continue
            if txt.isdigit():
                return int(txt)
    return None


def ensure_ide_front(sandbox="MPSV-MasterMerge-UI243"):
    """把沙盒 IDE 主窗口弄到前台（焦点类动作前置步骤）。失败抛 AcError。"""
    import locate
    pid = sandbox_pid(sandbox)
    if not pid:
        raise AcError("沙盒 %s 的 .lock 没读到 PID——沙盒在跑吗？" % sandbox)
    rect = locate.window_rect(pid=pid, pick="largest", min_size=(800, 600))
    if not rect:
        raise AcError("按 PID=%d 没找到 IDE 主窗口" % pid)
    hwnd = None
    for h, p, title, r in locate.windows():
        if p == pid and r == rect:
            hwnd = h
            break
    if not hwnd or not locate.ensure_foreground(hwnd):
        raise AcError("IDE 置前失败（Windows 前台锁）——先人工把浏览器最小化再试")
    return pid


# ---------------------------------------------------------------- 指纹（claim 内件）

def _fingerprint(region, grid=8):
    """与 verify.fingerprint 同源，但输入为 PIL ImageGrab（不经 mss），附截图坐标一致性。"""
    import numpy as np
    l, t, w, h = region
    a = np.asarray(ImageGrab.grab().crop((l, t, l + w, t + h)).convert("L"), dtype=float)
    ys = np.linspace(0, h, grid + 1).astype(int)
    xs = np.linspace(0, w, grid + 1).astype(int)
    blocks = [a[ys[i]:ys[i + 1], xs[j]:xs[j + 1]].mean() for i in range(grid) for j in range(grid)]
    return {"mean": float(a.mean()), "std": float(a.std()), "blocks": np.array(blocks)}


def claim(region, base=None, wait=0.5, thr=6.0):
    """点击后像素级变化自证：给 `base`（claim 前先记的指纹）则对比之，否则现取基准只返回指纹。

    典型序列：
        base = ac.claim(region)          # 记基准
        ...点击...
        changed, score = ac.claim(region, base=base)
    """
    if base is None:
        return _fingerprint(region)
    time.sleep(wait)
    from verify import diff_score
    cur = _fingerprint(region)
    return diff_score(base, cur) > thr, diff_score(base, cur)


# ---------------------------------------------------------------- CLI

def _cli(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd = argv[1]
    region, rest = None, []
    if len(argv) >= 7:
        try:
            region = tuple(int(x) for x in argv[3:7])
            rest = argv[2:3]
        except ValueError:
            rest = argv[2:]
    else:
        rest = argv[2:]
    try:
        if cmd == "shot" and rest:
            print("已存", shot(rest[0], box=region))
            return 0
        if cmd == "find" and rest:
            hit = find_image(rest[0], region=region)
            print(hit if hit else "未找到（不猜坐标）")
            return 0 if hit else 1
        if cmd == "click" and rest:
            hit = click_image(rest[0], region=region)
            print("已点击" if hit else "未找到（未点击）")
            return 0 if hit else 1
        if cmd == "probe_robot":
            print("robot-server:", "在线" if robot_available() else "不在线")
            return 0
    except (AcError, ClickMissed) as e:
        print("错误:", e)
        return 1
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(_cli(sys.argv))
