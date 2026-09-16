# LuckFox Core3506 Yocto Build — Technical Briefing

**Date:** 2026-03-03 (updated 2026-04-18)

**Target hardware:** LuckFox Core3506-0808 SoM (Rockchip RK3506B, 512 MB DDR3L, 8 GB eMMC)

**Build status:** Compiles cleanly. Verified on hardware (Foxbridge Rev A + Core3506-0808): boots to userspace in ~6 s, Ethernet 100 Mbps, USB1 host at USB2 HS (~35 MB/s), USB WiFi (RTL8188EU) associates and gets DHCP. Live dashboard at `http://foxbridge.local/`. Power: 0.30 W idle, 0.61 W full load.

---

## 1. Binary Blobs — What You Need to Know

The RK3506 boot chain **cannot function without proprietary Rockchip binaries**. There is no open-source alternative for DDR initialisation on this SoC. Every boot begins with closed-source code running before any open-source software executes. This is an important **security** consideration.

### 1.1 The Blobs

| Blob | File | Size | Purpose |
|------|------|------|---------|
| DDR init | `rk3506b_ddr_750MHz_v1.06.bin` | 19 KB | Initialises DDR3L memory controller and runs training. **Without this, RAM does not work.** |
| SPL | `rk3506_spl_v1.11.bin` | 180 KB | Secondary Program Loader. Runs after DDR init, loads U-Boot from storage. |
| OP-TEE | `rk3506_tee_v2.10.bin` | 126 KB | Trusted Execution Environment. Bundled into U-Boot's FIT image. Required by Rockchip's trust chain — U-Boot will not build without it. |
| USB Plug | `rk3506_usbplug_v1.03.bin` | 49 KB | USB download bridge for maskrom mode (used by `rkdeveloptool` to flash eMMC over USB). |

### 1.2 Source and Licensing

All blobs come from a single repository: **https://github.com/rockchip-linux/rkbin** (Rockchip's official binary distribution).

- **License:** CLOSED (proprietary, no source available)
- **Yocto handling:** The `rkbin-rk3506.bb` recipe fetches the repo and the build requires `LICENSE_FLAGS_ACCEPTED += "commercial"` in `local.conf`
- **Pinned to commit:** `74213af` (2025-06-13) — changing this commit may pull incompatible blob versions
- **No redistribution terms published** — Rockchip does not provide explicit redistribution licensing for rkbin. This is standard practice for Rockchip BSPs but should be reviewed if distributing production images commercially.

### 1.3 SoC Variant Selection

The RK3506 family has two DDR init variants:
- `rk3506_ddr_*.bin` — for RK3506 (G2 package, used on Lyra boards)
- `rk3506b_ddr_*.bin` — for RK3506B (Core3506 SoM)

**The wrong DDR blob will fail silently at boot** — the board will appear completely dead. The variant is selected by `RKBIN_SOC_VARIANT = "rk3506b"` in the machine config. If you create a new machine for a different RK3506 board, verify which SoC variant it uses.

### 1.4 How Blobs Are Assembled into Boot Images

```
rkbin repo
  ├─ rk3506b_ddr_*.bin ─┐
  │                      ├─ mkimage ──> idbloader.img  (written at sector 64 on eMMC/SD)
  ├─ rk3506_spl_*.bin ──┘
  │
  └─ rk3506_tee_*.bin ──> copied into U-Boot source as tee.bin
                           ──> Makefile runs make_fit_optee.sh
                           ──> u-boot.img (FIT: U-Boot + OP-TEE, written at 8 MiB offset)
```

The `idbloader.img` and `u-boot.img` are written as raw data at fixed sector offsets — they are NOT in any filesystem partition. The RK3506 BootROM hardcodes sector 64 as the idbloader location.

---

## 2. Kernel — Vendor BSP, No Mainline Path

### 2.1 Why Vendor Kernel

**There is no mainline Linux support for the RK3506.** No DTS, no SoC driver support, no clock/pinctrl/power domain drivers exist upstream. The vendor BSP kernel is the only option.

| Property | Value |
|----------|-------|
| Source | https://github.com/rockchip-linux/kernel |
| Branch | `develop-6.1` |
| Pinned commit | `d2b4477` (2025-06-19) |
| Base version | Linux 6.1.x |
| Defconfig | `rk3506_defconfig` (in-tree) |
| Architecture | ARMv7-A (32-bit), Cortex-A7 triple-core |

### 2.2 What the Vendor Kernel Contains

The vendor fork includes hundreds of Rockchip-specific patches:
- SoC clock tree (`clk-rk3506.c`), pinctrl, power domains
- Rockchip-specific MMC controller driver (`dw-mshc-rockchip`)
- FIQ debugger (serial console via `ttyFIQ0` — not standard `ttyS0`)
- AMP (Asymmetric Multi-Processing) support for the Cortex-M0 co-processor
- Display pipeline (VOP, MIPI DSI), audio (I2S, ACODEC, PDM)
- Rockchip-specific suspend/resume, thermal, DVFS

### 2.3 Vendor Defconfig Assumptions

The in-tree `rk3506_defconfig` was written for Rockchip's reference boards which boot from SPI NAND flash. Key implications:

- **`CONFIG_EXT4_FS=m` (module)** — We override this to `=y` (built-in) via `ext4-builtin.cfg` because our root filesystem is ext4. Without this override, the kernel panics at boot: it cannot mount rootfs because ext4 support hasn't loaded yet (no initramfs to load modules from).

- **`CONFIG_ROCKCHIP_MINI_KERNEL=y`** — Many subsystems are disabled or modular to minimise kernel size for SPI NAND targets. Features like USB, Ethernet, display etc. require additional config fragments.

- **Available config fragments** (in the vendor source tree, can be added to `SRC_URI` as needed):

  | Fragment | Enables |
  |----------|---------|
  | `rk3506-ethernet.config` | RMII Ethernet (gmac0/gmac1) |
  | `rk3506-usb-host.config` | USB host controller |
  | `rk3506-usb-otg.config` | USB OTG |
  | `rk3506-display.config` | MIPI DSI / LVGL display |
  | `rk3506-wifibt.config` | WiFi/BT (cfg80211, mac80211) |

### 2.4 Updating the Kernel

To move to a newer vendor kernel commit:
1. Update `SRCREV` in `linux-rockchip-rk3506_6.1.bb`
2. Verify that `rk3506_defconfig` still exists and hasn't been renamed
3. Verify that `rk3506.dtsi` (our DTS depends on it) hasn't had breaking changes
4. Rebuild and check that config fragments still apply cleanly

**There is no "just update to the next LTS" option.** Moving to mainline would require porting all SoC support — a months-long effort that Rockchip has not done.

---

## 3. Device Tree — What's Custom vs Inherited

### 3.1 DTS Hierarchy

```
rk3502.dtsi          (base SoC: CPU cores, GIC, UART, I2C, SPI, timers)
  └── rk3506.dtsi    (display, CAN, dual GMAC, additional peripherals)
        └── rk3506b-luckfox-core3506.dts   ← OUR FILE (board-specific)
```

The `.dtsi` files come from the vendor kernel source. Our custom `.dts` file lives in the layer at `meta-rockchip-rk3506/recipes-kernel/linux/files/rk3506b-luckfox-core3506.dts` and is injected into the kernel source tree at build time.

### 3.2 What Our Custom DTS Defines

The custom DTS is minimal — it only enables what's needed for first boot:

| Node | What it does | Why |
|------|-------------|-----|
| `chosen` / bootargs | Sets earlycon address, console, root device | Kernel needs to know where to find serial and rootfs |
| `fiq-debugger` | Enables serial console on UART0 at 115200 baud (Linux side) | Rockchip uses FIQ debugger instead of standard 8250. U-Boot recipe is currently sstate-cached at 1500000; the kernel takes over the same UART at 115200. |
| `vcc_sys`, `vcc3v3_stb`, `vcc_1v8`, `vcc_ddr` | Fixed voltage regulators | Tells kernel about power supply chain on the SoM |
| `vdd_arm` | PWM-controlled CPU voltage regulator | Required for CPU frequency scaling (DVFS) |
| `&mmc` | MMC controller at `0xff480000` — 4-bit, 3.3V, high-speed | Enables eMMC/SD card boot |
| `&pwm0_4ch_0` | PWM channel for vdd_arm regulator | Must be enabled for CPU regulator to work |
| `&usb2phy` | USB2 PHY parent node | Must be enabled for child port PHYs to work |
| `&usb20_otg1` | DWC2 OTG controller at `0xff780000`, `dr_mode = "host"` | USB1 host port (H6 USB-C on foxbridge) |
| `&u2phy_otg1` | USB2 PHY host-port, `phy-supply = <&vcc5v0_otg1>` | VBUS power via TPS2553 switch on carrier |
| `vcc5v0_otg1` | Fixed 5V regulator for USB1 VBUS | Foxbridge Rev A required two reworks (E1: VBUS pull-up; E2: 0Ω D+/D-) — both done. See DEV_SETUP.md. |

### 3.3 What Our DTS Does NOT Define (Yet)

- **USB OTG0** — not connected on foxbridge carrier; needs DTS and mode selection if used
- **Display** — no display on initial target
- **I2C, SPI, GPIO** — no peripherals connected yet
- **Audio** — not needed for initial bringup

> Note: Ethernet (gmac0) and USB OTG1 host were in this list in earlier revisions — both are now defined and verified on hardware (see §3.2).

### 3.4 Single MMC Controller Constraint

**The RK3506 has only ONE MMC controller** (`mmc@ff480000`). The on-module eMMC and any carrier board SD card slot share this controller — they cannot be used simultaneously.

Our DTS uses a "universal" configuration that works with both media:
```dts
&mmc {
    bus-width = <4>;
    broken-cd;          /* no card-detect GPIO — assume media present */
    cap-sd-highspeed;   /* SD card high-speed mode */
    cap-mmc-highspeed;  /* eMMC high-speed mode */
    vmmc-supply = <&vcc3v3_stb>;
    vqmmc-supply = <&vcc3v3_stb>;  /* 3.3V IO — no 1.8V signalling */
};
```

The BootROM decides which medium to boot from based on what it finds at power-on. If eMMC has a valid idbloader at sector 64, it boots from eMMC. To force SD card boot, either erase eMMC sector 64 or use maskrom mode.

### 3.5 Modifying the Device Tree

To enable new peripherals:
1. Edit `meta-rockchip-rk3506/recipes-kernel/linux/files/rk3506b-luckfox-core3506.dts`
2. Reference the vendor `.dtsi` files (in the kernel source under `arch/arm/boot/dts/`) for available nodes and their properties
3. Add the corresponding kernel config fragment to `SRC_URI` in the kernel recipe (e.g., `rk3506-ethernet.config` for Ethernet)
4. Rebuild: `bitbake linux-rockchip-rk3506 -c compile -f && bitbake luckfox-image-minimal`

The DTS is **not** a standalone file — it depends on `rk3506.dtsi` from the vendor kernel. If the kernel `SRCREV` changes, check for DTSI changes that might break our board DTS.

---

## 4. U-Boot — Vendor Fork with Custom Patches

### 4.1 U-Boot Version

| Property | Value |
|----------|-------|
| Source | https://github.com/rockchip-linux/u-boot |
| Branch | `next-dev` |
| Pinned commit | `b14196e` (2025-06-20) |
| Version | **2017.09** (Rockchip vendor fork — 8 years behind upstream) |
| Defconfig | `rk3506_defconfig` + `rk3506b.config` fragment |

This is not a typo — Rockchip maintains a heavily modified 2017.09 fork. It contains Rockchip-specific boot infrastructure (Android boot, FIT image generation, display init, fastboot) that does not exist in upstream U-Boot.

### 4.2 Patches We Apply

**Patch: `0001-rk3506-add-distro-boot-fallback.patch`**

This is a **critical** patch. Without it, the board cannot boot our Yocto images.

The vendor U-Boot boot command (`RKIMG_BOOTCOMMAND`) only tries:
1. `boot_fit` — looks for a Rockchip-format FIT image (Android/vendor SDK style)
2. `boot_android` — looks for Android boot partitions

Neither of these matches our Yocto partition layout (FAT /boot with extlinux.conf). The patch adds a third fallback:

3. `run distro_bootcmd` — standard U-Boot distro boot that reads extlinux.conf

**Config fragment: `rk3506-distroboot.config`**

Enables filesystem and partition commands needed for distro boot:
```
CONFIG_CMD_PART=y         # Partition table access
CONFIG_CMD_FS_GENERIC=y   # Generic FS commands (ls, load)
CONFIG_CMD_FAT=y          # FAT filesystem (our /boot partition)
CONFIG_CMD_EXT4=y         # ext4 filesystem (our rootfs)
CONFIG_CMD_PXE=y          # PXE/extlinux.conf parsing
CONFIG_CMD_SOURCE=y       # Script execution
```

### 4.3 Known Risk: boot_fit May Hang

The patched boot sequence is: `boot_fit` → `boot_android` → `distro_bootcmd`.

If `boot_fit` **hangs** instead of cleanly failing (e.g., it finds a partial FIT header on eMMC), distro boot is never reached. This is the single biggest boot risk. Workaround: enter maskrom mode, re-flash with a U-Boot that skips `boot_fit` entirely.

---

## 5. Boot Chain Summary

```
┌─────────────────────────────────────────────────────────────────┐
│  Power On                                                       │
│    ↓                                                            │
│  RK3506 BootROM (mask ROM, in silicon)                          │
│    Reads sector 64 from eMMC or SD card                         │
│    ↓                                                            │
│  idbloader.img                                         BLOB ●   │
│    DDR init blob: rk3506b_ddr_750MHz_v1.06.bin                  │
│    SPL: rk3506_spl_v1.11.bin                                    │
│    ↓                                                            │
│  u-boot.img (FIT image at 8 MiB offset)                         │
│    U-Boot proper (GPL, compiled from source)      OPEN SOURCE ○ │
│    OP-TEE: rk3506_tee_v2.10.bin                        BLOB ●   │
│    ↓                                                            │
│  U-Boot runs RKIMG_BOOTCOMMAND:                                 │
│    1. boot_fit      → fails (no vendor FIT image)               │
│    2. boot_android  → fails (no Android partitions)             │
│    3. distro_bootcmd → reads /boot/extlinux/extlinux.conf  ✓    │
│    ↓                                                            │
│  Linux kernel (zImage)                             OPEN SOURCE ○│
│    + Device tree (rk3506b-luckfox-core3506.dtb)                 │
│    ↓                                                            │
│  Root filesystem (ext4, LABEL=rootfs)              OPEN SOURCE ○│
│    systemd → login prompt                                       │
└─────────────────────────────────────────────────────────────────┘

● = proprietary binary blob (no source available)
○ = open source (compiled from source in build)
```

### 5.1 Partition Layout (eMMC and SD Card)

Both images use identical sector offsets:

| Offset | Content | Filesystem |
|--------|---------|------------|
| Sector 64 (32 KiB) | `idbloader.img` | Raw (no partition) |
| 8 MiB | `u-boot.img` | Raw (no partition) |
| 16 MiB | `/boot` — zImage + DTB + extlinux.conf | FAT32, ~83 MiB |
| ~120 MiB | `/` — root filesystem | ext4, fills remaining space |

Sector 64 is **hardcoded in the RK3506 BootROM** — this cannot be changed.

---

## 6. Build Reproducibility

### 6.1 All Sources Are Pinned

Every external source is pinned to a specific git commit:

| Component | Commit | Date |
|-----------|--------|------|
| rkbin (blobs) | `74213af` | 2025-06-13 |
| Kernel | `d2b4477` | 2025-06-19 |
| U-Boot | `b14196e` | 2025-06-20 |
| Poky (Yocto) | scarthgap branch | via `setup-layers.json` |

Builds are deterministic given the same Poky version and host toolchain. The `setup-layers.json` file can recreate the full build environment.

### 6.2 What's in Git vs What's Built

**Tracked in git:** All layer metadata, recipes, patches, DTS, config fragments, WKS partition layouts, documentation.

**NOT tracked (gitignored):** `build/` output directory (contains compiled artifacts, sstate cache, downloaded sources). The build output is ~50 GB and is regenerated by `bitbake luckfox-image-minimal`.

**Pre-built images** for flashing are in `build/tmp-glibc/deploy/images/luckfox-core3506/`:
- `luckfox-image-minimal-luckfox-core3506-emmc.wic` (287 MiB) — for eMMC via maskrom
- `luckfox-image-minimal-luckfox-core3506-sdcard.wic` (287 MiB) — dd to SD card

---

## 7. Key Risks and Open Questions

| # | Risk | Severity | Detail |
|---|------|----------|--------|
| 1 | `boot_fit` hangs instead of failing | **High** | If vendor FIT loader hangs, distro boot is never reached. Recovery: maskrom reflash. |
| 2 | Wrong DDR blob variant | **High** | RK3506 vs RK3506B DDR blobs are not interchangeable. Board appears dead if wrong. |
| 3 | eMMC IO voltage mismatch | **Medium** | DTS uses 3.3V signalling. Core3506-0808 pinout shows 1.8V(NC) for SDMMC domain. May need adjustment. |
| 4 | Vendor kernel API stability | **Medium** | Rockchip's `develop-6.1` branch has no stability guarantees. Updating SRCREV may break DTS or defconfig. |
| 5 | OP-TEE version coupling | **Medium** | OP-TEE blob must be compatible with U-Boot version. Updating one without the other may cause boot failure. |
| 6 | No blob redistribution licence | **Low** | Rockchip rkbin repo has no explicit licence file. Review needed for commercial distribution. |
| 7 | FIQ debugger interrupt number | **Low** | GIC_SPI 115 was derived from vendor EVB DTS. If wrong, serial console won't work but boot may still succeed. |

---

## 8. Quick Reference — Common Tasks

**Rebuild everything from scratch:**
```bash
source sources/poky/oe-init-build-env build
bitbake luckfox-image-minimal
```

**Rebuild just the kernel (after DTS or config changes):**
```bash
bitbake linux-rockchip-rk3506 -c compile -f && bitbake luckfox-image-minimal
```

**Flash eMMC via maskrom (USB):** see [FLASHING.md](FLASHING.md). The short version (third-party rkdeveloptool variant — most distro packages use this):

```bash
# 1. Build the combined USB download loader once:
RKBIN=build/tmp-glibc/work/armv7at2hf-neon-oe-linux-gnueabi/u-boot-rockchip-rk3506/2017.09/rkbin
( cd $RKBIN && ./tools/boot_merger RKBOOT/RK3506BMINIALL.ini )

# 2. Put board into maskrom (hold BOOT, pulse RESET, release BOOT)
rkdeveloptool list

# 3. Flash
rkdeveloptool boot $RKBIN/rk3506_spl_loader_v1.06.111.bin
rkdeveloptool write 0 build/tmp-glibc/deploy/images/luckfox-core3506/luckfox-image-minimal-luckfox-core3506.rootfs.wic
rkdeveloptool reset
```

**Serial console:**
```bash
# Linux kernel runs at 115200 8N1; use this for normal operation:
picocom -b 115200 /dev/ttyUSB0
# U-Boot/OP-TEE early stages emit at 1500000 — switch baud if you need to debug those.
```
