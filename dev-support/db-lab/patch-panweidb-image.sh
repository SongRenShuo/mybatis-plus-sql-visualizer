#!/usr/bin/env bash
# =============================================================================
# patch-panweidb-image.sh
#
# Replayable patch set for the PanWeiDB 3.4.0 container image
#   registry.cn-shenzhen.aliyuncs.com/shine_fei/panweidb:3.4.0
# (a third-party repack of the real PanWeiDB_V3.0-S3.4.0_B01 vendor tarball --
#  NOT a vendor-official distribution; see the research report for provenance)
#
# WHY THIS EXISTS
#   The image installs itself on first boot (init-db.service -> /root/run.sh:
#   unpack -> ansible -> gs_install). On this QEMU / cgroup-v2 host the install
#   cannot get through without three patches. Code-level history and evidence:
#     docs/调研报告/20261106-1435-磐维PanWeiDB容器镜像搜索与真机部署-调研报告.md
#     docs/经验总结/20261106-1523-磐维PanWeiDB镜像搜索与容器部署-经验总结.md
#   The research report's section 7 documents this script and its verification.
#
# THE THREE PATCHES
#   1  CPU precheck bypass   <soft>/script/local/PreInstallUtility.py
#        check_cpu_instructions() requires `lscpu | grep rdtscp && lscpu | grep
#        avx`, and this host is a QEMU vCPU with neither. Bypassing the precheck
#        is safe: initdb, gs_ctl start and gs_checkos were all verified working
#        on the unpatched binaries, so it is an over-strict gate, not a real
#        instruction dependency.
#   2  checkDockerEnv() cgroup-v2 fallback   <soft>/script/gspylib/common/Common.py
#        The probe greps /proc/1/cgroup in the cgroup-v1 shape, which never
#        matches under cgroup v2 ("0::/init.scope"). That single False disables
#        9 "tolerable inside a container" branches, which is exactly what makes
#        gs_checkos die on `find /etc/sysconfig/network-scripts` (GAUSS-51400)
#        and on `/sbin/ethtool -g eth0` (GAUSS-50612). Fixing the probe is
#        cleaner than patching each of the 9 call sites.
#   2b empty /etc/sysconfig/network-scripts
#        `find <dir>` exits non-zero when the directory is missing and 0 when it
#        exists with no match, so creating the empty directory alone satisfies
#        patch 2's first blocker.
#   3  listen_addresses keyed to the install-time container IP   /root/run.sh
#        run.sh rewrites localhost -> the IP seen at install time and never
#        revisits it, so a recreated container (new IP) fails to start with
#        `FATAL: could not create listen socket for "172.17.0.x:17700"`.
#        This inserts a rewrite against the CURRENT IP into the start branch.
#        `gs_guc` rejects both `*` and comma-separated values, so the config file
#        has to be edited directly.
#
# WHERE THE PATCHES LIVE (matters for the snapshot path below)
#   patches 1, 2, 2b  -> <soft> tree, which is the mounted data volume
#   patch 3           -> /root/run.sh, which is in the image layer, so it is lost
#                        when the container is recreated without a commit
#
# WHEN TO RUN
#   Inside the container, after the vendor tarball has been unpacked
#   (<soft>/script exists) and before gs_install runs. The install window is
#   short (tens of seconds to a few minutes), so the reliable recipe is to start
#   the container and poll for <soft>/script, then run this immediately.
#   Running it on an already-installed container is safe: every step detects its
#   own marker and no-ops. The script never overwrites an existing *.orig.
#
# TWO WAYS TO LAND THIS
#   A) Patch the original image on every rebuild
#        docker run -d --name panweidb-<x> --restart unless-stopped \
#          --privileged=true --memory=4g --shm-size=2g \
#          -v /www/panweidb-<x>/data:/database -p 2610x:17700 \
#          -e dbcompatibility=B \
#          registry.cn-shenzhen.aliyuncs.com/shine_fei/panweidb:3.4.0
#        # poll until /database/panweidb/soft/script exists, then:
#        docker cp patch-panweidb-image.sh panweidb-<x>:/root/
#        docker exec panweidb-<x> bash /root/patch-panweidb-image.sh
#      Pro: always built from the distribution's own image.
#      Con: the patch has to win a race against the installer; patch 3 is written
#           into the container's own layer, so a later `docker rm` loses it while
#           patches 1/2/2b survive in the data volume.
#   B) Snapshot a patched container as a stand-in image
#        docker commit panweidb-ora panweidb:3.4.0-pwfix
#        docker run -d --name panweidb-new ... panweidb:3.4.0-pwfix
#      Pro: no race, no manual step on rebuild.
#      Con: `docker commit` does NOT capture mounted volumes, so the snapshot only
#           carries patch 3 (plus 2b, a plain directory) in the image layer.
#           Patches 1/2 come from the data volume -- reattach the SAME volume, or
#           run this script again once the fresh volume has been unpacked.
#           It is also a frozen snapshot of one host's install, so redo it
#           whenever the upstream image is upgraded. Not a vendor artifact.
#
# USAGE
#   # inside the container (primary, verified path)
#   docker cp patch-panweidb-image.sh <container>:/root/
#   docker exec <container> bash /root/patch-panweidb-image.sh
#
#   # from a docker host that can reach the container (one command, same effect)
#   bash patch-panweidb-image.sh --container panweidb-ora
#
#   # every path is overridable; the defaults are the deployed layout, and the
#   # overrides are what the replay test uses to patch pristine files in a
#   # scratch tree:
#   PANWEIDB_SOFT_ROOT=... PANWEIDB_RUN_SH=... PANWEIDB_NET_SCRIPTS=... \
#     bash patch-panweidb-image.sh
#
# CONTRACT
#   * idempotent: a second run reports "already patched" and writes nothing
#   * *.orig is written once from an unpatched file and never clobbered
#   * edits go to a temp file and are renamed over the target, so mode and
#     ownership survive and a concurrently running run.sh keeps its own inode
#   * no credentials are read, written or echoed; no service is restarted
#   * ASCII-safe, LF only, UTF-8 without BOM
#   * each edit is followed by a syntax check and a behavioural check; a missing
#     or ambiguous anchor aborts with a non-zero status instead of guessing
#   * if <file>.orig exists, the behaviour check is also run against it in the
#     opposite direction, so "the patch is actually load-bearing" is proven
# =============================================================================
set -euo pipefail

SCRIPT_NAME='patch-panweidb-image.sh'
MARKER='patched-by-agent'

SOFT_ROOT="${PANWEIDB_SOFT_ROOT:-/database/panweidb/soft}"
RUN_SH="${PANWEIDB_RUN_SH:-/root/run.sh}"
NET_SCRIPTS="${PANWEIDB_NET_SCRIPTS:-/etc/sysconfig/network-scripts}"
CONTAINER="${PANWEIDB_CONTAINER:-}"

PREINSTALL="$SOFT_ROOT/script/local/PreInstallUtility.py"
COMMON_PY="$SOFT_ROOT/script/gspylib/common/Common.py"

HELPER="${TMPDIR:-/tmp}/pwfix-helper-$$.py"

say()  { printf '%s\n' "$*"; }
step() { printf '\n== [%s/5] %s\n' "$1" "$2"; }
info() { printf '   %s\n' "$*"; }
warn() { printf '   WARN: %s\n' "$*" >&2; }
die()  { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'USAGE'
patch-panweidb-image.sh -- replayable patch set for the PanWeiDB 3.4.0 image

Usage:
  bash patch-panweidb-image.sh [--container NAME]

Options:
  --container NAME   run on a docker host: copy this script into NAME and
                     execute it there (equivalent to docker cp + docker exec)
  -h, --help         show this help

Environment overrides (defaults are the deployed container layout):
  PANWEIDB_SOFT_ROOT    default /database/panweidb/soft
  PANWEIDB_RUN_SH       default /root/run.sh
  PANWEIDB_NET_SCRIPTS  default /etc/sysconfig/network-scripts
  PANWEIDB_PYTHON       default: first of python3, python
USAGE
}

while [ $# -gt 0 ]; do
    case "$1" in
        --container)
            [ $# -ge 2 ] || die "--container needs a container name"
            CONTAINER="$2"; shift 2 ;;
        -h|--help)
            usage; exit 0 ;;
        *)
            usage >&2; die "unknown argument: $1" ;;
    esac
done

# --- host mode: hand the work to the container -------------------------------
if [ ! -d "$SOFT_ROOT/script" ] && [ -n "$CONTAINER" ]; then
    self="${BASH_SOURCE[0]:-}"
    [ -f "$self" ] || die "cannot re-exec: script is not a readable file on disk (piped input?); copy it into the container and run it there"
    command -v docker >/dev/null 2>&1 || die "docker not found on this host"
    say "host mode: docker cp $(basename "$self") -> $CONTAINER:/root/$SCRIPT_NAME, then exec inside"
    docker cp "$self" "$CONTAINER:/root/$SCRIPT_NAME"
    exec docker exec "$CONTAINER" bash "/root/$SCRIPT_NAME"
fi

# --- interpreter, privileges, preconditions ----------------------------------
if [ -n "${PANWEIDB_PYTHON:-}" ]; then
    PY="$PANWEIDB_PYTHON"
elif command -v python3 >/dev/null 2>&1; then
    PY="$(command -v python3)"
elif command -v python >/dev/null 2>&1; then
    PY="$(command -v python)"
else
    PY=""
fi
[ -n "$PY" ] || die "no python3/python found; the vendor installer is python, so this is unexpected"
"$PY" -c 'pass' 2>/dev/null || die "python interpreter '$PY' is not usable"

[ "$(id -u)" = '0' ] || die "must run as root inside the container (patches $RUN_SH and omm-owned files)"

trap 'rm -f "$HELPER"' EXIT

step 0 "environment"
info "soft root      : $SOFT_ROOT"
info "run.sh         : $RUN_SH"
info "network-scripts: $NET_SCRIPTS"
info "python         : $PY ($("$PY" -c 'import sys; print(".".join(map(str, sys.version_info[:3])))'))"
info "cgroup         : $(tr '\n' ' ' < /proc/1/cgroup 2>/dev/null || echo unavailable)"
if [ -e /.dockerenv ]; then
    info "container mark : /.dockerenv present"
else
    info "container mark : /.dockerenv absent"
fi

[ -d "$SOFT_ROOT/script" ] || die "$SOFT_ROOT/script not found -- the vendor tarball has not been unpacked yet.
       Wait for it and re-run, or start the container first. See the header for the recipe."

# --- the patch helper ---------------------------------------------------------
# One embedded helper does all three edits: byte-exact, atomic, ownership-safe.
write_helper() {
    cat > "$HELPER" <<'PYEOF'
import os
import stat
import sys
import tempfile

# Inserted by patch 3; must stay byte-identical to what the verified deployment
# wrote into /root/run.sh (tab indent, single backslash before the sed text).
RUN_SH_BLOCK = (
    b"\tip=$(hostname -I | awk '{print $1}')\n"
    b"\tsed -i \"/^listen_addresses/c\\listen_addresses = 'localhost,${ip}'\""
    b" /database/panweidb/data/postgresql.conf\n"
)

# Inserted by patch 2. This is a superset of what the running container has
# (it checked /.dockerenv only); /run/.containerenv additionally covers
# podman-style runtimes and costs nothing.
DOCKER_ENV_BLOCK = (
    b"        # patched-by-agent: cgroup v2 hosts report \"0::/init.scope\" in /proc/1/cgroup,\n"
    b"        # so the cgroup v1 grep in this function can never match. Fall back to the\n"
    b"        # container marker files, otherwise the 9 \"tolerable inside a container\"\n"
    b"        # branches (LocalCheckOS / LocalCheck / inspection items) stay unreachable.\n"
    b"        # Evidence: gs_checkos GAUSS-51400 (network-scripts) + GAUSS-50612 (ethtool).\n"
    b"        if os.path.exists(\"/.dockerenv\") or os.path.exists(\"/run/.containerenv\"):\n"
    b"            return True\n"
)


def fail(msg):
    sys.stderr.write('helper: %s\n' % msg)
    sys.exit(2)


def atomic_write(path, data):
    """Replace path's contents, preserving mode and ownership (atomic rename)."""
    st = os.stat(path)
    directory = os.path.dirname(path) or '.'
    fd, tmp = tempfile.mkstemp(dir=directory, prefix='.pwfix-')
    try:
        with os.fdopen(fd, 'wb') as fh:
            fh.write(data)
        os.chmod(tmp, stat.S_IMODE(st.st_mode))
        try:
            os.chown(tmp, st.st_uid, st.st_gid)
        except OSError:
            # only root may chown; a non-root run already owns the temp file
            pass
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def replace_once(path, old, new):
    data = open(path, 'rb').read()
    count = data.count(old)
    if count != 1:
        fail('expected exactly 1 occurrence of %r in %s, found %d'
             % (old[:60], path, count))
    atomic_write(path, data.replace(old, new, 1))


def insert_before_line(path, anchor, block):
    data = open(path, 'rb').read()
    count = data.count(anchor)
    if count != 1:
        fail('expected exactly 1 occurrence of %r in %s, found %d'
             % (anchor, path, count))
    pos = data.find(anchor)
    line_start = data.rfind(b'\n', 0, pos) + 1
    atomic_write(path, data[:line_start] + block + data[line_start:])


def insert_after_line(path, anchor, block):
    data = open(path, 'rb').read()
    count = data.count(anchor)
    if count != 1:
        fail('expected exactly 1 occurrence of %r in %s, found %d'
             % (anchor, path, count))
    atomic_write(path, data.replace(anchor, anchor + block, 1))


def main():
    if len(sys.argv) != 3:
        fail('usage: helper.py MODE PATH')
    mode, path = sys.argv[1], sys.argv[2]
    if mode == 'cpu-precheck':
        # patch 1: the terminal raise becomes a no-op. Anchor text verified
        # against the pristine file extracted from PanWeiDB_...-om.tar.gz.
        replace_once(path,
                     b'            raise Exception(cpu_mission)\n',
                     b'            pass  # patched-by-agent\n')
    elif mode == 'docker-env':
        insert_after_line(path, b'    def checkDockerEnv():\n', DOCKER_ENV_BLOCK)
    elif mode == 'run-sh-ip':
        insert_before_line(path, b'su - omm -c "gs_ctl start"\n', RUN_SH_BLOCK)
    else:
        fail('unknown mode: %s' % mode)
    print('   helper: %s applied to %s' % (mode, path))


main()
PYEOF
    [ -s "$HELPER" ] || die "failed to write the patch helper to $HELPER"
}

backup_once() {
    local f="$1"
    if [ -e "$f.orig" ]; then
        info "pristine backup kept as-is: $f.orig"
    else
        cp -p "$f" "$f.orig"
        info "pristine backup created: $f.orig"
    fi
}

syntax_check() {
    local f="$1"
    "$PY" -c 'import sys; compile(open(sys.argv[1], "rb").read(), sys.argv[1], "exec")' "$f" \
        || die "python syntax check failed after patching $f"
    info "syntax check ok (compile)"
}

# Behavioural probes: run the real function body lifted out of the file, with the
# module imports and a stub `self` injected. This is what makes the patch
# verified rather than assumed.
behaviour_cpu_precheck() {
    local f="$1" expect="$2"
    "$PY" - "$f" "$expect" <<'PYEOF'
import ast
import platform
import subprocess
import sys

path, expect = sys.argv[1], sys.argv[2]
tree = ast.parse(open(path, 'rb').read(), path)
target = None
for node in ast.walk(tree):
    if isinstance(node, ast.FunctionDef) and node.name == 'check_cpu_instructions':
        target = node
        break
if target is None:
    sys.stderr.write('probe: check_cpu_instructions() not found in %s\n' % path)
    sys.exit(2)
target.decorator_list = []
try:
    module = ast.Module(body=[target], type_ignores=[])
except TypeError:                     # python < 3.8 has no type_ignores field
    module = ast.Module(body=[target])
ns = {'platform': platform, 'subprocess': subprocess}


class _Log(object):
    def debug(self, *a, **k):
        pass


class _Self(object):
    logger = _Log()
    user = 'omm'


exec(compile(ast.fix_missing_locations(module), '<check_cpu_instructions>', 'exec'), ns)

raised = None
try:
    ns['check_cpu_instructions'](_Self())
except BaseException as exc:          # noqa: BLE001 - the raise IS the signal
    raised = exc

if expect == 'ok':
    if raised is not None:
        sys.stderr.write('probe FAIL: precheck still raises on this host: %r\n' % (raised,))
        sys.exit(1)
    print('   probe: check_cpu_instructions() returns cleanly (patch is in effect)')
elif expect == 'raises':
    if raised is None:
        sys.stderr.write('probe FAIL: pristine file did NOT raise, so the patch is not load-bearing\n')
        sys.exit(1)
    print('   probe: pristine check_cpu_instructions() raises as expected ->'
          ' the patch is load-bearing')
else:
    sys.stderr.write('probe: bad expect=%r\n' % expect)
    sys.exit(2)
PYEOF
}

behaviour_docker_env() {
    local f="$1" expect="$2"
    "$PY" - "$f" "$expect" <<'PYEOF'
import ast
import os
import subprocess
import sys

path, expect = sys.argv[1], sys.argv[2]
tree = ast.parse(open(path, 'rb').read(), path)
target = None
for node in ast.walk(tree):
    if isinstance(node, ast.FunctionDef) and node.name == 'checkDockerEnv':
        target = node
        break
if target is None:
    sys.stderr.write('probe: checkDockerEnv() not found in %s\n' % path)
    sys.exit(2)
target.decorator_list = []
try:
    module = ast.Module(body=[target], type_ignores=[])
except TypeError:                     # python < 3.8 has no type_ignores field
    module = ast.Module(body=[target])
ns = {'os': os, 'subprocess': subprocess}
exec(compile(ast.fix_missing_locations(module), '<checkDockerEnv>', 'exec'), ns)

got = ns['checkDockerEnv']()
if expect == 'true':
    if got is not True:
        sys.stderr.write('probe FAIL: checkDockerEnv() -> %r inside a container (want True)\n' % (got,))
        sys.exit(1)
    print('   probe: checkDockerEnv() -> True (patch is in effect)')
elif expect == 'false':
    if got is not False:
        sys.stderr.write('probe FAIL: pristine checkDockerEnv() -> %r (want False)\n' % (got,))
        sys.exit(1)
    print('   probe: pristine checkDockerEnv() -> False as expected ->'
          ' the patch is load-bearing')
else:
    sys.stderr.write('probe: bad expect=%r\n' % expect)
    sys.exit(2)
PYEOF
}

# --- patch 1: CPU precheck ----------------------------------------------------
step 1 "patch(1) CPU precheck bypass"
[ -f "$PREINSTALL" ] || die "not found: $PREINSTALL"
write_helper
if grep -q "$MARKER" "$PREINSTALL"; then
    info "already patched (marker '$MARKER' present) -- no write"
else
    backup_once "$PREINSTALL"
    "$PY" "$HELPER" cpu-precheck "$PREINSTALL"
    syntax_check "$PREINSTALL"
fi
behaviour_cpu_precheck "$PREINSTALL" ok
if [ -f "$PREINSTALL.orig" ]; then
    behaviour_cpu_precheck "$PREINSTALL.orig" raises
else
    warn "no $PREINSTALL.orig -- reverse (pristine-must-raise) probe skipped"
fi

# --- patch 2: checkDockerEnv --------------------------------------------------
step 2 "patch(2) checkDockerEnv() cgroup-v2 fallback"
[ -f "$COMMON_PY" ] || die "not found: $COMMON_PY"
if grep -q "$MARKER" "$COMMON_PY"; then
    info "already patched (marker '$MARKER' present) -- no write"
else
    backup_once "$COMMON_PY"
    "$PY" "$HELPER" docker-env "$COMMON_PY"
    syntax_check "$COMMON_PY"
fi
behaviour_docker_env "$COMMON_PY" true
if [ -f "$COMMON_PY.orig" ]; then
    behaviour_docker_env "$COMMON_PY.orig" false
else
    warn "no $COMMON_PY.orig -- reverse (pristine-must-be-False) probe skipped"
fi

# --- patch 2b: network-scripts directory --------------------------------------
step 3 "patch(2b) create the empty $NET_SCRIPTS directory"
if [ -d "$NET_SCRIPTS" ]; then
    info "already present"
else
    mkdir -p "$NET_SCRIPTS"
    info "created"
fi
entries="$(ls -A "$NET_SCRIPTS" 2>/dev/null | wc -l || true)"
info "entry count: ${entries:-unknown} (an EMPTY directory is what makes find exit 0)"

# --- patch 3: listen_addresses rewrite ---------------------------------------
step 4 "patch(3) listen_addresses rewrite by current IP"
[ -f "$RUN_SH" ] || die "not found: $RUN_SH"
if grep -qF "listen_addresses = 'localhost," "$RUN_SH"; then
    info "already patched (listen_addresses rewrite present) -- no write"
else
    backup_once "$RUN_SH"
    "$PY" "$HELPER" run-sh-ip "$RUN_SH"
    info "inserted the rewrite before the start branch"
fi
if bash -n "$RUN_SH" 2>/dev/null; then
    info "syntax check ok (bash -n)"
else
    die "bash -n failed on $RUN_SH after patching"
fi
info "current IP would be: $(hostname -I 2>/dev/null | awk '{print $1}' || true)"
info "listen_addresses line(s) now in $RUN_SH:"
grep -n 'listen_addresses' "$RUN_SH" 2>/dev/null | sed 's/^/     /' || true

# --- summary ------------------------------------------------------------------
step 5 "summary"
for f in "$PREINSTALL" "$COMMON_PY" "$RUN_SH"; do
    if command -v sha256sum >/dev/null 2>&1; then
        info "$(sha256sum "$f")"
    else
        info "$f"
    fi
done
say ''
say 'done. patches 1/2/2b live in the data volume; patch 3 lives in the image layer.'
say 'No service was restarted and no database data was touched.'
