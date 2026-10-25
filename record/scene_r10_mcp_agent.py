# -*- coding: utf-8 -*-
"""R10 MCP 真实调用重录（T-DocsRefresh1023）——经 AI Assistant 的 ACP。

沿用 20260920 版 M1-c 的分镜与标定（见下方坑表），仅替换输出文件名。
重录原因：旧片画面是 09-20 的工具栏与面板形态，本轮四页签图标已统一为插件自带 SVG。

原 M1-c 说明：

成品：`public/demo/mcp-agent.gif` + `.png`（MCP 功能页「真实调用」节），1280 宽 / 15fps。

**为什么单独一张**：计划把 M1-a/b/c 设计成一张动图，但 M1-a/b 已出片 13.3s，再加 M1-c 会到 20s+
超出硬性规格 6~15s。故 M1-c 单独成片，`mcp.gif` 不动。

分镜（约 13 秒）：
1. AI Assistant 面板：Codex agent + 「Configured authentication: Managed by agent」
2. 在提示框输入任务（明确要求调用 `sqlv_check_sql`）→ 发送
3. agent 经 ACP 运行、**经 IDE 内置 MCP Server 调 `sqlv_check_sql`**：面板出现
   `mcp__idea__sqlv_check_sql (...)` 调用卡 → 返回「语句类型 / dangerous / findings」
4. 打开 `idea.log` 搜审计行 → 定位到 `[MCP-AUDIT] tool=sqlv_check_sql status=OK elapsedMs=3 args=ok`，
   上下文可见 `[Codex] Reusing existing session`、`Getting Tool: "sqlv_check_sql"`、`Got Tool` 全链
5. 关日志标签 → 面板 New Chat → 回到起点态（首尾帧一致）

## 标定与坑（2026-09-20）

- **面板入口易误判**：点主工具栏 AI 按钮打开的是 AI Assistant 的**落地页/推广页**（本沙盒无 Ultimate
  许可时整页是订阅 FAQ），**不是对话面板**。对话面板是右侧工具窗（图标在右侧栏），打开后显示
  `Chat History` / `Codex` / `Configured authentication: Managed by agent`。曾据此误判「ACP 面板不可达」。
- 面板底部两个下拉分别是 **agent（Codex）** 与 **模型（DeepSeek V4 Flash）**，认证由 agent 自己管
  （`MANAGED_EXTERNALLY`），不需要 JetBrains 账号。
- 审计行要搜 **`tool=sqlv_check_sql`** 而不是 `[MCP-AUDIT]`：日志里有旧一轮 `mpsv_check_sql` 的审计行
  （2026-09-19 22:05），搜 `[MCP-AUDIT]` 会先命中旧的，画面上工具名对不上。
- `idea.log` 在沙盒 `log/` 下（6.8MB / 3.8 万行），Go to File 搜不到（不在项目内）→ 用
  Find Action「Open File or Project」在文件名框里粘全路径打开。
"""
import sys
import time

import pyautogui

import scene_m1_mcp as base
from gifkit import Recorder, click, shot

REGION = (277, 25, 1370, 984)
OUT = "perf/r10_mcp_agent.mp4"

LOG_PATH = (r"F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer\build\MPSV-GifFresh"
            r"\mybatis-plus-sql-visualizer\IU-2026.2.1\log\idea.log")

PROMPT = "调用 sqlv_check_sql 工具检查这条 SQL 的写风险：DELETE FROM user WHERE id > 0"
PROMPT_BOX = (1064, 501)
SEND = (1281, 569)
NEW_CHAT = (1191, 62)
OPEN_FILE_NAME = (472, 468)      # 原生「Open File or Project」文件名框
EDITOR = (400, 300)
NEUTRAL_CURSOR = (600, 620)

# 标签坐标动态探测：标签数随打开文件数变、宽度随文件名变，写死必错（本轮踩过）
TAB_ROW_Y = 79


def _tab_centers():
    """编辑器标签栏的标签中心 x 列表（左→右）。"""
    img = base.grab()
    sub = img[60:98, 60:805]
    mask = (sub.mean(axis=2) > 90)
    cols = mask.sum(axis=0)
    xs = [60 + i for i, v in enumerate(cols) if v > 0]
    groups = []
    for x in xs:
        if groups and x - groups[-1][-1] <= 14:
            groups[-1].append(x)
        else:
            groups.append([x])
    return [int(sum(g) / len(g)) for g in groups]


def _tab_by_index(i):
    """第 i 个标签（0 起）的中心坐标；越界返回 None。"""
    c = _tab_centers()
    if i < 0 or i >= len(c):
        return None
    return (c[i], TAB_ROW_Y)


def _frame():
    return base._hwnd("mybatis-plus-sql-visualizer-demo", "SunAwtFrame")


def _click(x, y, wait=1.0):
    w = _frame()
    if w:
        base.focus(w)
    click(REGION[0] + x, REGION[1] + y, move_dur=0.22, pauses=(0.15, 0.2))
    time.sleep(wait)


def _editor_title_has(sub):
    """主窗标题里是否含某文件（判当前活动编辑器）。"""
    import ctypes
    import ctypes.wintypes as wt
    u32 = ctypes.WinDLL("user32", use_last_error=True)
    w = _frame()
    if not w:
        return False
    n = ctypes.create_unicode_buffer(300)
    u32.GetWindowTextW(w, n, 300)
    return sub in n.value


def _close_log():
    """关掉 idea.log 标签：Ctrl+W（当前活动编辑器）比点 × 稳。"""
    for _ in range(3):
        if not _editor_title_has("idea.log"):
            return True
        w = _frame()
        if w:
            base.focus(w)
        pyautogui.hotkey('ctrl', 'w')
        time.sleep(1.2)
    return not _editor_title_has("idea.log")


def _open_log_and_find_audit():
    """Find Action → Open File or Project → 粘全路径 → Ctrl+F 搜 tool=sqlv_check_sql。"""
    w = _frame()
    if w:
        base.focus(w)
    pyautogui.hotkey('ctrl', 'shift', 'a')
    time.sleep(1.5)
    base.paste("Open File")
    time.sleep(1.6)
    pyautogui.press('return')
    time.sleep(3.0)
    _click(*OPEN_FILE_NAME, wait=0.8)
    base.paste(LOG_PATH)
    time.sleep(1.0)
    pyautogui.press('return')
    time.sleep(4.5)
    if not _editor_title_has("idea.log"):
        return False
    _find_audit()
    return True


def _find_audit():
    """在 idea.log 里搜 `tool=sqlv_check_sql` 并定位（搜 [MCP-AUDIT] 会先命中旧一轮 mpsv_ 审计行）。"""
    w = _frame()
    if w:
        base.focus(w)
    pyautogui.hotkey('ctrl', 'f')
    time.sleep(1.4)
    base.paste("tool=sqlv_check_sql")
    time.sleep(1.2)
    pyautogui.press('return')
    time.sleep(2.0)
    pyautogui.press('esc')
    time.sleep(0.8)


def _new_chat():
    _click(*NEW_CHAT, wait=1.5)


def prep():
    """就位：日志已开好并停在审计行、编辑器切回 mapper、AI 面板开在新会话。

    **日志在 prep 里开**：录制段现场开日志要 13s（Find Action + 原生对话框 + 4.5s 载入），
    会把成片顶到 30s 超出规格；prep 开好后录制段只切标签，1s 内完成。"""
    w = _frame()
    if not w:
        raise RuntimeError("IDE 主窗未找到")
    base.focus(w)
    if not _editor_title_has("idea.log"):
        if not _open_log_and_find_audit():
            raise RuntimeError("prep：idea.log 未能打开并定位到审计行")
    else:
        _find_audit()
    time.sleep(0.5)
    rt = _tab_by_index(2)                # RoleMapper.xml（第 3 个标签）
    if rt:
        _click(rt[0], rt[1], wait=1.0)   # 切回 mapper（录制起点）
    # 面板自检：必须是对话面板（Codex / Chat History），不是落地页
    img = base.grab()
    if base.bright_count(img, 810, 1300, 60, 100, thr=110) < 40:
        raise RuntimeError("AI Assistant 对话面板未打开（可能是落地页）")
    _new_chat()
    shot(REGION, "perf/_r10_prep.png")
    print("prep ok: 日志已停在审计行并切回 mapper、面板在新会话")


def main():
    prep()
    time.sleep(0.4)
    r = Recorder(REGION, OUT)
    time.sleep(0.8)
    r.start()
    time.sleep(0.7)                       # 起始定格：Codex + Managed by agent

    # 1. 输入任务
    _click(*PROMPT_BOX, wait=0.7)
    base.paste(PROMPT)
    time.sleep(0.7)                       # 分镜 2 定格
    _click(*SEND, wait=0.5)

    # 2. 等 agent 跑完（ACP 会话 → MCP 调用 → 结果）；轮询画面变化，最多 40s
    for _ in range(40):
        time.sleep(1.0)
        img = base.grab()
        if base.bright_count(img, 820, 1290, 150, 420, thr=120) > 800:
            break
    time.sleep(0.8)                       # 分镜 3 定格：调用卡 + 结果

    # 3. 切到 idea.log（prep 已定位好）看审计行——日志是最后一个标签
    lt = _tab_by_index(3)
    if lt:
        _click(lt[0], lt[1], wait=1.0)
    if not _editor_title_has("idea.log"):
        r.stop()
        raise RuntimeError("未切到 idea.log 标签")
    time.sleep(1.0)                       # 分镜 4 定格：审计行

    # 4. 复原：切回 mapper + 新会话（回到起点态，首尾帧一致）
    rt = _tab_by_index(2)
    if rt:
        _click(rt[0], rt[1], wait=0.9)
    _new_chat()
    pyautogui.moveTo(REGION[0] + NEUTRAL_CURSOR[0], REGION[1] + NEUTRAL_CURSOR[1],
                     duration=0.5)
    time.sleep(1.0)
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
