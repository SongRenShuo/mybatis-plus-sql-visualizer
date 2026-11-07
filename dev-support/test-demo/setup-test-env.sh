#!/usr/bin/env bash
# Prepare the SQL Visualizer test demo and local MySQL databases.
#
# The full test shards include Demo* tests that hard-assert files in the sibling
# demo repository. testDatabase/testDb* and e2eFakeIde additionally need a local
# MySQL with the mpj_demo database.
#
# Usage:
#   bash dev-support/test-demo/setup-test-env.sh
#   CHECK_ONLY=1 bash dev-support/test-demo/setup-test-env.sh
#   DEMO_DIR=/path/to/demo SKIP_DB=1 bash dev-support/test-demo/setup-test-env.sh
#
# Environment:
#   DEMO_DIR         demo checkout (default: sibling mybatis-plus-sql-visualizer-demo)
#   DEMO_REVISION    revision to checkout (default: dev-support/test-demo/demo-revision.txt)
#   CHECK_ONLY=1     check only; do not clone or initialize databases
#   SKIP_DB=1        prepare demo only; do not initialize databases
#   REQUIRE_DB=1     fail when MySQL is unavailable
#   MYSQL_HOST       default 127.0.0.1
#   MYSQL_PORT       default 3306
#   MYSQL_USER       default root
#   MYSQL_PASSWORD   default $MYSQL_PWD (never written to disk)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEMO_REPO_URL="https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer-demo.git"
DEMO_DIR="${DEMO_DIR:-$(cd "$REPO_ROOT/.." && pwd)/mybatis-plus-sql-visualizer-demo}"
REVISION="${DEMO_REVISION:-$(tr -d '[:space:]' < "$SCRIPT_DIR/demo-revision.txt")}"
DEMO_DIR="${DEMO_DIR%/}"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${MYSQL_PWD:-}}"
MYSQL_BIN="${MYSQL_BIN:-mysql}"

step() { printf '[test-demo] %s\n' "$*"; }
warn() { printf '[test-demo] WARN: %s\n' "$*" >&2; }
fail() { printf '[test-demo] ERROR: %s\n' "$*" >&2; exit 1; }

step "repository : $REPO_ROOT"
step "demo dir   : $DEMO_DIR"
step "revision   : $REVISION"

# ---------- 1. demo checkout ----------
if [ ! -d "$DEMO_DIR" ]; then
  if [ "${CHECK_ONLY:-0}" = "1" ]; then
    fail "demo directory is missing. Run without CHECK_ONLY, or set DEMO_DIR."
  fi
  step "cloning demo repository..."
  git clone "$DEMO_REPO_URL" "$DEMO_DIR"
  git -C "$DEMO_DIR" checkout "$REVISION"
else
  if [ -d "$DEMO_DIR/.git" ]; then
    head="$(git -C "$DEMO_DIR" rev-parse --short HEAD 2>/dev/null || true)"
    if [ -n "$head" ] && [[ "$head" != "$REVISION"* ]]; then
      warn "demo HEAD ($head) differs from pinned revision ($REVISION); tests may drift."
    else
      step "demo revision OK (${head:-unknown})"
    fi
  else
    warn "demo directory exists but is not a git checkout; skip revision check."
  fi
fi

# ---------- 2. required files ----------
required=(
  "mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/service/impl/UserServiceImpl.java"
  "mybatis-plus-demo/src/main/resources/db/schema.sql"
  "mybatis-plus-join-demo/src/main/resources/db/schema.sql"
  "jpa-hibernate-demo/src/main/java/cn/song/jpademo"
  "jpa-embedded-prefix-demo/src/main/java/cn/song/embedprefixdemo/service"
  "jpa-jar-model-demo/src/main/java/cn/song/jarmodeldemo/service"
  "jpa-jar-model-lib/pom.xml"
)
missing=()
for rel in "${required[@]}"; do
  [ -e "$DEMO_DIR/$rel" ] || missing+=("$rel")
done
if [ "${#missing[@]}" -gt 0 ]; then
  printf '[test-demo] ERROR: demo checkout is incomplete; missing:\n' >&2
  printf '  %s\n' "${missing[@]}" >&2
  fail "check the pinned revision or update demo-revision.txt."
fi
step "required demo files OK"

# ---------- 3. IDE data source templates ----------
if [ "${CHECK_ONLY:-0}" != "1" ]; then
  mkdir -p "$DEMO_DIR/.idea"
  for name in dataSources.xml dataSources.local.xml; do
    if [ -e "$DEMO_DIR/.idea/$name" ]; then
      step "keep existing .idea/$name"
    else
      cp "$SCRIPT_DIR/templates/$name" "$DEMO_DIR/.idea/$name"
      step "installed .idea/$name (template)"
    fi
  done
  if [ -f "$DEMO_DIR/.idea/dataSources.xml" ] && ! grep -q "mpj_demo" "$DEMO_DIR/.idea/dataSources.xml"; then
    warn "existing .idea/dataSources.xml has no data source containing 'mpj_demo'; testDatabase will skip."
  fi
fi

# ---------- 4. MySQL databases ----------
run_mysql() {
  MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_BIN" --default-character-set=utf8mb4 \
    -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" "$@"
}

if [ "${SKIP_DB:-0}" = "1" ]; then
  step "DB init skipped (SKIP_DB=1)"
elif [ "${CHECK_ONLY:-0}" = "1" ]; then
  step "DB init skipped (CHECK_ONLY=1)"
elif ! { [ -x "$MYSQL_BIN" ] || command -v "$MYSQL_BIN" >/dev/null 2>&1; }; then
  warn "mysql client not found (MYSQL_BIN=$MYSQL_BIN); DB init skipped. Set MYSQL_BIN to the full mysql executable path if it is installed outside PATH."
  cat <<EOF

Run these commands manually (MySQL 5.7+/8.0):
  mysql -h$MYSQL_HOST -P$MYSQL_PORT -u$MYSQL_USER -p -e "CREATE DATABASE IF NOT EXISTS mpj_demo DEFAULT CHARSET utf8mb4; CREATE DATABASE IF NOT EXISTS mp_demo DEFAULT CHARSET utf8mb4;"
  mysql -h$MYSQL_HOST -P$MYSQL_PORT -u$MYSQL_USER -p --default-character-set=utf8mb4 mpj_demo < dev-support/test-demo/db/mpj_demo.sql
  mysql -h$MYSQL_HOST -P$MYSQL_PORT -u$MYSQL_USER -p --default-character-set=utf8mb4 mp_demo < dev-support/test-demo/db/mp_demo.sql
EOF
  [ "${REQUIRE_DB:-0}" = "1" ] && fail "MySQL is required (REQUIRE_DB=1) but the mysql client is unavailable."
else
  if ! run_mysql -e "SELECT 1" >/dev/null 2>&1; then
    warn "cannot connect to MySQL at ${MYSQL_HOST}:${MYSQL_PORT} as ${MYSQL_USER}; DB init skipped."
    [ "${REQUIRE_DB:-0}" = "1" ] && fail "MySQL connection failed (REQUIRE_DB=1)."
  else
    run_mysql -e "CREATE DATABASE IF NOT EXISTS mpj_demo DEFAULT CHARSET utf8mb4; CREATE DATABASE IF NOT EXISTS mp_demo DEFAULT CHARSET utf8mb4;"
    for spec in "mpj_demo:db/mpj_demo.sql" "mp_demo:db/mp_demo.sql"; do
      db="${spec%%:*}"
      sql="${spec#*:}"
      step "loading $sql into $db..."
      run_mysql "$db" < "$SCRIPT_DIR/$sql"
    done
    count="$(run_mysql -N -B mpj_demo -e 'SELECT COUNT(*) FROM user;' | tr -d '[:space:]')"
    if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -gt 0 ]; then
      step "mpj_demo.user rows = $count"
    else
      warn "mpj_demo.user is empty or unreadable; e2e/testDb data may be incomplete."
    fi
  fi
fi

printf '\n[test-demo] done. next commands:\n'
printf '  ./gradlew testFast        # no demo / DB required\n'
printf '  ./gradlew testShards      # requires the demo checkout\n'
printf '  ./gradlew testDatabase    # requires demo + MySQL mpj_demo\n'
printf '  ./gradlew e2eFakeIde      # requires demo + MySQL mpj_demo + Maven cache\n\n'
printf 'If the demo lives elsewhere, use -PmpDemoProjectDir=<path> for testShards/testDatabase,\n'
printf 'or MPSV_DEMO_ROOT=<path> for e2eFakeIde.\n'
