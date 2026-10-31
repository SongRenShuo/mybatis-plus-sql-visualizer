#!/usr/bin/env node
// 演示 GIF 素材规格校验（无依赖，只用 ffprobe）
//
// 用途：录制转制完成后批量核对成片规格——尺寸 / 帧数 / 时长 / 帧率 / 体积 / poster 同名同尺寸。
// 用法：
//   node record/tools/verify_gif.mjs <gif...>
//   node record/tools/verify_gif.mjs --dir <素材目录>
//
// 说明：
// - 站点侧的「GifPlayer 组件行为 / 亮暗主题 / 中英页」校验留在文档站仓
//   （`scripts/verify-crudsql-gif.mjs`，需 puppeteer + preview），不属录制工具链。
// - 接缝 diff 由 `assemble.py` 转制时打印（结构性跳变需人工登记），本工具不重复计算。
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const EXPECT = { width: 1280, height: 920, minFrames: 60, fps: 15, maxMB: 6, minDuration: 3, maxDuration: 30 };

// 例外清单（同目录 gif_spec_allow.json）：历史已登记的口径差异，每条带 why。
const ALLOW_PATH = new URL('./gif_spec_allow.json', import.meta.url);
const ALLOW = fs.existsSync(ALLOW_PATH) ? JSON.parse(fs.readFileSync(ALLOW_PATH, 'utf8')) : {};

function expectFor(file) {
  const name = path.basename(file);
  const over = ALLOW[name] || {};
  const e = { ...EXPECT };
  for (const k of ['width', 'height', 'minFrames', 'fps', 'maxMB', 'minDuration', 'maxDuration']) {
    if (over[k] != null) e[k] = over[k];
  }
  return { e, why: over.why };
}

function ffprobe(args) {
  return execFileSync('ffprobe', ['-v', 'error', ...args], { encoding: 'utf8' }).trim();
}

function probe(file) {
  const json = ffprobe([
    '-select_streams', 'v:0',
    '-show_entries', 'stream=width,height,r_frame_rate,nb_read_frames',
    '-show_entries', 'format=duration,size',
    '-of', 'json', '-count_frames', file,
  ]);
  const j = JSON.parse(json);
  const s = (j.streams && j.streams[0]) || {};
  const f = j.format || {};
  const [num, den] = String(s.r_frame_rate || '0/1').split('/').map(Number);
  return {
    width: Number(s.width),
    height: Number(s.height),
    frames: Number(s.nb_read_frames),
    fps: den ? num / den : 0,
    duration: Number(f.duration),
    sizeMB: Number(f.size) / 1048576,
  };
}

function check(file) {
  const issues = [];
  if (!fs.existsSync(file)) return { file, issues: ['文件不存在'] };

  const { e, why } = expectFor(file);
  const m = probe(file);
  const push = (name, ok, detail) => {
    if (!ok) issues.push(`${name}: ${detail}`);
  };

  push('尺寸', m.width === e.width && m.height === e.height,
    `${m.width}×${m.height}（期望 ${e.width}×${e.height}）`);
  push('帧数', m.frames >= e.minFrames, `${m.frames}（≥${e.minFrames}）`);
  push('帧率', Math.abs(m.fps - e.fps) < 0.5, `${m.fps.toFixed(2)}fps（期望 ${e.fps}）`);
  push('时长', m.duration > e.minDuration && m.duration < e.maxDuration,
    `${m.duration.toFixed(1)}s（${e.minDuration}~${e.maxDuration}s）`);
  push('体积', m.sizeMB <= e.maxMB, `${m.sizeMB.toFixed(2)}MB（≤${e.maxMB}MB）`);

  const poster = file.replace(/\.gif$/i, '.png');
  if (fs.existsSync(poster)) {
    const p = probe(poster);
    push('poster', p.width === m.width && p.height === m.height,
      `${path.basename(poster)} ${p.width}×${p.height}`);
  } else {
    push('poster', false, `缺 ${path.basename(poster)}`);
  }

  return { file, issues, metrics: m, why };
}

const args = process.argv.slice(2);
let files;
if (args[0] === '--dir') {
  const dir = args[1];
  files = fs.readdirSync(dir).filter((f) => f.endsWith('.gif')).map((f) => path.join(dir, f));
} else {
  files = args.filter((a) => !a.startsWith('--'));
}

if (!files.length) {
  console.error('用法: node record/tools/verify_gif.mjs <gif...> | --dir <素材目录>');
  process.exit(2);
}

let bad = 0;
for (const f of files.sort()) {
  const r = check(f);
  const name = path.basename(f);
  if (r.issues.length) {
    bad++;
    console.log(`FAIL  ${name}`);
    for (const i of r.issues) console.log(`        - ${i}`);
  } else {
    const m = r.metrics;
    const tag = r.why ? `  [例外: ${r.why}]` : '';
    console.log(`PASS  ${name}  ${m.width}×${m.height} ${m.frames}f ${m.duration.toFixed(1)}s ${m.sizeMB.toFixed(2)}MB${tag}`);
  }
}

console.log(`\n${files.length - bad}/${files.length} 通过${bad ? `，${bad} 个不合格` : ''}`);
process.exit(bad ? 1 : 0);
