#!/usr/bin/env bash
# bootstrap-db-env.sh — 按清单幂等重建整套数据库测试环境（靶机被重装系统后的一条龙）
#
# 设计前提：**测试服务器随时可能被重装，所有容器/卷/数据全部丢失**。所以环境必须是「声明式」的：
# 实例清单是唯一事实源，脚本可反复执行（已存在的容器直接跳过），失败重跑也只补没起来的那几个。
#
# 清单格式（TSV，一行一个实例；`#` 开头为注释；字段 9 个，用 Tab 分隔）：
#   name<TAB>image<TAB>hostPort<TAB>containerPort<TAB>memLimit<TAB>volumeName<TAB>env<TAB>initDir<TAB>verifyCmd
#
#   name           容器名（唯一）
#   image          镜像（含 tag）
#   hostPort       宿主端口（铁律：只用 20000–29999）
#   containerPort  容器内端口
#   memLimit       内存限额，如 1g（留空=不限）
#   volumeName     命名卷（留空=不挂数据卷，仅测试用）
#   env            KEY=VALUE;KEY=VALUE（分号分隔；值里可含空格，但不要含分号）
#   initDir        宿主初始化目录，挂到 /docker-entrypoint-initdb.d:ro（留空=不挂）
#   verifyCmd      容器内就绪探针（留空=只检查容器 Running）
#
# 真实清单放 .local/db-env-instances.tsv（.local/ 已 gitignore，可含明文凭据）；
# 模板见本目录 instances.example.tsv。
#
# 用法（在靶机上，或用 ssh 执行）：
#   bash bootstrap-db-env.sh [/path/to/instances.tsv] [--dry-run]
#
# 退出码：0=全部就绪（含跳过）；1=有实例未就绪（末尾会列出）。

set -uo pipefail

MANIFEST="${1:?用法: bootstrap-db-env.sh <instances.tsv> [--dry-run]}"
DRY_RUN=0
for a in "$@"; do [ "$a" = "--dry-run" ] && DRY_RUN=1; done

[ -f "$MANIFEST" ] || { echo "清单不存在：$MANIFEST" >&2; exit 1; }

# ── 镜像源：直连 Docker Hub 在这台机器上常挂死，按序回退 ────────────────────
# 2026-11-05 实测：三路并发直连时总吞吐 ~312KB/s；掐掉两路走 daocloud 单路 12MB/s。
MIRRORS=("docker.m.daocloud.io" "dockerproxy.com" "")
READY_RETRIES="${READY_RETRIES:-60}"
READY_SLEEP="${READY_SLEEP:-3}"

pull_image() {
  local img="$1"
  docker image inspect "$img" >/dev/null 2>&1 && { echo "  镜像已在本地"; return 0; }
  local m src
  for m in "${MIRRORS[@]}"; do
    if [ -z "$m" ]; then src="$img"; else src="$m/$img"; fi
    echo "  拉取 $src ..."
    if [ "$DRY_RUN" = "1" ]; then return 0; fi
    if docker pull "$src" >/dev/null 2>&1; then
      if [ "$src" != "$img" ]; then docker tag "$src" "$img"; fi
      echo "  OK: $img"
      return 0
    fi
    echo "  失败，换下一个源"
  done
  return 1
}

declare -a FAILED=()
declare -a READY=()

# 逐行解析（不用 while read 管道，避免子 shell 吞掉 FAILED/READY）
mapfile -t LINES < "$MANIFEST"
for line in "${LINES[@]}"; do
  case "$line" in ''|'#'*) continue;; esac
  IFS=$'\t' read -r name image host_port ctr_port mem volume envs init_dir verify <<< "$line"
  [ -n "${name:-}" ] || continue
  echo "== $name（$image → $host_port:$ctr_port）"

  if docker ps -a --format '{{.Names}}' | grep -qx "$name"; then
    if docker ps --format '{{.Names}}' | grep -qx "$name"; then
      echo "  已存在且运行中，跳过创建"
    else
      echo "  已存在但未运行，docker start"
      [ "$DRY_RUN" = "1" ] || docker start "$name" >/dev/null
    fi
  else
    pull_image "$image" || { echo "  镜像拉取失败：$image" >&2; FAILED+=("$name(镜像)"); continue; }

    args=(run -d --name "$name" --restart unless-stopped -p "${host_port}:${ctr_port}")
    [ -n "${mem:-}" ] && args+=(--memory "$mem")
    [ -n "${volume:-}" ] && args+=(-v "${volume}:/var/lib/auto-data")   # 需要具体挂载点时在清单外手工覆盖
    [ -n "${init_dir:-}" ] && args+=(-v "${init_dir}:/docker-entrypoint-initdb.d:ro")
    if [ -n "${envs:-}" ]; then
      IFS=';' read -ra kv <<< "$envs"
      for pair in "${kv[@]}"; do [ -n "$pair" ] && args+=(-e "$pair"); done
    fi
    args+=("$image")
    if [ "$DRY_RUN" = "1" ]; then
      echo "  [dry-run] docker ${args[*]}"
      continue
    fi
    if ! docker "${args[@]}" >/dev/null; then
      echo "  docker run 失败" >&2; FAILED+=("$name(run)"); continue
    fi
    echo "  已创建"
  fi

  # 就绪探针：有 verifyCmd 就轮询容器内执行，否则只认 Running
  ok=0
  if [ -n "${verify:-}" ]; then
    for _ in $(seq 1 "$READY_RETRIES"); do
      if docker exec "$name" sh -c "$verify" >/dev/null 2>&1; then ok=1; break; fi
      sleep "$READY_SLEEP"
    done
  else
    docker ps --format '{{.Names}}' | grep -qx "$name" && ok=1
  fi
  if [ "$ok" = "1" ]; then
    echo "  就绪"
    READY+=("${name}	${host_port}")
  else
    echo "  未就绪（${READY_RETRIES}x${READY_SLEEP}s 超时）——看 docker logs $name" >&2
    FAILED+=("$name(超时)")
  fi
done

echo
echo "==== 汇总 ===="
printf '%-24s %s\n' "实例" "宿主端口"
for r in "${READY[@]:-}"; do IFS=$'\t' read -r n p <<< "$r"; printf '%-24s %s\n' "$n" "$p"; done
if [ "${#FAILED[@]}" -gt 0 ]; then
  echo
  echo "未就绪：${FAILED[*]}" >&2
  echo "重跑本脚本可只补这些（已就绪的会跳过）。" >&2
  exit 1
fi

cat <<'EOF'

后续手工步骤（脚本不代做，避免误改生产/既有实例）：
  1) 凭据与连接写进 .local/test-databases.md（台账是唯一的凭据来源，别写进受控文件）
  2) 在 DBX 登记连接（新连接首个查询可能报 CONNECTION_OUT_OF_SCOPE，需本机放行一次）
  3) 按 skill §4 第三档取一次真机读数（渲染套件跑通才算装好）
  4) 若实例参与了 CI（-PmpvDbUrl 等），同步更新仓库 secrets
EOF