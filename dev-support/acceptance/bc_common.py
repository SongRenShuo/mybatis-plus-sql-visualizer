# -*- coding: utf-8 -*-
"""批 C 探针共享引导——probe_bc_*.py 十一份脚本共用的沙盒定位 / 截图 / 断言 / 导航胶水。

**设计纪律（批 C 定稿计划「提效纪律」行）**：
- 坐标一律动态定位——robot-server 组件树 > anchor 图像模板 > window_rect 几何，
  **任何路线都不落手工换算的屏幕坐标**（主控反馈手工坐标错位率高，这是批 C 的核心价值）；
- robot-server 本轮未启时自动跳过该路线（`robot_available()` 缓存探活结果）；
- 每个动作后用 `verify.Guard` / 像素指纹自证变化；语义级断言（通知文案、SQL 文本）
  在拿不到组件树/模板时**降级为「截图 + 区域指纹」**，绝不冒充已核对文本；
- 截图统一落 `mybatis-plus-sql-visualizer-dev/build/ui243-shots/bc-<组号>-*.png`。

**执行方式**：`_run_elev.bat probe_bc_UXX.py`（沙盒 IDE 是提权进程，UIPI 挡非提权 SendInput）。
脚本自身只做 GUI 探针，不跑 gradle、不执行 SQL 写操作。
"""
import os
import sys
import time
import glob
import ctypes

RECORD_DIR = os.path.dirname(os.path.abspath(__file__))
MAIN_REPO = os.path.dirname(RECORD_DIR)                     # .../mybatis-plus-sql-visualizer
# acceptance 布局（dev-support/acceptance）下 MAIN_REPO=dev-support，需按「含 build/ 的祖先」定位工作树
_dev_cand = os.path.dirname(os.path.dirname(RECORD_DIR))
DEV_TREE = (os.environ.get("BC_DEV_TREE")
            or (_dev_cand if os.path.isdir(os.path.join(_dev_cand, "build"))
                else os.path.join(os.path.dirname(MAIN_REPO), "mybatis-plus-sql-visualizer-dev")))
SHOTS_DIR = os.path.join(DEV_TREE, "build", "ui243-shots")
DEMO_ROOT = os.path.join(os.path.dirname(MAIN_REPO), "mybatis-plus-sql-visualizer-demo")

# 演示仓内各组要打开的固定入口文件（全用正斜杠拼，供 Open File 检索用相对路径）
P_USER_SERVICE = "UserServiceImpl"
P_USER_MAPPER_XML = "UserMapper.xml"
P_USER_ENTITY = "User.java"
P_KOTLIN_SCENARIOS = "KotlinWrapperScenarios"
P_HIBERNATE_DAO = "CommonDaoHibernateImpl"
P_JAR_SERVICE = "WmsInventoryJarQueryService"

for _p in (RECORD_DIR, os.path.join(RECORD_DIR, "lib")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

try:  # 提权 bat 把 stdout 重定向进日志文件，Windows 默认 cp936 会碎中文
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

import mss  # noqa: E402
import numpy as np  # noqa: E402
import pyautogui  # noqa: E402

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

from locate import (  # noqa: E402
    windows, window_rect, ensure_foreground, close_preview as _locate_close_preview,
)
from verify import Guard, fingerprint, diff_score  # noqa: E402
import anchor as anchor_mod  # noqa: E402
import robot as robot_mod  # noqa: E402
from robot import RobotServerError  # noqa: E402
import nav as nav_mod  # noqa: E402
import gifkit  # noqa: E402


# ---------------------------------------------------------------- 基础设施

def sandbox_pid():
    """243 沙盒 IDE 的 PID：从 dev 工作树 build/<沙盒名>/.../config/.lock 读 IDE 自写实例 PID。

    `locate.sandbox_pid()` 写死了主仓 GifFresh 沙盒，批 C 跑在 MPSV-MasterMerge-UI243（243 平台），
    路径结构同构但目录名/平台段都不同——这里用 glob 兜住两类沙盒：
    环境变量 `BC_SANDBOX` 指定沙盒名子串；缺省优先 MasterMerge-UI243，否则取最近改动的锁。
    找不到返回 None（调用方抛前置条件错误，不猜）。
    """
    pat = os.path.join(DEV_TREE, "build", "*", "mybatis-plus-sql-visualizer", "*", "config", ".lock")
    locks = glob.glob(pat)
    if not locks:
        return None
    want = os.environ.get("BC_SANDBOX", "MPSV-MasterMerge-UI243")
    ranked = sorted(locks, key=lambda p: (want not in p, -os.path.getmtime(p)))
    try:
        txt = open(ranked[0]).read().strip()
    except OSError:
        return None
    return int(txt) if txt.isdigit() else None


def ide():
    """返回 (pid, hwnd, rect)——沙盒 IDE 主窗口三件套；沙盒没起就抛前置条件错误。"""
    pid = sandbox_pid()
    if not pid:
        raise RuntimeError("未找到批 C 沙盒 IDE（dev/build/*/config/.lock）——先起 runIde 再跑本探针")
    for hwnd, wpid, _title, rect in windows():
        if wpid == pid and (rect[2] - rect[0]) > 1200 and (rect[3] - rect[1]) > 700:
            return pid, hwnd, rect
    raise RuntimeError("沙盒 PID=%s 在，但没找到主窗口（>1200x700）——IDE 起到一半？" % pid)


def front():
    """沙盒置前（键盘快捷键要求焦点；判据按 PID 比防模态弹窗误判）。返回 rect。"""
    pid, hwnd, rect = ide()
    if not ensure_foreground(hwnd, tries=6):
        raise RuntimeError("沙盒 IDE 无法置前——手工点一下 IDE 再重跑")
    return rect


def shot(name, region=None):
    """截图落 SHOTS_DIR；`region=(l,t,w,h)` 缺省全屏。返回落盘路径。"""
    os.makedirs(SHOTS_DIR, exist_ok=True)
    path = os.path.join(SHOTS_DIR, name + ".png")
    if region is None:
        with mss.mss() as sct:
            mon = sct.monitors[1]
            region = (mon["left"], mon["top"], mon["width"], mon["height"])
    gifkit.shot(tuple(int(v) for v in region), path)
    return path


def esc(times=2, wait=0.35):
    """连按 Esc 清浮层（菜单/弹层/气泡焦点）。"""
    for _ in range(times):
        pyautogui.keyDown("esc")
        pyautogui.keyUp("esc")
        time.sleep(wait)


class Reporter:
    """步骤与断言登记：每步打印、失败不中断（采满证据），收口按失败数给退出码。"""

    def __init__(self, group):
        self.group = group
        self.fails = []
        self.n = 0

    def step(self, msg):
        self.n += 1
        print("[STEP %02d] %s" % (self.n, msg))

    def ok(self, msg):
        print("  [PASS] %s" % msg)

    def fail(self, msg):
        self.fails.append(msg)
        print("  [FAIL] %s" % msg)

    def check(self, cond, ok_msg, fail_msg):
        if cond:
            self.ok(ok_msg)
        else:
            self.fail(fail_msg)
        return bool(cond)

    def shot_check(self, cond, shot_name, ok_msg, fail_msg, region=None):
        """断言 + 无论过没过都留截图（失败截图是人工归口的原料）。"""
        path = shot(shot_name, region)
        return self.check(cond, "%s（截图 %s）" % (ok_msg, os.path.basename(path)),
                          "%s（截图 %s）" % (fail_msg, os.path.basename(path)))

    def finish(self):
        print("==== %s 收口：%d 步，失败 %d ====" % (self.group, self.n, len(self.fails)))
        for f in self.fails:
            print("  FAIL: %s" % f)
        return 1 if self.fails else 0


# ---------------------------------------------------------------- 定位路线

_robot_ok_cache = None


def robot_available():
    """robot-server 探活（本轮未启则恒 False，全部路线自动落到 anchor/几何）。"""
    global _robot_ok_cache
    if _robot_ok_cache is None:
        try:
            robot_mod.find_components("//div[@class='IdeFrameImpl']", timeout=4)
            _robot_ok_cache = True
        except Exception:
            _robot_ok_cache = False
            print("[info] robot-server 不可用，组件树路线跳过（仅 anchor/几何路线）")
    return _robot_ok_cache


def _xp_escape(s):
    return s.replace("'", "&apos;")


def find_named(label):
    """按可见文本/可访问名找组件（robot 路线）；找不到/不可用返回 None。"""
    if not robot_available():
        return None
    for attr in ("accessiblename", "text"):
        try:
            comp = robot_mod.find_one("(//div[@%s='%s'])[last()]" % (attr, _xp_escape(label)))
        except RobotServerError:
            comp = None
        if comp:
            return comp
    return None


def find_contains(subtext):
    """按文本子串找组件（robot 路线，contains 语义）；找不到/不可用返回 None。"""
    if not robot_available():
        return None
    try:
        return robot_mod.find_one("(//div[contains(@text,'%s')]|//div[contains(@accessiblename,'%s')])[last()]"
                                  % (_xp_escape(subtext), _xp_escape(subtext)))
    except RobotServerError:
        return None


def click_named(label, offset=(0, 0)):
    """robot 路线点按名组件，成功返回 'robot'；找不到返回 None（不猜坐标）。"""
    if not robot_available():
        return None
    comp = find_named(label)
    if not comp:
        return None
    for attr in ("accessiblename", "text"):
        xp = "(//div[@%s='%s'])[last()]" % (attr, _xp_escape(label))
        try:
            x, y = robot_mod.component_screen_center(xp, comp=comp)
        except RobotServerError:
            continue
        pyautogui.moveTo(x + offset[0], y + offset[1], duration=0.25)
        pyautogui.click(x + offset[0], y + offset[1])
        return "robot"
    return None


def anchor_ready(name):
    return os.path.exists(anchor_mod.template_path(name))


def click_button(rep, label, anchor_name=None, region=None, must=True):
    """按钮定位统一入口：robot 按名 > anchor 模板 > 失败（给校准指引，不猜坐标）。"""
    rep.step("定位并点击「%s」%s" % (label, ("（锚点 %s）" % anchor_name) if anchor_name else ""))
    route = click_named(label)
    if route:
        rep.ok("robot 路线命中「%s」" % label)
        return route
    if anchor_name and anchor_ready(anchor_name) and anchor_mod.click_anchor(anchor_name, region=region):
        rep.ok("anchor 模板命中「%s」" % anchor_name)
        return "anchor"
    msg = ("无法定位「%s」：robot-server 未启且 anchor 模板 %s 未标定。"
           "标定：python anchor.py capture %s <l> <t> <w> <h>" % (label, anchor_name, anchor_name))
    rep.fail(msg)
    if must:
        raise RuntimeError(msg)
    return None


def editor_region(rect=None):
    """编辑器区域（IDE 窗口内中部），供像素指纹/gutter 检测；window_rect 几何推断。"""
    rect = rect or ide()[2]
    l, t, r, b = rect
    return (l + 200, t + 120, max(400, (r - l) - 420), max(300, (b - t) - 260))


def balloon_region(rect=None):
    """IDE 通知气泡区（窗口右下）。通知是 BALLOON 组，出没出靠这片的指纹差。"""
    rect = rect or ide()[2]
    l, t, r, b = rect
    return (max(l, r - 700), max(t, b - 320), 680, 300)


def gutter_icons_in_editor(ed_region=None):
    """在编辑器区截图里找插件 gutter 图标（绿箭头/紫Q），返回 [(screen_y, screen_x)]。"""
    ed = ed_region or editor_region()
    tmp = shot("bc-_gutter_tmp", ed)
    rel = gifkit.find_gutter_icons(tmp, strip_x=(30, 150), y_range=(0, ed[3]), gap=25)
    return [(ed[1] + y, ed[0] + x) for (y, x) in rel]


def preview_rect(pid=None):
    """SQL 预览浮窗矩形（同 PID 下最小可用窗口；无返回 None）。"""
    pid = pid or ide()[0]
    main = window_rect(pid=pid, pick="largest", min_size=(800, 600))
    pop = window_rect(pid=pid, pick="smallest", min_size=(600, 400))
    if pop and main and pop != main:
        # 视口上限过滤：IDE 内层 content frame（内缩全屏 1720x880 级）会被误当预览面板；
        # 预览面板固定模态实测约 1190x702，超过上限即非面板。
        if (pop[2] - pop[0]) <= 1600 and (pop[3] - pop[1]) <= 950:
            return pop
    return None


def _inside(xy, rect):
    return rect[0] <= xy[0] <= rect[0] + rect[2] and rect[1] <= xy[1] <= rect[1] + rect[3]


def text_fields_in(rect, classes=("JBTextField", "EditorTextField")):
    """rect（如预览浮窗）内的单行输入框屏幕中心列表（robot 路线；不可用返回 []）。"""
    if not robot_available():
        return []
    out = []
    for cls in classes:
        xp = "//div[@class='%s']" % cls
        try:
            comps = robot_mod.find_components(xp)
        except RobotServerError:
            continue
        for i, c in enumerate(comps):
            if int(c.get("width", 0)) <= 0 or int(c.get("height", 0)) <= 0:
                continue
            try:
                xy = robot_mod.component_screen_center("(%s)[%d]" % (xp, i + 1), comp=c)
            except RobotServerError:
                continue
            if _inside(xy, rect) and xy not in out:
                out.append(xy)
    out.sort(key=lambda p: (p[1], p[0]))
    return out


def fill_text_fields(rect, values, rep=None):
    """把 `values` 依序填进 rect 内的输入框（点入→Ctrl+A→键入）；返回成功填写的个数。

    值只走 ASCII/数字（IME 吞键环境下的硬约束）；中文值请改 clipboard 粘贴路线。
    """
    fields = text_fields_in(rect)
    n = 0
    for xy, val in zip(fields, values):
        if val is None:
            continue
        pyautogui.click(*xy)
        time.sleep(0.3)
        pyautogui.hotkey("ctrl", "a")
        time.sleep(0.15)
        nav_mod.force_en_us()
        pyautogui.write(str(val), interval=0.04)
        time.sleep(0.4)
        n += 1
    if rep:
        want = len([v for v in values if v is not None])
        rep.check(n == want, "填参 %d/%d（robot 输入框路线）" % (n, want),
                  "只填上 %d/%d 个参数（输入框定位不足，截图人工补）" % (n, want))
    return n


def close_preview(pid=None, wait=1.0):
    pid = pid or ide()[0]
    return bool(_locate_close_preview(pid, wait))


# ---------------------------------------------------------------- 导航胶水

def open_file(query, wait=2.2, rep=None):
    """Ctrl+Shift+N 搜文件并打开——动态定位，不写死弹窗坐标。

    姿势：弹窗是否弹出用像素护栏（goto 血训：不确认就敲字会把行号打进编辑器）；
    选中结果先 Enter（检测弹窗条带消失），IME 吞键则补一次，仍不行落到**窗口几何估算**的
    首行（弹窗水平居中，历史标定 y≈窗口顶+318px）双击，最后用「编辑区指纹已变」收口。
    """
    rep = rep or Reporter("open_file")
    pid, _hwnd, rect = ide()
    ed = editor_region(rect)
    base_ed = fingerprint(ed)
    band = (rect[0] + (rect[2] - rect[0]) // 2 - 280, rect[1] + 100, 560, 520)
    base_band = fingerprint(band)

    pyautogui.hotkey("ctrl", "shift", "n")
    time.sleep(1.1)
    popped = diff_score(base_band, fingerprint(band)) >= 0.5
    if not popped and robot_available():
        # 像素护栏在深色主题下对半透明弹窗不敏感——robot 文本命中「Project Files/All」兜底
        popped = find_contains("Project Files") is not None or find_named("All") is not None
    if not popped:
        esc()
        raise RuntimeError("Ctrl+Shift+N 未弹出搜索浮窗（像素护栏+robot 双判据未过）——中止，绝不盲敲")
    nav_mod.force_en_us()
    pyautogui.typewrite(query, interval=0.04)
    time.sleep(wait)

    before_band = fingerprint(band)
    pyautogui.keyDown("return")
    pyautogui.keyUp("return")
    time.sleep(0.9)
    if diff_score(before_band, fingerprint(band)) < 2.0:      # IME 吞了回车 → 补一次
        pyautogui.keyDown("return")
        pyautogui.keyUp("return")
        time.sleep(0.9)
    if diff_score(base_ed, fingerprint(ed)) > 4.0:
        rep.ok("open_file(%s)：Enter 路线开文件成功" % query)
        return True
    # 兜底：双击首行（窗口几何估算，非手工屏幕坐标）
    row = (rect[0] + (rect[2] - rect[0]) // 2, rect[1] + 318)
    pyautogui.doubleClick(*row)
    time.sleep(2.0)
    ok = diff_score(base_ed, fingerprint(ed)) > 4.0
    if not ok and find_named(query) is not None:
        # 目标文件本就打开着：Go to File 打开同一文件不改变编辑区指纹——页签在位即视为可达
        rep.ok("open_file(%s)：文件已打开（页签在位，指纹不变量属同文件激活）" % query)
        return True
    (rep.ok if ok else rep.fail)("open_file(%s)：%s" % (query, "双击首行兜底成功" if ok else "两种路线都没开成"))
    return ok


def caret(line=None, col=None, text=None, rep=None):
    """把光标落上目标：`text`（Ctrl+F 唯一片段）优先，其次 `goto_col(line,col)`。"""
    if text:
        nav_mod.goto_text(text)
    else:
        nav_mod.goto_col(line, col or 1)
    if rep:
        rep.shot_check(True, "bc-_caret", "光标已落位（line=%s col=%s text=%r）" % (line, col, text),
                       "光标落位（截图存档）")


def trigger_preview(rep, wait=3.5):
    """Alt+Shift+Q 触发预览并等浮窗出现；返回浮窗矩形，不出则 FAIL 并返回 None。

    不点编辑器（点击会移动 caret 破坏落点）；文件刚打开时给一次短暂等待让编辑器就绪。
    """
    time.sleep(0.6)
    nav_mod.trigger_preview(wait=wait)
    rect = preview_rect()
    if rect:
        rep.ok("预览浮窗已出现 %s" % (rect,))
    else:
        rep.fail("Alt+Shift+Q 后预览浮窗未出现")
    return rect


# ---------------------------------------------------------------- 剪贴板（win32 直读直写）

_CF_UNICODETEXT = 13


def clipboard_get_text():
    """读剪贴板 Unicode 文本；空/无该格式返回 None。"""
    u32 = ctypes.windll.user32
    k32 = ctypes.windll.kernel32
    if not u32.OpenClipboard(0):
        return None
    try:
        h = u32.GetClipboardData(_CF_UNICODETEXT)
        if not h:
            return None
        p = k32.GlobalLock(ctypes.c_void_p(h))
        if not p:
            return None
        try:
            return ctypes.wstring_at(p)
        finally:
            k32.GlobalUnlock(ctypes.c_void_p(h))
    finally:
        u32.CloseClipboard()


def clipboard_clear_strict():
    """清空剪贴板并**验证真空**（EnumClipboardFormats==0）——「真正空剪贴板」的可证形态。"""
    u32 = ctypes.windll.user32
    if not u32.OpenClipboard(0):
        raise OSError("OpenClipboard failed")
    try:
        u32.EmptyClipboard()
    finally:
        u32.CloseClipboard()
    time.sleep(0.2)
    n = 0
    fmt = u32.EnumClipboardFormats(0)
    while fmt:
        n += 1
        fmt = u32.EnumClipboardFormats(fmt)
    return n == 0


def clipboard_set_text(text):
    """写剪贴板 Unicode 文本（ctypes 直写，不经 IME/键盘）。"""
    u32 = ctypes.windll.user32
    k32 = ctypes.windll.kernel32
    buf = text.encode("utf-16-le") + b"\x00\x00"
    GMEM_MOVEABLE = 0x0002
    if not u32.OpenClipboard(0):
        raise OSError("OpenClipboard failed")
    try:
        u32.EmptyClipboard()
        k32.GlobalAlloc.restype = ctypes.c_void_p
        k32.GlobalAlloc.argtypes = [ctypes.c_uint, ctypes.c_size_t]
        k32.GlobalLock.restype = ctypes.c_void_p
        k32.GlobalLock.argtypes = [ctypes.c_void_p]
        k32.GlobalUnlock.argtypes = [ctypes.c_void_p]
        u32.SetClipboardData.restype = ctypes.c_void_p
        u32.SetClipboardData.argtypes = [ctypes.c_uint, ctypes.c_void_p]
        h = k32.GlobalAlloc(GMEM_MOVEABLE, len(buf))
        p = k32.GlobalLock(h)
        ctypes.memmove(p, buf, len(buf))
        k32.GlobalUnlock(h)
        u32.SetClipboardData(_CF_UNICODETEXT, h)
    finally:
        u32.CloseClipboard()


# ---------------------------------------------------------------- 计时（U43 不卡断言）

def type_and_measure(chars, region, max_wait=4.0):
    """逐字符键入并量「键入→画面响应」耗时（像素指纹轮询）；返回 [秒,...]。

    冻结的表现＝指纹迟迟不变；容差判据由调用方做（基线 2×）。
    """
    out = []
    for ch in chars:
        before = fingerprint(region)
        t0 = time.time()
        pyautogui.write(ch, interval=0.01)
        lat = None
        while time.time() - t0 < max_wait:
            time.sleep(0.05)
            if diff_score(before, fingerprint(region)) > 2.0:
                lat = time.time() - t0
                break
        out.append(lat if lat is not None else max_wait)
        time.sleep(0.15)
    return out
