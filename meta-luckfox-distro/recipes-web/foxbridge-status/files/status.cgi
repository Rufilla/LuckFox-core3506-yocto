#!/bin/sh
# CGI script — system status page for Foxbridge

# Handle LED toggle via query string
case "$QUERY_STRING" in
    toggle=user)
        LED="/sys/class/leds/user/brightness"
        CUR=$(cat "$LED" 2>/dev/null)
        if [ "$CUR" = "0" ]; then
            echo 1 > "$LED"
        else
            echo 0 > "$LED"
        fi
        echo "Status: 303 See Other"
        echo "Location: /status.cgi"
        echo ""
        exit 0
        ;;
esac

echo "Content-Type: text/html"
echo ""

HOSTNAME=$(hostname)
UPTIME=$(uptime -p 2>/dev/null || uptime | sed 's/.*up /up /' | sed 's/,.*load.*//')
LOAD=$(cat /proc/loadavg | cut -d' ' -f1-3)
NPROC=$(nproc)

# Memory
read _ TOTAL _ < /proc/meminfo
read _ _ _ < /proc/meminfo  # skip MemFree line
MEM_TOTAL=$(awk '/^MemTotal/ {printf "%.0f", $2/1024}' /proc/meminfo)
MEM_AVAIL=$(awk '/^MemAvailable/ {printf "%.0f", $2/1024}' /proc/meminfo)
MEM_USED=$((MEM_TOTAL - MEM_AVAIL))
MEM_PCT=$((MEM_USED * 100 / MEM_TOTAL))

# Network — ethernet (end0)
ETH_IP=$(ip -4 addr show end0 2>/dev/null | awk '/inet / {print $2}')
ETH_MAC=$(cat /sys/class/net/end0/address 2>/dev/null)
ETH_SPEED=$(cat /sys/class/net/end0/speed 2>/dev/null)
ETH_OPER=$(cat /sys/class/net/end0/operstate 2>/dev/null)

# Network — wifi (first wl* interface, if any)
WIFI_IF=$(ls /sys/class/net/ 2>/dev/null | awk '/^wl/ {print; exit}')
if [ -n "$WIFI_IF" ]; then
    WIFI_IP=$(ip -4 addr show "$WIFI_IF" 2>/dev/null | awk '/inet / {print $2}')
    WIFI_MAC=$(cat /sys/class/net/$WIFI_IF/address 2>/dev/null)
    WIFI_OPER=$(cat /sys/class/net/$WIFI_IF/operstate 2>/dev/null)
    # Link quality from /proc/net/wireless (nonzero implies associated)
    WIFI_LINK=$(awk -v i="$WIFI_IF:" '$1==i {gsub(/\./,"",$3); print $3}' /proc/net/wireless 2>/dev/null)
    # SSID via wpa_cli if the ctrl_interface is accessible
    WIFI_SSID=$(wpa_cli -i "$WIFI_IF" status 2>/dev/null | awk -F= '/^ssid=/ {print $2}')
    if [ -z "$WIFI_SSID" ] && [ -n "$WIFI_LINK" ] && [ "$WIFI_LINK" != "0" ]; then
        WIFI_SSID="(associated, SSID unavailable)"
    fi
fi

# System
KERNEL=$(uname -r)
MODEL=$(cat /proc/device-tree/model 2>/dev/null)
TEMP=$(awk '{printf "%.1f", $1/1000}' /sys/class/thermal/thermal_zone0/temp 2>/dev/null)
CPU_FREQ=$(awk '{printf "%.0f", $1/1000}' /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq 2>/dev/null)
OPTEE_VER=$(sed -n 's/.*tee-v\([^,]*\).*/\1/p' /proc/cmdline 2>/dev/null)
EMMC_LIFE_RAW=$(cat /sys/class/mmc_host/mmc0/mmc0:0001/life_time 2>/dev/null)
# Decode eMMC life_time: 0x01=0-10%, 0x02=10-20%, ..., 0x0a=90-100%, 0x0b=exceeded
EMMC_LIFE_A=$(echo "$EMMC_LIFE_RAW" | awk '{gsub(/0x0?/,"",$1); v=$1*10; if(v>0) printf "%d%%", v-10; else print "n/a"}')
EMMC_LIFE="${EMMC_LIFE_A} used"

# Storage
ROOT_DEV=$(mount | awk '/ on \/ / {print $1}')
if echo "$ROOT_DEV" | grep -q 'mmcblk0'; then
    # Check for boot partitions (eMMC has them, SD doesn't)
    if [ -e /sys/class/block/mmcblk0boot0 ]; then
        BOOT_MEDIA="eMMC"
    else
        BOOT_MEDIA="SD card"
    fi
    EMMC_SIZE=$(awk '{printf "%.1f GB", $1 * 512 / 1073741824}' /sys/class/block/mmcblk0/size 2>/dev/null)
else
    BOOT_MEDIA="unknown"
    EMMC_SIZE="n/a"
fi
DISK_USED=$(df / | awk 'NR==2 {printf "%.0f", $3/1024}')
DISK_TOTAL=$(df / | awk 'NR==2 {printf "%.0f", $2/1024}')
DISK_PCT=$(df / | awk 'NR==2 {gsub(/%/,""); print $5}')

# LEDs
USER_LED_STATE=$(cat /sys/class/leds/user/brightness 2>/dev/null)
if [ "$USER_LED_STATE" = "0" ]; then
    USER_LED_TEXT="OFF"
    USER_LED_COLOR="#8b949e"
else
    USER_LED_TEXT="ON"
    USER_LED_COLOR="#3fb950"
fi
SYS_LED_TRIGGER=$(cat /sys/class/leds/sys/trigger 2>/dev/null | sed 's/.*\[\(.*\)\].*/\1/')

cat <<HTML
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="5">
<title>${HOSTNAME}</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: -apple-system, sans-serif; background: #0d1117; color: #c9d1d9; padding: 2rem; }
  h1 { color: #58a6ff; margin-bottom: 0.5rem; }
  .subtitle { color: #8b949e; margin-bottom: 2rem; }
  .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 1rem; }
  .card { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 1.2rem; }
  .card h2 { color: #58a6ff; font-size: 0.85rem; text-transform: uppercase; letter-spacing: 0.05em; margin-bottom: 0.8rem; }
  .stat { display: flex; justify-content: space-between; padding: 0.4rem 0; border-bottom: 1px solid #21262d; }
  .stat:last-child { border-bottom: none; }
  .label { color: #8b949e; }
  .value { color: #f0f6fc; font-family: monospace; }
  .bar { background: #21262d; border-radius: 4px; height: 8px; margin-top: 0.5rem; }
  .bar-fill { background: #58a6ff; border-radius: 4px; height: 100%; }
  .btn { display: inline-block; padding: 0.4rem 1rem; border: 1px solid #30363d; border-radius: 6px; background: #21262d; color: #c9d1d9; text-decoration: none; font-size: 0.85rem; cursor: pointer; }
  .btn:hover { background: #30363d; }
  .led-dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 0.4rem; vertical-align: middle; }
</style>
</head>
<body>
<h1>${HOSTNAME}</h1>
<p class="subtitle">${MODEL}</p>
<div class="grid">
  <div class="card">
    <h2>System</h2>
    <div class="stat"><span class="label">Uptime</span><span class="value">${UPTIME}</span></div>
    <div class="stat"><span class="label">Kernel</span><span class="value">${KERNEL}</span></div>
    <div class="stat"><span class="label">CPUs</span><span class="value">${NPROC}</span></div>
    <div class="stat"><span class="label">CPU Freq</span><span class="value">${CPU_FREQ:-?} MHz</span></div>
    <div class="stat"><span class="label">Load</span><span class="value">${LOAD}</span></div>
    <div class="stat"><span class="label">Temperature</span><span class="value">${TEMP:-n/a} C</span></div>
    <div class="stat"><span class="label">OP-TEE</span><span class="value">${OPTEE_VER:-n/a}</span></div>
  </div>
  <div class="card">
    <h2>Memory</h2>
    <div class="stat"><span class="label">Used / Total</span><span class="value">${MEM_USED} / ${MEM_TOTAL} MB</span></div>
    <div class="bar"><div class="bar-fill" style="width:${MEM_PCT}%"></div></div>
  </div>
  <div class="card">
    <h2>Ethernet (end0)</h2>
    <div class="stat"><span class="label">State</span><span class="value">${ETH_OPER:-unknown}</span></div>
    <div class="stat"><span class="label">IP Address</span><span class="value">${ETH_IP:-down}</span></div>
    <div class="stat"><span class="label">MAC</span><span class="value">${ETH_MAC:-n/a}</span></div>
    <div class="stat"><span class="label">Link Speed</span><span class="value">${ETH_SPEED:-?} Mbps</span></div>
  </div>
HTML

if [ -n "$WIFI_IF" ]; then
cat <<HTML
  <div class="card">
    <h2>WiFi (${WIFI_IF})</h2>
    <div class="stat"><span class="label">State</span><span class="value">${WIFI_OPER:-unknown}</span></div>
    <div class="stat"><span class="label">SSID</span><span class="value">${WIFI_SSID:-not associated}</span></div>
    <div class="stat"><span class="label">IP Address</span><span class="value">${WIFI_IP:-down}</span></div>
    <div class="stat"><span class="label">MAC</span><span class="value">${WIFI_MAC:-n/a}</span></div>
    <div class="stat"><span class="label">Link Quality</span><span class="value">${WIFI_LINK:-n/a}</span></div>
  </div>
HTML
else
cat <<HTML
  <div class="card">
    <h2>WiFi</h2>
    <div class="stat"><span class="label">Status</span><span class="value">no dongle</span></div>
  </div>
HTML
fi

cat <<HTML
  <div class="card">
    <h2>Storage</h2>
    <div class="stat"><span class="label">Boot Media</span><span class="value">${BOOT_MEDIA}</span></div>
    <div class="stat"><span class="label">Device</span><span class="value">${ROOT_DEV} (${EMMC_SIZE})</span></div>
    <div class="stat"><span class="label">Rootfs Used</span><span class="value">${DISK_USED} / ${DISK_TOTAL} MB</span></div>
    <div class="bar"><div class="bar-fill" style="width:${DISK_PCT}%"></div></div>
    <div class="stat"><span class="label">eMMC Health</span><span class="value">${EMMC_LIFE:-n/a}</span></div>
  </div>
  <div class="card">
    <h2>LEDs</h2>
    <div class="stat"><span class="label">SYS (D1)</span><span class="value"><span class="led-dot" style="background:#3fb950"></span>${SYS_LED_TRIGGER}</span></div>
    <div class="stat">
      <span class="label">USER (D5)</span>
      <span class="value"><span class="led-dot" style="background:${USER_LED_COLOR}"></span>${USER_LED_TEXT} <a class="btn" href="/status.cgi?toggle=user">Toggle</a></span>
    </div>
  </div>
</div>
</body>
</html>
HTML
