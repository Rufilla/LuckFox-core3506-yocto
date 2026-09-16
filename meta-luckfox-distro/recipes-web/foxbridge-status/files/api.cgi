#!/bin/sh
# JSON API endpoint for foxbridge live dashboard

echo "Content-Type: application/json"
echo "Cache-Control: no-cache"
echo ""

# First backlight device (if any). DSI panel images expose a
# pwm-backlight class device here; headless images have nothing.
BL_DIR=$(ls -d /sys/class/backlight/*/ 2>/dev/null | head -1)
BL_DIR=${BL_DIR%/}
BL_NAME=$(basename "$BL_DIR" 2>/dev/null)

# Handle LED toggle / wifi restart / backlight set
case "$QUERY_STRING" in
    toggle=user)
        LED="/sys/class/leds/user/brightness"
        CUR=$(cat "$LED" 2>/dev/null)
        if [ "$CUR" = "0" ]; then echo 1 > "$LED"; else echo 0 > "$LED"; fi
        ;;
    restart=wifi)
        systemctl restart foxbridge-wifi >/dev/null 2>&1 &
        ;;
    set_backlight=*)
        # Numeric only, clamped to [0, max_brightness]. The slider in
        # dashboard.html is range-bounded, but harden the CGI in case
        # someone POSTs a hand-crafted value.
        REQ=${QUERY_STRING#set_backlight=}
        if [ -n "$BL_DIR" ] && expr "$REQ" : '^[0-9][0-9]*$' >/dev/null 2>&1; then
            BL_MAX=$(cat "$BL_DIR/max_brightness" 2>/dev/null || echo 255)
            [ "$REQ" -lt 0 ] && REQ=0
            [ "$REQ" -gt "$BL_MAX" ] && REQ=$BL_MAX
            echo "$REQ" > "$BL_DIR/brightness" 2>/dev/null
        fi
        ;;
esac

# Uptime
UPTIME_S=$(awk '{printf "%.0f", $1}' /proc/uptime)

# CPU
NPROC=$(nproc)
CPU_FREQ=$(awk '{printf "%.0f", $1/1000}' /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq 2>/dev/null)
CPU_FREQS=$(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies 2>/dev/null | tr ' ' '\n' | awk 'NF{printf "%.0f,", $1/1000}' | sed 's/,$//')
LOAD1=$(awk '{print $1}' /proc/loadavg)
LOAD5=$(awk '{print $2}' /proc/loadavg)
LOAD15=$(awk '{print $3}' /proc/loadavg)

# Per-CPU frequencies
CPU_FREQ_LIST=""
i=0
while [ $i -lt $NPROC ]; do
    f=$(awk '{printf "%.0f", $1/1000}' /sys/devices/system/cpu/cpu${i}/cpufreq/scaling_cur_freq 2>/dev/null)
    [ -n "$CPU_FREQ_LIST" ] && CPU_FREQ_LIST="${CPU_FREQ_LIST},"
    CPU_FREQ_LIST="${CPU_FREQ_LIST}${f:-0}"
    i=$((i+1))
done

# Memory
MEM_TOTAL=$(awk '/^MemTotal/ {printf "%.0f", $2/1024}' /proc/meminfo)
MEM_AVAIL=$(awk '/^MemAvailable/ {printf "%.0f", $2/1024}' /proc/meminfo)
MEM_USED=$((MEM_TOTAL - MEM_AVAIL))

# Ethernet
ETH_STATE=$(cat /sys/class/net/end0/operstate 2>/dev/null)
ETH_IP=$(ip -4 addr show end0 2>/dev/null | awk '/inet / {print $2}')
ETH_MAC=$(cat /sys/class/net/end0/address 2>/dev/null)
ETH_SPEED=$(cat /sys/class/net/end0/speed 2>/dev/null)
ETH_RX=$(cat /sys/class/net/end0/statistics/rx_bytes 2>/dev/null)
ETH_TX=$(cat /sys/class/net/end0/statistics/tx_bytes 2>/dev/null)

# WiFi
WIFI_IF=$(ls /sys/class/net/ 2>/dev/null | awk '/^wl/ {print; exit}')
WIFI_JSON="null"
if [ -n "$WIFI_IF" ]; then
    W_STATE=$(cat /sys/class/net/$WIFI_IF/operstate 2>/dev/null)
    W_IP=$(ip -4 addr show "$WIFI_IF" 2>/dev/null | awk '/inet / {print $2}')
    W_MAC=$(cat /sys/class/net/$WIFI_IF/address 2>/dev/null)
    W_RX=$(cat /sys/class/net/$WIFI_IF/statistics/rx_bytes 2>/dev/null)
    W_TX=$(cat /sys/class/net/$WIFI_IF/statistics/tx_bytes 2>/dev/null)
    W_LINK=$(awk -v i="$WIFI_IF:" '$1==i {gsub(/\./,"",$3); print $3}' /proc/net/wireless 2>/dev/null)
    W_SSID=$(wpa_cli -i "$WIFI_IF" status 2>/dev/null | awk -F= '/^ssid=/ {print $2}')
    WIFI_JSON="{\"interface\":\"${WIFI_IF}\",\"state\":\"${W_STATE}\",\"ip\":\"${W_IP}\",\"mac\":\"${W_MAC}\",\"ssid\":\"${W_SSID}\",\"link_quality\":${W_LINK:-0},\"rx_bytes\":${W_RX:-0},\"tx_bytes\":${W_TX:-0}}"
fi

# Storage
ROOT_DEV=$(mount | awk '/ on \/ / {print $1}')
if echo "$ROOT_DEV" | grep -q 'mmcblk0'; then
    if [ -e /sys/class/block/mmcblk0boot0 ]; then BOOT_MEDIA="eMMC"; else BOOT_MEDIA="SD"; fi
    DISK_SIZE_GB=$(awk '{printf "%.1f", $1 * 512 / 1073741824}' /sys/class/block/mmcblk0/size 2>/dev/null)
else
    BOOT_MEDIA="unknown"; DISK_SIZE_GB="0"
fi
DISK_USED=$(df / | awk 'NR==2 {printf "%.0f", $3/1024}')
DISK_TOTAL=$(df / | awk 'NR==2 {printf "%.0f", $2/1024}')
EMMC_LIFE_RAW=$(cat /sys/class/mmc_host/mmc0/mmc0:0001/life_time 2>/dev/null)
EMMC_LIFE_A=$(echo "$EMMC_LIFE_RAW" | awk '{gsub(/0x0?/,"",$1); v=$1*10; if(v>0) printf "%d", v-10; else print "-1"}')

# LEDs
USER_LED=$(cat /sys/class/leds/user/brightness 2>/dev/null || echo "-1")
SYS_TRIGGER=$(cat /sys/class/leds/sys/trigger 2>/dev/null | sed 's/.*\[\(.*\)\].*/\1/')

# Backlight (only present on screen-enabled images)
BL_JSON="null"
if [ -n "$BL_DIR" ]; then
    BL_BR=$(cat "$BL_DIR/brightness" 2>/dev/null || echo 0)
    BL_MAX=$(cat "$BL_DIR/max_brightness" 2>/dev/null || echo 255)
    BL_JSON="{\"name\":\"${BL_NAME}\",\"brightness\":${BL_BR},\"max\":${BL_MAX}}"
fi

# System
KERNEL=$(uname -r)
MODEL=$(cat /proc/device-tree/model 2>/dev/null | tr -d '\0')

cat <<EOF
{
  "hostname":"${HOSTNAME:-foxbridge}",
  "model":"${MODEL}",
  "kernel":"${KERNEL}",
  "uptime_s":${UPTIME_S},
  "cpu":{
    "cores":${NPROC},
    "freq_mhz":${CPU_FREQ:-0},
    "per_core":[${CPU_FREQ_LIST}],
    "available":[${CPU_FREQS}],
    "load":[${LOAD1},${LOAD5},${LOAD15}]
  },
  "memory":{
    "total_mb":${MEM_TOTAL},
    "used_mb":${MEM_USED},
    "available_mb":${MEM_AVAIL}
  },
  "eth":{
    "state":"${ETH_STATE}",
    "ip":"${ETH_IP}",
    "mac":"${ETH_MAC}",
    "speed":${ETH_SPEED:-0},
    "rx_bytes":${ETH_RX:-0},
    "tx_bytes":${ETH_TX:-0}
  },
  "wifi":${WIFI_JSON},
  "storage":{
    "media":"${BOOT_MEDIA}",
    "device":"${ROOT_DEV}",
    "size_gb":${DISK_SIZE_GB},
    "used_mb":${DISK_USED},
    "total_mb":${DISK_TOTAL},
    "emmc_life_pct":${EMMC_LIFE_A}
  },
  "leds":{
    "user":${USER_LED},
    "sys_trigger":"${SYS_TRIGGER}"
  },
  "backlight":${BL_JSON}
}
EOF
