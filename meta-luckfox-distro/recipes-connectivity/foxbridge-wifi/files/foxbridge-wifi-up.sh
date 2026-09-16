#!/bin/sh
# Foxbridge WiFi bringup helper.
# Started by foxbridge-wifi.service. Refuses to run while the placeholder
# CHANGEME marker is still present in the wpa_supplicant config.

set -e

CONF=/etc/wpa_supplicant/wpa_supplicant-wlan.conf

if [ ! -f "$CONF" ]; then
    echo "foxbridge-wifi: $CONF missing" >&2
    exit 0
fi

if grep -qE 'CHANGEME_(SSID|PSK)' "$CONF"; then
    echo "foxbridge-wifi: $CONF still contains CHANGEME_SSID/CHANGEME_PSK placeholder — edit it and restart foxbridge-wifi.service" >&2
    exit 0
fi

# Wait up to 30 seconds for a USB WiFi dongle to enumerate. systemd's
# udev-settle only covers cold-plug; the DWC2 USB host probe happens later
# in boot, so the dongle may not be visible yet when this unit first runs.
# The staging r8188eu driver names the interface wlu<N> (systemd renames
# based on USB path); upstream drivers typically use wlan0.
IFACE=""
for _ in $(seq 30); do
    IFACE=$(ls /sys/class/net 2>/dev/null | awk '/^wl/ {print; exit}')
    [ -n "$IFACE" ] && break
    sleep 1
done
if [ -z "$IFACE" ]; then
    echo "foxbridge-wifi: no wl* interface found after 30s (is a USB WiFi dongle connected?)" >&2
    exit 0
fi

echo "foxbridge-wifi: bringing up $IFACE"
ip link set "$IFACE" up

# WEXT backend is mandatory for the r8188eu staging driver. Other nl80211
# drivers (rtl8xxxu, ath9k_htc, etc.) also work via WEXT, so this is safe
# as a universal default for the dongles we ship with.
wpa_supplicant -B -Dwext -i "$IFACE" -c "$CONF"

# udhcpc blocks until it gets a lease or the first DISCOVER times out;
# run with -t 4 so a dead network doesn't hang the unit forever.
udhcpc -i "$IFACE" -t 4 -n || {
    echo "foxbridge-wifi: DHCP failed on $IFACE" >&2
    exit 1
}

# Ensure Ethernet is always preferred over WiFi for routing.
# udhcpc may add a default route and a subnet route with no metric (= 0),
# which beats Ethernet's metric-10 routes. Remove any WiFi default route
# and re-add the subnet route at metric 100.
ip route del default dev "$IFACE" 2>/dev/null || true
SUBNET=$(ip -4 addr show "$IFACE" | awk '/inet / {print $2}')
if [ -n "$SUBNET" ]; then
    ip route del "${SUBNET%/*}"/24 dev "$IFACE" proto kernel 2>/dev/null || true
    ip route add "${SUBNET%/*}"/24 dev "$IFACE" src "${SUBNET%/*}" metric 100 2>/dev/null || true
fi
