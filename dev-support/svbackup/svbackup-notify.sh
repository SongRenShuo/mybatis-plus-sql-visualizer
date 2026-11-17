#!/usr/bin/env bash
# svbackup-notify.sh —— 备份失败/告警推管理后台
#
# 令牌：/etc/svbackup/notify-token（600），接口：/etc/svbackup/notify-api（600）。
# 两者缺一时静默跳过、rc=0（不阻断主流程）。国内服务器必须用站内代理域名，
# 不能用 workers.dev（实测不可达，http=000）。
# 手工验证：/usr/local/bin/svbackup-notify.sh high '测试' '联调'

set -uo pipefail
LEVEL="${1:-info}"
TITLE="${2:-备份通知}"
BODY="${3:-}"
TOKEN_FILE=/etc/svbackup/notify-token
API_FILE=/etc/svbackup/notify-api
DEFAULT_API="https://sql-visualizer.jvtools.cn/api/v1/notify/backup"

[ -r "$TOKEN_FILE" ] || { echo "notify: 未配置令牌，静默跳过"; exit 0; }
API="$(cat "$API_FILE" 2>/dev/null || printf '%s' "$DEFAULT_API")"
TOKEN="$(cat "$TOKEN_FILE")"

# 中文请求体走 UTF-8 文件 + --data-binary，避免 Git Bash/命令行中文乱码
PAYLOAD="$(mktemp)"
trap 'rm -f "$PAYLOAD"' EXIT
python3 - "$LEVEL" "$TITLE" "$BODY" > "$PAYLOAD" <<'PY'
import json, sys
print(json.dumps({"level": sys.argv[1], "title": sys.argv[2], "body": sys.argv[3]},
                 ensure_ascii=False))
PY

HTTP="$(curl -sS --max-time 15 -o /dev/null -w '%{http_code}' \
  -X POST "$API" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary "@$PAYLOAD" 2>/dev/null || printf '000')"
echo "notify: http=$HTTP level=$LEVEL title=$TITLE"
[ "$HTTP" = "200" ] || exit 1
