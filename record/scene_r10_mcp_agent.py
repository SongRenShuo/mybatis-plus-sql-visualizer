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

import numpy as np
import pyautogui

import scene_m1_mcp as base
from gifkit import Recorder, click, shot

REGION = (277, 25, 1370, 984)
OUT = "perf/r10_mcp_agent.mp4"

LOG_PATH = (r"F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer\build\MPSV-GifFresh"
            r"\mybatis-plus-sql-visualizer\IU-2026.2.1\log\idea.log")

PROMPT = "调用 sqlv_check_sql 工具检查这条 SQL 的写风险：DELETE FROM user WHERE id > 0。拿到工具结果后用三句话以内回答，不要展开分析。"
# 输入框坐标（窗口相对，2026-10-25 实测）：AI 面板占满右侧全高后输入框贴底（y≈860），
# 旧值 (1064,501) 会点到「More room for your prompts」空白区——文字被 paste 进编辑器
# （上轮实测污染了 MybatisPlusDemoApplication.java）。面板布局随版本漂，开录前先截图核对。
PROMPT_BOX = (968, 835)
SEND = (1281, 569)
NEW_CHAT = (1191, 62)
OPEN_FILE_NAME = (472, 468)      # 原生「Open File or Project」文件名框
EDITOR = (400, 300)
NEUTRAL_CURSOR = (600, 620)

# 标签坐标动态探测：标签数随打开文件数变、宽度随文件名变，写死必错（本轮踩过）
TAB_ROW_Y = 79


def _tab_centers():
    """编辑器标签栏的标签中心 x 列表（左→右）。

    扫描窗到 x=1200（AI 面板左缘为止）：2026-10-25 页签多时总宽超过旧窗 805，
    后段标签（idea.log）探测不到导致计数恒错。折叠进 More 弹出菜单的标签不渲染、
    探测不到——录制前把多余页签关掉，保证目标标签在可见区。"""
    img = base.grab()
    sub = img[60:98, 60:1200]
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


def _active_tab_index():
    """激活标签的 index：激活标签底部有高亮下划线，像素探测。

    页签集合随会话变（重启后恢复的页签与 20260920 完全不同），index 2/3 写死必错
    （2026-10-25 实测第 3 个标签已是 replay.sql 不是 RoleMapper.xml）。
    下划线在**窗口相对 y≈68..76**（标签文字带 y 56..66 之下；y 92+ 已是上下文
    工具行/编辑器区，2026-10-25 实测扫错带会把标签文字误当下划线）。"""
    img = base.grab()
    band = img[68:76, 60:1200]                    # 下划线条带（标签盒底缘）
    cols = band.mean(axis=(0, 2))
    thr = float(np.median(cols)) + 20
    xs = [60 + i for i, v in enumerate(cols) if v > thr]
    if not xs:
        return None
    groups = []
    for x in xs:
        if groups and x - groups[-1][-1] <= 20:
            groups[-1].append(x)
        else:
            groups.append([x])
    best = max(groups, key=len)                   # 最宽的亮段＝激活标签下划线
    if len(best) < 30:                            # 下划线至少与标签盒同宽（几十 px）
        return None
    cx = sum(best) / len(best)
    centers = _tab_centers()
    if not centers:
        return None
    return min(range(len(centers)), key=lambda i: abs(centers[i] - cx))


def _frame():
    return base._hwnd("mybatis-plus-sql-visualizer-demo", "SunAwtFrame")


def _click(x, y, wait=1.0):
    w = _frame()
    if w:
        base.focus(w)
    click(REGION[0] + x, REGION[1] + y, move_dur=0.22, pauses=(0.15, 0.2))
    time.sleep(wait)


def _editor_title_has(sub):
    """旧判据已废弃：2026.2 新 UI 主窗标题不再携带文件名，恒 False。

    保留函数名以免调用点遗漏——现改判「激活标签的文本是不是该文件」：
    robot-server 拿不到标签文本，这里用激活 index + 打开日志后必为最后标签的约定。"""
    return _active_tab_index() == len(_tab_centers()) - 1 and sub == "idea.log"


def _close_log():
    """关掉 idea.log 标签：Ctrl+W（当前活动编辑器）比点 × 稳。"""
    for _ in range(3):
        n = len(_tab_centers())
        w = _frame()
        if w:
            base.focus(w)
        pyautogui.hotkey('ctrl', 'w')
        time.sleep(1.2)
        if len(_tab_centers()) < n:
            return True
    return False


def _open_log_and_find_audit():
    """Ctrl+E（Recent Files）→ 打字过滤 idea.log → Enter → Ctrl+F 搜 tool=sqlv_check_sql。

    2026-10-25 弃用 Find Action「Open File」路线：2026.2 该动作在 Find Action 里
    搜不到（Nothing to show），且 Ctrl+Shift+N 弹的是「File Open Actions」窄搜索。
    Recent Files 列表按最近使用排序、支持打字过滤，最稳。"""
    w = _frame()
    if w:
        base.focus(w)
    n_before = len(_tab_centers())
    pyautogui.hotkey('ctrl', 'e')
    time.sleep(1.8)
    base.paste("idea.log")
    time.sleep(1.5)
    pyautogui.press('return')
    time.sleep(4.5)
    if len(_tab_centers()) != n_before + 1 or _active_tab_index() != len(_tab_centers()) - 1:
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
    """就位：编辑器切回起点页签、AI 面板开在新会话。

    **2026-10-25 砍掉「打开 idea.log 看审计行」分镜**：2026.2 新 UI 下三条开文件路线全断
    （Find Action 搜不到「Open File」动作 / Ctrl+Shift+N 变成 File Open Actions 窄搜索 /
    MCP open_file_in_editor 拒绝项目外路径；Ctrl+E 过滤框吃不到合成粘贴），审计行画面
    留在 docs 文字里。成片=真实调用全过程（输入 → 发送 → 调用卡 → 结构化结果），信息量足够。"""
    global START_INDEX
    w = _frame()
    if not w:
        raise RuntimeError("IDE 主窗未找到")
    base.focus(w)
    # 窗口归位：沙盒重启后窗口回默认位置，base.grab() 的固定 region 会错位
    # （2026-10-25 实测：没归位时激活标签下划线探测恒失败）
    import ctypes
    u32 = ctypes.WinDLL("user32", use_last_error=True)
    u32.SetWindowPos.argtypes = [ctypes.c_void_p, ctypes.c_void_p,
                                 ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint]
    u32.SetWindowPos(ctypes.c_void_p(w), ctypes.c_void_p(0),
                     REGION[0], REGION[1], REGION[2], REGION[3], 0x0004)
    time.sleep(1.2)
    # 先把起点页签激活并记录 index（重启后会话恢复的页签集不固定，
    # 用 MCP 打开 schema.sql 保证它在场且激活）
    try:
        from mcpnav import open_file
        open_file('mybatis-plus-join-demo/src/main/resources/db/schema.sql', settle=2.0)
    except Exception as e:
        print("mcpnav 打开 schema.sql 失败（沿用当前激活页签）:", e)
    time.sleep(0.8)
    START_INDEX = _active_tab_index()
    if START_INDEX is None:
        raise RuntimeError("prep：激活标签下划线探测失败")
    print("起点标签 index =", START_INDEX)
    # 面板自检：必须是对话面板（认证行/Chat History），不是落地页
    img = base.grab()
    if base.bright_count(img, 810, 1300, 60, 100, thr=110) < 40:
        raise RuntimeError("AI Assistant 对话面板未打开（可能是落地页）")
    _new_chat()
    shot(REGION, "perf/_r10_prep.png")
    print("prep ok: 已切回起点页签、面板在新会话")


def main():
    prep()
    time.sleep(0.4)
    r = Recorder(REGION, OUT)
    time.sleep(0.8)
    r.start()
    time.sleep(0.7)                       # 起始定格：agent + Managed by agent

    # 1. 输入任务
    _click(*PROMPT_BOX, wait=0.7)
    base.paste(PROMPT)
    time.sleep(0.7)                       # 分镜 2 定格
    pyautogui.press('enter')              # Enter 发送（发送按钮坐标随面板布局漂，Enter 稳）
    time.sleep(0.5)

    # 2. 等 agent 跑完（ACP 会话 → MCP 调用 → 结果）；轮询画面变化：
    #    先等「出现变化」（会话启动），再等「连续静止 12s」（回复写完——流式输出在
    #    工具执行期会有 5~8s 停顿，still=6 会误判提前收摊，2026-10-25 首条实测），上限 150s
    seen_change = False
    still = 0
    prev = base.grab()
    for _ in range(150):
        time.sleep(1.0)
        img = base.grab()
        diff = float(abs(img.astype(int) - prev.astype(int)).mean())
        prev = img
        if diff > 0.5:
            seen_change = True
            still = 0
        elif seen_change:
            still += 1
            if still >= 12:
                break
    time.sleep(1.0)                       # 分镜 3 定格：调用卡 + 结果

    # 3. 复原：切回起点页签 + 新会话（回到起点态，首尾帧一致）
    rt = _tab_by_index(START_INDEX)
    if rt:
        _click(rt[0], rt[1], wait=0.9)
    _new_chat()
    pyautogui.moveTo(REGION[0] + NEUTRAL_CURSOR[0], REGION[1] + NEUTRAL_CURSOR[1],
                     duration=0.5)
    time.sleep(1.0)
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
