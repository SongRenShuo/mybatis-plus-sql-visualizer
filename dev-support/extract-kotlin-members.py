#!/usr/bin/env python3
"""把 Kotlin 类的一组内聚成员抽成**同包扩展函数**，从而把巨型单类文件拆小。

T-M53-II（巨型文件拆分第二轮）D2 用。为什么用扩展函数：
  扩展函数体内的 `field` / `other()` 会隐式解析到接收者，**方法体一字不用改**，
  原类内的调用点也不变（成员被搬走后自动落到同包扩展）。唯一代价是：被触及的
  `private` 成员必须放宽为 `internal`（脚本自动扫描并放宽）。

用法：
  python dev-support/extract-kotlin-members.py --inspect <file> [--class <名>]
  python dev-support/extract-kotlin-members.py --apply <file> --class <名> \
      --members a,b,c --new-file <新文件> --doc "一句话说明"

成员按「序号」指定（--inspect 输出）。只支持搬 `fun`（扩展函数不能承载属性）。
"""
import argparse
import re
import sys

_MODS = (r'(private|internal|protected|public|override|open|abstract|inline|suspend|operator|'
         r'tailrec|vararg|const|lateinit|inner|data|sealed|enum|annotation|external|value|@)')
DECL = re.compile(r'^ {4}(' + _MODS + r'\s*)*(fun|val|var|class|object|interface|companion|init)\b')


def read(path):
    text = open(path, 'rb').read().decode('utf-8')
    nl = '\r\n' if '\r\n' in text else '\n'
    return text.split(nl), nl, text.endswith(nl)


def _aware_delta(text):
    """字符串/注释感知的花括号净差（Kotlin 简版词法：行注释、块注释、三引号串、
    普通串（含 `${...}` 内的花括号一并视作串体，两侧同进同出故不影响净差）、字符字面量）。"""
    n = len(text)
    esc = chr(92)
    d = 0
    i = 0
    while i < n:
        c = text[i]
        if c == '/' and i + 1 < n and text[i + 1] == '/':
            j = text.find('\n', i)
            i = n if j < 0 else j
            continue
        if c == '/' and i + 1 < n and text[i + 1] == '*':
            j = text.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            i = n if j < 0 else j + 3
            continue
        if c == '"' or c == "'":
            j = i + 1
            while j < n:
                if text[j] == esc:
                    j += 2
                    continue
                if text[j] == c or text[j] == '\n':
                    break
                j += 1
            i = j + 1
            continue
        if c == '{':
            d += 1
        elif c == '}':
            d -= 1
        i += 1
    return d


def companion_member_names(lines, cs, ce):
    """类体内 `companion object` 声明的成员名（属性/函数；含 `const val`）。

    companion 与其成员都是「缩进 > 0 的行」，故按缩进找 companion 声明行，再扫到类体结束即可——
    本仓 companion 恒为类体最后一块（`}` 顶格收尾），无需精确配对。
    """
    start = next((i for i in range(cs, ce) if re.match(r'^ +companion object\b', lines[i])), None)
    if start is None:
        return []
    names = []
    for i in range(start, ce):
        for m in re.finditer(r'\b(?:const\s+val|val|var|fun)\s+([A-Za-z_]\w*)', lines[i]):
            names.append(m.group(1))
    return names


def _top_level_close(lines, cs):
    """类体结束 = `cs` 之后第一条**顶格** `}`（Kotlin 顶层 class/object 的闭合括号）。

    为什么不用朴素花括号计数：字符串模板 `${}`、KDoc 正文里的 `${}`、原始字符串里的花括号
    都会让计数偏移（`SqlPreviewPanel.kt` 朴素净差 = +1 ⇒ 误报「类体未闭合」）。
    类体内所有真实括号都有缩进，故「顶格 }」是可靠判据；本仓顶层文件均满足该形态。
    """
    for i in range(cs + 1, len(lines)):
        if lines[i].rstrip() == '}':
            return i
    raise SystemExit('类体未闭合（未找到顶格 }）')


def class_span(lines, cls):
    ci = next(i for i, l in enumerate(lines)
              if re.match(r'^[\w ]*(?:class|object)\s+' + re.escape(cls) + r'\b', l))
    cs = next(i for i in range(ci, len(lines)) if '{' in lines[i])
    return cs, _top_level_close(lines, cs)


def members(lines, cs, ce):
    out = []
    for i in range(cs + 1, ce):
        if DECL.match(lines[i]):
            out.append(i)
    rows = []
    for k, dd in enumerate(out):
        e = out[k + 1] if k + 1 < len(out) else ce
        nm = None
        for j in range(dd, min(e, dd + 6)):
            m = re.search(r'\bfun\s+`([^`]+)`|\bfun\s+([A-Za-z_]\w*)', lines[j])
            if m:
                nm = m.group(1) or m.group(2)
                break
        if nm is None:
            for j in range(dd, min(e, dd + 6)):
                m = re.search(r'\b(?:val|var|class|object|interface)\s+([A-Za-z_]\w*)', lines[j])
                if m:
                    nm = m.group(1)
                    break
        rows.append({'k': k, 'lo': dd, 'hi': e, 'name': nm or '?', 'line': dd + 1,
                     'size': e - dd, 'decl': lines[dd].strip()})
    return rows


def inspect(path, cls):
    lines, nl, tr = read(path)
    cs, ce = class_span(lines, cls)
    print(f'file={path}  ({len(lines)} 行)  class={cls}  类体 {cs + 1}..{ce + 1} ({ce - cs + 1} 行)')
    for r in sorted(members(lines, cs, ce), key=lambda x: -x['size'])[:40]:
        print(f"  [{r['k']:3d}] {r['line']:5d} {r['size']:5d}行  {r['name'][:38]:40s} {r['decl'][:46]}")


def apply(path, cls, idxs, new_file, doc):
    lines, nl, tr = read(path)
    cs, ce = class_span(lines, cls)
    rows = {r['k']: r for r in members(lines, cs, ce)}
    picked = [rows[i] for i in idxs]
    # 安全闸（红线：拆分不得影响既有功能）——整个类体与被搬走的每个成员块都必须括号自洽，
    # 否则说明边界切在了成员中间（方法体被截断/悬空注解），直接拒绝而不是产出破损文件。
    cls_delta = _aware_delta('\n'.join(lines[cs:ce + 1]))
    if cls_delta != 0:
        raise SystemExit(f'类体括号不自洽（净差 {cls_delta}）——拒绝拆分，先人工核对边界')
    for r in picked:
        bd = _aware_delta('\n'.join(lines[r['lo']:r['hi']]))
        if bd != 0:
            raise SystemExit(f"成员 {r['name']}（第 {r['line']} 行）块内括号净差 {bd}——拒绝拆分")
    body_text = '\n'.join('\n'.join(lines[r['lo']:r['hi']]) for r in picked)
    # companion 成员名必须先取（后面会从 lines 里删掉被搬成员，ce 会越过新长度）
    comp_names = companion_member_names(lines, cs, ce)
    # 原类私有成员里，被搬走代码引用到的 → 放宽为 internal
    priv = []
    for r in members(lines, cs, ce):
        if r['name'] in ('?',) or r['k'] in idxs:
            continue
        if not r['decl'].startswith(('private ', 'protected ')):
            continue
        if re.search(r'\b' + re.escape(r['name']) + r'\b', body_text):
            priv.append(r)
    for r in priv:
        src = lines[r['lo']]
        lines[r['lo']] = re.sub(r'^( {4})(private|protected) ', r'\1internal ', src)
    # companion object 等更深缩进的私有成员：按名全文件找声明行放宽
    for i in range(cs, ce):
        if not re.match(r'^ +(private|protected) ', lines[i]):
            continue
        m = re.search(r'\b(?:fun|val|var|const val|class|object)\s+([A-Za-z_]\w*)', lines[i])
        if m and re.search(r'\b' + re.escape(m.group(1)) + r'\b', body_text):
            lines[i] = re.sub(r'^( +)(private|protected) ', r'\1internal ', lines[i])
            priv.append({'name': m.group(1) + '（深层/companion）'})
    # 生成扩展文件
    ext = []
    for r in picked:
        blk = list(lines[r['lo']:r['hi']])
        for i, l in enumerate(blk):
            m = re.match(r'^ {4}(?:(?:private|internal|protected|public|override|open)\s+)*fun\s+', l)
            if m:
                rest = l[m.end():]
                blk[i] = f'internal fun {cls}.' + rest
                for j in range(i + 1, len(blk)):
                    if blk[j].startswith('    '):
                        blk[j] = blk[j][4:]
                break
        ext += blk + ['']
    # 从原文件删除（倒序）
    for r in sorted(picked, key=lambda x: -x['lo']):
        del lines[r['lo']:r['hi']]
    last_imp = max(i for i, l in enumerate(lines) if l.startswith('import '))
    # 头 = package 行 + **被搬代码真正引用到的** import。为什么按引用裁剪：原文件是全仓最大的面板
    # （130+ import），整块照抄会让每个拆出的文件背上上百条无用 import（拆分反而把信噪比拉低）。
    moved_text = '\n'.join(ext)
    head = [l for l in lines[:last_imp + 1] if l.startswith('package')] + ['']
    for l in lines[:last_imp + 1]:
        if not l.startswith('import '):
            continue
        m = re.match(r'import\s+([\w.]+)(?:\s+as\s+(\w+))?', l)
        if not m:
            continue
        if m.group(1).endswith('.*'):
            head.append(l)
            continue
        simple = m.group(2) or m.group(1).rsplit('.', 1)[-1]
        if re.search(r'\b' + re.escape(simple) + r'\b', moved_text):
            head.append(l)
    # companion 成员：Kotlin 规则下扩展函数**看不见**接收者类的 companion，被搬代码里引用到谁
    # 就得显式 `import <pkg>.<cls>.Companion.<名>`（否则一串 Unresolved reference，人工补易漏）。
    pkg = next((l.split()[1] for l in lines[:last_imp + 1] if l.startswith('package ')), '')
    imported = {l.split()[-1] for l in head if l.startswith('import ')}
    for name in comp_names:
        if name in imported:
            continue
        if re.search(r'\b' + re.escape(name) + r'\b', moved_text):
            head.append(f'import {pkg}.{cls}.Companion.{name}')
    tail_text = (['', f'// T-M53-II（2026-10-10）：以下为 {cls} 的「{doc}」内聚成员，',
                  '// 以同包扩展函数形式搬出——方法体逐字符搬运，被触及的私有成员已按需放宽为 internal。', ''] + ext)
    while tail_text and tail_text[-1].strip() == '':
        tail_text.pop()
    open(path, 'w', encoding='utf-8', newline='').write(nl.join(lines) + nl)
    open(new_file, 'w', encoding='utf-8', newline='').write(nl.join(head + tail_text) + nl)
    print('搬出成员:', [r['name'] for r in picked])
    print('放宽为 internal 的私有成员:', [r['name'] for r in priv])
    print(f'原文件 {len(lines)} 行 | 新文件 {len(head + tail_text)} 行')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--inspect'); ap.add_argument('--apply')
    ap.add_argument('--class', dest='cls', required=True, help='目标类名')
    ap.add_argument('--members', default='')
    ap.add_argument('--new-file'); ap.add_argument('--doc', default='内聚成员')
    a = ap.parse_args()
    if a.inspect:
        inspect(a.inspect, a.cls)
    elif a.apply:
        idxs = [int(x) for x in a.members.split(',') if x.strip()]
        if not idxs or not a.new_file:
            ap.error('--apply 需要 --members 与 --new-file')
        apply(a.apply, a.cls, idxs, a.new_file, a.doc)
    else:
        ap.error('需要 --inspect 或 --apply')


if __name__ == '__main__':
    main()
