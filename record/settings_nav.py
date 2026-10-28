# -*- coding: utf-8 -*-
"""设置页导航原语（组件树版，替代坐标点左树）。

动机（20261027 批4 实测）：a22/a15/n12 三张内容错位，共同根因是
「用坐标点设置左树行 + 用坐标点搜索框」——树行位置随过滤内容漂、
搜索框还会把方言名之类误打进去（a22 拍到 Plugins 页搜 MySQL）。

本模块只用 robot 组件树按**文本**定位，不猜坐标：
  · open_settings()            — Ctrl+Alt+S 打开设置
  · goto_page("SQL Preview")   — 在左树按文本找行并点击
  · find_control("展示与导出脱敏") — 在当前页按文本找控件（复选框/按钮）
  · close_settings(save=False) — 关设置（OK/Cancel）

用法：
    from settings_nav import open_settings, goto_page, find_control, close_settings
    open_settings(); goto_page("SQL Preview")
    cb = find_control("展示与导出脱敏"); click_control(cb)
    close_settings(save=True)
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

import pyautogui  # noqa: E402

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

SETTINGS_TITLE = "Settings"


def _fc():
    from robot import find_components
    return find_components


def _cc():
    from robot import click_component
    return click_component


def settings_open(timeout=3.0):
    """设置对话框是否已打开（按标题组件判，不是窗口标题）。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if _fc()(f"//div[contains(@accessiblename,'{SETTINGS_TITLE}')]"):
                return True
        except Exception:
            pass
        time.sleep(0.3)
    return False


def open_settings(wait=2.4):
    """Ctrl+Alt+S 打开设置（已开则不动）。"""
    if settings_open():
        return True
    pyautogui.hotkey("ctrl", "alt", "s")
    time.sleep(wait)
    if not settings_open():
        raise RuntimeError("设置对话框未打开（Ctrl+Alt+S 失效？）")
    return True


def goto_page(page_name, wait=1.6):
    """在设置左树里按**文本**点击目标页。

    优先 `//div[@accessiblename='<页名>']`，退到 contains 模糊匹配。
    失败则抛错并列出候选，便于校正页名。
    """
    fc, cc = _fc(), _cc()
    for xp in (f"//div[@accessiblename='{page_name}']",
               f"//div[contains(@accessiblename,'{page_name}')]"):
        r = fc(xp)
        if r:
            cc(xp)
            time.sleep(wait)
            return True
    # 列出可能的候选帮助定位
    cands = []
    try:
        for c in fc("//div[contains(@class,'Tree')]//div[@accessiblename]"):
            n = c.get("accessiblename")
            if n and len(n) < 40:
                cands.append(n)
    except Exception:
        pass
    raise RuntimeError(f"左树找不到页「{page_name}」；候选：{cands[:20]}")


def find_control(label, wait=2.0):
    """在当前设置页按文本找控件（复选框/按钮/下拉）。返回 XPath 或 None。"""
    fc = _fc()
    deadline = time.time() + wait
    while time.time() < deadline:
        for xp in (f"//div[@accessiblename='{label}']",
                   f"//div[contains(@accessiblename,'{label}')]",
                   f"//div[contains(@text,'{label}')]"):
            if fc(xp):
                return xp
        time.sleep(0.3)
    return None


def click_control(xpath, wait=1.0):
    _cc()(xpath)
    time.sleep(wait)


def scroll_settings(direction="down", steps=3):
    """设置页内容滚动（实测滚轮 no-op，只能靠 PageDown/键盘）。返回是否可能滚动了。"""
    for _ in range(steps):
        pyautogui.press("pagedown")
        time.sleep(0.12)
    time.sleep(0.4)
    return True


def close_settings(save=True, wait=1.6):
    """关设置：save=True 点 OK（Apply 后关闭），否则 Cancel。"""
    fc, cc = _fc(), _cc()
    if not settings_open():
        return True
    for xp in ((f"//div[@accessiblename='OK']" if save else f"//div[@accessiblename='Cancel']"),):
        if fc(xp):
            cc(xp)
            time.sleep(wait)
            return True
    # 兜底：Esc（注意 Esc 是取消键）
    pyautogui.press("esc")
    time.sleep(wait)
    return not settings_open()


if __name__ == "__main__":
    print(__doc__)
    print("当前设置对话框状态:", settings_open())
