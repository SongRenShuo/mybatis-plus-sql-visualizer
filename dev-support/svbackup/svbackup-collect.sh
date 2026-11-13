#!/usr/bin/env bash
# svbackup-collect.sh —— 枚举运行中的 Docker 容器，按镜像族做逻辑导出 + 元数据快照
#
# 维护位置：插件仓 dev-support/svbackup/（仓库资产，不再只放服务器）。
# 部署到服务器：bash dev-support/svbackup/install-svbackup.sh（见同目录 README.md）。
# 凭据：/etc/svbackup/credentials（AWS_* / RESTIC_REPOSITORY）与 /etc/svbackup/db.env（各库口令），均 600。
# 输出：/var/backups/svbackup/dumps/<日期>/{dbs,meta,volumes}/，本地只留最近 2 代。
#
# 设计口径（见 .agents/skills/srv-backup-restore/SKILL.md）：
#   - 逻辑导出不压缩，跨天去重交给 restic；
#   - 镜像名匹配引擎族：mysql/mariadb/oceanbase/tidb/percona、postgres/opengauss/gaussdb/
#     vastbase/highgo/kingbase/dameng/dm8、mssql；
#   - 库名靠 SHOW DATABASES / pg_database / sys.databases 自动枚举，不写死 xc3_test；
#   - 未知族：**默认 SKIP，不做文件级 tar**（2026-11-08 PanWeiDB 事故：整卷 tar 两天打爆 10GB 额度），
#     只记一条告警；确需文件级备份的容器在 SKIP_FILE_LEVEL_TAR=0 时才会走旧分支。
#   - 每库最多 2 次尝试（源库悬挂索引等偶发问题），间隔 3 秒；导出命令 stdin 接 /dev/null 防交互等口令。

set -uo pipefail

DATE="$(date -u +%F)"
ROOT="/var/backups/svbackup/dumps/$DATE"
DBS="$ROOT/dbs"; META="$ROOT/meta"; VOL="$ROOT/volumes"
LOGDIR="/var/backups/svbackup/logs"
mkdir -p "$DBS" "$META" "$VOL" "$LOGDIR"

R=/root/.local/bin/restic       # 本脚本不直接调 restic，仅保留路径供 run 阶段用
CRED=/etc/svbackup/credentials
DBENV=/etc/svbackup/db.env
[ -r "$CRED" ] && { set -a; . "$CRED"; set +a; }
[ -r "$DBENV" ] && { set -a; . "$DBENV"; set +a; }

SKIP_FILE_LEVEL_TAR="${SKIP_FILE_LEVEL_TAR:-1}"   # 1=未知族只告警不打包（默认，防仓库膨胀）
ENUM_TIMEOUT=90
DUMP_TIMEOUT=300

log()  { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "$LOGDIR/collect-$DATE.log"; }
warn() { printf '[%s] WARN %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "$LOGDIR/collect-$DATE.log" >&2; }

# ── 凭据查表：容器名 → 用户名/口令（全部来自 /etc/svbackup/db.env，不写死） ──────────
dbuser_for() {
  case "$1" in
    *mariadb*|*mysql8*)   printf '%s' "${MDB_USER:-root}" ;;
    *oceanbase*)          printf '%s' "${OB_USER:-xc3@test}" ;;
    *highgo*)             printf '%s' "${HG_USER:-highgo}" ;;
    *vastbase*)           printf '%s' "${VB_USER:-xc3}" ;;
    *gaussdb*)            printf '%s' "${GAUSS_USER:-xc3}" ;;
    *opengauss*)          printf '%s' "${OG_USER:-opengauss}" ;;
    *mssql*)              printf '%s' "${MSSQL_USER:-sa}" ;;
    *)                    printf '%s' "" ;;
  esac
}
dbpass_for() {
  case "$1" in
    *mariadb*)            printf '%s' "${MDB_PASS:-${MDB_ROOT_PASS:-}}" ;;
    *mysql8*)             printf '%s' "${MYSQL8_PASS:-}" ;;
    *oceanbase*)          printf '%s' "${OB_PASS:-}" ;;
    *highgo*)             printf '%s' "${HG_PASS:-}" ;;
    *vastbase*)           printf '%s' "${VB_PASS:-}" ;;
    *gaussdb*)            printf '%s' "$(grep -E '^GAUSS_[A-Z_]*PASS=' "$DBENV" 2>/dev/null | head -1 | cut -d= -f2-)" ;;
    *opengauss*)          printf '%s' "${OG_PASS:-}" ;;
    *mssql*)              printf '%s' "${MSSQL_PASS:-}" ;;
    *)                    printf '%s' "" ;;
  esac
}

# ── 工具路径探测：不同镜像的可执行文件位置不一样 ────────────────────────────────
# 国产库的 pg_dump/gs_dump 常装在 /opt/... 且不在 PATH 里，光用 command -v 会漏
# （2026-11-12 实测 Vastbase 两个实例都报「找不到 psql/gsql」）。
# 传进来的候选既可以是绝对路径（走 test -x），也可以是命令名（走 command -v）。
probe_tool() {
  local c="$1" p out; shift
  for p in "$@"; do
    case "$p" in
      /*) out="$(docker exec "$c" sh -c "[ -x '$p' ] && echo '$p'" 2>/dev/null | tail -1)" ;;
      *)  out="$(docker exec "$c" sh -c "command -v '$p' >/dev/null 2>&1 && echo '$p'" 2>/dev/null | tail -1)" ;;
    esac
    [ -n "$out" ] && { printf '%s' "$out"; return 0; }
  done
  return 1
}

# ── 元数据快照（整机重建时靠它恢复容器形态） ──────────────────────────────────
log "采集元数据"
docker ps --format '{{.Names}}' > "$META/containers.txt"
docker images --digests --format '{{.Repository}}:{{.Tag}} {{.Digest}} {{.ID}} {{.Size}}' > "$META/docker-images.txt"
docker inspect $(docker ps -aq) > "$META/docker-inspect.json" 2>/dev/null || true
for c in $(cat "$META/containers.txt"); do
  # start.sh 是海量/磐维等实例的停起入口，单独留一份
  for f in /opt/xc3-vastbase/start.sh /opt/xc3-vastbase-b/start.sh; do
    if docker exec "$c" sh -c "[ -f '$f' ]" >/dev/null 2>&1; then
      mkdir -p "$META/start-scripts/$c"
      docker cp "$c:$f" "$META/start-scripts/$c/$(basename "$(dirname "$f")")-$(basename "$f")" >/dev/null 2>&1 || true
    fi
  done
done
cp /etc/docker/daemon.json "$META/daemon.json" 2>/dev/null || true
crontab -l > "$META/root-crontab.txt" 2>/dev/null || true
tar -C "$META" -cf "$ROOT/meta.tar" . 2>/dev/null || true
zstd -q -f "$ROOT/meta.tar" -o "$ROOT/meta.tar.zst" 2>/dev/null && rm -f "$ROOT/meta.tar" || true

# ── 逐容器逻辑导出 ───────────────────────────────────────────────────────────
FAIL=0
for c in $(cat "$META/containers.txt"); do
  img="$(docker inspect -f '{{.Config.Image}}' "$c" 2>/dev/null)"
  low="$(printf '%s' "$img" | tr 'A-Z' ' ' | tr '[:upper:]' '[:lower:]')"
  user="$(dbuser_for "$c")"; pass="$(dbpass_for "$c")"
  log "→ $c [$img]"

  case "$low" in
    *mysql*|*mariadb*|*oceanbase*|*tidb*|*percona*)
      # mysqldump 在容器内的常见位置；OceanBase 的 MySQL 协议也可从宿主连
      tool="$(probe_tool "$c" mysqldump mariadb-dump /usr/bin/mysqldump 2>/dev/null || true)"
      if [ -z "$tool" ]; then warn "→ $c 找不到 mysqldump，跳过（FAIL）"; FAIL=$((FAIL+1)); continue; fi
      port="$(docker port "$c" 2>/dev/null | grep -m1 '/tcp' | sed -E 's/.*:([0-9]+)$/\1/')"
      dbs="$(timeout "$ENUM_TIMEOUT" docker exec -e MYSQL_PWD="$pass" "$c" sh -c \
        "'$tool' -h127.0.0.1 -u'$user' --no-tablespaces --skip-column-statistics -N -e 'SHOW DATABASES' 2>/dev/null" </dev/null \
        | grep -vE '^(information_schema|performance_schema|mysql|sys|test)$' || true)"
      for db in $dbs; do
        [ -n "$db" ] || continue
        out="$DBS/${c}__${db}.sql"
        ok=0
        for attempt in 1 2; do
          if timeout "$DUMP_TIMEOUT" docker exec -e MYSQL_PWD="$pass" "$c" sh -c \
            "'$tool' -h127.0.0.1 -u'$user' --no-tablespaces --skip-column-statistics --databases '$db'" </dev/null > "$out" 2>"$LOGDIR/${c}__${db}.log"; then
            ok=1; break
          fi
          sleep 3
        done
        if [ "$ok" = 1 ] && [ -s "$out" ]; then log "  OK $db $(wc -c < "$out")B"
        else warn "  FAIL $db（见 ${c}__${db}.log）"; rm -f "$out"; FAIL=$((FAIL+1)); fi
      done
      ;;

    *postgres*|*opengauss*|*gaussdb*|*vastbase*|*highgo*|*kingbase*|*dameng*|*dm8*)
      # gs_dump 优先、pg_dump 兜底；老版 pg_dump 不认 -d，库名一律走位置参数
      tool="$(probe_tool "$c" \
        /opt/gaussdb/app/bin/gs_dump /usr/local/opengauss/bin/gs_dump \
        /home/omm/vbdata/install/vastbase/bin/gs_dump \
        /opt/HighGo6.0.1-cluster/bin/pg_dump /usr/bin/pg_dump 2>/dev/null || true)"
      if [ -z "$tool" ]; then warn "→ $c 找不到 gs_dump/pg_dump，跳过（FAIL）"; FAIL=$((FAIL+1)); continue; fi
      lib=""
      case "$low" in
        *gaussdb*)  lib="/opt/gaussdb/app/lib" ;;
        *opengauss*) lib="/usr/local/opengauss/lib" ;;
        *vastbase*) lib="/home/omm/vbdata/install/vastbase/lib" ;;
        *highgo*)   lib="/opt/HighGo6.0.1-cluster/lib" ;;
      esac
      # 库名枚举走 psql/gsql/vsql 的 -Atc（gs_dump 不负责列库）。
      # 候选给绝对路径（probe_tool 走容器内 test -x），别在宿主机上 [ -x ] 判。
      client="$(probe_tool "$c" \
        /opt/gaussdb/app/bin/gsql /usr/local/opengauss/bin/gsql \
        /home/omm/vbdata/install/vastbase/bin/vsql \
        /opt/HighGo6.0.1-cluster/bin/psql \
        /usr/bin/psql psql gsql vsql 2>/dev/null || true)"
      if [ -z "$client" ]; then warn "→ $c 找不到 psql/gsql/vsql，跳过（FAIL）"; FAIL=$((FAIL+1)); continue; fi
      dbs="$(timeout "$ENUM_TIMEOUT" docker exec -e PGPASSWORD="$pass" -e LD_LIBRARY_PATH="$lib" "$c" \
        sh -c "'$client' -h127.0.0.1 -U'$user' -d postgres -Atc 'SELECT datname FROM pg_database WHERE datistemplate = false' 2>/dev/null" </dev/null \
        | grep -vE '^(postgres|template0|template1)$' || true)"
      # 瀚高没有 postgres 库，改用 template1 枚举
      if [ -z "$dbs" ] && printf '%s' "$low" | grep -q highgo; then
        dbs="$(timeout "$ENUM_TIMEOUT" docker exec -e PGPASSWORD="$pass" -e LD_LIBRARY_PATH="$lib" "$c" \
          sh -c "'$client' -h127.0.0.1 -U'$user' -d template1 -Atc 'SELECT datname FROM pg_database WHERE datistemplate = false' 2>/dev/null" </dev/null \
          | grep -vE '^(highgo|template0|template1)$' || true)"
      fi
      for db in $dbs; do
        [ -n "$db" ] || continue
        out="$DBS/${c}__${db}.dump"
        ok=0
        for attempt in 1 2; do
          # 该版 -Fc 不允许写 stdout 时，改容器内 -f 再 docker cp（此处统一走容器内文件 + cp，最稳）
          if timeout "$DUMP_TIMEOUT" docker exec -e PGPASSWORD="$pass" -e LD_LIBRARY_PATH="$lib" "$c" \
            sh -c "'$tool' -h127.0.0.1 -U'$user' -Fc -f /tmp/svbackup.dump '$db' </dev/null" >/dev/null 2>"$LOGDIR/${c}__${db}.log"; then
            docker cp "$c:/tmp/svbackup.dump" "$out" >/dev/null 2>&1 && ok=1
            docker exec "$c" rm -f /tmp/svbackup.dump >/dev/null 2>&1 || true
            [ "$ok" = 1 ] && break
          fi
          sleep 3
        done
        if [ "$ok" = 1 ] && [ -s "$out" ]; then log "  OK $db $(wc -c < "$out")B"
        else warn "  FAIL $db（见 ${c}__${db}.log）"; rm -f "$out"; FAIL=$((FAIL+1)); fi
      done
      ;;

    *mssql*)
      tool="$(probe_tool "$c" /opt/mssql-tools18/bin/sqlcmd 2>/dev/null || true)"
      if [ -z "$tool" ]; then warn "→ $c 找不到 sqlcmd，跳过（FAIL）"; FAIL=$((FAIL+1)); continue; fi
      dbs="$(timeout "$ENUM_TIMEOUT" docker exec -e MSSQL_SA_PASSWORD="$pass" "$c" sh -c \
        "'$tool' -C -S localhost -U '$user' -P \"\$MSSQL_SA_PASSWORD\" -h -1 -W -Q \"SET NOCOUNT ON; SELECT name FROM sys.databases WHERE database_id > 4\" 2>/dev/null" </dev/null \
        | tr -d '\r' | grep -vE '^\s*$' || true)"
      for db in $dbs; do
        [ -n "$db" ] || continue
        out="$DBS/${c}__${db}.bak"
        bak="/var/opt/mssql/backup/svbackup.bak"
        if timeout "$DUMP_TIMEOUT" docker exec "$c" mkdir -p /var/opt/mssql/backup >/dev/null 2>&1 && \
           timeout "$DUMP_TIMEOUT" docker exec -e MSSQL_SA_PASSWORD="$pass" "$c" sh -c \
             "'$tool' -C -S localhost -U '$user' -P \"\$MSSQL_SA_PASSWORD\" -Q \"BACKUP DATABASE [$db] TO DISK = N'$bak' WITH INIT, COMPRESSION\" </dev/null" >"$LOGDIR/${c}__${db}.log" 2>&1 && \
           docker cp "$c:$bak" "$out" >/dev/null 2>&1; then
          log "  OK $db $(wc -c < "$out")B"
          docker exec "$c" rm -f "$bak" >/dev/null 2>&1 || true
        else
          warn "  FAIL $db（见 ${c}__${db}.log）"; rm -f "$out"; FAIL=$((FAIL+1))
        fi
      done
      ;;

    *)
      # 未知族：默认只告警。绝不做文件级整卷 tar——2026-11-08 PanWeiDB 事故把
      # 10GB 免费额度两天打爆（两个容器各 3GB 的 .tar.zst）。
      if [ "$SKIP_FILE_LEVEL_TAR" = "1" ]; then
        warn "→ $c [$img] 跳过：未知引擎族，不做文件级 tar（SKIP_FILE_LEVEL_TAR=1，避免原始数据卷撑爆仓库）"
      else
        warn "→ $c [$img] 未知族，按显式开关做文件级 tar（风险自负）"
        mounts="$(docker inspect -f '{{range .Mounts}}{{.Source}} {{end}}' "$c" 2>/dev/null)"
        for m in $mounts; do
          [ -d "$m" ] || continue
          tag="$(printf '%s' "$m" | tr '/' '_')"
          tar -C "$m" -cf - . 2>/dev/null | zstd -q -f -o "$VOL/${c}${tag}.tar.zst" || true
        done
      fi
      ;;
  esac
done

# ── 体积哨兵：单次 dump 超阈值就告警（早发现异常大文件） ──────────────────────
size="$(du -sm "$ROOT" | awk '{print $1}')"
log "本次 dump 体积 ${size}MB；FAIL 条目 $FAIL"
if [ "${size:-0}" -gt "${SVBACKUP_SIZE_WARN_MB:-500}" ]; then
  warn "本次 dump ${size}MB 超过阈值 ${SVBACKUP_SIZE_WARN_MB:-500}MB，请检查是否有未知族做了整卷 tar"
fi

# 本地只留最近 2 代
ls -1dt /var/backups/svbackup/dumps/20* 2>/dev/null | tail -n +3 | xargs -r rm -rf

exit 0   # 采集阶段不因单库失败而中断主流程；FAIL 数由 run 阶段读日志判告警
