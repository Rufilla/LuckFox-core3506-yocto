# Hardware setup

These are added from the original developer's notes and may not suit your own purposes.

## USB Relays
ID 0403:6001 Future Technology Devices International, Ltd FT232 Serial (UART) IC

Relay 1 - BOOT (NO)

Relay 2 - Reset (NO)

## Serial to USB adapter for console

ID 0403:6010 Future Technology Devices International, Ltd FT2232C/D/H Dual UART/FIFO IC

When using an ESP-PROG, it appears on /dev/ttyUSBx where x = n+1

Note: Rockchip uses a default baud rate of 1500000 bps for the console, which is non-standard. Some UART adapters may not support this baud rate. The images in this project use 115200. There may be some garbled output from the ROM before switching to U-Boot.

### Core3506 + Foxbridge console (UART0, 115200 8N1)

Reached via a Raspberry-Pi Debugprobe, which enumerates as `/dev/ttyACM0` on the
host. Wiring (Debugprobe ↔ board):

- Debugprobe **RX** → board **P1.8** (UART0 TX / GPIO0_C6)
- Debugprobe **TX** → board **P1.10** (UART0 RX / GPIO0_C7)
- **GND ↔ GND**

If you get silence, swap the probe's TX/RX first. `debug-tweaks` is enabled in the
dev images, so the console logs in as **root with an empty password**. (The boot
getty shows on `ttyFIQ0`; login still works over the Debugprobe UART.)

### Resetting the debug probe ("GEEK") when the console goes silent

The console probe — a Raspberry Pi Debugprobe (CMSIS-DAP), USB **`2e8a:000c`**,
which provides `/dev/ttyACM0` — **disconnects and re-enumerates whenever the board
resets**. That can leave the host's port handle stale (reads 0 bytes). To force a
software disconnect/reconnect (no physical replug, no sudo — the host user is in the
`plugdev` group):

```sh
usbreset 2e8a:000c        # -> "Resetting Debugprobe on Pico (CMSIS-DAP) ... ok"
```

`/dev/ttyACM0` re-appears after re-enumeration. Caveats:

- `usbreset` is a USB-*protocol* reset, **not a power cycle** — it won't restart
  genuinely hung probe firmware (the Pico stays powered). For that, physically
  replug the probe, or use a PPPS-capable hub with `uhubctl` (not installed).
- **A silent console most often just means the board itself is unpowered** — there
  is nothing on its UART TX to forward. Check board power before blaming the probe;
  `usbreset` won't help in that case.

## Foxbridge Development board

### USB1 Host Port (H6, USB-C connector)

USB1 uses the DWC2 OTG controller (`ff780000.usb`) in host mode, routed through
the USB2 PHY host-port. VBUS power is switched by U7 (TPS2553).

**Schematic erratum (resolved 2026-04-12):** R3 was incorrectly connected to
pin 6 of U7 (TPS2553) instead of pin 1. This prevented VBUS from being enabled
on the USB-C host port. Fix: remove connection from R3 to U7 pin 6, reconnect
R3 to U7 pin 1. After rework, 5V VBUS is present and confirmed with inline
USB power measurement.

**Status (2026-04-14): FULLY WORKING.** Both carrier-board erratas resolved
(E1: R3→U7 pin 1; E2: R6/R7 swapped from 22Ω to 0Ω). USB1 host port now
enumerates USB2 high-speed devices end-to-end.

**Verified:**
- Kingston DataTraveler 3.0 (61.9 GB) — enumerated at 480 Mbps, mounted ext4
  partition, wrote and read files, clean unmount.
- TP-Link TL-WN725N (Realtek RTL8188EU, `0bda:8179`) — enumerated, driver
  bound (staging `r8188eu`), associated with WPA2 network, DHCP lease,
  ping to 8.8.8.8 round-tripping at 5 ms.
- Throughput: `dd if=/dev/sda of=/dev/null bs=1M count=200` → **35.0 MB/s**
  (200 MB in 5.71 s), essentially USB2 HS line rate after protocol overhead.
  Host is not the bottleneck.

**E2 root cause (historical, for future pattern-matching):** 22Ω series
resistors on D+/D- (legacy USB1.1 full-speed value) broke USB2 HS
termination. The PHY relies on internal ~45Ω-to-GND termination; 22Ω in
series attenuated the HS chirp handshake, port reset failed with
`usb1-port1: Cannot enable. Maybe the USB cable is bad?`. Fix was a pure
hardware rework (0Ω), no software changes.

**WiFi driver:** `CONFIG_R8188EU=m` (staging) is enabled in
`meta-rockchip-rk3506/recipes-kernel/linux/files/wifi-bt.cfg`. The driver
auto-loads via udev when the dongle is plugged in. The standard
`rtl8xxxu` driver does NOT claim `0bda:8179` even with `RTL8XXXU_UNTESTED=y`
(RTL8188EU is a different silicon family). Firmware (`rtl8188eufw.bin`) is
in `/lib/firmware/rtlwifi/` on the image. End-user bringup is via the
`foxbridge-wifi` systemd unit — see [README.md](README.md) §"After flashing".
**Critical:** wpa_supplicant must be invoked with `-Dwext`; the `r8188eu`
staging driver does not implement nl80211. The `foxbridge-wifi` unit handles
this transparently.
