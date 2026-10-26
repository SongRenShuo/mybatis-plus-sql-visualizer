#!/usr/bin/env python3
"""
把 Gitee 全部 Release 镜像到 GitHub（T-DualRepo 方案 C，2026-10-22）

做三件事，逐版本处理：
  1. POST /repos/{owner}/{repo}/releases  建 GitHub Release（name + body + prerelease + tag_name）
  2. POST /releases/{id}/assets?name=...  上传插件 zip（唯一需要上传的附件）
  3. GET  /releases/{id}                  回读断言：asset 名与 size 与本地文件一致

为什么不传另外两个附件：Gitee 每版有 3 个附件——插件 zip、源码 zip、源码 tar.gz。
后两个是 Gitee 按 tag 自动生成的源码归档，GitHub 在建 Release 时会自己生成同名归档，
上传反而重复。只传插件 zip。

幂等：已存在的 Release 会被跳过（不信本地状态，按 tag 查远端）；
      同 tag 已存在但缺附件时补传附件。可反复跑。

用法：
  python dev-support/mirror-releases-to-github.py --dry-run      # 只打印计划
  python dev-support/mirror-releases-to-github.py                # 执行
  python dev-support/mirror-releases-to-github.py --tag 2026.10.26   # 只处理指定版本
  python dev-support/mirror-releases-to-github.py --verify-only  # 只回读校验

依赖：
  - .local/github-token.json      GitHub PAT（字段 github_personal_access_token / owner / repo）
  - .local/gitee-releases.json    Gitee releases 原始 JSON（本脚本不自己拉，避免两套抓取逻辑）
  - .local/gh-release-assets/     待上传 zip 目录；也可回退读 build/distributions/
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)
LOCAL = os.path.join(REPO_ROOT, '.local')
ASSET_DIRS = [os.path.join(LOCAL, 'gh-release-assets'), os.path.join(REPO_ROOT, 'build', 'distributions')]

API = 'https://api.github.com'
# 上传附件是**另一个 host**：uploads.github.com。用 api.github.com 上传会得到 404
# （2026-10-22 实测：同一 token、同一 release id，换成 uploads. 前缀即 201）。
UPLOAD_API = 'https://uploads.github.com'
PROXY = 'http://127.0.0.1:7890'


def load_github():
    path = os.path.join(LOCAL, 'github-token.json')
    if not os.path.exists(path):
        sys.exit('缺少 %s' % path)
    cfg = json.load(open(path, encoding='utf-8'))
    return (cfg['github_personal_access_token'],
            cfg.get('owner', 'SongRenShuo'),
            cfg.get('repo', 'mybatis-plus-sql-visualizer'))


def load_gitee_releases():
    path = os.path.join(LOCAL, 'gitee-releases.json')
    if not os.path.exists(path):
        sys.exit('缺少 %s（先用 Gitee API 抓一份）' % path)
    return json.load(open(path, encoding='utf-8'))


def find_asset_file(tag):
    fn = 'mybatis-plus-sql-visualizer-%s.zip' % tag
    for d in ASSET_DIRS:
        p = os.path.join(d, fn)
        if os.path.exists(p):
            return p
    return None


def request(url, token, method='GET', data=None, headers=None, raw=False, timeout=180):
    hdrs = {
        'Authorization': 'Bearer %s' % token,
        'Accept': 'application/vnd.github+json',
        'User-Agent': 'mpv-dual-repo-mirror',
    }
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(url, data=data, method=method, headers=hdrs)

    # 代理优先、失败回退直连（与 push-all.ps1 同策略：本机代理会间歇性 000）
    last = None
    for opener in (urllib.request.build_opener(urllib.request.ProxyHandler({'https': PROXY, 'http': PROXY})),
                   urllib.request.build_opener(urllib.request.ProxyHandler({}))):
        try:
            with opener.open(req, timeout=timeout) as resp:
                body = resp.read()
                if raw:
                    return resp.status, body
                return resp.status, (json.loads(body) if body else None)
        except urllib.error.HTTPError as e:
            detail = e.read().decode('utf-8', 'replace')[:300]
            # 4xx 是业务错误，换通道没意义，直接抛
            if 400 <= e.code < 500:
                raise RuntimeError('HTTP %d: %s' % (e.code, detail))
            last = 'HTTP %d: %s' % (e.code, detail)
        except Exception as e:  # 网络层（超时/连接重置/SSL）
            last = '%s: %s' % (type(e).__name__, e)
    raise RuntimeError(last or 'unknown transport error')


def get_release_by_tag(token, owner, repo, tag):
    try:
        _, data = request('%s/repos/%s/%s/releases/tags/%s' % (API, owner, repo,
                                                              urllib.parse.quote(tag)), token)
        return data
    except RuntimeError as e:
        if 'HTTP 404' in str(e):
            return None
        raise


def create_release(token, owner, repo, tag, name, body, prerelease):
    payload = json.dumps({
        'tag_name': tag,
        'name': name,
        'body': body,
        'prerelease': bool(prerelease),
        'draft': False,
    }).encode('utf-8')
    _, data = request('%s/repos/%s/%s/releases' % (API, owner, repo), token,
                      method='POST', data=payload,
                      headers={'Content-Type': 'application/json'})
    return data


def upload_asset(token, owner, repo, release_id, file_path, attempts=4):
    name = os.path.basename(file_path)
    url = '%s/repos/%s/%s/releases/%d/assets?name=%s' % (
        UPLOAD_API, owner, repo, release_id, urllib.parse.quote(name))
    size = os.path.getsize(file_path)
    last = None
    for i in range(1, attempts + 1):
        try:
            with open(file_path, 'rb') as f:
                content = f.read()
            _, data = request(url, token, method='POST', data=content,
                              headers={'Content-Type': 'application/zip'}, timeout=600)
            if data and data.get('name') == name and data.get('size') == size:
                return data
            last = 'asset mismatch: %s' % (data or {})
        except Exception as e:
            last = str(e)
            print('      upload attempt %d/%d failed: %s' % (i, attempts, last[:160]), flush=True)
            if i < attempts:
                time.sleep(5 * i)
    raise RuntimeError('upload failed after %d attempts: %s' % (attempts, last))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--verify-only', action='store_true')
    ap.add_argument('--tag', action='append', default=None, help='只处理指定 tag（可多次）')
    args = ap.parse_args()

    token, owner, repo = load_github()
    releases = load_gitee_releases()
    # Gitee 返回顺序是创建时间正序，GitHub 也按此顺序建，时间线一致
    if args.tag:
        want = set(args.tag)
        releases = [r for r in releases if r['tag_name'] in want]
        if not releases:
            sys.exit('没有匹配的 tag: %s' % args.tag)

    print('版本数：%d   目标：%s/%s' % (len(releases), owner, repo), flush=True)
    stats = {'created': 0, 'existed': 0, 'uploaded': 0, 'verified': 0, 'failed': []}

    for i, r in enumerate(releases, 1):
        tag = r['tag_name']
        name = r.get('name') or tag
        body = r.get('body') or ''
        prerelease = r.get('prerelease', False)
        local = find_asset_file(tag)
        print('\n[%d/%d] %s  (prerelease=%s, zip=%s)' % (
            i, len(releases), tag, prerelease,
            '%d bytes' % os.path.getsize(local) if local else 'MISSING'), flush=True)

        try:
            remote = get_release_by_tag(token, owner, repo, tag)

            if args.verify_only:
                if not remote:
                    stats['failed'].append('%s: release 不存在' % tag)
                    print('      MISSING RELEASE', flush=True)
                    continue
                stats['existed'] += 1
            elif remote:
                stats['existed'] += 1
                print('      release 已存在，检查附件', flush=True)
            else:
                if args.dry_run:
                    print('      [dry] 将创建 release', flush=True)
                    continue
                remote = create_release(token, owner, repo, tag, name, body, prerelease)
                stats['created'] += 1
                print('      created id=%s' % remote['id'], flush=True)

            # 附件处理
            have = {a['name']: a for a in (remote.get('assets') or [])}
            if local is None:
                if args.verify_only:
                    print('      本地无 zip，跳过附件断言', flush=True)
                else:
                    print('      !! 本地缺 zip，无法上传', flush=True)
                    stats['failed'].append('%s: 本地缺 zip' % tag)
                continue
            want_name = os.path.basename(local)
            want_size = os.path.getsize(local)

            if want_name in have:
                got = have[want_name]
                if got.get('size') == want_size:
                    stats['verified'] += 1
                    print('      asset OK (%d bytes)' % want_size, flush=True)
                else:
                    stats['failed'].append('%s: size 不一致 %s vs %s' % (
                        tag, got.get('size'), want_size))
                    print('      !! size 不一致 %s vs %s' % (got.get('size'), want_size), flush=True)
            elif args.dry_run:
                print('      [dry] 将上传 %s' % want_name, flush=True)
            elif args.verify_only:
                stats['failed'].append('%s: 缺附件 %s' % (tag, want_name))
                print('      !! 缺附件', flush=True)
            else:
                upload_asset(token, owner, repo, remote['id'], local)
                stats['uploaded'] += 1
                stats['verified'] += 1
                print('      uploaded + verified (%d bytes)' % want_size, flush=True)

        except Exception as e:
            stats['failed'].append('%s: %s' % (tag, e))
            print('      FAILED: %s' % str(e)[:200], flush=True)

    print('\n===== 汇总 =====', flush=True)
    print('新建 %d | 已存在 %d | 上传 %d | 校验通过 %d' % (
        stats['created'], stats['existed'], stats['uploaded'], stats['verified']), flush=True)
    if stats['failed']:
        print('失败 %d 项：' % len(stats['failed']), flush=True)
        for f in stats['failed']:
            print('  -', f, flush=True)
        sys.exit(1)
    print('全部通过', flush=True)


if __name__ == '__main__':
    main()
