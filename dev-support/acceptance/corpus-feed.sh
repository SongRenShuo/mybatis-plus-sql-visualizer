#!/bin/bash
# 语料批处理：一次灌全形态 SQL 条目（U31 敏感值/U34 批量/U08 出口对照/U16 错误面素材）
# 用法: bash corpus-feed.sh ; 前置: demo 应用 8081 在跑且插件捕捉已开
set -u
set +H
BASE=http://localhost:8081
code() { curl -s -o /dev/null -w "%{http_code}" "$@"; }

echo "== 基础查询族 =="
echo "byid:      $(code "$BASE/user/1")"
echo "page:      $(code "$BASE/user/page?pageNum=1&pageSize=3")"
echo "list:      $(code "$BASE/user/list?username=ui")"
echo "nested:    $(code "$BASE/user/nested")"
echo "group:     $(code "$BASE/user/group")"

echo "== 敏感值族（密码字面量进 INSERT/UPDATE，供 U31 脱敏断言） =="
echo "save:      $(code -X POST "$BASE/user" -H 'Content-Type: application/json' -d '{"username":"ui_mask_probe","password":"Secret123X","email":"mask@probe.test"}')"

echo "== 批量族（U34 池/batch） =="
echo "batchIns:  $(code -X POST "$BASE/user/batchInsert" -H 'Content-Type: application/json' -d '[{"username":"ui_batch_a","password":"B1","email":"a@p.t"},{"username":"ui_batch_b","password":"B2","email":"b@p.t"}]')"

echo "== 错误/边界族（U16 素材：不存在的列值路径返回空集而非报错，错误面仍走预览手工 SQL） =="
echo "byid-miss: $(code "$BASE/user/999999")"
echo "done"
