# -*- coding: utf-8 -*-
"""A40 日志转 SQL 从剪贴板读取（log-to-sql-clipboard.gif）20261011 v6（终版）：
前置：剪贴板已放 Preparing/Parameters 日志、输入/结果区已清。
流程：点输入区 → Ctrl+V 贴日志（「从剪贴板读取」按钮本会话对合成点击免疫，
Ctrl+V 为等价交互面）→ 点转换 ▶（热区偏图标中心左下 ~(985,198)）→
结果区出还原 SQL。
标定（20261011 屏幕坐标）：输入区 (1150,300)；转换 ▶ (985,198)。
坑：工具窗停靠工具栏按钮需九宫格扫点定热区；合成滚轮对编辑器无效用键盘。
"""
import sys
import time

from gifkit import Recorder

REGION = (285, 26, 1354, 974)
OUT = "perf/a40_logtosql.mp4"

# 输入区：用「在此粘贴…」提示标签定位（robot 查 JBLabel 相对坐标），
# 输入框在其下方 —— 20261027 定案：坐标硬点会落空（焦点留在编辑器，paste 打进源文件 262 行）
INPUT_LABEL_XP = "//div[contains(@accessiblename,'在此粘贴')]"
CONVERT = (940, 138)  # ▶ 转换按钮（工具栏左侧）


LOG_SAMPLE = (
    "2026-10-27 20:30:15.123 DEBUG 1234 --- [http-nio-8081-exec-1] "
    "c.s.m.mapper.UserMapper.selectUserListByXml    : "
    "==>  Preparing: SELECT id,username,password,age,email FROM user "
    "WHERE username LIKE concat('%', ?, '%') AND status = ?\n"
    "2026-10-27 20:30:15.124 DEBUG 1234 --- [http-nio-8081-exec-1] "
    "c.s.m.mapper.UserMapper.selectUserListByXml    : "
    "==> Parameters: tom(String), 1(Integer)"
)


def tkinter_clip(text):
    """置剪贴板（tkinter，避免依赖 gifkit.paste 的 Ctrl+V 副作用）。"""
    try:
        import tkinter
        r = tkinter.Tk(); r.withdraw()
        r.clipboard_clear(); r.clipboard_append(text); r.update(); r.destroy()
        return True
    except Exception as e:
        print("(剪贴板失败)", str(e)[:50]); return False


def main():
    import pyautogui
    import gifkit
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0

    # ---- prep（不入镜）：切到「日志 → SQL」页签 + 置剪贴板 ----
    from robot import find_components, click_component
    from verify import assert_no_dialog
    try:
        assert_no_dialog()
    except AssertionError:
        pyautogui.press("esc"); time.sleep(0.8)
    # 关掉可能开着的预览，避免盖住面板
    for xp in ("//div[@accessiblename='取消']",):
        if find_components(xp):
            click_component(xp); time.sleep(1.0); break
    # 切到「日志 → SQL」页签。
    # **20261027 定案：工具窗页签只能用 robot 组件树点**——坐标点击（单击/双击/先取焦）
    # 与键盘（Ctrl+PageDown、Ctrl+Tab）十六次尝试全部无效；而
    # `//div[contains(@class,'TabLabel') and contains(@accessiblename,'日志 → SQL')]`
    # 一次命中并成功切换（类名是 com.intellij.ui.tabs.impl.TabLabel）。
    TAB_LOG_SQL = ("//div[contains(@class,'TabLabel') and "
                   "contains(@accessiblename,'日志 → SQL')]")
    if not find_components(TAB_LOG_SQL):
        raise RuntimeError("找不到「日志 → SQL」页签（工具窗没开？）")
    click_component(TAB_LOG_SQL)
    time.sleep(1.8)
    print("[prep] 已切到「日志 → SQL」页")
    # 剪贴板置入样例日志
    rc = tkinter_clip(LOG_SAMPLE)

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.4)

    # 1. 焦点进输入区 → Ctrl+V。
    #    输入区位置从「在此粘贴…」提示标签推算（标签在输入框上方），
    #    点标签下方 60px 的输入框本体；点完立即断言（避免 paste 打进编辑器）。
    # robot 对 JBLabel 拿不到屏幕坐标（component_screen_center 返 None），
    # 改用「已知提示标签在工具窗内 y≈42、输入框在其下方」的相对关系：
    # 工具窗右栏 x 1040~1290，输入区垂直范围 y 130~330 → 取中心 (1165, 230)
    inp = (1165, 250)     # 输入区本体（提示标签下方；有内容后标签会消失）
    # 断言：点击前确认已切到「日志 → SQL」页——**用页签本身判**，
    # 不能用「在此粘贴」提示标签（有内容后标签不渲染，20261027 实测）
    from robot import find_components as _fc
    if not _fc("//div[contains(@class,'TabLabel') and contains(@accessiblename,'日志 → SQL')]"):
        raise RuntimeError("找不到「日志 → SQL」页签，中止（防止 paste 打进源文件）")
    pyautogui.click(*inp); time.sleep(0.6)
    pyautogui.hotkey('ctrl', 'v')
    time.sleep(2.2)

    # 2. 点转换 ▶
    pyautogui.click(*CONVERT)
    time.sleep(2.4)

    # 3. 二次真实变化：清空输入重新粘贴并再转换（撑时长 + 展示可重复）
    pyautogui.click(*inp)          # 复用上面 robot 推算出的输入区坐标
    time.sleep(0.4)
    pyautogui.hotkey("ctrl", "a"); time.sleep(0.2)
    pyautogui.press("delete"); time.sleep(1.0)
    pyautogui.hotkey("ctrl", "v"); time.sleep(1.4)
    pyautogui.click(*CONVERT)
    time.sleep(2.6)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
