# LuckFox Yocto v1.1.0

**Released:** 2026-04-18
**Target:** LuckFox Core3506-0808 (RK3506B SoC, 512 MB DDR3L, 8 GB eMMC) on Foxbridge Rev A carrier.

Adds a live real-time dashboard, evaluation tools, hardware benchmarks, and a WiFi routing fix.

## What's new since v1.0.0

### Live dashboard (`http://foxbridge.local/`)
- Real-time SVG ring gauges: CPU frequency, memory, storage, system load
- CPU frequency OPP step visualization
- Network throughput sparklines (60 s history) and RX/TX rates
- Live uptime counter
- WiFi restart button (with replug hint for wedged r8188eu)
- LED toggle control
- JSON API at `/api.cgi` — polls every 1.5 s, no page reload
- Legacy static status page still available at `/status.cgi`

### Evaluation tools added to image
- `stress-ng` — repeatable CPU/memory/IO stress testing
- `curl` — HTTP testing and API interaction
- `usbutils` (`lsusb`) — USB device enumeration
- `i2c-tools` (`i2cdetect`, `i2cget`) — peripheral probing
- `wireless-regdb` — silences `cfg80211: failed to load regulatory.db` warning

### WiFi routing fix
- `foxbridge-wifi-up` now forces WiFi routes to metric 100 after DHCP
- Ethernet (metric 10) always preferred for default and subnet routing
- Prevents the failure mode where WiFi's metric-0 route shadowed Ethernet

### Documentation updates
- Performance table in README with measured benchmarks (stress-ng, eMMC, boot time, power)
- VALIDATION.md: new Benchmarks section with reproducible stress-ng commands
- Corrected eMMC read speed (22 MB/s measured vs 50 MB/s theoretical)
- Corrected DVFS range (600–1608 MHz, 8 OPPs)
- Fixed: README code block formatting, DEV_SETUP.md ttyUSB path typo,
  VALIDATION.md rkdeveloptool verb inconsistency, BRIEFING.md partition offsets

## Measured performance

| Metric | Value |
|--------|-------|
| CPU | 3x Cortex-A7, DVFS 600–1608 MHz (8 OPPs) |
| stress-ng CPU | 86.6 bogo ops/s (matrixprod, 3 workers, 10 s) |
| stress-ng VM | 4167 bogo ops/s (128 MB, 1 worker, 10 s) |
| stress-ng combined | CPU 228 + VM 5003 + HDD 179 bogo ops/s |
| Ethernet | 100 Mbps full-duplex (100BASE-TX) |
| eMMC read | ~22 MB/s sequential (cold cache) |
| USB1 host | ~35 MB/s raw read (USB2 HS) |
| RAM after boot | 23 MB used of 500 MB |
| Boot to network | ~6 s |
| Idle power | 0.30 W (0.06 A @ 5.07 V) |
| CPU stress power | 0.61 W (0.12 A @ 5.07 V) |

## Artifacts

| File | Purpose |
|---|---|
| `luckfox-image-minimal-foxbridge-v1.1.0.wic` | Full eMMC image (~370 MB) |
| `rk3506_spl_loader_v1.1.0.bin` | Combined USB download loader |
| `SHA256SUMS` | Integrity checksums |

See [FLASHING.md](../../FLASHING.md) for the full flashing procedure.

## Pinned source versions

| Component | Repository | Branch | Commit |
|-----------|-----------|--------|--------|
| rkbin | rockchip-linux/rkbin | master | `74213af1e952c4683d2e35952507133b61394862` |
| Kernel | rockchip-linux/kernel | develop-6.1 | `d2b4477a1df699e6639e83837c7dc45ea1d1d73f` |
| U-Boot | rockchip-linux/u-boot | next-dev | `b14196eade471bbc000c368f8555f2a2a1ecc17d` |
