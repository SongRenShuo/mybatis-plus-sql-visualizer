#!/bin/bash
# r35 keyword search pagination. Data -> C:/Users/USER/AppData/Local/Temp/mpsv-survey-r35
DATA=C:/Users/USER/AppData/Local/Temp/mpsv-survey-r35
LOG=$DATA/_reqlog.txt
cd "$DATA"

kw_search() { # kw_search <slug> <urlencoded>
  local slug=$1 q=$2
  local offset=0 total=9999
  while [ $offset -lt $total ]; do
    local f="kw_${slug}_o${offset}.json"
    local code
    code=$(curl -s -o "$f" -w "%{http_code}" "https://plugins.jetbrains.com/api/searchPlugins?search=${q}&offset=${offset}")
    echo "https://plugins.jetbrains.com/api/searchPlugins?search=${q}&offset=${offset} -> $code" >> "$LOG"
    [ "$code" != "200" ] && echo "FAIL: search ${slug} offset ${offset} -> $code"
    local t
    t=$(python -I -c "
import json
d=json.load(open('$DATA/$f',encoding='utf-8'))
print(d.get('total',0))
")
    total=$t
    offset=$((offset+10))
  done
  echo "KW ${slug}: total=${total}"
}

kw_search "sqlexplain"  "sql%20explain"
kw_search "sqlpreview"  "sql%20preview"
kw_search "sqllog"      "sql%20log"
kw_search "sqlvisualizer" "sql%20visualizer"
kw_search "mybatis"     "mybatis"
kw_search "mybatislog"  "mybatis%20log"
kw_search "jpasql"      "jpa%20sql"
kw_search "sqlformatter" "sql%20formatter"
echo "=== KEYWORDS DONE ==="
