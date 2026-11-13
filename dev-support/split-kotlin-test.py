#!/usr/bin/env python3
"""把巨型 Kotlin 测试/源文件按成员**逐字符搬运**拆成多个类。

T-M53-II（巨型文件拆分第二轮）配套工具。设计要点：
  - 只做「搬运」不做「改写」：成员块按原文行捕获，必要时原样复制；
  - 搬完做非空行多重集对账，**丢一行即报错且不写盘**；
  - 不碰 git（提交由主控统一做）。

成员块 = 前置注释/空行 + 声明 + 方法体，即 [本成员起点, 下一成员起点)。

用法：
  python dev-support/split-kotlin-test.py --inspect <file>
  python dev-support/split-kotlin-test.py --apply <file> \
      --new-file <newPath> --new-class <NewClass> --move 12,13 --dup 60 --doc "说明"
"""
import argparse
import collections
import re

_MODS = (r'(private|internal|protected|public|open|abstract|sealed|data|enum|annotation|'
         r'inner|override|lateinit|const|external|suspend|inline|tailrec|operator|infix|vararg|value|'
         r'companion)')
DECL_RE = re.compile(
    r'^ {4}(@|(' + _MODS + r'\s+)*(class|object|interface|fun|val|var|typealias)\b)'
)
COMMENT_ONLY = re.compile(r'^\s*(//|/\*|\*|\*/)')


def read_lines(path):
    text = open(path, 'rb').read().decode('utf-8')
    nl = '\r\n' if '\r\n' in text else '\n'
    return text.split(nl), nl, text.endswith(nl)


def find_class(lines):
    for i, l in enumerate(lines):
        if re.match(r'^\s*(internal\s+)?(class|object)\s', l) and l.rstrip().endswith('{'):
            return i
    raise SystemExit('找不到以 { 结尾的类声明行')


def find_class_end(lines):
    for i in range(len(lines) - 1, -1, -1):
        if lines[i].strip():
            if lines[i].strip() != '}':
                raise SystemExit(f'文件最后非空行不是类收尾 }}: {lines[i]!r}')
            return i
    raise SystemExit('空文件')


def member_decls(lines, ci, ce):
    raw = [i for i in range(ci + 1, ce) if DECL_RE.match(lines[i])]
    # 把「只有注解」的声明并入紧随其后的声明（@Test / @Before 等贴着 fun 时算同一成员）
    out = []
    for k, d in enumerate(raw):
        if lines[d].strip().startswith('@') and k + 1 < len(raw) and raw[k + 1] - d <= 3:
            continue
        out.append(d)
    return out


def is_trivia(l):
    return l.strip() == '' or bool(COMMENT_ONLY.match(l)) or bool(re.match(r'^\s*@', l))


def block_starts(lines, decls):
    starts = []
    for d in decls:
        s = d
        while s - 1 > 0 and is_trivia(lines[s - 1]):
            s -= 1
        starts.append(s)
    for k in range(1, len(starts)):
        if starts[k] < starts[k - 1]:
            starts[k] = starts[k - 1]
    return starts


def kind_of(l):
    t = l.strip()
    if t.startswith('@'): return 'ann'
    if re.match(r'^(private |internal |protected |public |override )*fun ', t): return 'fun'
    if re.match(r'^(private |internal |protected )?(val|var|lateinit)', t) or 'companion' in t: return 'field'
    return 'other'


def name_of(lines, d):
    for j in range(d, min(d + 6, len(lines))):
        m = re.search(r'\bfun\s+`?([^`(\s]+)`?', lines[j])
        if m: return m.group(1)
        m = re.search(r'\b(val|var)\s+([A-Za-z_]\w*)', lines[j])
        if m: return m.group(2)
    return lines[d].strip()[:40]


def struct(path):
    lines, nl, tr = read_lines(path)
    ci, ce = find_class(lines), find_class_end(lines)
    decls = member_decls(lines, ci, ce)
    starts = block_starts(lines, decls)
    blocks = []
    for k, d in enumerate(decls):
        e = starts[k + 1] if k + 1 < len(decls) else ce
        blocks.append((starts[k], e))
    return lines, nl, tr, ci, ce, decls, blocks


TOP_RE = re.compile(
    r'^(internal |private |abstract |open |sealed |data |enum )*'
    r'(class|object|interface|fun|val|var|typealias) '
)


def toplevel_decls(lines):
    return [i for i, l in enumerate(lines) if TOP_RE.match(l)]


def file_header_end(lines):
    """package/import 块的最后一行索引（含）。
    注意：import 之间可能夹注释行（实测 SqlLogPanel.kt 的 import 块被一行 `//` 注释打断），
    所以只认「最后一个以 package/import 开头的行」，不要遇到注释就停。"""
    last = 0
    for i, l in enumerate(lines):
        if l.startswith('package ') or l.startswith('import '):
            last = i
    return last


def top_span_lo(lines, d):
    """顶层声明的块起点：连同其**前导注解**与**紧邻注释块**（不跨空行，
    以免吃掉文件头 KDoc）。漏掉注解会留下孤立 `@Service(...)` 致语法错误。"""
    s = d
    while s - 1 >= 0 and lines[s - 1].lstrip().startswith('@'):
        s -= 1
    while s - 1 >= 0 and COMMENT_ONLY.match(lines[s - 1]):
        s -= 1
    return s


def list_toplevel(path):
    lines, nl, tr = read_lines(path)
    decls = toplevel_decls(lines)
    print(f'file={path}  ({len(lines)} 行)  顶层声明 {len(decls)} 个')
    for k, d in enumerate(decls):
        end = decls[k + 1] if k + 1 < len(decls) else len(lines)
        print(f'  [{k:3d}] {d + 1:5d}..{end:5d} ({end - d:5d}行) {lines[d][:80]}')


def extract_toplevel(path, new_file, lo, hi, doc):
    lines, nl, tr = read_lines(path)
    decls = toplevel_decls(lines)
    if not (0 <= lo <= hi < len(decls)):
        raise SystemExit('顶层声明序号越界')
    span_lo = top_span_lo(lines, decls[lo])
    span_hi = top_span_lo(lines, decls[hi + 1]) if hi + 1 < len(decls) else len(lines)
    moved = lines[span_lo:span_hi]
    header = lines[:file_header_end(lines) + 1]

    new_lines = list(header) + [''] + ['// ' + doc,
                                      '// （T-M53-II 2026-10-10：由 %s 原样搬出，同包同可见性，零行为改动。）'
                                      % path.replace('\\', '/').rsplit('/', 1)[-1]] + moved
    while new_lines and new_lines[-1].strip() == '':
        new_lines.pop()
    new_lines += ['']

    orig_lines = lines[:span_lo] + lines[span_hi:]
    while orig_lines and orig_lines[-1].strip() == '':
        orig_lines.pop()
    orig_lines += ['']

    def mset(ls): return collections.Counter(x for x in ls if x.strip())
    after = mset(orig_lines) + mset(new_lines)
    before = mset(lines)
    missing = before - after
    if missing:
        print('!! 有内容丢失：')
        for k, v in list(missing.items())[:5]:
            print('   ', v, repr(k[:80]))
        raise SystemExit('对账失败，未写盘')
    extra = after - before
    print('对账通过（无丢失行）。新增行：')
    for k, v in list(extra.items())[:10]:
        print('   ', v, repr(k[:90]))

    def write(p, ls):
        open(p, 'w', encoding='utf-8', newline='').write(nl.join(ls) + (nl if tr else ''))
    write(path, orig_lines)
    write(new_file, new_lines)
    print(f'写出 {path} ({len(orig_lines)} 行) + {new_file} ({len(new_lines)} 行)')


def kdoc_start(lines, ci):
    """类声明前的 KDoc/** 起点（无 KDoc 则返回 ci）——新类不该继承原类的类级说明。"""
    j = ci - 1
    while j >= 0 and lines[j].strip() == '':
        j -= 1
    if j >= 0 and lines[j].strip().endswith('*/'):
        k = j
        while k >= 0 and not lines[k].strip().startswith('/**'):
            k -= 1
        if k >= 0:
            return k
    return ci


def supertype_of(lines, ci):
    """类声明里类名之后、`{` 之前的部分（如 ` : BasePlatformTestCase()`）。
    新类必须沿用父类型——JUnit3 的 BasePlatformTestCase 子类丢了它就连 myFixture/project 都不可见。"""
    m = re.match(r'^\s*(internal\s+)?(class|object)\s+[A-Za-z_]\w*\s*(.*?)\s*\{\s*$',
                 lines[ci])
    return m.group(3) if m else ''


def inspect(path):
    lines, nl, tr, ci, ce, decls, blocks = struct(path)
    print(f'file={path}  ({len(lines)} 行, nl={"CRLF" if nl == chr(13)+chr(10) else "LF"})')
    print(f'class_decl_line={ci + 1}  {lines[ci].strip()}')
    print(f'class_end_line={ce + 1}   head=1..{ci + 1}   members={len(decls)}')
    for k, d in enumerate(decls):
        s, e = blocks[k]
        print(f'  [{k:3d}] {s + 1:5d}..{e:5d} ({e - s:4d}行) {kind_of(lines[d]):5s} {name_of(lines, d)}')


def apply_split(path, new_file, new_class, move, dup, doc):
    lines, nl, tr, ci, ce, decls, blocks = struct(path)
    n = len(decls)
    for k in move + dup:
        if not 0 <= k < n:
            raise SystemExit(f'成员序号越界 {k}（0..{n - 1}）')
    blk = lambda k: lines[blocks[k][0]:blocks[k][1]]
    moved = [lines[blocks[k][0]:blocks[k][1]] for k in move]
    dupd = [lines[blocks[k][0]:blocks[k][1]] for k in dup]
    keep = [k for k in range(n) if k not in set(move)]

    new_lines = list(lines[:kdoc_start(lines, ci)]) + ['/**', f' * {doc}',
                                                       ' * （T-M53-II 2026-10-10：逐字符原样搬运，零逻辑改动。）', ' */',
                                                       f'class {new_class}{supertype_of(lines, ci)} {{']
    for b in moved + dupd:
        new_lines += b
    while new_lines and new_lines[-1].strip() == '':
        new_lines.pop()
    new_lines += ['}']

    orig_lines = list(lines[:ci + 1])
    for k in keep:
        orig_lines += blk(k)
    while orig_lines and orig_lines[-1].strip() == '':
        orig_lines.pop()
    orig_lines += ['}']

    def mset(ls): return collections.Counter(x for x in ls if x.strip())
    after = mset(orig_lines) + mset(new_lines) - mset([x for b in dupd for x in b])
    before = mset(lines)
    missing = before - after
    if missing:
        print('!! 有内容丢失：')
        for k, v in list(missing.items())[:5]:
            print('   ', v, repr(k[:80]))
        raise SystemExit('对账失败，未写盘')
    extra = after - before
    print('对账通过（无丢失行）。新增行（应只有新类 KDoc/声明/收尾）：')
    for k, v in list(extra.items())[:10]:
        print('   ', v, repr(k[:90]))

    def write(p, ls):
        open(p, 'w', encoding='utf-8', newline='').write(nl.join(ls) + (nl if tr else ''))
    write(path, orig_lines)
    write(new_file, new_lines)
    print(f'写出 {path} ({len(orig_lines)} 行) + {new_file} ({len(new_lines)} 行)')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--inspect')
    ap.add_argument('--apply')
    ap.add_argument('--new-file')
    ap.add_argument('--new-class')
    ap.add_argument('--move', default='')
    ap.add_argument('--dup', default='')
    ap.add_argument('--doc', default='拆分而来')
    ap.add_argument('--list-toplevel')
    ap.add_argument('--extract-toplevel')
    ap.add_argument('--range', default='', help='顶层声明区间，如 1,12')
    a = ap.parse_args()
    idx = lambda s: [int(x) for x in s.split(',') if x.strip()]
    if a.list_toplevel:
        list_toplevel(a.list_toplevel)
    elif a.extract_toplevel:
        if not a.new_file or not a.range:
            ap.error('--extract-toplevel 需要 --new-file 与 --range')
        lo, hi = idx(a.range)
        extract_toplevel(a.extract_toplevel, a.new_file, lo, hi, a.doc)
    elif a.inspect:
        inspect(a.inspect)
    elif a.apply:
        apply_split(a.apply, a.new_file, a.new_class, idx(a.move), idx(a.dup), a.doc)
    else:
        ap.error('需要 --inspect 或 --apply')


if __name__ == '__main__':
    main()
