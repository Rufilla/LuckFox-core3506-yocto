# LuckFox Yocto v1.0.0

**Released:** 2026-04-14
**Target:** LuckFox Core3506-0808 (RK3506B SoC, 512 MB DDR3L, 8 GB eMMC) on Foxbridge Rev A carrier.

This is the first hardware-validated release. Boots end-to-end on real hardware, with Ethernet, USB1 host, and USB WiFi (Realtek RTL8188EU / TL-WN725N) all working out of the box.

## Artifacts

| File | Where to get it | Purpose |
|---|---|---|
| `luckfox-image-minimal-foxbridge-v1.0.0.wic` | **GitHub release asset** (too large for the repo, ~300 MB) | Full eMMC image — flash to LBA 0 with `rkdeveloptool write 0` |
| `rk3506_spl_loader_v1.0.0.bin` | `release/v1.0.0/` in this repo | Combined USB download loader (DDR init + SPL + USB plug). Pass to `rkdeveloptool boot` to wake DDR before writing the WIC. |
| `SHA256SUMS` | `release/v1.0.0/` in this repo | Integrity checksums for both files above — verify the downloaded WIC before flashing. |

**Download flow:** grab the WIC from the repo's Downloads section, drop it next to the `rk3506_spl_loader_v1.0.0.bin` and `SHA256SUMS` files from `release/v1.0.0/`, then `sha256sum -c SHA256SUMS`. See [FLASHING.md](../../FLASHING.md) for the full flashing procedure.

## What works

- **Boot:** BootROM → idbloader (DDR + SPL) → OP-TEE 3.13.0 → U-Boot 2017.09 → Linux 6.1.118 → systemd → login prompt.
- **CPU:** 3-core SMP via PSCI/OP-TEE. DVFS 600–1608 MHz (8 OPPs). stress-ng matrixprod: 86.6 bogo ops/s (3 workers).
- **Storage:** eMMC at 52 MHz high-speed, ~22 MB/s sequential read (cold cache). ext4 rootfs.
- **Networking:**
  - Ethernet (`end0`) via Rockchip GMAC + RMII PHY at 100 Mbps full-duplex.
  - USB WiFi via TL-WN725N (Realtek RTL8188EU) using staging `r8188eu` driver. WPA2 association, DHCP, ~5 ms ping. Brought up automatically by the `foxbridge-wifi` systemd unit once you fill in the placeholder credentials.
- **USB1 host (OTG1, H6 USB-C):** USB2 high-speed, ~35 MB/s mass storage throughput. Hot-plug working.
- **Live dashboard:** `http://foxbridge.local/` (Avahi/mDNS) — real-time gauges for CPU frequency, memory, storage, system load; network throughput sparklines; LED control. Polls every 1.5 s via JSON API. Legacy static status page at `/status.cgi`.
- **SSH server:** openssh, root login enabled (debug-tweaks).
- **mDNS:** `avahi-daemon` advertising `foxbridge.local`.
- **Power:** 0.30 W idle (0.06 A @ 5.07 V), 0.61 W under full CPU stress (0.12 A @ 5.07 V).
- **Boot:** ~6 s from kernel entry to Ethernet link up + SSH-ready. 23 MB RAM used after boot.
- **Evaluation tools:** `stress-ng`, `curl`, `lsusb`, `i2cdetect` included in image.

## What's NOT in v1.0.0

- USB OTG0 — currently used only as the maskrom flashing port; not exposed as USB host.
- Bluetooth — supported in kernel but no userspace bringup.
- Display / LVGL — Core3506 supports MIPI DSI but no display target on Foxbridge.
- Hardened root — image ships with `debug-tweaks` (empty root password). Do not deploy as-is.

## Known issues

- **~1 second of garble at the very start of boot.** The TPL DDR init (`rk3506b_ddr_750MHz_v1.06.bin`) and the initial OP-TEE stage are closed-source Rockchip blobs hardcoded at 1500000 baud — we can't recompile them. From U-Boot SPL onwards everything is at 115200 and fully readable on any standard USB-serial adapter (CH340, CP2102, FT232 basic). Just ignore the first ~1 s of garbage.
- **TL-WN725N (RTL8188EU) reliability.** The in-tree `r8188eu` driver is staging quality. We ship a modprobe fragment that disables its two known power-save failure modes (`rtw_power_mgnt=0 rtw_ips_mode=0`), which fixes the most common symptom (silent disconnect after idle). Even with that, the driver can still occasionally get stuck in a "associated but no traffic" state that only a physical replug recovers from. **For reliable production WiFi, use a dongle supported by the mainline `rtl8xxxu` driver** — RTL8192CU, RTL8188CUS, RTL8192EU, RTL8723BU, and similar are all supported and much more stable. `rtl8xxxu` is already compiled in (`CONFIG_RTL8XXXU=m` + `RTL8XXXU_UNTESTED=y`), so those dongles will just work — the `foxbridge-wifi` unit is dongle-agnostic.

  **Recovering a stuck r8188eu:** if WiFi stops passing traffic,
  ```sh
  cat /proc/net/wireless              # level -256 or 0 → radio stuck
  systemctl restart foxbridge-wifi    # lightest fix — try this first
  # if still stuck: physically unplug + replug the USB WiFi dongle, then:
  systemctl restart foxbridge-wifi
  # last resort:
  reboot
  ```
  The soft restart resolves the common "associated but power-saved out" case. A physical replug is needed when the driver's internal state machine has wedged — USB sysfs unbind/rebind is not enough, only a real USB re-enumeration recovers it.
- **FTDI tty renumbering on host:** if you use a USB FTDI relay board in parallel with the debug serial console, the FT2232's tty number can shift between sessions. Identify the right port with `udevadm info -q property /dev/ttyUSBx | grep INTERFACE_NUM=01`.

## Carrier hardware errata addressed in this build

The Foxbridge Rev A carrier shipped with two USB1 hardware bugs that needed manual rework before USB1 host would work. Both are documented in [DEV_SETUP.md](../../DEV_SETUP.md):

- **E1** (RESOLVED): TPS2553 VBUS switch had no pull-up on `EN`. Fix: 100k from IN to EN.
- **E2** (RESOLVED): 22 Ω series resistors on USB1 D+/D- broke USB2 HS termination. Fix: swap to 0 Ω.

Foxbridge Rev B will incorporate both fixes natively — no rework required.

## Pinned source versions

| Component | Repository | Branch | Commit |
|-----------|-----------|--------|--------|
| rkbin | rockchip-linux/rkbin | master | `74213af1e952c4683d2e35952507133b61394862` |
| Kernel | rockchip-linux/kernel | develop-6.1 | `d2b4477a1df699e6639e83837c7dc45ea1d1d73f` |
| U-Boot | rockchip-linux/u-boot | next-dev | `b14196eade471bbc000c368f8555f2a2a1ecc17d` |

## Reproducing this build

```bash
git clone https://github.com/Rufilla/LuckFox-core3506-yocto luckfox-yocto
cd luckfox-yocto
# Initialize layers (one-time)
sources/poky/scripts/setup-layers
# Build
source sources/poky/oe-init-build-env build
bitbake luckfox-image-minimal
```

See [README.md](../../README.md) and [BRIEFING.md](../../BRIEFING.md) for the full layer architecture and build details.
