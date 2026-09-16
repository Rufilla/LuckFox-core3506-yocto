# LuckFox A/B OTA SWUpdate config (milestone M1). See OTA_SWUPDATE_PLAN.md.
# Adds a Kconfig fragment on top of meta-swupdate's default defconfig; the
# recipe's do_configure merges any *.cfg found in SRC_URI (find_cfgs).
FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

SRC_URI += "file://swupdate-ota.cfg"

# swupdate's worker threads call pthread_exit/cancel; glibc lazily dlopens
# libgcc_s.so.1 for stack unwinding. Without it in the rootfs the image-write
# thread aborts ("libgcc_s.so.1 must be installed for pthread_exit to work" ->
# "swupdate_image_write failed: Broken pipe"). Pull libgcc into the image.
RDEPENDS:${PN} += "libgcc"
