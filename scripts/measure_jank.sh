#!/usr/bin/env bash
# issue #47 的验收口径测量脚本（只读，不安装、不改设备）
#
# 用法：
#   scripts/measure_jank.sh reset      # 清空 gfxinfo 统计
#   scripts/measure_jank.sh report     # 输出直方图与关键指标
#
# 口径（来自 issue #47）：
#   1. 12~14ms 帧占比（当前基线 33%，目标 <5%）
#   2. p90 帧时间（目标 ≤9ms）
#   3. RenderThread / 主线程 CPU 比（当前 3.4x，目标 <2x）
#   4. RSS（当前 403MB，目标 <350MB）
set -uo pipefail

ADB="${ADB:-/d/dsh/fixbug/_toolchain/sdk/platform-tools/adb.exe}"
SERIAL="${SERIAL:-192.168.0.102:40731}"
PKG="${PKG:-com.suixing.agent}"
DEV=(-s "$SERIAL")

cmd="${1:-report}"

case "$cmd" in
  reset)
    "$ADB" "${DEV[@]}" shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1
    echo "gfxinfo 已重置（现在去滚动会话列表，然后跑 report）"
    ;;
  report)
    raw=$("$ADB" "${DEV[@]}" shell dumpsys gfxinfo "$PKG" 2>/dev/null)
    echo "=== 帧时间直方图（ms → 帧数）==="
    echo "$raw" | sed -n '/HISTOGRAM/,/^$/p' | head -30

    # 解析 "Percentile" 行：p50 / p90 / p95 / p99
    echo "=== 百分位 ==="
    echo "$raw" | grep -iE "percentile|50th|90th|95th|99th" | head -8

    echo "=== janky 统计 ==="
    echo "$raw" | grep -iE "Janky frames|Number Missed|Total frames" | head -6

    echo "=== RSS (KB) ==="
    "$ADB" "${DEV[@]}" shell dumpsys meminfo "$PKG" 2>/dev/null | grep -iE "TOTAL PSS|TOTAL RSS" | head -3

    echo "=== 线程 CPU（主线程 vs RenderThread，5s 采样）==="
    "$ADB" "${DEV[@]}" shell "top -H -b -n 1 -d 1 -p $("$ADB" "${DEV[@]}" shell pidof "$PKG" 2>/dev/null | tr -d '\r')" 2>/dev/null \
      | grep -iE "RenderThread|main|TID" | head -6
    ;;
  *)
    echo "用法: $0 {reset|report}"; exit 2;;
esac
