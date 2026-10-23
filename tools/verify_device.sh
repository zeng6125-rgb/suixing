#!/usr/bin/env bash
# 随行 · 真机验证一条龙（不改应用、不安装、不动你的屏幕）
#
# 用法（Git-Bash / WSL / macOS 均可）：
#   bash tools/verify_device.sh
#
# 它做三件事，每步都把原始输出留下，方便直接贴进 issue：
#   1. 找到设备（USB 或无线调试；无线用 `adb mdns services` 自动发现）
#   2. 取回应用内的「流式上翻痕迹」pin.log（有 root 走 su，没有就提示改用应用内复制）
#   3. 打印 gfxinfo 的判读要点（帧数据需要你自己滑动屏幕产生，脚本不代劳）
set -u
PKG="com.suixing.agent"
OUT="$(dirname "$0")/../verify-out"
mkdir -p "$OUT" || exit 1
say() { printf '\n=== %s ===\n' "$1"; }

say "1/3 找设备"
adb devices | sed -n '1,5p'
if ! adb get-state >/dev/null 2>&1; then
  echo "没有已连接的设备。若开了无线调试，先执行（端口每次不同，会自动列出来）："
  adb mdns services || true
  echo "然后：adb connect <IP:端口>"
  exit 2
fi

say "2/3 取 pin.log（只在复现过「流式上翻」之后才有内容）"
FILES="/data/data/${PKG}/files/pin.log"
if adb shell "su -c 'cat ${FILES}'" >"$OUT/pin.log" 2>"$OUT/pin.err"; then
  if [ -s "$OUT/pin.log" ]; then
    echo "已保存到 $OUT/pin.log，最后 12 行："
    tail -12 "$OUT/pin.log"
    echo
    echo "判读：drift=0 ⇒ 钉住期间没动；drift≠0 且期间有重试/错误提示 ⇒ 已知例外；"
    echo "      drift≠0 且没有错误提示 ⇒ 真问题；没有 arm 行 ⇒ 钉住没生效。"
  else
    echo "pin.log 是空的：先在应用里复现一次（发长消息 → 流式中往上滑 → 停 2 秒）。"
  fi
else
  echo "读不到（没有 root，或路径不同）。改用应用内：设置 → 性能 → 流式上翻痕迹 → 复制。"
  sed -n '1,3p' "$OUT/pin.err" 2>/dev/null || true
fi

say "3/3 帧数据（这部分需要你自己用手指滑动屏幕）"
echo "先重置："
echo "  adb shell dumpsys gfxinfo ${PKG} reset"
echo "然后【用手指】上下滑动约 3 秒（不要用 adb input swipe，它测不出卡顿），再跑："
echo "  adb shell dumpsys gfxinfo ${PKG} | head -40"
echo
echo "判读重点："
echo "  · Number Slow UI thread   ← 跟手性主要看这个"
echo "  · Janky frames 的百分比"
echo "  · framestats 里 >32ms 的帧，看是 measure/layout 还是 draw 超时"
