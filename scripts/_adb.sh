#!/usr/bin/env bash
# 共享的设备定位逻辑（被 measure_jank.sh / measure_cpu.sh / verify_device.sh source）。
#
# issue #59 P0：脚本原来把开发机的 adb 路径和**某一次**的无线端口硬编码在文件里，
# 换一台机器、换一次无线调试就连不上，报错还是一串 adb 原文。
#
# 现在的约定：
#   ADB    默认 `adb`（PATH 里有就行）；开发者要指定绝对路径就导出 ADB=/path/to/adb
#   SERIAL 可选。不设时：恰好一台设备就用它；没有或多于一台 ⇒ 给可读错误并列出候选
#
# 用法：source "$(dirname "$0")/_adb.sh"; adb_pick || exit 1; "${ADB_CMD[@]}" shell ...

ADB="${ADB:-adb}"

say_err() { printf '%s\n' "$*" >&2; }

# 选出目标设备，写入全局数组 ADB_CMD（形如 `adb -s SERIAL`）。
adb_pick() {
    if ! command -v "$ADB" >/dev/null 2>&1 && [ ! -x "$ADB" ]; then
        say_err "找不到 adb（ADB=$ADB）。请把它放进 PATH，或设置 ADB=/完整路径/adb"
        return 1
    fi

    if [ -n "${SERIAL:-}" ]; then
        ADB_CMD=("$ADB" -s "$SERIAL")
        # 显式指定的设备也要确认在线 —— 否则后面的报错会变成一堆 "error: closed"
        if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
            say_err "设备 $SERIAL 不在线。"
            say_err "  无线调试的端口每次重启都可能变，先跑：adb mdns services"
            say_err "  然后：export SERIAL=<ip:port>"
            return 1
        fi
        return 0
    fi

    local devices
    devices=$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')
    local count
    count=$(printf '%s\n' "$devices" | grep -c . || true)
    case "$count" in
        0)
            say_err "没有已连接的设备。"
            say_err "  USB：插上并在手机上允许调试。"
            say_err "  无线：adb mdns services 找到 <ip:port> 后 adb connect <ip:port>"
            say_err "        再 export SERIAL=<ip:port>（或保证只有这一台在线）"
            return 1
            ;;
        1)
            ADB_CMD=("$ADB" -s "$devices")
            return 0
            ;;
        *)
            say_err "有多台设备在线，请用 SERIAL 指定其中一台："
            printf '    %s\n' $devices >&2
            return 1
            ;;
    esac
}

# 应用是否在跑，返回 pid（空 = 没跑）。
app_pid() {
    "${ADB_CMD[@]}" shell pidof "${PKG:-com.suixing.agent}" 2>/dev/null | tr -d '\r' | awk '{print $1}'
}
