# LuckFox Yocto v1.3.0

**Released:** 2026-06-11
**Target:** LuckFox Core3506-0808 (RK3506B SoC, 512 MB DDR3L, 8 GB eMMC) on Foxbridge Rev A carrier.

Brings up the 7" MIPI-DSI panel end-to-end — display, capacitive touch, and a boot splash — and ships a framebuffer port of DOOM as a demo payload. Also lands milestone M1 of the SWUpdate A/B OTA work (opt-in) and wires the `meta-rufilla-lucerna` scan layer into the build.

> **About this prebuilt image:** `luckfox-image-minimal-foxbridge-v1.3.0.wic` is the **DSI + DOOM demo** configuration — `MACHINE_FEATURES += "screen"` and `LUCKFOX_DOOM=1`. SWUpdate A/B OTA (`LUCKFOX_OTA`) and the OP-TEE secure-boot chain are **opt-in build options and are NOT enabled in this image** (it is a plain extlinux, non-secure boot). Rebuild with the relevant toggles in `conf/local.conf` if you need them — see the docs referenced below.

## What's new since v1.2.0

### 7" DSI display + DOOM
- Brings up the Luckfox **10.1-DSI-TOUCH-A** 7" MIPI-DSI panel via the on-panel **ICN6211** bridge (`chipone-icn6211` driver). Landscape orientation, with a boot splash.
- Kernel `display-subsystem` master explicitly enabled; panel `bpc` + backlight supply wired; VOP drives the panel at its native 800x1280p60 (rotated to landscape for apps).
- The `screen` machine feature now autoboots cleanly: `IMAGE_BOOT_FILES` stages the active `-dsi` DTB into the FAT boot partition under the fixed `rk3506b-luckfox-core3506.dtb` name that `extlinux.conf` references (no more on-target `cp` workaround).
- **DOOM:** `doomgeneric` framebuffer port rendering rotated/landscape to `/dev/fb0`, with the `freedoom` IWAD pulled in automatically. Opt-in via `LUCKFOX_DOOM=1`; launch on-target with the `doom` command.

### Goodix capacitive touch (poll mode)
- Goodix multitouch backported from the Luckfox Lyra SDK, running in poll mode. Valid multitouch confirmed on hardware via the input event device.

### SWUpdate A/B OTA — milestone M1 (opt-in)
- A/B boot-slot selection + rollback built on a persistent U-Boot environment in eMMC; full `.swu` apply verified end-to-end on hardware.
- Ships the complete `/etc/u-boot-initial-env` to close the blank-env brick gap, plus `libubootenv-bin` + `/etc/fw_env.config` so Linux can read/write the slot state.
- Opt-in via `LUCKFOX_OTA=1` (default off). See [`OTA_SWUPDATE_PLAN.md`](../../OTA_SWUPDATE_PLAN.md).

### Build & tooling
- `meta-rufilla-lucerna` scan layer wired into the kernel + U-Boot builds (CVE scanning on; SBOM/create-spdx temporarily off under the Python 3.12 fork-deadlock).
- `poky` relocated in-tree under `sources/poky`; build templates and path references updated.
- OP-TEE from-source + signed-FIT secure-boot work documented (plan + bring-up); not enabled in this prebuilt image.
- `FLASHING.md` and `DEV_SETUP.md` updates, including how to reset the GEEK debug probe when the console goes silent.

## Known issues

- **DOOM input needs a USB keyboard.** The `doom` launcher renders and runs, but interactive control currently requires a USB keyboard; **touch input is not yet wired to DOOM** (touch is poll-only at the driver level).
- **SBOM generation disabled.** `rufilla-lucerna`'s `create-spdx` deadlocks `do_create_runtime_spdx` under Python 3.12 (fakeroot worker forks while a second thread holds a lock). CVE scanning is unaffected. Re-enable once the fork-deadlock is resolved.

## Artifacts

| File | Purpose |
|---|---|
| `luckfox-image-minimal-foxbridge-v1.3.0.wic` | Full eMMC image — DSI + DOOM demo config (~498 MB) |
| `rk3506_spl_loader_v1.3.0.bin` | Combined USB download loader (DDR init + SPL + usbplug) |
| `SHA256SUMS` | Integrity checksums |

See [FLASHING.md](../../FLASHING.md) for the full flashing procedure.

## Pinned source versions

| Component | Repository | Branch | Commit |
|-----------|-----------|--------|--------|
| rkbin | rockchip-linux/rkbin | master | `74213af1e952c4683d2e35952507133b61394862` |
| Kernel | rockchip-linux/kernel | develop-6.1 | `d2b4477a1df699e6639e83837c7dc45ea1d1d73f` |
| U-Boot | rockchip-linux/u-boot | next-dev | `b14196eade471bbc000c368f8555f2a2a1ecc17d` |
