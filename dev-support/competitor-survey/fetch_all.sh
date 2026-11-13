#!/bin/bash
# r35 marketplace survey fetcher. Data -> C:/Users/USER/AppData/Local/Temp/mpsv-survey-r35
DATA=C:/Users/USER/AppData/Local/Temp/mpsv-survey-r35
LOG=$DATA/_reqlog.txt
cd "$DATA"
: > "$LOG"

fetch() { # fetch <url> <outfile>
  local code
  code=$(curl -s -o "$2" -w "%{http_code}" "$1")
  echo "$1 -> $code" >> "$LOG"
  [ "$code" != "200" ] && echo "FAIL: $1 -> $code"
}

IDS="15075 10119 9837 13905 17898 12670 33684 33397 33633 30447 34107 34131 34456 34687 34028 35174 35635 25744 21812 34166"

for id in $IDS; do
  fetch "https://plugins.jetbrains.com/api/plugins/$id" "base_$id.json"
  fetch "https://plugins.jetbrains.com/api/plugins/$id/updates" "updates_$id.json"
  fetch "https://plugins.jetbrains.com/api/plugins/$id/rating" "rating_$id.json"
  # comments: page 1, then keep paging while a full page (100) is returned
  page=1
  while :; do
    fetch "https://plugins.jetbrains.com/api/plugins/$id/comments?size=100&page=$page" "comments_${id}_p${page}.json"
    len=$(python -I -c "
import json
d=json.load(open('$DATA/comments_${id}_p${page}.json',encoding='utf-8'))
print(len(d) if isinstance(d,list) else -1)
")
    [ "$len" -eq 100 ] || break
    page=$((page+1))
  done
done

# locate CHP paid family via search
fetch "https://plugins.jetbrains.com/api/searchPlugins?search=MyBatisCodeHelperPro&offset=0" "search_chp_0.json"

echo "=== DONE ==="
