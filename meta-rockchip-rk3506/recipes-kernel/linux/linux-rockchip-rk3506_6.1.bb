# RK3506 vendor BSP kernel recipe (6.1.x)
# No mainline kernel support for RK3506 — this uses Rockchip's vendor BSP fork.
# Validated on Core3506-0808 + foxbridge carrier (2026-04-10).

SUMMARY = "Rockchip BSP kernel for RK3506"
DESCRIPTION = "Vendor BSP Linux kernel 6.1.x for the RK3506 SoC family \
(Cortex-A7 triple-core + Cortex-M0 MCU). Includes SoC drivers, DTS files, \
and AMP support for heterogeneous multi-core operation."
HOMEPAGE = "https://github.com/rockchip-linux/kernel"

LICENSE = "GPL-2.0-only"
LIC_FILES_CHKSUM = "file://COPYING;md5=6bc538ed5bd9a7fc9398086aedcd7e46"

DEPENDS += "coreutils-native openssl-native"

PROVIDES += "virtual/kernel"

SRC_URI = " \
    git://github.com/rockchip-linux/kernel.git;protocol=https;branch=develop-6.1 \
    file://rk3506b-luckfox-core3506.dtsi \
    file://rk3506b-luckfox-core3506.dts \
    file://rk3506b-luckfox-core3506-dsi.dts \
    file://ext4-builtin.cfg \
    file://systemd.cfg \
    file://wifi-bt.cfg \
"

# display-dsi.cfg + waveshare-backlight backport pulled in only when
# MACHINE_FEATURES includes 'screen'. Without screen, the kernel builds
# without DRM/MIPI-DSI/Goodix touch to keep flash footprint small on
# display-less deployments.
SRC_URI:append = " ${@bb.utils.contains('MACHINE_FEATURES', 'screen', \
    'file://display-dsi.cfg \
     file://0001-add-waveshare-dsi-backlight-driver.patch \
     file://0002-goodix-touch-poll-mode.patch', \
    '', d)}"

# OP-TEE memory-map / driver-binding overlay — pulled in only for the
# from-source OP-TEE build (OPTEE_PROVIDER = "optee-os-rk3506"). The default
# vendor-blob build omits it (its trust@0 -> 0x18000000 reservation matches
# only the source TEE's TZDRAM, not the vendor blob at 0x1000). It is included
# into the base DTSI by do_configure:prepend below so every core3506 DTB
# (base + DSI) inherits it.
SRC_URI:append = " ${@'file://rk3506b-luckfox-core3506-optee.dtsi file://optee.cfg' if d.getVar('OPTEE_PROVIDER') == 'optee-os-rk3506' else ''}"

# Phase 4: dm-verity + overlayfs kernel config — pulled in only for the verified
# rootfs build (RK3506_ROOTFS_VERITY = "1"). Built-in (=y): the root device is
# created from dm-mod.create= in the signed bootargs before rootfs mount.
SRC_URI:append = " ${@'file://dm-verity.cfg' if d.getVar('RK3506_ROOTFS_VERITY') == '1' else ''}"

SRCREV = "d2b4477a1df699e6639e83837c7dc45ea1d1d73f"

# In-tree config fragments to merge (from arch/arm/configs/)
KERNEL_CONFIG_FRAGMENTS = " \
    rk3506-ethernet.config \
    rk3506-usb-host.config \
"

COMPATIBLE_MACHINE = "luckfox-core3506"

LINUX_VERSION = "6.1"
LINUX_VERSION_EXTENSION = "-rockchip-rk3506"

inherit kernel

# --- Signed kernel FIT (opt-in via RK3506_KERNEL_FIT_SIGNATURE) ----
# The kernel ALWAYS builds a plain zImage + DTB — including for the signed build.
# Phase 3b-B authenticates the kernel via the Rockchip-native boot.img FIT
# (kernel + DTB + resource, RSA-2048 config-signed), which is assembled and
# signed in the U-BOOT recipe with the in-tree mkimage/make_fit_boot.sh and
# verified natively by U-Boot's boot_fit (it reads the DTB from the FIT
# /images/resource node and hang()s on a bad signature). See the plan for why
# the earlier OE `kernel-fitimage` path was abandoned (its FIT format is not
# verifiable by this 2017.09 U-Boot fork). So nothing kernel-side changes under
# the toggle: RK3506_KERNEL_FIT_SIGNATURE is consumed by the u-boot recipe and
# the wic .wks selection, not here.
KERNEL_IMAGETYPE = "zImage"

# S must be set AFTER inherit kernel, since kernel.bbclass also sets S.
# Our assignment overrides the class default so do_symlink_kernsrc can
# move the git checkout to STAGING_KERNEL_DIR.
S = "${WORKDIR}/git"

# The in-tree rk3506_defconfig provides a working base configuration.
KBUILD_DEFCONFIG = "rk3506_defconfig"

# Install out-of-tree DTS / DTSI files into the kernel source and register the
# top-level .dts files in the DTS Makefile so they get compiled alongside the
# in-tree ones. .dtsi files are only copied in (the C preprocessor finds them
# during .dts compilation; they have no direct Makefile entry).
do_configure:prepend() {
    # Copy DTSI fragments first so the .dts files can #include them.
    for dtsi in ${WORKDIR}/*.dtsi; do
        [ -f "$dtsi" ] || continue
        cp "$dtsi" ${S}/arch/arm/boot/dts/
    done

    # Copy custom DTS files from WORKDIR into kernel DTS directory.
    for dts in ${WORKDIR}/*.dts; do
        [ -f "$dts" ] || continue
        cp "$dts" ${S}/arch/arm/boot/dts/
        dtb=$(basename "$dts" .dts).dtb
        # Add to Makefile if not already present
        if ! grep -q "$dtb" ${S}/arch/arm/boot/dts/Makefile; then
            sed -i "/rk3506b-evb1-v10.dtb/a\\\\t${dtb} \\\\" \
                ${S}/arch/arm/boot/dts/Makefile
        fi
    done

    # From-source OP-TEE only: append the OP-TEE overlay #include to the base
    # DTSI so every core3506 DTB carries the optee node + secure-RAM
    # reservations. Gated on OPTEE_PROVIDER so the vendor-blob build is
    # unaffected (the overlay is not even fetched in that case).
    if [ "${OPTEE_PROVIDER}" = "optee-os-rk3506" ] && \
       [ -f "${S}/arch/arm/boot/dts/rk3506b-luckfox-core3506-optee.dtsi" ]; then
        if ! grep -q "rk3506b-luckfox-core3506-optee.dtsi" \
             ${S}/arch/arm/boot/dts/rk3506b-luckfox-core3506.dtsi; then
            echo '#include "rk3506b-luckfox-core3506-optee.dtsi"' \
                >> ${S}/arch/arm/boot/dts/rk3506b-luckfox-core3506.dtsi
        fi
    fi
}

# The kernel class does NOT apply KBUILD_DEFCONFIG automatically (that's
# kernel-yocto's job). We write a custom do_configure to apply the
# defconfig, then run olddefconfig to resolve any dependencies.
do_configure() {
    # Apply the in-tree defconfig to generate .config
    oe_runmake -C ${S} O=${B} ${KBUILD_DEFCONFIG}

    # Merge any config fragments from SRC_URI (.cfg files)
    for cfg in ${WORKDIR}/*.cfg; do
        [ -f "$cfg" ] || continue
        ${S}/scripts/kconfig/merge_config.sh -m -O ${B} ${B}/.config "$cfg"
    done

    # Merge in-tree config fragments (from arch/arm/configs/)
    for frag in ${KERNEL_CONFIG_FRAGMENTS}; do
        if [ -f "${S}/arch/arm/configs/$frag" ]; then
            ${S}/scripts/kconfig/merge_config.sh -m -O ${B} ${B}/.config \
                ${S}/arch/arm/configs/$frag
        fi
    done

    # Resolve any unset options with defaults
    oe_runmake -C ${S} O=${B} olddefconfig
}

# Machine config sets KERNEL_DEVICETREE to the correct DTB path(s)
# Example: KERNEL_DEVICETREE = "rk3506b-evb1-v10.dtb"

# Kernel config fragments available in the source tree:
#   arch/arm/configs/rk3506-display.config    — MIPI DSI, LVGL display
#   arch/arm/configs/rk3506-ethernet.config   — RMII Ethernet (gmac0/gmac1)
#   arch/arm/configs/rk3506-usb-host.config   — USB host controller
#   arch/arm/configs/rk3506-usb-otg.config    — USB OTG support
#   arch/arm/configs/rk3506-usb-peripheral.config — USB gadget/peripheral
#   arch/arm/configs/rk3506-wifibt.config     — cfg80211/mac80211 + WL_ROCKCHIP
