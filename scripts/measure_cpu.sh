#!/usr/bin/env bash
# issue #47 验收口径 3：RenderThread / 主线程 CPU 比（目标 <2x）
# 用法：scripts/measure_cpu.sh [滚动次数]
# 做法：先起 top（后台采样 3 帧），同时用 input swipe 真的滚会话，再解析稳态那一帧。
set -uo pipefail
ADB="${ADB:-/d/dsh/fixbug/_toolchain/sdk/platform-tools/adb.exe}"
SERIAL="${SERIAL:-192.168.0.102:40731}"
PKG="${PKG:-com.suixing.agent}"
N="${1:-6}"

pid=$("$ADB" -s "$SERIAL" shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')
[ -z "$pid" ] && { echo "应用未运行"; exit 1; }

"$ADB" -s "$SERIAL" shell top -H -b -n 3 -d 2 -p "$pid" > /tmp/cpu.txt 2>/dev/null &
topjob=$!
sleep 1
for i in $(seq 1 "$N"); do
  "$ADB" -s "$SERIAL" shell input swipe 540 1600 540 700 220 >/dev/null 2>&1
  sleep 0.4
done
wait $topjob 2>/dev/null

echo "=== 线程 CPU 采样（最后一帧为稳态）==="
grep -E "RenderThread|^\s*[0-9]+ .* main$|TID" /tmp/cpu.txt | tail -8
