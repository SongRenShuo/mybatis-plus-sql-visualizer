#!/usr/bin/env bash
# T-OpenIDEAPort T0-0 冒烟：对一个已构建的 mpsv-engine jar 跑三个门面各一例。
# 前提：先 ./gradlew :engine-jar:jar（产物 build/libs/mpsv-engine-<version>.jar，自包含）。
# 用 JAVA_HOME 的 java（本机 PATH 默认 java 是 JDK 8，加载不了 major 55 的类）。
set -euo pipefail
cd "$(dirname "$0")"

JAR="$(ls build/libs/mpsv-engine-*.jar 2>/dev/null | head -1 || true)"
if [ -z "${JAR}" ]; then
  echo "engine jar not found — run: ./gradlew :engine-jar:jar" >&2
  exit 1
fi

JAVA_BIN="java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_BIN="${JAVA_HOME}/bin/java"
fi

echo "jar: ${JAR}"
"${JAVA_BIN}" -cp "${JAR}" mpsv.engine.SmokeMain