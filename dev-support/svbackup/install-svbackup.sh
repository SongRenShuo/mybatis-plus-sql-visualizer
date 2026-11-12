#!/usr/bin/env bash
# install-svbackup.sh —— 把 dev-support/svbackup/ 的备份体系部署到目标服务器（幂等）
#
# 用法（在服务器上，先把本目录整个上传，例如 scp -r dev-support/svbackup root@host:/root/svbackup-src）：
#   bash /root/svbackup-src/install-svbackup.sh
# 或从仓库本地经 DBX SFTP 逐文件上传后，在服务器执行本脚本。
#
# 前置（脚本会检查、缺就明确报错，不代填）：
#   - /root/.local/bin/restic 0.19.1（本机 GitHub 必断，走「本地代理下载 .bz2 → SFTP 上传 → python bz2 解包」）
#   - /etc/svbackup/credentials（600，AWS_* / RESTIC_REPOSITORY）
#   - /etc/svbackup/pass（600，restic 仓库口令；重装后需从离线副本恢复）
#   - /etc/svbackup/db.env（600，各库口令）
#   - /etc/svbackup/notify-token 与 notify-api（可选，缺则告警静默跳过）
#
# 本脚本只动：/usr/local/bin/svbackup-*.sh、/etc/systemd/system/svbackup*、
# /var/backups/svbackup/ 目录；不动凭据、不动数据库容器、不动 restic 仓库。

set -uo pipefail
SRC="$(cd "$(dirname "$0")" && pwd)"
DST=/usr/local/bin
SYSD=/etc/systemd/system

echo "== install svbackup from $SRC =="
[ -x /root/.local/bin/restic ] || echo "WARN: 缺 /root/.local/bin/restic（需先装 0.19.1）"
[ -r /etc/svbackup/credentials ] || echo "WARN: 缺 /etc/svbackup/credentials"
[ -r /etc/svbackup/pass ] || echo "WARN: 缺 /etc/svbackup/pass（旧仓库口令，重装后无法读旧快照）"
[ -r /etc/svbackup/db.env ] || echo "WARN: 缺 /etc/svbackup/db.env"

for f in svbackup-collect.sh svbackup-run.sh svbackup-notify.sh; do
  install -m 700 "$SRC/$f" "$DST/$f" && echo "  安装 $DST/$f"
done
for f in svbackup.service svbackup.timer svbackup-alert@.service; do
  install -m 644 "$SRC/$f" "$SYSD/$f" && echo "  安装 $SYSD/$f"
done
mkdir -p /var/backups/svbackup/{dumps,logs}

systemctl daemon-reload
systemctl enable svbackup.timer
systemctl start svbackup.timer
echo "== timer 状态 =="
systemctl list-timers svbackup.timer --no-pager || true
echo
echo "== 手工跑一次（会写仓库，确认口令就位后再执行） =="
echo "  systemctl start svbackup.service && journalctl -u svbackup -n 50 --no-pager"
echo
echo "== 验收清单 =="
echo "  1) restic version = 0.19.1"
echo "  2) systemctl list-timers 的 LAST 在首跑后不再是 n/a"
echo "  3) set -a; . /etc/svbackup/credentials; set +a ; /root/.local/bin/restic -o s3.region=cn-east-1 --password-file /etc/svbackup/pass snapshots 有快照"
echo "  4) 按 .agents/skills/srv-backup-restore/SKILL.md §3.2 做一次单库恢复演练并比对行数"
