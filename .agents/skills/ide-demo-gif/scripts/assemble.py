# -*- coding: utf-8 -*-
"""GIF 后处理：空白压缩 -> 15fps 调色板 GIF + 同名 poster。

用法：
  python assemble.py perf.mp4 out.gif --width 1280 --poster out.png \
      [--hold 2.0] [--fps 15] [--poster-frame N] [--thr 1.5]

流程（20260909-脚本重放式录制-开发计划）：
1. mp4 按 15fps 解帧；
2. 帧稳定采样找静态段，超长静态段压缩到末尾 hold 秒（等待弹窗的空白被裁掉，
   「关键结果定格」保留 hold 秒紧贴下一动作）；
3. 保留帧重编号 -> palettegen/paletteuse 双通道转 GIF；
4. poster 默认取最长保留静态段的末帧（即关键结果定格帧）。
"""
import argparse
import os
import shutil
import subprocess
import sys

import numpy as np


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stderr[-2000:])
        sys.exit(f"CMD FAILED: {' '.join(cmd)}")


def decode_frames(mp4, workdir, fps):
    os.makedirs(workdir, exist_ok=True)
    # 残留帧必须清掉：上次解码帧数多于本次时，旧尾帧会混入新 GIF（T4 实证）
    for stale in os.listdir(workdir):
        if stale.endswith(".png"):
            os.remove(os.path.join(workdir, stale))
    run(["ffmpeg", "-y", "-loglevel", "error", "-i", mp4,
         "-vf", f"fps={fps}", "-start_number", "0",
         os.path.join(workdir, "f%05d.png")])
    return sorted(f for f in os.listdir(workdir) if f.endswith(".png"))


def load(path):
    from PIL import Image
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.int16)


def diff_map(frames_dir, names, step=4):
    """与 gifkit.frame_diff 同款的分块最大均值差异（小变化不被稀释）。"""
    from PIL import Image

    def blocks(path):
        a = np.asarray(Image.open(path).convert("RGB"), dtype=np.int16)[::step, ::step]
        h, w = a.shape[:2]
        gh, gw = h // 16, w // 16
        return a[:gh * 16, :gw * 16].reshape(gh, 16, gw, 16, 3)

    arrs = [blocks(os.path.join(frames_dir, n)) for n in names]
    out = [1e9]
    for i in range(1, len(arrs)):
        d = np.abs(arrs[i] - arrs[i - 1])
        out.append(float(d.mean(axis=(1, 3, 4)).max()))
    return out, arrs


# ---------------------------------------------------------------------------
# 光标叠加：mss 抓屏不含光标，按 curlog 轨迹回绘箭头 + 点击涟漪
# ---------------------------------------------------------------------------

ARROW = [(0, 0), (0, 16), (4, 12.5), (7, 19), (9.5, 17.8), (6.5, 11.5), (12, 11.5)]
RIPPLE_FRAMES = 4
RIPPLE_COLOR = (90, 150, 247)


def read_curlog(path):
    rows = {}
    if not os.path.exists(path):
        return rows
    with open(path) as f:
        for line in f:
            i, x, y, d = line.strip().split(",")
            rows[int(i)] = (int(x), int(y), d == "1")
    return rows


def draw_cursor(img, x, y, ripple_r=None, scale=1.3):
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)
    if ripple_r:
        cx, cy = x + 6, y + 6
        d.ellipse([cx - ripple_r, cy - ripple_r, cx + ripple_r, cy + ripple_r],
                  outline=RIPPLE_COLOR, width=3)
    pts = [(x + px * scale, y + py * scale) for px, py in ARROW]
    d.polygon(pts, fill=(240, 240, 240))
    d.line(pts + [pts[0]], fill=(20, 20, 20), width=2)
    return img


def overlay_cursor(workdir, names, kept, curlog):
    """给保留帧叠加光标；返回每帧的 (x, y) 供 poster 对齐。"""
    from PIL import Image
    clicks = []  # 涟漪起点：down False->True 的帧号
    prev_down = False
    for i in sorted(curlog):
        down = curlog[i][2]
        if down and not prev_down:
            clicks.append(i)
        prev_down = down
    positions = {}
    for idx, k in enumerate(kept):
        path = os.path.join(workdir, names[k])
        img = Image.open(path).convert("RGB")
        pos = curlog.get(k, (0, 0, False))
        x, y = pos[0], pos[1]
        r = None
        for c in clicks:
            if 0 <= k - c <= RIPPLE_FRAMES:
                r = 10 + 6 * (k - c)
                break
        draw_cursor(img, x, y, r)
        img.save(os.path.join(workdir + "_kept", "k%05d.png" % idx))
        positions[idx] = (x, y)
    return positions


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mp4")
    ap.add_argument("gif")
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--fps", type=int, default=15)
    ap.add_argument("--hold", type=float, default=2.2, help="静态段保留秒数")
    ap.add_argument("--thr", type=float, default=1.2, help="静态判定阈值")
    ap.add_argument("--lead", type=float, default=0.5, help="开头静态保留秒数")
    ap.add_argument("--tail", type=float, default=1.0, help="结尾静态保留秒数")
    ap.add_argument("--poster", default=None)
    ap.add_argument("--poster-frame", type=int, default=None, help="poster 取保留帧序号")
    ap.add_argument("--workdir", default=None)
    args = ap.parse_args()

    fps = args.fps
    base = os.path.splitext(args.gif)[0]
    workdir = args.workdir or base + "_frames"
    names = decode_frames(args.mp4, workdir, fps)
    n = len(names)
    print(f"decoded {n} frames @ {fps}fps = {n / fps:.1f}s")

    diffs, _ = diff_map(workdir, names)

    # 静态段划分（帧 0 视为动作起点，不计 diff）
    runs = []  # (start, end) 闭区间，静态帧段
    i = 1
    while i < n:
        if diffs[i] < args.thr:
            j = i
            while j + 1 < n and diffs[j + 1] < args.thr:
                j += 1
            runs.append((i, j))
            i = j + 1
        else:
            i += 1

    keep = bytearray(b"\x01" * n)
    for (a, b) in runs:
        length = b - a + 1
        is_first = a <= 1
        is_last = b >= n - 1
        if is_last:
            cap = max(1, int(args.tail * fps))
        elif is_first:
            cap = max(1, int(args.lead * fps))
        else:
            cap = int(args.hold * fps)
        if length > cap:
            for k in range(a, b - cap + 1):
                keep[k] = 0
        print(f"  static run [{a}..{b}] len={length} cap={cap} trimmed={length - min(length, cap)}")

    kept = [k for k in range(n) if keep[k]]
    print(f"kept {len(kept)}/{n} frames -> {len(kept) / fps:.1f}s")

    keptdir = workdir + "_kept"
    shutil.rmtree(keptdir, ignore_errors=True)
    os.makedirs(keptdir)
    curlog = read_curlog(args.mp4 + ".curlog")
    if curlog:
        overlay_cursor(workdir, names, kept, curlog)
        print("cursor overlay applied")
    else:
        for idx, k in enumerate(kept):
            shutil.copy(os.path.join(workdir, names[k]),
                        os.path.join(keptdir, f"k%05d.png" % idx))

    # 循环接缝检查：首末保留帧差异
    seam = float(np.abs(load(os.path.join(keptdir, "k%05d.png" % 0))[::4, ::4] -
                        load(os.path.join(keptdir, "k%05d.png" % (len(kept) - 1)))[::4, ::4]).mean())
    print(f"loop seam diff (first vs last kept): {seam:.2f}")

    # GIF 两通道转制
    pal = os.path.join(workdir + "_pal.png")
    run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", str(fps),
         "-i", os.path.join(keptdir, "k%05d.png"),
         "-vf", f"scale={args.width}:-2:flags=lanczos,palettegen=stats_mode=diff", pal])
    run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", str(fps),
         "-i", os.path.join(keptdir, "k%05d.png"), "-i", pal,
         "-lavfi", f"scale={args.width}:-2:flags=lanczos [x]; [x][1:v] paletteuse=dither=sierra2_4a:diff_mode=rectangle",
         args.gif])

    # poster：取「最后一段 ≥1s 的保留静态段」末帧（收尾定格即关键结果帧）；
    # 无满足段则退化为最长静态段末帧，再退化到末帧。
    poster = args.poster or (base + ".png")
    if args.poster_frame is not None:
        src_idx = args.poster_frame
    else:
        cand, longest, longest_len = None, None, -1
        for (a, b) in runs:
            in_kept = [k for k in kept if a <= k <= b]
            if not in_kept:
                continue
            if (b - a) >= fps:
                cand = kept.index(in_kept[-1])  # 循环取最后的满足段
            if (b - a) > longest_len:
                longest, longest_len = in_kept[-1], b - a
        if cand is not None:
            src_idx = cand
        elif longest is not None:
            src_idx = kept.index(longest)
        else:
            src_idx = len(kept) - 1
    run(["ffmpeg", "-y", "-loglevel", "error", "-i", os.path.join(keptdir, "k%05d.png" % src_idx),
         "-vf", f"scale={args.width}:-2:flags=lanczos", poster])
    print(f"poster <- kept frame #{src_idx} -> {poster}")

    sz = os.path.getsize(args.gif) / 1048576
    print(f"GIF: {args.gif} {sz:.1f}MB, {len(kept) / fps:.1f}s")


if __name__ == "__main__":
    main()
