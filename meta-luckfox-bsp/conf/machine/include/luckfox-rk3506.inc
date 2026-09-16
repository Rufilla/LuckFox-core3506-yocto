# Shared machine settings for all LuckFox boards using Rockchip RK3506 SoC family.
# Individual board .conf files include this and set board-specific overrides.

# ARM Cortex-A7 tuning (triple-core, 32-bit ARMv7-A with NEON)
require conf/machine/include/arm/armv7a/tune-cortexa7.inc

DEFAULTTUNE = "armv7athf-neon"

# SoC vendor layer provides kernel and bootloader
PREFERRED_PROVIDER_virtual/kernel = "linux-rockchip-rk3506"
PREFERRED_PROVIDER_virtual/bootloader = "u-boot-rockchip-rk3506"

# Vendor BSP kernel 6.1.x — no mainline alternative exists
PREFERRED_VERSION_linux-rockchip-rk3506 = "6.1%"

# In-tree defconfig for the RK3506 SoC
KBUILD_DEFCONFIG = "rk3506_defconfig"

# Serial console: ttyS0 (UART0) at 115200.
# UART0 (0xff0a0000, serial0) on the GPIO0_C6/C7 console pads via the standard
# IOMUX func 1 (uart0_xfer_pins — the vendor-original pad function, not the RMIO
# crossbar). Plain 8250 ttyS0, no fiq-debugger. One consistent UART0 chain:
# optee_os (W14) + native U-Boot + kernel console=ttyS0 — upstream-aligned.
# (Migrated 2026-06-01 from the earlier UART1/ttyS1-via-RMIO bring-up.)
SERIAL_CONSOLES = "115200;ttyS0"

# Kernel console arguments for bootloader
KERNEL_CONSOLE = "ttyS0,115200"

# Image types — Rockchip boot chain requires custom wic layout
IMAGE_FSTYPES = "wic ext4"

# Boot partition contents (kernel + DTB for the bootimg-partition wic plugin).
# Deploy the (possibly variant, e.g. -dsi) KERNEL_DEVICETREE into the boot
# partition under the fixed name extlinux.conf references, so the static
# `fdt /rk3506b-luckfox-core3506.dtb` line matches regardless of the screen
# (DSI) MACHINE_FEATURE selecting a variant DTB. "src;dest" = wic rename.
# Without the rename a `screen` build deploys only `...-dsi.dtb` and U-Boot's
# extlinux boot fails ("Unable to read file /rk3506b-luckfox-core3506.dtb");
# harmless no-op for the non-screen build (KERNEL_DEVICETREE already == that name).
# Phase 3b-B: when the kernel FIT is signed, the boot partition is a RAW signed
# Rockchip boot.img FIT (kernel + DTB + resource) written by rawcopy from the
# fit .wks — NOT a bootimg-partition filesystem — so IMAGE_BOOT_FILES is unused
# in that path (boot_fit reads the raw FIT and verifies it). The default/unsigned
# path keeps the raw zImage + renamed DTB on a vfat boot partition for extlinux.
IMAGE_BOOT_FILES = "${@'' if d.getVar('RK3506_KERNEL_FIT_SIGNATURE') == '1' else 'zImage ${KERNEL_DEVICETREE};rk3506b-luckfox-core3506.dtb'}"

# Inherit the Rockchip boot image assembly class
IMAGE_CLASSES += "rockchip_rk3506_image"

# Base machine features common to all RK3506 boards
# Individual board configs extend this with board-specific features.
MACHINE_FEATURES = "usbhost"

# rkbin blob configuration — boards override RKBIN_SOC_VARIANT for sub-variants
# RK3506G2 boards: RKBIN_SOC_VARIANT = "rk3506" (default)
# RK3506B boards:  RKBIN_SOC_VARIANT = "rk3506b"
RKBIN_SOC_VARIANT ?= "rk3506"

# U-Boot config fragment for RK3506B variant boards
# RK3506B boards set this to "rk3506b.config"
RK3506_UBOOT_CONFIG_FRAGMENT ?= ""
