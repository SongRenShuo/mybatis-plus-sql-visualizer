#!/usr/bin/env node
// 内容发布用 CDP 小工具（Edge --remote-debugging-port=9222）
// 用法见 SKILL.md §3.0；所有子命令都接受 tabId 或用 URL 片段定位标签。
//
//   node cdp.js tabs
//   node cdp.js goto <url> [waitMs]              -> 打印 tabId
//   node cdp.js eval <tabRef> <snippetFile>      -> 在页面里求值（表达式或 IIFE）
//   node cdp.js shot <tabRef> <out.png>
//   node cdp.js click <tabRef> <x> <y> [shift|ctrl]
//   node cdp.js key <tabRef> <Key> [ctrl|shift|ctrlshift]
//   node cdp.js paste <tabRef>                   -> Ctrl+V（配合 clip 上传掘金图片）
//   node cdp.js file <tabRef> <cssSelector> <localPath> [--last]
//   node cdp.js clip <localPath>                 -> Windows 剪贴板放文件
//   node cdp.js text <tabRef> <literal>          -> Input.insertText（中文安全）

const fs = require('fs');
const http = require('http');
const { execSync } = require('child_process');

const PORT = Number(process.env.CDP_PORT || 9222);
const sleep = ms => new Promise(r => setTimeout(r, ms));

function httpJSON(path, method = 'GET') {
  return new Promise((res, rej) => {
    const q = http.request({ host: '127.0.0.1', port: PORT, path, method }, r => {
      let b = ''; r.on('data', d => b += d);
      r.on('end', () => { try { res(JSON.parse(b)); } catch (e) { res(b); } });
    });
    q.on('error', rej); q.end();
  });
}

class CDP {
  constructor(url) { this.url = url; this.id = 0; this.pending = new Map(); this.evs = []; }
  async open() {
    this.ws = new WebSocket(this.url);
    await new Promise((res, rej) => { this.ws.onopen = res; this.ws.onerror = rej; });
    this.ws.onmessage = e => {
      const m = JSON.parse(e.data);
      if (m.id && this.pending.has(m.id)) {
        const { res, rej } = this.pending.get(m.id); this.pending.delete(m.id);
        m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result);
      } else this.evs.push(m);
    };
    return this;
  }
  send(method, params = {}, timeout = 30000) {
    const id = ++this.id;
    return new Promise((res, rej) => {
      const t = setTimeout(() => { this.pending.delete(id); rej(new Error('timeout ' + method)); }, timeout);
      this.pending.set(id, { res: v => { clearTimeout(t); res(v); }, rej: e => { clearTimeout(t); rej(e); } });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
  async eval(expr, timeout = 30000) {
    const r = await this.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true }, timeout);
    if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails).slice(0, 400));
    return r.result.value;
  }
  close() { try { this.ws.close(); } catch (e) {} }
}

async function pick(ref) {
  const ts = (await httpJSON('/json/list')).filter(t => t.type === 'page');
  const t = ts.find(x => x.id === ref) || ts.find(x => x.url.includes(ref));
  if (!t) throw new Error('找不到标签: ' + ref + '\n可用: ' + ts.map(x => x.id.slice(0, 8) + ' ' + x.url.slice(0, 60)).join('\n'));
  return t;
}

async function withTab(ref, fn) {
  const t = await pick(ref);
  const c = new CDP(t.webSocketDebuggerUrl);
  await c.open();
  await c.send('Page.enable');
  try { return await fn(c, t); } finally { c.close(); }
}

const MOD = { none: 0, shift: 8, ctrl: 2, ctrlshift: 10, alt: 1 };
const KEYCODE = { Enter: 13, Backspace: 8, Delete: 46, Home: 36, End: 35, Escape: 27, ArrowDown: 40, ArrowUp: 38, Tab: 9 };

async function pressKey(c, key, mod) {
  const m = MOD[mod] || 0;
  const code = KEYCODE[key] || (key.length === 1 ? key.toUpperCase().charCodeAt(0) : 0);
  const codeName = key.length === 1 ? 'Key' + key.toUpperCase() : key;
  const ev = { key, code: codeName, windowsVirtualKeyCode: code, nativeVirtualKeyCode: code };
  await c.send('Input.dispatchKeyEvent', Object.assign({ type: 'rawKeyDown', modifiers: m }, ev)).catch(() => {});
  if (key === 'Enter') {
    await c.send('Input.dispatchKeyEvent', Object.assign({ type: 'char', modifiers: m, text: '\r' }, ev)).catch(() => {});
  }
  await c.send('Input.dispatchKeyEvent', Object.assign({ type: 'keyUp', modifiers: m }, ev)).catch(() => {});
}

(async () => {
  const [cmd, ...rest] = process.argv.slice(2);
  try {
    switch (cmd) {
      case 'tabs': {
        const ts = (await httpJSON('/json/list')).filter(t => t.type === 'page');
        ts.forEach(t => console.log(t.id + '  ' + t.url.slice(0, 90)));
        break;
      }
      case 'goto': {
        const [url, waitMs = '10000'] = rest;
        const t = await httpJSON('/json/new?' + encodeURIComponent(url), 'PUT');
        console.log(t.id);
        await sleep(Number(waitMs));
        break;
      }
      case 'eval': {
        const [ref, file] = rest;
        const expr = fs.readFileSync(file, 'utf8');
        const v = await withTab(ref, c => c.eval(expr, 40000));
        console.log(typeof v === 'string' ? v : JSON.stringify(v, null, 1));
        break;
      }
      case 'shot': {
        const [ref, out] = rest;
        await withTab(ref, async c => {
          const r = await c.send('Page.captureScreenshot', { format: 'png' }, 60000);
          fs.writeFileSync(out, Buffer.from(r.data, 'base64'));
        });
        console.log('ok ' + out);
        break;
      }
      case 'click': {
        const [ref, x, y, mod = 'none'] = rest;
        await withTab(ref, async c => {
          for (const type of ['mouseMoved', 'mousePressed', 'mouseReleased']) {
            await c.send('Input.dispatchMouseEvent', { type, x: Number(x), y: Number(y), button: 'left', clickCount: 1, modifiers: MOD[mod] || 0 });
            await sleep(90);
          }
        });
        break;
      }
      case 'key': {
        const [ref, key, mod = 'none'] = rest;
        await withTab(ref, c => pressKey(c, key, mod));
        break;
      }
      case 'paste': {
        const [ref] = rest;
        await withTab(ref, async c => {
          await c.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', modifiers: 2, key: 'v', code: 'KeyV', windowsVirtualKeyCode: 86, nativeVirtualKeyCode: 86 }).catch(() => {});
          await c.send('Input.dispatchKeyEvent', { type: 'keyUp', modifiers: 2, key: 'v', code: 'KeyV', windowsVirtualKeyCode: 86, nativeVirtualKeyCode: 86 }).catch(() => {});
        });
        break;
      }
      case 'file': {
        const ref = rest[0];
        const sel = rest[1];
        const file = rest[2];
        const last = rest.includes('--last');
        await withTab(ref, async c => {
          await c.send('DOM.enable');
          const doc = await c.send('DOM.getDocument', { depth: -1 });
          const q = await c.send('DOM.querySelectorAll', { nodeId: doc.root.nodeId, selector: sel });
          if (!q.nodeIds.length) throw new Error('没匹配到 file input: ' + sel);
          const nodeId = last ? q.nodeIds[q.nodeIds.length - 1] : q.nodeIds[0];
          await c.send('DOM.setFileInputFiles', { nodeId, files: [file] }, 30000);
        });
        console.log('ok file -> ' + sel);
        break;
      }
      case 'clip': {
        const [file] = rest;
        execSync(`powershell -NoProfile -Command "Set-Clipboard -Path '${file.replace(/'/g, "''")}'"`, { stdio: 'inherit' });
        console.log('ok clipboard <- ' + file);
        break;
      }
      case 'text': {
        const [ref, ...words] = rest;
        await withTab(ref, c => c.send('Input.insertText', { text: words.join(' ') }));
        break;
      }
      default:
        console.log('用法: node cdp.js <tabs|goto|eval|shot|click|key|paste|file|clip|text> ...');
        process.exit(1);
    }
  } catch (e) {
    console.error('ERR ' + e.message);
    process.exit(1);
  }
  process.exit(0);
})();
