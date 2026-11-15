#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""changeNotes 源码级判据（秒级、零 Gradle、零产物）。

替代发行前本地那两次 gradle 调用里的静态部分：
  ./gradlew test --tests ...ChangeNotesRatingLineGuardTest   （约 3m35s，冷配置缓存）
  python dev-support/inspect-plugin-zip.py --version ...     （全角 / 首个 h2 / 本版块条目）

与产物级检查的关系（不可互相替代）：
  - 本脚本证明「gradle/plugin-change-notes.html 写对了」，与 patchPluginXml 注入进
    plugin.xml 的 <change-notes> 内容同源（mpv-release.gradle.kts:23-26，asBytes +
    UTF-8 + trim，原样包进 CDATA）。
  - inspect-plugin-zip.py 证明「这份内容真的编进了 zip，且版本号三方一致」。
    它仍须跑在产物上；本仓把它接进 CI build job 的 :buildPlugin 之后，查的是
    真正要发布的那份字节，比原先查本地包更强。
  - Kotlin 守卫 ChangeNotesRatingLineGuardTest 保留，仍由 CI testFast 腿跑。
    本脚本是本地 tag 前的等价预检，不是从 CI 测试集里删类。

判据清单（C1~C9 见 check() 内逐条注释）：
  C1 评分引导句全文恰好 1 次          ← ChangeNotesRatingLineGuardTest:49-57
  C2 评分句落在首个 </ul> 之前        ← :59-68
  C3 剥 <code> 后无裸 @+ASCII 字母    ← :70-84 / LESSONS L27
  C4 首个 <h2> == v{version}          ← inspect-plugin-zip.py:124-126
  C5 本版块 <li> 数 > 0              ← :127-132
  C6 本版块 <li> 扫「（ ） ：」零命中  ← :131-136 / LESSONS L25
  C7 html 本版 <li> 与 preview.update.json 的 changeNotes 逐条一致
                （原先只有文档约定，见 T-ReleaseChecklist:30；此处补上机器判据）
  C8 preview.update.json 的 version / downloadUrl 与本版一致
  C9 内测版（version 含 -beta）时 html 中 beta 标题块不超过 1 个
  另加 CDATA 安全（html 不得含 ]]>）与评分句 ≤60 字。

反向验证（S2 / L2）：`--self-test` 用内置合成样本，对每条判据各造一个违规样本
断言为红、一个合规样本断言为绿，并打印两次读数。仓内 0 违规不算证据。

用法:
  python -I dev-support/check-change-notes.py                  # 版本从 gradle.properties 读
  python -I dev-support/check-change-notes.py --version 2026.11.15-beta.1
  python -I dev-support/check-change-notes.py --self-test
  python -I dev-support/check-change-notes.py --print-version  # 供 CI 传给 inspect-plugin-zip.py

退出码: 0 = PASS / 1 = FAIL / 2 = 参数或文件错误。
"""
import argparse
import io
import json
import os
import re
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NOTES_REL = os.path.join('gradle', 'plugin-change-notes.html')
PREVIEW_REL = os.path.join('update', 'preview.update.json')
PROPERTIES_REL = 'gradle.properties'
RATING_LINE = '好用的话，欢迎到 Marketplace 页评分留言'
FULLWIDTH = ('（', '）', '：')
BAD_AT = re.compile(r'@(?=[A-Za-z])')
CODE_TAG = re.compile(r'<code>[\s\S]*?</code>')
ANY_TAG = re.compile(r'<[^>]+>')


def repo_file(rel):
    return os.path.join(REPO_ROOT, rel)


def read_text(rel):
    # 构建期显式 UTF-8（mpv-release.gradle.kts:18-20 的 GBK 乱码教训），绝不依赖 locale。
    with io.open(repo_file(rel), encoding='utf-8', newline='') as f:
        return f.read()


def read_version(explicit=None):
    if explicit:
        return explicit
    props = read_text(PROPERTIES_REL)
    m = re.search(r'(?m)^version=(.+)$', props)
    if not m:
        raise ValueError('gradle.properties 里没有 version=')
    return m.group(1).strip()


def strip_tags(text):
    return ANY_TAG.sub('', text)


def normalize_li(text):
    # 与 inspect-plugin-zip.py:131 同口径：折叠空白 + strip，标签一并剥掉。
    return re.sub(r'\s+', ' ', strip_tags(text)).strip()


def block_lis(html, version):
    m = re.search(r'<h2>v%s</h2>\s*<ul>(.*?)</ul>' % re.escape(version), html, re.S)
    if not m:
        return None
    return [normalize_li(x) for x in re.findall(r'<li>(.*?)</li>', m.group(1), re.S)]


def h2_titles(html):
    return re.findall(r'<h2>([^<]*)</h2>', html)


def reasons(html, preview_raw, version):
    """返回失败原因列表；空列表 = PASS。所有判据都在这里，self-test 复用同一入口。"""
    bad = []

    # C1 评分句全文恰好 1 次
    n = html.count(RATING_LINE)
    if n != 1:
        bad.append('C1 评分引导句全文应恰好 1 次，实测 %d 次' % n)

    # C2 评分句落在首个 </ul> 之前（等价 Kotlin 守卫的 firstBlock 口径）
    end = html.find('</ul>')
    first_block = html if end < 0 else html[:end]
    if RATING_LINE not in first_block:
        bad.append('C2 评分引导句不在最新块（首个 </ul> 之前）')

    # C3 剥 <code> 后无裸 @ + ASCII 字母；不做实体解码（KDoc :36 已证伪 &#64; 路线）
    stripped = CODE_TAG.sub('<code/>', html)
    hits = [m.start() for m in BAD_AT.finditer(stripped)]
    if hits:
        lines = [html.count('\n', 0, p) + 1 for p in hits]
        bad.append('C3 存在裸 @ + 字母（应包 <code>），行号 %s' % lines[:10])

    # C4 首个 <h2> == v{version}
    titles = h2_titles(html)
    want = 'v' + version
    if not titles or titles[0] != want:
        bad.append('C4 首个 <h2> 应为 %s，实测 %s' % (want, titles[:3]))

    # C5 本版块 <li> 数 > 0
    lis = block_lis(html, version)
    if not lis:
        bad.append('C5 找不到 <h2>v%s</h2> 块或块内没有 <li>' % version)

    # C6 本版块 <li> 扫「（ ） ：」零命中
    if lis:
        offenders = [li for li in lis if any(c in li for c in FULLWIDTH)]
        if offenders:
            bad.append('C6 本版块 <li> 含全角（ ） ：：%s' % offenders[:3])

    # C7 html 本版 <li> 与 preview.update.json 的 changeNotes 逐条一致
    #     约定：评分引导句是额外补的一行，不在更新清单里，比对时排除。
    try:
        preview = json.loads(preview_raw)
    except ValueError as e:
        bad.append('C7 preview.update.json 不是合法 JSON：%s' % e)
        preview = {}
    if lis and 'changeNotes' in preview:
        expected = [x for x in lis if x != RATING_LINE]
        actual = str(preview['changeNotes']).split('\n')
        if expected != actual:
            bad.append('C7 changeNotes 与 html 本版 <li> 不逐条一致（html %d 条 / json %d 条）'
                       % (len(expected), len(actual)))

    # C8 preview 的 version / downloadUrl 与本版一致
    if preview.get('version') != version:
        bad.append('C8 preview.update.json 的 version=%r，应为 %r'
                   % (preview.get('version'), version))
    url = str(preview.get('downloadUrl', ''))
    needle = '/releases/download/%s/mybatis-plus-sql-visualizer-%s.zip' % (version, version)
    if needle not in url:
        bad.append('C8 preview.update.json 的 downloadUrl 未指向本版 tag 路径')

    # C9 内测系列只保留最新一个 -beta.N 标题块
    if '-beta.' in version:
        beta_titles = [t for t in titles if '-beta.' in t]
        if len(beta_titles) > 1:
            bad.append('C9 内测系列应只保留最新一个 beta 块，实测 %s' % beta_titles)

    # 附加：CDATA 安全与评分句长度
    if ']]>' in html:
        bad.append('CDATA 安全：html 含 ]]>，会破坏 plugin.xml 的 <change-notes>')
    if len(RATING_LINE) > 60:
        bad.append('评分引导句超过 60 字')
    return bad


def check(html, preview_raw, version):
    bad = reasons(html, preview_raw, version)
    print('version : %s' % version)
    print('html    : %s' % NOTES_REL.replace('\\', '/'))
    print('preview : %s' % PREVIEW_REL.replace('\\', '/'))
    if bad:
        for b in bad:
            print('  [FAIL] %s' % b)
        print('\n结果: FAIL（%d 项未过）' % len(bad))
        return 1
    lis = block_lis(html, version)
    print('  [OK ] C1 评分引导句全文 1 次')
    print('  [OK ] C2 评分引导句落在最新块')
    print('  [OK ] C3 无裸 @ + 字母')
    print('  [OK ] C4 首个 <h2> = v%s' % version)
    print('  [OK ] C5 本版块 %d 条 <li>' % len(lis))
    print('  [OK ] C6 本版块扫「（ ） ：」零命中')
    print('  [OK ] C7 changeNotes 与 html 本版 <li> 逐条一致（%d 条，评分句除外）'
          % (len(lis) - 1))
    print('  [OK ] C8 preview version / downloadUrl 指向本版')
    print('  [OK ] C9 beta 块数量 / CDATA 安全 / 评分句长度')
    print('\n结果: PASS')
    return 0


def self_test():
    """S2 反向验证：每条判据各造一个违规样本断言红、合规样本断言绿，打印两次读数。"""
    ver = '2099.1.1-beta.1'
    lis = [
        '大工程里 Mapper 跳转图标改走自定义索引, 定位一次索引查询即可',
        '冷打开 mapper XML 时跳转图标不再闪没, 解析成功过一次就记住',
    ]
    good_html = ('<h2>v%s</h2>\n<ul>\n' % ver
                 + ''.join('    <li>%s</li>\n' % x for x in lis)
                 + '    <li>%s</li>\n' % RATING_LINE + '</ul>\n'
                 + '<h2>v2026.11.14</h2>\n<ul>\n    <li>历史条目</li>\n</ul>\n')
    good_preview = json.dumps({
        'version': ver,
        'downloadUrl': ('https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer'
                        '/releases/download/%s/mybatis-plus-sql-visualizer-%s.zip' % (ver, ver)),
        'changeNotes': '\n'.join(lis),
        'publishDate': '2099-01-01',
        'sinceBuild': '243',
    }, ensure_ascii=False)

    base = reasons(good_html, good_preview, ver)
    print('正对照（合规样本）:', 'PASS' if not base else 'FAIL %s' % base)
    if base:
        return 1

    # C2 样本：把评分句移到历史块（首块不再含它），全文仍恰好 1 次，避免 C1 抢先红。
    c2_html = good_html.replace('    <li>%s</li>\n' % RATING_LINE, '', 1)
    c2_html = c2_html.replace('<li>历史条目</li>', '<li>%s</li>' % RATING_LINE, 1)
    # C5 样本：本版块 <h2> 后直接 </ul>（真空块）；评分句放历史块维持 C1=1，
    # preview 同步清空避免 C7 抢红。C2 会连带红（评分句不在首块），属语义相连，
    # self-test 只要求目标判据 C5 出现在红因里。
    c5_html = ('<h2>v%s</h2>\n<ul>\n</ul>\n' % ver
               + '<h2>v2026.11.14</h2>\n<ul>\n    <li>%s</li>\n    <li>历史条目</li>\n</ul>\n' % RATING_LINE)
    c5_preview = json.dumps(dict(json.loads(good_preview), changeNotes=''), ensure_ascii=False)
    cases = [
        ('C1 评分句 2 次', good_html.replace('</ul>\n<h2>', '<li>%s</li>\n</ul>\n<h2>' % RATING_LINE, 1), good_preview),
        ('C2 评分句不在首块', c2_html, good_preview),
        ('C3 裸 @Query', good_html.replace('自定义索引', '<code>@Query</code> 之外还有裸 @Query'), good_preview),
        ('C4 首块版本不对', good_html.replace('<h2>v%s</h2>' % ver, '<h2>v2099.1.0</h2>', 1), good_preview),
        ('C5 本版块无 li', c5_html, c5_preview),
        ('C6 全角括号', good_html.replace('自定义索引', '自定义索引（第 15 种）'), good_preview),
        ('C7 清单漏一条', good_html, json.dumps(dict(json.loads(good_preview),
                                                  changeNotes=lis[0]), ensure_ascii=False)),
        ('C8 version 不符', good_html, json.dumps(dict(json.loads(good_preview),
                                                     version='2099.1.0'), ensure_ascii=False)),
        ('C9 两个 beta 块', good_html.replace('<h2>v2026.11.14</h2>',
                                           '<h2>v2099.1.1-beta.2</h2>', 1), good_preview),
    ]
    failed = []
    for name, html, preview in cases:
        r = reasons(html, preview, ver)
        tag = 'C' + name[1:name.find(' ')]
        hit = any(x.startswith(tag + ' ') or x.startswith(tag + '）') for x in r)
        print('  反向 %-22s -> %s' % (name, '恰红' if hit else '未捕获（空绿！）'))
        if not hit:
            failed.append(name)
    print('\n反向读数: %d/%d 条判据变红' % (len(cases) - len(failed), len(cases)))
    if failed:
        print('结果: FAIL（未捕获 %s）' % failed)
        return 1
    print('结果: PASS（正对照绿 + 9 条反向样本各恰红）')
    return 0


def main():
    ap = argparse.ArgumentParser(description='changeNotes 源码级判据（零 Gradle）')
    ap.add_argument('--version', help='版本号，缺省从 gradle.properties 读')
    ap.add_argument('--self-test', action='store_true', help='反向验证：合成违规样本必须变红')
    ap.add_argument('--print-version', action='store_true', help='只打印版本号，供 CI 传参')
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    try:
        version = read_version(args.version)
    except (OSError, ValueError) as e:
        print('ERROR: %s' % e, file=sys.stderr)
        return 2
    if args.print_version:
        print(version)
        return 0
    try:
        html = read_text(NOTES_REL)
        preview = read_text(PREVIEW_REL)
    except OSError as e:
        print('ERROR: %s' % e, file=sys.stderr)
        return 2
    return check(html, preview, version)


if __name__ == '__main__':
    sys.exit(main())
