# Shared config for all LuckFox minimal images.
# Include this from media-specific image recipes (eMMC, SD card).

SUMMARY = "Minimal LuckFox Linux image"
DESCRIPTION = "A minimal bootable image for LuckFox boards providing \
console access over serial (ttyS0), basic networking, and SSH. \
Intended for initial board bringup and validation."
LICENSE = "MIT"

inherit core-image

IMAGE_INSTALL = " \
    packagegroup-core-boot \
    packagegroup-luckfox-base \
    kernel-modules \
    alsa-utils \
    foxbridge-status \
    foxbridge-wifi \
    picoclaw \
    foxbridge-mcp \
    avahi-daemon \
    wpa-supplicant \
    wireless-regdb-static \
    linux-firmware-rtl8188 \
    linux-firmware-rtl8192cu \
"

# packagegroup-luckfox-screen pulls modetest / kmscube / libdrm-tools for
# panel bringup. Only pulled in when MACHINE_FEATURES contains 'screen'.
IMAGE_INSTALL:append = " ${@bb.utils.contains('MACHINE_FEATURES', 'screen', 'packagegroup-luckfox-screen', '', d)}"

# Optional: bundle Doom (doomgeneric framebuffer port + freedoom IWAD).
# Simple toggle — set in conf/local.conf to include it:
#     LUCKFOX_DOOM = "1"
# Renders rotated/landscape to /dev/fb0; run on-target with the `doom` launcher.
# freedoom is pulled in automatically via doomgeneric's RDEPENDS.
LUCKFOX_DOOM ?= "0"
IMAGE_INSTALL:append = " ${@bb.utils.contains('LUCKFOX_DOOM', '1', 'doomgeneric', '', d)}"

# Optional: SWUpdate A/B OTA tooling (milestone M1 — see OTA_SWUPDATE_PLAN.md).
# Opt-in via conf/local.conf:
#     LUCKFOX_OTA = "1"
# libubootenv-bin provides fw_printenv / fw_setenv (+ our /etc/fw_env.config) so
# Linux can read/write the U-Boot env that holds the A/B slot state.
# u-boot-rockchip-rk3506-env ships /etc/u-boot-initial-env — the complete U-Boot
# default env that fw_setenv seeds from on a freshly-flashed (blank-env) device;
# without it the first slot-state write would wipe U-Boot's defaults and break
# autoboot (see OTA_SWUPDATE_PLAN.md).
# ota-bootmgr seeds the A/B selector (the ab_select env script + bootcmd=run
# ab_select) and slot counters on first boot (ota-env-init), and commits a
# healthy slot (ota-mark-good). The selector lives in the U-Boot env, not a
# boot.scr file — this vendor U-Boot's `source` mishandles the mkimage script
# container. The A/B partition layout is selected by WKS_FILE in the image .bb.
# swupdate is the OTA daemon (applies a .swu: writes the inactive slot via the
# raw/archive handlers + flips the U-Boot env via the bootloader handler);
# swupdate-www is the local web-upload UI (Mongoose, port 8080). M1 .swu is
# unsigned. See OTA_SWUPDATE_PLAN.md.
LUCKFOX_OTA ?= "0"
IMAGE_INSTALL:append = " ${@bb.utils.contains('LUCKFOX_OTA', '1', 'libubootenv-bin u-boot-rockchip-rk3506-env ota-bootmgr swupdate swupdate-www', '', d)}"

# Mask systemd-networkd-wait-online: with no DHCP/link it blocks
# network-online.target (and thus multi-user.target) for ~120s, making boot
# appear to hang. This board doesn't need to gate boot on the network.
mask_networkd_wait_online() {
    mkdir -p ${IMAGE_ROOTFS}${sysconfdir}/systemd/system
    ln -sf /dev/null ${IMAGE_ROOTFS}${sysconfdir}/systemd/system/systemd-networkd-wait-online.service
}
ROOTFS_POSTPROCESS_COMMAND += "mask_networkd_wait_online;"

# OP-TEE normal-world userspace — tee-supplicant (libteec). Pulled in for the
# from-source OP-TEE build (OPTEE_PROVIDER = "optee-os-rk3506", now the default);
# set OPTEE_PROVIDER = "rkbin" for the vendor-blob fallback. xtest/optee-test is
# not built here (the upstream rk3506 port is already xtest-validated — see
# re-add optee-test + optee-os-tadevkit recipes to run the OP-TEE tests on target.
IMAGE_INSTALL:append = " ${@'optee-client' if d.getVar('OPTEE_PROVIDER') == 'optee-os-rk3506' else ''}"

# Keep the image small — target boards may have limited storage
IMAGE_ROOTFS_SIZE ?= "196608"
IMAGE_OVERHEAD_FACTOR ?= "1.2"

# No root password for initial bringup — set one after first boot
# FIXME: For production, generate with a locked root and SSH key auth only
EXTRA_IMAGE_FEATURES += "debug-tweaks"
