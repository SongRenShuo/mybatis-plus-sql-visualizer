# -*- coding: utf-8 -*-
"""图像锚点定位 —— 把「算坐标」换成「匹配一张小模板图」。

**为什么要有这个**：`locate.py` 的三件套（窗口矩形 / 底部偏移 / 像素剖面）都是**几何推断**，
控件一换布局（弹窗高度随内容变、设置对话框被人工调过、悬停才渲染的标题栏动作）就失效。
图像锚点改成**按外观找**，天然免疫控件漂移、CJK 双宽估算错、主题局部变化。

**用法（三步）**：

    # 1. 标定：把目标控件那一小块截成模板（只做一次，或控件改版后重做）
    python anchor.py capture btn_exec_sql 1256 810 120 38

    # 2. 找点：返回屏幕坐标（默认在整个屏幕里找，也可限定到某个窗口矩形）
    python anchor.py find btn_exec_sql

    # 3. 在场景脚本里直接用
    from anchor import click_anchor
    click_anchor("btn_exec_sql")          # 找到即点，返回 True；没找到返回 False

**设计取舍**：
- 灰度 + `TM_CCOEFF_NORMED`：对 IDE 深色主题下的文字按钮足够稳，且比彩色快。
- **多尺度**：IDE 缩放（120%）或窗口尺寸变化会让模板与屏上像素不等比，单尺度会失配；
  默认扫 0.9~1.1，命中后**用命中尺度细化一次**（粗扫步长 0.05、细化 0.01）。
- **限定搜索区域**：优先传 `region=`（一般用 `locate.window_rect()` 取的目标窗口矩形），
  既快又能避免在别的窗口里误匹配到长得像的控件。
- 阈值默认 0.85：实测 IDE 内按钮类模板同尺度命中普遍 >0.95，跨主题/带焦点描边时约 0.88~0.93；
  取 0.85 能在「宁可找不到也不要点错」之间平衡——**找不到就返回 None，调用方必须处理**，
  绝不退回「猜一个坐标」（那正是本模块要消灭的行为）。
"""
import os
import sys

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
TEMPLATE_DIR = os.path.join(HERE, "templates")

sys.path.insert(0, os.path.join(HERE, "lib"))
sys.path.insert(0, HERE)


# ---------------------------------------------------------------- 抓屏

def grab(region=None):
    """抓屏并返回 BGR ndarray。`region=(left, top, width, height)`；缺省全屏。"""
    import mss
    if region is None:
        region = (0, 0, 0, 0)          # 下面用 monitors[1] 补全
    with mss.mss() as sct:
        if region == (0, 0, 0, 0):
            mon = sct.monitors[1]
            region = (mon["left"], mon["top"], mon["width"], mon["height"])
        left, top, w, h = region
        raw = np.asarray(sct.grab({"left": left, "top": top, "width": w, "height": h}))
    return cv2.cvtColor(raw[:, :, :3], cv2.COLOR_RGB2BGR)


# ---------------------------------------------------------------- 模板存取

def template_path(name):
    return os.path.join(TEMPLATE_DIR, name + ".png")


def save_template(name, region):
    """把屏幕 `region=(left,top,w,h)` 截下来存成模板。

    **拒绝低方差模板**：纯色/近纯色块做模板时 `TM_CCOEFF_NORMED` 会退化成「到处都 1.0」，
    导致在任意位置都能「命中」（实测踩过：截了一块空白区，负对照也报 1.0）。
    """
    os.makedirs(TEMPLATE_DIR, exist_ok=True)
    img = grab(region)
    sd = float(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).std())
    if sd < 8.0:
        raise ValueError("这块区域几乎是纯色（灰度标准差 %.1f）——换一块有文字/边框/图标的区域做模板，"
                         "否则匹配会退化到「到处都命中」" % sd)
    cv2.imwrite(template_path(name), img)
    print("模板已存: %s  (%dx%d, 灰度标准差 %.1f)" % (template_path(name), img.shape[1], img.shape[0], sd))
    return template_path(name)


def load_template(name):
    p = template_path(name)
    img = cv2.imread(p, cv2.IMREAD_GRAYSCALE)
    if img is None:
        raise FileNotFoundError("模板不存在: %s（先用 anchor.py capture 标定）" % p)
    return img


# ---------------------------------------------------------------- 匹配

def find(name, region=None, min_score=0.85, scales=None, gray_screen=True):
    """在 `region`（缺省全屏）里找模板 `name`。

    返回 `(cx, cy, score, scale)`（**屏幕**坐标）或 `None`。
    """
    tpl = load_template(name)
    screen = grab(region)
    if gray_screen:
        screen = cv2.cvtColor(screen, cv2.COLOR_BGR2GRAY)
    ox, oy = (region[0], region[1]) if region else (0, 0)

    if scales is None:
        scales = [0.90 + 0.05 * i for i in range(5)]      # 0.90 0.95 1.00 1.05 1.10

    best = None
    for sc in scales:
        if abs(sc - 1.0) < 1e-6:
            t = tpl
        else:
            t = cv2.resize(tpl, None, fx=sc, fy=sc, interpolation=cv2.INTER_AREA)
        if t.shape[0] > screen.shape[0] or t.shape[1] > screen.shape[1]:
            continue
        res = cv2.matchTemplate(screen, t, cv2.TM_CCOEFF_NORMED)
        _, mx, _, mloc = cv2.minMaxLoc(res)
        if best is None or mx > best[0]:
            best = (mx, mloc, sc, t.shape)

    if best is None or best[0] < min_score:
        return None

    # 命中尺度细化一次（步长 0.01），把中心点钉准
    mx, mloc, sc, tshape = best
    for fine in (sc - 0.02, sc - 0.01, sc + 0.01, sc + 0.02):
        if fine <= 0:
            continue
        t = cv2.resize(tpl, None, fx=fine, fy=fine, interpolation=cv2.INTER_AREA)
        if t.shape[0] > screen.shape[0] or t.shape[1] > screen.shape[1]:
            continue
        res = cv2.matchTemplate(screen, t, cv2.TM_CCOEFF_NORMED)
        _, fmx, _, floc = cv2.minMaxLoc(res)
        if fmx > mx:
            mx, mloc, sc, tshape = fmx, floc, fine, t.shape
    if mx < min_score:
        return None

    h, w = tshape[:2]
    cx = ox + mloc[0] + w // 2
    cy = oy + mloc[1] + h // 2
    return (int(cx), int(cy), float(mx), round(float(sc), 3))


def find_all(name, region=None, min_score=0.85, scales=None, max_hits=10):
    """找出所有超过阈值的命中（同一模板在屏上出现多次时用），返回 [(cx,cy,score,scale), ...]。"""
    tpl = load_template(name)
    screen = grab(region)
    screen = cv2.cvtColor(screen, cv2.COLOR_BGR2GRAY)
    ox, oy = (region[0], region[1]) if region else (0, 0)
    if scales is None:
        scales = [1.0]

    hits = []
    for sc in scales:
        t = tpl if abs(sc - 1.0) < 1e-6 else cv2.resize(tpl, None, fx=sc, fy=sc, interpolation=cv2.INTER_AREA)
        if t.shape[0] > screen.shape[0] or t.shape[1] > screen.shape[1]:
            continue
        res = cv2.matchTemplate(screen, t, cv2.TM_CCOEFF_NORMED)
        ys, xs = np.where(res >= min_score)
        h, w = t.shape[:2]
        for y, x in zip(ys, xs):
            hits.append((ox + int(x) + w // 2, oy + int(y) + h // 2, float(res[y, x]), sc))
    hits.sort(key=lambda t: -t[2])
    # 去重（相邻命中保留分数最高的）
    out = []
    for h_ in hits:
        if all((h_[0] - o[0]) ** 2 + (h_[1] - o[1]) ** 2 > 400 for o in out):
            out.append(h_)
        if len(out) >= max_hits:
            break
    return out


# ---------------------------------------------------------------- 点击

def click_anchor(name, region=None, min_score=0.85, button="left", move_first=True,
                 dry_run=False, **kw):
    """找到即点，返回 True；**找不到返回 False（不猜坐标）**。"""
    import pyautogui
    hit = find(name, region=region, min_score=min_score, **kw)
    if not hit:
        return False
    cx, cy, score, sc = hit
    if dry_run:
        print("[dry] %s -> (%d,%d) score=%.3f scale=%s" % (name, cx, cy, score, sc))
        return True
    if move_first:
        pyautogui.moveTo(cx, cy, duration=0.25)
    pyautogui.click(cx, cy, button=button)
    return True


def require_anchor(name, region=None, min_score=0.85, retries=3, interval=0.6, **kw):
    """开录前的闸门：找不到就抛错（与 verify.Guard.require 同性质）。"""
    import time
    for _ in range(max(1, retries)):
        hit = find(name, region=region, min_score=min_score, **kw)
        if hit:
            return hit
        time.sleep(interval)
    raise RuntimeError("锚点未找到: %s（阈值 %.2f）——控件改版了？先重做模板" % (name, min_score))


# ---------------------------------------------------------------- CLI

def _cli(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd = argv[1]
    if cmd == "capture" and len(argv) >= 7:
        name = argv[2]
        l, t, w, h = (int(x) for x in argv[3:7])
        save_template(name, (l, t, w, h))
        return 0
    if cmd == "find" and len(argv) >= 3:
        name = argv[2]
        region = None
        if len(argv) >= 7:
            region = tuple(int(x) for x in argv[3:7])
        hit = find(name, region=region)
        print(hit if hit else "未找到")
        return 0 if hit else 1
    if cmd == "list":
        if not os.path.isdir(TEMPLATE_DIR):
            print("（还没有模板）")
            return 0
        for f in sorted(os.listdir(TEMPLATE_DIR)):
            if f.endswith(".png"):
                print(f)
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(_cli(sys.argv))
