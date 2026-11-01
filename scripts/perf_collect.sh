#!/usr/bin/env bash
# 按 perf-scenarios.md 的 6 组场景采集一帧数据，直接输出一行表格。
#
# 用法：
#   scripts/perf_collect.sh <A|B|C|D|E|F> [秒数]
#
# 它不启动应用、不操作屏幕：
#   · 应用没在跑 ⇒ 直接给可读错误（不用 adb 拉起，那是用户自己的决定）；
#   · 需要滑动的场景（B/C/D/E/F）由**你**在手机上操作，脚本只负责计时采样；
#   · A（冷启动）不在这里做：`am start -W` 属于启动应用，需你明确授权。
#
# 输出：一行 Markdown 表格，字段与 perf-scenarios.md 的"记录格式"一致。
set -uo pipefail

. "$(dirname "$0")/_adb.sh"
PKG="${PKG:-com.suixing.agent}"

SCENARIO="${1:-}"
SECONDS_TO_SAMPLE="${2:-6}"

case "$SCENARIO" in
  B|C|D|E|F) ;;
  A) echo "A 组（冷启动）要 am start -W，属于启动应用 —— 需你明确授权后再跑。" >&2; exit 3 ;;
  "") echo "用法：scripts/perf_collect.sh <B|C|D|E|F> [秒数]" >&2; exit 2 ;;
  *)  echo "未知场景「$SCENARIO」，可选 B/C/D/E/F（A 需单独授权）。" >&2; exit 2 ;;
esac

adb_pick || exit 1
pid=$(app_pid)
if [ -z "$pid" ]; then
  say_err "应用没在运行（pidof $PKG 为空）。"
  say_err "本脚本不会用 adb 拉起应用 —— 请先在手机上打开 JAgent 并停在要测的会话。"
  exit 4
fi

VER=$("${ADB_CMD[@]}" shell dumpsys package "${PKG:-com.suixing.agent}" 2>/dev/null | awk -F= '/versionName=/{print $2; exit}' | tr -d '\r')
HZ=$("${ADB_CMD[@]}" shell dumpsys display 2>/dev/null | grep -o 'fps=[0-9.]*' | head -1 | cut -d= -f2)

"${ADB_CMD[@]}" shell dumpsys gfxinfo "${PKG:-com.suixing.agent}" reset >/dev/null 2>&1
say_err "采集中：请**现在**在手机上按场景 $SCENARIO 操作，${SECONDS_TO_SAMPLE} 秒后自动结束…"
sleep "$SECONDS_TO_SAMPLE"

RAW=$("${ADB_CMD[@]}" shell dumpsys gfxinfo "${PKG:-com.suixing.agent}" 2>/dev/null)
RSS=$("${ADB_CMD[@]}" shell dumpsys meminfo "${PKG:-com.suixing.agent}" 2>/dev/null | awk '/TOTAL PSS|TOTAL:/{print $2; exit}')

# gfxinfo 的百分位段（Android 12+ 才有 Percentile 段；没有就退回直方图）
PCTS=$(printf '%s\n' "$RAW" | awk '/90th percentile|95th percentile|99th percentile/{gsub(/[^0-9.]/,"",$1); printf "%s ", $1}')
p50=$(printf '%s\n' "$PCTS" | awk '{print $1}')
p95=$(printf '%s\n' "$PCTS" | awk '{print $2}')
p99=$(printf '%s\n' "$PCTS" | awk '{print $3}')
JANKY=$(printf '%s\n' "$RAW" | awk -F: '/Janky frames/{gsub(/[^0-9.]/,"",$2); print $2; exit}')

[ -z "${p50:-}" ] && p50="—"
[ -z "${p95:-}" ] && p95="—"
[ -z "${p99:-}" ] && p99="—"
[ -z "${JANKY:-}" ] && JANKY="—"
[ -z "${RSS:-}" ] && RSS="—"

printf '| %s | %s | %s Hz | 1 | %s | %s | %s | %s | %s KB | 脚本计时 %ss，人手操作 |\n' \
  "$SCENARIO" "${VER:-—}" "${HZ:-—}" "$p50" "$p95" "$p99" "$JANKY" "$RSS" "$SECONDS_TO_SAMPLE"
