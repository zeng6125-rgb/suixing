#!/usr/bin/env bash
# 按 perf-scenarios.md 的 6 组场景采集一帧数据，直接输出一行表格。
#
# 用法：
#   scripts/perf_collect.sh <B|C|D|E|F> [秒数]
#   PERF_GFXINFO_FILE=<dump 文件> scripts/perf_collect.sh <B|C|D|E|F>   # 离线解析
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

# ---- gfxinfo 解析 ----
# 一份 dump 里有**多组同样标签**的直方图（main / gpu / …），所以一律取"第一次出现"；
# 毫秒数在标签后面的字段里，不是标签本身 —— 早期版本取错了，会把 90/95/99 当成帧时间。
# 输出：p50|p95|p99|janky%|总帧数
parse_gfxinfo() {
  printf '%s\n' "$1" | awk '
    /^50th percentile/ && p50 == "" { p50 = $3 }
    /^95th percentile/ && p95 == "" { p95 = $3 }
    /^99th percentile/ && p99 == "" { p99 = $3 }
    /^Janky frames: / && janky == "" { split($0, a, "("); split(a[2], b, ")"); janky = b[1] }
    /^Total frames rendered:/ && total == "" { total = $4 }
    END { printf "%s|%s|%s|%s|%s\n", (p50 == "" ? "-" : p50), (p95 == "" ? "-" : p95), (p99 == "" ? "-" : p99), (janky == "" ? "-" : janky), (total == "" ? "-" : total) }
  '
}

emit_row() {
  # $1=场景 $2=版本 $3=刷新率 $4=p50 $5=p95 $6=p99 $7=janky $8=rss $9=备注
  printf '| %s | %s | %s | 1 | %s | %s | %s | %s | %s | %s |\n' \
    "$1" "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$9"
}

# ---- 离线模式：解析一份已保存的 dump（无设备也能回归验证这套解析）----
if [ -n "${PERF_GFXINFO_FILE:-}" ]; then
  if [ ! -f "$PERF_GFXINFO_FILE" ]; then
    echo "PERF_GFXINFO_FILE 指向的文件不存在：$PERF_GFXINFO_FILE" >&2
    exit 5
  fi
  IFS='|' read -r P50 P95 P99 JANKY TOTAL < <(parse_gfxinfo "$(cat "$PERF_GFXINFO_FILE")")
  emit_row "$SCENARIO" "-" "-" "$P50" "$P95" "$P99" "$JANKY" "-" "离线解析（总帧 ${TOTAL}）"
  exit 0
fi

adb_pick || exit 1
pid=$(app_pid)
if [ -z "$pid" ]; then
  say_err "应用没在运行（pidof $PKG 为空）。"
  say_err "本脚本不会用 adb 拉起应用 —— 请先在手机上打开 JAgent 并停在要测的会话。"
  exit 4
fi

VER=$("${ADB_CMD[@]}" shell dumpsys package "$PKG" 2>/dev/null | awk -F= '/versionName=/{print $2; exit}' | tr -d '\r')
# 刷新率取 renderFrameRate：dumpsys display 里 fps= 出现多次（每个支持的模式一次），
# 直接取第一个是碰运气；renderFrameRate 是当前渲染速率，与帧数据一致。
HZ=$("${ADB_CMD[@]}" shell dumpsys display 2>/dev/null | grep -o "renderFrameRate [0-9.]*" | head -1 | awk '{print $2}')

"${ADB_CMD[@]}" shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1
say_err "采集中：请**现在**在手机上按场景 $SCENARIO 操作，${SECONDS_TO_SAMPLE} 秒后自动结束…"
sleep "$SECONDS_TO_SAMPLE"

RAW=$("${ADB_CMD[@]}" shell dumpsys gfxinfo "$PKG" 2>/dev/null)
# 「TOTAL PSS:   829857   TOTAL RSS:   676024」—— 数字在标签**后面**一个字段，
# 取 $2 只会拿到标签本身（PSS:）。
RSS=$("${ADB_CMD[@]}" shell dumpsys meminfo "$PKG" 2>/dev/null | awk '{ for (i=1;i<=NF;i++) if ($i=="PSS:") { print $(i+1); exit } }')

IFS='|' read -r P50 P95 P99 JANKY TOTAL < <(parse_gfxinfo "$RAW")

emit_row "$SCENARIO" "${VER:--}" "${HZ:--} Hz" "$P50" "$P95" "$P99" "$JANKY" "${RSS:--} KB" "脚本计时 ${SECONDS_TO_SAMPLE}s，人手操作，总帧 ${TOTAL}"
