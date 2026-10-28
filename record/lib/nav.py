"""沙盒 IDE 导航助手：跳行并回读状态栏校验落点（Goto Line 对话框会残留上次输入，必须先全选清空）。"""
import ctypes, time, pyautogui, mss

ctypes.windll.shcore.SetProcessDpiAwareness(2)
pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

WIN = {'left': 277, 'top': 25, 'width': 1370, 'height': 984}
SB  = {'left': 277, 'top': 25 + 944, 'width': 1370, 'height': 40}   # 状态栏条


def shot(path, region=WIN):
    with mss.mss() as sct:
        img = sct.grab(region)
        mss.tools.to_png(img.rgb, img.size, output=path)


def goto(line, wait=1.2, verify_shot=None):
    """Ctrl+G 跳行。goto 对话框残留上次输入 -> 先 Ctrl+A 清空再输入。

    ⚠️ **输入前必须确认对话框真的弹出来了**（2026-10-13 血的教训）：`Ctrl+G` 被吞时，
    紧随其后的 `Ctrl+A` 会**把整个文件全选**，`typewrite(str(line))` 于是把**整个文件内容
    替换成那个行号**——本轮 `DeviceInfoController.java` 就是这样被写成了一个字面 `110`，
    直到 IDE 报「需要class, interface或enum」才被发现。
    Goto Line 是**轻量弹窗、窗口枚举看不见**（EnumWindows 无新窗口），所以用**像素护栏**：
    比对 Ctrl+G 前后编辑区指纹，没变化即判定没弹出 → 直接抛错，绝不往下敲字。
    """
    base = _region_fp()
    pyautogui.hotkey('ctrl', 'g')
    time.sleep(0.7)
    if base is not None and not _changed(base):
        pyautogui.keyDown('esc'); pyautogui.keyUp('esc')
        raise RuntimeError('Ctrl+G 未弹出 Goto Line 对话框（可能被吞）——已中止，避免把行号打进编辑器覆盖文件')
    pyautogui.hotkey('ctrl', 'a')
    time.sleep(0.25)
    pyautogui.typewrite(str(line), interval=0.06)
    time.sleep(0.4)
    pyautogui.keyDown('return'); pyautogui.keyUp('return')
    time.sleep(wait)
    # Enter 可能被 IME 吞掉，弹窗就留在画面上（2026-10-25 实测：a26 录到第 90 帧时
    # `Go to Line:Column` 还开着、内容是 `56:35`，正是 Enter 没提交成功的形态）。
    # 补一次护栏：弹窗仍在就再按一次 Enter，仍不在再 Esc 兜底。
    if _goto_dialog_open():
        pyautogui.keyDown('return'); pyautogui.keyUp('return')
        time.sleep(0.6)
        if _goto_dialog_open():
            pyautogui.keyDown('esc'); pyautogui.keyUp('esc')
            time.sleep(0.4)
    if verify_shot:
        shot(verify_shot, SB)


def _goto_dialog_open():
    """Goto Line 弹窗是否还开着。

    判据＝输入框的**蓝色 focus 边框**（比亮度可靠：弹窗本体是深灰，与编辑器只差
    3~5 个灰度级，实测用「亮块占比」判恒 False）。蓝框实测屏幕范围 x 809~1179 /
    y 420~537，取窄一点的带（x 800~1190, y 415~545）扫蓝色像素。
    """
    try:
        import numpy as np
        import mss
        box = {'left': 800, 'top': 415, 'width': 390, 'height': 130}
        with mss.mss() as sct:
            img = np.asarray(sct.grab(box))[:, :, :3].astype(int)
        r, g, b = img[:, :, 0], img[:, :, 1], img[:, :, 2]
        blue = (b - r > 60) & (b > 120)
        return int(blue.sum()) > 150
    except Exception:
        return False


# ---- 像素护栏（goto 用）：编辑区指纹比对 -------------------------------------
_GUARD_REGION = {'left': 460, 'top': 25, 'width': 1100, 'height': 700}   # 编辑区（避开工具栏/侧栏）


def _region_fp():
    """编辑区指纹（8x8 网格灰度）；取不到返回 None（护栏降级为不拦）。"""
    try:
        import numpy as np
        import mss
        with mss.mss() as sct:
            img = np.asarray(sct.grab(_GUARD_REGION))[:, :, :3].mean(axis=2)
        h, w = img.shape
        return [img[i * h // 8:(i + 1) * h // 8, j * w // 8:(j + 1) * w // 8].mean()
                for i in range(8) for j in range(8)]
    except Exception:
        return None


def _changed(base, thr=2.0):
    cur = _region_fp()
    if cur is None:
        return True          # 取不到就放行（保持旧行为，别把护栏变成新故障点）
    return max(abs(a - b) for a, b in zip(base, cur)) > thr


def force_en_us(hwnd=None):
    """把输入法切回 en-US，避免 typewrite 被中文 IME 吃掉。

    ⚠️ **只用 ActivateKeyboardLayout 是无效的**（2026-10-13 实测）：它只影响**调用线程**的
    键盘布局，对前台 IDE 窗口不起作用——`typewrite('schema.sql')` 仍被中文 IME 转成「上车吗。sql」。
    正解＝给目标窗口 `PostMessage(WM_INPUTLANGCHANGEREQUEST, 0, hkl)`。
    """
    try:
        u32 = ctypes.windll.user32
        hkl = u32.LoadKeyboardLayoutW('00000409', 1)
        u32.ActivateKeyboardLayout(hkl, 0)
        target = hwnd or u32.GetForegroundWindow()
        if target:
            u32.PostMessageW(ctypes.c_void_p(target), 0x0050, 0, ctypes.c_void_p(hkl))
    except Exception:
        pass


def trigger_preview(wait=3.5, use_find_action=False):
    """打开 SQL 预览。默认 Alt+Shift+Q；use_find_action=True 走 Ctrl+Shift+A（文本输入更稳）。"""
    force_en_us()
    if use_find_action:
        pyautogui.hotkey('ctrl', 'shift', 'a')
        time.sleep(0.9)
        pyautogui.typewrite('Convert to SQL', interval=0.05)
        time.sleep(0.9)
        pyautogui.keyDown('return'); pyautogui.keyUp('return')
    else:
        pyautogui.hotkey('alt', 'shift', 'q')
    time.sleep(wait)


def goto_token(line, words=1):
    """跳行并把光标落到**首个 token** 上。

    插件 WrapperLocator 要求光标在 token 上；落行首缩进（col 1）会 anchor=null
    只弹「未在光标处找到可预览的 SQL 源」通知。Ctrl+Right 从缩进跳到首 token 起点。
    """
    goto(line)
    pyautogui.keyDown('home'); pyautogui.keyUp('home'); time.sleep(0.2)
    for _ in range(words):
        pyautogui.keyDown('ctrl'); pyautogui.keyDown('right')
        pyautogui.keyUp('right'); pyautogui.keyUp('ctrl')
        time.sleep(0.15)
    time.sleep(0.3)


def goto_col(line, col, wait=1.0):
    """跳行并把光标精确移到第 col 列（1 基）——落在指定 token 上。

    比 goto_token 可靠：列号由源码文本算出，不受缩进/词长影响。
    """
    goto(line)
    # IntelliJ 的 Home 是「智能 Home」：第一下到首个非空白字符，第二下才到绝对行首。
    # 不按两下的话列号基准会随缩进漂（8 空格缩进实测整体 +8 列）。
    for _ in range(2):
        pyautogui.keyDown('home'); pyautogui.keyUp('home'); time.sleep(0.12)
    for _ in range(max(0, col - 1)):
        pyautogui.keyDown('right'); pyautogui.keyUp('right')
        time.sleep(0.02)   # 连发不间隔会被丢键（实测 31 次右箭头只生效 24 次、列号短 7）
    time.sleep(wait)
    # **无条件按 Esc**：跳转已经生效，Esc 只关 goto 输入框、不会撤销光标位置。
    # 这比「先判弹窗是否还在」可靠得多——像素护栏在弹窗刚出现/正在淡出时判据不稳，
    # 2026-10-25 实测 a17 首帧就留着 `22:26`、a26 第 90 帧留着 `56:35`，
    # 两次都是护栏判 False 所以没兜底。
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc')
    time.sleep(0.35)


def close_preview(cancel_xy=(1485, 828), wait=1.4, body_xy=None):
    """关预览浮窗：优先点「取消」按钮。

    浮窗尺寸随内容变（带「参数列」面板的 INSERT 预览更宽），取消键位会漂；传 body_xy
    则改为「点进浮窗体内 → Esc」——浮窗拿到焦点时 Esc 才关得掉（在编辑器里按 Esc 会去动
    工具窗），这条实测更稳。
    """
    if body_xy:
        pyautogui.click(*body_xy); time.sleep(0.4)
        pyautogui.keyDown('esc'); pyautogui.keyUp('esc')
    else:
        pyautogui.click(*cancel_xy)
    time.sleep(wait)


def goto_text(text, wait=1.0):
    """用 Ctrl+F 查找唯一片段把光标落到该 token 上。

    比 goto_col 稳：不依赖行号，也不依赖对话框时序（`goto` 偶尔会落空——本轮实测
    文件刚打开时 `goto_col(55,27)` 把光标留在了 34:6）。查找串必须是文件内唯一的。
    """
    pyautogui.hotkey('ctrl', 'f')
    time.sleep(0.7)
    pyautogui.hotkey('ctrl', 'a')
    time.sleep(0.2)
    force_en_us()
    pyautogui.typewrite(text, interval=0.04)
    time.sleep(0.8)
    pyautogui.keyDown('return'); pyautogui.keyUp('return')
    time.sleep(0.5)
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc')   # 关查找条，焦点回编辑器
    time.sleep(0.3)
    # 查找命中后片段是**选中**状态，而插件的预览会走「选中文本里找 SQL」那条路 → 失败（实测弹
    # 「未在光标处找到可预览的 SQL 源」）。按一下 Left 把选中塌掉，光标落到命中片段起点＝token 上。
    pyautogui.keyDown('left'); pyautogui.keyUp('left')
    time.sleep(wait)
