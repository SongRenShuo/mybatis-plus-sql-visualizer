#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CI 运行与 Release 状态查询（2026-11-11 沉淀，T-ExecPsiGate 发行轮次产出）

背景：发行链路是 gate → publish → Gitee 同步，每一步都要「这次 run 绿没绿 / 产物在不在 /
两个平台的发行版指向哪个 sha」，此前每次都是在临时目录里现写一段 python。本脚本把它收口成
一条命令，避免再次散落在会话里。

子命令：
  runs [--limit N]              最近 N 次 build-plugin.yml 运行（id / 状态 / 结论 / sha / 事件）
  run <run_id>                  单次运行详情（含每个 job 的状态与结论）
  wait <run_id> [--timeout-min] 轮询到结束并打印结论；退出码 0 = success，1 = 其它（后台任务用）
  release <tag>                 某 tag 的 GitHub Release（id / prerelease / 附件名与字节数）
  pick-artifact <run_id>        列出该 run 产出的插件 zip 附件（文件名 + 字节数），供发行核对

为什么单独一个脚本而不是并进 mirror-releases-to-github.py：那个负责「Gitee → GitHub」的镜像写操作；
本脚本只读（查询/轮询），发行与镜像都不该被它的失败牵连。

依赖：`.local/github-token.json`（字段 github_personal_access_token；owner / repo 可取默认）。
代理：先走本机 127.0.0.1:7890，失败回退直连（与 push-all.ps1 / mirror 脚本同策略）。

用法示例：
  python dev-support/ci-status.py runs
  python dev-support/ci-status.py wait 42989276892            # 后台跑，完成即出结论
  python dev-support/ci-status.py release 2026.11.12-beta.2
  python dev-support/ci-status.py pick-artifact 42989276892
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)
API = 'https://api.github.com'
PROXY = 'http://127.0.0.1:7890'
WORKFLOW = 'build-plugin.yml'


def die(msg):
    print('ERROR:', msg, file=sys.stderr)
    raise SystemExit(2)


def load_token():
    path = os.path.join(REPO_ROOT, '.local', 'github-token.json')
    if not os.path.exists(path):
        die('缺少 %s' % path)
    cfg = json.load(open(path, encoding='utf-8'))
    tok = cfg.get('github_personal_access_token')
    if not tok:
        die('%s 里没有 github_personal_access_token' % path)
    return tok, cfg.get('owner', 'SongRenShuo'), cfg.get('repo', 'mybatis-plus-sql-visualizer')


def request(url, token, timeout=60):
    """GET JSON；代理优先、失败回退直连；4xx 直接报错（换通道没意义）。"""
    req = urllib.request.Request(url, headers={
        'Authorization': 'Bearer %s' % token,
        'Accept': 'application/vnd.github+json',
        'User-Agent': 'mpv-ci-status',
    })
    last = None
    for handler in (urllib.request.ProxyHandler({'https': PROXY, 'http': PROXY}),
                    urllib.request.ProxyHandler({})):
        opener = urllib.request.build_opener(handler)
        try:
            with opener.open(req, timeout=timeout) as resp:
                return json.loads(resp.read() or b'null')
        except urllib.error.HTTPError as e:
            detail = e.read().decode('utf-8', 'replace')[:300]
            if 400 <= e.code < 500:
                raise RuntimeError('HTTP %d: %s' % (e.code, detail))
            last = 'HTTP %d: %s' % (e.code, detail)
        except Exception as e:  # 网络层：超时/连接重置/SSL
            last = '%s: %s' % (type(e).__name__, e)
    raise RuntimeError(last or 'unknown transport error')


def api(path, token):
    return request(API + path, token)


def cmd_runs(args, token, owner, repo):
    data = api('/repos/%s/%s/actions/workflows/%s/runs?per_page=%d'
               % (owner, repo, WORKFLOW, args.limit), token)
    print('%-12s %-10s %-9s %-9s %-20s %s' % ('run_id', 'status', 'conclusion', 'sha', 'created_at', 'event'))
    for r in data.get('workflow_runs') or []:
        print('%-12s %-10s %-9s %-9s %-20s %s' % (
            r['id'], r['status'], r['conclusion'] or '-', r['head_sha'][:9], r['created_at'], r['event']))


def cmd_run(args, token, owner, repo):
    r = api('/repos/%s/%s/actions/runs/%s' % (owner, repo, args.run_id), token)
    print('run %s  status=%s  conclusion=%s  branch=%s  sha=%s'
          % (r['id'], r['status'], r['conclusion'] or '-', r['head_branch'], r['head_sha'][:9]))
    jobs = api('/repos/%s/%s/actions/runs/%s/jobs?per_page=100' % (owner, repo, args.run_id), token)
    for j in jobs.get('jobs') or []:
        print('  %-28s %-10s %s' % (j.get('name'), j.get('status'), j.get('conclusion') or '-'))


def cmd_wait(args, token, owner, repo):
    deadline = time.time() + args.timeout_min * 60
    last = None
    while time.time() < deadline:
        try:
            r = api('/repos/%s/%s/actions/runs/%s' % (owner, repo, args.run_id), token)
        except Exception as e:  # 轮询期网络抖动不该判失败
            print('poll error: %s' % e, flush=True)
            time.sleep(args.interval)
            continue
        state = (r['status'], r['conclusion'])
        if state != last:
            print('%s status=%s conclusion=%s' % (r['id'], r['status'], r['conclusion'] or '-'), flush=True)
            last = state
        if r['status'] == 'completed':
            ok = r['conclusion'] == 'success'
            print('FINAL %s %s' % (r['id'], r['conclusion']), flush=True)
            raise SystemExit(0 if ok else 1)
        time.sleep(args.interval)
    print('TIMEOUT after %d min waiting for %s' % (args.timeout_min, args.run_id), flush=True)
    raise SystemExit(1)


def cmd_release(args, token, owner, repo):
    r = api('/repos/%s/%s/releases/tags/%s' % (owner, repo, args.tag), token)
    print('release id=%s  tag=%s  name=%s  prerelease=%s  draft=%s'
          % (r['id'], r['tag_name'], r.get('name'), r['prerelease'], r['draft']))
    for a in r.get('assets') or []:
        print('  asset %-60s %d bytes  state=%s' % (a['name'], a['size'], a.get('state')))
    if not (r.get('assets') or []):
        print('  （无附件）')


def cmd_pick_artifact(args, token, owner, repo):
    arts = api('/repos/%s/%s/actions/runs/%s/artifacts' % (owner, repo, args.run_id), token)
    found = False
    for a in arts.get('artifacts') or []:
        found = True
        print('%-32s %d bytes  expired=%s  id=%s' % (a['name'], a['size_in_bytes'], a['expired'], a['id']))
    if not found:
        print('该 run 没有可下载的 artifact（gate 模式通常只产 plugin-* 一个）')


def main():
    ap = argparse.ArgumentParser(description='CI 运行与 Release 状态查询（只读）')
    sub = ap.add_subparsers(dest='cmd', required=True)

    p = sub.add_parser('runs', help='最近若干次 build-plugin.yml 运行')
    p.add_argument('--limit', type=int, default=5)
    p.set_defaults(func=cmd_runs)

    p = sub.add_parser('run', help='单次运行详情（含各 job）')
    p.add_argument('run_id')
    p.set_defaults(func=cmd_run)

    p = sub.add_parser('wait', help='轮询到结束；退出码 0 = success')
    p.add_argument('run_id')
    p.add_argument('--timeout-min', type=int, default=40)
    p.add_argument('--interval', type=int, default=30)
    p.set_defaults(func=cmd_wait)

    p = sub.add_parser('release', help='某 tag 的 GitHub Release 与附件')
    p.add_argument('tag')
    p.set_defaults(func=cmd_release)

    p = sub.add_parser('pick-artifact', help='该 run 的 artifact 清单')
    p.add_argument('run_id')
    p.set_defaults(func=cmd_pick_artifact)

    args = ap.parse_args()
    token, owner, repo = load_token()
    # Windows 控制台默认 GBK：不重设会把中文/emoji 打成乱码或直接抛 UnicodeEncodeError
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass
    args.func(args, token, owner, repo)


if __name__ == '__main__':
    main()
