#!/usr/bin/env python3
"""
Gitee 自动发行脚本（可复用）

用法:
  # 新建发行版（首次发布）
  python dev-support/gitee-release.py --version 2026.10.6 --notes docs/发行说明/xxx-发行说明.md
  # 内测通道
  python dev-support/gitee-release.py --version 2026.10.6-beta.1 --notes docs/发行说明/xxx.md --prerelease
  # 重发（同 tag 已有发行版时先删后重建；源码树变了要换附件走这条）
  python dev-support/gitee-release.py --version 2026.10.9 --notes docs/发行说明/xxx.md --replace
  # 只回读校验现有发行版（含附件 sha256 与本地 zip 逐字节比对）
  python dev-support/gitee-release.py --version 2026.10.9 --verify-only
  # 只删发行版（不动 tag）
  python dev-support/gitee-release.py --version 2026.10.9 --delete-only
  # 从 GitHub Release 取附件同步到 Gitee（发布流水线在 GitHub 侧跑完后用这条：不重新打包，
  # 附件就是门禁验过的那一份；默认要在终端里输入 yes 确认，非交互环境须显式 --yes）
  python dev-support/gitee-release.py --version 2026.10.9 --notes docs/发行说明/xxx.md --from-github

功能（对应《Gitee自动发行工作流-说明文档.md》第三节）:
  1. 建发行版（POST /releases，body 参数名坑位已在实现内规避）
  2. 上传 zip 附件（POST /releases/{id}/attach_files，multipart 字段 file + access_token）
  3. 回读校验（GET /releases/{id}，断言附件 browser_download_url 与预期 downloadUrl 逐字一致）
  4. 附件 sha256 比对（下载线上附件与 build/distributions 的同名 zip 对哈希——证明线上下发的
     就是本地这一份；--verify-only 下不一致即失败，建版流程下只告警，见 verify_artifact）
  5. --replace / --delete-only / --verify-only 三个重发与核查模式（按 tag 查发行版走
     GET /releases/tags/{tag}，实测受支持）
  6. --from-github：把 GitHub Release 的同名附件同步成 Gitee 发行版（本地不重新打包）。
     沿用 GitHub 侧的预发布标记；建版前打印摘要并要求人工确认，除非显式 --yes。
     不动 update/update.json——内测通道数据源仍由 writeUpdateManifest 本地维护。

依赖:
  - .local/gitee-token.json（字段: gitee_personal_access_token / owner / repo；已 gitignore。
    2026-10-10 起由仓库根迁入 .local/，本脚本保留根目录旧路径作回退）
  - .local/github-token.json（--from-github 需要：github_personal_access_token / owner / repo）
  - build/distributions/mybatis-plus-sql-visualizer-{version}.zip 已构建（buildPlugin）
    或由 --from-github 从 GitHub Release 下载
  - 远端标签 {version} 已 push 到 Gitee（否则建发行版 400；--from-github 会先检查并给出指引）
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
import urllib.parse

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)


def load_config():
    # 2026-10-10 迁移：令牌优先读 .local/gitee-token.json，回退仓库根旧路径（已迁移的
    # 机器与未迁移的机器都能跑）。
    candidates = [
        os.path.join(REPO_ROOT, ".local", "gitee-token.json"),
        os.path.join(REPO_ROOT, "gitee-token.json"),
    ]
    token_path = next((p for p in candidates if os.path.exists(p)), None)
    if token_path is None:
        raise SystemExit(
            "找不到令牌文件。请创建以下任一路径：\n  - "
            + "\n  - ".join(candidates)
            + "\n内容示例：{\"gitee_personal_access_token\": \"...\", \"owner\": \"...\", \"repo\": \"...\"}"
        )
    with open(token_path, encoding="utf-8") as f:
        cfg = json.load(f)
    return (
        cfg["gitee_personal_access_token"],
        cfg.get("owner", "songrenshuo"),
        cfg.get("repo", "mybatis-plus-sql-visualizer"),
    )


def create_release(token, owner, repo, version, name, body_md, prerelease=False):
    """建发行版。坑位：参数名是 body 不是 description（Gitee 与 GitHub API 不兼容）。"""
    url = f"https://gitee.com/api/v5/repos/{owner}/{repo}/releases"
    data = urllib.parse.urlencode({
        "access_token": token,
        "tag_name": version,
        "target_commitish": "master",
        "name": name,
        "body": body_md,
        "prerelease": "true" if prerelease else "false",
    }).encode()
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    resp = json.load(urllib.request.urlopen(req, timeout=60))
    return resp["id"]


def upload_zip(token, owner, repo, release_id, zip_path):
    """上传附件。坑位：端点只有 attach_files（upload_files / assets 均 404）；
    附件 filename 直接决定下载 URL 尾段。"""
    zip_name = os.path.basename(zip_path)
    boundary = "----UploadBoundary"
    with open(zip_path, "rb") as f:
        zip_data = f.read()
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{zip_name}"\r\n'
        f"Content-Type: application/zip\r\n\r\n"
    ).encode() + zip_data + (
        f"\r\n--{boundary}\r\n"
        f'Content-Disposition: form-data; name="access_token"\r\n\r\n'
        f"{token}\r\n--{boundary}--\r\n"
    ).encode()
    url = f"https://gitee.com/api/v5/repos/{owner}/{repo}/releases/{release_id}/attach_files"
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    resp = json.load(urllib.request.urlopen(req, timeout=180))
    return resp


def verify_release(token, owner, repo, release_id, version, zip_name, prerelease):
    """回读校验：prerelease 标记、附件在场、downloadUrl 与预期逐字一致。"""
    url = f"https://gitee.com/api/v5/repos/{owner}/{repo}/releases/{release_id}?access_token={token}"
    resp = json.load(urllib.request.urlopen(url, timeout=60))
    assert resp.get("prerelease") == prerelease, \
        f"prerelease mismatch: expected {prerelease}, got {resp.get('prerelease')}"
    expected_url = f"https://gitee.com/{owner}/{repo}/releases/download/{version}/{zip_name}"
    assets = resp.get("assets", [])
    urls = {a["browser_download_url"] for a in assets}
    assert expected_url in urls, f"downloadUrl mismatch: expected {expected_url}, got {urls}"
    return resp


def _api(owner, repo):
    return f"https://gitee.com/api/v5/repos/{owner}/{repo}"


def find_release_by_tag(token, owner, repo, version):
    """按标签查发行版；不存在返回 None（`/releases/tags/{tag}` 实测受支持，省去翻页）。"""
    url = f"{_api(owner, repo)}/releases/tags/{version}?access_token={token}"
    try:
        return json.load(urllib.request.urlopen(url, timeout=60))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def delete_release(token, owner, repo, release_id):
    """删发行版（不动远端标签）。Gitee 无编辑接口，换附件只能删了重建。"""
    url = f"{_api(owner, repo)}/releases/{release_id}?access_token={token}"
    req = urllib.request.Request(url, method="DELETE")
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.status


def _sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------------------
# --from-github：把 GitHub Release 的同名附件同步成 Gitee 发行版
# 设计约束（用户 2026-10-26 定）：Gitee 仍是主仓，GitHub 触发的发布只能**经人确认**后才
# 落一份到 Gitee；本脚本不自动跑、也不由 CI 调用，且不碰 update/update.json（内测通道数据源）。
# ---------------------------------------------------------------------------

def load_github_config():
    candidates = [
        os.path.join(REPO_ROOT, ".local", "github-token.json"),
        os.path.join(REPO_ROOT, "github-token.json"),
    ]
    token_path = next((p for p in candidates if os.path.exists(p)), None)
    if token_path is None:
        raise SystemExit(
            "找不到 GitHub 令牌文件（--from-github 需要）：\n  - " + "\n  - ".join(candidates)
            + "\n字段示例：{\"github_personal_access_token\": \"...\", \"owner\": \"...\", \"repo\": \"...\"}"
        )
    with open(token_path, encoding="utf-8") as f:
        cfg = json.load(f)
    return (
        cfg["github_personal_access_token"],
        cfg.get("owner", "SongRenShuo"),
        cfg.get("repo", "mybatis-plus-sql-visualizer"),
    )


def _github_get(url, token):
    req = urllib.request.Request(url, headers={
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "User-Agent": "sql-visualizer-release-sync",
    })
    try:
        return json.load(urllib.request.urlopen(req, timeout=60))
    except urllib.error.HTTPError as e:
        if e.code in (404, 410):
            return None
        raise SystemExit(f"GitHub API {e.code}: {e.read().decode('utf-8', 'replace')[:300]}")


def ensure_gitee_tag(version):
    """建版要求 tag 已在 Gitee（否则 Gitee 返回 400）。只读检查，不自动推 tag。"""
    try:
        proc = subprocess.run(
            ["git", "-C", REPO_ROOT, "ls-remote", "--tags", "origin", f"refs/tags/{version}"],
            capture_output=True, text=True, timeout=60,
        )
    except (OSError, subprocess.SubprocessError) as e:  # noqa: BLE001 - 环境缺 git 时只告警
        print(f"  WARN: 无法执行 git（{type(e).__name__}），跳过 tag 前置检查")
        return
    if proc.returncode != 0:
        print(f"  WARN: git ls-remote 失败（{proc.stderr.strip()[:120]}），跳过 tag 前置检查")
        return
    if not proc.stdout.strip():
        raise SystemExit(
            f"ERROR: Gitee 上没有 tag {version}，建发行版会 400。\n"
            f"  先在本地打 tag 并推到两端：git tag {version} <目标提交> && "
            f"powershell -NoProfile -File dev-support/push-all.ps1 -Tags"
        )


def fetch_github_zip(version, zip_name, dest):
    """取 GitHub Release 里与 zip_name 同名的附件写入 dest，不重新打包。

    返回 (prerelease, sha256, size)。本地已有同名文件且哈希一致时直接复用；
    哈希不同则拒绝覆盖（避免静默改掉本地构建产物）。
    """
    token, gh_owner, gh_repo = load_github_config()
    rel = _github_get(f"https://api.github.com/repos/{gh_owner}/{gh_repo}/releases/tags/{version}", token)
    if rel is None:
        raise SystemExit(
            f"ERROR: GitHub 上没有 tag {version} 的发行版。\n"
            f"  先在 Actions 跑 build-plugin.yml：mode=release（门禁后审批发布）或 mode=publish（复用门禁产物）"
        )
    if rel.get("draft"):
        raise SystemExit(f"ERROR: GitHub 发行版 {version} 仍是草稿，先正式发布再同步")
    assets = rel.get("assets") or []
    asset = next((a for a in assets if a.get("name") == zip_name), None)
    if asset is None:
        names = "、".join(a.get("name", "?") for a in assets) or "（无附件）"
        raise SystemExit(f"ERROR: GitHub 发行版 {version} 里没有附件 {zip_name}；现有：{names}")
    expected = int(asset.get("size") or 0)
    req = urllib.request.Request(asset["url"], headers={
        "Authorization": "Bearer " + token,
        "Accept": "application/octet-stream",
        "User-Agent": "sql-visualizer-release-sync",
    })
    data = urllib.request.urlopen(req, timeout=600).read()
    if expected and len(data) != expected:
        raise SystemExit(f"ERROR: 下载长度不符：GitHub 报 {expected} 字节，实际 {len(data)} 字节")
    sha = hashlib.sha256(data).hexdigest()
    if os.path.exists(dest):
        local_sha = _sha256_file(dest)
        if local_sha != sha:
            raise SystemExit(
                f"ERROR: 本地已有同名 zip 但内容不同（本地 {local_sha[:16]}… / GitHub {sha[:16]}…）。\n"
                f"  确认以 GitHub 为准就先把它移走：{dest}"
            )
        print(f"  本地已存在同名 zip，sha256 与 GitHub 一致（{sha[:16]}…），直接复用")
        return bool(rel.get("prerelease")), sha, len(data)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "wb") as f:
        f.write(data)
    print(f"  已从 GitHub 下载 {zip_name}（{len(data)} 字节，sha256 {sha[:16]}…）")
    return bool(rel.get("prerelease")), sha, len(data)


def verify_artifact(token, owner, repo, version, zip_path, strict):
    """下载线上附件与本地 zip 对 sha256——比 URL 串比对强一级，证明下发的是本地这一份。

    同名附件重传后 CDN 可能仍缓存旧包：建版流程下只告警（strict=False），
    隔一会儿用 --verify-only 复查（strict=True，不一致即失败）。
    """
    zip_name = os.path.basename(zip_path)
    url = f"https://gitee.com/{owner}/{repo}/releases/download/{version}/{zip_name}"
    try:
        data = urllib.request.urlopen(url, timeout=600).read()
    except Exception as e:  # noqa: BLE001 - 网络层任意异常都归为「下不下来」
        msg = f"artifact download failed: {type(e).__name__}: {e}"
        if strict:
            raise AssertionError(msg)
        print("  WARN:", msg)
        return
    local, remote = _sha256_file(zip_path), hashlib.sha256(data).hexdigest()
    if local == remote:
        print(f"  artifact sha256 OK ({remote[:16]}…, {len(data)} bytes)")
        return
    msg = (f"artifact sha256 mismatch: local={local[:16]}… remote={remote[:16]}… "
           f"(同名附件重传后 CDN 可能仍缓存旧包，稍后用 --verify-only 复查)")
    if strict:
        raise AssertionError(msg)
    print("  WARN:", msg)


def main():
    parser = argparse.ArgumentParser(description="Gitee 自动发行脚本")
    parser.add_argument("--version", required=True,
                        help="版本号，逐字等于 gradle.properties（如 2026.10.6 或 2026.10.6-beta.1）")
    parser.add_argument("--notes", required=False,
                        help="发行说明 markdown 路径（全文作为发行版正文 body；建版必填）")
    parser.add_argument("--name", default=None,
                        help="发行版标题（默认: 🎉 SQL Visualizer v{version}，内测加「 内测版」请显式传）")
    parser.add_argument("--prerelease", action="store_true", help="标记为预览版本（内测通道）")
    parser.add_argument("--skip-upload", action="store_true", help="跳过附件上传（仅建发行版）")
    parser.add_argument("--skip-verify", action="store_true", help="跳过回读校验")
    parser.add_argument("--skip-sha256", action="store_true",
                        help="跳过附件 sha256 比对（建版流程默认比对但只告警；--verify-only 下为硬失败）")
    parser.add_argument("--replace", action="store_true",
                        help="同 tag 已有发行版时先删除再重建（源码树变了要换附件走这条）")
    parser.add_argument("--delete-only", action="store_true",
                        help="只删除该 tag 的发行版（不动远端标签）后退出")
    parser.add_argument("--verify-only", action="store_true",
                        help="不建版不传附件，只回读校验现有发行版")
    parser.add_argument("--from-github", action="store_true",
                        help="从 GitHub Release 取同名附件同步到 Gitee（不重新打包；建版前需人工确认）")
    parser.add_argument("--yes", action="store_true",
                        help="--from-github 时跳过人工确认；非交互环境必须显式给")
    args = parser.parse_args()

    version = args.version
    zip_name = f"mybatis-plus-sql-visualizer-{version}.zip"
    zip_path = os.path.join(REPO_ROOT, "build", "distributions", zip_name)
    name = args.name or f"\U0001f389 SQL Visualizer v{version}"

    token, owner, repo = load_config()
    existing = find_release_by_tag(token, owner, repo, version)

    # ① 只删（换附件/撤版用；不动远端标签——删标签才会连坐删发行版）
    if args.delete_only:
        if existing is None:
            print(f"tag {version} 没有发行版，无需删除")
            return
        rid = existing["id"]
        status = delete_release(token, owner, repo, rid)
        print(f"已删除发行版 id={rid} (HTTP {status})；远端标签 {version} 未动")
        return

    # ①.5 同步来源：从 GitHub Release 取附件（本地不重新打包；CI 不走这条，只能人手动跑）
    if args.from_github:
        gh_prerelease, gh_sha, gh_size = fetch_github_zip(version, zip_name, zip_path)
        if gh_prerelease and not args.prerelease:
            args.prerelease = True
            print("  沿用 GitHub 侧的预发布标记（内测版）")
        if not args.verify_only:
            ensure_gitee_tag(version)
            print("\n即将把上述 GitHub 附件同步为 Gitee 发行版：")
            print(f"  版本   : {version}")
            print(f"  附件   : {zip_name}（{gh_size} 字节，sha256 {gh_sha[:16]}…）")
            print(f"  预发布 : {'是' if args.prerelease else '否'}")
            print(f"  正文   : {args.notes or '（缺 --notes，建版会失败）'}")
            print(f"  Gitee  : https://gitee.com/{owner}/{repo}/releases/tag/{version}")
            if not args.yes:
                if not sys.stdin.isatty():
                    raise SystemExit("ERROR: 非交互环境下同步到 Gitee 需显式加 --yes（这是有意的二次确认）")
                if input("确认上传到 Gitee 吗？输入 yes 继续：").strip().lower() != "yes":
                    raise SystemExit("已取消：未对 Gitee 做任何写操作")

    # ② 只校验（发布后复查，尤其同名附件重传后确认 CDN 已换新）
    if args.verify_only:
        if existing is None:
            print(f"ERROR: tag {version} 没有发行版", file=sys.stderr)
            sys.exit(1)
        if not os.path.exists(zip_path):
            print(f"ERROR: 本地 zip 不存在，无法做附件比对: {zip_path}", file=sys.stderr)
            sys.exit(1)
        rid = existing["id"]
        print(f"[1/3] Verifying release id={rid} ...")
        verify_release(token, owner, repo, rid, version, zip_name, args.prerelease)
        print("  state / downloadUrl OK")
        if not args.skip_sha256:
            print(f"[2/3] Comparing artifact sha256 against {zip_name} ...")
            verify_artifact(token, owner, repo, version, zip_path, strict=True)
        print("[3/3] Verified")
        print(f"  Release URL : https://gitee.com/{owner}/{repo}/releases/{rid}")
        return

    # ③ 建版：首次发布，或 --replace 重发
    if not args.notes:
        print("ERROR: --notes 必填（建发行版需要发行说明正文）", file=sys.stderr)
        sys.exit(1)
    notes_path = args.notes if os.path.isabs(args.notes) else os.path.join(REPO_ROOT, args.notes)
    if not os.path.exists(zip_path):
        print(f"ERROR: zip not found: {zip_path}\n  先跑 ./gradlew buildPlugin", file=sys.stderr)
        sys.exit(1)
    if not os.path.exists(notes_path):
        print(f"ERROR: notes not found: {notes_path}", file=sys.stderr)
        sys.exit(1)

    if existing is not None:
        if not args.replace:
            print(f"ERROR: tag {version} 已有发行版 id={existing['id']}——同 tag 再建报 400。\n"
                  f"  重发加 --replace（先删后重建）；只想删用 --delete-only。", file=sys.stderr)
            sys.exit(1)
        old_id = existing["id"]
        status = delete_release(token, owner, repo, old_id)
        print(f"[0/4] 旧发行版 id={old_id} 已删除 (HTTP {status})，按同 tag 重建")

    with open(notes_path, encoding="utf-8") as f:
        body_md = f.read()

    print(f"[1/4] Creating release v{version} ...")
    release_id = create_release(token, owner, repo, version, name, body_md, args.prerelease)
    print(f"  Release ID: {release_id}")

    if not args.skip_upload:
        print(f"[2/4] Uploading {zip_name} ...")
        upload_zip(token, owner, repo, release_id, zip_path)
        print("  Upload complete")
    else:
        print("[2/4] Skipped (--skip-upload)")

    if not args.skip_verify:
        print("[3/4] Verifying ...")
        verify_release(token, owner, repo, release_id, version, zip_name, args.prerelease)
        print("  state / downloadUrl OK")
        if not args.skip_sha256:
            print("  Comparing artifact sha256 ...")
            verify_artifact(token, owner, repo, version, zip_path, strict=False)
    else:
        print("[3/4] Skipped (--skip-verify)")

    print("[4/4] Done!")
    print(f"  Release URL : https://gitee.com/{owner}/{repo}/releases/{release_id}")
    print(f"  Download URL: https://gitee.com/{owner}/{repo}/releases/download/{version}/{zip_name}")


if __name__ == "__main__":
    main()
