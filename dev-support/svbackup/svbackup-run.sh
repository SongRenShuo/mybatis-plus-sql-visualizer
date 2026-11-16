#!/usr/bin/env bash
# svbackup-run.sh —— 每日备份主流程：采集 → 上传 → 滚动保留 → 容量闸门 → 失败告警
#
# 维护位置：插件仓 dev-support/svbackup/（仓库资产）。
# 由 svbackup.service 调用（Environment="HOME=/root" + XDG_CACHE_HOME，systemd 下无 HOME 会报
# "unable to locate cache directory"）。也可手工 `systemctl start svbackup.service`。
# 仓库口令走 /etc/svbackup/pass（600，--password-file），AK/SK 走 /etc/svbackup/credentials。
# 口令不落脚本、不落 shell 历史；口令丢失时 restic 会报 wrong password or no key found，
# 此时本脚本 rc=1 并告警，不会静默假绿。

set -uo pipefail

CRED=/etc/svbackup/credentials
PASSFILE=/etc/svbackup/pass
NOTIFY=/usr/local/bin/svbackup-notify.sh
COLLECT=/usr/local/bin/svbackup-collect.sh
R=/root/.local/bin/restic
O="-o s3.region=cn-east-1"
LIMIT=$((9*1024*1024*1024))          # 9GB 容量闸门（七牛免费额度 0-10GB）
KEEP=7
LOGDIR=/var/backups/svbackup/logs
RUNLOG="$LOGDIR/run-$(date -u +%Y%m%d-%H%M%S).log"
mkdir -p "$LOGDIR"

log() { printf '[%s] %s\n' "$(date -u +%F' '%T)" "$*" | tee -a "$RUNLOG"; }
fail() { log "FAIL $*"; [ -x "$NOTIFY" ] && "$NOTIFY" high "备份失败" "$*" >/dev/null 2>&1 || true; exit 1; }

[ -r "$CRED" ] || fail "缺 $CRED（600 文件）"
[ -r "$PASSFILE" ] || fail "缺 $PASSFILE（restic 仓库口令，重装后需从离线副本恢复）"
[ -x "$R" ] || fail "缺 $R"
set -a; . "$CRED"; set +a
export HOME=/root XDG_CACHE_HOME=/root/.cache

# ── 1/4 采集 ────────────────────────────────────────────────────────────────
log "1/4 采集"
"$COLLECT" >>"$RUNLOG" 2>&1 || log "采集器返回非 0，继续（单库失败不阻断）"
DATE="$(date -u +%F)"
SRC="/var/backups/svbackup/dumps/$DATE"
[ -d "$SRC" ] || fail "采集产物不存在：$SRC"
grep -q 'FAIL ' "$LOGDIR/collect-$DATE.log" 2>/dev/null && \
  [ -x "$NOTIFY" ] && "$NOTIFY" warn "备份采集有失败项" "见 collect-$DATE.log" >/dev/null 2>&1 || true

# ── 2/4 上传 ────────────────────────────────────────────────────────────────
log "2/4 上传 $SRC"
if ! "$R" $O --password-file "$PASSFILE" backup "$SRC" \
      --limit-upload 3000 --retry-lock 10m --tag "date=$DATE" >>"$RUNLOG" 2>&1; then
  fail "restic backup 失败（口令不对 / AK/SK 失效 / 网络），见 $RUNLOG"
fi

# ── 3/4 滚动保留 ────────────────────────────────────────────────────────────
log "3/4 forget --keep-last $KEEP --prune"
# backup 与 forget 之间可能撞上同一个锁（刚 init 的仓库尤其常见），显式等锁
"$R" $O --password-file "$PASSFILE" --retry-lock 2m forget --keep-last "$KEEP" \
  --prune --max-repack-size 2G >>"$RUNLOG" 2>&1 || log "prune 返回非 0，继续做容量闸门"

# ── 4/4 容量闸门 ────────────────────────────────────────────────────────────
# stats 输出形如 "     Total Size: 12.343 KiB"；$3 是数值、$4 是单位。
# 旧版把整行当 $3 再 tr，会因前导空格让 case 匹配不上「KiB」，实测踩过。
read -r num unit <<<"$($R $O --password-file "$PASSFILE" stats --mode raw-data 2>/dev/null \
  | awk '/Total Size:/{print $3, $4}')"
bytes="${num} ${unit}"
case "$unit" in
  KiB|kb) mult=1024 ;; MiB|mb) mult=$((1024*1024)) ;; GiB|gb) mult=$((1024*1024*1024)) ;;
  *) mult=0 ;;
esac
if [ "$mult" = 0 ]; then
  log "仓库体积读数解析失败（$bytes），跳过容量闸门，请人工核对"
  [ -x "$NOTIFY" ] && "$NOTIFY" warn "仓库体积读数异常" "$bytes" >/dev/null 2>&1 || true
else
  total="$(awk -v n="$num" -v m="$mult" 'BEGIN{printf "%.0f", n*m}')"
  log "仓库体积 $total B（闸门 $LIMIT B）"
  if [ "$total" -gt "$LIMIT" ]; then
    log "超 9GB：先降档到 keep-last 3 + prune 4G"
    "$R" $O --password-file "$PASSFILE" forget --keep-last 3 \
      --prune --max-repack-size 4G >>"$RUNLOG" 2>&1 || true
    read -r num2 unit2 <<<"$($R $O --password-file "$PASSFILE" stats --mode raw-data 2>/dev/null \
      | awk '/Total Size:/{print $3, $4}')"
    bytes2="${num2} ${unit2}"
    case "$unit2" in KiB|kb) m2=1024 ;; MiB|mb) m2=$((1024*1024)) ;; GiB|gb) m2=$((1024*1024*1024)) ;; *) m2=0 ;; esac
    total2=0; [ "$m2" != 0 ] && total2="$(awk -v n="$num2" -v m="$m2" 'BEGIN{printf "%.0f", n*m}')"
    if [ "$m2" = 0 ] || [ "$total2" -gt "$LIMIT" ]; then
      fail "降档后仍超 9GB（${bytes2}），中止并告警，不产生扣费"
    fi
  fi
fi

log "完成 rc=0（$RUNLOG）"
exit 0
