#!/bin/bash
# fetch updates for candidate new-face IDs, keep oldest update date
DATA=C:/Users/USER/AppData/Local/Temp/mpsv-survey-r35
LOG=$DATA/_reqlog.txt
cd "$DATA"
IDS="34032 34037 34051 34096 34135 34138 34171 34234 34252 34302 34378 34394 34420 34435 34442 34460 34505 34564 34574 34578 34596 34613 34625 34663 34745 34755 34772 34861 34916 34930 34974 34998 35018 35115 35116 35126 35149 35159 35180 35187 35206 35230 35242 35244 35270 35272 35371 35423 35460 35469 35496 35533 35595 35622 35703"
for id in $IDS; do
  code=$(curl -s -o "cand_updates_$id.json" -w "%{http_code}" "https://plugins.jetbrains.com/api/plugins/$id/updates")
  echo "https://plugins.jetbrains.com/api/plugins/$id/updates -> $code" >> "$LOG"
  [ "$code" != "200" ] && echo "FAIL: cand $id -> $code"
done
echo "=== CANDIDATES DONE ==="
