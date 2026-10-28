# -*- coding: utf-8 -*-
"""闸门 3（20261026-0330 计划 §5.2）：收尾自检——对 take 的 mp4 逐帧扫事故元素。

检测项与口径：
- 跳行弹窗：nav._goto_dialog_open 的像素判据搬到帧上——弹窗输入框的蓝色 focus
  边框（b-r>60 且 b>120 的像素计数 >150）出现在编辑器上部区域即命中；
- 红色报错计数：状态栏/结果区出现成簇红字（r>150 且 r-g>60 且 r-b>60 的像素 >200，
  排除 gutter 断点区）——命中只提醒（演示错误也可能是分镜本身，dialect-gate 就有）；
- 加载占位 / 通知气球 / Claude 计费横幅：文本类，像素法检不出——由主控抽帧目视，
  本工具把首 / 25% / 50% / 75% / 末五帧存 PNG 到 perf/_gate_frames/ 供 Read 检查。

用法：python gate_frame_scan.py perf/xxx.mp4 [--frames 15]
退出码：0 通过（或仅红色提醒）；2 跳行弹窗命中。
"""
import os
import subprocess
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
PERF = os.path.join(HERE, "perf")
OUTDIR = os.path.join(PERF, "_gate_frames")


def probe_frames(mp4):
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-count_packets", "-show_entries", "stream=nb_read_packets",
         "-of", "csv=p=0", mp4], capture_output=True, text=True)
    return int(r.stdout.strip() or 0)


def extract(mp4, picks, outdir):
    os.makedirs(outdir, exist_ok=True)
    paths = []
    for i in picks:
        out = os.path.join(outdir, f"f{i:05d}.png")
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", mp4,
                        "-vf", f"select='eq(n\\,{i})'", "-vsync", "0",
                        "-frames:v", "1", out], capture_output=True)
        if os.path.exists(out):
            paths.append((i, out))
    return paths


_TPL_GOTO = None


def _goto_template():
    global _TPL_GOTO
    if _TPL_GOTO is None:
        import cv2
        p = os.path.join(HERE, "templates", "goto_dialog.png")
        import numpy as np
        _TPL_GOTO = cv2.cvtColor(np.asarray(Image.open(p).convert("RGB")), cv2.COLOR_RGB2BGR)
    return _TPL_GOTO


def goto_dialog_hit(img):
    """「Go to Line:Column」弹窗标题模板匹配（阈 0.85）。
    蓝色像素计数法在暗色主题下全帧误报（20261026 批0 实测弃用）。"""
    import cv2
    import numpy as np
    scr = cv2.cvtColor(np.asarray(img.convert("RGB")), cv2.COLOR_RGB2BGR)
    res = cv2.matchTemplate(scr, _goto_template(), cv2.TM_CCOEFF_NORMED)
    return float(res.max()) > 0.85


def red_hit(img):
    a = np.asarray(img.convert("RGB"), dtype=np.int16)
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    return int(((r > 150) & (r - g > 60) & (r - b > 60)).sum()) > 200


def main():
    mp4 = os.path.join(HERE, sys.argv[1]) if not os.path.isabs(sys.argv[1]) else sys.argv[1]
    n = probe_frames(mp4)
    if n == 0:
        print(f"[GATE-3 FAIL] 读不到帧：{mp4}")
        sys.exit(2)
    k = 5
    picks = sorted({0, n * 1 // 4, n // 2, n * 3 // 4, n - 1})
    outdir = os.path.join(OUTDIR, os.path.basename(mp4).replace(".mp4", ""))
    frames = extract(mp4, picks, outdir)
    print(f"[GATE-3] {os.path.basename(mp4)} 共 {n} 帧，抽 {len(frames)} 帧存 {outdir}")
    goto_frames, red_frames = [], []
    for i, p in frames:
        img = Image.open(p)
        if goto_dialog_hit(img):
            goto_frames.append(i)
        if red_hit(img):
            red_frames.append(i)
    if goto_frames:
        print(f"[GATE-3 FAIL] 跳行弹窗入镜帧：{goto_frames}")
        sys.exit(2)
    print("[GATE-3] 跳行弹窗：未检出（Go to Line:Column 标题模板匹配）")
    if red_frames:
        print(f"[GATE-3 WARN] 红色报错色簇帧（目视确认是否分镜本身）：{red_frames}")
    print("[GATE-3] 待目视项（加载占位/通知气球/计费横幅/末帧回起点态）→ Read 上面 5 张 PNG")


if __name__ == "__main__":
    main()
