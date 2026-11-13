#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""演示动图交付后处理：裁窗框 → GIF + MP4 + WebP 三件套 → 零损失校验。

用法：
    python deliver.py perf/tN.gif                       # 输出到 perf/deliver/
    python deliver.py perf/tN.gif --out public/demo     # 直接进文档站目录
    python deliver.py perf/tN.gif --poster perf/tN.png  # 用现成 poster 转 WebP
    python deliver.py perf/tN.gif --no-crop             # 跳过裁框
    python deliver.py perf/tN.gif --dry-run             # 只报检测到的框厚度

为什么要有这一步：录制区域虽然已内缩 8px 去阴影，成片四边仍会残留 5~7px 浅灰
窗框（2026-11-03 全量扫描文档站 90 个素材，34 个有框：左/右 5~7、底 4~7、
顶 0~1）。站点上曾用 clip-path 做补偿，结果对无框素材反而切出透明边（浅色主题
下就是白边），所以补偿全部撤掉、改为裁源。

编码参数（2026-11-03 全站实测：GIF 52.06MB → MP4 18.7MB、PNG 6.45MB → WebP 1.25MB）：
  GIF  ：必须带 palettegen(stats_mode=diff)+paletteuse(dither=none)，省掉调色板
         体积会涨 40~277 倍；dither=none 才能做到逐像素一致（PSNR inf）。
  MP4  ：libx264 crf24 preset slow + yuv420p + faststart，尺寸取偶数。
  WebP ：libwebp q82 method 6 preset picture。
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys

LIGHT = 150          # 亮度阈值：>= 视为窗框浅色
UNIFORM = 0.8        # 某深度上有多少比例的采样点达标才算「整条边都是框」
MAX_DEPTH = 12       # 框厚上限（实测 5~7px，留余量）
SAMPLES = 40


def run(cmd: list[str], binary: bool = False) -> bytes | str:
    out = subprocess.run(cmd, capture_output=True, check=True)
    return out.stdout if binary else out.stdout.decode('utf-8', 'ignore')


def probe_size(path: str) -> tuple[int, int]:
    raw = run(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
               '-show_entries', 'stream=width,height', '-of', 'csv=p=0', path]).strip()
    w, h = raw.split(',')[:2]
    return int(w), int(h)


def first_frame_rgb(path: str, width: int, height: int) -> bytes:
    return run(['ffmpeg', '-v', 'error', '-i', path, '-frames:v', '1',
                '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], binary=True)


def detect_frame(path: str) -> dict:
    """扫描四边的浅灰窗框厚度；返回 {top,bottom,left,right}（像素）"""
    width, height = probe_size(path)
    buf = first_frame_rgb(path, width, height)

    def lum(x: int, y: int) -> float:
        i = (y * width + x) * 3
        return (buf[i] + buf[i + 1] + buf[i + 2]) / 3.0

    stats = {'left': [0] * MAX_DEPTH, 'right': [0] * MAX_DEPTH,
             'top': [0] * MAX_DEPTH, 'bottom': [0] * MAX_DEPTH}
    for s in range(1, SAMPLES):
        y = height * s // SAMPLES
        x = width * s // SAMPLES
        for d in range(MAX_DEPTH):
            stats['left'][d] += 1 if lum(d, y) >= LIGHT else 0
            stats['right'][d] += 1 if lum(width - 1 - d, y) >= LIGHT else 0
            stats['top'][d] += 1 if lum(x, d) >= LIGHT else 0
            stats['bottom'][d] += 1 if lum(x, height - 1 - d) >= LIGHT else 0

    def depth(arr: list[int]) -> int:
        d = 0
        while d < MAX_DEPTH and arr[d] / (SAMPLES - 1) >= UNIFORM:
            d += 1
        return d

    return {k: depth(v) for k, v in stats.items()}


def crop_filter(width: int, height: int, frame: dict) -> str:
    x, y = frame['left'], frame['top']
    w = width - frame['left'] - frame['right']
    h = height - frame['top'] - frame['bottom']
    return f'crop={w}:{h}:{x}:{y}'


def even(v: int) -> int:
    return v if v % 2 == 0 else v - 1


def psnr(a: str, b: str, crop: str) -> str:
    try:
        log = subprocess.run(
            ['ffmpeg', '-hide_banner', '-i', a, '-i', b,
             '-filter_complex', f'[1:v]{crop}[ref];[0:v][ref]psnr', '-f', 'null', '-'],
            capture_output=True, text=True).stderr
    except Exception as exc:  # noqa: BLE001
        return f'ERR({exc})'
    for line in log.splitlines():
        if 'average:' in line:
            return line.split('average:')[1].split()[0]
    return 'n/a'


def main() -> int:
    ap = argparse.ArgumentParser(description='演示动图交付后处理：裁框 + MP4 + WebP')
    ap.add_argument('src', help='assemble.py 产出的 GIF（也可以是 mp4/png，仅提取尺寸时会用到）')
    ap.add_argument('--out', default=None, help='输出目录，默认 <src 目录>/deliver')
    ap.add_argument('--poster', default=None, help='现成的 poster PNG；不传则从 GIF 抽 --poster-frame 帧')
    ap.add_argument('--poster-frame', type=int, default=0, help='抽 poster 用的帧号（默认 0）')
    ap.add_argument('--crf', type=int, default=24, help='H.264 CRF（默认 24）')
    ap.add_argument('--quality', type=int, default=82, help='WebP 质量（默认 82）')
    ap.add_argument('--no-crop', action='store_true', help='跳过裁框')
    ap.add_argument('--dry-run', action='store_true', help='只报检测结果，不写文件')
    args = ap.parse_args()

    src = os.path.abspath(args.src)
    if not os.path.exists(src):
        print(f'找不到输入文件: {src}', file=sys.stderr)
        return 2

    stem = os.path.splitext(os.path.basename(src))[0]
    out_dir = os.path.abspath(args.out or os.path.join(os.path.dirname(src), 'deliver'))
    width, height = probe_size(src)
    frame = {'top': 0, 'bottom': 0, 'left': 0, 'right': 0} if args.no_crop else detect_frame(src)
    crop = crop_filter(width, height, frame)
    cw, ch = width - frame['left'] - frame['right'], height - frame['top'] - frame['bottom']

    summary = {
        'src': src,
        'source_size': f'{width}x{height}',
        'frame_px': frame,
        'output_size': f'{cw}x{ch}',
        'crop': crop,
    }
    if args.dry_run:
        print(json.dumps(summary, ensure_ascii=False, indent=1))
        return 0

    os.makedirs(out_dir, exist_ok=True)
    gif_out = os.path.join(out_dir, f'{stem}.gif')
    mp4_out = os.path.join(out_dir, f'{stem}.mp4')
    webp_out = os.path.join(out_dir, f'{stem}.webp')

    is_gif = src.lower().endswith('.gif')

    # 1) GIF：带调色板重编码，dither=none 保逐像素一致
    run(['ffmpeg', '-y', '-v', 'error', '-i', src, '-filter_complex',
         f'[0:v]{crop},split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=none',
         '-loop', '0', gif_out])

    # 2) MP4：yuv420p 要求偶数尺寸
    scale = f'scale={even(cw)}:{even(ch)}'
    run(['ffmpeg', '-y', '-v', 'error', '-i', src, '-vf', f'{crop},{scale}',
         '-c:v', 'libx264', '-crf', str(args.crf), '-preset', 'slow',
         '-pix_fmt', 'yuv420p', '-movflags', '+faststart', mp4_out])

    # 3) WebP poster：优先用现成 poster，否则从源抽帧
    poster_src = args.poster
    if poster_src:
        run(['ffmpeg', '-y', '-v', 'error', '-i', poster_src,
             '-vf', crop, '-c:v', 'libwebp', '-quality', str(args.quality),
             '-compression_level', '6', '-preset', 'picture', webp_out])
    elif is_gif:
        run(['ffmpeg', '-y', '-v', 'error', '-i', src, '-vf',
             f"select=eq(n\\,{args.poster_frame}),{crop}",
             '-frames:v', '1', '-c:v', 'libwebp', '-quality', str(args.quality),
             '-compression_level', '6', '-preset', 'picture', webp_out])

    size = lambda p: os.path.getsize(p) if os.path.exists(p) else 0
    summary.update({
        'gif': f'{size(gif_out) // 1024}K',
        'mp4': f'{size(mp4_out) // 1024}K',
        'webp': f'{size(webp_out) // 1024}K' if os.path.exists(webp_out) else 'n/a',
        'ratio_mp4_vs_gif': f'{size(mp4_out) / max(size(src), 1):.2f}',
        'psnr_gif_vs_src': psnr(gif_out, src, crop) if is_gif else 'n/a',
    })
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    return 0


if __name__ == '__main__':
    sys.exit(main())
