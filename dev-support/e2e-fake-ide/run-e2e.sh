#!/usr/bin/env bash
# 端到端验证驱动：假 IDE 探针（FakeIde） + 真实 Spring Boot/MPJ 应用（demo 工程）。
#
# 一条命令复现全部读数：
#   bash dev-support/e2e-fake-ide/run-e2e.sh
#
# 产物：
#   logs/probe.log    探针全量日志（帧级：时刻/命令/摘要 + 断言 PASS/FAIL）
#   logs/app.log      应用 stdout+stderr（agent 的 System.err 诊断打点在此）
#   logs/run-meta.txt 本次运行的环境与参数快照
#
# 退出码：0 = 探针全部断言通过；非 0 = 有断言失败（详见 logs/probe.log 的 SUMMARY 段）。
#
# 可用环境变量覆盖：AGENT_JAR / DEMO_DIR / MVN / PROBE_PORT / APP_PORT / APP_URL_BASE

set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"

# 可选的本机覆盖（T-LocalEnvOverride 2026-10-10）：MPSV_DEMO_ROOT / MPSV_MVN 等键见
# .local/local.env.example。
if [ -f "$REPO_ROOT/.local/local.env" ]; then
  # shellcheck disable=SC1091
  . "$REPO_ROOT/.local/local.env"
fi

# MSYS 路径（/f/code/...）Java 进程认不出来（会被当成相对路径）——凡是要交给 java 的路径
# 一律先转成 Windows 形式；cygpath 缺失时原样返回（纯 Windows shell 下本来就是对的形式）。
winpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

# T-AgentResiduals R7：从 gradle 任务（e2eFakeIde / releaseGate）调用时，子进程环境里**没有**交互式
# shell 的 JDK（Gradle daemon 的 PATH 不含本机 JDK8 bin）——故 JAVA_HOME 非空时把它的 bin 前置进 PATH
# （cygpath -u 转 MSYS 形式，Windows 形式的 JAVA_HOME 直接拼进 PATH 在 MSYS 下不可靠）。
# 手动运行且 PATH 里已有 java/javac 时此段无副作用。
if [ -n "${JAVA_HOME:-}" ] && [ -d "$JAVA_HOME/bin" ]; then
  if command -v cygpath >/dev/null 2>&1; then
    PATH="$(cygpath -u "$JAVA_HOME")/bin:$PATH"
  else
    PATH="$JAVA_HOME/bin:$PATH"
  fi
  export PATH
fi
if ! command -v javac >/dev/null 2>&1 || ! command -v java >/dev/null 2>&1; then
  echo "缺少 javac/java（探针与 demo 都需要 JDK，探针按 Java 8 编译）。"
  echo "  指定 JDK：JAVA_HOME=<jdk 路径> bash dev-support/e2e-fake-ide/run-e2e.sh"
  echo "           或 ./gradlew e2eFakeIde -Pe2eJavaHome=<jdk 路径>"
  exit 3
fi

AGENT_JAR="${AGENT_JAR:-$REPO_ROOT/sub-projects/sql-visualizer-agent/build/dist/mps-sql-agent.jar}"

# demo 工程与 Maven 不再写死本机路径（T-LocalEnvOverride 2026-10-10）：
# DEMO_DIR 优先显式传入，其次由 MPSV_DEMO_ROOT 派生（demo 根下的 join 演示子工程）；
# MVN 优先显式传入，其次 MPSV_MVN，最后 PATH 上的 mvnd / mvn。缺一则报错退出。
DEMO_DIR="${DEMO_DIR:-${MPSV_DEMO_ROOT:+$MPSV_DEMO_ROOT/mybatis-plus-join-demo}}"
if [ -z "$DEMO_DIR" ]; then
  echo "缺少 DEMO_DIR：请设 MPSV_DEMO_ROOT（见 .local/local.env.example），或直接 DEMO_DIR=<join demo 路径>。" >&2
  exit 3
fi
MVN="${MVN:-${MPSV_MVN:-$(command -v mvnd 2>/dev/null || command -v mvn 2>/dev/null || true)}}"
# T-CiNightly（2026-11-05）：离线开关。默认 1（沿用 -o，本机 ~/.m2 已有依赖时最快）；
# CI 上首次跑没有 ~/.m2，传 MVN_OFFLINE=0 走联网解析（夜间时间充足，多等几分钟）。
MVN_OFFLINE="${MVN_OFFLINE:-1}"
MVN_OFFLINE_FLAG=""; [ "$MVN_OFFLINE" = "1" ] && MVN_OFFLINE_FLAG="-o"
if [ -z "$MVN" ]; then
  echo "缺少 MVN：请设 MPSV_MVN（见 .local/local.env.example），或把 mvnd / mvn 放进 PATH。" >&2
  exit 3
fi
PROBE_PORT="${PROBE_PORT:-53999}"
APP_PORT="${APP_PORT:-8082}"
LOGLEVEL="${LOGLEVEL:-info}"

case "$APP_PORT" in
  ''|*[!0-9]*) echo "无效应用端口：$APP_PORT" >&2; exit 3 ;;
esac
if [ "$APP_PORT" -lt 1 ] || [ "$APP_PORT" -gt 65525 ]; then
  echo "应用端口超出可回退范围：$APP_PORT" >&2
  exit 3
fi
REQUESTED_APP_PORT="$APP_PORT"
for candidate in $(seq "$APP_PORT" "$((APP_PORT + 10))"); do
  if ! netstat -ano 2>/dev/null | grep -q ":$candidate .*LISTENING"; then
    APP_PORT="$candidate"
    break
  fi
done
if netstat -ano 2>/dev/null | grep -q ":$APP_PORT .*LISTENING"; then
  echo "应用端口 $REQUESTED_APP_PORT 起连续 11 个端口均被占用，本轮 E2E 不启动。" >&2
  exit 3
fi
if [ "$APP_PORT" != "$REQUESTED_APP_PORT" ]; then
  echo "应用端口 $REQUESTED_APP_PORT 已被占用，改用 $APP_PORT；原监听进程不会被清理。"
fi

LOGS="$HERE/evidence"
PROBE_LOG="$LOGS/probe.log"
APP_LOG="$LOGS/app.log"
META="$LOGS/run-meta.txt"
PROBE_CONSOLE="$LOGS/probe.console.log"

mkdir -p "$LOGS"
: > "$PROBE_LOG"
: > "$APP_LOG"

echo "=== T-AgentE2EFakeIde 端到端验证 ==="

# ── 1) 探针编译 ────────────────────────────────────────────────
echo "[1/6] 编译假 IDE 探针"
mkdir -p "$HERE/build"
javac -encoding UTF-8 -d "$HERE/build" "$HERE/FakeIde.java" || { echo "探针编译失败"; exit 3; }
RESOLVED_SCRIPT="$HERE/build/script-e2e-$APP_PORT.txt"
sed "s|http://localhost:8082/|http://localhost:$APP_PORT/|g" "$HERE/script-e2e.txt" > "$RESOLVED_SCRIPT" || exit 3

# ── 2) agent 产物检查 ─────────────────────────────────────────
if [ ! -f "$AGENT_JAR" ]; then
  echo "缺少 agent jar：$AGENT_JAR"
  echo "请先构建：cd $REPO_ROOT && ./gradlew :sql-visualizer-agent:agentFatJar \\"
  echo "            -Pkotlin.compiler.execution.strategy=in-process"
  exit 3
fi
echo "      agent jar: $(ls -la "$AGENT_JAR" | awk '{print $5" bytes  "$6" "$7" "$8}')"

# ── 3) 应用 classpath / 编译 ──────────────────────────────────
# T-AgentResiduals R7：本脚本已挂 gradle 任务 e2eFakeIde / releaseGate——前置缺失一律**硬失败 + 给指引**，
# 不静默跳过（跳过＝把「没验」伪装成「验过」）。
if [ ! -d "$DEMO_DIR" ]; then
  echo "缺少 demo 工程（真机级探针的被测应用）：$DEMO_DIR"
  echo "  指定路径：DEMO_DIR=<路径> bash dev-support/e2e-fake-ide/run-e2e.sh"
  echo "            或 ./gradlew e2eFakeIde -Pe2eDemoDir=<路径>"
  echo "  环境准备见：dev-support/e2e-fake-ide/README.md"
  exit 3
fi
CP_FILE="$DEMO_DIR/target/e2e-cp.txt"
if [ ! -f "$CP_FILE" ]; then
  echo "[2/6] 生成 demo classpath（$CP_FILE）"
  ( cd "$DEMO_DIR" && "$MVN" -q $MVN_OFFLINE_FLAG dependency:build-classpath -Dmdep.outputFile=target/e2e-cp.txt ) || {
    echo "classpath 生成失败（离线模式时确认 ~/.m2 已有依赖；CI 上可传 MVN_OFFLINE=0）"; exit 3; }
fi
if [ ! -d "$DEMO_DIR/target/classes" ]; then
  echo "编译 demo 工程"
  ( cd "$DEMO_DIR" && "$MVN" -q $MVN_OFFLINE_FLAG compile ) || { echo "demo 编译失败"; exit 3; }
fi
APP_CP="$(winpath "$DEMO_DIR/target/classes");$(cat "$CP_FILE")"

# ── 4) 起探针（必须先于应用：agent 在 premain 阶段回连） ──────
echo "[3/6] 启动假 IDE 探针 127.0.0.1:$PROBE_PORT"
MSYS2_ARG_CONV_EXCL='*' java -cp "$(winpath "$HERE/build")" FakeIde \
  --port "$PROBE_PORT" --script "$(winpath "$RESOLVED_SCRIPT")" --log "$(winpath "$PROBE_LOG")" \
  > "$PROBE_CONSOLE" 2>&1 &
PROBE_PID=$!

for i in $(seq 1 30); do
  grep -q "LISTENING" "$PROBE_LOG" 2>/dev/null && break
  sleep 0.5
done
if ! grep -q "LISTENING" "$PROBE_LOG" 2>/dev/null; then
  echo "探针未就绪，日志尾部："; tail -20 "$PROBE_LOG"
  kill "$PROBE_PID" 2>/dev/null
  exit 3
fi
echo "      探针已监听（pid=$PROBE_PID）"

# ── 5) 起真实应用（-javaagent 注入，logLevel=info 打开定位器诊断） ──
echo "[4/6] 启动 demo 应用（javaagent 注入）"
{
  echo "date=$(date -Iseconds)"
  echo "probe_port=$PROBE_PORT app_port=$APP_PORT loglevel=$LOGLEVEL"
  echo "agent_jar=$AGENT_JAR"
  echo "agent_jar_sha256=$(sha256sum "$AGENT_JAR" | awk '{print $1}')"
  echo "demo_dir=$DEMO_DIR"
  echo "java=$(java -version 2>&1 | head -1)"
  echo "demo_git_head=$(cd "$DEMO_DIR" && git rev-parse HEAD 2>/dev/null)"
  echo "repo_git_head=$(cd "$REPO_ROOT" && git rev-parse HEAD)"
} > "$META"

# T-ReleasePerf7 R0（20261007）：钉死 mysql profile——demo target/classes 的 application.yml
# 是 mvn 资源过滤产物（@profiles.active@），demo 工程被其他轮以 -Dprofiles.active=dm 重编后
# e2e 静默吃到 dm（达梦驱动被织入改写 VerifyError，应用起不来）。命令行 arg 优先级最高，
# 不受 demo 构建态污染；README 前置本就写明 MySQL mpj_demo。
MSYS2_ARG_CONV_EXCL='*' java \
  "-javaagent:$(winpath "$AGENT_JAR")=port=$PROBE_PORT;logLevel=$LOGLEVEL" \
  -Dfile.encoding=UTF-8 \
  -cp "$APP_CP" \
  cn.song.mpjdemo.MybatisPlusJoinDemoApplication \
  --spring.profiles.active=mysql --server.port="$APP_PORT" \
  > "$APP_LOG" 2>&1 &
APP_PID=$!
echo "      应用已启动（pid=$APP_PID），日志：$APP_LOG"

# ── 6) 等探针跑完脚本（最多 300s，防挂死） ───────────────────
echo "[5/6] 等探针脚本执行完毕（握手/停采/恢复/脱敏/收口）"
PROBE_RC=1
for i in $(seq 1 300); do
  if ! kill -0 "$PROBE_PID" 2>/dev/null; then
    wait "$PROBE_PID"
    PROBE_RC=$?
    break
  fi
  sleep 1
done
if kill -0 "$PROBE_PID" 2>/dev/null; then
  echo "      探针超时未结束（300s），强制终止"
  kill "$PROBE_PID" 2>/dev/null
  PROBE_RC=124
fi
echo "      探针退出码 = $PROBE_RC"

# 给应用一点时间把最后一批诊断打点刷进日志
sleep 2
echo "[6/6] 关闭应用（pid=$APP_PID）"
kill "$APP_PID" 2>/dev/null
sleep 1
kill -9 "$APP_PID" 2>/dev/null
# MSYS PID 与 Windows PID 不同域；只允许按本轮 agent HELLO 身份收口，不能杀端口的新占用者。
if netstat -ano 2>/dev/null | grep -q ":$APP_PORT .*LISTENING"; then
  WIN_PID="$(netstat -ano 2>/dev/null | grep ":$APP_PORT .*LISTENING" | head -1 | awk '{print $NF}')"
  AGENT_WIN_PID="$(grep -am1 -oE 'HELLO pid=[0-9]+' "$PROBE_LOG" | cut -d= -f2)"
  if [ -n "${WIN_PID:-}" ] && [ "$WIN_PID" = "$AGENT_WIN_PID" ]; then
    echo "      本轮应用仍监听端口 $APP_PORT，taskkill Windows PID=$WIN_PID"
    taskkill //F //PID "$WIN_PID" >/dev/null 2>&1
  else
    echo "      端口 $APP_PORT 的监听进程不属于本轮应用，不执行 taskkill"
  fi
fi

echo
echo "=== 产物 ==="
echo "  探针日志（帧级 + 断言）: $PROBE_LOG"
echo "  应用日志（agent 打点） : $APP_LOG"
echo "  运行元数据            : $META"
echo
echo "=== 探针 SUMMARY ==="
sed -n '/──────── SUMMARY ────────/,/─────────────────────────/p' "$PROBE_LOG"
echo
echo "=== 应用侧定位器诊断（entryKey 取证）==="
grep -a -E "T-SourceJumpFunnel|T-AgentSourceLocateStale|T-AgentConfigChannel" "$APP_LOG" | head -40
echo
echo "退出码 = $PROBE_RC"
exit "$PROBE_RC"
