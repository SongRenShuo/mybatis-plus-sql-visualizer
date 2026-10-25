#!/usr/bin/env python3
"""同 tag 重发但**保留发行版创建时间**：PATCH 更新正文 + 删旧附件后上传新附件。

与 `gitee-release.py --replace` 的区别：后者 DELETE 整版再重建，创建时间会被刷新；
本脚本原地更新，`created_at` 保持不变（Gitee 无 created_at 入参，PATCH 后自动保留原值）。

用法：
  python dev-support/gitee-release-update.py --version 2026.10.23 --notes docs/发行说明/xxx.md
  python dev-support/gitee-release-update.py --version 2026.10.23 --verify-only

前置：远端标签已 push、`build/distributions/mybatis-plus-sql-visualizer-{version}.zip` 已构建。
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)


def load_config():
    for rel in (os.path.join(".local", "gitee-token.json"), "gitee-token.json"):
        p = os.path.join(REPO_ROOT, rel)
        if os.path.exists(p):
            with open(p, encoding="utf-8") as f:
                cfg = json.load(f)
            return (
                cfg["gitee_personal_access_token"],
                cfg.get("owner", "songrenshuo"),
                cfg.get("repo", "mybatis-plus-sql-visualizer"),
            )
    raise SystemExit("找不到 .local/gitee-token.json")


def api(owner, repo):
    return f"https://gitee.com/api/v5/repos/{owner}/{repo}"


def find_release(token, owner, repo, version):
    url = f"{api(owner, repo)}/releases/tags/{version}?access_token={token}"
    try:
        return json.load(urllib.request.urlopen(url, timeout=60))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def patch_release(token, owner, repo, rid, version, name, body_md, prerelease):
    """PATCH 更新发行版。Gitee 无 created_at 入参，创建时间自动保持原值。

    坑位（20261025 实测）：Gitee 的 PATCH 要求 **tag_name 与 name 同时在场**，
    只传 body 报 400 `{"messages":["tag_name is missing","name is missing"]}`
    —— 与 GitHub 的「部分更新」语义不同，必须把全量字段带上。
    """
    url = f"{api(owner, repo)}/releases/{rid}"
    data = urllib.parse.urlencode({
        "access_token": token,
        "tag_name": version,
        "name": name,
        "body": body_md,
        "prerelease": "true" if prerelease else "false",
    }).encode()
    req = urllib.request.Request(url, data=data, method="PATCH")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    return json.load(urllib.request.urlopen(req, timeout=120))


def delete_asset(token, owner, repo, rid, asset_id):
    url = f"{api(owner, repo)}/releases/{rid}/attach_files/{asset_id}?access_token={token}"
    req = urllib.request.Request(url, method="DELETE")
    return urllib.request.urlopen(req, timeout=60).status


def upload_zip(token, owner, repo, rid, zip_path):
    zip_name = os.path.basename(zip_path)
    boundary = "----UploadBoundary"
    with open(zip_path, "rb") as f:
        data = f.read()
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{zip_name}"\r\n'
        f"Content-Type: application/zip\r\n\r\n"
    ).encode() + data + (
        f"\r\n--{boundary}\r\n"
        f'Content-Disposition: form-data; name="access_token"\r\n\r\n'
        f"{token}\r\n--{boundary}--\r\n"
    ).encode()
    url = f"{api(owner, repo)}/releases/{rid}/attach_files"
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    return json.load(urllib.request.urlopen(req, timeout=300))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True)
    ap.add_argument("--notes")
    ap.add_argument("--verify-only", action="store_true")
    args = ap.parse_args()

    token, owner, repo = load_config()
    ver = args.version
    zip_name = f"mybatis-plus-sql-visualizer-{ver}.zip"
    zip_path = os.path.join(REPO_ROOT, "build", "distributions", zip_name)

    rel = find_release(token, owner, repo, ver)
    if rel is None:
        print(f"ERROR: tag {ver} 无发行版", file=sys.stderr)
        sys.exit(1)
    rid = rel["id"]
    created_before = rel.get("created_at")
    print(f"[info] release id={rid} created_at={created_before} prerelease={rel.get('prerelease')}")

    if args.verify_only:
        if not os.path.exists(zip_path):
            print(f"ERROR: 本地 zip 不存在: {zip_path}", file=sys.stderr)
            sys.exit(1)
        rel2 = find_release(token, owner, repo, ver)
        expected = f"https://gitee.com/{owner}/{repo}/releases/download/{ver}/{zip_name}"
        urls = {a["browser_download_url"] for a in rel2.get("assets", [])}
        assert expected in urls, f"downloadUrl mismatch: {urls}"
        print("  state / downloadUrl OK")
        data = urllib.request.urlopen(expected, timeout=600).read()
        local = hashlib.sha256(open(zip_path, "rb").read()).hexdigest()
        remote = hashlib.sha256(data).hexdigest()
        if local != remote:
            print(f"ERROR: sha256 mismatch local={local[:16]} remote={remote[:16]}", file=sys.stderr)
            sys.exit(1)
        print(f"  artifact sha256 OK ({remote[:16]}…, {len(data)} bytes)")
        print(f"  created_at={rel2.get('created_at')} (应保持 {created_before})")
        return

    if not args.notes:
        print("ERROR: --notes 必填", file=sys.stderr)
        sys.exit(1)
    notes_path = args.notes if os.path.isabs(args.notes) else os.path.join(REPO_ROOT, args.notes)
    with open(notes_path, encoding="utf-8") as f:
        body_md = f.read()

    print("[1/3] PATCH 更新发行版正文（创建时间保持不变）...")
    patch_release(token, owner, repo, rid, ver, rel.get("name"), body_md,
                  bool(rel.get("prerelease")))
    print("[2/3] 删旧附件 + 上传新附件 ...")
    # 坑位（20261025 实测）：发行版详情里的 `assets` 只有 name/browser_download_url，
    # **没有 id**；删除必须用 `GET /releases/{id}/attach_files` 拿 id，否则 KeyError。
    # 另：同名附件允许并存（不自动覆盖），下载会命中先传的那个 —— 必须先删旧的再传新的，
    # 否则线上会一直发旧包（sha256 对不上）。
    assets = json.load(urllib.request.urlopen(
        f"{api(owner, repo)}/releases/{rid}/attach_files?access_token={token}", timeout=60))
    for a in assets:
        if a.get("name") == zip_name:
            st = delete_asset(token, owner, repo, rid, a["id"])
            print(f"  旧附件 id={a['id']} ({a.get('size')} 字节) 已删除 (HTTP {st})")
    upload_zip(token, owner, repo, rid, zip_path)
    print("[3/3] 回读校验 ...")
    rel2 = find_release(token, owner, repo, ver)
    expected = f"https://gitee.com/{owner}/{repo}/releases/download/{ver}/{zip_name}"
    urls = {a["browser_download_url"] for a in rel2.get("assets", [])}
    assert expected in urls, f"downloadUrl mismatch: {urls}"
    data = urllib.request.urlopen(expected, timeout=600).read()
    local = hashlib.sha256(open(zip_path, "rb").read()).hexdigest()
    remote = hashlib.sha256(data).hexdigest()
    assert local == remote, f"sha256 mismatch local={local[:16]} remote={remote[:16]}"
    created_after = rel2.get("created_at")
    print(f"  sha256 OK ({remote[:16]}…, {len(data)} bytes)")
    print(f"  created_at: {created_before} -> {created_after}")
    if created_after != created_before:
        print("  WARN: 创建时间发生变化", file=sys.stderr)
    print(f"  Release URL: https://gitee.com/{owner}/{repo}/releases/{rid}")


if __name__ == "__main__":

    main()
