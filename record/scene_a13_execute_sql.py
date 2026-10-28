# -*- coding: utf-8 -*-
"""A13 执行 SQL（临时 .sql + 结果）20261012 v5。

v1~v4 的返工各自暴露一个坑，全部已固化进 locate/verify 标准件：
  ① 弹窗按钮行**目测**写死偏低 48px（连点三次落空）→ `bright_band_y` 实测；
  ② 按钮 x 写死（右对齐按钮随弹窗宽度变）→ `bright_band_x` 取亮簇；
  ③ `window_rect(pid=None)` 会匹配到**别人的同尺寸窗口**（撞上 ZCode 的 ZCodeTpsFooter）→ 限定沙盒 PID；
  ④ **IDE 不在前台 → 键盘快捷键送不到**（开文件无反应、预览不出，且不报错）→ `ensure_foreground` 置顶+取焦+校验。

素材：`DeviceInfoServiceImpl#joinListEntity`（mpjdemo，`car_device_info` LEFT JOIN `station_info`）。
**为什么最终选它**：本张要的是「执行 SQL 有结果」——
① 曾经的目标 `UserServiceImpl#selectJoinList` 执行**必然 0 行**：链里写死 `.eq(UserDO::getId, 1)`，
   而 demo 库里 `user` 只有一行且 **id = 2**（2026-10-12 直连 MySQL 只读清点：user 1 行 / user_address 2 行 /
   area 3 行 / station_info 3 行 / car_device_info 4 行）——造数与条件不匹配，属 demo 侧数据问题，不改 demo。
② 本素材 `eq(shareSchemeId, 1)` 命中 2 行（car_device_info 的 share_scheme_id 为 1,1,2,3），结果网格非空。
③ 其链 `leftJoin(StationInfoEntity.class, StationInfoEntity::getStationId, DeviceInfoEntity::getStationId)`
   的 ON 右侧列**正是主表字段**，不涉及插件缺陷 ★A47，画面即标准形态。

★A47 的现场读数另存为 `perf/a13_a47_evidence.png`（用 UserServiceImpl 链录的那版帧：修复后渲染
`RIGHT JOIN area areaDO ON areaDO.id = addressDO.area_id`，执行不再报 1054）。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import Recorder                                              # noqa: E402
from locate import (bright_band_x, bright_band_y, close_preview,         # noqa: E402
                    ensure_foreground, grab as locate_grab, sandbox_pid,
                    window_rect, windows)
from nav import force_en_us, goto_col, trigger_preview                   # noqa: E402
from verify import Guard                                                 # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = "perf/a13_execute_sql.mp4"

FILE_QUERY = "mpjdemo/DeviceInfoServiceImpl.java"
FILE_TITLE_KEY = "DeviceInfoServiceImpl"
CHAIN_LINE, CHAIN_COL = 110, 18         # .eq(DeviceInfoEntity::getShareSchemeId, 1)

ROW_X = 600                    # Go-to-File 首条结果行的点击 x：**必须落在行左半的路径文字上**。
# ⚠️ 别点行右半：结果行右半会被一个白色浮层 chip（`1 mpjdemo/UserServiceImpl.java`）盖住，
# 点在它边缘会**穿透到后面的编辑器**（弹窗还开着、编辑器却选中了一行），实测 x=777 就是这样
# 失效的；x=600 落在 chip 左侧的路径文字上，稳定命中。
# 首条结果行的 y：**实测 341**。候选从行内往上留一点开始、再向下兜底，覆盖 ±20px 的弹窗位置漂移；
# 落在搜索框或弹窗外都无害（不关弹窗），首个命中即开文件。
# ⚠️ 键盘路径（`↓` + Return）在本平台**无效**：实测 Down 连选中行都不动、Return 也不开文件
# （弹窗仍在、标题不变），别再用它。
FILE_ROW_YS = (341, 333, 349, 357)
MAVEN_HIDE = (1622, 103)       # Maven 工具窗面板头部的「—」

OVERLAY_WATCH = (1290, 520, 60, 200)    # 预览右缘深色边距（浅底浮层盖上来会明显变亮）
CLICK_WATCH = (740, 620, 500, 190)      # 点「执行 SQL」后会长出结果网格的地方
PARK = (577, 725)
GAZE_SQL = (1100, 500)


def ide_hwnd_and_title(pid):
    for h, p, t, r in windows():
        if p == pid and (r[2] - r[0]) > 1200:
            return h, t
    return None, ""


def find_exec_button(ide_pid):
    """定位预览弹窗底部「执行 SQL」按钮 → 屏幕坐标；弹窗不在新建态时抛错。

    y 用**几何标定**（底边 − 39，与 plan/BASELINE 实测一致），不用 `bright_band_y`：
    底部这带的**行**均值只有 40~43（面板底色 26、按钮是描边样式文字稀疏），行向阈值分不出来
    （实测 thr=45 全幅无亮带）；而**列**均值能把按钮文字聚成亮簇。实测三簇恰为
    执行 SQL / 复制 SQL / 取消，最左簇＝执行 SQL。
    ⚠️ 弹窗必须处于**新建态（SQL 视图）**：停在结果态时按钮行整体上移约 40px，
    底边−39 会落到按钮下方空白处。
    """
    main_rect = window_rect(pid=ide_pid, pick="largest")
    popup = window_rect(pid=ide_pid, pick="smallest", min_size=(600, 400))
    if popup is None or popup == main_rect:
        raise RuntimeError("预览弹窗未打开（限定 PID 后只匹配到主窗口 %s）" % (main_rect,))
    pw, ph = popup[2] - popup[0], popup[3] - popup[1]
    img = locate_grab((popup[0], popup[1], pw, ph))
    rx0 = int(pw * 0.45)
    row_y = ph - 39
    clusters = bright_band_x(img, max(0, row_y - 14), min(ph, row_y + 14), rx0, pw,
                             thr=45.0, min_gap=14, min_w=8)
    if not clusters:
        raise RuntimeError("按钮行上未找到亮簇（弹窗可能不在新建态）")
    btn = (popup[0] + (clusters[0][0] + clusters[0][1]) // 2, popup[1] + row_y)
    print("预览弹窗=%s(%dx%d) 按钮行 popup y=%d 亮簇=%s → 点最左簇=%s"
          % (popup, pw, ph, row_y, [(popup[0] + a, popup[0] + b) for a, b in clusters[:4]], btn))
    return btn


def main():
    import pyautogui

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()
    g = Guard()

    # ---- 前置：沙盒 PID + 前台（键盘输入的前提）----
    ide_pid = sandbox_pid()
    if ide_pid is None:
        raise RuntimeError("未找到沙盒 IDE 进程（命令行应含 MPSV-GifFresh）")
    hwnd, title0 = ide_hwnd_and_title(ide_pid)
    if not ensure_foreground(hwnd):
        raise RuntimeError("无法把沙盒 IDE 弄到前台（当前前台句柄 != %s）——键盘快捷键会送不到" % hwnd)
    print("沙盒 pid=%s hwnd=%s 已置前，起始标题=%s" % (ide_pid, hwnd, title0[:60]))

    # ---- prep（不入镜）----
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.8)
    print("关旧预览弹窗:", "已关闭" if close_preview(ide_pid) else "!! 没关掉")
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.5)
    pyautogui.click(*MAVEN_HIDE); time.sleep(1.2)      # 收起 Maven 工具窗，恢复录制版式
    pyautogui.click(600, 500); time.sleep(0.5)

    # 开文件改 MCP 按路径精确打开（20261027：Ctrl+Shift+N + typewrite 必撞同名文件）
    try:
        from mcpnav import open_file
        open_file("mybatis-plus-join-demo/src/main/java/cn/song/mpjdemo/service/impl/DeviceInfoServiceImpl.java", settle=2.0)
    except Exception as e:
        print("MCP 打不开，中止:", e)
        raise
    goto_col(CHAIN_LINE, CHAIN_COL)
    trigger_preview()
    pyautogui.moveTo(*PARK, duration=0.4); time.sleep(1.0)

    # ---- 预热控制台（默认关闭）----
    # 历史：冷启动控制台下，插件把控制台嵌进预览弹窗时结果页签还没建立，弹窗下半部会是**空的**
    # （2026-10-12 用户当场指出「sql 预览窗口怎么没结果」）——那是插件缺陷 ★A48，已在插件仓
    # `7983968` 修复（结果换入判定补「组件已不在本 SplitPane 底部」第四判据并每拍幂等回收重挂）。
    # 修复后**首次执行**就应正常显示结果，故这里默认不再预热：本 take 本身就是 A48 的现场验证。
    # 若哪天又见到「首次执行预览窗空白」，把 WARMUP 打开即可临时规避，并按 A48 的判据
    # （日志里有没有 `reclaimed=true`）复查。
    WARMUP = False
    if WARMUP:
        warm = find_exec_button(ide_pid)
        if warm:
            print("预热执行：点 %s" % (warm,))
            pyautogui.click(*warm)
            time.sleep(9.0)
    close_preview(ide_pid)
    pyautogui.click(600, 500); time.sleep(0.4)
    goto_col(CHAIN_LINE, CHAIN_COL)
    trigger_preview()
    pyautogui.moveTo(*PARK, duration=0.4); time.sleep(1.0)

    # ---- 开录前自检：预览右缘必须是深色（浅底文档浮层盖上来会变亮）----
    mean = float(locate_grab(OVERLAY_WATCH).mean())
    print("预览右缘灰度均值 = %.1f（干净态 ~26）" % mean)
    if mean > 60:
        raise AssertionError("开录前画面不干净：预览右缘变亮（%.1f）——文档浮层可能盖住了预览" % mean)

    btn = find_exec_button(ide_pid)

    # ---- 录制 ----
    r = Recorder(REGION, OUT)
    r.start()
    pyautogui.moveTo(*GAZE_SQL, duration=0.6); time.sleep(1.2)
    g.after_click(btn, region=CLICK_WATCH, wait=1.2, retries=1,
                  msg="点『执行 SQL』后预览下半部无变化（落空）")
    time.sleep(3.0)          # 结果网格出现
    # 凝视结果网格（避开 "2 rows" 气泡：停在表格中上部，不停在行尾/汇总区）
    pyautogui.moveTo(700, 600, duration=0.9); time.sleep(1.8)
    pyautogui.moveTo(900, 660, duration=0.8); time.sleep(1.6)
    # 真实 UI 变化：返回预览 → 再执行一次（撑时长 + 展示可重复执行）
    from robot import click_component as _cc
    _cc("//div[contains(@text,'返回预览') or contains(@accessiblename,'返回预览')]")
    time.sleep(2.2)
    pyautogui.moveTo(700, 450, duration=0.7); time.sleep(1.2)
    _cc("//div[@text='执行 SQL']")
    time.sleep(3.2)
    pyautogui.moveTo(700, 600, duration=0.9); time.sleep(2.0)
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
